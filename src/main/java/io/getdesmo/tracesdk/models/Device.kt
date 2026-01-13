package io.getdesmo.tracesdk.models

import kotlinx.serialization.Serializable

/**
 * Device information for a session.
 */
@Serializable
data class Device(
    val platform: String,
    val sdkVersion: String,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null
)
