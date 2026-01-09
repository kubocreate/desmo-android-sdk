package io.getdesmo.tracesdk.telemetry

import android.util.Log
import io.getdesmo.tracesdk.network.HttpClient
import io.getdesmo.tracesdk.network.RequestError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Result of a telemetry upload attempt.
 */
internal sealed class UploadResult {
    /** Upload succeeded. */
    data object Success : UploadResult()

    /** Upload failed with a retryable error (network issue, server error). */
    data class RetryableFailure(val error: Throwable) : UploadResult()

    /** Upload failed with a permanent error (e.g., 400 Bad Request - don't retry). */
    data class PermanentFailure(val error: Throwable) : UploadResult()
}

/**
 * Uploads telemetry batches to the backend via HTTP.
 *
 * Returns [UploadResult] so the caller can decide how to handle failures.
 */
internal class TelemetryUploader(
    private val httpClient: HttpClient,
    private val loggingEnabled: Boolean
) {

    private companion object {
        private const val TAG = "DesmoSDK"
        private const val TELEMETRY_PATH = "/v1/telemetry"
    }

    /**
     * Sends a batch of samples for the given session.
     *
     * @return [UploadResult] indicating success or type of failure.
     */
    suspend fun upload(sessionId: String, samples: List<TelemetrySample>): UploadResult {
        if (samples.isEmpty()) return UploadResult.Success

        val body = TelemetryRequest(sessionId = sessionId, events = samples)
        val jsonBody = Json.encodeToString(body)

        return try {
            if (loggingEnabled) {
                Log.d(TAG, "Uploading telemetry: ${samples.size} samples")
            }
            httpClient.post(path = TELEMETRY_PATH, jsonBody = jsonBody)
            if (loggingEnabled) {
                Log.d(TAG, "Telemetry upload success")
            }
            UploadResult.Success
        } catch (e: RequestError.StatusCode) {
            // Client errors (4xx) are permanent - don't retry
            if (e.code in 400..499) {
                if (loggingEnabled) {
                    Log.e(TAG, "Telemetry upload permanent failure (${e.code}): $e")
                }
                UploadResult.PermanentFailure(e)
            } else {
                // Server errors (5xx) are retryable
                if (loggingEnabled) {
                    Log.w(TAG, "Telemetry upload retryable failure (${e.code}): $e")
                }
                UploadResult.RetryableFailure(e)
            }
        } catch (e: RequestError.NetworkError) {
            // Network issues are retryable
            if (loggingEnabled) {
                Log.w(TAG, "Telemetry upload network failure: ${e.message}")
            }
            UploadResult.RetryableFailure(e)
        } catch (e: RequestError) {
            // Other request errors - treat as retryable
            if (loggingEnabled) {
                Log.w(TAG, "Telemetry upload failed: $e")
            }
            UploadResult.RetryableFailure(e)
        } catch (t: Throwable) {
            // Unexpected errors - treat as retryable
            if (loggingEnabled) {
                Log.e(TAG, "Telemetry upload unexpected failure: $t")
            }
            UploadResult.RetryableFailure(t)
        }
    }

    /**
     * Upload pre-encoded JSON (used when retrying from persistence).
     */
    suspend fun uploadJson(jsonBody: String): UploadResult {
        return try {
            if (loggingEnabled) {
                Log.d(TAG, "Uploading persisted telemetry batch")
            }
            httpClient.post(path = TELEMETRY_PATH, jsonBody = jsonBody)
            if (loggingEnabled) {
                Log.d(TAG, "Persisted telemetry upload success")
            }
            UploadResult.Success
        } catch (e: RequestError.StatusCode) {
            if (e.code in 400..499) {
                UploadResult.PermanentFailure(e)
            } else {
                UploadResult.RetryableFailure(e)
            }
        } catch (e: RequestError) {
            UploadResult.RetryableFailure(e)
        } catch (t: Throwable) {
            UploadResult.RetryableFailure(t)
        }
    }
}
