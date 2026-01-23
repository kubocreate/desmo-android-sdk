package io.getdesmo.tracesdk.util

import io.getdesmo.tracesdk.telemetry.BarometerPayload
import io.getdesmo.tracesdk.telemetry.ContextPayload
import io.getdesmo.tracesdk.telemetry.ImuPayload
import io.getdesmo.tracesdk.telemetry.MagnetometerPayload
import io.getdesmo.tracesdk.telemetry.PositionPayload
import io.getdesmo.tracesdk.telemetry.TelemetrySample
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/**
 * Test utilities for stress testing the Desmo SDK.
 */
object TestHelpers {

    /**
     * Generate a single realistic telemetry sample.
     *
     * @param timestamp Unix timestamp in seconds
     * @param includePosition Whether to include GPS position data
     * @param includeContext Whether to include context data
     */
    fun createSample(
        timestamp: Double = System.currentTimeMillis() / 1000.0,
        includePosition: Boolean = true,
        includeContext: Boolean = false
    ): TelemetrySample {
        return TelemetrySample(
            ts = timestamp,
            imu = createImuPayload(),
            barometer = createBarometerPayload(),
            position = if (includePosition) createPositionPayload() else null,
            context = if (includeContext) createContextPayload() else null,
            magnetometer = createMagnetometerPayload()
        )
    }

    /**
     * Generate a list of telemetry samples with sequential timestamps.
     *
     * @param count Number of samples to generate
     * @param startTimestamp Starting timestamp in seconds
     * @param intervalMs Interval between samples in milliseconds
     */
    fun createSamples(
        count: Int,
        startTimestamp: Double = System.currentTimeMillis() / 1000.0,
        intervalMs: Long = 10 // 100 Hz default
    ): List<TelemetrySample> {
        return (0 until count).map { i ->
            createSample(
                timestamp = startTimestamp + (i * intervalMs / 1000.0),
                includePosition = i % 100 == 0, // Position every 100 samples
                includeContext = i % 500 == 0  // Context every 500 samples
            )
        }
    }

    /**
     * Create realistic IMU payload with slight variations.
     */
    fun createImuPayload(): ImuPayload {
        return ImuPayload(
            accel = listOf(
                Random.nextDouble(-0.5, 0.5),      // x - slight movement
                Random.nextDouble(-0.5, 0.5),      // y - slight movement
                Random.nextDouble(9.5, 10.0)       // z - gravity ~9.8 m/s²
            ),
            gyro = listOf(
                Random.nextDouble(-0.1, 0.1),      // x rotation
                Random.nextDouble(-0.1, 0.1),      // y rotation
                Random.nextDouble(-0.1, 0.1)       // z rotation
            ),
            gravity = listOf(0.0, 0.0, 9.81),
            attitude = listOf(
                Random.nextDouble(-0.1, 0.1),      // pitch
                Random.nextDouble(-0.1, 0.1),      // roll
                Random.nextDouble(-Math.PI, Math.PI) // yaw
            )
        )
    }

    /**
     * Create realistic barometer payload.
     */
    fun createBarometerPayload(): BarometerPayload {
        return BarometerPayload(
            pressureHpa = Random.nextDouble(1000.0, 1020.0),
            relativeAltitudeM = Random.nextDouble(-5.0, 5.0)
        )
    }

    /**
     * Create realistic position payload (somewhere in San Francisco).
     */
    fun createPositionPayload(): PositionPayload {
        return PositionPayload(
            lat = 37.7749 + Random.nextDouble(-0.01, 0.01),
            lng = -122.4194 + Random.nextDouble(-0.01, 0.01),
            accuracyM = Random.nextDouble(3.0, 15.0),
            altitudeM = Random.nextDouble(0.0, 100.0),
            speedMps = Random.nextDouble(0.0, 15.0),
            bearingDeg = Random.nextDouble(0.0, 360.0),
            source = "gps"
        )
    }

    /**
     * Create realistic context payload.
     */
    fun createContextPayload(): ContextPayload {
        return ContextPayload(
            screenOn = true,
            appForeground = true,
            batteryLevel = Random.nextDouble(0.2, 1.0),
            charging = Random.nextBoolean(),
            network = listOf("wifi", "cellular", "none").random(),
            motionActivity = listOf("walking", "in_vehicle", "still", "on_bicycle").random()
        )
    }

    /**
     * Create realistic magnetometer payload.
     */
    fun createMagnetometerPayload(): MagnetometerPayload {
        return MagnetometerPayload(
            x = Random.nextDouble(-50.0, 50.0),
            y = Random.nextDouble(-50.0, 50.0),
            z = Random.nextDouble(-50.0, 50.0)
        )
    }

    /**
     * Generate a unique session ID for testing.
     */
    fun generateSessionId(): String {
        return "test-session-${System.currentTimeMillis()}-${Random.nextInt(10000)}"
    }

    /**
     * Measure execution time of a block and return the result with duration.
     */
    inline fun <T> measureTimeWithResult(block: () -> T): Pair<T, Long> {
        val start = System.currentTimeMillis()
        val result = block()
        val duration = System.currentTimeMillis() - start
        return Pair(result, duration)
    }

    /**
     * Run a block multiple times concurrently and collect results.
     */
    suspend fun <T> runConcurrently(
        times: Int,
        block: suspend (Int) -> T
    ): List<T> = coroutineScope {
        (0 until times).map { i ->
            async { block(i) }
        }.awaitAll()
    }
}
