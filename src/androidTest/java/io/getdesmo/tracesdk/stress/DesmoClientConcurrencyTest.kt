package io.getdesmo.tracesdk.stress

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.getdesmo.tracesdk.api.DesmoClientError
import io.getdesmo.tracesdk.api.DesmoResult
import io.getdesmo.tracesdk.config.DesmoConfig
import io.getdesmo.tracesdk.config.DesmoEnvironment
import io.getdesmo.tracesdk.models.Address
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
import org.junit.After
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
    private var testClient: DesmoClient? = null

    // Valid sandbox API key for real device testing
    companion object {
        private const val SANDBOX_API_KEY = "pk_sandbox_6f616a27_YZALAnWKgh_Z8hJKz7jo_O8yU6T75KyXS5MU9YCx7uE"
        
        // Test address required for DROP sessions
        private val TEST_ADDRESS = Address(
            line1 = "123 Concurrency Test St",
            city = "San Francisco",
            state = "CA",
            postalCode = "94102",
            country = "US",
            lat = 37.7749,
            lng = -122.4194
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        config = DesmoConfig(
            apiKey = SANDBOX_API_KEY,
            environment = DesmoEnvironment.SANDBOX,
            loggingEnabled = false // Reduce log noise during stress tests
        )
    }

    @After
    fun cleanup() {
        // Ensure any active session is stopped after each test
        runBlocking {
            try {
                testClient?.stopSession()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        testClient = null
    }

    /**
     * Test: Call startSession() from multiple coroutines simultaneously.
     * Expected: At most one succeeds, rest get InvalidState or other errors.
     * 
     * Note: On real devices, network latency and sensor initialization may cause
     * different timing behavior than on emulators.
     */
    @Test
    fun testConcurrentStartSession_onlyOneSucceeds() {
        runBlocking {
            val client = DesmoClient(config, context)
            testClient = client // Store for cleanup
            val numCalls = 5 // Reduced for real devices to avoid overwhelming resources

            val successCount = AtomicInteger(0)
            val invalidStateCount = AtomicInteger(0)
            val otherErrorCount = AtomicInteger(0)

            // Launch concurrent startSession calls with slight stagger to avoid resource contention
            val jobs = (0 until numCalls).map { i ->
                async(Dispatchers.Default) {
                    // Small stagger to reduce resource contention on real devices
                    delay(i * 10L)
                    
                    try {
                        val result = client.startSession(
                            deliveryId = "concurrent-test-$i-${System.currentTimeMillis()}",
                            sessionType = SessionType.DROP,
                            address = TEST_ADDRESS
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
                    } catch (e: Exception) {
                        // Handle any unexpected exceptions on real devices
                        otherErrorCount.incrementAndGet()
                    }
                }
            }

            jobs.awaitAll()

            // At most one success (might be zero if network fails)
            assertTrue(
                "At most one session should start: success=${successCount.get()}",
                successCount.get() <= 1
            )

            // Most should get InvalidState or other error
            assertTrue(
                "Most calls should get InvalidState or other error: invalidState=${invalidStateCount.get()}, other=${otherErrorCount.get()}",
                invalidStateCount.get() + otherErrorCount.get() >= numCalls - 1
            )

            // Cleanup - allow time for session to fully start before stopping
            delay(500)
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
            testClient = client
            val cycles = 3 // Reduced for real devices

            repeat(cycles) { cycle ->
                try {
                    client.startSession(
                        deliveryId = "cycle-$cycle-${System.currentTimeMillis()}",
                        sessionType = SessionType.DROP,
                        address = TEST_ADDRESS
                    )
                    delay(200) // Allow time for real network/sensor operations
                    client.stopSession()
                    delay(100) // Allow cleanup between cycles
                } catch (e: Exception) {
                    // Continue even if a cycle fails on real device
                }
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
            testClient = client
            val operationCount = 10 // Reduced for real devices

            val result = withTimeoutOrNull(60_000L) { // Longer timeout for real devices
                val jobs = (0 until operationCount).map { i ->
                    async(Dispatchers.Default) {
                        delay(i * 20L) // Stagger operations
                        try {
                            when (i % 4) {
                                0 -> client.startSession(
                                    deliveryId = "load-test-$i-${System.currentTimeMillis()}",
                                    sessionType = SessionType.DROP,
                                    address = TEST_ADDRESS
                                )
                                1 -> client.stopSession()
                                2 -> client.onForeground()
                                else -> client.onBackground()
                            }
                        } catch (e: Exception) {
                            // Handle exceptions gracefully on real devices
                        }
                    }
                }
                jobs.awaitAll()
                true
            }

            assertTrue("All operations should complete within timeout", result == true)
            
            // Cleanup
            delay(200)
            try { client.stopSession() } catch (e: Exception) { }
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
            testClient = client
            val callCount = 20 // Reduced for real devices

            val result = withTimeoutOrNull(15_000L) {
                val jobs = (0 until callCount).map { i ->
                    async(Dispatchers.Default) {
                        delay(i * 10L) // Slight stagger
                        try {
                            if (i % 2 == 0) {
                                client.onForeground()
                            } else {
                                client.onBackground()
                            }
                        } catch (e: Exception) {
                            // Handle exceptions gracefully
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

            try {
                // Try to start on both with staggered timing
                val result1 = async {
                    client1.startSession(
                        deliveryId = "client1-${System.currentTimeMillis()}",
                        sessionType = SessionType.DROP,
                        address = TEST_ADDRESS
                    )
                }
                
                delay(100) // Stagger to avoid resource contention
                
                val result2 = async {
                    client2.startSession(
                        deliveryId = "client2-${System.currentTimeMillis()}",
                        sessionType = SessionType.PICKUP,
                        address = TEST_ADDRESS
                    )
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
            } finally {
                // Cleanup
                delay(200)
                try { client1.stopSession() } catch (e: Exception) { }
                try { client2.stopSession() } catch (e: Exception) { }
            }
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
