package ml.melun.mangaview.source.ntk

import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class NtkDocumentParserTest {
    private val parser = NtkDocumentParser()
    private val sourceId = SourceId("ntk")

    @Test
    fun currentWebtoonAndComicGenrePayloadsAreComplete() {
        val webtoon = """<script>{\"tags\":[{\"id\":1,\"name\":\"학원\"},
            {\"id\":517,\"name\":\"절륜공\"}],\"platforms\":[]}</script>"""
        val comic = """<script>{\"genres\":[\"순정\",\"판타지\",\"17\"]}</script>"""

        assertEquals(
            listOf("1:학원", "517:절륜공"),
            parser.genres(webtoon, SeriesKind.WEBTOON).map { "${it.key}:${it.label}" },
        )
        assertEquals(
            listOf("순정", "판타지", "17"),
            parser.genres(comic, SeriesKind.COMIC).map { it.label },
        )
    }

    @Test
    fun quickReadLinksAreNotEpisodes() {
        val series = SeriesId(sourceId, "/webtoon/42")
        val html = """
            <a href="/webtoon/42/latest"><strong>▶최신화 보기</strong></a>
            <a href="/webtoon/42/first"><strong>📖첫화부터 정주행</strong></a>
            <a href="/webtoon/42/real"><strong>1190화</strong></a>
        """.trimIndent()

        assertEquals(listOf("1190화"), parser.episodes(html, series).episodes.map { it.episode.title })
    }

    @Test
    fun catalogApiUsesAdapterKindAndCurrentArtworkFields() {
        val json = """{
          "works":[{"sourceWorkId":"60914825","title":"픽 미 업!",
            "thumbnailUrl":"https://cdn.example/cover.jpg","genre":"소년"}],
          "total":1
        }""".trimIndent()

        val result = parser.searchApi(json, sourceId, NtkKind.WEBTOON)

        assertEquals(1, result.series.size)
        assertEquals("/webtoon/60914825", result.series.single().id.remoteKey)
        assertEquals("소년", result.series.single().subtitle)
        assertEquals("https://cdn.example/cover.jpg", result.series.single().thumbnailKey)
        assertTrue(result.recognized)
    }

    @Test
    fun catalogApiNeverPromotesNestedUpdateMetadataToAWork() {
        val json = """{
          "works":[{"sourceWorkId":"42","title":"정확한 작품"}],
          "updates":[{"id":"999","name":"업데이트","title":"업데이트"}],
          "total":1
        }""".trimIndent()

        val result = parser.searchApi(json, sourceId, NtkKind.WEBTOON)

        assertEquals(listOf("정확한 작품"), result.series.map { it.title })
        assertEquals(listOf("/webtoon/42"), result.series.map { it.id.remoteKey })
    }

    @Test
    fun htmlCatalogUsesInitialWorksAndRejectsNavigationCards() {
        val html = """
            <a href="/webtoon/999"><strong>업데이트</strong></a>
            <script>{"initialWorks":[{"sourceWorkId":"42","title":"실제 성인 작품",
              "thumbnailUrl":"https://cdn.example/42.jpg"}],"initialHasMore":false}</script>
        """.trimIndent()

        val result = parser.searchHtml(html, sourceId, NtkKind.WEBTOON)

        assertEquals(listOf("실제 성인 작품"), result.map { it.title })
        assertEquals(listOf("/webtoon/42"), result.map { it.id.remoteKey })
    }

    @Test
    fun currentUnicodeProviderSlugsRemainStableSeriesKeys() {
        val json = """{"works":[{"sourceWorkId":"복학생-네이버","title":"복학생"}],"total":1}"""

        val result = parser.searchApi(json, sourceId, NtkKind.WEBTOON)

        assertEquals("/webtoon/복학생-네이버", result.series.single().id.remoteKey)
        assertEquals("복학생-네이버", NtkSeriesKey.decode(result.series.single().id).workKey)
    }

    @Test
    fun mergesNumericAndSlugRowsWithEmbeddedImageMetadata() {
        val series = SeriesId(sourceId, "/webtoon/61393986")
        val html = """
            <a class="ep-row-v2-link" href="/webtoon/61393986/kp-61393986-64942327">
              <span class="ep-row-v2-no">68</span><strong>68화</strong>
            </a>
            <script>{"episodes":[{"id":"1377023","sourceEpisodeId":"kp-61393986-64942327",
              "epNo":68,"imageCount":67}]}</script>
        """.trimIndent()

        val result = parser.episodes(html, series)

        assertEquals(1, result.episodes.size)
        assertEquals("/webtoon/61393986/kp-61393986-64942327", result.episodes.single().episode.id.remoteKey)
        assertEquals(67, result.episodes.single().imageCount)
        assertEquals("1377023", result.episodes.single().imageEpisodeId)
    }

    @Test
    fun protectedDescriptorAndDirectImagesNeverPromotePageChrome() {
        val html = """
            <script type="application/json">{
              "sourceWorkId":"18190","episodeId":"1518441","token":"token-value",
              "imageApiPath":"/api/webtoon-images","images":[{"page":1},{"page":2},{"page":3}]
            }</script>
            <img src="https://cdn.example/board_uploads/banner.png">
            <img src="https://i.toonflix.app/webtoon_uploads/real-page-001.jpg">
        """.trimIndent()

        val result = parser.manifest(NtkEpisodeDocument("https://ntk.test", "/webtoon/18190/1518441", html))

        assertEquals(1, result.directPages.size)
        assertNotNull(result.descriptor)
        assertEquals(3, result.descriptor?.expectedPageCount)
        assertTrue(result.directPages.single().url.contains("real-page-001.jpg"))
    }

    @Test
    fun verifiedViewerObjectOwnsTitleAndSlugNeighbors() {
        val html = """
            <script>{"sourceWorkId":"work-slug","episodeId":"current","imagesToken":"token",
              "imageApiPath":"/api/webtoon-images","imageMetas":[{"page":1}],
              "epTitle":"특별편","prevEpId":"older","nextEpId":"newer"}</script>
        """.trimIndent()

        val viewer = parser.manifest(
            NtkEpisodeDocument("https://ntk.test", "/webtoon/work-slug/current", html),
        ).viewer

        assertEquals("특별편", viewer?.title)
        assertEquals("/webtoon/work-slug/older", viewer?.previousEpisodePath)
        assertEquals("/webtoon/work-slug/newer", viewer?.nextEpisodePath)
        assertTrue(viewer?.previousKnown == true)
        assertTrue(viewer?.nextKnown == true)
    }

    @Test
    fun explicitNullBoundaryIsKnownWithoutInventingAnEpisode() {
        val html = """
            <script>{"sourceWorkId":"42","episodeId":"100","imagesToken":"token",
              "imageApiPath":"/api/manhwa-images","imageCount":1,
              "prevEpId":"99","nextEpId":null}</script>
        """.trimIndent()

        val viewer = parser.manifest(
            NtkEpisodeDocument("https://ntk.test", "/manhwa/42/100", html),
        ).viewer

        assertEquals("/manhwa/42/99", viewer?.previousEpisodePath)
        assertNull(viewer?.nextEpisodePath)
        assertTrue(viewer?.nextKnown == true)
    }

    @Test
    fun textualNullBoundariesAreKnownWithoutInventingEpisodes() {
        val html = """
            <script>{"sourceWorkId":"42","episodeId":"100","imagesToken":"token",
              "imageApiPath":"/api/manhwa-images","imageCount":1,
              "prevEpId":"undefined","nextEpId":"null"}</script>
        """.trimIndent()

        val viewer = parser.manifest(
            NtkEpisodeDocument("https://ntk.test", "/manhwa/42/100", html),
        ).viewer

        assertNull(viewer?.previousEpisodePath)
        assertNull(viewer?.nextEpisodePath)
        assertTrue(viewer?.previousKnown == true)
        assertTrue(viewer?.nextKnown == true)
    }

    @Test
    fun mismatchedViewerIdentityIsRejected() {
        val html = """
            <script>{"sourceWorkId":"other-work","episodeId":"current","imagesToken":"token",
              "imageApiPath":"/api/webtoon-images","imageCount":1}</script>
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parser.manifest(
                NtkEpisodeDocument("https://ntk.test", "/webtoon/work-slug/current", html),
            )
        }
    }

    @Test
    fun escapedPaginationAndEpisodeRowsAreParsedWithoutProviderSpecialCases() {
        val series = SeriesId(sourceId, "/manhwa/3540")
        val payload = """
            self.__next_f.push([1,"\u003ca href=\"/manhwa/3540/135918\" class=\"ep-row-v2-link\"\u003e
            \u003cstrong\u003e258화\u003c/strong\u003e\u003c/a\u003e?epage=6"]);
        """.trimIndent()

        assertEquals(6, parser.episodePageCount(payload))
        assertEquals("/manhwa/3540/135918", parser.episodes(payload, series).episodes.single().episode.id.remoteKey)
    }
}
