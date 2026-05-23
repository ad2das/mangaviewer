package ml.melun.mangaview.reader

import ml.melun.mangaview.mangaview.Manga

object ReadTargetResolver {
    @JvmStatic
    fun likelyReadTarget(episodes: List<Manga>?, bookmarkIndex: Int): Manga? {
        if (episodes.isNullOrEmpty()) return null
        if (bookmarkIndex > 0 && bookmarkIndex <= episodes.size) {
            episodes[bookmarkIndex - 1]?.let { return it }
        }
        val first = firstReadableEpisodeIndex(episodes)
        return episodes.getOrNull(first) ?: episodes.firstOrNull()
    }

    @JvmStatic
    fun firstReadableEpisodeIndex(episodes: List<Manga>?): Int {
        if (episodes.isNullOrEmpty()) return -1
        return episodes.size - 1
    }
}
