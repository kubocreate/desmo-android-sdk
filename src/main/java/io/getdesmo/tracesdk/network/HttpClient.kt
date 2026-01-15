package io.getdesmo.tracesdk.network

import android.util.Log
import io.getdesmo.tracesdk.config.DesmoConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP client wrapper for Desmo network calls. It returns raw bytes and lets callers handle JSON
 * decoding.
 */
class HttpClient(private val config: DesmoConfig) {

    companion object {
        private const val TAG = "DesmoSDK"
        private const val HEADER_API_KEY = "Desmo-Key"
        private const val HEADER_CONTENT_ENCODING = "Content-Encoding"
        private const val TIMEOUT_SECONDS = 30L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * OkHttpClient with:
     * - Automatic header injection (API key)
     * - Configured timeouts
     */
    private val client: OkHttpClient =
            OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request =
                                chain.request()
                                        .newBuilder()
                                        .addHeader(HEADER_API_KEY, config.apiKey)
                                        .build()
                        chain.proceed(request)
                    }
                    .build()

    /**
     * Perform a POST with a JSON body.
     *
     * The body is GZIP compressed to reduce network bandwidth (~75% reduction).
     *
     * @param path Path component, e.g. "/v1/sessions/start".
     * @param jsonBody JSON-encoded request body.
     */
    suspend fun post(path: String, jsonBody: String): ByteArray {
        val fullUrl = buildUrl(path)
        
        // GZIP compress the JSON body for ~75% bandwidth reduction
        val compressedBody = gzipCompress(jsonBody)
        val requestBody = compressedBody.toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .addHeader(HEADER_CONTENT_ENCODING, "gzip")
            .build()

        if (config.loggingEnabled) {
            val originalSize = jsonBody.toByteArray().size
            val compressedSize = compressedBody.size
            val savings = ((1.0 - compressedSize.toDouble() / originalSize) * 100).toInt()
            Log.d(TAG, "Request: POST $fullUrl")
            Log.d(TAG, "Request Body: $originalSize bytes → $compressedSize bytes (${savings}% saved)")
        }

        return perform(request)
    }

    /**
     * Perform a GET request.
     *
     * @param path Path component, e.g. "/v1/sessions/{id}".
     */
    suspend fun get(path: String): ByteArray {
        val fullUrl = buildUrl(path)

        val request = Request.Builder().url(fullUrl).get().build()

        if (config.loggingEnabled) {
            Log.d(TAG, "Request: GET $fullUrl")
        }

        return perform(request)
    }

    /** Build the full URL from a path. */
    private fun buildUrl(path: String): String {
        val baseUrl = config.environment.baseUrl.trimEnd('/')
        return "$baseUrl$path"
    }

    /** GZIP compress a string to bytes. */
    private fun gzipCompress(data: String): ByteArray {
        val byteStream = ByteArrayOutputStream()
        GZIPOutputStream(byteStream).use { gzip ->
            gzip.write(data.toByteArray(Charsets.UTF_8))
        }
        return byteStream.toByteArray()
    }

    /** Execute the request and return raw bytes. */
    private suspend fun perform(request: Request): ByteArray =
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (config.loggingEnabled) {
                            Log.d(TAG, "Response: ${response.code}")
                        }

                        val bodyBytes = response.body?.bytes() ?: ByteArray(0)

                        if (!response.isSuccessful) {
                            val errorPreview = bodyBytes.decodeToString().take(500)
                            if (config.loggingEnabled) {
                                Log.e(TAG, "Error Body: $errorPreview")
                            }
                            throw RequestError.StatusCode(
                                    code = response.code,
                                    url = request.url.toString(),
                                    errorBody = errorPreview
                            )
                        }

                        bodyBytes
                    }
                } catch (e: RequestError) {
                    throw e
                } catch (t: Throwable) {
                    throw RequestError.NetworkError(t)
                }
            }
}
