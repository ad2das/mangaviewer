package ml.melun.mangaview.data.engine

import java.io.IOException
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.AccessPrerequisite
import ml.melun.mangaview.engine.api.EnginePositionPort
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.content.EnginePageWork
import ml.melun.mangaview.engine.content.EngineEpisodeWork
import ml.melun.mangaview.engine.content.PageHttpException
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.wfwf.WfwfAccessPlanner
import ml.melun.mangaview.source.wfwf.WfwfKind
import ml.melun.mangaview.source.wfwf.WfwfSeriesKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnginePageWorkIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val planner = WfwfAccessPlanner("test-agent")
    private val episode = EpisodeId(SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 7).encode()), "12")
    private val bytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=",
    )

    @Test fun parsedOriginalIsSharedPublishedPinnedAndThenReusableWithoutHttp() = runTest {
        val store = store()
        val coordinator = WorkCoordinator(this)
        val plan = plan()
        var calls = 0
        val body = Body()
        val factory = factory(store, SourceTransport {
            calls++
            assertEquals("https://images.test/original.png", it.url)
            assertEquals("https://wfwf.test/cv?toon=7&num=12", it.headers["Referer"])
            response(body)
        })
        val request = factory.request(plan, plan.pages.single().pageId, WorkPriority.VISIBLE)
        val first = coordinator.submit(request)
        val second = coordinator.submit(request)
        val page = first.await()
        assertEquals(page, second.await())
        assertArrayEquals(bytes, page.file.readBytes())
        assertEquals(1, calls)
        assertEquals(1, body.closes)
        assertEquals(1, store.ownership().fileLeases)
        first.awaitReleased()
        assertEquals(bytes.size.toLong(), store.trimTo(0))
        second.awaitReleased()
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, store.ownership().preparedPages)
        val cached = coordinator.acquire(request)
        assertEquals(1, calls)
        assertArrayEquals(bytes, cached.value.file.readBytes())
        cached.awaitReleased()
        assertEquals(0L, store.trimTo(0))
        coordinator.close()
    }

    @Test fun documentRequestThroughPublishedOriginalUsesTheSameCoordinator() = runTest {
        val coordinator = WorkCoordinator(this)
        val store = store()
        val paths = mutableListOf<String>()
        val document = """<div class="viewer-wrap"><img data-original="/original.png"></div>""".toByteArray()
        var documentClosed = false
        val transport = SourceTransport { request ->
            paths += URI(request.url).path
            if (paths.size == 1) {
                var sent = false
                val body = object : PageByteStream {
                    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
                        if (sent) return -1
                        document.copyInto(destination, offset)
                        sent = true
                        return document.size
                    }
                    override fun close() { documentClosed = true }
                }
                SourceResponse(200, request.url, emptyMap(), body, document.size.toLong(), "text/html")
            } else response(Body())
        }
        val episodeFactory = EngineEpisodeWork("test", planner, transport, StandardTestDispatcher(testScheduler))
        val documentLease = coordinator.acquire(episodeFactory.request(episode, URI("https://wfwf.test"),
            0, WorkPriority.FOCUS))
        val plan = documentLease.value
        val pageLease = coordinator.acquire(factory(store, transport).request(plan,
            plan.pages.single().pageId, WorkPriority.FOCUS))
        assertEquals(listOf("/cv", "/original.png"), paths)
        assertTrue(documentClosed)
        assertArrayEquals(bytes, pageLease.value.file.readBytes())
        documentLease.awaitReleased()
        assertEquals(bytes.size.toLong(), store.trimTo(0))
        pageLease.awaitReleased()
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, store.ownership().preparedPages)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun missingOriginalUsesOnlyTheSameSourceRecordsDeclaredMirror() = runTest {
        val coordinator = WorkCoordinator(this)
        val store = store()
        val plan = plan(mirror = true)
        val requests = mutableListOf<String>()
        val missing = Body()
        val original = Body()
        val factory = factory(store, SourceTransport {
            requests += it.url
            if (requests.size == 1) response(missing, 404) else response(original)
        })
        val lease = coordinator.acquire(factory.request(plan, plan.pages.single().pageId, WorkPriority.FOCUS))
        assertEquals(listOf("https://images.test/original.png", "https://mirror.test/original.png"), requests)
        assertEquals(1, missing.closes)
        assertEquals(1, original.closes)
        assertArrayEquals(bytes, lease.value.file.readBytes())
        lease.awaitReleased()
        coordinator.close()
    }

    @Test fun authorizationFailureDoesNotTryAnImageMirror() = runTest {
        val coordinator = WorkCoordinator(this)
        val store = store()
        val plan = plan(mirror = true)
        var calls = 0
        val body = Body()
        val factory = factory(store, SourceTransport { calls++; response(body, 403) })
        expect<PageHttpException> {
            coordinator.acquire(factory.request(plan, plan.pages.single().pageId, WorkPriority.FOCUS))
        }
        assertEquals(1, calls)
        assertEquals(1, body.closes)
        assertEquals(0, store.ownership().preparedPages)
        coordinator.close()
    }

    @Test fun partialResponseCannotBePublishedAsTheCompleteOriginal() = runTest {
        val coordinator = WorkCoordinator(this)
        val store = store()
        val plan = plan()
        val body = Body()
        val factory = factory(store, SourceTransport { response(body, 206) })
        expect<PageHttpException> {
            coordinator.acquire(factory.request(plan, plan.pages.single().pageId, WorkPriority.FOCUS))
        }
        assertEquals(1, body.closes)
        assertNull(store.find(plan.pages.single().pageId, plan.contentRevision))
        assertEquals(0, store.ownership().preparedPages)
        coordinator.close()
    }

    @Test fun cancellationDuringBodyReadClosesStreamAndRemovesStaging() = runTest {
        val coordinator = WorkCoordinator(this)
        val store = store()
        val plan = plan()
        val reading = CompletableDeferred<Unit>()
        val body = Body { reading.complete(Unit); awaitCancellation() }
        val factory = factory(store, SourceTransport { response(body) })
        val subscription = coordinator.submit(factory.request(plan, plan.pages.single().pageId, WorkPriority.NEXT_IMAGE))
        reading.await()
        subscription.awaitReleased()
        assertEquals(1, body.closes)
        assertEquals(0, store.ownership().preparedPages)
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun anOpenBodyReceivesParentPriorityPromotion() = runTest {
        val coordinator = WorkCoordinator(this)
        val store = store()
        val plan = plan()
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val body = Body { reading.complete(Unit); release.await() }
        val factory = factory(store, SourceTransport { response(body) })
        val subscription = coordinator.submit(factory.request(plan, plan.pages.single().pageId, WorkPriority.OFFLINE))
        reading.await()
        subscription.promote(WorkPriority.FOCUS)
        runCurrent()
        assertEquals(PageFetchPriority.FOCUS, body.priority)
        release.complete(Unit)
        subscription.await()
        subscription.awaitReleased()
        coordinator.close()
    }

    @Test fun failedPublicationCleansStagingWithoutRetryingImageTransfer() = runTest {
        val coordinator = WorkCoordinator(this)
        val store = store { if (it == EnginePublicationStep.FILE_SYNCED) throw IOException("fsync checkpoint") }
        val plan = plan(mirror = true)
        var calls = 0
        val factory = factory(store, SourceTransport { calls++; response(Body()) })
        expect<IOException> {
            coordinator.acquire(factory.request(plan, plan.pages.single().pageId, WorkPriority.FOCUS))
        }
        assertEquals(1, calls)
        assertEquals(0, store.ownership().preparedPages)
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, coordinator.snapshot().retainedResults)
        coordinator.close()
    }

    @Test fun accessPrerequisiteFinishesBeforeTransferAndCacheDoesNotReactivateIt() = runTest {
        val coordinator = WorkCoordinator(this)
        val store = store()
        val base = plan()
        val plan = EpisodeAccessPlan(base.manifest, base.contentRevision, base.documentSha256,
            base.finalDocumentUrl, base.authEpoch, base.pages, listOf(AccessPrerequisite.BrowserAuthorization(episode)))
        val events = mutableListOf<String>()
        val factory = EnginePageWork("test", planner, SourceTransport { events += "body"; response(Body()) }, store) {
                access, _, priority ->
            WorkRequest(WorkKey("test", "authorization", "access", "1", Unit::class.java),
                WorkDomain.NETWORK, priority, authEpoch = access.authEpoch, execute = { events += "access" })
        }
        val request = factory.request(plan, plan.pages.single().pageId, WorkPriority.FOCUS)
        coordinator.acquire(request).awaitReleased()
        assertEquals(listOf("access", "body"), events)
        coordinator.acquire(request).awaitReleased()
        assertEquals(listOf("access", "body"), events)
        coordinator.close()
    }

    private fun plan(mirror: Boolean = false): EpisodeAccessPlan {
        val source = if (mirror) "https://mirror.test/original.png" else "https://images.test/thumb.png"
        return planner.parseEpisode(episode, SourceDocument(URI("https://wfwf.test/cv?toon=7&num=12"),
            """<div class="viewer-wrap"><img data-original="https://images.test/original.png" src="$source"></div>"""
                .toByteArray()), 0)
    }

    private fun TestScope.store(checkpoint: suspend (EnginePublicationStep) -> Unit = {}) = EngineRawStorage(
        temporary.newFolder(), MemoryIndex(), StandardTestDispatcher(testScheduler),
        object : EnginePositionPort {
            override suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) = Unit
            override suspend fun load(episodeId: EpisodeId): SourceAnchor? = null
        }, LocalFileOps(), checkpoint = checkpoint,
    )

    private fun factory(store: EngineRawStorage, transport: SourceTransport) =
        EnginePageWork("test", planner, transport, store) { _, _, _ -> error("Unexpected prerequisite") }

    private fun response(body: Body, status: Int = 200) = SourceResponse(status,
        "https://images.test/original.png", emptyMap(), body, bytes.size.toLong(), "image/png")

    private inner class Body(private val beforeRead: suspend () -> Unit = {}) : PageByteStream {
        var closes = 0
        var priority: PageFetchPriority? = null
        private var read = false
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            if (read) return -1
            beforeRead()
            bytes.copyInto(destination, offset)
            read = true
            return bytes.size
        }
        override fun promote(priority: PageFetchPriority) { this.priority = priority }
        override fun close() { closes++ }
    }

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.name}") }
        catch (failure: Throwable) { if (failure !is T) throw failure }
    }
}
