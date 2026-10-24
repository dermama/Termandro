package com.deckos.core.packs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.deckos.core.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps pack downloads and extractions alive even when
 * the user minimises the app or the screen turns off.
 *
 * Start via [PackDownloadService.start]; cancel via [PackDownloadService.stop].
 */
class PackDownloadService : Service() {

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Preparing…", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packId = intent?.getStringExtra(EXTRA_PACK_ID) ?: run {
            stopSelf(); return START_NOT_STICKY
        }

        val pack = ExtensionPackCatalog.getPackById(packId) ?: run {
            Log.e(TAG, "Unknown pack id: $packId")
            stopSelf(); return START_NOT_STICKY
        }

        scope.launch {
            val manager = ExtensionPackManager(applicationContext)
            manager.install(pack) { status ->
                when (status) {
                    is PackStatus.Downloading -> {
                        val pct = (status.progress * 100).toInt()
                        notify(status.message, pct)
                        // Broadcast progress so UI can update even in background
                        sendBroadcast(Intent(ACTION_PROGRESS).apply {
                            putExtra(EXTRA_PACK_ID, packId)
                            putExtra(EXTRA_STATUS,  "downloading")
                            putExtra(EXTRA_PROGRESS, pct)
                            putExtra(EXTRA_MESSAGE,  status.message)
                        })
                    }
                    is PackStatus.Verifying -> {
                        notify("Verifying checksum…", -1)
                        sendBroadcast(Intent(ACTION_PROGRESS).apply {
                            putExtra(EXTRA_PACK_ID, packId)
                            putExtra(EXTRA_STATUS,  "verifying")
                        })
                    }
                    is PackStatus.Extracting -> {
                        val pct = (status.progress * 100).toInt()
                        notify("Extracting… $pct%", pct)
                        sendBroadcast(Intent(ACTION_PROGRESS).apply {
                            putExtra(EXTRA_PACK_ID, packId)
                            putExtra(EXTRA_STATUS,  "extracting")
                            putExtra(EXTRA_PROGRESS, pct)
                        })
                    }
                    is PackStatus.Installed -> {
                        notify("✓ ${pack.displayName} ready!", 100)
                        sendBroadcast(Intent(ACTION_PROGRESS).apply {
                            putExtra(EXTRA_PACK_ID, packId)
                            putExtra(EXTRA_STATUS,  "installed")
                        })
                        stopSelf()
                    }
                    is PackStatus.Failed -> {
                        notify("✗ Failed: ${status.reason}", 0)
                        sendBroadcast(Intent(ACTION_PROGRESS).apply {
                            putExtra(EXTRA_PACK_ID, packId)
                            putExtra(EXTRA_STATUS,  "failed")
                            putExtra(EXTRA_MESSAGE,  status.reason)
                        })
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }

        return START_REDELIVER_INTENT   // restart with same intent if killed
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Notification helpers ─────────────────────────────────────────────────

    private fun notify(text: String, progress: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, progress))
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pack Downloads", NotificationManager.IMPORTANCE_LOW)
                    .also { it.description = "Background pack installation progress" }
            )
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DeckOS — Installing Pack")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .apply {
                if (progress in 0..100) {
                    setProgress(100, progress, progress == 0)
                }
            }
            .build()
    }

    companion object {
        private const val TAG        = "PackDownloadService"
        private const val NOTIF_ID   = 7751
        private const val CHANNEL_ID = "deckos_pack_dl"

        const val ACTION_PROGRESS = "com.deckos.core.PACK_PROGRESS"
        const val EXTRA_PACK_ID   = "pack_id"
        const val EXTRA_STATUS    = "status"
        const val EXTRA_PROGRESS  = "progress"
        const val EXTRA_MESSAGE   = "message"

        fun start(context: Context, packId: String) {
            val i = Intent(context, PackDownloadService::class.java)
                .putExtra(EXTRA_PACK_ID, packId)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PackDownloadService::class.java))
        }
    }
}
