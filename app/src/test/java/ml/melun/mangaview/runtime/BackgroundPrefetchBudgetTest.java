package ml.melun.mangaview.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundPrefetchBudgetTest {
    @Test
    public void episodeSnapshotBudgetDedupesAndLimitsActiveWork() {
        BackgroundPrefetchBudget.clearEpisodeSnapshotsForTest();

        assertTrue(BackgroundPrefetchBudget.tryAcquireEpisodeSnapshot("a"));
        assertFalse(BackgroundPrefetchBudget.tryAcquireEpisodeSnapshot("a"));
        assertTrue(BackgroundPrefetchBudget.tryAcquireEpisodeSnapshot("b"));
        assertFalse(BackgroundPrefetchBudget.tryAcquireEpisodeSnapshot("c"));
        assertEquals(2, BackgroundPrefetchBudget.activeEpisodeSnapshotsForTest());

        BackgroundPrefetchBudget.releaseEpisodeSnapshot("a");

        assertTrue(BackgroundPrefetchBudget.tryAcquireEpisodeSnapshot("c"));
        assertEquals(2, BackgroundPrefetchBudget.activeEpisodeSnapshotsForTest());
        BackgroundPrefetchBudget.clearEpisodeSnapshotsForTest();
    }

    @Test
    public void episodeSnapshotFailureSuppressesNonCriticalPrefetch() {
        BackgroundPrefetchBudget.clearEpisodeSnapshotsForTest();

        BackgroundPrefetchBudget.recordEpisodeSnapshotFailure();

        assertTrue(BackgroundPrefetchBudget.isNonCriticalPrefetchSuppressed());
        assertFalse(BackgroundPrefetchBudget.tryAcquireEpisodeSnapshot("a"));
        assertEquals(0, BackgroundPrefetchBudget.activeEpisodeSnapshotsForTest());
        BackgroundPrefetchBudget.clearEpisodeSnapshotsForTest();
    }

    @Test
    public void userNavigationSuppressesNonCriticalPrefetchBriefly() {
        BackgroundPrefetchBudget.clearEpisodeSnapshotsForTest();

        BackgroundPrefetchBudget.suppressForUserNavigation();

        assertTrue(BackgroundPrefetchBudget.userNavigationSuppressMsForTest() >= 3500L);
        assertTrue(BackgroundPrefetchBudget.isNonCriticalPrefetchSuppressed());
        assertFalse(BackgroundPrefetchBudget.tryAcquireEpisodeSnapshot("tap"));
        BackgroundPrefetchBudget.clearEpisodeSnapshotsForTest();
    }
}
