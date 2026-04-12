package info.plateaukao.einkbro.mapper.missiondock.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.mapper.missiondock.state.CandidateCardViewState
import java.util.Locale

class MissionDockCandidateAdapter(
    private val onToggleExpand: (String) -> Unit,
    private val onSelect: (CandidateCardViewState) -> Unit,
    private val onExclude: (CandidateCardViewState) -> Unit,
    private val onTest: (CandidateCardViewState) -> Unit,
    private val onCopy: (CandidateCardViewState) -> Unit,
) : ListAdapter<CandidateCardViewState, MissionDockCandidateAdapter.CandidateViewHolder>(DiffCallback) {

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<CandidateCardViewState>() {
            override fun areItemsTheSame(oldItem: CandidateCardViewState, newItem: CandidateCardViewState): Boolean {
                return oldItem.endpointId == newItem.endpointId
            }

            override fun areContentsTheSame(oldItem: CandidateCardViewState, newItem: CandidateCardViewState): Boolean {
                return oldItem == newItem
            }
        }
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).endpointId.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val context = parent.context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = safeDrawable(context, R.drawable.background_with_border)
            background?.mutate()?.alpha = (255 * 0.9f).toInt()
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(context, 6)
            }
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            headerRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val title = TextView(context).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }
        headerRow.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 4)
            },
        )

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        headerRow.addView(actionRow)

        val selectButton = createButton(context)
        val excludeButton = createButton(context)
        val testButton = createButton(context)
        val copyButton = createButton(context)
        listOf(selectButton, excludeButton, testButton, copyButton).forEach { button ->
            applyButtonPressFeedback(button)
        }
        actionRow.addView(selectButton)
        actionRow.addView(excludeButton, buttonParams(context))
        actionRow.addView(testButton, buttonParams(context))
        actionRow.addView(copyButton, buttonParams(context))

        val template = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }
        root.addView(template, topMargin(context, 3))

        val metrics = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        root.addView(metrics, topMargin(context, 2))

        val badges = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_orange_dark))
        }
        root.addView(badges, topMargin(context, 2))

        val detailContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(detailContainer, topMargin(context, 4))

        val observedExamples = detailBlock(context)
        val rankReasons = detailBlock(context)
        val fieldHits = detailBlock(context)
        val runtimeViability = detailBlock(context)
        val warnings = detailBlock(context)
        val missingProof = detailBlock(context)
        val exportReadiness = detailBlock(context)
        val endpointInfo = detailBlock(context)
        val testResult = detailBlock(context)
        detailContainer.addView(observedExamples)
        detailContainer.addView(rankReasons, topMargin(context, 2))
        detailContainer.addView(fieldHits, topMargin(context, 2))
        detailContainer.addView(runtimeViability, topMargin(context, 2))
        detailContainer.addView(warnings, topMargin(context, 2))
        detailContainer.addView(missingProof, topMargin(context, 2))
        detailContainer.addView(exportReadiness, topMargin(context, 2))
        detailContainer.addView(endpointInfo, topMargin(context, 2))
        detailContainer.addView(testResult, topMargin(context, 2))

        return CandidateViewHolder(
            root = root,
            title = title,
            template = template,
            metrics = metrics,
            badges = badges,
            detailContainer = detailContainer,
            observedExamples = observedExamples,
            rankReasons = rankReasons,
            fieldHits = fieldHits,
            runtimeViability = runtimeViability,
            warnings = warnings,
            missingProof = missingProof,
            exportReadiness = exportReadiness,
            endpointInfo = endpointInfo,
            testResult = testResult,
            selectButton = selectButton,
            excludeButton = excludeButton,
            testButton = testButton,
            copyButton = copyButton,
        )
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.root.context
        val rolePrefix = when {
            item.topCandidate -> "TOP"
            item.selected -> "SELECTED"
            item.excluded -> "EXCLUDED"
            else -> "CANDIDATE"
        }
        holder.title.text = "$rolePrefix · ${item.role.uppercase(Locale.ROOT)}"
        holder.template.text = item.generalizedTemplate
        holder.metrics.text = buildString {
            append("score=")
            append(item.score.toInt())
            append("  conf=")
            append(String.format(Locale.ROOT, "%.2f", item.confidence))
            append("  ev=")
            append(item.evidenceCount)
            if (item.operation.isNotBlank()) {
                append("  op=")
                append(item.operation)
            }
        }

        val badges = mutableListOf<String>()
        if (item.runtimeViability.isNotBlank()) badges += "viability:${item.runtimeViability}"
        if (item.exportReadiness.isNotBlank()) badges += "export:${item.exportReadiness}"
        if (item.missingProof.isNotEmpty()) badges += "missing:${item.missingProof.joinToString(",")}"
        if (item.warnings.isNotEmpty()) badges += item.warnings.joinToString(",")
        holder.badges.text = badges.joinToString(" | ").ifBlank { "ready_for_review" }
        holder.badges.visibility = View.VISIBLE

        val titleColor = when {
            item.excluded -> android.R.color.holo_red_dark
            item.selected -> android.R.color.holo_green_dark
            item.topCandidate -> android.R.color.holo_blue_dark
            else -> android.R.color.black
        }
        holder.title.setTextColor(ContextCompat.getColor(context, titleColor))
        holder.root.alpha = if (item.lowQuality && !item.selected) 0.68f else 1f
        val tint = when {
            item.topCandidate -> android.R.color.holo_green_light
            item.excluded -> android.R.color.darker_gray
            else -> android.R.color.transparent
        }
        ViewCompat.setBackgroundTintList(
            holder.root,
            ColorStateList.valueOf(ContextCompat.getColor(context, tint)),
        )

        holder.detailContainer.visibility = if (item.expanded) View.VISIBLE else View.GONE
        holder.observedExamples.text = "Observed examples: ${item.observedExamples.joinToString(" | ").ifBlank { "-" }}"
        holder.rankReasons.text = "Rank reasons: ${item.rankReasons.joinToString(", ").ifBlank { "-" }}"
        holder.fieldHits.text = "Field hits: ${item.fieldHits.joinToString(", ").ifBlank { "-" }}"
        holder.runtimeViability.text = "Runtime viability: ${item.runtimeViability}"
        holder.warnings.text = "Warnings: ${item.warnings.joinToString(", ").ifBlank { "-" }}"
        holder.missingProof.text = "Missing proof linkage: ${item.missingProof.joinToString(", ").ifBlank { "-" }}"
        holder.exportReadiness.text = "Export readiness: ${item.exportReadiness}"
        holder.endpointInfo.text = "Endpoint: ${item.method.uppercase(Locale.ROOT)} ${item.host}${item.path} | operation=${item.operation.ifBlank { "-" }}"
        holder.testResult.text = buildString {
            append("Last test: ")
            item.testResult?.let { result ->
                append("${result.status} ${result.durationMillis}ms size=${result.sizeBytes} mime=${result.mimeType} ok=${result.ok}")
            } ?: append("-")
        }

        holder.selectButton.text = safeString(
            context = context,
            resId = if (item.selected) R.string.mapper_wizard_live_selected else R.string.mapper_wizard_live_select,
            fallback = if (item.selected) "Selected" else "Select",
        )
        holder.selectButton.isEnabled = !item.selected && !item.excluded
        holder.selectButton.alpha = if (holder.selectButton.isEnabled) 1f else 0.5f

        holder.excludeButton.text = safeString(
            context = context,
            resId = if (item.excluded) R.string.mapper_wizard_live_include else R.string.mapper_wizard_live_exclude,
            fallback = if (item.excluded) "Include" else "Exclude",
        )
        holder.excludeButton.isEnabled = true
        holder.excludeButton.alpha = 1f

        holder.testButton.text = if (item.testing) {
            "Testing..."
        } else {
            safeString(context, R.string.mapper_wizard_live_test, "Test")
        }
        holder.testButton.isEnabled = !item.excluded && !item.testing
        holder.testButton.alpha = if (holder.testButton.isEnabled) 1f else 0.5f

        holder.copyButton.text = safeString(context, R.string.mapper_wizard_live_copy_candidate, "Copy")
        holder.copyButton.isEnabled = true
        holder.copyButton.alpha = 1f

        holder.root.setOnClickListener { onToggleExpand(item.endpointId) }
        holder.selectButton.setOnClickListener { onSelect(item) }
        holder.excludeButton.setOnClickListener { onExclude(item) }
        holder.testButton.setOnClickListener { onTest(item) }
        holder.copyButton.setOnClickListener { onCopy(item) }
    }

    class CandidateViewHolder(
        val root: LinearLayout,
        val title: TextView,
        val template: TextView,
        val metrics: TextView,
        val badges: TextView,
        val detailContainer: LinearLayout,
        val observedExamples: TextView,
        val rankReasons: TextView,
        val fieldHits: TextView,
        val runtimeViability: TextView,
        val warnings: TextView,
        val missingProof: TextView,
        val exportReadiness: TextView,
        val endpointInfo: TextView,
        val testResult: TextView,
        val selectButton: TextView,
        val excludeButton: TextView,
        val testButton: TextView,
        val copyButton: TextView,
    ) : RecyclerView.ViewHolder(root)

    private fun createButton(context: Context): TextView {
        return TextView(context).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            background = safeDrawable(context, R.drawable.roundcorner)
            setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4))
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            minHeight = dp(context, 48)
            minWidth = dp(context, 58)
        }
    }

    private fun detailBlock(context: Context): TextView {
        return TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }
    }

    private fun topMargin(context: Context, value: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, value) }
    }

    private fun buttonParams(context: Context): LinearLayout.LayoutParams {
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

    private fun applyButtonPressFeedback(view: TextView) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().setDuration(90).alpha(0.72f).scaleX(0.97f).scaleY(0.97f).start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().setDuration(90).alpha(1f).scaleX(1f).scaleY(1f).start()
                }
            }
            false
        }
    }
}
