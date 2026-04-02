package dev.fishit.mapper.wave01.debug

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import dev.fishit.mapper.network.MapperHttpMethod
import dev.fishit.mapper.network.MapperNativeHttpRequest
import dev.fishit.mapper.wave01.debug.replay.MapperNativeReplayRuntime
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
import java.util.zip.ZipFile
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
    private const val SETTING_MISSION_ID = "mission.id"
    private const val SETTING_WIZARD_STEP_ID = "wizard.step_id"
    private const val SETTING_WIZARD_SATURATION_STATE = "wizard.saturation_state"
    private const val SETTING_MISSION_TARGET_URL = "mission.target_url"
    private const val SETTING_MISSION_TARGET_SITE_ID = "mission.target_site_id"
    private const val SETTING_MISSION_TARGET_HOST_FAMILY = "mission.target_host_family"
    private const val SETTING_MISSION_STARTED_AT = "mission.started_at"
    private const val SETTING_MISSION_FINISHED_AT = "mission.finished_at"
    private const val SETTING_WIZARD_STEP_STATES_JSON = "wizard.step_states_json"
    private const val SETTING_OVERLAY_ANCHORS_JSON = "wizard.overlay_anchors_json"

    private const val PREF_COOKIE_SNAPSHOT = "mapper_toolkit_cookie_snapshot"
    private const val MAX_RECENT_REQUEST_IDS = 4096
    private const val MAX_DEDUP_REQUESTS = 8192
    private const val DEDUP_BUCKET_NS = 150_000_000L
    private const val DEDUP_RETENTION_NS = 12_000_000_000L
    private const val DEFAULT_SCOPE_MODE = "strict_target"
    private const val PHASE_BACKGROUND = "background_noise"
    private const val LEGACY_CAP_4MB = 4 * 1024 * 1024
    private const val EXPORT_READINESS_NOT_READY = "NOT_READY"
    private const val EXPORT_READINESS_PARTIAL = "PARTIAL"
    private const val EXPORT_READINESS_READY = "READY"
    private const val EXPORT_READINESS_BLOCKED = "BLOCKED"

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

    data class MissionSessionState(
        val missionId: String,
        val wizardStepId: String,
        val saturationState: String,
        val targetUrl: String,
        val targetSiteId: String,
        val targetHostFamily: String,
        val startedAt: String,
        val finishedAt: String,
        val stepStates: Map<String, String>,
    )

    data class MissionExportSummary(
        val missionId: String,
        val targetSiteId: String,
        val targetUrl: String,
        val generatedAt: String,
        val exportReadiness: String,
        val reason: String,
        val requiredSteps: List<String>,
        val missingRequiredSteps: List<String>,
        val requiredArtifacts: List<String>,
        val missingRequiredArtifacts: List<String>,
        val availableArtifacts: Map<String, Boolean>,
        val hasFinalizedExport: Boolean,
        val warnings: List<String>,
    )

    data class OverlayAnchor(
        val anchorId: String,
        val name: String,
        val anchorType: String,
        val url: String,
        val phaseId: String,
        val targetSiteId: String,
        val createdAt: String,
        val updatedAt: String,
    )

    data class ExportBundleInfo(
        val fileName: String,
        val absolutePath: String,
        val sizeBytes: Long,
        val modifiedAtUtc: String,
    )

    data class FixtureReplayStatus(
        val replayBundlePresent: Boolean,
        val fixtureManifestPresent: Boolean,
        val responseIndexPresent: Boolean,
        val runtimeEventsPresent: Boolean,
        val latestExportPresent: Boolean,
        val replayBundlePath: String,
        val fixtureManifestPath: String,
        val responseIndexPath: String,
        val runtimeEventsPath: String,
        val latestExportPath: String,
    ) {
        val ready: Boolean
            get() = replayBundlePresent && fixtureManifestPresent && responseIndexPresent && runtimeEventsPresent
    }

    data class ExportBundleReplayReadiness(
        val bundlePath: String,
        val bundleFileName: String,
        val ready: Boolean,
        val missingRequiredEntries: List<String>,
        val replayStepCount: Int,
        val runnableStepCount: Int,
        val warnings: List<String>,
    )

    data class FixtureReplayRequestResult(
        val requestId: String,
        val url: String,
        val method: String,
        val attempted: Boolean,
        val succeeded: Boolean,
        val statusCode: Int?,
        val durationMillis: Long?,
        val error: String?,
        val skipReason: String?,
    )

    data class FixtureReplayExecutionResult(
        val bundlePath: String,
        val bundleFileName: String,
        val startedAtUtc: String,
        val finishedAtUtc: String,
        val readiness: ExportBundleReplayReadiness,
        val attemptedCount: Int,
        val successCount: Int,
        val failedCount: Int,
        val skippedCount: Int,
        val transport: String,
        val reportPath: String,
        val warnings: List<String>,
        val requests: List<FixtureReplayRequestResult>,
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
        val explicitCaptureTruncated = boolPayloadValue(payload["capture_truncated"]) ?: false
        val explicitCaptureLimit = intPayloadValue(payload["capture_limit_bytes"]) ?: 0
        val inferredCaptureTruncated = contentLengthInt > 0 && storedSizeBytes > 0 && storedSizeBytes.toLong() < contentLengthInt
        val bodyCapturePolicy = stringPayloadValue(payload["body_capture_policy"])
            ?: inferBodyCapturePolicy(
                url = url,
                mimeType = mimeType,
                hostClass = hostClass,
                phaseId = phaseId,
                source = source,
            )
        val candidateRelevance = stringPayloadValue(payload["candidate_relevance"])
            ?: inferCandidateRelevance(bodyCapturePolicy)
        var captureTruncated = explicitCaptureTruncated || inferredCaptureTruncated
        if (!captureTruncated && storedSizeBytes == LEGACY_CAP_4MB && bodyCapturePolicy !in setOf("metadata_only", "skipped_media_segment", "skip_body")) {
            captureTruncated = true
        }
        val captureLimitBytes = if (captureTruncated) {
            when {
                explicitCaptureLimit > 0 -> explicitCaptureLimit
                storedSizeBytes == LEGACY_CAP_4MB -> LEGACY_CAP_4MB
                else -> storedSizeBytes
            }
        } else {
            0
        }
        val truncationReason = if (captureTruncated) {
            stringPayloadValue(payload["truncation_reason"])
                ?: if (captureLimitBytes == LEGACY_CAP_4MB) "body_size_limit" else "body_size_limit"
        } else {
            ""
        }
        val captureReason = stringPayloadValue(payload["capture_reason"]) ?: "response_capture"
        val captureFailure = if (captureTruncated && bodyCapturePolicy == "full_candidate_required") {
            stringPayloadValue(payload["capture_failure"]) ?: "required_body_truncated"
        } else {
            stringPayloadValue(payload["capture_failure"]) ?: ""
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
                "truncation_reason" to truncationReason,
                "body_capture_policy" to bodyCapturePolicy,
                "candidate_relevance" to candidateRelevance,
                "capture_reason" to captureReason,
                "capture_failure" to captureFailure,
                "body_preview" to bodyPreview,
                "response_store_path" to responsePath,
                "body_ref" to responsePath,
                "response_size_bytes" to (rawBody?.size ?: 0),
                "response_sha256" to bodyHash,
                "response_observed" to true,
            ),
        )
        if (captureTruncated || captureFailure.isNotBlank()) {
            logTruncationEvent(
                context = context,
                payload = mapOf(
                    "request_id" to resolvedRequestId,
                    "response_id" to eventId,
                    "body_ref" to responsePath,
                    "normalized_host" to normalizedParts.host,
                    "normalized_path" to normalizedParts.path,
                    "mime_type" to resolvedMime.mimeType,
                    "phase_id" to phaseId,
                    "host_class" to hostClass,
                    "capture_limit_bytes" to captureLimitBytes,
                    "stored_size_bytes" to storedSizeBytes,
                    "original_content_length" to originalContentLength,
                    "truncation_reason" to truncationReason,
                    "body_capture_policy" to bodyCapturePolicy,
                    "candidate_relevance" to candidateRelevance,
                    "capture_truncated" to captureTruncated,
                    "required_body_failure" to captureFailure,
                    "target_site_id" to targetSiteId,
                ),
            )
        }
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
        val normalizedOperation = operation.trim().ifBlank { "runtime_event" }
        val phaseId = stringPayloadValue(payload["phase_id"]) ?: activePhaseId(context)
        val hostClass = stringPayloadValue(payload["host_class"])
            ?: classifyHost(
                url = stringPayloadValue(payload["url"]).orEmpty(),
                targetHosts = targetHostFamily(context),
                scopeMode = scopeMode(context),
                phaseId = phaseId,
                source = stringPayloadValue(payload["source"]).orEmpty(),
            )
        val extractedFieldCount = intPayloadValue(payload["extracted_field_count"]) ?: 0
        val success = boolPayloadValue(payload["success"])
            ?: inferExtractionSuccess(normalizedOperation, extractedFieldCount)
        val extractionKind = stringPayloadValue(payload["extraction_kind"])
            ?: inferExtractionKind(normalizedOperation)
        val sourceRef = stringPayloadValue(payload["source_ref"])
            ?: inferExtractionSourceRef(normalizedOperation, payload)
        val confidenceSummary = stringPayloadValue(payload["confidence_summary"])
            ?: inferExtractionConfidence(extractedFieldCount, success)

        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "extraction_event",
            correlation = correlation,
            payload = payload + mapOf(
                "operation" to normalizedOperation,
                "source_ref" to sourceRef,
                "phase_id" to phaseId,
                "host_class" to hostClass,
                "extraction_kind" to extractionKind,
                "success" to success,
                "extracted_field_count" to extractedFieldCount,
                "confidence_summary" to confidenceSummary,
            ),
        )
    }

    fun logTruncationEvent(
        context: Context,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "truncation_event",
            correlation = correlation,
            payload = payload + mapOf(
                "phase_id" to (stringPayloadValue(payload["phase_id"]) ?: activePhaseId(context)),
                "host_class" to (stringPayloadValue(payload["host_class"]) ?: "ignored"),
                "truncation_reason" to (stringPayloadValue(payload["truncation_reason"]) ?: "body_size_limit"),
                "body_capture_policy" to (stringPayloadValue(payload["body_capture_policy"]) ?: "metadata_only"),
                "candidate_relevance" to (stringPayloadValue(payload["candidate_relevance"]) ?: "non_candidate"),
            ),
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
            "mission_id" to (prefs.getString(SETTING_MISSION_ID, "") ?: ""),
            "wizard_step_id" to (prefs.getString(SETTING_WIZARD_STEP_ID, "") ?: ""),
            "wizard_saturation_state" to (
                prefs.getString(SETTING_WIZARD_SATURATION_STATE, RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE)
                    ?: RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE
            ),
            "mission_target_url" to (prefs.getString(SETTING_MISSION_TARGET_URL, "") ?: ""),
            "mission_target_site_id" to (prefs.getString(SETTING_MISSION_TARGET_SITE_ID, "") ?: ""),
            "mission_target_host_family" to (prefs.getString(SETTING_MISSION_TARGET_HOST_FAMILY, "") ?: ""),
        )
    }

    private fun normalizeExportReadiness(value: String?): String {
        return when (value?.trim()?.uppercase(Locale.ROOT)) {
            EXPORT_READINESS_NOT_READY -> EXPORT_READINESS_NOT_READY
            EXPORT_READINESS_PARTIAL -> EXPORT_READINESS_PARTIAL
            EXPORT_READINESS_READY -> EXPORT_READINESS_READY
            EXPORT_READINESS_BLOCKED -> EXPORT_READINESS_BLOCKED
            else -> EXPORT_READINESS_NOT_READY
        }
    }

    fun logMissionEvent(
        context: Context,
        operation: String,
        missionId: String? = null,
        wizardStepId: String? = null,
        saturationState: String? = null,
        exportReadiness: String? = null,
        reason: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val state = missionSessionState(context)
        val safeMission = (missionId ?: state.missionId).orEmpty()
            .ifBlank { RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE }
        val safeStep = (wizardStepId ?: state.wizardStepId).orEmpty()
            .ifBlank { RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT }
        val safeSaturation = (saturationState ?: state.saturationState).orEmpty()
            .ifBlank { RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE }
        val safeTargetSite = state.targetSiteId.ifBlank { "unknown_target" }
        val safeExportReadiness = normalizeExportReadiness(exportReadiness)
        val safeReason = reason.orEmpty()
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "mission_event",
            correlation = correlation,
            payload = payload + mapOf(
                "operation" to operation.trim().ifBlank { "mission_config_applied" },
                "mission_id" to safeMission,
                "wizard_step_id" to safeStep,
                "saturation_state" to safeSaturation,
                "phase_id" to activePhaseId(context),
                "target_site_id" to safeTargetSite,
                "export_readiness" to safeExportReadiness,
                "reason" to safeReason,
            ),
        )
    }

    fun logWizardEvent(
        context: Context,
        operation: String,
        missionId: String,
        wizardStepId: String,
        saturationState: String,
        phaseId: String? = null,
        targetSiteId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val safeMission = missionId.trim().ifBlank { RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE }
        val safeStep = wizardStepId.trim().ifBlank { RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT }
        val safeSaturation = saturationState.trim()
            .ifBlank { RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE }
        val safePhase = normalizePhaseId(phaseId ?: activePhaseId(context))
        val safeTargetSite = (targetSiteId ?: missionSessionState(context).targetSiteId).trim()
            .ifBlank { "unknown_target" }
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "wizard_event",
            correlation = correlation,
            payload = payload + mapOf(
                "operation" to operation,
                "mission_id" to safeMission,
                "wizard_step_id" to safeStep,
                "saturation_state" to safeSaturation,
                "phase_id" to safePhase,
                "target_site_id" to safeTargetSite,
            ),
        )
    }

    fun logOverlayAnchorEvent(
        context: Context,
        operation: String,
        anchorId: String,
        name: String,
        anchorType: String,
        phaseId: String? = null,
        targetSiteId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val safeAnchorId = anchorId.trim().ifBlank { "anchor_${UUID.randomUUID()}" }
        val safeName = name.trim().ifBlank { "anchor" }
        val safeType = anchorType.trim().ifBlank { "custom" }
        val safePhase = normalizePhaseId(phaseId ?: activePhaseId(context))
        val safeTargetSite = (targetSiteId ?: missionSessionState(context).targetSiteId).trim()
            .ifBlank { "unknown_target" }
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "overlay_anchor_event",
            correlation = correlation,
            payload = payload + mapOf(
                "operation" to operation,
                "anchor_id" to safeAnchorId,
                "name" to safeName,
                "anchor_type" to safeType,
                "phase_id" to safePhase,
                "target_site_id" to safeTargetSite,
            ),
        )
    }

    fun missionSessionState(context: Context): MissionSessionState {
        val prefs = runtimeSettings(context)
        val missionId = prefs.getString(SETTING_MISSION_ID, "").orEmpty()
        val stepId = prefs.getString(SETTING_WIZARD_STEP_ID, "").orEmpty()
            .ifBlank { RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT }
        val saturation = prefs.getString(
            SETTING_WIZARD_SATURATION_STATE,
            RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        ).orEmpty().ifBlank { RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE }
        val targetUrl = prefs.getString(SETTING_MISSION_TARGET_URL, "").orEmpty()
        val targetSiteId = prefs.getString(SETTING_MISSION_TARGET_SITE_ID, "").orEmpty()
        val targetHostFamily = prefs.getString(SETTING_MISSION_TARGET_HOST_FAMILY, "").orEmpty()
        val startedAt = prefs.getString(SETTING_MISSION_STARTED_AT, "").orEmpty()
        val finishedAt = prefs.getString(SETTING_MISSION_FINISHED_AT, "").orEmpty()
        val stepStates = parseStepStates(prefs.getString(SETTING_WIZARD_STEP_STATES_JSON, "{}").orEmpty())
        return MissionSessionState(
            missionId = missionId,
            wizardStepId = stepId,
            saturationState = saturation,
            targetUrl = targetUrl,
            targetSiteId = targetSiteId,
            targetHostFamily = targetHostFamily,
            startedAt = startedAt,
            finishedAt = finishedAt,
            stepStates = stepStates,
        )
    }

    fun missionSessionSnapshot(context: Context): Map<String, Any?> {
        val state = missionSessionState(context)
        val summary = buildMissionExportSummary(context, state.missionId)
        return mapOf(
            "mission_id" to state.missionId,
            "wizard_step_id" to state.wizardStepId,
            "saturation_state" to state.saturationState,
            "target_url" to state.targetUrl,
            "target_site_id" to state.targetSiteId,
            "target_host_family" to state.targetHostFamily,
            "started_at" to state.startedAt,
            "finished_at" to state.finishedAt,
            "step_states" to state.stepStates,
            "anchors" to listOverlayAnchors(context).map { anchor ->
                mapOf(
                    "anchor_id" to anchor.anchorId,
                    "name" to anchor.name,
                    "anchor_type" to anchor.anchorType,
                    "phase_id" to anchor.phaseId,
                    "target_site_id" to anchor.targetSiteId,
                    "url" to anchor.url,
                )
            },
            "mission_export_summary" to mapOf(
                "export_readiness" to summary.exportReadiness,
                "reason" to summary.reason,
                "missing_required_steps" to summary.missingRequiredSteps,
                "missing_required_artifacts" to summary.missingRequiredArtifacts,
                "has_finalized_export" to summary.hasFinalizedExport,
            ),
        )
    }

    fun buildMissionExportSummary(
        context: Context,
        missionId: String = missionSessionState(context).missionId,
    ): MissionExportSummary {
        RuntimeToolkitMissionWizard.ensureRegistryLoaded(context)
        val state = missionSessionState(context)
        val safeMissionId = missionId.trim().ifBlank { state.missionId }.ifBlank {
            RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE
        }
        val requiredSteps = RuntimeToolkitMissionWizard.requiredStepIds(safeMissionId, context)
        val missingRequiredSteps = requiredSteps.filter {
            state.stepStates[it] != RuntimeToolkitMissionWizard.SATURATION_SATURATED
        }

        val root = runtimeRoot(context)
        val requiredArtifacts = RuntimeToolkitMissionWizard.requiredArtifactsForMission(safeMissionId, context)
        val availableArtifacts = linkedMapOf<String, Boolean>()
        requiredArtifacts.forEach { artifact ->
            val exists = artifact.paths.any { relativePath ->
                val artifactPath = File(root, relativePath)
                artifactPath.exists() && artifactPath.length() > 0L
            }
            availableArtifacts[artifact.id] = exists
        }
        val missingRequiredArtifacts = requiredArtifacts
            .filter { artifact -> !availableArtifacts.getOrDefault(artifact.id, false) }
            .map { it.id }

        val hasFinalizedExport = File(root, "exports")
            .listFiles()
            ?.any { it.isFile && it.name.endsWith(".zip") && it.length() > 0L }
            ?: false

        val exportReadiness: String
        val reason: String
        if (missingRequiredSteps.isNotEmpty()) {
            exportReadiness = EXPORT_READINESS_BLOCKED
            reason = "missing_required_steps"
        } else if (missingRequiredArtifacts.isEmpty() && hasFinalizedExport) {
            exportReadiness = EXPORT_READINESS_READY
            reason = "required_steps_and_artifacts_complete"
        } else if (missingRequiredArtifacts.size < requiredArtifacts.size) {
            exportReadiness = EXPORT_READINESS_PARTIAL
            reason = "required_artifacts_partial"
        } else {
            exportReadiness = EXPORT_READINESS_NOT_READY
            reason = "required_artifacts_missing"
        }

        val warnings = mutableListOf<String>()
        if (missingRequiredSteps.isNotEmpty()) {
            warnings += "missing_required_steps:${missingRequiredSteps.joinToString(",")}"
        }
        if (missingRequiredArtifacts.isNotEmpty()) {
            warnings += "missing_required_artifacts:${missingRequiredArtifacts.joinToString(",")}"
        }
        if (!hasFinalizedExport) {
            warnings += "finalized_export_missing"
        }

        return MissionExportSummary(
            missionId = safeMissionId,
            targetSiteId = state.targetSiteId.ifBlank { "unknown_target" },
            targetUrl = state.targetUrl,
            generatedAt = Instant.now().toString(),
            exportReadiness = exportReadiness,
            reason = reason,
            requiredSteps = requiredSteps,
            missingRequiredSteps = missingRequiredSteps,
            requiredArtifacts = requiredArtifacts.map { it.id },
            missingRequiredArtifacts = missingRequiredArtifacts,
            availableArtifacts = availableArtifacts,
            hasFinalizedExport = hasFinalizedExport,
            warnings = warnings,
        )
    }

    fun writeMissionExportSummary(
        context: Context,
        missionId: String = missionSessionState(context).missionId,
    ): File {
        val summary = buildMissionExportSummary(context, missionId)
        val root = runtimeRoot(context)
        if (!root.exists()) root.mkdirs()
        val summaryFile = File(root, "mission_export_summary.json")
        val json = JSONObject().apply {
            put("schema_version", 1)
            put("mission_id", summary.missionId)
            put("target_site_id", summary.targetSiteId)
            put("target_url", summary.targetUrl)
            put("generated_at_utc", summary.generatedAt)
            put("export_readiness", summary.exportReadiness)
            put("reason", summary.reason)
            put("required_steps", JSONArray(summary.requiredSteps))
            put("missing_required_steps", JSONArray(summary.missingRequiredSteps))
            put("required_artifacts", JSONArray(summary.requiredArtifacts))
            put("missing_required_artifacts", JSONArray(summary.missingRequiredArtifacts))
            put("available_artifacts", toJson(summary.availableArtifacts))
            put("has_finalized_export", summary.hasFinalizedExport)
            put("warnings", JSONArray(summary.warnings))
        }
        synchronized(ioLock) {
            summaryFile.writeText(json.toString(), Charsets.UTF_8)
        }
        return summaryFile
    }

    fun startMissionSession(
        context: Context,
        missionId: String,
    ): MissionSessionState {
        RuntimeToolkitMissionWizard.ensureRegistryLoaded(context)
        val safeMission = missionId.trim().ifBlank { RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE }
        val firstStep = RuntimeToolkitMissionWizard.firstStepId(safeMission, context)
        val stepStates = linkedMapOf(firstStep to RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE)
        val updated = MissionSessionState(
            missionId = safeMission,
            wizardStepId = firstStep,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
            targetUrl = "",
            targetSiteId = "",
            targetHostFamily = "",
            startedAt = Instant.now().toString(),
            finishedAt = "",
            stepStates = stepStates,
        )
        writeMissionSession(context, updated)
        return updated
    }

    fun setMissionTarget(
        context: Context,
        targetUrl: String,
    ): MissionSessionState {
        val state = missionSessionState(context)
        val normalizedParts = normalizedUrlParts(targetUrl)
        val host = normalizedParts.host
        val targetSiteId = canonicalTargetSiteIdFromHost(host)
        val currentHosts = targetHostFamily(context).toMutableList()
        if (host.isNotBlank() && !currentHosts.contains(host)) {
            currentHosts.add(host)
        }
        val hostCsv = currentHosts.joinToString(",")
        if (hostCsv.isNotBlank()) {
            setScopeMode(context, "strict_target")
            setTargetHostFamily(context, hostCsv)
        }
        val updated = state.copy(
            targetUrl = targetUrl.trim(),
            targetSiteId = targetSiteId,
            targetHostFamily = hostCsv,
        )
        writeMissionSession(context, updated)
        return updated
    }

    fun setMissionWizardStepState(
        context: Context,
        stepId: String,
        saturationState: String,
    ): MissionSessionState {
        val state = missionSessionState(context)
        val stepStates = LinkedHashMap(state.stepStates)
        stepStates[stepId] = saturationState
        val updated = state.copy(
            wizardStepId = stepId,
            saturationState = saturationState,
            stepStates = stepStates,
        )
        writeMissionSession(context, updated)
        return updated
    }

    fun retryMissionWizardStep(context: Context): MissionSessionState {
        val state = missionSessionState(context)
        return setMissionWizardStepState(
            context = context,
            stepId = state.wizardStepId,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
    }

    fun advanceMissionWizardStep(context: Context): MissionSessionState {
        val state = missionSessionState(context)
        val nextStep = RuntimeToolkitMissionWizard.nextStepId(state.missionId, state.wizardStepId, context) ?: state.wizardStepId
        return setMissionWizardStepState(
            context = context,
            stepId = nextStep,
            saturationState = state.stepStates[nextStep] ?: RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
    }

    fun skipOptionalMissionWizardStep(context: Context): MissionSessionState {
        val state = missionSessionState(context)
        if (!RuntimeToolkitMissionWizard.isOptionalStep(state.missionId, state.wizardStepId, context)) {
            return state
        }
        setMissionWizardStepState(
            context = context,
            stepId = state.wizardStepId,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
        )
        return advanceMissionWizardStep(context = context)
    }

    fun finishMissionSession(
        context: Context,
        finalSaturationState: String,
    ): MissionSessionState {
        val state = missionSessionState(context)
        val stepStates = LinkedHashMap(state.stepStates)
        stepStates[state.wizardStepId] = finalSaturationState
        val updated = state.copy(
            saturationState = finalSaturationState,
            finishedAt = Instant.now().toString(),
            stepStates = stepStates,
        )
        writeMissionSession(context, updated)
        return updated
    }

    fun evaluateMissionStepSaturation(
        context: Context,
        stepId: String,
    ): RuntimeToolkitMissionWizard.SaturationResult {
        val state = missionSessionState(context)
        return when (state.missionId) {
            RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
            RuntimeToolkitMissionWizard.MISSION_API_MAPPING,
            RuntimeToolkitMissionWizard.MISSION_STANDALONE_APP,
            RuntimeToolkitMissionWizard.MISSION_REPLAY_BUNDLE,
            -> evaluateFishitStepSaturation(context, stepId)
            else -> RuntimeToolkitMissionWizard.SaturationResult(
                state = RuntimeToolkitMissionWizard.SATURATION_BLOCKED,
                reason = "unsupported_mission",
            )
        }
    }

    fun listOverlayAnchors(context: Context): List<OverlayAnchor> {
        val prefs = runtimeSettings(context)
        val raw = prefs.getString(SETTING_OVERLAY_ANCHORS_JSON, "[]").orEmpty()
        return parseOverlayAnchors(raw)
    }

    fun createOverlayAnchor(
        context: Context,
        name: String,
        anchorType: String,
        url: String,
    ): OverlayAnchor {
        val now = Instant.now().toString()
        val state = missionSessionState(context)
        val anchor = OverlayAnchor(
            anchorId = "anchor_${UUID.randomUUID()}",
            name = name.trim().ifBlank { "anchor" },
            anchorType = anchorType.trim().ifBlank { "custom" },
            url = url.trim(),
            phaseId = activePhaseId(context),
            targetSiteId = state.targetSiteId.ifBlank { "unknown_target" },
            createdAt = now,
            updatedAt = now,
        )
        val anchors = listOverlayAnchors(context).toMutableList()
        anchors.add(anchor)
        runtimeSettings(context).edit()
            .putString(SETTING_OVERLAY_ANCHORS_JSON, encodeOverlayAnchors(anchors))
            .apply()
        logOverlayAnchorEvent(
            context = context,
            operation = "overlay_anchor_created",
            anchorId = anchor.anchorId,
            name = anchor.name,
            anchorType = anchor.anchorType,
            phaseId = anchor.phaseId,
            targetSiteId = anchor.targetSiteId,
            payload = mapOf("url" to anchor.url),
        )
        return anchor
    }

    fun labelOverlayAnchor(
        context: Context,
        anchorId: String,
        name: String,
        anchorType: String,
    ): OverlayAnchor? {
        val anchors = listOverlayAnchors(context).toMutableList()
        val idx = anchors.indexOfFirst { it.anchorId == anchorId }
        if (idx < 0) return null
        val current = anchors[idx]
        val updated = current.copy(
            name = name.trim().ifBlank { current.name },
            anchorType = anchorType.trim().ifBlank { current.anchorType },
            updatedAt = Instant.now().toString(),
            phaseId = activePhaseId(context),
        )
        anchors[idx] = updated
        runtimeSettings(context).edit()
            .putString(SETTING_OVERLAY_ANCHORS_JSON, encodeOverlayAnchors(anchors))
            .apply()
        logOverlayAnchorEvent(
            context = context,
            operation = "overlay_anchor_labeled",
            anchorId = updated.anchorId,
            name = updated.name,
            anchorType = updated.anchorType,
            phaseId = updated.phaseId,
            targetSiteId = updated.targetSiteId,
            payload = mapOf("url" to updated.url),
        )
        return updated
    }

    fun removeOverlayAnchor(
        context: Context,
        anchorId: String,
    ): Boolean {
        val anchors = listOverlayAnchors(context).toMutableList()
        val idx = anchors.indexOfFirst { it.anchorId == anchorId }
        if (idx < 0) return false
        val removed = anchors.removeAt(idx)
        runtimeSettings(context).edit()
            .putString(SETTING_OVERLAY_ANCHORS_JSON, encodeOverlayAnchors(anchors))
            .apply()
        logOverlayAnchorEvent(
            context = context,
            operation = "overlay_anchor_removed",
            anchorId = removed.anchorId,
            name = removed.name,
            anchorType = removed.anchorType,
            phaseId = activePhaseId(context),
            targetSiteId = removed.targetSiteId,
            payload = mapOf("url" to removed.url),
        )
        return true
    }

    fun isCaptureEnabled(context: Context): Boolean {
        return runtimeSettings(context).getBoolean(SETTING_CAPTURE_ENABLED, true)
    }

    fun setCaptureEnabled(context: Context, enabled: Boolean) {
        runtimeSettings(context).edit().putBoolean(SETTING_CAPTURE_ENABLED, enabled).commit()
    }

    fun startCaptureSession(context: Context, source: String = "in_app_overlay") {
        setCaptureEnabled(context, true)
        logMissionEvent(
            context = context,
            operation = "capture_started",
            payload = mapOf(
                "source" to source,
                "capture_enabled" to true,
            ),
        )
    }

    fun stopCaptureSession(context: Context, source: String = "in_app_overlay") {
        logMissionEvent(
            context = context,
            operation = "capture_finished",
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

    fun listExportBundles(context: Context): List<ExportBundleInfo> {
        val exportDir = File(runtimeRoot(context), "exports")
        if (!exportDir.exists()) return emptyList()
        return exportDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".zip") && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                ExportBundleInfo(
                    fileName = it.name,
                    absolutePath = it.absolutePath,
                    sizeBytes = it.length(),
                    modifiedAtUtc = Instant.ofEpochMilli(it.lastModified()).toString(),
                )
            }
            ?.toList()
            .orEmpty()
    }

    fun fixtureReplayStatus(context: Context): FixtureReplayStatus {
        val root = runtimeRoot(context)
        val replayBundle = File(root, "replay_bundle.json")
        val fixtureManifest = File(root, "fixture_manifest.json")
        val responseIndex = File(root, "response_index.json")
        val runtimeEvents = File(root, "events/runtime_events.jsonl")
        val latestExport = listExportBundles(context).firstOrNull()
        return FixtureReplayStatus(
            replayBundlePresent = replayBundle.exists() && replayBundle.length() > 0L,
            fixtureManifestPresent = fixtureManifest.exists() && fixtureManifest.length() > 0L,
            responseIndexPresent = responseIndex.exists() && responseIndex.length() > 0L,
            runtimeEventsPresent = runtimeEvents.exists() && runtimeEvents.length() > 0L,
            latestExportPresent = latestExport != null,
            replayBundlePath = replayBundle.absolutePath,
            fixtureManifestPath = fixtureManifest.absolutePath,
            responseIndexPath = responseIndex.absolutePath,
            runtimeEventsPath = runtimeEvents.absolutePath,
            latestExportPath = latestExport?.absolutePath.orEmpty(),
        )
    }

    internal fun evaluateExportBundleReplayReadiness(bundlePath: String): ExportBundleReplayReadiness {
        val requiredEntries = listOf(
            "replay_bundle.json",
            "fixture_manifest.json",
            "response_index.json",
            "events/runtime_events.jsonl",
        )
        val file = File(bundlePath)
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            return ExportBundleReplayReadiness(
                bundlePath = bundlePath,
                bundleFileName = file.name.ifBlank { "unknown_export.zip" },
                ready = false,
                missingRequiredEntries = requiredEntries,
                replayStepCount = 0,
                runnableStepCount = 0,
                warnings = listOf("bundle_not_found_or_empty"),
            )
        }

        return runCatching {
            ZipFile(file).use { zip ->
                val missing = requiredEntries.filter { findZipEntry(zip, it) == null }
                val steps = replayStepsFromBundle(zip)
                val runnable = steps.count { it.method == MapperHttpMethod.GET && it.url.startsWith("http") }
                val warnings = mutableListOf<String>()
                if (steps.isEmpty()) warnings += "replay_seed_steps_missing"
                if (runnable == 0) warnings += "no_runnable_get_steps"
                if (missing.isNotEmpty()) warnings += "missing_required_bundle_entries"
                ExportBundleReplayReadiness(
                    bundlePath = file.absolutePath,
                    bundleFileName = file.name,
                    ready = missing.isEmpty() && steps.isNotEmpty() && runnable > 0,
                    missingRequiredEntries = missing,
                    replayStepCount = steps.size,
                    runnableStepCount = runnable,
                    warnings = warnings,
                )
            }
        }.getOrElse { throwable ->
            ExportBundleReplayReadiness(
                bundlePath = file.absolutePath,
                bundleFileName = file.name.ifBlank { "unknown_export.zip" },
                ready = false,
                missingRequiredEntries = requiredEntries,
                replayStepCount = 0,
                runnableStepCount = 0,
                warnings = listOf("bundle_read_failed:${throwable.message ?: "unknown"}"),
            )
        }
    }

    fun executeFixtureReplayFromExport(
        context: Context,
        bundlePath: String,
        maxRequests: Int = 3,
    ): FixtureReplayExecutionResult {
        val startedAt = Instant.now().toString()
        val readiness = evaluateExportBundleReplayReadiness(bundlePath)
        if (!readiness.ready) {
            val blocked = FixtureReplayExecutionResult(
                bundlePath = readiness.bundlePath,
                bundleFileName = readiness.bundleFileName,
                startedAtUtc = startedAt,
                finishedAtUtc = Instant.now().toString(),
                readiness = readiness,
                attemptedCount = 0,
                successCount = 0,
                failedCount = 0,
                skippedCount = 0,
                transport = MapperNativeReplayRuntime.transportLabel(context),
                reportPath = "",
                warnings = readiness.warnings + listOf("fixture_replay_blocked_by_readiness"),
                requests = emptyList(),
            )
            writeFixtureReplayReport(context, blocked)
            return blocked
        }

        val requests = mutableListOf<FixtureReplayRequestResult>()
        val warnings = mutableListOf<String>()
        val uniqueKeys = linkedSetOf<String>()
        val maxAllowed = maxRequests.coerceIn(1, 20)
        val exportFile = File(bundlePath)

        runCatching {
            ZipFile(exportFile).use { zip ->
                replayStepsFromBundle(zip).forEach { step ->
                    val key = "${step.method}:${step.url}"
                    if (!uniqueKeys.add(key)) {
                        requests += FixtureReplayRequestResult(
                            requestId = step.requestId,
                            url = step.url,
                            method = step.method.name,
                            attempted = false,
                            succeeded = false,
                            statusCode = null,
                            durationMillis = null,
                            error = null,
                            skipReason = "duplicate_endpoint_in_seed",
                        )
                        return@forEach
                    }
                    if (requests.count { it.attempted } >= maxAllowed) {
                        requests += FixtureReplayRequestResult(
                            requestId = step.requestId,
                            url = step.url,
                            method = step.method.name,
                            attempted = false,
                            succeeded = false,
                            statusCode = null,
                            durationMillis = null,
                            error = null,
                            skipReason = "max_request_limit_reached",
                        )
                        return@forEach
                    }
                    if (step.method != MapperHttpMethod.GET) {
                        requests += FixtureReplayRequestResult(
                            requestId = step.requestId,
                            url = step.url,
                            method = step.method.name,
                            attempted = false,
                            succeeded = false,
                            statusCode = null,
                            durationMillis = null,
                            error = null,
                            skipReason = "non_get_method_skipped_for_safety",
                        )
                        return@forEach
                    }
                    if (!step.url.startsWith("http")) {
                        requests += FixtureReplayRequestResult(
                            requestId = step.requestId,
                            url = step.url,
                            method = step.method.name,
                            attempted = false,
                            succeeded = false,
                            statusCode = null,
                            durationMillis = null,
                            error = null,
                            skipReason = "invalid_or_non_http_url",
                        )
                        return@forEach
                    }

                    val response = runCatching {
                        MapperNativeReplayRuntime.execute(
                            context = context,
                            request = MapperNativeHttpRequest(
                                url = step.url,
                                method = step.method,
                                headers = step.headers,
                                queryParams = step.queryParams,
                                timeoutMillis = 20_000,
                                operationTag = "fixture_replay_from_export",
                            ),
                        )
                    }
                    response.onSuccess { native ->
                        requests += FixtureReplayRequestResult(
                            requestId = step.requestId,
                            url = step.url,
                            method = step.method.name,
                            attempted = true,
                            succeeded = native.succeeded && native.statusCode in 200..499,
                            statusCode = native.statusCode,
                            durationMillis = native.durationMillis,
                            error = native.failureMessage,
                            skipReason = null,
                        )
                    }.onFailure { throwable ->
                        requests += FixtureReplayRequestResult(
                            requestId = step.requestId,
                            url = step.url,
                            method = step.method.name,
                            attempted = true,
                            succeeded = false,
                            statusCode = null,
                            durationMillis = null,
                            error = throwable.message ?: "native_replay_failed",
                            skipReason = null,
                        )
                    }
                }
            }
        }.onFailure { throwable ->
            warnings += "replay_execution_failed:${throwable.message ?: "unknown"}"
        }

        val attempted = requests.count { it.attempted }
        val success = requests.count { it.attempted && it.succeeded }
        val failed = requests.count { it.attempted && !it.succeeded }
        val skipped = requests.count { !it.attempted }
        if (attempted == 0) warnings += "no_requests_attempted"
        if (failed > 0) warnings += "fixture_replay_has_failures"
        if (success == 0) warnings += "fixture_replay_zero_success"

        val result = FixtureReplayExecutionResult(
            bundlePath = readiness.bundlePath,
            bundleFileName = readiness.bundleFileName,
            startedAtUtc = startedAt,
            finishedAtUtc = Instant.now().toString(),
            readiness = readiness,
            attemptedCount = attempted,
            successCount = success,
            failedCount = failed,
            skippedCount = skipped,
            transport = MapperNativeReplayRuntime.transportLabel(context),
            reportPath = "",
            warnings = (readiness.warnings + warnings).distinct().sorted(),
            requests = requests,
        )
        val reportPath = writeFixtureReplayReport(context, result).absolutePath
        return result.copy(reportPath = reportPath)
    }

    private data class ReplaySeedStep(
        val requestId: String,
        val url: String,
        val method: MapperHttpMethod,
        val headers: Map<String, String>,
        val queryParams: List<Pair<String, String>>,
    )

    private fun replayStepsFromBundle(zip: ZipFile): List<ReplaySeedStep> {
        val replayBundleText = readZipEntryText(zip, "replay_bundle.json")
        val replaySeedText = readZipEntryText(zip, "replay_seed.json")
        val replaySeedNode = when {
            !replayBundleText.isNullOrBlank() -> {
                val bundle = runCatching { JSONObject(replayBundleText) }.getOrNull()
                bundle?.optJSONObject("replay_seed")
            }
            !replaySeedText.isNullOrBlank() -> runCatching { JSONObject(replaySeedText) }.getOrNull()
            else -> null
        } ?: return emptyList()

        val steps = replaySeedNode.optJSONArray("steps") ?: JSONArray()
        val parsed = mutableListOf<ReplaySeedStep>()
        for (idx in 0 until steps.length()) {
            val step = steps.optJSONObject(idx) ?: continue
            val url = step.optString("url").trim()
            if (url.isBlank()) continue
            val method = when (step.optString("method").trim().uppercase(Locale.ROOT)) {
                "POST" -> MapperHttpMethod.POST
                else -> MapperHttpMethod.GET
            }
            parsed += ReplaySeedStep(
                requestId = step.optString("request_id").trim().ifBlank { "step_$idx" },
                url = url,
                method = method,
                headers = parseReplayHeaders(step),
                queryParams = parseReplayQueryParams(step),
            )
        }
        return parsed
    }

    private fun parseReplayHeaders(step: JSONObject): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val source = step.optJSONObject("headers") ?: step.optJSONObject("headers_reduced") ?: JSONObject()
        source.keys().forEach { key ->
            val headerName = key.trim()
            if (headerName.isBlank()) return@forEach
            val lower = headerName.lowercase(Locale.ROOT)
            if (lower in setOf("host", "connection", "content-length")) return@forEach
            val value = source.opt(key)
            val rendered = when (value) {
                null, JSONObject.NULL -> ""
                is JSONArray -> (0 until value.length())
                    .mapNotNull { i -> value.opt(i)?.toString()?.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                else -> value.toString().trim()
            }
            if (rendered.isNotBlank()) {
                result[headerName] = rendered
            }
        }
        return result
    }

    private fun parseReplayQueryParams(step: JSONObject): List<Pair<String, String>> {
        val value = step.opt("query_params") ?: return emptyList()
        val pairs = mutableListOf<Pair<String, String>>()
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val paramName = key.trim()
                    if (paramName.isBlank()) return@forEach
                    val raw = value.opt(key)
                    when (raw) {
                        is JSONArray -> {
                            for (i in 0 until raw.length()) {
                                val item = raw.opt(i)?.toString()?.trim().orEmpty()
                                pairs += paramName to item
                            }
                        }
                        null, JSONObject.NULL -> pairs += paramName to ""
                        else -> pairs += paramName to raw.toString()
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    if (item is JSONObject) {
                        val name = item.optString("name").trim()
                        val rawValue = item.optString("value")
                        if (name.isNotBlank()) {
                            pairs += name to rawValue
                        }
                    }
                }
            }
        }
        return pairs
    }

    private fun findZipEntry(zip: ZipFile, suffix: String): ZipEntry? {
        val normalized = suffix.trimStart('/')
        return zip.entries().asSequence().firstOrNull { entry ->
            !entry.isDirectory && (entry.name == normalized || entry.name.endsWith("/$normalized"))
        }
    }

    private fun readZipEntryText(zip: ZipFile, suffix: String): String? {
        val entry = findZipEntry(zip, suffix) ?: return null
        return runCatching {
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    private fun writeFixtureReplayReport(
        context: Context,
        result: FixtureReplayExecutionResult,
    ): File {
        val replayDir = File(runtimeRoot(context), "replay")
        if (!replayDir.exists()) replayDir.mkdirs()
        val timestamp = result.finishedAtUtc.replace(':', '-').replace('.', '-')
        val target = File(replayDir, "fixture_replay_$timestamp.json")
        val latest = File(replayDir, "fixture_replay_latest.json")

        val payload = JSONObject().apply {
            put("schema_version", 1)
            put("bundle_path", result.bundlePath)
            put("bundle_file_name", result.bundleFileName)
            put("started_at_utc", result.startedAtUtc)
            put("finished_at_utc", result.finishedAtUtc)
            put("transport", result.transport)
            put("attempted_count", result.attemptedCount)
            put("success_count", result.successCount)
            put("failed_count", result.failedCount)
            put("skipped_count", result.skippedCount)
            put(
                "readiness",
                JSONObject().apply {
                    put("ready", result.readiness.ready)
                    put("missing_required_entries", JSONArray(result.readiness.missingRequiredEntries))
                    put("replay_step_count", result.readiness.replayStepCount)
                    put("runnable_step_count", result.readiness.runnableStepCount)
                    put("warnings", JSONArray(result.readiness.warnings))
                },
            )
            put("warnings", JSONArray(result.warnings))
            put("requests", toJson(result.requests.map { request ->
                mapOf(
                    "request_id" to request.requestId,
                    "url" to request.url,
                    "method" to request.method,
                    "attempted" to request.attempted,
                    "succeeded" to request.succeeded,
                    "status_code" to request.statusCode,
                    "duration_millis" to request.durationMillis,
                    "error" to request.error,
                    "skip_reason" to request.skipReason,
                )
            }))
        }
        synchronized(ioLock) {
            target.writeText(payload.toString(), Charsets.UTF_8)
            latest.writeText(payload.toString(), Charsets.UTF_8)
        }
        return latest
    }

    fun exportRuntimeArtifacts(context: Context): File {
        val root = runtimeRoot(context)
        val exportDir = File(root, "exports")
        if (!exportDir.exists()) exportDir.mkdirs()
        writeMissionExportSummary(context)

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
        val captureBypassEventTypes = setOf(
            "extraction_event",
            "wizard_event",
            "overlay_anchor_event",
            "probe_phase_event",
            "mission_event",
        )
        if (!isCaptureEnabled(context) && !captureBypassEventTypes.contains(eventType)) return
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

    private fun writeMissionSession(
        context: Context,
        state: MissionSessionState,
    ) {
        runtimeSettings(context).edit()
            .putString(SETTING_MISSION_ID, state.missionId)
            .putString(SETTING_WIZARD_STEP_ID, state.wizardStepId)
            .putString(SETTING_WIZARD_SATURATION_STATE, state.saturationState)
            .putString(SETTING_MISSION_TARGET_URL, state.targetUrl)
            .putString(SETTING_MISSION_TARGET_SITE_ID, state.targetSiteId)
            .putString(SETTING_MISSION_TARGET_HOST_FAMILY, state.targetHostFamily)
            .putString(SETTING_MISSION_STARTED_AT, state.startedAt)
            .putString(SETTING_MISSION_FINISHED_AT, state.finishedAt)
            .putString(SETTING_WIZARD_STEP_STATES_JSON, encodeStepStates(state.stepStates))
            .apply()
        runCatching { writeMissionExportSummary(context) }
    }

    private fun parseStepStates(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        json.keys().forEach { key ->
            val value = json.optString(key).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }

    private fun encodeStepStates(stepStates: Map<String, String>): String {
        val obj = JSONObject()
        stepStates.toSortedMap().forEach { (stepId, state) ->
            obj.put(stepId, state)
        }
        return obj.toString()
    }

    private fun parseOverlayAnchors(raw: String): List<OverlayAnchor> {
        if (raw.isBlank()) return emptyList()
        val json = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val anchors = mutableListOf<OverlayAnchor>()
        for (i in 0 until json.length()) {
            val item = json.optJSONObject(i) ?: continue
            val anchorId = item.optString("anchor_id").trim()
            if (anchorId.isBlank()) continue
            anchors += OverlayAnchor(
                anchorId = anchorId,
                name = item.optString("name").trim().ifBlank { "anchor" },
                anchorType = item.optString("anchor_type").trim().ifBlank { "custom" },
                url = item.optString("url").trim(),
                phaseId = normalizePhaseId(item.optString("phase_id")) ?: PHASE_BACKGROUND,
                targetSiteId = item.optString("target_site_id").trim().ifBlank { "unknown_target" },
                createdAt = item.optString("created_at").trim(),
                updatedAt = item.optString("updated_at").trim(),
            )
        }
        return anchors
    }

    private fun encodeOverlayAnchors(anchors: List<OverlayAnchor>): String {
        val arr = JSONArray()
        anchors.sortedBy { it.anchorId }.forEach { anchor ->
            arr.put(
                JSONObject()
                    .put("anchor_id", anchor.anchorId)
                    .put("name", anchor.name)
                    .put("anchor_type", anchor.anchorType)
                    .put("url", anchor.url)
                    .put("phase_id", anchor.phaseId)
                    .put("target_site_id", anchor.targetSiteId)
                    .put("created_at", anchor.createdAt)
                    .put("updated_at", anchor.updatedAt),
            )
        }
        return arr.toString()
    }

    private data class ResponseObservation(
        val phaseId: String,
        val hostClass: String,
        val statusCode: Int,
        val operation: String,
        val fingerprint: String,
        val mimeType: String,
        val path: String,
    ) {
        val succeeded: Boolean
            get() = statusCode in 200..399
        val targetEvidence: Boolean
            get() = hostClass.startsWith("target_")
    }

    private fun collectResponseObservations(context: Context): List<ResponseObservation> {
        val file = eventFile(context)
        if (!file.exists()) return emptyList()
        val observations = mutableListOf<ResponseObservation>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (root.optString("event_type") != "network_response_event") return@forEachLine
                val payload = root.optJSONObject("payload") ?: JSONObject()
                val phaseId = normalizePhaseId(payload.optString("phase_id")) ?: PHASE_BACKGROUND
                val hostClass = payload.optString("host_class").ifBlank { "ignored" }
                val statusCode = when {
                    payload.has("status_code") -> payload.optInt("status_code", 0)
                    payload.has("status") -> payload.optInt("status", 0)
                    else -> 0
                }
                val operation = payload.optString("request_operation")
                    .ifBlank { payload.optString("response_operation") }
                    .ifBlank { payload.optString("operation") }
                val fingerprint = payload.optString("request_fingerprint")
                    .ifBlank { payload.optString("request_id") }
                    .ifBlank { root.optString("event_id") }
                val mimeType = payload.optString("mime_type")
                    .ifBlank { payload.optString("mime") }
                val path = payload.optString("normalized_path")
                observations += ResponseObservation(
                    phaseId = phaseId,
                    hostClass = hostClass,
                    statusCode = statusCode,
                    operation = operation.lowercase(Locale.ROOT),
                    fingerprint = fingerprint,
                    mimeType = mimeType.lowercase(Locale.ROOT),
                    path = path.lowercase(Locale.ROOT),
                )
            }
        }
        return observations
    }

    private fun collectAuthEvidence(context: Context): Int {
        val file = eventFile(context)
        if (!file.exists()) return 0
        var count = 0
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (root.optString("event_type") != "auth_event") return@forEachLine
                val payload = root.optJSONObject("payload") ?: JSONObject()
                val phaseId = normalizePhaseId(payload.optString("phase_id")) ?: PHASE_BACKGROUND
                if (phaseId == "auth_probe") {
                    count += 1
                }
            }
        }
        return count
    }

    private fun evaluateFishitStepSaturation(
        context: Context,
        stepId: String,
    ): RuntimeToolkitMissionWizard.SaturationResult {
        val responses = collectResponseObservations(context)
        val successfulTargetByPhase = responses
            .filter { it.succeeded && it.targetEvidence }
            .groupBy { it.phaseId }

        return when (stepId) {
            RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT -> {
                val state = missionSessionState(context)
                if (state.targetUrl.isNotBlank() && state.targetSiteId.isNotBlank()) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "target_url_bound",
                        metrics = mapOf("target_url" to state.targetUrl, "target_site_id" to state.targetSiteId),
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
                        reason = "target_url_missing",
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_HOME_PROBE -> {
                val count = successfulTargetByPhase["home_probe"].orEmpty().size
                if (count >= 1) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "home_probe_evidence_ok",
                        metrics = mapOf("response_count" to count),
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = "missing_home_target_response",
                        metrics = mapOf("response_count" to count),
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE -> {
                val relevant = successfulTargetByPhase["search_probe"].orEmpty().filter {
                    it.operation.contains("search") || it.path.contains("/search")
                }
                val unique = relevant.map { it.fingerprint }.toSet().size
                if (unique >= 2) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "search_probe_saturated",
                        metrics = mapOf("unique_request_templates" to unique),
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = "search_probe_needs_more_variants",
                        metrics = mapOf("unique_request_templates" to unique),
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE -> {
                val relevant = successfulTargetByPhase["detail_probe"].orEmpty().filter {
                    it.operation.contains("detail") || it.path.contains("/detail")
                }
                if (relevant.isNotEmpty()) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "detail_probe_evidence_ok",
                        metrics = mapOf("response_count" to relevant.size),
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = "missing_detail_target_response",
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE -> {
                val relevant = successfulTargetByPhase["playback_probe"].orEmpty().filter {
                    it.operation.contains("playback") ||
                        it.path.endsWith(".m3u8") ||
                        it.path.endsWith(".mpd") ||
                        it.mimeType.contains("mpegurl") ||
                        it.mimeType.contains("dash+xml")
                }
                if (relevant.isNotEmpty()) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "playback_probe_evidence_ok",
                        metrics = mapOf("response_count" to relevant.size),
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = "missing_playback_manifest_or_resolver",
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL -> {
                val authEvents = collectAuthEvidence(context)
                val authResponses = successfulTargetByPhase["auth_probe"].orEmpty().size
                if (authEvents > 0 || authResponses > 0) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "auth_evidence_present",
                        metrics = mapOf("auth_events" to authEvents, "auth_responses" to authResponses),
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = "auth_optional_not_collected",
                        metrics = mapOf("auth_events" to authEvents, "auth_responses" to authResponses),
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT -> {
                val state = missionSessionState(context)
                val requiredSteps = RuntimeToolkitMissionWizard.requiredStepIds(state.missionId, context)
                val requiredComplete = requiredSteps.all {
                    state.stepStates[it] == RuntimeToolkitMissionWizard.SATURATION_SATURATED
                }
                val summary = buildMissionExportSummary(context, state.missionId)
                val exportReady = summary.exportReadiness == EXPORT_READINESS_READY
                if (requiredComplete && exportReady) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "mission_ready_for_export",
                        metrics = mapOf(
                            "required_steps_complete" to true,
                            "export_readiness" to summary.exportReadiness,
                            "summary_reason" to summary.reason,
                            "missing_required_artifacts" to summary.missingRequiredArtifacts,
                        ),
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = "final_validation_incomplete",
                        metrics = mapOf(
                            "required_steps_complete" to requiredComplete,
                            "export_readiness" to summary.exportReadiness,
                            "summary_reason" to summary.reason,
                            "missing_required_artifacts" to summary.missingRequiredArtifacts,
                            "missing_required_steps" to summary.missingRequiredSteps,
                        ),
                    )
                }
            }
            else -> RuntimeToolkitMissionWizard.SaturationResult(
                state = RuntimeToolkitMissionWizard.SATURATION_BLOCKED,
                reason = "unknown_step",
            )
        }
    }

    private fun canonicalTargetSiteIdFromHost(host: String): String {
        val normalizedHost = host.trim().lowercase(Locale.ROOT).removePrefix("www.")
        if (normalizedHost.isBlank()) return "unknown_target"
        return normalizedHost.replace(".", "_")
    }

    private fun runtimeSettings(context: Context) =
        context.getSharedPreferences(PREF_RUNTIME_SETTINGS, Context.MODE_PRIVATE)

    private fun stringPayloadValue(value: Any?): String? {
        if (value == null) return null
        val normalized = value.toString().trim()
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun intPayloadValue(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun boolPayloadValue(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase(Locale.ROOT)) {
                "1", "true", "yes", "y", "ok" -> true
                "0", "false", "no", "n" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun normalizePhaseId(phaseId: String?): String? {
        val normalized = phaseId?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return when (normalized) {
            "unscoped" -> PHASE_BACKGROUND
            "home_probe",
            "search_probe",
            "detail_probe",
            "playback_probe",
            "auth_probe",
            PHASE_BACKGROUND,
            -> normalized
            else -> PHASE_BACKGROUND
        }
    }

    private fun inferExtractionSuccess(operation: String, extractedFieldCount: Int): Boolean {
        if (extractedFieldCount > 0) return true
        val normalized = operation.lowercase(Locale.ROOT)
        return !(
            normalized.contains("fail") ||
                normalized.contains("error") ||
                normalized.endsWith("_failed")
            )
    }

    private fun inferExtractionKind(operation: String): String {
        val normalized = operation.lowercase(Locale.ROOT)
        return when {
            normalized.contains("field_matrix") -> "field_matrix"
            normalized.contains("playback") -> "playback"
            normalized.contains("detail") -> "detail"
            normalized.contains("search") -> "search"
            normalized.contains("auth") -> "auth"
            else -> "runtime_event"
        }
    }

    private fun inferExtractionSourceRef(operation: String, payload: Map<String, Any?>): String {
        stringPayloadValue(payload["source_event_id"])?.let { return "event:$it" }
        stringPayloadValue(payload["request_id"])?.let { return "request:$it" }
        stringPayloadValue(payload["response_id"])?.let { return "response:$it" }
        stringPayloadValue(payload["url"])?.let { return "url:$it" }
        return "runtime:$operation"
    }

    private fun inferExtractionConfidence(extractedFieldCount: Int, success: Boolean): String {
        if (!success || extractedFieldCount <= 0) return "none"
        return when {
            extractedFieldCount >= 6 -> "high"
            extractedFieldCount >= 3 -> "medium"
            else -> "low"
        }
    }

    private fun inferBodyCapturePolicy(
        url: String,
        mimeType: String?,
        hostClass: String,
        phaseId: String,
        source: String,
    ): String {
        val merged = "${url.lowercase(Locale.ROOT)} ${(mimeType ?: "").lowercase(Locale.ROOT)} ${source.lowercase(Locale.ROOT)}"
        val isMediaSegment = merged.contains(".m4s") ||
            merged.contains(".ts") ||
            merged.contains(".mp4") ||
            merged.contains("video/") ||
            merged.contains("audio/")
        if (isMediaSegment) return "skipped_media_segment"
        if (hostClass !in setOf("target_document", "target_api", "target_playback")) return "metadata_only"
        if (merged.contains("graphql") || merged.contains(".m3u8") || merged.contains(".mpd") || merged.contains("manifest") || merged.contains("resolver")) {
            return "full_candidate_required"
        }
        if (merged.contains("json") && phaseId in setOf("home_probe", "search_probe", "detail_probe", "playback_probe", "auth_probe")) {
            return "full_candidate_required"
        }
        if (merged.contains("html") && phaseId in setOf("home_probe", "search_probe", "detail_probe", "playback_probe", "auth_probe")) {
            return "full_candidate"
        }
        return "metadata_only"
    }

    private fun inferCandidateRelevance(bodyCapturePolicy: String): String {
        return when (bodyCapturePolicy) {
            "full_candidate_required" -> "required_candidate"
            "full_candidate", "truncated_candidate" -> "signal_candidate"
            else -> "non_candidate"
        }
    }

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
