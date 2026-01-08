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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    // Scope is created fresh on start() and cancelled on stop()
    private var scope: CoroutineScope? = null

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

    override fun start(sessionId: String) {
        // Create a fresh scope for this session
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        this.sessionId = sessionId
        sensorCollector.start()
        locationCollector.start()
        startUploadLoop()
    }

    override fun stop() {
        // Cancel all coroutines (upload loop, buffer adds, etc.)
        scope?.cancel()
        scope = null

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
        val currentScope = scope ?: return // Don't add samples if not recording

        val tsSeconds = System.currentTimeMillis() / 1000.0
        val sample =
                TelemetrySample(
                        ts = tsSeconds,
                        imu = imu,
                        barometer = barometer,
                        position = locationCollector.latestPosition,
                        context = contextCollector.getContext()
                )
        currentScope.launch { buffer.add(sample) }
    }

    private fun startUploadLoop() {
        val currentScope = scope ?: return

        currentScope.launch {
            while (true) {
                delay(UPLOAD_INTERVAL_MS)
                val batch = buffer.drain()
                val sid = sessionId ?: continue
                uploader.upload(sid, batch)
            }
        }
    }
}
