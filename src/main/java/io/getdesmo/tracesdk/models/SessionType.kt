package io.getdesmo.tracesdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Type of delivery session. */
@Serializable
enum class SessionType {
  @SerialName("pickup") PICKUP,
  @SerialName("drop") DROP,
  @SerialName("transit") TRANSIT
}
