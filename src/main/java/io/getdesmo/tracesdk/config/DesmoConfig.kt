package io.getdesmo.tracesdk.config

import io.getdesmo.tracesdk.api.DesmoClientError

/**
 * Configuration for the Desmo SDK.
 *
 * Mirrors the Swift `DesmoConfig` struct.
 */
data class DesmoConfig(
    val apiKey: String,
    val environment: DesmoEnvironment,
    val loggingEnabled: Boolean = true
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
