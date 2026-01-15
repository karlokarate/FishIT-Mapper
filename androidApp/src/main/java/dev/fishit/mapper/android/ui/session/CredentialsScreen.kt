package dev.fishit.mapper.android.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fishit.mapper.android.di.LocalAppContainer
import dev.fishit.mapper.contract.*
import dev.fishit.mapper.engine.CredentialExtractor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(
    projectId: String,
    sessionId: String?,
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    
    var credentials by remember { mutableStateOf<List<StoredCredential>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSensitiveData by remember { mutableStateOf(false) }
    
    LaunchedEffect(projectId, sessionId) {
        loading = true
        error = null
        scope.launch {
            runCatching {
                if (sessionId != null) {
                    // Load credentials for specific session
                    val session = container.store.loadSession(ProjectId(projectId), SessionId(sessionId))
                    if (session != null) {
                        val extracted = CredentialExtractor.extractCredentials(session)
                        credentials = extracted
                        
                        // Save to project credentials store
                        extracted.forEach { cred ->
                            container.store.addCredential(ProjectId(projectId), cred)
                        }
                    }
                } else {
                    // Load all credentials for project
                    credentials = container.store.loadCredentials(ProjectId(projectId))
                }
            }.onFailure { t ->
                error = t.message ?: "Unknown error"
            }
            loading = false
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credentials") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSensitiveData = !showSensitiveData }) {
                        Icon(
                            imageVector = if (showSensitiveData) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showSensitiveData) "Hide sensitive data" else "Show sensitive data"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                error != null -> {
                    Text(
                        text = "Error: $error",
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                credentials.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No credentials found",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Credentials are automatically extracted from sessions with login forms or authentication headers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                else -> {
                    CredentialsContent(
                        credentials = credentials,
                        showSensitiveData = showSensitiveData,
                        onDelete = { credId ->
                            scope.launch {
                                container.store.deleteCredential(ProjectId(projectId), credId)
                                credentials = credentials.filter { it.id != credId }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialsContent(
    credentials: List<StoredCredential>,
    showSensitiveData: Boolean,
    onDelete: (CredentialId) -> Unit,
    modifier: Modifier = Modifier
) {
    val groupedByDomain = remember(credentials) {
        CredentialExtractor.groupByDomain(credentials)
    }
    
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Privacy Notice",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Passwords are hashed for privacy. Tokens are truncated. This data is stored locally on your device.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        groupedByDomain.forEach { (domain, creds) ->
            item {
                Text(
                    text = domain,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            items(creds) { credential ->
                CredentialCard(
                    credential = credential,
                    showSensitiveData = showSensitiveData,
                    onDelete = { onDelete(credential.id) }
                )
            }
        }
    }
}

@Composable
private fun CredentialCard(
    credential: StoredCredential,
    showSensitiveData: Boolean,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = getCredentialTypeLabel(credential.type),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = credential.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete credential",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            
            when (credential.type) {
                CredentialType.UsernamePassword -> {
                    credential.username?.let { username ->
                        CredentialField(
                            label = "Username",
                            value = username,
                            sensitive = false
                        )
                    }
                    credential.passwordHash?.let { passwordHash ->
                        CredentialField(
                            label = "Password",
                            value = if (showSensitiveData) passwordHash else "••••••••",
                            sensitive = true
                        )
                    }
                }
                CredentialType.Token, CredentialType.OAuth -> {
                    credential.token?.let { token ->
                        CredentialField(
                            label = "Token",
                            value = if (showSensitiveData) token else "••••••••",
                            sensitive = true
                        )
                    }
                }
                CredentialType.Cookie -> {
                    credential.metadata["cookieName"]?.let { cookieName ->
                        CredentialField(
                            label = "Cookie Name",
                            value = cookieName,
                            sensitive = false
                        )
                    }
                    credential.token?.let { token ->
                        CredentialField(
                            label = "Value",
                            value = if (showSensitiveData) token else "••••••••",
                            sensitive = true
                        )
                    }
                }
                CredentialType.Header -> {
                    credential.token?.let { token ->
                        CredentialField(
                            label = "Header Value",
                            value = if (showSensitiveData) token else "••••••••",
                            sensitive = true
                        )
                    }
                }
                else -> {}
            }
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = "Captured: ${credential.capturedAt}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            
            if (credential.metadata.isNotEmpty()) {
                Text(
                    text = "Session: ${credential.sessionId.value.take(8)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
    
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Credential") },
            text = { Text("Are you sure you want to delete this credential? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    sensitive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(120.dp)
        )
        
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (sensitive) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun getCredentialTypeLabel(type: CredentialType): String = when (type) {
    CredentialType.UsernamePassword -> "Username & Password"
    CredentialType.Token -> "Bearer Token"
    CredentialType.Cookie -> "Session Cookie"
    CredentialType.OAuth -> "OAuth Token"
    CredentialType.Header -> "Custom Header"
    CredentialType.Unknown -> "Unknown"
}
