package io.getdesmo.tracesdk

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.getdesmo.tracesdk.config.DesmoConfig
import io.getdesmo.tracesdk.config.DesmoEnvironment
import io.getdesmo.tracesdk.internal.DesmoRequirements
import io.getdesmo.tracesdk.lifecycle.DesmoLifecycleObserver
import io.getdesmo.tracesdk.session.DesmoClient

/**
 * Main entry point for the Desmo Android SDK.
 *
 * Mirrors the Swift `Desmo` class:
 * - `Desmo.shared`  ->  `Desmo.client`
 * - `Desmo.setup()` ->  `Desmo.setup()`
 */
object Desmo {

    private const val TAG = "DesmoSDK"

    /**
     * The shared Desmo client instance.
     *
     * Populated after [setup] is called.
     */
    @JvmStatic
    var client: DesmoClient? = null
        private set

    private var lifecycleObserver: DesmoLifecycleObserver? = null
    private var boundLifecycleOwner: LifecycleOwner? = null

    /**
     * Configure the SDK without telemetry (no Android Context).
     *
     * This mirrors the original iOS-style setup and is useful for
     * environments where you don't have easy access to an Application
     * context. Telemetry will be disabled (NoopTelemetryProvider).
     */
    @JvmStatic
    fun setup(apiKey: String, environment: DesmoEnvironment) {
        internalSetup(context = null, apiKey = apiKey, environment = environment)
    }

    /**
     * Configure the SDK with telemetry enabled.
     *
     * Call this from your Application class so we can attach to Android
     * system services (sensors, location, etc.).
     */
    @JvmStatic
    fun setup(context: Context, apiKey: String, environment: DesmoEnvironment) {
        internalSetup(context = context.applicationContext, apiKey = apiKey, environment = environment)
    }

    private fun internalSetup(
        context: Context?,
        apiKey: String,
        environment: DesmoEnvironment
    ) {
        try {
            if (context != null && !DesmoRequirements.hasRequiredPermissions(context)) {
                Log.w(TAG, "Required permissions are missing: ${DesmoRequirements.getMissingPermissions(context)}")
            }

            val config = DesmoConfig(
                apiKey = apiKey,
                environment = environment
            )
            client = DesmoClient(config, context)

            if (config.loggingEnabled) {
                Log.d(TAG, "Setup successful pointing to: ${environment.baseUrl}")
            }
        } catch (t: Throwable) {
            // Mirror the iOS behavior of logging on failure.
            Log.e(TAG, "Setup failed: $t")
        }
    }

    /**
     * Returns the array of Android runtime permissions required by the SDK.
     *
     * Currently: ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, 
     * and ACTIVITY_RECOGNITION (on API 29+).
     */
    @JvmStatic
    fun getRequiredPermissions(): Array<String> {
        return DesmoRequirements.getRequiredPermissions()
    }

    /**
     * Checks if the host app has granted all permissions required by the SDK.
     */
    @JvmStatic
    fun hasRequiredPermissions(context: Context): Boolean {
        return DesmoRequirements.hasRequiredPermissions(context)
    }

    /**
     * Returns a list of permissions that are currently denied but required.
     */
    @JvmStatic
    fun getMissingPermissions(context: Context): List<String> {
        return DesmoRequirements.getMissingPermissions(context)
    }

    /**
     * Bind the SDK to the app's lifecycle for automatic sensor management.
     *
     * When the app goes to background, Android may throttle sensor updates.
     * By binding to the lifecycle, the SDK will automatically re-register
     * sensors when the app comes back to foreground.
     *
     * **Recommended:** Call this from your Application.onCreate():
     * ```kotlin
     * Desmo.setup(this, apiKey, environment)
     * Desmo.bindToProcessLifecycle()
     * ```
     *
     * For Activity-level binding (less common):
     * ```kotlin
     * Desmo.bindToLifecycle(this) // in Activity.onCreate()
     * ```
     */
    @JvmStatic
    fun bindToProcessLifecycle() {
        bindToLifecycle(ProcessLifecycleOwner.get())
    }

    /**
     * Bind the SDK to a specific LifecycleOwner (Activity, Fragment, etc.).
     *
     * @see bindToProcessLifecycle for application-level binding (recommended)
     */
    @JvmStatic
    fun bindToLifecycle(lifecycleOwner: LifecycleOwner) {
        val currentClient = client
        if (currentClient == null) {
            Log.w(TAG, "Cannot bind to lifecycle before setup() is called")
            return
        }

        // Remove any existing observer
        unbindFromLifecycle()

        // Create and attach new observer
        lifecycleObserver = DesmoLifecycleObserver(
            onForeground = { currentClient.onForeground() },
            onBackground = { currentClient.onBackground() },
            loggingEnabled = true // TODO: get from config
        )

        boundLifecycleOwner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver!!)

        Log.d(TAG, "Bound to lifecycle: ${lifecycleOwner::class.simpleName}")
    }

    /**
     * Unbind from the currently bound lifecycle.
     */
    @JvmStatic
    fun unbindFromLifecycle() {
        lifecycleObserver?.let { observer ->
            boundLifecycleOwner?.lifecycle?.removeObserver(observer)
        }
        lifecycleObserver = null
        boundLifecycleOwner = null
    }
}
