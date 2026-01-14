package dev.fishit.mapper.android.data

import android.content.Context
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
}
