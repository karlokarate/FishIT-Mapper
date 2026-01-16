package dev.fishit.mapper.android.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.fishit.mapper.android.data.AndroidProjectStore
import dev.fishit.mapper.android.import.httpcanary.WebsiteMap
import dev.fishit.mapper.contract.FishitJson
import dev.fishit.mapper.contract.SessionId
import dev.fishit.mapper.engine.ExportBundleBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manages export of FishIT-Mapper bundles.
 *
 * Export bundle structure:
 * ```
 * bundle.zip/
 * ├── manifest.json
 * ├── graph.json
 * ├── chains.json
 * ├── sessions/
 * │   └── <sessionId>.json
 * ├── maps/                    # WebsiteMaps (action-to-traffic correlation)
 * │   └── <sessionId>.json
 * ├── httpcanary/              # Raw HttpCanary ZIPs (optional)
 * │   └── <sessionId>.zip
 * └── README.txt
 * ```
 */
class ExportManager(
    private val context: Context,
    private val store: AndroidProjectStore
) {
    suspend fun exportProjectZip(projectId: dev.fishit.mapper.contract.ProjectId): File = withContext(Dispatchers.IO) {
        val meta = store.loadProjectMeta(projectId)
            ?: error("Project not found: ${projectId.value}")

        val graph = store.loadGraph(projectId)
        val chains = store.loadChains(projectId)
        val sessions = store.listSessions(projectId)

        // Build base bundle
        val bundle = ExportBundleBuilder.build(
            project = meta,
            graph = graph,
            chains = chains,
            sessions = sessions
        )

        val outFile = File(context.cacheDir, "fishit_${projectId.value}_${System.currentTimeMillis()}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
            // Write standard bundle files
            bundle.files.forEach { file ->
                val entry = ZipEntry(file.path)
                zos.putNextEntry(entry)
                zos.write(file.bytes)
                zos.closeEntry()
            }

            // Add WebsiteMaps
            val websiteMaps = store.listWebsiteMaps(projectId)
            for ((sessionId, map) in websiteMaps) {
                val mapJson = FishitJson.encodeToString(WebsiteMap.serializer(), map)
                val entry = ZipEntry("maps/${sessionId.value}.json")
                zos.putNextEntry(entry)
                zos.write(mapJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // Add raw HttpCanary ZIPs (if available)
            for (session in sessions) {
                val httpCanaryZip = store.getHttpCanaryZipFile(projectId, session.id)
                if (httpCanaryZip != null && httpCanaryZip.exists()) {
                    val entry = ZipEntry("httpcanary/${session.id.value}.zip")
                    zos.putNextEntry(entry)
                    zos.write(httpCanaryZip.readBytes())
                    zos.closeEntry()
                }
            }
        }
        outFile
    }

    fun shareZip(zipFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share FishIT bundle"))
    }
}
