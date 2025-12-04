package io.getdesmo.tracesdk.http

import io.getdesmo.tracesdk.config.DesmoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP client wrapper for Desmo network calls.
 *
 * Mirrors the Swift `HTTPClient` behavior using OkHttp.
 * It returns raw bytes and lets callers handle JSON decoding.
 */
class HttpClient(
    private val config: DesmoConfig
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .build()

    /**
     * Perform a POST with a JSON body.
     *
     * @param path Path component, e.g. "/v1/sessions/start".
     * @param jsonBody JSON-encoded request body.
     */
    suspend fun post(path: String, jsonBody: String): ByteArray {
        val baseUrl = config.environment.baseUrl.trimEnd('/')
        val fullUrl = "$baseUrl$path"

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("Desmo-Key", config.apiKey)
            .build()

        if (config.loggingEnabled) {
            println("[DesmoSDK] Request: POST $fullUrl")
            println("[DesmoSDK] Request Body: $jsonBody")
        }

        return perform(request)
    }

    /**
     * Perform a GET request.
     */
    suspend fun get(path: String): ByteArray {
        val baseUrl = config.environment.baseUrl.trimEnd('/')
        val fullUrl = "$baseUrl$path"

        val request = Request.Builder()
            .url(fullUrl)
            .get()
            .addHeader("Content-Type", "application/json")
            .addHeader("Desmo-Key", config.apiKey)
            .build()

        if (config.loggingEnabled) {
            println("[DesmoSDK] Request: GET $fullUrl")
        }

        return perform(request)
    }

    private suspend fun perform(request: Request): ByteArray = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (config.loggingEnabled) {
                    println("[DesmoSDK] Response: ${response.code}")
                }

                val bodyBytes = response.body?.bytes() ?: ByteArray(0)

                if (!response.isSuccessful) {
                    if (config.loggingEnabled) {
                        println("[DesmoSDK] Error Body: ${bodyBytes.decodeToString()}")
                    }
                    throw HttpError.StatusCode(response.code)
                }

                bodyBytes
            }
        } catch (e: HttpError) {
            throw e
        } catch (t: Throwable) {
            throw HttpError.NetworkError(t)
        }
    }
}

