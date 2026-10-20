package com.majordomo.messenger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class BackgroundSessionService : Service() {
    companion object {
        const val ACTION_START = "com.majordomo.messenger.action.START_BACKGROUND_SESSION"
        const val ACTION_STOP = "com.majordomo.messenger.action.STOP_BACKGROUND_SESSION"
        private const val CHANNEL_ID = "majordomo_background_session"
        private const val NOTIFICATION_ID = 7302
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + ":MajordomoBackground")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Работа в фоне",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает, что открытая сессия Majordomo продолжает работать в фоне"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                2,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle("Majordomo Message")
            .setContentText("Фоновая сессия активна; сеть может работать при выключенном экране")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
