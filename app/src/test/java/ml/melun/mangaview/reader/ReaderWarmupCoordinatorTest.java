package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReaderWarmupCoordinatorTest {
    @Test
    public void speculativeProfilesAvoidWindowDecode() {
        assertEquals(0, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.URL_ONLY));
        assertEquals(0, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.FIRST_BYTE));
        assertEquals(1, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.FIRST_BITMAP));
        assertEquals(4, ReaderWarmupCoordinator.decodeLimitForTest(ReaderWarmupCoordinator.WarmupProfile.LAUNCH_WINDOW));
    }

    @Test
    public void launchProfileKeepsBoundedByteWindow() {
        assertEquals(0, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.URL_ONLY));
        assertEquals(1, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.FIRST_BYTE));
        assertEquals(1, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.FIRST_BITMAP));
        assertEquals(16, ReaderWarmupCoordinator.byteLimitForTest(ReaderWarmupCoordinator.WarmupProfile.LAUNCH_WINDOW));
    }

    @Test
    public void preparedStoreCapsPinnedStartBitmaps() {
        assertEquals(3, ReaderPreparedStore.maxPinnedStartBitmapsForTest());
        assertEquals(48L * 1024L * 1024L, ReaderPreparedStore.maxBitmapBytesForTest());
    }
}
