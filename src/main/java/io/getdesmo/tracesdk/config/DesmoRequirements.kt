package io.getdesmo.tracesdk.config

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
)

interface RequirementsChecker {
    fun currentStatus(context: Context): DesmoRequirementsStatus
}

/**
 * Default implementation that checks:
 * - Location: ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION.
 * - Motion: ACTIVITY_RECOGNITION (on API 29+), otherwise assumed true.
 */
class DesmoRequirements : RequirementsChecker {

    override fun currentStatus(context: Context): DesmoRequirementsStatus {
        val hasLocation = hasLocationPermission(context)
        val hasMotion = hasMotionPermission(context)

        return DesmoRequirementsStatus(
            hasLocationPermission = hasLocation,
            hasMotionPermission = hasMotion
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


