package com.deckos.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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

import androidx.compose.foundation.clickable
import com.deckos.core.game.PerfProfile
import com.deckos.core.game.PerfProfileSelector
import com.deckos.core.runtime.SessionManager

data class CoreStatus(
    val title: String,
    val detail: String,
    val ready: Boolean,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun DeckOSScreen(
    serverStatus: X11ServerStatus,
    clientStatus: X11ClientStatus,
    desktopStatus: SessionManager.SessionStatus,
    hwInfo: PerfProfileSelector.DeviceHardwareInfo,
    isDesktopInstalled: Boolean,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenX11: () -> Unit,
    onTestWindow: () -> Unit,
    onRestartX11: () -> Unit,
    onStopX11: () -> Unit,
) {
    val isDesktopRunning = desktopStatus.state == SessionManager.State.RUNNING || desktopStatus.desktopPid != null
    val statuses = listOf(
        CoreStatus(
            title = "Core scaffold",
            detail = "Android + Compose shell is ready",
            ready = true,
        ),
        CoreStatus(
            title = "Terminal",
            detail = "Termux terminal-view bridge (Tap to open)",
            ready = true,
            onClick = onOpenTerminal,
        ),
        CoreStatus(
            title = "Desktop host",
            detail = when {
                desktopStatus.desktopPid != null -> "Openbox running (pid ${desktopStatus.desktopPid})"
                isDesktopInstalled -> "Ready to launch Openbox session"
                else -> "Bootstrap and desktop ready to install"
            },
            ready = isDesktopInstalled || desktopStatus.desktopPid != null,
            onClick = onOpenDesktop,
        ),
        CoreStatus(
            title = "Game mode",
            detail = "Profile: ${hwInfo.profile.displayName} · Adaptive scaling",
            ready = true,
            onClick = onOpenDesktop,
        ),
    )

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

            // Hardware capability & adaptive tier badge
            DeviceHardwareBadge(hwInfo)

            Spacer(Modifier.height(4.dp))

            X11Card(
                serverStatus = serverStatus,
                clientStatus = clientStatus,
                onTestWindow = onTestWindow,
                onRestartX11 = onRestartX11,
                onStopX11 = onStopX11,
            )

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

        // Primary CTA — dynamically adapts to whether desktop is already running
        Button(
            onClick = {
                if (isDesktopRunning) {
                    onOpenX11()
                } else {
                    onOpenDesktop()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = DeckCyan,
                contentColor = DeckBackground,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = if (isDesktopRunning) "Open running desktop" else "Launch desktop",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        OutlinedButton(
            onClick = onOpenTerminal,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckCyan),
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
            onClick = onOpenDesktop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckCyan),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = "Desktop controls & Game Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun X11Card(
    serverStatus: X11ServerStatus,
    clientStatus: X11ClientStatus,
    onTestWindow: () -> Unit,
    onRestartX11: () -> Unit,
    onStopX11: () -> Unit,
) {
    val headline = when {
        serverStatus.running -> "X11 server running"
        serverStatus.disabled -> "X11 server stopped"
        else -> "X11 server starting"
    }
    val detail = when {
        serverStatus.running -> "display ${X11Server.DISPLAY}  ·  pid ${serverStatus.pid}  ·  " +
            if (serverStatus.socketReady) "socket ready" else "socket pending"
        serverStatus.disabled -> "Stopped on request"
        else -> "Waiting for the server process"
    }
    val clientLine = when {
        clientStatus.running -> "Test window open (pid ${clientStatus.pid})"
        else -> "No test window"
    }

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
                            color = if (serverStatus.running) DeckCyan else Color(0xFF3A4A5E),
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = DeckMuted,
            )
            Text(
                text = clientLine,
                style = MaterialTheme.typography.bodyMedium,
                color = DeckMuted,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallAction("Test window", onTestWindow)
                SmallAction("Restart", onRestartX11)
                SmallAction("Stop", onStopX11)
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckCyan),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DeviceHardwareBadge(info: PerfProfileSelector.DeviceHardwareInfo) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DeckOutline, RoundedCornerShape(16.dp)),
        color = Color(0xFF13171F),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${info.model} · ${if (info.totalRamMb > 0) "${info.totalRamMb / 1024} GB RAM" else "Detected"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (info.hasAdrenoGpu) "Adreno GPU detected (/dev/kgsl)" else "Standard GPU (fallback)",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckMuted,
                )
            }
            Surface(
                color = when (info.profile) {
                    PerfProfile.ULTRA -> DeckCyan.copy(alpha = 0.15f)
                    PerfProfile.NORMAL -> Color(0xFF388E3C).copy(alpha = 0.15f)
                    PerfProfile.LIGHT -> Color(0xFFF57C00).copy(alpha = 0.15f)
                },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = info.profile.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (info.profile) {
                        PerfProfile.ULTRA -> DeckCyan
                        PerfProfile.NORMAL -> Color(0xFF81C784)
                        PerfProfile.LIGHT -> Color(0xFFFFB74D)
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: CoreStatus) {
    Surface(
        onClick = { status.onClick?.invoke() },
        enabled = status.onClick != null,
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
                if (status.onClick != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "→",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DeckCyan,
                    )
                }
            }
            Text(
                text = status.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = DeckMuted,
            )
        }
    }
}
