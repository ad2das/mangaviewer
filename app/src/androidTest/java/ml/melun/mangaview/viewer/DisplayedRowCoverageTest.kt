package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.*
import org.junit.Test

class DisplayedRowCoverageTest {
    private val page = PageId(EpisodeId(SeriesId(SourceId("test"), "series"), "episode"), "page")

    @Test fun lastRowDoesNotProveInterveningRowsWereDisplayed() {
        val coverage = DisplayedRowCoverage()
        coverage.record(page, 0, 40, 100, true)
        coverage.record(page, 70, 100, 100, true)
        assertFalse(coverage.complete(page))
        assertEquals(40, coverage.firstMissingRow(page))
        coverage.record(page, 35, 75, 100, true)
        assertTrue(coverage.complete(page))
    }

    @Test fun telemetryOrLatchEvidenceDoesNotGrantCredit() {
        val coverage = DisplayedRowCoverage()
        coverage.record(page, 0, 100, 100, false)
        assertFalse(coverage.complete(page))
        assertEquals(page, coverage.firstMissing(listOf(page)))
    }

    @Test fun changedSourceDimensionsInvalidateOtherwiseCompleteCoverage() {
        val coverage = DisplayedRowCoverage()
        coverage.record(page, 0, 100, 100, true)
        coverage.record(page, 0, 120, 120, true)
        assertTrue(coverage.violations(listOf(page)).any { it.contains("height changed") })
    }
}
