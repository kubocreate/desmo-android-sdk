package io.getdesmo.tracesdk.config

import android.app.Notification

/**
 * Configuration for the optional foreground service that keeps telemetry
 * collection alive when the app is in background.
 *
 * Two options:
 * - [Simple]: Provide title, text, and icon - SDK builds the notification
 * - [Custom]: Provide your own [Notification] object for full control
 *
 * If not configured, sessions will work normally in foreground but may
 * be throttled or killed by Android when the app goes to background.
 */
sealed class ForegroundServiceConfig {

    /**
     * Simple configuration - SDK builds the notification for you.
     *
     * Example:
     * ```kotlin
     * ForegroundServiceConfig.Simple(
     *     title = "QWQER Delivery",
     *     text = "Recording your route...",
     *     smallIconResId = R.drawable.ic_delivery
     * )
     * ```
     *
     * @param title Notification title (e.g., "QWQER Delivery")
     * @param text Notification body text (e.g., "Recording your route...")
     * @param smallIconResId Resource ID for small icon (must be provided by host app)
     * @param channelId Notification channel ID (Android 8+)
     * @param channelName Channel name shown in system settings
     * @param notificationId Unique ID for this notification (avoid conflicts with host app)
     */
    data class Simple(
        val title: String,
        val text: String,
        val smallIconResId: Int,
        val channelId: String = "desmo_tracking",
        val channelName: String = "Delivery Tracking",
        val notificationId: Int = 1001
    ) : ForegroundServiceConfig()

    /**
     * Custom configuration - you provide your own notification.
     *
     * Use this for full control over notification appearance, including:
     * - Custom colors and branding
     * - Action buttons
     * - Large icons
     * - Custom content intents
     *
     * Example:
     * ```kotlin
     * val notification = NotificationCompat.Builder(context, "my_channel")
     *     .setContentTitle("QWQER Active")
     *     .setContentText("3 stops remaining")
     *     .setSmallIcon(R.drawable.qwqer_logo)
     *     .setColor(0xFF00B140.toInt())
     *     .addAction(R.drawable.ic_stop, "End Shift", stopIntent)
     *     .build()
     *
     * ForegroundServiceConfig.Custom(notification)
     * ```
     *
     * **Important:** You are responsible for creating the notification channel
     * before the session starts (Android 8+).
     *
     * @param notification The notification to display while recording
     * @param notificationId Unique ID for this notification (avoid conflicts with host app)
     */
    data class Custom(
        val notification: Notification,
        val notificationId: Int = 1001
    ) : ForegroundServiceConfig()
}
