package io.getdesmo.tracesdk.telemetry

import android.content.Context
import android.hardware.SensorManager
import android.location.LocationManager
import android.util.Log
import io.getdesmo.tracesdk.config.TelemetryConfig
import io.getdesmo.tracesdk.network.HttpClient
import io.getdesmo.tracesdk.telemetry.collectors.ActivityRecognitionCollector
import io.getdesmo.tracesdk.telemetry.collectors.ContextCollector
import io.getdesmo.tracesdk.telemetry.collectors.LocationCollector
import io.getdesmo.tracesdk.telemetry.collectors.SensorCollector
import io.getdesmo.tracesdk.telemetry.persistence.TelemetryDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Manages telemetry collection: coordinates sensors, location, context, activity, buffering, and upload.
 *
 * Implements [TelemetryProvider] interface.
 *
 * Architecture:
 * - [SensorCollector], [LocationCollector], [ContextCollector], [ActivityRecognitionCollector] → collect data
 * - [TelemetryBuffer] → holds hot data in RAM
 * - [TelemetryQueue] → persists to SQLite, handles upload + retry
 */
internal class TelemetryManager(
    context: Context,
    httpClient: HttpClient,
    private val telemetryConfig: TelemetryConfig,
    private val loggingEnabled: Boolean
) : TelemetryProvider {

    private companion object {
        private const val TAG = "DesmoSDK"
        private const val RETRY_INTERVAL_MS = 30_000L // Retry pending batches every 30 seconds
    }

    private val appContext = context.applicationContext

    // Exception handler for coroutines - logs but never crashes host app
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (loggingEnabled) {
            Log.e(TAG, "Coroutine exception: $throwable")
        }
    }

    // Scope is created fresh on start() and cancelled on stop()
    private var scope: CoroutineScope? = null

    // System services
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Collectors - configured via TelemetryConfig
    private val activityCollector = ActivityRecognitionCollector(appContext, loggingEnabled)
    private val contextCollector = ContextCollector(appContext, loggingEnabled).apply {
        setActivityCollector(activityCollector)
    }
    private val locationCollector = LocationCollector(
        locationManager = locationManager,
        locationUpdateMs = telemetryConfig.locationUpdateMs,
        loggingEnabled = loggingEnabled
    )
    private val sensorCollector = SensorCollector(
        sensorManager = sensorManager,
        sampleRateHz = telemetryConfig.sampleRateHz,
        loggingEnabled = loggingEnabled,
        onSample = { tsSeconds, imu, barometer, magnetometer -> onSensorSample(tsSeconds, imu, barometer, magnetometer) }
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
        // Create a fresh scope with exception handler to prevent crashes
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

        this.sessionId = sessionId

        // CRITICAL: Clear any stale samples from previous sessions that may have
        // crashed or been killed without calling stopSession(). This prevents old
        // telemetry from leaking into the new session.
        // We use runBlocking here because this must complete before we start
        // collecting new samples - it's a brief operation (just clearing a list).
        runBlocking {
            buffer.clear()
        }
        if (loggingEnabled) {
            Log.d(TAG, "Cleared stale buffer data before starting new session")
        }

        // Start collectors
        sensorCollector.start()
        locationCollector.start()
        activityCollector.start()

        // Start background loops
        startUploadLoop()
        startRetryLoop()

        // Process any pending batches from previous sessions
        // Note: These batches are persisted with their ORIGINAL session IDs,
        // so they will be uploaded to their correct sessions, not the new one.
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
        activityCollector.stop()
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

    override fun onForeground() {
        // Only resume if we're actively recording a session
        if (sessionId != null && scope != null) {
            if (loggingEnabled) {
                Log.d(TAG, "Resuming telemetry collection after foreground")
            }
            // Re-register sensor listeners that Android may have throttled
            sensorCollector.resume()
            // Restart activity recognition
            activityCollector.start()
        }
    }

    override fun onBackground() {
        // Log the transition for debugging
        if (loggingEnabled) {
            Log.d(TAG, "App went to background, telemetry may be throttled by Android")
        }
        // Note: We don't stop collection here - Android will naturally throttle sensors
        // If the host app wants continuous background collection, they should use a foreground service
    }

    private fun onSensorSample(tsSeconds: Double, imu: ImuPayload, barometer: BarometerPayload?, magnetometer: MagnetometerPayload?) {
        val currentScope = scope ?: return // Don't add samples if not recording

        // tsSeconds is the accurate sensor timestamp converted to Unix time
        // (computed from SensorEvent.timestamp + boot time offset)
        val sample = TelemetrySample(
            ts = tsSeconds,
            imu = imu,
            barometer = barometer,
            position = locationCollector.latestPosition,
            context = contextCollector.getContext(),
            magnetometer = magnetometer
        )
        currentScope.launch { buffer.add(sample) }
    }

    /**
     * Upload loop: drain buffer and enqueue samples at configured interval.
     */
    private fun startUploadLoop() {
        val currentScope = scope ?: return

        currentScope.launch {
            while (true) {
                delay(telemetryConfig.uploadIntervalMs)
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
