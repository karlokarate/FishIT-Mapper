package dev.fishit.mapper.wave01.debug

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

object RuntimeToolkitMissionWizard {
    const val MISSION_FISHIT_PIPELINE = "FISHIT_PIPELINE"
    const val MISSION_STANDALONE_APP = "STANDALONE_APP"
    const val MISSION_API_MAPPING = "API_MAPPING"
    const val MISSION_REPLAY_BUNDLE = "REPLAY_BUNDLE"

    const val SATURATION_INCOMPLETE = "INCOMPLETE"
    const val SATURATION_NEEDS_MORE_EVIDENCE = "NEEDS_MORE_EVIDENCE"
    const val SATURATION_SATURATED = "SATURATED"
    const val SATURATION_BLOCKED = "BLOCKED"

    const val STEP_TARGET_URL_INPUT = "target_url_input"
    const val STEP_HOME_PROBE = "home_probe_step"
    const val STEP_SEARCH_PROBE = "search_probe_step"
    const val STEP_DETAIL_PROBE = "detail_probe_step"
    const val STEP_PLAYBACK_PROBE = "playback_probe_step"
    const val STEP_AUTH_PROBE_OPTIONAL = "auth_probe_step_optional"
    const val STEP_FINAL_VALIDATION_EXPORT = "final_validation_export"

    private const val REGISTRY_ASSET_FILE = "mapper_mission_registry.json"

    data class StepDefinition(
        val stepId: String,
        val displayName: String,
        val optional: Boolean,
        val phaseId: String?,
        val browserInstruction: String,
        val requiredSignals: List<String> = emptyList(),
    )

    data class SaturationResult(
        val state: String,
        val reason: String,
        val metrics: Map<String, Any?> = emptyMap(),
    )

    data class MissionArtifact(
        val id: String,
        val paths: List<String>,
    ) {
        val relativePath: String
            get() = paths.firstOrNull().orEmpty()
    }

    data class MissionProfile(
        val missionId: String,
        val displayName: String,
        val description: String,
        val implemented: Boolean,
        val availabilityNotes: String,
        val requiredProbeSet: List<String>,
        val optionalProbeSet: List<String>,
        val expectedOutputTargets: List<String>,
        val steps: List<StepDefinition>,
        val requiredArtifacts: List<MissionArtifact>,
    )

    private val registryLock = Any()

    @Volatile
    private var cachedProfiles: LinkedHashMap<String, MissionProfile>? = null

    private val fallbackProfiles: LinkedHashMap<String, MissionProfile> by lazy {
        val fishitSteps = listOf(
            StepDefinition(
                stepId = STEP_TARGET_URL_INPUT,
                displayName = "Target URL Input",
                optional = false,
                phaseId = null,
                browserInstruction = "Ziel-URL eingeben und laden.",
                requiredSignals = listOf("target_site_id_correlated"),
            ),
            StepDefinition(
                stepId = STEP_HOME_PROBE,
                displayName = "Home Probe",
                optional = false,
                phaseId = "home_probe",
                browserInstruction = "Startseite laden und stabilen Home-Traffic erzeugen.",
                requiredSignals = listOf("home_candidate_count_min"),
            ),
            StepDefinition(
                stepId = STEP_SEARCH_PROBE,
                displayName = "Search Probe",
                optional = false,
                phaseId = "search_probe",
                browserInstruction = "Suche ausfuehren, bis Such-Traffic gesaettigt ist.",
                requiredSignals = listOf("search_candidate_count_min", "field_matrix_search_mapping_present"),
            ),
            StepDefinition(
                stepId = STEP_DETAIL_PROBE,
                displayName = "Detail Probe",
                optional = false,
                phaseId = "detail_probe",
                browserInstruction = "Detailseite oeffnen und Detail-Requests erfassen.",
                requiredSignals = listOf("detail_candidate_count_min", "field_matrix_detail_mapping_present"),
            ),
            StepDefinition(
                stepId = STEP_PLAYBACK_PROBE,
                displayName = "Playback Probe",
                optional = false,
                phaseId = "playback_probe",
                browserInstruction = "Playback starten und Resolver/Manifest erfassen.",
                requiredSignals = listOf("playback_resolver_or_manifest_present"),
            ),
            StepDefinition(
                stepId = STEP_AUTH_PROBE_OPTIONAL,
                displayName = "Auth Probe (Optional)",
                optional = true,
                phaseId = "auth_probe",
                browserInstruction = "Optional anmelden, um Auth-/Refresh-Evidenz zu erfassen.",
            ),
            StepDefinition(
                stepId = STEP_FINAL_VALIDATION_EXPORT,
                displayName = "Final Validation & Export",
                optional = false,
                phaseId = null,
                browserInstruction = "Saettigung pruefen und missionrelevante Exporte finalisieren.",
                requiredSignals = listOf("mission_relevant_export_artifacts_ready"),
            ),
        )

        linkedMapOf(
            MISSION_FISHIT_PIPELINE to MissionProfile(
                missionId = MISSION_FISHIT_PIPELINE,
                displayName = "FishIT Pipeline",
                description = "Build a FishIT-compatible provider draft.",
                implemented = true,
                availabilityNotes = "primary production mission",
                requiredProbeSet = listOf("home_probe", "search_probe", "detail_probe", "playback_probe"),
                optionalProbeSet = listOf("auth_probe", "replay_probe"),
                expectedOutputTargets = listOf(
                    "site_runtime_model",
                    "fishit_provider_draft",
                    "source_pipeline_bundle",
                    "source_plugin_bundle",
                    "endpoint_templates",
                    "field_matrix",
                    "auth_draft",
                    "playback_draft",
                    "confidence_report",
                    "warnings",
                ),
                steps = fishitSteps,
                requiredArtifacts = listOf(
                    MissionArtifact("site_runtime_model", listOf("site_profile.draft.json", "site_runtime_model.json")),
                    MissionArtifact("fishit_provider_draft", listOf("provider_draft_export.json", "fishit_provider_draft.json")),
                    MissionArtifact("source_pipeline_bundle", listOf("source_pipeline_bundle.json")),
                    MissionArtifact("source_bundle_manifest", listOf("manifest.json")),
                    MissionArtifact("source_plugin_bundle", listOf("exports/source_plugin_bundle.zip", "source_plugin_bundle.zip")),
                    MissionArtifact("endpoint_templates", listOf("endpoint_candidates.json", "endpoint_templates.json")),
                    MissionArtifact("field_matrix", listOf("field_matrix.json")),
                    MissionArtifact("auth_draft", listOf("replay_requirements.json", "auth_draft.json")),
                    MissionArtifact("playback_draft", listOf("replay_seed.json", "playback_draft.json")),
                    MissionArtifact("confidence_report", listOf("pipeline_ready_report.json", "confidence_report.json")),
                    MissionArtifact("warnings", listOf("mission_export_summary.json", "warnings.json")),
                ),
            ),
            MISSION_API_MAPPING to MissionProfile(
                missionId = MISSION_API_MAPPING,
                displayName = "API Mapping",
                description = "Build technical endpoint/auth/provenance/replay export.",
                implemented = true,
                availabilityNotes = "enabled in this slice",
                requiredProbeSet = listOf("home_probe", "search_probe", "detail_probe"),
                optionalProbeSet = listOf("playback_probe", "auth_probe", "replay_probe"),
                expectedOutputTargets = listOf(
                    "site_runtime_model",
                    "endpoint_templates",
                    "replay_bundle",
                    "confidence_report",
                    "warnings",
                ),
                steps = listOf(
                    fishitSteps.first(),
                    fishitSteps[1],
                    fishitSteps[2],
                    fishitSteps[3],
                    fishitSteps.last(),
                    fishitSteps[4].copy(optional = true),
                    fishitSteps[5],
                ),
                requiredArtifacts = listOf(
                    MissionArtifact("site_runtime_model", listOf("site_profile.draft.json", "site_runtime_model.json")),
                    MissionArtifact("endpoint_templates", listOf("endpoint_candidates.json", "endpoint_templates.json")),
                    MissionArtifact("replay_bundle", listOf("replay_seed.json", "replay_bundle.json")),
                    MissionArtifact("confidence_report", listOf("pipeline_ready_report.json", "confidence_report.json")),
                    MissionArtifact("warnings", listOf("mission_export_summary.json", "warnings.json")),
                    MissionArtifact("runtime_events", listOf("events/runtime_events.jsonl", "runtime_events.jsonl")),
                ),
            ),
            MISSION_STANDALONE_APP to MissionProfile(
                missionId = MISSION_STANDALONE_APP,
                displayName = "Standalone App",
                description = "Build selective website-to-app runtime bundles.",
                implemented = true,
                availabilityNotes = "enabled in this slice",
                requiredProbeSet = listOf("home_probe", "detail_probe"),
                optionalProbeSet = listOf("search_probe", "playback_probe", "auth_probe"),
                expectedOutputTargets = listOf("site_runtime_model", "webapp_runtime_draft"),
                steps = fishitSteps,
                requiredArtifacts = emptyList(),
            ),
            MISSION_REPLAY_BUNDLE to MissionProfile(
                missionId = MISSION_REPLAY_BUNDLE,
                displayName = "Replay Bundle",
                description = "Produce fixture-ready replay/debug bundles.",
                implemented = true,
                availabilityNotes = "enabled in this slice",
                requiredProbeSet = listOf("home_probe"),
                optionalProbeSet = listOf("search_probe", "detail_probe", "playback_probe", "auth_probe"),
                expectedOutputTargets = listOf("site_runtime_model", "replay_bundle", "fixture_bundle"),
                steps = fishitSteps,
                requiredArtifacts = listOf(
                    MissionArtifact("site_runtime_model", listOf("site_profile.draft.json", "site_runtime_model.json")),
                    MissionArtifact("replay_bundle", listOf("replay_bundle.json", "replay_seed.json")),
                    MissionArtifact("confidence_report", listOf("pipeline_ready_report.json", "confidence_report.json")),
                    MissionArtifact("warnings", listOf("mission_export_summary.json", "warnings.json")),
                    MissionArtifact("runtime_events", listOf("events/runtime_events.jsonl", "runtime_events.jsonl")),
                    MissionArtifact("response_index", listOf("response_index.json")),
                    MissionArtifact("fixture_manifest", listOf("fixture_manifest.json")),
                ),
            ),
        )
    }

    internal fun parseRegistryJson(raw: String): LinkedHashMap<String, MissionProfile> {
        if (raw.isBlank()) return LinkedHashMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return LinkedHashMap()
        val missions = root.optJSONArray("missions") ?: JSONArray()
        val parsed = LinkedHashMap<String, MissionProfile>()
        for (i in 0 until missions.length()) {
            val node = missions.optJSONObject(i) ?: continue
            val missionId = node.optString("mission_id").trim()
            if (missionId.isBlank()) continue

            val stepsJson = node.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<StepDefinition>()
            for (stepIdx in 0 until stepsJson.length()) {
                val step = stepsJson.optJSONObject(stepIdx) ?: continue
                val stepId = step.optString("step_id").trim()
                if (stepId.isBlank()) continue
                val phaseValue = step.optString("phase_id").trim().ifBlank { null }
                val requiredSignals = mutableListOf<String>()
                val signalArray = step.optJSONArray("required_signals") ?: JSONArray()
                for (signalIdx in 0 until signalArray.length()) {
                    val signal = signalArray.optString(signalIdx).trim()
                    if (signal.isNotBlank()) requiredSignals += signal
                }
                steps += StepDefinition(
                    stepId = stepId,
                    displayName = step.optString("display_name").trim().ifBlank { stepId },
                    optional = step.optBoolean("optional", false),
                    phaseId = phaseValue,
                    browserInstruction = step.optString("browser_instruction").trim(),
                    requiredSignals = requiredSignals,
                )
            }

            val requiredProbes = mutableListOf<String>()
            val requiredProbeArray = node.optJSONArray("required_probe_set") ?: JSONArray()
            for (idx in 0 until requiredProbeArray.length()) {
                val value = requiredProbeArray.optString(idx).trim()
                if (value.isNotBlank()) requiredProbes += value
            }

            val optionalProbes = mutableListOf<String>()
            val optionalProbeArray = node.optJSONArray("optional_probe_set") ?: JSONArray()
            for (idx in 0 until optionalProbeArray.length()) {
                val value = optionalProbeArray.optString(idx).trim()
                if (value.isNotBlank()) optionalProbes += value
            }

            val outputs = mutableListOf<String>()
            val outputArray = node.optJSONArray("expected_output_targets") ?: JSONArray()
            for (idx in 0 until outputArray.length()) {
                val value = outputArray.optString(idx).trim()
                if (value.isNotBlank()) outputs += value
            }

            val artifacts = mutableListOf<MissionArtifact>()
            val artifactsArray = node.optJSONArray("required_artifacts") ?: JSONArray()
            for (artifactIdx in 0 until artifactsArray.length()) {
                val artifact = artifactsArray.optJSONObject(artifactIdx) ?: continue
                val artifactId = artifact.optString("id").trim()
                if (artifactId.isBlank()) continue
                val paths = mutableListOf<String>()
                val pathArray = artifact.optJSONArray("paths") ?: JSONArray()
                for (pathIdx in 0 until pathArray.length()) {
                    val path = pathArray.optString(pathIdx).trim()
                    if (path.isNotBlank()) paths += path
                }
                artifacts += MissionArtifact(id = artifactId, paths = paths)
            }

            parsed[missionId] = MissionProfile(
                missionId = missionId,
                displayName = node.optString("display_name").trim().ifBlank { missionId },
                description = node.optString("description").trim(),
                implemented = node.optBoolean("implemented", missionId == MISSION_FISHIT_PIPELINE),
                availabilityNotes = node.optString("availability_notes").trim(),
                requiredProbeSet = requiredProbes,
                optionalProbeSet = optionalProbes,
                expectedOutputTargets = outputs,
                steps = steps,
                requiredArtifacts = artifacts,
            )
        }
        return parsed
    }

    private fun loadProfilesFromAsset(context: Context): LinkedHashMap<String, MissionProfile> {
        return runCatching {
            context.assets.open(REGISTRY_ASSET_FILE).bufferedReader(Charsets.UTF_8).use { reader ->
                parseRegistryJson(reader.readText())
            }
        }.getOrElse { LinkedHashMap() }
    }

    private fun profiles(context: Context? = null): LinkedHashMap<String, MissionProfile> {
        val cached = cachedProfiles
        if (cached != null) return cached

        synchronized(registryLock) {
            val existing = cachedProfiles
            if (existing != null) return existing

            val loaded = if (context != null) loadProfilesFromAsset(context) else LinkedHashMap()
            val resolved = if (loaded.isNotEmpty()) loaded else LinkedHashMap(fallbackProfiles)
            cachedProfiles = resolved
            return resolved
        }
    }

    fun ensureRegistryLoaded(context: Context) {
        profiles(context)
    }

    internal fun resetRegistryForTests() {
        synchronized(registryLock) {
            cachedProfiles = null
        }
    }

    fun supportedMissionIds(context: Context? = null): List<String> = profiles(context).keys.toList()

    fun profileForMission(missionId: String, context: Context? = null): MissionProfile? {
        return profiles(context)[missionId]
    }

    fun isMissionImplemented(missionId: String, context: Context? = null): Boolean {
        return profileForMission(missionId, context)?.implemented == true
    }

    fun stepsForMission(missionId: String, context: Context? = null): List<StepDefinition> {
        return profileForMission(missionId, context)?.steps.orEmpty()
    }

    fun firstStepId(missionId: String, context: Context? = null): String {
        return stepsForMission(missionId, context).firstOrNull()?.stepId ?: STEP_TARGET_URL_INPUT
    }

    fun stepById(missionId: String, stepId: String, context: Context? = null): StepDefinition? {
        return stepsForMission(missionId, context).firstOrNull { it.stepId == stepId }
    }

    fun nextStepId(missionId: String, currentStepId: String, context: Context? = null): String? {
        val steps = stepsForMission(missionId, context)
        val idx = steps.indexOfFirst { it.stepId == currentStepId }
        if (idx < 0) return null
        return steps.getOrNull(idx + 1)?.stepId
    }

    fun isOptionalStep(missionId: String, stepId: String, context: Context? = null): Boolean {
        return stepById(missionId, stepId, context)?.optional == true
    }

    fun missionDisplayName(missionId: String, context: Context? = null): String {
        return profileForMission(missionId, context)?.displayName ?: missionId.ifBlank { "Unknown Mission" }
    }

    fun requiredProbeSet(missionId: String, context: Context? = null): List<String> {
        return profileForMission(missionId, context)?.requiredProbeSet.orEmpty()
    }

    fun expectedOutputTargets(missionId: String, context: Context? = null): List<String> {
        return profileForMission(missionId, context)?.expectedOutputTargets.orEmpty()
    }

    fun requiredArtifactsForMission(missionId: String, context: Context? = null): List<MissionArtifact> {
        return profileForMission(missionId, context)?.requiredArtifacts.orEmpty()
    }

    fun requiredStepIds(missionId: String, context: Context? = null): List<String> {
        return stepsForMission(missionId, context)
            .filter { !it.optional && it.stepId != STEP_FINAL_VALIDATION_EXPORT }
            .map { it.stepId }
    }
}
