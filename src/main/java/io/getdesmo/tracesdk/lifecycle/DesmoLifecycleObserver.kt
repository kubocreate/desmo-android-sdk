package io.getdesmo.tracesdk.lifecycle

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Lifecycle observer that notifies the SDK when the app goes to foreground/background.
 *
 * This is used internally by the SDK to:
 * - Re-register sensor listeners when app comes to foreground
 * - Pause non-essential collection when app goes to background
 *
 * The host app should call [Desmo.bindToLifecycle] with their Activity or Application lifecycle.
 */
internal class DesmoLifecycleObserver(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
    private val loggingEnabled: Boolean = false
) : DefaultLifecycleObserver {

    private companion object {
        private const val TAG = "DesmoSDK"
    }

    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground
        if (loggingEnabled) {
            Log.d(TAG, "App came to foreground, resuming sensors")
        }
        onForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background
        if (loggingEnabled) {
            Log.d(TAG, "App went to background")
        }
        onBackground()
    }
}
