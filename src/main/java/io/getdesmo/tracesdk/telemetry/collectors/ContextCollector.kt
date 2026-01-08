package io.getdesmo.tracesdk.telemetry.collectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import io.getdesmo.tracesdk.telemetry.ContextPayload

/**
 * Collects device context: battery level, charging state, screen on/off, network type.
 *
 * Battery status is cached and refreshed every [BATTERY_REFRESH_INTERVAL_MS] to avoid
 * excessive system calls (registerReceiver is expensive).
 */
internal class ContextCollector(context: Context) {

    private companion object {
        // Battery changes slowly - refresh every 30 seconds is plenty
        private const val BATTERY_REFRESH_INTERVAL_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // Cached battery state
    @Volatile private var cachedBatteryLevel: Double? = null
    @Volatile private var cachedCharging: Boolean = false
    @Volatile private var lastBatteryRefreshTime: Long = 0L

    /** Returns the current device context snapshot. */
    fun getContext(): ContextPayload {
        // Refresh battery cache if stale
        refreshBatteryCacheIfNeeded()

        return ContextPayload(
            screenOn = powerManager?.isInteractive ?: true,
            appForeground = true, // Approximation: SDK runs while app is active
            batteryLevel = cachedBatteryLevel,
            charging = cachedCharging,
            network = getNetworkType(),
            motionActivity = null
        )
    }

    /**
     * Refresh battery cache only if [BATTERY_REFRESH_INTERVAL_MS] has passed.
     */
    private fun refreshBatteryCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastBatteryRefreshTime < BATTERY_REFRESH_INTERVAL_MS) {
            return // Cache is still fresh
        }

        // Refresh from system
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        cachedBatteryLevel = getBatteryLevel(batteryIntent)
        cachedCharging = isCharging(batteryIntent)
        lastBatteryRefreshTime = now
    }

    private fun getBatteryLevel(batteryIntent: Intent?): Double? {
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            level.toDouble() / scale.toDouble()
        } else {
            null
        }
    }

    private fun isCharging(batteryIntent: Intent?): Boolean {
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getNetworkType(): String {
        val cm = connectivityManager ?: return "unknown"
        val networkCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            networkCaps == null -> "none"
            networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
    }
}
