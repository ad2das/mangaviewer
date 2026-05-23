package ml.melun.mangaview.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ContinueReadinessCoordinatorTest {
    @Test
    public void coldStartPrimesMultipleVisibleContinuesWhenDataSaveIsOff() {
        assertEquals(1, ContinueReadinessCoordinator.coldStartLimitForTest(false));
        assertEquals(1, ContinueReadinessCoordinator.coldStartLimitForTest(true));
    }

    @Test
    public void repeatedVisibleAndTouchWarmupsShareShortDedupeWindow() {
        assertEquals(2000L, ContinueReadinessCoordinator.submitDedupeMsForTest());
    }

    @Test
    public void readinessStatePrefersFirstFrameOverUrlSnapshots() {
        assertEquals(ContinueReadinessCoordinator.State.FIRST_FRAME_READY,
                ContinueReadinessCoordinator.stateForTest(true, true, true, false));
        assertEquals(ContinueReadinessCoordinator.State.IMAGES_READY,
                ContinueReadinessCoordinator.stateForTest(false, true, true, false));
        assertEquals(ContinueReadinessCoordinator.State.EPISODES_READY,
                ContinueReadinessCoordinator.stateForTest(false, false, true, false));
        assertEquals(ContinueReadinessCoordinator.State.UNSEEN,
                ContinueReadinessCoordinator.stateForTest(false, false, false, false));
        assertEquals(ContinueReadinessCoordinator.State.FAILED,
                ContinueReadinessCoordinator.stateForTest(true, true, true, true));
    }
}
