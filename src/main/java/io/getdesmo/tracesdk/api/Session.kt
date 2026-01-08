package io.getdesmo.tracesdk.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Status of a Desmo recording session.
 *
 * These values match the backend API response.
 */
@Serializable
enum class SessionStatus {
  @SerialName("recording") RECORDING,
  @SerialName("completed") COMPLETED,
  @SerialName("failed") FAILED
}

/**
 * Represents a Desmo recording session.
 *
 * Returned by [DesmoClient.startSession] and [DesmoClient.stopSession].
 */
@Serializable data class Session(val sessionId: String, val status: SessionStatus)
