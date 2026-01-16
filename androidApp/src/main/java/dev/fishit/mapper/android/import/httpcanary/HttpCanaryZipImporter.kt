package dev.fishit.mapper.android.import.httpcanary

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imports HttpCanary "Save ZIP" exports into FishIT-Mapper's CapturedExchange format.
 *
 * HttpCanary is an external Android app that captures network traffic using VPN.
 * This importer parses the ZIP exports and normalizes them for correlation with
 * in-app user actions.
 *
 * ## Usage
 * ```kotlin
 * val importer = HttpCanaryZipImporter(context)
 * val exchanges = importer.importZip(zipUri)
 * // exchanges: List<CapturedExchange> sorted by startedAt
 * ```
 *
 * ## HttpCanary ZIP Structure
 * ```
 * export.zip/
 * ├── request.json          # Root-level request (optional, single capture)
 * ├── response.json         # Root-level response (optional)
 * ├── 1/                    # First exchange
 * │   ├── request.json
 * │   ├── response.json
 * │   ├── request_body.txt  # Optional
 * │   └── response_body.json/.txt # Optional
 * ├── 2/                    # Second exchange
 * │   └── ...
 * └── ...
 * ```
 *
 * ## Ignored Files
 * - *.hcy files (HttpCanary internal format)
 * - websocket.json / udp.json (not HTTP exchanges)
 * - Binary files (*.bin) are noted but not fully decoded
 */
class HttpCanaryZipImporter(private val context: Context) {

    companion object {
        private const val TAG = "HttpCanaryZipImporter"

        // Maximum body size to include (larger bodies are truncated)
        private const val MAX_BODY_SIZE = 1024 * 1024 // 1 MB
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Import a HttpCanary ZIP file and return normalized CapturedExchange objects.
     *
     * @param zipUri URI pointing to the HttpCanary ZIP file
     * @return Result with list of CapturedExchange sorted by startedAt, or error
     */
    suspend fun importZip(zipUri: Uri): Result<List<CapturedExchange>> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "httpcanary_import_${System.currentTimeMillis()}")

        try {
            // Extract ZIP to temp directory
            extractZip(zipUri, tempDir)

            // Parse all exchanges
            val exchanges = parseExchanges(tempDir)

            Log.i(TAG, "Imported ${exchanges.size} exchanges from HttpCanary ZIP")

            Result.success(exchanges.sortedBy { it.startedAt })

        } catch (e: Exception) {
            Log.e(TAG, "Failed to import HttpCanary ZIP", e)
            Result.failure(Exception("HttpCanary import failed: ${e.message}", e))
        } finally {
            // Clean up temp directory
            tempDir.deleteRecursively()
        }
    }

    /**
     * Extract ZIP file to target directory with path validation.
     */
    private fun extractZip(zipUri: Uri, targetDir: File) {
        targetDir.mkdirs()
        val canonicalTargetPath = targetDir.canonicalPath

        context.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // Skip .hcy files (HttpCanary internal format)
                    if (entry.name.endsWith(".hcy")) {
                        entry = zis.nextEntry
                        continue
                    }

                    val file = File(targetDir, entry.name)

                    // Prevent zip slip vulnerability
                    val canonicalFilePath = file.canonicalPath
                    if (!canonicalFilePath.startsWith(canonicalTargetPath + File.separator)) {
                        throw SecurityException("Zip entry outside target directory: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            zis.copyTo(output)
                        }
                    }

                    entry = zis.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("Cannot open ZIP file")
    }

    /**
     * Parse all exchanges from extracted directory.
     */
    private fun parseExchanges(extractedDir: File): List<CapturedExchange> {
        val exchanges = mutableListOf<CapturedExchange>()

        // Check for root-level exchange (single capture mode)
        val rootRequest = File(extractedDir, "request.json")
        val rootResponse = File(extractedDir, "response.json")
        if (rootRequest.exists()) {
            parseExchange("root", extractedDir)?.let { exchanges.add(it) }
        }

        // Parse numbered folders (1/, 2/, 3/, ...)
        extractedDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("\\d+")) }
            ?.sortedBy { it.name.toIntOrNull() ?: 0 }
            ?.forEach { folder ->
                parseExchange(folder.name, folder)?.let { exchanges.add(it) }
            }

        return exchanges
    }

    /**
     * Parse a single exchange from a folder.
     *
     * @param exchangeId The exchange identifier (folder name or "root")
     * @param folder The folder containing request.json, response.json, etc.
     * @return CapturedExchange or null if this is not an HTTP exchange (e.g., WebSocket, UDP)
     */
    private fun parseExchange(exchangeId: String, folder: File): CapturedExchange? {
        val requestFile = File(folder, "request.json")
        val responseFile = File(folder, "response.json")

        // Check if this is a WebSocket or UDP folder (skip these)
        if (File(folder, "websocket.json").exists()) {
            Log.d(TAG, "Skipping WebSocket exchange in folder: $exchangeId")
            return null
        }
        if (File(folder, "udp.json").exists()) {
            Log.d(TAG, "Skipping UDP exchange in folder: $exchangeId")
            return null
        }

        // Request is required
        if (!requestFile.exists()) {
            Log.w(TAG, "No request.json in folder: $exchangeId")
            return null
        }

        return try {
            // Parse request
            val requestJson = requestFile.readText()
            val hcRequest = json.decodeFromString<HttpCanaryRequest>(requestJson)

            // Parse response (optional)
            val hcResponse = if (responseFile.exists()) {
                try {
                    json.decodeFromString<HttpCanaryResponse>(responseFile.readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse response.json in $exchangeId", e)
                    null
                }
            } else null

            // Read request body (optional)
            val requestBody = readBody(folder, "request_body")

            // Read response body (optional)
            val responseBody = readBody(folder, "response_body")

            // Determine timestamp
            val timestampMs = hcRequest.getTimestampMs()
            val startedAt = Instant.fromEpochMilliseconds(timestampMs)

            // Build normalized exchange
            CapturedExchange(
                exchangeId = exchangeId,
                startedAt = startedAt,
                request = CapturedRequest(
                    method = hcRequest.method,
                    url = hcRequest.url,
                    headers = hcRequest.headers,
                    body = requestBody?.first,
                    bodyBinary = requestBody?.second ?: false
                ),
                response = hcResponse?.let { resp ->
                    CapturedResponse(
                        status = resp.resolveStatusCode(),
                        statusMessage = resp.resolveStatusMessage(),
                        headers = resp.headers,
                        body = responseBody?.first,
                        bodyBinary = responseBody?.second ?: false,
                        redirectLocation = resp.getRedirectLocation()
                    )
                },
                protocol = "http"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse exchange in folder: $exchangeId", e)
            null
        }
    }

    /**
     * Read body content from various possible file formats.
     *
     * @param folder The exchange folder
     * @param baseName The base name without extension (e.g., "request_body", "response_body")
     * @return Pair of (body content, isBinary) or null if no body file exists
     */
    private fun readBody(folder: File, baseName: String): Pair<String, Boolean>? {
        // Try common extensions in order of preference
        val extensions = listOf(".json", ".txt", ".xml", ".html", "")

        for (ext in extensions) {
            val file = File(folder, "$baseName$ext")
            if (file.exists() && file.isFile) {
                return try {
                    val content = if (file.length() > MAX_BODY_SIZE) {
                        // Truncate large bodies
                        file.inputStream().bufferedReader().use { reader ->
                            val buffer = CharArray(MAX_BODY_SIZE)
                            val read = reader.read(buffer)
                            String(buffer, 0, read) + "\n... [TRUNCATED]"
                        }
                    } else {
                        file.readText()
                    }
                    Pair(content, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read body file: ${file.name}", e)
                    null
                }
            }
        }

        // Check for binary files
        val binFile = File(folder, "$baseName.bin")
        if (binFile.exists()) {
            return Pair("[BINARY DATA: ${binFile.length()} bytes]", true)
        }

        return null
    }
}
