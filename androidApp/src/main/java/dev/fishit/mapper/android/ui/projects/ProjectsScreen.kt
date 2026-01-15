package dev.fishit.mapper.android.ui.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fishit.mapper.android.di.LocalAppContainer
import dev.fishit.mapper.android.ui.common.SimpleVmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (projectId: String) -> Unit
) {
    val container = LocalAppContainer.current
    val vm: ProjectsViewModel = viewModel(
        factory = SimpleVmFactory { 
            ProjectsViewModel(container.store, container.importManager) 
        }
    )
    val state by vm.state.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    
    // File picker launcher for importing ZIP files
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            vm.importProject(it) { projectId ->
                onOpenProject(projectId.value)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                actions = {
                    // Import button
                    IconButton(
                        onClick = { importLauncher.launch("application/zip") },
                        enabled = !state.isImporting
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = "Import project")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create project")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Show import progress
            if (state.isImporting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(8.dp))
                    Text("Importing project...")
                }
                Spacer(Modifier.height(12.dp))
            }
            
            // Show error message
            if (state.error != null) {
                Snackbar(
                    action = {
                        TextButton(onClick = { vm.clearMessages() }) {
                            Text("Dismiss")
                        }
                    },
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text("Error: ${state.error}")
                }
            }
            
            // Show success message
            state.importSuccess?.let { successMessage ->
                Snackbar(
                    action = {
                        TextButton(onClick = { vm.clearMessages() }) {
                            Text("Dismiss")
                        }
                    },
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(successMessage)
                }
            }

            if (state.projects.isEmpty() && !state.isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No projects yet.")
                    Spacer(Modifier.height(8.dp))
                    Text("Create one or import an existing project.")
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.projects) { project ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenProject(project.id.value) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(project.name)
                                    Spacer(Modifier.height(4.dp))
                                    Text(project.startUrl)
                                }

                                IconButton(onClick = { vm.deleteProject(project.id.value) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, startUrl ->
                vm.createProject(name, startUrl) { created ->
                    showCreate = false
                    onOpenProject(created.id.value)
                }
            }
        )
    }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, startUrl: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var startUrl by remember { mutableStateOf("https://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = startUrl,
                    onValueChange = { startUrl = it },
                    label = { Text("Start URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, startUrl) },
                enabled = startUrl.trim().startsWith("http")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
