package dev.fishit.mapper.wave01.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuntimeToolkitTelemetryWizardFlowTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mapper_toolkit_runtime_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("mapper_toolkit_v2", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        RuntimeToolkitTelemetry.clearRuntimeArtifacts(context)
    }

    @Test
    fun capture_defaults_to_off() {
        assertFalse(RuntimeToolkitTelemetry.isCaptureEnabled(context))
        val snapshot = RuntimeToolkitTelemetry.runtimeSettingsSnapshot(context)
        assertEquals(false, snapshot["capture_enabled"])
    }

    @Test
    fun mission_start_keeps_capture_off_until_target_url() {
        RuntimeToolkitTelemetry.setCaptureEnabled(context, true)
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        assertFalse(RuntimeToolkitTelemetry.isCaptureEnabled(context))
    }

    @Test
    fun ready_window_arms_and_hits_on_matching_search_request() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "test")
        val armed = RuntimeToolkitTelemetry.beginWizardReadyWindow(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE,
            source = "unit_test",
        )
        assertTrue(armed.active)
        RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/search?q=heute-journal",
            method = "GET",
            headers = emptyMap(),
        )
        val afterHit = RuntimeToolkitTelemetry.readyWindowState(context)
        assertFalse(afterHit.active)
    }

    @Test
    fun mission_snapshot_contains_feedback_and_ready_window_payload() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        val snapshot = RuntimeToolkitTelemetry.missionSessionSnapshot(context)
        assertNotNull(snapshot["step_progress_percent"])
        assertNotNull(snapshot["step_missing_signals"])
        assertNotNull(snapshot["step_user_hints"])
        val readyWindow = snapshot["ready_window"] as? Map<*, *>
        assertNotNull(readyWindow)
        assertTrue(readyWindow!!.containsKey("active"))
        assertTrue(readyWindow.containsKey("armed_step_id"))
    }

    @Test
    fun live_snapshot_exposes_recent_correlation_and_candidates() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setActivePhaseId(context, "search_probe")
        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/api/search?q=heute",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/api/search?q=heute",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"results":[{"title":"Search Title"}]}""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        assertTrue(snapshot.recentCorrelated.isNotEmpty())
        assertTrue(snapshot.endpointCandidates.isNotEmpty())
    }

    @Test
    fun live_snapshot_exposes_feed_first_internal_signals_and_playback_resolution_chain() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")

        RuntimeToolkitTelemetry.setActivePhaseId(context, "home_probe")
        val homeReq = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/kategorien",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/kategorien",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"rows":[{"title":"Dokus","items":[{"title":"A","canonicalId":"a1"}]}]}""".toByteArray(),
            requestId = homeReq.requestId,
        )

        RuntimeToolkitTelemetry.setActivePhaseId(context, "playback_probe")
        val playbackReq = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://api.zdf.de/tmd/2/ngplayer_2_5/vod/ptmd/tivi/a1",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://api.zdf.de/tmd/2/ngplayer_2_5/vod/ptmd/tivi/a1",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"manifestUrl":"https://zdfvod.akamaized.net/.../master.m3u8"}""".toByteArray(),
            requestId = playbackReq.requestId,
        )
        val manifestReq = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://zdfvod.akamaized.net/i/mp4/de/tivi/master.m3u8",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://zdfvod.akamaized.net/i/mp4/de/tivi/master.m3u8",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/vnd.apple.mpegurl",
            headers = emptyMap(),
            rawBody = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=476000\nvariant.m3u8".toByteArray(),
            requestId = manifestReq.requestId,
        )

        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_HOME_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "home_probe")
        val homeSnapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val homeCandidates = homeSnapshot.endpointCandidates.values.flatten()
        assertTrue(homeCandidates.isNotEmpty())

        val homeCandidate = homeCandidates.firstOrNull { it.normalizedPath == "/kategorien" }
        assertNotNull(homeCandidate)
        assertTrue(homeCandidate!!.internalSignals.contains("entry_surface") || homeCandidate.internalSignals.contains("collection_feed"))
        assertTrue(homeCandidate.topologyHints.contains("category_collection") || homeCandidate.topologyHints.contains("entry_surface_route"))

        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "playback_probe")
        val playbackSnapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val playbackCandidates = playbackSnapshot.endpointCandidates.values.flatten()
        assertTrue(playbackCandidates.isNotEmpty())

        val playbackCandidate = playbackCandidates.firstOrNull { it.normalizedPath.contains("/ptmd/") || it.normalizedPath.contains("/tmd/") }
        assertNotNull(playbackCandidate)
        assertTrue(playbackCandidate!!.internalSignals.contains("playback_resolution"))

        val manifestCandidate = playbackCandidates.firstOrNull { it.normalizedPath.endsWith(".m3u8") }
        assertNotNull(manifestCandidate)
    }

    @Test
    fun live_snapshot_uses_response_body_hints_for_collection_feed_without_entry_surface_leak() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_HOME_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setActivePhaseId(context, "home_probe")

        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://api.zdf.de/graphql",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://api.zdf.de/graphql",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"rows":[{"title":"Top-Serien","items":[{"title":"S1","canonicalId":"s1"}]}]}""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val candidates = snapshot.endpointCandidates.values.flatten()
        assertTrue(candidates.isNotEmpty())
        val graphqlCandidate = candidates.firstOrNull { it.normalizedPath == "/graphql" }
        assertNotNull(graphqlCandidate)
        assertTrue(graphqlCandidate!!.internalSignals.contains("collection_feed"))
        assertFalse(graphqlCandidate.internalSignals.contains("entry_surface"))
    }
}
