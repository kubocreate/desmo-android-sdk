package io.getdesmo.tracesdk.telemetry

import android.util.Log
import io.getdesmo.tracesdk.telemetry.persistence.PendingTelemetryEntity
import io.getdesmo.tracesdk.telemetry.persistence.TelemetryDao
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages the telemetry upload queue with persistence.
 *
 * Responsibilities:
 * 1. Accept samples from the buffer
 * 2. Persist to SQLite (survives app crashes)
 * 3. Attempt upload
 * 4. Delete on success, retry on failure
 *
 * This class implements the "store-and-forward" pattern for reliable delivery.
 */
internal class TelemetryQueue(
    private val dao: TelemetryDao,
    private val uploader: TelemetryUploader,
    private val loggingEnabled: Boolean
) {

    private companion object {
        private const val TAG = "DesmoSDK"

        // Max retry attempts before giving up on a batch
        private const val MAX_RETRY_ATTEMPTS = 10
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Enqueue samples for upload.
     *
     * Flow:
     * 1. Persist to SQLite immediately (durability)
     * 2. Attempt upload
     * 3. If success → delete from SQLite
     * 4. If failure → leave in SQLite for retry
     */
    suspend fun enqueue(sessionId: String, samples: List<TelemetrySample>) {
        if (samples.isEmpty()) return

        // 1. Encode samples to JSON
        val samplesJson = json.encodeToString(samples)

        // 2. Persist to SQLite FIRST (before attempting upload)
        val entity = PendingTelemetryEntity(
            sessionId = sessionId,
            samplesJson = samplesJson,
            sampleCount = samples.size
        )
        val rowId = dao.insert(entity)

        if (loggingEnabled) {
            Log.d(TAG, "Persisted ${samples.size} samples (id=$rowId)")
        }

        // 3. Build the full request body
        val requestBody = TelemetryRequest(sessionId = sessionId, events = samples)
        val requestJson = json.encodeToString(requestBody)

        // 4. Attempt upload
        when (val result = uploader.uploadJson(requestJson)) {
            is UploadResult.Success -> {
                // Success! Delete from SQLite
                dao.deleteById(rowId)
                if (loggingEnabled) {
                    Log.d(TAG, "Upload success, removed from queue (id=$rowId)")
                }
            }
            is UploadResult.RetryableFailure -> {
                // Leave in SQLite for retry
                dao.incrementAttemptCount(rowId)
                if (loggingEnabled) {
                    Log.w(TAG, "Upload failed, will retry (id=$rowId): ${result.error.message}")
                }
            }
            is UploadResult.PermanentFailure -> {
                // Permanent failure - delete to avoid infinite retries
                dao.deleteById(rowId)
                if (loggingEnabled) {
                    Log.e(TAG, "Upload permanent failure, discarding (id=$rowId): ${result.error.message}")
                }
            }
        }
    }

    /**
     * Process all pending batches (retry failed uploads).
     *
     * Call this:
     * - On app startup
     * - Periodically during a session
     * - When network connectivity is restored
     */
    suspend fun processPendingBatches() {
        // First, clean up batches that exceeded max retries
        dao.deleteStaleRetries(MAX_RETRY_ATTEMPTS)

        val pending = dao.getAllPending()
        if (pending.isEmpty()) return

        if (loggingEnabled) {
            Log.d(TAG, "Processing ${pending.size} pending batches")
        }

        for (entity in pending) {
            processEntity(entity)
        }
    }

    /**
     * Process pending batches for a specific session.
     */
    suspend fun processPendingForSession(sessionId: String) {
        val pending = dao.getPendingForSession(sessionId)
        if (pending.isEmpty()) return

        if (loggingEnabled) {
            Log.d(TAG, "Processing ${pending.size} pending batches for session $sessionId")
        }

        for (entity in pending) {
            processEntity(entity)
        }
    }

    private suspend fun processEntity(entity: PendingTelemetryEntity) {
        // Rebuild request body from stored data
        val requestBody = TelemetryRequest(
            sessionId = entity.sessionId,
            events = json.decodeFromString(entity.samplesJson)
        )
        val requestJson = json.encodeToString(requestBody)

        when (val result = uploader.uploadJson(requestJson)) {
            is UploadResult.Success -> {
                dao.deleteById(entity.id)
                if (loggingEnabled) {
                    Log.d(TAG, "Retry success, removed (id=${entity.id})")
                }
            }
            is UploadResult.RetryableFailure -> {
                dao.incrementAttemptCount(entity.id)
                if (loggingEnabled) {
                    Log.w(TAG, "Retry failed (attempt ${entity.attemptCount + 1}): ${result.error.message}")
                }
            }
            is UploadResult.PermanentFailure -> {
                dao.deleteById(entity.id)
                if (loggingEnabled) {
                    Log.e(TAG, "Retry permanent failure, discarding: ${result.error.message}")
                }
            }
        }
    }

    /**
     * Get count of pending batches (for monitoring/debugging).
     */
    suspend fun getPendingCount(): Int {
        return dao.getPendingCount()
    }
}
