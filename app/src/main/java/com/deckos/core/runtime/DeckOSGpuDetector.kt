package com.deckos.core.runtime

import android.util.Log
import java.io.File

/**
 * L-63 — DeckOS GPU Detector
 *
 * Detects available GPU capabilities and builds the optimal graphics environment
 * variables. Ported concept from DroidDesk's `hasAdrenoGpu()` + `getGraphicsEnv()`,
 * but with key differences:
 *
 * 1. Turnip/Zink is NEVER enabled by default — requires explicit opt-in via
 *    the environment variable `DECKOS_GPU=turnip`.
 * 2. Does not require Mesa packages to be pre-installed — checks availability
 *    before setting any Mesa-specific vars.
 * 3. Decision is documented: vendor Adreno driver is the default because it is
 *    the most stable path on Android 16 + S24 Ultra (Adreno A740).
 *
 * DroidDesk mistake avoided: setting llvmpipe as chroot default while claiming Turnip.
 * DeckOS approach: always tell the truth about the active GPU path.
 */
object DeckOSGpuDetector {

    private const val TAG = "DeckOSGpu"

    /** Adreno GPU node — present on Qualcomm SoCs. */
    private const val KGSL_NODE = "/dev/kgsl-3d0"

    /** Mesa Turnip Vulkan ICD descriptor installed by mesa-vulkan-icd-freedreno. */
    private const val TURNIP_ICD_PATH =
        "/data/user/0/com.deckos.core/files/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"

    enum class GpuMode {
        /** Vendor Adreno driver via EGL — default, most stable. */
        VENDOR_DEFAULT,
        /** Turnip Vulkan + Zink OpenGL — opt-in, requires mesa-vulkan-icd-freedreno. */
        TURNIP_ZINK,
        /** llvmpipe software renderer — fallback when nothing else works. */
        LLVMPIPE,
    }

    data class GpuEnvironment(
        val mode: GpuMode,
        val envVars: Map<String, String>,
        val notes: String,
    )

    /**
     * Detect the active GPU mode and return the matching environment variables.
     *
     * Decision tree:
     * 1. If `DECKOS_GPU=turnip` AND Adreno AND Turnip ICD present → TURNIP_ZINK
     * 2. If `DECKOS_GPU=software` → LLVMPIPE
     * 3. Otherwise → VENDOR_DEFAULT (no extra vars needed)
     */
    fun detect(prefix: String): GpuEnvironment {
        val requested = System.getenv("DECKOS_GPU") ?: ""

        return when {
            requested.equals("turnip", ignoreCase = true) -> tryTurnip(prefix)
            requested.equals("software", ignoreCase = true) -> llvmpipe()
            else -> vendorDefault()
        }
    }

    private fun hasAdrenoGpu(): Boolean = File(KGSL_NODE).exists()

    private fun hasTurnipIcd(prefix: String): Boolean {
        // Check both the standard path and prefix-relative path
        val stdPath = File(TURNIP_ICD_PATH)
        val prefixPath = File("$prefix/share/vulkan/icd.d/freedreno_icd.aarch64.json")
        return stdPath.exists() || prefixPath.exists()
    }

    private fun tryTurnip(prefix: String): GpuEnvironment {
        if (!hasAdrenoGpu()) {
            Log.w(TAG, "DECKOS_GPU=turnip requested but no Adreno GPU found (/dev/kgsl-3d0 missing). Falling back to vendor default.")
            return vendorDefault()
        }
        if (!hasTurnipIcd(prefix)) {
            Log.w(TAG, "DECKOS_GPU=turnip requested but Turnip ICD not found. Install: pkg install mesa-vulkan-icd-freedreno. Falling back to vendor default.")
            return vendorDefault()
        }

        val icdPath = if (File("$prefix/share/vulkan/icd.d/freedreno_icd.aarch64.json").exists())
            "$prefix/share/vulkan/icd.d/freedreno_icd.aarch64.json"
        else
            TURNIP_ICD_PATH

        Log.i(TAG, "GPU: Turnip/Zink path active (DECKOS_GPU=turnip, ICD=$icdPath)")
        return GpuEnvironment(
            mode = GpuMode.TURNIP_ZINK,
            envVars = mapOf(
                "VK_ICD_FILENAMES" to icdPath,
                "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                "GALLIUM_DRIVER" to "zink",
                "MESA_GL_VERSION_OVERRIDE" to "4.6",
                "MESA_GLES_VERSION_OVERRIDE" to "3.2",
                // Disable Adreno proprietary path so Mesa takes over
                "MESA_VK_DEVICE_SELECT" to "0",
            ),
            notes = "Turnip Vulkan + Zink OpenGL on Adreno. Opt-in only.",
        )
    }

    private fun llvmpipe(): GpuEnvironment {
        Log.i(TAG, "GPU: llvmpipe software renderer (DECKOS_GPU=software)")
        return GpuEnvironment(
            mode = GpuMode.LLVMPIPE,
            envVars = mapOf(
                "LIBGL_ALWAYS_SOFTWARE" to "true",
                "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
                "GALLIUM_DRIVER" to "llvmpipe",
            ),
            notes = "Software renderer. Use only for debugging.",
        )
    }

    private fun vendorDefault(): GpuEnvironment {
        val hasAdreno = hasAdrenoGpu()
        Log.i(TAG, "GPU: vendor default (Adreno=$hasAdreno). Use DECKOS_GPU=turnip to opt-in to Turnip/Zink.")
        return GpuEnvironment(
            mode = GpuMode.VENDOR_DEFAULT,
            envVars = emptyMap(), // no extra vars — let the system handle it
            notes = if (hasAdreno)
                "Adreno detected. Vendor EGL/GLES driver active. Opt-in: DECKOS_GPU=turnip."
            else
                "No Adreno detected. Vendor default GPU path.",
        )
    }

    /**
     * Returns the extra environment entries to add to [LinuxRuntime.environment()]
     * based on the detected GPU mode.
     */
    fun extraEnvArray(prefix: String): Array<String> {
        val env = detect(prefix)
        return env.envVars.map { (k, v) -> "$k=$v" }.toTypedArray()
    }
}
