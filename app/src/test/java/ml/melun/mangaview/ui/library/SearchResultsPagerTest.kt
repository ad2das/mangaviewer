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
    @Test fun openingDetailsPausesPendingSearchAndResumesTheSameCursorWithoutLosingResults() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pending = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requests = mutableListOf<String?>()
        var secondAttempts = 0
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { cursor ->
            requests += cursor
            if (cursor == null) SourcePage(listOf(series(1)), "2") else {
                if (secondAttempts++ == 0) pending.await()
                SourcePage(listOf(series(2)))
            }
        }
        runCurrent(); pager.next(); runCurrent(); pager.pause(); runCurrent()
        assertFalse((output as LibraryContent.Series).loadingNext)
        assertEquals(listOf(series(1)), (output as LibraryContent.Series).items)
        pager.next(); runCurrent()
        assertEquals(listOf(null, "2"), requests)
        pager.resume(); advanceUntilIdle()
        assertEquals(listOf(null, "2", "2"), requests)
        assertEquals(listOf(series(1), series(2)), (output as LibraryContent.Series).items)
        assertNull((output as LibraryContent.Series).nextCursor)
    }

    @Test fun reconciliationWarningKeepsResultsAndCanResumeAtTheRepairCursor() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val requested = mutableListOf<String?>()
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { cursor ->
            requested += cursor
            when (cursor) {
                null -> SourcePage(listOf(series(1)), "2")
                "2" -> SourcePage(listOf(series(1)), "r5:1", "추가 확인 필요")
                else -> SourcePage(listOf(series(2)))
            }
        }
        runCurrent(); pager.next(); runCurrent()
        val paused = output as LibraryContent.Series
        assertEquals(listOf(series(1)), paused.items)
        assertEquals("r5:1", paused.nextCursor)
        assertEquals("추가 확인 필요", paused.nextFailure)
        assertFalse(paused.loadingNext)
        assertEquals(listOf(null, "2"), requested)
        pager.next(); runCurrent()
        assertEquals(listOf(series(1), series(2)), (output as LibraryContent.Series).items)
        assertNull((output as LibraryContent.Series).nextFailure)
    }

    @Test fun providerCooldownResumesTheFailedPageWithoutLosingEarlierResults() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        var attempts = 0
        val cursors = mutableListOf<String?>()
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { cursor ->
            cursors += cursor
            if (cursor == null) SourcePage(listOf(series(1)), "2") else {
                if (attempts++ == 0) throw ml.melun.mangaview.source.SourceThrottledException("429", 60_000)
                SourcePage(listOf(series(2)))
            }
        }
        runCurrent(); pager.next(); runCurrent()
        val waiting = output as LibraryContent.Series
        assertEquals(listOf(series(1)), waiting.items)
        assertTrue(waiting.loadingNext)
        assertTrue(waiting.nextFailure!!.contains("60초"))
        advanceTimeBy(59_999); runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(null, "2", "2"), cursors)
        assertEquals(listOf(series(1), series(2)), (output as LibraryContent.Series).items)
        assertNull((output as LibraryContent.Series).nextFailure)
    }

    @Test fun aNewSearchCancelsThePreviousCooldownAndCannotReviveItsResults() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        var oldCalls = 0
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { oldCalls++; throw ml.melun.mangaview.source.SourceThrottledException("429", 60_000) }
        runCurrent()
        pager.start { SourcePage(listOf(series(2))) }; runCurrent()
        advanceUntilIdle()
        assertEquals(1, oldCalls)
        assertEquals(listOf(series(2)), (output as LibraryContent.Series).items)
    }

    @Test fun timeoutBecomesRetryableFailureInsteadOfPermanentLoading() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { kotlinx.coroutines.awaitCancellation() }
        advanceTimeBy(SEARCH_REQUEST_TIMEOUT_MILLIS); runCurrent()
        assertTrue((output as LibraryContent.Series).loadingNext)
        advanceUntilIdle()
        assertTrue(output is LibraryContent.Failure)
        pager.start { SourcePage(listOf(series(1))) }; runCurrent()
        assertEquals(listOf(series(1)), (output as LibraryContent.Series).items)
    }

    @Test fun timedOutContinuationAutomaticallyRetriesTheSamePageAndPreservesEarlierItems() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val cursors = mutableListOf<String?>()
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { cursor ->
            cursors += cursor
            if (cursor == null) SourcePage(listOf(series(1)), "2") else {
                if (cursors.size == 2) kotlinx.coroutines.awaitCancellation()
                SourcePage(listOf(series(2)))
            }
        }
        runCurrent(); pager.next(); runCurrent()
        advanceTimeBy(SEARCH_REQUEST_TIMEOUT_MILLIS); runCurrent()
        assertEquals(listOf(series(1)), (output as LibraryContent.Series).items)
        advanceUntilIdle()
        assertEquals(listOf(null, "2", "2"), cursors)
        assertEquals(listOf(series(1), series(2)), (output as LibraryContent.Series).items)
        assertNull((output as LibraryContent.Series).nextCursor)
    }

    @Test fun clearingAnInFlightSearchCannotPublishALateResult() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val deferred = kotlinx.coroutines.CompletableDeferred<SourcePage<SourceSeries>>()
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { deferred.await() }; runCurrent()
        pager.reset(); output = LibraryContent.Empty
        deferred.complete(SourcePage(listOf(series(1)))); runCurrent()
        assertEquals(LibraryContent.Empty, output)
    }

    @Test fun repeatedPromotedCardsAreDeduplicatedWithoutLosingLaterResults() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { cursor ->
            if (cursor == null) SourcePage(listOf(series(1), series(2)), "2")
            else SourcePage(listOf(series(1), series(3)))
        }
        runCurrent(); pager.next(); runCurrent()
        assertEquals(listOf(series(1), series(2), series(3)), (output as LibraryContent.Series).items)
    }

    @Test fun filteredEmptyPagesAreFollowedButEndlessNoProgressIsBoundedAndRetryable() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        var calls = 0
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { cursor -> calls++; SourcePage(emptyList(), ((cursor?.toInt() ?: 1) + 1).toString()) }
        runCurrent()
        assertEquals(8, calls)
        assertEquals("9", (output as LibraryContent.Series).nextCursor)
        assertNotNull((output as LibraryContent.Series).nextFailure)
    }

    @Test fun repeatedCursorFailsWithoutDiscardingTheVisiblePage() = runTest {
        var output: LibraryContent = LibraryContent.Empty
        val pager = SearchResultsPager(this, StandardTestDispatcher(testScheduler), "failed") { output = it }
        pager.start { cursor -> SourcePage(listOf(series(if (cursor == null) 1 else 2)), "2") }
        runCurrent(); pager.next(); runCurrent()
        assertEquals(listOf(series(1)), (output as LibraryContent.Series).items)
        assertNotNull((output as LibraryContent.Series).nextFailure)
    }

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
