package dev.fishit.mapper.wave01.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeToolkitTelemetryReplayBundleTest {

    @Test
    fun runtime_export_filter_keeps_only_minimal_contract_files() {
        assertTrue(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("source_pipeline_bundle.json"))
        assertTrue(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("site_runtime_model.json"))
        assertTrue(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("manifest.json"))
        assertTrue(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("exports/source_plugin_bundle.zip"))
        assertTrue(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("pipeline_ready_report.json"))
        assertTrue(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("mission_export_summary.json"))

        assertFalse(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("events/runtime_events.jsonl"))
        assertFalse(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("response_store/resp_1.bin"))
        assertFalse(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("replay_bundle.json"))
        assertFalse(RuntimeToolkitTelemetry.shouldIncludeInRuntimeExport("replay_seed.json"))
    }

    @Test
    fun replay_readiness_blocks_bundle_with_missing_required_entries() {
        val zip = createZip(
            "replay_bundle_missing.zip",
            mapOf(
                "replay_bundle.json" to """{"replay_seed":{"steps":[]}}""",
            ),
        )
        val readiness = RuntimeToolkitTelemetry.evaluateExportBundleReplayReadiness(zip.absolutePath)
        assertFalse(readiness.ready)
        assertTrue(readiness.missingRequiredEntries.contains("fixture_manifest.json"))
        assertTrue(readiness.missingRequiredEntries.contains("response_index.json"))
        assertTrue(readiness.missingRequiredEntries.contains("events/runtime_events.jsonl"))
    }

    @Test
    fun replay_readiness_accepts_bundle_with_required_entries_and_get_step() {
        val replayBundle = """
            {
              "schema_version": 1,
              "replay_seed": {
                "steps": [
                  {
                    "request_id": "req_1",
                    "url": "https://www.zdf.de/api/search?q=test",
                    "method": "GET",
                    "headers": {"accept": "application/json"},
                    "query_params": {"q": "test"}
                  }
                ]
              }
            }
        """.trimIndent()
        val zip = createZip(
            "replay_bundle_ready.zip",
            mapOf(
                "replay_bundle.json" to replayBundle,
                "fixture_manifest.json" to """{"schema_version":1}""",
                "response_index.json" to """{"entries":[]}""",
                "events/runtime_events.jsonl" to """{"event_type":"network_request_event"}""",
            ),
        )
        val readiness = RuntimeToolkitTelemetry.evaluateExportBundleReplayReadiness(zip.absolutePath)
        assertTrue(readiness.ready)
        assertEquals(0, readiness.missingRequiredEntries.size)
        assertEquals(1, readiness.replayStepCount)
        assertEquals(1, readiness.runnableStepCount)
    }

    private fun createZip(name: String, entries: Map<String, String>): File {
        val file = File.createTempFile(name, ".zip")
        file.deleteOnExit()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
