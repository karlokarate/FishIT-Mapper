package dev.fishit.mapper.android.ui.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.fishit.mapper.android.capture.CaptureSessionManager
import dev.fishit.mapper.android.export.SessionExportManager
import dev.fishit.mapper.android.export.SessionExportManager.ExportFormat
import kotlinx.coroutines.launch

/**
 * Dialog für Export-Optionen.
 *
 * Ermöglicht:
 * - Direktes Teilen mit Apps
 * - Export in benutzerdefinierten Ordner
 * - Auswahl des Export-Formats
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDialog(
    session: CaptureSessionManager.CaptureSession,
    onDismiss: () -> Unit,
    onExportComplete: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportManager = remember { SessionExportManager(context) }

    var selectedFormat by remember { mutableStateOf(ExportFormat.HAR) }
    var isExporting by remember { mutableStateOf(false) }
    var showFormatPicker by remember { mutableStateOf(false) }

    // Folder Picker für "Speichern unter"
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { folderUri ->
            // Persistente Berechtigung anfordern
            context.contentResolver.takePersistableUriPermission(
                folderUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            scope.launch {
                isExporting = true
                val result = exportManager.exportToFolder(session, folderUri, selectedFormat)
                isExporting = false

                result.fold(
                    onSuccess = {
                        onExportComplete("Exportiert: ${selectedFormat.displayName}")
                        onDismiss()
                    },
                    onFailure = {
                        onExportComplete("Fehler: ${it.message}")
                    }
                )
            }
        }
    }

    // Folder Picker für "Alle Formate exportieren"
    val allFormatsFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { folderUri ->
            context.contentResolver.takePersistableUriPermission(
                folderUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            scope.launch {
                isExporting = true
                val result = exportManager.exportAllFormatsToFolder(session, folderUri)
                isExporting = false

                result.fold(
                    onSuccess = { uris ->
                        onExportComplete("${uris.size} Dateien exportiert")
                        onDismiss()
                    },
                    onFailure = {
                        onExportComplete("Fehler: ${it.message}")
                    }
                )
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header
                Text(
                    text = "Session exportieren",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = session.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Session Info
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InfoChip(
                        icon = Icons.Default.Http,
                        text = "${session.exchanges.size} Requests"
                    )
                    InfoChip(
                        icon = Icons.Default.TouchApp,
                        text = "${session.userActions.size} Actions"
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Format Auswahl
                OutlinedCard(
                    onClick = { showFormatPicker = true },
                    modifier = Modifier.fillMaxWidth()
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
                                text = "Format",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = selectedFormat.displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Icon(Icons.Default.KeyboardArrowDown, "Format wählen")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Export Buttons
                if (isExporting) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Exportiere...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Teilen Button
                    Button(
                        onClick = {
                            scope.launch {
                                isExporting = true
                                if (selectedFormat == ExportFormat.ZIP) {
                                    exportManager.shareAsZipBundle(session)
                                } else {
                                    exportManager.shareSession(session, selectedFormat)
                                }
                                isExporting = false
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mit App teilen")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Speichern unter Button
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("In Ordner speichern")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Alle Formate Button
                    TextButton(
                        onClick = { allFormatsFolderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Inventory, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Alle Formate exportieren")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Abbrechen
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Abbrechen")
                }
            }
        }
    }

    // Format Picker Dialog
    if (showFormatPicker) {
        FormatPickerDialog(
            selectedFormat = selectedFormat,
            onSelectFormat = {
                selectedFormat = it
                showFormatPicker = false
            },
            onDismiss = { showFormatPicker = false }
        )
    }
}

@Composable
private fun FormatPickerDialog(
    selectedFormat: ExportFormat,
    onSelectFormat: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Export-Format wählen",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn {
                    items(SessionExportManager.availableFormats) { format ->
                        FormatItem(
                            format = format,
                            isSelected = format == selectedFormat,
                            onClick = { onSelectFormat(format) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatItem(
    format: ExportFormat,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (format) {
        ExportFormat.HAR -> Icons.Default.Http
        ExportFormat.JSON -> Icons.Default.DataObject
        ExportFormat.OPENAPI -> Icons.Default.Api
        ExportFormat.POSTMAN -> Icons.Default.Send
        ExportFormat.CURL -> Icons.Default.Terminal
        ExportFormat.TYPESCRIPT -> Icons.Default.Code
        ExportFormat.MARKDOWN -> Icons.Default.Description
        ExportFormat.MERMAID -> Icons.Default.AccountTree
        ExportFormat.MERMAID_CORRELATION -> Icons.Default.Timeline
        ExportFormat.STATE_GRAPH -> Icons.Default.BubbleChart
        ExportFormat.TIMELINE -> Icons.Default.Schedule
        ExportFormat.ZIP -> Icons.Default.FolderZip
    }

    val description = when (format) {
        ExportFormat.HAR -> "Chrome DevTools, Postman"
        ExportFormat.JSON -> "Rohdaten zur Weiterverarbeitung"
        ExportFormat.OPENAPI -> "API Dokumentation"
        ExportFormat.POSTMAN -> "Postman Import"
        ExportFormat.CURL -> "Shell-Befehle"
        ExportFormat.TYPESCRIPT -> "TypeScript API Client"
        ExportFormat.MARKDOWN -> "Dokumentation mit Diagramm"
        ExportFormat.MERMAID -> "Sequenzdiagramm (Mermaid)"
        ExportFormat.MERMAID_CORRELATION -> "Actions → Requests Korrelation"
        ExportFormat.STATE_GRAPH -> "State-Graph pro User-Aktion"
        ExportFormat.TIMELINE -> "Normalisierte Timeline (JSON)"
        ExportFormat.ZIP -> "Alle Formate in einem Archiv"
    }

    ListItem(
        headlineContent = { Text(format.displayName) },
        supportingContent = { Text(description) },
        leadingContent = {
            Icon(
                icon,
                null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    "Ausgewählt",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun InfoChip(
    icon: ImageVector,
    text: String
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Schnell-Export Button für Toolbar.
 */
@Composable
fun QuickExportButton(
    session: CaptureSessionManager.CaptureSession?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    IconButton(
        onClick = { showExportDialog = true },
        enabled = session != null,
        modifier = modifier
    ) {
        Icon(Icons.Default.FileDownload, "Exportieren")
    }

    if (showExportDialog && session != null) {
        ExportDialog(
            session = session,
            onDismiss = { showExportDialog = false },
            onExportComplete = { message ->
                exportMessage = message
            }
        )
    }

    // Snackbar für Export-Nachricht
    exportMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3000)
            exportMessage = null
        }
    }
}
