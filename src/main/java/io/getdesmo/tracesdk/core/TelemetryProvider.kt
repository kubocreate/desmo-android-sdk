package io.getdesmo.tracesdk.core

/**
 * Abstraction for telemetry collection.
 *
 * Mirrors the Swift `TelemetryProvider` protocol.
 */
interface TelemetryProvider {
    fun start(sessionId: String)
    fun stop()
    suspend fun flush()
}


