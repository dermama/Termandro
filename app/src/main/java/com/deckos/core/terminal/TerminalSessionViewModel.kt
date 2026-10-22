package com.deckos.core.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deckos.core.runtime.LinuxRuntime
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LinuxUiState {
    data class Installing(val progress: Float, val message: String) : LinuxUiState
    data class Failed(val message: String) : LinuxUiState
    data object Ready : LinuxUiState
}

class TerminalSessionViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = LinuxRuntime(application)
    private var session: TerminalSession? = null

    private val _state = MutableStateFlow<LinuxUiState>(
        if (runtime.isInstalled()) {
            LinuxUiState.Ready
        } else {
            LinuxUiState.Installing(0f, "Preparing Linux")
        },
    )
    val state: StateFlow<LinuxUiState> = _state.asStateFlow()

    init {
        if (runtime.isInstalled()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { runtime.prepare() }
                    .onFailure { Log.w("DeckOSLinux", "Could not refresh runtime files", it) }
            }
        } else {
            install()
        }
    }

    fun retry() {
        if (_state.value is LinuxUiState.Failed) {
            install()
        }
    }

    fun session(): TerminalSession {
        session?.let { return it }

        val app = getApplication<Application>()
        return TerminalSession(
            runtime.shellPath(),
            runtime.homePath(),
            arrayOf("bash", "-l"),
            runtime.environment(),
            5000,
            DeckTerminalSessionClient(app),
        ).also { session = it }
    }

    fun endSession() {
        session?.finishIfRunning()
        session = null
    }

    private fun install() {
        _state.value = LinuxUiState.Installing(0f, "Preparing Linux")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                runtime.ensureInstalled { progress, message ->
                    _state.value = LinuxUiState.Installing(progress, message)
                }
            }.onSuccess {
                _state.value = LinuxUiState.Ready
            }.onFailure { error ->
                Log.e("DeckOSLinux", "Linux install failed", error)
                _state.value = LinuxUiState.Failed(
                    error.message ?: "Could not install the Linux environment",
                )
            }
        }
    }

    override fun onCleared() {
        endSession()
        super.onCleared()
    }
}

private class DeckTerminalSessionClient(
    private val app: Application,
) : TerminalSessionClient {

    private val clipboard: ClipboardManager
        get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun onTextChanged(changedSession: TerminalSession) = Unit

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("DeckOS terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(app)
            ?.toString()
            ?: return
        if (text.isEmpty()) return

        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        Log.i("DeckOS", "terminal shell pid=$pid")
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "terminal stack trace", e)
    }
}
