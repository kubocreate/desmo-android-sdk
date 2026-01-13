package io.getdesmo.tracesdk.api

/**
 * A type-safe result wrapper that guarantees the SDK will never crash the host application.
 *
 * All public API methods return [DesmoResult] instead of throwing exceptions.
 * This allows callers to handle success and failure explicitly without risk of
 * uncaught exceptions crashing their app.
 *
 * Example usage:
 * ```kotlin
 * val result = client.startSession(...)
 *
 * // Pattern 1: Explicit when
 * when (result) {
 *     is DesmoResult.Success -> useSession(result.data)
 *     is DesmoResult.Failure -> logError(result.error)
 * }
 *
 * // Pattern 2: Fluent callbacks
 * result
 *     .onSuccess { session -> saveSessionId(session.sessionId) }
 *     .onFailure { error -> analytics.track("desmo_error", error) }
 *
 * // Pattern 3: Fire and forget
 * val session = result.getOrNull() // null if failed
 * ```
 */
sealed class DesmoResult<out T> {

    /**
     * Represents a successful operation with the resulting [data].
     */
    data class Success<T>(val data: T) : DesmoResult<T>()

    /**
     * Represents a failed operation with the [error] that occurred.
     */
    data class Failure(val error: DesmoClientError) : DesmoResult<Nothing>()

    /**
     * Returns `true` if this is a [Success] result.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Returns `true` if this is a [Failure] result.
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * Returns the success value if this is [Success], or `null` if this is [Failure].
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Returns the error if this is [Failure], or `null` if this is [Success].
     */
    fun errorOrNull(): DesmoClientError? = (this as? Failure)?.error

    /**
     * Executes [action] if this is [Success], passing the success value.
     * Returns this result for chaining.
     */
    inline fun onSuccess(action: (T) -> Unit): DesmoResult<T> {
        if (this is Success) {
            action(data)
        }
        return this
    }

    /**
     * Executes [action] if this is [Failure], passing the error.
     * Returns this result for chaining.
     */
    inline fun onFailure(action: (DesmoClientError) -> Unit): DesmoResult<T> {
        if (this is Failure) {
            action(error)
        }
        return this
    }

    /**
     * Returns the success value if this is [Success], or the result of [defaultValue] if [Failure].
     */
    inline fun getOrElse(defaultValue: (DesmoClientError) -> @UnsafeVariance T): T {
        return when (this) {
            is Success -> data
            is Failure -> defaultValue(error)
        }
    }

    /**
     * Transforms the success value using [transform] if this is [Success].
     * Returns [Failure] unchanged.
     */
    inline fun <R> map(transform: (T) -> R): DesmoResult<R> {
        return when (this) {
            is Success -> Success(transform(data))
            is Failure -> this
        }
    }
}
