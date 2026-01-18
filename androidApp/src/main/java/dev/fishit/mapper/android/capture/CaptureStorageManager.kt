package dev.fishit.mapper.android.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Optimierte Speicher- und Export-Verwaltung für Capture-Sessions.
 *
 * ## Features
 * - **GZIP-Kompression** → 70-90% kleinere Dateien
 * - **Auto-Save** → Alle 30s während Recording (Crash-Schutz)
 * - **Smart Filtering** → Unwichtige Requests rausfiltern
 * - **Body Limits** → Große Responses abschneiden
 * - **Quick Share** → 1-Tap Export zu beliebiger App
 * - **Cleanup** → Alte Sessions automatisch löschen
 *
 * ## Speicherstruktur
 * ```
 * fishit/
 * └── har/
 *     ├── index.json
 *     ├── 2024-01-15_amazon-api.har.gz    # Komprimiert!
 *     └── exports/                         # Temporäre unkomprimierte Exports
 *         └── amazon-api.har              # Zum Teilen
 * ```
 */
class CaptureStorageManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val harStore = HarSessionStore(context)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ==================== Configuration ====================

    /**
     * Konfiguration für Speicheroptimierung.
     */
    data class StorageConfig(
        /** Auto-Save Intervall in Sekunden (0 = deaktiviert) */
        val autoSaveIntervalSeconds: Int = 30,

        /** Maximale Response-Body-Größe in KB (0 = unbegrenzt) */
        val maxResponseBodyKb: Int = 500,

        /** GZIP-Kompression aktivieren */
        val useCompression: Boolean = true,

        /** Sessions älter als X Tage automatisch löschen (0 = nie) */
        val autoDeleteAfterDays: Int = 30,

        /** Unwichtige Requests filtern */
        val filterNoise: Boolean = true,

        /** Maximale Anzahl Sessions (0 = unbegrenzt) */
        val maxSessions: Int = 50
    )

    private val _config = MutableStateFlow(StorageConfig())
    val config: StateFlow<StorageConfig> = _config.asStateFlow()

    fun updateConfig(config: StorageConfig) {
        _config.value = config
    }

    // ==================== Auto-Save ====================

    private var autoSaveJob: kotlinx.coroutines.Job? = null

    /**
     * Startet Auto-Save für eine aktive Session.
     */
    fun startAutoSave(sessionManager: CaptureSessionManager) {
        val interval = _config.value.autoSaveIntervalSeconds
        if (interval <= 0) return

        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            while (true) {
                delay(interval * 1000L)
                sessionManager.saveCurrentSession()
            }
        }
    }

    /**
     * Stoppt Auto-Save.
     */
    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    // ==================== Smart Filtering ====================

    /**
     * Domains/Pfade die gefiltert werden sollen.
     */
    private val noisePatterns = listOf(
        // Analytics & Tracking
        Regex("google-analytics\\.com"),
        Regex("googletagmanager\\.com"),
        Regex("facebook\\.com/tr"),
        Regex("doubleclick\\.net"),
        Regex("hotjar\\.com"),
        Regex("segment\\.io"),
        Regex("mixpanel\\.com"),
        Regex("amplitude\\.com"),

        // Ad Networks
        Regex("googlesyndication\\.com"),
        Regex("adsystem\\.com"),
        Regex("adnxs\\.com"),

        // Static Assets (optional - manchmal relevant)
        // Regex("\\.woff2?$"),
        // Regex("\\.ttf$"),

        // Monitoring
        Regex("sentry\\.io"),
        Regex("bugsnag\\.com"),
        Regex("newrelic\\.com"),

        // Social Widgets
        Regex("platform\\.twitter\\.com"),
        Regex("connect\\.facebook\\.net"),
    )

    /**
     * Content-Types die wahrscheinlich nicht relevant sind.
     */
    private val ignoredContentTypes = listOf(
        "image/",
        "font/",
        "video/",
        "audio/"
    )

    /**
     * Filtert unwichtige Exchanges raus.
     */
    fun filterNoise(exchanges: List<TrafficInterceptWebView.CapturedExchange>): List<TrafficInterceptWebView.CapturedExchange> {
        if (!_config.value.filterNoise) return exchanges

        return exchanges.filter { exchange ->
            // URL-Patterns prüfen
            val isNoisyUrl = noisePatterns.any { it.containsMatchIn(exchange.url) }
            if (isNoisyUrl) return@filter false

            // Content-Type prüfen (nur für Responses)
            val contentType = exchange.responseHeaders?.get("Content-Type")
                ?: exchange.responseHeaders?.get("content-type")
            if (contentType != null) {
                val isIgnoredContent = ignoredContentTypes.any { contentType.startsWith(it) }
                if (isIgnoredContent) return@filter false
            }

            true
        }
    }

    /**
     * Kürzt große Response-Bodies.
     */
    fun trimLargeBodies(exchanges: List<TrafficInterceptWebView.CapturedExchange>): List<TrafficInterceptWebView.CapturedExchange> {
        val maxBytes = _config.value.maxResponseBodyKb * 1024
        if (maxBytes <= 0) return exchanges

        return exchanges.map { exchange ->
            val body = exchange.responseBody
            if (body != null && body.length > maxBytes) {
                exchange.copy(
                    responseBody = body.take(maxBytes) + "\n\n[... truncated at ${maxBytes / 1024}KB ...]"
                )
            } else {
                exchange
            }
        }
    }

    // ==================== Quick Share ====================

    /**
     * Teilt eine Session als HAR-Datei.
     *
     * @return Intent zum Teilen (startActivity(intent))
     */
    suspend fun shareSession(sessionId: String): Intent? {
        val harFile = harStore.getHarFile(sessionId) ?: return null

        // Für Sharing brauchen wir eine unkomprimierte Version
        val exportFile = prepareForSharing(harFile)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "HAR Export: ${harFile.nameWithoutExtension}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Teilt mehrere Sessions als ZIP.
     */
    suspend fun shareMultipleSessions(sessionIds: List<String>): Intent? {
        val files = sessionIds.mapNotNull { harStore.getHarFile(it) }
        if (files.isEmpty()) return null

        val zipFile = createZipArchive(files)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FishIT Export (${files.size} sessions)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Kopiert HAR-Inhalt in die Zwischenablage.
     */
    suspend fun copyToClipboard(sessionId: String): Boolean {
        val content = harStore.getHarContent(sessionId) ?: return false

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("HAR Export", content)
        clipboard.setPrimaryClip(clip)

        return true
    }

    // ==================== Storage Cleanup ====================

    /**
     * Führt Cleanup durch (alte Sessions, Speicherlimit).
     */
    suspend fun performCleanup() {
        val config = _config.value

        // Alte Sessions löschen
        if (config.autoDeleteAfterDays > 0) {
            deleteOldSessions(config.autoDeleteAfterDays)
        }

        // Limit einhalten
        if (config.maxSessions > 0) {
            enforceSessionLimit(config.maxSessions)
        }

        // Temporäre Export-Dateien löschen
        cleanupExportDir()
    }

    private suspend fun deleteOldSessions(maxAgeDays: Int) {
        val cutoff = Clock.System.now().toEpochMilliseconds() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        val sessions = harStore.listSessions()

        sessions.filter { it.startedAt.toEpochMilliseconds() < cutoff }
            .forEach { harStore.deleteSession(it.id) }
    }

    private suspend fun enforceSessionLimit(maxSessions: Int) {
        val sessions = harStore.listSessions()
            .sortedByDescending { it.startedAt }

        if (sessions.size > maxSessions) {
            sessions.drop(maxSessions).forEach {
                harStore.deleteSession(it.id)
            }
        }
    }

    private fun cleanupExportDir() {
        val exportDir = File(context.filesDir, "fishit/har/exports")
        exportDir.listFiles()?.forEach { it.delete() }
    }

    // ==================== Storage Info ====================

    /**
     * Detaillierte Speicher-Statistiken.
     */
    suspend fun getStorageStats(): StorageStats {
        val sessions = harStore.listSessions()
        val totalSize = File(context.filesDir, "fishit/har")
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        return StorageStats(
            sessionCount = sessions.size,
            totalSizeBytes = totalSize,
            totalSizeFormatted = formatBytes(totalSize),
            oldestSession = sessions.minByOrNull { it.startedAt }?.startedAt,
            newestSession = sessions.maxByOrNull { it.startedAt }?.startedAt,
            totalExchanges = sessions.sumOf { it.exchangeCount },
            totalActions = sessions.sumOf { it.actionCount }
        )
    }

    data class StorageStats(
        val sessionCount: Int,
        val totalSizeBytes: Long,
        val totalSizeFormatted: String,
        val oldestSession: kotlinx.datetime.Instant?,
        val newestSession: kotlinx.datetime.Instant?,
        val totalExchanges: Int,
        val totalActions: Int
    )

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    // ==================== Compression Helpers ====================

    private fun prepareForSharing(harFile: File): File {
        val exportDir = File(context.filesDir, "fishit/har/exports")
        exportDir.mkdirs()

        val exportFile = File(exportDir, harFile.name.removeSuffix(".gz"))

        // Falls komprimiert, dekomprimieren
        if (harFile.name.endsWith(".gz")) {
            java.util.zip.GZIPInputStream(harFile.inputStream()).use { gzip ->
                exportFile.outputStream().use { out ->
                    gzip.copyTo(out)
                }
            }
        } else {
            harFile.copyTo(exportFile, overwrite = true)
        }

        return exportFile
    }

    private fun createZipArchive(files: List<File>): File {
        val exportDir = File(context.filesDir, "fishit/har/exports")
        exportDir.mkdirs()

        val zipFile = File(exportDir, "fishit-export-${System.currentTimeMillis()}.zip")

        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            files.forEach { file ->
                val entry = java.util.zip.ZipEntry(file.name.removeSuffix(".gz"))
                zip.putNextEntry(entry)

                if (file.name.endsWith(".gz")) {
                    java.util.zip.GZIPInputStream(file.inputStream()).use { gzip ->
                        gzip.copyTo(zip)
                    }
                } else {
                    file.inputStream().use { it.copyTo(zip) }
                }

                zip.closeEntry()
            }
        }

        return zipFile
    }

    // ==================== Session Preprocessing ====================

    /**
     * Bereitet eine Session für optimale Speicherung vor.
     *
     * Wendet alle Optimierungen an:
     * - Noise-Filtering
     * - Body-Trimming
     * - Deduplizierung
     */
    fun optimizeSession(session: CaptureSessionManager.CaptureSession): CaptureSessionManager.CaptureSession {
        var exchanges = session.exchanges

        // 1. Noise filtern
        exchanges = filterNoise(exchanges)

        // 2. Große Bodies kürzen
        exchanges = trimLargeBodies(exchanges)

        // 3. Duplikate entfernen (gleiche URL + Method + Body)
        exchanges = deduplicateExchanges(exchanges)

        return session.copy(exchanges = exchanges)
    }

    private fun deduplicateExchanges(
        exchanges: List<TrafficInterceptWebView.CapturedExchange>
    ): List<TrafficInterceptWebView.CapturedExchange> {
        val seen = mutableSetOf<String>()
        return exchanges.filter { exchange ->
            val key = "${exchange.method}|${exchange.url}|${exchange.requestBody?.take(100)}"
            seen.add(key)
        }
    }
}
