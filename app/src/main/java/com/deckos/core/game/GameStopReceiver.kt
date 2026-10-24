package com.deckos.core.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * L-71: BroadcastReceiver that forwards ACTION_STOP from the game
 * notification to GameService.requestStop().
 *
 * This indirection is required because the notification PendingIntent
 * targets a Broadcast (not the Service directly) for compatibility with
 * Android's background start restrictions on API 31+.
 */
class GameStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GameService.ACTION_STOP) {
            Log.i("DeckOSGameStop", "Stop received from notification")
            GameService.stop(context)
        }
    }
}
