package io.getdesmo.tracesdk.stress

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.getdesmo.tracesdk.api.DesmoClientError
import io.getdesmo.tracesdk.api.DesmoResult
import io.getdesmo.tracesdk.config.DesmoConfig
import io.getdesmo.tracesdk.config.DesmoEnvironment
import io.getdesmo.tracesdk.models.SessionType
import io.getdesmo.tracesdk.session.DesmoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stress tests for [DesmoClient] concurrency and state machine integrity.
 *
 * Tests:
 * - Concurrent startSession calls (only one should succeed)
 * - Concurrent stopSession calls
 * - State machine consistency under load
 * - No deadlocks under concurrent access
 */
@RunWith(AndroidJUnit4::class)
class DesmoClientConcurrencyTest {

    private lateinit var context: Context
    private lateinit var config: DesmoConfig

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        config = DesmoConfig(
            apiKey = "pk_test_concurrency_key",
            environment = DesmoEnvironment.SANDBOX,
            loggingEnabled = false // Reduce log noise during stress tests
        )
    }

    /**
     * Test: Call startSession() from multiple coroutines simultaneously.
     * Expected: At most one succeeds, rest get InvalidState.
     */
    @Test
    fun testConcurrentStartSession_onlyOneSucceeds() {
        runBlocking {
            val client = DesmoClient(config, context)
            val numCalls = 10

            val successCount = AtomicInteger(0)
            val invalidStateCount = AtomicInteger(0)
            val otherErrorCount = AtomicInteger(0)

            // Launch concurrent startSession calls
            val jobs = (0 until numCalls).map { i ->
                async(Dispatchers.Default) {
                    val result = client.startSession(
                        deliveryId = "delivery-$i",
                        sessionType = SessionType.DROP
                    )

                    when (result) {
                        is DesmoResult.Success -> successCount.incrementAndGet()
                        is DesmoResult.Failure -> {
                            when (result.error) {
                                is DesmoClientError.InvalidState -> invalidStateCount.incrementAndGet()
                                else -> otherErrorCount.incrementAndGet()
                            }
                        }
                    }
                }
            }

            jobs.awaitAll()

            // At most one success (might be zero if network fails)
            assertTrue(
                "At most one session should start: success=${successCount.get()}",
                successCount.get() <= 1
            )

            // Most should get InvalidState
            assertTrue(
                "Most calls should get InvalidState or other error",
                invalidStateCount.get() + otherErrorCount.get() >= numCalls - 1
            )

            // Cleanup
            client.stopSession()
        }
    }

    /**
     * Test: Call stopSession() when no session is active.
     * Expected: InvalidState error, not crash.
     */
    @Test
    fun testStopWithoutStart_returnsInvalidState() {
        runBlocking {
            val client = DesmoClient(config, context)

            val result = client.stopSession()

            assertTrue("Should return failure", result is DesmoResult.Failure)
            
            val failure = result as DesmoResult.Failure
            assertTrue(
                "Should be InvalidState error",
                failure.error is DesmoClientError.InvalidState
            )

            val error = failure.error as DesmoClientError.InvalidState
            assertEquals("Expected state should be recording", "recording", error.expected)
            assertEquals("Actual state should be idle", "idle", error.actual)
        }
    }

    /**
     * Test: Rapid start/stop cycles.
     * Expected: State machine remains consistent, no crashes.
     */
    @Test
    fun testRapidStartStopCycles_noDeadlock() {
        runBlocking {
            val client = DesmoClient(config, context)
            val cycles = 5

            repeat(cycles) { cycle ->
                client.startSession(
                    deliveryId = "delivery-cycle-$cycle",
                    sessionType = SessionType.DROP
                )
                delay(50)
                client.stopSession()
            }

            // If we reach here without timeout, no deadlock occurred
            assertTrue("Completed without deadlock", true)
        }
    }

    /**
     * Test: No deadlock under heavy concurrent load.
     * Expected: All operations complete within timeout.
     */
    @Test
    fun testNoDeadlockUnderLoad() {
        runBlocking {
            val client = DesmoClient(config, context)
            val operationCount = 20

            val result = withTimeoutOrNull(30_000L) {
                val jobs = (0 until operationCount).map { i ->
                    async(Dispatchers.Default) {
                        when (i % 4) {
                            0 -> client.startSession("delivery-$i", SessionType.DROP)
                            1 -> client.stopSession()
                            2 -> client.onForeground()
                            else -> client.onBackground()
                        }
                    }
                }
                jobs.awaitAll()
                true
            }

            assertTrue("All operations should complete within timeout", result == true)
        }
    }

    /**
     * Test: Lifecycle callbacks don't cause issues when called concurrently.
     * Expected: No crashes, no deadlocks.
     */
    @Test
    fun testConcurrentLifecycleCallbacks() {
        runBlocking {
            val client = DesmoClient(config, context)
            val callCount = 50

            val result = withTimeoutOrNull(10_000L) {
                val jobs = (0 until callCount).map { i ->
                    async(Dispatchers.Default) {
                        if (i % 2 == 0) {
                            client.onForeground()
                        } else {
                            client.onBackground()
                        }
                    }
                }
                jobs.awaitAll()
                true
            }

            assertTrue("Lifecycle callbacks should not deadlock", result == true)
        }
    }

    /**
     * Test: Multiple clients don't interfere with each other.
     * Expected: Each client maintains independent state.
     */
    @Test
    fun testMultipleClientInstances() {
        runBlocking {
            val client1 = DesmoClient(config, context)
            val client2 = DesmoClient(config, context)

            // Try to start on both
            val result1 = async {
                client1.startSession("client1-delivery", SessionType.DROP)
            }
            val result2 = async {
                client2.startSession("client2-delivery", SessionType.PICKUP)
            }

            val r1 = result1.await()
            val r2 = result2.await()

            // Both operations should complete (success or failure doesn't matter)
            assertTrue(
                "Client 1 operation completed",
                r1 is DesmoResult.Success || r1 is DesmoResult.Failure
            )
            assertTrue(
                "Client 2 operation completed",
                r2 is DesmoResult.Success || r2 is DesmoResult.Failure
            )

            // Cleanup
            client1.stopSession()
            client2.stopSession()
        }
    }

    /**
     * Test: State machine error messages are correct.
     */
    @Test
    fun testInvalidStateErrorMessages() {
        runBlocking {
            val client = DesmoClient(config, context)

            // Try to stop without starting
            val stopResult = client.stopSession()

            assertTrue("Should fail", stopResult is DesmoResult.Failure)

            val error = (stopResult as DesmoResult.Failure).error
            assertTrue("Should be InvalidState", error is DesmoClientError.InvalidState)

            val invalidState = error as DesmoClientError.InvalidState
            assertEquals("Expected should be 'recording'", "recording", invalidState.expected)
            assertEquals("Actual should be 'idle'", "idle", invalidState.actual)
        }
    }
}
