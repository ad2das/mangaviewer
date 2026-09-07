package ml.melun.mangaview.content

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.viewer.session.DemandSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineEpisodeWorkTest {
    private val fixture = PipelineFixture()
    private val next = fixture.manifest.id.copy(remoteKey = "next")

    @Test
    fun repeatedDemandCannotReviveAnExhaustedManifestRetryBudget() = runTest {
        var calls = 0
        val events = mutableListOf<ContentPipelineEvent>()
        val pipeline = pipeline(EpisodeManifestPort { calls++; error("unavailable") }, events)
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.updateDemand(demand(), 1080, 2138)
            advanceUntilIdle()
            assertEquals(3, calls)
            repeat(100) { pipeline.updateDemand(demand().copy(revision = it.toLong()), 1080, 2138) }
            advanceUntilIdle()
            assertEquals(3, calls)
            assertEquals(1, events.filterIsInstance<ContentPipelineEvent.ManifestFailed>().size)
            assertEquals(0, pipeline.snapshot().retryWakeups)
        } finally { pipeline.closeAndJoin() }
    }

    @Test
    fun retriesPublishOneManifestAndShareTheExistingWakeup() = runTest {
        var calls = 0
        val events = mutableListOf<ContentPipelineEvent>()
        val pipeline = pipeline(EpisodeManifestPort { calls++; if (calls < 3) error("retry"); manifest() }, events)
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.updateDemand(demand(), 1080, 2138)
            runCurrent()
            assertEquals(1, pipeline.snapshot().retryWakeups)
            advanceUntilIdle()
            assertEquals(3, calls)
            assertEquals(listOf(next), events.filterIsInstance<ContentPipelineEvent.ManifestReady>()
                .map { it.manifest.id })
            assertEquals(0, pipeline.snapshot().activeManifests)
        } finally { pipeline.closeAndJoin() }
    }

    @Test
    fun generationAndBackgroundCancellationRetainPhysicalOwnershipUntilCloseJoins() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val events = mutableListOf<ContentPipelineEvent>()
        val pipeline = pipeline(EpisodeManifestPort {
            calls++
            withContext(NonCancellable) { gate.await() }
            manifest()
        }, events)
        pipeline.registerManifest(1L, fixture.manifest)
        pipeline.updateDemand(demand(), 1080, 2138)
        runCurrent()
        for (generation in 2L..8L) {
            pipeline.setForeground(false)
            pipeline.registerManifest(generation, fixture.manifest)
            pipeline.updateDemand(demand().copy(generation = generation), 1080, 2138)
            pipeline.setForeground(true)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(1, pipeline.snapshot().activeManifests)
        }
        val closed = launch { pipeline.closeAndJoin() }
        runCurrent()
        assertFalse(closed.isCompleted)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(closed.isCompleted)
        assertEquals(1, calls)
        assertTrue(events.none { it is ContentPipelineEvent.ManifestReady })
    }

    @Test
    fun aSingleNetworkSlotServesTheViewportBeforeTheNextManifest() = runTest {
        val gate = CompletableDeferred<Unit>()
        var manifests = 0
        val pipeline = pipeline(EpisodeManifestPort { manifests++; manifest() }, mutableListOf(),
            raw = FakeRawPort(fixture, gate), networkLimit = 1)
        try {
            pipeline.registerManifest(1L, fixture.manifest)
            pipeline.updateDemand(demand(), 1080, 2138)
            runCurrent()
            assertEquals(0, manifests)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, manifests)
        } finally { gate.complete(Unit); pipeline.closeAndJoin() }
    }

    private fun TestScope.pipeline(source: EpisodeManifestPort, events: MutableList<ContentPipelineEvent>,
        raw: RawPagePort = FakeRawPort(fixture), networkLimit: Int = 4): ViewerContentPipeline {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ViewerContentPipeline(coroutineContext,
            ContentPipelineDispatchers(dispatcher, dispatcher, dispatcher, dispatcher),
            raw, FakeDecoder(), FakeUploader(), ContentPipelineSink { events += it },
            PipelineClock { testScheduler.currentTime }, networkLimit, source)
    }

    private fun demand(): DemandSnapshot = fixture.demand().copy(nextEpisode = next)
    private fun manifest() = EpisodeManifest(next, "Next", listOf(PageSpec(PageId.at(next, 0), 0)))
}
