package info.plateaukao.einkbro.mapper.missiondock.state

import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry

class StepGuidanceStateMachine {

    fun project(
        current: StepGuidanceViewState,
        missionState: RuntimeToolkitTelemetry.MissionSessionState,
        displayName: String,
        feedback: RuntimeToolkitTelemetry.StepFeedback,
        readyWindow: RuntimeToolkitTelemetry.ReadyWindowState,
        readyHit: RuntimeToolkitTelemetry.ReadyHitState,
        statusText: String,
        exportReadiness: String,
    ): StepGuidanceViewState {
        val nextReadyWindow = when {
            readyWindow.withinWindow -> ReadyWindowUiState.Armed(
                expiresAtMs = readyWindow.expiresAtEpochMillis,
                stepId = readyWindow.armedStepId,
            )
            readyHit.recent -> ReadyWindowUiState.Hit(
                stepId = readyHit.stepId,
                hitAt = readyHit.hitAt,
            )
            else -> ReadyWindowUiState.Inactive
        }
        val nextAutoAdvance = when {
            current.autoAdvance is AutoAdvanceUiState.Scheduled -> current.autoAdvance
            current.autoAdvance is AutoAdvanceUiState.UndoWindow -> current.autoAdvance
            else -> AutoAdvanceUiState.None
        }

        return current.copy(
            stepId = missionState.wizardStepId,
            stepTitle = displayName,
            saturationState = feedback.state,
            progress = feedback.progressPercent,
            missingSignals = feedback.missingSignals,
            hints = feedback.userHints,
            reason = feedback.reason,
            exportReadiness = exportReadiness,
            statusText = statusText,
            readyWindowState = nextReadyWindow,
            autoAdvance = nextAutoAdvance,
        )
    }
}
