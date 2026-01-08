package io.getdesmo.tracesdk.api

import io.getdesmo.tracesdk.network.RequestError

/**
 * Top-level errors surfaced by the Desmo client.
 *
 * Mirrors the Swift DesmoClientError enum.
 */
sealed class DesmoClientError(message: String? = null) : Exception(message) {

    /**
     * API key is syntactically invalid (e.g., does not start with "pk_").
     */
    data object InvalidApiKey : DesmoClientError("Invalid Desmo API key")

    /**
     * No active session exists when one is required.
     */
    data object NoActiveSession : DesmoClientError("No active session")

    /**
     * DesmoClient was in an unexpected state for the requested operation.
     */
    data class InvalidState(
        val expected: String,
        val actual: String
    ) : DesmoClientError("Invalid state: expected=$expected, actual=$actual")

    /**
     * Wrapped HTTP-layer error. Filled in once HTTP is implemented.
     */
    data class Http(val error: RequestError) : DesmoClientError("HTTP error: $error")
}

