package com.deckos.core

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

object X11Server {
    fun ensureRunning(context: Context) {
        val filesDir = context.filesDir
        val xkbDir = File(filesDir, "xkb")
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
        val pidFile = File(filesDir, "x11.pid")

        if (!File(xkbDir, "rules/evdev").isFile) {
            extractXkb(context, xkbDir)
        }

        val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (pid != null && File("/proc/$pid").exists()) {
            return
        }

        val command = "echo \$\$ > '${pidFile.absolutePath}' && " +
            "exec /system/bin/app_process -Xnoimage-dex2oat / " +
            "--nice-name=termux-x11-deck " +
            "com.termux.x11.CmdEntryPoint :1 -ac"

        ProcessBuilder("/system/bin/sh", "-c", command).apply {
            directory(filesDir)
            environment()["CLASSPATH"] = context.applicationInfo.sourceDir
            environment()["TMPDIR"] = tmpDir.absolutePath
            environment()["XKB_CONFIG_ROOT"] = xkbDir.absolutePath
            redirectErrorStream(true)
            redirectOutput(File(filesDir, "x11.log"))
        }.start()
    }

    private fun extractXkb(context: Context, dest: File) {
        val staging = File(dest.parentFile, "xkb.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        val root = staging.canonicalPath + File.separator

        context.assets.open("xkb.zip").use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(staging, entry.name)
                    if (!target.canonicalPath.startsWith(root)) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }

        dest.deleteRecursively()
        staging.renameTo(dest)
    }
}
