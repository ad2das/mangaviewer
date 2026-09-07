package ml.melun.mangaview.content

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.session.DemandEngine
import ml.melun.mangaview.viewer.session.DemandClass
import ml.melun.mangaview.viewer.session.DemandSnapshot
import ml.melun.mangaview.viewer.session.PageDemand
import ml.melun.mangaview.viewer.session.SemanticViewportAnchor
import ml.melun.mangaview.viewer.session.SourceRangeFraction
import ml.melun.mangaview.viewer.session.ViewerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerContentPipelineTest {
    @Test
    fun generationChangesCannotOverlapTheOldDecoder() = runTest {
        val fixture = PipelineFixture()
        val gate = CompletableDeferred<Unit>()
        var decodes = 0
        val decoder = ImageDecodePort { request ->
            decodes++
            withContext(NonCancellable) { gate.await() }
            FakeCpuTile(request.page.id, 0, 1600, 1600)
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, FakeRawPort(fixture), decoder, FakeUploader())
        try {
            pipeline.setRendererEpoch(1L)
            for (generation in 1L..6L) {
                pipeline.registerManifest(generation, fixture.manifest)
                pipeline.updateDemand(fixture.demand().copy(generation = generation), 1080, 2138)
                runCurrent()
                assertEquals("Generation replacement overlapped native decode", 1, decodes)
                assertEquals(1, pipeline.snapshot().activeDecodes)
            }
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, decodes)
        } finally { gate.complete(Unit); pipeline.closeAndJoin() }
    }

    @Test
    fun generationChangesCannotOverlapTheOldFetch() = runTest {
        val fixture = PipelineFixture()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val raw = object : RawPagePort {
            override suspend fun find(pageId: PageId): EncodedPageRef? = null
            override suspend fun fetch(pageId: PageId, priority: PageFetchPriority,
                responseStarted: () -> Unit): EncodedPageRef {
                fetches++
                withContext(NonCancellable) { gate.await() }
                return fixture.encoded(pageId)
            }
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, raw, FakeDecoder(), FakeUploader())
        try {
            for (generation in 1L..6L) {
                pipeline.registerManifest(generation, fixture.manifest)
                pipeline.updateDemand(fixture.demand().copy(generation = generation), 1080, 2138)
                runCurrent()
                assertEquals("Generation replacement overlapped same-page fetch", 1, fetches)
                assertEquals(1, pipeline.snapshot().activeFetches)
            }
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, fetches)
        } finally { gate.complete(Unit); pipeline.closeAndJoin() }
    }

    @Test
    fun closeAndJoinWaitsForEveryPhysicallyRunningWorker() = runTest {
        val fixture = PipelineFixture()
        val gate = CompletableDeferred<Unit>()
        var ended = false
        val raw = object : RawPagePort {
            override suspend fun find(pageId: PageId): EncodedPageRef? = null
            override suspend fun fetch(pageId: PageId, priority: PageFetchPriority,
                responseStarted: () -> Unit): EncodedPageRef {
                try {
                    withContext(NonCancellable) { gate.await() }
                    return fixture.encoded(pageId)
                } finally { ended = true }
            }
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, raw, FakeDecoder(), FakeUploader())
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.updateDemand(fixture.demand(), 1080, 2138)
            runCurrent()
            val closing = launch { pipeline.closeAndJoin() }
            runCurrent()
            assertFalse("closeAndJoin returned while raw worker was still alive", closing.isCompleted)
            val otherClosers = List(3) { launch { pipeline.closeAndJoin() } }
            closing.cancel()
            runCurrent()
            assertTrue(otherClosers.none { it.isCompleted })
            assertFalse(closing.isCompleted)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(ended)
            assertTrue(closing.isCompleted)
            assertTrue(otherClosers.all { it.isCompleted })
            pipeline.closeAndJoin()
        } finally {
            gate.complete(Unit)
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun homeReturnDoesNotReuseTheLaneUntilCancelledUploadEnds() = runTest {
        val fixture = PipelineFixture()
        val gate = CompletableDeferred<Unit>()
        var uploads = 0
        val uploader = object : TextureUploadPort {
            override suspend fun upload(rendererEpoch: Long, pixels: CpuTileLease): TextureRef {
                uploads += 1
                try {
                    withContext(NonCancellable) { gate.await() }
                    return TextureRef(pixels.pageId, rendererEpoch, uploads.toLong(),
                        pixels.sourceTopPx, pixels.sourceBottomPx, pixels.sourceHeightPx, pixels.byteCount)
                } finally { pixels.close() }
            }
            override fun release(texture: TextureRef) = Unit
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, FakeRawPort(fixture), FakeDecoder(), uploader)
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.setRendererEpoch(1L)
            pipeline.updateDemand(fixture.demand(), 1080, 2138)
            runCurrent()
            repeat(5) {
                pipeline.setForeground(false)
                pipeline.setForeground(true)
                runCurrent()
                assertEquals(1, uploads)
            }
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, uploads)
        } finally {
            gate.complete(Unit)
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun homeReturnDoesNotOverlapAnUnfinishedCancelledDecode() = runTest {
        val fixture = PipelineFixture()
        val gate = CompletableDeferred<Unit>()
        var decodes = 0
        val decoder = ImageDecodePort { request ->
            decodes += 1
            withContext(NonCancellable) { gate.await() }
            FakeCpuTile(request.page.id, 0, 1600, 1600)
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, FakeRawPort(fixture), decoder, FakeUploader())
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.setRendererEpoch(1L)
            pipeline.updateDemand(fixture.demand(), 1080, 2138)
            runCurrent()
            assertEquals(1, decodes)
            repeat(5) {
                pipeline.setForeground(false)
                pipeline.setForeground(true)
                runCurrent()
                assertEquals("HOME return overlapped a still-running cancelled decoder", 1, decodes)
            }
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, decodes)
        } finally {
            gate.complete(Unit)
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun retargetedFetchKeepsItsSlotUntilPhysicalCancellationCompletes() = runTest {
        val fixture = PipelineFixture(pageCount = 6)
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<PageId>()
        val raw = object : RawPagePort {
            override suspend fun find(pageId: PageId): EncodedPageRef? = null
            override suspend fun fetch(pageId: PageId, priority: PageFetchPriority,
                responseStarted: () -> Unit): EncodedPageRef {
                started += pageId
                responseStarted()
                withContext(NonCancellable) { gate.await() }
                return fixture.encoded(pageId)
            }
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, raw, FakeDecoder(), FakeUploader())
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            val initial = fixture.demand()
            pipeline.updateDemand(initial, 1080, 2138)
            runCurrent()
            assertEquals(4, started.size)
            val target = fixture.manifest.pages.last().id
            pipeline.updateDemand(initial.copy(demands = listOf(initial.demands.first().copy(pageId = target))),
                1080, 2138)
            runCurrent()
            assertEquals("A cancellation request must not release a physical fetch slot", 4, started.size)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(target in started)
            assertEquals(1, started.count { it == target })
        } finally {
            gate.complete(Unit)
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun cancelledDecodeDeliveryClosesItsCompletedCpuTile() = runTest {
        val fixture = PipelineFixture()
        val page = fixture.manifest.pages.first()
        val tile = FakeCpuTile(page.id, 0, 1600, 1600)
        val decoder = ImageDecodePort {
            currentCoroutineContext().cancel()
            tile
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, FakeRawPort(fixture), decoder, FakeUploader())
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.setRendererEpoch(1L)
            pipeline.updateDemand(fixture.demand(), 1080, 2138)
            advanceUntilIdle()
            assertEquals(1, tile.closeCount)
        } finally {
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun cancelledUploadReturnReleasesTheCompletedTexture() = runTest {
        val fixture = PipelineFixture()
        val delegate = FakeUploader()
        val uploader = object : TextureUploadPort by delegate {
            override suspend fun upload(rendererEpoch: Long, pixels: CpuTileLease): TextureRef {
                val texture = delegate.upload(rendererEpoch, pixels)
                currentCoroutineContext().cancel()
                return texture
            }
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, FakeRawPort(fixture), FakeDecoder(), uploader)
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.setRendererEpoch(1L)
            pipeline.updateDemand(fixture.demand(), 1080, 2138)
            advanceUntilIdle()
            assertEquals(1, delegate.uploadCount.get())
            assertTrue(delegate.liveTextures.isEmpty())
        } finally {
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun latestDemandSurvivesAFullControlQueue() = runTest {
        val fixture = PipelineFixture(pageCount = 2)
        val raw = FakeRawPort(fixture, CompletableDeferred())
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, raw, FakeDecoder(), FakeUploader())
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.setRendererEpoch(1L)
            runCurrent()
            // One command resumes the suspended receiver; the next 64 fill its buffer.
            repeat(65) { pipeline.setForeground(true) }
            val basis = fixture.demand()
            repeat(100) { index ->
                val page = fixture.manifest.pages[index % 2]
                val demand = basis.copy(demands = listOf(basis.demands.first().copy(pageId = page.id)))
                pipeline.offerDemand(demand, 1080, 2138)
            }
            runCurrent()
            assertEquals(1, raw.fetchCount.get())
            assertEquals(listOf(fixture.manifest.pages.last().id), raw.fetchedPages)
        } finally {
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun aRealPromotionStillRevivesAnExhaustedFetch() = runTest {
        val fixture = PipelineFixture()
        val raw = FakeRawPort(fixture, failure = IllegalStateException("network"))
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, raw, FakeDecoder(), FakeUploader())
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            val visible = fixture.demand()
            val distant = visible.copy(demands = visible.demands.map {
                it.copy(demandClass = DemandClass.CURRENT_FORWARD_FAR)
            })
            pipeline.updateDemand(distant, 1080, 2138)
            advanceUntilIdle()
            val exhaustedAttempts = raw.fetchCount.get()
            pipeline.updateDemand(visible, 1080, 2138)
            advanceUntilIdle()
            assertTrue(raw.fetchCount.get() > exhaustedAttempts)
        } finally {
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun repeatedUnchangedDemandDoesNotReviveExhaustedFetches() = runTest {
        val fixture = PipelineFixture()
        val raw = FakeRawPort(fixture, failure = IllegalStateException("network"))
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, raw, FakeDecoder(), FakeUploader())
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.setRendererEpoch(1L)
            pipeline.updateDemand(fixture.demand(), 1080, 2138)
            advanceUntilIdle()
            val attempts = raw.fetchCount.get()
            assertTrue(attempts > 0)
            repeat(5) {
                pipeline.updateDemand(fixture.demand(), 1080, 2138)
                advanceUntilIdle()
            }
            assertEquals(attempts, raw.fetchCount.get())
        } finally {
            pipeline.closeAndJoin()
        }
    }

    @Test
    fun repeatedDemandHasOneFetchOneDecodeAndOneUploadOwner() = runTest {
        val fixture = PipelineFixture()
        val fetchGate = CompletableDeferred<Unit>()
        val raw = FakeRawPort(fixture, fetchGate)
        val decoder = FakeDecoder()
        val uploader = FakeUploader()
        val pipeline = fixture.pipeline(testScheduler, this.coroutineContext, raw, decoder, uploader)
        pipeline.registerManifest(1L, fixture.manifest)
        pipeline.setRendererEpoch(1L)
        repeat(20) { pipeline.updateDemand(fixture.demand(), 1_080, 2_138) }
        runCurrent()

        assertEquals(1, raw.fetchCount.get())
        assertEquals(1, pipeline.snapshot().activeFetches)

        fetchGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, raw.fetchCount.get())
        assertEquals(1, decoder.decodeCount.get())
        assertEquals(1, uploader.uploadCount.get())
        assertEquals(1, uploader.liveTextures.size)
        pipeline.closeAndJoin()
        assertTrue(uploader.liveTextures.isEmpty())
    }

    @Test
    fun fourFailuresStillCreateOnlyOneRetryWakeup() = runTest {
        val fixture = PipelineFixture(pageCount = 4)
        val raw = FakeRawPort(fixture, failure = IllegalStateException("network"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline = ViewerContentPipeline(
            coroutineContext,
            ContentPipelineDispatchers(dispatcher, dispatcher, dispatcher, dispatcher),
            raw,
            FakeDecoder(),
            FakeUploader(),
            ContentPipelineSink {},
            PipelineClock { testScheduler.currentTime },
            networkLimit = 4,
        )
        pipeline.registerManifest(1L, fixture.manifest)
        pipeline.setRendererEpoch(1L)
        pipeline.updateDemand(fixture.demand(), 1_080, 2_138)
        runCurrent()

        assertEquals(1, pipeline.snapshot().retryWakeups)
        assertEquals(4, raw.fetchCount.get())

        advanceTimeBy(251L)
        runCurrent()
        assertEquals(1, pipeline.snapshot().retryWakeups)
        pipeline.closeAndJoin()
    }

    @Test
    fun backgroundCancelsDecodeAndNeverStartsUpload() = runTest {
        val fixture = PipelineFixture()
        val decodeGate = CompletableDeferred<Unit>()
        val decoder = FakeDecoder(decodeGate)
        val uploader = FakeUploader()
        val pipeline = fixture.pipeline(
            testScheduler,
            coroutineContext,
            FakeRawPort(fixture),
            decoder,
            uploader,
        )
        pipeline.registerManifest(1L, fixture.manifest)
        pipeline.setRendererEpoch(1L)
        pipeline.updateDemand(fixture.demand(), 1_080, 2_138)
        runCurrent()
        assertEquals(1, decoder.decodeCount.get())

        pipeline.setForeground(false)
        runCurrent()
        decodeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, uploader.uploadCount.get())
        assertEquals(0, pipeline.snapshot().activeDecodes)
        pipeline.closeAndJoin()
    }

    @Test
    fun rendererEpochChangeReleasesEveryResidentTexture() = runTest {
        val fixture = PipelineFixture()
        val uploader = FakeUploader()
        val pipeline = fixture.pipeline(
            testScheduler,
            coroutineContext,
            FakeRawPort(fixture),
            FakeDecoder(),
            uploader,
        )
        pipeline.registerManifest(1L, fixture.manifest)
        pipeline.setRendererEpoch(1L)
        pipeline.updateDemand(fixture.demand(), 1_080, 2_138)
        advanceUntilIdle()
        assertEquals(1, uploader.liveTextures.size)

        pipeline.setRendererEpoch(2L)
        runCurrent()

        assertTrue(uploader.liveTextures.none { it.rendererEpoch == 1L })
        pipeline.closeAndJoin()
    }

    @Test
    fun decodeFailureUsesSingleRetryClockAndEventuallyPublishes() = runTest {
        val fixture = PipelineFixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val decoder = FailingDecoder(failuresBeforeSuccess = 2)
        val uploader = FakeUploader()
        val events = mutableListOf<ContentPipelineEvent>()
        val pipeline = ViewerContentPipeline(
            coroutineContext,
            ContentPipelineDispatchers(dispatcher, dispatcher, dispatcher, dispatcher),
            FakeRawPort(fixture),
            decoder,
            uploader,
            ContentPipelineSink(events::add),
            PipelineClock { testScheduler.currentTime },
        )
        pipeline.registerManifest(1L, fixture.manifest)
        pipeline.setRendererEpoch(1L)
        pipeline.updateDemand(fixture.demand(), 1_080, 2_138)
        runCurrent()
        advanceTimeBy(251L)
        runCurrent()
        advanceTimeBy(1_001L)
        advanceUntilIdle()

        assertEquals(3, decoder.decodeCount.get())
        assertEquals(1, uploader.uploadCount.get())
        assertTrue(events.none { it is ContentPipelineEvent.PageFailed })
        pipeline.closeAndJoin()
    }

    @Test
    fun residentBudgetEvictsBehindBeforeVisible() {
        val fixture = PipelineFixture(pageCount = 2)
        val visible = PageRecord(fixture.manifest.pages[0]).apply {
            demand = DemandTarget(
                ml.melun.mangaview.viewer.session.DemandClass.VISIBLE,
                ml.melun.mangaview.viewer.session.SourceRangeFraction(0L, 1L shl 32),
                0,
            )
            residents = listOf(texture(page.id, key = 1L, bytes = 60L))
        }
        val behind = PageRecord(fixture.manifest.pages[1]).apply {
            demand = DemandTarget(ml.melun.mangaview.viewer.session.DemandClass.BEHIND, null, 1)
            residents = listOf(texture(page.id, key = 2L, bytes = 60L))
        }

        val evicted = residentEvictions(listOf(visible, behind), budgetBytes = 80L)

        assertEquals(listOf(2L), evicted.map { it.key })
    }

    @Test
    fun visiblePageRetainsItsRecentlySeenBandAcrossDirectionChange() {
        val fixture = PipelineFixture(pageCount = 2)
        val visible = PageRecord(fixture.manifest.pages[0]).apply {
            demand = DemandTarget(
                DemandClass.VISIBLE,
                SourceRangeFraction(SemanticViewportAnchor.Q32_ONE / 2L,
                    SemanticViewportAnchor.Q32_ONE),
                0,
            )
            residents = listOf(texture(page.id, key = 1L, bytes = 60L))
        }
        val behind = PageRecord(fixture.manifest.pages[1]).apply {
            demand = DemandTarget(
                DemandClass.CURRENT_BEHIND_NEAR,
                SourceRangeFraction(0L, SemanticViewportAnchor.Q32_ONE),
                1,
            )
            residents = listOf(texture(page.id, key = 2L, bytes = 60L))
        }

        val evicted = residentEvictions(listOf(visible, behind), budgetBytes = 80L)

        assertEquals(listOf(2L), evicted.map { it.key })
    }

    @Test
    fun longForwardDemandAutomaticallyFillsEveryBoundedBand() = runTest {
        val fixture = PipelineFixture(dimensions = PageDimensions(1_080, 10_000))
        val decoder = FakeDecoder()
        val uploader = FakeUploader()
        val pipeline = fixture.pipeline(
            testScheduler,
            coroutineContext,
            FakeRawPort(fixture),
            decoder,
            uploader,
        )
        val page = fixture.manifest.pages.single()
        val demand = DemandSnapshot(1L, 1L, listOf(PageDemand(
            page.id,
            DemandClass.CURRENT_FORWARD_NEAR,
            FixedPx.ZERO,
            0,
            SourceRangeFraction(0L, SemanticViewportAnchor.Q32_ONE),
        )))

        pipeline.registerManifest(1L, fixture.manifest)
        pipeline.setRendererEpoch(1L)
        pipeline.updateDemand(demand, 1_080, 2_138)
        advanceUntilIdle()

        assertEquals(5, decoder.decodeCount.get())
        assertEquals(5, uploader.uploadCount.get())
        assertEquals(5, uploader.liveTextures.size)
        pipeline.closeAndJoin()
    }
}

private fun texture(pageId: PageId, key: Long, bytes: Long) = TextureRef(
    pageId,
    rendererEpoch = 1L,
    key,
    sourceTopPx = 0,
    sourceBottomPx = 1_600,
    sourceHeightPx = 1_600,
    byteCount = bytes,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineFixture(
    pageCount: Int = 1,
    val dimensions: PageDimensions = PageDimensions(1_080, 1_600),
) {
    private val series = SeriesId(SourceId("ntk"), "pipeline")
    private val episode = EpisodeId(series, "episode")
    val manifest = EpisodeManifest(
        episode,
        "Episode",
        List(pageCount) { ordinal ->
            PageSpec(PageId.at(episode, ordinal), ordinal, dimensions)
        },
    )

    fun demand() = ViewerSession(
        Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(2_138)),
    ).let { session ->
        session.initialManifestResolved(manifest)
        session.savedPositionResolved(null)
        DemandEngine().snapshot(session.state)
    }

    fun encoded(pageId: PageId) = EncodedPageRef(
        pageId,
        "C:/cache/${pageId.remoteKey}",
        8_192L,
        "sha256-${pageId.remoteKey}",
        dimensions,
    )

    fun pipeline(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        context: kotlin.coroutines.CoroutineContext,
        raw: RawPagePort,
        decoder: ImageDecodePort,
        uploader: TextureUploadPort,
    ): ViewerContentPipeline {
        val dispatcher = StandardTestDispatcher(scheduler)
        return ViewerContentPipeline(
            context,
            ContentPipelineDispatchers(dispatcher, dispatcher, dispatcher, dispatcher),
            raw,
            decoder,
            uploader,
            ContentPipelineSink {},
            PipelineClock { scheduler.currentTime },
        )
    }
}

internal class FakeRawPort(
    private val fixture: PipelineFixture,
    private val gate: CompletableDeferred<Unit>? = null,
    private val failure: Throwable? = null,
) : RawPagePort {
    val fetchCount = AtomicInteger()
    val fetchedPages = mutableListOf<PageId>()

    override suspend fun find(pageId: PageId): EncodedPageRef? = null

    override suspend fun fetch(
        pageId: PageId,
        priority: PageFetchPriority,
        responseStarted: () -> Unit,
    ): EncodedPageRef {
        fetchCount.incrementAndGet()
        fetchedPages += pageId
        responseStarted()
        gate?.await()
        failure?.let { throw it }
        return fixture.encoded(pageId)
    }
}

internal class FakeCpuTile(
    override val pageId: PageId,
    override val sourceTopPx: Int,
    override val sourceBottomPx: Int,
    override val sourceHeightPx: Int,
) : CpuTileLease {
    override val byteCount: Long = 4_096L
    private val closes = AtomicInteger()
    val closeCount: Int get() = closes.get()

    override fun close() {
        check(closes.incrementAndGet() == 1) { "CPU tile closed more than once" }
    }
}

internal class FakeDecoder(
    private val gate: CompletableDeferred<Unit>? = null,
) : ImageDecodePort {
    val decodeCount = AtomicInteger()

    override suspend fun decode(request: DecodeRequest): CpuTileLease {
        decodeCount.incrementAndGet()
        gate?.await()
        val height = request.dimensions.heightPx
        val top = request.sourceRange.top
        val bottom = request.sourceRange.bottomExclusive
        return FakeCpuTile(request.page.id, top, bottom, height)
    }
}

private class FailingDecoder(
    private val failuresBeforeSuccess: Int,
) : ImageDecodePort {
    val decodeCount = AtomicInteger()

    override suspend fun decode(request: DecodeRequest): CpuTileLease {
        val attempt = decodeCount.incrementAndGet()
        if (attempt <= failuresBeforeSuccess) error("decode-$attempt")
        return FakeCpuTile(
            request.page.id,
            0,
            request.dimensions.heightPx,
            request.dimensions.heightPx,
        )
    }
}

internal class FakeUploader : TextureUploadPort {
    val uploadCount = AtomicInteger()
    val liveTextures = linkedSetOf<TextureRef>()

    override suspend fun upload(rendererEpoch: Long, pixels: CpuTileLease): TextureRef = try {
        uploadCount.incrementAndGet()
        TextureRef(
            pixels.pageId,
            rendererEpoch,
            uploadCount.get().toLong(),
            pixels.sourceTopPx,
            pixels.sourceBottomPx,
            pixels.sourceHeightPx,
            pixels.byteCount,
        ).also(liveTextures::add)
    } finally {
        pixels.close()
    }

    override fun release(texture: TextureRef) {
        check(liveTextures.remove(texture)) { "Texture released more than once" }
    }
}
