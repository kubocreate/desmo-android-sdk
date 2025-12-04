package io.getdesmo.tracesdk.core

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
}


