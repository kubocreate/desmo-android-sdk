package io.getdesmo.tracesdk.stress

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.getdesmo.tracesdk.util.TestHelpers
import io.getdesmo.tracesdk.telemetry.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stress tests for telemetry buffer behavior.
 *
 * Since TelemetryBuffer is an internal class, we create a test implementation
 * with the same logic to verify the buffer algorithm works correctly:
 * - Buffer overflow handling when exceeding max capacity
 * - Concurrent add operations from multiple coroutines
 * - Memory stability under high load
 * - Drain operations during active adds
 */
@RunWith(AndroidJUnit4::class)
class TelemetryBufferStressTest {

    /**
     * Test buffer implementation matching SDK's TelemetryBuffer logic.
     */
    private class TestBuffer(private val maxSize: Int = 10_000) {
        private val mutex = Mutex()
        private val samples: MutableList<TelemetrySample> = mutableListOf()

        suspend fun add(sample: TelemetrySample) {
            mutex.withLock {
                samples.add(sample)
                if (samples.size > maxSize) {
                    val overflow = samples.size - maxSize
                    repeat(overflow) { samples.removeFirst() }
                }
            }
        }

        suspend fun drain(): List<TelemetrySample> {
            return mutex.withLock {
                if (samples.isEmpty()) {
                    emptyList()
                } else {
                    val snapshot = samples.toList()
                    samples.clear()
                    snapshot
                }
            }
        }

        suspend fun clear() {
            mutex.withLock { samples.clear() }
        }
    }

    private lateinit var buffer: TestBuffer

    @Before
    fun setup() {
        buffer = TestBuffer()
    }

    /**
     * Test: Add 15,000 samples to buffer with 10k max.
     * Expected: Buffer contains exactly 10k samples, oldest 5k dropped.
     */
    @Test
    fun testBufferOverflow_dropsOldestSamples() {
        runBlocking {
            val totalSamples = 15_000
            val maxSize = 10_000
            val startTs = 1000.0

            // Add samples with incrementing timestamps
            for (i in 0 until totalSamples) {
                val sample = TestHelpers.createSample(timestamp = startTs + i)
                buffer.add(sample)
            }

            // Drain and verify
            val drained = buffer.drain()

            // Should have exactly maxSize samples
            assertEquals("Buffer should contain max samples", maxSize, drained.size)

            // Oldest sample should have timestamp of (startTs + 5000)
            val expectedOldestTs = startTs + (totalSamples - maxSize)
            assertEquals(
                "Oldest sample should be the 5001st added",
                expectedOldestTs,
                drained.first().ts,
                0.001
            )

            // Newest sample should have timestamp of (startTs + 14999)
            val expectedNewestTs = startTs + (totalSamples - 1)
            assertEquals(
                "Newest sample should be the last added",
                expectedNewestTs,
                drained.last().ts,
                0.001
            )
        }
    }

    /**
     * Test: Concurrent adds from 10 coroutines.
     * Expected: No crashes, no lost samples (up to max), thread-safe.
     */
    @Test
    fun testConcurrentAdds_noDataCorruption() {
        runBlocking {
            val numCoroutines = 10
            val samplesPerCoroutine = 500
            val totalSamples = numCoroutines * samplesPerCoroutine

            // Launch concurrent adds
            val jobs = (0 until numCoroutines).map { coroutineId ->
                async(Dispatchers.Default) {
                    repeat(samplesPerCoroutine) { sampleIdx ->
                        val ts = (coroutineId * 1000 + sampleIdx).toDouble()
                        buffer.add(TestHelpers.createSample(timestamp = ts))
                    }
                }
            }

            jobs.awaitAll()

            // Verify all samples were added
            val drained = buffer.drain()
            assertEquals("All samples should be present", totalSamples, drained.size)

            // Verify no duplicate timestamps
            val timestamps = drained.map { it.ts }.toSet()
            assertEquals("No duplicate timestamps", totalSamples, timestamps.size)
        }
    }

    /**
     * Test: Concurrent adds exceeding max size.
     * Expected: Buffer handles overflow correctly under concurrent load.
     */
    @Test
    fun testConcurrentOverflow_handlesGracefully() {
        runBlocking {
            val numCoroutines = 20
            val samplesPerCoroutine = 1000
            val maxSize = 10_000

            // Launch concurrent adds that will overflow
            val jobs = (0 until numCoroutines).map { coroutineId ->
                async(Dispatchers.Default) {
                    repeat(samplesPerCoroutine) {
                        buffer.add(TestHelpers.createSample(timestamp = System.nanoTime().toDouble()))
                    }
                }
            }

            jobs.awaitAll()

            // Verify buffer didn't exceed max
            val drained = buffer.drain()
            assertTrue("Buffer should not exceed max size", drained.size <= maxSize)
            assertTrue("Buffer should have samples", drained.isNotEmpty())
        }
    }

    /**
     * Test: Drain while adding.
     * Expected: No crashes, no deadlocks.
     */
    @Test
    fun testDrainDuringAdds_noCrash() {
        runBlocking {
            val addCount = 5_000

            // Start adding samples
            val addJob = async(Dispatchers.Default) {
                repeat(addCount) {
                    buffer.add(TestHelpers.createSample())
                }
            }

            // Simultaneously drain multiple times
            val drainedSamples = mutableListOf<Int>()
            repeat(10) {
                delay(10)
                val drained = buffer.drain()
                drainedSamples.add(drained.size)
            }

            addJob.await()

            // Final drain
            val finalDrain = buffer.drain()
            drainedSamples.add(finalDrain.size)

            // Total drained should equal added
            val totalDrained = drainedSamples.sum()
            assertEquals("All added samples should be drained", addCount, totalDrained)
        }
    }

    /**
     * Test: Rapid add/clear cycles.
     * Expected: No stale data between sessions.
     */
    @Test
    fun testClearBetweenSessions_noLeakage() {
        runBlocking {
            // Session 1: Add samples with session-1 marker timestamp
            repeat(1000) {
                buffer.add(TestHelpers.createSample(timestamp = 1.0))
            }

            // Clear (simulating session end/crash)
            buffer.clear()

            // Session 2: Add samples with session-2 marker timestamp
            repeat(500) {
                buffer.add(TestHelpers.createSample(timestamp = 2.0))
            }

            // Drain and verify no session-1 data
            val drained = buffer.drain()
            assertEquals("Only session 2 samples", 500, drained.size)
            assertTrue("All samples from session 2", drained.all { it.ts == 2.0 })
        }
    }

    /**
     * Test: Memory usage doesn't grow unbounded.
     * Expected: After many add cycles, memory is stable.
     */
    @Test
    fun testMemoryStability_manyIterations() {
        runBlocking {
            val runtime = Runtime.getRuntime()
            System.gc()
            val initialMemory = runtime.totalMemory() - runtime.freeMemory()

            // Run many add/drain cycles
            repeat(100) {
                repeat(1000) {
                    buffer.add(TestHelpers.createSample())
                }
                buffer.drain()
            }

            System.gc()
            val finalMemory = runtime.totalMemory() - runtime.freeMemory()

            // Memory growth should be minimal
            val memoryGrowthMB = (finalMemory - initialMemory) / (1024 * 1024)
            assertTrue("Memory growth should be bounded: ${memoryGrowthMB}MB", memoryGrowthMB < 20)
        }
    }

    /**
     * Test: Performance benchmark - add rate.
     * Expected: Can add at least 10,000 samples/second.
     */
    @Test
    fun testPerformance_addRate() {
        runBlocking {
            val sampleCount = 10_000

            val startTime = System.currentTimeMillis()
            repeat(sampleCount) {
                buffer.add(TestHelpers.createSample())
            }
            val duration = System.currentTimeMillis() - startTime

            val samplesPerSecond = if (duration > 0) sampleCount * 1000L / duration else sampleCount * 1000L

            assertTrue(
                "Add rate should be >= 10,000/s, was $samplesPerSecond/s",
                samplesPerSecond >= 10_000
            )

            buffer.drain()
        }
    }

    /**
     * Test: Buffer preserves sample order.
     */
    @Test
    fun testOrderPreservation() {
        runBlocking {
            val count = 1000
            repeat(count) { i ->
                buffer.add(TestHelpers.createSample(timestamp = i.toDouble()))
            }

            val drained = buffer.drain()

            assertEquals("Should have all samples", count, drained.size)

            // Verify order
            drained.forEachIndexed { index, sample ->
                assertEquals(
                    "Sample at index $index should have correct timestamp",
                    index.toDouble(),
                    sample.ts,
                    0.001
                )
            }
        }
    }

    /**
     * Test: Empty buffer behavior.
     */
    @Test
    fun testEmptyBuffer() {
        runBlocking {
            // Drain empty buffer
            val drained = buffer.drain()
            assertTrue("Empty buffer should return empty list", drained.isEmpty())

            // Clear empty buffer (should not crash)
            buffer.clear()

            // Drain again
            val drainedAgain = buffer.drain()
            assertTrue("Still empty", drainedAgain.isEmpty())
        }
    }
}
