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
    private const val SETTING_WIZARD_ARMED_ACTIVE = "wizard.armed.active"
    private const val SETTING_WIZARD_ARMED_STEP_ID = "wizard.armed.step_id"
    private const val SETTING_WIZARD_ARMED_ACTION_ID = "wizard.armed.action_id"
    private const val SETTING_WIZARD_ARMED_TRACE_ID = "wizard.armed.trace_id"
    private const val SETTING_WIZARD_ARMED_STARTED_AT_MS = "wizard.armed.started_at_ms"
    private const val SETTING_WIZARD_ARMED_EXPIRES_AT_MS = "wizard.armed.expires_at_ms"
    private const val SETTING_WIZARD_ARMED_HIT_RECORDED = "wizard.armed.hit_recorded"
    private const val SETTING_WIZARD_READY_LAST_HIT_STEP_ID = "wizard.ready.last_hit_step_id"
    private const val SETTING_WIZARD_READY_LAST_HIT_AT_MS = "wizard.ready.last_hit_at_ms"
    private const val SETTING_EXPORT_GATE_ERROR = "mission.export_gate_error"

    private const val PREF_COOKIE_SNAPSHOT = "mapper_toolkit_cookie_snapshot"
    private const val MAX_RECENT_REQUEST_IDS = 4096
    private const val MAX_DEDUP_REQUESTS = 8192
    private const val DEDUP_BUCKET_NS = 150_000_000L
    private const val DEDUP_RETENTION_NS = 12_000_000_000L
    private const val DEFAULT_SCOPE_MODE = "strict_target"
    private const val PHASE_BACKGROUND = "background_noise"
    private const val LEGACY_CAP_4MB = 4 * 1024 * 1024
    private const val WIZARD_READY_WINDOW_MS = 90_000L
    private const val EXPORT_READINESS_NOT_READY = "NOT_READY"
    private const val EXPORT_READINESS_PARTIAL = "PARTIAL"
    private const val EXPORT_READINESS_READY = "READY"
    private const val EXPORT_READINESS_BLOCKED = "BLOCKED"
    private const val ENDPOINT_OVERRIDE_SCHEMA_VERSION = 1
    private const val ENDPOINT_OVERRIDE_FILE = "endpoint_overrides.json"
    private val MINIMAL_RUNTIME_EXPORT_FILES = setOf(
        "source_pipeline_bundle.json",
        "site_runtime_model.json",
        "manifest.json",
        "provider_draft_export.json",
        "fishit_provider_draft.json",
        "endpoint_templates.json",
        "endpoint_candidates.json",
        "endpoint_overrides.json",
        "replay_requirements.json",
        "field_matrix.json",
        "auth_draft.json",
        "playback_draft.json",
        "pipeline_ready_report.json",
        "mission_export_summary.json",
        "exports/source_plugin_bundle.zip",
    )

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

    data class ReadyWindowState(
        val active: Boolean,
        val armedStepId: String,
        val armedActionId: String,
        val armedTraceId: String,
        val armedStartedAt: String,
        val armedExpiresAt: String,
        val withinWindow: Boolean,
        val expiresAtEpochMillis: Long,
        val hitRecorded: Boolean,
    )

    data class ReadyHitState(
        val stepId: String,
        val hitAt: String,
        val ageSeconds: Int,
        val recent: Boolean,
    )

    data class StepFeedback(
        val state: String,
        val progressPercent: Int,
        val missingSignals: List<String>,
        val userHints: List<String>,
        val reason: String,
        val metrics: Map<String, Any?>,
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

    data class EndpointOverrideTestResult(
        val status: Int,
        val durationMillis: Long,
        val sizeBytes: Int,
        val mimeType: String,
        val ok: Boolean,
        val recordedAt: String,
    )

    data class EndpointOverrides(
        val schemaVersion: Int,
        val selectedEndpointByRole: LinkedHashMap<String, String>,
        val excludedEndpoints: LinkedHashSet<String>,
        val lastTestResults: LinkedHashMap<String, EndpointOverrideTestResult>,
    )

    data class LiveCorrelationCount(
        val phaseId: String,
        val routeKind: String,
        val statusBucket: String,
        val hostClass: String,
        val count: Int,
    )

    data class LiveCorrelationEntry(
        val statusCode: Int,
        val routeKind: String,
        val operation: String,
        val normalizedHost: String,
        val normalizedPath: String,
        val phaseId: String,
    )

    data class LiveEndpointCandidate(
        val endpointId: String,
        val role: String,
        val method: String,
        val normalizedHost: String,
        val normalizedPath: String,
        val requestOperation: String,
        val score: Double,
        val confidence: Double,
        val evidenceCount: Int,
        val phaseId: String,
        val queryParamNames: List<String>,
        val bodyFieldNames: List<String>,
        val sampleUrl: String,
        val internalSignals: List<String> = emptyList(),
        val topologyHints: List<String> = emptyList(),
    )

    data class LiveWizardSnapshot(
        val schemaVersion: Int,
        val generatedAt: String,
        val missionId: String,
        val wizardStepId: String,
        val phaseId: String,
        val correlationSummary: List<LiveCorrelationCount>,
        val recentCorrelated: List<LiveCorrelationEntry>,
        val endpointCandidates: Map<String, List<LiveEndpointCandidate>>,
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
        val readyWindowPayload = resolveReadyWindowContextForNetworkEvent(
            context = context,
            semantic = semantic,
            normalizedPath = normalizedParts.path,
            operation = semantic.operation,
            eventKind = "request",
            requestId = requestId,
            phaseId = phaseId,
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
                    "wizard_arm_active" to readyWindowPayload["wizard_arm_active"],
                    "wizard_arm_step_id" to readyWindowPayload["wizard_arm_step_id"],
                    "wizard_arm_action_id" to readyWindowPayload["wizard_arm_action_id"],
                    "wizard_arm_within_window" to readyWindowPayload["wizard_arm_within_window"],
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
                "wizard_arm_active" to readyWindowPayload["wizard_arm_active"],
                "wizard_arm_step_id" to readyWindowPayload["wizard_arm_step_id"],
                "wizard_arm_action_id" to readyWindowPayload["wizard_arm_action_id"],
                "wizard_arm_within_window" to readyWindowPayload["wizard_arm_within_window"],
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
        val readyWindowPayload = resolveReadyWindowContextForNetworkEvent(
            context = context,
            semantic = semantic,
            normalizedPath = normalizedParts.path,
            operation = semantic.operation,
            eventKind = "response",
            requestId = resolvedRequestId,
            phaseId = phaseId,
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
                "wizard_arm_active" to readyWindowPayload["wizard_arm_active"],
                "wizard_arm_step_id" to readyWindowPayload["wizard_arm_step_id"],
                "wizard_arm_action_id" to readyWindowPayload["wizard_arm_action_id"],
                "wizard_arm_within_window" to readyWindowPayload["wizard_arm_within_window"],
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
        val readyWindow = readyWindowState(context)
        val readyHit = latestReadyHitState(context)
        return mapOf(
            "scope_mode" to (prefs.getString(SETTING_SCOPE_MODE, DEFAULT_SCOPE_MODE) ?: DEFAULT_SCOPE_MODE),
            "target_host_family" to (prefs.getString(SETTING_TARGET_HOST_FAMILY, "") ?: ""),
            "active_phase_id" to (prefs.getString(SETTING_ACTIVE_PHASE_ID, PHASE_BACKGROUND) ?: PHASE_BACKGROUND),
            "capture_enabled" to prefs.getBoolean(SETTING_CAPTURE_ENABLED, false),
            "mission_id" to (prefs.getString(SETTING_MISSION_ID, "") ?: ""),
            "wizard_step_id" to (prefs.getString(SETTING_WIZARD_STEP_ID, "") ?: ""),
            "wizard_saturation_state" to (
                prefs.getString(SETTING_WIZARD_SATURATION_STATE, RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE)
                    ?: RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE
            ),
            "mission_target_url" to (prefs.getString(SETTING_MISSION_TARGET_URL, "") ?: ""),
            "mission_target_site_id" to (prefs.getString(SETTING_MISSION_TARGET_SITE_ID, "") ?: ""),
            "mission_target_host_family" to (prefs.getString(SETTING_MISSION_TARGET_HOST_FAMILY, "") ?: ""),
            "ready_window" to mapOf(
                "active" to readyWindow.active,
                "expires_at" to readyWindow.armedExpiresAt,
                "armed_step_id" to readyWindow.armedStepId,
                "armed_action_id" to readyWindow.armedActionId,
                "within_window" to readyWindow.withinWindow,
                "last_hit_step_id" to readyHit.stepId,
                "last_hit_at" to readyHit.hitAt,
                "last_hit_recent" to readyHit.recent,
                "last_hit_age_seconds" to readyHit.ageSeconds,
            ),
            "export_gate_error" to latestExportGateError(context),
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
        val feedback = evaluateMissionStepFeedback(context, state.wizardStepId)
        val readyWindow = readyWindowState(context)
        val readyHit = latestReadyHitState(context)
        return mapOf(
            "mission_id" to state.missionId,
            "wizard_step_id" to state.wizardStepId,
            "saturation_state" to state.saturationState,
            "step_progress_percent" to feedback.progressPercent,
            "step_missing_signals" to feedback.missingSignals,
            "step_user_hints" to feedback.userHints,
            "step_reason" to feedback.reason,
            "step_metrics" to feedback.metrics,
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
            "ready_window" to mapOf(
                "active" to readyWindow.active,
                "expires_at" to readyWindow.armedExpiresAt,
                "armed_step_id" to readyWindow.armedStepId,
                "armed_action_id" to readyWindow.armedActionId,
                "last_hit_step_id" to readyHit.stepId,
                "last_hit_at" to readyHit.hitAt,
                "last_hit_recent" to readyHit.recent,
                "last_hit_age_seconds" to readyHit.ageSeconds,
            ),
            "export_gate_error" to latestExportGateError(context),
        )
    }

    fun ensureEndpointOverrides(context: Context): EndpointOverrides {
        return readEndpointOverrides(runtimeRoot(context))
    }

    fun readEndpointOverrides(context: Context): EndpointOverrides {
        return readEndpointOverrides(runtimeRoot(context))
    }

    fun readEndpointOverrides(runtimeRoot: File): EndpointOverrides {
        if (!runtimeRoot.exists()) runtimeRoot.mkdirs()
        val file = File(runtimeRoot, ENDPOINT_OVERRIDE_FILE)
        synchronized(ioLock) {
            if (!file.exists() || file.length() <= 0L) {
                val defaults = defaultEndpointOverrides()
                writeEndpointOverrides(runtimeRoot, defaults)
                return defaults
            }
            val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
            val schema = json.optInt("schema_version", ENDPOINT_OVERRIDE_SCHEMA_VERSION)
            val selected = linkedMapOf<String, String>()
            val selectedObj = json.optJSONObject("selected_endpoint_by_role") ?: JSONObject()
            selectedObj.keys().forEach { role ->
                val value = selectedObj.optString(role).trim()
                if (value.isNotBlank()) selected[role.trim()] = value
            }
            val excluded = linkedSetOf<String>()
            val excludedArr = json.optJSONArray("excluded_endpoints") ?: JSONArray()
            for (i in 0 until excludedArr.length()) {
                val value = excludedArr.optString(i).trim()
                if (value.isNotBlank()) excluded += value
            }
            val testResults = linkedMapOf<String, EndpointOverrideTestResult>()
            val testsObj = json.optJSONObject("last_test_results") ?: JSONObject()
            testsObj.keys().forEach { endpointId ->
                val entry = testsObj.optJSONObject(endpointId) ?: return@forEach
                val result = EndpointOverrideTestResult(
                    status = entry.optInt("status", 0),
                    durationMillis = entry.optLong("ms", 0L),
                    sizeBytes = entry.optInt("size", 0),
                    mimeType = entry.optString("mime").trim(),
                    ok = entry.optBoolean("ok", false),
                    recordedAt = entry.optString("at").trim(),
                )
                testResults[endpointId] = result
            }
            return EndpointOverrides(
                schemaVersion = schema,
                selectedEndpointByRole = LinkedHashMap(selected),
                excludedEndpoints = LinkedHashSet(excluded),
                lastTestResults = LinkedHashMap(testResults),
            )
        }
    }

    fun writeEndpointOverrides(context: Context, overrides: EndpointOverrides): EndpointOverrides {
        return writeEndpointOverrides(runtimeRoot(context), overrides)
    }

    fun writeEndpointOverrides(runtimeRoot: File, overrides: EndpointOverrides): EndpointOverrides {
        if (!runtimeRoot.exists()) runtimeRoot.mkdirs()
        val file = File(runtimeRoot, ENDPOINT_OVERRIDE_FILE)
        val payload = JSONObject().apply {
            put("schema_version", overrides.schemaVersion)
            put("selected_endpoint_by_role", JSONObject().apply {
                overrides.selectedEndpointByRole.toSortedMap().forEach { (role, endpointId) ->
                    put(role, endpointId)
                }
            })
            put("excluded_endpoints", JSONArray().apply {
                overrides.excludedEndpoints.toList().sorted().forEach { put(it) }
            })
            put("last_test_results", JSONObject().apply {
                overrides.lastTestResults.toSortedMap().forEach { (endpointId, result) ->
                    put(
                        endpointId,
                        JSONObject().apply {
                            put("status", result.status)
                            put("ms", result.durationMillis)
                            put("size", result.sizeBytes)
                            put("mime", result.mimeType)
                            put("ok", result.ok)
                            put("at", result.recordedAt)
                        },
                    )
                }
            })
        }
        synchronized(ioLock) {
            file.writeText(payload.toString(2) + "\n", Charsets.UTF_8)
        }
        return overrides
    }

    fun setEndpointOverrideSelection(
        context: Context,
        role: String,
        endpointId: String?,
    ): EndpointOverrides {
        val safeRole = role.trim()
        if (safeRole.isBlank()) return readEndpointOverrides(context)
        val overrides = readEndpointOverrides(context)
        val selected = LinkedHashMap(overrides.selectedEndpointByRole)
        if (endpointId.isNullOrBlank()) {
            selected.remove(safeRole)
        } else {
            selected[safeRole] = endpointId.trim()
        }
        return writeEndpointOverrides(
            context,
            overrides.copy(selectedEndpointByRole = selected),
        )
    }

    fun setEndpointExcluded(
        context: Context,
        endpointId: String,
        excluded: Boolean,
    ): EndpointOverrides {
        val safeId = endpointId.trim()
        if (safeId.isBlank()) return readEndpointOverrides(context)
        val overrides = readEndpointOverrides(context)
        val excludedIds = LinkedHashSet(overrides.excludedEndpoints)
        val selectedByRole = LinkedHashMap(overrides.selectedEndpointByRole)
        if (excluded) {
            excludedIds += safeId
            selectedByRole.entries.removeAll { it.value == safeId }
        } else {
            excludedIds.remove(safeId)
        }
        return writeEndpointOverrides(
            context,
            overrides.copy(
                excludedEndpoints = excludedIds,
                selectedEndpointByRole = selectedByRole,
            ),
        )
    }

    fun recordEndpointTestResult(
        context: Context,
        endpointId: String,
        status: Int,
        durationMillis: Long,
        sizeBytes: Int,
        mimeType: String,
        ok: Boolean,
    ): EndpointOverrides {
        val safeId = endpointId.trim()
        if (safeId.isBlank()) return readEndpointOverrides(context)
        val overrides = readEndpointOverrides(context)
        val results = LinkedHashMap(overrides.lastTestResults)
        results[safeId] = EndpointOverrideTestResult(
            status = status,
            durationMillis = durationMillis,
            sizeBytes = sizeBytes,
            mimeType = mimeType.trim(),
            ok = ok,
            recordedAt = Instant.now().toString(),
        )
        return writeEndpointOverrides(
            context,
            overrides.copy(lastTestResults = results),
        )
    }

    fun buildLiveWizardSnapshot(context: Context, maxRecent: Int = 10): LiveWizardSnapshot {
        RuntimeToolkitMissionWizard.ensureRegistryLoaded(context)
        val state = missionSessionState(context)
        val step = RuntimeToolkitMissionWizard.stepById(state.missionId, state.wizardStepId, context)
        val stepPhase = step?.phaseId?.trim().orEmpty()
        val activePhase = normalizePhaseId(activePhaseId(context)) ?: PHASE_BACKGROUND
        val targetPhase = when {
            stepPhase.isNotBlank() -> stepPhase
            activePhase != PHASE_BACKGROUND -> activePhase
            else -> ""
        }
        val file = eventFile(context)
        val responses = mutableListOf<LiveResponseEvent>()
        val requests = mutableListOf<LiveRequestEvent>()

        if (file.exists()) {
            runCatching {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    val eventType = root.optString("event_type")
                    val payload = root.optJSONObject("payload") ?: JSONObject()
                    if (!isEventWithinMission(state, root, payload)) return@forEachLine
                    when (eventType) {
                        "network_request_event" -> {
                            val parsed = parseLiveRequestEvent(root, payload, targetPhase)
                            if (parsed != null) requests += parsed
                        }
                        "network_response_event" -> {
                            val parsed = parseLiveResponseEvent(root, payload, targetPhase)
                            if (parsed != null) responses += parsed
                        }
                    }
                }
            }
        }

        val correlationSummary = responses
            .groupingBy { entry ->
                listOf(entry.phaseId, entry.routeKind, statusBucket(entry.statusCode), entry.hostClass).joinToString("|")
            }
            .eachCount()
            .map { (key, count) ->
                val parts = key.split("|")
                LiveCorrelationCount(
                    phaseId = parts.getOrNull(0).orEmpty(),
                    routeKind = parts.getOrNull(1).orEmpty(),
                    statusBucket = parts.getOrNull(2).orEmpty(),
                    hostClass = parts.getOrNull(3).orEmpty(),
                    count = count,
                )
            }

        val recentCorrelated = responses
            .takeLast(maxRecent)
            .map { response ->
                LiveCorrelationEntry(
                    statusCode = response.statusCode,
                    routeKind = response.routeKind,
                    operation = response.operation,
                    normalizedHost = response.normalizedHost,
                    normalizedPath = response.normalizedPath,
                    phaseId = response.phaseId,
                )
            }

        val aggregateById = linkedMapOf<String, LiveEndpointAggregate>()
        val responsesByRequestId = responses.groupBy { it.requestId }
        requests.forEach { request ->
            val role = inferLiveRole(request.phaseId, request.operation, request.normalizedPath)
            if (role == "helper") return@forEach
            val endpointId = "ep_${shortHash("$role|${request.method}|${request.normalizedHost}|${request.normalizedPath}")}".take(19)
            val aggregate = aggregateById.getOrPut(endpointId) {
                LiveEndpointAggregate(
                    endpointId = endpointId,
                    role = role,
                    method = request.method,
                    normalizedHost = request.normalizedHost,
                    normalizedPath = request.normalizedPath,
                    requestOperation = request.operation,
                    phaseId = request.phaseId,
                    sampleUrl = request.url,
                )
            }
            aggregate.internalSignals += inferLiveInternalSignalsForRequest(
                role = role,
                phaseId = request.phaseId,
                operation = request.operation,
                path = request.normalizedPath,
                url = request.url,
            )
            aggregate.topologyHints += inferLiveTopologyHints(
                operation = request.operation,
                path = request.normalizedPath,
            )
            aggregate.requestEvidenceCount += 1
            aggregate.queryParamNames += request.queryParamNames
            aggregate.bodyFieldNames += request.bodyFieldNames
            responsesByRequestId[request.requestId].orEmpty().forEach { response ->
                aggregate.responseEvidenceCount += 1
                if (response.statusCode in 200..399) aggregate.responseOkCount += 1
                if (response.hostClass.isNotBlank()) aggregate.hostClassSignals += response.hostClass
                if (response.mimeType.isNotBlank()) aggregate.responseMimeTypes += response.mimeType
                if (response.routeKind.isNotBlank()) aggregate.routeKinds += response.routeKind
                aggregate.internalSignals += inferLiveInternalSignalsFromResponse(response)
                aggregate.topologyHints += inferLiveTopologyHints(
                    operation = response.operation,
                    path = response.normalizedPath,
                )
            }
        }

        val candidateList = aggregateById.values.map { aggregate ->
            val evidence = aggregate.requestEvidenceCount + aggregate.responseEvidenceCount
            val confidence = when {
                evidence >= 6 -> 0.9
                evidence >= 3 -> 0.8
                evidence >= 1 -> 0.7
                else -> 0.5
            }
            val score = scoreLiveCandidate(aggregate, confidence, targetPhase)
            LiveEndpointCandidate(
                endpointId = aggregate.endpointId,
                role = aggregate.role,
                method = aggregate.method,
                normalizedHost = aggregate.normalizedHost,
                normalizedPath = aggregate.normalizedPath,
                requestOperation = aggregate.requestOperation,
                score = score,
                confidence = confidence,
                evidenceCount = evidence,
                phaseId = aggregate.phaseId,
                queryParamNames = aggregate.queryParamNames.toList().sorted(),
                bodyFieldNames = aggregate.bodyFieldNames.toList().sorted(),
                sampleUrl = aggregate.sampleUrl,
                internalSignals = aggregate.internalSignals.toList().sorted(),
                topologyHints = aggregate.topologyHints.toList().sorted(),
            )
        }.sortedWith(
            compareByDescending<LiveEndpointCandidate> { it.score }
                .thenByDescending { it.confidence }
                .thenBy { it.endpointId.lowercase(Locale.ROOT) },
        )
        val filteredCandidates = candidateList.filterNot { candidate ->
            val aggregate = LiveEndpointAggregate(
                endpointId = candidate.endpointId,
                role = candidate.role,
                method = candidate.method,
                normalizedHost = candidate.normalizedHost,
                normalizedPath = candidate.normalizedPath,
                requestOperation = candidate.requestOperation,
                phaseId = candidate.phaseId,
                sampleUrl = candidate.sampleUrl,
            )
            isLiveNoiseEndpoint(aggregate) ||
                isLiveRoleCrossContaminated(candidate.role, candidate.requestOperation, candidate.normalizedPath)
        }
        val finalCandidates = if (filteredCandidates.isNotEmpty()) filteredCandidates else candidateList

        val roleOrder = listOf("home", "search", "detail", "playbackResolver", "auth", "refresh", "config", "helper")
        val grouped = linkedMapOf<String, List<LiveEndpointCandidate>>()
        roleOrder.forEach { role ->
            val items = finalCandidates.filter { it.role == role }
            if (items.isNotEmpty()) grouped[role] = items
        }
        finalCandidates
            .map { it.role }
            .distinct()
            .filterNot { roleOrder.contains(it) }
            .forEach { role ->
                val items = finalCandidates.filter { it.role == role }
                if (items.isNotEmpty()) grouped[role] = items
            }

        return LiveWizardSnapshot(
            schemaVersion = 1,
            generatedAt = Instant.now().toString(),
            missionId = state.missionId,
            wizardStepId = state.wizardStepId,
            phaseId = targetPhase,
            correlationSummary = correlationSummary.sortedByDescending { it.count },
            recentCorrelated = recentCorrelated,
            endpointCandidates = grouped,
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
        ensureMissionDerivedArtifacts(
            context = context,
            missionId = safeMissionId,
            state = state,
        )
        val requiredSteps = RuntimeToolkitMissionWizard.requiredStepIds(safeMissionId, context)
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
        val missingRequiredSteps = inferMissingRequiredSteps(
            context = context,
            state = state,
            requiredSteps = requiredSteps,
            missingRequiredArtifacts = missingRequiredArtifacts,
            hasFinalizedExport = hasFinalizedExport,
        )
        val exportGateError = latestExportGateError(context)

        val exportReadiness: String
        val reason: String
        if (exportGateError.isNotBlank()) {
            exportReadiness = EXPORT_READINESS_BLOCKED
            reason = "export_gate_blocked"
        } else if (missingRequiredSteps.isNotEmpty()) {
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
        if (exportGateError.isNotBlank()) {
            warnings += "export_gate_blocked:$exportGateError"
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
        writeWarningsArtifact(runtimeRoot(context), summary)
        return summaryFile
    }

    private fun ensureMissionDerivedArtifacts(
        context: Context,
        missionId: String,
        state: MissionSessionState,
    ) {
        val root = runtimeRoot(context)
        if (!root.exists()) root.mkdirs()

        val eventsFile = File(root, "events/runtime_events.jsonl")
        if (eventsFile.exists() && eventsFile.length() > 0L) {
            runCatching {
                RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
                    runtimeRoot = root,
                    targetSiteHint = state.targetSiteId,
                )
            }.onSuccess {
                setLatestExportGateError(context, null)
            }.onFailure { throwable ->
                setLatestExportGateError(context, throwable.message ?: "source_pipeline_export_failed")
            }
        } else {
            setLatestExportGateError(context, null)
        }
        writeMissionQualityArtifacts(context, missionId, state)
    }

    private fun writeMissionQualityArtifacts(
        context: Context,
        missionId: String,
        state: MissionSessionState,
    ) {
        val root = runtimeRoot(context)
        if (!root.exists()) root.mkdirs()

        val requiredSteps = RuntimeToolkitMissionWizard.requiredStepIds(missionId, context)
        val requiredArtifacts = RuntimeToolkitMissionWizard.requiredArtifactsForMission(missionId, context)
        val missingRequiredArtifacts = requiredArtifacts
            .filter { artifact ->
                artifact.paths.none { relativePath ->
                    val artifactPath = File(root, relativePath)
                    artifactPath.exists() && artifactPath.length() > 0L
                }
            }
            .map { it.id }
        val hasFinalizedExport = File(root, "exports")
            .listFiles()
            ?.any { it.isFile && it.name.endsWith(".zip") && it.length() > 0L }
            ?: false
        val missingRequiredSteps = inferMissingRequiredSteps(
            context = context,
            state = state,
            requiredSteps = requiredSteps,
            missingRequiredArtifacts = missingRequiredArtifacts,
            hasFinalizedExport = hasFinalizedExport,
        )
        val saturatedSteps = requiredSteps.size - missingRequiredSteps.size
        val requiredStepCoverage = if (requiredSteps.isEmpty()) 1.0 else saturatedSteps.toDouble() / requiredSteps.size.toDouble()

        val requiredPhases = RuntimeToolkitMissionWizard.requiredProbeSet(missionId, context)
        val successfulTargetByPhase = collectResponseObservations(context)
            .filter { it.succeeded && it.targetEvidence }
            .groupingBy { inferProbePhase(it) }
            .eachCount()
        val satisfiedRequiredPhases = requiredPhases.count { phaseId ->
            (successfulTargetByPhase[phaseId] ?: 0) > 0
        }
        val requiredPhaseCoverage = if (requiredPhases.isEmpty()) {
            1.0
        } else {
            satisfiedRequiredPhases.toDouble() / requiredPhases.size.toDouble()
        }
        val targetResponseCount = successfulTargetByPhase.values.sum()
        val responseSignalCoverage = minOf(1.0, targetResponseCount.toDouble() / 10.0)
        val overallConfidence = ((requiredStepCoverage * 0.5) + (requiredPhaseCoverage * 0.3) + (responseSignalCoverage * 0.2))
            .coerceIn(0.0, 1.0)

        val qualityWarnings = mutableListOf<String>()
        if (requiredStepCoverage < 1.0) {
            qualityWarnings += "required_step_coverage_incomplete"
        }
        if (requiredPhaseCoverage < 1.0) {
            qualityWarnings += "required_probe_coverage_incomplete"
        }
        if (targetResponseCount == 0) {
            qualityWarnings += "target_response_evidence_missing"
        }

        val pipelineReady = requiredPhaseCoverage >= 1.0 && targetResponseCount > 0
        val generatedAt = Instant.now().toString()
        val pipelinePayload = JSONObject().apply {
            put("schema_version", 1)
            put("generated_at_utc", generatedAt)
            put("mission_id", missionId)
            put("target_site_id", state.targetSiteId.ifBlank { "unknown_target" })
            put("pipeline_ready", pipelineReady)
            put("required_step_coverage", requiredStepCoverage)
            put("required_probe_coverage", requiredPhaseCoverage)
            put("response_signal_coverage", responseSignalCoverage)
            put("target_response_count", targetResponseCount)
            put("warnings", JSONArray(qualityWarnings))
        }

        val confidencePayload = JSONObject().apply {
            put("schema_version", 1)
            put("generated_at_utc", generatedAt)
            put("mission_id", missionId)
            put("target_site_id", state.targetSiteId.ifBlank { "unknown_target" })
            put("confidence", JSONObject().apply {
                put("overall_confidence", overallConfidence)
                put("required_step_coverage", requiredStepCoverage)
                put("required_probe_coverage", requiredPhaseCoverage)
                put("response_signal_coverage", responseSignalCoverage)
            })
            put("warnings", JSONArray(qualityWarnings))
        }

        val warningsPayload = JSONObject().apply {
            put("schema_version", 1)
            put("generated_at_utc", generatedAt)
            put("mission_id", missionId)
            put("warnings", JSONArray(qualityWarnings))
        }

        val pipelineReadyReportFile = File(root, "pipeline_ready_report.json")
        val confidenceReportFile = File(root, "confidence_report.json")
        val warningsFile = File(root, "warnings.json")
        synchronized(ioLock) {
            pipelineReadyReportFile.writeText(pipelinePayload.toString(), Charsets.UTF_8)
            confidenceReportFile.writeText(confidencePayload.toString(), Charsets.UTF_8)
            if (!warningsFile.exists() || warningsFile.length() <= 0L) {
                warningsFile.writeText(warningsPayload.toString(), Charsets.UTF_8)
            }
        }
    }

    private fun writeWarningsArtifact(root: File, summary: MissionExportSummary) {
        val warningsFile = File(root, "warnings.json")
        val payload = JSONObject().apply {
            put("schema_version", 1)
            put("generated_at_utc", summary.generatedAt)
            put("mission_id", summary.missionId)
            put("export_readiness", summary.exportReadiness)
            put("reason", summary.reason)
            put("warnings", JSONArray(summary.warnings.distinct()))
        }
        synchronized(ioLock) {
            warningsFile.writeText(payload.toString(), Charsets.UTF_8)
        }
    }

    private fun inferMissingRequiredSteps(
        context: Context,
        state: MissionSessionState,
        requiredSteps: List<String>,
        missingRequiredArtifacts: List<String>,
        hasFinalizedExport: Boolean,
    ): List<String> {
        val missing = linkedSetOf<String>()
        requiredSteps.forEach { stepId ->
            if (stepId == RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT) {
                return@forEach
            }
            val saturated = isStepSaturatedByEvidence(
                context = context,
                state = state,
                stepId = stepId,
            )
            if (!saturated) {
                missing += stepId
            }
        }
        if (requiredSteps.contains(RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT)) {
            val finalReady = missing.isEmpty() && missingRequiredArtifacts.isEmpty() && hasFinalizedExport
            if (!finalReady) {
                missing += RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT
            }
        }
        return requiredSteps.filter { it in missing }
    }

    private fun isStepSaturatedByEvidence(
        context: Context,
        state: MissionSessionState,
        stepId: String,
    ): Boolean {
        if (state.stepStates[stepId] == RuntimeToolkitMissionWizard.SATURATION_SATURATED) {
            return true
        }
        if (stepId == RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT) {
            return false
        }
        return runCatching {
            evaluateMissionStepSaturation(context, stepId).state == RuntimeToolkitMissionWizard.SATURATION_SATURATED
        }.getOrDefault(false)
    }

    fun startMissionSession(
        context: Context,
        missionId: String,
    ): MissionSessionState {
        RuntimeToolkitMissionWizard.ensureRegistryLoaded(context)
        clearRuntimeArtifacts(context)
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
        setCaptureEnabled(context, false)
        clearWizardReadyWindow(context, reason = "mission_started")
        clearLatestReadyHit(context)
        setLatestExportGateError(context, null)
        writeMissionSession(context, updated)
        ensureEndpointOverrides(context)
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
        if (state.wizardStepId != stepId) {
            clearWizardReadyWindow(context, reason = "step_changed")
        }
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
        return runtimeSettings(context).getBoolean(SETTING_CAPTURE_ENABLED, false)
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

    fun isReadyActionStep(stepId: String): Boolean {
        return stepId in setOf(
            RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE,
            RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE,
            RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE,
            RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL,
        )
    }

    fun readyWindowState(context: Context, clearExpired: Boolean = true): ReadyWindowState {
        val prefs = runtimeSettings(context)
        val active = prefs.getBoolean(SETTING_WIZARD_ARMED_ACTIVE, false)
        val stepId = prefs.getString(SETTING_WIZARD_ARMED_STEP_ID, "").orEmpty()
        val actionId = prefs.getString(SETTING_WIZARD_ARMED_ACTION_ID, "").orEmpty()
        val traceId = prefs.getString(SETTING_WIZARD_ARMED_TRACE_ID, "").orEmpty()
        val startedAtMs = prefs.getLong(SETTING_WIZARD_ARMED_STARTED_AT_MS, 0L)
        val expiresAtMs = prefs.getLong(SETTING_WIZARD_ARMED_EXPIRES_AT_MS, 0L)
        val hitRecorded = prefs.getBoolean(SETTING_WIZARD_ARMED_HIT_RECORDED, false)
        val now = System.currentTimeMillis()
        val withinWindow = active && expiresAtMs > now
        if (active && !withinWindow && clearExpired) {
            clearWizardReadyWindow(context, reason = "expired")
            return ReadyWindowState(
                active = false,
                armedStepId = "",
                armedActionId = actionId,
                armedTraceId = traceId,
                armedStartedAt = if (startedAtMs > 0L) Instant.ofEpochMilli(startedAtMs).toString() else "",
                armedExpiresAt = if (expiresAtMs > 0L) Instant.ofEpochMilli(expiresAtMs).toString() else "",
                withinWindow = false,
                expiresAtEpochMillis = expiresAtMs,
                hitRecorded = hitRecorded,
            )
        }
        return ReadyWindowState(
            active = withinWindow,
            armedStepId = stepId,
            armedActionId = actionId,
            armedTraceId = traceId,
            armedStartedAt = if (startedAtMs > 0L) Instant.ofEpochMilli(startedAtMs).toString() else "",
            armedExpiresAt = if (expiresAtMs > 0L) Instant.ofEpochMilli(expiresAtMs).toString() else "",
            withinWindow = withinWindow,
            expiresAtEpochMillis = expiresAtMs,
            hitRecorded = hitRecorded,
        )
    }

    fun latestReadyHitState(context: Context, recentWindowMs: Long = 8_000L): ReadyHitState {
        val prefs = runtimeSettings(context)
        val stepId = prefs.getString(SETTING_WIZARD_READY_LAST_HIT_STEP_ID, "").orEmpty()
        val hitAtMs = prefs.getLong(SETTING_WIZARD_READY_LAST_HIT_AT_MS, 0L)
        if (stepId.isBlank() || hitAtMs <= 0L) {
            return ReadyHitState(
                stepId = "",
                hitAt = "",
                ageSeconds = 0,
                recent = false,
            )
        }
        val now = System.currentTimeMillis()
        val ageMs = (now - hitAtMs).coerceAtLeast(0L)
        return ReadyHitState(
            stepId = stepId,
            hitAt = Instant.ofEpochMilli(hitAtMs).toString(),
            ageSeconds = (ageMs / 1000L).toInt(),
            recent = ageMs <= recentWindowMs.coerceAtLeast(1_000L),
        )
    }

    private fun setLatestReadyHit(context: Context, stepId: String, hitAtMs: Long) {
        runtimeSettings(context).edit()
            .putString(SETTING_WIZARD_READY_LAST_HIT_STEP_ID, stepId)
            .putLong(SETTING_WIZARD_READY_LAST_HIT_AT_MS, hitAtMs)
            .apply()
    }

    private fun clearLatestReadyHit(context: Context) {
        runtimeSettings(context).edit()
            .remove(SETTING_WIZARD_READY_LAST_HIT_STEP_ID)
            .remove(SETTING_WIZARD_READY_LAST_HIT_AT_MS)
            .apply()
    }

    private fun latestExportGateError(context: Context): String {
        return runtimeSettings(context).getString(SETTING_EXPORT_GATE_ERROR, "").orEmpty().trim()
    }

    private fun setLatestExportGateError(context: Context, errorMessage: String?) {
        val normalized = errorMessage.orEmpty().trim().replace(Regex("\\s+"), " ").take(600)
        runtimeSettings(context).edit().apply {
            if (normalized.isBlank()) {
                remove(SETTING_EXPORT_GATE_ERROR)
            } else {
                putString(SETTING_EXPORT_GATE_ERROR, normalized)
            }
        }.apply()
    }

    fun beginWizardReadyWindow(
        context: Context,
        stepId: String,
        source: String = "wizard_overlay",
        durationMs: Long = WIZARD_READY_WINDOW_MS,
    ): ReadyWindowState {
        if (!isReadyActionStep(stepId)) {
            return readyWindowState(context)
        }
        val existing = readyWindowState(context, clearExpired = false)
        if (existing.active || existing.armedActionId.isNotBlank()) {
            clearWizardReadyWindow(context, reason = "rearmed")
        }
        val correlation = beginUiAction(
            context = context,
            actionName = "wizard_ready_$stepId",
            screenId = "browser",
            payload = mapOf("source" to source, "wizard_step_id" to stepId),
        )
        val now = System.currentTimeMillis()
        val expiresAt = now + durationMs.coerceAtLeast(1_000L)
        runtimeSettings(context).edit()
            .putBoolean(SETTING_WIZARD_ARMED_ACTIVE, true)
            .putString(SETTING_WIZARD_ARMED_STEP_ID, stepId)
            .putString(SETTING_WIZARD_ARMED_ACTION_ID, correlation.actionId)
            .putString(SETTING_WIZARD_ARMED_TRACE_ID, correlation.traceId)
            .putLong(SETTING_WIZARD_ARMED_STARTED_AT_MS, now)
            .putLong(SETTING_WIZARD_ARMED_EXPIRES_AT_MS, expiresAt)
            .putBoolean(SETTING_WIZARD_ARMED_HIT_RECORDED, false)
            .apply()
        val session = missionSessionState(context)
        logWizardEvent(
            context = context,
            operation = "wizard_ready_window_started",
            missionId = session.missionId.ifBlank { RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE },
            wizardStepId = stepId,
            saturationState = session.stepStates[stepId] ?: RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
            phaseId = activePhaseId(context),
            targetSiteId = session.targetSiteId,
            payload = mapOf(
                "source" to source,
                "armed_action_id" to correlation.actionId,
                "armed_trace_id" to correlation.traceId,
                "armed_started_at" to Instant.ofEpochMilli(now).toString(),
                "armed_expires_at" to Instant.ofEpochMilli(expiresAt).toString(),
                "window_ms" to durationMs,
            ),
        )
        return readyWindowState(context, clearExpired = false)
    }

    fun clearWizardReadyWindow(context: Context, reason: String = "cleared") {
        val previous = readyWindowState(context, clearExpired = false)
        val current = currentCorrelationContext(context)
        if (previous.armedActionId.isNotBlank()) {
            finishUiAction(
                context = context,
                correlation = CorrelationContext(
                    traceId = previous.armedTraceId.ifBlank { UUID.randomUUID().toString() },
                    actionId = previous.armedActionId,
                    spanId = UUID.randomUUID().toString(),
                ),
                actionName = "wizard_ready_${previous.armedStepId.ifBlank { "step" }}",
                result = when {
                    reason == "hit" -> "hit"
                    reason == "expired" -> "expired"
                    else -> "cleared"
                },
                payload = mapOf(
                    "wizard_step_id" to previous.armedStepId,
                    "reason" to reason,
                ),
            )
            setCorrelationContext(context, current)
        }
        runtimeSettings(context).edit()
            .putBoolean(SETTING_WIZARD_ARMED_ACTIVE, false)
            .remove(SETTING_WIZARD_ARMED_STEP_ID)
            .remove(SETTING_WIZARD_ARMED_ACTION_ID)
            .remove(SETTING_WIZARD_ARMED_TRACE_ID)
            .remove(SETTING_WIZARD_ARMED_STARTED_AT_MS)
            .remove(SETTING_WIZARD_ARMED_EXPIRES_AT_MS)
            .remove(SETTING_WIZARD_ARMED_HIT_RECORDED)
            .apply()
    }

    fun evaluateMissionStepFeedback(
        context: Context,
        stepId: String = missionSessionState(context).wizardStepId,
    ): StepFeedback {
        val saturation = evaluateMissionStepSaturation(context, stepId)
        val state = missionSessionState(context)
        val progress = inferStepProgressPercent(stepId = stepId, saturation = saturation)
        val missingSignals = inferStepMissingSignals(stepId = stepId, saturation = saturation)
        val hints = inferStepUserHints(context = context, stepId = stepId, saturation = saturation, state = state)
        return StepFeedback(
            state = saturation.state,
            progressPercent = progress,
            missingSignals = missingSignals,
            userHints = hints,
            reason = saturation.reason,
            metrics = saturation.metrics,
        )
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
        clearWizardReadyWindow(context, reason = "runtime_artifacts_cleared")
        clearLatestReadyHit(context)
        setLatestExportGateError(context, null)
        setCaptureEnabled(context, false)
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
        val eventsFile = File(root, "events/runtime_events.jsonl")
        if (eventsFile.exists() && eventsFile.length() > 0L) {
            val targetHint = missionSessionState(context).targetSiteId
            runCatching {
                RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
                    runtimeRoot = root,
                    targetSiteHint = targetHint,
                )
            }.getOrElse { throwable ->
                throw IllegalStateException(
                    "Failed to derive source pipeline artifacts: ${throwable.message ?: "unknown"}",
                    throwable,
                )
            }
        }
        writeMissionExportSummary(context)

        val exportCandidates = if (root.exists()) {
            root.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    val relativePath = root.toPath().relativize(file.toPath()).toString()
                        .replace(File.separatorChar, '/')
                    shouldIncludeInRuntimeExport(relativePath)
                }
                .sortedBy { file -> root.toPath().relativize(file.toPath()).toString() }
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

    internal fun shouldIncludeInRuntimeExport(relativePath: String): Boolean {
        val normalized = relativePath.trim().replace('\\', '/')
        if (normalized.isBlank()) return false
        return normalized in MINIMAL_RUNTIME_EXPORT_FILES
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
        val routeKind: String,
        val targetSiteId: String,
        val statusCode: Int,
        val operation: String,
        val fingerprint: String,
        val mimeType: String,
        val path: String,
    ) {
        val succeeded: Boolean
            get() = statusCode in 200..399
        val targetEvidence: Boolean
            get() {
                val routeEvidence = routeKind in setOf("home", "search", "detail", "playback", "auth", "category", "live")
                val siteEvidence = targetSiteId.isNotBlank() && targetSiteId !in setOf("unknown_target", "unknown")
                return hostClass.startsWith("target_") || routeEvidence || siteEvidence
        }
    }

    private data class LiveRequestEvent(
        val requestId: String,
        val phaseId: String,
        val method: String,
        val url: String,
        val normalizedHost: String,
        val normalizedPath: String,
        val operation: String,
        val queryParamNames: Set<String>,
        val bodyFieldNames: Set<String>,
    )

    private data class LiveResponseEvent(
        val requestId: String,
        val phaseId: String,
        val statusCode: Int,
        val routeKind: String,
        val operation: String,
        val normalizedHost: String,
        val normalizedPath: String,
        val hostClass: String,
        val mimeType: String,
        val bodyPreview: String,
    )

    private data class LiveEndpointAggregate(
        val endpointId: String,
        val role: String,
        val method: String,
        val normalizedHost: String,
        val normalizedPath: String,
        val requestOperation: String,
        val phaseId: String,
        val sampleUrl: String,
        var requestEvidenceCount: Int = 0,
        var responseEvidenceCount: Int = 0,
        var responseOkCount: Int = 0,
        val hostClassSignals: MutableSet<String> = linkedSetOf(),
        val responseMimeTypes: MutableSet<String> = linkedSetOf(),
        val routeKinds: MutableSet<String> = linkedSetOf(),
        val queryParamNames: MutableSet<String> = linkedSetOf(),
        val bodyFieldNames: MutableSet<String> = linkedSetOf(),
        val internalSignals: MutableSet<String> = linkedSetOf(),
        val topologyHints: MutableSet<String> = linkedSetOf(),
    )

    private fun parseInstant(value: String): Instant? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return runCatching { Instant.parse(trimmed) }.getOrNull()
    }

    private fun isEventWithinMission(
        state: MissionSessionState,
        root: JSONObject,
        payload: JSONObject,
    ): Boolean {
        val missionId = payload.optString("mission_id").trim()
        if (missionId.isNotBlank() && state.missionId.isNotBlank() && missionId != state.missionId) {
            return false
        }
        val startedAt = parseInstant(state.startedAt) ?: return true
        val eventTime = parseInstant(root.optString("ts_utc")) ?: return true
        return !eventTime.isBefore(startedAt)
    }

    private fun collectResponseObservations(context: Context): List<ResponseObservation> {
        val file = eventFile(context)
        if (!file.exists()) return emptyList()
        val session = missionSessionState(context)
        val observations = mutableListOf<ResponseObservation>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (root.optString("event_type") != "network_response_event") return@forEachLine
                val payload = root.optJSONObject("payload") ?: JSONObject()
                if (!isEventWithinMission(session, root, payload)) return@forEachLine
                val phaseId = normalizePhaseId(payload.optString("phase_id")) ?: PHASE_BACKGROUND
                val hostClass = payload.optString("host_class").ifBlank { "ignored" }
                val routeKind = payload.optString("route_kind").trim().lowercase(Locale.ROOT)
                val targetSiteId = payload.optString("target_site_id")
                    .trim()
                    .lowercase(Locale.ROOT)
                    .replace('.', '_')
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
                    routeKind = routeKind,
                    targetSiteId = targetSiteId,
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

    private fun inferProbePhase(observation: ResponseObservation): String {
        if (observation.phaseId != PHASE_BACKGROUND) return observation.phaseId
        val operation = observation.operation
        val path = observation.path
        val mime = observation.mimeType
        val context = "$operation $path"
        return when {
            observation.routeKind == "home" || path == "/" || hasHomeHint(context) -> "home_probe"
            observation.routeKind == "search" || hasSearchHint(context) -> "search_probe"
            observation.routeKind == "detail" || hasDetailHint(context) -> "detail_probe"
            isPlaybackSignal(
                routeKind = observation.routeKind,
                operation = operation,
                path = path,
                mimeType = mime,
            ) -> "playback_probe"
            observation.routeKind == "auth" ||
                hasAuthHint(context) -> "auth_probe"
            else -> PHASE_BACKGROUND
        }
    }

    private fun defaultEndpointOverrides(): EndpointOverrides {
        return EndpointOverrides(
            schemaVersion = ENDPOINT_OVERRIDE_SCHEMA_VERSION,
            selectedEndpointByRole = linkedMapOf(),
            excludedEndpoints = linkedSetOf(),
            lastTestResults = linkedMapOf(),
        )
    }

    private fun statusBucket(statusCode: Int): String {
        return when (statusCode) {
            in 200..299 -> "2xx"
            in 300..399 -> "3xx"
            in 400..499 -> "4xx"
            in 500..599 -> "5xx"
            0 -> "unknown"
            else -> "${statusCode / 100}xx"
        }
    }

    private val liveEntrySurfacePaths = setOf(
        "/",
        "/suche",
        "/suchergebnisse",
        "/startseite",
        "/mediathek",
        "/kategorien",
        "/serien",
        "/filme",
        "/dokus",
        "/reportagen",
        "/nachrichten",
        "/sport",
        "/wissen",
        "/kinder",
        "/live-tv",
        "/mein-zdf",
    )

    private val liveBrowseRootSegments = setOf(
        "suche",
        "suchergebnisse",
        "startseite",
        "mediathek",
        "kategorien",
        "kategorie",
        "genre",
        "genres",
        "serien",
        "serie",
        "film",
        "dokus",
        "filme",
        "reportagen",
        "reportage",
        "nachrichten",
        "sport",
        "wissen",
        "zdfheute",
        "sportstudio",
        "spielfilm",
        "fernsehfilm",
        "einzeldokus",
        "kurzdoku",
        "kinder",
        "programm",
        "live-tv",
        "livetv",
        "themen",
        "thema",
        "rubriken",
        "rubrik",
        "mein-zdf",
    )

    private val searchHintTokens = listOf(
        "/search",
        "/suche",
        "/suchergebnis",
        "/suchergebnisse",
        "/such-resultate",
        "/suchtreffer",
        "/mediathek/suche",
        "search=",
        "suche=",
        "suchbegriff=",
        "suchwort=",
        "suchtext=",
        "suchphrase=",
        "suchanfrage=",
        "searchterm=",
        "suchterm=",
        "query=",
        "q=",
        "suggest",
        "autocomplete",
        "searchrecommendation",
        "getsearchresults",
        "searchresults",
        "suchergebnisse",
        "suchtreffer",
        "trefferliste",
        "ergebnisliste",
        "volltextsuche",
        "suchfeld",
        "sucheingabe",
        "suchanfrage",
        "suchvorschlag",
        "vorschlag",
        "suchhistorie",
    )

    private val detailHintTokens = listOf(
        "/detail",
        "/content/",
        "/video/",
        "/episode/",
        "/episodes/",
        "/folge/",
        "/folgen/",
        "/staffel/",
        "/staffeln/",
        "/sendung/",
        "/sendungen/",
        "/beitrag/",
        "/beiträge/",
        "/reportage",
        "/doku",
        "/dokus/",
        "/dokumentation/",
        "/dokumentationen/",
        "/kurzdoku/",
        "/einzeldokus/",
        "/film",
        "/filme/",
        "/spielfilm/",
        "/fernsehfilm/",
        "/serie/",
        "/serien/",
        "/sendereihe/",
        "/sendereihen/",
        "/thema/",
        "/themen/",
        "detail",
        "detailseite",
        "detailansicht",
        "beitragsdetail",
        "sendungsdetail",
        "episodendetail",
        "videodetail",
        "episode",
        "canonical",
        "canonicalid",
        "video",
        "content",
        "media_by_canonical",
        "mediadetail",
        "contentdetail",
        "getvideo",
        "sendung",
        "folge",
        "folgen",
        "staffel",
        "staffeln",
        "serie",
        "serien",
        "beitrag",
        "doku",
        "dokus",
        "dokumentation",
        "kurzdoku",
        "einzeldokus",
        "reportage",
        "reportagen",
        "spielfilm",
        "fernsehfilm",
        "film",
        "filme",
    )

    private val homeHintTokens = listOf(
        "/home",
        "/start",
        "/startseite",
        "/mediathek",
        "/entdecken",
        "/stöbern",
        "/für-dich",
        "/neu-in-der-mediathek",
        "home",
        "start",
        "startseite",
        "entdecken",
        "stöbern",
        "für dich",
        "weiterschauen",
        "neu in der mediathek",
        "top serien",
        "top dokus",
        "hero",
        "highlight",
        "highlights",
        "cluster",
        "rail",
        "row",
        "recommend",
        "collection",
        "teaser",
        "empfehl",
    )

    private val categoryHintTokens = listOf(
        "/kategorie",
        "/kategorien",
        "/category",
        "/categories",
        "/rubrik",
        "/rubriken",
        "/genre",
        "/thema",
        "/themen",
        "/serien",
        "/dokus",
        "/kinder",
        "/filme",
        "/reportagen",
        "/nachrichten",
        "/sport",
        "/wissen",
        "/zdfheute",
        "/sportstudio",
        "/krimi",
        "/thriller",
        "/drama",
        "/komoedie",
        "/komödie",
        "/romance",
        "/romanze",
        "/action",
        "category",
        "categories",
        "kategorie",
        "kategorien",
        "kategorienseite",
        "rubrik",
        "rubriken",
        "rubrikenseite",
        "genre",
        "topic",
        "thema",
        "themen",
        "themenwelt",
        "sammlung",
        "sammlungen",
        "facet",
        "facets",
        "alle-inhalte",
        "alleinhalte",
        "krimi",
        "thriller",
        "drama",
        "komödie",
        "komoedie",
        "romance",
        "romanze",
        "action",
        "horror",
        "fantasy",
        "mystery",
        "science fiction",
        "science-fiction",
        "sciencefiction",
        "sci-fi",
        "dokumentation",
        "spielfilm",
        "fernsehfilm",
        "sportstudio",
        "zdfheute",
        "erzgebirgskrimi",
        "taunuskrimi",
        "familie",
        "familienfilm",
        "kinderfilm",
    )

    private val genreHintTokens = listOf(
        "krimi",
        "thriller",
        "drama",
        "komödie",
        "komoedie",
        "komodie",
        "romance",
        "romanze",
        "action",
        "horror",
        "fantasy",
        "mystery",
        "science fiction",
        "science-fiction",
        "sciencefiction",
        "sci-fi",
        "sci fi",
        "doku",
        "dokus",
        "dokumentation",
        "reportage",
        "reportagen",
        "spielfilm",
        "fernsehfilm",
        "familie",
        "familienfilm",
        "kinderfilm",
        "kinder",
        "nachrichten",
        "sport",
        "wissen",
        "sportstudio",
        "zdfheute",
        "erzgebirgskrimi",
        "taunuskrimi",
    )

    private val mediaTypeHintTokens = listOf(
        "serie",
        "serien",
        "staffel",
        "staffeln",
        "folge",
        "folgen",
        "episode",
        "episoden",
        "show",
        "film",
        "filme",
        "movie",
        "spielfilm",
        "fernsehfilm",
        "doku",
        "dokus",
        "dokumentation",
        "reportage",
        "reportagen",
        "kurzdoku",
        "einzeldokus",
        "live",
        "livestream",
        "clip",
        "trailer",
    )

    private val liveHintTokens = listOf(
        "/live",
        "/live-tv",
        "/livetv",
        "/livestream",
        "/programm",
        "/tv-programm",
        "/sendung-verpasst",
        "/verpasst",
        "/epg",
        "onair",
        "jetzt live",
        "jetzt-live",
        "sendungverpasst",
        "programmübersicht",
        "live_catalog",
        "activelive",
        "epg",
        "livetv",
    )

    private val authHintTokens = listOf(
        "/auth",
        "/oauth",
        "/identity",
        "/login",
        "/signin",
        "/logout",
        "/mein-zdf",
        "/meinzdf",
        "/token",
        "/session",
        "/userinfo",
        "/userdetails",
        "/fsk",
        "/pin",
        "auth",
        "token",
        "login",
        "logout",
        "signin",
        "authorize",
        "oidc",
        "openid",
        "refresh",
        "identity",
        "session",
        "anmelden",
        "anmeldung",
        "abmelden",
        "abmeldung",
        "einloggen",
        "ausloggen",
        "konto",
        "benutzerkonto",
        "kontoverwaltung",
        "profil",
        "benutzer",
        "passwort",
        "kennwort",
        "mein-zdf",
        "meinzdf",
        "registrieren",
        "registrierung",
        "jugendschutz",
        "altersnachweis",
        "altersfreigabe",
        "personalisierung",
    )

    private val loginHintTokens = listOf(
        "login",
        "signin",
        "authorize",
        "anmelden",
        "anmeldung",
        "einloggen",
        "registrieren",
        "registrierung",
        "/login",
        "/signin",
        "/authorize",
        "/anmelden",
    )

    private val validationHintTokens = listOf(
        "validate",
        "validation",
        "userinfo",
        "profile",
        "session",
        "status",
        "sessionstatus",
        "authstatus",
        "kontostatus",
        "angemeldet",
        "eingeloggt",
        "whoami",
        "werbinich",
        "konto",
        "profil",
        "benutzer",
        "/userinfo",
        "/profile",
        "/profil",
        "/konto",
        "/me",
    )

    private val refreshHintTokens = listOf(
        "refresh",
        "token_refresh",
        "session_refresh",
        "sessionrefresh",
        "session_renew",
        "token_renew",
        "keepalive",
        "renew",
        "renewal",
        "revalidate",
        "erneuern",
        "aktualisieren",
        "verlängern",
        "/refresh",
        "/session/refresh",
        "/token/erneuern",
        "/token/refresh",
    )

    private fun hasSearchHint(text: String): Boolean = containsAnyToken(text, searchHintTokens)

    private fun hasDetailHint(text: String): Boolean = containsAnyToken(text, detailHintTokens)

    private fun hasHomeHint(text: String): Boolean = containsAnyToken(text, homeHintTokens)

    private fun hasCategoryHint(text: String): Boolean = containsAnyToken(text, categoryHintTokens)

    private fun hasLiveHint(text: String): Boolean = containsAnyToken(text, liveHintTokens)

    private fun hasGenreHint(text: String): Boolean = containsAnyToken(text, genreHintTokens)

    private fun hasMediaTypeHint(text: String): Boolean = containsAnyToken(text, mediaTypeHintTokens)

    private fun hasLiveOrCategoryHint(text: String): Boolean = hasLiveHint(text) || hasCategoryHint(text) || hasGenreHint(text)

    private fun hasAuthHint(text: String): Boolean = containsAnyToken(text, authHintTokens)

    private fun hasLoginHint(text: String): Boolean = containsAnyToken(text, loginHintTokens)

    private fun hasValidationHint(text: String): Boolean = containsAnyToken(text, validationHintTokens)

    private fun hasRefreshHint(text: String): Boolean = containsAnyToken(text, refreshHintTokens)

    private fun normalizeLiveRoutePath(path: String): String {
        val trimmed = path.trim().ifBlank { "/" }
        if (trimmed == "/") return "/"
        return "/" + trimmed.trim('/').lowercase(Locale.ROOT)
    }

    private fun liveRouteSegments(path: String): List<String> {
        return normalizeLiveRoutePath(path)
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
    }

    private fun isLiveCollectionBrowsePath(path: String): Boolean {
        val normalized = normalizeLiveRoutePath(path)
        if (normalized in liveEntrySurfacePaths) return true
        val segments = liveRouteSegments(path)
        if (segments.isEmpty()) return normalized == "/"
        val first = segments.first()
        if (first in liveBrowseRootSegments && segments.size == 1) return true
        if (first in liveBrowseRootSegments && segments.size == 2 && segments.last() in setOf("alle", "neu", "top", "beliebt", "aktuell", "empfohlen", "meistgesehen")) return true
        if (first in setOf("genre", "genres", "kategorie", "kategorien", "rubrik", "rubriken", "thema", "themen", "serien", "filme", "dokus", "reportagen", "nachrichten", "sport", "wissen") && segments.size == 2) return true
        return false
    }

    private fun looksLikeLiveSingleItemPath(path: String): Boolean {
        val segments = liveRouteSegments(path)
        if (segments.size < 2) return false
        if (isLiveCollectionBrowsePath(path)) return false
        val last = segments.last()
        if (last in setOf("index", "overview", "alle", "top", "neu", "aktuell", "empfohlen")) return false
        if (Regex(".*-\\d{2,}$").matches(last)) return true
        val contentPrefix = segments.dropLast(1).lastOrNull().orEmpty()
        if (contentPrefix in setOf("reportagen", "reportage", "doku", "dokus", "dokumentation", "dokumentationen", "einzeldokus", "kurzdoku", "filme", "film", "spielfilm", "fernsehfilm", "serien", "serie", "sendung", "sendungen", "folge", "folgen", "staffel", "staffeln", "beitrag", "beitraege", "beiträge", "video", "videos", "episode", "episoden")) {
            return last.length >= 8 && last.any { it.isDigit() }
        }
        return false
    }

    private fun hasLiveCollectionPayloadHints(body: String): Boolean {
        if (body.isBlank()) return false
        return body.contains("\"rows\"") ||
            body.contains("\"rails\"") ||
            body.contains("\"clusters\"") ||
            body.contains("\"teasers\"") ||
            body.contains("\"results\"") ||
            body.contains("\"items\":[{") ||
            body.contains("\"edges\":[{")
    }

    private fun hasLiveSingleItemPayloadHints(body: String): Boolean {
        if (body.isBlank()) return false
        val hasIdentity =
            body.contains("\"canonical\"") ||
                body.contains("\"canonicalid\"") ||
                body.contains("\"contentid\"") ||
                body.contains("\"videoid\"")
        val hasTitle = body.contains("\"title\"") || body.contains("\"headline\"")
        val looksCollection = hasLiveCollectionPayloadHints(body)
        return hasIdentity && hasTitle && !looksCollection
    }

    private fun isLiveSearchSignal(operation: String, path: String): Boolean {
        return hasSearchHint("${operation.lowercase(Locale.ROOT)} ${path.lowercase(Locale.ROOT)}")
    }

    private fun isLiveDetailSignal(operation: String, path: String): Boolean {
        val loweredPath = path.lowercase(Locale.ROOT)
        if (isLiveCollectionBrowsePath(loweredPath)) return false
        return hasDetailHint("${operation.lowercase(Locale.ROOT)} $loweredPath") || hasMediaTypeHint("${operation.lowercase(Locale.ROOT)} $loweredPath") || looksLikeLiveSingleItemPath(loweredPath)
    }

    private fun isLiveHomeSignal(operation: String, path: String): Boolean {
        val loweredPath = path.lowercase(Locale.ROOT)
        return loweredPath == "/" ||
            hasHomeHint("${operation.lowercase(Locale.ROOT)} $loweredPath") ||
            loweredPath.contains("/kategorien")
    }

    private fun isLiveOrCategorySignal(operation: String, path: String): Boolean {
        return hasLiveOrCategoryHint("${operation.lowercase(Locale.ROOT)} ${path.lowercase(Locale.ROOT)}")
    }

    private fun isLiveTrackingSignal(operation: String, path: String): Boolean {
        val context = "$operation $path".lowercase(Locale.ROOT)
        return context.contains("tracking") ||
            context.contains("telemetry") ||
            context.contains("analytics") ||
            context.contains("measurement") ||
            context.contains("tracksrv") ||
            context.contains("nmrodam") ||
            context.contains("beacon") ||
            context.contains("pixel") ||
            context.contains("/event")
    }

    private fun inferLiveInternalSignalsForRequest(
        role: String,
        phaseId: String,
        operation: String,
        path: String,
        url: String,
    ): Set<String> {
        val signals = linkedSetOf<String>()
        val op = operation.lowercase(Locale.ROOT)
        val normalizedPath = path.lowercase(Locale.ROOT)
        val context = "$op $normalizedPath $url".lowercase(Locale.ROOT)

        val entrySurface = normalizedPath in liveEntrySurfacePaths
        val collectionSignal =
            containsAnyToken(op, listOf("collection", "rail", "row", "reihe", "reihen", "cluster", "teaser", "facet", "filter", "tab", "grid", "kachel", "tile", "catalog", "kategorie", "rubrik", "thema", "genre", "serien", "serie", "filme", "film", "dokus", "doku", "reportagen")) ||
                isLiveCollectionBrowsePath(normalizedPath)
        val playbackSignal =
            context.contains("/ptmd/") ||
                context.contains("/tmd/") ||
                context.contains("resolver") ||
                context.contains("manifest") ||
                normalizedPath.endsWith(".m3u8") ||
                normalizedPath.endsWith(".mpd")
        val accountSignal =
            role in setOf("auth", "refresh", "config", "helper") ||
                hasAuthHint(context)

        if (entrySurface) signals += "entry_surface"
        if (collectionSignal && !playbackSignal) signals += "collection_feed"
        if ((isLiveSearchSignal(op, normalizedPath) || role == "search") && !playbackSignal) signals += "search_results"
        if (isLiveDetailSignal(op, normalizedPath) && !collectionSignal && !playbackSignal) signals += "item_detail"
        if (playbackSignal) signals += "playback_resolution"
        if (accountSignal) signals += "account_or_policy"
        if ((collectionSignal || isLiveSearchSignal(op, normalizedPath) || entrySurface) && !playbackSignal && !accountSignal) {
            signals += "item_summary"
        }

        val normalizedPhase = normalizePhaseId(phaseId) ?: PHASE_BACKGROUND
        if (signals.isEmpty()) {
            when (normalizedPhase) {
                "home_probe" -> {
                    val homeLikeRoute = isLiveCollectionBrowsePath(normalizedPath)
                    val homeLikeOperation =
                        hasHomeHint(op) ||
                            hasCategoryHint(op) ||
                            hasGenreHint(op) ||
                            containsAnyToken(op, listOf("collection", "rail", "row", "reihe", "reihen", "cluster", "teaser", "catalog"))
                    if (homeLikeRoute) signals += "entry_surface"
                    if (homeLikeRoute || homeLikeOperation) signals += "collection_feed"
                }
                "search_probe" -> {
                    signals += "search_results"
                    signals += "item_summary"
                }
                "detail_probe" -> signals += "item_detail"
                "playback_probe" -> signals += "playback_resolution"
                "auth_probe" -> signals += "account_or_policy"
            }
        }

        return signals
    }

    private fun inferLiveInternalSignalsFromResponse(response: LiveResponseEvent): Set<String> {
        val signals = linkedSetOf<String>()
        val op = response.operation.lowercase(Locale.ROOT)
        val path = response.normalizedPath.lowercase(Locale.ROOT)
        val route = response.routeKind.lowercase(Locale.ROOT)
        val body = response.bodyPreview.lowercase(Locale.ROOT)
        val collectionPayload = hasLiveCollectionPayloadHints(body)
        val singleItemPayload = hasLiveSingleItemPayloadHints(body)
        if (path in liveEntrySurfacePaths) {
            signals += "entry_surface"
        }
        if (route.contains("home") || route.contains("category") || hasCategoryHint("$op $path") || hasGenreHint("$op $path") || op.contains("collection") || op.contains("cluster")) {
            signals += "collection_feed"
        }
        if (collectionPayload) {
            signals += "collection_feed"
        }
        if (route.contains("search") || hasSearchHint("$op $path")) {
            signals += "search_results"
        }
        if (
            body.contains("\"title\"") &&
            (body.contains("\"canonical") || body.contains("\"teaser") || body.contains("\"image")) &&
            ("collection_feed" in signals || "search_results" in signals || collectionPayload)
        ) {
            signals += "item_summary"
        }
        if (
            (route.contains("detail") || hasDetailHint("$op $path") || op.contains("canonical") || singleItemPayload || looksLikeLiveSingleItemPath(path)) &&
            !isLiveCollectionBrowsePath(path) &&
            "collection_feed" !in signals &&
            !collectionPayload
        ) {
            signals += "item_detail"
        }
        if (path.contains("/ptmd/") || path.contains("/tmd/") || op.contains("resolver") || path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            signals += "playback_resolution"
        }
        if (route.contains("auth") || hasAuthHint("$op $path")) {
            signals += "account_or_policy"
        }
        return signals
    }

    private fun inferLiveTopologyHints(operation: String, path: String): Set<String> {
        val hints = linkedSetOf<String>()
        val op = operation.lowercase(Locale.ROOT)
        val normalizedPath = path.lowercase(Locale.ROOT)
        if (containsAnyToken(op, listOf("row", "rail", "cluster", "reihe", "reihen"))) hints += "row_or_rail"
        if (op.contains("tab") || normalizedPath.contains("/tabs")) hints += "tabbed_collection"
        if (containsAnyToken(op, listOf("facet", "filter", "facette"))) hints += "faceted_collection"
        if (op.contains("grid") || normalizedPath.contains("/grid")) hints += "grid_collection"
        if (hasCategoryHint("$op $normalizedPath") || hasGenreHint("$op $normalizedPath") || normalizedPath.contains("/serien") || normalizedPath.contains("/dokus") || normalizedPath.contains("/kinder") || normalizedPath.contains("/filme")) {
            hints += "category_collection"
        }
        if (normalizedPath in liveEntrySurfacePaths) {
            hints += "entry_surface_route"
        }
        return hints
    }

    private fun isLiveRoleCrossContaminated(role: String, operation: String, path: String): Boolean {
        val normalizedRole = role.trim()
        val op = operation.lowercase(Locale.ROOT)
        val loweredPath = path.lowercase(Locale.ROOT)
        val context = "$op $loweredPath"
        return when (normalizedRole) {
            "home" -> {
                isLiveTrackingSignal(op, loweredPath) ||
                    context.contains("/ptmd/") ||
                    context.contains("/tmd/") ||
                    context.contains("manifest") ||
                    (isLiveDetailSignal(op, loweredPath) && !isLiveCollectionBrowsePath(loweredPath)) ||
                    (isLiveSearchSignal(op, loweredPath) && !loweredPath.contains("/suche"))
            }
            "search" -> {
                isLiveTrackingSignal(op, loweredPath) ||
                    op.contains("live_catalog") ||
                    context.contains("/ptmd/") ||
                    context.contains("/tmd/") ||
                    isLiveDetailSignal(op, loweredPath) ||
                    (isLiveOrCategorySignal(op, loweredPath) && !loweredPath.contains("/suche"))
            }
            "detail" -> isLiveSearchSignal(op, loweredPath) || isLiveOrCategorySignal(op, loweredPath) || isLiveCollectionBrowsePath(loweredPath) || context.contains("playback_manifest") || context.contains("/ptmd/") || context.contains("/tmd/") || isLiveTrackingSignal(op, loweredPath)
            "playbackResolver" -> isLiveTrackingSignal(op, loweredPath) || isLiveCollectionBrowsePath(loweredPath) || context.contains("playback_history") || context.contains("usage-data")
            else -> false
        }
    }

    private fun strongCandidateThreshold(role: String): Double {
        return when (role) {
            "home" -> 120.0
            "search" -> 126.0
            "detail" -> 124.0
            "playbackResolver" -> 128.0
            "auth", "refresh" -> 118.0
            else -> 120.0
        }
    }

    private fun strongestRoleCandidate(snapshot: LiveWizardSnapshot, role: String): LiveEndpointCandidate? {
        return snapshot.endpointCandidates[role].orEmpty().maxByOrNull { it.score }
    }

    private fun hasStrongRoleCandidate(snapshot: LiveWizardSnapshot, role: String): Boolean {
        val candidate = strongestRoleCandidate(snapshot, role) ?: return false
        if (candidate.confidence < 0.7) return false
        if (candidate.score < strongCandidateThreshold(role)) return false
        if (isLiveRoleCrossContaminated(role, candidate.requestOperation, candidate.normalizedPath)) return false
        if (isLiveNoiseEndpoint(
                LiveEndpointAggregate(
                    endpointId = candidate.endpointId,
                    role = candidate.role,
                    method = candidate.method,
                    normalizedHost = candidate.normalizedHost,
                    normalizedPath = candidate.normalizedPath,
                    requestOperation = candidate.requestOperation,
                    phaseId = candidate.phaseId,
                    sampleUrl = candidate.sampleUrl,
                ),
            )
        ) {
            return false
        }
        return true
    }

    private fun inferLiveRole(phaseId: String, operation: String, normalizedPath: String): String {
        val phase = normalizePhaseId(phaseId) ?: PHASE_BACKGROUND
        val op = operation.lowercase(Locale.ROOT)
        val path = normalizedPath.lowercase(Locale.ROOT)
        val context = "$op $path"
        if (isLiveTrackingSignal(op, path) || isLiveNoiseEndpoint(
                LiveEndpointAggregate(
                    endpointId = "live_role_probe",
                    role = "helper",
                    method = "GET",
                    normalizedHost = "",
                    normalizedPath = path,
                    requestOperation = op,
                    phaseId = phase,
                    sampleUrl = "",
                ),
            )
        ) {
            return "helper"
        }
        return when {
            hasRefreshHint(context) -> "refresh"
            hasAuthHint(context) -> "auth"
            context.contains("playback") || context.contains("resolver") || context.contains("ptmd") || context.contains("manifest") || path.endsWith(".m3u8") || path.endsWith(".mpd") -> "playbackResolver"
            isLiveSearchSignal(op, path) -> "search"
            isLiveDetailSignal(op, path) -> "detail"
            isLiveHomeSignal(op, path) -> "home"
            context.contains("config") || context.contains("settings") || context.contains("einstellungen") || context.contains("konfiguration") -> "config"
            phase.startsWith("home") -> "home"
            phase.startsWith("search") -> "search"
            phase.startsWith("detail") -> "detail"
            phase.startsWith("playback") -> "playbackResolver"
            phase.startsWith("auth") -> "auth"
            phase.startsWith("replay") -> "helper"
            else -> "helper"
        }
    }

    private fun scoreLiveCandidate(
        aggregate: LiveEndpointAggregate,
        confidence: Double,
        targetPhase: String,
    ): Double {
        var score = confidence * 100.0
        score += aggregate.requestEvidenceCount * 3.0
        score += aggregate.responseOkCount * 8.0
        if (aggregate.hostClassSignals.any { it.startsWith("target_") }) score += 12.0
        if (aggregate.responseMimeTypes.any { it.contains("json") }) score += 7.0
        if (aggregate.responseMimeTypes.any { it.contains("html") }) score += 0.4
        if (aggregate.method == "OPTIONS") score -= 40.0
        if (aggregate.phaseId == targetPhase) score += 10.0
        if (aggregate.routeKinds.contains(aggregate.role)) score += 6.0
        val operation = aggregate.requestOperation.lowercase(Locale.ROOT)
        val path = aggregate.normalizedPath.lowercase(Locale.ROOT)
        val signals = aggregate.internalSignals
        when (aggregate.role) {
            "home" -> {
                if (isLiveHomeSignal(operation, path)) score += 8.0
                if ("entry_surface" in signals) score += 6.0
                if ("collection_feed" in signals) score += 7.0
                if ("item_summary" in signals) score += 3.5
                if ("item_detail" in signals) score -= 6.0
                if ("playback_resolution" in signals) score -= 8.0
                if (isLiveSearchSignal(operation, path) || isLiveDetailSignal(operation, path) || isLiveOrCategorySignal(operation, path)) score -= 8.0
            }
            "search" -> {
                if (isLiveSearchSignal(operation, path)) score += 8.0
                if (path.contains("/suche") || path.contains("/suchergebnisse")) score += 6.0
                if ("search_results" in signals) score += 7.0
                if ("item_summary" in signals) score += 3.0
                if ("item_detail" in signals) score -= 5.0
                if ("playback_resolution" in signals) score -= 8.0
                if (path.contains("/graphql") && !hasSearchHint(operation)) score -= 6.0
                if (isLiveOrCategorySignal(operation, path) || isLiveDetailSignal(operation, path)) score -= 8.0
            }
            "detail" -> {
                if (isLiveDetailSignal(operation, path)) score += 8.0
                if ("item_detail" in signals) score += 7.0
                if ("collection_feed" in signals) score -= 9.0
                if ("entry_surface" in signals) score -= 8.0
                if ("search_results" in signals) score -= 7.0
                if ("playback_resolution" in signals) score -= 8.0
                if (path.contains("/graphql") && !isLiveDetailSignal(operation, path)) score -= 5.0
                if (isLiveSearchSignal(operation, path) || isLiveOrCategorySignal(operation, path)) score -= 7.0
                if (path.contains("/ptmd/") || path.contains("/tmd/") || path.contains("manifest")) score -= 9.0
            }
            "playbackResolver" -> {
                if (path.contains("/ptmd/") || path.contains("/tmd/")) score += 14.0
                if (path.endsWith(".m3u8") || path.endsWith(".mpd")) score += 4.0
                if (operation.contains("resolver") || operation.contains("playback_manifest")) score += 6.0
                if ("playback_resolution" in signals) score += 8.0
                if ("collection_feed" in signals) score -= 10.0
                if ("item_summary" in signals) score -= 5.0
            }
        }
        if ("row_or_rail" in aggregate.topologyHints && aggregate.role == "home") score += 2.0
        if ("tabbed_collection" in aggregate.topologyHints && aggregate.role == "home") score += 1.5
        if ("category_collection" in aggregate.topologyHints && aggregate.role == "home") score += 2.0
        if ("tabbed_collection" in aggregate.topologyHints && aggregate.role == "search") score += 1.0
        if (isLiveRoleCrossContaminated(aggregate.role, operation, path)) score -= 18.0
        if (isLiveNoiseEndpoint(aggregate)) score -= 80.0
        return score
    }

    private fun isLiveNoiseEndpoint(aggregate: LiveEndpointAggregate): Boolean {
        val host = aggregate.normalizedHost.lowercase(Locale.ROOT)
        val path = aggregate.normalizedPath.lowercase(Locale.ROOT)
        val context = "${aggregate.requestOperation} $host $path".lowercase(Locale.ROOT)
        if (host.contains("analytics") || host.contains("measurement") || host.contains("metrics") || host.contains("sentry")) {
            return true
        }
        if (path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".woff") || path.endsWith(".woff2")) return true
        if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif")) return true
        if (path.endsWith(".svg") || path.endsWith(".webp") || path.endsWith(".ico")) return true
        if (path.endsWith(".ts") || path.endsWith(".m4s")) return true
        if (path.endsWith(".vtt") || path.endsWith(".mp3") || path.endsWith(".mp4") || path.endsWith(".m4a")) return true
        if (path.contains("/usage-data/") || path.contains("/user-histories/")) return true
        if (path.contains("/geo.txt") || path.contains("/nmrodam")) return true
        if (path.endsWith("/event") || path.contains("/event/")) return true
        if (context.contains("segment") || context.contains("subtitle") || context.contains("geo") || context.contains("usage-history")) return true
        return false
    }

    private fun parseLiveRequestEvent(
        root: JSONObject,
        payload: JSONObject,
        targetPhase: String,
    ): LiveRequestEvent? {
        val requestId = payload.optString("request_id").ifBlank { root.optString("event_id") }
        if (requestId.isBlank()) return null
        val method = payload.optString("method").ifBlank { "GET" }.uppercase(Locale.ROOT)
        val url = payload.optString("url")
        if (url.isBlank()) return null
        val normalizedParts = normalizedUrlParts(url)
        val normalizedHost = payload.optString("normalized_host").ifBlank { normalizedParts.host }
        val normalizedPath = payload.optString("normalized_path").ifBlank { normalizedParts.path }
        val operation = payload.optString("request_operation")
            .ifBlank { payload.optString("operation") }
            .ifBlank { normalizedPath.trim('/').substringBefore('/') }
        val phaseId = resolveLivePhase(
            phaseId = payload.optString("phase_id"),
            routeKind = payload.optString("route_kind"),
            operation = operation,
            path = normalizedPath,
            mimeType = "",
        )
        if (targetPhase.isNotBlank() && phaseId != targetPhase) return null
        val queryParamNames = parseQueryParamNames(url)
        val bodyFieldNames = parseBodyFieldNames(payload.optString("body_preview"))
        return LiveRequestEvent(
            requestId = requestId,
            phaseId = phaseId,
            method = method,
            url = url,
            normalizedHost = normalizedHost,
            normalizedPath = normalizedPath,
            operation = operation.lowercase(Locale.ROOT),
            queryParamNames = queryParamNames,
            bodyFieldNames = bodyFieldNames,
        )
    }

    private fun parseLiveResponseEvent(
        root: JSONObject,
        payload: JSONObject,
        targetPhase: String,
    ): LiveResponseEvent? {
        val requestId = payload.optString("request_id").ifBlank { root.optString("request_id") }
        if (requestId.isBlank()) return null
        val url = payload.optString("url")
        if (url.isBlank()) return null
        val normalizedParts = normalizedUrlParts(url)
        val normalizedHost = payload.optString("normalized_host").ifBlank { normalizedParts.host }
        val normalizedPath = payload.optString("normalized_path").ifBlank { normalizedParts.path }
        val operation = payload.optString("response_operation")
            .ifBlank { payload.optString("request_operation") }
            .ifBlank { payload.optString("operation") }
            .ifBlank { normalizedPath.trim('/').substringBefore('/') }
        val statusCode = when {
            payload.has("status_code") -> payload.optInt("status_code", 0)
            payload.has("status") -> payload.optInt("status", 0)
            else -> 0
        }
        val mimeType = payload.optString("mime_type").ifBlank { payload.optString("mime") }
        val phaseId = resolveLivePhase(
            phaseId = payload.optString("phase_id"),
            routeKind = payload.optString("route_kind"),
            operation = operation,
            path = normalizedPath,
            mimeType = mimeType,
        )
        if (targetPhase.isNotBlank() && phaseId != targetPhase) return null
        return LiveResponseEvent(
            requestId = requestId,
            phaseId = phaseId,
            statusCode = statusCode,
            routeKind = payload.optString("route_kind").trim().lowercase(Locale.ROOT),
            operation = operation.lowercase(Locale.ROOT),
            normalizedHost = normalizedHost,
            normalizedPath = normalizedPath,
            hostClass = payload.optString("host_class").ifBlank { "unknown" },
            mimeType = mimeType.lowercase(Locale.ROOT),
            bodyPreview = payload.optString("body_preview"),
        )
    }

    private fun resolveLivePhase(
        phaseId: String,
        routeKind: String,
        operation: String,
        path: String,
        mimeType: String,
    ): String {
        val normalized = normalizePhaseId(phaseId) ?: PHASE_BACKGROUND
        if (normalized != PHASE_BACKGROUND) return normalized
        val loweredRoute = routeKind.trim().lowercase(Locale.ROOT)
        val loweredOp = operation.lowercase(Locale.ROOT)
        val loweredPath = path.lowercase(Locale.ROOT)
        val context = "$loweredOp $loweredPath"
        return when {
            loweredRoute == "home" || loweredPath == "/" || hasHomeHint(context) -> "home_probe"
            loweredRoute == "search" || hasSearchHint(context) -> "search_probe"
            loweredRoute == "detail" || hasDetailHint(context) -> "detail_probe"
            isPlaybackSignal(
                routeKind = loweredRoute,
                operation = loweredOp,
                path = loweredPath,
                mimeType = mimeType.lowercase(Locale.ROOT),
            ) -> "playback_probe"
            loweredRoute == "auth" ||
                hasAuthHint(context) -> "auth_probe"
            else -> PHASE_BACKGROUND
        }
    }

    private fun parseQueryParamNames(url: String): Set<String> {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return emptySet()
        return uri.queryParameterNames.map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    private fun parseBodyFieldNames(bodyPreview: String): Set<String> {
        val trimmed = bodyPreview.trim()
        if (trimmed.isBlank()) return emptySet()
        return runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.keys().asSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) emptySet()
                    else {
                        val first = arr.optJSONObject(0) ?: return@runCatching emptySet()
                        first.keys().asSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    }
                }
                else -> emptySet()
            }
        }.getOrDefault(emptySet())
    }

    private fun collectAuthEvidence(context: Context): Int {
        val file = eventFile(context)
        if (!file.exists()) return 0
        val session = missionSessionState(context)
        var count = 0
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (root.optString("event_type") != "auth_event") return@forEachLine
                val payload = root.optJSONObject("payload") ?: JSONObject()
                if (!isEventWithinMission(session, root, payload)) return@forEachLine
                val phaseId = normalizePhaseId(payload.optString("phase_id")) ?: PHASE_BACKGROUND
                if (phaseId == "auth_probe") {
                    count += 1
                }
            }
        }
        return count
    }

    private data class AuthChainEvidence(
        val loginResponses: Int,
        val validationResponses: Int,
        val refreshResponses: Int,
        val authEvents: Int,
        val refreshTriggers: Int,
    ) {
        val totalResponses: Int
            get() = loginResponses + validationResponses + refreshResponses
        val hasAnyEvidence: Boolean
            get() = totalResponses > 0 || authEvents > 0
        val chainComplete: Boolean
            get() = loginResponses > 0 && validationResponses > 0 && refreshResponses > 0
    }

    private fun collectAuthChainEvidence(context: Context): AuthChainEvidence {
        val file = eventFile(context)
        if (!file.exists()) {
            return AuthChainEvidence(
                loginResponses = 0,
                validationResponses = 0,
                refreshResponses = 0,
                authEvents = 0,
                refreshTriggers = 0,
            )
        }
        val session = missionSessionState(context)
        var loginResponses = 0
        var validationResponses = 0
        var refreshResponses = 0
        var authEvents = 0
        var refreshTriggers = 0
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val eventType = root.optString("event_type")
                val payload = root.optJSONObject("payload") ?: JSONObject()
                if (!isEventWithinMission(session, root, payload)) return@forEachLine
                when (eventType) {
                    "auth_event" -> {
                        val phaseId = normalizePhaseId(payload.optString("phase_id")) ?: PHASE_BACKGROUND
                        if (phaseId == "auth_probe") authEvents += 1
                    }
                    "network_response_event" -> {
                        val phaseId = normalizePhaseId(payload.optString("phase_id")) ?: PHASE_BACKGROUND
                        val statusCode = when {
                            payload.has("status_code") -> payload.optInt("status_code", 0)
                            payload.has("status") -> payload.optInt("status", 0)
                            else -> 0
                        }
                        val operation = payload.optString("response_operation")
                            .ifBlank { payload.optString("request_operation") }
                            .ifBlank { payload.optString("operation") }
                            .lowercase(Locale.ROOT)
                        val path = payload.optString("normalized_path")
                            .ifBlank { runCatching { normalizedUrlParts(payload.optString("url")).path }.getOrDefault("") }
                            .lowercase(Locale.ROOT)
                        val routeKind = payload.optString("route_kind").lowercase(Locale.ROOT)
                        val authRelated = phaseId == "auth_probe" ||
                            routeKind == "auth" ||
                            hasAuthHint("$operation $path")
                        if (statusCode in setOf(401, 403)) {
                            refreshTriggers += 1
                        }
                        if (statusCode !in 200..399 || !authRelated) return@forEachLine
                        when {
                            isRefreshOperation(operation = operation, path = path) -> refreshResponses += 1
                            isValidationOperation(operation = operation, path = path) -> validationResponses += 1
                            isLoginOperation(operation = operation, path = path) -> loginResponses += 1
                        }
                    }
                }
            }
        }
        return AuthChainEvidence(
            loginResponses = loginResponses,
            validationResponses = validationResponses,
            refreshResponses = refreshResponses,
            authEvents = authEvents,
            refreshTriggers = refreshTriggers,
        )
    }

    private fun isLoginOperation(operation: String, path: String): Boolean {
        if (isRefreshOperation(operation, path) || isValidationOperation(operation, path)) return false
        return hasLoginHint("$operation $path")
    }

    private fun isValidationOperation(operation: String, path: String): Boolean {
        return hasValidationHint("$operation $path")
    }

    private fun isRefreshOperation(operation: String, path: String): Boolean {
        return hasRefreshHint("$operation $path")
    }

    private fun collectWizardReadyHitsByStep(context: Context): Map<String, Int> {
        val file = eventFile(context)
        if (!file.exists()) return emptyMap()
        val session = missionSessionState(context)
        val counts = linkedMapOf<String, Int>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (root.optString("event_type") != "wizard_event") return@forEachLine
                val payload = root.optJSONObject("payload") ?: JSONObject()
                if (!isEventWithinMission(session, root, payload)) return@forEachLine
                if (payload.optString("operation") != "wizard_ready_window_hit") return@forEachLine
                val stepId = payload.optString("wizard_step_id").trim()
                if (stepId.isBlank()) return@forEachLine
                counts[stepId] = (counts[stepId] ?: 0) + 1
            }
        }
        return counts
    }

    private fun evaluateFishitStepSaturation(
        context: Context,
        stepId: String,
    ): RuntimeToolkitMissionWizard.SaturationResult {
        val responses = collectResponseObservations(context)
        val readyHitsByStep = collectWizardReadyHitsByStep(context)
        val authChainEvidence = collectAuthChainEvidence(context)
        val liveSnapshot = buildLiveWizardSnapshot(context, maxRecent = 20)
        val successfulTargetByPhase = responses
            .filter { it.succeeded && it.targetEvidence }
            .groupBy { inferProbePhase(it) }
        fun roleCandidateMetrics(role: String): Map<String, Any?> {
            val strongest = strongestRoleCandidate(liveSnapshot, role)
            return mapOf(
                "role_candidate_ok" to hasStrongRoleCandidate(liveSnapshot, role),
                "top_candidate_endpoint_id" to strongest?.endpointId.orEmpty(),
                "top_candidate_score" to (strongest?.score ?: 0.0),
                "top_candidate_confidence" to (strongest?.confidence ?: 0.0),
            )
        }

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
                val candidateMetrics = roleCandidateMetrics("home")
                val candidateOk = candidateMetrics["role_candidate_ok"] == true
                if (count >= 1 && candidateOk) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "home_probe_evidence_ok",
                        metrics = mapOf("response_count" to count) + candidateMetrics,
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = if (count <= 0) "missing_home_target_response" else "home_probe_low_quality_candidates",
                        metrics = mapOf("response_count" to count) + candidateMetrics,
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE -> {
                val relevant = successfulTargetByPhase["search_probe"].orEmpty().filter {
                    hasSearchHint("${it.operation} ${it.path}")
                }
                val unique = relevant.map { it.fingerprint }.toSet().size
                val readyHits = readyHitsByStep[RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE] ?: 0
                val candidateMetrics = roleCandidateMetrics("search")
                val candidateOk = candidateMetrics["role_candidate_ok"] == true
                val saturated = unique >= 2 && candidateOk && (readyHits > 0 || unique >= 3)
                if (saturated) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "search_probe_saturated",
                        metrics = mapOf(
                            "unique_request_templates" to unique,
                            "ready_hits" to readyHits,
                        ) + candidateMetrics,
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = if (unique < 2) "search_probe_needs_more_variants" else "search_probe_low_quality_candidates",
                        metrics = mapOf(
                            "unique_request_templates" to unique,
                            "ready_hits" to readyHits,
                        ) + candidateMetrics,
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE -> {
                val relevant = successfulTargetByPhase["detail_probe"].orEmpty().filter {
                    it.routeKind == "detail" ||
                        it.routeKind == "category" ||
                        hasDetailHint("${it.operation} ${it.path}") ||
                        hasCategoryHint("${it.operation} ${it.path}") ||
                        hasGenreHint("${it.operation} ${it.path}")
                }
                val readyHits = readyHitsByStep[RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE] ?: 0
                val responseCount = relevant.size
                val candidateMetrics = roleCandidateMetrics("detail")
                val candidateOk = candidateMetrics["role_candidate_ok"] == true
                val saturated = responseCount > 0 && candidateOk && (readyHits > 0 || responseCount >= 2)
                if (saturated) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "detail_probe_evidence_ok",
                        metrics = mapOf(
                            "response_count" to responseCount,
                            "ready_hits" to readyHits,
                        ) + candidateMetrics,
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = if (responseCount <= 0) "missing_detail_target_response" else "detail_probe_low_quality_candidates",
                        metrics = mapOf(
                            "response_count" to responseCount,
                            "ready_hits" to readyHits,
                        ) + candidateMetrics,
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE -> {
                val relevant = successfulTargetByPhase["playback_probe"].orEmpty().filter {
                    isPlaybackSignal(
                        routeKind = it.routeKind,
                        operation = it.operation,
                        path = it.path,
                        mimeType = it.mimeType,
                    )
                }
                val readyHits = readyHitsByStep[RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE] ?: 0
                val responseCount = relevant.size
                val candidateMetrics = roleCandidateMetrics("playbackResolver")
                val candidateOk = candidateMetrics["role_candidate_ok"] == true
                val strongEvidenceCount = relevant.count {
                    it.path.contains("/ptmd/") ||
                        it.path.contains("/tmd/") ||
                        it.path.endsWith(".m3u8") ||
                        it.path.endsWith(".mpd") ||
                        it.operation.contains("manifest")
                }
                val saturated = when {
                    responseCount <= 0 -> false
                    !candidateOk -> false
                    readyHits > 0 -> true
                    strongEvidenceCount > 0 -> true
                    else -> responseCount >= 2
                }
                if (saturated) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "playback_probe_evidence_ok",
                        metrics = mapOf(
                            "response_count" to responseCount,
                            "strong_evidence_count" to strongEvidenceCount,
                            "ready_hits" to readyHits,
                        ) + candidateMetrics,
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = if (responseCount <= 0) "missing_playback_manifest_or_resolver" else "playback_probe_low_quality_candidates",
                        metrics = mapOf(
                            "response_count" to responseCount,
                            "strong_evidence_count" to strongEvidenceCount,
                            "ready_hits" to readyHits,
                        ) + candidateMetrics,
                    )
                }
            }
            RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL -> {
                val readyHits = readyHitsByStep[RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL] ?: 0
                if (!authChainEvidence.hasAnyEvidence) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = "auth_optional_not_collected",
                        metrics = mapOf(
                            "auth_events" to authChainEvidence.authEvents,
                            "auth_responses" to authChainEvidence.totalResponses,
                            "login_responses" to authChainEvidence.loginResponses,
                            "validation_responses" to authChainEvidence.validationResponses,
                            "refresh_responses" to authChainEvidence.refreshResponses,
                            "refresh_triggers" to authChainEvidence.refreshTriggers,
                            "ready_hits" to readyHits,
                        ),
                    )
                } else if (authChainEvidence.chainComplete &&
                    (readyHits > 0 || authChainEvidence.totalResponses >= 4)
                ) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "auth_chain_complete",
                        metrics = mapOf(
                            "auth_events" to authChainEvidence.authEvents,
                            "auth_responses" to authChainEvidence.totalResponses,
                            "login_responses" to authChainEvidence.loginResponses,
                            "validation_responses" to authChainEvidence.validationResponses,
                            "refresh_responses" to authChainEvidence.refreshResponses,
                            "refresh_triggers" to authChainEvidence.refreshTriggers,
                            "ready_hits" to readyHits,
                        ),
                    )
                } else {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
                        reason = "auth_chain_incomplete",
                        metrics = mapOf(
                            "auth_events" to authChainEvidence.authEvents,
                            "auth_responses" to authChainEvidence.totalResponses,
                            "login_responses" to authChainEvidence.loginResponses,
                            "validation_responses" to authChainEvidence.validationResponses,
                            "refresh_responses" to authChainEvidence.refreshResponses,
                            "refresh_triggers" to authChainEvidence.refreshTriggers,
                            "ready_hits" to readyHits,
                        ),
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
                val gateError = latestExportGateError(context)
                val topGateReasons = extractTopGateReasons(gateError)
                if (requiredComplete && exportReady) {
                    RuntimeToolkitMissionWizard.SaturationResult(
                        state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
                        reason = "mission_ready_for_export",
                        metrics = mapOf(
                            "required_steps_complete" to true,
                            "export_readiness" to summary.exportReadiness,
                            "summary_reason" to summary.reason,
                            "missing_required_artifacts" to summary.missingRequiredArtifacts,
                            "export_gate_top_reasons" to topGateReasons,
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
                            "export_gate_error" to gateError,
                            "export_gate_top_reasons" to topGateReasons,
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

    private fun extractTopGateReasons(rawError: String, maxItems: Int = 3): List<String> {
        val value = rawError.trim()
        if (value.isBlank()) return emptyList()
        return value
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                when {
                    line.startsWith("- ") -> line.removePrefix("- ").trim()
                    line.startsWith("source pipeline export blocked by gates:") -> ""
                    else -> line
                }
            }
            .filter { it.isNotBlank() }
            .take(maxItems)
            .toList()
    }

    private fun inferStepProgressPercent(
        stepId: String,
        saturation: RuntimeToolkitMissionWizard.SaturationResult,
    ): Int {
        if (saturation.state == RuntimeToolkitMissionWizard.SATURATION_SATURATED) return 100
        return when (stepId) {
            RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT -> 0
            RuntimeToolkitMissionWizard.STEP_HOME_PROBE -> {
                val count = intPayloadValue(saturation.metrics["response_count"]) ?: 0
                val candidateOk = boolPayloadValue(saturation.metrics["role_candidate_ok"]) == true
                val base = (count * 70) + if (candidateOk) 20 else -10
                minOf(95, base)
            }
            RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE -> {
                val variants = intPayloadValue(saturation.metrics["unique_request_templates"]) ?: 0
                val readyHits = intPayloadValue(saturation.metrics["ready_hits"]) ?: 0
                val candidateOk = boolPayloadValue(saturation.metrics["role_candidate_ok"]) == true
                val base = (variants * 40) + if (readyHits > 0) 15 else 0
                minOf(95, base + if (candidateOk) 15 else -15)
            }
            RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE -> {
                val count = intPayloadValue(saturation.metrics["response_count"]) ?: 0
                val readyHits = intPayloadValue(saturation.metrics["ready_hits"]) ?: 0
                val candidateOk = boolPayloadValue(saturation.metrics["role_candidate_ok"]) == true
                val base = (count * 55) + if (readyHits > 0) 20 else 0
                minOf(95, base + if (candidateOk) 15 else -15)
            }
            RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE -> {
                val count = intPayloadValue(saturation.metrics["response_count"]) ?: 0
                val strongEvidenceCount = intPayloadValue(saturation.metrics["strong_evidence_count"]) ?: 0
                val readyHits = intPayloadValue(saturation.metrics["ready_hits"]) ?: 0
                val candidateOk = boolPayloadValue(saturation.metrics["role_candidate_ok"]) == true
                val base = (count * 40) + (strongEvidenceCount * 20) + if (readyHits > 0) 15 else 0
                minOf(95, base + if (candidateOk) 15 else -15)
            }
            RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL -> {
                val login = intPayloadValue(saturation.metrics["login_responses"]) ?: 0
                val validation = intPayloadValue(saturation.metrics["validation_responses"]) ?: 0
                val refresh = intPayloadValue(saturation.metrics["refresh_responses"]) ?: 0
                val readyHits = intPayloadValue(saturation.metrics["ready_hits"]) ?: 0
                val chainScore = minOf(75, (if (login > 0) 25 else 0) + (if (validation > 0) 25 else 0) + (if (refresh > 0) 25 else 0))
                minOf(95, chainScore + if (readyHits > 0) 20 else 0)
            }
            RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT -> {
                val requiredComplete = boolPayloadValue(saturation.metrics["required_steps_complete"]) == true
                val missingSteps = (saturation.metrics["missing_required_steps"] as? List<*>)?.size ?: 0
                val readiness = stringPayloadValue(saturation.metrics["export_readiness"]).orEmpty()
                val gateBlocked = stringPayloadValue(saturation.metrics["export_gate_error"]).orEmpty().isNotBlank()
                val readinessBonus = when (readiness) {
                    EXPORT_READINESS_READY -> 40
                    EXPORT_READINESS_PARTIAL -> 20
                    else -> 0
                }
                val coveragePenalty = minOf(30, missingSteps * 10)
                val base = (if (requiredComplete) 60 else 25) + readinessBonus - coveragePenalty
                if (gateBlocked) minOf(45, base) else base
            }
            else -> 0
        }.coerceIn(0, 99)
    }

    private fun inferStepMissingSignals(
        stepId: String,
        saturation: RuntimeToolkitMissionWizard.SaturationResult,
    ): List<String> {
        if (saturation.state == RuntimeToolkitMissionWizard.SATURATION_SATURATED) return emptyList()
        return when (stepId) {
            RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT -> listOf("target_url_confirmed")
            RuntimeToolkitMissionWizard.STEP_HOME_PROBE -> {
                val count = intPayloadValue(saturation.metrics["response_count"]) ?: 0
                val candidateOk = boolPayloadValue(saturation.metrics["role_candidate_ok"]) == true
                buildList {
                    if (count <= 0) add("home_target_response")
                    if (!candidateOk) add("high_quality_home_candidate")
                }.ifEmpty { listOf("home_target_response") }
            }
            RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE -> {
                val variants = intPayloadValue(saturation.metrics["unique_request_templates"]) ?: 0
                val readyHits = intPayloadValue(saturation.metrics["ready_hits"]) ?: 0
                val candidateOk = boolPayloadValue(saturation.metrics["role_candidate_ok"]) == true
                buildList {
                    if (variants < 2) add("search_variants>=2")
                    if (!candidateOk) add("high_quality_search_candidate")
                    if (readyHits <= 0) add("ready_window_hit")
                }.ifEmpty { listOf("search_variants>=2") }
            }
            RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE -> {
                val count = intPayloadValue(saturation.metrics["response_count"]) ?: 0
                val readyHits = intPayloadValue(saturation.metrics["ready_hits"]) ?: 0
                val candidateOk = boolPayloadValue(saturation.metrics["role_candidate_ok"]) == true
                buildList {
                    if (count <= 0) add("detail_target_response")
                    if (!candidateOk) add("high_quality_detail_candidate")
                    if (readyHits <= 0) add("ready_window_hit")
                }.ifEmpty { listOf("detail_target_response") }
            }
            RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE -> {
                val count = intPayloadValue(saturation.metrics["response_count"]) ?: 0
                val strongEvidenceCount = intPayloadValue(saturation.metrics["strong_evidence_count"]) ?: 0
                val readyHits = intPayloadValue(saturation.metrics["ready_hits"]) ?: 0
                val candidateOk = boolPayloadValue(saturation.metrics["role_candidate_ok"]) == true
                buildList {
                    if (count <= 0) add("playback_manifest_or_resolver")
                    if (count > 0 && strongEvidenceCount <= 0) add("strong_playback_signal")
                    if (!candidateOk) add("high_quality_playback_candidate")
                    if (readyHits <= 0) add("ready_window_hit_recommended")
                }.ifEmpty { listOf("playback_manifest_or_resolver") }
            }
            RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL -> {
                val login = intPayloadValue(saturation.metrics["login_responses"]) ?: 0
                val validation = intPayloadValue(saturation.metrics["validation_responses"]) ?: 0
                val refresh = intPayloadValue(saturation.metrics["refresh_responses"]) ?: 0
                val readyHits = intPayloadValue(saturation.metrics["ready_hits"]) ?: 0
                buildList {
                    if (login <= 0) add("auth_login_response")
                    if (validation <= 0) add("auth_validation_response")
                    if (refresh <= 0) add("auth_refresh_response")
                    if (readyHits <= 0) add("ready_window_hit")
                }.ifEmpty { listOf("auth_event_or_response") }
            }
            RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT -> {
                val metrics = saturation.metrics
                val missingSteps = (metrics["missing_required_steps"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
                val missingArtifacts = (metrics["missing_required_artifacts"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
                val exportGateError = stringPayloadValue(metrics["export_gate_error"]).orEmpty()
                val topGateReasons = (metrics["export_gate_top_reasons"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
                val signals = mutableListOf<String>()
                signals += missingSteps.map { "step:$it" }
                signals += missingArtifacts.map { "artifact:$it" }
                if (exportGateError.isNotBlank()) signals += "gate:$exportGateError"
                signals += topGateReasons.map { "gate_reason:$it" }
                if (signals.isEmpty()) signals += "mission_export_ready"
                signals.take(5)
            }
            else -> listOf("unknown_step")
        }
    }

    private fun inferStepUserHints(
        context: Context,
        stepId: String,
        saturation: RuntimeToolkitMissionWizard.SaturationResult,
        state: MissionSessionState,
    ): List<String> {
        if (saturation.state == RuntimeToolkitMissionWizard.SATURATION_SATURATED) {
            val nextStep = RuntimeToolkitMissionWizard.nextStepId(state.missionId, stepId, context)
            return if (nextStep != null) {
                listOf("Step gesaettigt. Du kannst zum naechsten Schritt wechseln.")
            } else {
                listOf("Step gesaettigt. Export Summary pruefen und Bundle exportieren.")
            }
        }
        return when (stepId) {
            RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT -> listOf(
                "Ziel-URL eingeben und bestaetigen.",
                "Die Seite im Browser laden lassen.",
            )
            RuntimeToolkitMissionWizard.STEP_HOME_PROBE -> listOf(
                "Startseite laden und kurz scrollen.",
                "Mindestens eine Kategorie aufrufen.",
            )
            RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE -> listOf(
                "Ready druecken, dann Suche ausfuehren.",
                "Erneute Suche mit anderem Schlagwort ausfuehren.",
                "Suchergebnisse kurz oeffnen, damit API-Calls sichtbar werden.",
            )
            RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE -> listOf(
                "Zu einer Detailseite navigieren.",
                "Ready druecken, dann Detailansicht oeffnen.",
                "Wenn noetig zweiten Titel pruefen.",
            )
            RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE -> listOf(
                "Zum abspielbaren Video navigieren.",
                "Ready direkt vor Play druecken.",
                "Playback starten, bis PTMD/Manifest (.m3u8/.mpd) sichtbar ist.",
            )
            RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL -> listOf(
                "Ready druecken und anschliessend Login ausloesen.",
                "Danach Validation ausloesen (z. B. /userinfo oder Profilseite).",
                "Zum Schluss Refresh triggern (Session erneuern oder Token-Refresh).",
            )
            RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT -> {
                val exportGateError = stringPayloadValue(saturation.metrics["export_gate_error"]).orEmpty()
                if (exportGateError.isNotBlank()) {
                    listOf(
                        "Export ist fail-closed blockiert.",
                        "Auth-Flow-Kette (login/validation/refresh) und Placeholder-Usage vervollstaendigen.",
                        "Danach Step erneut mit Check pruefen und ZIP exportieren.",
                    )
                } else {
                    listOf(
                        "Fehlende Steps abschliessen.",
                        "Export Summary pruefen.",
                        "Bundle als ZIP exportieren.",
                    )
                }
            }
            else -> listOf("Schritt starten und Saettigung pruefen.")
        }
    }

    private fun resolveReadyWindowContextForNetworkEvent(
        context: Context,
        semantic: NetworkSemantic,
        normalizedPath: String,
        operation: String,
        eventKind: String,
        requestId: String?,
        phaseId: String,
    ): Map<String, Any?> {
        val window = readyWindowState(context)
        if (!window.active) {
            return mapOf(
                "wizard_arm_active" to false,
                "wizard_arm_step_id" to "",
                "wizard_arm_action_id" to "",
                "wizard_arm_within_window" to false,
            )
        }
        val withinWindow = window.withinWindow
        val matches = withinWindow && doesReadyWindowMatchEvent(
            stepId = window.armedStepId,
            semantic = semantic,
            normalizedPath = normalizedPath,
            operation = operation,
            phaseId = phaseId,
        )
        if (matches && !window.hitRecorded) {
            val hitAtMs = System.currentTimeMillis()
            runtimeSettings(context).edit()
                .putBoolean(SETTING_WIZARD_ARMED_HIT_RECORDED, true)
                .apply()
            setLatestReadyHit(context, window.armedStepId, hitAtMs)
            val session = missionSessionState(context)
            logWizardEvent(
                context = context,
                operation = "wizard_ready_window_hit",
                missionId = session.missionId.ifBlank { RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE },
                wizardStepId = window.armedStepId,
                saturationState = session.stepStates[window.armedStepId] ?: RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
                phaseId = phaseId,
                targetSiteId = session.targetSiteId,
                payload = mapOf(
                    "armed_action_id" to window.armedActionId,
                    "armed_trace_id" to window.armedTraceId,
                    "request_id" to requestId,
                    "event_kind" to eventKind,
                    "ready_hit_at" to Instant.ofEpochMilli(hitAtMs).toString(),
                    "route_kind" to semantic.routeKind,
                    "operation" to semantic.operation,
                    "normalized_path" to normalizedPath,
                ),
            )
            clearWizardReadyWindow(context, reason = "hit")
        }
        return mapOf(
            "wizard_arm_active" to window.active,
            "wizard_arm_step_id" to window.armedStepId,
            "wizard_arm_action_id" to window.armedActionId,
            "wizard_arm_within_window" to withinWindow,
        )
    }

    private fun doesReadyWindowMatchEvent(
        stepId: String,
        semantic: NetworkSemantic,
        normalizedPath: String,
        operation: String,
        phaseId: String,
    ): Boolean {
        val normalizedOperation = operation.lowercase(Locale.ROOT)
        val path = normalizedPath.lowercase(Locale.ROOT)
        val context = "$normalizedOperation $path"
        return when (stepId) {
            RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE ->
                semantic.routeKind == "search" || hasSearchHint(context)
            RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE ->
                semantic.routeKind == "detail" ||
                    semantic.routeKind == "category" ||
                    hasDetailHint(context) ||
                    hasCategoryHint(context) ||
                    hasGenreHint(context)
            RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE ->
                semantic.playbackRelated ||
                    isPlaybackSignal(
                        routeKind = semantic.routeKind,
                        operation = normalizedOperation,
                        path = path,
                        mimeType = "",
                    )
            RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL ->
                semantic.routeKind == "auth" ||
                    semantic.authRelated ||
                    phaseId == "auth_probe" ||
                    hasAuthHint(context)
            else -> false
        }
    }

    private fun isPlaybackSignal(
        routeKind: String,
        operation: String,
        path: String,
        mimeType: String,
    ): Boolean {
        val normalizedRoute = routeKind.lowercase(Locale.ROOT)
        val normalizedOperation = operation.lowercase(Locale.ROOT)
        val normalizedPath = path.lowercase(Locale.ROOT)
        val normalizedMime = mimeType.lowercase(Locale.ROOT)
        if (normalizedRoute == "playback") return true
        if (normalizedOperation.contains("playback")) return true
        if (normalizedOperation.contains("manifest")) return true
        if (normalizedOperation.contains("stream")) return true
        if (normalizedPath.contains("/ptmd/") || normalizedPath.contains("/tmd/")) return true
        if (normalizedPath.endsWith(".m3u8") || normalizedPath.endsWith(".mpd")) return true
        if (normalizedPath.endsWith(".ism/manifest")) return true
        if (normalizedPath.endsWith(".ts")) return true
        if (normalizedMime.contains("mpegurl") || normalizedMime.contains("dash+xml")) return true
        return false
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
            "replay_probe",
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
            hasDetailHint(normalized) -> "detail"
            hasSearchHint(normalized) -> "search"
            hasAuthHint(normalized) -> "auth"
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
                lowerUrl.contains("suche") ||
                lowerUrl.contains("suchergebnisse") ||
                lowerUrl.contains("detail") ||
                lowerUrl.contains("kategorie") ||
                lowerUrl.contains("rubrik") ||
                lowerUrl.contains("sendung") ||
                lowerUrl.contains("folge")
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

        val graphqlLower = graphqlOperation?.lowercase(Locale.ROOT).orEmpty()
        val authRelated = hasAuthHint("$host $pathLower $queryLower $graphqlLower")
        if (authRelated) labels += "auth"

        val searchRelated = hasSearchHint("$pathLower $queryLower $graphqlLower")
        if (searchRelated) labels += "search"

        val categoryRelated = hasCategoryHint("$pathLower $queryLower $graphqlLower") ||
            hasGenreHint("$pathLower $queryLower $graphqlLower") ||
            graphqlLower.contains("cluster") ||
            (graphqlLower.contains("collection") && !graphqlLower.contains("byvideo"))
        if (categoryRelated) labels += "category"

        val detailRelated = hasDetailHint("$pathLower $graphqlLower") ||
            hasMediaTypeHint("$pathLower $queryLower $graphqlLower") ||
            graphqlLower.contains("smartcollection")
        if (detailRelated) labels += "detail"

        val liveRelated = hasLiveHint("$pathLower $queryLower $graphqlLower")
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

        val trackingHostRelated = containsAnyToken(
            text = host,
            tokens = listOf("tracksrv", "analytics", "measurement", "metrics", "telemetry", "googletagmanager", "nmrodam"),
        )
        val trackingPathRelated = containsAnyToken(
            text = "$host $pathLower $queryLower",
            tokens = listOf("track", "analytic", "telemetry", "metrics", "/event", "/collect", "beacon", "eventtype=", "nmrodam"),
        )
        val trackingGraphqlRelated = graphqlLower.contains("tracking") || graphqlLower.contains("telemetry")
        val tracking = trackingHostRelated || trackingPathRelated || trackingGraphqlRelated || source.contains("analytics", ignoreCase = true)
        if (tracking) labels += "tracking"
        val trackingPreferred = tracking && !authRelated && !playbackStrongSignal

        val configRelated = containsAnyToken(
            text = "$pathLower $queryLower",
            tokens = listOf(
                "/config",
                "/configuration",
                "/settings",
                "/einstellungen",
                "/konfiguration",
                "/bootstrap",
                "appconfig",
                "konfig",
            ),
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
            trackingPreferred -> "tracking"
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
            trackingPreferred -> "tracking"
            hasGraphql && (graphqlLower.contains("activelive") || graphqlLower == "getepg") -> "live"
            hasGraphql && graphqlLower == "getsmartcollectionidsbyvideoids" -> "detail"
            else -> null
        }

        val classification = classificationOverride ?: when {
            authRelated -> "auth"
            trackingPreferred -> "tracking"
            searchRelated -> "search"
            detailRelated -> "detail"
            categoryRelated -> "category"
            liveRelated -> "live"
            playbackRelated -> "playback"
            configRelated -> "config"
            assetRelated -> "asset"
            else -> "generic"
        }

        val operation = when (classification) {
            "auth" -> when {
                pathLower.contains("/fsk/pin") -> "auth_pin_verify"
                pathLower.contains("/fsk/verification") -> "auth_age_verification"
                pathLower.contains("/userinfo") -> "auth_userinfo"
                pathLower.contains("/userdetails") -> "auth_userdetails"
                pathLower.contains("logout") || pathLower.contains("abmelden") || pathLower.contains("ausloggen") -> "auth_logout"
                pathLower.contains("login") || pathLower.contains("signin") || pathLower.contains("anmelden") || pathLower.contains("einloggen") -> "auth_login"
                pathLower.contains("refresh") || pathLower.contains("token_refresh") || pathLower.contains("/token/refresh") || pathLower.contains("aktualisieren") || pathLower.contains("erneuern") -> "auth_token_refresh"
                else -> "auth_request"
            }

            "search" -> if (hasGraphql) "search_graphql_query" else "search_request"
            "category" -> if (hasGraphql) "category_graphql_query" else "category_browse"
            "detail" -> if (hasGraphql) "detail_graphql_query" else "detail_load"
            "live" -> if (hasGraphql) "live_catalog_query" else "live_load"
            "playback" -> when {
                pathLower.contains("/playbackhistory") || pathLower.contains("seamless-view-entries") -> "playback_history_sync"
                pathLower.contains("/tmd/") || pathLower.contains("/ptmd/") -> "playback_resolver_fetch"
                pathLower.contains("manifest") || extension in setOf("m3u8", "mpd") -> "playback_manifest_fetch"
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
        val normalizedHaystack = normalizeHintText(haystack)
        return tokens.any {
            val token = it.lowercase(Locale.ROOT)
            haystack.contains(token) || normalizedHaystack.contains(normalizeHintText(token))
        }
    }

    private fun normalizeHintText(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
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

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            out.append(String.format("%02x", b))
        }
        return out.toString().take(16)
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
