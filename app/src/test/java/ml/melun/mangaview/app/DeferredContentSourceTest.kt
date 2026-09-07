package ml.melun.mangaview.app

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredContentSourceTest {
    @Test
    fun explicitStartBeginsLazyInitializationWithoutAwaitingAProviderCall() = runTest {
        val started = CompletableDeferred<Unit>()
        val source = DeferredContentSource(SOURCE_ID, backgroundScope) {
            started.complete(Unit)
            DeferredSourceResource(FakeContentSource()) {}
        }

        assertTrue(source.start())
        started.await()
        source.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun closeBeforeLazyUsePreventsInitialization() = runTest {
        val initializations = AtomicInteger()
        val activations = AtomicInteger()
        val source = DeferredContentSource(
            id = SOURCE_ID,
            scope = this,
            beforeFirstStart = { activations.incrementAndGet() },
        ) {
            initializations.incrementAndGet()
            DeferredSourceResource(FakeContentSource()) {}
        }

        source.close()
        val failure = runCatching { source.manifest(EPISODE_ID) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(0, initializations.get())
        assertEquals(0, activations.get())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun closeDuringNonCooperativeEagerInitializationClosesLateResource() = runTest {
        val initializing = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        val closes = AtomicInteger()
        val source = DeferredContentSource(SOURCE_ID, this, CoroutineStart.DEFAULT) {
            initializing.complete(Unit)
            withContext(NonCancellable) { allowPublication.await() }
            DeferredSourceResource(FakeContentSource()) { closes.incrementAndGet() }
        }
        runCurrent()
        initializing.await()

        source.close()
        allowPublication.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, closes.get())
        assertTrue(runCatching { source.manifest(EPISODE_ID) }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun publishedResourceIsClosedExactlyOnce() = runTest {
        val closes = AtomicInteger()
        val source = DeferredContentSource(SOURCE_ID, this) {
            DeferredSourceResource(FakeContentSource()) { closes.incrementAndGet() }
        }

        assertEquals(EPISODE_ID, source.manifest(EPISODE_ID).id)
        source.close()
        source.close()

        assertEquals(1, closes.get())
    }

    @Test
    fun rejectedSourceIdentityClosesItsResource() = runTest {
        val closes = AtomicInteger()
        val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val source = DeferredContentSource(SOURCE_ID, sourceScope) {
            DeferredSourceResource(FakeContentSource(SourceId("other"))) {
                closes.incrementAndGet()
            }
        }

        val failure = runCatching { source.manifest(EPISODE_ID) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, closes.get())
        sourceScope.cancel()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentCallersShareOnePublishedResource() = runTest {
        val initializations = AtomicInteger()
        val activations = AtomicInteger()
        val closes = AtomicInteger()
        val source = DeferredContentSource(
            id = SOURCE_ID,
            scope = this,
            beforeFirstStart = { activations.incrementAndGet() },
        ) {
            initializations.incrementAndGet()
            DeferredSourceResource(FakeContentSource()) { closes.incrementAndGet() }
        }

        val calls = List(100) { async { source.manifest(EPISODE_ID) } }
        calls.forEach { assertEquals(EPISODE_ID, it.await().id) }
        source.close()

        assertEquals(1, initializations.get())
        assertEquals(1, activations.get())
        assertEquals(1, closes.get())
    }

    @Test
    fun failedFirstStartHookCanRetryBeforeInitializingTheDelegate() = runTest {
        val activations = AtomicInteger()
        val initializations = AtomicInteger()
        val source = DeferredContentSource(
            id = SOURCE_ID,
            scope = this,
            beforeFirstStart = {
                if (activations.incrementAndGet() == 1) {
                    throw IllegalStateException("warm failed")
                }
            },
        ) {
            initializations.incrementAndGet()
            DeferredSourceResource(FakeContentSource()) {}
        }

        assertTrue(
            runCatching { source.manifest(EPISODE_ID) }.exceptionOrNull() is IllegalStateException,
        )
        assertEquals(1, activations.get())
        assertEquals(0, initializations.get())

        assertEquals(EPISODE_ID, source.manifest(EPISODE_ID).id)
        assertEquals(2, activations.get())
        assertEquals(1, initializations.get())
        source.close()
    }

    @Test
    fun prepareActivatesBeforeProviderSpecificPreparation() = runTest {
        val events = mutableListOf<String>()
        val source = DeferredContentSource(
            id = SOURCE_ID,
            scope = this,
            preInitializationPrepare = { _, _ -> events += "provider-prepare" },
            beforeFirstStart = { events += "first-start" },
        ) {
            events += "initialize"
            DeferredSourceResource(FakeContentSource { events += "source-prepare" }) {}
        }

        source.prepare(EPISODE_ID, PreparationIntent.INITIAL_VIEW)

        assertEquals(
            listOf("first-start", "provider-prepare", "initialize", "source-prepare"),
            events,
        )
        source.close()
    }

    private class FakeContentSource(
        override val id: SourceId = SOURCE_ID,
        private val onPrepare: () -> Unit = {},
    ) : ContentSource {
        override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = EpisodeManifest(
            episodeId,
            "Episode",
            listOf(PageSpec(PageId.at(episodeId, 0), 0)),
        )

        override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
            unsupported()

        override suspend fun episodes(
            seriesId: SeriesId,
            cursor: String?,
        ): SourcePage<SourceEpisode> = unsupported()

        override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = unsupported()

        override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = onPrepare()

        override suspend fun openPage(
            pageId: PageId,
            validation: PageValidation?,
        ): OpenedPage = unsupported()

        private fun <T> unsupported(): T = throw UnsupportedOperationException("Not used")
    }

    private companion object {
        val SOURCE_ID = SourceId("source")
        val EPISODE_ID = EpisodeId(SeriesId(SOURCE_ID, "series"), "episode")
    }
}
