package dev.fishit.mapper.android.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fishit.mapper.android.di.LocalAppContainer
import dev.fishit.mapper.contract.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    projectId: String,
    sessionId: String,
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current

    var session by remember { mutableStateOf<RecordingSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId, sessionId) {
        error = null
        session = null
        runCatching {
            container.store.loadSession(ProjectId(projectId), SessionId(sessionId))
        }.onSuccess { loaded ->
            session = loaded
        }.onFailure { t ->
            error = t.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session $sessionId") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (error != null) {
                Text("Error: $error")
                return@Column
            }

            val s = session
            if (s == null) {
                Text("Loading…")
                return@Column
            }

            Text("Started: ${s.startedAt}")
            Text("Ended: ${s.endedAt ?: "ongoing"}")
            Text("Initial: ${s.initialUrl}")
            if (s.finalUrl != null) Text("Final: ${s.finalUrl}")
            Text("Events: ${s.events.size}")

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(s.events) { e ->
                    EventRow(e)
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: RecorderEvent) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (e) {
            is NavigationEvent -> {
                Text("NAV  ${e.at}")
                Text(e.url)
                if (e.fromUrl != null) Text("from: ${e.fromUrl}")
            }
            is ResourceRequestEvent -> {
                Text("REQ  ${e.at}  ${e.method ?: ""}  ${e.resourceKind ?: ResourceKind.Other}")
                Text(e.url)
                if (e.initiatorUrl != null) Text("init: ${e.initiatorUrl}")
            }
            is ResourceResponseEvent -> {
                Text("RES  ${e.at}  ${e.statusCode}  ${e.contentType ?: "unknown"}")
                Text(e.url)
                if (e.isRedirect && e.redirectLocation != null) Text("→ ${e.redirectLocation}")
            }
            is ConsoleMessageEvent -> {
                Text("CONSOLE  ${e.at}  ${e.level}")
                Text(e.message)
            }
            is UserActionEvent -> {
                Text("ACTION  ${e.at}  ${e.action}")
                if (e.target != null) Text("target: ${e.target}")
            }
            is CustomEvent -> {
                Text("CUSTOM  ${e.at}  ${e.name}")
                if (e.payload.isNotEmpty()) Text(e.payload.toString())
            }
        }
    }
}
