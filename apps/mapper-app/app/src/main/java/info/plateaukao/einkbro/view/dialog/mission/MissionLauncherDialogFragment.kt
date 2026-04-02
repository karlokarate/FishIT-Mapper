package info.plateaukao.einkbro.view.dialog.mission

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import dev.fishit.mapper.wave01.debug.RuntimeToolkitMissionWizard
import info.plateaukao.einkbro.R

class MissionLauncherDialogFragment : AppCompatDialogFragment() {

    interface Host {
        fun onMissionLauncherSelectMission(missionId: String)
        fun onMissionLauncherOpenFixtureReplay()
        fun onMissionLauncherOpenAdvancedSettings()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val host = activity as? Host
        val missionIds = RuntimeToolkitMissionWizard.supportedMissionIds(requireContext())
        val labels = missionIds.map { missionId ->
            val profile = RuntimeToolkitMissionWizard.profileForMission(missionId, requireContext())
            val displayName = profile?.displayName ?: missionId
            if (RuntimeToolkitMissionWizard.isMissionImplemented(missionId, requireContext())) {
                displayName
            } else {
                "$displayName (disabled)"
            }
        }.toTypedArray()

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.mapper_mission_entry_title))
            .setCancelable(false)
            .setItems(labels) { _, which ->
                val missionId = missionIds.getOrNull(which).orEmpty()
                if (!RuntimeToolkitMissionWizard.isMissionImplemented(missionId, requireContext())) {
                    Toast.makeText(requireContext(), getString(R.string.mapper_mission_not_available), Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                host?.onMissionLauncherSelectMission(missionId)
                dismissAllowingStateLoss()
            }
            .setNeutralButton(getString(R.string.mapper_mission_fixture_replay)) { _, _ ->
                host?.onMissionLauncherOpenFixtureReplay()
            }
            .setNegativeButton(getString(R.string.mapper_mission_advanced_settings)) { _, _ ->
                host?.onMissionLauncherOpenAdvancedSettings()
            }
            .create()
    }

    companion object {
        const val TAG = "MissionLauncherDialog"
    }
}
