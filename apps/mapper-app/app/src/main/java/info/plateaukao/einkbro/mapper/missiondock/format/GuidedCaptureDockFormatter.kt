package info.plateaukao.einkbro.mapper.missiondock.format

import info.plateaukao.einkbro.mapper.missiondock.state.CandidateCardViewState
import info.plateaukao.einkbro.mapper.missiondock.state.GuidedCaptureDockPanelState
import java.util.Locale

object CandidateClipboardFormatter {

    fun format(candidate: CandidateCardViewState): String {
        val warnings = candidate.warnings.joinToString(",").ifBlank { "none" }
        val fieldHits = candidate.fieldHits.joinToString(",").ifBlank { "none" }
        val operationLine = candidate.operation.trim().takeIf { it.isNotBlank() }?.let {
            "operation_name=$it\n"
        }.orEmpty()
        return buildString {
            append("endpoint_id=")
            append(candidate.endpointId)
            append('\n')
            append("role=")
            append(candidate.role)
            append('\n')
            append("generalized_template=")
            append(candidate.generalizedTemplate)
            append('\n')
            append(operationLine)
            append("confidence=")
            append(String.format(Locale.ROOT, "%.2f", candidate.confidence))
            append('\n')
            append("evidence_count=")
            append(candidate.evidenceCount)
            append('\n')
            append("field_hits=")
            append(fieldHits)
            append('\n')
            append("runtime_viability=")
            append(candidate.runtimeViability)
            append('\n')
            append("warnings=")
            append(warnings)
            append('\n')
            append("score=")
            append(String.format(Locale.ROOT, "%.2f", candidate.score))
            append('\n')
            append("selected=")
            append(candidate.selected)
            append(" excluded=")
            append(candidate.excluded)
            candidate.testResult?.let { result ->
                append('\n')
                append("test_status=")
                append(result.status)
                append(" test_ms=")
                append(result.durationMillis)
                append(" test_size=")
                append(result.sizeBytes)
                append(" test_ok=")
                append(result.ok)
            }
        }
    }
}

object PanelSummaryClipboardFormatter {

    fun format(state: GuidedCaptureDockPanelState): String {
        val phase = state.probe.phaseId.ifBlank { "unknown" }
        val topCandidates = state.candidates.take(6).joinToString("\n") { candidate ->
            "- ${candidate.role} ${candidate.method} ${candidate.path} score=${String.format(Locale.ROOT, "%.1f", candidate.score)}"
        }
        val summaryCandidates = if (topCandidates.isBlank()) "- none" else topCandidates
        val missingProof = state.step.missingSignals.joinToString(", ").ifBlank { "-" }
        val hints = state.step.hints.joinToString(" | ").ifBlank { "-" }
        val blockers = state.step.blockersSummary.ifBlank { "-" }
        return buildString {
            append("probe_name=")
            append(state.step.stepTitle.ifBlank { state.step.stepId })
            append('\n')
            append("step=")
            append(state.step.stepId)
            append('\n')
            append("status=")
            append(state.step.saturationState)
            append('\n')
            append("saturation_percent=")
            append(state.step.progress)
            append('\n')
            append("phase=")
            append(phase)
            append('\n')
            append("missing_proof=")
            append(missingProof)
            append('\n')
            append("hints=")
            append(hints)
            append('\n')
            append("reason=")
            append(state.step.reason)
            append('\n')
            append("export_readiness=")
            append(state.step.exportReadiness)
            append('\n')
            append("top_candidates:\n")
            append(summaryCandidates)
            append('\n')
            append("blockers_if_any=")
            append(blockers)
        }
    }
}

object GuidedCaptureDockFormatter {

    fun formatCandidate(candidate: CandidateCardViewState): String {
        return CandidateClipboardFormatter.format(candidate)
    }

    fun formatSummary(state: GuidedCaptureDockPanelState): String {
        return PanelSummaryClipboardFormatter.format(state)
    }
}
