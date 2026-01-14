package dev.fishit.mapper.android.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fishit.mapper.contract.RecordingSession

@Composable
fun SessionsScreen(
    sessions: List<RecordingSession>,
    onOpenSession: (sessionId: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sessions: ${'$'}{sessions.size}")

        if (sessions.isEmpty()) {
            Text("No sessions yet. Use the Browser tab to record one.")
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sessions) { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSession(session.id.value) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Session ${'$'}{session.id.value}")
                        Spacer(Modifier.height(6.dp))
                        Text("Started: ${'$'}{session.startedAt}")
                        Text("Events: ${'$'}{session.events.size}")
                        Text("Initial: ${'$'}{session.initialUrl}")
                        if (session.finalUrl != null) {
                            Text("Final: ${'$'}{session.finalUrl}")
                        }
                    }
                }
            }
        }
    }
}
