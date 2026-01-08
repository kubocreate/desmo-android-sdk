package io.getdesmo.tracesdk.network

/**
 * Errors originating from the HTTP/network layer.
 *
 * These are low-level errors that get wrapped by DesmoClientError before
 * being exposed to SDK users.
 */
sealed class RequestError : Exception() {

    /** Response was not an HTTP response or otherwise malformed. */
    data object InvalidResponse : RequestError() {
        override val message: String = "Invalid or malformed HTTP response"
    }

    /**
     * Non-2xx HTTP status code.
     *
     * @param code The HTTP status code (e.g., 401, 404, 500)
     * @param url The URL that was requested (for debugging)
     * @param errorBody Preview of the error response body (first 500 chars)
     */
    data class StatusCode(
        val code: Int,
        val url: String = "",
        val errorBody: String = ""
    ) : RequestError() {
        override val message: String
            get() = "HTTP $code from $url"
    }

    /** Failed to decode response body. */
    data object DecodingError : RequestError() {
        override val message: String = "Failed to decode response body"
    }

    /**
     * Lower-level network / IO error (no response received).
     *
     * This includes: no internet, DNS failure, timeout, connection refused, etc.
     *
     * We avoid naming the property `cause` because `Exception` already has a `cause` member;
     * reusing that name would hide the superclass member.
     */
    data class NetworkError(val error: Throwable) : RequestError() {
        init {
            // Attach the original throwable as the underlying cause so
            // stack traces still show the root error.
            initCause(error)
        }

        override val message: String
            get() = "Network error: ${error.message ?: error::class.simpleName}"
    }
}
