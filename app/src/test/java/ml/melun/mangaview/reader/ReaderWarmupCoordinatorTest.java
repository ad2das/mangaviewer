package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderWarmupCoordinatorTest {
    @Test
    public void speculativeProfilesAvoidWindowDecode() {
        assertEquals(0, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.URL_ONLY));
        assertEquals(0, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.FIRST_BYTE));
        assertEquals(0, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.ADJACENT_BYTES));
        assertEquals(1, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.FIRST_BITMAP));
        assertEquals(8, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.LAUNCH_WINDOW));
    }

    @Test
    public void launchProfileKeepsBoundedByteWindow() {
        assertEquals(0, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.URL_ONLY));
        assertEquals(1, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.FIRST_BYTE));
        assertEquals(1, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.FIRST_BITMAP));
        assertEquals(5, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.ADJACENT_BYTES));
        assertEquals(16, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.LAUNCH_WINDOW));
        assertEquals(20, ReaderWarmupCoordinator.launchByteLimitForTest("ntk"));
        assertEquals(48, ReaderWarmupCoordinator.launchByteLimitForTest("wfwf"));
        assertEquals(4, ReaderWarmupCoordinator.launchDecodeLimitForTest("ntk"));
        assertEquals(32, ReaderWarmupCoordinator.launchDecodeLimitForTest("wfwf"));
    }

    @Test
    public void tapProfilesDecodeFirstBitmapForFastLaunch() {
        assertEquals(ReaderWarmupCoordinator.WarmupProfile.FIRST_BITMAP, ReaderWarmupCoordinator.tapProfileForTest("ntk"));
        assertEquals(ReaderWarmupCoordinator.WarmupProfile.FIRST_BITMAP, ReaderWarmupCoordinator.tapProfileForTest("wfwf"));
        assertEquals(ReaderWarmupCoordinator.WarmupProfile.LAUNCH_WINDOW, ReaderWarmupCoordinator.launchProfileForTest(false));
        assertEquals(ReaderWarmupCoordinator.WarmupProfile.FIRST_BITMAP, ReaderWarmupCoordinator.launchProfileForTest(false, "wfwf"));
        assertEquals(ReaderWarmupCoordinator.WarmupProfile.LAUNCH_WINDOW, ReaderWarmupCoordinator.launchProfileForTest(false, "ntk"));
        assertEquals(ReaderWarmupCoordinator.WarmupProfile.URL_ONLY, ReaderWarmupCoordinator.launchProfileForTest(true));
        assertEquals(12, ReaderWarmupCoordinator.adjacentByteLimitForTest("ntk"));
        assertEquals(3, ReaderWarmupCoordinator.adjacentByteLimitForTest("wfwf"));
    }

    @Test
    public void preparedStoreCapsPinnedStartBitmaps() {
        assertEquals(1, ReaderPreparedStore.maxPinnedStartBitmapsForTest());
        assertEquals(16L * 1024L * 1024L, ReaderPreparedStore.softBitmapBytesForTest(""));
        assertEquals(24L * 1024L * 1024L, ReaderPreparedStore.hardBitmapBytesForTest(""));
        assertEquals(12L * 1024L * 1024L, ReaderPreparedStore.softBitmapBytesForTest("ntk"));
        assertEquals(16L * 1024L * 1024L, ReaderPreparedStore.hardBitmapBytesForTest("ntk"));
    }

    @Test
    public void preparedStoreReplacesFailedEntriesOnly() {
        assertTrue(ReaderPreparedStore.shouldReplaceExistingEntryForTest(true));
        assertFalse(ReaderPreparedStore.shouldReplaceExistingEntryForTest(false));
    }
}
