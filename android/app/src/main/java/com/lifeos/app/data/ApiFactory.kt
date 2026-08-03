package com.lifeos.app.data

import com.google.gson.Gson
import com.google.gson.JsonNull
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

    // Gson's writer drops JsonNull properties from a JsonElement tree during
    // serialization unless serializeNulls() is set on the instance - this applies
    // even to nulls added explicitly (not just reflected Java nulls), so a plain
    // Gson().toJson(jsonObjectWithNull) silently omits the key. Needed wherever a
    // client-side "unset/clear this field" needs to reach the server as an
    // explicit `"field": null`, not an omitted key.
    private val gsonSerializeNulls = com.google.gson.GsonBuilder().serializeNulls().create()
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
        notes: String? = null,
    ): Project {
        val fields = mutableMapOf<String, Any>("name" to name, "color" to color)
        notes?.let { fields["notes"] = it }
        val json = gson.toJson(fields)
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
        archived: Boolean? = null,
        notes: String? = null,
    ): Project {
        val fields = mutableMapOf<String, Any>()
        name?.let { fields["name"] = it }
        color?.let { fields["color"] = it }
        archived?.let { fields["archived"] = it }
        notes?.let { fields["notes"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/projects/$id")
            .patch(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (response.code == 409) {
                throw parseProjectNameConflict(body) ?: IOException("updateProject conflict: HTTP 409")
            }
            if (!response.isSuccessful) {
                throw IOException("updateProject failed: HTTP ${response.code}")
            }
            return gson.fromJson(body ?: throw IOException("updateProject: empty response body"), Project::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. Folds
     * `sourceId`'s subtasks/events/plan entries into `targetId` and deletes `sourceId`
     * (chapter: archive restore name-collision resolution - the "merge" choice). */
    fun mergeProjects(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        sourceId: Int,
        targetId: Int,
    ): ProjectMergeResult {
        val json = gson.toJson(mapOf("source_id" to sourceId, "target_id" to targetId))
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/projects/merge")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                throw IOException("mergeProjects failed: HTTP ${response.code} $body")
            }
            return gson.fromJson(
                body ?: throw IOException("mergeProjects: empty response body"),
                ProjectMergeResult::class.java,
            )
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    /**
     * Blocking call - run on a background dispatcher. Throws
     * [ProjectHasRecordsException] on HTTP 409 (the project has Events or
     * Plan entries and `force` was false), or IOException otherwise.
     */
    fun deleteProject(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
        force: Boolean = false,
    ) {
        val query = if (force) "?force=true" else ""
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/projects/$id" + query)
            .delete()
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 409) {
                throw ProjectHasRecordsException()
            }
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
        subtaskId: Int? = null,
    ): PlanEntry {
        val fields = mutableMapOf<String, Any>(
            "project_id" to projectId, "start_time" to startTime, "end_time" to endTime,
        )
        name?.let { fields["name"] = it }
        subtaskId?.let { fields["subtask_id"] = it }
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
        subtaskId: Int? = null,
        clearSubtask: Boolean = false,
    ): PlanEntry {
        // A plain Map skips null values entirely when serialized (Gson's default
        // serializeNulls=false), which would make clearSubtask a no-op - JsonObject
        // lets us add an explicit JsonNull the server can tell apart from "omitted"
        // via Pydantic's model_fields_set. Serializing with gsonSerializeNulls (not
        // the default gson) is required too - Gson's writer drops JsonNull tree
        // nodes during toJson() unless serializeNulls() is set on the instance,
        // even for nulls added explicitly rather than via reflection.
        val fields = JsonObject()
        projectId?.let { fields.addProperty("project_id", it) }
        startTime?.let { fields.addProperty("start_time", it) }
        endTime?.let { fields.addProperty("end_time", it) }
        name?.let { fields.addProperty("name", it) }
        if (clearSubtask) fields.add("subtask_id", JsonNull.INSTANCE) else subtaskId?.let { fields.addProperty("subtask_id", it) }
        val json = gsonSerializeNulls.toJson(fields)
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
    fun createRecurringPlan(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int,
        startTimeOfDay: String,
        endTimeOfDay: String,
        weekdays: String,
        timezone: String,
        seriesStartDate: String,
        seriesEndDate: String? = null,
        name: String? = null,
        subtaskId: Int? = null,
    ): RecurringPlan {
        val fields = mutableMapOf<String, Any>(
            "project_id" to projectId,
            "start_time_of_day" to startTimeOfDay,
            "end_time_of_day" to endTimeOfDay,
            "weekdays" to weekdays,
            "timezone" to timezone,
            "series_start_date" to seriesStartDate,
        )
        seriesEndDate?.let { fields["series_end_date"] = it }
        name?.let { fields["name"] = it }
        subtaskId?.let { fields["subtask_id"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/recurring-plans")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("createRecurringPlan failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("createRecurringPlan: empty response body")
            return gson.fromJson(body, RecurringPlan::class.java)
        }
    }

    /** "All occurrences" edit (chapter: recurring plans) - blocking call, run on a background
     * dispatcher. Throws IOException on failure. */
    fun updateRecurringPlan(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
        projectId: Int? = null,
        name: String? = null,
        startTimeOfDay: String? = null,
        endTimeOfDay: String? = null,
        subtaskId: Int? = null,
        clearSubtask: Boolean = false,
        seriesEndDate: String? = null,
        clearSeriesEndDate: Boolean = false,
    ): RecurringPlan {
        val fields = JsonObject()
        projectId?.let { fields.addProperty("project_id", it) }
        name?.let { fields.addProperty("name", it) }
        startTimeOfDay?.let { fields.addProperty("start_time_of_day", it) }
        endTimeOfDay?.let { fields.addProperty("end_time_of_day", it) }
        if (clearSubtask) fields.add("subtask_id", JsonNull.INSTANCE) else subtaskId?.let { fields.addProperty("subtask_id", it) }
        if (clearSeriesEndDate) fields.add("series_end_date", JsonNull.INSTANCE) else seriesEndDate?.let { fields.addProperty("series_end_date", it) }
        val json = gsonSerializeNulls.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/recurring-plans/$id")
            .patch(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("updateRecurringPlan failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("updateRecurringPlan: empty response body")
            return gson.fromJson(body, RecurringPlan::class.java)
        }
    }

    /** "All occurrences" delete (chapter: recurring plans) - blocking call, run on a
     * background dispatcher. Throws IOException on failure. */
    fun deleteRecurringPlan(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
    ) {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/recurring-plans/$id")
            .delete()
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("deleteRecurringPlan failed: HTTP ${response.code}")
            }
        }
    }

    /** "This and following" delete (chapter: recurring plans) - blocking call, run on a
     * background dispatcher. Throws IOException on failure. */
    fun stopRecurrence(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        entryId: Int,
    ) {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/entries/$entryId/recurrence/stop")
            .post("".toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("stopRecurrence failed: HTTP ${response.code}")
            }
        }
    }

    /** "This and following" edit (chapter: recurring plans) - blocking call, run on a
     * background dispatcher. Throws IOException on failure. */
    fun splitRecurrence(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        entryId: Int,
        projectId: Int,
        startTimeOfDay: String,
        endTimeOfDay: String,
        name: String? = null,
        subtaskId: Int? = null,
    ): RecurringPlan {
        val fields = mutableMapOf<String, Any>(
            "project_id" to projectId,
            "start_time_of_day" to startTimeOfDay,
            "end_time_of_day" to endTimeOfDay,
        )
        name?.let { fields["name"] = it }
        subtaskId?.let { fields["subtask_id"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/plan/entries/$entryId/recurrence/split")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("splitRecurrence failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("splitRecurrence: empty response body")
            return gson.fromJson(body, RecurringPlan::class.java)
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

    /**
     * Blocking call - run on a background dispatcher. Throws IOException on failure.
     * `projectJson` is sent as-is (already matches the server's ImportProjectRequest
     * shape) - unlike [importCsv], there's no client-side wrapping into an envelope.
     */
    fun importProject(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectJson: String,
    ): ImportProjectResult {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/import/project")
            .post(projectJson.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                throw IOException("importProject failed: HTTP ${response.code} $body")
            }
            return gson.fromJson(
                body ?: throw IOException("importProject: empty response body"),
                ImportProjectResult::class.java,
            )
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun clearData(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        scope: String,
    ): ClearResult {
        val json = gson.toJson(mapOf("scope" to scope))
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/admin/clear")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                throw IOException("clearData failed: HTTP ${response.code} $body")
            }
            return gson.fromJson(body ?: throw IOException("clearData: empty response body"), ClearResult::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun registerDeviceToken(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        token: String,
    ) {
        val json = gson.toJson(mapOf("token" to token))
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/notifications/register")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("registerDeviceToken failed: HTTP ${response.code}")
            }
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun unregisterDeviceToken(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        token: String,
    ) {
        val json = gson.toJson(mapOf("token" to token))
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/notifications/unregister")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("unregisterDeviceToken failed: HTTP ${response.code}")
            }
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun createReminder(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        remindAt: String,
        message: String,
    ): Reminder {
        val json = gson.toJson(mapOf("remind_at" to remindAt, "message" to message))
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/reminders")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("createReminder failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("createReminder: empty response body")
            return gson.fromJson(body, Reminder::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun listReminders(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        from: String? = null,
        to: String? = null,
    ): List<Reminder> {
        val params = mutableListOf<String>()
        from?.let { params += "from=" + URLEncoder.encode(it, "UTF-8") }
        to?.let { params += "to=" + URLEncoder.encode(it, "UTF-8") }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/reminders" + query)
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("listReminders failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("listReminders: empty response body")
            val type = object : TypeToken<List<Reminder>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun deleteReminder(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
    ) {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/reminders/$id")
            .delete()
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("deleteReminder failed: HTTP ${response.code}")
            }
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun listSubtasks(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int,
    ): List<Subtask> {
        val requestBuilder = Request.Builder().url(normalize(baseUrl) + "api/projects/$projectId/subtasks")
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("listSubtasks failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("listSubtasks: empty response body")
            val type = object : TypeToken<List<Subtask>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun createSubtask(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int,
        title: String,
        parentId: Int? = null,
        isChecklist: Boolean = false,
    ): Subtask {
        val fields = mutableMapOf<String, Any>("project_id" to projectId, "title" to title)
        parentId?.let { fields["parent_id"] = it }
        if (isChecklist) fields["is_checklist"] = true
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/subtasks")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("createSubtask failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("createSubtask: empty response body")
            return gson.fromJson(body, Subtask::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun updateSubtask(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
        title: String? = null,
        done: Boolean? = null,
        notes: String? = null,
        parentId: Int? = null,
        clearParent: Boolean = false,
    ): Subtask {
        // JsonObject (not a plain map) + gsonSerializeNulls - clearParent needs an
        // explicit "parent_id": null the server can tell apart from "omitted" via
        // model_fields_set, same reasoning as updatePlanEntry's clearSubtask.
        val fields = JsonObject()
        title?.let { fields.addProperty("title", it) }
        done?.let { fields.addProperty("done", it) }
        notes?.let { fields.addProperty("notes", it) }
        if (clearParent) fields.add("parent_id", JsonNull.INSTANCE) else parentId?.let { fields.addProperty("parent_id", it) }
        val json = gsonSerializeNulls.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/subtasks/$id")
            .patch(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (response.code == 409) {
                throw parseActiveConflict(body) ?: IOException("updateSubtask conflict: HTTP 409")
            }
            if (!response.isSuccessful) {
                throw IOException("updateSubtask failed: HTTP ${response.code}")
            }
            return gson.fromJson(body ?: throw IOException("updateSubtask: empty response body"), Subtask::class.java)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. Resets
     * every checked direct child of an is_checklist subtask back to unchecked WITHOUT deleting
     * their Instant events (chapter: checklist entity - the "Завершить" button). */
    fun checklistReset(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
    ): List<Subtask> {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/subtasks/$id/checklist-reset")
            .post("".toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("checklistReset failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("checklistReset: empty response body")
            val type = object : TypeToken<List<Subtask>>() {}.type
            return gson.fromJson(body, type)
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun deleteSubtask(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        id: Int,
    ) {
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/subtasks/$id")
            .delete()
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("deleteSubtask failed: HTTP ${response.code}")
            }
        }
    }

    /** Blocking call - run on a background dispatcher. Throws IOException on failure. */
    fun reorderSubtasks(
        baseUrl: String,
        accessClientId: String = "",
        accessClientSecret: String = "",
        projectId: Int,
        orderedIds: List<Int>,
        parentId: Int? = null,
    ): List<Subtask> {
        val fields = mutableMapOf<String, Any>("ordered_ids" to orderedIds)
        parentId?.let { fields["parent_id"] = it }
        val json = gson.toJson(fields)
        val requestBuilder = Request.Builder()
            .url(normalize(baseUrl) + "api/projects/$projectId/subtasks/reorder")
            .post(json.toRequestBody(jsonMediaType))
        addAccessHeaders(requestBuilder, accessClientId, accessClientSecret)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("reorderSubtasks failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("reorderSubtasks: empty response body")
            val type = object : TypeToken<List<Subtask>>() {}.type
            return gson.fromJson(body, type)
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

    private fun parseProjectNameConflict(body: String?): ProjectNameConflictException? {
        if (body == null) return null
        return runCatching {
            val detail = gson.fromJson(body, JsonObject::class.java).getAsJsonObject("detail")
            ProjectNameConflictException(
                conflictingProjectId = detail.get("conflicting_project_id").asInt,
                conflictingProjectName = detail.get("conflicting_project_name").asString,
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
