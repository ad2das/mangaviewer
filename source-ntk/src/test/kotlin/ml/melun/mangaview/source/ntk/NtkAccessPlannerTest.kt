package ml.melun.mangaview.source.ntk

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.AccessPrerequisite
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.source.AdjacentEpisodes
import org.junit.Assert.*
import org.junit.Test

class NtkAccessPlannerTest {
    private val planner = NtkAccessPlanner("agent")
    private val episode = EpisodeId(SeriesId(SourceId("ntk"), "/webtoon/work"), "/webtoon/work/current")

    @Test fun browserReplayRetainsAllDocumentResponseCookies() {
        val headers = mapOf("Set-Cookie" to listOf("session=one; Path=/", "proof=two; HttpOnly"))
        val source = SourceDocument(URI("https://ntk.test/webtoon/work/current"), viewer().toByteArray(), headers)
        val parsed = planner.parseDocument(episode, source, 1)
        assertEquals(headers, parsed.browserDocument.responseHeaders)
        assertEquals(source.finalUrl.toString(), parsed.browserDocument.finalUrl)
    }

    @Test fun onlyProofForThisExactDocumentAndEpochCanSatisfyBrowserPrerequisite() {
        val parsed = planner.parseDocument(episode, document(viewer()), 2)
        fun proof(id: EpisodeId = episode, sha: String = parsed.sourceDocument.sha256, epoch: Long = 2) =
            NtkEngineAuthorization(payload(), id, sha, parsed.sourceDocument.replaySha256, epoch, 1, 10, 20, 30)
        val plan = planner.completeAuthorized(parsed, proof())
        assertTrue(plan.prerequisites.isEmpty())
        assertEquals(2, plan.pages.size)
        assertTrue(planner.pageRequest(plan, plan.pages.first().pageId, 0, WorkPriority.FOCUS, null).preferQuic)
        assertThrows(IllegalArgumentException::class.java) {
            planner.completeAuthorized(parsed, proof(id = episode.copy(remoteKey = "/webtoon/work/other")))
        }
        assertThrows(IllegalArgumentException::class.java) { planner.completeAuthorized(parsed, proof(sha = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { planner.completeAuthorized(parsed, proof(epoch = 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            NtkEngineAuthorization(payload(), episode, parsed.sourceDocument.sha256, parsed.sourceDocument.replaySha256,
                2, 1, 30, 20, 10)
        }
        val otherCookies = planner.parseDocument(episode, SourceDocument(parsed.sourceDocument.finalUrl,
            viewer().toByteArray(), mapOf("Set-Cookie" to listOf("session=other"))), 2)
        assertThrows(IllegalArgumentException::class.java) { planner.completeAuthorized(otherCookies, proof()) }
    }

    @Test fun unprotectedDomRetainsDuplicateRecordsAndSourceOrder() {
        val plan = planner.parseEpisode(episode, document("""
            <aside class="advert"><img src="/webtoon_uploads/ad.jpg"></aside>
            <img src="/webtoon_uploads/same.jpg"><img src="/webtoon_uploads/same.jpg">
        """), 2, null)
        assertEquals(2, plan.pages.size)
        assertEquals(plan.pages[0].candidates, plan.pages[1].candidates)
        assertNotEquals(plan.pages[0].sourceRecord, plan.pages[1].sourceRecord)
        assertTrue(plan.prerequisites.isEmpty())
        assertFalse(plan.navigationKnown)
    }

    @Test fun protectedDocumentCannotAdoptUnownedDomImages() {
        val parsed = planner.parseDocument(episode, document(viewer() + "<img src='/webtoon_uploads/unowned.jpg'>"), 0)
        assertFalse(parsed.directPagesComplete)
        assertThrows(NtkImageManifestRequired::class.java) { planner.complete(parsed) }
        assertFalse(parsed.toString().contains("secret-token"))
    }

    @Test fun protectedApiOrderAlternativesAndAuthorizationArePreserved() {
        val parsed = planner.parseDocument(episode, document(viewer()), 3)
        val plan = planner.complete(parsed, payload())
        assertEquals(listOf("https://cdn.test/a.woff2", "https://cdn.test/b.woff2"),
            plan.pages.map { it.candidates.first().toString() })
        assertEquals(2, plan.pages.first().candidates.size)
        assertEquals(listOf("image-api-page:1", "image-api-page:2"), plan.pages.map { it.sourceRecord })
        assertEquals(AccessPrerequisite.BrowserAuthorization(episode), plan.prerequisites.single())
        assertTrue(plan.navigationKnown)
        val request = planner.pageRequest(plan, plan.pages.first().pageId, 1, WorkPriority.FOCUS, null)
        assertEquals("https://mirror.test/a.woff2", request.url)
        assertEquals("https://ntk.test/webtoon/work/current", request.headers["Referer"])
        assertEquals("cross-site", request.headers["Sec-Fetch-Site"])
    }

    @Test fun responseFromAnotherEpisodeIsRejected() {
        val parsed = planner.parseDocument(episode, document(viewer()), 0)
        assertThrows(IllegalArgumentException::class.java) {
            planner.complete(parsed, payload().replace("\"requestEpisodeId\":\"current\"", "\"requestEpisodeId\":\"other\""))
        }
    }

    @Test fun missingPageAndDuplicatePageNumbersAreRejected() {
        val parsed = planner.parseDocument(episode, document(viewer()), 0)
        assertThrows(IllegalArgumentException::class.java) { planner.complete(parsed, payload().replace("\"page\":2", "\"page\":3")) }
        assertThrows(IllegalArgumentException::class.java) { planner.complete(parsed, payload().replace("\"page\":2", "\"page\":1")) }
    }

    @Test fun knownDocumentNavigationCannotBeOverwrittenByConflictingCatalog() {
        val parsed = planner.parseDocument(episode, document(viewer()), 0)
        val conflicting = AdjacentEpisodes(episode.copy(remoteKey = "/webtoon/work/older"), null)
        assertThrows(IllegalArgumentException::class.java) { planner.complete(parsed, payload(), conflicting) }
    }

    @Test fun redirectedDocumentIsNotRelabeledAsTheRequestedEpisode() {
        val document = SourceDocument(URI("https://ntk.test/webtoon/work/other"), viewer().toByteArray())
        assertThrows(IllegalArgumentException::class.java) { planner.parseDocument(episode, document, 0) }
    }

    @Test fun directOwnedSequenceStillRequiresAuthorizationAndHasStableContentRevision() {
        val pages = ",\"images\":[\"/webtoon_uploads/a.jpg\",\"/webtoon_uploads/b.jpg\"]"
        val first = planner.parseEpisode(episode, document(viewer(pages)), 0, null)
        val renewed = planner.parseEpisode(episode, document(viewer(pages).replace("secret-token", "renewed-token")), 1, null)
        assertEquals(first.contentRevision, renewed.contentRevision)
        assertNotEquals(first.documentSha256, renewed.documentSha256)
        assertEquals(AccessPrerequisite.BrowserAuthorization(episode), first.prerequisites.single())
    }

    private fun document(html: String) = SourceDocument(URI("https://ntk.test/webtoon/work/current"), html.toByteArray())
    private fun viewer(extra: String = "") = """<script>{"sourceWorkId":"work","episodeId":"current",
        "imagesToken":"secret-token","imageApiPath":"/api/webtoon-images","imageCount":2,
        "prevEpId":null,"nextEpId":null$extra}</script>"""
    private fun payload() = """{"ok":true,"endpoint":"/api/webtoon-images",
        "responseUrl":"https://ntk.test/api/webtoon-images","responseContentType":"application/json",
        "requestMethod":"POST","requestContentType":"application/json","requestWorkId":"work",
        "requestEpisodeId":"current","requestToken":"secret-token","images":[
        {"page":2,"src":"https://cdn.test/b.woff2"},
        {"page":1,"src":"https://cdn.test/a.woff2","srcCandidates":["https://mirror.test/a.woff2"]}]}"""
}
