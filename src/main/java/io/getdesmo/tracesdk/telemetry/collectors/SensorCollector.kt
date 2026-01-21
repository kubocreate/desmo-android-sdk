package io.getdesmo.tracesdk.telemetry.collectors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import io.getdesmo.tracesdk.telemetry.BarometerPayload
import io.getdesmo.tracesdk.telemetry.ImuPayload
import io.getdesmo.tracesdk.telemetry.MagnetometerPayload
import io.getdesmo.tracesdk.telemetry.SensorAvailability

/**
 * Collects IMU sensor data (accelerometer, gyroscope, gravity, rotation), barometer, and magnetometer.
 *
 * Samples are throttled to [sampleRateHz] to prevent excessive data collection. Calls [onSample] at
 * the configured rate with the accurate sensor timestamp.
 */
internal class SensorCollector(
        private val sensorManager: SensorManager,
        private val sampleRateHz: Int,
        private val loggingEnabled: Boolean,
        private val onSample: (Double, ImuPayload, BarometerPayload?, MagnetometerPayload?) -> Unit
) {

    private companion object {
        private const val TAG = "DesmoSDK"
    }

    // Boot time offset for converting sensor timestamps to wall-clock time
    // SensorEvent.timestamp is nanoseconds since boot (elapsedRealtimeNanos)
    // We convert to Unix timestamp by adding this offset
    private val bootTimeOffsetNanos: Long = System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos()

    // Throttling: minimum ns between emitted samples (using sensor timestamp for accuracy)
    private val minIntervalNanos = 1_000_000_000L / sampleRateHz
    @Volatile private var lastEmitTimeNanos = 0L

    // Latest barometer reading
    @Volatile
    var latestBarometer: BarometerPayload? = null
        private set

    // Latest magnetometer reading
    @Volatile
    var latestMagnetometer: MagnetometerPayload? = null
        private set

    // IMU component values
    private val accelValues = DoubleArray(3)
    private val gyroValues = DoubleArray(3)
    private val gravityValues = DoubleArray(3)
    private val attitudeValues = DoubleArray(4)

    // Latest sensor timestamp (nanoseconds since boot)
    @Volatile private var latestSensorTimestampNanos = 0L

    @Volatile private var hasAccel = false
    @Volatile private var hasGyro = false
    @Volatile private var hasGravity = false
    @Volatile private var hasAttitude = false

    private val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // Wrapped in try/catch to guarantee SDK never crashes the host app
                    try {
                        // Capture sensor timestamp for accurate timing
                        val eventTimestampNanos = event.timestamp
                        
                        when (event.sensor.type) {
                            Sensor.TYPE_ACCELEROMETER -> {
                                accelValues[0] = event.values[0].toDouble()
                                accelValues[1] = event.values[1].toDouble()
                                accelValues[2] = event.values[2].toDouble()
                                hasAccel = true
                                latestSensorTimestampNanos = eventTimestampNanos
                                emitSample(eventTimestampNanos)
                            }
                            Sensor.TYPE_GYROSCOPE -> {
                                gyroValues[0] = event.values[0].toDouble()
                                gyroValues[1] = event.values[1].toDouble()
                                gyroValues[2] = event.values[2].toDouble()
                                hasGyro = true
                                latestSensorTimestampNanos = eventTimestampNanos
                                emitSample(eventTimestampNanos)
                            }
                            Sensor.TYPE_GRAVITY -> {
                                gravityValues[0] = event.values[0].toDouble()
                                gravityValues[1] = event.values[1].toDouble()
                                gravityValues[2] = event.values[2].toDouble()
                                hasGravity = true
                                latestSensorTimestampNanos = eventTimestampNanos
                                emitSample(eventTimestampNanos)
                            }
                            Sensor.TYPE_ROTATION_VECTOR -> {
                                // Convert rotation vector to quaternion [x, y, z, w]
                                val q = FloatArray(4)
                                SensorManager.getQuaternionFromVector(q, event.values)
                                attitudeValues[0] = q[1].toDouble() // x
                                attitudeValues[1] = q[2].toDouble() // y
                                attitudeValues[2] = q[3].toDouble() // z
                                attitudeValues[3] = q[0].toDouble() // w
                                hasAttitude = true
                                latestSensorTimestampNanos = eventTimestampNanos
                                emitSample(eventTimestampNanos)
                            }
                            Sensor.TYPE_PRESSURE -> {
                                latestBarometer =
                                        BarometerPayload(
                                                pressureHpa = event.values[0].toDouble(),
                                                relativeAltitudeM =
                                                        null // Android doesn't provide relative
                                                // altitude
                                                )
                            }
                            Sensor.TYPE_MAGNETIC_FIELD -> {
                                latestMagnetometer =
                                        MagnetometerPayload(
                                                x = event.values[0].toDouble(),  // μT
                                                y = event.values[1].toDouble(),
                                                z = event.values[2].toDouble()
                                        )
                            }
                        }
                    } catch (t: Throwable) {
                        // Log but never propagate - SDK must not crash host app
                        if (loggingEnabled) {
                            Log.e(TAG, "Sensor callback error: $t")
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    // No-op
                }
            }

    private fun emitSample(eventTimestampNanos: Long) {
        // Throttle: only emit if enough time has passed since last sample
        // Using sensor timestamp for accurate throttling
        if (eventTimestampNanos - lastEmitTimeNanos < minIntervalNanos) {
            return // Skip this sample - too soon
        }
        lastEmitTimeNanos = eventTimestampNanos

        // Convert sensor timestamp (nanoseconds since boot) to Unix timestamp (seconds)
        val tsSeconds = (eventTimestampNanos + bootTimeOffsetNanos) / 1_000_000_000.0

        val imu =
                ImuPayload(
                        accel = if (hasAccel) accelValues.toList() else emptyList(),
                        gyro = if (hasGyro) gyroValues.toList() else emptyList(),
                        gravity = if (hasGravity) gravityValues.toList() else emptyList(),
                        attitude = if (hasAttitude) attitudeValues.toList() else emptyList()
                )
        onSample(tsSeconds, imu, latestBarometer, latestMagnetometer)
    }

    fun start() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val magnet = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // ~50 Hz updates for motion analysis
        val delayUs = SensorManager.SENSOR_DELAY_GAME

        accel?.let { sensorManager.registerListener(listener, it, delayUs) }
        gyro?.let { sensorManager.registerListener(listener, it, delayUs) }
        gravity?.let { sensorManager.registerListener(listener, it, delayUs) }
        rotation?.let { sensorManager.registerListener(listener, it, delayUs) }
        pressure?.let { sensorManager.registerListener(listener, it, delayUs) }
        magnet?.let { sensorManager.registerListener(listener, it, delayUs) }

        if (loggingEnabled) {
            Log.d(TAG, "Sensors started (accel/gyro/gravity/rotation/pressure/magnet)")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        if (loggingEnabled) {
            Log.d(TAG, "Sensors stopped")
        }
    }

    /**
     * Resume sensor collection after app comes back to foreground.
     * Android may throttle or stop sensor updates when app is in background,
     * so we re-register all listeners to ensure data collection continues.
     */
    fun resume() {
        // Unregister first to avoid duplicate registrations
        sensorManager.unregisterListener(listener)
        // Re-register all sensors
        start()
        if (loggingEnabled) {
            Log.d(TAG, "Sensors resumed after foreground")
        }
    }

    /** Returns which sensors are available on this device. */
    fun getAvailability(): SensorAvailability {
        return SensorAvailability(
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
                gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
                gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null,
                rotationVector =
                        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null,
                barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null,
                gps = false, // GPS availability is handled by LocationCollector
                magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        )
    }
}
