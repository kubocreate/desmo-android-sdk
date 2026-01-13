package io.getdesmo.tracesdk.telemetry

/**
 * Telemetry provider that does nothing.
 *
 * Used in Phase 1 to keep the API aligned with iOS without
 * implementing real sensor / location collection yet.
 */
class NoopTelemetryProvider : TelemetryProvider {
    override fun start(sessionId: String) {
        // No-op
    }

    override fun stop() {
        // No-op
    }

    override suspend fun flush() {
        // No-op
    }

    override fun getSensorAvailability(): SensorAvailability {
        // No sensors available in noop mode
        return SensorAvailability()
    }

    override fun getLastKnownPosition(): PositionPayload? {
        // No position available in noop mode
        return null
    }

    override fun onForeground() {
        // No-op
    }

    override fun onBackground() {
        // No-op
    }
}

