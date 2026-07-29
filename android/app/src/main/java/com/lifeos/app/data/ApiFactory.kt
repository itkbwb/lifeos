package com.lifeos.app.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin OkHttp+Gson client for the Life OS server: the connectivity check
 * (`GET /health`) plus the Projects CRUD surface (`/api/projects`).
 */
object ApiFactory {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Blocking call - run on a background dispatcher. */
    fun checkHealth(baseUrl: String, accessClientId: String = "", accessClientSecret: String = ""): Boolean {
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "health")
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            return response.isSuccessful
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun listProjects(baseUrl: String, accessClientId: String = "", accessClientSecret: String = ""): List<Project> {
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/projects")
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("listProjects failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("listProjects: empty response body")
            val type = object : TypeToken<List<Project>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun createProject(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        name: String,
        color: String,
    ): Project {
        val json = gson.toJson(mapOf("name" to name, "color" to color))
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/projects")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("createProject failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("createProject: empty response body")
            return gson.fromJson(body, Project::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun updateProject(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
        name: String? = null,
        color: String? = null,
    ): Project {
        val fields = mutableMapOf<String, String>()
        name?.let { fields["name"] = it }
        color?.let { fields["color"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/projects/$id")
            .patch(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("updateProject failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("updateProject: empty response body")
            return gson.fromJson(body, Project::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun deleteProject(baseUrl: String, accessClientId: String = "", accessClientSecret: String = "", id: Int) {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/projects/$id")
            .delete()
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("deleteProject failed: HTTP ${response.code}")
            }
        }
    }

    private fun addAccessHeaders(builder: Request.Builder, accessClientId: String, accessClientSecret: String) {
        if (accessClientId.isNotBlank() && accessClientSecret.isNotBlank()) {
            builder
                .addHeader("CF-Access-Client-Id", accessClientId)
                .addHeader("CF-Access-Client-Secret", accessClientSecret)
        }
    }

    private fun normalize(baseUrl: String) = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
}
