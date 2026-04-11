package info.plateaukao.einkbro.mapper.missiondock.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockShellStateMachineTest {

    private val machine = DockShellStateMachine()

    @Test
    fun missionActivationDefaultsToPeekAndStartsPollingWhenVisible() {
        var state = DockShellState()
        state = machine.reduce(state, DockEvent.ActivityStarted).first
        val (activated, effects) = machine.reduce(state, DockEvent.MissionActivated)

        assertEquals(MissionState.Active, activated.missionState)
        assertEquals(DockMode.Peek, activated.dockMode)
        assertTrue(effects.any { it is DockEffect.StartRefresh && it.intervalMs == 4_000L })
    }

    @Test
    fun handleTapCyclesPeekExpandedPeek() {
        val base = DockShellState(
            missionState = MissionState.Active,
            dockMode = DockMode.Peek,
            activityVisible = true,
        )
        val expanded = machine.reduce(base, DockEvent.HandleTapped).first
        val peekAgain = machine.reduce(expanded, DockEvent.HandleTapped).first

        assertEquals(DockMode.Expanded, expanded.dockMode)
        assertEquals(DockMode.Peek, peekAgain.dockMode)
    }

    @Test
    fun collapsedOrStoppedAlwaysStopsPolling() {
        val active = DockShellState(
            missionState = MissionState.Active,
            dockMode = DockMode.Expanded,
            activityVisible = true,
        )

        val collapsedEffects = machine.reduce(active, DockEvent.SetDockMode(DockMode.Collapsed)).second
        assertTrue(collapsedEffects.any { it is DockEffect.StopRefresh })

        val stoppedEffects = machine.reduce(active, DockEvent.ActivityStopped).second
        assertTrue(stoppedEffects.any { it is DockEffect.StopRefresh })
    }
}
