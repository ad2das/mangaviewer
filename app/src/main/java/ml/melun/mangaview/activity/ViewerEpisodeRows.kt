package ml.melun.mangaview.activity

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.SectionIndexer
import android.widget.TextView
import ml.melun.mangaview.ui.AppFonts

/** Rows show the exact episode title as text; the open episode adds an accent chip beside it. */
internal class EpisodeSheetAdapter(
    private val titles: List<String>,
    val currentIndex: Int,
    var palette: ViewerPalette,
) : BaseAdapter(), SectionIndexer {
    /** Positions into [titles] that pass the search; every row when the query is blank. */
    private var shown: List<Int> = titles.indices.toList()
    private var sections: Array<Any> = sectionsFor(shown)

    fun filter(query: String) {
        val needle = query.trim()
        shown = if (needle.isEmpty()) titles.indices.toList()
        else titles.indices.filter { titles[it].contains(needle, ignoreCase = true) }
        sections = sectionsFor(shown)
        notifyDataSetChanged()
    }

    fun episodeAt(position: Int): Int = shown[position]
    fun positionOf(episode: Int): Int = shown.indexOf(episode)

    private fun sectionsFor(rows: List<Int>): Array<Any> = Array((rows.size + SECTION - 1) / SECTION) { index ->
        titles[rows[index * SECTION]]
    }

    override fun getCount(): Int = shown.size
    override fun getItem(position: Int): Any = titles[shown[position]]
    override fun getItemId(position: Int): Long = shown[position].toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView as? EpisodeRow ?: EpisodeRow(parent.context)
        row.bind(titles[shown[position]], shown[position] == currentIndex, palette)
        return row
    }

    override fun getSections(): Array<Any> = sections
    override fun getPositionForSection(section: Int): Int = (section * SECTION).coerceIn(0, (count - 1).coerceAtLeast(0))
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

/** Rounded search box for the episode sheet; filters as the reader types. */
internal class EpisodeSearchField(context: Context, private val changed: (String) -> Unit) :
    android.widget.EditText(context) {
    init {
        hint = "회차 검색 (제목·번호)"
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        setPadding(context.dp(14), 0, context.dp(14), 0)
        compoundDrawablePadding = context.dp(10)
        setCompoundDrawablesRelativeWithIntrinsicBounds(ml.melun.mangaview.R.drawable.ic_search, 0, 0, 0)
        contentDescription = "회차 검색"
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = changed(s?.toString().orEmpty())
        })
        setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
    }

    fun applyPalette(value: ViewerPalette) {
        style(14f, AppFonts.REGULAR, value.text)
        setHintTextColor(value.secondary)
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(value.secondary)
        background = roundedFill(value.surface, context.dpf(14f))
        highlightColor = value.accentSurface
    }

    fun clear() {
        if (!text.isNullOrEmpty()) setText("")
    }

    fun hideKeyboard() {
        clearFocus()
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }
}
