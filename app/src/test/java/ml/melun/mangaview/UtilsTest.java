package ml.melun.mangaview;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UtilsTest {
    @Test
    public void readTextStreamUsesUtf8() {
        String text = "{\"title\":\"데스러버\"}";

        assertEquals(text, Utils.readTextStreamForTest(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void documentNamesSortWithNullsLast() {
        assertEquals(0, Utils.compareDocumentNamesForTest(null, null));
        assertEquals(1, Utils.compareDocumentNamesForTest(null, "0001.title"));
        assertEquals(-1, Utils.compareDocumentNamesForTest("0001.title", null));
        assertEquals(-1, Utils.compareDocumentNamesForTest("0001.title", "0002.title"));
    }

    @Test
    public void documentNamesSortNumericPrefixesNaturally() {
        assertEquals(-1, Integer.signum(Utils.compareDocumentNamesForTest("2.episode.2", "10.episode.10")));
        assertEquals(1, Integer.signum(Utils.compareDocumentNamesForTest("10.episode.10", "2.episode.2")));
    }

    @Test
    public void textBytesUseUtf8() {
        String text = "{\"favorite\":\"데스러버\"}";

        assertEquals(text, new String(Utils.utf8BytesForTest(text), StandardCharsets.UTF_8));
    }

    @Test
    public void numberFromStringHandlesMissingAndWholeNumberInput() {
        assertEquals(-1, Utils.getNumberFromString(null));
        assertEquals(-1, Utils.getNumberFromString(""));
        assertEquals(-1, Utils.getNumberFromString("abc123"));
        assertEquals(-1, Utils.getNumberFromString("999999999999999999999"));
        assertEquals(123, Utils.getNumberFromString("123"));
        assertEquals(123, Utils.getNumberFromString("123abc"));
    }

    @Test
    public void sampleBitmapDimensionsNeverDropBelowOnePixel() {
        assertEquals(1, Utils.sampleWidthForTest(100, 0));
        assertEquals(1, Utils.sampleHeightForTest(100, 100, 0));
        assertEquals(50, Utils.sampleHeightForTest(200, 100, 100));
    }

    @Test
    public void deleteRecursiveReportsMissingTargetAsFailure() throws Exception {
        File root = Files.createTempDirectory("delete-missing").toFile();
        File missing = new File(root, "missing");
        try {
            assertEquals(false, Utils.deleteRecursive(missing));
        } finally {
            root.delete();
        }
    }

    @Test
    public void offlineEpisodesSkipIncompleteDownloadFolders() throws Exception {
        File root = Files.createTempDirectory("offline-root").toFile();
        File complete = new File(root, "0001.done.1");
        File incomplete = new File(root, "0002.partial.2");
        try {
            complete.mkdirs();
            incomplete.mkdirs();
            new File(incomplete, "downloading").createNewFile();

            List<File> episodes = Utils.getOfflineEpisodes(root.getAbsolutePath());

            assertEquals(1, episodes.size());
            assertEquals(complete.getName(), episodes.get(0).getName());
        } finally {
            new File(incomplete, "downloading").delete();
            incomplete.delete();
            complete.delete();
            root.delete();
        }
    }

    @Test
    public void offlineEpisodesSortNumericPrefixesNaturally() throws Exception {
        File root = Files.createTempDirectory("offline-sort").toFile();
        File second = new File(root, "2.episode.2");
        File tenth = new File(root, "10.episode.10");
        try {
            second.mkdirs();
            tenth.mkdirs();

            List<File> episodes = Utils.getOfflineEpisodes(root.getAbsolutePath());

            assertEquals(second.getName(), episodes.get(0).getName());
            assertEquals(tenth.getName(), episodes.get(1).getName());
        } finally {
            second.delete();
            tenth.delete();
            root.delete();
        }
    }

    @Test
    public void episodeSnapshotsNormalizeVisibleEpisodeOrderForViewerPicker() {
        Title title = new Title("서머타임 렌더링", "", "", null, "", 10017, MTitle.base_comic);
        title.setSourceSite("wfwf");
        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(new Manga(25, "서머타임 렌더링 23화", "", MTitle.base_comic));
        episodes.add(new Manga(24, "서머타임 렌더링 24화", "", MTitle.base_comic));
        episodes.add(new Manga(23, "서머타임 렌더링 22화", "", MTitle.base_comic));
        title.setEps(episodes);

        Manga current = new Manga(24, "서머타임 렌더링 24화", "", MTitle.base_comic);
        current.setTitle(title);
        current.setEps(episodes);

        assertEquals("서머타임 렌더링 24화", Utils.snapshotEpisodes(title).get(0).getName());
        assertEquals("서머타임 렌더링 24화", Utils.snapshotEpisodes(current).get(0).getName());
    }

    @Test
    public void ntkAccessProbeTreatsMissingResponseAsChallenge() {
        assertTrue(NtkCaptchaPolicy.isAccessProbeChallenged(false, 0, null, false));
        assertTrue(NtkCaptchaPolicy.isAccessProbeChallenged(true, 403, "blocked", false));
        assertTrue(NtkCaptchaPolicy.isAccessProbeChallenged(true, 200, "", true));
        assertFalse(NtkCaptchaPolicy.isAccessProbeChallenged(true, 200, "{}", false));
    }

    @Test
    public void exactViewerLaunchWaitsForPreparedFirstFrame() {
        assertTrue(Utils.shouldWaitForExactFirstFrameForTest("wfwf", false));
        assertTrue(Utils.shouldWaitForExactFirstFrameForTest("ntk", true));
        assertFalse(Utils.shouldWaitForExactFirstFrameForTest("ntk", false));
        assertTrue(Utils.shouldWaitForExactFirstFrameForTest("", false));
    }

    @Test
    public void exactViewerLaunchUsesBoundedFirstFrameWaits() {
        assertEquals(450L, Utils.exactFirstFrameWaitMsForTest("wfwf", false));
        assertEquals(450L, Utils.exactFirstFrameWaitMsForTest("", false));
        assertEquals(350L, Utils.exactFirstFrameWaitMsForTest("ntk", true));
    }

    @Test
    public void exactViewerLaunchFallsBackQuicklyWhenPreparationIsSlow() {
        assertEquals(450L, Utils.exactLaunchFallbackMsForTest("wfwf", false));
        assertEquals(450L, Utils.exactLaunchFallbackMsForTest("", false));
        assertEquals(350L, Utils.exactLaunchFallbackMsForTest("ntk", true));
    }

    @Test
    public void continueViewerLaunchHasShortFallbackWhenPreparationIsSlow() {
        assertEquals(220L, Utils.continueLaunchFallbackMsForTest("wfwf", false));
        assertEquals(220L, Utils.continueLaunchFallbackMsForTest("", false));
        assertEquals(180L, Utils.continueLaunchFallbackMsForTest("ntk", true));
        assertEquals(180L, Utils.continueLaunchFallbackMsForTest("", true));
    }

    @Test
    public void exactViewerLaunchAllowsForegroundFallbackOnlyForWolf() {
        assertTrue(Utils.shouldAllowExactForegroundFallbackForTest("wfwf", false));
        assertTrue(Utils.shouldAllowExactForegroundFallbackForTest("", false));
        assertFalse(Utils.shouldAllowExactForegroundFallbackForTest("ntk", true));
        assertFalse(Utils.shouldAllowExactForegroundFallbackForTest("", true));
    }

    @Test
    public void viewerIntentWarmupSkipsWhenImagesAlreadyLoaded() {
        assertTrue(Utils.shouldScheduleViewerIntentWarmupForTest(true, true, false));
        assertFalse(Utils.shouldScheduleViewerIntentWarmupForTest(true, true, true));
        assertFalse(Utils.shouldScheduleViewerIntentWarmupForTest(false, true, false));
        assertFalse(Utils.shouldScheduleViewerIntentWarmupForTest(true, false, false));
    }

    @Test
    public void viewerLaunchDebounceRejectsRapidDuplicateStarts() {
        assertFalse(Utils.shouldAllowViewerLaunchForTest(3_000L, 1_000L));
        assertTrue(Utils.shouldAllowViewerLaunchForTest(3_200L, 1_000L));
    }

    @Test
    public void destinationLaunchDebounceRejectsRapidDuplicateStarts() {
        assertFalse(Utils.shouldAllowDestinationLaunchForTest(2_000L, 1_000L, 1_500L));
        assertTrue(Utils.shouldAllowDestinationLaunchForTest(2_500L, 1_000L, 1_500L));
    }

    @Test
    public void captchaLaunchBlocksWhenOffline() {
        assertTrue(Utils.shouldBlockCaptchaForOffline(false));
        assertFalse(Utils.shouldBlockCaptchaForOffline(true));
    }

    @Test
    public void notificationPermissionIsOnlyRequestedForDownloadNotificationsOnAndroid13Plus() {
        assertFalse(Utils.shouldRequestNotificationPermissionForDownloads(32, false));
        assertTrue(Utils.shouldRequestNotificationPermissionForDownloads(33, false));
        assertFalse(Utils.shouldRequestNotificationPermissionForDownloads(33, true));
    }
}
