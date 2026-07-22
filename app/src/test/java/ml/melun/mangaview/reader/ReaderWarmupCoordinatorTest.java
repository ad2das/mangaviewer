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
        assertEquals(32, ReaderWarmupCoordinator.launchByteLimitForTest("ntk"));
        assertEquals(48, ReaderWarmupCoordinator.launchByteLimitForTest("wfwf"));
        assertEquals(8, ReaderWarmupCoordinator.launchDecodeLimitForTest("ntk"));
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
        assertEquals(ReaderWarmupCoordinator.WarmupProfile.URL_ONLY, ReaderWarmupCoordinator.exactVisibleProfileForTest("wfwf"));
    }

    @Test
    public void preparedStoreCapsPinnedStartBitmaps() {
        assertEquals(3, ReaderPreparedStore.maxPinnedStartBitmapsForTest());
        assertEquals(16L * 1024L * 1024L, ReaderPreparedStore.softBitmapBytesForTest(""));
        assertEquals(24L * 1024L * 1024L, ReaderPreparedStore.hardBitmapBytesForTest(""));
        assertEquals(96L * 1024L * 1024L, ReaderPreparedStore.softBitmapBytesForTest("ntk"));
        assertEquals(128L * 1024L * 1024L, ReaderPreparedStore.hardBitmapBytesForTest("ntk"));
    }

    @Test
    public void preparedStoreReplacesFailedEntriesOnly() {
        assertTrue(ReaderPreparedStore.shouldReplaceExistingEntryForTest(true));
        assertFalse(ReaderPreparedStore.shouldReplaceExistingEntryForTest(false));
    }

    @Test
    public void concreteNtkPathDedupesTemporaryMangaIds() {
        assertEquals(
                ReaderWarmupCoordinator.stableEpisodeComponentForTest("/manhwa/20877/169511", 1),
                ReaderWarmupCoordinator.stableEpisodeComponentForTest(
                        "/manhwa/20877/169511?resume=1", 169511));
        assertFalse(ReaderWarmupCoordinator.stableEpisodeComponentForTest(
                        "/manhwa/20877/169511", 1)
                .equals(ReaderWarmupCoordinator.stableEpisodeComponentForTest(
                        "/manhwa/20877/169512", 1)));
        assertFalse(ReaderWarmupCoordinator.stableEpisodeComponentForTest("", 1)
                .equals(ReaderWarmupCoordinator.stableEpisodeComponentForTest("", 2)));
    }

    @Test
    public void authoritativeNtkPartialLaunchUsesEightPageTileRunwayBudget() {
        assertEquals(8, ReaderWarmupCoordinator.authoritativeNtkRunwayDecodePages());
        assertEquals(8, ReaderWarmupCoordinator.authoritativeNtkRunwaySoftwarePageLimitForTest());
        assertEquals(128L * 1024L * 1024L,
                ReaderWarmupCoordinator.authoritativeNtkRunwaySoftwareByteLimitForTest());
        assertTrue(ReaderWarmupCoordinator.isNtkLaunchRunwayOnlyForTest(8, 31));
        assertFalse(ReaderWarmupCoordinator.isNtkLaunchRunwayOnlyForTest(31, 31));
        assertFalse(ReaderWarmupCoordinator.isNtkLaunchRunwayOnlyForTest(0, 31));
        assertEquals(1, ReaderWarmupCoordinator.authoritativeNtkRunwaySampleSizeForTest(764, 1080));
        assertEquals(1, ReaderWarmupCoordinator.authoritativeNtkRunwaySampleSizeForTest(2160, 1080));
        assertFalse(ReaderWarmupCoordinator.claimMarksForegroundDuringStagingForTest());
    }

    @Test
    public void authoritativeNtkRunwayRequiresContiguousSafeTextureSpanTiles() {
        assertEquals(2048, ReaderWarmupCoordinator.authoritativeNtkRunwayTileSourceHeightForTest());
        assertTrue(ReaderWarmupCoordinator.hasAuthoritativeNtkRunwayTileSourceLayoutForTest(
                1200,
                new int[] {0},
                new int[] {1200}));
        assertTrue(ReaderWarmupCoordinator.hasAuthoritativeNtkRunwayTileSourceLayoutForTest(
                512,
                new int[] {0},
                new int[] {512}));
        assertFalse(ReaderWarmupCoordinator.hasAuthoritativeNtkRunwayTileSourceLayoutForTest(
                1200,
                new int[] {0, 1199},
                new int[] {1199, 1200}));
        assertFalse(ReaderWarmupCoordinator.hasAuthoritativeNtkRunwayTileSourceLayoutForTest(
                1200,
                new int[] {0, 1000},
                new int[] {1000, 1200}));
        assertFalse(ReaderWarmupCoordinator.hasAuthoritativeNtkRunwayTileSourceLayoutForTest(
                1200,
                new int[] {0, 600},
                new int[] {600, 1200}));
    }
}
