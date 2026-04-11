package info.plateaukao.einkbro.mapper.missiondock.ui

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.core.app.ApplicationProvider
import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import info.plateaukao.einkbro.mapper.missiondock.state.CandidateCardViewState
import info.plateaukao.einkbro.mapper.missiondock.state.DockMode
import info.plateaukao.einkbro.mapper.missiondock.state.DockShellState
import info.plateaukao.einkbro.mapper.missiondock.state.FeedMode
import info.plateaukao.einkbro.mapper.missiondock.state.GuidedCaptureDockPanelState
import info.plateaukao.einkbro.mapper.missiondock.state.ProbeSummaryViewState
import info.plateaukao.einkbro.mapper.missiondock.state.StepGuidanceViewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = "src/main/AndroidManifest.xml",
    application = android.app.Application::class,
)
class GuidedCaptureDockUiTest {

    @Test
    fun handleAndStepActionsAreTouchAccessibleAndWired() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = ConstraintLayout(context)
        val views = GuidedCaptureDockViewsFactory.attach(context, parent)

        var handleTapCount = 0
        var startCount = 0
        var readyCount = 0
        var checkCount = 0
        var pauseCount = 0
        var nextCount = 0

        val adapter = MissionDockCandidateAdapter(
            onToggleExpand = {},
            onSelect = {},
            onExclude = {},
            onTest = {},
            onCopy = {},
        )
        val binder = GuidedCaptureDockBinder(context, views, adapter)
        binder.bindHandlers(
            GuidedCaptureDockBinder.Callbacks(
                onHandleTap = { handleTapCount += 1 },
                onHandleSwipe = {},
                onRefresh = {},
                onCopySummary = {},
                onCollapse = {},
                onModeTap = {},
                onFeedToggle = {},
                onOverflowTap = {},
                onActionStart = { startCount += 1 },
                onActionReady = { readyCount += 1 },
                onActionCheck = { checkCount += 1 },
                onActionPause = { pauseCount += 1 },
                onActionNext = { nextCount += 1 },
            ),
        )

        assertTrue(views.handle.minHeight >= 48)
        assertTrue(views.actionStart.minHeight >= 48)
        assertTrue(views.actionReady.minHeight >= 48)
        assertTrue(views.actionCheck.minHeight >= 48)
        assertTrue(views.actionPause.minHeight >= 48)
        assertTrue(views.actionNext.minHeight >= 48)

        views.handle.performClick()
        views.actionStart.performClick()
        views.actionReady.performClick()
        views.actionCheck.performClick()
        views.actionPause.performClick()
        views.actionNext.performClick()

        assertEquals(1, handleTapCount)
        assertEquals(1, startCount)
        assertEquals(1, readyCount)
        assertEquals(1, checkCount)
        assertEquals(1, pauseCount)
        assertEquals(1, nextCount)
    }

    @Test
    fun collapsedExpandedAndFeedModesRenderAsExpected() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = ConstraintLayout(context)
        val views = GuidedCaptureDockViewsFactory.attach(context, parent)
        val adapter = MissionDockCandidateAdapter(
            onToggleExpand = {},
            onSelect = {},
            onExclude = {},
            onTest = {},
            onCopy = {},
        )
        val binder = GuidedCaptureDockBinder(context, views, adapter)
        binder.bindHandlers(noOpCallbacks())

        binder.render(panelState(mode = DockMode.Collapsed, feedMode = FeedMode.Collapsed))
        assertEquals(View.GONE, views.panel.visibility)

        binder.render(panelState(mode = DockMode.Expanded, feedMode = FeedMode.Collapsed))
        assertEquals(View.VISIBLE, views.panel.visibility)
        assertEquals(View.GONE, views.feedContainer.visibility)

        binder.render(panelState(mode = DockMode.Expanded, feedMode = FeedMode.Expanded))
        assertEquals(View.VISIBLE, views.feedContainer.visibility)
        assertTrue(views.feedSummary.text.toString().contains("search_probe"))
    }

    @Test
    fun candidateCardActionsAndExpandStateWork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var expandId = ""
        var selectId = ""
        var excludeId = ""
        var testId = ""
        var copyId = ""

        val adapter = MissionDockCandidateAdapter(
            onToggleExpand = { expandId = it },
            onSelect = { selectId = it.endpointId },
            onExclude = { excludeId = it.endpointId },
            onTest = { testId = it.endpointId },
            onCopy = { copyId = it.endpointId },
        )
        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, 0)

        adapter.submitList(listOf(sampleCandidate(expanded = false, top = true, lowQuality = false)))
        shadowOf(Looper.getMainLooper()).idle()
        adapter.onBindViewHolder(holder, 0)

        holder.root.performClick()
        holder.selectButton.performClick()
        holder.excludeButton.performClick()
        holder.testButton.performClick()
        holder.copyButton.performClick()

        assertEquals("ep_1", expandId)
        assertEquals("ep_1", selectId)
        assertEquals("ep_1", excludeId)
        assertEquals("ep_1", testId)
        assertEquals("ep_1", copyId)

        val visualAdapter = MissionDockCandidateAdapter(
            onToggleExpand = {},
            onSelect = {},
            onExclude = {},
            onTest = {},
            onCopy = {},
        )
        val visualHolder = visualAdapter.onCreateViewHolder(parent, 0)
        visualAdapter.submitList(listOf(sampleCandidate(expanded = true, top = false, lowQuality = true)))
        shadowOf(Looper.getMainLooper()).idle()
        visualAdapter.onBindViewHolder(visualHolder, 0)
        assertEquals(View.VISIBLE, visualHolder.detail.visibility)
        assertTrue(visualHolder.root.alpha < 1f)
    }

    private fun panelState(mode: DockMode, feedMode: FeedMode): GuidedCaptureDockPanelState {
        return GuidedCaptureDockPanelState(
            shell = DockShellState(
                dockMode = mode,
                feedMode = feedMode,
            ),
            step = StepGuidanceViewState(
                stepId = "search_probe",
                stepTitle = "Search Probe",
                saturationState = "NEEDS_MORE_EVIDENCE",
                progress = 64,
                missingSignals = listOf("search_variants>=2"),
                hints = listOf("zweiten Suchbegriff testen"),
            ),
            probe = ProbeSummaryViewState(
                phaseId = "search_probe",
                correlationSummary = listOf(
                    RuntimeToolkitTelemetry.LiveCorrelationCount(
                        phaseId = "search_probe",
                        routeKind = "search_request",
                        statusBucket = "2xx",
                        hostClass = "api",
                        count = 2,
                    ),
                ),
                recentCorrelated = listOf(
                    RuntimeToolkitTelemetry.LiveCorrelationEntry(
                        statusCode = 200,
                        routeKind = "search",
                        operation = "search_query",
                        normalizedHost = "api.example.org",
                        normalizedPath = "/suche",
                        phaseId = "search_probe",
                    ),
                ),
            ),
            candidates = listOf(sampleCandidate(expanded = false, top = true, lowQuality = false)),
        )
    }

    private fun sampleCandidate(expanded: Boolean, top: Boolean, lowQuality: Boolean): CandidateCardViewState {
        return CandidateCardViewState(
            endpointId = "ep_1",
            role = "search",
            method = "GET",
            host = "api.example.org",
            path = "/suche",
            operation = "getSearchResults",
            score = 129.0,
            confidence = if (lowQuality) 0.62 else 0.89,
            evidenceCount = 6,
            generalizedTemplate = "GET https://api.example.org/suche",
            observedExamples = listOf("https://api.example.org/suche?q=test"),
            rankReasons = listOf("high_evidence"),
            fieldHits = listOf("query:q"),
            runtimeViability = if (lowQuality) "weak_signal" else "likely_runnable",
            warnings = if (lowQuality) listOf("LOW_CONFIDENCE") else emptyList(),
            missingProof = if (lowQuality) listOf("search_variants>=2") else emptyList(),
            exportReadiness = "PARTIAL",
            topCandidate = top,
            lowQuality = lowQuality,
            selected = false,
            excluded = false,
            expanded = expanded,
            testing = false,
            testResult = null,
        )
    }

    private fun noOpCallbacks(): GuidedCaptureDockBinder.Callbacks {
        return GuidedCaptureDockBinder.Callbacks(
            onHandleTap = {},
            onHandleSwipe = {},
            onRefresh = {},
            onCopySummary = {},
            onCollapse = {},
            onModeTap = {},
            onFeedToggle = {},
            onOverflowTap = {},
            onActionStart = {},
            onActionReady = {},
            onActionCheck = {},
            onActionPause = {},
            onActionNext = {},
        )
    }
}
