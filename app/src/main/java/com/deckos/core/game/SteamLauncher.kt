package com.deckos.core.game

import android.content.Context
import android.util.Log
import com.deckos.core.X11Server
import com.deckos.core.packs.ExtensionPackCatalog
import com.deckos.core.packs.ExtensionPackManager
import java.io.File

private const val TAG = "SteamLauncher"

/**
 * Launches Steam via the bundled DroidDeck runtime inside the game pack rootfs.
 *
 * Architecture:
 *  - Runtime: Arch Linux ARM rootfs with Mesa Turnip KGSL (Adreno Vulkan) & Gamescope 3.16
 *  - Host Executor: Termux PRoot fork (opt/android-host/proot + loader)
 *  - Session runner: /usr/local/bin/bannerlator-session steam
 */
object SteamLauncher {

    data class LaunchResult(val success: Boolean, val pid: Int?, val message: String)

    /**
     * Build the PRoot execution command for running inside the Steam/Proton pack.
     */
    fun buildProotCommand(
        context: Context,
        guestArgs: List<String>,
        packDir: File = ExtensionPackManager(context).getPackDir(ExtensionPackCatalog.STEAM_PROTON.id)
    ): List<String> {
        val prootBin = File(packDir, "opt/android-host/proot")

        return buildList {
            add(prootBin.absolutePath)
            add("--kill-on-exit")
            add("-0")
            add("-r")
            add(packDir.absolutePath)
            add("-w")
            add("/root")
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("/dev/urandom:/dev/random")
            add("-b")
            add("/proc/self/fd:/dev/fd")
            add("-b")
            add("/proc/self/fd/0:/dev/stdin")
            add("-b")
            add("/proc/self/fd/1:/dev/stdout")
            add("-b")
            add("/proc/self/fd/2:/dev/stderr")
            add("-b")
            add("${context.cacheDir.absolutePath}:/tmp")

            val kgsl = File("/dev/kgsl-3d0")
            if (kgsl.exists()) {
                add("-b")
                add("/dev/kgsl-3d0")
            }
            val dri = File("/dev/dri")
            if (dri.exists()) {
                add("-b")
                add("/dev/dri")
            }
            val sdcard = File("/sdcard")
            if (sdcard.exists()) {
                add("-b")
                add("/sdcard")
            }
            addAll(guestArgs)
        }
    }

    /**
     * Setup environment variables required by proot and gamescope/turnip.
     */
    fun setupEnvironment(
        context: Context,
        env: MutableMap<String, String>,
        display: String = X11Server.DISPLAY,
        packDir: File = ExtensionPackManager(context).getPackDir(ExtensionPackCatalog.STEAM_PROTON.id)
    ) {
        val prootBin = File(packDir, "opt/android-host/proot")
        val loaderBin = File(packDir, "opt/android-host/loader")

        env["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
        env["PROOT_LOADER"] = loaderBin.absolutePath
        env["LD_LIBRARY_PATH"] = prootBin.parentFile.absolutePath
        env["PATH"] = "/usr/local/bin:/usr/bin:/bin:/system/bin"
        env["DISPLAY"] = display
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["BL_WIDTH"] = "1280"
        env["BL_HEIGHT"] = "720"
        env["BL_FPS"] = "60"
        env["BL_LOG"] = File(context.cacheDir, "steam-session.log").absolutePath
        env["VK_ICD_FILENAMES"] = "/usr/share/vulkan/icd.d/freedreno_icd.json"
    }

    /**
     * Attempt to start Steam. Returns immediately; process runs in background.
     */
    fun launch(context: Context): LaunchResult {
        val manager = ExtensionPackManager(context)
        if (!manager.isInstalled(ExtensionPackCatalog.STEAM_PROTON.id)) {
            return LaunchResult(false, null, "Game Pack not installed. Download it from Extension Packs.")
        }

        val packDir = manager.getPackDir(ExtensionPackCatalog.STEAM_PROTON.id)
        val prootBin = File(packDir, "opt/android-host/proot")
        val sessionScript = File(packDir, "usr/local/bin/bannerlator-session")

        if (!prootBin.exists() || !sessionScript.exists()) {
            return LaunchResult(false, null, "Game pack files missing. Reinstall the pack.")
        }

        // Ensure X11 server is running
        X11Server.ensureRunning(context)

        return try {
            val cmd = buildProotCommand(
                context = context,
                guestArgs = listOf("/usr/local/bin/bannerlator-session", "steam"),
                packDir = packDir
            )

            val pb = ProcessBuilder(cmd).apply {
                setupEnvironment(context, environment(), X11Server.DISPLAY, packDir)
                redirectErrorStream(true)
            }
            val process = pb.start()
            val pid = runCatching {
                val f = process.javaClass.getDeclaredField("pid")
                f.isAccessible = true
                f.getInt(process)
            }.getOrNull()

            Log.i(TAG, "Steam launched, pid=$pid display=${X11Server.DISPLAY}")
            LaunchResult(true, pid, "Steam started on display ${X11Server.DISPLAY}")
        } catch (e: Exception) {
            Log.e(TAG, "Steam launch failed", e)
            LaunchResult(false, null, "Launch failed: ${e.message}")
        }
    }
}
