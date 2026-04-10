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
}
