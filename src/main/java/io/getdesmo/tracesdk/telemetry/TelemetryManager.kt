package io.getdesmo.tracesdk.telemetry

import android.content.Context
import android.hardware.SensorManager
import android.location.LocationManager
import io.getdesmo.tracesdk.network.HttpClient
import io.getdesmo.tracesdk.telemetry.collectors.ContextCollector
import io.getdesmo.tracesdk.telemetry.collectors.LocationCollector
import io.getdesmo.tracesdk.telemetry.collectors.SensorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages telemetry collection: coordinates sensors, location, context, buffering, and upload.
 *
 * Implements [TelemetryProvider] interface.
 */
internal class TelemetryManager(
        context: Context,
        httpClient: HttpClient,
        private val loggingEnabled: Boolean
) : TelemetryProvider {

    private companion object {
        private const val UPLOAD_INTERVAL_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Sub-collectors
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager =
            appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val contextCollector = ContextCollector(appContext)
    private val locationCollector = LocationCollector(locationManager, loggingEnabled)
    private val buffer = TelemetryBuffer()
    private val uploader = TelemetryUploader(httpClient, loggingEnabled)

    private val sensorCollector =
            SensorCollector(
                    sensorManager = sensorManager,
                    loggingEnabled = loggingEnabled,
                    onSample = { imu, barometer -> onSensorSample(imu, barometer) }
            )

    private var sessionId: String? = null
    private var uploadJob: Job? = null

    override fun start(sessionId: String) {
        this.sessionId = sessionId
        sensorCollector.start()
        locationCollector.start()
        startUploadLoop()
    }

    override fun stop() {
        stopUploadLoop()
        sensorCollector.stop()
        locationCollector.stop()
    }

    override suspend fun flush() {
        val batch = buffer.drain()
        val sid = sessionId ?: return
        uploader.upload(sid, batch)
    }

    override fun getSensorAvailability(): SensorAvailability {
        val sensorAvail = sensorCollector.getAvailability()
        return sensorAvail.copy(gps = locationCollector.isGpsAvailable())
    }

    override fun getLastKnownPosition(): PositionPayload? {
        return locationCollector.getLastKnownPosition()
    }

    private fun onSensorSample(imu: ImuPayload, barometer: BarometerPayload?) {
        val tsSeconds = System.currentTimeMillis() / 1000.0
        val sample =
                TelemetrySample(
                        ts = tsSeconds,
                        imu = imu,
                        barometer = barometer,
                        position = locationCollector.latestPosition,
                        context = contextCollector.getContext()
                )
        scope.launch { buffer.add(sample) }
    }

    private fun startUploadLoop() {
        if (uploadJob != null) return

        uploadJob =
                scope.launch {
                    while (true) {
                        delay(UPLOAD_INTERVAL_MS)
                        val batch = buffer.drain()
                        val sid = sessionId ?: continue
                        uploader.upload(sid, batch)
                    }
                }
    }

    private fun stopUploadLoop() {
        uploadJob?.cancel()
        uploadJob = null
    }
}
