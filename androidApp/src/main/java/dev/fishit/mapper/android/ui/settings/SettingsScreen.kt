package dev.fishit.mapper.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Settings-Screen für FishIT-Mapper.
 *
 * Da Traffic-Capture jetzt extern über HttpCanary erfolgt,
 * zeigt dieser Screen Anleitungen zur Verwendung von HttpCanary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text("Einstellungen") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                            }
                        }
                )
            }
    ) { padding ->
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .padding(padding)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // HttpCanary Info Section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Traffic Capture", style = MaterialTheme.typography.titleLarge)
                    }

                    Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                    CardDefaults.cardColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.primaryContainer
                                    )
                    ) {
                        Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                    "📱 HttpCanary Integration",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                    "FishIT-Mapper verwendet HttpCanary für die Netzwerk-Analyse. " +
                                            "HttpCanary erfasst den Traffic, FishIT-Mapper korreliert " +
                                            "ihn mit deinen Aktionen.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // HttpCanary installieren
                    Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse("market://details?id=com.guoshi.httpcanary")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse("https://play.google.com/store/apps/details?id=com.guoshi.httpcanary")
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("HttpCanary im Play Store öffnen")
                    }
                }
            }

            // Workflow Anleitung
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Workflow", style = MaterialTheme.typography.titleLarge)

                    Text(
                            "So verwendest du FishIT-Mapper mit HttpCanary:",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                            """
                            1. HttpCanary installieren und einrichten
                            2. HttpCanary VPN starten
                            3. In FishIT-Mapper: Recording starten
                            4. Website im Browser navigieren
                            5. Recording stoppen
                            6. In HttpCanary: Traffic als ZIP exportieren
                            7. ZIP in FishIT-Mapper importieren
                            8. WebsiteMap wird automatisch generiert
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // HttpCanary Setup
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("HttpCanary Setup", style = MaterialTheme.typography.titleLarge)

                    Text(
                            "Einmalige Einrichtung:",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                            """
                            1. HttpCanary öffnen
                            2. HTTPS-Zertifikat installieren (Settings → SSL)
                            3. VPN-Berechtigung erteilen
                            4. Optional: Filter für Browser-App setzen
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                            "Export-Format:",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                            """
                            • Wähle die Requests die du exportieren willst
                            • Tippe auf "Save" → "Save as ZIP"
                            • Importiere das ZIP in FishIT-Mapper
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // App Info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Über FishIT-Mapper", style = MaterialTheme.typography.titleLarge)

                    Text(
                            "Version: 0.1.0 (MVP)",
                            style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                            "FishIT-Mapper hilft bei der Analyse von Websites durch " +
                            "Korrelation von User-Aktionen mit HTTP-Traffic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
