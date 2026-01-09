package io.getdesmo.tracesdk.telemetry.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for pending telemetry batches.
 *
 * Provides CRUD operations for the pending_telemetry table.
 */
@Dao
internal interface TelemetryDao {

    /**
     * Insert a new pending batch.
     *
     * @return The auto-generated row ID.
     */
    @Insert
    suspend fun insert(entity: PendingTelemetryEntity): Long

    /**
     * Get all pending batches, oldest first (FIFO).
     */
    @Query("SELECT * FROM pending_telemetry ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingTelemetryEntity>

    /**
     * Get pending batches for a specific session, oldest first.
     */
    @Query("SELECT * FROM pending_telemetry WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getPendingForSession(sessionId: String): List<PendingTelemetryEntity>

    /**
     * Delete a batch by ID (call after successful upload).
     */
    @Query("DELETE FROM pending_telemetry WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Increment the attempt count for a batch (call after failed upload).
     */
    @Query("UPDATE pending_telemetry SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun incrementAttemptCount(id: Long)

    /**
     * Delete batches that have exceeded max retry attempts.
     */
    @Query("DELETE FROM pending_telemetry WHERE attemptCount >= :maxAttempts")
    suspend fun deleteStaleRetries(maxAttempts: Int)

    /**
     * Get total count of pending batches.
     */
    @Query("SELECT COUNT(*) FROM pending_telemetry")
    suspend fun getPendingCount(): Int

    /**
     * Delete all pending batches (for testing/cleanup).
     */
    @Query("DELETE FROM pending_telemetry")
    suspend fun deleteAll()
}
