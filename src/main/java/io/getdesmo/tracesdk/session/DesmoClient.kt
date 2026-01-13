package io.getdesmo.tracesdk.session

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.getdesmo.tracesdk.BuildConfig
import io.getdesmo.tracesdk.api.DesmoClientError
import io.getdesmo.tracesdk.api.DesmoResult
import io.getdesmo.tracesdk.config.DesmoConfig
import io.getdesmo.tracesdk.models.Address
import io.getdesmo.tracesdk.models.Device
import io.getdesmo.tracesdk.models.StartLocation
import io.getdesmo.tracesdk.models.Session
import io.getdesmo.tracesdk.models.SessionType
import io.getdesmo.tracesdk.network.HttpClient
import io.getdesmo.tracesdk.network.RequestError
import io.getdesmo.tracesdk.network.StartSessionRequest
import io.getdesmo.tracesdk.network.StopSessionRequest
import io.getdesmo.tracesdk.telemetry.NoopTelemetryProvider
import io.getdesmo.tracesdk.telemetry.TelemetryManager
import io.getdesmo.tracesdk.telemetry.TelemetryProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Main Desmo client for starting and stopping recording sessions.
 *
 * All public methods return [DesmoResult] instead of throwing exceptions, guaranteeing that the SDK
 * will never crash the host application.
 */
class DesmoClient(private val config: DesmoConfig, private val appContext: Context? = null) {

    private companion object {
        private const val TAG = "DesmoSDK"

        // SDK version is now sourced from BuildConfig (set in build.gradle.kts)
        private val SDK_VERSION = BuildConfig.SDK_VERSION

        private val json = Json { ignoreUnknownKeys = true }
    }

    private enum class SessionState(val description: String) {
        IDLE("idle"),
        STARTING("starting"),
        RECORDING("recording"),
        STOPPING("stopping")
    }

    private var state: SessionState = SessionState.IDLE
    private var currentSessionId: String? = null

    // Single shared HttpClient instance for both session calls and telemetry
    private val httpClient: HttpClient = HttpClient(config)

    private val telemetry: TelemetryProvider =
            if (appContext != null) {
                TelemetryManager(appContext, httpClient, config.telemetry, config.loggingEnabled)
            } else {
                NoopTelemetryProvider()
            }

    private val stateMutex = Mutex()

    /**
     * Start a Desmo session for a specific delivery.
     *
     * This method **never throws**. All errors are returned as [DesmoResult.Failure].
     *
     * @param deliveryId Unique identifier for this delivery (from your system)
     * @param sessionType Type of session: PICKUP, DROP, or TRANSIT
     * @param externalRiderId Optional external rider/driver ID from your system
     * @param address Optional delivery address (structured or raw string via Address.fromRaw())
     * @param startLocation Optional starting location (auto-captured if not provided)
     * @return [DesmoResult.Success] with the session, or [DesmoResult.Failure] with error details
     */
    suspend fun startSession(
            deliveryId: String,
            sessionType: SessionType,
            externalRiderId: String? = null,
            address: Address? = null,
            startLocation: StartLocation? = null
    ): DesmoResult<Session> =
            stateMutex.withLock {
                try {
                    // 1. Check state: must be idle
                    if (state != SessionState.IDLE) {
                        return@withLock DesmoResult.Failure(
                                DesmoClientError.InvalidState(
                                        expected = SessionState.IDLE.description,
                                        actual = state.description
                                )
                        )
                    }

                    if (config.loggingEnabled) {
                        Log.d(TAG, "Starting session for delivery: $deliveryId")
                    }

                    // 2. Transition state: idle -> starting
                    state = SessionState.STARTING

                    // Capture anchor position if not provided
                    val finalStartLocation =
                            startLocation
                                    ?: run {
                                        val position = telemetry.getLastKnownPosition()
                                        if (position != null) {
                                            if (config.loggingEnabled) {
                                                Log.d(
                                                        TAG,
                                                        "Anchor position captured: ${position.lat}, ${position.lng}"
                                                )
                                            }
                                            StartLocation(lat = position.lat, lng = position.lng)
                                        } else {
                                            null
                                        }
                                    }

                    // Get sensor availability
                    val sensorAvailability = telemetry.getSensorAvailability()
                    if (config.loggingEnabled) {
                        Log.d(
                                TAG,
                                "Sensor availability: accel=${sensorAvailability.accelerometer}, " +
                                        "gyro=${sensorAvailability.gyroscope}, " +
                                        "baro=${sensorAvailability.barometer}, " +
                                        "gps=${sensorAvailability.gps}"
                        )
                    }

                    // Build device info
                    val device =
                            Device(
                                    platform = "android",
                                    sdkVersion = SDK_VERSION,
                                    deviceModel = Build.MODEL,
                                    osVersion = Build.VERSION.RELEASE
                                                    ?: "SDK_${Build.VERSION.SDK_INT}",
                                    appVersion = resolveAppVersion()
                            )

                    val requestBody =
                            StartSessionRequest(
                                    deliveryId = deliveryId,
                                    sessionType = sessionType,
                                    externalRiderId = externalRiderId,
                                    address = address,
                                    device = device,
                                    startLocation = finalStartLocation,
                                    sensorAvailability = sensorAvailability
                            )

                    // 3. Perform network request
                    val jsonBody = json.encodeToString(requestBody)
                    val data = httpClient.post(path = "/v1/sessions/start", jsonBody = jsonBody)
                    val session = json.decodeFromString(Session.serializer(), data.decodeToString())

                    if (config.loggingEnabled) {
                        Log.d(TAG, "Session started successfully: ${session.sessionId}")
                    }

                    // Start collecting telemetry
                    telemetry.start(session.sessionId)

                    // 4. Transition state: starting -> recording
                    currentSessionId = session.sessionId
                    state = SessionState.RECORDING

                    DesmoResult.Success(session)
                } catch (e: RequestError) {
                    // Roll back state on failure
                    state = SessionState.IDLE
                    if (config.loggingEnabled) {
                        Log.e(TAG, "startSession failed: $e")
                    }
                    DesmoResult.Failure(DesmoClientError.Http(e))
                } catch (t: Throwable) {
                    // Roll back state on failure
                    state = SessionState.IDLE
                    if (config.loggingEnabled) {
                        Log.e(TAG, "startSession failed: $t")
                    }
                    DesmoResult.Failure(DesmoClientError.Http(RequestError.NetworkError(t)))
                }
            }

    private fun resolveAppVersion(): String? {
        val context = appContext ?: return null
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val versionName =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                                .versionName
                    } else {
                        @Suppress("DEPRECATION") pm.getPackageInfo(packageName, 0).versionName
                    }
            versionName
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Stop the currently active Desmo session.
     *
     * This method **never throws**. All errors are returned as [DesmoResult.Failure].
     *
     * @return [DesmoResult.Success] with the stopped session, or [DesmoResult.Failure] with error
     * details
     */
    suspend fun stopSession(): DesmoResult<Session> =
            stateMutex.withLock {
                try {
                    // 1. Check state: must be recording and have a session id
                    if (state != SessionState.RECORDING || currentSessionId == null) {
                        return@withLock DesmoResult.Failure(
                                DesmoClientError.InvalidState(
                                        expected = SessionState.RECORDING.description,
                                        actual = state.description
                                )
                        )
                    }

                    val sessionId = currentSessionId!!

                    if (config.loggingEnabled) {
                        Log.d(TAG, "Stopping session: $sessionId")
                    }

                    // 2. Transition state: recording -> stopping
                    state = SessionState.STOPPING

                    // Flush and stop telemetry before notifying server
                    telemetry.flush()
                    telemetry.stop()

                    val requestBody = StopSessionRequest(sessionId = sessionId)

                    // 3. Perform network request
                    val jsonBody = json.encodeToString(requestBody)
                    val data = httpClient.post(path = "/v1/sessions/stop", jsonBody = jsonBody)
                    val session = json.decodeFromString(Session.serializer(), data.decodeToString())

                    if (config.loggingEnabled) {
                        Log.d(TAG, "Session stopped successfully")
                    }

                    // 4. Transition state: stopping -> idle
                    state = SessionState.IDLE
                    currentSessionId = null

                    DesmoResult.Success(session)
                } catch (e: RequestError) {
                    // Revert to recording so caller can retry stop
                    state = SessionState.RECORDING
                    if (config.loggingEnabled) {
                        Log.e(TAG, "stopSession failed: $e")
                    }
                    DesmoResult.Failure(DesmoClientError.Http(e))
                } catch (t: Throwable) {
                    // Revert to recording so caller can retry stop
                    state = SessionState.RECORDING
                    if (config.loggingEnabled) {
                        Log.e(TAG, "stopSession failed: $t")
                    }
                    DesmoResult.Failure(DesmoClientError.Http(RequestError.NetworkError(t)))
                }
            }

    /**
     * Called when the app comes to foreground.
     * Re-registers sensors that Android may have throttled in background.
     *
     * Typically called automatically via [Desmo.bindToProcessLifecycle].
     */
    fun onForeground() {
        telemetry.onForeground()
    }

    /**
     * Called when the app goes to background.
     * Logs the transition; actual throttling is handled by Android.
     *
     * Typically called automatically via [Desmo.bindToProcessLifecycle].
     */
    fun onBackground() {
        telemetry.onBackground()
    }
}
