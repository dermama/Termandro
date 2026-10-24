package com.deckos.core.game

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.deckos.core.X11Server
import com.deckos.core.runtime.LinuxRuntime
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ─────────────────────────────────────────────────────────────────────────────
// L-71 — DeckOS Game Service
//
// A long-running Foreground Service that manages the game session lifecycle
// independently from the core desktop. Key design decisions:
//
//  • Foreground service with a sticky notification prevents Android from
//    killing the game mid-session (same pattern as DroidDeck's SessionService).
//  • Game session runs on a dedicated thread (gameThread), never on main.
//  • Desktop suspension (L-74): sends SIGTERM to openbox/pcmanfm/tint2 before
//    starting the game, then restores them on exit/crash (L-78).
//  • WakeLock held during the game to prevent CPU throttle or screen sleep.
//  • No root, no PRoot — stays native (same constraint as Core).
//
// Integration with MainActivity:
//   startForegroundService(Intent(this, GameService::class.java).apply {
//       putExtra(GameService.EXTRA_GAME_NAME, "My Game")
//       putExtra(GameService.EXTRA_EXEC_PATH, "/path/to/game")
//   })
//   sendBroadcast(Intent(GameService.ACTION_STOP)) to stop.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "DeckOSGameSvc"

class GameService : Service(), IGameSession {

    // ── Companion / intent constants ──────────────────────────────────────

    companion object {
        const val ACTION_STOP       = "com.deckos.game.STOP"
        const val EXTRA_GAME_NAME   = "game_name"
        const val EXTRA_EXEC_PATH   = "exec_path"
        const val EXTRA_EXEC_ARGS   = "exec_args"
        const val EXTRA_RUNTIME     = "runtime"
        const val EXTRA_RESOLUTION  = "resolution"
        const val EXTRA_FPS_CAP     = "fps_cap"

        private const val NOTIF_CHANNEL = "deckos_game"
        private const val NOTIF_ID      = 0x4A00

        private val _currentPhase = MutableStateFlow(GamePhase.IDLE)
        val currentPhase: StateFlow<GamePhase> = _currentPhase.asStateFlow()

        @Volatile
        var activeInstance: GameService? = null
            private set

        /** Start GameService for the given config. Returns false if service was already running. */
        fun start(context: Context, config: GameConfig): Boolean {
            val intent = Intent(context, GameService::class.java).apply {
                putExtra(EXTRA_GAME_NAME, config.gameName)
                putExtra(EXTRA_EXEC_PATH, config.execPath)
                putExtra(EXTRA_EXEC_ARGS, config.execArgs.toTypedArray())
                putExtra(EXTRA_RUNTIME,   config.runtimeHint)
                putExtra(EXTRA_RESOLUTION, config.resolutionProfile.name)
                putExtra(EXTRA_FPS_CAP,   config.fpsCap)
            }
            context.startForegroundService(intent)
            return true
        }

        /** Request a graceful stop from outside the service. */
        fun stop(context: Context) {
            activeInstance?.requestStop()
            val intent = Intent(context, GameService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Failed to startService with ACTION_STOP: ${it.message}") }
        }
    }

    // ── IGameSession state ────────────────────────────────────────────────

    @Volatile override var phase: GamePhase = GamePhase.IDLE
        private set(value) {
            field = value
            _currentPhase.value = value
            phaseListener?.invoke(value)
            Log.i(TAG, "Phase → $value")
        }

    override var config: GameConfig? = null
        private set

    override var preflightResult: PreflightResult? = null
        private set

    override var errorCode: String? = null
        private set

    override var errorMessage: String? = null
        private set

    private var phaseListener: ((GamePhase) -> Unit)? = null
    private val idleLatch = CountDownLatch(1)

    // ── Runtime state ─────────────────────────────────────────────────────

    private var gameThread: Thread? = null
    private var gamePid: Int = -1
    @Volatile private var activeProcess: Process? = null
    @Volatile private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val runtime by lazy { LinuxRuntime(applicationContext) }
    private val packManager by lazy { GamePackManager(applicationContext) }

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
        }

        if (phase != GamePhase.IDLE && phase != GamePhase.FAILED) {
            Log.w(TAG, "GameService already running (phase=$phase) — ignoring start")
            return START_NOT_STICKY
        }

        // Build config from intent extras
        val cfg = GameConfig(
            gameName        = intent?.getStringExtra(EXTRA_GAME_NAME) ?: "Game",
            execPath        = intent?.getStringExtra(EXTRA_EXEC_PATH) ?: "",
            execArgs        = intent?.getStringArrayExtra(EXTRA_EXEC_ARGS)?.toList() ?: emptyList(),
            runtimeHint     = intent?.getStringExtra(EXTRA_RUNTIME) ?: "native",
            resolutionProfile = runCatching {
                GameConfig.ResolutionProfile.valueOf(
                    intent?.getStringExtra(EXTRA_RESOLUTION) ?: "HD"
                )
            }.getOrDefault(GameConfig.ResolutionProfile.HD),
            fpsCap          = intent?.getIntExtra(EXTRA_FPS_CAP, 0) ?: 0,
        )

        startForeground(NOTIF_ID, buildNotification(cfg.gameName, "Starting…"))
        acquireWakeLock()

        config = cfg
        stopRequested = false

        // Run session on a dedicated background thread
        gameThread = Thread({ runSession(cfg) }, "deckos-game-session").apply { start() }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        requestStop()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── IGameSession implementation ───────────────────────────────────────

    override fun start(
        config: GameConfig,
        onProgress: ((GamePhase, Float, String) -> Unit)?,
    ): Boolean {
        // When called in-process (tests), run directly
        return runCatching { runSession(config, onProgress); phase == GamePhase.IDLE }.getOrDefault(false)
    }

    override fun requestStop() {
        Log.i(TAG, "requestStop requested (gamePid=$gamePid, hasActiveProc=${activeProcess != null})")
        stopRequested = true
        if (gamePid > 0) {
            runCatching { android.os.Process.sendSignal(gamePid, 15) } // SIGTERM
        }
        runCatching { activeProcess?.destroy() }
    }

    override fun awaitIdle(timeoutMs: Long): Boolean =
        idleLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun reset() {
        if (phase == GamePhase.FAILED) {
            phase = GamePhase.IDLE
            errorCode = null
            errorMessage = null
        }
    }

    override fun setPhaseListener(listener: ((GamePhase) -> Unit)?) {
        phaseListener = listener
    }

    // ── Session execution (runs on gameThread) ────────────────────────────

    private fun runSession(
        cfg: GameConfig,
        onProgress: ((GamePhase, Float, String) -> Unit)? = null,
    ) {
        try {
            // ── 1. Preflight (L-73) ────────────────────────────────────────
            phase = GamePhase.PREFLIGHT
            updateNotification(cfg.gameName, "Checking system…")
            onProgress?.invoke(GamePhase.PREFLIGHT, 0.05f, "Running preflight checks…")

            // Use a dummy pack for preflight if execPath is empty (test mode)
            val testPack = GamePack(
                id          = "preflight_check",
                displayName = cfg.gameName,
                url         = "",
                sha256      = "",
                sizeBytes   = 0,
            )
            val preflight = GamePreflight.run(this, testPack, packManager)
            preflightResult = preflight

            if (!preflight.ok) {
                fail(
                    code = "PREFLIGHT_FAILED",
                    message = preflight.issues.joinToString("; "),
                )
                return
            }
            onProgress?.invoke(GamePhase.PREFLIGHT, 0.15f, "Preflight passed")

            if (stopRequested) { cleanStop(); return }

            // ── 2. Acquire pack if needed (L-72) ──────────────────────────
            val requiredPack = GamePackCatalog.resolvePackFor(cfg.runtimeHint, cfg.execPath)
            if (requiredPack != null && !packManager.isInstalled(requiredPack)) {
                phase = GamePhase.ACQUIRING_PACK
                updateNotification(cfg.gameName, "Downloading ${requiredPack.displayName}…")
                onProgress?.invoke(GamePhase.ACQUIRING_PACK, 0.20f, "Downloading ${requiredPack.displayName}…")
                val packResult = packManager.ensureInstalled(requiredPack) { p, msg ->
                    onProgress?.invoke(GamePhase.ACQUIRING_PACK, 0.20f + p * 0.15f, msg)
                }
                if (packResult is PackResult.Failed) {
                    fail("PACK_DOWNLOAD_FAILED", "Failed to acquire ${requiredPack.displayName}: ${packResult.reason}")
                    return
                }
            } else if (cfg.execPath.isNotEmpty() && !File(cfg.execPath).exists() && requiredPack == null) {
                phase = GamePhase.ACQUIRING_PACK
                onProgress?.invoke(GamePhase.ACQUIRING_PACK, 0.20f, "Executable not found locally")
                fail("PACK_NOT_FOUND", "Game executable not found: ${cfg.execPath}")
                return
            }
            onProgress?.invoke(GamePhase.ACQUIRING_PACK, 0.35f, "Pack ready")

            if (stopRequested) { cleanStop(); return }

            // ── 3. Suspend desktop to free memory (L-74) ──────────────────
            phase = GamePhase.SUSPENDING_DESKTOP
            updateNotification(cfg.gameName, "Suspending desktop…")
            onProgress?.invoke(GamePhase.SUSPENDING_DESKTOP, 0.35f, "Suspending desktop…")
            suspendDesktop()
            onProgress?.invoke(GamePhase.SUSPENDING_DESKTOP, 0.50f, "Desktop suspended")

            if (stopRequested) { restoreDesktop(); cleanStop(); return }

            // ── 4. Launch game (L-75) ──────────────────────────────────────
            phase = GamePhase.STARTING_GAME
            updateNotification(cfg.gameName, "Launching…")
            onProgress?.invoke(GamePhase.STARTING_GAME, 0.55f, "Launching game…")

            val prefix = File(filesDir, "usr")
            val activePack = requiredPack ?: testPack
            val env = packManager.buildGameEnv(activePack, cfg, prefix)

            val process = launchGame(cfg, env)
            if (process == null) {
                restoreDesktop()
                fail("LAUNCH_FAILED", "Failed to start game process: ${cfg.execPath}")
                return
            }

            // ── 5. Running (L-75/L-76/L-77) ───────────────────────────────
            phase = GamePhase.RUNNING
            updateNotification(cfg.gameName, "Running")
            onProgress?.invoke(GamePhase.RUNNING, 1f, "Game running")

            Log.i(TAG, "Game process launched, waiting for exit…")
            val exitCode = process.waitFor()
            Log.i(TAG, "Game exited with code $exitCode")

        } catch (e: InterruptedException) {
            Log.i(TAG, "Session thread interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected session error", e)
            if (phase != GamePhase.FAILED) {
                fail("UNEXPECTED", e.message ?: "Unknown error")
            }
        } finally {
            // ── 6. Cleanup and restore desktop (L-78) ─────────────────────
            phase = GamePhase.STOPPING
            updateNotification(cfg.gameName, "Stopping…")
            cleanupGameProcesses()
            restoreDesktop()
            phase = GamePhase.RESTORING_DESKTOP
            Thread.sleep(1_500)  // wait for desktop to stabilize
            cleanStop()
        }
    }

    // ── Desktop suspend/restore (L-74, L-78) ─────────────────────────────

    /**
     * Gracefully stop Openbox, Tint2, and PCManFM to free RAM for the game.
     * Uses SIGTERM (not SIGKILL) to allow clean state saves.
     * X11 server remains running — the game will use DISPLAY :1 directly.
     */
    private fun suspendDesktop() {
        Log.i(TAG, "Suspending desktop processes")
        val processNames = listOf("openbox", "tint2", "pcmanfm", "st", "featherpad")
        processNames.forEach { name ->
            runCatching {
                val pidFile = File(filesDir, "${name}.pid")
                if (pidFile.exists()) {
                    val pid = pidFile.readText().trim().toIntOrNull()
                    if (pid != null && pid > 0) {
                        android.os.Process.sendSignal(pid, 15) // SIGTERM
                        Log.i(TAG, "Sent SIGTERM to $name (pid=$pid)")
                    }
                    pidFile.delete()
                } else {
                    // Fallback: scan /proc for the process
                    killByName(name)
                }
            }.onFailure { Log.w(TAG, "Error suspending $name: ${it.message}") }
        }
        Thread.sleep(800)  // allow processes to exit cleanly
    }

    /**
     * Restart the desktop after the game exits.
     * Delegates to LinuxRuntime.startDesktop() which handles the full
     * Openbox + Tint2 + PCManFM autostart sequence.
     */
    private fun restoreDesktop() {
        Log.i(TAG, "Restoring desktop session")
        phase = GamePhase.RESTORING_DESKTOP
        runCatching {
            val x11Pid = X11Server.ensureRunning(applicationContext)
            if (x11Pid != null) {
                runtime.startDesktop()
                Log.i(TAG, "Desktop restored")
            } else {
                Log.w(TAG, "X11 server not running — skipping desktop restore")
            }
        }.onFailure { Log.e(TAG, "Desktop restore failed", it) }
    }

    // ── Game launch (L-75) ───────────────────────────────────────────────

    /**
     * Launch the game executable as a child process.
     *
     * The command is assembled based on config.runtimeHint:
     *  "native"  → exec directly
     *  "fex"     → exec via FEX-Emu if installed in the pack
     *  "proton"  → exec via Proton ARM64 if installed in the pack
     *
     * For Phase 5A, only "native" is fully wired. FEX/Proton hooks are
     * present but require the game pack to provide the binaries.
     */
    private fun launchGame(cfg: GameConfig, env: Map<String, String>): Process? {
        if (cfg.execPath.isEmpty()) {
            Log.i(TAG, "No exec path — game launch skipped (test mode)")
            return null
        }

        val cmd = buildLaunchCommand(cfg)
        Log.i(TAG, "Launching: ${cmd.joinToString(" ")}")

        return try {
            val logFile = File(filesDir, "game.log")
            logFile.appendText("\n[DeckOSGame] Launching ${cfg.gameName} at ${System.currentTimeMillis()}\n")
            val pb = ProcessBuilder(cmd).apply {
                environment().putAll(env)
                environment()["DISPLAY"] = ":1"
                redirectErrorStream(true)
                redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                directory(File(cfg.execPath).parentFile ?: filesDir)
            }
            val process = pb.start()
            activeProcess = process
            gamePid = extractPid(process)
            Log.i(TAG, "Game process started: pid=$gamePid")
            process
        } catch (e: Exception) {
            Log.e(TAG, "ProcessBuilder failed: ${e.message}")
            null
        }
    }

    /**
     * Extract PID from a java.lang.Process using reflection.
     * On Android, Process is implemented as java.lang.UNIXProcess (API <26)
     * or ProcessImpl (API 26+). Both expose a "pid" field.
     * Note: Process.pid() on Android API 26+ returns a Long (J in JNI).
     * Returns -1 if extraction fails.
     */
    private fun extractPid(process: Process): Int {
        return try {
            val method = process.javaClass.getDeclaredMethod("pid").apply { isAccessible = true }
            val res = method.invoke(process)
            when (res) {
                is Number -> res.toInt()
                else -> -1
            }
        } catch (_: NoSuchMethodException) {
            try {
                val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
                val res = field.get(process)
                when (res) {
                    is Number -> res.toInt()
                    else -> -1
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract game PID: ${e.message}")
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractPid failed: ${e.message}")
            -1
        }
    }

    private fun buildLaunchCommand(cfg: GameConfig): List<String> {
        val prefix = File(filesDir, "usr").absolutePath
        val execFile = File(cfg.execPath)

        val packProton = File(filesDir, "game_packs/steam_proton/usr/local/bin/droiddeck-proton-run")
        val packSteam = File(filesDir, "game_packs/steam_proton/usr/bin/steam")
        val packFex = File(filesDir, "game_packs/steam_proton/usr/bin/FEXInterpreter")
        val wineBin = File(filesDir, "game_packs/proton/bin/wine")

        return when {
            cfg.runtimeHint.equals("fex", ignoreCase = true) -> {
                when {
                    packFex.exists() -> listOf(packFex.absolutePath, cfg.execPath) + cfg.execArgs
                    File("$prefix/bin/fex-emu").exists() -> listOf("$prefix/bin/fex-emu", cfg.execPath) + cfg.execArgs
                    else -> {
                        Log.w(TAG, "FEX not found — falling back to native exec")
                        listOf(cfg.execPath) + cfg.execArgs
                    }
                }
            }
            cfg.runtimeHint.equals("proton", ignoreCase = true)
                || cfg.runtimeHint.equals("steam", ignoreCase = true)
                || cfg.execPath.endsWith(".exe", ignoreCase = true)
                || cfg.execPath.equals("steam", ignoreCase = true) -> {
                when {
                    cfg.execPath.equals("steam", ignoreCase = true) && packSteam.exists() -> {
                        listOf(packSteam.absolutePath) + cfg.execArgs
                    }
                    cfg.execPath.endsWith(".exe", ignoreCase = true) && packProton.exists() -> {
                        listOf(packProton.absolutePath, cfg.execPath) + cfg.execArgs
                    }
                    packProton.exists() -> {
                        listOf(packProton.absolutePath, cfg.execPath) + cfg.execArgs
                    }
                    wineBin.exists() -> {
                        listOf(wineBin.absolutePath, cfg.execPath) + cfg.execArgs
                    }
                    else -> {
                        Log.w(TAG, "Proton/Wine not found — falling back to native exec")
                        listOf(cfg.execPath) + cfg.execArgs
                    }
                }
            }
            else -> listOf(cfg.execPath) + cfg.execArgs   // "native"
        }
    }

    // ── Cleanup (L-78) ───────────────────────────────────────────────────

    /**
     * Kill any leftover game processes.
     * Reads gamePid and sends SIGKILL after a short SIGTERM grace period.
     */
    private fun cleanupGameProcesses() {
        val pid = gamePid
        if (pid > 0) {
            runCatching {
                android.os.Process.sendSignal(pid, 15)
                Thread.sleep(300)
                android.os.Process.sendSignal(pid, 9)
            }
            gamePid = -1
        }
        runCatching { activeProcess?.destroyForcibly() }
        activeProcess = null
        killByName("featherpad")
        Log.i(TAG, "Game processes cleaned up")
    }

    /**
     * Kill a process group by name, scanning /proc/[pid]/cmdline.
     * Used as fallback when a PID file is absent.
     */
    private fun killByName(name: String) {
        File("/proc").listFiles()?.forEach { pidDir ->
            if (!pidDir.isDirectory) return@forEach
            val cmdline = File(pidDir, "cmdline")
            if (!cmdline.isFile) return@forEach
            val cmd = runCatching {
                cmdline.readBytes().toString(Charsets.ISO_8859_1)
                    .split('\u0000').firstOrNull()?.substringAfterLast('/') ?: ""
            }.getOrDefault("")
            if (cmd == name) {
                val pid = pidDir.name.toIntOrNull() ?: return@forEach
                android.os.Process.sendSignal(pid, 15)
                Log.i(TAG, "Sent SIGTERM to $name (pid=$pid) via /proc scan")
            }
        }
    }

    // ── Error handling ────────────────────────────────────────────────────

    private fun fail(code: String, message: String) {
        errorCode = code
        errorMessage = message
        phase = GamePhase.FAILED
        Log.e(TAG, "Session failed [$code]: $message")
        updateNotification(config?.gameName ?: "Game", "Error: $code")
    }

    private fun cleanStop() {
        if (phase != GamePhase.FAILED) {
            phase = GamePhase.IDLE
        }
        idleLatch.countDown()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── WakeLock ──────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "deckos::game_session"
        ).apply { acquire(4 * 60 * 60 * 1_000L) }  // 4h max
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
        val ch = NotificationChannel(
            NOTIF_CHANNEL,
            "DeckOS Game",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Game session status" }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(gameName: String, status: String): Notification {
        val stopIntent = Intent(ACTION_STOP).let {
            PendingIntent.getBroadcast(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("DeckOS · $gameName")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(
                null, "Stop", stopIntent
            ).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(gameName: String, status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(gameName, status))
    }
}
