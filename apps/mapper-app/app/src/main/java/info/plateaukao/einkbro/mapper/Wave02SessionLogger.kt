package info.plateaukao.einkbro.mapper

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Wave02SessionLogger(
    context: Context
) {
    private val sessionId = "wave02-${System.currentTimeMillis()}-${UUID.randomUUID()}"
    private val outputDir = File(context.filesDir, "mapper/wave02").apply { mkdirs() }
    private val eventLogFile = File(outputDir, "structured_log_stream.jsonl")
    private val metadataFile = File(outputDir, "session_metadata.json")
    private val startedAtUtc = utcNow()
    private val appId = context.packageName

    @Volatile
    private var finished = false

    init {
        writeMetadata(
            status = "running",
            endedAtUtc = null,
            endReason = null
        )
        logEvent(
            eventType = "session_started",
            payload = mapOf(
                "session_id" to sessionId,
                "app_id" to appId,
                "output_dir" to outputDir.absolutePath
            )
        )
    }

    @Synchronized
    fun logEvent(
        eventType: String,
        payload: Map<String, Any?> = emptyMap()
    ) {
        runCatching {
            if (!outputDir.exists()) outputDir.mkdirs()
            val event = JSONObject().apply {
                put("ts_utc", utcNow())
                put("session_id", sessionId)
                put("event_type", eventType)
                put("payload", JSONObject(payload))
            }
            eventLogFile.appendText(event.toString() + "\n")
        }
    }

    @Synchronized
    fun finish(endReason: String) {
        if (finished) return
        finished = true
        logEvent(
            eventType = "session_finished",
            payload = mapOf("reason" to endReason)
        )
        writeMetadata(
            status = "finished",
            endedAtUtc = utcNow(),
            endReason = endReason
        )
    }

    private fun writeMetadata(
        status: String,
        endedAtUtc: String?,
        endReason: String?
    ) {
        runCatching {
            if (!outputDir.exists()) outputDir.mkdirs()
            val metadata = JSONObject().apply {
                put("schema_version", 1)
                put("wave_id", "WAVE-02")
                put("session_id", sessionId)
                put("app_id", appId)
                put("started_at_utc", startedAtUtc)
                put("ended_at_utc", endedAtUtc)
                put("status", status)
                put("end_reason", endReason)
                put("outputs", JSONObject().apply {
                    put("structured_log_stream", eventLogFile.absolutePath)
                    put("session_metadata", metadataFile.absolutePath)
                })
            }
            metadataFile.writeText(metadata.toString(2))
        }
    }

    private fun utcNow(): String = java.time.Instant.now().toString()
}
