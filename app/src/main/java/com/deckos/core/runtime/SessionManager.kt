package com.deckos.core.runtime

import android.content.Context
import android.util.Log
import com.deckos.core.X11Server
import java.io.File

/**
 * L-65/L-66 — DeckOS Session Manager
 *
 * Unified session lifecycle controller that replaces the scattered calls
 * across LinuxRuntime + X11Server.
 *
 * Architecture:
 * - One public API surface for start/stop/restart/status.
 * - A/B path selection: Core path (default) vs. optional enhanced path.
 * - Regression guard: tracks session open/close cycles for L-67 20/20 test.
 *
 * DroidDesk lessons applied:
 * ✅  Auto-repair broken packages before session start.
 * ✅  dbus session bus started before window manager.
 * ✅  waitForDesktopReady() reads /proc directly (no pgrep dependency).
 * ✅  GPU mode logged explicitly — no silent llvmpipe fallback.
 * ❌  No PRoot/chroot/root — Core stays native only.
 * ❌  No Turnip by default — must opt-in via DECKOS_GPU=turnip.
 */
class SessionManager(private val context: Context) {

    companion object {
        private const val TAG = "DeckOSSession"

        /** L-66: Default path is always CORE (fastest, most stable on S24U). */
        const val PATH_CORE = "core"

        /** L-66: Enhanced path adds optional DroidDesk-ported improvements.
         *  Activate by setting DECKOS_SESSION_PATH=enhanced in the environment. */
        const val PATH_ENHANCED = "enhanced"

        /** Maximum time to wait for Openbox to appear in /proc, ms. */
        private const val DESKTOP_READY_TIMEOUT_MS = 10_000L

        /** Cycle counter file for L-67 regression tracking. */
        private const val CYCLE_LOG = "session_cycles.log"
    }

    private val runtime = LinuxRuntime(context)

    init {
        if (runtime.isInstalled()) {
            runCatching { runtime.prepare() }
        }
    }

    // ── Session state ──────────────────────────────────────────────────────

    enum class State {
        IDLE, STARTING, RUNNING, STOPPING, ERROR
    }

    data class SessionStatus(
        val state: State,
        val path: String,
        val x11Pid: Int?,
        val desktopPid: Int?,
        val gpuMode: DeckOSGpuDetector.GpuMode,
        val cycleCount: Int,
        val lastError: String?,
    )

    @Volatile private var state = State.IDLE
    @Volatile private var lastError: String? = null
    private var cycleCount: Int = 0  // loaded lazily on first use
    private var cycleCountLoaded = false

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Start a full DeckOS session:
     * 1. Ensure bootstrap + desktop are installed.
     * 2. Auto-repair broken packages.
     * 3. Start X11 server.
     * 4. Launch desktop session.
     * 5. Wait for Openbox to appear in /proc.
     */
    fun start(onProgress: ((Float, String) -> Unit)? = null): Boolean {
        if (state == State.RUNNING) {
            Log.i(TAG, "Session already running")
            return true
        }
        state = State.STARTING
        lastError = null

        return try {
            val path = activePath()
            Log.i(TAG, "Starting session: path=$path, gpu=${detectGpuMode()}")

            // Step 1: Ensure Linux is installed
            if (!runtime.isInstalled()) {
                onProgress?.invoke(0.05f, "Installing Linux...")
                runtime.ensureInstalled { p, msg -> onProgress?.invoke(p * 0.5f, msg) }
            } else {
                runtime.prepare()
            }

            // Step 2: Ensure desktop packages
            if (!runtime.isDesktopInstalled()) {
                onProgress?.invoke(0.5f, "Installing desktop...")
                runtime.installDesktopPackages { p, msg ->
                    onProgress?.invoke(0.5f + p * 0.3f, msg)
                }
            }

            // Step 3: L-61 Auto-repair (idempotent, fast on healthy systems)
            onProgress?.invoke(0.82f, "Verifying packages...")
            runtime.repairBrokenPackages()

            // Step 4: Start X11 server
            onProgress?.invoke(0.87f, "Starting X11 server...")
            val x11Pid = X11Server.ensureRunning(context)
            if (x11Pid == null) {
                throw IllegalStateException("X11 server failed to start")
            }
            Log.i(TAG, "X11 server running: pid=$x11Pid")

            // Step 5: Launch desktop session
            onProgress?.invoke(0.92f, "Launching desktop...")
            val desktopPid = runtime.startDesktop()
            if (desktopPid == null) {
                throw IllegalStateException("Desktop session failed to start")
            }
            Log.i(TAG, "Desktop session launched: pid=$desktopPid")

            // Step 6: L-65 waitForDesktopReady via /proc (no pgrep)
            if (path == PATH_ENHANCED) {
                onProgress?.invoke(0.96f, "Waiting for desktop...")
                val ready = waitForDesktopReady()
                if (!ready) {
                    Log.w(TAG, "Desktop ready timeout — continuing anyway")
                }
            }

            state = State.RUNNING
            recordCycle()
            onProgress?.invoke(1f, "Desktop ready")
            Log.i(TAG, "Session started successfully (cycle #$cycleCount)")
            true

        } catch (e: Exception) {
            lastError = e.message
            state = State.ERROR
            Log.e(TAG, "Session start failed", e)
            false
        }
    }

    /** Stop the session gracefully. */
    fun stop() {
        state = State.STOPPING
        runCatching { X11Server.stop(context) }
        state = State.IDLE
        Log.i(TAG, "Session stopped")
    }

    /** Restart the session. */
    fun restart(onProgress: ((Float, String) -> Unit)? = null): Boolean {
        stop()
        Thread.sleep(500)
        return start(onProgress)
    }

    /** Current session status for UI display. */
    fun status(): SessionStatus {
        val x11Status = X11Server.status(context)
        return SessionStatus(
            state = state,
            path = activePath(),
            x11Pid = x11Status.pid,
            desktopPid = readDesktopPid(),
            gpuMode = detectGpuMode(),
            cycleCount = cycleCount,
            lastError = lastError,
        )
    }

    // ── L-66: Path selection ───────────────────────────────────────────────

    /**
     * L-66: Active path decision.
     * CORE is always the default — it's faster and more stable on S24 Ultra.
     * ENHANCED adds: waitForDesktopReady(), extended GPU detection logging.
     * Switch via: DECKOS_SESSION_PATH=enhanced (env or settings).
     */
    private fun activePath(): String {
        val requested = System.getenv("DECKOS_SESSION_PATH") ?: ""
        return if (requested.equals(PATH_ENHANCED, ignoreCase = true)) PATH_ENHANCED else PATH_CORE
    }

    private fun detectGpuMode(): DeckOSGpuDetector.GpuMode {
        val prefix = File(context.filesDir, "usr").absolutePath
        return DeckOSGpuDetector.detect(prefix).mode
    }

    // ── L-65: waitForDesktopReady via /proc ───────────────────────────────

    /**
     * L-65: Wait until "openbox" appears in /proc/[pid]/cmdline.
     * Reads /proc directly — no dependency on `pgrep` (DroidDesk bug avoided).
     * Returns true if found within [DESKTOP_READY_TIMEOUT_MS], false on timeout.
     */
    private fun waitForDesktopReady(): Boolean {
        val deadline = System.currentTimeMillis() + DESKTOP_READY_TIMEOUT_MS
        val procDir = File("/proc")

        while (System.currentTimeMillis() < deadline) {
            val found = procDir.listFiles()?.any { pidDir ->
                if (!pidDir.isDirectory) return@any false
                val cmdline = File(pidDir, "cmdline")
                if (!cmdline.isFile) return@any false
                runCatching {
                    val bytes = cmdline.readBytes()
                    val cmd = String(bytes, Charsets.ISO_8859_1)
                        .split('\u0000')
                        .firstOrNull()
                        ?.substringAfterLast('/') ?: ""
                    cmd == "openbox" || cmd.startsWith("openbox-session")
                }.getOrDefault(false)
            } ?: false

            if (found) {
                Log.i(TAG, "Openbox found in /proc — desktop ready")
                return true
            }
            Thread.sleep(100)
        }
        Log.w(TAG, "waitForDesktopReady: timeout after ${DESKTOP_READY_TIMEOUT_MS}ms")
        return false
    }

    // ── L-67: Cycle tracking for 20/20 regression test ────────────────────

    private fun cycleFile(): File = File(context.filesDir, CYCLE_LOG)

    private fun loadCycleCount(): Int =
        runCatching { cycleFile().readText().trim().toInt() }.getOrDefault(0)

    private fun ensureCycleCountLoaded() {
        if (!cycleCountLoaded) {
            cycleCount = loadCycleCount()
            cycleCountLoaded = true
        }
    }

    private fun recordCycle() {
        ensureCycleCountLoaded()
        cycleCount++
        runCatching { cycleFile().writeText(cycleCount.toString()) }
    }

    private fun readDesktopPid(): Int? {
        val pidFile = File(context.filesDir, "openbox.pid")
        val pid = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull() ?: return null
        // Validate PID is actually alive via /proc — stale PID files are common
        val alive = File("/proc/$pid").exists()
        if (!alive) {
            Log.d(TAG, "openbox.pid=$pid is stale (not in /proc), clearing")
            runCatching { pidFile.delete() }
            return null
        }
        return pid
    }


    /**
     * L-67: Run N open/close cycles and report pass/fail.
     * Acceptance: 20/20 without crash or visible memory leak.
     */
    fun runRegressionCycles(
        n: Int = 20,
        onCycle: (Int, Boolean) -> Unit
    ): Boolean {
        var passed = 0
        repeat(n) { i ->
            val ok = runCatching {
                val started = start()
                if (!started) return@runCatching false
                Thread.sleep(2000) // hold session briefly
                stop()
                Thread.sleep(1000) // cooldown
                true
            }.getOrDefault(false)

            onCycle(i + 1, ok)
            if (ok) passed++
            Log.i(TAG, "Regression cycle ${i+1}/$n: ${if (ok) "PASS" else "FAIL"}")
        }

        val result = passed == n
        Log.i(TAG, "Regression test: $passed/$n passed — ${if (result) "✅ PASS" else "❌ FAIL"}")
        return result
    }
}
