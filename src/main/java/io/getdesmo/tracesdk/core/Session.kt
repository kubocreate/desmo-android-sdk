package io.getdesmo.tracesdk.core

import kotlinx.serialization.Serializable

/**
 * Represents a Desmo recording session.
 *
 * Mirrors the Swift `Session` struct.
 */
@Serializable
data class Session(
    val sessionId: String,
    val status: String
)

