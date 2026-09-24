package ml.melun.mangaview.data.offline

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatePruneTest {
    private val series = SeriesId(SourceId("test"), "downloads")

    private fun episode(number: Int) = EpisodeId(series, number.toString())

    @Test
    fun completedRowsBeyondTheCapAreDroppedOldestFirst() {
        val states = linkedMapOf<EpisodeId, EpisodeDownloadState>()
        (0 until 300).forEach { states[episode(it)] = EpisodeDownloadState.Complete }
        pruneDownloadStates(states, 256)
        assertEquals(256, states.size)
        assertTrue(episode(0) !in states)
        assertEquals(episode(299), states.keys.last())
    }

    @Test
    fun activeRowsAreNeverDropped() {
        val states = linkedMapOf<EpisodeId, EpisodeDownloadState>()
        states[episode(0)] = EpisodeDownloadState.Queued
        states[episode(1)] = EpisodeDownloadState.Running(1, 10)
        (2 until 300).forEach { states[episode(it)] = EpisodeDownloadState.Complete }
        pruneDownloadStates(states, 256)
        assertEquals(256, states.size)
        assertTrue(episode(0) in states)
        assertTrue(episode(1) in states)
    }
}
