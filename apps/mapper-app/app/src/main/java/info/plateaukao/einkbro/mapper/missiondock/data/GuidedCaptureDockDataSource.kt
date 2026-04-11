package info.plateaukao.einkbro.mapper.missiondock.data

import android.content.Context
import dev.fishit.mapper.wave01.debug.RuntimeToolkitMissionWizard
import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import java.security.MessageDigest

class GuidedCaptureDockDataSource(
    private val context: Context,
) {

    data class SnapshotPayload(
        val missionState: RuntimeToolkitTelemetry.MissionSessionState,
        val stepDisplayName: String,
        val feedback: RuntimeToolkitTelemetry.StepFeedback,
        val readyWindow: RuntimeToolkitTelemetry.ReadyWindowState,
        val readyHit: RuntimeToolkitTelemetry.ReadyHitState,
        val liveSnapshot: RuntimeToolkitTelemetry.LiveWizardSnapshot,
        val endpointOverrides: RuntimeToolkitTelemetry.EndpointOverrides,
        val exportReadiness: String,
        val hash: String,
        val focusedRole: String?,
    )

    fun load(maxRecent: Int = 20): SnapshotPayload {
        val state = RuntimeToolkitTelemetry.missionSessionState(context)
        val stepDef = RuntimeToolkitMissionWizard.stepById(state.missionId, state.wizardStepId, context)
        val displayName = stepDef?.displayName?.ifBlank { state.wizardStepId } ?: state.wizardStepId
        val feedback = RuntimeToolkitTelemetry.evaluateMissionStepFeedback(context, state.wizardStepId)
        val readyWindow = RuntimeToolkitTelemetry.readyWindowState(context)
        val readyHit = RuntimeToolkitTelemetry.latestReadyHitState(context)
        val liveSnapshot = RuntimeToolkitTelemetry.buildLiveWizardSnapshot(context, maxRecent = maxRecent)
        val overrides = RuntimeToolkitTelemetry.readEndpointOverrides(context)
        val summary = RuntimeToolkitTelemetry.buildMissionExportSummary(context)
        val focusedRole = inferFocusedRole(state.wizardStepId)
        val hash = computeHash(
            missionState = state,
            feedback = feedback,
            liveSnapshot = liveSnapshot,
            overrides = overrides,
            focusedRole = focusedRole,
        )
        return SnapshotPayload(
            missionState = state,
            stepDisplayName = displayName,
            feedback = feedback,
            readyWindow = readyWindow,
            readyHit = readyHit,
            liveSnapshot = liveSnapshot,
            endpointOverrides = overrides,
            exportReadiness = summary.exportReadiness,
            hash = hash,
            focusedRole = focusedRole,
        )
    }

    private fun computeHash(
        missionState: RuntimeToolkitTelemetry.MissionSessionState,
        feedback: RuntimeToolkitTelemetry.StepFeedback,
        liveSnapshot: RuntimeToolkitTelemetry.LiveWizardSnapshot,
        overrides: RuntimeToolkitTelemetry.EndpointOverrides,
        focusedRole: String?,
    ): String {
        val source = buildString {
            append(missionState.missionId)
            append('|')
            append(missionState.wizardStepId)
            append('|')
            append(missionState.saturationState)
            append('|')
            append(feedback.state)
            append(':')
            append(feedback.progressPercent)
            append('|')
            append(liveSnapshot.phaseId)
            append('|')
            append(liveSnapshot.endpointCandidates.entries.joinToString(";") { (role, items) ->
                "$role:${items.joinToString(",") { "${it.endpointId}:${it.score}:${it.confidence}:${it.evidenceCount}" }}"
            })
            append('|')
            append(overrides.selectedEndpointByRole.entries.joinToString(";") { "${it.key}:${it.value}" })
            append('|')
            append(overrides.excludedEndpoints.joinToString(","))
            append('|')
            append(focusedRole.orEmpty())
        }
        return sha256(source)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private fun inferFocusedRole(stepId: String): String? {
        val normalized = stepId.trim().lowercase()
        return when {
            normalized.startsWith("home") -> "home"
            normalized.startsWith("search") -> "search"
            normalized.startsWith("detail") -> "detail"
            normalized.startsWith("playback") -> "playbackResolver"
            normalized.startsWith("auth") -> "auth"
            normalized.startsWith("refresh") -> "refresh"
            normalized.startsWith("config") -> "config"
            else -> null
        }
    }
}
