package com.deckos.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the DeckOS X11 server alive.
 *
 * The xserver is a separate process spawned with /system/bin/app_process, so it can die on
 * its own (low memory, app force stop, phantom process killer). This foreground service owns
 * the lifecycle: it starts the server, watches it and restarts it when it disappears.
 */
class X11Service : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Worker()
    private val workerThread = Thread(worker, "deckos-x11-watchdog")

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        workerThread.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        worker.wake()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.quit()
        workerThread.interrupt()
        super.onDestroy()
    }

    private inner class Worker : Runnable {
        @Volatile
        private var running = true

        fun wake() {
            running = true
        }

        fun quit() {
            running = false
        }

        override fun run() {
            while (running) {
                try {
                    X11Server.ensureRunningIfEnabled(this@X11Service)
                    mainHandler.post { updateNotification() }
                } catch (_: Exception) {
                    // The watchdog must never take the service down.
                }
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DeckOS X11",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the DeckOS X11 server running"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val status = X11Server.status(this)
        val text = when {
            status.running -> "X11 server running on ${X11Server.DISPLAY} (pid ${status.pid})"
            status.disabled -> "X11 server stopped"
            else -> "Starting X11 server..."
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DeckOS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "deckos_x11"
        const val NOTIFICATION_ID = 4711
        const val WATCHDOG_INTERVAL_MS = 10_000L
    }
}
