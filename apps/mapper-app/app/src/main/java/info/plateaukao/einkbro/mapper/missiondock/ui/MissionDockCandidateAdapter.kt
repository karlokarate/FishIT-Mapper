package info.plateaukao.einkbro.mapper.missiondock.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.Gravity
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
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(context, 6)
            }
        }

        val title = TextView(context).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }
        root.addView(title)

        val meta = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        root.addView(meta)

        val badges = TextView(context).apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_orange_dark))
        }
        root.addView(
            badges,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 2) },
        )

        val detail = TextView(context).apply {
            textSize = 10f
            visibility = View.GONE
        }
        root.addView(detail)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        root.addView(
            actionRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 4) },
        )

        val selectButton = createButton(context)
        val excludeButton = createButton(context)
        val testButton = createButton(context)
        val copyButton = createButton(context)
        actionRow.addView(selectButton)
        actionRow.addView(excludeButton, buttonParams(context))
        actionRow.addView(testButton, buttonParams(context))
        actionRow.addView(copyButton, buttonParams(context))

        return CandidateViewHolder(
            root = root,
            title = title,
            meta = meta,
            badges = badges,
            detail = detail,
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
        holder.meta.text = buildString {
            append(item.generalizedTemplate)
            append('\n')
            append("score=")
            append(item.score.toInt())
            append(" conf=")
            append(String.format(Locale.ROOT, "%.2f", item.confidence))
            append(" ev=")
            append(item.evidenceCount)
            if (item.operation.isNotBlank()) {
                append(" op=")
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

        holder.detail.visibility = if (item.expanded) View.VISIBLE else View.GONE
        holder.detail.text = buildString {
            append("observed_examples=")
            append(item.observedExamples.joinToString(" | ").ifBlank { "-" })
            append('\n')
            append("rank_reasons=")
            append(item.rankReasons.joinToString(", ").ifBlank { "-" })
            append('\n')
            append("field_hits=")
            append(item.fieldHits.joinToString(", ").ifBlank { "-" })
            append('\n')
            append("runtime_viability=")
            append(item.runtimeViability)
            append('\n')
            append("warnings=")
            append(item.warnings.joinToString(", ").ifBlank { "-" })
            append('\n')
            append("missing_proof=")
            append(item.missingProof.joinToString(", ").ifBlank { "-" })
            append('\n')
            append("export_readiness=")
            append(item.exportReadiness)
            append('\n')
            append("host=")
            append(item.host)
            append(" path=")
            append(item.path)
            append('\n')
            append("operation=")
            append(item.operation.ifBlank { "-" })
            append('\n')
            append("test=")
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
        val meta: TextView,
        val badges: TextView,
        val detail: TextView,
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
        }
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
}
