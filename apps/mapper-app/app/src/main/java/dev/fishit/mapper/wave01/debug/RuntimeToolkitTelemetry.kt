package dev.fishit.mapper.wave01.debug

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RuntimeToolkitTelemetry {
    private const val TAG = "PIPELAB/RTK"
    private const val EVENT_PREFIX = "PIPELAB_EVT"
    private const val ACK_PREFIX = "PIPELAB_ACK"

    private const val PREF_NAME = "mapper_toolkit_v2"
    private const val PREF_RUN_ID = "run_id"
    private const val PREF_TRACE_ID = "trace_id"
    private const val PREF_ACTION_ID = "action_id"
    private const val PREF_SPAN_ID = "span_id"

    private const val PREF_RUNTIME_SETTINGS = "mapper_toolkit_runtime_settings"
    private const val SETTING_SCOPE_MODE = "scope.mode"
    private const val SETTING_TARGET_HOST_FAMILY = "scope.target_host_family"
    private const val SETTING_ACTIVE_PHASE_ID = "probe.active_phase"
    private const val SETTING_CAPTURE_ENABLED = "capture.enabled"

    private const val PREF_COOKIE_SNAPSHOT = "mapper_toolkit_cookie_snapshot"
    private const val MAX_RECENT_REQUEST_IDS = 4096
    private const val MAX_DEDUP_REQUESTS = 8192
    private const val DEDUP_BUCKET_NS = 1_500_000_000L
    private const val DEDUP_RETENTION_NS = 12_000_000_000L
    private const val DEFAULT_SCOPE_MODE = "strict_target"

    private val ioLock = Any()
    private val requestLock = Any()
    private val dedupLock = Any()
    private val recentRequestIds = LinkedHashMap<String, String>(MAX_RECENT_REQUEST_IDS + 1, 0.75f, true)
    private val dedupRequests = LinkedHashMap<String, CanonicalRequest>(MAX_DEDUP_REQUESTS + 1, 0.75f, true)

    data class CorrelationContext(
        val traceId: String,
        val actionId: String,
        val spanId: String,
    )

    data class RequestLogResult(
        val requestId: String,
        val requestFingerprint: String,
        val normalizedUrl: String,
        val phaseId: String,
        val hostClass: String,
        val dedupOf: String?,
        val dedupCount: Int,
        val canonical: Boolean,
    )

    private data class CanonicalRequest(
        val requestId: String,
        var duplicateCount: Int,
        var lastSeenMonoNs: Long,
    )

    private data class MimeResolution(
        val mimeType: String?,
        val source: String,
    )

    fun ensureRunId(context: Context, preferred: String? = null): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_RUN_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val runId = preferred?.takeIf { it.isNotBlank() } ?: "mapper_${System.currentTimeMillis()}"
        prefs.edit().putString(PREF_RUN_ID, runId).apply()
        return runId
    }

    fun setRunId(context: Context, runId: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_RUN_ID, runId)
            .commit()
    }

    fun setRunAndContext(context: Context, runId: String, correlation: CorrelationContext) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_RUN_ID, runId)
            .putString(PREF_TRACE_ID, correlation.traceId)
            .putString(PREF_ACTION_ID, correlation.actionId)
            .putString(PREF_SPAN_ID, correlation.spanId)
            .commit()
    }

    fun currentCorrelationContext(context: Context): CorrelationContext {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val traceId = prefs.getString(PREF_TRACE_ID, null) ?: UUID.randomUUID().toString()
        val actionId = prefs.getString(PREF_ACTION_ID, null) ?: UUID.randomUUID().toString()
        val spanId = prefs.getString(PREF_SPAN_ID, null) ?: UUID.randomUUID().toString()
        return CorrelationContext(traceId, actionId, spanId)
    }

    fun setCorrelationContext(context: Context, correlation: CorrelationContext) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_TRACE_ID, correlation.traceId)
            .putString(PREF_ACTION_ID, correlation.actionId)
            .putString(PREF_SPAN_ID, correlation.spanId)
            .commit()
    }

    fun rotateAction(context: Context, traceId: String? = null): CorrelationContext {
        val correlation = CorrelationContext(
            traceId = traceId ?: UUID.randomUUID().toString(),
            actionId = UUID.randomUUID().toString(),
            spanId = UUID.randomUUID().toString(),
        )
        setCorrelationContext(context, correlation)
        return correlation
    }

    fun beginUiAction(
        context: Context,
        actionName: String,
        screenId: String? = null,
        uiAnchorId: String? = null,
        tabId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): CorrelationContext {
        val correlation = CorrelationContext(
            traceId = UUID.randomUUID().toString(),
            actionId = UUID.randomUUID().toString(),
            spanId = UUID.randomUUID().toString(),
        )
        setCorrelationContext(context, correlation)

        logEvent(
            context = context,
            eventType = "ui_action_event",
            correlation = correlation,
            payload = payload + mapOf(
                "action_name" to actionName,
                "screen_id" to screenId,
                "ui_anchor_id" to uiAnchorId,
                "tab_id" to tabId,
                "started_at" to Instant.now().toString(),
                "result" to "started",
            ),
        )

        return correlation
    }

    fun finishUiAction(
        context: Context,
        correlation: CorrelationContext,
        actionName: String,
        result: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        setCorrelationContext(context, correlation)
        logEvent(
            context = context,
            eventType = "ui_action_event",
            correlation = correlation,
            payload = payload + mapOf(
                "action_name" to actionName,
                "ended_at" to Instant.now().toString(),
                "result" to result,
            ),
        )
    }

    fun logUiObserved(
        context: Context,
        actionName: String,
        payload: Map<String, Any?> = emptyMap(),
        screenId: String? = "browser",
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "ui_action_event",
            correlation = correlation,
            payload = payload + mapOf(
                "action_name" to actionName,
                "screen_id" to screenId,
                "result" to "observed",
            ),
        )
    }

    fun logNetworkRequest(
        context: Context,
        source: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        requestId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): RequestLogResult {
        val correlation = currentCorrelationContext(context)
        val nowMonoNs = SystemClock.elapsedRealtimeNanos()
        val phaseId = activePhaseId(context)
        val targetHosts = targetHostFamily(context)
        val hostClass = classifyHost(url = url, targetHosts = targetHosts, scopeMode = scopeMode(context))
        val normalizedUrl = normalizedUrl(url)
        val headerSubset = canonicalHeaderSubset(headers)
        val requestFingerprint = requestFingerprint(method, normalizedUrl, source, headerSubset)
        val dedupKey = "$requestFingerprint:${nowMonoNs / DEDUP_BUCKET_NS}"

        synchronized(dedupLock) {
            pruneDedupRequests(nowMonoNs)
            val existing = dedupRequests[dedupKey]
            if (existing != null) {
                existing.duplicateCount += 1
                existing.lastSeenMonoNs = nowMonoNs
                rememberRequestId(url = url, method = method, requestId = existing.requestId)
                logCorrelationEvent(
                    context = context,
                    operation = "request_dedup",
                    payload = mapOf(
                        "source" to source,
                        "url" to normalizedUrl,
                        "method" to method.uppercase(Locale.ROOT),
                        "request_fingerprint" to requestFingerprint,
                        "dedup_of" to existing.requestId,
                        "dedup_count" to existing.duplicateCount,
                        "phase_id" to phaseId,
                        "host_class" to hostClass,
                    ),
                )
                return RequestLogResult(
                    requestId = existing.requestId,
                    requestFingerprint = requestFingerprint,
                    normalizedUrl = normalizedUrl,
                    phaseId = phaseId,
                    hostClass = hostClass,
                    dedupOf = existing.requestId,
                    dedupCount = existing.duplicateCount,
                    canonical = false,
                )
            }
        }

        val resolvedRequestId = requestId?.takeIf { it.isNotBlank() } ?: "req_${UUID.randomUUID()}"
        synchronized(dedupLock) {
            dedupRequests[dedupKey] = CanonicalRequest(
                requestId = resolvedRequestId,
                duplicateCount = 0,
                lastSeenMonoNs = nowMonoNs,
            )
        }
        rememberRequestId(url = url, method = method, requestId = resolvedRequestId)
        logEvent(
            context = context,
            eventType = "network_request_event",
            correlation = correlation,
            payload = payload + mapOf(
                "request_id" to resolvedRequestId,
                "request_fingerprint" to requestFingerprint,
                "phase_id" to phaseId,
                "host_class" to hostClass,
                "normalized_url" to normalizedUrl,
                "source" to source,
                "url" to url,
                "method" to method,
                "headers" to headers,
            ),
        )
        return RequestLogResult(
            requestId = resolvedRequestId,
            requestFingerprint = requestFingerprint,
            normalizedUrl = normalizedUrl,
            phaseId = phaseId,
            hostClass = hostClass,
            dedupOf = null,
            dedupCount = 0,
            canonical = true,
        )
    }

    fun logNetworkResponse(
        context: Context,
        source: String,
        url: String,
        method: String,
        statusCode: Int?,
        reason: String?,
        mimeType: String?,
        headers: Map<String, String>,
        rawBody: ByteArray? = null,
        requestId: String? = null,
        responseId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): String {
        val correlation = currentCorrelationContext(context)
        val eventId = responseId?.takeIf { it.isNotBlank() } ?: "resp_${UUID.randomUUID()}"
        val resolvedRequestId = requestId?.takeIf { it.isNotBlank() } ?: resolveRecentRequestId(url, method)
        if (!resolvedRequestId.isNullOrBlank()) {
            rememberRequestId(url = url, method = method, requestId = resolvedRequestId)
        }
        val phaseId = activePhaseId(context)
        val hostClass = classifyHost(url = url, targetHosts = targetHostFamily(context), scopeMode = scopeMode(context))
        val normalizedUrl = normalizedUrl(url)
        val requestFingerprint = requestFingerprint(method, normalizedUrl, source, canonicalHeaderSubset(headers))

        val responsePath = if (rawBody != null && rawBody.isNotEmpty()) {
            storeRawBody(context, eventId, rawBody)
        } else {
            null
        }

        val bodyPreview = if (rawBody != null && rawBody.isNotEmpty()) {
            String(rawBody, Charsets.UTF_8).take(16_384)
        } else {
            null
        }
        val bodyHash = if (rawBody != null && rawBody.isNotEmpty()) sha256(rawBody) else null
        val resolvedMime = resolveMimeType(
            url = url,
            explicitMimeType = mimeType,
            headers = headers,
            rawBody = rawBody,
        )

        logEvent(
            context = context,
            explicitEventId = eventId,
            eventType = "network_response_event",
            correlation = correlation,
            payload = payload + mapOf(
                "request_id" to resolvedRequestId,
                "response_id" to eventId,
                "request_fingerprint" to requestFingerprint,
                "phase_id" to phaseId,
                "host_class" to hostClass,
                "normalized_url" to normalizedUrl,
                "source" to source,
                "url" to url,
                "method" to method,
                "status" to statusCode,
                "status_code" to statusCode,
                "http_status" to statusCode,
                "reason" to reason,
                "mime" to resolvedMime.mimeType,
                "mime_type" to resolvedMime.mimeType,
                "mime_source" to resolvedMime.source,
                "headers" to headers,
                "body_preview" to bodyPreview,
                "response_store_path" to responsePath,
                "body_ref" to responsePath,
                "response_size_bytes" to (rawBody?.size ?: 0),
                "response_sha256" to bodyHash,
            ),
        )
        return eventId
    }

    fun logCookieEvent(
        context: Context,
        operation: String,
        domain: String,
        cookieName: String?,
        cookieValuePreview: String?,
        reason: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "cookie_event",
            correlation = correlation,
            payload = payload + mapOf(
                "operation" to operation,
                "domain" to domain,
                "cookie_name" to cookieName,
                "cookie_preview" to cookieValuePreview,
                "reason" to reason,
                "phase_id" to activePhaseId(context),
            ),
        )
    }

    fun logAuthEvent(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "auth_event",
            correlation = correlation,
            payload = payload + mapOf(
                "operation" to operation,
                "phase_id" to activePhaseId(context),
            ),
        )
    }

    fun logExtractionEvent(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "extraction_event",
            correlation = correlation,
            payload = payload + mapOf("operation" to operation),
        )
    }

    fun logCorrelationEvent(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "correlation_event",
            correlation = correlation,
            payload = payload + mapOf("operation" to operation),
        )
    }

    fun logProbePhaseEvent(
        context: Context,
        phaseId: String,
        transition: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        setActivePhaseId(context, phaseId)
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "probe_phase_event",
            correlation = correlation,
            payload = payload + mapOf(
                "phase_id" to phaseId,
                "transition" to transition,
            ),
        )
    }

    fun logProvenanceEvent(
        context: Context,
        entityType: String,
        entityKey: String,
        producedBy: String? = null,
        consumedBy: String? = null,
        derivedFrom: List<String> = emptyList(),
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "provenance_event",
            correlation = correlation,
            payload = payload + mapOf(
                "entity_type" to entityType,
                "entity_key" to entityKey,
                "produced_by" to producedBy,
                "consumed_by" to consumedBy,
                "derived_from" to derivedFrom,
                "phase_id" to activePhaseId(context),
            ),
        )
    }

    fun logStorageEvent(
        context: Context,
        storageType: String,
        key: String,
        operation: String,
        valuePreview: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "storage_event",
            correlation = correlation,
            payload = payload + mapOf(
                "storage_type" to storageType,
                "key" to key,
                "operation" to operation,
                "value_preview" to valuePreview,
                "phase_id" to activePhaseId(context),
            ),
        )
    }

    fun setActivePhaseId(context: Context, phaseId: String) {
        setRuntimeSetting(context, SETTING_ACTIVE_PHASE_ID, phaseId)
    }

    fun clearActivePhaseId(context: Context) {
        setRuntimeSetting(context, SETTING_ACTIVE_PHASE_ID, "unscoped")
    }

    fun activePhaseId(context: Context): String {
        return runtimeSettings(context).getString(SETTING_ACTIVE_PHASE_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: "unscoped"
    }

    fun setTargetHostFamily(context: Context, hostsCsv: String) {
        setRuntimeSetting(context, SETTING_TARGET_HOST_FAMILY, hostsCsv)
    }

    fun targetHostFamily(context: Context): List<String> {
        val raw = runtimeSettings(context).getString(SETTING_TARGET_HOST_FAMILY, "").orEmpty()
        return raw.split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun setScopeMode(context: Context, mode: String) {
        val normalized = mode.trim().lowercase(Locale.ROOT)
        val safeMode = when (normalized) {
            "strict_target", "full_raw_all" -> normalized
            else -> DEFAULT_SCOPE_MODE
        }
        setRuntimeSetting(context, SETTING_SCOPE_MODE, safeMode)
    }

    fun scopeMode(context: Context): String {
        return runtimeSettings(context).getString(SETTING_SCOPE_MODE, DEFAULT_SCOPE_MODE)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SCOPE_MODE
    }

    fun runtimeSettingsSnapshot(context: Context): Map<String, Any?> {
        val prefs = runtimeSettings(context)
        return mapOf(
            "scope_mode" to (prefs.getString(SETTING_SCOPE_MODE, DEFAULT_SCOPE_MODE) ?: DEFAULT_SCOPE_MODE),
            "target_host_family" to (prefs.getString(SETTING_TARGET_HOST_FAMILY, "") ?: ""),
            "active_phase_id" to (prefs.getString(SETTING_ACTIVE_PHASE_ID, "unscoped") ?: "unscoped"),
            "capture_enabled" to prefs.getBoolean(SETTING_CAPTURE_ENABLED, true),
        )
    }

    fun isCaptureEnabled(context: Context): Boolean {
        return runtimeSettings(context).getBoolean(SETTING_CAPTURE_ENABLED, true)
    }

    fun setCaptureEnabled(context: Context, enabled: Boolean) {
        runtimeSettings(context).edit().putBoolean(SETTING_CAPTURE_ENABLED, enabled).commit()
    }

    fun startCaptureSession(context: Context, source: String = "in_app_overlay") {
        setCaptureEnabled(context, true)
        logExtractionEvent(
            context = context,
            operation = "session_start",
            payload = mapOf(
                "source" to source,
                "capture_enabled" to true,
            ),
        )
    }

    fun stopCaptureSession(context: Context, source: String = "in_app_overlay") {
        logExtractionEvent(
            context = context,
            operation = "session_stop",
            payload = mapOf(
                "source" to source,
                "capture_enabled" to false,
            ),
        )
        setCaptureEnabled(context, false)
    }

    fun emitAck(op: String, status: String, payload: Map<String, Any?> = emptyMap()) {
        val json = JSONObject().apply {
            put("op", op)
            put("status", status)
            put("ts_utc", Instant.now().toString())
            put("payload", toJson(payload))
        }
        Log.i(TAG, "$ACK_PREFIX $json")
    }

    fun recordCookieSnapshot(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val host = uri.host ?: return

        val manager = CookieManager.getInstance()
        val raw = runCatching { manager.getCookie(url).orEmpty() }.getOrDefault("")

        val prefs = context.getSharedPreferences(PREF_COOKIE_SNAPSHOT, Context.MODE_PRIVATE)
        val previous = prefs.getString(host, null)
        val previousMap = parseCookieMap(previous.orEmpty())
        val currentMap = parseCookieMap(raw)

        if (previous == null) {
            prefs.edit().putString(host, raw).apply()
            for ((name, value) in currentMap) {
                logCookieEvent(
                    context = context,
                    operation = "set",
                    domain = host,
                    cookieName = name,
                    cookieValuePreview = value.take(128),
                    reason = "cookie_manager_snapshot_init",
                    payload = mapOf("cookie_length" to raw.length),
                )
            }
            return
        }

        if (previous == raw) return

        val names = linkedSetOf<String>().apply {
            addAll(previousMap.keys)
            addAll(currentMap.keys)
        }
        for (name in names) {
            val oldValue = previousMap[name]
            val newValue = currentMap[name]
            if (oldValue == null && newValue != null) {
                logCookieEvent(
                    context = context,
                    operation = "set",
                    domain = host,
                    cookieName = name,
                    cookieValuePreview = newValue.take(128),
                    reason = "cookie_manager_snapshot_diff",
                    payload = mapOf("previous_length" to previous.length, "current_length" to raw.length),
                )
            } else if (oldValue != null && newValue == null) {
                logCookieEvent(
                    context = context,
                    operation = "delete",
                    domain = host,
                    cookieName = name,
                    cookieValuePreview = null,
                    reason = "cookie_manager_snapshot_diff",
                    payload = mapOf("previous_length" to previous.length, "current_length" to raw.length),
                )
            } else if (oldValue != null && newValue != null && oldValue != newValue) {
                logCookieEvent(
                    context = context,
                    operation = "refresh",
                    domain = host,
                    cookieName = name,
                    cookieValuePreview = newValue.take(128),
                    reason = "cookie_manager_snapshot_diff",
                    payload = mapOf("previous_length" to previous.length, "current_length" to raw.length),
                )
            }
        }
        prefs.edit().putString(host, raw).apply()
    }

    fun resolveRecentRequestId(url: String, method: String): String? {
        val key = requestKey(url = url, method = method)
        synchronized(requestLock) {
            return recentRequestIds[key]
        }
    }

    fun clearRuntimeArtifacts(context: Context): Boolean {
        val root = runtimeRoot(context)
        val deleted = root.exists() && root.deleteRecursively()
        return deleted || !root.exists()
    }

    fun runtimeRoot(context: Context): File = File(context.filesDir, "runtime-toolkit")

    fun exportRuntimeArtifacts(context: Context): File {
        val root = runtimeRoot(context)
        val exportDir = File(root, "exports")
        if (!exportDir.exists()) exportDir.mkdirs()

        val exportCandidates = if (root.exists()) {
            root.walkTopDown()
                .filter { it.isFile }
                .filterNot { it.absolutePath.startsWith(exportDir.absolutePath) }
                .toList()
        } else {
            emptyList()
        }
        if (exportCandidates.isEmpty()) {
            throw IllegalStateException("No runtime artifacts available to export")
        }

        val fileName = "runtime_export_${System.currentTimeMillis()}.zip"
        val output = File(exportDir, fileName)

        synchronized(ioLock) {
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                exportCandidates.forEach { file ->
                    val relativePath = root.toPath().relativize(file.toPath()).toString()
                    zip.putNextEntry(ZipEntry(relativePath))
                    FileInputStream(file).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
        return output
    }

    private fun eventFile(context: Context): File {
        val dir = File(runtimeRoot(context), "events")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "runtime_events.jsonl")
    }

    private fun storeRawBody(context: Context, eventId: String, rawBody: ByteArray): String {
        val dir = File(runtimeRoot(context), "response_store")
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "$eventId.bin")
        synchronized(ioLock) {
            file.writeBytes(rawBody)
        }

        return "files/runtime-toolkit/response_store/${file.name}"
    }

    private fun logEvent(
        context: Context,
        eventType: String,
        correlation: CorrelationContext,
        payload: Map<String, Any?>,
        explicitEventId: String? = null,
    ) {
        if (!isCaptureEnabled(context) && eventType != "extraction_event") return
        val runId = ensureRunId(context)
        val event = JSONObject().apply {
            put("schema_version", 1)
            put("run_id", runId)
            put("event_id", explicitEventId ?: UUID.randomUUID().toString())
            put("event_type", eventType)
            put("ts_utc", Instant.now().toString())
            put("ts_mono_ns", SystemClock.elapsedRealtimeNanos())
            put("trace_id", correlation.traceId)
            put("span_id", correlation.spanId)
            put("action_id", correlation.actionId)
            put("device", buildDeviceJson())
            put("app", buildAppJson(context))
            put("payload", toJson(payload))
        }

        synchronized(ioLock) {
            eventFile(context).appendText(event.toString() + "\n")
        }

        Log.d(TAG, "$EVENT_PREFIX ${event}")
    }

    private fun rememberRequestId(url: String, method: String, requestId: String) {
        val key = requestKey(url = url, method = method)
        synchronized(requestLock) {
            recentRequestIds[key] = requestId
            if (recentRequestIds.size > MAX_RECENT_REQUEST_IDS) {
                val iterator = recentRequestIds.entries.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    private fun runtimeSettings(context: Context) =
        context.getSharedPreferences(PREF_RUNTIME_SETTINGS, Context.MODE_PRIVATE)

    private fun setRuntimeSetting(context: Context, key: String, value: String) {
        runtimeSettings(context).edit().putString(key, value).commit()
    }

    private fun canonicalHeaderSubset(headers: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val preferredKeys = listOf(
            "accept",
            "content-type",
            "origin",
            "referer",
            "authorization",
            "cookie",
            "x-requested-with",
            "x-api-key",
            "x-auth-token",
        )
        for (pref in preferredKeys) {
            headers.entries.firstOrNull { it.key.equals(pref, ignoreCase = true) }?.let { entry ->
                out[pref] = entry.value.take(256)
            }
        }
        headers.entries
            .asSequence()
            .filter { it.key.lowercase(Locale.ROOT).startsWith("x-") }
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .take(8)
            .forEach { entry ->
                out[entry.key.lowercase(Locale.ROOT)] = entry.value.take(256)
            }
        return out
    }

    private fun normalizedUrl(rawUrl: String): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl.trim()
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty()
        val queryKeys = uri.queryParameterNames.toList().sorted()
        val queryShape = if (queryKeys.isEmpty()) "" else "?" + queryKeys.joinToString("&")
        return "$scheme://$host$path$queryShape"
    }

    private fun resolveMimeType(
        url: String,
        explicitMimeType: String?,
        headers: Map<String, String>,
        rawBody: ByteArray?,
    ): MimeResolution {
        normalizeMime(explicitMimeType)?.let { return MimeResolution(mimeType = it, source = "explicit") }

        val headerMime = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        normalizeMime(headerMime)?.let { return MimeResolution(mimeType = it, source = "response_header") }

        inferMimeFromUrl(url)?.let { return MimeResolution(mimeType = it, source = "url_extension") }
        inferMimeFromBody(rawBody)?.let { return MimeResolution(mimeType = it, source = "body_sniffed") }

        return MimeResolution(mimeType = null, source = "unknown")
    }

    private fun normalizeMime(raw: String?): String? {
        val cleaned = raw
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return cleaned.ifBlank { null }
    }

    private fun inferMimeFromUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val lastPathSegment = uri.lastPathSegment.orEmpty()
        val ext = lastPathSegment.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isBlank()) return null
        return when (ext) {
            "webm" -> "video/webm"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "mpd" -> "application/dash+xml"
            "mp4", "m4v" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "m4s" -> "video/iso.segment"
            "ts" -> "video/mp2t"
            "vtt" -> "text/vtt"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> null
        }
    }

    private fun inferMimeFromBody(rawBody: ByteArray?): String? {
        if (rawBody == null || rawBody.isEmpty()) return null

        if (
            rawBody.size >= 4 &&
            rawBody[0] == 0x1A.toByte() &&
            rawBody[1] == 0x45.toByte() &&
            rawBody[2] == 0xDF.toByte() &&
            rawBody[3] == 0xA3.toByte()
        ) {
            return "video/webm"
        }

        val probe = runCatching {
            String(rawBody, 0, minOf(rawBody.size, 256), Charsets.UTF_8)
        }.getOrDefault("")
            .trimStart()

        if (probe.startsWith("#EXTM3U")) return "application/vnd.apple.mpegurl"
        if (probe.startsWith("{") || probe.startsWith("[")) return "application/json"
        if (probe.startsWith("<?xml")) return "application/xml"
        if (probe.startsWith("<MPD")) return "application/dash+xml"
        if (probe.startsWith("<!DOCTYPE html", ignoreCase = true) || probe.startsWith("<html", ignoreCase = true)) {
            return "text/html"
        }
        return null
    }

    private fun requestFingerprint(
        method: String,
        normalizedUrl: String,
        frameContext: String,
        headerSubset: Map<String, String>,
    ): String {
        val payload = "${method.uppercase(Locale.ROOT)}|$normalizedUrl|$frameContext|${headerSubset.entries.joinToString(";") { "${it.key}=${it.value}" }}"
        return sha256(payload.toByteArray(Charsets.UTF_8))
    }

    private fun classifyHost(url: String, targetHosts: List<String>, scopeMode: String): String {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host.isBlank()) return "ignored"
        if (scopeMode == "full_raw_all") return "target"
        val isTarget = targetHosts.any { configured ->
            host == configured || host.endsWith(".$configured")
        }
        return if (isTarget) "target" else "external_noise"
    }

    private fun pruneDedupRequests(nowMonoNs: Long) {
        val iterator = dedupRequests.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val age = nowMonoNs - item.value.lastSeenMonoNs
            if (age > DEDUP_RETENTION_NS || dedupRequests.size > MAX_DEDUP_REQUESTS) {
                iterator.remove()
            }
        }
    }

    private fun requestKey(url: String, method: String): String {
        return "${method.trim().uppercase()} ${url.trim()}"
    }

    private fun parseCookieMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        raw.split(';').forEach { part ->
            val token = part.trim()
            if (token.isBlank() || !token.contains("=")) return@forEach
            val name = token.substringBefore('=').trim()
            val value = token.substringAfter('=', "")
            if (name.isNotBlank()) {
                out[name] = value
            }
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            out.append(String.format("%02x", b))
        }
        return out.toString()
    }

    private fun buildDeviceJson(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
    }

    private fun buildAppJson(context: Context): JSONObject {
        val packageName = context.packageName
        var versionName: String? = null
        var versionCode: Long? = null

        runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            versionName = info.versionName
            @Suppress("DEPRECATION")
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }

        return JSONObject().apply {
            put("package", packageName)
            put("version_name", versionName)
            put("version_code", versionCode)
        }
    }

    private fun toJson(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject -> value
            is JSONArray -> value
            is String, is Number, is Boolean -> value
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) {
                    obj.put(k?.toString() ?: "null", toJson(v))
                }
                obj
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                value.forEach { arr.put(toJson(it)) }
                arr
            }
            else -> value.toString()
        }
    }
}
