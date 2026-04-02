package dev.fishit.mapper.wave01.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeToolkitMissionWizardTest {

    @Test
    fun fishit_flow_has_expected_step_order() {
        val steps = RuntimeToolkitMissionWizard.stepsForMission(RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE)
        assertEquals(RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT, steps.first().stepId)
        assertEquals(RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT, steps.last().stepId)
        assertTrue(steps.any { it.stepId == RuntimeToolkitMissionWizard.STEP_SEARCH_PROBE })
        assertTrue(steps.any { it.stepId == RuntimeToolkitMissionWizard.STEP_PLAYBACK_PROBE })
    }

    @Test
    fun fishit_mission_contract_metadata_is_defined() {
        val probes = RuntimeToolkitMissionWizard.requiredProbeSet(RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE)
        assertEquals(listOf("home_probe", "search_probe", "detail_probe", "playback_probe"), probes)

        val outputs = RuntimeToolkitMissionWizard.expectedOutputTargets(RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE)
        assertTrue(outputs.contains("fishit_provider_draft"))
        assertTrue(outputs.contains("field_matrix"))

        val artifacts = RuntimeToolkitMissionWizard.requiredArtifactsForMission(RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE)
        assertTrue(artifacts.any { it.id == "fishit_provider_draft" && it.paths.contains("provider_draft_export.json") })
        assertTrue(artifacts.any { it.id == "warnings" && it.relativePath == "mission_export_summary.json" })
    }

    @Test
    fun mission_implementation_flags_match_current_slice() {
        assertTrue(RuntimeToolkitMissionWizard.isMissionImplemented(RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE))
        assertTrue(RuntimeToolkitMissionWizard.isMissionImplemented(RuntimeToolkitMissionWizard.MISSION_API_MAPPING))
        assertTrue(RuntimeToolkitMissionWizard.isMissionImplemented(RuntimeToolkitMissionWizard.MISSION_STANDALONE_APP))
        assertTrue(RuntimeToolkitMissionWizard.isMissionImplemented(RuntimeToolkitMissionWizard.MISSION_REPLAY_BUNDLE))
    }

    @Test
    fun mission_registry_parser_reads_implemented_flag_and_steps() {
        val raw = """
            {
              "schema_version": 1,
              "missions": [
                {
                  "mission_id": "API_MAPPING",
                  "display_name": "API Mapping",
                  "implemented": true,
                  "required_probe_set": ["home_probe"],
                  "optional_probe_set": ["auth_probe"],
                  "expected_output_targets": ["endpoint_templates"],
                  "steps": [
                    {
                      "step_id": "target_url_input",
                      "display_name": "Target URL Input",
                      "optional": false,
                      "phase_id": null,
                      "browser_instruction": "enter target"
                    },
                    {
                      "step_id": "search_probe_step",
                      "display_name": "Search Probe",
                      "optional": false,
                      "phase_id": "search_probe",
                      "browser_instruction": "search",
                      "required_signals": ["search_candidate_count_min"]
                    }
                  ],
                  "required_artifacts": [
                    {
                      "id": "endpoint_templates",
                      "paths": ["endpoint_candidates.json", "endpoint_templates.json"]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        RuntimeToolkitMissionWizard.resetRegistryForTests()
        val parsed = RuntimeToolkitMissionWizard.parseRegistryJson(raw)
        val profile = parsed["API_MAPPING"]
        assertTrue(profile != null)
        assertTrue(profile!!.implemented)
        assertEquals(2, profile.steps.size)
        assertEquals("search_probe", profile.steps[1].phaseId)
        assertEquals(listOf("search_candidate_count_min"), profile.steps[1].requiredSignals)
        assertEquals(listOf("endpoint_candidates.json", "endpoint_templates.json"), profile.requiredArtifacts.first().paths)
    }
}
