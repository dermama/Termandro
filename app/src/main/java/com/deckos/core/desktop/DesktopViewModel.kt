package com.deckos.core.desktop

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deckos.core.game.GameConfig
import com.deckos.core.game.GamePhase
import com.deckos.core.game.GameService
import com.deckos.core.game.PerfProfileSelector
import com.deckos.core.runtime.LinuxRuntime
import com.deckos.core.runtime.SessionManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface DesktopUiState {
    /** Desktop packages not yet installed. */
    data class Installing(val progress: Float, val message: String) : DesktopUiState

    /** Desktop is installed but not running. */
    data object Idle : DesktopUiState

    /** openbox-session is launching. */
    data object Starting : DesktopUiState

    /** openbox is running; [pid] is the openbox process PID. */
    data class Running(val pid: Int) : DesktopUiState

    /** A non-recoverable error occurred. */
    data class Failed(val message: String) : DesktopUiState
}

class DesktopViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = LinuxRuntime(application)
    private val sessionManager = SessionManager(application)

    private val _state = MutableStateFlow<DesktopUiState>(
        when {
            !runtime.isInstalled() -> DesktopUiState.Failed("Bootstrap not installed")
            runtime.isDesktopInstalled() -> {
                val currentPid = sessionManager.status().desktopPid
                if (currentPid != null) {
                    DesktopUiState.Running(currentPid)
                } else {
                    DesktopUiState.Idle
                }
            }
            else -> DesktopUiState.Installing(0f, "Preparing desktop packages")
        },
    )
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    // ── L-70/L-71: Game Mode state ────────────────────────────────────────
    val gamePhase: StateFlow<GamePhase> = GameService.currentPhase

    init {
        if (_state.value is DesktopUiState.Installing) {
            installDesktop()
        }
        // Live polling: detect when Openbox dies unexpectedly and reset to Idle
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2_000)
                val current = _state.value
                if (current is DesktopUiState.Running) {
                    val alive = sessionManager.status().desktopPid != null
                    if (!alive) {
                        Log.w("DeckOSDesktop", "Openbox process died unexpectedly — resetting to Idle")
                        _state.value = DesktopUiState.Idle
                    }
                }
            }
        }
    }

    fun retry() {
        if (_state.value is DesktopUiState.Failed) {
            _state.value = DesktopUiState.Installing(0f, "Retrying…")
            installDesktop()
        }
    }

    /** Install Openbox + desktop packages on a background thread. */
    private fun installDesktop() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                runtime.installDesktopPackages { progress, message ->
                    _state.value = DesktopUiState.Installing(progress, message)
                }
            }.onSuccess {
                _state.value = DesktopUiState.Idle
            }.onFailure { e ->
                Log.e("DeckOSDesktop", "Desktop install failed", e)
                _state.value = DesktopUiState.Failed(e.message ?: "Desktop installation failed")
            }
        }
    }

    /**
     * Start the full desktop session via unified SessionManager.
     * Auto-repairs broken packages, starts X11, starts Openbox, and verifies readiness.
     */
    fun launchDesktop() {
        if (_state.value is DesktopUiState.Running || _state.value is DesktopUiState.Starting) return
        _state.value = DesktopUiState.Starting
        viewModelScope.launch(Dispatchers.IO) {
            val started = sessionManager.start { progress, message ->
                _state.value = DesktopUiState.Installing(progress, message)
            }
            if (started) {
                val status = sessionManager.status()
                _state.value = DesktopUiState.Running(status.desktopPid ?: -1)
            } else {
                val err = sessionManager.status().lastError ?: "Could not start desktop (check openbox.log)"
                _state.value = DesktopUiState.Failed(err)
            }
        }
    }

    /** Stop the desktop session gracefully via SessionManager. */
    fun stopDesktop() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.stop()
            _state.value = DesktopUiState.Idle
        }
    }

    /** Re-check whether desktop is still running. */
    fun checkRunning() {
        val current = _state.value
        if (current is DesktopUiState.Running) {
            val status = sessionManager.status()
            if (status.state != SessionManager.State.RUNNING) {
                _state.value = DesktopUiState.Idle
            }
        }
    }

    // ── L-71/L-73/L-74: Game Mode launch ─────────────────────────────────

    /**
     * Launch a game session via GameService.
     *
     * Uses PerfProfileSelector to auto-pick the right resolution for this device
     * (entry-level → 720p/30fps, mid → 1080p/60fps, flagship → native/uncapped).
     *
     * @param execPath   Absolute path to the game binary, or empty string for test mode.
     * @param gameName   Human-readable game name shown in the overlay and notification.
     * @param runtimeHint "native" | "fex" | "proton".
     */
    fun launchGame(
        execPath: String = "",
        gameName: String = "DeckOS Game",
        runtimeHint: String = "native",
    ) {
        if (gamePhase.value != GamePhase.IDLE && gamePhase.value != GamePhase.FAILED) {
            Log.w("DeckOSDesktop", "Game already active (phase=${gamePhase.value})")
            return
        }

        val profile = PerfProfileSelector.fromEnvOrAuto()
        val app: Application = getApplication()
        val defaultFile = File(app.filesDir, "usr/bin/featherpad")
        val defaultExec = if (defaultFile.exists()) defaultFile.absolutePath else ""
        val targetExec = if (execPath.isNotEmpty()) execPath else defaultExec
        val targetName = if (gameName.isNotEmpty() && gameName != "DeckOS Game") gameName else "DeckOS Graphics Demo"

        val config = GameConfig(
            gameName         = targetName,
            execPath         = targetExec,
            runtimeHint      = runtimeHint,
            resolutionProfile = profile.resolution,
            fsrRatio         = profile.fsrRatio,
            fpsCap           = profile.fpsCap,
            showOverlay      = true,
        )

        viewModelScope.launch(Dispatchers.Main) {
            Log.i("DeckOSDesktop", "Launching game: $targetName via profile=${profile.displayName}")
            GameService.start(getApplication(), config)
        }
    }

    /** Request a graceful stop of the active game session. */
    fun stopGame() {
        viewModelScope.launch(Dispatchers.Main) {
            GameService.stop(getApplication())
        }
    }
}
