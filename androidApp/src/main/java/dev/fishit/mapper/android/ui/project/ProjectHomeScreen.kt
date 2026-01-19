package dev.fishit.mapper.android.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    Capture("Capture"),
    Graph("Graph"),
    Sessions("Sessions"),
    Chains("Chains")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectHomeScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onOpenCapture: ((projectId: String, startUrl: String) -> Unit)? = null
) {
    val container = LocalAppContainer.current
    val vm: ProjectViewModel = viewModel(
        key = projectId, // Ensure a new ViewModel for each project
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

    var tab by remember { mutableStateOf(ProjectTab.Capture) }

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
                    selected = tab == ProjectTab.Capture,
                    onClick = {
                        if (onOpenCapture != null) {
                            val startUrl = state.meta?.startUrl ?: "https://"
                            onOpenCapture(projectId, startUrl)
                        } else {
                            tab = ProjectTab.Capture
                        }
                    },
                    label = { Text(ProjectTab.Capture.label) },
                    icon = { Text("📡") }
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
                    text = "Error: ${state.error}",
                    modifier = Modifier.padding(16.dp)
                )
            } else if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text("Loading project...")
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (tab) {
                        ProjectTab.Capture -> {
                            // Wird über Navigation gehandhabt via onOpenCapture
                            // Fallback: Zeige Hinweis zum Öffnen
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text("📡", style = MaterialTheme.typography.displayLarge)
                                    Text(
                                        "Traffic Capture",
                                        style = MaterialTheme.typography.headlineMedium
                                    )
                                    Text(
                                        "Tippe auf den Tab um den Capture-Browser zu öffnen",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (onOpenCapture != null) {
                                        Button(onClick = {
                                            val startUrl = state.meta?.startUrl ?: "https://"
                                            onOpenCapture(projectId, startUrl)
                                        }) {
                                            Text("Capture öffnen")
                                        }
                                    }
                                }
                            }
                        }

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
}
