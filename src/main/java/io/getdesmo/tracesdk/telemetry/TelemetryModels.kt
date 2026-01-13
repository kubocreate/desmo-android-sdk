package io.getdesmo.tracesdk.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format models for telemetry uploads.
 *
 * Mirrors the Swift models and backend schemas.
 */

@Serializable
data class TelemetryRequest(
    val sessionId: String,
    val events: List<TelemetrySample>
)

@Serializable
data class ImuPayload(
    val accel: List<Double>,
    val gyro: List<Double>,
    val gravity: List<Double>,
    val attitude: List<Double>
)

@Serializable
data class BarometerPayload(
    val pressureHpa: Double,
    val relativeAltitudeM: Double? = null
)

@Serializable
data class PositionPayload(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val source: String? = null
)

@Serializable
data class ContextPayload(
    val screenOn: Boolean? = null,
    val appForeground: Boolean? = null,
    val batteryLevel: Double? = null,
    val charging: Boolean? = null,
    val network: String? = null,
    val motionActivity: String? = null
)

@Serializable
data class MagnetometerPayload(
    val x: Double,  // μT (microtesla)
    val y: Double,
    val z: Double
)

@Serializable
data class TelemetrySample(
    val ts: Double,
    val imu: ImuPayload? = null,
    val barometer: BarometerPayload? = null,
    val position: PositionPayload? = null,
    val context: ContextPayload? = null,
    val magnetometer: MagnetometerPayload? = null
)

/**
 * Reports which sensors are available on the device.
 * Sent with session start so backend knows what data to expect.
 *
 * Uses @SerialName to match backend field naming convention (hasXxx).
 *
 * Note: Android can provide rotation/attitude data even without a hardware gyroscope
 * by fusing accelerometer + magnetometer data. The [gyroscope] field indicates
 * hardware gyroscope presence, while [rotationVector] indicates whether rotation
 * data (from any source) is available.
 */
@Serializable
data class SensorAvailability(
    /** Hardware accelerometer sensor */
    @SerialName("hasAccelerometer") val accelerometer: Boolean = false,
    /** Hardware gyroscope sensor (not fused rotation) */
    @SerialName("hasGyroscope") val gyroscope: Boolean = false,
    /** Gravity sensor (often software-derived from accelerometer) */
    @SerialName("hasGravity") val gravity: Boolean = false,
    /** Rotation vector sensor (may be fused from accel+mag without hardware gyro) */
    @SerialName("hasRotationVector") val rotationVector: Boolean = false,
    /** Hardware barometer/pressure sensor */
    @SerialName("hasBarometer") val barometer: Boolean = false,
    /** GPS/location available */
    @SerialName("hasGps") val gps: Boolean = false,
    /** Hardware magnetometer/compass */
    @SerialName("hasMagnetometer") val magnetometer: Boolean = false
)

