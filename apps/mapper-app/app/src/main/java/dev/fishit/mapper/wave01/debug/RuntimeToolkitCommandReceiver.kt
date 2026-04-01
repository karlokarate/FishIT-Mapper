package dev.fishit.mapper.wave01.debug

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import dev.fishit.mapper.network.MapperHttpMethod
import dev.fishit.mapper.network.MapperNativeHttpRequest
import dev.fishit.mapper.network.MapperRedirectPolicy
import dev.fishit.mapper.wave01.debug.integration.MapperToolkitIntegrationContract
import dev.fishit.mapper.wave01.debug.replay.MapperNativeReplayRuntime
import info.plateaukao.einkbro.activity.BrowserActivity
import info.plateaukao.einkbro.activity.SettingActivity
import info.plateaukao.einkbro.unit.BrowserUnit
import org.json.JSONObject
import java.util.UUID

class RuntimeToolkitCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RUNTIME_TOOLKIT_COMMAND) {
            RuntimeToolkitTelemetry.emitAck("unknown", "ignored", mapOf("reason" to "unexpected_action"))
            return
        }

        if (!isDebuggable(context)) {
            RuntimeToolkitTelemetry.emitAck("rejected", "error", mapOf("reason" to "non_debug_build"))
            return
        }

        val op = intent.getStringExtra(EXTRA_OP).orEmpty().ifBlank { "ping" }

        runCatching {
            when (op) {
                OP_PING -> handlePing(context)
                OP_SESSION_START -> handleSessionStart(context, intent)
                OP_SESSION_STOP -> handleSessionStop(context)
                OP_MARK_UI_START -> handleMarkUiStart(context, intent)
                OP_MARK_UI_END -> handleMarkUiEnd(context, intent)
                OP_OPEN_SCREEN -> handleOpenScreen(context, intent)
                OP_OPEN_URL -> handleOpenUrl(context, intent)
                OP_SET_SETTING -> handleSetSetting(context, intent)
                OP_OPEN_DETAIL -> handleOpenDetail(context, intent)
                OP_INSPECT_WORK -> handleInspectWork(context, intent)
                OP_QUERY_ENTITIES -> handleQueryEntities(context, intent)
                OP_SNAPSHOT_COOKIES -> handleSnapshotCookies(context, intent)
                OP_SET_SCOPE -> handleSetScope(context, intent)
                OP_SET_PROBE_PHASE -> handleSetProbePhase(context, intent)
                OP_CLEAR_PROBE_PHASE -> handleClearProbePhase(context)
                OP_MARK_PROVENANCE -> handleMarkProvenance(context, intent)
                OP_NATIVE_REPLAY_REQUEST -> handleNativeReplayRequest(context, intent)
                OP_RESET_SITE_STATE -> handleResetSiteState(context, intent)
                OP_CLEAR_RUNTIME_EVENTS -> handleClearRuntimeEvents(context)
                OP_STATUS -> handleStatus(context)
                else -> {
                    RuntimeToolkitTelemetry.logExtractionEvent(
                        context = context,
                        operation = "unknown_command",
                        payload = mapOf("op" to op),
                    )
                    RuntimeToolkitTelemetry.emitAck(op, "ignored", mapOf("reason" to "unknown_op"))
                }
            }
        }.onFailure { throwable ->
            RuntimeToolkitTelemetry.logExtractionEvent(
                context = context,
                operation = "command_failed",
                payload = mapOf(
                    "op" to op,
                    "message" to (throwable.message ?: "unknown"),
                ),
            )
            RuntimeToolkitTelemetry.emitAck(op, "error", mapOf("message" to (throwable.message ?: "unknown")))
        }
    }

    private fun handlePing(context: Context) {
        RuntimeToolkitTelemetry.logExtractionEvent(context, "ping")
        RuntimeToolkitTelemetry.emitAck(OP_PING, "ok")
    }

    private fun handleSessionStart(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty().ifBlank {
            "mapper_${System.currentTimeMillis()}"
        }
        val scopeMode = intent.getStringExtra(EXTRA_SCOPE_MODE).orEmpty()
        if (scopeMode.isNotBlank()) {
            RuntimeToolkitTelemetry.setScopeMode(context, scopeMode)
        }
        val targetHosts = intent.getStringExtra(EXTRA_TARGET_HOST_FAMILY).orEmpty()
        if (targetHosts.isNotBlank()) {
            RuntimeToolkitTelemetry.setTargetHostFamily(context, targetHosts)
        }
        val providedActionId = intent.getStringExtra(EXTRA_ACTION_ID).orEmpty()
        val correlation = RuntimeToolkitTelemetry.CorrelationContext(
            traceId = intent.getStringExtra(EXTRA_TRACE_ID).orEmpty().ifBlank { UUID.randomUUID().toString() },
            actionId = providedActionId.takeIf { it.isNotBlank() && it != "session_start" } ?: UUID.randomUUID().toString(),
            spanId = UUID.randomUUID().toString(),
        )

        RuntimeToolkitTelemetry.setRunAndContext(context, runId, correlation)
        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "session_start",
            payload = mapOf(
                "run_id" to runId,
                "runtime_settings" to RuntimeToolkitTelemetry.runtimeSettingsSnapshot(context),
            ),
        )
        RuntimeToolkitTelemetry.emitAck(
            OP_SESSION_START,
            "ok",
            mapOf(
                "run_id" to runId,
                "runtime_settings" to RuntimeToolkitTelemetry.runtimeSettingsSnapshot(context),
            ),
        )
    }

    private fun handleSessionStop(context: Context) {
        RuntimeToolkitTelemetry.logExtractionEvent(context, "session_stop")
        RuntimeToolkitTelemetry.emitAck(OP_SESSION_STOP, "ok")
    }

    private fun handleMarkUiStart(context: Context, intent: Intent) {
        val actionName = intent.getStringExtra(EXTRA_ACTION_NAME).orEmpty().ifBlank { "ui_action" }
        val screenId = intent.getStringExtra(EXTRA_SCREEN)
        val anchorId = intent.getStringExtra(EXTRA_UI_ANCHOR_ID)
        val tabId = intent.getStringExtra(EXTRA_TAB_ID)

        val correlation = RuntimeToolkitTelemetry.beginUiAction(
            context = context,
            actionName = actionName,
            screenId = screenId,
            uiAnchorId = anchorId,
            tabId = tabId,
            payload = mapOf("source" to "adb_receiver"),
        )

        RuntimeToolkitTelemetry.emitAck(
            OP_MARK_UI_START,
            "ok",
            mapOf(
                "trace_id" to correlation.traceId,
                "action_id" to correlation.actionId,
                "span_id" to correlation.spanId,
            ),
        )
    }

    private fun handleMarkUiEnd(context: Context, intent: Intent) {
        val correlation = RuntimeToolkitTelemetry.currentCorrelationContext(context)
        val actionName = intent.getStringExtra(EXTRA_ACTION_NAME).orEmpty().ifBlank { "ui_action" }
        val result = intent.getStringExtra(EXTRA_RESULT).orEmpty().ifBlank { "ok" }

        RuntimeToolkitTelemetry.finishUiAction(
            context = context,
            correlation = correlation,
            actionName = actionName,
            result = result,
            payload = mapOf("source" to "adb_receiver"),
        )

        RuntimeToolkitTelemetry.emitAck(OP_MARK_UI_END, "ok", mapOf("result" to result))
    }

    private fun handleOpenScreen(context: Context, intent: Intent) {
        val screen = intent.getStringExtra(EXTRA_SCREEN).orEmpty().ifBlank { "home" }
        val correlation = RuntimeToolkitTelemetry.beginUiAction(
            context = context,
            actionName = "open_screen",
            screenId = screen,
            payload = mapOf("source" to "adb_receiver"),
        )

        when (screen) {
            "settings" -> {
                context.startActivity(
                    Intent(context, SettingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            "mapper_toolkit" -> {
                context.startActivity(
                    Intent(context, MapperToolkitActivity::class.java)
                        .setAction(MapperToolkitIntegrationContract.ACTION_OPEN_MAPPER_TOOLKIT)
                        .putExtra(MapperToolkitIntegrationContract.EXTRA_CALLER, "runtime_toolkit_receiver")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            "library" -> {
                context.startActivity(
                    Intent(context, BrowserActivity::class.java)
                        .setAction("sc_bookmark")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            "search" -> {
                val query = intent.getStringExtra(EXTRA_QUERY).orEmpty().ifBlank { " " }
                context.startActivity(
                    Intent(context, BrowserActivity::class.java)
                        .setAction(Intent.ACTION_WEB_SEARCH)
                        .putExtra(SearchManager.QUERY, query)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            "home", "refresh" -> {
                context.startActivity(
                    Intent(context, BrowserActivity::class.java)
                        .setAction("sc_home")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            else -> {
                context.startActivity(
                    Intent(context, BrowserActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }

        RuntimeToolkitTelemetry.finishUiAction(
            context = context,
            correlation = correlation,
            actionName = "open_screen",
            result = "ok",
            payload = mapOf("screen" to screen),
        )
        RuntimeToolkitTelemetry.emitAck(OP_OPEN_SCREEN, "ok", mapOf("screen" to screen))
    }

    private fun handleOpenUrl(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        require(url.isNotBlank()) { "open_url requires url" }

        val correlation = RuntimeToolkitTelemetry.beginUiAction(
            context = context,
            actionName = "open_url",
            screenId = "browser",
            payload = mapOf("url" to url),
        )

        context.startActivity(
            Intent(context, BrowserActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )

        RuntimeToolkitTelemetry.finishUiAction(
            context = context,
            correlation = correlation,
            actionName = "open_url",
            result = "ok",
            payload = mapOf("url" to url),
        )
        RuntimeToolkitTelemetry.emitAck(OP_OPEN_URL, "ok", mapOf("url" to url))
    }

    private fun handleSetSetting(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val value = intent.getStringExtra(EXTRA_VALUE).orEmpty()
        require(key.isNotBlank()) { "set_setting requires key" }

        context.getSharedPreferences(PREF_RUNTIME_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()

        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "set_setting",
            payload = mapOf("key" to key, "value" to value),
        )
        RuntimeToolkitTelemetry.emitAck(OP_SET_SETTING, "ok", mapOf("key" to key, "value" to value))
    }

    private fun handleOpenDetail(context: Context, intent: Intent) {
        val workKey = intent.getStringExtra(EXTRA_WORK_KEY).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE).orEmpty().ifBlank { "UNKNOWN" }

        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "open_detail",
            payload = mapOf(
                "work_key" to workKey,
                "title" to title,
                "source_type" to sourceType,
            ),
        )

        context.startActivity(
            Intent(context, BrowserActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )

        RuntimeToolkitTelemetry.emitAck(
            OP_OPEN_DETAIL,
            "ok",
            mapOf("work_key" to workKey, "title" to title, "source_type" to sourceType),
        )
    }

    private fun handleInspectWork(context: Context, intent: Intent) {
        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "inspect_work",
            payload = intent.extrasToMap(),
        )
        RuntimeToolkitTelemetry.emitAck(OP_INSPECT_WORK, "ok")
    }

    private fun handleQueryEntities(context: Context, intent: Intent) {
        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "query_entities",
            payload = intent.extrasToMap(),
        )
        RuntimeToolkitTelemetry.emitAck(OP_QUERY_ENTITIES, "ok")
    }

    private fun handleSnapshotCookies(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)
        val cookieString = if (url.isNullOrBlank()) "" else CookieManager.getInstance().getCookie(url).orEmpty()
        RuntimeToolkitTelemetry.recordCookieSnapshot(context, url)
        RuntimeToolkitTelemetry.emitAck(
            OP_SNAPSHOT_COOKIES,
            "ok",
            mapOf(
                "url" to (url ?: ""),
                "cookie_length" to cookieString.length,
            ),
        )
    }

    private fun handleSetScope(context: Context, intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_SCOPE_MODE).orEmpty()
        val targetHosts = intent.getStringExtra(EXTRA_TARGET_HOST_FAMILY).orEmpty()
        if (mode.isNotBlank()) {
            RuntimeToolkitTelemetry.setScopeMode(context, mode)
        }
        if (targetHosts.isNotBlank()) {
            RuntimeToolkitTelemetry.setTargetHostFamily(context, targetHosts)
        }
        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "set_scope",
            payload = RuntimeToolkitTelemetry.runtimeSettingsSnapshot(context),
        )
        RuntimeToolkitTelemetry.emitAck(
            OP_SET_SCOPE,
            "ok",
            RuntimeToolkitTelemetry.runtimeSettingsSnapshot(context),
        )
    }

    private fun handleSetProbePhase(context: Context, intent: Intent) {
        val phaseId = intent.getStringExtra(EXTRA_PHASE_ID).orEmpty().ifBlank { "unscoped" }
        val transition = intent.getStringExtra(EXTRA_TRANSITION).orEmpty().ifBlank { "start" }
        RuntimeToolkitTelemetry.logProbePhaseEvent(
            context = context,
            phaseId = phaseId,
            transition = transition,
        )
        RuntimeToolkitTelemetry.emitAck(
            OP_SET_PROBE_PHASE,
            "ok",
            mapOf(
                "phase_id" to phaseId,
                "transition" to transition,
            ),
        )
    }

    private fun handleClearProbePhase(context: Context) {
        val previous = RuntimeToolkitTelemetry.activePhaseId(context)
        RuntimeToolkitTelemetry.logProbePhaseEvent(
            context = context,
            phaseId = previous,
            transition = "stop",
        )
        RuntimeToolkitTelemetry.clearActivePhaseId(context)
        RuntimeToolkitTelemetry.emitAck(
            OP_CLEAR_PROBE_PHASE,
            "ok",
            mapOf(
                "previous_phase_id" to previous,
                "active_phase_id" to RuntimeToolkitTelemetry.activePhaseId(context),
            ),
        )
    }

    private fun handleMarkProvenance(context: Context, intent: Intent) {
        val entityType = intent.getStringExtra(EXTRA_ENTITY_TYPE).orEmpty().ifBlank { "custom" }
        val entityKey = intent.getStringExtra(EXTRA_ENTITY_KEY).orEmpty()
        require(entityKey.isNotBlank()) { "mark_provenance requires entityKey" }
        val producedBy = intent.getStringExtra(EXTRA_PRODUCED_BY).orEmpty().ifBlank { null }
        val consumedBy = intent.getStringExtra(EXTRA_CONSUMED_BY).orEmpty().ifBlank { null }
        val derivedFrom = intent.getStringExtra(EXTRA_DERIVED_FROM).orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        RuntimeToolkitTelemetry.logProvenanceEvent(
            context = context,
            entityType = entityType,
            entityKey = entityKey,
            producedBy = producedBy,
            consumedBy = consumedBy,
            derivedFrom = derivedFrom,
            payload = mapOf("source" to "adb_receiver"),
        )
        RuntimeToolkitTelemetry.emitAck(
            OP_MARK_PROVENANCE,
            "ok",
            mapOf(
                "entity_type" to entityType,
                "entity_key" to entityKey,
                "produced_by" to producedBy,
                "consumed_by" to consumedBy,
                "derived_from_count" to derivedFrom.size,
            ),
        )
    }

    private fun handleNativeReplayRequest(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        require(url.isNotBlank()) { "native_replay_request requires url" }

        val method = intent.getStringExtra(EXTRA_METHOD).orEmpty()
            .ifBlank { MapperHttpMethod.GET.name }
            .uppercase()
        val timeoutMillis = intent.getLongExtra(EXTRA_TIMEOUT_MS, 20_000L).coerceAtLeast(1_000L)
        val redirectPolicy = when (intent.getStringExtra(EXTRA_REDIRECT_POLICY).orEmpty().lowercase()) {
            "no_follow", "no-follow", "nofollow", "off" -> MapperRedirectPolicy.NO_FOLLOW
            else -> MapperRedirectPolicy.FOLLOW
        }
        val contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE).orEmpty().ifBlank { null }
        val headers = parseHeaders(intent.getStringExtra(EXTRA_HEADERS))
        val queryParams = parseQueryParams(intent.getStringExtra(EXTRA_QUERY_PARAMS))
        val rawBody = parseBody(intent)

        val requestId = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "native_replay_${MapperNativeReplayRuntime.transportLabel(context)}",
            url = url,
            method = method,
            headers = headers,
            payload = mapOf(
                "query_param_count" to queryParams.size,
                "timeout_ms" to timeoutMillis,
                "redirect_policy" to redirectPolicy.name.lowercase(),
                "operation" to "native_replay_request",
            ),
        ).requestId

        val request = MapperNativeHttpRequest(
            url = url,
            method = if (method == MapperHttpMethod.POST.name) MapperHttpMethod.POST else MapperHttpMethod.GET,
            headers = headers,
            queryParams = queryParams,
            body = rawBody,
            contentType = contentType,
            timeoutMillis = timeoutMillis,
            redirectPolicy = redirectPolicy,
            operationTag = "runtime_toolkit_native_replay",
        )

        val response = MapperNativeReplayRuntime.execute(context, request)
        val mimeType = response.headers
            .entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }

        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "native_replay_${response.transport}",
            url = url,
            method = method,
            statusCode = response.statusCode,
            reason = response.statusText ?: response.failureType,
            mimeType = mimeType,
            headers = response.headers,
            rawBody = response.body.takeIf { it.isNotEmpty() && it.size <= 1024 * 1024 },
            requestId = requestId,
            payload = mapOf(
                "transport" to response.transport,
                "duration_ms" to response.durationMillis,
                "redirect_count" to response.redirectCount,
                "final_url" to response.finalUrl,
                "failure_type" to response.failureType,
                "failure_message" to response.failureMessage,
                "operation" to "native_replay_request",
            ),
        )

        RuntimeToolkitTelemetry.emitAck(
            OP_NATIVE_REPLAY_REQUEST,
            if (response.succeeded) "ok" else "error",
            mapOf(
                "status_code" to response.statusCode,
                "transport" to response.transport,
                "duration_ms" to response.durationMillis,
                "redirect_count" to response.redirectCount,
                "final_url" to response.finalUrl,
                "response_size" to response.body.size,
                "failure_type" to response.failureType,
            ),
        )
    }

    private fun handleResetSiteState(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        require(url.isNotBlank()) { "reset_site_state requires url" }

        BrowserUnit.clearSiteDataForUrl(url)
        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "reset_site_state",
            payload = mapOf("url" to url),
        )
        RuntimeToolkitTelemetry.emitAck(
            OP_RESET_SITE_STATE,
            "ok",
            mapOf("url" to url),
        )
    }

    private fun handleClearRuntimeEvents(context: Context) {
        val ok = RuntimeToolkitTelemetry.clearRuntimeArtifacts(context)
        RuntimeToolkitTelemetry.emitAck(OP_CLEAR_RUNTIME_EVENTS, if (ok) "ok" else "error")
    }

    private fun handleStatus(context: Context) {
        val runId = RuntimeToolkitTelemetry.ensureRunId(context)
        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "status",
            payload = mapOf(
                "run_id" to runId,
                "runtime_settings" to RuntimeToolkitTelemetry.runtimeSettingsSnapshot(context),
            ),
        )
        RuntimeToolkitTelemetry.emitAck(
            OP_STATUS,
            "ok",
            mapOf(
                "run_id" to runId,
                "runtime_settings" to RuntimeToolkitTelemetry.runtimeSettingsSnapshot(context),
            ),
        )
    }

    private fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun Intent.extrasToMap(): Map<String, Any?> {
        val extras = extras ?: return emptyMap()
        return extras.keySet().associateWith { key -> extras.get(key) }
    }

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val json = JSONObject(trimmed)
            return json.keys().asSequence().associateWith { key -> json.optString(key) }
        }
        return trimmed
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
    }

    private fun parseQueryParams(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('&')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { token ->
                val idx = token.indexOf('=')
                if (idx < 0) {
                    Uri.decode(token) to ""
                } else {
                    Uri.decode(token.substring(0, idx)) to Uri.decode(token.substring(idx + 1))
                }
            }
            .toList()
    }

    private fun parseBody(intent: Intent): ByteArray? {
        val bodyBase64 = intent.getStringExtra(EXTRA_BODY_BASE64).orEmpty()
        if (bodyBase64.isNotBlank()) {
            return Base64.decode(bodyBase64, Base64.DEFAULT)
        }
        val bodyUtf8 = intent.getStringExtra(EXTRA_BODY_UTF8).orEmpty()
        if (bodyUtf8.isNotBlank()) {
            return bodyUtf8.toByteArray(Charsets.UTF_8)
        }
        return null
    }

    companion object {
        const val ACTION_RUNTIME_TOOLKIT_COMMAND = "dev.fishit.mapper.wave01.debug.RUNTIME_TOOLKIT_COMMAND"

        const val EXTRA_OP = "op"
        const val EXTRA_RUN_ID = "runId"
        const val EXTRA_TRACE_ID = "traceId"
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_ACTION_NAME = "actionName"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_RESULT = "result"
        const val EXTRA_QUERY = "query"
        const val EXTRA_URL = "url"
        const val EXTRA_METHOD = "method"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_QUERY_PARAMS = "queryParams"
        const val EXTRA_BODY_BASE64 = "bodyBase64"
        const val EXTRA_BODY_UTF8 = "bodyUtf8"
        const val EXTRA_CONTENT_TYPE = "contentType"
        const val EXTRA_TIMEOUT_MS = "timeoutMs"
        const val EXTRA_REDIRECT_POLICY = "redirectPolicy"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
        const val EXTRA_WORK_KEY = "workKey"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SOURCE_TYPE = "sourceType"
        const val EXTRA_UI_ANCHOR_ID = "uiAnchorId"
        const val EXTRA_TAB_ID = "tabId"
        const val EXTRA_SCOPE_MODE = "scopeMode"
        const val EXTRA_TARGET_HOST_FAMILY = "targetHostFamily"
        const val EXTRA_PHASE_ID = "phaseId"
        const val EXTRA_TRANSITION = "transition"
        const val EXTRA_ENTITY_TYPE = "entityType"
        const val EXTRA_ENTITY_KEY = "entityKey"
        const val EXTRA_PRODUCED_BY = "producedBy"
        const val EXTRA_CONSUMED_BY = "consumedBy"
        const val EXTRA_DERIVED_FROM = "derivedFrom"

        private const val PREF_RUNTIME_SETTINGS = "mapper_toolkit_runtime_settings"

        const val OP_PING = "ping"
        const val OP_STATUS = "status"
        const val OP_SESSION_START = "session_start"
        const val OP_SESSION_STOP = "session_stop"
        const val OP_MARK_UI_START = "mark_ui_start"
        const val OP_MARK_UI_END = "mark_ui_end"
        const val OP_OPEN_SCREEN = "open_screen"
        const val OP_OPEN_URL = "open_url"
        const val OP_SET_SETTING = "set_setting"
        const val OP_OPEN_DETAIL = "open_detail"
        const val OP_INSPECT_WORK = "inspect_work"
        const val OP_QUERY_ENTITIES = "query_entities"
        const val OP_SNAPSHOT_COOKIES = "snapshot_cookies"
        const val OP_SET_SCOPE = "set_scope"
        const val OP_SET_PROBE_PHASE = "set_probe_phase"
        const val OP_CLEAR_PROBE_PHASE = "clear_probe_phase"
        const val OP_MARK_PROVENANCE = "mark_provenance"
        const val OP_NATIVE_REPLAY_REQUEST = "native_replay_request"
        const val OP_RESET_SITE_STATE = "reset_site_state"
        const val OP_CLEAR_RUNTIME_EVENTS = "clear_runtime_events"
    }
}
