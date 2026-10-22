package com.deckos.core

import android.content.Context
import java.io.File

data class X11ClientStatus(
    val running: Boolean,
    val pid: Int?,
    val log: String,
)

/**
 * Runs the bundled raw X11 client (tools/deck_xclient.c) against the DeckOS X11 server.
 *
 * The client speaks the X11 wire protocol directly, so it does not need Xlib, a display
 * manager or any Termux package. It opens one window, paints a small scene and reacts to
 * pointer input, which proves that the server accepts clients and renders frames.
 */
object X11TestClient {
    private const val ASSET_NAME = "deck-xclient"
    private const val BINARY_NAME = "deck-xclient"

    private fun binaryFile(context: Context) = File(File(context.filesDir, "bin"), BINARY_NAME)

    private fun pidFile(context: Context) = File(context.filesDir, "xclient.pid")

    fun logFile(context: Context) = File(context.filesDir, "xclient.log")

    fun ensureBinary(context: Context): File? {
        val target = binaryFile(context)
        if (target.isFile && target.canExecute()) return target
        return runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
            target
        }.getOrNull()
    }

    fun isRunning(context: Context): Boolean {
        val pid = readPid(context) ?: return false
        val cmdline = File("/proc/$pid/cmdline")
        if (!cmdline.isFile) return false
        return runCatching {
            cmdline.readBytes().toString(Charsets.ISO_8859_1).contains(BINARY_NAME)
        }.getOrDefault(false)
    }

    private fun readPid(context: Context): Int? =
        pidFile(context).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    fun status(context: Context): X11ClientStatus = X11ClientStatus(
        running = isRunning(context),
        pid = readPid(context),
        log = logTail(context),
    )

    /**
     * Starts the test client. Returns the pid when the client is running, null otherwise.
     */
    fun start(context: Context): Int? {
        stop(context)
        val binary = ensureBinary(context) ?: return null
        if (!binary.canExecute()) return null

        val socket = X11Server.socketPath(context)
        val pidFile = pidFile(context)
        pidFile.delete()
        val command = "echo \$\$ > '${pidFile.absolutePath}' && " +
            "exec '${binary.absolutePath}' '${socket}'"
        runCatching {
            ProcessBuilder("/system/bin/sh", "-c", command).apply {
                environment()["DISPLAY"] = X11Server.DISPLAY
                environment()["HOME"] = File(context.filesDir, "home").absolutePath
                redirectErrorStream(true)
                redirectOutput(logFile(context))
            }.start()
        }.onFailure { return null }

        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (isRunning(context)) return readPid(context)
            Thread.sleep(100)
        }
        return readPid(context)
    }

    fun stop(context: Context) {
        val pid = readPid(context)
        if (pid != null) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-TERM", pid.toString())).waitFor()
            }
            val deadline = System.currentTimeMillis() + 1_500
            while (System.currentTimeMillis() < deadline && isRunning(context)) {
                Thread.sleep(80)
            }
        }
        pidFile(context).delete()
    }

    fun logTail(context: Context, lines: Int = 20): String {
        val file = logFile(context)
        if (!file.isFile) return ""
        return runCatching {
            file.readLines().takeLast(lines).joinToString("\n")
        }.getOrDefault("")
    }
}
