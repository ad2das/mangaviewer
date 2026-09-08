package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenreCatalogPagerTest {
    @Test fun reachesTheLastPageBeyondOneHundredWithoutDuplicates() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = GenreCatalogPager(this, StandardTestDispatcher(testScheduler)) { output = it }
        val requested = mutableListOf<Int>()
        pager.start { cursor ->
            val page = cursor?.toInt() ?: 1
            requested += page
            SourcePage(listOf(series(page - 1), series(page)), if (page < 105) "${page + 1}" else null)
        }
        runCurrent()
        repeat(104) { pager.next(); pager.next(); runCurrent() }
        pager.next()
        runCurrent()
        assertEquals((1..105).toList(), requested)
        val catalog = output as LibraryContent.Series
        assertEquals((0..105).map(::series), catalog.items)
        assertNull(catalog.nextCursor)
        assertNull(catalog.nextFailure)
    }

    @Test fun emptyIntermediatePageAndFailedNextPageRetainTheExactRetryCursor() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = GenreCatalogPager(this, StandardTestDispatcher(testScheduler)) { output = it }
        val cursors = mutableListOf<String?>()
        var failed = false
        pager.start { cursor ->
            cursors += cursor
            when (cursor) {
                null -> SourcePage(listOf(series(1)), "empty")
                "empty" -> SourcePage(emptyList(), "last")
                else -> {
                    if (!failed) { failed = true; error("temporary failure") }
                    SourcePage(listOf(series(2)))
                }
            }
        }
        runCurrent(); pager.next(); runCurrent()
        val failure = output as LibraryContent.Series
        assertEquals(listOf(series(1)), failure.items)
        assertEquals("last", failure.nextCursor)
        assertNotNull(failure.nextFailure)
        pager.next(); runCurrent()
        assertEquals(listOf(null, "empty", "last", "last"), cursors)
        assertEquals(listOf(series(1), series(2)), (output as LibraryContent.Series).items)
    }

    @Test fun staleSelectionCannotPublishEvenIfItsFetchIgnoresCancellation() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val gate = CompletableDeferred<Unit>()
        val pager = GenreCatalogPager(this, StandardTestDispatcher(testScheduler)) { output = it }
        pager.start { withContext(NonCancellable) { gate.await(); SourcePage(listOf(series(1))) } }
        runCurrent()
        pager.start { SourcePage(listOf(series(2))) }
        runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(listOf(series(2)), (output as LibraryContent.Series).items)
    }

    @Test fun repeatedCursorReportsFailureInsteadOfLoopingOrClaimingCompletion() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = GenreCatalogPager(this, StandardTestDispatcher(testScheduler)) { output = it }
        pager.start { cursor -> SourcePage(listOf(series(1)), cursor ?: "repeat") }
        runCurrent(); pager.next(); runCurrent()
        val catalog = output as LibraryContent.Series
        assertEquals(listOf(series(1)), catalog.items)
        assertNotNull(catalog.nextFailure)
        assertEquals("repeat", catalog.nextCursor)
    }

    private fun series(index: Int) = SourceSeries(SeriesId(SourceId("test"), "$index"), "$index")
}
