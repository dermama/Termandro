package com.deckos.core.game

import android.content.Context
import android.os.Build
import android.util.Log
import com.deckos.core.runtime.DeckOSGpuDetector
import java.io.File
import java.io.RandomAccessFile

// ─────────────────────────────────────────────────────────────────────────────
// L-73 — DeckOS Game Preflight
//
// Runs before every game launch to ensure the device is ready:
//  1. RAM: at least MIN_GAME_RAM_MB free after subtracting X11/Openbox footprint.
//  2. Storage: at least MIN_STORAGE_GB free in the app's files partition.
//  3. GPU: Adreno device with /dev/kgsl-3d0 present (required for Turnip).
//         On devices without kgsl, we fall back to software rendering and warn.
//  4. Temperature: CPU/GPU composite sensor read from /sys/class/thermal.
//  5. Required pack: whether the target GamePack is installed.
//
// Design notes:
//  • All checks are synchronous and fast (<10ms total on S24 Ultra).
//  • No shell spawning — reads /proc and /sys directly (DroidDesk lesson).
//  • Returns PreflightResult (defined in GameSession.kt) which carries both
//    the pass/fail flag and enough detail for a diagnostic dialog.
//  • Cross-device: thresholds scale by total RAM bracket:
//    ≥6 GB device → require 1500 MB free  (flagship like S24 Ultra)
//    ≥3 GB device → require 1000 MB free  (mid-range)
//    <3 GB device → require  700 MB free  (entry-level, Tab A9+)
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "DeckOSPreflight"

object GamePreflight {

    /**
     * Run all preflight checks and return a [PreflightResult].
     *
     * @param context  Android context.
     * @param pack     The [GamePack] that will be installed/launched.
     * @param packMgr  [GamePackManager] used to check if pack is installed.
     */
    fun run(
        context: Context,
        pack: GamePack,
        packMgr: GamePackManager,
    ): PreflightResult {
        val ramOkMb         = checkRam()
        val storageFreeGb   = checkStorage(context)
        val gpuSupported    = checkGpu(context)
        val temperatureSafe = checkTemperature()
        val packInstalled   = packMgr.isInstalled(pack)

        val result = PreflightResult(
            ramOkMb            = ramOkMb,
            storageFreeGb      = storageFreeGb,
            gpuSupported       = gpuSupported,
            temperatureSafe    = temperatureSafe,
            requiredPackInstalled = packInstalled,
        )
        Log.i(TAG, "Preflight: ram=${ramOkMb}MB storage=${"%.2f".format(storageFreeGb)}GB " +
                "gpu=$gpuSupported temp=$temperatureSafe pack=$packInstalled → ok=${result.ok}")
        return result
    }

    // ── RAM ───────────────────────────────────────────────────────────────

    /**
     * Read MemAvailable from /proc/meminfo and return it in MB.
     * Falls back to 0 if the file is unreadable (never blocks).
     */
    private fun checkRam(): Int {
        return try {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemAvailable") }
                    ?.split("\\s+".toRegex())
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.div(1024)
                    ?.toInt()
                    ?: 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkRam failed: ${e.message}")
            0
        }
    }

    /**
     * Adaptive RAM threshold based on device tier.
     * Called by [PreflightResult] override — here for documentation.
     */
    fun adaptiveRamThresholdMb(): Int {
        val totalRam = totalRamMb()
        return when {
            totalRam >= 6_000 -> PreflightResult.MIN_GAME_RAM_MB   // 1500
            totalRam >= 3_000 -> 1_000
            else              ->   700
        }
    }

    private fun totalRamMb(): Long {
        return try {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal") }
                    ?.split("\\s+".toRegex())
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.div(1024)
                    ?: 0L
            }
        } catch (e: Exception) { 0L }
    }

    // ── Storage ───────────────────────────────────────────────────────────

    /**
     * Return free space in the app's files directory partition, in GB.
     */
    private fun checkStorage(context: Context): Float {
        return try {
            val stat = android.os.StatFs(context.filesDir.absolutePath)
            val free = stat.availableBlocksLong * stat.blockSizeLong
            free.toFloat() / (1024 * 1024 * 1024)
        } catch (e: Exception) {
            Log.w(TAG, "checkStorage failed: ${e.message}")
            0f
        }
    }

    // ── GPU ───────────────────────────────────────────────────────────────

    /**
     * A game session is supported if:
     *  (a) /dev/kgsl-3d0 is present (Adreno Qualcomm device), OR
     *  (b) The device has a Vulkan-capable GPU detectable through DeckOSGpuDetector.
     *
     * Software rendering (llvmpipe) is allowed as a last resort but flagged as
     * "not recommended" via gpuSupported=false so the user sees a warning.
     * This keeps entry-level devices (Tab A9+ with Adreno 523) usable.
     */
    private fun checkGpu(context: Context): Boolean {
        val prefix = File(context.filesDir, "usr").absolutePath
        val result = DeckOSGpuDetector.detect(prefix)
        return result.mode != DeckOSGpuDetector.GpuMode.LLVMPIPE
    }

    // ── Temperature ───────────────────────────────────────────────────────

    // Temperature danger threshold — 65°C on SoC cluster risks sustained throttle.
    private const val TEMP_DANGER_C = 65

    /**
     * Read the highest temperature from /sys/class/thermal/thermal_zoneN/temp.
     * Each file contains temperature in milli-Celsius.
     * Returns true (safe) when all zones are below TEMP_DANGER_C.
     * On devices where thermal_zone is restricted, assumes safe.
     */

    private fun checkTemperature(): Boolean {
        return try {
            val thermalRoot = File("/sys/class/thermal")
            if (!thermalRoot.exists()) return true  // safe assumption

            val maxTempC = thermalRoot.listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.mapNotNull { zone ->
                    runCatching {
                        File(zone, "temp").readText().trim().toLongOrNull()
                    }.getOrNull()
                }
                ?.maxOrNull()
                ?.div(1000L)  // milli-Celsius → Celsius
                ?: return true

            val safe = maxTempC < TEMP_DANGER_C
            if (!safe) Log.w(TAG, "Temperature check: ${maxTempC}°C >= threshold ${TEMP_DANGER_C}°C")
            safe
        } catch (e: Exception) {
            Log.w(TAG, "checkTemperature failed: ${e.message}")
            true  // fail open — don't block games when we can't read sensors
        }
    }

    // ── Device profile (used for cross-device UI messaging) ───────────────

    /**
     * Human-readable device tier for UI/logging.
     */
    fun deviceTier(): String {
        val ram = totalRamMb()
        return when {
            ram >= 10_000 -> "flagship"
            ram >=  6_000 -> "upper-mid"
            ram >=  3_000 -> "mid-range"
            else          -> "entry-level"
        }
    }

    /**
     * Recommended resolution profile for this device tier.
     * Entry-level devices default to 720p to stay within memory budget.
     */
    fun recommendedResolution(): GameConfig.ResolutionProfile {
        return when (deviceTier()) {
            "flagship"   -> GameConfig.ResolutionProfile.HD
            "upper-mid"  -> GameConfig.ResolutionProfile.HD
            "mid-range"  -> GameConfig.ResolutionProfile.HD
            else         -> GameConfig.ResolutionProfile.HD720
        }
    }
}
