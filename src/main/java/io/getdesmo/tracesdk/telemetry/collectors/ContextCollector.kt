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
 */
internal class ContextCollector(context: Context) {

    private val appContext = context.applicationContext

    /** Returns the current device context snapshot. */
    fun getContext(): ContextPayload {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val batteryLevel = getBatteryLevel(batteryIntent)
        val charging = isCharging(batteryIntent)
        val screenOn = pm?.isInteractive ?: true
        val network = getNetworkType(cm)

        return ContextPayload(
            screenOn = screenOn,
            appForeground = true, // Approximation: SDK runs while app is active
            batteryLevel = batteryLevel,
            charging = charging,
            network = network,
            motionActivity = null
        )
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

    private fun getNetworkType(cm: ConnectivityManager?): String {
        if (cm == null) return "unknown"

        val networkCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            networkCaps == null -> "none"
            networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
    }
}
