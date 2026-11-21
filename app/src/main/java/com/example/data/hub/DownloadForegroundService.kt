package com.example.data.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service that keeps the process alive (and shows a progress
 * notification) while model downloads are in flight, so multi-GB transfers survive the
 * app being backgrounded. The actual byte copying lives in [HuggingFaceHubRepository].
 */
class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "localmind_downloads"
        const val NOTIFICATION_ID = 4201

        fun startIfDownloadsActive(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stopIfIdle(context: Context) {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(null), foregroundType())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, buildNotification(null))
        }
        scope.launch {
            // Observes hub download state for as long as the service lives.
            (applicationContext as? com.example.LocalMindApplication)?.huggingFaceHubRepository
                ?.downloads
                ?.collectLatest { tasks ->
                    val active = tasks.values.filter { it.status == DownloadStatus.DOWNLOADING }
                    if (active.isEmpty()) {
                        stopIfIdle(this@DownloadForegroundService)
                    } else {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, buildNotification(active.firstOrNull()))
                    }
                }
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // No notification permission: the service cannot post its notification, so
            // stop rather than risk a foreground-service crash on OEM builds.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(task: ModelDownloadTask?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(
                task?.let { "${it.fileName} • ${(it.fraction * 100).toInt()}%" }
                    ?: getString(R.string.download_notification_preparing)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((task?.fraction ?: 0f) * 100).toInt(), false)
            .setContentIntent(contentIntent)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
