package dev.fishit.mapper.wave01.debug

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class RuntimeToolkitSourcePipelineExporterReplayFixtureTest {
    @Test
    fun exporter_replays_real_runtime_export_and_preserves_catalog_fields() {
        val fixturePath = System.getenv("MAPPER_RUNTIME_EXPORT_ZIP").orEmpty().trim()
        assumeTrue("MAPPER_RUNTIME_EXPORT_ZIP not set", fixturePath.isNotBlank())
        val fixtureZip = File(fixturePath)
        assumeTrue("fixture zip missing: ${fixtureZip.absolutePath}", fixtureZip.exists() && fixtureZip.isFile)

        val runtimeRoot = Files.createTempDirectory("rtk_replay_fixture").toFile()
        unzipInto(fixtureZip, runtimeRoot)
        val artifacts = RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
            runtimeRoot = runtimeRoot,
            targetSiteHint = "zdf.de",
        )
        val bundle = JSONObject(artifacts.sourcePipelineBundlePath.readText(Charsets.UTF_8))
        val endpointTemplates = bundle.getJSONArray("endpointTemplates")
        val endpointIds = mutableSetOf<String>()
        forEachObject(endpointTemplates) { endpoint ->
            val endpointId = endpoint.optString("endpointId")
            if (endpointId.isNotBlank()) endpointIds += endpointId
        }

        val fieldMappings = bundle.getJSONArray("fieldMappings")
        val title = findFieldMapping(fieldMappings, "title")
        val poster = findFieldMapping(fieldMappings, "poster")
        val backdrop = findFieldMapping(fieldMappings, "backdrop")
        val itemType = findFieldMapping(fieldMappings, "itemType")
        val searchMapping = findFieldMapping(fieldMappings, "searchMapping")
        val detailMapping = findFieldMapping(fieldMappings, "detailMapping")

        assertNotNull(title)
        assertNotNull(itemType)
        assertNotNull(searchMapping)
        assertNotNull(detailMapping)
        assertNotNull(poster)
        assertNotNull(backdrop)

        val titleTemplate = title!!.opt("valueTemplate")?.toString().orEmpty()
        val itemTypeTemplate = itemType!!.opt("valueTemplate")?.toString().orEmpty()
        val posterTemplate = poster!!.opt("valueTemplate")?.toString().orEmpty()
        val backdropTemplate = backdrop!!.opt("valueTemplate")?.toString().orEmpty()
        val titleSourceRef = title.optString("sourceRef")
        val itemTypeSourceRef = itemType.optString("sourceRef")

        assertTrue(titleTemplate.isNotBlank())
        assertTrue(itemTypeTemplate.isNotBlank())
        assertTrue(posterTemplate.isNotBlank())
        assertTrue(backdropTemplate.isNotBlank())
        assertFalse(titleTemplate.equals("Google", ignoreCase = true))
        assertFalse(itemTypeTemplate.equals("Bearer", ignoreCase = true))
        assertFalse(itemTypeTemplate.lowercase().contains("token_type"))
        assertFalse(posterTemplate.lowercase().contains("errors"))
        assertFalse(posterTemplate.lowercase().contains("fsk"))
        assertFalse(Regex("\\[\\d+]").containsMatchIn(titleTemplate))
        assertFalse(Regex("\\[\\d+]").containsMatchIn(itemTypeTemplate))
        assertTrue(titleSourceRef in endpointIds)
        assertTrue(itemTypeSourceRef in endpointIds)
    }

    private fun unzipInto(sourceZip: File, targetDir: File) {
        ZipFile(sourceZip).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    continue
                }
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun forEachObject(array: JSONArray, block: (JSONObject) -> Unit) {
        for (idx in 0 until array.length()) {
            val row = array.optJSONObject(idx) ?: continue
            block(row)
        }
    }

    private fun findFieldMapping(array: JSONArray, fieldName: String): JSONObject? {
        for (idx in 0 until array.length()) {
            val item = array.optJSONObject(idx) ?: continue
            if (item.optString("fieldName") == fieldName) return item
        }
        return null
    }
}

