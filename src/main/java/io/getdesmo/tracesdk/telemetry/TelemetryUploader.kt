package io.getdesmo.tracesdk.telemetry

import android.util.Log
import io.getdesmo.tracesdk.network.HttpClient
import io.getdesmo.tracesdk.network.RequestError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Uploads telemetry batches to the backend via HTTP.
 */
internal class TelemetryUploader(
    private val httpClient: HttpClient,
    private val loggingEnabled: Boolean
) {

    private companion object {
        private const val TAG = "DesmoSDK"
        private const val TELEMETRY_PATH = "/v1/telemetry"
    }

    /** Sends a batch of samples for the given session. */
    suspend fun upload(sessionId: String, samples: List<TelemetrySample>) {
        if (samples.isEmpty()) return

        val body = TelemetryRequest(sessionId = sessionId, events = samples)
        val jsonBody = Json.encodeToString(body)

        try {
            if (loggingEnabled) {
                Log.d(TAG, "Uploading telemetry: ${samples.size} samples")
            }
            httpClient.post(path = TELEMETRY_PATH, jsonBody = jsonBody)
            if (loggingEnabled) {
                Log.d(TAG, "Telemetry upload success")
            }
        } catch (e: RequestError) {
            if (loggingEnabled) {
                Log.e(TAG, "Telemetry upload failed: $e")
            }
        } catch (t: Throwable) {
            if (loggingEnabled) {
                Log.e(TAG, "Telemetry upload failed: $t")
            }
        }
    }
}
