package ml.melun.mangaview.activity

import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Title

object ReaderDisplayPolicy {
    private const val DEFAULT_PAGE_GAP_PX = 2
    private const val WEBTOON_PAGE_GAP_PX = 0

    @JvmStatic
    fun pageGapForBaseMode(baseMode: Int): Int {
        return if (baseMode == MTitle.base_webtoon) WEBTOON_PAGE_GAP_PX else DEFAULT_PAGE_GAP_PX
    }

    @JvmStatic
    fun shouldEnableAdjacentButton(hasAdjacent: Boolean, canFetchMissingAdjacent: Boolean): Boolean {
        return hasAdjacent || canFetchMissingAdjacent
    }

    fun fastDisplayEpisodeTitle(manga: Manga?, title: Title?): String {
        return manga?.name?.trim()?.takeIf { it.isNotBlank() }
            ?: title?.name?.takeIf { it.isNotBlank() }
            ?: manga?.title?.name?.takeIf { it.isNotBlank() }
            ?: "회차"
    }

    fun episodeDisplayName(manga: Manga?, episodes: List<Manga?>, index: Int, title: Title?): String {
        val cleaned = Manga.cleanViewerEpisodeName(manga?.name).takeIf { it.isNotBlank() }
        if (cleaned != null) return cleaned
        val matched = episodes.getOrNull(index)
        val matchedName = Manga.cleanViewerEpisodeName(matched?.name).takeIf { it.isNotBlank() }
        if (matchedName != null) return matchedName
        val number = Manga.visibleEpisodeNumberKey(manga?.name).takeIf { it.isNotBlank() }
        if (number != null) return "${number}화"
        val matchedNumber = Manga.visibleEpisodeNumberKey(matched?.name).takeIf { it.isNotBlank() }
        if (matchedNumber != null) return "${matchedNumber}화"
        return title?.name?.takeIf { it.isNotBlank() }
            ?: manga?.title?.name?.takeIf { it.isNotBlank() }
            ?: ""
    }

    fun episodeIndex(episodes: List<Manga?>, target: Manga?): Int {
        if (target == null) return -1
        for (i in episodes.indices) {
            val episode = episodes[i]
            if (episode != null && Manga.sameEpisodeIdentity(episode, target)) return i
        }
        return -1
    }
}
