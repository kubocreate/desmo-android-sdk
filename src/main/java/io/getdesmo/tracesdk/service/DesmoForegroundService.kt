package io.getdesmo.tracesdk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.getdesmo.tracesdk.config.ForegroundServiceConfig

/**
 * Foreground service that keeps telemetry collection alive when the app is in background.
 *
 * This service:
 * 1. Shows a persistent notification (required by Android)
 * 2. Prevents Android from killing the app process
 * 3. Allows continuous location + sensor collection
 *
 * The service accepts either:
 * - [ForegroundServiceConfig.Simple]: SDK builds the notification
 * - [ForegroundServiceConfig.Custom]: Host app provides their own notification
 */
class DesmoForegroundService : Service() {

    companion object {
        private const val TAG = "DesmoSDK"

        // Static config set before service starts
        @Volatile
        internal var serviceConfig: ForegroundServiceConfig? = null

        /**
         * Start the foreground service with the given configuration.
         *
         * @param context Application context
         * @param config Foreground service configuration (Simple or Custom)
         */
        fun start(context: Context, config: ForegroundServiceConfig) {
            serviceConfig = config
            val intent = Intent(context, DesmoForegroundService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service.
         *
         * @param context Application context
         */
        fun stop(context: Context) {
            val intent = Intent(context, DesmoForegroundService::class.java)
            context.stopService(intent)
            serviceConfig = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DesmoForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = serviceConfig
        if (config == null) {
            Log.e(TAG, "ForegroundServiceConfig not set, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Build or use the notification based on config type
        val (notification, notificationId) = when (config) {
            is ForegroundServiceConfig.Simple -> {
                createNotificationChannel(config.channelId, config.channelName)
                val notif = buildSimpleNotification(config)
                Pair(notif, config.notificationId)
            }
            is ForegroundServiceConfig.Custom -> {
                Pair(config.notification, config.notificationId)
            }
        }

        // Start foreground - this is what keeps the app alive!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(notificationId, notification)
        }

        Log.d(TAG, "DesmoForegroundService started in foreground")

        // If system kills service, don't restart automatically
        // (session would need to be restarted by user anyway)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DesmoForegroundService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Create notification channel for Android 8+.
     * Only needed for Simple config - Custom config assumes host app created channel.
     */
    private fun createNotificationChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW // Low = no sound, just shows in tray
            ).apply {
                description = "Shows when Desmo is recording a delivery"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Build a simple notification from the Simple config.
     */
    private fun buildSimpleNotification(config: ForegroundServiceConfig.Simple): Notification {
        return NotificationCompat.Builder(this, config.channelId)
            .setContentTitle(config.title)
            .setContentText(config.text)
            .setSmallIcon(config.smallIconResId)
            .setOngoing(true) // Can't be swiped away
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
