package dev.fishit.mapper.android.ui.api

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fishit.mapper.engine.api.*

/**
 * Hauptscreen für die API Blueprint Anzeige.
 *
 * Zeigt den vollständigen analysierten API Blueprint mit:
 * - Übersicht (Statistiken)
 * - Endpoint-Liste
 * - Auth-Patterns
 * - Erkannte Flows
 * - Export-Optionen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiBlueprintScreen(
    blueprint: ApiBlueprint,
    onEndpointClick: (ApiEndpoint) -> Unit,
    onFlowClick: (ApiFlow) -> Unit,
    onExportClick: (ExportFormat) -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Übersicht", "Endpoints", "Auth", "Flows")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(blueprint.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    var showExportMenu by remember { mutableStateOf(false) }

                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Exportieren")
                    }

                    DropdownMenu(
                        expanded = showExportMenu,
                        onDismissRequest = { showExportMenu = false }
                    ) {
                        ExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.displayName) },
                                leadingIcon = { Icon(format.icon, contentDescription = null) },
                                onClick = {
                                    showExportMenu = false
                                    onExportClick(format)
                                }
                            )
                        }
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
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            // Tab Content
            when (selectedTab) {
                0 -> OverviewTab(blueprint)
                1 -> EndpointsTab(blueprint.endpoints, onEndpointClick)
                2 -> AuthTab(blueprint.authPatterns)
                3 -> FlowsTab(blueprint.flows, onFlowClick)
            }
        }
    }
}

@Composable
private fun OverviewTab(blueprint: ApiBlueprint) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Statistik-Karten
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Endpoints",
                    value = blueprint.metadata.uniqueEndpointsDetected.toString(),
                    icon = Icons.Default.Api
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Exchanges",
                    value = blueprint.metadata.totalExchangesAnalyzed.toString(),
                    icon = Icons.Default.SwapHoriz
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Auth Patterns",
                    value = blueprint.metadata.authPatternsDetected.toString(),
                    icon = Icons.Default.Security
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Flows",
                    value = blueprint.metadata.flowsDetected.toString(),
                    icon = Icons.Default.AccountTree
                )
            }
        }

        item {
            // Coverage
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "API Coverage",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { blueprint.metadata.coveragePercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${blueprint.metadata.coveragePercent.toInt()}% der Requests zu Endpoints gemappt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            // Base URL Info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Base URL",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = blueprint.baseUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Description
        blueprint.description?.let { desc ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Beschreibung",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointsTab(
    endpoints: List<ApiEndpoint>,
    onEndpointClick: (ApiEndpoint) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterMethod by remember { mutableStateOf<HttpMethod?>(null) }

    val filteredEndpoints = endpoints.filter { endpoint ->
        val matchesSearch = searchQuery.isEmpty() ||
            endpoint.pathTemplate.contains(searchQuery, ignoreCase = true) ||
            endpoint.id.contains(searchQuery, ignoreCase = true)

        val matchesMethod = filterMethod == null || endpoint.method == filterMethod

        matchesSearch && matchesMethod
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search & Filter
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Endpoint suchen...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )

        // Method Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterMethod == null,
                onClick = { filterMethod = null },
                label = { Text("Alle") }
            )
            HttpMethod.entries.take(4).forEach { method ->
                FilterChip(
                    selected = filterMethod == method,
                    onClick = { filterMethod = if (filterMethod == method) null else method },
                    label = { Text(method.name) }
                )
            }
        }

        // Endpoint List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredEndpoints, key = { it.id }) { endpoint ->
                EndpointCard(endpoint = endpoint, onClick = { onEndpointClick(endpoint) })
            }
        }
    }
}

@Composable
private fun EndpointCard(
    endpoint: ApiEndpoint,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Method Badge
            MethodBadge(method = endpoint.method)

            Spacer(modifier = Modifier.width(12.dp))

            // Path & Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = endpoint.pathTemplate,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${endpoint.metadata.hitCount}x",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (endpoint.authRequired != AuthType.None) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Auth required",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MethodBadge(method: HttpMethod) {
    val (color, textColor) = when (method) {
        HttpMethod.GET -> Color(0xFF61AFFE) to Color.White
        HttpMethod.POST -> Color(0xFF49CC90) to Color.White
        HttpMethod.PUT -> Color(0xFFFCA130) to Color.Black
        HttpMethod.PATCH -> Color(0xFF50E3C2) to Color.Black
        HttpMethod.DELETE -> Color(0xFFF93E3E) to Color.White
        else -> Color.Gray to Color.White
    }

    Surface(
        color = color,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = method.name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AuthTab(authPatterns: List<AuthPattern>) {
    if (authPatterns.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Security,
            title = "Keine Auth-Patterns erkannt",
            description = "Die API scheint keine Authentifizierung zu verwenden oder sie wurde nicht erkannt."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(authPatterns, key = { it.id }) { pattern ->
            AuthPatternCard(pattern = pattern)
        }
    }
}

@Composable
private fun AuthPatternCard(pattern: AuthPattern) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (pattern) {
                        is AuthPattern.BearerTokenPattern -> Icons.Default.Key
                        is AuthPattern.SessionCookiePattern -> Icons.Default.Cookie
                        is AuthPattern.ApiKeyPattern -> Icons.Default.VpnKey
                        is AuthPattern.BasicAuthPattern -> Icons.Default.Person
                        is AuthPattern.OAuth2Pattern -> Icons.Default.CloudSync
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = pattern.type.name,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (pattern) {
                is AuthPattern.BearerTokenPattern -> {
                    DetailRow("Header", pattern.headerName)
                    DetailRow("Prefix", pattern.tokenPrefix)
                }
                is AuthPattern.SessionCookiePattern -> {
                    DetailRow("Cookie", pattern.cookieName)
                    pattern.domain?.let { DetailRow("Domain", it) }
                }
                is AuthPattern.ApiKeyPattern -> {
                    DetailRow("Location", pattern.location.name)
                    DetailRow("Parameter", pattern.parameterName)
                }
                is AuthPattern.OAuth2Pattern -> {
                    DetailRow("Token Endpoint", pattern.tokenEndpoint)
                    DetailRow("Grant Type", pattern.grantType)
                    if (pattern.scopes.isNotEmpty()) {
                        DetailRow("Scopes", pattern.scopes.joinToString(", "))
                    }
                }
                is AuthPattern.BasicAuthPattern -> {
                    Text(
                        text = "HTTP Basic Authentication",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowsTab(
    flows: List<ApiFlow>,
    onFlowClick: (ApiFlow) -> Unit
) {
    if (flows.isEmpty()) {
        EmptyState(
            icon = Icons.Default.AccountTree,
            title = "Keine Flows erkannt",
            description = "Importiere Traffic mit User Actions um API-Flows zu erkennen."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(flows, key = { it.id }) { flow ->
            FlowCard(flow = flow, onClick = { onFlowClick(flow) })
        }
    }
}

@Composable
private fun FlowCard(
    flow: ApiFlow,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = flow.name,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            flow.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Steps Preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                flow.steps.take(5).forEachIndexed { index, step ->
                    if (index > 0) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (flow.steps.size > 5) {
                    Text(
                        text = "+${flow.steps.size - 5}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Tags
            if (flow.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    flow.tags.forEach { tag ->
                        SuggestionChip(
                            onClick = { },
                            label = { Text(tag) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Export-Formate für den API Blueprint.
 */
enum class ExportFormat(
    val displayName: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    OPENAPI("OpenAPI / Swagger", Icons.Default.Code),
    CURL("cURL Commands", Icons.Default.Terminal),
    POSTMAN("Postman Collection", Icons.Default.Folder),
    TYPESCRIPT("TypeScript Client", Icons.Default.Javascript),
    KOTLIN("Kotlin Client", Icons.Default.Android)
}
