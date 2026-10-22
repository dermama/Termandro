package com.deckos.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deckos.core.ui.theme.DeckBackground
import com.deckos.core.ui.theme.DeckCyan
import com.deckos.core.ui.theme.DeckMuted
import com.deckos.core.ui.theme.DeckOutline
import com.deckos.core.ui.theme.DeckSurface

private data class CoreStatus(
    val title: String,
    val detail: String,
    val ready: Boolean,
)

private val statuses = listOf(
    CoreStatus(
        title = "Core scaffold",
        detail = "Android + Compose shell is ready",
        ready = true,
    ),
    CoreStatus(
        title = "Terminal",
        detail = "Termux terminal-view bridge",
        ready = true,
    ),
    CoreStatus(
        title = "X11 surface",
        detail = "Lorie X11 bridge is ready",
        ready = true,
    ),
    CoreStatus(
        title = "Desktop host",
        detail = "Phase 4: DroidDesk fast path",
        ready = false,
    ),
    CoreStatus(
        title = "Game mode",
        detail = "Phase 5A: DroidDeck engine",
        ready = false,
    ),
)

@Composable
fun DeckOSScreen(
    onOpenTerminal: () -> Unit,
    onOpenX11: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeckBackground)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "DeckOS",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = DeckCyan,
            )
            Text(
                text = "Light Linux core for Android",
                style = MaterialTheme.typography.titleMedium,
                color = DeckMuted,
            )

            Spacer(Modifier.height(12.dp))

            statuses.forEach { status ->
                StatusCard(status)
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "arm64-v8a  ·  minSdk 28  ·  targetSdk 28",
                style = MaterialTheme.typography.labelMedium,
                color = DeckMuted,
            )
        }

        Button(
            onClick = onOpenTerminal,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = DeckCyan,
                contentColor = DeckBackground,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = "Open terminal",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        OutlinedButton(
            onClick = onOpenX11,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckCyan),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = "Open X11 desktop",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(status: CoreStatus) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DeckOutline, RoundedCornerShape(20.dp)),
        color = DeckSurface,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(
                            color = if (status.ready) DeckCyan else Color(0xFF3A4A5E),
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = status.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = status.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = DeckMuted,
            )
        }
    }
}
