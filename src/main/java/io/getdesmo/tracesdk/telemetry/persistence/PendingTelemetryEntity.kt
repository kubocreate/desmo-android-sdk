package io.getdesmo.tracesdk.telemetry.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a batch of telemetry samples pending upload.
 *
 * Each row contains a JSON-encoded batch that failed to upload and is waiting
 * for retry. Batches are processed in FIFO order (oldest first).
 */
@Entity(tableName = "pending_telemetry")
internal data class PendingTelemetryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Session this batch belongs to. */
    val sessionId: String,

    /** JSON-encoded list of TelemetrySample objects. */
    val samplesJson: String,

    /** Number of samples in this batch (for logging/debugging). */
    val sampleCount: Int,

    /** Timestamp when this batch was created (epoch millis). */
    val createdAt: Long = System.currentTimeMillis(),

    /** Number of upload attempts so far. */
    val attemptCount: Int = 0
)
