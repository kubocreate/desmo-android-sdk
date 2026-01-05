package io.getdesmo.tracesdk.network

/* Errors originating from the HTTP layer. */

sealed class HttpError : Exception() {

    /** Response was not an HTTP response or otherwise malformed. */
    data object InvalidResponse : HttpError()

    /** Non-2xx HTTP status code. */
    data class StatusCode(val code: Int) : HttpError()

    /** Failed to decode response body. */
    data object DecodingError : HttpError()

    /**
     * Lower-level network / IO error.
     *
     * We avoid naming the property `cause` because `Exception` already has a `cause` member;
     * reusing that name would hide the superclass member.
     */
    data class NetworkError(val error: Throwable) : HttpError() {
        init {
            // Attach the original throwable as the underlying cause so
            // stack traces still show the root error.
            initCause(error)
        }
    }
}

