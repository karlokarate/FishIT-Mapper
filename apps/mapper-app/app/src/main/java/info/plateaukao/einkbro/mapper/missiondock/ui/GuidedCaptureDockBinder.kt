package info.plateaukao.einkbro.mapper.missiondock.ui

import android.content.Context
import android.view.View
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import dev.fishit.mapper.wave01.debug.RuntimeToolkitMissionWizard
import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.mapper.missiondock.state.CandidateCardViewState
import info.plateaukao.einkbro.mapper.missiondock.state.DockMode
import info.plateaukao.einkbro.mapper.missiondock.state.FeedMode
import info.plateaukao.einkbro.mapper.missiondock.state.GuidedCaptureDockPanelState

class GuidedCaptureDockBinder(
    private val context: Context,
    private val views: GuidedCaptureDockViews,
    private val candidateAdapter: MissionDockCandidateAdapter,
) {

    data class Callbacks(
        val onHandleTap: () -> Unit,
        val onHandleSwipe: () -> Unit,
        val onRefresh: () -> Unit,
        val onCopySummary: () -> Unit,
        val onCollapse: () -> Unit,
        val onModeTap: () -> Unit,
        val onFeedToggle: () -> Unit,
        val onOverflowTap: () -> Unit,
        val onActionStart: () -> Unit,
        val onActionReady: () -> Unit,
        val onActionCheck: () -> Unit,
        val onActionPause: () -> Unit,
        val onActionNext: () -> Unit,
    )

    fun bindHandlers(callbacks: Callbacks) {
        views.handle.setOnClickListener { callbacks.onHandleTap() }
        views.handle.setOnTouchListener(object : View.OnTouchListener {
            private var downX: Float = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                    }
                    MotionEvent.ACTION_UP -> {
                        val delta = downX - event.rawX
                        if (delta > 28f * context.resources.displayMetrics.density) {
                            callbacks.onHandleSwipe()
                            return true
                        }
                    }
                }
                return false
            }
        })
        views.refresh.setOnClickListener { callbacks.onRefresh() }
        views.copySummary.setOnClickListener { callbacks.onCopySummary() }
        views.collapse.setOnClickListener { callbacks.onCollapse() }
        views.mode.setOnClickListener { callbacks.onModeTap() }
        views.feedToggle.setOnClickListener { callbacks.onFeedToggle() }
        views.overflow.setOnClickListener { callbacks.onOverflowTap() }
        views.actionStart.setOnClickListener { callbacks.onActionStart() }
        views.actionReady.setOnClickListener { callbacks.onActionReady() }
        views.actionCheck.setOnClickListener { callbacks.onActionCheck() }
        views.actionPause.setOnClickListener { callbacks.onActionPause() }
        views.actionNext.setOnClickListener { callbacks.onActionNext() }
        views.candidateList.adapter = candidateAdapter
    }

    fun render(state: GuidedCaptureDockPanelState) {
        views.root.visibility = View.VISIBLE
        renderShell(state)
        renderStepState(state)
        renderCandidates(state.candidates)
        renderFeed(state)
    }

    fun hide() {
        views.root.visibility = View.GONE
    }

    private fun renderShell(state: GuidedCaptureDockPanelState) {
        val mode = state.shell.dockMode
        views.panel.visibility = if (mode == DockMode.Collapsed) View.GONE else View.VISIBLE
        val missingCount = state.step.missingSignals.size
        val stepShort = state.step.stepId.uppercase().take(4).ifBlank { "WIZ" }
        val satShort = when (state.step.saturationState) {
            RuntimeToolkitMissionWizard.SATURATION_SATURATED -> "SAT"
            RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE -> "MORE"
            RuntimeToolkitMissionWizard.SATURATION_BLOCKED -> "BLK"
            else -> "RUN"
        }
        views.handle.text = when (mode) {
            DockMode.Collapsed -> "$stepShort\n${state.step.progress}%\n$satShort"
            DockMode.Peek -> "⟨\n${state.step.progress}%"
            DockMode.Expanded -> "⟩"
        }
        views.mode.text = when (mode) {
            DockMode.Collapsed -> safeString(R.string.mapper_wizard_dock_mode_collapsed, "Collapsed")
            DockMode.Peek -> safeString(R.string.mapper_wizard_dock_mode_peek, "Peek")
            DockMode.Expanded -> safeString(R.string.mapper_wizard_dock_mode_expanded, "Expanded")
        }
        views.feedToggle.text = safeString(
            if (state.shell.feedMode == FeedMode.Expanded) {
                R.string.mapper_wizard_live_hide
            } else {
                R.string.mapper_wizard_live_show
            },
            if (state.shell.feedMode == FeedMode.Expanded) "Hide" else "Show",
        )
        views.peekSummary.visibility = if (mode == DockMode.Peek) View.VISIBLE else View.GONE
        views.peekSummary.text = buildString {
            append("status=")
            append(state.step.saturationState)
            append(" ")
            append(state.step.progress)
            append("% | missing=")
            append(missingCount)
            if (state.step.topCandidateSummary.isNotBlank()) {
                append(" | top=")
                append(state.step.topCandidateSummary)
            }
        }
        if (mode == DockMode.Peek) {
            views.candidateList.layoutParams.height = dp(124)
            views.feedContainer.visibility = View.GONE
        } else if (mode == DockMode.Expanded) {
            views.candidateList.layoutParams.height = dp(220)
        }
    }

    private fun renderStepState(state: GuidedCaptureDockPanelState) {
        val step = state.step
        views.title.text = "${safeString(R.string.mapper_wizard_live_title, "Live Panel")} · ${step.stepTitle}"
        views.statusBadge.text = step.saturationState
        views.saturationPercent.text = "${step.progress}%"
        val badgeColor = when (step.saturationState) {
            RuntimeToolkitMissionWizard.SATURATION_SATURATED -> android.R.color.holo_green_dark
            RuntimeToolkitMissionWizard.SATURATION_BLOCKED -> android.R.color.holo_red_dark
            RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE -> android.R.color.holo_orange_dark
            else -> android.R.color.darker_gray
        }
        views.statusBadge.setTextColor(ContextCompat.getColor(context, badgeColor))
        views.stepTitle.text = "${step.stepTitle} (${step.stepId})"
        views.stepState.text = "${step.saturationState} | ${step.progress}%"
        views.stepProgress.progress = step.progress.coerceIn(0, 100)
        views.stepMissing.text = runCatching {
            context.getString(
                R.string.mapper_wizard_overlay_missing_prefix,
                step.missingSignals.take(3).joinToString(", ").ifBlank { "-" },
            )
        }.getOrElse {
            "Missing: ${step.missingSignals.take(3).joinToString(", ").ifBlank { "-" }}"
        }
        views.stepHints.text = runCatching {
            context.getString(
                R.string.mapper_wizard_overlay_hints_prefix,
                step.hints.take(3).joinToString(" | ").ifBlank { "-" },
            )
        }.getOrElse {
            "Hints: ${step.hints.take(3).joinToString(" | ").ifBlank { "-" }}"
        }
        views.stepStatus.text = step.statusText
        views.stepExportReadiness.text = "Export: ${step.exportReadiness}"
        views.stepCandidateInsight.text = buildString {
            if (step.topCandidateSummary.isNotBlank()) {
                append("Top candidate: ")
                append(step.topCandidateSummary)
            }
            if (step.blockersSummary.isNotBlank()) {
                if (isNotBlank()) append(" | ")
                append("Blockers: ")
                append(step.blockersSummary)
            }
        }.ifBlank { "Top candidate: -" }
        val stateColor = when (step.saturationState) {
            RuntimeToolkitMissionWizard.SATURATION_SATURATED -> android.R.color.holo_green_dark
            RuntimeToolkitMissionWizard.SATURATION_BLOCKED -> android.R.color.holo_red_dark
            RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE -> android.R.color.holo_orange_dark
            else -> android.R.color.black
        }
        views.stepState.setTextColor(ContextCompat.getColor(context, stateColor))
        views.actionReady.visibility = if (RuntimeToolkitTelemetry.isReadyActionStep(step.stepId)) View.VISIBLE else View.GONE
        views.actionPause.text = safeString(R.string.mapper_wizard_overlay_pause, "Pause")
        views.actionNext.text = safeString(R.string.mapper_wizard_overlay_next, "Next")
    }

    private fun renderCandidates(candidates: List<CandidateCardViewState>) {
        candidateAdapter.submitList(candidates)
    }

    private fun renderFeed(state: GuidedCaptureDockPanelState) {
        views.feedContainer.visibility = if (
            state.shell.dockMode == DockMode.Expanded && state.shell.feedMode == FeedMode.Expanded
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (views.feedContainer.visibility != View.VISIBLE) return

        val lines = mutableListOf<String>()
        state.probe.correlationSummary.take(6).forEach { row ->
            lines += "${row.phaseId} | ${row.routeKind} | ${row.statusBucket} | ${row.hostClass} (${row.count})"
        }
        state.probe.recentCorrelated.takeLast(8).forEach { entry ->
            lines += "${entry.statusCode} ${entry.routeKind} ${entry.operation} ${entry.normalizedHost}${entry.normalizedPath}"
        }
        views.feedSummary.text = if (lines.isEmpty()) {
            safeString(R.string.mapper_wizard_live_empty, "No live data yet.")
        } else {
            lines.joinToString("\n")
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun safeString(resId: Int, fallback: String): String {
        return runCatching { context.getString(resId) }.getOrElse { fallback }
    }
}
