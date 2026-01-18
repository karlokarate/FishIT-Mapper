package dev.fishit.mapper.android.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import dev.fishit.mapper.android.capture.CaptureSessionManager
import dev.fishit.mapper.android.capture.HarSessionStore
import dev.fishit.mapper.engine.api.*
import dev.fishit.mapper.engine.export.ApiExporter
import dev.fishit.mapper.engine.export.ExportOrchestrator
import dev.fishit.mapper.engine.export.HarExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verwaltet alle Export-Operationen für Sessions.
 *
 * ## Features
 * - Export in benutzerdefinierten Ordner (SAF)
 * - Direktes Teilen mit anderen Apps
 * - Multiple Export-Formate (HAR, JSON, OpenAPI, Postman, cURL)
 * - ZIP-Bundle für Codespace/Copilot
 *
 * ## Verwendung
 * ```kotlin
 * val exportManager = SessionExportManager(context)
 *
 * // Teilen
 * exportManager.shareSession(session, ExportFormat.HAR)
 *
 * // In Ordner speichern (mit SAF)
 * exportManager.exportToFolder(session, folderUri, ExportFormat.HAR)
 *
 * // Alle Formate als ZIP
 * exportManager.exportAsZipBundle(session, folderUri)
 * ```
 */
class SessionExportManager(private val context: Context) {

    private val harStore = HarSessionStore(context)
    private val apiExporter = ApiExporter()
    private val harExporter = HarExporter()

    /**
     * Export-Formate
     */
    enum class ExportFormat(
        val extension: String, 
        val mimeType: String, 
        val displayName: String,
        val description: String
    ) {
        HAR(".har", "application/json", "HAR", "HTTP Archive Format für Browser DevTools"),
        JSON(".json", "application/json", "JSON", "FishIT Session-Format"),
        OPENAPI(".json", "application/json", "OpenAPI", "OpenAPI 3.0 Spezifikation"),
        POSTMAN(".json", "application/json", "Postman", "Postman Collection v2.1"),
        CURL(".sh", "text/x-shellscript", "cURL", "Shell-Script mit cURL Befehlen"),
        TYPESCRIPT(".ts", "text/typescript", "TypeScript", "TypeScript API Client"),
        MARKDOWN(".md", "text/markdown", "Markdown", "Markdown Dokumentation"),
        ZIP(".zip", "application/zip", "ZIP Bundle", "Alle Formate in einem ZIP-Archiv")
    }

    /**
     * Exportiert eine Session und teilt sie direkt mit anderen Apps.
     */
    suspend fun shareSession(
        session: CaptureSessionManager.CaptureSession,
        format: ExportFormat = ExportFormat.HAR
    ) = withContext(Dispatchers.IO) {
        val tempFile = createTempExportFile(session, format)

        withContext(Dispatchers.Main) {
            shareFile(tempFile, format.mimeType, "Export: ${session.name}")
        }
    }

    /**
     * Exportiert mehrere Formate und teilt als ZIP.
     */
    suspend fun shareAsZipBundle(session: CaptureSessionManager.CaptureSession) = withContext(Dispatchers.IO) {
        val zipFile = createZipBundle(session)

        withContext(Dispatchers.Main) {
            shareFile(zipFile, "application/zip", "FishIT Export: ${session.name}")
        }
    }

    /**
     * Exportiert in einen benutzerdefinierten Ordner (via SAF DocumentFile).
     */
    suspend fun exportToFolder(
        session: CaptureSessionManager.CaptureSession,
        folderUri: Uri,
        format: ExportFormat
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val content = generateExportContent(session, format)
            val fileName = generateFileName(session, format)

            val resolver = context.contentResolver
            val docUri = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
                ?: return@withContext Result.failure(Exception("Ordner nicht zugänglich"))

            // Existierende Datei löschen falls vorhanden
            docUri.findFile(fileName)?.delete()

            // Neue Datei erstellen
            val newFile = docUri.createFile(format.mimeType, fileName)
                ?: return@withContext Result.failure(Exception("Datei konnte nicht erstellt werden"))

            resolver.openOutputStream(newFile.uri)?.use { output ->
                if (format == ExportFormat.ZIP) {
                    createZipContent(session, output)
                } else {
                    output.write(content.toByteArray())
                }
            }

            Result.success(newFile.uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Exportiert alle Formate in einen Ordner.
     */
    suspend fun exportAllFormatsToFolder(
        session: CaptureSessionManager.CaptureSession,
        folderUri: Uri
    ): Result<List<Uri>> = withContext(Dispatchers.IO) {
        val exportedUris = mutableListOf<Uri>()
        val formats = listOf(ExportFormat.HAR, ExportFormat.JSON, ExportFormat.OPENAPI, ExportFormat.POSTMAN, ExportFormat.CURL)

        for (format in formats) {
            val result = exportToFolder(session, folderUri, format)
            if (result.isSuccess) {
                result.getOrNull()?.let { exportedUris.add(it) }
            }
        }

        if (exportedUris.isEmpty()) {
            Result.failure(Exception("Kein Export erfolgreich"))
        } else {
            Result.success(exportedUris)
        }
    }

    /**
     * Speichert direkt in einen lokalen Pfad (für programmatische Nutzung).
     */
    suspend fun exportToPath(
        session: CaptureSessionManager.CaptureSession,
        targetDir: File,
        format: ExportFormat
    ): File = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val fileName = generateFileName(session, format)
        val targetFile = File(targetDir, fileName)

        if (format == ExportFormat.ZIP) {
            ZipOutputStream(FileOutputStream(targetFile)).use { zip ->
                createZipContent(session, zip)
            }
        } else {
            targetFile.writeText(generateExportContent(session, format))
        }

        targetFile
    }

    // ==================== Private Helpers ====================

    private fun generateFileName(session: CaptureSessionManager.CaptureSession, format: ExportFormat): String {
        val safeName = session.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .take(40)
        val date = session.startedAt.toString().take(10)
        return "${date}_$safeName${format.extension}"
    }

    private suspend fun generateExportContent(
        session: CaptureSessionManager.CaptureSession,
        format: ExportFormat
    ): String {
        // Konvertiere Session-Exchanges zu Engine-Exchanges
        val engineExchanges = session.exchanges.map { exchange ->
            EngineExchange(
                exchangeId = exchange.id,
                startedAt = exchange.startedAt,
                completedAt = exchange.completedAt,
                request = EngineRequest(
                    method = exchange.method,
                    url = exchange.url,
                    headers = exchange.requestHeaders,
                    body = exchange.requestBody
                ),
                response = exchange.responseStatus?.let { status ->
                    EngineResponse(
                        status = status,
                        headers = exchange.responseHeaders ?: emptyMap(),
                        body = exchange.responseBody
                    )
                }
            )
        }

        return when (format) {
            ExportFormat.HAR -> harExporter.export(engineExchanges, session.name)
            ExportFormat.JSON -> generateJsonExport(session)
            ExportFormat.OPENAPI -> generateOpenApiExport(session, engineExchanges)
            ExportFormat.POSTMAN -> generatePostmanExport(session, engineExchanges)
            ExportFormat.CURL -> generateCurlExport(session, engineExchanges)
            ExportFormat.TYPESCRIPT -> generateTypeScriptExport(session, engineExchanges)
            ExportFormat.MARKDOWN -> generateMarkdownExport(session, engineExchanges)
            ExportFormat.ZIP -> "" // ZIP wird separat behandelt
        }
    }

    private fun generateJsonExport(session: CaptureSessionManager.CaptureSession): String {
        return buildString {
            appendLine("{")
            appendLine("""  "sessionId": "${session.id}",""")
            appendLine("""  "name": "${session.name}",""")
            appendLine("""  "targetUrl": "${session.targetUrl ?: ""}",""")
            appendLine("""  "startedAt": "${session.startedAt}",""")
            appendLine("""  "stoppedAt": "${session.stoppedAt ?: ""}",""")
            appendLine("""  "exchangeCount": ${session.exchanges.size},""")
            appendLine("""  "actionCount": ${session.userActions.size},""")
            appendLine("""  "exchanges": [""")

            session.exchanges.forEachIndexed { index, exchange ->
                appendLine("    {")
                appendLine("""      "method": "${exchange.method}",""")
                appendLine("""      "url": "${exchange.url.replace("\"", "\\\"")}",""")
                appendLine("""      "status": ${exchange.responseStatus ?: 0},""")
                appendLine("""      "timestamp": "${exchange.startedAt}"""")
                append("    }")
                if (index < session.exchanges.size - 1) appendLine(",") else appendLine()
            }

            appendLine("  ],")
            appendLine("""  "userActions": [""")

            session.userActions.forEachIndexed { index, action ->
                appendLine("    {")
                appendLine("""      "type": "${action.type}",""")
                appendLine("""      "target": "${action.target.replace("\"", "\\\"").take(100)}",""")
                appendLine("""      "timestamp": "${action.timestamp}"""")
                append("    }")
                if (index < session.userActions.size - 1) appendLine(",") else appendLine()
            }

            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun generateOpenApiExport(
        session: CaptureSessionManager.CaptureSession,
        exchanges: List<EngineExchange>
    ): String {
        // Erstelle Blueprint aus Exchanges
        val blueprint = createBlueprintFromExchanges(session, exchanges)
        return apiExporter.toOpenApi(blueprint)
    }

    private fun generatePostmanExport(
        session: CaptureSessionManager.CaptureSession,
        exchanges: List<EngineExchange>
    ): String {
        val blueprint = createBlueprintFromExchanges(session, exchanges)
        return apiExporter.toPostman(blueprint)
    }

    private fun generateCurlExport(
        session: CaptureSessionManager.CaptureSession,
        exchanges: List<EngineExchange>
    ): String {
        val blueprint = createBlueprintFromExchanges(session, exchanges)
        return apiExporter.toCurl(blueprint)
    }

    private fun generateTypeScriptExport(
        session: CaptureSessionManager.CaptureSession,
        exchanges: List<EngineExchange>
    ): String {
        val blueprint = createBlueprintFromExchanges(session, exchanges)
        return apiExporter.toTypeScript(blueprint)
    }

    private fun generateMarkdownExport(
        session: CaptureSessionManager.CaptureSession,
        exchanges: List<EngineExchange>
    ): String {
        return buildString {
            appendLine("# FishIT Session Export: ${session.name}")
            appendLine()
            appendLine("## Übersicht")
            appendLine("- **Target URL**: ${session.targetUrl ?: "N/A"}")
            appendLine("- **Gestartet**: ${session.startedAt}")
            appendLine("- **Beendet**: ${session.stoppedAt ?: "N/A"}")
            appendLine("- **Exchanges**: ${session.exchanges.size}")
            appendLine("- **User Actions**: ${session.userActions.size}")
            appendLine()

            appendLine("## HTTP Exchanges")
            appendLine()

            // Gruppiere nach Host
            val byHost = exchanges.groupBy { it.request.host }
            byHost.forEach { (host, hostExchanges) ->
                appendLine("### $host")
                appendLine()
                hostExchanges.forEach { exchange ->
                    val status = exchange.response?.status ?: 0
                    val statusEmoji = when {
                        status in 200..299 -> "✅"
                        status in 300..399 -> "↪️"
                        status in 400..499 -> "⚠️"
                        status >= 500 -> "❌"
                        else -> "❓"
                    }
                    appendLine("- $statusEmoji `${exchange.request.method}` ${exchange.request.path} → $status")
                }
                appendLine()
            }

            appendLine("## User Actions Timeline")
            appendLine()
            session.userActions.forEachIndexed { index, action ->
                appendLine("${index + 1}. **${action.type}** - ${action.target.take(60)}")
            }
        }
    }

    private fun createBlueprintFromExchanges(
        session: CaptureSessionManager.CaptureSession,
        exchanges: List<EngineExchange>
    ): ApiBlueprint {
        val now = kotlinx.datetime.Clock.System.now()

        // Gruppiere nach Endpoint und konvertiere zu Liste
        val grouped = exchanges.groupBy { ex -> "${ex.request.method} ${ex.request.path}" }
        val endpointsList = grouped.entries.mapIndexed { index, entry ->
            val exs = entry.value
            val first = exs.first()
            val method = try {
                HttpMethod.valueOf(first.request.method.uppercase())
            } catch (e: Exception) {
                HttpMethod.GET
            }

            ApiEndpoint(
                id = "ep_$index",
                method = method,
                pathTemplate = first.request.path,
                pathParameters = emptyList(),
                queryParameters = emptyList(),
                headerParameters = emptyList(),
                requestBody = first.request.body?.let { bodyContent ->
                    RequestBodySpec(
                        contentType = first.request.headers?.get("Content-Type") ?: "application/json",
                        schema = null,
                        examples = listOf(BodyExample(
                            name = "request_example",
                            value = bodyContent.take(1000),
                            exchangeId = first.exchangeId
                        ))
                    )
                },
                responses = listOfNotNull(first.response?.let { resp ->
                    ResponseSpec(
                        statusCode = resp.status,
                        contentType = resp.headers?.get("Content-Type") ?: "application/json",
                        schema = null,
                        examples = listOfNotNull(resp.body?.let { bodyContent ->
                            BodyExample(
                                name = "response_example",
                                value = bodyContent.take(1000),
                                exchangeId = first.exchangeId
                            )
                        })
                    )
                }),
                authRequired = AuthType.None,
                examples = exs.map { ex -> ex.exchangeId },
                metadata = EndpointMetadata(
                    hitCount = exs.size,
                    firstSeen = exs.minOf { ex -> ex.startedAt },
                    lastSeen = exs.maxOf { ex -> ex.startedAt },
                    avgResponseTimeMs = null,
                    successRate = null,
                    description = null
                ),
                tags = emptyList()
            )
        }

        return ApiBlueprint(
            id = session.id,
            projectId = "export",
            name = session.name,
            description = "Exported from FishIT session",
            baseUrl = session.targetUrl ?: exchanges.firstOrNull()?.request?.let { req ->
                "${if (req.url.startsWith("https")) "https" else "http"}://${req.host}"
            } ?: "",
            endpoints = endpointsList,
            authPatterns = emptyList(),
            flows = emptyList(),
            metadata = BlueprintMetadata(
                totalExchangesAnalyzed = exchanges.size,
                uniqueEndpointsDetected = endpointsList.size,
                authPatternsDetected = 0,
                flowsDetected = 0,
                coveragePercent = 100f,
                generatedBy = "FishIT-Mapper Export"
            ),
            createdAt = session.startedAt,
            updatedAt = now
        )
    }

    private suspend fun createTempExportFile(
        session: CaptureSessionManager.CaptureSession,
        format: ExportFormat
    ): File {
        val cacheDir = File(context.cacheDir, "exports")
        cacheDir.mkdirs()

        val fileName = generateFileName(session, format)
        val tempFile = File(cacheDir, fileName)

        if (format == ExportFormat.ZIP) {
            ZipOutputStream(FileOutputStream(tempFile)).use { zip ->
                createZipContent(session, zip)
            }
        } else {
            tempFile.writeText(generateExportContent(session, format))
        }

        return tempFile
    }

    private suspend fun createZipBundle(session: CaptureSessionManager.CaptureSession): File {
        val cacheDir = File(context.cacheDir, "exports")
        cacheDir.mkdirs()

        val fileName = generateFileName(session, ExportFormat.ZIP)
        val zipFile = File(cacheDir, fileName)

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            createZipContent(session, zip)
        }

        return zipFile
    }

    private suspend fun createZipContent(
        session: CaptureSessionManager.CaptureSession,
        outputStream: java.io.OutputStream
    ) {
        val zip = if (outputStream is ZipOutputStream) outputStream
                  else ZipOutputStream(outputStream)

        val formats = listOf(
            ExportFormat.HAR to "traffic.har",
            ExportFormat.JSON to "session.json",
            ExportFormat.OPENAPI to "openapi.json",
            ExportFormat.POSTMAN to "postman_collection.json",
            ExportFormat.CURL to "curl_commands.sh",
            ExportFormat.MARKDOWN to "README.md"
        )

        for ((format, fileName) in formats) {
            try {
                val content = generateExportContent(session, format)
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(content.toByteArray())
                zip.closeEntry()
            } catch (e: Exception) {
                // Skip failed format
            }
        }

        if (outputStream !is ZipOutputStream) {
            zip.close()
        }
    }

    private fun shareFile(file: File, mimeType: String, title: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Export teilen")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Fehler beim Teilen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /**
         * Verfügbare Export-Formate für UI-Anzeige.
         */
        val availableFormats = listOf(
            ExportFormat.HAR,
            ExportFormat.JSON,
            ExportFormat.OPENAPI,
            ExportFormat.POSTMAN,
            ExportFormat.CURL,
            ExportFormat.TYPESCRIPT,
            ExportFormat.MARKDOWN,
            ExportFormat.ZIP
        )
    }
}
