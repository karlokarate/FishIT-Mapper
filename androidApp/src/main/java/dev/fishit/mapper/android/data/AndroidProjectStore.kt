package dev.fishit.mapper.android.data

import android.content.Context
import dev.fishit.mapper.android.import.httpcanary.CapturedExchange
import dev.fishit.mapper.android.import.httpcanary.WebsiteMap
import dev.fishit.mapper.contract.*
import dev.fishit.mapper.engine.IdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * MVP file-based storage.
 *
 * Layout (under app files dir):
 *   fishit/
 *     projects/
 *       index.json
 *       <projectId>/
 *         graph.json
 *         chains.json
 *         sessions/
 *           <sessionId>.json
 *         maps/
 *           <sessionId>.json           # WebsiteMap
 *         exchanges/
 *           <sessionId>.json           # CapturedExchange list
 *         httpcanary/
 *           <sessionId>.zip            # Raw HttpCanary ZIP (for export)
 */
class AndroidProjectStore(
    private val context: Context
) {
    private val rootDir: File = File(context.filesDir, "fishit")
    private val projectsDir: File = File(rootDir, "projects")
    private val indexFile: File = File(projectsDir, "index.json")

    suspend fun listProjects(): List<ProjectMeta> = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext emptyList()
        val txt = indexFile.readText(Charsets.UTF_8)
        FishitJson.decodeFromString(ListSerializer(ProjectMeta.serializer()), txt)
    }

    suspend fun createProject(name: String, startUrl: String): ProjectMeta = withContext(Dispatchers.IO) {
        projectsDir.mkdirs()
        val now = Clock.System.now()
        val meta = ProjectMeta(
            id = IdGenerator.newProjectId(),
            name = name.trim().ifBlank { "Untitled" },
            startUrl = startUrl.trim(),
            createdAt = now,
            updatedAt = now
        )

        val current = listProjects().toMutableList()
        current.add(meta)
        saveProjectIndex(current)

        // initialize files
        val projectDir = projectDir(meta.id)
        projectDir.mkdirs()
        sessionsDir(meta.id).mkdirs()
        graphFile(meta.id).writeText(FishitJson.encodeToString(MapGraph.serializer(), MapGraph()), Charsets.UTF_8)
        chainsFile(meta.id).writeText(FishitJson.encodeToString(ChainsFile.serializer(), ChainsFile()), Charsets.UTF_8)

        meta
    }

    suspend fun deleteProject(projectId: ProjectId) = withContext(Dispatchers.IO) {
        val current = listProjects().filterNot { it.id == projectId }
        saveProjectIndex(current)
        projectDir(projectId).deleteRecursively()
    }

    suspend fun loadGraph(projectId: ProjectId): MapGraph = withContext(Dispatchers.IO) {
        val file = graphFile(projectId)
        if (!file.exists()) return@withContext MapGraph()
        FishitJson.decodeFromString(MapGraph.serializer(), file.readText(Charsets.UTF_8))
    }

    suspend fun saveGraph(projectId: ProjectId, graph: MapGraph) = withContext(Dispatchers.IO) {
        graphFile(projectId).parentFile?.mkdirs()
        graphFile(projectId).writeText(FishitJson.encodeToString(MapGraph.serializer(), graph), Charsets.UTF_8)
    }

    suspend fun loadChains(projectId: ProjectId): ChainsFile = withContext(Dispatchers.IO) {
        val file = chainsFile(projectId)
        if (!file.exists()) return@withContext ChainsFile()
        FishitJson.decodeFromString(ChainsFile.serializer(), file.readText(Charsets.UTF_8))
    }

    suspend fun saveChains(projectId: ProjectId, chains: ChainsFile) = withContext(Dispatchers.IO) {
        chainsFile(projectId).parentFile?.mkdirs()
        chainsFile(projectId).writeText(FishitJson.encodeToString(ChainsFile.serializer(), chains), Charsets.UTF_8)
    }

    suspend fun listSessions(projectId: ProjectId): List<RecordingSession> = withContext(Dispatchers.IO) {
        val dir = sessionsDir(projectId)
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f ->
                runCatching {
                    FishitJson.decodeFromString(RecordingSession.serializer(), f.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            ?: emptyList()
    }


suspend fun loadSession(projectId: ProjectId, sessionId: SessionId): RecordingSession? = withContext(Dispatchers.IO) {
    val file = File(sessionsDir(projectId), "${'$'}{sessionId.value}.json")
    if (!file.exists()) return@withContext null
    runCatching {
        FishitJson.decodeFromString(RecordingSession.serializer(), file.readText(Charsets.UTF_8))
    }.getOrNull()
}

suspend fun saveSession(projectId: ProjectId, session: RecordingSession) = withContext(Dispatchers.IO) {
        val dir = sessionsDir(projectId)
        dir.mkdirs()
        val file = File(dir, "${'$'}{session.id.value}.json")
        file.writeText(FishitJson.encodeToString(RecordingSession.serializer(), session), Charsets.UTF_8)
    }

    suspend fun loadProjectMeta(projectId: ProjectId): ProjectMeta? = withContext(Dispatchers.IO) {
        listProjects().firstOrNull { it.id == projectId }
    }

    /**
     * Save or update project metadata in the index.
     * Used for importing projects with existing metadata.
     */
    suspend fun saveProjectMeta(meta: ProjectMeta) = withContext(Dispatchers.IO) {
        val current = listProjects().toMutableList()
        val existing = current.indexOfFirst { it.id == meta.id }
        if (existing >= 0) {
            current[existing] = meta
        } else {
            current.add(meta)
        }
        saveProjectIndex(current)

        // Ensure directories exist
        projectDir(meta.id).mkdirs()
        sessionsDir(meta.id).mkdirs()
    }

    suspend fun updateProjectMeta(meta: ProjectMeta) = withContext(Dispatchers.IO) {
        val updated = listProjects().map { if (it.id == meta.id) meta else it }
        saveProjectIndex(updated)
    }

    private fun saveProjectIndex(projects: List<ProjectMeta>) {
        projectsDir.mkdirs()
        indexFile.writeText(
            FishitJson.encodeToString(ListSerializer(ProjectMeta.serializer()), projects),
            Charsets.UTF_8
        )
    }

    private fun projectDir(projectId: ProjectId): File = File(projectsDir, projectId.value)
    private fun graphFile(projectId: ProjectId): File = File(projectDir(projectId), "graph.json")
    private fun chainsFile(projectId: ProjectId): File = File(projectDir(projectId), "chains.json")
    private fun sessionsDir(projectId: ProjectId): File = File(projectDir(projectId), "sessions")
    private fun credentialsFile(projectId: ProjectId): File = File(projectDir(projectId), "credentials.json")
    private fun timelineFile(projectId: ProjectId, sessionId: SessionId): File =
        File(sessionsDir(projectId), "${sessionId.value}_timeline.json")
    private fun mapsDir(projectId: ProjectId): File = File(projectDir(projectId), "maps")
    private fun exchangesDir(projectId: ProjectId): File = File(projectDir(projectId), "exchanges")
    private fun httpcanaryDir(projectId: ProjectId): File = File(projectDir(projectId), "httpcanary")
    private fun websiteMapFile(projectId: ProjectId, sessionId: SessionId): File =
        File(mapsDir(projectId), "${sessionId.value}.json")
    private fun exchangesFile(projectId: ProjectId, sessionId: SessionId): File =
        File(exchangesDir(projectId), "${sessionId.value}.json")
    private fun httpcanaryZipFile(projectId: ProjectId, sessionId: SessionId): File =
        File(httpcanaryDir(projectId), "${sessionId.value}.zip")

    // === Credential Storage ===

    suspend fun saveCredentials(projectId: ProjectId, credentials: List<StoredCredential>) = withContext(Dispatchers.IO) {
        credentialsFile(projectId).parentFile?.mkdirs()
        credentialsFile(projectId).writeText(
            FishitJson.encodeToString(ListSerializer(StoredCredential.serializer()), credentials),
            Charsets.UTF_8
        )
    }

    suspend fun loadCredentials(projectId: ProjectId): List<StoredCredential> = withContext(Dispatchers.IO) {
        val file = credentialsFile(projectId)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            FishitJson.decodeFromString(ListSerializer(StoredCredential.serializer()), file.readText(Charsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    suspend fun addCredential(projectId: ProjectId, credential: StoredCredential) = withContext(Dispatchers.IO) {
        val current = loadCredentials(projectId)
        // Filter out duplicate by id and add new credential
        val existing = current.filter { it.id != credential.id }
        val updated = existing + credential
        saveCredentials(projectId, updated)
    }

    suspend fun deleteCredential(projectId: ProjectId, credentialId: CredentialId) = withContext(Dispatchers.IO) {
        val current = loadCredentials(projectId).filterNot { it.id == credentialId }
        saveCredentials(projectId, current)
    }

    // === Timeline Storage ===

    suspend fun saveTimeline(projectId: ProjectId, timeline: UnifiedTimeline) = withContext(Dispatchers.IO) {
        val file = timelineFile(projectId, timeline.sessionId)
        file.parentFile?.mkdirs()
        file.writeText(
            FishitJson.encodeToString(UnifiedTimeline.serializer(), timeline),
            Charsets.UTF_8
        )
    }

    suspend fun loadTimeline(projectId: ProjectId, sessionId: SessionId): UnifiedTimeline? = withContext(Dispatchers.IO) {
        val file = timelineFile(projectId, sessionId)
        if (!file.exists()) return@withContext null
        runCatching {
            FishitJson.decodeFromString(UnifiedTimeline.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    suspend fun deleteTimeline(projectId: ProjectId, sessionId: SessionId) = withContext(Dispatchers.IO) {
        timelineFile(projectId, sessionId).delete()
    }

    // === WebsiteMap Storage (HttpCanary correlation results) ===

    /**
     * Save a WebsiteMap for a session.
     * WebsiteMaps correlate user actions with HttpCanary traffic.
     */
    suspend fun saveWebsiteMap(projectId: ProjectId, sessionId: SessionId, map: WebsiteMap) = withContext(Dispatchers.IO) {
        val file = websiteMapFile(projectId, sessionId)
        file.parentFile?.mkdirs()
        file.writeText(
            FishitJson.encodeToString(WebsiteMap.serializer(), map),
            Charsets.UTF_8
        )
    }

    /**
     * Load a WebsiteMap for a session.
     */
    suspend fun loadWebsiteMap(projectId: ProjectId, sessionId: SessionId): WebsiteMap? = withContext(Dispatchers.IO) {
        val file = websiteMapFile(projectId, sessionId)
        if (!file.exists()) return@withContext null
        runCatching {
            FishitJson.decodeFromString(WebsiteMap.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * List all WebsiteMaps for a project.
     */
    suspend fun listWebsiteMaps(projectId: ProjectId): List<Pair<SessionId, WebsiteMap>> = withContext(Dispatchers.IO) {
        val dir = mapsDir(projectId)
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                val sessionId = SessionId(file.nameWithoutExtension)
                runCatching {
                    val map = FishitJson.decodeFromString(WebsiteMap.serializer(), file.readText(Charsets.UTF_8))
                    sessionId to map
                }.getOrNull()
            }
            ?: emptyList()
    }

    // === CapturedExchange Storage (HttpCanary imports) ===

    /**
     * Save captured exchanges for a session.
     */
    suspend fun saveExchanges(projectId: ProjectId, sessionId: SessionId, exchanges: List<CapturedExchange>) = withContext(Dispatchers.IO) {
        val file = exchangesFile(projectId, sessionId)
        file.parentFile?.mkdirs()
        file.writeText(
            FishitJson.encodeToString(ListSerializer(CapturedExchange.serializer()), exchanges),
            Charsets.UTF_8
        )
    }

    /**
     * Load captured exchanges for a session.
     */
    suspend fun loadExchanges(projectId: ProjectId, sessionId: SessionId): List<CapturedExchange> = withContext(Dispatchers.IO) {
        val file = exchangesFile(projectId, sessionId)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            FishitJson.decodeFromString(ListSerializer(CapturedExchange.serializer()), file.readText(Charsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    // === HttpCanary ZIP Storage (raw imports for re-export) ===

    /**
     * Store the raw HttpCanary ZIP for a session (for re-export).
     */
    suspend fun saveHttpCanaryZip(projectId: ProjectId, sessionId: SessionId, zipBytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = httpcanaryZipFile(projectId, sessionId)
        file.parentFile?.mkdirs()
        file.writeBytes(zipBytes)
    }

    /**
     * Load the raw HttpCanary ZIP for a session.
     */
    suspend fun loadHttpCanaryZip(projectId: ProjectId, sessionId: SessionId): ByteArray? = withContext(Dispatchers.IO) {
        val file = httpcanaryZipFile(projectId, sessionId)
        if (!file.exists()) return@withContext null
        file.readBytes()
    }

    /**
     * Get the HttpCanary ZIP file for a session (for export).
     */
    suspend fun getHttpCanaryZipFile(projectId: ProjectId, sessionId: SessionId): File? = withContext(Dispatchers.IO) {
        val file = httpcanaryZipFile(projectId, sessionId)
        if (file.exists()) file else null
    }
}
