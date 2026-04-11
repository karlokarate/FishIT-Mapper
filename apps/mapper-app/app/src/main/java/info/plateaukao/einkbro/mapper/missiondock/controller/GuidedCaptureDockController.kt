package info.plateaukao.einkbro.mapper.missiondock.controller

import android.app.AlertDialog
import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.mapper.missiondock.data.GuidedCaptureDockDataSource
import info.plateaukao.einkbro.mapper.missiondock.format.GuidedCaptureDockFormatter
import info.plateaukao.einkbro.mapper.missiondock.state.AutoAdvanceUiState
import info.plateaukao.einkbro.mapper.missiondock.state.CandidateCardViewState
import info.plateaukao.einkbro.mapper.missiondock.state.CandidateDecisionEvent
import info.plateaukao.einkbro.mapper.missiondock.state.CandidateDecisionState
import info.plateaukao.einkbro.mapper.missiondock.state.CandidateDecisionStateMachine
import info.plateaukao.einkbro.mapper.missiondock.state.DockEffect
import info.plateaukao.einkbro.mapper.missiondock.state.DockEvent
import info.plateaukao.einkbro.mapper.missiondock.state.DockMode
import info.plateaukao.einkbro.mapper.missiondock.state.DockShellState
import info.plateaukao.einkbro.mapper.missiondock.state.DockShellStateMachine
import info.plateaukao.einkbro.mapper.missiondock.state.GuidedCaptureDockPanelState
import info.plateaukao.einkbro.mapper.missiondock.state.MissionState
import info.plateaukao.einkbro.mapper.missiondock.state.ProbeSummaryViewState
import info.plateaukao.einkbro.mapper.missiondock.state.StepGuidanceStateMachine
import info.plateaukao.einkbro.mapper.missiondock.state.StepGuidanceViewState
import info.plateaukao.einkbro.mapper.missiondock.ui.GuidedCaptureDockBinder
import info.plateaukao.einkbro.mapper.missiondock.ui.GuidedCaptureDockViews
import info.plateaukao.einkbro.mapper.missiondock.ui.GuidedCaptureDockViewsFactory
import info.plateaukao.einkbro.mapper.missiondock.ui.MissionDockCandidateAdapter
import java.util.Locale

class GuidedCaptureDockController(
    private val context: Context,
    private val host: GuidedCaptureDockHost,
) {
    private val dataSource = GuidedCaptureDockDataSource(context)
    private val shellMachine = DockShellStateMachine()
    private val stepMachine = StepGuidanceStateMachine()
    private val decisionMachine = CandidateDecisionStateMachine()

    private var shellState = DockShellState()
    private var stepState = StepGuidanceViewState(autoAdvance = AutoAdvanceUiState.None)
    private var decisionState = CandidateDecisionState()
    private var panelState: GuidedCaptureDockPanelState? = null

    private var views: GuidedCaptureDockViews? = null
    private var binder: GuidedCaptureDockBinder? = null
    private var candidateAdapter: MissionDockCandidateAdapter? = null
    private var pendingRefreshRunnable: Runnable? = null
    private var latestCandidateById: Map<String, RuntimeToolkitTelemetry.LiveEndpointCandidate> = emptyMap()

    fun attach(parent: ConstraintLayout) {
        if (views != null) return
        val newViews = GuidedCaptureDockViewsFactory.attach(context, parent)
        val adapter = MissionDockCandidateAdapter(
            onToggleExpand = { endpointId ->
                dispatchShellEvent(DockEvent.CandidateExpandToggled(endpointId))
                panelState?.let { render(it.copy(shell = shellState)) }
            },
            onSelect = { card ->
                host.selectEndpoint(card.role, card.endpointId, card.score)
                refresh(force = true)
            },
            onExclude = { card ->
                host.excludeEndpoint(card.endpointId, !card.excluded)
                refresh(force = true)
            },
            onTest = { card ->
                val candidate = latestCandidateById[card.endpointId] ?: return@MissionDockCandidateAdapter
                decisionState = decisionMachine.reduce(
                    decisionState,
                    CandidateDecisionEvent.TestStarted(card.endpointId),
                )
                host.testEndpoint(candidate) {
                    refresh(force = true)
                }
                refresh(force = true)
            },
            onCopy = { card ->
                host.copyToClipboard(
                    label = "Candidate ${card.endpointId}",
                    value = GuidedCaptureDockFormatter.formatCandidate(card),
                )
            },
        )
        val newBinder = GuidedCaptureDockBinder(context, newViews, adapter)
        newBinder.bindHandlers(
            GuidedCaptureDockBinder.Callbacks(
                onHandleTap = {
                    dispatchShellEvent(DockEvent.HandleTapped)
                    refresh(force = true)
                },
                onHandleSwipe = {
                    dispatchShellEvent(DockEvent.HandleTapped)
                    refresh(force = true)
                },
                onRefresh = { refresh(force = true) },
                onCopySummary = {
                    panelState?.let { state ->
                        host.copyToClipboard(
                            label = "Wizard Summary",
                            value = GuidedCaptureDockFormatter.formatSummary(state),
                        )
                    }
                },
                onCollapse = { setDockMode(DockMode.Collapsed) },
                onModeTap = {
                    dispatchShellEvent(DockEvent.HandleTapped)
                    refresh(force = true)
                },
                onFeedToggle = {
                    dispatchShellEvent(DockEvent.ToggleFeed)
                    panelState?.let { render(it.copy(shell = shellState)) }
                },
                onOverflowTap = { showOverflowMenu() },
                onActionStart = { host.runStepStart() },
                onActionReady = { host.runStepReady() },
                onActionCheck = { host.runStepCheck() },
                onActionPause = { host.runStepPauseOrHold() },
                onActionNext = { host.runStepNextOrUndo() },
            ),
        )
        views = newViews
        candidateAdapter = adapter
        binder = newBinder
    }

    fun onActivityStarted() {
        dispatchShellEvent(DockEvent.ActivityStarted)
        refresh(force = true)
    }

    fun onActivityStopped() {
        dispatchShellEvent(DockEvent.ActivityStopped)
        cancelRefresh()
    }

    fun onMissionUiTick(force: Boolean = false) {
        refresh(force = force)
    }

    fun setDockMode(mode: DockMode) {
        dispatchShellEvent(DockEvent.SetDockMode(mode))
        refresh(force = true)
    }

    fun currentDockMode(): DockMode {
        return shellState.dockMode
    }

    fun release() {
        cancelRefresh()
    }

    private fun refresh(force: Boolean) {
        if (views == null || binder == null) return
        val snapshot = dataSource.load()
        if (snapshot.missionState.missionId.isBlank()) {
            dispatchShellEvent(DockEvent.MissionEnded)
            binder?.hide()
            return
        }

        if (shellState.missionState != MissionState.Active) {
            dispatchShellEvent(DockEvent.MissionActivated)
        }

        val sameHash = snapshot.hash == shellState.latestSnapshotHash
        decisionState = decisionMachine.reduce(
            decisionState,
            CandidateDecisionEvent.SyncFromOverrides(snapshot.endpointOverrides),
        )
        stepState = stepMachine.project(
            current = stepState,
            missionState = snapshot.missionState,
            displayName = snapshot.stepDisplayName,
            feedback = snapshot.feedback,
            readyWindow = snapshot.readyWindow,
            readyHit = snapshot.readyHit,
            statusText = buildStatusText(snapshot),
            exportReadiness = snapshot.exportReadiness,
        )

        val nextPanelState = buildPanelState(snapshot)
        if (!force && sameHash) {
            scheduleRefreshFromShell()
            return
        }

        dispatchShellEvent(DockEvent.SnapshotLoaded(snapshot.hash, snapshot.focusedRole))
        render(nextPanelState.copy(shell = shellState))
        scheduleRefreshFromShell()
    }

    private fun buildPanelState(
        snapshot: GuidedCaptureDockDataSource.SnapshotPayload,
    ): GuidedCaptureDockPanelState {
        val focusedRole = snapshot.focusedRole
        val roleOrder = linkedSetOf<String>().apply {
            if (!focusedRole.isNullOrBlank()) add(focusedRole)
            addAll(snapshot.liveSnapshot.endpointCandidates.keys)
        }
        val flattened = mutableListOf<RuntimeToolkitTelemetry.LiveEndpointCandidate>()
        roleOrder.forEach { role ->
            flattened += snapshot.liveSnapshot.endpointCandidates[role].orEmpty()
                .sortedByDescending { it.score }
        }
        val bestCandidate = flattened.maxByOrNull { it.score }
        val reordered = if (bestCandidate != null) {
            listOf(bestCandidate) + flattened.filterNot { it.endpointId == bestCandidate.endpointId }
        } else {
            flattened
        }
        latestCandidateById = reordered.associateBy { it.endpointId }

        val cards = reordered.map { candidate ->
            val testResult = decisionState.lastTestResults[candidate.endpointId]
            val warnings = candidateWarnings(candidate, testResult)
            val lowQuality = candidate.confidence < 0.7 || candidate.score < scoreThreshold(candidate.role)
            val fieldHits = buildFieldHits(candidate)
            val runtimeViability = runtimeViabilityLabel(
                candidate = candidate,
                excluded = decisionState.excludedEndpoints.contains(candidate.endpointId),
                testResult = testResult,
                lowQuality = lowQuality,
            )
            val missingProofLinks = candidateMissingProofLinks(
                missingSignals = stepState.missingSignals,
                candidate = candidate,
            )
            CandidateCardViewState(
                endpointId = candidate.endpointId,
                role = candidate.role,
                method = candidate.method,
                host = candidate.normalizedHost,
                path = candidate.normalizedPath,
                operation = candidate.requestOperation,
                score = candidate.score,
                confidence = candidate.confidence,
                evidenceCount = candidate.evidenceCount,
                generalizedTemplate = "${candidate.method.uppercase(Locale.ROOT)} https://${candidate.normalizedHost}${candidate.normalizedPath}",
                observedExamples = listOf(candidate.sampleUrl).filter { it.isNotBlank() },
                rankReasons = candidateRankReasons(candidate),
                fieldHits = fieldHits,
                runtimeViability = runtimeViability,
                warnings = warnings,
                missingProof = missingProofLinks,
                exportReadiness = stepState.exportReadiness,
                topCandidate = bestCandidate?.endpointId == candidate.endpointId,
                lowQuality = lowQuality,
                selected = decisionState.selectedEndpointByRole[candidate.role] == candidate.endpointId,
                excluded = decisionState.excludedEndpoints.contains(candidate.endpointId),
                expanded = shellState.expandedCandidateIds.contains(candidate.endpointId),
                testing = decisionState.testingEndpointIds.contains(candidate.endpointId),
                testResult = testResult,
            )
        }

        val bestCard = cards.firstOrNull { it.topCandidate } ?: cards.firstOrNull()
        stepState = stepState.copy(
            topCandidateSummary = bestCard?.let {
                "${it.role} ${it.method} ${it.path} (${it.score.toInt()}|${"%.2f".format(Locale.ROOT, it.confidence)})"
            }.orEmpty(),
            blockersSummary = buildBlockersSummary(stepState.missingSignals, bestCard),
        )

        val probeState = ProbeSummaryViewState(
            phaseId = snapshot.liveSnapshot.phaseId,
            correlationSummary = snapshot.liveSnapshot.correlationSummary,
            recentCorrelated = snapshot.liveSnapshot.recentCorrelated,
        )
        val result = GuidedCaptureDockPanelState(
            shell = shellState,
            step = stepState,
            probe = probeState,
            candidates = cards,
        )
        panelState = result
        return result
    }

    private fun scoreThreshold(role: String): Double {
        return when (role) {
            "playbackResolver" -> 128.0
            "search" -> 126.0
            "detail" -> 124.0
            "home" -> 120.0
            "auth", "refresh" -> 118.0
            else -> 120.0
        }
    }

    private fun buildFieldHits(candidate: RuntimeToolkitTelemetry.LiveEndpointCandidate): List<String> {
        val hits = mutableListOf<String>()
        if (candidate.requestOperation.isNotBlank()) {
            hits += "operation:${candidate.requestOperation}"
        }
        candidate.queryParamNames.sorted().forEach { name ->
            hits += "query:$name"
        }
        candidate.bodyFieldNames.sorted().forEach { name ->
            hits += "body:$name"
        }
        if (hits.isEmpty()) hits += "none"
        return hits
    }

    private fun candidateRankReasons(candidate: RuntimeToolkitTelemetry.LiveEndpointCandidate): List<String> {
        val reasons = mutableListOf<String>()
        if (candidate.evidenceCount >= 6) reasons += "high_evidence"
        if (candidate.score >= scoreThreshold(candidate.role)) reasons += "score_above_role_threshold"
        if (candidate.requestOperation.contains("search", ignoreCase = true)) reasons += "operation_matches_search"
        if (candidate.requestOperation.contains("detail", ignoreCase = true)) reasons += "operation_matches_detail"
        if (candidate.requestOperation.contains("playback", ignoreCase = true) ||
            candidate.normalizedPath.contains("/ptmd/", ignoreCase = true) ||
            candidate.normalizedPath.contains("/tmd/", ignoreCase = true)
        ) {
            reasons += "playback_signal_present"
        }
        if (reasons.isEmpty()) reasons += "baseline_ranked"
        return reasons
    }

    private fun candidateWarnings(
        candidate: RuntimeToolkitTelemetry.LiveEndpointCandidate,
        testResult: RuntimeToolkitTelemetry.EndpointOverrideTestResult?,
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (candidate.confidence < 0.7) warnings += "LOW_CONFIDENCE"
        if (candidate.score < scoreThreshold(candidate.role)) warnings += "LOW_SCORE"
        val path = candidate.normalizedPath.lowercase(Locale.ROOT)
        if (path.contains("/event") || path.endsWith(".m3u8") || path.endsWith(".mp4")) {
            warnings += "NOISY_OR_ASSET_LIKE"
        }
        if (testResult != null && !testResult.ok) warnings += "LAST_TEST_FAILED"
        return warnings
    }

    private fun runtimeViabilityLabel(
        candidate: RuntimeToolkitTelemetry.LiveEndpointCandidate,
        excluded: Boolean,
        testResult: RuntimeToolkitTelemetry.EndpointOverrideTestResult?,
        lowQuality: Boolean,
    ): String {
        if (excluded) return "excluded_by_user"
        if (testResult != null) {
            return if (testResult.ok) "tested_ok" else "tested_failed"
        }
        if (candidate.confidence >= 0.82 && !lowQuality) return "likely_runnable"
        if (candidate.confidence >= 0.70) return "needs_validation"
        return "weak_signal"
    }

    private fun candidateMissingProofLinks(
        missingSignals: List<String>,
        candidate: RuntimeToolkitTelemetry.LiveEndpointCandidate,
    ): List<String> {
        if (missingSignals.isEmpty()) return emptyList()
        val links = mutableListOf<String>()
        missingSignals.forEach { signal ->
            val normalized = signal.lowercase(Locale.ROOT)
            when {
                normalized.contains("search") && candidate.role == "search" -> links += signal
                normalized.contains("detail") && candidate.role == "detail" -> links += signal
                normalized.contains("playback") && candidate.role == "playbackResolver" -> links += signal
                normalized.contains("auth") && (candidate.role == "auth" || candidate.role == "refresh") -> links += signal
                normalized.contains("ready_window") && candidate.role in setOf("search", "detail", "playbackResolver", "auth") -> links += signal
            }
        }
        return links.distinct()
    }

    private fun buildBlockersSummary(
        missingSignals: List<String>,
        bestCard: CandidateCardViewState?,
    ): String {
        val blockers = mutableListOf<String>()
        if (missingSignals.isNotEmpty()) blockers += "missing:${missingSignals.take(2).joinToString(",")}"
        if (bestCard != null) {
            if (bestCard.lowQuality) blockers += "top_candidate_low_quality"
            if (bestCard.warnings.isNotEmpty()) blockers += "top_warnings:${bestCard.warnings.take(2).joinToString(",")}"
            if (bestCard.missingProof.isEmpty() && missingSignals.isNotEmpty()) blockers += "top_candidate_not_covering_missing_proof"
        }
        return blockers.joinToString(" | ")
    }

    private fun render(state: GuidedCaptureDockPanelState) {
        binder?.render(state)
    }

    private fun buildStatusText(snapshot: GuidedCaptureDockDataSource.SnapshotPayload): String {
        val now = System.currentTimeMillis()
        return when {
            snapshot.readyWindow.withinWindow -> {
                val left = ((snapshot.readyWindow.expiresAtEpochMillis - now).coerceAtLeast(0L) / 1000L).toInt()
                context.getString(
                    R.string.mapper_wizard_overlay_status_ready_window,
                    left,
                    snapshot.readyWindow.armedStepId,
                )
            }
            snapshot.readyHit.recent -> context.getString(R.string.mapper_wizard_overlay_status_ready_hit)
            else -> "${context.getString(R.string.mapper_wizard_overlay_status_idle)} (${snapshot.feedback.reason})"
        }
    }

    private fun dispatchShellEvent(event: DockEvent) {
        val (next, effects) = shellMachine.reduce(shellState, event)
        shellState = next
        applyEffects(effects)
    }

    private fun applyEffects(effects: List<DockEffect>) {
        effects.forEach { effect ->
            when (effect) {
                DockEffect.StopRefresh -> cancelRefresh()
                is DockEffect.StartRefresh -> scheduleRefresh(effect.intervalMs)
                DockEffect.RebindPanel -> panelState?.let { render(it.copy(shell = shellState)) }
            }
        }
    }

    private fun scheduleRefreshFromShell() {
        if (shellState.refreshState.running) {
            scheduleRefresh(shellState.refreshState.intervalMs)
        } else {
            cancelRefresh()
        }
    }

    private fun scheduleRefresh(intervalMs: Long) {
        if (intervalMs <= 0L || views == null) {
            cancelRefresh()
            return
        }
        cancelRefresh()
        val runnable = Runnable {
            pendingRefreshRunnable = null
            refresh(force = false)
        }
        pendingRefreshRunnable = runnable
        views?.root?.postDelayed(runnable, intervalMs)
    }

    private fun cancelRefresh() {
        pendingRefreshRunnable?.let { runnable ->
            views?.root?.removeCallbacks(runnable)
        }
        pendingRefreshRunnable = null
    }

    private fun showOverflowMenu() {
        val state = panelState ?: return
        val actions = arrayOf(
            context.getString(R.string.mapper_wizard_live_copy_summary),
            context.getString(R.string.mapper_wizard_overlay_retry),
            context.getString(R.string.mapper_wizard_overlay_skip_optional),
            context.getString(R.string.mapper_wizard_overlay_finish),
            context.getString(R.string.mapper_mission_export_summary_title),
            context.getString(R.string.mapper_wizard_overlay_anchor_create),
            context.getString(R.string.mapper_wizard_overlay_anchor_label),
            context.getString(R.string.mapper_wizard_overlay_anchor_remove),
            context.getString(R.string.mapper_wizard_overlay_menu_minimize),
        )
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.mapper_wizard_status_title))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> host.copyToClipboard(
                        "Wizard Summary",
                        GuidedCaptureDockFormatter.formatSummary(state),
                    )
                    1 -> host.runStepRetry()
                    2 -> host.runStepSkipOptional()
                    3 -> host.runStepFinish()
                    4 -> host.runExportSummary()
                    5 -> host.runAnchorCreate()
                    6 -> host.runAnchorLabel()
                    7 -> host.runAnchorRemove()
                    8 -> setDockMode(DockMode.Collapsed)
                }
            }
            .show()
    }
}
