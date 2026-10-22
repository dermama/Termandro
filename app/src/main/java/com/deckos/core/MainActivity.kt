package com.deckos.core

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.deckos.core.terminal.TerminalScreen
import com.deckos.core.ui.theme.DeckOSTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeckOSTheme {
                var terminalOpen by rememberSaveable { mutableStateOf(false) }
                if (terminalOpen) {
                    TerminalScreen(onBack = { terminalOpen = false })
                } else {
                    DeckOSScreen(
                        onOpenTerminal = { terminalOpen = true },
                        onOpenX11 = ::openX11,
                    )
                }
            }
        }
    }

    private fun openX11() {
        runCatching {
            X11Server.ensureRunning(this)
            startActivity(Intent(this, com.termux.x11.MainActivity::class.java))
        }.onFailure {
            Toast.makeText(this, "X11 could not be started", Toast.LENGTH_SHORT).show()
        }
    }
}
