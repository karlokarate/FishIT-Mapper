package info.plateaukao.einkbro.mapper.missiondock.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import info.plateaukao.einkbro.R

data class GuidedCaptureDockViews(
    val root: LinearLayout,
    val handle: TextView,
    val panel: LinearLayout,
    val title: TextView,
    val statusBadge: TextView,
    val saturationPercent: TextView,
    val refresh: TextView,
    val copySummary: TextView,
    val collapse: TextView,
    val mode: TextView,
    val feedToggle: TextView,
    val overflow: TextView,
    val peekSummary: TextView,
    val stepTitle: TextView,
    val stepState: TextView,
    val stepProgress: ProgressBar,
    val stepMissing: TextView,
    val stepHints: TextView,
    val stepStatus: TextView,
    val stepExportReadiness: TextView,
    val stepCandidateInsight: TextView,
    val actionStart: TextView,
    val actionReady: TextView,
    val actionCheck: TextView,
    val actionPause: TextView,
    val actionNext: TextView,
    val candidateList: RecyclerView,
    val feedContainer: LinearLayout,
    val feedSummary: TextView,
)

object GuidedCaptureDockViewsFactory {

    fun attach(context: Context, parent: ConstraintLayout): GuidedCaptureDockViews {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            id = R.id.guided_capture_dock_root
            visibility = View.GONE
        }

        val handle = TextView(context).apply {
            id = R.id.guided_capture_dock_handle
            text = "WIZ"
            gravity = Gravity.CENTER
            textSize = 10f
            background = safeDrawable(context, R.drawable.roundcorner)
            minHeight = dp(context, 72)
            minWidth = dp(context, 54)
            setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10))
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
        }
        root.addView(
            handle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP
                topMargin = dp(context, 8)
                marginEnd = dp(context, 4)
            },
        )

        val panel = LinearLayout(context).apply {
            id = R.id.guided_capture_dock_panel
            orientation = LinearLayout.VERTICAL
            background = safeDrawable(context, R.drawable.background_with_border)
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        }
        root.addView(
            panel,
            LinearLayout.LayoutParams(dp(context, 336), LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val title = TextView(context).apply {
            id = R.id.guided_capture_dock_title
            textSize = 13f
            text = safeString(context, R.string.mapper_wizard_live_title, "Live Panel")
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 4)
            },
        )

        val statusBadge = TextView(context).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            background = safeDrawable(context, R.drawable.roundcorner)
            setPadding(dp(context, 6), dp(context, 3), dp(context, 6), dp(context, 3))
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            text = "IDLE"
        }
        header.addView(statusBadge)

        val saturationPercent = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            text = "0%"
        }
        header.addView(
            saturationPercent,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(context, 4)
            },
        )

        fun headerButton(id: Int, label: String): TextView {
            return TextView(context).apply {
                this.id = id
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                background = safeDrawable(context, R.drawable.roundcorner)
                setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4))
                setTextColor(ContextCompat.getColor(context, android.R.color.black))
                minHeight = dp(context, 48)
            }
        }

        val refresh = headerButton(R.id.guided_capture_dock_refresh, safeString(context, R.string.mapper_wizard_live_refresh, "Refresh"))
        val copySummary = headerButton(R.id.guided_capture_dock_copy_summary, safeString(context, R.string.mapper_wizard_live_copy_summary_short, "Copy"))
        val collapse = headerButton(R.id.guided_capture_dock_collapse, safeString(context, R.string.mapper_wizard_live_hide, "Hide"))
        val mode = headerButton(R.id.guided_capture_dock_mode, safeString(context, R.string.mapper_wizard_dock_mode_peek, "Peek"))
        val feedToggle = headerButton(R.id.guided_capture_dock_feed_toggle, safeString(context, R.string.mapper_wizard_live_show, "Show"))
        val overflow = headerButton(R.id.guided_capture_dock_overflow, "Menu")
        header.addView(refresh, headerButtonParams(context))
        header.addView(copySummary, headerButtonParams(context))
        header.addView(collapse, headerButtonParams(context))
        header.addView(mode, headerButtonParams(context))
        header.addView(feedToggle, headerButtonParams(context))
        header.addView(overflow, headerButtonParams(context))

        val peekSummary = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            visibility = View.GONE
        }
        panel.addView(peekSummary, fillWrap(top = 4, context = context))

        val stepTitle = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }
        panel.addView(stepTitle, fillWrap())

        val stepState = TextView(context).apply {
            textSize = 11f
        }
        panel.addView(stepState, fillWrap())

        val stepProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        panel.addView(
            stepProgress,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 6)).apply {
                topMargin = dp(context, 4)
            },
        )

        val stepMissing = TextView(context).apply { textSize = 10f }
        val stepHints = TextView(context).apply { textSize = 10f }
        val stepStatus = TextView(context).apply { textSize = 10f }
        val stepExportReadiness = TextView(context).apply { textSize = 10f }
        val stepCandidateInsight = TextView(context).apply { textSize = 10f }
        panel.addView(stepMissing, fillWrap(top = 4, context = context))
        panel.addView(stepHints, fillWrap(top = 2, context = context))
        panel.addView(stepStatus, fillWrap(top = 2, context = context))
        panel.addView(stepExportReadiness, fillWrap(top = 2, context = context))
        panel.addView(stepCandidateInsight, fillWrap(top = 2, context = context))

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        panel.addView(actionRow, fillWrap(top = 6, context = context))
        fun actionButton(id: Int, labelRes: Int): TextView {
            return TextView(context).apply {
                this.id = id
                text = safeString(context, labelRes, "")
                textSize = 10f
                gravity = Gravity.CENTER
                background = safeDrawable(context, R.drawable.roundcorner)
                setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4))
                minHeight = dp(context, 48)
            }
        }
        val actionStart = actionButton(R.id.guided_capture_dock_action_start, R.string.mapper_wizard_overlay_start)
        val actionReady = actionButton(R.id.guided_capture_dock_action_ready, R.string.mapper_wizard_overlay_ready)
        val actionCheck = actionButton(R.id.guided_capture_dock_action_check, R.string.mapper_wizard_overlay_check)
        val actionPause = actionButton(R.id.guided_capture_dock_action_pause, R.string.mapper_wizard_overlay_pause)
        val actionNext = actionButton(R.id.guided_capture_dock_action_next, R.string.mapper_wizard_overlay_next)
        actionRow.addView(actionStart)
        actionRow.addView(actionReady, rowSpacing(context))
        actionRow.addView(actionCheck, rowSpacing(context))
        actionRow.addView(actionPause, rowSpacing(context))
        actionRow.addView(actionNext, rowSpacing(context))

        val listHeader = TextView(context).apply {
            text = safeString(context, R.string.mapper_wizard_live_section_candidates, "Endpoint Candidates")
            textSize = 11f
        }
        panel.addView(listHeader, fillWrap(top = 8, context = context))

        val candidateList = RecyclerView(context).apply {
            id = R.id.guided_capture_dock_candidate_list
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        panel.addView(
            candidateList,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 220),
            ).apply { topMargin = dp(context, 4) },
        )

        val feedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        panel.addView(feedContainer, fillWrap(top = 8, context = context))

        val feedHeader = TextView(context).apply {
            text = safeString(context, R.string.mapper_wizard_live_section_feed, "Correlation Feed")
            textSize = 11f
        }
        feedContainer.addView(feedHeader, fillWrap())

        val feedSummary = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        feedContainer.addView(feedSummary, fillWrap(top = 2, context = context))

        parent.addView(
            root,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        ConstraintSet().apply {
            clone(parent)
            connect(root.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(root.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            setMargin(root.id, ConstraintSet.TOP, dp(context, 56))
            setMargin(root.id, ConstraintSet.END, dp(context, 10))
            applyTo(parent)
        }

        return GuidedCaptureDockViews(
            root = root,
            handle = handle,
            panel = panel,
            title = title,
            statusBadge = statusBadge,
            saturationPercent = saturationPercent,
            refresh = refresh,
            copySummary = copySummary,
            collapse = collapse,
            mode = mode,
            feedToggle = feedToggle,
            overflow = overflow,
            peekSummary = peekSummary,
            stepTitle = stepTitle,
            stepState = stepState,
            stepProgress = stepProgress,
            stepMissing = stepMissing,
            stepHints = stepHints,
            stepStatus = stepStatus,
            stepExportReadiness = stepExportReadiness,
            stepCandidateInsight = stepCandidateInsight,
            actionStart = actionStart,
            actionReady = actionReady,
            actionCheck = actionCheck,
            actionPause = actionPause,
            actionNext = actionNext,
            candidateList = candidateList,
            feedContainer = feedContainer,
            feedSummary = feedSummary,
        )
    }

    private fun fillWrap(top: Int = 0, context: Context? = null): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (context != null && top > 0) topMargin = dp(context, top)
        }
    }

    private fun headerButtonParams(context: Context): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(context, 4) }
    }

    private fun rowSpacing(context: Context): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(context, 4) }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun safeDrawable(context: Context, resId: Int): Drawable? {
        return runCatching { ContextCompat.getDrawable(context, resId) }.getOrNull()
    }

    private fun safeString(context: Context, resId: Int, fallback: String): String {
        return runCatching { context.getString(resId) }.getOrElse { fallback }
    }
}
