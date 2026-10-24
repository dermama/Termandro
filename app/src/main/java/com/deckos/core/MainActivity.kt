package com.deckos.core

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.deckos.core.desktop.DesktopScreen
import com.deckos.core.terminal.TerminalScreen
import com.deckos.core.ui.theme.DeckOSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(this, Intent(this, X11Service::class.java))

        setContent {
            DeckOSTheme {
                var terminalOpen by rememberSaveable { mutableStateOf(false) }
                var desktopOpen  by rememberSaveable { mutableStateOf(false) }

                val runtime = androidx.compose.runtime.remember { com.deckos.core.runtime.LinuxRuntime(this@MainActivity) }
                val sessionManager = androidx.compose.runtime.remember { com.deckos.core.runtime.SessionManager(this@MainActivity) }

                val hwInfo by produceState(
                    initialValue = com.deckos.core.game.PerfProfileSelector.DeviceHardwareInfo(
                        model = android.os.Build.MODEL,
                        totalRamMb = 0L,
                        hasAdrenoGpu = false,
                        profile = com.deckos.core.game.PerfProfile.ULTRA,
                    )
                ) {
                    value = withContext(Dispatchers.IO) { com.deckos.core.game.PerfProfileSelector.getHardwareInfo() }
                }

                val desktopStatus by produceState(initialValue = sessionManager.status()) {
                    while (true) {
                        value = withContext(Dispatchers.IO) { sessionManager.status() }
                        delay(STATUS_REFRESH_MS)
                    }
                }

                val server by produceState(initialValue = X11Server.status(this@MainActivity)) {
                    while (true) {
                        value = withContext(Dispatchers.IO) { X11Server.status(this@MainActivity) }
                        delay(STATUS_REFRESH_MS)
                    }
                }
                val client by produceState(initialValue = X11TestClient.status(this@MainActivity)) {
                    while (true) {
                        value = withContext(Dispatchers.IO) { X11TestClient.status(this@MainActivity) }
                        delay(STATUS_REFRESH_MS)
                    }
                }

                when {
                    terminalOpen -> TerminalScreen(onBack = { terminalOpen = false })

                    desktopOpen -> DesktopScreen(
                        onBack = { desktopOpen = false },
                        // When Openbox is launched, flip back to home then open X11 Lorie view.
                        onDesktopReady = {
                            desktopOpen = false
                            openX11()
                        },
                    )

                    else -> DeckOSScreen(
                        serverStatus = server,
                        clientStatus = client,
                        desktopStatus = desktopStatus,
                        hwInfo = hwInfo,
                        isDesktopInstalled = runtime.isDesktopInstalled(),
                        onOpenTerminal  = { terminalOpen = true },
                        onOpenDesktop   = { desktopOpen  = true },
                        onOpenX11       = ::openX11,
                        onTestWindow    = ::testWindow,
                        onRestartX11    = ::restartX11,
                        onStopX11       = ::stopX11,
                    )
                }
            }
        }
    }

    private fun openX11() {
        lifecycleScope.launch {
            val pid = withContext(Dispatchers.IO) { X11Server.ensureRunning(this@MainActivity) }
            if (pid == null) {
                toast("X11 could not be started")
                return@launch
            }
            startActivity(Intent(this@MainActivity, com.termux.x11.MainActivity::class.java))
        }
    }

    private fun testWindow() {
        lifecycleScope.launch {
            val server = withContext(Dispatchers.IO) { X11Server.ensureRunning(this@MainActivity) }
            if (server == null) {
                toast("X11 server is not running")
                return@launch
            }
            val pid = withContext(Dispatchers.IO) { X11TestClient.start(this@MainActivity) }
            toast(if (pid != null) "Test window opened (pid $pid)" else "Test window failed to start")
        }
    }

    private fun restartX11() {
        lifecycleScope.launch {
            val pid = withContext(Dispatchers.IO) {
                X11TestClient.stop(this@MainActivity)
                X11Server.restart(this@MainActivity)
            }
            toast(if (pid != null) "X11 restarted (pid $pid)" else "X11 restart failed")
        }
    }

    private fun stopX11() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                X11TestClient.stop(this@MainActivity)
                X11Server.stop(this@MainActivity)
            }
            toast("X11 stopped")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val STATUS_REFRESH_MS = 2_000L
    }
}
