package dev.fishit.mapper.android.ui.api

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fishit.mapper.engine.api.*
import dev.fishit.mapper.engine.export.ApiExporter

/**
 * Detail-Screen für einen einzelnen API Endpoint.
 *
 * Zeigt alle Details inkl.:
 * - Method, Path, Parameters
 * - Request Body Schema
 * - Response Codes & Bodies
 * - Beispiel-cURL Command
 * - Code-Snippets
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndpointDetailScreen(
    endpoint: ApiEndpoint,
    baseUrl: String,
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val exporter = remember { ApiExporter() }
    var showSnackbar by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MethodBadge(method = endpoint.method)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = endpoint.pathTemplate,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        },
        snackbarHost = {
            if (showSnackbar) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showSnackbar = false }) {
                            Text("OK")
                        }
                    }
                ) {
                    Text("In Zwischenablage kopiert")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Metadata
            item {
                MetadataSection(endpoint = endpoint)
            }

            // cURL Command
            item {
                CurlSection(
                    curlCommand = exporter.toCurl(baseUrl, endpoint),
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(it))
                        showSnackbar = true
                    }
                )
            }

            // Path Parameters
            if (endpoint.pathParameters.isNotEmpty()) {
                item {
                    ParametersSection(
                        title = "Path Parameters",
                        parameters = endpoint.pathParameters
                    )
                }
            }

            // Query Parameters
            if (endpoint.queryParameters.isNotEmpty()) {
                item {
                    ParametersSection(
                        title = "Query Parameters",
                        parameters = endpoint.queryParameters
                    )
                }
            }

            // Header Parameters
            if (endpoint.headerParameters.isNotEmpty()) {
                item {
                    ParametersSection(
                        title = "Headers",
                        parameters = endpoint.headerParameters
                    )
                }
            }

            // Request Body
            endpoint.requestBody?.let { body ->
                item {
                    RequestBodySection(requestBody = body)
                }
            }

            // Responses
            if (endpoint.responses.isNotEmpty()) {
                item {
                    ResponsesSection(responses = endpoint.responses)
                }
            }

            // Examples
            if (endpoint.examples.isNotEmpty()) {
                item {
                    ExamplesSection(
                        examples = endpoint.examples,
                        onViewExample = { /* TODO: Navigate to exchange detail */ }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(endpoint: ApiEndpoint) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Übersicht",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = "Aufrufe",
                    value = endpoint.metadata.hitCount.toString()
                )
                StatItem(
                    label = "Erfolgsrate",
                    value = endpoint.metadata.successRate?.let { "${(it * 100).toInt()}%" } ?: "N/A"
                )
                StatItem(
                    label = "Ø Antwortzeit",
                    value = endpoint.metadata.avgResponseTimeMs?.let { "${it}ms" } ?: "N/A"
                )
            }

            if (endpoint.authRequired != AuthType.None) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Authentifizierung erforderlich: ${endpoint.authRequired.name}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (endpoint.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    endpoint.tags.forEach { tag ->
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
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CurlSection(
    curlCommand: String,
    onCopy: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "cURL",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { onCopy(curlCommand) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Kopieren")
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = curlCommand,
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ParametersSection(
    title: String,
    parameters: List<ApiParameter>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            parameters.forEach { param ->
                ParameterRow(parameter = param)
                if (param != parameters.last()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ParameterRow(parameter: ApiParameter) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = parameter.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
                if (parameter.required) {
                    Text(
                        text = "*",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            TypeBadge(type = parameter.type)
        }

        parameter.description?.let { desc ->
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (parameter.observedValues.isNotEmpty()) {
            Text(
                text = "Beispiele: ${parameter.observedValues.take(3).joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TypeBadge(type: ParameterType) {
    val color = when (type) {
        ParameterType.STRING -> Color(0xFF4CAF50)
        ParameterType.INTEGER -> Color(0xFF2196F3)
        ParameterType.NUMBER -> Color(0xFF9C27B0)
        ParameterType.BOOLEAN -> Color(0xFFFF9800)
        ParameterType.ARRAY -> Color(0xFFE91E63)
        ParameterType.OBJECT -> Color(0xFF607D8B)
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = type.name.lowercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun RequestBodySection(requestBody: RequestBodySpec) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Request Body",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = requestBody.contentType,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (requestBody.examples.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = requestBody.examples.first().value.take(500),
                        modifier = Modifier
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponsesSection(responses: List<ResponseSpec>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Responses",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            responses.forEach { response ->
                ResponseRow(response = response)
                if (response != responses.last()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ResponseRow(response: ResponseSpec) {
    val statusColor = when (response.statusCode) {
        in 200..299 -> Color(0xFF4CAF50)
        in 300..399 -> Color(0xFF2196F3)
        in 400..499 -> Color(0xFFFF9800)
        in 500..599 -> Color(0xFFF44336)
        else -> Color.Gray
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = statusColor,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = response.statusCode.toString(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = response.description ?: "Response",
                style = MaterialTheme.typography.bodyMedium
            )
            response.contentType?.let { ct ->
                Text(
                    text = ct,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ExamplesSection(
    examples: List<String>,
    onViewExample: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Beispiele (${examples.size})",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Echte Requests aus dem importierten Traffic",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            examples.take(5).forEach { exchangeId ->
                TextButton(onClick = { onViewExample(exchangeId) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = exchangeId,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (examples.size > 5) {
                Text(
                    text = "+${examples.size - 5} weitere",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
