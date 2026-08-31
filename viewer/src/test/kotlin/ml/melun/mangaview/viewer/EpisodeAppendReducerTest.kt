package ml.melun.mangaview.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeAppendReducerTest {
    private val reducer = ViewerFixtures.reducer()

    @Test
    fun appendPreservesTheExactAnchorAndExistingGeometry() {
        val ready = readyForAppend(generation = 31L)
        val scheduled = requireNotNull(reducer.reduce(
            ready,
            ViewerEvent.RetryWakeup(20L),
        ))
        val load = scheduled.commands.filterIsInstance<ViewerCommand.LoadNextEpisode>().single()
        val anchor = scheduled.state.scroll
        val prefix = scheduled.state.layout.entries.dropLast(1)
        val next = ViewerFixtures.manifest(7, episodeKey = "episode-2")

        val appended = requireNotNull(reducer.reduce(
            scheduled.state,
            ViewerEvent.NextEpisodeSucceeded(load.token, next, 21L),
        ))

        assertEquals(anchor, appended.state.scroll)
        assertEquals(scheduled.state.userInputRevision, appended.state.userInputRevision)
        assertEquals(prefix, appended.state.layout.entries.take(prefix.size))
        assertEquals(listOf("episode-1", "episode-2"), appended.state.manifests.map { it.id.remoteKey })
        assertTrue(appended.state.episodeAppends.getValue(load.token.fromEpisodeId).terminal)
        val duplicate = requireNotNull(reducer.reduce(
            appended.state,
            ViewerEvent.NextEpisodeSucceeded(load.token, next, 22L),
        ))
        assertEquals(appended.state.copy(lastEventNanos = 22L), duplicate.state)
    }

    @Test
    fun failedAppendHasOneOwnerAndRetriesOnlyAfterItsDeadline() {
        val ready = readyForAppend(generation = 41L)
        val scheduled = requireNotNull(reducer.reduce(
            ready,
            ViewerEvent.RetryWakeup(100L),
        ))
        val first = scheduled.commands.filterIsInstance<ViewerCommand.LoadNextEpisode>().single()
        val failed = requireNotNull(reducer.reduce(
            scheduled.state,
            ViewerEvent.NextEpisodeFailed(first.token, "timeout", 50L, 110L),
        ))

        assertTrue(failed.commands.none { it is ViewerCommand.LoadNextEpisode })
        val early = requireNotNull(reducer.reduce(failed.state, ViewerEvent.RetryWakeup(159L)))
        assertTrue(early.commands.none { it is ViewerCommand.LoadNextEpisode })
        val due = requireNotNull(reducer.reduce(early.state, ViewerEvent.RetryWakeup(160L)))
        val retry = due.commands.filterIsInstance<ViewerCommand.LoadNextEpisode>().single()
        assertEquals(2, retry.token.attempt)
        assertEquals(retry.token, due.state.episodeAppends.getValue(first.token.fromEpisodeId).owner)
    }

    @Test
    fun pendingManifestAddsOneScrollableBoundaryAndAtomicallyReplacesIt() {
        val next = ViewerFixtures.manifest(7, episodeKey = "episode-2")
        val current = ViewerFixtures.manifest(5) {
            ml.melun.mangaview.core.PageDimensions(1_000, 2_400)
        }.copy(nextEpisodeId = next.id)
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(51L, current, ViewerFixtures.viewport, 1L),
        )).state
        val verified = opened.replacePages(
            opened.pages.mapValues { (_, page) ->
                page.copy(encoded = VerifiedPageRef("cache", 1L, "sha"))
            },
        ).copy(velocityUnitsPerSecond = 0L)

        val scheduled = requireNotNull(reducer.reduce(
            verified,
            ViewerEvent.RetryWakeup(2L),
        ))
        val load = scheduled.commands.filterIsInstance<ViewerCommand.LoadNextEpisode>().single()
        val boundary = requireNotNull(
            scheduled.state.episodeAppends.getValue(current.id).boundaryPageId,
        )
        assertEquals(boundary, scheduled.state.pageOrder.last())
        assertEquals(current.pages.size + 1, scheduled.state.pageOrder.size)

        val atBoundary = requireNotNull(reducer.reduce(
            scheduled.state,
            ViewerEvent.UserScroll(FixedPx.fromPixels(100_000), 10_000L, 3L),
        ))
        assertEquals(boundary, atBoundary.state.scroll.anchor.pageId)
        assertFalse(atBoundary.commands.filterIsInstance<ViewerCommand.FetchPage>()
            .any { it.token.pageId == boundary })

        val boundaryTop = requireNotNull(atBoundary.state.layout.topOf(boundary))
        val boundaryHeight = requireNotNull(atBoundary.state.layout.heightOf(boundary))
        val appended = requireNotNull(reducer.reduce(
            atBoundary.state,
            ViewerEvent.NextEpisodeSucceeded(load.token, next, 4L),
        )).state

        assertFalse(appended.layout.contains(boundary))
        assertEquals(boundaryTop, appended.layout.topOf(next.pages.first().id))
        assertEquals(boundaryHeight, appended.layout.heightOf(next.pages.first().id))
        assertEquals(next.pages.first().id, appended.scroll.anchor.pageId)
        assertEquals(atBoundary.state.userInputRevision, appended.userInputRevision)
        assertEquals(current.pages.size + next.pages.size, appended.pageOrder.size)
        assertEquals(appended.pageOrder.size, appended.coldFetchSweep.pageCount)
    }

    @Test
    fun terminalEpisodeNeverStartsAnAdjacentSourceRequest() {
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(61L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        )).state
        val verified = opened.replacePages(
            opened.pages.mapValues { (_, page) ->
                page.copy(encoded = VerifiedPageRef("cache", 1L, "sha"))
            },
        )

        val terminal = requireNotNull(reducer.reduce(
            verified,
            ViewerEvent.RetryWakeup(2L),
        ))

        assertTrue(terminal.commands.none { it is ViewerCommand.LoadNextEpisode })
        assertTrue(terminal.state.episodeAppends.getValue(opened.currentEpisodeId).terminal)
    }

    private fun readyForAppend(generation: Long): ViewerState {
        val next = ViewerFixtures.manifest(1, episodeKey = "episode-2").id
        val current = ViewerFixtures.manifest(5).copy(nextEpisodeId = next)
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation,
                current,
                ViewerFixtures.viewport,
                1L,
            ),
        )).state
        val moved = requireNotNull(reducer.reduce(
            opened,
            ViewerEvent.UserScroll(FixedPx.fromPixels(2_000), 10_000L, 2L),
        )).state
        return moved.replacePages(
            moved.pages.mapValues { (_, page) ->
                page.copy(encoded = VerifiedPageRef("cache", 1L, "sha"))
            },
        ).copy(velocityUnitsPerSecond = 0L)
    }
}
