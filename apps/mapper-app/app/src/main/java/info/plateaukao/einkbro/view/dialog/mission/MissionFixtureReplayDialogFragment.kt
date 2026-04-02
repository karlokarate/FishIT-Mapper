package info.plateaukao.einkbro.view.dialog.mission

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import info.plateaukao.einkbro.R

class MissionFixtureReplayDialogFragment : AppCompatDialogFragment() {

    interface Host {
        fun onMissionFixtureReplayOpenExportHistory()
        fun onMissionFixtureReplayStartReplayMission()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val host = activity as? Host
        val summaryTitle = requireArguments().getString(ARG_TITLE).orEmpty()
            .ifBlank { getString(R.string.mapper_mission_fixture_replay) }
        val summaryBody = requireArguments().getString(ARG_BODY).orEmpty()
        val canStartReplayMission = requireArguments().getBoolean(ARG_CAN_START_REPLAY, true)

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(summaryTitle)
            .setMessage(summaryBody)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(getString(R.string.mapper_mission_export_history_title)) { _, _ ->
                host?.onMissionFixtureReplayOpenExportHistory()
            }

        if (canStartReplayMission) {
            builder.setPositiveButton(getString(R.string.mapper_mission_fixture_replay_start_mission)) { _, _ ->
                host?.onMissionFixtureReplayStartReplayMission()
            }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        return builder.create()
    }

    companion object {
        const val TAG = "MissionFixtureReplayDialog"
        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"
        private const val ARG_CAN_START_REPLAY = "can_start_replay"

        fun newInstance(
            title: String,
            body: String,
            canStartReplayMission: Boolean,
        ): MissionFixtureReplayDialogFragment {
            return MissionFixtureReplayDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_BODY, body)
                    putBoolean(ARG_CAN_START_REPLAY, canStartReplayMission)
                }
            }
        }
    }
}
