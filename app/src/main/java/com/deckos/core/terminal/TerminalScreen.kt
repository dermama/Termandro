package com.deckos.core.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deckos.core.ui.theme.DeckBackground
import com.deckos.core.ui.theme.DeckCyan
import com.deckos.core.ui.theme.DeckSurface
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    viewModel: TerminalSessionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val close = {
        viewModel.endSession()
        onBack()
    }

    BackHandler(onBack = close)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeckBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DeckSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = close) {
                Text("Back", color = DeckCyan)
            }
            Text(
                text = "Terminal",
                style = MaterialTheme.typography.titleMedium,
                color = DeckCyan,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        when (val current = state) {
            is LinuxUiState.Installing -> InstallPanel(current)
            is LinuxUiState.Failed -> FailurePanel(current, onRetry = viewModel::retry)
            LinuxUiState.Ready -> TerminalContent(viewModel)
        }
    }
}

@Composable
private fun TerminalContent(viewModel: TerminalSessionViewModel) {
    val session = remember { viewModel.session() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            TerminalView(context, null).apply {
                setBackgroundColor(Color.BLACK)
                setTextSize(14)
                setTypeface(Typeface.MONOSPACE)
                setTerminalViewClient(DeckTerminalViewClient())
                isFocusable = true
                isFocusableInTouchMode = true
                attachSession(session)
                requestFocus()
            }
        },
    )
}

@Composable
private fun InstallPanel(state: LinuxUiState.Installing) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Setting up Linux",
            style = MaterialTheme.typography.titleLarge,
            color = DeckCyan,
        )
        Spacer(modifier = Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth(),
            color = DeckCyan,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "The first setup downloads about 33 MB.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FailurePanel(state: LinuxUiState.Failed, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Linux setup failed",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRetry) {
            Text("Try again")
        }
    }
}

private class DeckTerminalViewClient : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent) = Unit

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) = Unit

    override fun logWarn(tag: String, message: String) = Unit

    override fun logInfo(tag: String, message: String) = Unit

    override fun logDebug(tag: String, message: String) = Unit

    override fun logVerbose(tag: String, message: String) = Unit

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit

    override fun logStackTrace(tag: String, e: Exception) = Unit
}
