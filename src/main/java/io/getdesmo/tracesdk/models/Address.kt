package io.getdesmo.tracesdk.models

import kotlinx.serialization.Serializable

/**
 * Address information for a delivery session.
 *
 * Customers can provide either:
 * - Structured fields (line1, city, etc.) for precise addresses
 * - rawAddress as a fallback for unstructured address strings
 * - Both, if they have structured data but want to preserve the original
 */
@Serializable
data class Address(
    val line1: String? = null,
    val line2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val rawAddress: String? = null
) {
    companion object {
        /**
         * Create an Address from just a raw string.
         * Use this when you only have an unstructured address.
         */
        fun fromRaw(rawAddress: String): Address = Address(rawAddress = rawAddress)
    }
}
