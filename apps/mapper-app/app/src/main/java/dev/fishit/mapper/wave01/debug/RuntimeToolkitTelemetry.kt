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
import java.time.Instant
import java.util.UUID

object RuntimeToolkitTelemetry {
    private const val TAG = "PIPELAB/RTK"
    private const val EVENT_PREFIX = "PIPELAB_EVT"
    private const val ACK_PREFIX = "PIPELAB_ACK"

    private const val PREF_NAME = "mapper_toolkit_v2"
    private const val PREF_RUN_ID = "run_id"
    private const val PREF_TRACE_ID = "trace_id"
    private const val PREF_ACTION_ID = "action_id"
    private const val PREF_SPAN_ID = "span_id"

    private const val PREF_COOKIE_SNAPSHOT = "mapper_toolkit_cookie_snapshot"

    private val ioLock = Any()

    data class CorrelationContext(
        val traceId: String,
        val actionId: String,
        val spanId: String,
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
            .apply()
    }

    fun setRunAndContext(context: Context, runId: String, correlation: CorrelationContext) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_RUN_ID, runId)
            .putString(PREF_TRACE_ID, correlation.traceId)
            .putString(PREF_ACTION_ID, correlation.actionId)
            .putString(PREF_SPAN_ID, correlation.spanId)
            .apply()
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
            .apply()
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
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        logEvent(
            context = context,
            eventType = "network_request_event",
            correlation = correlation,
            payload = payload + mapOf(
                "source" to source,
                "url" to url,
                "method" to method,
                "headers" to headers,
            ),
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
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val correlation = currentCorrelationContext(context)
        val eventId = UUID.randomUUID().toString()

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

        logEvent(
            context = context,
            explicitEventId = eventId,
            eventType = "network_response_event",
            correlation = correlation,
            payload = payload + mapOf(
                "source" to source,
                "url" to url,
                "method" to method,
                "status_code" to statusCode,
                "reason" to reason,
                "mime_type" to mimeType,
                "headers" to headers,
                "body_preview" to bodyPreview,
                "response_store_path" to responsePath,
            ),
        )
    }

    fun logCookieEvent(
        context: Context,
        operation: String,
        domain: String,
        cookieName: String?,
        cookieValuePreview: String?,
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
            payload = payload + mapOf("operation" to operation),
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

        if (previous == null) {
            prefs.edit().putString(host, raw).apply()
            if (raw.isNotBlank()) {
                logCookieEvent(
                    context = context,
                    operation = "snapshot_init",
                    domain = host,
                    cookieName = null,
                    cookieValuePreview = raw.take(128),
                    payload = mapOf("cookie_length" to raw.length),
                )
            }
            return
        }

        if (previous == raw) return

        val op = if (raw.isBlank()) "cookie_deleted" else "cookie_refreshed"
        logCookieEvent(
            context = context,
            operation = op,
            domain = host,
            cookieName = null,
            cookieValuePreview = raw.take(128),
            payload = mapOf(
                "previous_length" to previous.length,
                "current_length" to raw.length,
            ),
        )
        prefs.edit().putString(host, raw).apply()
    }

    fun clearRuntimeArtifacts(context: Context): Boolean {
        val root = runtimeRoot(context)
        val deleted = root.exists() && root.deleteRecursively()
        return deleted || !root.exists()
    }

    fun runtimeRoot(context: Context): File = File(context.filesDir, "runtime-toolkit")

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
