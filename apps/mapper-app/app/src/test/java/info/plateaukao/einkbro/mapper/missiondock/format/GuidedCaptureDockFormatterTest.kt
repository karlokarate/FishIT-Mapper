package info.plateaukao.einkbro.mapper.missiondock.format

import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import info.plateaukao.einkbro.mapper.missiondock.state.CandidateCardViewState
import info.plateaukao.einkbro.mapper.missiondock.state.DockMode
import info.plateaukao.einkbro.mapper.missiondock.state.DockShellState
import info.plateaukao.einkbro.mapper.missiondock.state.GuidedCaptureDockPanelState
import info.plateaukao.einkbro.mapper.missiondock.state.ProbeSummaryViewState
import info.plateaukao.einkbro.mapper.missiondock.state.StepGuidanceViewState
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedCaptureDockFormatterTest {

    @Test
    fun candidateCopyIncludesRequiredContractFields() {
        val text = CandidateClipboardFormatter.format(sampleCandidate())

        assertTrue(text.contains("role=search"))
        assertTrue(text.contains("generalized_template=GET https://api.example.org/suche"))
        assertTrue(text.contains("operation_name=getSearchResults"))
        assertTrue(text.contains("confidence=0.91"))
        assertTrue(text.contains("evidence_count=7"))
        assertTrue(text.contains("field_hits=query:q,body:variables.query"))
        assertTrue(text.contains("runtime_viability=likely_runnable"))
        assertTrue(text.contains("warnings=LOW_SCORE"))
    }

    @Test
    fun panelSummaryCopyIncludesStepStatusHintsCandidatesAndBlockers() {
        val summary = PanelSummaryClipboardFormatter.format(
            GuidedCaptureDockPanelState(
                shell = DockShellState(dockMode = DockMode.Peek),
                step = StepGuidanceViewState(
                    stepId = "search_probe",
                    stepTitle = "Search Probe",
                    saturationState = "NEEDS_MORE_EVIDENCE",
                    progress = 72,
                    missingSignals = listOf("search_variants>=2"),
                    hints = listOf("Nutze eine zweite Suchvariante"),
                    blockersSummary = "top_candidate_low_quality",
                ),
                probe = ProbeSummaryViewState(phaseId = "search_probe"),
                candidates = listOf(sampleCandidate()),
            ),
        )

        assertTrue(summary.contains("probe_name=Search Probe"))
        assertTrue(summary.contains("status=NEEDS_MORE_EVIDENCE"))
        assertTrue(summary.contains("saturation_percent=72"))
        assertTrue(summary.contains("missing_proof=search_variants>=2"))
        assertTrue(summary.contains("hints=Nutze eine zweite Suchvariante"))
        assertTrue(summary.contains("top_candidates:"))
        assertTrue(summary.contains("blockers_if_any=top_candidate_low_quality"))
    }

    private fun sampleCandidate(): CandidateCardViewState {
        return CandidateCardViewState(
            endpointId = "ep_search_1",
            role = "search",
            method = "GET",
            host = "api.example.org",
            path = "/suche",
            operation = "getSearchResults",
            score = 133.0,
            confidence = 0.91,
            evidenceCount = 7,
            generalizedTemplate = "GET https://api.example.org/suche",
            observedExamples = listOf("https://api.example.org/suche?q=test"),
            rankReasons = listOf("high_evidence"),
            fieldHits = listOf("query:q", "body:variables.query"),
            runtimeViability = "likely_runnable",
            warnings = listOf("LOW_SCORE"),
            missingProof = listOf("search_variants>=2"),
            exportReadiness = "PARTIAL",
            topCandidate = true,
            lowQuality = false,
            selected = false,
            excluded = false,
            expanded = false,
            testing = false,
            testResult = RuntimeToolkitTelemetry.EndpointOverrideTestResult(
                status = 200,
                durationMillis = 210,
                sizeBytes = 2048,
                mimeType = "application/json",
                ok = true,
                recordedAt = "2026-04-11T00:00:00Z",
            ),
        )
    }
}
