package ml.melun.mangaview.activity

import android.content.Context
import android.widget.ListView

/**
 * Episode picker list for a series with a long run.
 *
 * The platform's fling carries a fixed distance, so a five-hundred episode series takes a dozen
 * flicks to cross. Scroll distance grows with the square of the launch velocity, so doubling the
 * velocity roughly quadruples the travel: a few flings reach the end, and the always-visible
 * fast-scroll thumb can be dragged straight there in one gesture. The thumb is the reason the
 * sheet adapter reports sections at all — a ListView only shows it for a SectionIndexer.
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
