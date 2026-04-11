package info.plateaukao.einkbro.mapper.missiondock.state

class DockShellStateMachine {

    fun reduce(
        state: DockShellState,
        event: DockEvent,
    ): Pair<DockShellState, List<DockEffect>> {
        val next = when (event) {
            DockEvent.ActivityStarted -> state.copy(activityVisible = true)
            DockEvent.ActivityStopped -> state.copy(activityVisible = false)
            DockEvent.MissionActivated -> {
                val mode = if (state.dockMode == DockMode.Collapsed) DockMode.Peek else state.dockMode
                state.copy(
                    missionState = MissionState.Active,
                    dockMode = mode,
                )
            }
            DockEvent.MissionEnded -> state.copy(
                missionState = MissionState.Inactive,
                dockMode = DockMode.Collapsed,
                feedMode = FeedMode.Collapsed,
                expandedCandidateIds = emptySet(),
                focusedRole = null,
                latestSnapshotHash = "",
            )
            DockEvent.HandleTapped -> state.copy(dockMode = cycleDockMode(state.dockMode))
            is DockEvent.SetDockMode -> state.copy(dockMode = event.mode)
            DockEvent.ToggleFeed -> state.copy(
                feedMode = if (state.feedMode == FeedMode.Expanded) {
                    FeedMode.Collapsed
                } else {
                    FeedMode.Expanded
                },
            )
            is DockEvent.CandidateExpandToggled -> {
                val nextExpanded = state.expandedCandidateIds.toMutableSet()
                if (nextExpanded.contains(event.endpointId)) {
                    nextExpanded.remove(event.endpointId)
                } else {
                    nextExpanded.add(event.endpointId)
                }
                state.copy(expandedCandidateIds = nextExpanded)
            }
            is DockEvent.SnapshotLoaded -> {
                state.copy(
                    latestSnapshotHash = event.hash,
                    focusedRole = event.focusedRole,
                )
            }
            DockEvent.RefreshTick -> state
        }

        val effects = refreshEffectsFor(next)
        return next.copy(refreshState = resolveRefreshState(next)) to effects
    }

    private fun cycleDockMode(current: DockMode): DockMode {
        return when (current) {
            DockMode.Collapsed -> DockMode.Peek
            DockMode.Peek -> DockMode.Expanded
            DockMode.Expanded -> DockMode.Peek
        }
    }

    private fun refreshEffectsFor(state: DockShellState): List<DockEffect> {
        if (!state.activityVisible || state.missionState != MissionState.Active || state.dockMode == DockMode.Collapsed) {
            return listOf(DockEffect.StopRefresh)
        }
        return listOf(DockEffect.StartRefresh(intervalMs = refreshIntervalFor(state.dockMode)))
    }

    private fun resolveRefreshState(state: DockShellState): RefreshState {
        if (!state.activityVisible || state.missionState != MissionState.Active || state.dockMode == DockMode.Collapsed) {
            return RefreshState(running = false, intervalMs = 0L)
        }
        return RefreshState(running = true, intervalMs = refreshIntervalFor(state.dockMode))
    }

    private fun refreshIntervalFor(mode: DockMode): Long {
        return when (mode) {
            DockMode.Expanded -> 2_000L
            DockMode.Peek -> 4_000L
            DockMode.Collapsed -> 0L
        }
    }
}
