package io.getdesmo.tracesdk.session

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.getdesmo.tracesdk.api.DesmoClientError
import io.getdesmo.tracesdk.config.DesmoConfig
import io.getdesmo.tracesdk.models.Address
import io.getdesmo.tracesdk.models.Device
import io.getdesmo.tracesdk.models.Location
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
 * Mirrors the Swift `DesmoClient` class.
 */
class DesmoClient(private val config: DesmoConfig, private val appContext: Context? = null) {

    private companion object {
        private const val TAG = "DesmoSDK"

        // Keep this in sync with the published Android SDK version.
        private const val SDK_VERSION = "0.1.2"

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
                TelemetryManager(appContext, httpClient, config.loggingEnabled)
            } else {
                NoopTelemetryProvider()
            }

    private val stateMutex = Mutex()

    /**
     * Start a Desmo session for a specific delivery.
     *
     * @param deliveryId Unique identifier for this delivery (from your system)
     * @param sessionType Type of session: PICKUP, DROP, or TRANSIT
     * @param externalRiderId Optional external rider/driver ID from your system
     * @param address Optional delivery address (structured or raw string via Address.fromRaw())
     * @param startLocation Optional starting location (auto-captured if not provided)
     */
    suspend fun startSession(
            deliveryId: String,
            sessionType: SessionType,
            externalRiderId: String? = null,
            address: Address? = null,
            startLocation: Location? = null
    ): Session =
            stateMutex.withLock {
                // 1. Check state: must be idle
                requireState(SessionState.IDLE)

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
                                        Location(lat = position.lat, lng = position.lng)
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
                                osVersion = Build.VERSION.RELEASE ?: "SDK_${Build.VERSION.SDK_INT}",
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

                try {
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
                    session
                } catch (e: RequestError) {
                    // Roll back state on failure
                    state = SessionState.IDLE
                    throw DesmoClientError.Http(e)
                } catch (t: Throwable) {
                    state = SessionState.IDLE
                    throw DesmoClientError.Http(RequestError.NetworkError(t))
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
     * Mirrors the Swift `stopSession()` method.
     */
    suspend fun stopSession(): Session =
            stateMutex.withLock {
                // 1. Check state: must be recording and have a session id
                if (state != SessionState.RECORDING || currentSessionId == null) {
                    throw DesmoClientError.InvalidState(
                            expected = SessionState.RECORDING.description,
                            actual = state.description
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

                try {
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
                    session
                } catch (e: RequestError) {
                    // Revert to recording so caller can retry stop
                    state = SessionState.RECORDING
                    throw DesmoClientError.Http(e)
                } catch (t: Throwable) {
                    state = SessionState.RECORDING
                    throw DesmoClientError.Http(RequestError.NetworkError(t))
                }
            }

    /** Helper to assert we are in a specific state. */
    @Suppress("SameParameterValue")
    private fun requireState(expected: SessionState) {
        if (state != expected) {
            throw DesmoClientError.InvalidState(
                    expected = expected.description,
                    actual = state.description
            )
        }
    }
}
