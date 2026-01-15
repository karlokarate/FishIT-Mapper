package dev.fishit.mapper.android.ui.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fishit.mapper.android.data.AndroidProjectStore
import dev.fishit.mapper.android.export.ExportManager
import dev.fishit.mapper.contract.*
import dev.fishit.mapper.engine.HubDetector
import dev.fishit.mapper.engine.IdGenerator
import dev.fishit.mapper.engine.MappingEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class ProjectUiState(
    val meta: ProjectMeta? = null,
    val graph: MapGraph = MapGraph(),
    val chains: ChainsFile = ChainsFile(),
    val sessions: List<RecordingSession> = emptyList(),

    val isLoading: Boolean = true,
    val error: String? = null,

    // Recording
    val isRecording: Boolean = false,
    val liveEvents: List<RecorderEvent> = emptyList(),
    val liveUrl: String? = null
)

class ProjectViewModel(
    private val projectId: ProjectId,
    private val store: AndroidProjectStore,
    private val mappingEngine: MappingEngine,
    private val exportManager: ExportManager
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state

    private var liveSessionId: SessionId? = null
    private var liveSessionStartedAt = Clock.System.now()
    private var liveInitialUrl: String = ""

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching {
                val meta = store.loadProjectMeta(projectId)
                val graph = store.loadGraph(projectId)
                val chains = store.loadChains(projectId)
                val sessions = store.listSessions(projectId)
                Quad(meta, graph, chains, sessions)
            }.onSuccess { (meta, graph, chains, sessions) ->
                _state.value = _state.value.copy(
                    meta = meta,
                    graph = graph,
                    chains = chains,
                    sessions = sessions,
                    isLoading = false
                )
            }.onFailure { t ->
                _state.value = _state.value.copy(isLoading = false, error = t.message)
            }
        }
    }

    fun startRecording(initialUrl: String) {
        val now = Clock.System.now()
        liveSessionId = IdGenerator.newSessionId()
        liveSessionStartedAt = now
        liveInitialUrl = initialUrl

        _state.value = _state.value.copy(
            isRecording = true,
            liveEvents = emptyList(),
            liveUrl = initialUrl
        )
    }

    fun stopRecording(finalUrl: String? = null) {
        val sessionId = liveSessionId ?: return
        val startedAt = liveSessionStartedAt
        val endedAt = Clock.System.now()
        val events = _state.value.liveEvents

        val session = RecordingSession(
            id = sessionId,
            projectId = projectId,
            startedAt = startedAt,
            endedAt = endedAt,
            initialUrl = liveInitialUrl,
            finalUrl = finalUrl,
            events = events
        )

        viewModelScope.launch {
            runCatching {
                store.saveSession(projectId, session)

                val currentGraph = store.loadGraph(projectId)
                val updatedGraph = mappingEngine.applySession(currentGraph, session)
                store.saveGraph(projectId, updatedGraph)

                // bump updatedAt
                val meta = store.loadProjectMeta(projectId)
                if (meta != null) {
                    store.updateProjectMeta(meta.copy(updatedAt = endedAt))
                }
            }.onSuccess {
                liveSessionId = null
                _state.value = _state.value.copy(isRecording = false, liveEvents = emptyList())
                refresh()
            }.onFailure { t ->
                _state.value = _state.value.copy(error = t.message)
            }
        }
    }

    fun onRecorderEvent(event: RecorderEvent) {
        if (!_state.value.isRecording) return
        _state.value = _state.value.copy(
            liveEvents = _state.value.liveEvents + event,
            liveUrl = when (event) {
                is NavigationEvent -> event.url
                else -> _state.value.liveUrl
            }
        )
    }

    fun exportAndShare() {
        viewModelScope.launch {
            runCatching { exportManager.exportProjectZip(projectId) }
                .onSuccess { zip ->
                    exportManager.shareZip(zip)
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(error = t.message)
                }
        }
    }

    /**
     * Updates tags for a specific node and persists the change.
     */
    fun updateNodeTags(nodeId: NodeId, tags: List<String>) {
        viewModelScope.launch {
            runCatching {
                val currentGraph = store.loadGraph(projectId)
                val updatedNodes = currentGraph.nodes.map { node ->
                    if (node.id == nodeId) {
                        node.copy(tags = tags)
                    } else {
                        node
                    }
                }
                val updatedGraph = currentGraph.copy(nodes = updatedNodes)
                store.saveGraph(projectId, updatedGraph)
                updatedGraph
            }.onSuccess { updatedGraph ->
                _state.value = _state.value.copy(graph = updatedGraph)
            }.onFailure { t ->
                _state.value = _state.value.copy(error = t.message)
            }
        }
    }

    /**
     * Applies hub detection algorithm to automatically tag important nodes.
     */
    fun applyHubDetection(threshold: Double = 5.0) {
        viewModelScope.launch {
            runCatching {
                val currentGraph = store.loadGraph(projectId)
                val hubDetectedGraph = HubDetector.tagHubs(currentGraph, threshold)
                store.saveGraph(projectId, hubDetectedGraph)
                hubDetectedGraph
            }.onSuccess { updatedGraph ->
                _state.value = _state.value.copy(graph = updatedGraph)
            }.onFailure { t ->
                _state.value = _state.value.copy(error = t.message)
            }
        }
    }

    /**
     * Small helper to avoid pulling in a tuple library just for MVP.
     */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
