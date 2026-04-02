package info.plateaukao.einkbro.view.dialog.mission

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import info.plateaukao.einkbro.R

class MissionExportSummaryDialogFragment : AppCompatDialogFragment() {

    interface Host {
        fun onMissionExportSummaryRequestExport()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val host = activity as? Host
        val summaryTitle = requireArguments().getString(ARG_TITLE).orEmpty()
            .ifBlank { getString(R.string.mapper_mission_export_summary_title) }
        val summaryBody = requireArguments().getString(ARG_BODY).orEmpty()
        val exportReady = requireArguments().getBoolean(ARG_EXPORT_READY, false)

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(summaryTitle)
            .setMessage(summaryBody)
            .setNegativeButton(android.R.string.cancel, null)

        if (exportReady) {
            builder.setPositiveButton(getString(R.string.mapper_mission_export_bundle)) { _, _ ->
                host?.onMissionExportSummaryRequestExport()
            }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }

        return builder.create()
    }

    companion object {
        const val TAG = "MissionExportSummaryDialog"
        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"
        private const val ARG_EXPORT_READY = "export_ready"

        fun newInstance(
            title: String,
            body: String,
            exportReady: Boolean,
        ): MissionExportSummaryDialogFragment {
            return MissionExportSummaryDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_BODY, body)
                    putBoolean(ARG_EXPORT_READY, exportReady)
                }
            }
        }
    }
}
