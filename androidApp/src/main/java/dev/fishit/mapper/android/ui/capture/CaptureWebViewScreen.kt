package dev.fishit.mapper.android.ui.capture

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.fishit.mapper.android.capture.CaptureSessionManager
import dev.fishit.mapper.android.capture.TrafficInterceptWebView
import kotlinx.coroutines.launch

/**
 * In-App Browser Screen mit Traffic Recording.
 *
 * Ermöglicht das Browsen einer Website während alle API-Calls
 * aufgezeichnet und mit User-Actions korreliert werden.
 *
 * ## Flow (wichtig!)
 * 1. User gibt URL ein
 * 2. User drückt Record Button
 * 3. Dialog: Session-Name eingeben
 * 4. Seite wird FRISCH geladen (ohne Cache)
 * 5. ALLE Requests inkl. initialer Redirects werden erfasst!
 *
 * ## Features
 * - URL-Eingabe mit Autocomplete
 * - Recording Start/Stop
 * - Live Traffic Counter
 * - Cache-Clear bei Recording-Start
 *
 * @param onExportSession Callback wenn Session exportiert werden soll
 * @param onBack Callback für Zurück-Navigation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureWebViewScreen(
    onExportSession: (CaptureSessionManager.CaptureSession) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // WebView und Session Manager
    val webView = remember { TrafficInterceptWebView(context) }
    val sessionManager = remember { CaptureSessionManager(context) }

    // State
    var urlInput by remember { mutableStateOf("https://") }
    var showSessionDialog by remember { mutableStateOf(false) }
    var showStatsPanel by remember { mutableStateOf(false) }
    var pendingRecordUrl by remember { mutableStateOf<String?>(null) }

    // Flows sammeln
    val currentSession by sessionManager.currentSession.collectAsState()
    val isRecording by sessionManager.isRecording.collectAsState()
    val exchanges by webView.capturedExchanges.collectAsState()
    val userActions by webView.userActions.collectAsState()
    val pageEvents by webView.pageEvents.collectAsState()
    val isLoading by webView.isLoading.collectAsState()
    val currentUrl by webView.currentUrl.collectAsState()

    // URL-Input synchron halten mit geladener URL
    LaunchedEffect(currentUrl) {
        if (currentUrl.isNotEmpty() && !isRecording) {
            urlInput = currentUrl
        }
    }

    // Exchanges und Actions zur Session hinzufügen
    LaunchedEffect(exchanges) {
        if (isRecording) {
            sessionManager.addExchanges(exchanges)
        }
    }

    LaunchedEffect(userActions) {
        if (isRecording) {
            sessionManager.addUserActions(userActions)
        }
    }

    LaunchedEffect(pageEvents) {
        if (isRecording) {
            sessionManager.addPageEvents(pageEvents)
        }
    }

    // Funktion: Recording starten mit URL
    fun startRecordingWithUrl(sessionName: String, url: String) {
        // 1. Alte Daten löschen
        webView.clearCapturedData()

        // 2. Cache leeren für frischen Start
        webView.clearCache(true)

        // 3. Session starten BEVOR die URL geladen wird
        sessionManager.startSession(sessionName, url)

        // 4. URL frisch laden - JETZT werden alle Requests erfasst!
        val fullUrl = if (url.startsWith("http")) url else "https://$url"
        webView.loadUrl(fullUrl)
    }

    Scaffold(
        topBar = {
            Column {
                // Top App Bar
                TopAppBar(
                    title = { Text("Traffic Capture") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Close, "Schließen")
                        }
                    },
                    actions = {
                        // Stats Button
                        IconButton(onClick = { showStatsPanel = !showStatsPanel }) {
                            BadgedBox(
                                badge = {
                                    if (exchanges.isNotEmpty()) {
                                        Badge { Text("${exchanges.size}") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Analytics, "Statistiken")
                            }
                        }

                        // Recording Button
                        IconButton(
                            onClick = {
                                if (isRecording) {
                                    // STOP Recording
                                    val session = sessionManager.stopSession()
                                    onExportSession(session)
                                } else {
                                    // START Recording - URL merken
                                    pendingRecordUrl = urlInput.ifBlank { currentUrl }
                                    showSessionDialog = true
                                }
                            }
                        ) {
                            Icon(
                                if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                if (isRecording) "Stop" else "Record",
                                tint = if (isRecording) Color.Red else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isRecording)
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surface
                    )
                )

                // URL Bar
                UrlBar(
                    url = urlInput,
                    onUrlChange = { urlInput = it },
                    onGo = {
                        if (isRecording) {
                            // Während Recording: Normal laden (wird erfasst)
                            val url = if (urlInput.startsWith("http")) urlInput
                            else "https://$urlInput"
                            webView.loadUrl(url)
                        } else {
                            // Nicht Recording: Warnung zeigen, dass Traffic nicht erfasst wird
                            val url = if (urlInput.startsWith("http")) urlInput
                            else "https://$urlInput"
                            webView.loadUrl(url)
                        }
                    },
                    onBack = { webView.goBack() },
                    onForward = { webView.goForward() },
                    onRefresh = {
                        if (isRecording) {
                            // Cache leeren und neu laden
                            webView.clearCache(true)
                        }
                        webView.reload()
                    },
                    canGoBack = webView.canGoBack(),
                    canGoForward = webView.canGoForward(),
                    isLoading = isLoading,
                    isRecording = isRecording,
                    onStartRecording = {
                        pendingRecordUrl = urlInput.ifBlank { currentUrl }
                        showSessionDialog = true
                    }
                )

                // Recording Indicator
                AnimatedVisibility(
                    visible = isRecording,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    RecordingIndicator(
                        exchangeCount = currentSession?.exchangeCount ?: 0,
                        actionCount = currentSession?.actionCount ?: 0,
                        sessionName = currentSession?.name ?: ""
                    )
                }

                // Stats Panel
                AnimatedVisibility(
                    visible = showStatsPanel,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    StatsPanel(
                        exchanges = exchanges,
                        userActions = userActions,
                        onClose = { showStatsPanel = false }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // WebView
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )

            // Loading Indicator
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    // Session Start Dialog
    if (showSessionDialog) {
        SessionStartDialog(
            targetUrl = pendingRecordUrl ?: urlInput,
            onDismiss = {
                showSessionDialog = false
                pendingRecordUrl = null
            },
            onConfirm = { name, url ->
                startRecordingWithUrl(name, url)
                showSessionDialog = false
                pendingRecordUrl = null
            }
        )
    }
}

@Composable
private fun UrlBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isLoading: Boolean,
    isRecording: Boolean,
    onStartRecording: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Navigation Buttons
            IconButton(
                onClick = onBack,
                enabled = canGoBack
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
            }

            IconButton(
                onClick = onForward,
                enabled = canGoForward
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Vor")
            }

            IconButton(onClick = onRefresh) {
                Icon(
                    if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                    if (isLoading) "Stop" else "Neu laden"
                )
            }

            // URL Input
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                singleLine = true,
                placeholder = { Text("URL eingeben...") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = { onGo() }
                ),
                trailingIcon = {
                    if (isRecording) {
                        // Während Recording: Go Button
                        IconButton(onClick = onGo) {
                            Icon(Icons.Default.Search, "Los")
                        }
                    } else {
                        // Nicht Recording: Record & Go Button
                        IconButton(onClick = onStartRecording) {
                            Icon(
                                Icons.Default.FiberManualRecord,
                                "Recording starten",
                                tint = Color.Red
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun RecordingIndicator(
    exchangeCount: Int,
    actionCount: Int,
    sessionName: String
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FiberManualRecord,
                    "Recording",
                    tint = Color.Red,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Recording: $sessionName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "$exchangeCount Requests",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "$actionCount Actions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun StatsPanel(
    exchanges: List<TrafficInterceptWebView.CapturedExchange>,
    userActions: List<TrafficInterceptWebView.UserAction>,
    onClose: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Live Traffic",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Schließen")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Stats Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard("Requests", exchanges.size.toString())
                StatCard("Actions", userActions.size.toString())
                StatCard(
                    "Domains",
                    exchanges.mapNotNull {
                        try {
                            java.net.URL(it.url).host
                        } catch (e: Exception) {
                            null
                        }
                    }.distinct().size.toString()
                )
            }

            Spacer(Modifier.height(8.dp))

            // Recent Exchanges
            Text(
                "Letzte Requests",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(exchanges.takeLast(10).reversed()) { exchange ->
                    ExchangeItem(exchange)
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExchangeItem(exchange: TrafficInterceptWebView.CapturedExchange) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Method Badge
        Surface(
            color = when (exchange.method) {
                "GET" -> Color(0xFF4CAF50)
                "POST" -> Color(0xFF2196F3)
                "PUT" -> Color(0xFFFF9800)
                "DELETE" -> Color(0xFFF44336)
                else -> Color.Gray
            },
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = exchange.method,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        // Status
        Text(
            text = exchange.responseStatus?.toString() ?: "...",
            style = MaterialTheme.typography.labelMedium,
            color = when (exchange.responseStatus) {
                in 200..299 -> Color(0xFF4CAF50)
                in 400..499 -> Color(0xFFFF9800)
                in 500..599 -> Color(0xFFF44336)
                else -> MaterialTheme.colorScheme.onSurface
            }
        )

        Spacer(Modifier.width(8.dp))

        // URL
        Text(
            text = try {
                java.net.URL(exchange.url).path.take(50)
            } catch (e: Exception) {
                exchange.url.take(50)
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SessionStartDialog(
    targetUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (sessionName: String, url: String) -> Unit
) {
    var sessionName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(targetUrl) }

    // Auto-generiere Session-Name aus Domain
    LaunchedEffect(url) {
        if (sessionName.isBlank() && url.isNotBlank()) {
            try {
                val domain = java.net.URL(
                    if (url.startsWith("http")) url else "https://$url"
                ).host.removePrefix("www.")
                sessionName = domain.replaceFirstChar { it.uppercase() } + " API"
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FiberManualRecord,
                    null,
                    tint = Color.Red,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Recording starten")
            }
        },
        text = {
            Column {
                // Erklärung
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Die Seite wird frisch geladen. Alle Requests inkl. Redirects werden erfasst!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // URL Input
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Start-URL") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Language, null)
                    }
                )

                Spacer(Modifier.height(12.dp))

                // Session Name Input
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    label = { Text("Session Name") },
                    placeholder = { Text("z.B. Login Flow, Checkout API") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Label, null)
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        sessionName.ifBlank { "Unnamed Session" },
                        url.ifBlank { "https://example.com" }
                    )
                },
                enabled = url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.FiberManualRecord, null)
                Spacer(Modifier.width(8.dp))
                Text("Recording starten")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
