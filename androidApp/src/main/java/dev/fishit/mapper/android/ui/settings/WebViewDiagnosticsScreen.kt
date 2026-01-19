package dev.fishit.mapper.android.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fishit.mapper.android.webview.WebViewDiagnosticsManager
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Diagnostics-Screen für WebView-Debugging.
 *
 * Zeigt alle relevanten Informationen über den WebView-Status:
 * - WebView Package und Version
 * - Aktivierte Settings
 * - Cookie-Status
 * - User Agent
 * - Console-Logs (letzte 50)
 * - Fehler-Logs (letzte 50)
 *
 * Hilfreich bei der Diagnose von Login-Problemen, grauen Overlays,
 * Cookie-Problemen und JavaScript-Fehlern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewDiagnosticsScreen(onBack: () -> Unit) {
    val diagnostics by WebViewDiagnosticsManager.diagnosticsData.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebView Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { WebViewDiagnosticsManager.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Logs löschen")
                    }
                }
            )
        }
    ) { padding ->
        diagnostics?.let { data ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // WebView Info Section
                item {
                    DiagnosticsSection(title = "WebView Information") {
                        DiagnosticItem("Package", data.webViewPackage ?: "Unknown")
                        DiagnosticItem("Version", data.webViewVersion ?: "Unknown")
                        DiagnosticItem(
                            "User Agent",
                            data.userAgent ?: "Unknown",
                            maxLines = 3
                        )
                    }
                }

                // Settings Section
                item {
                    DiagnosticsSection(title = "WebView Settings") {
                        SettingItem("JavaScript", data.javaScriptEnabled)
                        SettingItem("DOM Storage", data.domStorageEnabled)
                        SettingItem("Database", data.databaseEnabled)
                        SettingItem("Cookies", data.cookiesEnabled)
                        SettingItem("3rd-Party Cookies", data.thirdPartyCookiesEnabled)
                        SettingItem("Multiple Windows", data.multipleWindowsSupported)
                    }
                }

                // Cookie Status Section
                item {
                    DiagnosticsSection(title = "Cookie Status") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Active Cookies (approx.)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${data.cookieCount}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Console Logs Section
                item {
                    Text(
                        "Console Logs (${data.consoleLogs.size})",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                if (data.consoleLogs.isEmpty()) {
                    item {
                        InfoCard(
                            icon = Icons.Default.Info,
                            message = "Keine Console-Logs verfügbar. Navigiere mit dem WebView, um Logs zu sehen."
                        )
                    }
                } else {
                    items(data.consoleLogs.reversed()) { log ->
                        ConsoleLogItem(log)
                    }
                }

                // Error Logs Section
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Error Logs (${data.errorLogs.size})",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (data.errorLogs.isEmpty()) {
                    item {
                        InfoCard(
                            icon = Icons.Default.CheckCircle,
                            message = "Keine Fehler gefunden. WebView funktioniert einwandfrei.",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                } else {
                    items(data.errorLogs.reversed()) { error ->
                        ErrorLogItem(error)
                    }
                }

                // Last Updated
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Zuletzt aktualisiert: ${formatTimestamp(data.lastUpdated)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } ?: run {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Lade Diagnostics...")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticItem(
    label: String,
    value: String,
    maxLines: Int = 1
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = maxLines,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SettingItem(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium
        )
        Icon(
            if (enabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (enabled) "Aktiviert" else "Deaktiviert",
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ConsoleLogItem(log: WebViewDiagnosticsManager.ConsoleLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (log.level) {
                "ERROR" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                "WARN" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = when (log.level) {
                        "ERROR" -> MaterialTheme.colorScheme.error
                        "WARN" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        log.level,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    formatTimestamp(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                log.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            log.sourceUrl?.let { url ->
                Text(
                    "Source: $url",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorLogItem(error: WebViewDiagnosticsManager.ErrorLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            error.type.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    formatTimestamp(error.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                error.description,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            error.failingUrl?.let { url ->
                Text(
                    "URL: $url",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            error.errorCode?.let { code ->
                Text(
                    "Error Code: $code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = color
            )
        }
    }
}

private fun formatTimestamp(instant: Instant): String {
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return String.format(
        "%02d:%02d:%02d",
        localDateTime.hour,
        localDateTime.minute,
        localDateTime.second
    )
}
