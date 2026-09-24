package ml.melun.mangaview.source.ntk

import java.util.concurrent.ConcurrentHashMap
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PreparationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPreparationIntentBoundTest {
    private val series = SeriesId(SourceId("test"), "prep")

    private fun episode(number: Int) = EpisodeId(series, number.toString())

    @Test
    fun hintsBeyondTheCapAreDroppedAndTheCurrentOneSurvives() {
        val intents = ConcurrentHashMap<EpisodeId, PreparationIntent>()
        (0 until 100).forEach { intents[episode(it)] = PreparationIntent.INITIAL_VIEW }
        prunePreparationIntents(intents, episode(50), 64)
        assertEquals(64, intents.size)
        assertTrue(intents.containsKey(episode(50)))
    }
}
