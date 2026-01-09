package io.getdesmo.tracesdk.telemetry

import android.content.Context
import android.hardware.SensorManager
import android.location.LocationManager
import android.util.Log
import io.getdesmo.tracesdk.network.HttpClient
import io.getdesmo.tracesdk.telemetry.collectors.ContextCollector
import io.getdesmo.tracesdk.telemetry.collectors.LocationCollector
import io.getdesmo.tracesdk.telemetry.collectors.SensorCollector
import io.getdesmo.tracesdk.telemetry.persistence.TelemetryDatabase
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
 *
 * Architecture:
 * - [SensorCollector], [LocationCollector], [ContextCollector] → collect data
 * - [TelemetryBuffer] → holds hot data in RAM
 * - [TelemetryQueue] → persists to SQLite, handles upload + retry
 */
internal class TelemetryManager(
    context: Context,
    httpClient: HttpClient,
    private val loggingEnabled: Boolean
) : TelemetryProvider {

    private companion object {
        private const val TAG = "DesmoSDK"
        private const val UPLOAD_INTERVAL_MS = 5_000L
        private const val RETRY_INTERVAL_MS = 30_000L // Retry pending batches every 30 seconds
    }

    private val appContext = context.applicationContext

    // Scope is created fresh on start() and cancelled on stop()
    private var scope: CoroutineScope? = null

    // System services
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Collectors
    private val contextCollector = ContextCollector(appContext)
    private val locationCollector = LocationCollector(locationManager, loggingEnabled)
    private val sensorCollector = SensorCollector(
        sensorManager = sensorManager,
        loggingEnabled = loggingEnabled,
        onSample = { imu, barometer -> onSensorSample(imu, barometer) }
    )

    // Buffer + Queue
    private val buffer = TelemetryBuffer()
    private val uploader = TelemetryUploader(httpClient, loggingEnabled)
    private val queue: TelemetryQueue

    private var sessionId: String? = null

    init {
        // Initialize persistence layer
        val database = TelemetryDatabase.getInstance(appContext)
        queue = TelemetryQueue(database.telemetryDao(), uploader, loggingEnabled)
    }

    override fun start(sessionId: String) {
        // Create a fresh scope for this session
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        this.sessionId = sessionId

        // Start collectors
        sensorCollector.start()
        locationCollector.start()

        // Start background loops
        startUploadLoop()
        startRetryLoop()

        // Process any pending batches from previous sessions
        scope?.launch {
            queue.processPendingBatches()
        }

        if (loggingEnabled) {
            Log.d(TAG, "TelemetryManager started for session: $sessionId")
        }
    }

    override fun stop() {
        if (loggingEnabled) {
            Log.d(TAG, "TelemetryManager stopping")
        }

        // Cancel all coroutines (upload loop, retry loop, etc.)
        scope?.cancel()
        scope = null

        // Stop collectors
        sensorCollector.stop()
        locationCollector.stop()
    }

    override suspend fun flush() {
        val sid = sessionId ?: return
        val batch = buffer.drain()
        if (batch.isNotEmpty()) {
            queue.enqueue(sid, batch)
        }
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
        val sample = TelemetrySample(
            ts = tsSeconds,
            imu = imu,
            barometer = barometer,
            position = locationCollector.latestPosition,
            context = contextCollector.getContext()
        )
        currentScope.launch { buffer.add(sample) }
    }

    /**
     * Upload loop: drain buffer and enqueue samples every [UPLOAD_INTERVAL_MS].
     */
    private fun startUploadLoop() {
        val currentScope = scope ?: return

        currentScope.launch {
            while (true) {
                delay(UPLOAD_INTERVAL_MS)
                val sid = sessionId ?: continue
                val batch = buffer.drain()
                if (batch.isNotEmpty()) {
                    queue.enqueue(sid, batch)
                }
            }
        }
    }

    /**
     * Retry loop: process pending batches every [RETRY_INTERVAL_MS].
     *
     * This handles batches that failed to upload due to network issues.
     */
    private fun startRetryLoop() {
        val currentScope = scope ?: return

        currentScope.launch {
            while (true) {
                delay(RETRY_INTERVAL_MS)
                queue.processPendingBatches()
            }
        }
    }
}
