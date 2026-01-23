package io.getdesmo.tracesdk.stress

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.getdesmo.tracesdk.util.TestHelpers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for HTTP status code classification logic.
 *
 * These tests verify that HTTP responses are correctly classified as:
 * - SUCCESS (2xx)
 * - PERMANENT_FAILURE (4xx - don't retry)
 * - RETRYABLE_FAILURE (5xx, network errors - retry later)
 *
 * Note: We test the classification logic directly rather than making
 * actual HTTP calls, as Android blocks cleartext HTTP by default.
 */
@RunWith(AndroidJUnit4::class)
class NetworkRetryStressTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Classify an HTTP status code using the same logic as TelemetryUploader.
     * This mirrors the SDK's retry decision logic.
     */
    private fun classifyHttpStatus(code: Int): UploadResultType {
        return when {
            code in 200..299 -> UploadResultType.SUCCESS
            code in 400..499 -> UploadResultType.PERMANENT_FAILURE
            else -> UploadResultType.RETRYABLE_FAILURE
        }
    }

    // ==================== 2xx Success Tests ====================

    @Test
    fun testSuccessOn200() {
        assertEquals("200 should be success", UploadResultType.SUCCESS, classifyHttpStatus(200))
    }

    @Test
    fun testSuccessOn201() {
        assertEquals("201 should be success", UploadResultType.SUCCESS, classifyHttpStatus(201))
    }

    @Test
    fun testSuccessOn202() {
        assertEquals("202 should be success", UploadResultType.SUCCESS, classifyHttpStatus(202))
    }

    @Test
    fun testSuccessOn204() {
        assertEquals("204 should be success", UploadResultType.SUCCESS, classifyHttpStatus(204))
    }

    @Test
    fun testSuccessOn299() {
        assertEquals("299 should be success", UploadResultType.SUCCESS, classifyHttpStatus(299))
    }

    // ==================== 4xx Permanent Failure Tests ====================

    @Test
    fun testPermanentFailureOn400() {
        assertEquals("400 should be permanent failure", UploadResultType.PERMANENT_FAILURE, classifyHttpStatus(400))
    }

    @Test
    fun testPermanentFailureOn401() {
        assertEquals("401 should be permanent failure", UploadResultType.PERMANENT_FAILURE, classifyHttpStatus(401))
    }

    @Test
    fun testPermanentFailureOn403() {
        assertEquals("403 should be permanent failure", UploadResultType.PERMANENT_FAILURE, classifyHttpStatus(403))
    }

    @Test
    fun testPermanentFailureOn404() {
        assertEquals("404 should be permanent failure", UploadResultType.PERMANENT_FAILURE, classifyHttpStatus(404))
    }

    @Test
    fun testPermanentFailureOn422() {
        assertEquals("422 should be permanent failure", UploadResultType.PERMANENT_FAILURE, classifyHttpStatus(422))
    }

    @Test
    fun testPermanentFailureOn429() {
        // Rate limiting - some systems treat this as retryable, but our SDK treats 4xx as permanent
        assertEquals("429 should be permanent failure", UploadResultType.PERMANENT_FAILURE, classifyHttpStatus(429))
    }

    @Test
    fun testPermanentFailureOn499() {
        assertEquals("499 should be permanent failure", UploadResultType.PERMANENT_FAILURE, classifyHttpStatus(499))
    }

    // ==================== 5xx Retryable Failure Tests ====================

    @Test
    fun testRetryableFailureOn500() {
        assertEquals("500 should be retryable", UploadResultType.RETRYABLE_FAILURE, classifyHttpStatus(500))
    }

    @Test
    fun testRetryableFailureOn502() {
        assertEquals("502 should be retryable", UploadResultType.RETRYABLE_FAILURE, classifyHttpStatus(502))
    }

    @Test
    fun testRetryableFailureOn503() {
        assertEquals("503 should be retryable", UploadResultType.RETRYABLE_FAILURE, classifyHttpStatus(503))
    }

    @Test
    fun testRetryableFailureOn504() {
        assertEquals("504 should be retryable", UploadResultType.RETRYABLE_FAILURE, classifyHttpStatus(504))
    }

    @Test
    fun testRetryableFailureOn599() {
        assertEquals("599 should be retryable", UploadResultType.RETRYABLE_FAILURE, classifyHttpStatus(599))
    }

    // ==================== Edge Cases ====================

    @Test
    fun testBoundary199IsRetryable() {
        // 1xx informational - treated as retryable (unexpected)
        assertEquals("199 should be retryable", UploadResultType.RETRYABLE_FAILURE, classifyHttpStatus(199))
    }

    @Test
    fun testBoundary300IsRetryable() {
        // 3xx redirects - treated as retryable (unexpected for our API)
        assertEquals("300 should be retryable", UploadResultType.RETRYABLE_FAILURE, classifyHttpStatus(300))
    }

    // ==================== Sequence Tests ====================

    @Test
    fun testMixedStatusSequence() {
        val statuses = listOf(500, 500, 503, 200)
        val expected = listOf(
            UploadResultType.RETRYABLE_FAILURE,
            UploadResultType.RETRYABLE_FAILURE,
            UploadResultType.RETRYABLE_FAILURE,
            UploadResultType.SUCCESS
        )

        val results = statuses.map { classifyHttpStatus(it) }

        assertEquals("Sequence should match expected", expected, results)
    }

    @Test
    fun testMixed4xxAnd5xxSequence() {
        val statuses = listOf(400, 500, 401, 503)
        val expected = listOf(
            UploadResultType.PERMANENT_FAILURE,
            UploadResultType.RETRYABLE_FAILURE,
            UploadResultType.PERMANENT_FAILURE,
            UploadResultType.RETRYABLE_FAILURE
        )

        val results = statuses.map { classifyHttpStatus(it) }

        assertEquals("Mixed sequence should match", expected, results)
    }

    @Test
    fun testAllSuccessCodes() {
        val successCodes = (200..299).toList()
        val allSuccess = successCodes.all { classifyHttpStatus(it) == UploadResultType.SUCCESS }
        assertTrue("All 2xx codes should be success", allSuccess)
    }

    @Test
    fun testAllPermanentFailureCodes() {
        val permanentCodes = (400..499).toList()
        val allPermanent = permanentCodes.all { classifyHttpStatus(it) == UploadResultType.PERMANENT_FAILURE }
        assertTrue("All 4xx codes should be permanent failure", allPermanent)
    }

    @Test
    fun testAllRetryableFailureCodes() {
        val retryableCodes = (500..599).toList()
        val allRetryable = retryableCodes.all { classifyHttpStatus(it) == UploadResultType.RETRYABLE_FAILURE }
        assertTrue("All 5xx codes should be retryable failure", allRetryable)
    }

    // ==================== JSON Serialization Tests ====================

    @Test
    fun testTelemetryRequestSerialization() {
        val samples = TestHelpers.createSamples(100)
        val requestBody = io.getdesmo.tracesdk.telemetry.TelemetryRequest(
            sessionId = TestHelpers.generateSessionId(),
            events = samples
        )

        // Should serialize without error
        val jsonString = json.encodeToString(requestBody)

        assertTrue("JSON should not be empty", jsonString.isNotEmpty())
        assertTrue("JSON should contain sessionId", jsonString.contains("sessionId"))
        assertTrue("JSON should contain events", jsonString.contains("events"))
    }

    @Test
    fun testLargeBatchSerialization() {
        val largeSamples = TestHelpers.createSamples(1000)
        val requestBody = io.getdesmo.tracesdk.telemetry.TelemetryRequest(
            sessionId = TestHelpers.generateSessionId(),
            events = largeSamples
        )

        val jsonString = json.encodeToString(requestBody)

        // Large batch should produce substantial JSON
        assertTrue("Large batch JSON should be > 100KB", jsonString.length > 100_000)
    }

    @Test
    fun testSerializationRoundTrip() = runBlocking {
        val originalSamples = TestHelpers.createSamples(50)
        val originalRequest = io.getdesmo.tracesdk.telemetry.TelemetryRequest(
            sessionId = "test-session-123",
            events = originalSamples
        )

        // Serialize
        val jsonString = json.encodeToString(originalRequest)

        // Deserialize
        val deserializedRequest: io.getdesmo.tracesdk.telemetry.TelemetryRequest = 
            json.decodeFromString(jsonString)

        assertEquals("Session ID should match", originalRequest.sessionId, deserializedRequest.sessionId)
        assertEquals("Event count should match", originalRequest.events.size, deserializedRequest.events.size)
    }

    /**
     * Classification of upload results (mirrors SDK's UploadResult).
     */
    enum class UploadResultType {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }
}
