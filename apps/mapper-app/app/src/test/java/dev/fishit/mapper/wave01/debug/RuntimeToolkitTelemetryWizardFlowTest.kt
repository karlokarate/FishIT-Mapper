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
    fun tracking_event_host_with_search_term_remains_tracking() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "search_probe")
        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://tracksrv.zdf.de/event?eventType=search",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://tracksrv.zdf.de/event?eventType=search",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"ok":true}""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val tracked = snapshot.recentCorrelated.firstOrNull { it.normalizedHost.contains("tracksrv") }
        assertNotNull(tracked)
        assertEquals("tracking", tracked!!.routeKind)
        assertEquals("tracking_event", tracked.operation)
    }

    @Test
    fun german_search_terms_are_resolved_to_search_role_candidates() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "search_probe")
        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/suchergebnisse?suchbegriff=heute",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/suchergebnisse?suchbegriff=heute",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"items":[{"title":"Heute Journal"}]}""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val searchCandidates = snapshot.endpointCandidates["search"].orEmpty()
        assertTrue(searchCandidates.any { it.normalizedPath.contains("/suchergebnisse") })
    }

    @Test
    fun ptmd_calls_are_named_as_playback_resolver_fetch() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "playback_probe")
        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://api.zdf.de/tmd/2/ngplayer_2_5/vod/ptmd/tivi/123",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://api.zdf.de/tmd/2/ngplayer_2_5/vod/ptmd/tivi/123",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"priorityList":[],"formitaeten":[]}""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val playback = snapshot.recentCorrelated.firstOrNull { it.normalizedPath.contains("/ptmd/") }
        assertNotNull(playback)
        assertEquals("playback_resolver_fetch", playback!!.operation)
    }

    @Test
    fun german_auth_login_terms_are_classified_as_auth_login() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_AUTH_PROBE_OPTIONAL,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "auth_probe")
        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://api.zdf.de/identity/anmelden",
            method = "POST",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://api.zdf.de/identity/anmelden",
            method = "POST",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"ok":true}""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val authEvent = snapshot.recentCorrelated.firstOrNull { it.normalizedPath.contains("/anmelden") }
        assertNotNull(authEvent)
        assertEquals("auth", authEvent!!.routeKind)
        assertEquals("auth_login", authEvent.operation)
    }

    @Test
    fun german_ascii_path_variants_match_umlaut_hint_tokens() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "detail_probe")
        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/beitraege/fokus-europa-100",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/beitraege/fokus-europa-100",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"item":{"title":"Fokus Europa"}}""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val detailCandidates = snapshot.endpointCandidates["detail"].orEmpty()
        assertTrue(detailCandidates.any { it.normalizedPath.contains("/beitraege/") })
    }

    @Test
    fun german_genre_route_is_classified_as_category() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_HOME_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "home_probe")
        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/genre/krimi",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/genre/krimi",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "application/json",
            headers = emptyMap(),
            rawBody = """{"rows":[{"title":"Krimi","items":[{"title":"Taunuskrimi"}]}]}""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val genre = snapshot.recentCorrelated.firstOrNull { it.normalizedPath.contains("/genre/krimi") }
        assertNotNull(genre)
        assertEquals("category", genre!!.routeKind)
    }

    @Test
    fun german_filme_item_paths_are_classified_as_detail() {
        RuntimeToolkitTelemetry.startMissionSession(
            context = context,
            missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
        )
        RuntimeToolkitTelemetry.setMissionTarget(context, "https://www.zdf.de")
        RuntimeToolkitTelemetry.startCaptureSession(context, source = "unit_test")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = context,
            stepId = RuntimeToolkitMissionWizard.STEP_DETAIL_PROBE,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        RuntimeToolkitTelemetry.setActivePhaseId(context, "detail_probe")
        val request = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/filme/der-fernsehfilm-der-woche-100",
            method = "GET",
            headers = emptyMap(),
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = "https://www.zdf.de/filme/der-fernsehfilm-der-woche-100",
            method = "GET",
            statusCode = 200,
            reason = "OK",
            mimeType = "text/html",
            headers = emptyMap(),
            rawBody = """<html><title>Der Fernsehfilm der Woche</title></html>""".toByteArray(),
            requestId = request.requestId,
        )

        val snapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context)
        val filmDetail = snapshot.recentCorrelated.firstOrNull { it.normalizedPath.contains("/filme/der-fernsehfilm-der-woche-100") }
        assertNotNull(filmDetail)
        assertEquals("detail", filmDetail!!.routeKind)
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
