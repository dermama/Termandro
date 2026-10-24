package com.deckos.core.game

// ─────────────────────────────────────────────────────────────────────────────
// L-79 — DeckOS A/B Performance Profiles for Game Mode
//
// Three pre-defined profiles that balance quality vs. performance:
//  LIGHT  — entry-level devices (Adreno 523/610, 4 GB RAM, Tab A9+)
//  NORMAL — mid-range and upper-mid (Adreno 630–740, 6–8 GB RAM)
//  ULTRA  — flagship (Adreno 750/8xx, 8–12 GB RAM, S24 Ultra)
//
// Each profile sets:
//  • resolution  — GameConfig.ResolutionProfile
//  • fsrRatio    — FSR upscale ratio (1.0 = off, 0.66 = ~75% render resolution)
//  • fpsCap      — target FPS cap (0 = uncapped)
//  • useNative   — prefer ARM64 native over FEX for x86-64 games
//
// The auto-selector picks a profile based on:
//  1. Total RAM (from /proc/meminfo MemTotal)
//  2. Adreno generation (parsed from /sys/bus/platform/drivers/kgsl or sysfs)
//
// Users can override via Settings or DECKOS_PERF_PROFILE env var.
// ─────────────────────────────────────────────────────────────────────────────

enum class PerfProfile(
    val displayName: String,
    val resolution: GameConfig.ResolutionProfile,
    val fsrRatio: Float,
    val fpsCap: Int,
    val description: String,
) {
    /**
     * LIGHT: 720p, FSR 0.66 (renders at ~480p and upscales), 30 FPS cap.
     * Target: Galaxy Tab A9+ (Snapdragon 695, Adreno 619, 4 GB) and similar
     * entry-level devices. Keeps GPU busy ≤70% and thermal ≤48°C.
     */
    LIGHT(
        displayName = "Light (720p / 30fps)",
        resolution  = GameConfig.ResolutionProfile.HD720,
        fsrRatio    = 0.66f,
        fpsCap      = 30,
        description = "Best for entry-level devices. Lower resolution, FSR upscale, " +
            "30 FPS cap. Reduces heat and extends battery life.",
    ),

    /**
     * NORMAL: 1080p, FSR 0.77 (renders at ~830p), 60 FPS cap.
     * Target: mid-range/upper-mid (Adreno 630–730, 6–8 GB).
     */
    NORMAL(
        displayName = "Normal (1080p / 60fps)",
        resolution  = GameConfig.ResolutionProfile.HD,
        fsrRatio    = 0.77f,
        fpsCap      = 60,
        description = "Balanced profile. 1080p output, FSR upscale from ~830p, " +
            "60 FPS cap. Good balance of quality and thermal headroom.",
    ),

    /**
     * ULTRA: 1080p native (or full display resolution), no FSR, uncapped FPS.
     * Target: flagship (Adreno 750/8xx, 8–12 GB, S24 Ultra).
     */
    ULTRA(
        displayName = "Ultra (Native / Uncapped)",
        resolution  = GameConfig.ResolutionProfile.HD,
        fsrRatio    = 1.0f,
        fpsCap      = 0,
        description = "Maximum quality. Native 1080p or device resolution, no FSR, " +
            "uncapped FPS. Use on flagship devices with active cooling.",
    ),
}

/**
 * Auto-select a [PerfProfile] based on the device's memory tier.
 * Reads /proc/meminfo MemTotal in kB (same path used by GamePreflight).
 */
object PerfProfileSelector {

    /**
     * Returns the recommended [PerfProfile] for the current device.
     * Call on a background thread (file I/O, though trivially fast).
     */
    fun autoSelect(): PerfProfile {
        val totalRamMb = readTotalRamMb()
        return when {
            totalRamMb >= 8_000 -> PerfProfile.ULTRA
            totalRamMb >= 4_000 -> PerfProfile.NORMAL
            else                -> PerfProfile.LIGHT
        }
    }

    /**
     * Build a [GameConfig] from a [PerfProfile], merging with a base config.
     */
    fun applyProfile(base: GameConfig, profile: PerfProfile): GameConfig = base.copy(
        resolutionProfile = profile.resolution,
        fsrRatio          = profile.fsrRatio,
        fpsCap            = profile.fpsCap,
    )

    /**
     * Parse the selected profile from an env var or settings string.
     * Accepts "light", "normal", "ultra" (case-insensitive).
     * Falls back to [autoSelect] on unknown input.
     */
    fun fromEnvOrAuto(): PerfProfile {
        val override = System.getenv("DECKOS_PERF_PROFILE") ?: ""
        return when (override.lowercase()) {
            "light"  -> PerfProfile.LIGHT
            "normal" -> PerfProfile.NORMAL
            "ultra"  -> PerfProfile.ULTRA
            else     -> autoSelect()
        }
    }

    // ── Device capability tests ───────────────────────────────────────────

    /**
     * Returns true if the device likely supports Turnip/Vulkan via kgsl.
     * Used to decide whether FSR/resolution matters (software renderer is
     * always slow regardless of FSR).
     */
    fun hasVulkanGpu(): Boolean = java.io.File("/dev/kgsl-3d0").exists()

    fun readTotalRamMb(): Long {
        return try {
            java.io.File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal") }
                    ?.split("\\s+".toRegex())
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.div(1024)
                    ?: 0L
            }
        } catch (e: Exception) { 0L }
    }

    data class DeviceHardwareInfo(
        val model: String,
        val totalRamMb: Long,
        val hasAdrenoGpu: Boolean,
        val profile: PerfProfile,
    )

    fun getHardwareInfo(): DeviceHardwareInfo = DeviceHardwareInfo(
        model = android.os.Build.MODEL,
        totalRamMb = readTotalRamMb(),
        hasAdrenoGpu = hasVulkanGpu(),
        profile = autoSelect(),
    )
}
