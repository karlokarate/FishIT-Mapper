package info.plateaukao.einkbro.view.dialog.mission

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import dev.fishit.mapper.wave01.debug.RuntimeToolkitMissionWizard
import info.plateaukao.einkbro.R

class MissionWizardSetupDialogFragment : AppCompatDialogFragment() {

    interface Host {
        fun onMissionSetupConfirmed(missionId: String, targetUrl: String)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val host = activity as? Host
        val missionId = requireArguments().getString(ARG_MISSION_ID).orEmpty()
            .ifBlank { RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE }
        val prefillTargetUrl = requireArguments().getString(ARG_TARGET_URL).orEmpty()

        val scroll = ScrollView(requireContext())
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val missionTitle = TextView(requireContext()).apply {
            text = getString(
                R.string.mapper_mission_setup_selected,
                RuntimeToolkitMissionWizard.missionDisplayName(missionId, requireContext()),
            )
        }
        val requiredProbeLabel = TextView(requireContext()).apply {
            val probes = RuntimeToolkitMissionWizard.requiredProbeSet(missionId, requireContext())
            text = getString(R.string.mapper_mission_setup_required_probes, probes.joinToString(", "))
        }
        val expectedOutputsLabel = TextView(requireContext()).apply {
            val outputs = RuntimeToolkitMissionWizard.expectedOutputTargets(missionId, requireContext())
            text = getString(R.string.mapper_mission_setup_expected_outputs, outputs.joinToString(", "))
        }
        val stepPreviewLabel = TextView(requireContext()).apply {
            val steps = RuntimeToolkitMissionWizard.stepsForMission(missionId, requireContext())
                .joinToString("\n") { step ->
                    val optionalSuffix = if (step.optional) " (optional)" else ""
                    "- ${step.stepId}$optionalSuffix: ${step.displayName}"
                }
            text = getString(R.string.mapper_mission_setup_step_preview, steps)
        }
        val targetInput = EditText(requireContext()).apply {
            hint = getString(R.string.mapper_target_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefillTargetUrl)
        }

        content.addView(missionTitle)
        content.addView(requiredProbeLabel)
        content.addView(expectedOutputsLabel)
        content.addView(stepPreviewLabel)
        content.addView(targetInput)
        scroll.addView(content)

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.mapper_mission_setup_title))
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.mapper_mission_setup_start)) { _, _ ->
                val targetUrl = targetInput.text?.toString().orEmpty().trim()
                host?.onMissionSetupConfirmed(missionId = missionId, targetUrl = targetUrl)
            }
            .create()
    }

    companion object {
        const val TAG = "MissionWizardSetupDialog"
        private const val ARG_MISSION_ID = "mission_id"
        private const val ARG_TARGET_URL = "target_url"

        fun newInstance(missionId: String, targetUrl: String): MissionWizardSetupDialogFragment {
            return MissionWizardSetupDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MISSION_ID, missionId)
                    putString(ARG_TARGET_URL, targetUrl)
                }
            }
        }
    }
}
