package com.deckos.core

import android.content.Context
import android.os.Process
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

data class X11ServerStatus(
    val running: Boolean,
    val pid: Int?,
    val socketPath: String,
    val socketReady: Boolean,
    val disabled: Boolean,
    val log: String,
)

/**
 * Owns the DeckOS X11 server (Termux:X11 "lorie" xserver) that runs inside this app process
 * group. The server is started through /system/bin/app_process with this APK on the CLASSPATH,
 * exactly like the standalone Termux:X11 app does it, but with its own display number and its
 * own knock port so it never collides with a system wide Termux:X11 installation.
 */
object X11Server {
    const val DISPLAY_NUMBER = 1
    const val DISPLAY = ":$DISPLAY_NUMBER"
    const val KNOCK_PORT = 7893

    private const val SERVER_PROCESS_NAME = "termux-x11-deck"
    private const val SERVER_ENTRY_POINT = "com.termux.x11.CmdEntryPoint"
    private const val SERVER_ARGUMENTS = "$SERVER_ENTRY_POINT $DISPLAY -ac"
    // L-52: reduced from 6000ms — socket appears in <1s on S24 Ultra; 4s gives safe margin
    private const val SOCKET_WAIT_MS = 4_000L

    fun socketPath(context: Context): String =
        File(File(context.filesDir, "tmp"), ".X11-unix/X$DISPLAY_NUMBER").absolutePath

    private fun pidFile(context: Context) = File(context.filesDir, "x11.pid")

    private fun disabledFile(context: Context) = File(context.filesDir, "x11.disabled")

    fun logFile(context: Context) = File(context.filesDir, "x11.log")

    fun isDisabled(context: Context): Boolean = disabledFile(context).isFile

    fun readPid(context: Context): Int? {
        val pid = pidFile(context).takeIf { it.isFile }
            ?.readText()?.trim()?.toIntOrNull() ?: return null
        return if (isServerProcess(pid)) pid else null
    }

    fun isRunning(context: Context): Boolean = readPid(context) != null

    /**
     * app_process replaces argv[0] with the --nice-name value once it starts, so the live
     * server advertises exactly "termux-x11-deck" and no longer carries the entry point.
     * Match that token; never match the transient shell wrapper, whose argv[0] is "sh".
     */
    fun isServerProcess(pid: Int): Boolean {
        val argv = readCmdline(pid) ?: return false
        val argv0 = argv.firstOrNull()?.substringAfterLast('/') ?: return false
        if (argv0 == SERVER_PROCESS_NAME) return true
        return argv0.startsWith("app_process") &&
            argv.any { it == "--nice-name=$SERVER_PROCESS_NAME" }
    }

    private fun readCmdline(pid: Int): List<String>? {
        val file = File("/proc/$pid/cmdline")
        if (!file.isFile) return null
        return runCatching {
            file.readBytes().toString(Charsets.ISO_8859_1)
                .split('\u0000')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }.getOrNull()
    }

    fun status(context: Context): X11ServerStatus {
        val pid = readPid(context)
        val socket = File(socketPath(context))
        return X11ServerStatus(
            running = pid != null,
            pid = pid,
            socketPath = socket.absolutePath,
            socketReady = socket.exists(),
            disabled = isDisabled(context),
            log = logTail(context),
        )
    }

    /**
     * Starts the server unless it is already running. Returns the pid of the live server or
     * null when the server could not be started.
     */
    fun ensureRunning(context: Context): Int? {
        readPid(context)?.let { return it }
        disabledFile(context).delete()
        startServer(context)?.let { return it }
        return null
    }

    /** Watchdog variant: respects an explicit user stop. */
    fun ensureRunningIfEnabled(context: Context): Int? {
        if (isDisabled(context)) return readPid(context)
        return ensureRunning(context)
    }

    private fun startServer(context: Context): Int? {
        val filesDir = context.filesDir
        val xkbDir = File(filesDir, "xkb")
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
        val homeDir = File(filesDir, "home").apply { mkdirs() }

        // L-52: use a stamp file so we skip extracting 2,000+ XKB files on every launch
        val xkbStamp = File(filesDir, "xkb.stamp")
        if (!xkbStamp.isFile || !xkbDir.isDirectory) {
            extractXkb(context, xkbDir)
            xkbStamp.writeText("ok")
        }

        val socket = File(socketPath(context))
        socket.parentFile?.mkdirs()
        if (socket.exists()) socket.delete()

        val pidFile = pidFile(context)
        pidFile.delete()

        val command = "echo \$\$ > '${pidFile.absolutePath}' && " +
            "exec /system/bin/app_process -Xnoimage-dex2oat / " +
            "--nice-name=$SERVER_PROCESS_NAME " +
            "$SERVER_ARGUMENTS"

        runCatching {
            ProcessBuilder("/system/bin/sh", "-c", command).apply {
                directory(filesDir)
                environment()["CLASSPATH"] = context.applicationInfo.sourceDir
                environment()["TMPDIR"] = tmpDir.absolutePath
                environment()["XKB_CONFIG_ROOT"] = xkbDir.absolutePath
                environment()["HOME"] = homeDir.absolutePath
                environment()["DISPLAY"] = DISPLAY
                redirectErrorStream(true)
                redirectOutput(logFile(context))
            }.start()
        }.onFailure { return null }

        // L-52: poll at 50ms intervals instead of 120ms for faster detection
        val deadline = System.currentTimeMillis() + SOCKET_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val pid = readPid(context)
            if (pid != null && File(socketPath(context)).exists()) return pid
            Thread.sleep(50)
        }
        return readPid(context)
    }

    /** Stops our own server instance. Never touches a foreign Termux:X11 server. */
    fun stop(context: Context) {
        val pid = pidFile(context).takeIf { it.isFile }
            ?.readText()?.trim()?.toIntOrNull()
        if (pid != null && isServerProcess(pid)) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-TERM", pid.toString())).waitFor()
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline && isServerProcess(pid)) {
                Thread.sleep(100)
            }
            if (isServerProcess(pid)) {
                runCatching { Process.killProcess(pid) }
            }
        }
        pidFile(context).delete()
        File(socketPath(context)).delete()
        disabledFile(context).writeText("stopped")
    }

    fun restart(context: Context): Int? {
        stop(context)
        disabledFile(context).delete()
        return ensureRunning(context)
    }

    fun logTail(context: Context, lines: Int = 30): String {
        val file = logFile(context)
        if (!file.isFile) return ""
        return runCatching {
            file.readLines().takeLast(lines).joinToString("\n")
        }.getOrDefault("")
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
