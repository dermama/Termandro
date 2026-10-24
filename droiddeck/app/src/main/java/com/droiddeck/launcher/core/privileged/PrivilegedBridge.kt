package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

private const val TAG = "DroidDeckPrivBridge"

enum class PrivilegedMode {
    EMBEDDED_DAEMON,
    SHIZUKU_APP,
    WIRELESS_ADB,
    NONE,
}

data class PrivilegedResult(
    val exitCode: Int,
    val output: String,
    val mode: PrivilegedMode,
)

object PrivilegedBridge {

    /**
     * Checks what privileged execution mode is currently active.
     */
    fun getActiveMode(): PrivilegedMode {
        if (isEmbeddedServerAlive()) {
            return PrivilegedMode.EMBEDDED_DAEMON
        }

        if (isShizukuAppAlive()) {
            return PrivilegedMode.SHIZUKU_APP
        }

        return PrivilegedMode.NONE
    }

    fun isPrivilegedAvailable(): Boolean = getActiveMode() != PrivilegedMode.NONE

    /**
     * Executes a shell command under UID 2000 (shell).
     * Routes to embedded daemon if running, or Shizuku app if installed, or fails gracefully.
     */
    fun execute(command: String): PrivilegedResult {
        val mode = getActiveMode()
        return when (mode) {
            PrivilegedMode.EMBEDDED_DAEMON -> executeViaEmbeddedServer(command)
            PrivilegedMode.SHIZUKU_APP -> executeViaShizuku(command)
            else -> PrivilegedResult(
                exitCode = -1,
                output = "No privileged daemon active. Start embedded server or Shizuku to proceed.",
                mode = PrivilegedMode.NONE,
            )
        }
    }

    /**
     * Disables Android Phantom Process Killer and child process restrictions.
     */
    fun disablePhantomProcesses(): Boolean {
        val mode = getActiveMode()
        if (mode == PrivilegedMode.EMBEDDED_DAEMON) {
            return runEmbeddedAction("DISABLE_PHANTOM")
        }

        val res = execute(
            "device_config set_sync_disabled_for_tests persistent && " +
            "device_config put activity_manager max_phantom_processes 2147483647 && " +
            "settings put global settings_enable_monitor_phantom_procs false && " +
            "cmd activity disable-child-process-restrictions 2>/dev/null || true"
        )
        return res.exitCode == 0
    }

    /**
     * Protects a live game process from Android LMK (low-memory killer) by setting OOM score to -1000.
     */
    fun boostAndProtectSession(targetPid: Int): Boolean {
        if (targetPid <= 0) return false
        val mode = getActiveMode()
        if (mode == PrivilegedMode.EMBEDDED_DAEMON) {
            return runEmbeddedAction("BOOST_PERFORMANCE:$targetPid")
        }

        val script = "echo -1000 > /proc/$targetPid/oom_score_adj 2>/dev/null || true; " +
                     "renice -n -20 -p $targetPid 2>/dev/null || true; " +
                     "cmd power set-fixed-performance-mode-enabled true 2>/dev/null || true"
        val res = execute(script)
        return res.exitCode == 0
    }

    /**
     * Grants DroidDeck privileged permissions (WRITE_SECURE_SETTINGS, DUMP, etc.).
     */
    fun grantDroidDeckPermissions(context: Context): Boolean {
        val pkg = context.packageName
        val mode = getActiveMode()
        if (mode == PrivilegedMode.EMBEDDED_DAEMON) {
            return runEmbeddedAction("GRANT_PERMS:$pkg")
        }

        val res = execute(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS && " +
            "pm grant $pkg android.permission.DUMP && " +
            "pm grant $pkg android.permission.PACKAGE_USAGE_STATS"
        )
        return res.exitCode == 0
    }

    /**
     * Returns the 1-line command to start the daemon via wireless ADB or USB ADB.
     */
    fun getStarterAdbCommand(context: Context): String {
        val apkPath = context.applicationInfo.sourceDir
        return "nohup app_process -Djava.class.path=\"$apkPath\" /system/bin --nice-name=droiddeck_privileged com.droiddeck.launcher.core.privileged.DroidDeckPrivilegedServer > /dev/null 2>&1 &"
    }

    // ── Internal Helpers ──────────────────────────────────────────────────

    private fun isEmbeddedServerAlive(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DroidDeckPrivilegedServer.PORT), 350)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                writer.println("PING")
                val response = reader.readLine()
                response != null && response.startsWith("PONG")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isShizukuAppAlive(): Boolean {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val pingMethod = shizukuClass.getMethod("pingBinder")
            val ok = pingMethod.invoke(null) as? Boolean ?: false
            if (ok) {
                val checkPermMethod = shizukuClass.getMethod("checkSelfPermission")
                val permResult = checkPermMethod.invoke(null) as? Int ?: -1
                permResult == 0 // PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun executeViaEmbeddedServer(command: String): PrivilegedResult {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DroidDeckPrivilegedServer.PORT), 3000)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))

                val encoded = Base64.encodeToString(command.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                writer.println("EXEC:$encoded")

                val resp = reader.readLine() ?: return PrivilegedResult(-1, "No response from daemon", PrivilegedMode.EMBEDDED_DAEMON)
                if (resp.startsWith("EXIT:")) {
                    val parts = resp.split(":")
                    val exitCode = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    val outputEncoded = parts.getOrNull(2) ?: ""
                    val output = String(Base64.decode(outputEncoded, Base64.DEFAULT), StandardCharsets.UTF_8)
                    PrivilegedResult(exitCode, output, PrivilegedMode.EMBEDDED_DAEMON)
                } else {
                    PrivilegedResult(-1, resp, PrivilegedMode.EMBEDDED_DAEMON)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeViaEmbeddedServer failed: ${e.message}")
            PrivilegedResult(-1, "Error: ${e.message}", PrivilegedMode.EMBEDDED_DAEMON)
        }
    }

    private fun executeViaShizuku(command: String): PrivilegedResult {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val method = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = method.invoke(
                null,
                arrayOf("/system/bin/sh", "-c", command),
                null,
                null
            ) as java.lang.Process

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            PrivilegedResult(exitCode, output, PrivilegedMode.SHIZUKU_APP)
        } catch (e: Exception) {
            Log.e(TAG, "executeViaShizuku failed: ${e.message}")
            PrivilegedResult(-1, "Shizuku error: ${e.message}", PrivilegedMode.SHIZUKU_APP)
        }
    }

    private fun runEmbeddedAction(action: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DroidDeckPrivilegedServer.PORT), 2000)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                writer.println(action)
                val resp = reader.readLine()
                resp == "OK"
            }
        } catch (_: Exception) {
            false
        }
    }
}
