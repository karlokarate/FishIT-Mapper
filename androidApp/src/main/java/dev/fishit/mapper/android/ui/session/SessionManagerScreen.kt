package dev.fishit.mapper.android.capture

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * UI für Session-Verwaltung mit Quick-Share.
 *
 * Features:
 * - Session-Liste mit Statistiken
 * - 1-Tap Share
 * - Multi-Select Export
 * - Storage-Info
 * - Cleanup-Optionen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionManagerScreen(
    sessionManager: CaptureSessionManager,
    storageManager: CaptureStorageManager,
    onSessionClick: (CaptureSessionManager.CaptureSession) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sessions by sessionManager.completedSessions.collectAsState()
    var storageStats by remember { mutableStateOf<CaptureStorageManager.StorageStats?>(null) }
    var selectedSessions by remember { mutableStateOf(setOf<String>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    // Storage Stats laden
    LaunchedEffect(sessions) {
        storageStats = storageManager.getStorageStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    // Multi-Share (wenn ausgewählt)
                    if (selectedSessions.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                val intent = storageManager.shareMultipleSessions(selectedSessions.toList())
                                intent?.let { context.startActivity(it) }
                            }
                        }) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                Text("${selectedSessions.size}")
                            }
                            Icon(Icons.Default.Share, "Ausgewählte teilen")
                        }
                    }

                    // Settings
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, "Einstellungen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Storage Info Card
            storageStats?.let { stats ->
                StorageInfoCard(
                    stats = stats,
                    onCleanup = {
                        scope.launch {
                            storageManager.performCleanup()
                            sessionManager.loadSavedSessions()
                            storageStats = storageManager.getStorageStats()
                        }
                    }
                )
            }

            // Session Liste
            if (sessions.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(sessions.sortedByDescending { it.startedAt }) { session ->
                        SessionCard(
                            session = session,
                            isSelected = session.id in selectedSessions,
                            onSelect = {
                                selectedSessions = if (session.id in selectedSessions) {
                                    selectedSessions - session.id
                                } else {
                                    selectedSessions + session.id
                                }
                            },
                            onClick = { onSessionClick(session) },
                            onShare = {
                                scope.launch {
                                    val intent = storageManager.shareSession(session.id)
                                    intent?.let { context.startActivity(it) }
                                }
                            },
                            onCopy = {
                                scope.launch {
                                    storageManager.copyToClipboard(session.id)
                                }
                            },
                            onDelete = {
                                sessionManager.deleteSession(session.id)
                            }
                        )
                    }
                }
            }
        }
    }

    // Settings Bottom Sheet
    if (showSettingsSheet) {
        SettingsBottomSheet(
            config = storageManager.config.collectAsState().value,
            onConfigChange = { storageManager.updateConfig(it) },
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun StorageInfoCard(
    stats: CaptureStorageManager.StorageStats,
    onCleanup: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "${stats.sessionCount} Sessions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${stats.totalSizeFormatted} • ${stats.totalExchanges} Requests",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onCleanup) {
                Icon(Icons.Default.CleaningServices, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Aufräumen")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCard(
    session: CaptureSessionManager.CaptureSession,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox für Multi-Select
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelect() }
            )

            Spacer(Modifier.width(12.dp))

            // Session Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Requests
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Http,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${session.exchangeCount}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Actions
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.TouchApp,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${session.actionCount}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Datum
                    Text(
                        formatDate(session.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Actions
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, "Teilen")
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "Mehr")
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("In Zwischenablage") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = {
                            onCopy()
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Löschen") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            onDelete()
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Inbox,
            null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Keine Sessions",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Starte eine neue Capture-Session",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBottomSheet(
    config: CaptureStorageManager.StorageConfig,
    onConfigChange: (CaptureStorageManager.StorageConfig) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                "Speicher-Einstellungen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(24.dp))

            // Auto-Save
            SettingsSwitch(
                title = "Auto-Save",
                subtitle = "Speichert alle ${config.autoSaveIntervalSeconds}s während Recording",
                checked = config.autoSaveIntervalSeconds > 0,
                onCheckedChange = {
                    onConfigChange(config.copy(
                        autoSaveIntervalSeconds = if (it) 30 else 0
                    ))
                }
            )

            // Noise Filter
            SettingsSwitch(
                title = "Noise-Filter",
                subtitle = "Filtert Analytics, Ads und Tracking",
                checked = config.filterNoise,
                onCheckedChange = {
                    onConfigChange(config.copy(filterNoise = it))
                }
            )

            // Compression
            SettingsSwitch(
                title = "Kompression",
                subtitle = "GZIP-Kompression für kleinere Dateien",
                checked = config.useCompression,
                onCheckedChange = {
                    onConfigChange(config.copy(useCompression = it))
                }
            )

            // Auto-Delete
            SettingsSwitch(
                title = "Auto-Löschen",
                subtitle = "Sessions nach ${config.autoDeleteAfterDays} Tagen löschen",
                checked = config.autoDeleteAfterDays > 0,
                onCheckedChange = {
                    onConfigChange(config.copy(
                        autoDeleteAfterDays = if (it) 30 else 0
                    ))
                }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatDate(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.dayOfMonth}.${local.monthNumber}.${local.year}"
}
