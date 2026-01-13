package io.getdesmo.tracesdk.telemetry.collectors

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Collects motion activity data using Google's Activity Recognition API.
 *
 * Detects activities like WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, STILL, etc.
 * and provides the most likely activity as a string for telemetry.
 *
 * Requires ACTIVITY_RECOGNITION permission on Android Q+ (API 29+).
 */
internal class ActivityRecognitionCollector(
    private val context: Context,
    private val loggingEnabled: Boolean = false
) {

    private companion object {
        private const val TAG = "DesmoSDK"
        private const val ACTION_ACTIVITY_UPDATE = "io.getdesmo.tracesdk.ACTION_ACTIVITY_UPDATE"
        
        // Detection interval in milliseconds (3 seconds for responsive detection)
        private const val DETECTION_INTERVAL_MS = 3000L
    }

    private val appContext = context.applicationContext
    private var activityRecognitionClient: ActivityRecognitionClient? = null
    private var pendingIntent: PendingIntent? = null
    private var isStarted = false

    /**
     * Latest detected activity. Null if no activity detected yet or collection not started.
     */
    @Volatile
    var latestActivity: String? = null
        private set

    /**
     * Confidence of the latest detected activity (0-100).
     */
    @Volatile
    var latestConfidence: Int = 0
        private set

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            
            try {
                if (ActivityRecognitionResult.hasResult(intent)) {
                    val result = ActivityRecognitionResult.extractResult(intent)
                    result?.let { handleActivityResult(it) }
                }
            } catch (t: Throwable) {
                if (loggingEnabled) {
                    Log.e(TAG, "Activity recognition callback error: $t")
                }
            }
        }
    }

    private fun handleActivityResult(result: ActivityRecognitionResult) {
        val mostProbable = result.mostProbableActivity
        
        latestActivity = activityTypeToString(mostProbable.type)
        latestConfidence = mostProbable.confidence

        if (loggingEnabled) {
            Log.d(TAG, "Activity detected: $latestActivity (confidence: $latestConfidence%)")
        }
    }

    /**
     * Convert DetectedActivity type to human-readable string.
     * These strings match common activity recognition terminology.
     */
    private fun activityTypeToString(type: Int): String {
        return when (type) {
            DetectedActivity.IN_VEHICLE -> "in_vehicle"
            DetectedActivity.ON_BICYCLE -> "on_bicycle"
            DetectedActivity.ON_FOOT -> "on_foot"
            DetectedActivity.RUNNING -> "running"
            DetectedActivity.STILL -> "still"
            DetectedActivity.TILTING -> "tilting"
            DetectedActivity.WALKING -> "walking"
            DetectedActivity.UNKNOWN -> "unknown"
            else -> "unknown"
        }
    }

    /**
     * Start activity recognition updates.
     * Does nothing if already started or permission not granted.
     */
    fun start() {
        if (isStarted) return
        
        // Check permission on Android Q+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasPermission = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                if (loggingEnabled) {
                    Log.w(TAG, "ACTIVITY_RECOGNITION permission not granted, skipping activity collection")
                }
                return
            }
        }

        try {
            activityRecognitionClient = ActivityRecognition.getClient(appContext)
            
            // Create PendingIntent for activity updates
            val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
                setPackage(appContext.packageName)
            }
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            pendingIntent = PendingIntent.getBroadcast(
                appContext,
                0,
                intent,
                flags
            )

            // Register receiver
            val filter = IntentFilter(ACTION_ACTIVITY_UPDATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(activityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(activityReceiver, filter)
            }

            // Request activity updates
            pendingIntent?.let { pi ->
                activityRecognitionClient?.requestActivityUpdates(DETECTION_INTERVAL_MS, pi)
                    ?.addOnSuccessListener {
                        isStarted = true
                        if (loggingEnabled) {
                            Log.d(TAG, "Activity recognition started")
                        }
                    }
                    ?.addOnFailureListener { e ->
                        if (loggingEnabled) {
                            Log.e(TAG, "Failed to start activity recognition: ${e.message}")
                        }
                    }
            }
        } catch (t: Throwable) {
            if (loggingEnabled) {
                Log.e(TAG, "Error starting activity recognition: $t")
            }
        }
    }

    /**
     * Stop activity recognition updates.
     */
    fun stop() {
        if (!isStarted) return

        try {
            pendingIntent?.let { pi ->
                activityRecognitionClient?.removeActivityUpdates(pi)
            }
            
            try {
                appContext.unregisterReceiver(activityReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver not registered, ignore
            }

            isStarted = false
            latestActivity = null
            latestConfidence = 0

            if (loggingEnabled) {
                Log.d(TAG, "Activity recognition stopped")
            }
        } catch (t: Throwable) {
            if (loggingEnabled) {
                Log.e(TAG, "Error stopping activity recognition: $t")
            }
        }
    }

    /**
     * Check if activity recognition is available on this device.
     */
    fun isAvailable(): Boolean {
        // Check if Google Play Services is available
        return try {
            ActivityRecognition.getClient(appContext)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
