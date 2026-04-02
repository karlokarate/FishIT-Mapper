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
    private const val DEDUP_BUCKET_NS = 150_000_000L
    private const val DEDUP_RETENTION_NS = 12_000_000_000L
    private const val DEFAULT_SCOPE_MODE = "strict_target"
    private const val PHASE_BACKGROUND = "background_noise"

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
        val responseObservable: Boolean,
    )

    private data class NormalizedUrlParts(
        val scheme: String,
        val host: String,
        val path: String,
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

    private data class NetworkSemantic(
        val classification: String,
        val operation: String,
        val labels: List<String>,
        val graphqlOperation: String?,
        val routeKind: String,
        val mimeFamily: String?,
        val mediaKind: String?,
        val tracking: Boolean,
        val authRelated: Boolean,
        val playbackRelated: Boolean,
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

    fun navigationActionName(url: String?): String {
        val semantic = classifyNetworkSemantics(
            url = url.orEmpty(),
            method = "GET",
            mimeType = null,
            statusCode = null,
            headers = emptyMap(),
            source = "webview_navigation",
        )
        return when (semantic.classification) {
            "auth" -> "webview_auth_navigation"
            "search" -> "webview_search_navigation"
            "category" -> "webview_category_navigation"
            "detail" -> "webview_detail_navigation"
            "live" -> "webview_live_navigation"
            "playback" -> "webview_playback_navigation"
            "config" -> "webview_config_navigation"
            else -> {
                if (semantic.routeKind == "home") "webview_home_navigation" else "webview_navigation"
            }
        }
    }

    fun navigationSemanticPayload(url: String?): Map<String, Any?> {
        val semantic = classifyNetworkSemantics(
            url = url.orEmpty(),
            method = "GET",
            mimeType = null,
            statusCode = null,
            headers = emptyMap(),
            source = "webview_navigation",
        )
        return mapOf(
            "navigation_classification" to semantic.classification,
            "navigation_operation" to semantic.operation,
            "navigation_labels" to semantic.labels,
            "route_kind" to semantic.routeKind,
            "graphql_operation_name" to semantic.graphqlOperation,
            "url" to url,
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
        val normalizedParts = normalizedUrlParts(url)
        val targetSiteId = resolveTargetSiteId(targetHosts = targetHosts, normalizedHost = normalizedParts.host)
        val hostClass = classifyHost(
            url = url,
            targetHosts = targetHosts,
            scopeMode = scopeMode(context),
            phaseId = phaseId,
            source = source,
        )
        val normalizedUrl = normalizedUrl(url)
        val headerSubset = canonicalHeaderSubset(headers)
        val frameContext = canonicalFrameContext(source)
        val responseObservable = isResponseObservableSource(source)
        val semantic = classifyNetworkSemantics(
            url = url,
            method = method,
            mimeType = null,
            statusCode = null,
            headers = headers,
            source = source,
        )
        val requestFingerprint = requestFingerprint(method, normalizedUrl, frameContext, headerSubset)
        val dedupFingerprint = strictRequestFingerprint(method, url, frameContext, headerSubset)
        val dedupKey = "$dedupFingerprint:${nowMonoNs / DEDUP_BUCKET_NS}"

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
                        "dedup_fingerprint" to dedupFingerprint,
                        "dedup_of" to existing.requestId,
                        "dedup_count" to existing.duplicateCount,
                        "phase_id" to phaseId,
                        "host_class" to hostClass,
                        "response_observable" to responseObservable,
                        "request_classification" to semantic.classification,
                        "request_operation" to semantic.operation,
                        "semantic_labels" to semantic.labels,
                        "graphql_operation_name" to semantic.graphqlOperation,
                        "route_kind" to semantic.routeKind,
                        "media_kind" to semantic.mediaKind,
                        "is_tracking" to semantic.tracking,
                        "is_auth_related" to semantic.authRelated,
                        "is_playback_related" to semantic.playbackRelated,
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
                    responseObservable = responseObservable,
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
                    "dedup_fingerprint" to dedupFingerprint,
                    "phase_id" to phaseId,
                    "target_site_id" to targetSiteId,
                    "host_class" to hostClass,
                    "normalized_url" to normalizedUrl,
                    "normalized_scheme" to normalizedParts.scheme,
                    "normalized_host" to normalizedParts.host,
                    "normalized_path" to normalizedParts.path,
                    "frame_context" to frameContext,
                    "source" to source,
                    "capture_channel" to source,
                    "source_channel" to source,
                    "response_observable" to responseObservable,
                    "request_classification" to semantic.classification,
                    "request_operation" to semantic.operation,
                "semantic_labels" to semantic.labels,
                "graphql_operation_name" to semantic.graphqlOperation,
                "route_kind" to semantic.routeKind,
                "media_kind" to semantic.mediaKind,
                "is_tracking" to semantic.tracking,
                "is_auth_related" to semantic.authRelated,
                "is_playback_related" to semantic.playbackRelated,
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
            responseObservable = responseObservable,
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
        val targetHosts = targetHostFamily(context)
        val normalizedParts = normalizedUrlParts(url)
        val targetSiteId = resolveTargetSiteId(targetHosts = targetHosts, normalizedHost = normalizedParts.host)
        val hostClass = classifyHost(
            url = url,
            targetHosts = targetHosts,
            scopeMode = scopeMode(context),
            phaseId = phaseId,
            source = source,
        )
        val normalizedUrl = normalizedUrl(url)
        val requestFingerprint = requestFingerprint(method, normalizedUrl, canonicalFrameContext(source), canonicalHeaderSubset(headers))

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
        val contentLengthHeader = headers.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value
            ?.trim()
            .orEmpty()
        val storedSizeBytes = rawBody?.size ?: 0
        val contentLengthInt = contentLengthHeader.toLongOrNull() ?: -1L
        val originalContentLength = when {
            contentLengthInt > 0L -> contentLengthInt
            else -> null
        }
        val explicitCaptureTruncated = (payload["capture_truncated"] as? Boolean) ?: false
        val explicitCaptureLimit = (payload["capture_limit_bytes"] as? Number)?.toInt() ?: 0
        val inferredCaptureTruncated = contentLengthInt > 0 && storedSizeBytes > 0 && storedSizeBytes.toLong() < contentLengthInt
        val captureTruncated = explicitCaptureTruncated || inferredCaptureTruncated
        val captureLimitBytes = if (captureTruncated) {
            if (explicitCaptureLimit > 0) explicitCaptureLimit else storedSizeBytes
        } else {
            0
        }
        val bodyHash = if (rawBody != null && rawBody.isNotEmpty()) sha256(rawBody) else null
        val resolvedMime = resolveMimeType(
            url = url,
            explicitMimeType = mimeType,
            headers = headers,
            rawBody = rawBody,
        )
        val semantic = classifyNetworkSemantics(
            url = url,
            method = method,
            mimeType = resolvedMime.mimeType,
            statusCode = statusCode,
            headers = headers,
            source = source,
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
                "target_site_id" to targetSiteId,
                "host_class" to hostClass,
                "normalized_url" to normalizedUrl,
                "normalized_scheme" to normalizedParts.scheme,
                "normalized_host" to normalizedParts.host,
                "normalized_path" to normalizedParts.path,
                "source" to source,
                "capture_channel" to source,
                "source_channel" to source,
                "url" to url,
                "method" to method,
                "response_classification" to semantic.classification,
                "response_operation" to semantic.operation,
                "semantic_labels" to semantic.labels,
                "graphql_operation_name" to semantic.graphqlOperation,
                "route_kind" to semantic.routeKind,
                "media_kind" to semantic.mediaKind,
                "mime_family" to semantic.mimeFamily,
                "is_tracking" to semantic.tracking,
                "is_auth_related" to semantic.authRelated,
                "is_playback_related" to semantic.playbackRelated,
                "status" to statusCode,
                "status_code" to statusCode,
                "http_status" to statusCode,
                "reason" to reason,
                "mime" to resolvedMime.mimeType,
                "mime_type" to resolvedMime.mimeType,
                "mime_source" to resolvedMime.source,
                "headers" to headers,
                "content_length_header" to contentLengthHeader,
                "original_content_length" to originalContentLength,
                "stored_size_bytes" to storedSizeBytes,
                "capture_truncated" to captureTruncated,
                "capture_limit_bytes" to captureLimitBytes,
                "body_preview" to bodyPreview,
                "response_store_path" to responsePath,
                "body_ref" to responsePath,
                "response_size_bytes" to (rawBody?.size ?: 0),
                "response_sha256" to bodyHash,
                "response_observed" to true,
            ),
        )
        emitDerivedAuthEvents(
            context = context,
            semantic = semantic,
            statusCode = statusCode,
            url = url,
            requestId = resolvedRequestId,
            responseId = eventId,
            headers = headers,
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
        val normalizedTransition = transition.trim().lowercase(Locale.ROOT).ifBlank { "mark" }
        val markerOperation = when (normalizedTransition) {
            "start", "resume", "enter" -> "${phaseId}_start"
            "stop", "exit", "pause" -> "probe_end"
            else -> "probe_mark"
        }
        logEvent(
            context = context,
            eventType = "probe_phase_event",
            correlation = correlation,
            payload = payload + mapOf(
                "phase_id" to phaseId,
                "transition" to normalizedTransition,
                "operation" to markerOperation,
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
        val normalized = phaseId.trim().ifBlank { PHASE_BACKGROUND }
        val resolved = if (normalized == "unscoped") PHASE_BACKGROUND else normalized
        setRuntimeSetting(context, SETTING_ACTIVE_PHASE_ID, resolved)
    }

    fun clearActivePhaseId(context: Context) {
        setRuntimeSetting(context, SETTING_ACTIVE_PHASE_ID, PHASE_BACKGROUND)
    }

    fun activePhaseId(context: Context): String {
        return runtimeSettings(context).getString(SETTING_ACTIVE_PHASE_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: PHASE_BACKGROUND
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
            "active_phase_id" to (prefs.getString(SETTING_ACTIVE_PHASE_ID, PHASE_BACKGROUND) ?: PHASE_BACKGROUND),
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

    private fun normalizedUrlParts(rawUrl: String): NormalizedUrlParts {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty().trim('.')
        val path = uri?.path.orEmpty().ifBlank { "/" }
        return NormalizedUrlParts(
            scheme = scheme,
            host = host,
            path = path,
        )
    }

    private fun normalizedUrl(rawUrl: String): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl.trim()
        val parts = normalizedUrlParts(rawUrl)
        val queryKeys = uri.queryParameterNames.toList().sorted()
        val queryShape = if (queryKeys.isEmpty()) "" else "?" + queryKeys.joinToString("&")
        return "${parts.scheme}://${parts.host}${parts.path}$queryShape"
    }

    private fun resolveTargetSiteId(targetHosts: List<String>, normalizedHost: String): String {
        val candidate = targetHosts.firstOrNull { it.isNotBlank() } ?: normalizedHost
        if (candidate.isBlank()) return "unknown_target"
        val parts = candidate.split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) {
            parts.takeLast(2).joinToString(".")
        } else {
            candidate
        }
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

    private fun strictRequestFingerprint(
        method: String,
        rawUrl: String,
        frameContext: String,
        headerSubset: Map<String, String>,
    ): String {
        val payload = "${method.uppercase(Locale.ROOT)}|${rawUrl.trim()}|$frameContext|${headerSubset.entries.joinToString(";") { "${it.key}=${it.value}" }}"
        return sha256(payload.toByteArray(Charsets.UTF_8))
    }

    private fun canonicalFrameContext(source: String): String {
        val normalized = source.trim().lowercase(Locale.ROOT)
        return when {
            normalized.startsWith("webview_js_bridge") -> "webview"
            normalized.startsWith("webview") -> "webview"
            normalized.startsWith("native_replay_") -> "native_replay"
            normalized.contains("okhttp") -> "okhttp"
            else -> normalized
        }
    }

    private fun isResponseObservableSource(source: String): Boolean {
        val normalized = source.trim().lowercase(Locale.ROOT)
        return when {
            normalized.startsWith("webview_js_bridge") -> true
            normalized.startsWith("native_replay_") -> true
            normalized.startsWith("webview_main_frame_html") -> true
            normalized.contains("okhttp") -> true
            normalized.contains("repository") -> true
            normalized.startsWith("webview") -> false
            else -> true
        }
    }

    private fun classifyHost(
        url: String,
        targetHosts: List<String>,
        scopeMode: String,
        phaseId: String,
        source: String,
    ): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase(Locale.ROOT).trim('.')
        val path = uri?.path.orEmpty().ifBlank { "/" }.lowercase(Locale.ROOT)
        val lowerUrl = url.lowercase(Locale.ROOT)
        val lowerSource = source.lowercase(Locale.ROOT)
        if (host.isBlank()) return "ignored"

        val normalizedTargets = targetHosts
            .map { it.lowercase(Locale.ROOT).trim('.') }
            .filter { it.isNotBlank() }
        val isTarget = normalizedTargets.any { configured ->
            host == configured || host.endsWith(".$configured")
        }
        if (isTarget) {
            if (
                lowerUrl.contains("playback") ||
                lowerUrl.contains("resolver") ||
                lowerUrl.contains("manifest") ||
                lowerUrl.contains(".m3u8") ||
                lowerUrl.contains(".mpd")
            ) {
                return "target_playback"
            }
            if (
                lowerUrl.contains("/api/") ||
                lowerUrl.contains("/v1/") ||
                lowerUrl.contains("/v2/") ||
                lowerUrl.contains("graphql") ||
                lowerUrl.contains("operationname=") ||
                lowerUrl.contains("search") ||
                lowerUrl.contains("detail")
            ) {
                return "target_api"
            }
            if (looksLikeBrowserBootstrap(url = lowerUrl, path = path, source = lowerSource)) {
                return "target_asset"
            }
            return "target_document"
        }

        if (looksLikeBrowserBootstrap(url = lowerUrl, path = path, source = lowerSource)) return "browser_bootstrap"
        if (looksLikeGoogleNoise(host, lowerUrl)) return "google_noise"
        if (looksLikeAdNoise(host, lowerUrl) || looksLikeAnalyticsNoise(host, lowerUrl)) return "analytics_noise"
        if (scopeMode == "full_raw_all" && phaseId != PHASE_BACKGROUND) return "target_document"
        return "background_noise"
    }

    private fun looksLikeGoogleNoise(host: String, url: String): Boolean {
        val value = "$host $url"
        return listOf(
            "google.",
            "google-",
            "gstatic.com",
            "googlesyndication",
            "googleadservices",
            "doubleclick.net",
            "googletagmanager",
        ).any { token -> value.contains(token) }
    }

    private fun looksLikeAnalyticsNoise(host: String, url: String): Boolean {
        val value = "$host $url"
        return listOf(
            "analytics",
            "telemetry",
            "segment.io",
            "newrelic",
            "sentry",
            "metrics",
            "pixel",
        ).any { token -> value.contains(token) }
    }

    private fun looksLikeAdNoise(host: String, url: String): Boolean {
        val value = "$host $url"
        return listOf(
            "adservice",
            "ads.",
            "/ads",
            "adnxs",
            "taboola",
            "outbrain",
        ).any { token -> value.contains(token) }
    }

    private fun looksLikeBrowserBootstrap(url: String, path: String, source: String): Boolean {
        val value = "$url $path $source"
        return listOf(
            "_next/static",
            ".woff",
            ".woff2",
            ".ttf",
            ".png",
            ".jpg",
            ".jpeg",
            ".css",
            "favicon",
            "chrome://",
            "about:blank",
        ).any { token -> value.contains(token) }
    }

    private fun classifyNetworkSemantics(
        url: String,
        method: String,
        mimeType: String?,
        statusCode: Int?,
        headers: Map<String, String>,
        source: String,
    ): NetworkSemantic {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val host = parsed?.host?.lowercase(Locale.ROOT).orEmpty()
        val path = parsed?.path.orEmpty()
        val pathLower = path.lowercase(Locale.ROOT)
        val queryLower = parsed?.encodedQuery?.lowercase(Locale.ROOT).orEmpty()
        val lowerUrl = url.lowercase(Locale.ROOT)
        val normalizedMethod = method.uppercase(Locale.ROOT)
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val graphqlOperation = parsed?.getQueryParameter("operationName")
            ?.takeIf { it.isNotBlank() }

        val labels = linkedSetOf<String>()
        val hasGraphql = pathLower.endsWith("/graphql") || lowerUrl.contains("operationname=")
        if (hasGraphql) labels += "graphql"
        if (!graphqlOperation.isNullOrBlank()) labels += "graphql_operation"

        val authRelated = containsAnyToken(
            text = "$host $pathLower $queryLower",
            tokens = listOf(
                "/auth",
                "/identity",
                "/oauth",
                "/login",
                "/signin",
                "/logout",
                "/token",
                "/session",
                "/userinfo",
                "/userdetails",
                "/fsk",
                "refresh",
            ),
        )
        if (authRelated) labels += "auth"
        val graphqlLower = graphqlOperation?.lowercase(Locale.ROOT).orEmpty()

        val searchRelated = containsAnyToken(
            text = "$pathLower $queryLower",
            tokens = listOf("/search", "/suche", "search=", "query=", "q=", "suggest"),
        ) || (!graphqlOperation.isNullOrBlank() && graphqlOperation.contains("search", ignoreCase = true))
        if (searchRelated) labels += "search"

        val categoryRelated = containsAnyToken(
            text = "$pathLower $queryLower",
            tokens = listOf("/kategorie", "/kategorien", "/category", "/genre", "collectionid", "cluster"),
        ) || (!graphqlOperation.isNullOrBlank() && (
            graphqlLower.contains("cluster") ||
                (graphqlLower.contains("collection") && !graphqlLower.contains("byvideo"))
            ))
        if (categoryRelated) labels += "category"

        val detailRelated = containsAnyToken(
            text = pathLower,
            tokens = listOf("/serien/", "/serie/", "/film", "/episode", "/sendung", "/doku", "/reportage", "/video/"),
        ) || (!graphqlOperation.isNullOrBlank() && (
            graphqlOperation.contains("details", ignoreCase = true) ||
                graphqlOperation.contains("video", ignoreCase = true) ||
                graphqlOperation.contains("smartcollection", ignoreCase = true)
            ))
        if (detailRelated) labels += "detail"

        val liveRelated = containsAnyToken(
            text = "$pathLower $queryLower",
            tokens = listOf("/live", "/livetv", "onair", "/epg"),
        ) || (!graphqlOperation.isNullOrBlank() && (
            graphqlOperation.contains("activelive", ignoreCase = true) ||
                graphqlOperation.contains("epg", ignoreCase = true)
            ))
        if (liveRelated) labels += "live"

        val playbackByPath = containsAnyToken(
            text = "$host $pathLower $queryLower",
            tokens = listOf("/tmd/", "/ptmd/", "manifest", "playlist", "/stream/", "playbackhistory", "seamless-view-entries"),
        )
        val playbackByExt = extension in setOf("webm", "m3u8", "mpd", "m4s", "mp4", "ts", "aac", "m4a", "m4v")
        val playbackByMime = mimeType?.lowercase(Locale.ROOT).orEmpty().let { mime ->
            mime.startsWith("video/") ||
                mime.startsWith("audio/") ||
                mime.contains("mpegurl") ||
                mime.contains("dash")
        }
        val playbackRelated = playbackByPath || playbackByExt || playbackByMime
        val playbackStrongSignal = pathLower.contains("/tmd/") ||
            pathLower.contains("/ptmd/") ||
            extension in setOf("webm", "m3u8", "mpd", "m4s", "mp4", "ts", "aac", "m4a", "m4v")
        if (playbackRelated) labels += "playback"

        val tracking = containsAnyToken(
            text = "$host $pathLower $queryLower",
            tokens = listOf("track", "analytic", "telemetry", "metrics", "/event", "/collect", "beacon"),
        ) || source.contains("analytics", ignoreCase = true)
        if (tracking) labels += "tracking"

        val configRelated = containsAnyToken(
            text = "$pathLower $queryLower",
            tokens = listOf("/config", "/configuration", "/settings", "/bootstrap", "appconfig"),
        )
        if (configRelated) labels += "config"

        val assetRelated = extension in setOf(
            "css",
            "js",
            "mjs",
            "map",
            "woff",
            "woff2",
            "ttf",
            "otf",
            "eot",
            "png",
            "jpg",
            "jpeg",
            "gif",
            "svg",
            "ico",
            "webp",
            "avif",
        ) || pathLower.contains("/_next/static/")
        if (assetRelated) labels += "asset"

        val routeKind = when {
            path.isBlank() || path == "/" -> "home"
            assetRelated -> "asset"
            searchRelated -> "search"
            detailRelated -> "detail"
            categoryRelated -> "category"
            playbackRelated -> "playback"
            liveRelated -> "live"
            authRelated -> "auth"
            configRelated -> "config"
            else -> "generic"
        }

        val classificationOverride = when {
            playbackStrongSignal -> "playback"
            hasGraphql && (graphqlLower.contains("activelive") || graphqlLower == "getepg") -> "live"
            hasGraphql && graphqlLower == "getsmartcollectionidsbyvideoids" -> "detail"
            else -> null
        }

        val classification = classificationOverride ?: when {
            authRelated -> "auth"
            searchRelated -> "search"
            detailRelated -> "detail"
            categoryRelated -> "category"
            liveRelated -> "live"
            playbackRelated -> "playback"
            configRelated -> "config"
            tracking -> "tracking"
            assetRelated -> "asset"
            else -> "generic"
        }

        val operation = when (classification) {
            "auth" -> when {
                pathLower.contains("/fsk/pin") -> "auth_pin_verify"
                pathLower.contains("/fsk/verification") -> "auth_age_verification"
                pathLower.contains("/userinfo") -> "auth_userinfo"
                pathLower.contains("/userdetails") -> "auth_userdetails"
                pathLower.contains("logout") -> "auth_logout"
                pathLower.contains("login") || pathLower.contains("signin") -> "auth_login"
                pathLower.contains("refresh") || pathLower.contains("token") -> "auth_token_refresh"
                else -> "auth_request"
            }

            "search" -> if (hasGraphql) "search_graphql_query" else "search_request"
            "category" -> if (hasGraphql) "category_graphql_query" else "category_browse"
            "detail" -> if (hasGraphql) "detail_graphql_query" else "detail_load"
            "live" -> if (hasGraphql) "live_catalog_query" else "live_load"
            "playback" -> when {
                pathLower.contains("/playbackhistory") || pathLower.contains("seamless-view-entries") -> "playback_history_sync"
                pathLower.contains("/tmd/") || pathLower.contains("/ptmd/") || pathLower.contains("manifest") || extension in setOf("m3u8", "mpd") -> "playback_manifest_fetch"
                extension in setOf("webm", "mp4", "m4s", "ts", "aac", "m4a", "m4v") -> "playback_media_segment"
                else -> "playback_request"
            }

            "config" -> "config_fetch"
            "tracking" -> "tracking_event"
            "asset" -> "asset_fetch"
            else -> if (normalizedMethod == "POST" && hasGraphql) "graphql_query" else "network_request"
        }

        val mimeFamily = mimeType
            ?.substringBefore('/')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
        val mediaKind = when {
            extension == "webm" || mimeType?.contains("webm", ignoreCase = true) == true -> "webm"
            extension == "m3u8" || mimeType?.contains("mpegurl", ignoreCase = true) == true -> "hls_manifest"
            extension == "mpd" || mimeType?.contains("dash", ignoreCase = true) == true -> "dash_manifest"
            extension in setOf("m4s", "ts") -> "media_segment"
            extension in setOf("mp4", "m4v") || mimeType?.contains("mp4", ignoreCase = true) == true -> "mp4"
            else -> null
        }
        if (statusCode == 401 || statusCode == 403) labels += "auth_challenge"
        if (statusCode != null && statusCode in 200..299) labels += "success"

        return NetworkSemantic(
            classification = classification,
            operation = operation,
            labels = labels.toList(),
            graphqlOperation = graphqlOperation,
            routeKind = routeKind,
            mimeFamily = mimeFamily,
            mediaKind = mediaKind,
            tracking = tracking,
            authRelated = authRelated,
            playbackRelated = playbackRelated,
        )
    }

    private fun emitDerivedAuthEvents(
        context: Context,
        semantic: NetworkSemantic,
        statusCode: Int?,
        url: String,
        requestId: String?,
        responseId: String?,
        headers: Map<String, String>,
    ) {
        if (statusCode == 401 || statusCode == 403) {
            logAuthEvent(
                context = context,
                operation = "auth_challenge",
                payload = mapOf(
                    "status_code" to statusCode,
                    "url" to url,
                    "request_id" to requestId,
                    "response_id" to responseId,
                ),
            )
            return
        }

        if (semantic.authRelated && statusCode != null && statusCode in 200..299) {
            logAuthEvent(
                context = context,
                operation = "${semantic.operation}_success",
                payload = mapOf(
                    "status_code" to statusCode,
                    "url" to url,
                    "request_id" to requestId,
                    "response_id" to responseId,
                ),
            )
        }

        if ((semantic.authRelated || semantic.playbackRelated) &&
            headers.keys.any { it.equals("set-cookie", ignoreCase = true) || it.contains("token", ignoreCase = true) }
        ) {
            logAuthEvent(
                context = context,
                operation = "token_or_cookie_updated",
                payload = mapOf(
                    "url" to url,
                    "request_id" to requestId,
                    "response_id" to responseId,
                    "headers_present" to headers.keys.map { it.lowercase(Locale.ROOT) },
                ),
            )
        }
    }

    private fun containsAnyToken(text: String, tokens: List<String>): Boolean {
        if (text.isBlank()) return false
        val haystack = text.lowercase(Locale.ROOT)
        return tokens.any { haystack.contains(it.lowercase(Locale.ROOT)) }
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
