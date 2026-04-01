package dev.fishit.mapper.wave01.debug

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.webkit.CookieManager
import info.plateaukao.einkbro.activity.BrowserActivity
import info.plateaukao.einkbro.activity.SettingActivity
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
        val correlation = RuntimeToolkitTelemetry.CorrelationContext(
            traceId = intent.getStringExtra(EXTRA_TRACE_ID) ?: UUID.randomUUID().toString(),
            actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: UUID.randomUUID().toString(),
            spanId = UUID.randomUUID().toString(),
        )

        RuntimeToolkitTelemetry.setRunAndContext(context, runId, correlation)
        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "session_start",
            payload = mapOf("run_id" to runId),
        )
        RuntimeToolkitTelemetry.emitAck(OP_SESSION_START, "ok", mapOf("run_id" to runId))
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
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
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

    private fun handleClearRuntimeEvents(context: Context) {
        val ok = RuntimeToolkitTelemetry.clearRuntimeArtifacts(context)
        RuntimeToolkitTelemetry.emitAck(OP_CLEAR_RUNTIME_EVENTS, if (ok) "ok" else "error")
    }

    private fun handleStatus(context: Context) {
        val runId = RuntimeToolkitTelemetry.ensureRunId(context)
        RuntimeToolkitTelemetry.logExtractionEvent(
            context = context,
            operation = "status",
            payload = mapOf("run_id" to runId),
        )
        RuntimeToolkitTelemetry.emitAck(OP_STATUS, "ok", mapOf("run_id" to runId))
    }

    private fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun Intent.extrasToMap(): Map<String, Any?> {
        val extras = extras ?: return emptyMap()
        return extras.keySet().associateWith { key -> extras.get(key) }
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
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
        const val EXTRA_WORK_KEY = "workKey"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SOURCE_TYPE = "sourceType"
        const val EXTRA_UI_ANCHOR_ID = "uiAnchorId"
        const val EXTRA_TAB_ID = "tabId"

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
        const val OP_CLEAR_RUNTIME_EVENTS = "clear_runtime_events"
    }
}
