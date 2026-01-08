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
    val hasMotionPermission: Boolean
) {
    /** True if all required permissions are granted. */
    val allGranted: Boolean
        get() = hasLocationPermission && hasMotionPermission
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
            hasMotionPermission = hasMotionPermission(context)
        )
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
