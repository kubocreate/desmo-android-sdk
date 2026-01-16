package io.getdesmo.tracesdk.models

import kotlinx.serialization.Serializable

/**
 * Device information for a session.
 * Field names match DatabaseSchema.md conventions.
 */
@Serializable
data class Device(
    val platform: String,
    val sdkVersion: String,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null
)
