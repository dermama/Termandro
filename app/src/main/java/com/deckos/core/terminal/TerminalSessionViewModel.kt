package com.deckos.core.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class TerminalSessionViewModel(application: Application) : AndroidViewModel(application) {

    private var session: TerminalSession? = null

    fun session(): TerminalSession {
        session?.let { return it }

        val app = getApplication<Application>()
        val home = app.filesDir.absolutePath
        val cache = app.cacheDir.absolutePath
        val env = arrayOf(
            "HOME=$home",
            "TMPDIR=$cache",
            "PATH=/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
        )

        return TerminalSession(
            "/system/bin/sh",
            home,
            arrayOf("-i"),
            env,
            5000,
            DeckTerminalSessionClient(app),
        ).also { session = it }
    }

    fun endSession() {
        session?.finishIfRunning()
        session = null
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
