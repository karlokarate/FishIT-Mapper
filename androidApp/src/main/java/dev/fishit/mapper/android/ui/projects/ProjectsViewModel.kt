package dev.fishit.mapper.android.ui.projects

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fishit.mapper.android.data.AndroidProjectStore
import dev.fishit.mapper.android.import.ImportManager
import dev.fishit.mapper.contract.ProjectId
import dev.fishit.mapper.contract.ProjectMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val projects: List<ProjectMeta> = emptyList(),
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val importSuccess: String? = null,
    val error: String? = null
)

class ProjectsViewModel(
    private val store: AndroidProjectStore,
    private val importManager: ImportManager
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching { store.listProjects() }
                .onSuccess { projects ->
                    _state.value = ProjectsUiState(projects = projects, isLoading = false)
                }
                .onFailure { t ->
                    _state.value = ProjectsUiState(projects = emptyList(), isLoading = false, error = t.message)
                }
        }
    }

    fun createProject(name: String, startUrl: String, onCreated: (ProjectMeta) -> Unit) {
        viewModelScope.launch {
            runCatching { store.createProject(name, startUrl) }
                .onSuccess { meta ->
                    refresh()
                    onCreated(meta)
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(error = t.message)
                }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            runCatching { store.deleteProject(ProjectId(projectId)) }
                .onSuccess { refresh() }
                .onFailure { t -> _state.value = _state.value.copy(error = t.message) }
        }
    }

    fun importProject(zipUri: Uri, onImported: (ProjectId) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isImporting = true, error = null, importSuccess = null)
            
            importManager.importBundle(zipUri)
                .onSuccess { projectId ->
                    refresh()
                    _state.value = _state.value.copy(
                        isImporting = false,
                        importSuccess = "Successfully imported project: ${projectId.value}"
                    )
                    onImported(projectId)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isImporting = false,
                        error = "Import failed: ${error.message}"
                    )
                }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, importSuccess = null)
    }
}
