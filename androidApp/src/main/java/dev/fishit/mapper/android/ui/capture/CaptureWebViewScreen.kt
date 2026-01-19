package dev.fishit.mapper.android.ui.capture

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import dev.fishit.mapper.android.ui.export.ExportDialog
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
    startUrl: String = "https://",
    onExportSession: (CaptureSessionManager.CaptureSession) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // WebView und Session Manager
    val webView = remember { TrafficInterceptWebView(context) }
    val sessionManager = remember { CaptureSessionManager(context) }

    // State - URL Input startet mit der übergebenen Start-URL (wird NICHT automatisch geladen!)
    var urlInput by remember { mutableStateOf(startUrl) }
    // Track ob WebView schon eine URL geladen hat (verhindert doppeltes Laden)
    var hasLoadedInitialUrl by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }
    var showStatsPanel by remember { mutableStateOf(false) }
    var showChainpointDialog by remember { mutableStateOf(false) }
    var showSessionEditor by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showSessionsList by remember { mutableStateOf(false) }
    var pendingRecordUrl by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Session für Export speichern (bleibt erhalten nach Stop)
    var sessionToExport by remember { mutableStateOf<CaptureSessionManager.CaptureSession?>(null) }

    // Gespeicherte Sessions beim Start laden
    LaunchedEffect(Unit) {
        sessionManager.loadSavedSessions()
    }

    // Flows sammeln
    val currentSession by sessionManager.currentSession.collectAsState()
    val completedSessions by sessionManager.completedSessions.collectAsState()
    val isRecording by sessionManager.isRecording.collectAsState()
    val isPaused by sessionManager.isPaused.collectAsState()
    val exchanges by webView.capturedExchanges.collectAsState()
    val userActions by webView.userActions.collectAsState()
    val pageEvents by webView.pageEvents.collectAsState()
    val isLoading by webView.isLoading.collectAsState()
    val currentUrl by webView.currentUrl.collectAsState()
    val nextChainpointLabel by sessionManager.nextChainpointLabel.collectAsState()
    val chainpointLabels by sessionManager.chainpointLabels.collectAsState()
    val interceptionEnabled by webView.interceptionEnabled.collectAsState()

    // Interception mit Recording-State synchronisieren
    LaunchedEffect(isRecording, isPaused) {
        // Interception NUR aktiv wenn Recording läuft UND nicht pausiert
        webView.setInterceptionEnabled(isRecording && !isPaused)
    }

    // URL-Input synchron halten mit geladener URL
    LaunchedEffect(currentUrl) {
        if (!currentUrl.isNullOrEmpty()) {
            urlInput = currentUrl ?: ""
        }
    }

    // Exchanges und Actions zur Session hinzufügen - NUR wenn aktiv recording (nicht pausiert)
    LaunchedEffect(exchanges) {
        if (isRecording && !isPaused) {
            sessionManager.addExchanges(exchanges)
        }
    }

    LaunchedEffect(userActions) {
        if (isRecording && !isPaused) {
            sessionManager.addUserActions(userActions)
        }
    }

    LaunchedEffect(pageEvents) {
        if (isRecording && !isPaused) {
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
        // Interception wird automatisch durch LaunchedEffect aktiviert
        sessionManager.startSession(sessionName, url)

        // 4. Markiere dass wir jetzt eine URL geladen haben
        hasLoadedInitialUrl = true

        // 5. URL frisch laden - Requests werden erfasst sobald Interception aktiv
        val fullUrl = if (url.startsWith("http")) url else "https://$url"
        webView.loadUrl(fullUrl)
    }

    // Funktion: URL OHNE Recording laden (normales Browsen)
    fun loadUrlWithoutRecording(url: String) {
        hasLoadedInitialUrl = true
        val fullUrl = if (url.startsWith("http")) url else "https://$url"
        webView.loadUrl(fullUrl)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

                        // Sessions-Liste Button (wenn gespeicherte Sessions vorhanden)
                        if (completedSessions.isNotEmpty()) {
                            IconButton(onClick = { showSessionsList = true }) {
                                BadgedBox(
                                    badge = { Badge { Text("${completedSessions.size}") } }
                                ) {
                                    Icon(Icons.Default.History, "Gespeicherte Sessions")
                                }
                            }
                        }

                        // Export Button (wenn Session zum Exportieren vorhanden)
                        if (sessionToExport != null || (currentSession != null && !isRecording)) {
                            IconButton(onClick = {
                                if (sessionToExport == null && currentSession != null) {
                                    sessionToExport = currentSession
                                }
                                showExportDialog = true
                            }) {
                                Icon(Icons.Default.FileDownload, "Exportieren")
                            }
                        }

                        // Pause Button (nur während Recording sichtbar)
                        if (isRecording) {
                            IconButton(
                                onClick = {
                                    if (isPaused) {
                                        sessionManager.resumeSession()
                                        snackbarMessage = "Recording fortgesetzt"
                                    } else {
                                        sessionManager.pauseSession()
                                        snackbarMessage = "Recording pausiert - Navigation ohne Erfassung möglich"
                                    }
                                }
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    if (isPaused) "Fortsetzen" else "Pausieren",
                                    tint = if (isPaused) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Recording Button (Start/Stop)
                        IconButton(
                            onClick = {
                                if (isRecording) {
                                    // STOP Recording - Session für Export speichern, dann Dialog zeigen
                                    val stoppedSession = sessionManager.stopSession()
                                    sessionToExport = stoppedSession
                                    showExportDialog = true
                                    snackbarMessage = "Session '${stoppedSession.name}' gespeichert (${stoppedSession.exchangeCount} Requests)"
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
                        containerColor = when {
                            isRecording && isPaused -> Color(0xFFFF9800).copy(alpha = 0.2f) // Orange für pausiert
                            isRecording -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) // Rot für recording
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                )

                // URL Bar - mit Optionen für Recording oder normales Browsen
                UrlBar(
                    url = urlInput,
                    onUrlChange = { urlInput = it },
                    onGo = {
                        // URL IMMER laden - Recording oder nicht
                        val url = if (urlInput.startsWith("http")) urlInput
                        else "https://$urlInput"
                        hasLoadedInitialUrl = true
                        webView.loadUrl(url)
                    },
                    onBack = {
                        // Navigation IMMER erlauben
                        if (webView.canGoBack()) {
                            webView.goBack()
                        }
                    },
                    onForward = {
                        // Navigation IMMER erlauben
                        if (webView.canGoForward()) {
                            webView.goForward()
                        }
                    },
                    onRefresh = {
                        // Refresh IMMER erlauben
                        if (isRecording) {
                            // Mit Recording: Cache leeren für saubere Erfassung
                            webView.clearCache(true)
                        }
                        webView.reload()
                    },
                    canGoBack = webView.canGoBack(), // IMMER basierend auf WebView-State
                    canGoForward = webView.canGoForward(), // IMMER basierend auf WebView-State
                    isLoading = isLoading,
                    isRecording = isRecording,
                    onStartRecording = {
                        pendingRecordUrl = urlInput.ifBlank { currentUrl }
                        showSessionDialog = true
                    }
                )

                // Recording Indicator mit Chainpoint-Button
                AnimatedVisibility(
                    visible = isRecording,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    RecordingIndicator(
                        exchangeCount = currentSession?.exchangeCount ?: 0,
                        actionCount = currentSession?.actionCount ?: 0,
                        sessionName = currentSession?.name ?: "",
                        pendingChainpointLabel = nextChainpointLabel,
                        onSetChainpoint = { showChainpointDialog = true },
                        onClearChainpoint = { sessionManager.setNextChainpointLabel(null) },
                        onEditSession = { showSessionEditor = true }
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
                        chainpointLabels = chainpointLabels,
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
            // WebView mit korrektem Scrolling und Focus-Support
            AndroidView(
                factory = {
                    webView.apply {
                        // WICHTIG: Focus-Handling für Tastatur-Input auf Eingabefeldern
                        isFocusable = true
                        isFocusableInTouchMode = true

                        // WICHTIG: Scrolling aktivieren
                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = true
                        isScrollbarFadingEnabled = true
                        scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_OVERLAY

                        // OverScroll für besseres Scroll-Feeling
                        overScrollMode = android.view.View.OVER_SCROLL_ALWAYS

                        // Nested Scrolling für Compose-Kompatibilität
                        isNestedScrollingEnabled = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // Focus und Scrolling bei jedem Update sicherstellen
                    view.isFocusable = true
                    view.isFocusableInTouchMode = true
                    view.isVerticalScrollBarEnabled = true
                    view.isHorizontalScrollBarEnabled = true
                    view.isNestedScrollingEnabled = true
                }
            )

            // Start-Overlay - zeigt Optionen wenn noch keine URL geladen
            if (!hasLoadedInitialUrl) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Web,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            urlInput.ifBlank { "URL eingeben" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(Modifier.height(8.dp))

                        // Option 1: Mit Recording starten
                        Button(
                            onClick = {
                                pendingRecordUrl = urlInput
                                showSessionDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(0.8f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red.copy(alpha = 0.9f)
                            )
                        ) {
                            Icon(Icons.Default.FiberManualRecord, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Mit Recording starten")
                        }

                        Text(
                            "Alle API-Requests werden erfasst",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(16.dp))

                        // Option 2: Ohne Recording browsen
                        OutlinedButton(
                            onClick = {
                                loadUrlWithoutRecording(urlInput)
                            },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Ohne Recording öffnen")
                        }

                        Text(
                            "Normales Browsen - Recording später starten",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Pause-Indicator Overlay (wenn pausiert)
            if (isRecording && isPaused) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    color = Color(0xFFFF9800),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Pause,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "PAUSIERT - Navigation ohne Erfassung",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

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

    // Chainpoint Label Dialog
    if (showChainpointDialog) {
        ChainpointLabelDialog(
            onDismiss = { showChainpointDialog = false },
            onConfirm = { label ->
                sessionManager.setNextChainpointLabel(label)
                showChainpointDialog = false
            }
        )
    }

    // Session Editor
    if (showSessionEditor && currentSession != null) {
        SessionEditorSheet(
            session = currentSession!!,
            chainpointLabels = chainpointLabels,
            onDismiss = { showSessionEditor = false },
            onDeleteExchange = { sessionManager.deleteExchange(it) },
            onDeleteAction = { sessionManager.deleteUserAction(it) },
            onUpdateChainpointLabel = { actionId, label ->
                sessionManager.updateChainpointLabel(actionId, label)
            },
            onUpdateExchangeUrl = { exchangeId, url ->
                sessionManager.updateExchangeUrl(exchangeId, url)
            },
            onUpdateExchangeRequestBody = { exchangeId, body ->
                sessionManager.updateExchangeRequestBody(exchangeId, body)
            },
            onUpdateExchangeResponseBody = { exchangeId, body ->
                sessionManager.updateExchangeResponseBody(exchangeId, body)
            }
        )
    }

    // Export Dialog - nutzt sessionToExport statt currentSession
    if (showExportDialog && sessionToExport != null) {
        ExportDialog(
            session = sessionToExport!!,
            onDismiss = {
                showExportDialog = false
                // Callback für Navigation
                onExportSession(sessionToExport!!)
            },
            onExportComplete = { message ->
                snackbarMessage = message
            }
        )
    }

    // Sessions-Liste Dialog
    if (showSessionsList) {
        SavedSessionsDialog(
            sessions = completedSessions,
            onDismiss = { showSessionsList = false },
            onSelectSession = { session ->
                sessionToExport = session
                showSessionsList = false
                showExportDialog = true
            },
            onDeleteSession = { session ->
                // TODO: Implementiere Session-Löschung
                snackbarMessage = "Session '${session.name}' gelöscht"
            }
        )
    }

    // Snackbar für Export-Nachrichten
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
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
    sessionName: String,
    pendingChainpointLabel: String?,
    onSetChainpoint: () -> Unit,
    onClearChainpoint: () -> Unit,
    onEditSession: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Obere Zeile: Recording-Info und Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "$exchangeCount Req",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "$actionCount Act",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Untere Zeile: Chainpoint-Label und Edit-Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Chainpoint Label Button
                if (pendingChainpointLabel != null) {
                    // Label ist gesetzt - zeige es an
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        onClick = onClearChainpoint
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Flag,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = pendingChainpointLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Close,
                                "Entfernen",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    // Kein Label - Button zum Setzen
                    OutlinedButton(
                        onClick = onSetChainpoint,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Flag,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Chainpoint benennen",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Edit Session Button
                OutlinedButton(
                    onClick = onEditSession,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Bearbeiten",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsPanel(
    exchanges: List<TrafficInterceptWebView.CapturedExchange>,
    userActions: List<TrafficInterceptWebView.UserAction>,
    chainpointLabels: Map<String, String>,
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
/**
 * Dialog zum Benennen des nächsten Chainpoints.
 */
@Composable
private fun ChainpointLabelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Flag,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Nächsten Chainpoint benennen")
            }
        },
        text = {
            Column {
                Text(
                    "Gib einen Namen für den nächsten Klick-Punkt ein. " +
                    "Der nächste Klick wird diesem Label zugeordnet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Chainpoint Name") },
                    placeholder = { Text("z.B. Login Button, Submit Form") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Flag, null)
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(label) },
                enabled = label.isNotBlank()
            ) {
                Text("Setzen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

/**
 * Bottom Sheet zum Bearbeiten der Session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionEditorSheet(
    session: CaptureSessionManager.CaptureSession,
    chainpointLabels: Map<String, String>,
    onDismiss: () -> Unit,
    onDeleteExchange: (String) -> Unit,
    onDeleteAction: (String) -> Unit,
    onUpdateChainpointLabel: (String, String?) -> Unit,
    onUpdateExchangeUrl: (String, String) -> Unit,
    onUpdateExchangeRequestBody: (String, String?) -> Unit,
    onUpdateExchangeResponseBody: (String, String?) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedExchangeId by remember { mutableStateOf<String?>(null) }
    var selectedActionId by remember { mutableStateOf<String?>(null) }
    var editMode by remember { mutableStateOf<EditMode?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Session bearbeiten",
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Schließen")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Exchanges (${session.exchanges.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Actions (${session.userActions.size})") }
                )
            }

            Spacer(Modifier.height(8.dp))

            // Content
            when (selectedTab) {
                0 -> ExchangesList(
                    exchanges = session.exchanges,
                    selectedId = selectedExchangeId,
                    onSelect = { selectedExchangeId = it },
                    onDelete = onDeleteExchange,
                    onEditUrl = { id -> editMode = EditMode.ExchangeUrl(id) },
                    onEditRequestBody = { id -> editMode = EditMode.RequestBody(id) },
                    onEditResponseBody = { id -> editMode = EditMode.ResponseBody(id) }
                )
                1 -> ActionsList(
                    actions = session.userActions,
                    chainpointLabels = chainpointLabels,
                    selectedId = selectedActionId,
                    onSelect = { selectedActionId = it },
                    onDelete = onDeleteAction,
                    onEditLabel = { id -> editMode = EditMode.ActionLabel(id) }
                )
            }
        }
    }

    // Edit Dialogs
    editMode?.let { mode ->
        when (mode) {
            is EditMode.ExchangeUrl -> {
                val exchange = session.exchanges.find { it.id == mode.exchangeId }
                if (exchange != null) {
                    TextEditDialog(
                        title = "URL bearbeiten",
                        initialValue = exchange.url,
                        onDismiss = { editMode = null },
                        onConfirm = { newValue ->
                            onUpdateExchangeUrl(mode.exchangeId, newValue)
                            editMode = null
                        }
                    )
                }
            }
            is EditMode.RequestBody -> {
                val exchange = session.exchanges.find { it.id == mode.exchangeId }
                if (exchange != null) {
                    TextEditDialog(
                        title = "Request Body bearbeiten",
                        initialValue = exchange.requestBody ?: "",
                        multiLine = true,
                        onDismiss = { editMode = null },
                        onConfirm = { newValue ->
                            onUpdateExchangeRequestBody(mode.exchangeId, newValue.ifBlank { null })
                            editMode = null
                        }
                    )
                }
            }
            is EditMode.ResponseBody -> {
                val exchange = session.exchanges.find { it.id == mode.exchangeId }
                if (exchange != null) {
                    TextEditDialog(
                        title = "Response Body bearbeiten",
                        initialValue = exchange.responseBody ?: "",
                        multiLine = true,
                        onDismiss = { editMode = null },
                        onConfirm = { newValue ->
                            onUpdateExchangeResponseBody(mode.exchangeId, newValue.ifBlank { null })
                            editMode = null
                        }
                    )
                }
            }
            is EditMode.ActionLabel -> {
                val currentLabel = chainpointLabels[mode.actionId] ?: ""
                TextEditDialog(
                    title = "Chainpoint Label bearbeiten",
                    initialValue = currentLabel,
                    onDismiss = { editMode = null },
                    onConfirm = { newValue ->
                        onUpdateChainpointLabel(mode.actionId, newValue.ifBlank { null })
                        editMode = null
                    }
                )
            }
        }
    }
}

private sealed class EditMode {
    data class ExchangeUrl(val exchangeId: String) : EditMode()
    data class RequestBody(val exchangeId: String) : EditMode()
    data class ResponseBody(val exchangeId: String) : EditMode()
    data class ActionLabel(val actionId: String) : EditMode()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExchangesList(
    exchanges: List<TrafficInterceptWebView.CapturedExchange>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onEditUrl: (String) -> Unit,
    onEditRequestBody: (String) -> Unit,
    onEditResponseBody: (String) -> Unit
) {
    LazyColumn {
        items(exchanges, key = { it.id }) { exchange ->
            val isSelected = exchange.id == selectedId

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .animateItemPlacement(),
                onClick = { onSelect(if (isSelected) null else exchange.id) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    exchange.method,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            // Status
                            Text(
                                exchange.responseStatus?.toString() ?: "...",
                                style = MaterialTheme.typography.labelMedium,
                                color = when (exchange.responseStatus) {
                                    in 200..299 -> Color(0xFF4CAF50)
                                    in 400..499 -> Color(0xFFFF9800)
                                    in 500..599 -> Color(0xFFF44336)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }

                        // Delete Button
                        IconButton(onClick = { onDelete(exchange.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                "Löschen",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // URL (mit Copy-Button)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            exchange.url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (isSelected) 5 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        CopyButton(exchange.url)
                    }

                    // Expanded Details
                    AnimatedVisibility(visible = isSelected) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            // Edit Actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onEditUrl(exchange.id) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("URL", style = MaterialTheme.typography.labelSmall)
                                }

                                if (exchange.requestBody != null) {
                                    OutlinedButton(
                                        onClick = { onEditRequestBody(exchange.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Req Body", style = MaterialTheme.typography.labelSmall)
                                    }
                                }

                                if (exchange.responseBody != null) {
                                    OutlinedButton(
                                        onClick = { onEditResponseBody(exchange.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Resp Body", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            // Request Body Preview
                            exchange.requestBody?.let { body ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Request Body:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                CopyableText(body.take(500))
                            }

                            // Response Body Preview
                            exchange.responseBody?.let { body ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Response Body:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                CopyableText(body.take(500))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionsList(
    actions: List<TrafficInterceptWebView.UserAction>,
    chainpointLabels: Map<String, String>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onEditLabel: (String) -> Unit
) {
    LazyColumn {
        items(actions, key = { it.id }) { action ->
            val isSelected = action.id == selectedId
            val label = chainpointLabels[action.id]

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .animateItemPlacement(),
                onClick = { onSelect(if (isSelected) null else action.id) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Action Type Icon
                            Icon(
                                when (action.type) {
                                    TrafficInterceptWebView.ActionType.CLICK -> Icons.Default.TouchApp
                                    TrafficInterceptWebView.ActionType.SUBMIT -> Icons.Default.Send
                                    TrafficInterceptWebView.ActionType.INPUT -> Icons.Default.Keyboard
                                    TrafficInterceptWebView.ActionType.NAVIGATION -> Icons.Default.Navigation
                                },
                                action.type.name,
                                tint = MaterialTheme.colorScheme.primary
                            )

                            Spacer(Modifier.width(8.dp))

                            Column {
                                Text(
                                    action.type.name,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (label != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Row {
                            // Edit Label Button
                            IconButton(onClick = { onEditLabel(action.id) }) {
                                Icon(
                                    Icons.Default.Flag,
                                    "Label bearbeiten",
                                    tint = if (label != null) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Delete Button
                            IconButton(onClick = { onDelete(action.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Löschen",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Target
                    Text(
                        "Target: ${action.target}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (isSelected) 5 else 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Value (if any)
                    action.value?.let { value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Value: $value",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (isSelected) 3 else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            CopyButton(value)
                        }
                    }

                    // Page URL
                    action.pageUrl?.let { pageUrl ->
                        Text(
                            "Page: $pageUrl",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val context = LocalContext.current

    IconButton(
        onClick = {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("FishIT", text)
            clipboard.setPrimaryClip(clip)
        },
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            Icons.Default.ContentCopy,
            "Kopieren",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyableText(text: String) {
    val context = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("FishIT", text)
                            clipboard.setPrimaryClip(clip)
                        }
                    )
            )
            CopyButton(text)
        }
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initialValue: String,
    multiLine: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (multiLine) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 300.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                )
            } else {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

/**
 * Dialog zur Anzeige gespeicherter Sessions mit Export-Möglichkeit.
 */
@Composable
private fun SavedSessionsDialog(
    sessions: List<CaptureSessionManager.CaptureSession>,
    onDismiss: () -> Unit,
    onSelectSession: (CaptureSessionManager.CaptureSession) -> Unit,
    onDeleteSession: (CaptureSessionManager.CaptureSession) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gespeicherte Sessions (${sessions.size})") },
        text = {
            if (sessions.isEmpty()) {
                Text("Keine gespeicherten Sessions vorhanden.")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions.sortedByDescending { it.startedAt }) { session ->
                        SavedSessionItem(
                            session = session,
                            onExport = { onSelectSession(session) },
                            onDelete = { onDeleteSession(session) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )
}

/**
 * Einzelnes Session-Item in der Liste.
 */
@Composable
private fun SavedSessionItem(
    session: CaptureSessionManager.CaptureSession,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatSessionDate(session.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onExport) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = "Exportieren",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Löschen",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "📡 ${session.exchangeCount} Requests",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "👆 ${session.actionCount} Actions",
                    style = MaterialTheme.typography.bodySmall
                )
                session.duration?.let { durationMs ->
                    Text(
                        text = "⏱️ ${formatDuration(durationMs)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            session.targetUrl?.let { url ->
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun formatSessionDate(instant: kotlinx.datetime.Instant): String {
    // Einfaches Format ohne TimeZone-Konvertierung
    val isoString = instant.toString() // Format: 2024-01-15T10:30:00Z
    return isoString.take(16).replace("T", " ")
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
