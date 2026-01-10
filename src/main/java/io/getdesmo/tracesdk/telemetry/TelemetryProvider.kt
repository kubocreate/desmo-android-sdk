package io.getdesmo.tracesdk.telemetry

/** Abstraction for telemetry collection. */
interface TelemetryProvider {
    fun start(sessionId: String)
    fun stop()
    suspend fun flush()

    /** Returns which sensors are available on this device. */
    fun getSensorAvailability(): SensorAvailability

    /** Returns the last known GPS position as an anchor point. */
    fun getLastKnownPosition(): PositionPayload?

    /**
     * Called when app comes to foreground.
     * Re-registers sensor listeners that may have been throttled by Android in background.
     */
    fun onForeground()

    /**
     * Called when app goes to background.
     * Can be used to reduce collection frequency or log the transition.
     */
    fun onBackground()
}
