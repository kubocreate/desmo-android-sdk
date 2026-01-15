package io.getdesmo.tracesdk.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Simple requirements status for the host app.
 *
 * Mirrors the Swift `DesmoRequirementsStatus` and `RequirementsChecker`,
 * but uses Android runtime permissions instead of iOS system dialogs.
 */
data class DesmoRequirementsStatus(
    val hasLocationPermission: Boolean,
    val hasMotionPermission: Boolean,
    val hasNotificationPermission: Boolean
) {
    /** True if all required permissions are granted. */
    val allGranted: Boolean
        get() = hasLocationPermission && hasMotionPermission && hasNotificationPermission
}

/**
 * Default implementation that checks:
 * - Location: ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION.
 * - Motion: ACTIVITY_RECOGNITION (on API 29+), otherwise assumed true.
 */
object DesmoRequirements {

    /**
     * Returns the array of Android runtime permissions required by the SDK.
     */
    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // ACTIVITY_RECOGNITION only exists on Android Q+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        return permissions.toTypedArray()
    }

    /**
     * Returns a list of required permissions that are currently denied.
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks if the host app has granted all permissions required by the SDK.
     */
    fun hasRequiredPermissions(context: Context): Boolean {
        return getMissingPermissions(context).isEmpty()
    }

    /**
     * Returns detailed status of each permission category.
     */
    fun currentStatus(context: Context): DesmoRequirementsStatus {
        return DesmoRequirementsStatus(
            hasLocationPermission = hasLocationPermission(context),
            hasMotionPermission = hasMotionPermission(context),
            hasNotificationPermission = canShowNotifications(context)
        )
    }

    /**
     * Returns permissions needed for foreground service functionality.
     * On Android 13+, this includes POST_NOTIFICATIONS.
     */
    fun getForegroundServicePermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        // Android 13+ requires runtime permission for notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    /**
     * Checks if the app can show foreground service notifications.
     * Always returns true on Android 12 and below (no runtime permission needed).
     */
    fun canShowNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No runtime permission needed before Android 13
        }
    }

    /**
     * Checks if background location permission is granted.
     * This is separate from foreground location and requires Play Store declaration.
     *
     * Note: Background location is NOT required for foreground service operation.
     * The foreground service keeps your app in "foreground" state even when minimized.
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Before Android 10, foreground permission covers background too
            hasLocationPermission(context)
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun hasMotionPermission(context: Context): Boolean {
        // On Android Q+ we check ACTIVITY_RECOGNITION. On older devices
        // there is no separate runtime permission for motion.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
