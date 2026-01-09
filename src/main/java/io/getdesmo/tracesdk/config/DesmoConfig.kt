package io.getdesmo.tracesdk.config

import io.getdesmo.tracesdk.api.DesmoClientError

/**
 * Configuration for the Desmo SDK.
 *
 * Mirrors the Swift `DesmoConfig` struct.
 *
 * @property apiKey Your Desmo publishable API key (must start with "pk_")
 * @property environment Target environment (SANDBOX or LIVE)
 * @property loggingEnabled Enable SDK debug logging (default false for production)
 * @property telemetry Optional telemetry configuration for advanced tuning
 */
data class DesmoConfig(
        val apiKey: String,
        val environment: DesmoEnvironment,
        val loggingEnabled: Boolean = false,
        val telemetry: TelemetryConfig = TelemetryConfig()
) {

    init {
        requireApiKeyValid(apiKey)
    }

    private fun requireApiKeyValid(key: String) {
        if (!key.startsWith("pk_")) {
            throw DesmoClientError.InvalidApiKey
        }
    }
}
