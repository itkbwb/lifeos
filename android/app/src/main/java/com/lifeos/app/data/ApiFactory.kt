package com.lifeos.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The server currently exposes nothing but GET /health - this is a thin
 * connectivity check, not a general-purpose API client.
 */
object ApiFactory {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Blocking call - run on a background dispatcher. */
    fun checkHealth(baseUrl: String, accessClientId: String = "", accessClientSecret: String = ""): Boolean {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val requestBuilder = Request.Builder().url(normalized + "health")
        if (accessClientId.isNotBlank() && accessClientSecret.isNotBlank()) {
            requestBuilder
                .addHeader("CF-Access-Client-Id", accessClientId)
                .addHeader("CF-Access-Client-Secret", accessClientSecret)
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            return response.isSuccessful
        }
    }
}
