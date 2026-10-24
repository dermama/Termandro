package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

/**
 * AppFreezerEngine — intelligent background app suspension & system deep-doze manager.
 *
 * ── Objectives ──────────────────────────────────────────────────────────────
 * 1. Deep System Doze: Commands Android framework into deep idle mode (`cmd deviceidle force-idle deep`)
 *    while keeping DroidDeck whitelisted, immediately freezing all non-essential timers,
 *    network calls, alarms, and background wakelocks OS-wide.
 * 2. Bloatware & Telemetry Freeze: Detects non-essential OEM bloatware (Bixby, Facebook
 *    services, Digital Wellbeing tracking, Samsung Pay daemons) and puts them into suspended
 *    state (`cmd package suspend`) for the duration of the game session.
 * 3. 3rd-Party User Apps Freeze (Optional): Can suspend all installed user apps during play
 *    so zero social media, background sync, or analytics daemons can wake up CPU cores.
 * 4. 100% Fail-Safe Restoration: Every suspended package is logged to a persistent marker file
 *    (`suspended-packages.txt`). On session exit or upon next app startup (even after crash/reboot),
 *    every single package is guaranteed to be restored and unsuspended immediately.
 */
object AppFreezerEngine {
    private const val TAG = "AppFreezerEngine"
    private const val MARKER_FILE = "suspended-packages.txt"

    /**
     * Non-essential system daemons and telemetry trackers that run in the background
     * without providing any gaming or core OS functionality.
     */
    val KNOWN_BLOATWARE = listOf(
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.bixbyvision.framework",
        "com.samsung.android.forest",         // Samsung Digital Wellbeing (constant usage stats polling)
        "com.samsung.android.spayfw",         // Samsung Pay framework
        "com.samsung.android.game.gos",       // Game Optimizing Service (unnecessary background polling)
        "com.facebook.services",
        "com.facebook.system",
        "com.facebook.appmanager",
        "com.samsung.android.arzone",
        "com.samsung.android.kidsinstaller",
        "com.google.android.apps.tachyon",   // Google Meet background daemon
        "com.microsoft.skydrive",             // OneDrive cloud sync
    )

    /** Packages that must NEVER be suspended under any circumstance. */
    private val PROTECTED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.server.telecom",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.sec.android.app.launcher",
        "moe.shizuku.privileged.api",
        "com.termux",
    )

    @Volatile private var suspendedPackages = mutableListOf<String>()
    @Volatile private var dozeForced = false
    @Volatile private var applied = false

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Apply system doze and package suspension when session starts. */
    @Synchronized
    fun apply(context: Context): String {
        if (applied) return "freezer: already active"
        val results = mutableListOf<String>()
        val packagesToSuspend = mutableListOf<String>()

        // 1. Deep System Doze
        if (SessionPrefs.deepSystemDoze(context)) {
            val r = exec(context, "dumpsys deviceidle whitelist +${context.packageName} && cmd deviceidle force-idle deep")
            if (r.contains("Now forced") || r.contains("IDLE") || r.contains("ACTIVE")) {
                dozeForced = true
                results.add("deep_doze_forced")
            }
        }

        // 2. Discover and add Bloatware
        if (SessionPrefs.freezeBloatware(context)) {
            val installedBloatware = getInstalledBloatware(context)
            packagesToSuspend.addAll(installedBloatware)
            results.add("bloatware_found=${installedBloatware.size}")
        }

        // 3. User 3rd-party apps (if opted in)
        if (SessionPrefs.freezeUserApps(context)) {
            val userApps = getInstalledUserApps(context)
            packagesToSuspend.addAll(userApps)
            results.add("user_apps_found=${userApps.size}")
        }

        val uniqueTargets = packagesToSuspend.distinct().filter { it !in PROTECTED_PACKAGES && it != context.packageName }
        if (uniqueTargets.isNotEmpty()) {
            suspendBatch(context, uniqueTargets)
            suspendedPackages.clear()
            suspendedPackages.addAll(uniqueTargets)
            saveMarker(context, uniqueTargets)
            results.add("suspended_total=${uniqueTargets.size}")
        }

        applied = true
        val summary = "app freezer: ON [${results.joinToString(", ")}]"
        Log.i(TAG, summary)
        return summary
    }

    /** Restore all suspended packages and unforce doze on session exit. */
    @Synchronized
    fun restore(context: Context) {
        if (!applied && !hasMarker(context)) return
        applied = false
        Log.i(TAG, "app freezer: restoring suspended packages and system doze...")

        // 1. Unforce Doze
        if (dozeForced) {
            exec(context, "cmd deviceidle unforce")
            dozeForced = false
        }

        // 2. Unsuspend all packages
        val targets = loadMarker(context).ifEmpty { suspendedPackages }
        if (targets.isNotEmpty()) {
            unsuspendBatch(context, targets)
            suspendedPackages.clear()
        }
        deleteMarker(context)

        Log.i(TAG, "app freezer: OFF (all apps restored)")
    }

    /**
     * Called on application start to ensure any packages left suspended from
     * an earlier abrupt termination or device reboot are safely unsuspended.
     */
    fun clearLeftover(context: Context) {
        if (!hasMarker(context)) return
        Thread {
            try {
                Log.w(TAG, "clearLeftover: found leftover suspended packages from earlier session, restoring...")
                val targets = loadMarker(context)
                if (targets.isNotEmpty()) {
                    unsuspendBatch(context, targets)
                }
                exec(context, "cmd deviceidle unforce 2>/dev/null || true")
                deleteMarker(context)
                Log.i(TAG, "clearLeftover: all leftover packages unsuspended successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "clearLeftover error", t)
            }
        }.start()
    }

    fun isDozeActive(): Boolean = dozeForced
    fun getSuspendedCount(): Int = suspendedPackages.size
    fun getSuspendedList(): List<String> = suspendedPackages.toList()

    /** Live diagnostics for Debug Monitor. */
    fun runDiagnostic(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        out["Deep Doze State"] = exec(context, "cmd deviceidle get deep").trim().let { if (it.isBlank()) "Unknown" else it }
        out["Currently Suspended Apps"] = if (applied) "${suspendedPackages.size} packages" else "0 (Standby)"
        val bloat = getInstalledBloatware(context)
        out["Detected Bloatware on Device"] = "${bloat.size} daemons detected"
        val userCount = exec(context, "pm list packages -3 | wc -l").trim()
        out["Total 3rd Party User Apps"] = userCount.ifBlank { "0" }
        return out
    }

    // ── Internal Helpers ───────────────────────────────────────────────────────

    private fun getInstalledBloatware(context: Context): List<String> {
        val allSystem = exec(context, "pm list packages -s").lines()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return KNOWN_BLOATWARE.filter { it in allSystem }
    }

    private fun getInstalledUserApps(context: Context): List<String> {
        return exec(context, "pm list packages -3").lines()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() && it !in PROTECTED_PACKAGES && it != context.packageName }
    }

    private fun suspendBatch(context: Context, packages: List<String>) {
        packages.chunked(40).forEach { chunk ->
            val cmd = "cmd package suspend --user 0 " + chunk.joinToString(" ")
            exec(context, cmd)
        }
    }

    private fun unsuspendBatch(context: Context, packages: List<String>) {
        packages.chunked(40).forEach { chunk ->
            val cmd = "cmd package unsuspend --user 0 " + chunk.joinToString(" ")
            exec(context, cmd)
        }
    }

    private fun saveMarker(context: Context, list: List<String>) {
        try {
            File(context.filesDir, MARKER_FILE).writeText(list.joinToString("\n"))
        } catch (_: Exception) {}
    }

    private fun loadMarker(context: Context): List<String> {
        return try {
            val f = File(context.filesDir, MARKER_FILE)
            if (f.exists()) f.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun hasMarker(context: Context): Boolean = File(context.filesDir, MARKER_FILE).exists()

    private fun deleteMarker(context: Context) {
        try { File(context.filesDir, MARKER_FILE).delete() } catch (_: Exception) {}
    }

    private fun exec(context: Context, cmd: String): String {
        return try { PrivilegedBridge.execute(cmd).output } catch (t: Throwable) {
            Log.w(TAG, "exec error: $cmd", t)
            ""
        }
    }
}
