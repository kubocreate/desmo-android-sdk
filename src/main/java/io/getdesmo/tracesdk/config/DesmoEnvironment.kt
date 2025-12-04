package io.getdesmo.tracesdk.config

/**
 * Desmo backend environments.
 *
 * For now both SANDBOX and LIVE point to the same host (https://api.getdesmo.io).
 * This mirrors the iOS SDK's DesmoEnvironment.
 */
enum class DesmoEnvironment(val baseUrl: String) {
    SANDBOX("https://api.getdesmo.io"),
    LIVE("https://api.getdesmo.io");
}


