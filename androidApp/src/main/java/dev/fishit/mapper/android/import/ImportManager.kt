package dev.fishit.mapper.android.import

import android.content.Context
import android.net.Uri
import dev.fishit.mapper.android.data.AndroidProjectStore
import dev.fishit.mapper.android.import.httpcanary.WebsiteMap
import dev.fishit.mapper.contract.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Manages importing FishIT-Mapper export bundles (ZIP files).
 *
 * Import process:
 * 1. Extract ZIP to temporary directory
 * 2. Read and validate manifest.json
 * 3. Load graph.json
 * 4. Load chains.json
 * 5. Load all session files
 * 6. Load WebsiteMaps from maps/ directory
 * 7. Load raw HttpCanary ZIPs from httpcanary/ directory
 * 8. Merge data into existing project or create new one
 * 9. Clean up temporary files
 *
 * Bundle structure:
 * ```
 * bundle.zip/
 * ├── manifest.json
 * ├── graph.json
 * ├── chains.json
 * ├── sessions/
 * │   └── <sessionId>.json
 * ├── maps/                    # WebsiteMaps (action-to-traffic correlation)
 * │   └── <sessionId>.json
 * ├── httpcanary/              # Raw HttpCanary ZIPs
 * │   └── <sessionId>.zip
 * └── README.txt
 * ```
 */
class ImportManager(
    private val context: Context,
    private val store: AndroidProjectStore
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Import a FishIT-Mapper bundle from a ZIP file URI.
     *
     * @param zipUri URI pointing to the ZIP file to import
     * @return Result with the imported ProjectId or error message
     */
    suspend fun importBundle(zipUri: Uri): Result<ProjectId> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "import_${System.currentTimeMillis()}")

        try {
            // Extract ZIP to temp directory
            extractZip(zipUri, tempDir)

            // Read manifest
            val manifestFile = File(tempDir, "manifest.json")
            if (!manifestFile.exists()) {
                return@withContext Result.failure(Exception("Invalid bundle: manifest.json not found"))
            }

            val manifest = json.decodeFromString<ExportManifest>(manifestFile.readText())

            // Validate bundle format version
            if (manifest.bundleFormatVersion != ContractInfo.BUNDLE_FORMAT_VERSION) {
                return@withContext Result.failure(
                    Exception("Bundle format version mismatch: expected ${ContractInfo.BUNDLE_FORMAT_VERSION}, got ${manifest.bundleFormatVersion}")
                )
            }

            // Read graph
            val graphFile = File(tempDir, "graph.json")
            if (!graphFile.exists()) {
                return@withContext Result.failure(Exception("Invalid bundle: graph.json not found"))
            }
            val importedGraph = json.decodeFromString<MapGraph>(graphFile.readText())

            // Read chains
            val chainsFile = File(tempDir, "chains.json")
            val importedChains = if (chainsFile.exists()) {
                json.decodeFromString<ChainsFile>(chainsFile.readText())
            } else {
                ChainsFile(chains = emptyList())
            }

            // Read sessions
            val sessionsDir = File(tempDir, "sessions")
            val importedSessions = if (sessionsDir.exists() && sessionsDir.isDirectory) {
                sessionsDir.listFiles()?.mapNotNull { sessionFile ->
                    try {
                        json.decodeFromString<RecordingSession>(sessionFile.readText())
                    } catch (e: Exception) {
                        null // Skip invalid session files
                    }
                } ?: emptyList()
            } else {
                emptyList()
            }

            // Read WebsiteMaps
            val mapsDir = File(tempDir, "maps")
            val importedMaps = if (mapsDir.exists() && mapsDir.isDirectory) {
                mapsDir.listFiles()?.mapNotNull { mapFile ->
                    try {
                        val sessionId = SessionId(mapFile.nameWithoutExtension)
                        val map = json.decodeFromString<WebsiteMap>(mapFile.readText())
                        sessionId to map
                    } catch (e: Exception) {
                        null // Skip invalid map files
                    }
                } ?: emptyList()
            } else {
                emptyList()
            }

            // Read HttpCanary ZIPs
            val httpcanaryDir = File(tempDir, "httpcanary")
            val httpcanaryZips = if (httpcanaryDir.exists() && httpcanaryDir.isDirectory) {
                httpcanaryDir.listFiles()?.filter { it.name.endsWith(".zip") }?.map { zipFile ->
                    val sessionId = SessionId(zipFile.nameWithoutExtension)
                    sessionId to zipFile.readBytes()
                } ?: emptyList()
            } else {
                emptyList()
            }

            // Get project ID from manifest
            val projectId = manifest.project.id

            // Check if project already exists
            val existingMeta = try {
                store.loadProjectMeta(projectId)
            } catch (e: Exception) {
                null
            }

            if (existingMeta != null) {
                // Merge into existing project
                mergeIntoExistingProject(
                    projectId = projectId,
                    importedGraph = importedGraph,
                    importedChains = importedChains,
                    importedSessions = importedSessions,
                    importedMaps = importedMaps,
                    importedHttpCanaryZips = httpcanaryZips
                )
            } else {
                // Create new project from import
                createProjectFromImport(
                    manifest = manifest,
                    graph = importedGraph,
                    chains = importedChains,
                    sessions = importedSessions,
                    maps = importedMaps,
                    httpcanaryZips = httpcanaryZips
                )
            }

            Result.success(projectId)

        } catch (e: Exception) {
            Result.failure(Exception("Import failed: ${e.message}", e))
        } finally {
            // Clean up temp directory
            tempDir.deleteRecursively()
        }
    }

    /**
     * Extract a ZIP file to a target directory.
     * Validates paths to prevent zip slip attacks.
     */
    private fun extractZip(zipUri: Uri, targetDir: File) {
        targetDir.mkdirs()
        val canonicalTargetPath = targetDir.canonicalPath

        context.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(targetDir, entry.name)

                    // Prevent zip slip vulnerability by validating the canonical path
                    val canonicalFilePath = file.canonicalPath
                    if (!canonicalFilePath.startsWith(canonicalTargetPath + File.separator)) {
                        throw SecurityException("Zip entry is outside of target directory: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            zis.copyTo(output)
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: throw Exception("Failed to open ZIP file")
    }

    /**
     * Create a new project from imported data.
     */
    private suspend fun createProjectFromImport(
        manifest: ExportManifest,
        graph: MapGraph,
        chains: ChainsFile,
        sessions: List<RecordingSession>,
        maps: List<Pair<SessionId, WebsiteMap>> = emptyList(),
        httpcanaryZips: List<Pair<SessionId, ByteArray>> = emptyList()
    ) {
        val projectId = manifest.project.id

        // Save project metadata
        store.saveProjectMeta(manifest.project)

        // Save graph
        store.saveGraph(projectId, graph)

        // Save chains
        store.saveChains(projectId, chains)

        // Save all sessions
        sessions.forEach { session ->
            store.saveSession(projectId, session)
        }

        // Save WebsiteMaps
        maps.forEach { (sessionId, map) ->
            store.saveWebsiteMap(projectId, sessionId, map)
        }

        // Save HttpCanary ZIPs
        httpcanaryZips.forEach { (sessionId, zipBytes) ->
            store.saveHttpCanaryZip(projectId, sessionId, zipBytes)
        }
    }

    /**
     * Merge imported data into an existing project.
     *
     * Graph merge strategy:
     * - Nodes: Keep the newest version of each node (by lastSeenAt)
     * - Edges: Keep unique edges (no duplicates)
     *
     * Chains merge strategy:
     * - Append new chains, skip duplicates by ID
     *
     * Sessions merge strategy:
     * - Append new sessions, skip duplicates by ID
     *
     * Maps/HttpCanary merge strategy:
     * - Overwrite existing maps with imported ones (newer data wins)
     */
    private suspend fun mergeIntoExistingProject(
        projectId: ProjectId,
        importedGraph: MapGraph,
        importedChains: ChainsFile,
        importedSessions: List<RecordingSession>,
        importedMaps: List<Pair<SessionId, WebsiteMap>> = emptyList(),
        importedHttpCanaryZips: List<Pair<SessionId, ByteArray>> = emptyList()
    ) {
        // Merge graphs
        val existingGraph = store.loadGraph(projectId)
        val mergedGraph = mergeGraphs(existingGraph, importedGraph)
        store.saveGraph(projectId, mergedGraph)

        // Merge chains
        val existingChains = store.loadChains(projectId)
        val existingChainIds = existingChains.chains.map { it.id }.toSet()
        val newChains = importedChains.chains.filter { it.id !in existingChainIds }
        val mergedChains = ChainsFile(chains = existingChains.chains + newChains)
        store.saveChains(projectId, mergedChains)

        // Merge sessions
        val existingSessions = store.listSessions(projectId)
        val existingSessionIds = existingSessions.map { it.id }.toSet()
        val newSessions = importedSessions.filter { it.id !in existingSessionIds }
        newSessions.forEach { session ->
            store.saveSession(projectId, session)
        }

        // Save WebsiteMaps (overwrite existing)
        importedMaps.forEach { (sessionId, map) ->
            store.saveWebsiteMap(projectId, sessionId, map)
        }

        // Save HttpCanary ZIPs (overwrite existing)
        importedHttpCanaryZips.forEach { (sessionId, zipBytes) ->
            store.saveHttpCanaryZip(projectId, sessionId, zipBytes)
        }
    }

    /**
     * Merge two graphs intelligently.
     *
     * Strategy:
     * - For nodes with the same ID, keep the one with the latest lastSeenAt
     * - For edges, keep unique edges (by from, to, kind)
     */
    private fun mergeGraphs(existing: MapGraph, imported: MapGraph): MapGraph {
        // Merge nodes - keep newest version of each node
        val nodesById = (existing.nodes + imported.nodes)
            .groupBy { it.id }
            .mapValues { (_, nodes) ->
                // Keep the node with the newest lastSeenAt (nulls are treated as oldest)
                nodes.sortedByDescending { it.lastSeenAt ?: kotlinx.datetime.Instant.DISTANT_PAST }.first()
            }

        // Merge edges - keep unique edges
        val uniqueEdges = (existing.edges + imported.edges)
            .distinctBy { Triple(it.from, it.to, it.kind) }

        return MapGraph(
            nodes = nodesById.values.toList(),
            edges = uniqueEdges
        )
    }
}
