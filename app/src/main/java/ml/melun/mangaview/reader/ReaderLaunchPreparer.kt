package ml.melun.mangaview.reader

import android.content.Context
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title

object ReaderLaunchPreparer {
    const val EXTRA_PREPARED_KEY = "ml.melun.mangaview.reader.PREPARED_KEY"

    @JvmStatic
    fun prepareFirstFrame(
        context: Context,
        manga: Manga,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): String? {
        return ReaderWarmupCoordinator.prepareBlocking(context, manga, title, viewerWidth, exactEpisode)
    }
}
