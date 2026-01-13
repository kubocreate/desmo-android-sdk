package io.getdesmo.tracesdk.config

/**
 * Configuration for telemetry collection behavior.
 *
 * All values have sensible defaults - most customers won't need to change these.
 *
 * @property sampleRateHz IMU sensor sample rate in Hz (1-100). Higher = more data, more battery.
 *                        Default 50 Hz is good for motion analysis.
 * @property locationUpdateMs GPS update interval in milliseconds. Default 2000ms (every 2 seconds).
 * @property uploadIntervalMs How often to batch and upload telemetry in milliseconds. Default 5000ms.
 */
data class TelemetryConfig(
    val sampleRateHz: Int = 50,
    val locationUpdateMs: Long = 2000,
    val uploadIntervalMs: Long = 5000
) {
    init {
        require(sampleRateHz in 1..100) { "Sample rate must be 1-100 Hz" }
        require(locationUpdateMs >= 500) { "Location update interval must be at least 500ms" }
        require(uploadIntervalMs >= 1000) { "Upload interval must be at least 1000ms" }
    }
}
