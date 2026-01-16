package dev.fishit.mapper.android.import.httpcanary

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.fishit.mapper.android.data.AndroidProjectStore
import dev.fishit.mapper.contract.ProjectId
import dev.fishit.mapper.contract.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages importing HttpCanary ZIP exports and correlating them with recording sessions.
 *
 * ## Workflow
 * 1. User records a session in FishIT-Mapper (captures user actions, navigations)
 * 2. HttpCanary (running in background) captures all network traffic
 * 3. User exports HttpCanary traffic as ZIP
 * 4. User imports the ZIP here, associating it with a session
 * 5. This manager:
 *    - Parses the HttpCanary ZIP
 *    - Correlates exchanges with user actions by timestamp
 *    - Generates a WebsiteMap
 *    - Saves everything for export
 *
 * ## Usage
 * ```kotlin
 * val manager = HttpCanaryImportManager(context, store)
 * val result = manager.importAndCorrelate(
 *     projectId = projectId,
 *     sessionId = sessionId,
 *     httpCanaryZipUri = uri
 * )
 * result.onSuccess { websiteMap ->
 *     // WebsiteMap is saved and can be viewed/exported
 * }
 * ```
 */
class HttpCanaryImportManager(
    private val context: Context,
    private val store: AndroidProjectStore
) {
    companion object {
        private const val TAG = "HttpCanaryImportManager"
    }

    private val zipImporter = HttpCanaryZipImporter(context)
    private val mapBuilder = WebsiteMapBuilder()

    /**
     * Import an HttpCanary ZIP and correlate it with a recording session.
     *
     * @param projectId The project containing the session
     * @param sessionId The recording session to correlate with
     * @param httpCanaryZipUri URI pointing to the HttpCanary ZIP file
     * @return Result with the generated WebsiteMap or error
     */
    suspend fun importAndCorrelate(
        projectId: ProjectId,
        sessionId: SessionId,
        httpCanaryZipUri: Uri
    ): Result<WebsiteMap> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting HttpCanary import for session: ${sessionId.value}")

            // Load the recording session
            val session = store.loadSession(projectId, sessionId)
                ?: return@withContext Result.failure(Exception("Session not found: ${sessionId.value}"))

            // Import the HttpCanary ZIP
            val exchangesResult = zipImporter.importZip(httpCanaryZipUri)
            val exchanges = exchangesResult.getOrElse { error ->
                return@withContext Result.failure(error)
            }

            Log.i(TAG, "Imported ${exchanges.size} exchanges from HttpCanary")

            // Save raw ZIP for re-export
            context.contentResolver.openInputStream(httpCanaryZipUri)?.use { input ->
                store.saveHttpCanaryZip(projectId, sessionId, input.readBytes())
            }

            // Save parsed exchanges
            store.saveExchanges(projectId, sessionId, exchanges)

            // Build correlation map
            val websiteMap = mapBuilder.build(
                sessionId = sessionId.value,
                events = session.events,
                exchanges = exchanges
            )

            Log.i(TAG, "Generated WebsiteMap with ${websiteMap.actions.size} correlated actions")
            Log.i(TAG, "  - Correlated exchanges: ${websiteMap.correlatedExchanges}")
            Log.i(TAG, "  - Uncorrelated exchanges: ${websiteMap.uncorrelatedExchanges}")

            // Save the WebsiteMap
            store.saveWebsiteMap(projectId, sessionId, websiteMap)

            Result.success(websiteMap)

        } catch (e: Exception) {
            Log.e(TAG, "HttpCanary import failed", e)
            Result.failure(Exception("HttpCanary import failed: ${e.message}", e))
        }
    }

    /**
     * Re-correlate exchanges with a session using updated settings.
     * Useful if the correlation algorithm is improved or user wants to adjust windows.
     *
     * @param projectId The project containing the session
     * @param sessionId The recording session to correlate with
     * @return Result with the regenerated WebsiteMap or error
     */
    suspend fun recorrelate(
        projectId: ProjectId,
        sessionId: SessionId
    ): Result<WebsiteMap> = withContext(Dispatchers.IO) {
        try {
            // Load session
            val session = store.loadSession(projectId, sessionId)
                ?: return@withContext Result.failure(Exception("Session not found: ${sessionId.value}"))

            // Load saved exchanges
            val exchanges = store.loadExchanges(projectId, sessionId)
            if (exchanges.isEmpty()) {
                return@withContext Result.failure(Exception("No exchanges found for session: ${sessionId.value}"))
            }

            // Rebuild correlation map
            val websiteMap = mapBuilder.build(
                sessionId = sessionId.value,
                events = session.events,
                exchanges = exchanges
            )

            // Save updated WebsiteMap
            store.saveWebsiteMap(projectId, sessionId, websiteMap)

            Result.success(websiteMap)

        } catch (e: Exception) {
            Log.e(TAG, "Re-correlation failed", e)
            Result.failure(Exception("Re-correlation failed: ${e.message}", e))
        }
    }

    /**
     * Check if a session has HttpCanary data imported.
     */
    suspend fun hasImportedData(projectId: ProjectId, sessionId: SessionId): Boolean {
        return store.loadWebsiteMap(projectId, sessionId) != null
    }

    /**
     * Get import statistics for a session.
     */
    suspend fun getImportStats(projectId: ProjectId, sessionId: SessionId): ImportStats? {
        val websiteMap = store.loadWebsiteMap(projectId, sessionId) ?: return null
        val exchanges = store.loadExchanges(projectId, sessionId)

        return ImportStats(
            sessionId = sessionId.value,
            totalExchanges = exchanges.size,
            correlatedExchanges = websiteMap.correlatedExchanges,
            uncorrelatedExchanges = websiteMap.uncorrelatedExchanges,
            totalActions = websiteMap.actions.size,
            redirectChains = websiteMap.actions.sumOf { it.redirectChains.size }
        )
    }
}

/**
 * Statistics about an HttpCanary import.
 */
data class ImportStats(
    val sessionId: String,
    val totalExchanges: Int,
    val correlatedExchanges: Int,
    val uncorrelatedExchanges: Int,
    val totalActions: Int,
    val redirectChains: Int
)
