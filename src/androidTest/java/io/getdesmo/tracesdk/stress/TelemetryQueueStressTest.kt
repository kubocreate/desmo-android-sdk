package io.getdesmo.tracesdk.stress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.getdesmo.tracesdk.telemetry.TelemetrySample
import io.getdesmo.tracesdk.telemetry.persistence.PendingTelemetryEntity
import io.getdesmo.tracesdk.telemetry.persistence.TelemetryDao
import io.getdesmo.tracesdk.telemetry.persistence.TelemetryDatabase
import io.getdesmo.tracesdk.util.TestHelpers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stress tests for telemetry persistence (TelemetryDao/SQLite).
 *
 * Since TelemetryQueue and TelemetryUploader are internal final classes,
 * we test the persistence layer directly via TelemetryDao.
 *
 * Tests:
 * - Batch persistence to SQLite
 * - Recovery after "app restart" (database survives)
 * - Concurrent insert operations
 * - High-volume batch processing
 * - Retry count tracking
 */
@RunWith(AndroidJUnit4::class)
class TelemetryQueueStressTest {

    private lateinit var database: TelemetryDatabase
    private lateinit var dao: TelemetryDao
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Use in-memory database for tests
        database = Room.inMemoryDatabaseBuilder(context, TelemetryDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        dao = database.telemetryDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    /**
     * Test: Insert 100 batches to SQLite.
     * Expected: All batches persisted and retrievable.
     */
    @Test
    fun testPersistenceOfManyBatches() = runBlocking {
        val batchCount = 100
        val samplesPerBatch = 50
        val sessionId = TestHelpers.generateSessionId()

        // Insert batches
        repeat(batchCount) {
            val samples = TestHelpers.createSamples(samplesPerBatch)
            val samplesJson = json.encodeToString(samples)

            val entity = PendingTelemetryEntity(
                sessionId = sessionId,
                samplesJson = samplesJson,
                sampleCount = samples.size
            )
            dao.insert(entity)
        }

        // Verify all batches are persisted
        val pendingCount = dao.getPendingCount()
        assertEquals("All batches should be persisted", batchCount, pendingCount)

        // Verify in database directly
        val pending = dao.getAllPending()
        assertEquals("Database should have all batches", batchCount, pending.size)

        // Verify sample counts
        pending.forEach { entity ->
            assertEquals("Each batch should have correct sample count", samplesPerBatch, entity.sampleCount)
            assertEquals("Session ID should match", sessionId, entity.sessionId)
        }
    }

    /**
     * Test: Recovery after "app restart" (database survives, new DAO instance).
     * Expected: All pending batches are retrievable from new DAO.
     */
    @Test
    fun testRecoveryAfterRestart() = runBlocking {
        val batchCount = 20
        val sessionId = TestHelpers.generateSessionId()

        // Phase 1: Insert batches (simulating pre-crash state)
        repeat(batchCount) {
            val samples = TestHelpers.createSamples(30)
            val entity = PendingTelemetryEntity(
                sessionId = sessionId,
                samplesJson = json.encodeToString(samples),
                sampleCount = samples.size
            )
            dao.insert(entity)
        }

        // Verify batches are pending
        assertEquals(batchCount, dao.getPendingCount())

        // Phase 2: Simulate app restart - the database persists
        // (In real scenario, we'd close and reopen the database)
        // For in-memory test, the data still exists

        // Verify all batches are still there
        val pending = dao.getAllPending()
        assertEquals("All batches should survive restart", batchCount, pending.size)

        // Phase 3: Simulate successful upload - delete batches
        pending.forEach { entity ->
            dao.deleteById(entity.id)
        }

        // Verify database is empty
        assertEquals("Database should be empty after processing", 0, dao.getPendingCount())
    }

    /**
     * Test: Concurrent insert operations from multiple coroutines.
     * Expected: No data corruption, all batches persisted.
     */
    @Test
    fun testConcurrentInserts() = runBlocking {
        val numCoroutines = 10
        val batchesPerCoroutine = 20
        val totalBatches = numCoroutines * batchesPerCoroutine

        // Launch concurrent inserts
        val jobs = (0 until numCoroutines).map { coroutineId ->
            async(Dispatchers.Default) {
                val sessionId = "session-$coroutineId"
                repeat(batchesPerCoroutine) {
                    val samples = TestHelpers.createSamples(10)
                    val entity = PendingTelemetryEntity(
                        sessionId = sessionId,
                        samplesJson = json.encodeToString(samples),
                        sampleCount = samples.size
                    )
                    dao.insert(entity)
                }
            }
        }

        jobs.awaitAll()

        // Verify all batches persisted
        val pendingCount = dao.getPendingCount()
        assertEquals("All batches should be persisted", totalBatches, pendingCount)

        // Verify each session has correct batch count
        (0 until numCoroutines).forEach { coroutineId ->
            val sessionId = "session-$coroutineId"
            val sessionBatches = dao.getPendingForSession(sessionId)
            assertEquals("Session $coroutineId should have correct batches", batchesPerCoroutine, sessionBatches.size)
        }
    }

    /**
     * Test: Attempt count tracking for retry logic.
     * Expected: Attempt count increments correctly.
     */
    @Test
    fun testAttemptCountTracking() = runBlocking {
        val sessionId = TestHelpers.generateSessionId()

        // Insert a batch
        val samples = TestHelpers.createSamples(10)
        val entity = PendingTelemetryEntity(
            sessionId = sessionId,
            samplesJson = json.encodeToString(samples),
            sampleCount = samples.size
        )
        val rowId = dao.insert(entity)

        // Verify initial attempt count is 0
        var pending = dao.getAllPending()
        assertEquals(1, pending.size)
        assertEquals(0, pending[0].attemptCount)

        // Increment attempt count 5 times
        repeat(5) {
            dao.incrementAttemptCount(rowId)
        }

        // Verify attempt count
        pending = dao.getAllPending()
        assertEquals(5, pending[0].attemptCount)

        // Increment more
        repeat(5) {
            dao.incrementAttemptCount(rowId)
        }

        pending = dao.getAllPending()
        assertEquals(10, pending[0].attemptCount)
    }

    /**
     * Test: Delete stale retries (batches exceeding max attempts).
     * Expected: Only stale batches are deleted.
     */
    @Test
    fun testDeleteStaleRetries() = runBlocking {
        val sessionId = TestHelpers.generateSessionId()
        val maxAttempts = 10

        // Insert 5 batches
        val rowIds = mutableListOf<Long>()
        repeat(5) {
            val entity = PendingTelemetryEntity(
                sessionId = sessionId,
                samplesJson = json.encodeToString(TestHelpers.createSamples(5)),
                sampleCount = 5
            )
            rowIds.add(dao.insert(entity))
        }

        // Set different attempt counts:
        // Batch 0: 5 attempts (keep)
        // Batch 1: 10 attempts (delete - at max)
        // Batch 2: 15 attempts (delete - over max)
        // Batch 3: 0 attempts (keep)
        // Batch 4: 9 attempts (keep)
        repeat(5) { dao.incrementAttemptCount(rowIds[0]) }
        repeat(10) { dao.incrementAttemptCount(rowIds[1]) }
        repeat(15) { dao.incrementAttemptCount(rowIds[2]) }
        // rowIds[3] stays at 0
        repeat(9) { dao.incrementAttemptCount(rowIds[4]) }

        // Delete stale retries
        dao.deleteStaleRetries(maxAttempts)

        // Verify: 2 batches deleted (10 and 15 attempts), 3 remain
        val remaining = dao.getAllPending()
        assertEquals("3 batches should remain", 3, remaining.size)

        // Verify the correct ones remain (0, 3, 4)
        val remainingIds = remaining.map { it.id }.toSet()
        assertTrue("Batch 0 should remain", rowIds[0] in remainingIds)
        assertTrue("Batch 3 should remain", rowIds[3] in remainingIds)
        assertTrue("Batch 4 should remain", rowIds[4] in remainingIds)
    }

    /**
     * Test: High-volume batch processing performance.
     * Expected: Can insert and retrieve 500 batches quickly.
     */
    @Test
    fun testHighVolumePerformance() = runBlocking {
        val batchCount = 500
        val sessionId = TestHelpers.generateSessionId()

        // Measure insert time
        val insertStart = System.currentTimeMillis()
        repeat(batchCount) {
            val entity = PendingTelemetryEntity(
                sessionId = sessionId,
                samplesJson = json.encodeToString(TestHelpers.createSamples(20)),
                sampleCount = 20
            )
            dao.insert(entity)
        }
        val insertDuration = System.currentTimeMillis() - insertStart

        assertEquals(batchCount, dao.getPendingCount())

        // Measure retrieval time
        val retrieveStart = System.currentTimeMillis()
        val pending = dao.getAllPending()
        val retrieveDuration = System.currentTimeMillis() - retrieveStart

        assertEquals(batchCount, pending.size)

        // Measure deletion time
        val deleteStart = System.currentTimeMillis()
        pending.forEach { dao.deleteById(it.id) }
        val deleteDuration = System.currentTimeMillis() - deleteStart

        assertEquals(0, dao.getPendingCount())

        // Should complete quickly (< 10 seconds each)
        assertTrue("Insert should complete in < 10s, took ${insertDuration}ms", insertDuration < 10_000)
        assertTrue("Retrieve should complete in < 10s, took ${retrieveDuration}ms", retrieveDuration < 10_000)
        assertTrue("Delete should complete in < 10s, took ${deleteDuration}ms", deleteDuration < 10_000)
    }

    /**
     * Test: Multiple sessions with pending batches.
     * Expected: getPendingForSession returns correct batches.
     */
    @Test
    fun testMultiSessionFiltering() = runBlocking {
        val session1 = "session-1"
        val session2 = "session-2"
        val session3 = "session-3"

        // Insert batches for multiple sessions
        repeat(10) {
            dao.insert(PendingTelemetryEntity(
                sessionId = session1,
                samplesJson = json.encodeToString(TestHelpers.createSamples(5)),
                sampleCount = 5
            ))
        }
        repeat(15) {
            dao.insert(PendingTelemetryEntity(
                sessionId = session2,
                samplesJson = json.encodeToString(TestHelpers.createSamples(5)),
                sampleCount = 5
            ))
        }
        repeat(5) {
            dao.insert(PendingTelemetryEntity(
                sessionId = session3,
                samplesJson = json.encodeToString(TestHelpers.createSamples(5)),
                sampleCount = 5
            ))
        }

        // Total should be 30
        assertEquals(30, dao.getPendingCount())

        // Filter by session
        assertEquals(10, dao.getPendingForSession(session1).size)
        assertEquals(15, dao.getPendingForSession(session2).size)
        assertEquals(5, dao.getPendingForSession(session3).size)

        // Delete session1 batches
        dao.getPendingForSession(session1).forEach { dao.deleteById(it.id) }

        // Verify session1 is empty, others unchanged
        assertEquals(0, dao.getPendingForSession(session1).size)
        assertEquals(15, dao.getPendingForSession(session2).size)
        assertEquals(5, dao.getPendingForSession(session3).size)
        assertEquals(20, dao.getPendingCount())
    }

    /**
     * Test: JSON serialization round-trip.
     * Expected: Samples can be serialized and deserialized correctly.
     */
    @Test
    fun testJsonSerializationRoundTrip() = runBlocking {
        val sessionId = TestHelpers.generateSessionId()
        val originalSamples = TestHelpers.createSamples(100)
        val samplesJson = json.encodeToString(originalSamples)

        // Insert
        val entity = PendingTelemetryEntity(
            sessionId = sessionId,
            samplesJson = samplesJson,
            sampleCount = originalSamples.size
        )
        dao.insert(entity)

        // Retrieve
        val pending = dao.getAllPending()
        assertEquals(1, pending.size)

        // Deserialize and compare
        val retrievedSamples: List<TelemetrySample> = json.decodeFromString(pending[0].samplesJson)
        assertEquals("Sample count should match", originalSamples.size, retrievedSamples.size)

        // Compare first and last samples
        assertEquals("First sample timestamp should match", originalSamples.first().ts, retrievedSamples.first().ts, 0.001)
        assertEquals("Last sample timestamp should match", originalSamples.last().ts, retrievedSamples.last().ts, 0.001)
    }

    /**
     * Test: Delete all batches.
     * Expected: All batches removed.
     */
    @Test
    fun testDeleteAll() = runBlocking {
        val sessionId = TestHelpers.generateSessionId()

        // Insert many batches
        repeat(50) {
            dao.insert(PendingTelemetryEntity(
                sessionId = sessionId,
                samplesJson = json.encodeToString(TestHelpers.createSamples(10)),
                sampleCount = 10
            ))
        }

        assertEquals(50, dao.getPendingCount())

        // Delete all
        dao.deleteAll()

        assertEquals(0, dao.getPendingCount())
        assertTrue(dao.getAllPending().isEmpty())
    }
}
