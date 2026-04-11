package info.plateaukao.einkbro.mapper.missiondock.state

import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry

enum class MissionState {
    Inactive,
    Active,
}

enum class DockMode {
    Collapsed,
    Peek,
    Expanded,
}

enum class FeedMode {
    Collapsed,
    Expanded,
}

data class RefreshState(
    val running: Boolean,
    val intervalMs: Long,
)

data class DockShellState(
    val missionState: MissionState = MissionState.Inactive,
    val dockMode: DockMode = DockMode.Collapsed,
    val feedMode: FeedMode = FeedMode.Collapsed,
    val refreshState: RefreshState = RefreshState(running = false, intervalMs = 0L),
    val expandedCandidateIds: Set<String> = emptySet(),
    val focusedRole: String? = null,
    val latestSnapshotHash: String = "",
    val activityVisible: Boolean = false,
)

data class StepGuidanceViewState(
    val stepId: String = "",
    val stepTitle: String = "",
    val saturationState: String = "",
    val progress: Int = 0,
    val missingSignals: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
    val reason: String = "",
    val exportReadiness: String = "NOT_READY",
    val statusText: String = "",
    val topCandidateSummary: String = "",
    val blockersSummary: String = "",
    val readyWindowState: ReadyWindowUiState = ReadyWindowUiState.Inactive,
    val autoAdvance: AutoAdvanceUiState = AutoAdvanceUiState.None,
)

sealed class ReadyWindowUiState {
    data object Inactive : ReadyWindowUiState()
    data class Armed(val expiresAtMs: Long, val stepId: String) : ReadyWindowUiState()
    data class Hit(val stepId: String, val hitAt: String) : ReadyWindowUiState()
}

sealed class AutoAdvanceUiState {
    data object None : AutoAdvanceUiState()
    data class Scheduled(val expiresAtMs: Long) : AutoAdvanceUiState()
    data class UndoWindow(val expiresAtMs: Long) : AutoAdvanceUiState()
}

data class ProbeSummaryViewState(
    val phaseId: String = "",
    val correlationSummary: List<RuntimeToolkitTelemetry.LiveCorrelationCount> = emptyList(),
    val recentCorrelated: List<RuntimeToolkitTelemetry.LiveCorrelationEntry> = emptyList(),
)

data class CandidateCardViewState(
    val endpointId: String,
    val role: String,
    val method: String,
    val host: String,
    val path: String,
    val operation: String,
    val score: Double,
    val confidence: Double,
    val evidenceCount: Int,
    val generalizedTemplate: String,
    val observedExamples: List<String>,
    val rankReasons: List<String>,
    val fieldHits: List<String>,
    val runtimeViability: String,
    val warnings: List<String>,
    val missingProof: List<String>,
    val exportReadiness: String,
    val topCandidate: Boolean,
    val lowQuality: Boolean,
    val selected: Boolean,
    val excluded: Boolean,
    val expanded: Boolean,
    val testing: Boolean,
    val testResult: RuntimeToolkitTelemetry.EndpointOverrideTestResult?,
)

sealed class DockEffect {
    data object StopRefresh : DockEffect()
    data class StartRefresh(val intervalMs: Long) : DockEffect()
    data object RebindPanel : DockEffect()
}

sealed class DockEvent {
    data object MissionActivated : DockEvent()
    data object MissionEnded : DockEvent()
    data object HandleTapped : DockEvent()
    data class SetDockMode(val mode: DockMode) : DockEvent()
    data object ToggleFeed : DockEvent()
    data object RefreshTick : DockEvent()
    data class SnapshotLoaded(val hash: String, val focusedRole: String?) : DockEvent()
    data class CandidateExpandToggled(val endpointId: String) : DockEvent()
    data object ActivityStarted : DockEvent()
    data object ActivityStopped : DockEvent()
}

data class CandidateDecisionState(
    val selectedEndpointByRole: Map<String, String> = emptyMap(),
    val excludedEndpoints: Set<String> = emptySet(),
    val lastTestResults: Map<String, RuntimeToolkitTelemetry.EndpointOverrideTestResult> = emptyMap(),
    val testingEndpointIds: Set<String> = emptySet(),
)

sealed class CandidateDecisionEvent {
    data class SelectCandidate(val role: String, val endpointId: String) : CandidateDecisionEvent()
    data class ExcludeCandidate(val endpointId: String, val excluded: Boolean) : CandidateDecisionEvent()
    data class TestStarted(val endpointId: String) : CandidateDecisionEvent()
    data class TestFinished(
        val endpointId: String,
        val result: RuntimeToolkitTelemetry.EndpointOverrideTestResult?,
    ) : CandidateDecisionEvent()
    data class SyncFromOverrides(val overrides: RuntimeToolkitTelemetry.EndpointOverrides) : CandidateDecisionEvent()
}

data class GuidedCaptureDockPanelState(
    val shell: DockShellState,
    val step: StepGuidanceViewState,
    val probe: ProbeSummaryViewState,
    val candidates: List<CandidateCardViewState>,
)
