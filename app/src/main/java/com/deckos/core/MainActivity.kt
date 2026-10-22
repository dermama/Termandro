package com.deckos.core

import android.os.Bundle
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
                    DeckOSScreen(onOpenTerminal = { terminalOpen = true })
                }
            }
        }
    }
}
