package ml.melun.mangaview.activity

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.SectionIndexer
import android.widget.TextView

/**
 * Episode picker list for a series with a long run.
 *
 * The platform's fling carries a fixed distance, so a five-hundred episode series takes a dozen
 * flicks to cross. Scroll distance grows with the square of the launch velocity, so doubling the
 * velocity roughly quadruples the travel: a few flings reach the end, and the always-visible
 * fast-scroll thumb can be dragged straight there in one gesture. The thumb is the reason the
 * adapter below reports sections at all — a ListView only shows it for a SectionIndexer.
 */
internal class EpisodePickerList(context: Context) : ListView(context) {
    override fun fling(velocityY: Int) {
        super.fling((velocityY * FLING_BOOST).toInt())
    }

    private companion object {
        /** Doubling the launch velocity multiplies the scroll distance by about four. */
        const val FLING_BOOST = 2.0f
    }
}

/**
 * Episode titles with the one being read marked, grouped every [SECTION] rows so the fast-scroll
 * bubble names the block being dragged through instead of showing a blank label.
 */
internal class EpisodePickerAdapter(
    private val titles: List<String>,
    private val currentIndex: Int,
) : BaseAdapter(), SectionIndexer {
    private val sections: Array<Any> = Array((titles.size + SECTION - 1) / SECTION) { index ->
        titles.getOrElse(index * SECTION) { "" }
    }

    override fun getCount(): Int = titles.size
    override fun getItem(position: Int): Any = titles[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun isEnabled(position: Int): Boolean = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView ?: LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)) as TextView
        val title = titles[position]
        val current = position == currentIndex
        row.text = if (current) "▶ $title" else title
        row.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
        return row
    }

    override fun getSections(): Array<Any> = sections
    override fun getPositionForSection(section: Int): Int = (section * SECTION).coerceAtMost(count - 1)
    override fun getSectionForPosition(position: Int): Int = position / SECTION

    private companion object {
        const val SECTION = 50
    }
}
