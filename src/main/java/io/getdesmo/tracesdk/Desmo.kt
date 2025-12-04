package io.getdesmo.tracesdk

import android.content.Context
import io.getdesmo.tracesdk.config.DesmoConfig
import io.getdesmo.tracesdk.config.DesmoEnvironment
import io.getdesmo.tracesdk.core.DesmoClient

/**
 * Main entry point for the Desmo Android SDK.
 *
 * Mirrors the Swift `Desmo` class:
 * - `Desmo.shared`  ->  `Desmo.client`
 * - `Desmo.setup()` ->  `Desmo.setup()`
 */
object Desmo {

    /**
     * The shared Desmo client instance.
     *
     * Populated after [setup] is called.
     */
    @JvmStatic
    var client: DesmoClient? = null
        private set

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
            val config = DesmoConfig(
                apiKey = apiKey,
                environment = environment
            )
            client = DesmoClient(config, context)

            if (config.loggingEnabled) {
                println("[DesmoSDK] Setup successful pointing to: ${environment.baseUrl}")
            }
        } catch (t: Throwable) {
            // Mirror the iOS behavior of logging on failure.
            println("[DesmoSDK] Setup failed: $t")
        }
    }
}


