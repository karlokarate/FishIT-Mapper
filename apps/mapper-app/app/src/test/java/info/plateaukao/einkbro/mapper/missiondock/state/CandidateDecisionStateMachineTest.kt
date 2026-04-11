package info.plateaukao.einkbro.mapper.missiondock.state

import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateDecisionStateMachineTest {

    private val machine = CandidateDecisionStateMachine()

    @Test
    fun selectAndExcludeUpdatesStateDeterministically() {
        var state = CandidateDecisionState()
        state = machine.reduce(state, CandidateDecisionEvent.SelectCandidate("search", "ep_a"))
        state = machine.reduce(state, CandidateDecisionEvent.ExcludeCandidate("ep_b", excluded = true))

        assertEquals("ep_a", state.selectedEndpointByRole["search"])
        assertTrue(state.excludedEndpoints.contains("ep_b"))
    }

    @Test
    fun testStartedAndFinishedTrackRunningIdsAndResult() {
        var state = CandidateDecisionState()
        state = machine.reduce(state, CandidateDecisionEvent.TestStarted("ep_x"))
        assertTrue(state.testingEndpointIds.contains("ep_x"))

        val result = RuntimeToolkitTelemetry.EndpointOverrideTestResult(
            status = 200,
            durationMillis = 120,
            sizeBytes = 1024,
            mimeType = "application/json",
            ok = true,
            recordedAt = "2026-04-11T00:00:00Z",
        )
        state = machine.reduce(state, CandidateDecisionEvent.TestFinished("ep_x", result))
        assertFalse(state.testingEndpointIds.contains("ep_x"))
        assertEquals(result, state.lastTestResults["ep_x"])
    }
}
