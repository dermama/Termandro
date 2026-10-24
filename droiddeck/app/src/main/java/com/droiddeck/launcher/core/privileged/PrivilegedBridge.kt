package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
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

/** Rich status snapshot exposed to the UI panel via StateFlow. */
data class PrivilegedStatus(
    val mode: PrivilegedMode,
    val daemonUid: Int = -1,
    val daemonPid: Int = -1,
    val shizukuVersion: Int = -1,
    val phantomDisabled: Boolean = false,
    val lastError: String? = null,
) {
    val isActive get() = mode != PrivilegedMode.NONE
    val modeLabel get() = when (mode) {
        PrivilegedMode.EMBEDDED_DAEMON -> "Embedded Daemon (UID $daemonUid)"
        PrivilegedMode.SHIZUKU_APP     -> "Shizuku v$shizukuVersion"
        PrivilegedMode.WIRELESS_ADB    -> "Wireless ADB"
        PrivilegedMode.NONE            -> "Not Active"
    }
    val statusIcon get() = if (isActive) "✅" else "⚠️"
    val shortLabel get() = when (mode) {
        PrivilegedMode.EMBEDDED_DAEMON -> "Daemon"
        PrivilegedMode.SHIZUKU_APP     -> "Shizuku"
        PrivilegedMode.WIRELESS_ADB    -> "ADB"
        PrivilegedMode.NONE            -> "None"
    }
}

object PrivilegedBridge {

    private val _status = MutableStateFlow(PrivilegedStatus(PrivilegedMode.NONE))
    val statusFlow: StateFlow<PrivilegedStatus> = _status.asStateFlow()

    fun getActiveMode(): PrivilegedMode = _status.value.mode
    fun isPrivilegedAvailable(): Boolean = _status.value.isActive

    fun refresh(): PrivilegedStatus {
        val (embMode, daemonUid, daemonPid) = probeEmbedded()
        if (embMode == PrivilegedMode.EMBEDDED_DAEMON) {
            val s = PrivilegedStatus(embMode, daemonUid, daemonPid)
            _status.value = s
            return s
        }
        val (shizMode, shizVer) = probeShizuku()
        if (shizMode == PrivilegedMode.SHIZUKU_APP) {
            val s = PrivilegedStatus(shizMode, shizukuVersion = shizVer)
            _status.value = s
            return s
        }
        val s = PrivilegedStatus(PrivilegedMode.NONE)
        _status.value = s
        return s
    }

    fun tryAutoStart(context: Context) {
        if (_status.value.isActive) return
        Thread {
            try {
                if (com.droiddeck.launcher.core.WirelessAdbFix.startPrivilegedDaemonWithSavedPairing(context)) {
                    Thread.sleep(600)
                    refresh()
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun execute(command: String): PrivilegedResult {
        if (_status.value.mode == PrivilegedMode.NONE) {
            refresh()
        }
        return when (_status.value.mode) {
            PrivilegedMode.EMBEDDED_DAEMON -> executeViaEmbeddedServer(command)
            PrivilegedMode.SHIZUKU_APP     -> executeViaShizuku(command)
            else -> PrivilegedResult(-1, "No privileged backend active.", PrivilegedMode.NONE)
        }
    }

    fun disablePhantomProcesses(): Boolean {
        val cmd = "device_config set_sync_disabled_for_tests persistent && " +
            "device_config put activity_manager max_phantom_processes 2147483647 && " +
            "settings put global settings_enable_monitor_phantom_procs false && " +
            "cmd activity disable-child-process-restrictions 2>/dev/null || true"
        return if (_status.value.mode == PrivilegedMode.EMBEDDED_DAEMON)
            runEmbeddedAction("DISABLE_PHANTOM")
        else execute(cmd).exitCode == 0
    }

    fun boostAndProtectSession(targetPid: Int): Boolean {
        if (targetPid <= 0) return false
        return if (_status.value.mode == PrivilegedMode.EMBEDDED_DAEMON) {
            runEmbeddedAction("BOOST_PERFORMANCE:$targetPid")
        } else {
            val script = "echo -1000 > /proc/$targetPid/oom_score_adj 2>/dev/null || true; " +
                "renice -n -20 -p $targetPid 2>/dev/null || true; " +
                "cmd power set-fixed-performance-mode-enabled true 2>/dev/null || true"
            execute(script).exitCode == 0
        }
    }

    fun grantDroidDeckPermissions(context: Context): Boolean {
        val pkg = context.packageName
        return if (_status.value.mode == PrivilegedMode.EMBEDDED_DAEMON) {
            runEmbeddedAction("GRANT_PERMS:$pkg")
        } else {
            execute(
                "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS && " +
                "pm grant $pkg android.permission.DUMP && " +
                "pm grant $pkg android.permission.PACKAGE_USAGE_STATS"
            ).exitCode == 0
        }
    }

    fun getStarterAdbCommand(context: Context): String {
        val apkPath = context.applicationInfo.sourceDir
        return "nohup app_process -Djava.class.path=\"$apkPath\" /system/bin " +
            "--nice-name=droiddeck_privileged " +
            "com.droiddeck.launcher.core.privileged.DroidDeckPrivilegedServer > /dev/null 2>&1 &"
    }

    // ── Probing ──────────────────────────────────────────────────────────

    private data class EmbeddedProbe(val mode: PrivilegedMode, val uid: Int, val pid: Int)

    private fun probeEmbedded(): EmbeddedProbe {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DroidDeckPrivilegedServer.PORT), 400)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                writer.println("PING")
                val response = reader.readLine() ?: return EmbeddedProbe(PrivilegedMode.NONE, -1, -1)
                if (response.startsWith("PONG")) {
                    val uid = response.substringAfter("uid=", "-1").substringBefore(" ").toIntOrNull() ?: -1
                    val pid = response.substringAfter("pid=", "-1").toIntOrNull() ?: -1
                    EmbeddedProbe(PrivilegedMode.EMBEDDED_DAEMON, uid, pid)
                } else EmbeddedProbe(PrivilegedMode.NONE, -1, -1)
            }
        } catch (_: Exception) { EmbeddedProbe(PrivilegedMode.NONE, -1, -1) }
    }

    private data class ShizukuProbe(val mode: PrivilegedMode, val version: Int)

    private fun probeShizuku(): ShizukuProbe {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val alive = cls.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!alive) return ShizukuProbe(PrivilegedMode.NONE, -1)
            val granted = (cls.getMethod("checkSelfPermission").invoke(null) as? Int ?: -1) == 0
            if (!granted) return ShizukuProbe(PrivilegedMode.NONE, -1)
            val ver = runCatching { cls.getMethod("getVersion").invoke(null) as? Int ?: -1 }.getOrDefault(-1)
            ShizukuProbe(PrivilegedMode.SHIZUKU_APP, ver)
        } catch (_: Exception) { ShizukuProbe(PrivilegedMode.NONE, -1) }
    }

    // ── Execution Backends ────────────────────────────────────────────

    private fun executeViaEmbeddedServer(command: String): PrivilegedResult {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DroidDeckPrivilegedServer.PORT), 3000)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val encoded = Base64.encodeToString(command.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                writer.println("EXEC:$encoded")
                val resp = reader.readLine() ?: return PrivilegedResult(-1, "No response", PrivilegedMode.EMBEDDED_DAEMON)
                if (resp.startsWith("EXIT:")) {
                    val parts = resp.split(":")
                    val exitCode = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    val outEncoded = parts.getOrNull(2) ?: ""
                    val out = String(Base64.decode(outEncoded, Base64.DEFAULT), StandardCharsets.UTF_8)
                    PrivilegedResult(exitCode, out, PrivilegedMode.EMBEDDED_DAEMON)
                } else PrivilegedResult(-1, resp, PrivilegedMode.EMBEDDED_DAEMON)
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeViaEmbeddedServer", e)
            PrivilegedResult(-1, "Daemon error: ${e.message}", PrivilegedMode.EMBEDDED_DAEMON)
        }
    }

    private fun executeViaShizuku(command: String): PrivilegedResult {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val method = cls.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            val process = method.invoke(null, arrayOf("/system/bin/sh", "-c", command), null, null) as java.lang.Process
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            PrivilegedResult(exitCode, output, PrivilegedMode.SHIZUKU_APP)
        } catch (e: Exception) {
            Log.e(TAG, "executeViaShizuku", e)
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
                reader.readLine() == "OK"
            }
        } catch (_: Exception) { false }
    }
}
