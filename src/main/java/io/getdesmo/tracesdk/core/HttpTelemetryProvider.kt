package io.getdesmo.tracesdk.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import io.getdesmo.tracesdk.http.HttpClient
import io.getdesmo.tracesdk.http.HttpError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * TelemetryProvider that uses Android sensors + location and
 * sends batched telemetry to the backend over HTTP.
 *
 * This is an analogue of the iOS HTTPTelemetryProvider:
 * - Collects IMU (accelerometer, gyroscope, gravity, rotation/attitude).
 * - Collects barometer (pressure).
 * - Optionally collects GPS location.
 * - Adds basic device context (screen, battery, network).
 * - Buffers samples and periodically uploads them.
 */
internal class HttpTelemetryProvider(
    private val context: Context,
    private val httpClient: HttpClient,
    private val loggingEnabled: Boolean
) : TelemetryProvider {

    private val appContext = context.applicationContext

    private val sensorManager: SensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Buffering
    private val bufferMutex = Mutex()
    private val buffer: MutableList<TelemetrySample> = mutableListOf()
    private val maxBufferSize = 10_000

    private var sessionId: String? = null
    private var uploadJob: Job? = null

    // Latest position
    @Volatile
    private var latestPosition: PositionPayload? = null

    // Latest barometer
    @Volatile
    private var latestBarometer: BarometerPayload? = null

    // Latest IMU components
    private val accelValues = DoubleArray(3)
    private val gyroValues = DoubleArray(3)
    private val gravityValues = DoubleArray(3)
    private val attitudeValues = DoubleArray(4)
    @Volatile private var hasAccel = false
    @Volatile private var hasGyro = false
    @Volatile private var hasGravity = false
    @Volatile private var hasAttitude = false

    // Sensor listeners
    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val tsSeconds = System.currentTimeMillis() / 1000.0

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelValues[0] = event.values[0].toDouble()
                    accelValues[1] = event.values[1].toDouble()
                    accelValues[2] = event.values[2].toDouble()
                    hasAccel = true
                    appendImuSample(tsSeconds)
                }

                Sensor.TYPE_GYROSCOPE -> {
                    gyroValues[0] = event.values[0].toDouble()
                    gyroValues[1] = event.values[1].toDouble()
                    gyroValues[2] = event.values[2].toDouble()
                    hasGyro = true
                    appendImuSample(tsSeconds)
                }

                Sensor.TYPE_GRAVITY -> {
                    gravityValues[0] = event.values[0].toDouble()
                    gravityValues[1] = event.values[1].toDouble()
                    gravityValues[2] = event.values[2].toDouble()
                    hasGravity = true
                    appendImuSample(tsSeconds)
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
                    appendImuSample(tsSeconds)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // No-op
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestPosition = PositionPayload(
                lat = location.latitude,
                lng = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                altitudeM = if (location.hasAltitude()) location.altitude else null,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                source = location.provider
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun start(sessionId: String) {
        this.sessionId = sessionId

        startSensors()
        startLocationUpdates()
        startUploadLoop()
    }

    override fun stop() {
        stopUploadLoop()
        stopSensors()
        stopLocationUpdates()
    }

    override suspend fun flush() {
        val batch: List<TelemetrySample> = bufferMutex.withLock {
            if (buffer.isEmpty()) {
                emptyList()
            } else {
                val snapshot = buffer.toList()
                buffer.clear()
                snapshot
            }
        }

        if (batch.isNotEmpty()) {
            sendBatch(batch)
        }
    }

    // --- Sensors ---

    private fun startSensors() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        // 50 Hz updates is usually enough for motion analysis
        val delayUs = SensorManager.SENSOR_DELAY_GAME

        accel?.let {
            sensorManager.registerListener(imuListener, it, delayUs)
        }
        gyro?.let {
            sensorManager.registerListener(imuListener, it, delayUs)
        }
        gravity?.let {
            sensorManager.registerListener(imuListener, it, delayUs)
        }
        rotation?.let {
            sensorManager.registerListener(imuListener, it, delayUs)
        }
        pressure?.let {
            sensorManager.registerListener(imuListener, it, delayUs)
        }

        if (loggingEnabled) {
            println("[DesmoSDK] Telemetry sensors started (accel/gyro/gravity/rotation/pressure)")
        }
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(imuListener)
        if (loggingEnabled) {
            println("[DesmoSDK] Telemetry sensors stopped")
        }
    }

    // --- Location ---

    private fun startLocationUpdates() {
        val lm = locationManager ?: return
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5_000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )

            if (loggingEnabled) {
                println("[DesmoSDK] Telemetry location updates started")
            }
        } catch (se: SecurityException) {
            if (loggingEnabled) {
                println("[DesmoSDK] Location permission not granted, skipping location telemetry")
            }
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(locationListener)
        if (loggingEnabled) {
            println("[DesmoSDK] Telemetry location updates stopped")
        }
    }

    // --- Buffer & Upload ---

    private fun appendSample(sample: TelemetrySample) {
        scope.launch {
            bufferMutex.withLock {
                buffer.add(sample)
                if (buffer.size > maxBufferSize) {
                    // Drop the oldest samples so we keep at most maxBufferSize items.
                    // We can't pass a count into removeFirst(), so we remove repeatedly.
                    val overflow = buffer.size - maxBufferSize
                    repeat(overflow) {
                        buffer.removeFirst()
                    }
                }
            }
        }
    }

    private fun appendImuSample(tsSeconds: Double) {
        val accel = if (hasAccel) accelValues.toList() else emptyList()
        val gyro = if (hasGyro) gyroValues.toList() else emptyList()
        val gravity = if (hasGravity) gravityValues.toList() else emptyList()
        val attitude = if (hasAttitude) attitudeValues.toList() else emptyList()

        val imu = ImuPayload(
            accel = accel,
            gyro = gyro,
            gravity = gravity,
            attitude = attitude
        )

        val sample = TelemetrySample(
            ts = tsSeconds,
            imu = imu,
            barometer = latestBarometer,
            position = latestPosition,
            context = buildContextPayload()
        )

        appendSample(sample)
    }

    // --- Context ---

    private fun buildContextPayload(): ContextPayload {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryLevel = if (level >= 0 && scale > 0) {
            level.toDouble() / scale.toDouble()
        } else {
            null
        }

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val screenOn = pm?.isInteractive ?: true

        val network = when {
            cm == null -> "unknown"
            else -> {
                val networkCaps = cm.getNetworkCapabilities(cm.activeNetwork)
                when {
                    networkCaps == null -> "none"
                    networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "unknown"
                }
            }
        }

        return ContextPayload(
            screenOn = screenOn,
            appForeground = true, // Approximation; SDK runs while app is active.
            batteryLevel = batteryLevel,
            charging = charging,
            network = network,
            motionActivity = null
        )
    }

    private fun startUploadLoop() {
        if (uploadJob != null) return

        uploadJob = scope.launch {
            while (true) {
                delay(5_000L)

                val batch: List<TelemetrySample> = bufferMutex.withLock {
                    if (buffer.isEmpty()) {
                        emptyList()
                    } else {
                        val snapshot = buffer.toList()
                        buffer.clear()
                        snapshot
                    }
                }

                if (batch.isNotEmpty()) {
                    sendBatch(batch)
                }
            }
        }
    }

    private fun stopUploadLoop() {
        uploadJob?.cancel()
        uploadJob = null
    }

    private suspend fun sendBatch(batch: List<TelemetrySample>) {
        val sessionIdSnapshot = sessionId ?: return
        val body = TelemetryRequest(
            sessionId = sessionIdSnapshot,
            events = batch
        )

        val jsonBody = Json.encodeToString(body)

        try {
            if (loggingEnabled) {
                println("[DesmoSDK] Sending telemetry batch: ${batch.size} samples")
            }
            httpClient.post(path = "/v1/telemetry", jsonBody = jsonBody)
            if (loggingEnabled) {
                println("[DesmoSDK] Telemetry batch success")
            }
        } catch (e: HttpError) {
            if (loggingEnabled) {
                println("[DesmoSDK] Telemetry batch failed: $e")
            }
        } catch (t: Throwable) {
            if (loggingEnabled) {
                println("[DesmoSDK] Telemetry batch failed: $t")
            }
        }
    }
}


