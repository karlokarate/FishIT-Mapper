package info.plateaukao.einkbro.mapper.missiondock.state

import dev.fishit.mapper.wave01.debug.RuntimeToolkitMissionWizard
import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepGuidanceStateMachineTest {

    private val machine = StepGuidanceStateMachine()

    @Test
    fun projectMapsFeedbackAndReadyWindow() {
        val mission = RuntimeToolkitTelemetry.MissionSessionState(
            missionId = "API_MAPPING",
            wizardStepId = "search_probe",
            saturationState = RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE,
            targetUrl = "https://example.org",
            targetSiteId = "example_org",
            targetHostFamily = "example.org",
            startedAt = "2026-04-11T00:00:00Z",
            finishedAt = "",
            stepStates = emptyMap(),
        )
        val feedback = RuntimeToolkitTelemetry.StepFeedback(
            state = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
            progressPercent = 100,
            missingSignals = emptyList(),
            userHints = listOf("looks good"),
            reason = "search_probe_saturated",
            metrics = emptyMap(),
        )
        val readyWindow = RuntimeToolkitTelemetry.ReadyWindowState(
            active = true,
            armedStepId = "search_probe",
            armedActionId = "a1",
            armedTraceId = "t1",
            armedStartedAt = "2026-04-11T00:00:00Z",
            armedExpiresAt = "2026-04-11T00:01:30Z",
            withinWindow = true,
            expiresAtEpochMillis = 90_000L,
            hitRecorded = false,
        )
        val readyHit = RuntimeToolkitTelemetry.ReadyHitState(
            stepId = "",
            hitAt = "",
            ageSeconds = 999,
            recent = false,
        )

        val next = machine.project(
            current = StepGuidanceViewState(),
            missionState = mission,
            displayName = "Search Probe",
            feedback = feedback,
            readyWindow = readyWindow,
            readyHit = readyHit,
            statusText = "ready",
            exportReadiness = "PARTIAL",
        )

        assertEquals("search_probe", next.stepId)
        assertEquals(100, next.progress)
        assertEquals("PARTIAL", next.exportReadiness)
        assertTrue(next.readyWindowState is ReadyWindowUiState.Armed)
    }
}
