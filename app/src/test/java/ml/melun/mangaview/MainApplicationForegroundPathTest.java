package ml.melun.mangaview;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MainApplicationForegroundPathTest {
    @Test
    public void sameActivePathSkipsRepeatedSideEffects() {
        assertFalse(MainApplication.shouldRunNtkForegroundPathSideEffects(
                "/manhwa/10/20",
                1_000L,
                "/manhwa/10/20",
                30_999L));
    }

    @Test
    public void changedOrExpiredPathRunsSideEffects() {
        assertTrue(MainApplication.shouldRunNtkForegroundPathSideEffects(
                "/manhwa/10/20",
                1_000L,
                "/webtoon/30/40",
                1_001L));
        assertTrue(MainApplication.shouldRunNtkForegroundPathSideEffects(
                "/manhwa/10/20",
                1_000L,
                "/manhwa/10/20",
                31_000L));
    }

    @Test
    public void onlyTheClosingViewerCanClearForegroundOwnership() {
        assertTrue(MainApplication.shouldClearNtkForegroundViewerPath(
                "/manhwa/10/20", "/manhwa/10/20"));
        assertFalse(MainApplication.shouldClearNtkForegroundViewerPath(
                "/manhwa/10/20", "/manhwa/10/21"));
        assertFalse(MainApplication.shouldClearNtkForegroundViewerPath("", "/manhwa/10/20"));
    }

    @Test
    public void activeExactViewerKeepsLongReadingSessionForegroundPastLeaseTimeout() {
        assertTrue(MainApplication.isNtkForegroundViewerLeaseActive(
                "/webtoon/16034/1441525",
                1_000L,
                31_001L,
                true));
        assertFalse(MainApplication.isNtkForegroundViewerLeaseActive(
                "/webtoon/16034/1441525",
                1_000L,
                31_001L,
                false));
    }

    @Test
    public void ordinaryLeaseAndBlankPathRemainFailClosed() {
        assertTrue(MainApplication.isNtkForegroundViewerLeaseActive(
                "/manhwa/10/20",
                1_000L,
                30_999L,
                false));
        assertFalse(MainApplication.isNtkForegroundViewerLeaseActive(
                "",
                1_000L,
                1_001L,
                true));
    }
}
