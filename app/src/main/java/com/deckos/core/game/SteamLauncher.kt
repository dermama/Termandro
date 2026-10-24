package com.deckos.core.game

import android.content.Context
import android.util.Log
import com.deckos.core.packs.ExtensionPackCatalog
import com.deckos.core.packs.ExtensionPackManager
import java.io.File

private const val TAG = "SteamLauncher"

/**
 * Launches Steam via Proton-GE inside the game pack rootfs.
 *
 * Prerequisites:
 *  - Game Pack installed (steam_proton)
 *  - X11 display running
 *  - Termux usr/bin/proot or proot-distro available
 */
object SteamLauncher {

    data class LaunchResult(val success: Boolean, val pid: Int?, val message: String)

    /**
     * Attempt to start Steam. Returns immediately; process runs in background.
     */
    fun launch(context: Context): LaunchResult {
        val manager = ExtensionPackManager(context)
        if (!manager.isInstalled(ExtensionPackCatalog.STEAM_PROTON.id)) {
            return LaunchResult(false, null, "Game Pack not installed. Download it from Extension Packs.")
        }

        val packDir   = manager.getPackDir(ExtensionPackCatalog.STEAM_PROTON.id)
        val steamBin  = File(packDir, "usr/bin/steam")
        val protonBin = File(packDir, "usr/bin/proton")

        if (!steamBin.exists() && !protonBin.exists()) {
            return LaunchResult(false, null, "Steam binary not found in game pack. Reinstall the pack.")
        }

        val prefix    = File(context.filesDir, "usr")
        val prootBin  = File(prefix, "bin/proot")
        val display   = System.getenv("DISPLAY") ?: ":1"

        return try {
            val cmd = buildList {
                if (prootBin.exists()) {
                    add(prootBin.absolutePath)
                    add("--rootfs=${packDir.absolutePath}")
                    add("--bind=/dev/kgsl-3d0:/dev/kgsl-3d0")    // Adreno Turnip
                    add("--bind=/dev/dri:/dev/dri")
                    add("--bind=/tmp:/tmp")
                    add("/usr/bin/env")
                } else {
                    add("/system/bin/sh")
                    add("-c")
                }
                add("DISPLAY=$display")
                add("STEAM_RUNTIME=1")
                add("PROTON_USE_WINED3D=0")
                add("DXVK_ASYNC=1")
                add(steamBin.absolutePath)
            }

            val pb = ProcessBuilder(cmd).apply {
                environment()["DISPLAY"] = display
                environment()["PATH"] = "${prefix.absolutePath}/bin:/system/bin"
                environment()["HOME"] = context.filesDir.absolutePath
                redirectErrorStream(true)
            }
            val process = pb.start()
            val pid = runCatching {
                val f = process.javaClass.getDeclaredField("pid")
                f.isAccessible = true
                f.getInt(process)
            }.getOrNull()

            Log.i(TAG, "Steam launched, pid=$pid display=$display")
            LaunchResult(true, pid, "Steam started on display $display")
        } catch (e: Exception) {
            Log.e(TAG, "Steam launch failed", e)
            LaunchResult(false, null, "Launch failed: ${e.message}")
        }
    }
}
