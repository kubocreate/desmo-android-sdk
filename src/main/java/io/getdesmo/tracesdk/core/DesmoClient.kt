package io.getdesmo.tracesdk.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.getdesmo.tracesdk.config.DesmoConfig
import io.getdesmo.tracesdk.errors.DesmoClientError
import io.getdesmo.tracesdk.http.HttpClient
import io.getdesmo.tracesdk.http.HttpError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Main Desmo client for starting and stopping recording sessions.
 *
 * Mirrors the Swift `DesmoClient` class.
 */
class DesmoClient(
    private val config: DesmoConfig,
    context: Context? = null
) {

    private companion object {
        // Keep this in sync with the published Android SDK version.
        private const val SDK_VERSION = "0.1.2"

        private val json = Json {
            ignoreUnknownKeys = true
        }
    }

    private enum class SessionState(val description: String) {
        IDLE("idle"),
        STARTING("starting"),
        RECORDING("recording"),
        STOPPING("stopping")
    }

    private var state: SessionState = SessionState.IDLE
    private var currentSessionId: String? = null

    private val telemetry: TelemetryProvider =
        if (context != null) {
            HttpTelemetryProvider(context, HttpClient(config), config.loggingEnabled)
        } else {
            NoopTelemetryProvider()
        }

    private val httpClient: HttpClient = HttpClient(config)

    private val stateMutex = Mutex()

    /**
     * Start a Desmo session for a specific delivery.
     *
     * This mirrors the Swift signature:
     * `startSession(deliveryId: String, address: String?, metadata: [String: String]?)`
     */
    suspend fun startSession(
        deliveryId: String,
        address: String? = null,
        metadata: Map<String, String>? = null,
        deviceModel: String? = null,
        osVersion: String? = null,
        appVersion: String? = null,
        startLat: Double? = null,
        startLon: Double? = null
    ): Session = stateMutex.withLock {
        // 1. Check state: must be idle
        requireState(SessionState.IDLE)

        if (config.loggingEnabled) {
            println("[DesmoSDK] Starting session for delivery: $deliveryId")
        }

        // 2. Transition state: idle -> starting
        state = SessionState.STARTING

        val requestBody = StartSessionRequest(
            deliveryId = deliveryId,
            address = address,
            platform = "android",
            sdkVersion = SDK_VERSION,
            deviceModel = deviceModel,
            osVersion = osVersion,
            appVersion = appVersion,
            startLat = startLat,
            startLon = startLon
        )

        try {
            // 3. Perform network request
            val jsonBody = json.encodeToString(requestBody)
            val data = httpClient.post(path = "/v1/sessions/start", jsonBody = jsonBody)
            val session = json.decodeFromString(Session.serializer(), data.decodeToString())

            if (config.loggingEnabled) {
                println("[DesmoSDK] Session started successfully: ${session.sessionId}")
            }

            // Start collecting telemetry
            telemetry.start(session.sessionId)

            // 4. Transition state: starting -> recording
            currentSessionId = session.sessionId
            state = SessionState.RECORDING
            session
        } catch (e: HttpError) {
            // Roll back state on failure
            state = SessionState.IDLE
            throw DesmoClientError.Http(e)
        } catch (t: Throwable) {
            state = SessionState.IDLE
            throw DesmoClientError.Http(HttpError.NetworkError(t))
        }
    }

    /**
     * Convenience helper that fills in common device/app metadata for you.
     *
     * - deviceModel: Build.MODEL
     * - osVersion: Build.VERSION.RELEASE (or SDK_INT if unavailable)
     * - appVersion: versionName from PackageManager
     *
     * You still provide deliveryId/address/location; this method just
     * avoids duplicating the boilerplate in every app.
     */
    suspend fun startSessionWithDeviceInfo(
        context: Context,
        deliveryId: String,
        address: String? = null,
        metadata: Map<String, String>? = null,
        startLat: Double? = null,
        startLon: Double? = null
    ): Session {
        val deviceModel = Build.MODEL
        val osVersion = Build.VERSION.RELEASE ?: "SDK_${Build.VERSION.SDK_INT}"

        val appVersion = resolveAppVersion(context)

        return startSession(
            deliveryId = deliveryId,
            address = address,
            metadata = metadata,
            deviceModel = deviceModel,
            osVersion = osVersion,
            appVersion = appVersion,
            startLat = startLat,
            startLon = startLon
        )
    }

    private fun resolveAppVersion(context: Context): String? {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val versionName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0).versionName
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
    suspend fun stopSession(): Session = stateMutex.withLock {
        // 1. Check state: must be recording and have a session id
        if (state != SessionState.RECORDING || currentSessionId == null) {
            throw DesmoClientError.InvalidState(
                expected = SessionState.RECORDING.description,
                actual = state.description
            )
        }

        val sessionId = currentSessionId!!

        if (config.loggingEnabled) {
            println("[DesmoSDK] Stopping session: $sessionId")
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
                println("[DesmoSDK] Session stopped successfully")
            }

            // 4. Transition state: stopping -> idle
            state = SessionState.IDLE
            currentSessionId = null
            session
        } catch (e: HttpError) {
            // Revert to recording so caller can retry stop
            state = SessionState.RECORDING
            throw DesmoClientError.Http(e)
        } catch (t: Throwable) {
            state = SessionState.RECORDING
            throw DesmoClientError.Http(HttpError.NetworkError(t))
        }
    }

    /**
     * Helper to assert we are in a specific state.
     */
    @Suppress("SameParameterValue")
    private fun requireState(expected: SessionState) {
        if (state != expected) {
            throw DesmoClientError.InvalidState(
                expected = expected.description,
                actual = state.description
            )
        }
    }

    // MARK: - Internal request models

    @Serializable
    private data class StartSessionRequest(
        val deliveryId: String,
        val address: String?,
        val platform: String?,
        val sdkVersion: String?,
        val deviceModel: String? = null,
        val osVersion: String? = null,
        val appVersion: String? = null,
        val startLat: Double? = null,
        val startLon: Double? = null
    )

    @Serializable
    private data class StopSessionRequest(
        val sessionId: String
    )
}


