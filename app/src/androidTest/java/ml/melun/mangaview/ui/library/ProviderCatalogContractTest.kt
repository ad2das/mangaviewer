package ml.melun.mangaview.ui.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCatalogContractTest {
    @Test
    fun everyProviderKindExposesItsFullGenreSet() = runBlocking {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ViewerApplication).graph
        val ntk = graph.sources.require(SourceId("ntk"))
        val wfwf = graph.sources.require(SourceId("wfwf"))

        val sets = listOf(
            "ntk-webtoon" to ntk.genres(SeriesKind.WEBTOON),
            "ntk-comic" to ntk.genres(SeriesKind.COMIC),
            "wfwf-webtoon" to wfwf.genres(SeriesKind.WEBTOON),
            "wfwf-comic" to wfwf.genres(SeriesKind.COMIC),
        )

        sets.forEach { (name, genres) ->
            if (genres.isEmpty() && name.startsWith("wfwf")) {
                val kind = if (name.endsWith("comic")) SeriesKind.COMIC else SeriesKind.WEBTOON
                val source = graph.sources.require(SourceId("wfwf"))
                val first = source.catalog(CatalogQuery(kind, CatalogOrder.LATEST)).items.firstOrNull()
                val url = first?.let { source.seriesUrl(it.id) }
                throw AssertionError("$name exposes no live genres; resolved=$url")
            }
            assertTrue("$name exposes no live genres", genres.isNotEmpty())
            assertEquals("$name has duplicate genre keys", genres.size, genres.distinctBy { it.key }.size)
            assertTrue("$name contains a blank genre", genres.all { it.key.isNotBlank() && it.label.isNotBlank() })
        }
        assertTrue(sets[0].second.any { it.key == "category:bl" && it.label == "BL/백합" })
        assertTrue(sets[0].second.any { it.key == "category:adult" && it.label == "성인" })
    }

    @Test
    fun everyExposedGenreReturnsOnlyRealProviderSeries() = runBlocking {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ViewerApplication).graph
        val genericTitles = setOf("업데이트", "최신 업데이트", "전체", "웹툰", "만화", "목록", "더보기")
        listOf(SourceId("ntk"), SourceId("wfwf")).forEach { sourceId ->
            val source = graph.sources.require(sourceId)
            listOf(SeriesKind.WEBTOON, SeriesKind.COMIC).forEach { kind ->
                val genres = source.genres(kind)
                genres.forEach { genre ->
                    val series = source.catalog(CatalogQuery(kind, CatalogOrder.LATEST, genre)).items
                    Log.i(
                        "GenreSweep",
                        "$sourceId/$kind/${genre.label} count=${series.size} " +
                            "first=${series.firstOrNull()?.title}/${series.firstOrNull()?.subtitle}",
                    )
                    assertTrue("$sourceId/$kind/${genre.label} has no works", series.isNotEmpty())
                    if (sourceId == SourceId("wfwf") && genre.label != "일반") {
                        val evidence = series.first().subtitle.orEmpty().split('/').map(String::trim)
                        assertTrue(
                            "$sourceId/$kind/${genre.label} ignored its provider filter: " +
                                "${series.first().title}/${series.first().subtitle}",
                            evidence.any { token ->
                                token == genre.label || token.startsWith("${genre.label}+")
                            },
                        )
                    }
                    assertEquals(
                        "$sourceId/$kind/${genre.label} contains duplicate series",
                        series.size,
                        series.distinctBy { it.id }.size,
                    )
                    assertTrue(
                        "$sourceId/$kind/${genre.label} leaked another provider",
                        series.all { it.id.sourceId == sourceId },
                    )
                    assertFalse(
                        "$sourceId/$kind/${genre.label} exposed navigation labels: " +
                            series.filter { it.title.isBlank() || it.title in genericTitles }
                                .joinToString { "${it.title}:${it.id.remoteKey}" },
                        series.any { it.title.isBlank() || it.title in genericTitles },
                    )
                }
            }
        }
    }

    @Test
    fun adultAndYuriGenresOpenTheirOwnRealEpisodes() = runBlocking {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ViewerApplication).graph
        val cases = listOf(
            GenreCase(SourceId("ntk"), SeriesKind.WEBTOON, "성인"),
            GenreCase(SourceId("ntk"), SeriesKind.WEBTOON, "BL/백합"),
            GenreCase(SourceId("ntk"), SeriesKind.COMIC, "성인"),
            GenreCase(SourceId("ntk"), SeriesKind.COMIC, "백합"),
            GenreCase(SourceId("wfwf"), SeriesKind.WEBTOON, "성인"),
            GenreCase(SourceId("wfwf"), SeriesKind.WEBTOON, "BL"),
            GenreCase(SourceId("wfwf"), SeriesKind.COMIC, "성인"),
            GenreCase(SourceId("wfwf"), SeriesKind.COMIC, "백합"),
        )
        cases.forEach { case ->
            Log.i("GenreContract", "start=$case")
            val source = graph.sources.require(case.sourceId)
            val genre = source.genres(case.kind).singleOrNull { it.label == case.label }
            assertTrue("Missing genre: $case", genre != null)
            val series = source.catalog(
                CatalogQuery(case.kind, CatalogOrder.LATEST, requireNotNull(genre)),
            ).items.firstOrNull()
            Log.i("GenreContract", "catalog=$case title=${series?.title} id=${series?.id?.remoteKey}")
            assertTrue("Genre contains no works: $case", series != null)
            val selected = requireNotNull(series)
            assertEquals(case.sourceId, selected.id.sourceId)
            assertFalse("Genre leaked a navigation card: $case/${selected.title}",
                selected.title.isBlank() || selected.title == "업데이트")
            if (case.sourceId == SourceId("wfwf")) {
                assertTrue(
                    "WFWF returned a work outside the requested category: $case/${selected.title}/${selected.subtitle}",
                    selected.subtitle.orEmpty().split('/').map(String::trim).contains(case.label),
                )
            }

            val episodes = source.episodes(selected.id).items
            Log.i(
                "GenreContract",
                "episodes=$case count=${episodes.size} first=${episodes.firstOrNull()?.id?.remoteKey}",
            )
            assertTrue("Selected work contains no real episodes: $case/${selected.title}", episodes.isNotEmpty())
            assertTrue("Episode identity changed after selecting the work: $case/${selected.title}",
                episodes.all { it.id.seriesId == selected.id })
            val episode = episodes.first()
            val manifest = withTimeout(PROVIDER_STEP_TIMEOUT_MILLIS) {
                source.manifest(episode.id)
            }
            Log.i("GenreContract", "manifest=$case pages=${manifest.pages.size}")
            assertEquals("Viewer opened another episode: $case/${selected.title}", episode.id, manifest.id)
            assertTrue("Viewer has no real pages: $case/${selected.title}", manifest.pages.isNotEmpty())
            assertTrue("Page identity changed in viewer: $case/${selected.title}",
                manifest.pages.all { it.id.episodeId.seriesId == selected.id })
            val opened = withTimeout(PROVIDER_STEP_TIMEOUT_MILLIS) {
                source.openPage(manifest.pages.first().id)
            }
            try {
                val prefix = ByteArray(12)
                val received = withTimeout(PROVIDER_STEP_TIMEOUT_MILLIS) {
                    opened.stream.readAtMost(prefix, 0, prefix.size)
                }
                assertTrue("First viewer page returned no bytes: $case/${selected.title}", received > 0)
            } finally {
                opened.stream.close()
            }
        }
    }

    @Test
    fun liveCatalogsNeverExposeNavigationActionsAsEpisodes() = runBlocking {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ViewerApplication).graph
        val forbidden = listOf("최신화 보기", "첫화부터", "처음부터", "정주행", "이어보기", "전체보기")
        listOf(SourceId("ntk"), SourceId("wfwf")).forEach { sourceId ->
            val source = graph.sources.require(sourceId)
            listOf(SeriesKind.WEBTOON, SeriesKind.COMIC).forEach { kind ->
                val series = source.catalog(CatalogQuery(kind, CatalogOrder.POPULAR)).items.first()
                val episodes = source.episodes(series.id).items
                assertTrue("$sourceId/$kind returned no episodes", episodes.isNotEmpty())
                assertFalse(
                    "$sourceId/$kind exposed a navigation action as an episode",
                    episodes.any { episode -> forbidden.any(episode.title::contains) },
                )
                assertEquals(episodes.size, episodes.distinctBy { it.id }.size)
            }
        }
    }
}

private data class GenreCase(
    val sourceId: SourceId,
    val kind: SeriesKind,
    val label: String,
)

private const val PROVIDER_STEP_TIMEOUT_MILLIS = 30_000L
