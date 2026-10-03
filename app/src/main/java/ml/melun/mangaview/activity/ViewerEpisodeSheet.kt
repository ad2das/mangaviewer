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
    private val search = EpisodeSearchField(context) { query -> adapter?.filter(query); updateEmpty() }
    private val empty = TextView(context).apply {
        text = "검색 결과가 없습니다"
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val jumpCurrent = jumpChip("읽는 회차") { adapter?.currentIndex?.takeIf { it >= 0 } }
    private val jumpFirst = jumpChip("첫 화") { adapter?.count?.minus(1)?.takeIf { it >= 0 } }
    private val jumpLatest = jumpChip("최신 화") { 0.takeIf { (adapter?.count ?: 0) > 0 } }
    private val tools = LinearLayout(context)
    private val adapter: EpisodeSheetAdapter? get() = list.adapter as? EpisodeSheetAdapter
    private var bottomInset = 0
    private var imeInset = 0
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
        card.addView(toolRow())
        card.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(120)))
        card.addView(spinner, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, context.dp(36), 0, context.dp(48))
        })
        card.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.setOnItemClickListener { _, _, position, _ ->
            val episode = adapter?.episodeAt(position) ?: return@setOnItemClickListener
            dismiss()
            onPick(episode)
        }
        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) {
                if (state == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) search.hideKeyboard()
            }

            override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) = Unit
        })
        // The reader window does not resize for the keyboard, so the sheet lifts itself above it.
        setOnApplyWindowInsetsListener { _, insets ->
            imeInset = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            updateCardPadding()
            insets
        }
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

    private fun toolRow(): View = tools.apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(18), 0, context.dp(18), context.dp(8))
        addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)))
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(jumpCurrent, jumpFirst, jumpLatest).forEach { chip ->
                addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(36)).apply {
                    marginEnd = context.dp(8)
                })
            }
        }
        addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = context.dp(10) })
    }

    /** A quick jump clears any search first so the target row is guaranteed to be listed. */
    private fun jumpChip(label: String, target: () -> Int?) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(context.dp(14), 0, context.dp(14), 0)
        isClickable = true
        setOnClickListener {
            search.clear()
            val episode = target() ?: return@setOnClickListener
            val position = adapter?.positionOf(episode)?.takeIf { it >= 0 } ?: return@setOnClickListener
            list.setSelectionFromTop(position, context.dp(72))
        }
    }

    private fun updateEmpty() {
        val none = adapter?.count == 0
        empty.visibility = if (none) View.VISIBLE else View.GONE
        list.visibility = if (none) View.GONE else View.VISIBLE
    }

    private fun updateCardPadding() {
        card.setPadding(0, 0, 0, maxOf(bottomInset, imeInset) + context.dp(8))
    }

    fun applyPalette(value: ViewerPalette) {
        palette = value
        search.applyPalette(value)
        empty.style(14f, AppFonts.MEDIUM, value.secondary)
        listOf(jumpCurrent, jumpFirst, jumpLatest).forEach { chip ->
            chip.style(13f, AppFonts.SEMIBOLD, value.text)
            chip.background = pressable(value, value.surface, context.dpf(18f))
        }
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
        updateCardPadding()
    }

    /** Opens at once with a spinner so the tap is acknowledged while the catalog loads. */
    fun showLoading() {
        spinner.visibility = View.VISIBLE
        list.visibility = View.GONE
        tools.visibility = View.GONE
        empty.visibility = View.GONE
        count.text = ""
        enter()
    }

    /** Fills the sheet the reader is waiting on; a sheet they already closed stays closed. */
    fun showEpisodes(titles: List<String>, currentIndex: Int, pick: (Int) -> Unit) {
        if (visibility != View.VISIBLE || closing) return
        onPick = pick
        spinner.visibility = View.GONE
        list.visibility = View.VISIBLE
        tools.visibility = View.VISIBLE
        search.clear()
        jumpCurrent.visibility = if (currentIndex >= 0) View.VISIBLE else View.GONE
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
        search.hideKeyboard()
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
