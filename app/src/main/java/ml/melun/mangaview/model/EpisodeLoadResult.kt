package ml.melun.mangaview.model

import ml.melun.mangaview.mangaview.Manga

data class EpisodeLoadResult(
    val resultCode: Int,
    val episodes: List<Manga>
)
