package ml.melun.mangaview.reader

import android.content.Context
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.repository.MangaRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderImageRepositoryTest {
    @Test
    fun repositoryPortCanBeFakedForReaderSessionMigration() {
        val fake = object : ReaderImageRepository {
            override fun imageUrls(manga: Manga?, context: Context?): List<String> {
                return listOf("https://example.com/1.jpg")
            }

            override fun fetchViewerInitial(manga: Manga, cancellation: MangaRepository.Cancellation): Int {
                return 0
            }

            override fun fetchEpisodesForeground(title: Title, cancellation: MangaRepository.Cancellation): Int {
                return 0
            }
        }

        assertEquals(listOf("https://example.com/1.jpg"), fake.imageUrls(null, null))
        assertEquals(0, fake.fetchEpisodesForeground(Title("", "", "", null, "", 1, 0), MangaRepository.cancellation()))
    }

    @Test
    fun legacyRepositoryNormalizesMissingImagesToEmptyList() {
        assertEquals(emptyList<String>(), LegacyReaderImageRepository.imageUrls(null, null))
    }
}
