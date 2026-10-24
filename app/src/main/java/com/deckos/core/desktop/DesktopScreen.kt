package com.deckos.core.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deckos.core.game.GamePhase
import com.deckos.core.ui.theme.DeckBackground
import com.deckos.core.ui.theme.DeckCyan
import com.deckos.core.ui.theme.DeckMuted
import com.deckos.core.ui.theme.DeckOutline
import com.deckos.core.ui.theme.DeckSurface

@Composable
fun DesktopScreen(
    onBack: () -> Unit,
    onDesktopReady: () -> Unit,
    viewModel: DesktopViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val gamePhase by viewModel.gamePhase.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeckBackground),
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DeckSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = DeckCyan)
            }
            Text(
                text = "Desktop",
                style = MaterialTheme.typography.titleMedium,
                color = DeckCyan,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // ── Content ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is DesktopUiState.Installing -> InstallPanel(s)

                DesktopUiState.Idle -> IdlePanel(
                    onLaunch = {
                        viewModel.launchDesktop()
                        onDesktopReady()
                    },
                    gamePhase = gamePhase,
                    onLaunchSteam = {
                        viewModel.launchSteam()
                        onDesktopReady()
                    },
                    onLaunchGame = { viewModel.launchGame() },
                    onOpenView = onDesktopReady,
                    onStopGame = { viewModel.stopGame() },
                )

                DesktopUiState.Starting -> {
                    Text(
                        text = "Starting Openbox…",
                        style = MaterialTheme.typography.titleMedium,
                        color = DeckCyan,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = DeckCyan,
                    )
                }

                is DesktopUiState.Running -> {
                    Text(
                        text = "Desktop running",
                        style = MaterialTheme.typography.titleMedium,
                        color = DeckCyan,
                    )
                    Text(
                        text = "pid ${s.pid} · DISPLAY :1",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DeckMuted,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onDesktopReady,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckCyan,
                            contentColor = DeckBackground,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open X11 View", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(12.dp))

                    // L-71/L-75: Game Mode panel
                    GameModePanel(
                        phase     = gamePhase,
                        onLaunchSteam = {
                            viewModel.launchSteam()
                            onDesktopReady()
                        },
                        onLaunchGame  = { viewModel.launchGame() },
                        onOpenView = onDesktopReady,
                        onStop    = { viewModel.stopGame() },
                    )
                }

                is DesktopUiState.Failed -> FailPanel(s, onRetry = viewModel::retry)
            }
        }
    }
}

@Composable
private fun InstallPanel(state: DesktopUiState.Installing) {
    Text(
        text = state.message,
        style = MaterialTheme.typography.titleMedium,
        color = DeckCyan,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { state.progress },
        modifier = Modifier.fillMaxWidth(),
        color = DeckCyan,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Installing Openbox + st terminal via apt. This takes a minute on first launch.",
        style = MaterialTheme.typography.bodySmall,
        color = DeckMuted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun IdlePanel(
    onLaunch: () -> Unit,
    gamePhase: GamePhase,
    onLaunchSteam: () -> Unit,
    onLaunchGame: () -> Unit,
    onOpenView: () -> Unit,
    onStopGame: () -> Unit,
) {
    Text(
        text = "Desktop ready",
        style = MaterialTheme.typography.titleLarge,
        color = DeckCyan,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Openbox + st terminal installed. Start the X11 desktop session.",
        style = MaterialTheme.typography.bodyMedium,
        color = DeckMuted,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onLaunch,
        colors = ButtonDefaults.buttonColors(
            containerColor = DeckCyan,
            contentColor = DeckBackground,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Launch desktop",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }

    Spacer(Modifier.height(16.dp))

    GameModePanel(
        phase = gamePhase,
        onLaunchSteam = onLaunchSteam,
        onLaunchGame = onLaunchGame,
        onOpenView = onOpenView,
        onStop = onStopGame,
    )
}

@Composable
private fun FailPanel(state: DesktopUiState.Failed, onRetry: () -> Unit) {
    Text(
        text = "Desktop error",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = state.message,
        style = MaterialTheme.typography.bodySmall,
        color = DeckMuted,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onRetry,
        colors = ButtonDefaults.buttonColors(
            containerColor = DeckCyan,
            contentColor = DeckBackground,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text("Retry", fontWeight = FontWeight.SemiBold)
    }
}

// ── L-71/L-75/L-77: Game Mode Panel ─────────────────────────────────────────

/**
 * Game Mode panel shown below the X11 View button when the desktop is running.
 *
 * States:
 *  IDLE/FAILED     → shows "Launch Game" button
 *  PREFLIGHT       → shows preflight spinner
 *  ACQUIRING_PACK  → shows download progress indicator
 *  SUSPENDING_DESKTOP → shows suspension message
 *  STARTING_GAME   → shows launch spinner
 *  RUNNING         → shows "Stop Game" button + status
 *  STOPPING/RESTORING_DESKTOP → shows stopping spinner
 */
@Composable
private fun GameModePanel(
    phase: GamePhase,
    onLaunchSteam: () -> Unit,
    onLaunchGame: () -> Unit,
    onOpenView: () -> Unit,
    onStop: () -> Unit,
) {
    val isActive = phase != GamePhase.IDLE && phase != GamePhase.FAILED
    val dotColor = when (phase) {
        GamePhase.RUNNING           -> DeckCyan
        GamePhase.FAILED            -> Color(0xFFFF4B4B)
        GamePhase.IDLE              -> Color(0xFF3A4A5E)
        else                        -> Color(0xFFFFA500)  // amber = transitioning
    }
    val statusText = when (phase) {
        GamePhase.IDLE              -> "Game mode ready"
        GamePhase.PREFLIGHT         -> "Running preflight checks…"
        GamePhase.ACQUIRING_PACK    -> "Downloading game pack…"
        GamePhase.SUSPENDING_DESKTOP -> "Suspending desktop…"
        GamePhase.STARTING_GAME     -> "Launching game…"
        GamePhase.RUNNING           -> "Game running"
        GamePhase.STOPPING          -> "Stopping game…"
        GamePhase.RESTORING_DESKTOP -> "Restoring desktop…"
        GamePhase.FAILED            -> "Game mode error"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DeckOutline, RoundedCornerShape(16.dp)),
        color = DeckSurface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )
                Text(
                    text = "Game Mode (Proton / Turnip KGSL)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = DeckMuted,
            )

            if (isActive && phase != GamePhase.RUNNING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = DeckCyan,
                )
            }

            if (!isActive) {
                Button(
                    onClick = onLaunchSteam,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1B2838),
                        contentColor = Color(0xFF66C0F4),
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "🎮 Launch Steam (Valve Big Picture)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    onClick = onLaunchGame,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "▶️ Run Graphics Demo",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (phase == GamePhase.RUNNING) {
                Button(
                    onClick = onOpenView,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeckCyan,
                        contentColor = DeckBackground,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "🖥️ Open Game View (X11)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF4B4B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "⏹ Stop Game",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}


