package ml.melun.mangaview.data.engine

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ml.melun.mangaview.core.*
import ml.melun.mangaview.data.cache.AtomicFilePublisher
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.content.EngineCachedSessionWork
import ml.melun.mangaview.engine.content.EnginePageWork
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.*
import ml.melun.mangaview.source.wfwf.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EngineCompleteEpisodeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val episode = EpisodeId(SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 7).encode()), "12")
    private val planner = WfwfAccessPlanner("test-agent")
    private val bytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=")
    private val plan = planner.parseEpisode(episode, SourceDocument(URI("https://wfwf.test/cv?toon=7&num=12"),
        """<div class="viewer-wrap"><img data-original="/original.png"><img data-original="/original.png"></div>"""
            .toByteArray()), 0)

    @Test fun metadataAndPartialBodiesNeverCountAsACompleteEpisode() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        assertNull(fixture.cache.open(episode))
        fixture.publish(plan.pages.first().pageId)
        assertNull(fixture.cache.open(episode))
        assertEquals(0, fixture.storage.ownership().fileLeases)
    }

    @Test fun completeEpisodeSkipsLivePlanAndPinsExactOrderedOriginalsUntilCoordinatorRelease() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        plan.pages.forEach { fixture.publish(it.pageId) }
        val coordinator = WorkCoordinator(this)
        val live = fixture.live()
        val work = EngineCachedSessionWork(live, fixture.cache)
        val lease = coordinator.acquire(work.episode(episode, WorkPriority.FOCUS))
        val cached = lease.value
        assertTrue(cached.localOnly)
        assertEquals(plan.manifest, cached.manifest)
        assertEquals(plan.contentRevision, cached.contentRevision)
        assertEquals(plan.documentSha256, cached.documentSha256)
        assertEquals(plan.finalDocumentUrl, cached.finalDocumentUrl)
        assertEquals(plan.pages.map { it.sourceRecord }, cached.pages.map { it.sourceRecord })
        assertEquals(plan.pages.map { it.candidates }, cached.pages.map { it.candidates })
        assertEquals(0, live.episodeCalls)
        assertEquals(2, fixture.storage.ownership().fileLeases)
        assertEquals(2L * bytes.size, fixture.storage.trimTo(0))
        val page = coordinator.acquire(work.page(cached, cached.pages[1].pageId, WorkPriority.FOCUS))
        assertArrayEquals(bytes, page.value.file.readBytes())
        assertEquals(0, fixture.httpCalls)
        lease.awaitReleased()
        assertEquals(1, fixture.storage.ownership().fileLeases)
        assertEquals(bytes.size.toLong(), fixture.storage.trimTo(0))
        page.awaitReleased()
        assertEquals(0L, fixture.storage.trimTo(0))
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun missingPageSelectsFreshWholeManifestBeforeAnyCachedPageIsReturned() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        fixture.publish(plan.pages.first().pageId)
        val newer = EpisodeAccessPlan(plan.manifest, "updated-content", plan.documentSha256,
            plan.finalDocumentUrl, 0, plan.pages)
        val live = fixture.live(newer)
        val coordinator = WorkCoordinator(this)
        val work = EngineCachedSessionWork(live, fixture.cache)
        val lease = coordinator.acquire(work.episode(episode, WorkPriority.FOCUS))
        assertSame(newer, lease.value)
        assertEquals(1, live.episodeCalls)
        assertEquals(0, fixture.storage.ownership().fileLeases)
        lease.awaitReleased()
        coordinator.close()
    }

    @Test fun actualSessionKeepsCompleteSnapshotPinnedAfterAcceptanceAndAcrossBackground() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        plan.pages.forEach { fixture.publish(it.pageId) }
        val coordinator = WorkCoordinator(this)
        val work = EngineCachedSessionWork(fixture.live(), fixture.cache)
        val session = ml.melun.mangaview.engine.session.EngineSession(1, episode, EngineViewport(1, 1), System::nanoTime)
        val runtime = ml.melun.mangaview.engine.runtime.EngineSessionRuntime(this, coordinator, session, work,
            episode, { _, _ -> }, { _, failure -> throw failure })
        runtime.open()
        runCurrent()
        assertTrue(checkNotNull(runtime.snapshot.plans[episode]).localOnly)
        runtime.foreground(false)
        runCurrent()
        assertTrue(runtime.snapshot.pages.isEmpty())
        assertEquals(2, fixture.storage.ownership().fileLeases)
        assertEquals(2L * bytes.size, fixture.storage.trimTo(0))
        runtime.foreground(true)
        runCurrent()
        assertEquals(plan.pages.map { it.pageId }.toSet(), runtime.snapshot.pages.keys)
        assertEquals(0, fixture.httpCalls)
        runtime.close()
        assertEquals(0, fixture.storage.ownership().fileLeases)
        assertEquals(0, coordinator.snapshot().subscribers)
        assertEquals(0L, fixture.storage.trimTo(0))
        coordinator.close()
    }

    @Test fun sameLengthBodyCorruptionRejectsSnapshotAndReleasesEarlierPins() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        plan.pages.forEach { fixture.publish(it.pageId) }
        val second = checkNotNull(fixture.storage.find(plan.pages[1].pageId, plan.contentRevision))
        val file = second.page.file
        second.close()
        file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertNull(fixture.cache.open(episode))
        assertEquals(0, fixture.storage.ownership().fileLeases)
    }

    @Test fun truncatedAlteredAndTrailingMetadataAreRejectedBeforeAnyPin() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        plan.pages.forEach { fixture.publish(it.pageId) }
        val file = fixture.root.listFiles()!!.single()
        val valid = file.readBytes()
        for (broken in listOf(valid.copyOf(20), valid + byteArrayOf(0),
            valid.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })) {
            file.writeBytes(broken)
            assertNull(fixture.cache.open(episode))
            assertEquals(0, fixture.storage.ownership().fileLeases)
        }
        file.writeBytes(valid)
        checkNotNull(fixture.cache.open(episode)).close()
    }

    @Test fun wrongEpisodeAndConflictingManifestDimensionsAreNotAccepted() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        plan.pages.forEach { fixture.publish(it.pageId) }
        val file = fixture.root.listFiles()!!.single()
        val other = EpisodeId(episode.seriesId, "13")
        val otherPlan = planner.parseEpisode(other, SourceDocument(URI("https://wfwf.test/cv?toon=7&num=13"),
            """<div class="viewer-wrap"><img data-original="/original.png"></div>""".toByteArray()), 0)
        EngineEpisodePlanCodec.write(file, otherPlan)
        assertNull(fixture.cache.open(episode))
        val changed = plan.manifest.copy(pages = plan.manifest.pages.map { it.copy(dimensions = PageDimensions(7, 9)) })
        EngineEpisodePlanCodec.write(file, EpisodeAccessPlan(changed, plan.contentRevision, plan.documentSha256,
            plan.finalDocumentUrl, 0, plan.pages))
        assertNull(fixture.cache.open(episode))
        assertEquals(0, fixture.storage.ownership().fileLeases)
    }

    @Test fun cancellationWhileAcquiringRemainingPagesReleasesEarlierOriginals() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        plan.pages.forEach { fixture.publish(it.pageId) }
        val blocked = CompletableDeferred<Unit>()
        val wrapped = object : EngineStoragePort by fixture.storage {
            override suspend fun find(pageId: PageId, contentRevision: String): StoredPageLease? {
                if (pageId == plan.pages[1].pageId) { blocked.complete(Unit); awaitCancellation() }
                return fixture.storage.find(pageId, contentRevision)
            }
        }
        val cache = EngineCompleteEpisodeStore(fixture.root, wrapped, StandardTestDispatcher(testScheduler))
        val opening = async { cache.open(episode) }
        blocked.await()
        assertEquals(1, fixture.storage.ownership().fileLeases)
        opening.cancelAndJoin()
        assertEquals(0, fixture.storage.ownership().fileLeases)
    }

    @Test fun expiredLocalPlanNeverReactivatesSourceHttpOrAuthorization() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        plan.pages.forEach { fixture.publish(it.pageId) }
        val snapshot = checkNotNull(fixture.cache.open(episode))
        val local = snapshot.plan
        snapshot.close()
        fixture.storage.trimTo(0)
        val coordinator = WorkCoordinator(this)
        try {
            coordinator.acquire(fixture.pages.request(local, local.pages.first().pageId, WorkPriority.FOCUS))
            fail("Missing original must fail without HTTP")
        } catch (_: IOException) { }
        assertEquals(0, fixture.httpCalls)
        assertEquals(0, fixture.storage.ownership().fileLeases)
        coordinator.close()
    }

    @Test fun metadataPublicationFailurePreservesPreviousPlanAndDoesNotFailLiveReading() = runTest {
        val fixture = fixture()
        fixture.cache.remember(plan)
        val failures = mutableListOf<Exception>()
        val cache = EngineCompleteEpisodeStore(fixture.root, fixture.storage, StandardTestDispatcher(testScheduler),
            AtomicFilePublisher { _, _ -> throw IOException("disk") }, failures::add)
        val live = fixture.live()
        val coordinator = WorkCoordinator(this)
        val lease = coordinator.acquire(EngineCachedSessionWork(live, cache).episode(episode, WorkPriority.FOCUS))
        assertSame(plan, lease.value)
        assertEquals(1, live.episodeCalls)
        assertEquals(1, failures.size)
        assertEquals(listOf("plan"), fixture.root.listFiles()!!.map { it.extension })
        lease.awaitReleased()
        coordinator.close()
    }

    private fun TestScope.fixture() = Fixture(StandardTestDispatcher(testScheduler))

    private inner class Fixture(dispatcher: CoroutineDispatcher) {
        val root = temporary.newFolder()
        val storage = EngineRawStorage(temporary.newFolder(), MemoryIndex(), dispatcher, object : EnginePositionPort {
            override suspend fun load(episodeId: EpisodeId): SourceAnchor? = null
            override suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) = Unit
        }, LocalFileOps())
        val cache = EngineCompleteEpisodeStore(root, storage, dispatcher, AtomicFilePublisher { stage, destination ->
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        })
        var httpCalls = 0
        val pages = EnginePageWork("test", planner, SourceTransport { httpCalls++; error("Unexpected network") },
            storage) { _, _, _ -> error("Unexpected authorization") }

        suspend fun publish(id: PageId) {
            var offset = 0
            val stream = object : PageByteStream {
                override suspend fun readAtMost(destination: ByteArray, destinationOffset: Int, byteCount: Int): Int {
                    if (offset == bytes.size) return -1
                    val count = minOf(byteCount, bytes.size - offset)
                    bytes.copyInto(destination, destinationOffset, offset, offset + count)
                    offset += count
                    return count
                }
                override fun close() = Unit
            }
            storage.publish(storage.prepare(id, plan.contentRevision,
                OpenedPage(stream, bytes.size.toLong(), "image/png", null, null))).close()
        }

        fun live(result: EpisodeAccessPlan = plan) = Live(result, pages)
    }

    private inner class Live(private val result: EpisodeAccessPlan, private val pages: EnginePageWork) : EngineSessionWork {
        var episodeCalls = 0
        override fun episode(episodeId: EpisodeId, priority: WorkPriority) = WorkRequest(
            WorkKey("test", episodeId.toString(), "live.plan", "0", EpisodeAccessPlan::class.java),
            WorkDomain.CONTROL, priority, execute = { episodeCalls++; result })
        override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) = pages.request(plan, pageId, priority)
        override fun position(episodeId: EpisodeId) = WorkRequest(
            WorkKey("test", episodeId.toString(), "position", "0", SessionPosition::class.java),
            WorkDomain.STORAGE, WorkPriority.FOCUS, execute = { SessionPosition(null) })
        override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = WorkRequest(
            WorkKey("test", episodeId.toString(), "navigation", "0", AdjacentEpisodes::class.java),
            WorkDomain.CONTROL, priority, execute = { AdjacentEpisodes(null, null) })
    }
}
