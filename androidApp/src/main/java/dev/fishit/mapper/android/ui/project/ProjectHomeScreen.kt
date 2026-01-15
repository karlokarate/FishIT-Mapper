package dev.fishit.mapper.android.ui.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fishit.mapper.android.di.LocalAppContainer
import dev.fishit.mapper.android.ui.common.SimpleVmFactory
import dev.fishit.mapper.contract.ProjectId

private enum class ProjectTab(val label: String) {
    Browser("Browser"),
    Graph("Graph"),
    Sessions("Sessions"),
    Chains("Chains")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectHomeScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit
) {
    val container = LocalAppContainer.current
    val vm: ProjectViewModel = viewModel(
        factory = SimpleVmFactory {
            ProjectViewModel(
                projectId = ProjectId(projectId),
                store = container.store,
                mappingEngine = container.mappingEngine,
                exportManager = container.exportManager
            )
        }
    )
    val state by vm.state.collectAsState()

    var tab by remember { mutableStateOf(ProjectTab.Browser) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.meta?.name ?: "Project") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.exportAndShare() }) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == ProjectTab.Browser,
                    onClick = { tab = ProjectTab.Browser },
                    label = { Text(ProjectTab.Browser.label) },
                    icon = { Text("🌍") }
                )
                NavigationBarItem(
                    selected = tab == ProjectTab.Graph,
                    onClick = { tab = ProjectTab.Graph },
                    label = { Text(ProjectTab.Graph.label) },
                    icon = { Text("🧠") }
                )
                NavigationBarItem(
                    selected = tab == ProjectTab.Sessions,
                    onClick = { tab = ProjectTab.Sessions },
                    label = { Text(ProjectTab.Sessions.label) },
                    icon = { Text("🧾") }
                )
                NavigationBarItem(
                    selected = tab == ProjectTab.Chains,
                    onClick = { tab = ProjectTab.Chains },
                    label = { Text(ProjectTab.Chains.label) },
                    icon = { Text("🔗") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.error != null) {
                Text(
                    text = "Error: ${'$'}{state.error}",
                    modifier = Modifier.padding(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    ProjectTab.Browser -> BrowserScreen(
                        projectMeta = state.meta,
                        isRecording = state.isRecording,
                        liveEvents = state.liveEvents,
                        onStartRecording = { url -> vm.startRecording(url) },
                        onStopRecording = { finalUrl -> vm.stopRecording(finalUrl) },
                        onRecorderEvent = { event -> vm.onRecorderEvent(event) }
                    )

                    ProjectTab.Graph -> GraphScreen(
                        graph = state.graph,
                        onNodeTagsChanged = { nodeId, tags ->
                            vm.updateNodeTags(nodeId, tags)
                        }
                    )

                    ProjectTab.Sessions -> SessionsScreen(
                        sessions = state.sessions,
                        onOpenSession = { onOpenSession(it) }
                    )

                    ProjectTab.Chains -> ChainsScreen(chainsFile = state.chains)
                }
            }
        }
    }
}
