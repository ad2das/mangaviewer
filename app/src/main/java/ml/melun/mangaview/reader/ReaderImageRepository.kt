package ml.melun.mangaview.reader

import android.content.Context
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.repository.MangaRepository

interface ReaderImageRepository {
    fun imageUrls(manga: Manga?, context: Context?): List<String>

    @Throws(Exception::class)
    fun fetchViewerInitial(manga: Manga, cancellation: MangaRepository.Cancellation): Int

    @Throws(Exception::class)
    fun fetchViewerInitialWithMode(manga: Manga, cancellation: MangaRepository.Cancellation, mode: String): Int

    fun fetchEpisodesForeground(title: Title, cancellation: MangaRepository.Cancellation): Int
}

object LegacyReaderImageRepository : ReaderImageRepository {
    override fun imageUrls(manga: Manga?, context: Context?): List<String> {
        return MangaRepository.imageUrls(manga, context) ?: emptyList()
    }

    override fun fetchViewerInitial(manga: Manga, cancellation: MangaRepository.Cancellation): Int {
        return MangaRepository.fetchViewerInitial(manga, cancellation)
    }

    override fun fetchViewerInitialWithMode(
        manga: Manga,
        cancellation: MangaRepository.Cancellation,
        mode: String
    ): Int {
        return MangaRepository.fetchViewerInitialWithMode(manga, cancellation, mode)
    }

    override fun fetchEpisodesForeground(title: Title, cancellation: MangaRepository.Cancellation): Int {
        return MangaRepository.fetchEpisodesForeground(title, cancellation)
    }
}
