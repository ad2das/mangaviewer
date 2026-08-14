package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfAdjacentEpisodeArchitectureTest {
    @Test
    fun forwardTimelinePrimeUsesTheDirectionCheckedBoundaryResolver() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val prime = source.substringAfter("private fun primeForwardTimeline()")
            .substringBefore("private fun pageRefsForEpisode(")
        assertTrue(prime.contains("adjacentEpisodeCandidates("))
        assertTrue(prime.contains("ReaderSurfaceView.DIRECTION_NEXT"))
        assertTrue(prime.contains("isWfwfSource(current, currentTitle) -> 1"))
        assertFalse(prime.contains("current.nextEp()"))
    }

    @Test
    fun wfwfKeepsOnlyOnePreparedNeighbourAndStopsAfterTheImmediateMiss() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val selector = source.substringAfter("private fun nextUnloadedAdjacentEpisode(")
            .substringBefore("private fun adjacentEpisodeCandidates(")
        assertTrue(selector.contains("isImmediateNumericCandidate("))
        assertTrue(selector.contains("return null"))

        val append = source.substringAfter("val adjacentCandidates = adjacentEpisodeCandidates(")
            .substringBefore("if (resolvedTarget == null || resolvedUrls.isEmpty())")
        assertTrue(append.contains("isImmediateNumericCandidate("))
        assertTrue(append.contains("break"))
    }

    @Test
    fun anAlreadyPreparedImmediateNeighbourWinsBeforeAnyRemoteListRefresh() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val boundary = source.substringAfter(
            "var target = nextUnloadedAdjacentEpisode(anchorManga, currentTitle, episodes, direction)",
        ).substringBefore("var resolvedTarget: Manga? = null")
        val prepared = boundary.indexOf("hasLoadedImmediateWfwfAdjacentEpisode(")
        val refresh = boundary.indexOf("imageRepository.fetchEpisodesForeground(currentTitle, it)")
        assertTrue(prepared >= 0)
        assertTrue(refresh > prepared)
        assertTrue(boundary.substring(prepared, refresh).contains("return@execute"))
    }

    @Test
    fun directWfwfNeighbourIsRankedBeforeAListRowThatSkipsManyEpisodes() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val selector = source.substringAfter("private fun adjacentEpisodeCandidates(")
            .substringBefore("private fun ntkTrustedProvidedAdjacentCandidate(")
        val direct = selector.indexOf("syntheticCandidateIds(source.id, direction, 1)")
        val listWalk = selector.indexOf("val sourceIndex = looseEpisodeIndexForAppend")
        assertTrue(direct >= 0)
        assertTrue(listWalk > direct)
        assertTrue(selector.substringBefore("syntheticCandidateIds(source.id, direction, 1)")
            .contains("isImmediateVisibleCandidate("))
    }
}
