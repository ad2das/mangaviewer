package ml.melun.mangaview.source.wfwf

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.PageValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfAccessPlannerTest {
    private val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 7).encode())
    private val episode = EpisodeId(series, "12")
    private val planner = WfwfAccessPlanner("agent")

    @Test
    fun duplicateUrlsAcrossDomRecordsRemainSeparatePages() {
        val plan = plan("""
            <div class="viewer-wrap">
              <img data-original="/pages/one.jpg?sig=x">
              <img data-original="/pages/one.jpg?sig=x">
            </div>
        """)

        assertEquals(2, plan.pages.size)
        assertEquals(listOf("img:0", "img:1"), plan.pages.map { it.sourceRecord })
        assertEquals(plan.pages[0].candidates.single(), plan.pages[1].candidates.single())
    }

    @Test
    fun lazyAlternatesAndCrossOriginMirrorStayOnOneRecord() {
        val plan = plan("""
            <div class="viewer-wrap">
              <img data-original="https://images-a.example/pages/one.jpg?sig=x"
                   data-src="/pages/one-alt.jpg"
                   src="https://images-b.example/pages/one.jpg?sig=x">
            </div>
        """)

        assertEquals(1, plan.pages.size)
        assertEquals(
            listOf(
                "https://images-a.example/pages/one.jpg?sig=x",
                "https://wfwf.test/pages/one-alt.jpg",
                "https://images-b.example/pages/one.jpg?sig=x",
            ),
            plan.pages.single().candidates.map(URI::toString),
        )
    }

    @Test
    fun distinctThumbnailSrcIsNotAnOriginalFallback() {
        val plan = plan("""
            <div class="viewer-wrap">
              <img data-original="https://images.example/pages/one.jpg?sig=x"
                   src="https://images.example/thumbs/one.jpg">
            </div>
        """)

        assertEquals(
            listOf("https://images.example/pages/one.jpg?sig=x"),
            plan.pages.single().candidates.map(URI::toString),
        )
    }

    @Test
    fun manifestFollowsSelectedDomOrder() {
        val plan = plan("""
            <div class="viewer-wrap">
              <img data-original="/pages/two.jpg">
              <img data-original="/pages/one.jpg">
            </div>
        """)

        assertEquals(
            listOf(
                "https://wfwf.test/pages/two.jpg",
                "https://wfwf.test/pages/one.jpg",
            ),
            plan.pages.map { it.candidates.single().toString() },
        )
        assertEquals(listOf("p0000", "p0001"), plan.manifest.pages.map { it.id.remoteKey })
    }

    @Test
    fun relativeAndProtocolRelativeImagesUseRedirectedDocumentBase() {
        val plan = plan(
            """
                <div class="viewer-wrap">
                  <img data-original="../pages/one.jpg?sig=x">
                  <img data-original="//images.example/pages/two.webp?sig=y">
                </div>
            """,
            finalUrl = "https://redirected.example/view/cv?toon=7&num=12",
        )

        assertEquals(
            listOf(
                "https://redirected.example/pages/one.jpg?sig=x",
                "https://images.example/pages/two.webp?sig=y",
            ),
            plan.pages.map { it.candidates.single().toString() },
        )
    }

    @Test
    fun advertisementsPlaceholdersAndDataUrisAreExcluded() {
        val plan = plan("""
            <img src="https://ads.example/banner.jpg" alt="광고">
            <div class="viewer-wrap">
              <img src="/assets/blank.png">
              <img src="data:image/jpeg;base64,AAAA">
              <img data-original="/pages/one.jpg">
            </div>
        """)

        assertEquals(listOf("https://wfwf.test/pages/one.jpg"),
            plan.pages.map { it.candidates.single().toString() })
    }

    @Test
    fun missingNavigationIsReportedAsUnknown() {
        val plan = plan("""
            <div class="viewer-wrap"><img data-original="/pages/one.jpg"></div>
        """)

        assertTrue(!plan.navigationKnown)
        assertEquals(null, plan.manifest.previousEpisodeId)
        assertEquals(null, plan.manifest.nextEpisodeId)
    }

    @Test
    fun nativeViewerNavigationIsKnown() {
        val plan = plan("""
            <div class="vbar-title"><div class="vt-name">작품 12화</div></div>
            <div class="viewer-wrap"><img data-original="/pages/one.jpg"></div>
            <div class="vnav-row">
              <a class="vnav-btn" href="/cv?toon=7&amp;num=11">previous</a>
              <a class="vnav-btn" href="/cv?toon=7&amp;num=13">next</a>
            </div>
        """)

        assertTrue(plan.navigationKnown)
        assertEquals("11", plan.manifest.previousEpisodeId?.remoteKey)
        assertEquals("13", plan.manifest.nextEpisodeId?.remoteKey)
    }

    @Test
    fun catalogFallbackValidatesNeighborsAndMarksNavigationKnown() {
        val previous = EpisodeId(series, "11")
        val next = EpisodeId(series, "13")
        val plan = plan(
            "<div class=\"viewer-wrap\"><img data-original=\"/pages/one.jpg\"></div>",
            catalogAdjacency = AdjacentEpisodes(previous, next),
        )

        assertTrue(plan.navigationKnown)
        assertEquals(previous, plan.manifest.previousEpisodeId)
        assertEquals(next, plan.manifest.nextEpisodeId)
        assertRejected {
            plan(
                "<div class=\"viewer-wrap\"><img data-original=\"/pages/one.jpg\"></div>",
                catalogAdjacency = AdjacentEpisodes(EpisodeId(series, "12"), null),
            )
        }
    }

    @Test
    fun unrelatedDocumentBytesDoNotChangeAccessRevision() {
        val first = plan("""
            <p>unrelated one</p>
            <div class="viewer-wrap"><img data-original="/pages/one.jpg?sig=x"></div>
        """)
        val second = plan("""
            <p>unrelated two</p>
            <div class="viewer-wrap"><img data-original="/pages/one.jpg?sig=x"></div>
        """)

        assertEquals(first.contentRevision, second.contentRevision)
        assertEquals(first.pages.map { it.candidates }, second.pages.map { it.candidates })
        assertNotEquals(first.documentSha256, second.documentSha256)
    }

    @Test
    fun blockedImageBeforeContentDoesNotChangeContentRevision() {
        val withoutAd = plan("""
            <div class="viewer-wrap">
              <img data-original="/pages/one.jpg?sig=x">
              <img data-original="/pages/two.jpg?sig=y">
            </div>
        """)
        val withAd = plan("""
            <img src="/assets/banner.jpg" alt="광고">
            <div class="viewer-wrap">
              <img data-original="/pages/one.jpg?sig=x">
              <img data-original="/pages/two.jpg?sig=y">
            </div>
        """)

        assertEquals(withoutAd.contentRevision, withAd.contentRevision)
        assertNotEquals(withoutAd.documentSha256, withAd.documentSha256)
        assertNotEquals(withoutAd.pages.map { it.sourceRecord }, withAd.pages.map { it.sourceRecord })
    }

    @Test
    fun pageRequestOwnsUrlRefererValidationAndMappedPriority() {
        val plan = plan("""
            <div class="viewer-wrap">
              <img data-original="/pages/one.jpg" data-src="/pages/one-alt.jpg">
            </div>
        """, finalUrl = "https://redirected.example/cv?toon=7&num=12")

        val request = planner.pageRequest(
            plan,
            plan.pages.single().pageId,
            candidateIndex = 1,
            priority = WorkPriority.NEXT_EPISODE,
            validation = PageValidation("etag", "modified"),
        )

        assertEquals("https://redirected.example/pages/one-alt.jpg", request.url)
        assertEquals("https://redirected.example/cv?toon=7&num=12", request.headers["Referer"])
        assertEquals("etag", request.headers["If-None-Match"])
        assertEquals("modified", request.headers["If-Modified-Since"])
        assertEquals(ml.melun.mangaview.source.PageFetchPriority.ADJACENT_FORWARD, request.priority)
        assertEquals(45_000L, request.totalTimeoutMillis)
    }

    @Test
    fun foreignPageAndOutOfBoundsCandidateAreRejected() {
        val plan = plan("<div class=\"viewer-wrap\"><img data-original=\"/pages/one.jpg\"></div>")
        val foreignSeries = SeriesId(SourceId("other"), "comic:7")
        assertRejected {
            planner.pageRequest(
                plan,
                PageId(EpisodeId(foreignSeries, "12"), "p0000"),
                0,
                WorkPriority.FOCUS,
            )
        }
        assertRejected {
            planner.pageRequest(plan, plan.pages.single().pageId, 1, WorkPriority.FOCUS)
        }
        assertRejected {
            planner.pageRequest(plan, plan.pages.single().pageId, -1, WorkPriority.FOCUS)
        }
    }

    private fun plan(
        html: String,
        finalUrl: String = "https://wfwf.test/cv?toon=7&num=12",
        catalogAdjacency: AdjacentEpisodes? = null,
    ): EpisodeAccessPlan = planner.parseEpisode(
        episode,
        SourceDocument(URI(finalUrl), html.trimIndent().toByteArray()),
        authEpoch = 9L,
        catalogAdjacency = catalogAdjacency,
    )

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
