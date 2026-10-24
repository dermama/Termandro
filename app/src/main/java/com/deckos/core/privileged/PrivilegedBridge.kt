package com.deckos.core.privileged

import android.content.Context
import android.content.pm.PackageManager
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
import rikka.shizuku.Shizuku

private const val TAG = "DeckOSPrivBridge"

enum class PrivilegedMode {
    EMBEDDED_DAEMON,
    SHIZUKU_APP,
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
            PrivilegedMode.NONE -> PrivilegedResult(
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
            "settings put global settings_enable_monitor_phantom_procs false"
        )
        return res.exitCode == 0
    }

    /**
     * Grants DeckOS privileged permissions (WRITE_SECURE_SETTINGS, DUMP, etc.).
     */
    fun grantDeckOSPermissions(context: Context): Boolean {
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
     * Generates a ready-to-run shell script to start the embedded daemon via ADB.
     */
    fun generateStarterScript(context: Context): File {
        val script = File(context.filesDir, "start_privileged.sh")
        val apkPath = context.applicationInfo.sourceDir

        val content = """
            #!/system/bin/sh
            # DeckOS Privileged Daemon Starter
            APK="$apkPath"
            echo "Starting DeckOS Privileged Server from: ${'$'}APK"
            nohup app_process -Djava.class.path="${'$'}APK" /system/bin --nice-name=deckos_privileged com.deckos.core.privileged.DeckOSPrivilegedServer > /dev/null 2>&1 &
            sleep 1
            echo "DeckOS Privileged Server started."
        """.trimIndent()

        script.writeText(content)
        script.setExecutable(true, false)
        return script
    }

    /**
     * Returns the 1-line command to start the daemon via wireless ADB or USB ADB.
     */
    fun getStarterAdbCommand(context: Context): String {
        val apkPath = context.applicationInfo.sourceDir
        return "app_process -Djava.class.path=\"$apkPath\" /system/bin --nice-name=deckos_privileged com.deckos.core.privileged.DeckOSPrivilegedServer &"
    }

    // ── Internal Helpers ──────────────────────────────────────────────────

    private fun isEmbeddedServerAlive(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DeckOSPrivilegedServer.PORT), 500)
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
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    private fun executeViaEmbeddedServer(command: String): PrivilegedResult {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DeckOSPrivilegedServer.PORT), 2000)
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
            val method = Shizuku::class.java.getDeclaredMethod(
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
                socket.connect(InetSocketAddress("127.0.0.1", DeckOSPrivilegedServer.PORT), 1500)
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
