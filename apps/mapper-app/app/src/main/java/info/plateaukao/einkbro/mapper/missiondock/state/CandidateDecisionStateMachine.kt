package info.plateaukao.einkbro.mapper.missiondock.state

class CandidateDecisionStateMachine {

    fun reduce(
        current: CandidateDecisionState,
        event: CandidateDecisionEvent,
    ): CandidateDecisionState {
        return when (event) {
            is CandidateDecisionEvent.SelectCandidate -> {
                val selected = current.selectedEndpointByRole.toMutableMap()
                if (event.role.isNotBlank() && event.endpointId.isNotBlank()) {
                    selected[event.role] = event.endpointId
                }
                current.copy(selectedEndpointByRole = selected)
            }

            is CandidateDecisionEvent.ExcludeCandidate -> {
                val excluded = current.excludedEndpoints.toMutableSet()
                if (event.endpointId.isBlank()) return current
                if (event.excluded) {
                    excluded += event.endpointId
                } else {
                    excluded -= event.endpointId
                }
                current.copy(excludedEndpoints = excluded)
            }

            is CandidateDecisionEvent.TestStarted -> {
                if (event.endpointId.isBlank()) return current
                val testing = current.testingEndpointIds.toMutableSet()
                testing += event.endpointId
                current.copy(testingEndpointIds = testing)
            }

            is CandidateDecisionEvent.TestFinished -> {
                if (event.endpointId.isBlank()) return current
                val testing = current.testingEndpointIds.toMutableSet()
                testing -= event.endpointId
                val results = current.lastTestResults.toMutableMap()
                if (event.result != null) {
                    results[event.endpointId] = event.result
                }
                current.copy(
                    testingEndpointIds = testing,
                    lastTestResults = results,
                )
            }

            is CandidateDecisionEvent.SyncFromOverrides -> {
                current.copy(
                    selectedEndpointByRole = LinkedHashMap(event.overrides.selectedEndpointByRole),
                    excludedEndpoints = LinkedHashSet(event.overrides.excludedEndpoints),
                    lastTestResults = LinkedHashMap(event.overrides.lastTestResults),
                )
            }
        }
    }
}
