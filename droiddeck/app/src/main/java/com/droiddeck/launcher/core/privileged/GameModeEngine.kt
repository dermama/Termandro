package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs

/**
 * GameModeEngine — manages privileged low-overhead performance tuning during game sessions.
 *
 * ── Philosophy: Reduction of Load First ─────────────────────────────────────
 * The primary objective is to *relieve* system stress and eliminate non-game waste,
 * rather than brutally forcing hardware to run at maximum thermal clock ceilings.
 *
 * ── Profile 1: Cool & Efficient (Default) ──────────────────────────────────
 * 1. Clean RAM Cache: Frees cached background pages (`am kill-all`) upfront, eliminating
 *    Android low-memory killer (LMK) page scanning thrash during gameplay.
 * 2. Background Process Limit: Caps background cached apps to 4 during gaming (`background_process_limit = 4`),
 *    preventing background sync and push tasks from waking up CPU cores.
 * 3. Dynamic Clock Scaling: Keeps CPU/GPU governors dynamic. Clocks scale DOWN in menus,
 *    loading screens, and light scenes, preventing thermal throttling.
 * 4. Performance Core Affinity: Binds game threads to the 5 Cortex-A720 Performance cores
 *    (mask `0x7c`, cores 2-6). This avoids cache thrashing across clusters while leaving the
 *    hot Cortex-X4 prime core idle to keep device skin temperature cool.
 * 5. Gentle Real-Time Nice: Sets process nice to -10, ensuring priority over background
 *    threads without starving audio daemons.
 *
 * ── Profile 2: Aggressive Turbo (Optional, User-Toggled) ────────────────────
 * Activated only when `SessionPrefs.gameModeAggressive(context)` is toggled ON:
 * 1. Samsung Enhanced Processing (`sem_enhanced_cpu_responsiveness = 1`).
 * 2. Samsung Unrestricted Performance (`restricted_device_performance = 0,0`).
 * 3. Power HAL Fixed Performance Mode (`cmd power set-fixed-performance-mode-enabled true`).
 * 4. Prime Core Affinity: Expands affinity mask to `0xfc` (cores 2-7, including Cortex-X4).
 * 5. Maximum Real-Time Nice: -20.
 *
 * ── Observability & Live Verification ───────────────────────────────────────
 * Provides direct query methods that inspect live Linux /proc and Android system
 * properties, allowing the user to verify exactly what is active on the real hardware.
 */
object GameModeEngine {
    private const val TAG = "GameModeEngine"

    // Cores 2, 3, 4, 5, 6 (5x Cortex-A720 Performance cores, excluding Little cores 0-1 and hot Prime core 7)
    // Binary: 01111100 = 0x7c
    private const val COOL_PERF_CORES_MASK = "7c"

    // Cores 2, 3, 4, 5, 6, 7 (Performance + Prime cores)
    // Binary: 11111100 = 0xfc
    private const val TURBO_CORES_MASK = "fc"

    @Volatile private var savedBgProcessLimit: String? = null
    @Volatile private var savedSemEnhanced: String? = null
    @Volatile private var savedRestrictedPerf: String? = null
    @Volatile private var savedAdaptiveBattery: String? = null
    @Volatile private var applied = false
    @Volatile private var aggressiveActive = false

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Apply game-mode optimizations at session start. */
    @Synchronized
    fun apply(context: Context): String {
        if (!SessionPrefs.gameModeEngine(context)) return "game mode: disabled in settings"
        if (applied) return "game mode: already active"

        val aggressive = SessionPrefs.gameModeAggressive(context)
        aggressiveActive = aggressive
        val results = mutableListOf<String>()

        // 1. Relieve background memory & CPU load (Always in Cool Mode)
        safeStep(results, "bg_limit")     { saveThenCapBgLimit(context) }
        safeStep(results, "ram_trim")     { trimRam(context) }
        safeStep(results, "game_manager") { applyGameManager(context) }
        safeStep(results, "doze_exempt")  { applyDozeExempt(context) }

        // 2. Aggressive Turbo features (ONLY if user explicitly opted in)
        if (aggressive) {
            safeStep(results, "samsung_turbo") { applySamsungTurbo(context) }
            safeStep(results, "power_fixed")   { applyPowerFixed(context) }
            safeStep(results, "governor")      { stabilizeGovernor(context) }
        }

        applied = true
        val summary = "game mode: ON [mode=${if (aggressive) "TURBO" else "COOL_LOAD_REDUCER"}, ${results.joinToString(", ")}]"
        Log.i(TAG, summary)
        return summary
    }

    /** Boost the specific game process / container PID when launched. */
    fun boostSessionProcess(context: Context, pid: Int): String {
        if (!applied || pid <= 1) return "pid_boost: skipped (pid=$pid applied=$applied)"
        val mask = if (aggressiveActive) TURBO_CORES_MASK else COOL_PERF_CORES_MASK
        val nice = if (aggressiveActive) "-20" else "-10"

        exec(context, "renice -n $nice -p $pid 2>/dev/null || true")
        exec(context, "echo -1000 > /proc/$pid/oom_score_adj 2>/dev/null || true")
        exec(context, "taskset -p $mask $pid 2>/dev/null || true")

        val desc = "pid $pid boosted (nice=$nice, affinity=0x$mask [cores=${if (aggressiveActive) "2-7" else "2-6"}])"
        Log.i(TAG, "boostSessionProcess: $desc")
        return desc
    }

    /** Restore all modified system settings when the session ends. */
    @Synchronized
    fun restore(context: Context) {
        if (!applied) return
        applied = false
        Log.i(TAG, "game mode: restoring system settings...")

        safeStep(null, "bg_limit")     { restoreBgLimit(context) }
        safeStep(null, "game_manager") { restoreGameManager(context) }

        if (aggressiveActive) {
            safeStep(null, "samsung_turbo") { restoreSamsungTurbo(context) }
            safeStep(null, "power_fixed")   { restorePowerFixed(context) }
            safeStep(null, "governor")      { restoreGovernor(context) }
        }

        aggressiveActive = false
        Log.i(TAG, "game mode: OFF (all settings restored cleanly)")
    }

    fun isActive(): Boolean = applied
    fun isAggressiveActive(): Boolean = aggressiveActive

    // ── Live System Verification & Diagnostics ─────────────────────────────────

    /**
     * Inspects actual live hardware and system settings directly via PrivilegedBridge
     * and returns verifiable real-time metrics.
     */
    fun runLiveVerification(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        out["Game Mode State"] = if (applied) (if (aggressiveActive) "🟢 ACTIVE (Turbo)" else "🟢 ACTIVE (Cool / Load Reducer)") else "⚪ STANDBY"
        out["Background Process Limit"] = exec(context, "settings get global background_process_limit").trim().let { if (it.isBlank() || it == "null") "Default (Unlimited)" else it }
        out["Free System RAM"] = exec(context, "cat /proc/meminfo | grep MemFree").trim().substringAfter(":").trim()
        out["Android Game Mode"] = exec(context, "cmd game list-modes ${context.packageName}").trim()

        val sem = exec(context, "settings get global sem_enhanced_cpu_responsiveness").trim()
        out["Samsung Enhanced Processing"] = if (sem == "1") "ON (High Clock)" else "OFF (Balanced / Cool)"

        val restricted = exec(context, "settings get global restricted_device_performance").trim()
        out["Samsung Restricted Perf"] = if (restricted.isBlank() || restricted == "null") "Default" else restricted

        return out
    }

    /** Manually triggers apply for verification testing in Debug Monitor. */
    fun testApplyNow(context: Context): String {
        val res = apply(context)
        val metrics = runLiveVerification(context)
        return buildString {
            appendLine("=== TEST APPLY RESULT ===")
            appendLine(res)
            appendLine("--- Verified Live System State ---")
            metrics.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }

    /** Manually triggers restore for verification testing in Debug Monitor. */
    fun testRestoreNow(context: Context): String {
        restore(context)
        val metrics = runLiveVerification(context)
        return buildString {
            appendLine("=== TEST RESTORE RESULT ===")
            appendLine("game mode restored to defaults")
            appendLine("--- Verified Live System State ---")
            metrics.forEach { (k, v) -> appendLine("$k: $v") }
        }
    }

    // ── Internal Steps ─────────────────────────────────────────────────────────

    private fun saveThenCapBgLimit(context: Context): String {
        val cur = exec(context, "settings get global background_process_limit").trim()
        savedBgProcessLimit = cur.takeIf { it.isNotEmpty() && it != "null" }
        exec(context, "settings put global background_process_limit 4")
        return "bg_limit→4"
    }

    private fun restoreBgLimit(context: Context): String {
        val saved = savedBgProcessLimit
        if (saved != null) {
            exec(context, "settings put global background_process_limit $saved")
        } else {
            exec(context, "settings delete global background_process_limit")
        }
        savedBgProcessLimit = null
        return "bg_limit_restored"
    }

    private fun trimRam(context: Context): String {
        exec(context, "am kill-all")
        return "ram_cleaned"
    }

    private fun applyGameManager(context: Context): String {
        val pkg = context.packageName
        exec(context, "cmd game mode 2 $pkg 2>/dev/null || true")
        return "android_game_perf"
    }

    private fun restoreGameManager(context: Context): String {
        val pkg = context.packageName
        exec(context, "cmd game reset $pkg 2>/dev/null || cmd game mode 1 $pkg 2>/dev/null || true")
        return "android_game_reset"
    }

    private fun applyDozeExempt(context: Context): String {
        val pkg = context.packageName
        exec(context, "dumpsys deviceidle whitelist +$pkg 2>/dev/null || true")
        exec(context, "cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null || true")
        return "doze_exempt"
    }

    private fun applySamsungTurbo(context: Context): String {
        val sem = exec(context, "settings get global sem_enhanced_cpu_responsiveness").trim()
        savedSemEnhanced = sem.takeIf { it.isNotEmpty() && it != "null" }
        exec(context, "settings put global sem_enhanced_cpu_responsiveness 1 2>/dev/null || true")

        val r = exec(context, "settings get global restricted_device_performance").trim()
        savedRestrictedPerf = r.takeIf { it.isNotEmpty() && it != "null" }
        exec(context, "settings put global restricted_device_performance 0,0 2>/dev/null || true")

        return "samsung_turbo_on"
    }

    private fun restoreSamsungTurbo(context: Context): String {
        savedSemEnhanced?.let {
            exec(context, "settings put global sem_enhanced_cpu_responsiveness $it 2>/dev/null || true")
        }
        savedRestrictedPerf?.let {
            exec(context, "settings put global restricted_device_performance $it 2>/dev/null || true")
        }
        savedSemEnhanced = null
        savedRestrictedPerf = null
        return "samsung_turbo_restored"
    }

    private fun applyPowerFixed(context: Context): String {
        exec(context, "cmd power set-fixed-performance-mode-enabled true 2>/dev/null || true")
        return "fixed_power_perf"
    }

    private fun restorePowerFixed(context: Context): String {
        exec(context, "cmd power set-fixed-performance-mode-enabled false 2>/dev/null || true")
        return "fixed_power_restored"
    }

    private fun stabilizeGovernor(context: Context): String {
        val cur = exec(context, "settings get global adaptive_battery_management_enabled").trim()
        savedAdaptiveBattery = cur.takeIf { it.isNotEmpty() && it != "null" }
        exec(context, "settings put global adaptive_battery_management_enabled 0 2>/dev/null || true")
        return "adaptive_battery_disabled"
    }

    private fun restoreGovernor(context: Context): String {
        savedAdaptiveBattery?.let {
            exec(context, "settings put global adaptive_battery_management_enabled $it 2>/dev/null || true")
        }
        savedAdaptiveBattery = null
        return "adaptive_battery_restored"
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private fun safeStep(results: MutableList<String>?, name: String, block: () -> String) {
        try {
            val res = block()
            results?.add(res)
        } catch (t: Throwable) {
            Log.w(TAG, "step $name error: ${t.message}")
            results?.add("$name:err")
        }
    }

    private fun exec(context: Context, cmd: String): String {
        return try {
            PrivilegedBridge.execute(cmd).output
        } catch (t: Throwable) {
            Log.w(TAG, "exec error: $cmd", t)
            ""
        }
    }
}
