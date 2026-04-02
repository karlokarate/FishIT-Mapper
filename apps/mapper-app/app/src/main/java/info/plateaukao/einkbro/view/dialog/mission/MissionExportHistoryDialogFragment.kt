package info.plateaukao.einkbro.view.dialog.mission

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import info.plateaukao.einkbro.R

class MissionExportHistoryDialogFragment : AppCompatDialogFragment() {

    interface Host {
        fun onMissionExportHistoryRunReplay(bundlePath: String)
        fun onMissionExportHistoryStartReplayMission()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val host = activity as? Host
        val summaryTitle = requireArguments().getString(ARG_TITLE).orEmpty()
            .ifBlank { getString(R.string.mapper_mission_export_history_title) }
        val summaryBody = requireArguments().getString(ARG_BODY).orEmpty()
        val replayEnabled = requireArguments().getBoolean(ARG_REPLAY_ENABLED, false)
        val bundleLabels = requireArguments().getStringArrayList(ARG_BUNDLE_LABELS).orEmpty()
        val bundlePaths = requireArguments().getStringArrayList(ARG_BUNDLE_PATHS).orEmpty()

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(summaryTitle)
            .setMessage(summaryBody)
            .setNegativeButton(android.R.string.cancel, null)

        if (replayEnabled && bundleLabels.isNotEmpty() && bundleLabels.size == bundlePaths.size) {
            builder.setItems(bundleLabels.toTypedArray()) { _, which ->
                val selected = bundlePaths.getOrNull(which).orEmpty()
                if (selected.isNotBlank()) {
                    host?.onMissionExportHistoryRunReplay(selected)
                }
            }
        }

        if (replayEnabled) {
            builder.setPositiveButton(getString(R.string.mapper_mission_export_history_start_replay)) { _, _ ->
                host?.onMissionExportHistoryStartReplayMission()
            }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        return builder.create()
    }

    companion object {
        const val TAG = "MissionExportHistoryDialog"
        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"
        private const val ARG_REPLAY_ENABLED = "replay_enabled"
        private const val ARG_BUNDLE_LABELS = "bundle_labels"
        private const val ARG_BUNDLE_PATHS = "bundle_paths"

        fun newInstance(
            title: String,
            body: String,
            replayEnabled: Boolean,
            bundleLabels: List<String> = emptyList(),
            bundlePaths: List<String> = emptyList(),
        ): MissionExportHistoryDialogFragment {
            return MissionExportHistoryDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_BODY, body)
                    putBoolean(ARG_REPLAY_ENABLED, replayEnabled)
                    putStringArrayList(ARG_BUNDLE_LABELS, ArrayList(bundleLabels))
                    putStringArrayList(ARG_BUNDLE_PATHS, ArrayList(bundlePaths))
                }
            }
        }
    }
}
