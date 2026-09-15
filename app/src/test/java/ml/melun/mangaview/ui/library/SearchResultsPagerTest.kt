package ml.melun.mangaview.ui.library

import kotlinx.coroutines.test.*
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchResultsPagerTest {
    @Test fun appendsPagesAndStopsAtTheLastCursor() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "검색에 실패했습니다") { output = it }
        val cursors = mutableListOf<String?>()
        pager.start { cursor ->
            cursors += cursor
            val page = cursor?.toInt() ?: 1
            SourcePage(listOf(series(page)), if (page < 3) "${page + 1}" else null)
        }
        runCurrent()
        assertEquals(listOf(series(1)), (output as LibraryContent.Series).items)
        pager.next(); runCurrent()
        pager.next(); runCurrent()
        assertEquals(listOf(null, "2", "3"), cursors)
        val results = output as LibraryContent.Series
        assertEquals(listOf(series(1), series(2), series(3)), results.items)
        assertNull(results.nextCursor)
        assertNull(results.nextFailure)
    }

    @Test fun failedFirstPageSurfacesAsSearchFailure() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "검색에 실패했습니다") { output = it }

        pager.start { error("boom") }
        runCurrent()

        assertEquals("boom", (output as LibraryContent.Failure).message)
    }

    @Test fun failedNextPageKeepsItemsAndTheExactRetryCursor() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "검색에 실패했습니다") { output = it }
        var failed = false
        pager.start { cursor ->
            when (cursor) {
                null -> SourcePage(listOf(series(1)), "2")
                else -> {
                    if (!failed) { failed = true; error("temporary failure") }
                    SourcePage(listOf(series(2)))
                }
            }
        }
        runCurrent(); pager.next(); runCurrent()
        val failure = output as LibraryContent.Series
        assertEquals(listOf(series(1)), failure.items)
        assertEquals("2", failure.nextCursor)
        assertEquals("temporary failure", failure.nextFailure)

        pager.next(); runCurrent()
        assertEquals(listOf(series(1), series(2)), (output as LibraryContent.Series).items)
    }

    private fun series(index: Int) = SourceSeries(SeriesId(SourceId("test"), "$index"), "$index")
}
