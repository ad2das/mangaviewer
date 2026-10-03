package ml.melun.mangaview.activity

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SectionIndexer
import android.widget.TextView
import ml.melun.mangaview.ui.AppFonts

/**
 * Episode list as a reader bottom sheet in the reader's own palette, replacing the platform
 * dialog. The open episode is marked with a "읽는 중" chip and scrolled into view; long runs keep
 * the fast-scroll thumb from [EpisodePickerList].
 */
internal class ViewerEpisodeSheet(context: Context) : FrameLayout(context) {
    private var palette = ViewerPalette.of(dark = true)
    private val card = LinearLayout(context)
    private val handle = View(context)
    private val title = TextView(context).apply { text = "회차 선택" }
    private val count = TextView(context)
    private val cancel = TextView(context).apply { text = "취소" }
    private val spinner = ProgressBar(context)
    private val list = EpisodePickerList(context)
    private var bottomInset = 0
    private var onPick: (Int) -> Unit = {}
    private var closing = false

    val visible: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { dismiss() }
        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        card.addView(handle, LinearLayout.LayoutParams(context.dp(36), context.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = context.dp(10)
            bottomMargin = context.dp(8)
        })
        card.addView(header())
        card.addView(spinner, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, context.dp(36), 0, context.dp(48))
        })
        card.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.setOnItemClickListener { _, _, position, _ -> dismiss(); onPick(position) }
        cancel.setOnClickListener { dismiss() }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        applyPalette(palette)
    }

    private fun header(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(22), 0, context.dp(10), context.dp(8))
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(8)
        })
        addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(44)))
        cancel.gravity = Gravity.CENTER
        cancel.setPadding(context.dp(14), 0, context.dp(14), 0)
    }

    fun applyPalette(value: ViewerPalette) {
        palette = value
        setBackgroundColor(value.scrim)
        card.background = GradientDrawable().apply {
            setColor(value.sheet)
            val radius = context.dpf(24f)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        handle.background = roundedFill(value.track, context.dpf(2f))
        title.style(18f, AppFonts.BOLD, value.text)
        count.style(13f, AppFonts.MEDIUM, value.secondary)
        cancel.style(14f, AppFonts.SEMIBOLD, value.secondary)
        cancel.background = pressable(value, Color.TRANSPARENT, context.dpf(12f))
        spinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(value.accent)
        (list.adapter as? EpisodeSheetAdapter)?.palette = value
        list.invalidateViews()
    }

    fun applyInsets(bottom: Int) {
        if (bottomInset == bottom) return
        bottomInset = bottom
        (card.layoutParams as? LayoutParams)?.let { it.bottomMargin = -bottom; card.layoutParams = it }
        card.setPadding(0, 0, 0, bottom + context.dp(8))
    }

    /** Opens at once with a spinner so the tap is acknowledged while the catalog loads. */
    fun showLoading() {
        spinner.visibility = View.VISIBLE
        list.visibility = View.GONE
        count.text = ""
        enter()
    }

    /** Fills the sheet the reader is waiting on; a sheet they already closed stays closed. */
    fun showEpisodes(titles: List<String>, currentIndex: Int, pick: (Int) -> Unit) {
        if (visibility != View.VISIBLE || closing) return
        onPick = pick
        spinner.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.adapter = EpisodeSheetAdapter(titles, currentIndex, palette)
        list.isFastScrollAlwaysVisible = titles.size >= FAST_SCROLL_FROM
        (list.layoutParams as LinearLayout.LayoutParams).height =
            (resources.displayMetrics.heightPixels * LIST_HEIGHT_FRACTION).toInt()
        list.requestLayout()
        count.text = "총 ${titles.size}화"
        if (currentIndex > 0) list.setSelectionFromTop(currentIndex, context.dp(96))
        enter()
    }

    private fun enter() {
        if (visibility == View.VISIBLE && !closing) return
        closing = false
        animate().cancel()
        card.animate().cancel()
        alpha = 0f
        visibility = View.VISIBLE
        card.translationY = context.dpf(320f)
        animate().alpha(1f).setDuration(FADE_MS).start()
        card.animate().translationY(0f).setDuration(ENTER_MS).setInterpolator(EMPHASIZED).start()
    }

    fun dismiss() {
        if (visibility != View.VISIBLE || closing) return
        closing = true
        animate().cancel()
        card.animate().cancel()
        card.animate().translationY(card.height.toFloat()).setDuration(EXIT_MS).start()
        animate().alpha(0f).setDuration(EXIT_MS).withEndAction {
            closing = false
            visibility = View.GONE
            alpha = 1f
            card.translationY = 0f
        }.start()
    }

    private companion object {
        const val FAST_SCROLL_FROM = 40
        const val LIST_HEIGHT_FRACTION = 0.56f
        const val FADE_MS = 180L
        const val ENTER_MS = 280L
        const val EXIT_MS = 180L
        val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}

/** Rows show the exact episode title as text; the open episode adds an accent chip beside it. */
private class EpisodeSheetAdapter(
    private val titles: List<String>,
    private val currentIndex: Int,
    var palette: ViewerPalette,
) : BaseAdapter(), SectionIndexer {
    private val sections: Array<Any> = Array((titles.size + SECTION - 1) / SECTION) { index ->
        titles.getOrElse(index * SECTION) { "" }
    }

    override fun getCount(): Int = titles.size
    override fun getItem(position: Int): Any = titles[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView as? EpisodeRow ?: EpisodeRow(parent.context)
        row.bind(titles[position], position == currentIndex, palette)
        return row
    }

    override fun getSections(): Array<Any> = sections
    override fun getPositionForSection(section: Int): Int = (section * SECTION).coerceAtMost(count - 1)
    override fun getSectionForPosition(position: Int): Int = position / SECTION

    private companion object {
        const val SECTION = 50
    }
}

private class EpisodeRow(context: Context) : LinearLayout(context) {
    private val title = TextView(context)
    private val chip = TextView(context).apply { text = "읽는 중" }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(56)
        setPadding(context.dp(22), context.dp(6), context.dp(22), context.dp(6))
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(10)
        })
        chip.setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(3))
    }

    fun bind(text: String, current: Boolean, palette: ViewerPalette) {
        title.text = text
        title.style(15f, if (current) AppFonts.BOLD else AppFonts.REGULAR, if (current) palette.accent else palette.text)
        chip.visibility = if (current) View.VISIBLE else View.GONE
        chip.style(11f, AppFonts.BOLD, palette.accent)
        chip.background = roundedFill(palette.accentSurface, context.dpf(8f))
        background = pressable(palette, if (current) palette.accentSurface.withAlpha(0x66) else Color.TRANSPARENT, 0f)
    }
}

private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)
