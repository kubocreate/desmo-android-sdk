package io.getdesmo.tracesdk.config

import io.getdesmo.tracesdk.errors.DesmoClientError
import org.junit.Assert.assertThrows
import org.junit.Test

class DesmoConfigTest {

    @Test
    fun `invalid api key throws DesmoClientError_InvalidApiKey`() {
        assertThrows(DesmoClientError.InvalidApiKey::class.java) {
            DesmoConfig(
                apiKey = "sk_test_invalid",
                environment = DesmoEnvironment.SANDBOX
            )
        }
    }
}


