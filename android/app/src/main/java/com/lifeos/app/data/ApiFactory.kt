package com.lifeos.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.net.URLEncoder
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

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun listEvents(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int? = null,
        from: String? = null,
        to: String? = null,
    ): List<Event> {
        val params = mutableListOf<String>()
        projectId?.let { params += "project_id=$it" }
        from?.let { params += "from=" + URLEncoder.encode(it, "UTF-8") }
        to?.let { params += "to=" + URLEncoder.encode(it, "UTF-8") }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/events" + query)
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("listEvents failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("listEvents: empty response body")
            val type = object : TypeToken<List<Event>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /**
     * Blocking call - run on a background dispatcher. Throws [ActiveProjectConflictException] on
     * HTTP 409 when a `start` conflicts with an already-active project, or IOException otherwise.
     */
    fun createEvent(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int,
        type: String,
        occurredAt: String? = null,
        label: String? = null,
    ): Event {
        val fields = mutableMapOf<String, Any>("project_id" to projectId, "type" to type)
        occurredAt?.let { fields["occurred_at"] = it }
        label?.let { fields["label"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/events")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (response.code == 409) {
                throw parseActiveConflict(body) ?: IOException("createEvent conflict: HTTP 409")
            }
            if (!response.isSuccessful) {
                throw IOException("createEvent failed: HTTP ${response.code}")
            }
            return gson.fromJson(body ?: throw IOException("createEvent: empty response body"), Event::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Returns null when no project is active. */
    fun getActiveProject(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
    ): ActiveProject? {
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/events/active")
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 204) return null
            if (!response.isSuccessful) {
                throw IOException("getActiveProject failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("getActiveProject: empty response body")
            return gson.fromJson(body, ActiveProject::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun correctEvent(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        eventId: Int,
        projectId: Int? = null,
        type: String? = null,
        occurredAt: String? = null,
        label: String? = null,
    ): Event {
        val fields = mutableMapOf<String, Any>()
        projectId?.let { fields["project_id"] = it }
        type?.let { fields["type"] = it }
        occurredAt?.let { fields["occurred_at"] = it }
        label?.let { fields["label"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/events/$eventId/correct")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (response.code == 409) {
                throw parseActiveConflict(body) ?: IOException("correctEvent conflict: HTTP 409")
            }
            if (!response.isSuccessful) {
                throw IOException("correctEvent failed: HTTP ${response.code}")
            }
            return gson.fromJson(body ?: throw IOException("correctEvent: empty response body"), Event::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun deleteEvent(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        eventId: Int,
    ) {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/events/$eventId")
            .delete()
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("deleteEvent failed: HTTP ${response.code}")
            }
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun createPlanEntry(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int,
        startTime: String,
        endTime: String,
        name: String? = null,
    ): PlanEntry {
        val fields = mutableMapOf<String, Any>(
            "project_id" to projectId, "start_time" to startTime, "end_time" to endTime,
        )
        name?.let { fields["name"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/entries")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("createPlanEntry failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("createPlanEntry: empty response body")
            return gson.fromJson(body, PlanEntry::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun listPlanEntries(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int? = null,
        from: String? = null,
        to: String? = null,
    ): List<PlanEntry> {
        val params = mutableListOf<String>()
        projectId?.let { params += "project_id=$it" }
        from?.let { params += "from=" + URLEncoder.encode(it, "UTF-8") }
        to?.let { params += "to=" + URLEncoder.encode(it, "UTF-8") }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/plan/entries" + query)
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("listPlanEntries failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("listPlanEntries: empty response body")
            val type = object : TypeToken<List<PlanEntry>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun listDynamicPlan(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int? = null,
        from: String? = null,
        to: String? = null,
    ): List<DynamicPlanEntry> {
        val params = mutableListOf<String>()
        projectId?.let { params += "project_id=$it" }
        from?.let { params += "from=" + URLEncoder.encode(it, "UTF-8") }
        to?.let { params += "to=" + URLEncoder.encode(it, "UTF-8") }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/plan/dynamic" + query)
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("listDynamicPlan failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("listDynamicPlan: empty response body")
            val type = object : TypeToken<List<DynamicPlanEntry>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun createPlanChange(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        planEntryId: Int,
        changeType: String,
        newStartTime: String? = null,
        newEndTime: String? = null,
    ): PlanChange {
        val fields = mutableMapOf<String, Any>("change_type" to changeType)
        newStartTime?.let { fields["new_start_time"] = it }
        newEndTime?.let { fields["new_end_time"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/entries/$planEntryId/changes")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("createPlanChange failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("createPlanChange: empty response body")
            return gson.fromJson(body, PlanChange::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun deletePlanChange(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        changeId: Int,
    ) {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/changes/$changeId")
            .delete()
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("deletePlanChange failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * Blocking call - run on a background dispatcher. Throws IOException on failure. A direct
     * mutation of a Static Plan entry (chapter 5.7), distinct from [createPlanChange].
     */
    fun updatePlanEntry(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
        projectId: Int? = null,
        startTime: String? = null,
        endTime: String? = null,
        name: String? = null,
    ): PlanEntry {
        val fields = mutableMapOf<String, Any>()
        projectId?.let { fields["project_id"] = it }
        startTime?.let { fields["start_time"] = it }
        endTime?.let { fields["end_time"] = it }
        name?.let { fields["name"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/entries/$id")
            .patch(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("updatePlanEntry failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("updatePlanEntry: empty response body")
            return gson.fromJson(body, PlanEntry::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun deletePlanEntry(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
    ) {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/entries/$id")
            .delete()
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("deletePlanEntry failed: HTTP ${response.code}")
            }
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun importCsv(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        csv: String,
        tzOffsetMinutes: Int,
    ): ImportResult {
        val json = gson.toJson(mapOf("csv" to csv, "tz_offset_minutes" to tzOffsetMinutes))
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/import/csv")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                throw IOException("importCsv failed: HTTP ${response.code} $body")
            }
            return gson.fromJson(body ?: throw IOException("importCsv: empty response body"), ImportResult::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun createPlanEntry(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int,
        startTime: String,
        endTime: String,
        name: String? = null,
    ): PlanEntry {
        val fields = mutableMapOf<String, Any>(
            "project_id" to projectId, "start_time" to startTime, "end_time" to endTime,
        )
        name?.let { fields["name"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/entries")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("createPlanEntry failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("createPlanEntry: empty response body")
            return gson.fromJson(body, PlanEntry::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun listPlanEntries(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int? = null,
        from: String? = null,
        to: String? = null,
    ): List<PlanEntry> {
        val params = mutableListOf<String>()
        projectId?.let { params += "project_id=$it" }
        from?.let { params += "from=" + URLEncoder.encode(it, "UTF-8") }
        to?.let { params += "to=" + URLEncoder.encode(it, "UTF-8") }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/plan/entries" + query)
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("listPlanEntries failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("listPlanEntries: empty response body")
            val type = object : TypeToken<List<PlanEntry>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun listDynamicPlan(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int? = null,
        from: String? = null,
        to: String? = null,
    ): List<DynamicPlanEntry> {
        val params = mutableListOf<String>()
        projectId?.let { params += "project_id=$it" }
        from?.let { params += "from=" + URLEncoder.encode(it, "UTF-8") }
        to?.let { params += "to=" + URLEncoder.encode(it, "UTF-8") }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/plan/dynamic" + query)
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("listDynamicPlan failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("listDynamicPlan: empty response body")
            val type = object : TypeToken<List<DynamicPlanEntry>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun createPlanChange(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        planEntryId: Int,
        changeType: String,
        newStartTime: String? = null,
        newEndTime: String? = null,
    ) {
        val fields = mutableMapOf<String, Any>("change_type" to changeType)
        newStartTime?.let { fields["new_start_time"] = it }
        newEndTime?.let { fields["new_end_time"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/entries/$planEntryId/changes")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("createPlanChange failed: HTTP ${response.code}")
            }
        }
    }

    private fun parseActiveConflict(body: String?): ActiveProjectConflictException? {
        if (body == null) return null
        return runCatching {
            val detail = gson.fromJson(body, JsonObject::class.java).getAsJsonObject("detail")
            ActiveProjectConflictException(
                activeProjectId = detail.get("active_project_id").asInt,
                activeEventId = detail.get("active_event_id").asInt,
                startedAt = detail.get("started_at").asString,
            )
        }.getOrNull()
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
