package info.plateaukao.einkbro.mapper.missiondock.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
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
    val topCandidateHeader: TextView,
    val topCandidateDetails: TextView,
    val topCandidateWeaknesses: TextView,
    val topCandidateBlockers: TextView,
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
            background?.mutate()?.alpha = (255 * 0.9f).toInt()
            applyElevationCompat(dp(context, 6).toFloat())
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
        }
        root.addView(
            panel,
            LinearLayout.LayoutParams(resolvePanelWidthPx(context), resolvePanelHeightPx(context)),
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            titleRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val title = TextView(context).apply {
            id = R.id.guided_capture_dock_title
            textSize = 12f
            text = safeString(context, R.string.mapper_wizard_live_title, "Live Panel")
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleRow.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 4)
            },
        )

        val statusBadge = TextView(context).apply {
            textSize = 9f
            gravity = Gravity.CENTER
            background = safeDrawable(context, R.drawable.roundcorner)
            setPadding(dp(context, 6), dp(context, 3), dp(context, 6), dp(context, 3))
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            text = "IDLE"
        }
        titleRow.addView(statusBadge)

        val saturationPercent = TextView(context).apply {
            textSize = 9f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            text = "0%"
        }
        titleRow.addView(
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
                minHeight = dp(context, 40)
                minWidth = dp(context, 52)
            }
        }

        val refresh = headerButton(R.id.guided_capture_dock_refresh, safeString(context, R.string.mapper_wizard_live_refresh, "Refresh"))
        val copySummary = headerButton(R.id.guided_capture_dock_copy_summary, safeString(context, R.string.mapper_wizard_live_copy_summary_short, "Copy"))
        val collapse = headerButton(R.id.guided_capture_dock_collapse, safeString(context, R.string.mapper_wizard_live_hide, "Hide"))
        val mode = headerButton(R.id.guided_capture_dock_mode, safeString(context, R.string.mapper_wizard_dock_mode_peek, "Peek"))
        val feedToggle = headerButton(R.id.guided_capture_dock_feed_toggle, safeString(context, R.string.mapper_wizard_live_show, "Show"))
        val overflow = headerButton(R.id.guided_capture_dock_overflow, "Menu")
        val headerControlsScroll = HorizontalScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
        }
        header.addView(
            headerControlsScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 2) },
        )
        val headerControlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerControlsScroll.addView(
            headerControlsRow,
            FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        headerControlsRow.addView(refresh, headerButtonParams(context, first = true))
        headerControlsRow.addView(copySummary, headerButtonParams(context))
        headerControlsRow.addView(collapse, headerButtonParams(context))
        headerControlsRow.addView(mode, headerButtonParams(context))
        headerControlsRow.addView(feedToggle, headerButtonParams(context))
        headerControlsRow.addView(overflow, headerButtonParams(context))

        val contentScroll = NestedScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
        }
        panel.addView(
            contentScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { topMargin = dp(context, 4) },
        )

        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentScroll.addView(
            contentContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val peekSummary = TextView(context).apply {
            textSize = 9f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            visibility = View.GONE
        }
        contentContainer.addView(peekSummary, fillWrap(top = 2, context = context))

        val stepSectionHeader = TextView(context).apply {
            text = safeString(context, R.string.mapper_wizard_live_section_status, "Step Status")
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }
        contentContainer.addView(stepSectionHeader, fillWrap(top = 4, context = context))

        val stepTitle = TextView(context).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        contentContainer.addView(stepTitle, fillWrap())

        val stepState = TextView(context).apply {
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        contentContainer.addView(stepState, fillWrap())

        val stepProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        contentContainer.addView(
            stepProgress,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 4)).apply {
                topMargin = dp(context, 2)
            },
        )

        val stepMissing = TextView(context).apply {
            textSize = 9f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val stepHints = TextView(context).apply {
            textSize = 9f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val stepStatus = TextView(context).apply {
            textSize = 9f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val stepExportReadiness = TextView(context).apply {
            textSize = 9f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        contentContainer.addView(stepMissing, fillWrap(top = 2, context = context))
        contentContainer.addView(stepHints, fillWrap(top = 1, context = context))
        contentContainer.addView(stepStatus, fillWrap(top = 1, context = context))
        contentContainer.addView(stepExportReadiness, fillWrap(top = 1, context = context))

        val floatingActionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = safeDrawable(context, R.drawable.background_with_border)
            background?.mutate()?.alpha = (255 * 0.9f).toInt()
            applyElevationCompat(dp(context, 4).toFloat())
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        }
        panel.addView(
            floatingActionBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 4) },
        )

        fun actionButton(id: Int, labelRes: Int): TextView {
            return TextView(context).apply {
                this.id = id
                text = safeString(context, labelRes, "")
                textSize = 10f
                gravity = Gravity.CENTER
                background = safeDrawable(context, R.drawable.roundcorner)
                setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4))
                minHeight = dp(context, 48)
                minWidth = dp(context, 54)
            }
        }
        val actionStart = actionButton(R.id.guided_capture_dock_action_start, R.string.mapper_wizard_overlay_start)
        val actionReady = actionButton(R.id.guided_capture_dock_action_ready, R.string.mapper_wizard_overlay_ready)
        val actionCheck = actionButton(R.id.guided_capture_dock_action_check, R.string.mapper_wizard_overlay_check)
        val actionPause = actionButton(R.id.guided_capture_dock_action_pause, R.string.mapper_wizard_overlay_pause)
        val actionNext = actionButton(R.id.guided_capture_dock_action_next, R.string.mapper_wizard_overlay_next)
        floatingActionBar.addView(actionStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        floatingActionBar.addView(actionReady, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(context, 4) })
        floatingActionBar.addView(actionCheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(context, 4) })
        floatingActionBar.addView(actionPause, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(context, 4) })
        floatingActionBar.addView(actionNext, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(context, 4) })

        val topCandidateSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = safeDrawable(context, R.drawable.background_with_border)
            background?.mutate()?.alpha = (255 * 0.9f).toInt()
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
        }
        contentContainer.addView(topCandidateSection, fillWrap(top = 6, context = context))

        val topCandidateHeader = TextView(context).apply {
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
            text = "Top candidate: -"
        }
        val topCandidateDetails = TextView(context).apply {
            textSize = 9f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }
        val topCandidateWeaknesses = TextView(context).apply {
            textSize = 9f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_orange_dark))
        }
        val topCandidateBlockers = TextView(context).apply {
            textSize = 9f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
        }
        topCandidateSection.addView(topCandidateHeader)
        topCandidateSection.addView(topCandidateDetails, fillWrap(top = 2, context = context))
        topCandidateSection.addView(topCandidateWeaknesses, fillWrap(top = 2, context = context))
        topCandidateSection.addView(topCandidateBlockers, fillWrap(top = 2, context = context))

        val listHeader = TextView(context).apply {
            text = safeString(context, R.string.mapper_wizard_live_section_candidates, "Endpoint Candidates")
            textSize = 10f
        }
        contentContainer.addView(listHeader, fillWrap(top = 6, context = context))

        val candidateList = RecyclerView(context).apply {
            id = R.id.guided_capture_dock_candidate_list
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
        }
        contentContainer.addView(
            candidateList,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 3) },
        )

        val feedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        contentContainer.addView(feedContainer, fillWrap(top = 6, context = context))

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

        listOf(
            handle,
            refresh,
            copySummary,
            collapse,
            mode,
            feedToggle,
            overflow,
            actionStart,
            actionReady,
            actionCheck,
            actionPause,
            actionNext,
        ).forEach { button ->
            applyButtonPressFeedback(button)
        }

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
            topCandidateHeader = topCandidateHeader,
            topCandidateDetails = topCandidateDetails,
            topCandidateWeaknesses = topCandidateWeaknesses,
            topCandidateBlockers = topCandidateBlockers,
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

    private fun headerButtonParams(context: Context, first: Boolean = false): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (!first) marginStart = dp(context, 4)
        }
    }

    private fun rowSpacing(context: Context): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(context, 4) }
    }

    private fun resolvePanelWidthPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val screenWidthDp = context.resources.displayMetrics.widthPixels / density
        val desiredDp = (screenWidthDp * 0.78f).toInt().coerceIn(280, 360)
        return dp(context, desiredDp)
    }

    private fun resolvePanelHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val screenHeightDp = context.resources.displayMetrics.heightPixels / density
        val desiredDp = (screenHeightDp * 0.80f).toInt().coerceIn(360, 700)
        return dp(context, desiredDp)
    }

    private fun View.applyElevationCompat(elevationPx: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = elevationPx
        }
    }

    private fun applyButtonPressFeedback(view: TextView) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().setDuration(90).alpha(0.72f).scaleX(0.97f).scaleY(0.97f).start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().setDuration(90).alpha(1f).scaleX(1f).scaleY(1f).start()
                }
            }
            false
        }
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
