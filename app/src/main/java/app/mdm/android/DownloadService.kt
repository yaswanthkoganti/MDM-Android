// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <YOUR NAME>. Part of Mister DM for Android; see LICENSE.

package app.mdm.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.mdm.android.data.Status
import app.mdm.android.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val CHANNEL = "downloads"
private const val NOTIF_ID = 1
const val ACTION_PAUSE_ALL = "app.mdm.android.PAUSE_ALL"

/** Keeps the process alive while downloads run, with a progress notification and a Pause-all action. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, build("Starting…", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val mgr = (application as MdmApp).manager
        scope.launch {
            mgr.items.collectLatest { list ->
                val active = list.filter { it.status == Status.DOWNLOADING }
                if (active.isEmpty()) {
                    delay(4000)   // grace period so back-to-back downloads don't flap the service
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val known = active.filter { it.totalBytes > 0 }
                    val total = known.sumOf { it.totalBytes }
                    val done = known.sumOf { it.downloadedBytes }
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    val speed = active.sumOf { it.speedBps }
                    val text = "${active.size} downloading • ${Fmt.speed(speed)}"
                    nm.notify(NOTIF_ID, build(text, pct, total <= 0))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) (application as MdmApp).manager.stopAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(text: String, pct: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mister Download Manager")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Pause all", pause)
            .setProgress(100, pct, indeterminate)
            .build()
    }
}

object Notifier {
    private const val DONE_CHANNEL = "completed"

    fun complete(ctx: Context, name: String, uri: Uri, mime: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT))
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pi = PendingIntent.getActivity(ctx, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, DONE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete")
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(ctx).notify(uri.hashCode(), n)
        } catch (e: SecurityException) {
            // Notification permission denied: nothing to do.
        }
    }
}
