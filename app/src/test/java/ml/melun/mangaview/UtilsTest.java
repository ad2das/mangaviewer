package ml.melun.mangaview;

import org.junit.Test;

import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.runtime.PreparedViewerLaunch;
import ml.melun.mangaview.runtime.ViewerPreparationCoordinator;
import ml.melun.mangaview.glide.ViewerWarmupManager;

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
    public void sourceSiteSwitchKeepsAnAlreadyActiveResolvedPreset() {
        assertTrue(Utils.sourceSiteAlreadyActiveForTest("ntk", true));
        assertTrue(Utils.sourceSiteAlreadyActiveForTest("wfwf", false));
        assertFalse(Utils.sourceSiteAlreadyActiveForTest("ntk", false));
        assertFalse(Utils.sourceSiteAlreadyActiveForTest("wfwf", true));
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
    public void ntkCaptchaLaunchSkipsOnlyForProofWithoutRecentChallenge() {
        assertTrue(Utils.shouldSkipNtkCaptchaLaunchForTest(true, true, false));
        assertFalse(Utils.shouldSkipNtkCaptchaLaunchForTest(true, true, true));
        assertFalse(Utils.shouldSkipNtkCaptchaLaunchForTest(true, false, true));
        assertFalse(Utils.shouldSkipNtkCaptchaLaunchForTest(false, true, false));
    }

    @Test
    public void preparedContinuePreventsBackgroundMainCaptchaFromStealingFocus() {
        assertTrue(Utils.shouldSkipPreparedMainCaptchaLaunchForTest(true, true, true));
        assertFalse(Utils.shouldSkipPreparedMainCaptchaLaunchForTest(true, true, false));
        assertFalse(Utils.shouldSkipPreparedMainCaptchaLaunchForTest(true, false, true));
        assertFalse(Utils.shouldSkipPreparedMainCaptchaLaunchForTest(false, true, true));
    }

    @Test
    public void ntkRecentChallengePreventsProofSuppression() {
        assertTrue(Utils.shouldSuppressNtkCaptchaAfterRecentVerificationForTest(true, true, false));
        assertFalse(Utils.shouldSuppressNtkCaptchaAfterRecentVerificationForTest(true, true, true));
        assertFalse(Utils.shouldSuppressNtkCaptchaAfterRecentVerificationForTest(true, false, false));
        assertFalse(Utils.shouldSuppressNtkCaptchaAfterRecentVerificationForTest(false, true, false));
    }

    @Test
    public void ntkWarpAssistShowsForUnverifiedNonVpnRouteBlocks() {
        assertTrue(Utils.shouldOfferNtkWarpAssistForFailureForTest(true, false, false, false, false));
        assertTrue(Utils.shouldOfferNtkWarpAssistForFailureForTest(true, false, false, true, true));
        assertFalse(Utils.shouldOfferNtkWarpAssistForFailureForTest(true, true, false, false, false));
        assertFalse(Utils.shouldOfferNtkWarpAssistForFailureForTest(true, false, true, false, false));
        assertFalse(Utils.shouldOfferNtkWarpAssistForFailureForTest(true, false, false, true, false));
        assertFalse(Utils.shouldOfferNtkWarpAssistForFailureForTest(false, false, false, false, false));
    }

    @Test
    public void cloudflareWarpAssistLaunchIsRecentOnlyWithinWindow() {
        assertTrue(Utils.hasRecentCloudflareWarpAssistLaunchForTest(1_000L, 1_500L, 1_000L));
        assertFalse(Utils.hasRecentCloudflareWarpAssistLaunchForTest(1_000L, 2_500L, 1_000L));
        assertFalse(Utils.hasRecentCloudflareWarpAssistLaunchForTest(0L, 1_500L, 1_000L));
        assertFalse(Utils.hasRecentCloudflareWarpAssistLaunchForTest(2_000L, 1_500L, 1_000L));
        assertFalse(Utils.hasRecentCloudflareWarpAssistLaunchForTest(1_000L, 1_500L, 0L));
    }

    @Test
    public void warpAssistSkipsEmulatorLicenseCheckerPlayStoreStub() {
        assertFalse(Utils.isFunctionalPlayStoreForTest("com.android.vending",
                "/product/app/LicenseChecker/LicenseChecker.apk"));
        assertTrue(Utils.isFunctionalPlayStoreForTest("com.android.vending",
                "/product/priv-app/Phonesky/Phonesky.apk"));
        assertTrue(Utils.isFunctionalPlayStoreForTest("com.android.vending",
                "/data/app/~~token/com.android.vending-abc/base.apk"));
        assertFalse(Utils.isFunctionalPlayStoreForTest("com.example.store",
                "/product/priv-app/Phonesky/Phonesky.apk"));
    }

    @Test
    public void warpAssistPrefersVpnSettingsBeforeWebPlayWithoutFunctionalPlayStore() {
        assertTrue(Utils.shouldPreferVpnSettingsBeforeWebPlayForTest(false));
        assertFalse(Utils.shouldPreferVpnSettingsBeforeWebPlayForTest(true));
        assertFalse(Utils.shouldAllowWebPlayFallbackForTest(false));
        assertTrue(Utils.shouldAllowWebPlayFallbackForTest(true));
    }

    @Test
    public void warpAssistAutoOpensOnlyForFreshHardBlockOffer() {
        assertTrue(Utils.shouldAutoOpenNtkWarpAssistForHardBlockForTest(true, true, false));
        assertFalse(Utils.shouldAutoOpenNtkWarpAssistForHardBlockForTest(false, true, false));
        assertFalse(Utils.shouldAutoOpenNtkWarpAssistForHardBlockForTest(true, false, false));
        assertFalse(Utils.shouldAutoOpenNtkWarpAssistForHardBlockForTest(true, true, true));
    }

    @Test
    public void exactViewerLaunchDoesNotBlockActivityLaunchForOnlineSources() {
        assertFalse(Utils.shouldWaitForExactFirstFrameForTest("wfwf", false));
        assertFalse(Utils.shouldWaitForExactFirstFrameForTest("ntk", true));
        assertFalse(Utils.shouldWaitForExactFirstFrameForTest("ntk", false));
        assertFalse(Utils.shouldWaitForExactFirstFrameForTest("", false));
        assertFalse(Utils.shouldWaitForExactFirstFrameForTest("", true));
    }

    @Test
    public void continueViewerLaunchDoesNotGateOnPreparedFirstFrame() {
        assertFalse(Utils.shouldWaitForContinueFirstFrameForTest(true, false));
        assertFalse(Utils.shouldWaitForContinueFirstFrameForTest(false, true));
        assertFalse(Utils.shouldWaitForContinueFirstFrameForTest(true, true));
        assertFalse(Utils.shouldWaitForContinueFirstFrameForTest(true, false, "ntk", true));
        assertFalse(Utils.shouldWaitForContinueFirstFrameForTest(true, true, "ntk", true));
        assertFalse(Utils.shouldWaitForContinueFirstFrameForTest(false, false, "ntk", true));
    }

    @Test
    public void exactViewerLaunchUsesBoundedFirstFrameWaits() {
        assertEquals(450L, Utils.exactFirstFrameWaitMsForTest("wfwf", false));
        assertEquals(450L, Utils.exactFirstFrameWaitMsForTest("", false));
        assertEquals(350L, Utils.exactFirstFrameWaitMsForTest("ntk", true));
    }

    @Test
    public void continueViewerLaunchHasShortFallbackWhenPreparationIsSlow() {
        assertEquals(520L, Utils.continueLaunchFallbackMsForTest("wfwf", false));
        assertEquals(520L, Utils.continueLaunchFallbackMsForTest("", false));
        assertEquals(220L, Utils.continueLaunchFallbackMsForTest("ntk", true));
        assertEquals(220L, Utils.continueLaunchFallbackMsForTest("", true));
    }

    @Test
    public void continueViewerFallbackWaitsWhenOnlineImagesAreNotReady() {
        assertTrue(Utils.shouldLaunchContinueFallbackForTest(true, false));
        assertTrue(Utils.shouldLaunchContinueFallbackForTest(true, true));
        assertTrue(Utils.shouldLaunchContinueFallbackForTest(false, false));
        assertFalse(Utils.shouldLaunchContinueFallbackForTest("ntk", true, false, false));
        assertTrue(Utils.shouldLaunchContinueFallbackForTest("ntk", true, true, false));
        assertTrue(Utils.shouldLaunchContinueFallbackForTest("ntk", true, false, true));
        assertFalse(Utils.shouldBlockUnpreparedContinueFallbackForTest(true));
        assertFalse(Utils.shouldBlockUnpreparedContinueFallbackForTest(false));
    }

    @Test
    public void exactViewerLaunchAllowsForegroundFallbackWhenPreparationMissesFirstFrame() {
        assertTrue(Utils.shouldAllowExactForegroundFallbackForTest("wfwf", false));
        assertTrue(Utils.shouldAllowExactForegroundFallbackForTest("", false));
        assertTrue(Utils.shouldAllowExactForegroundFallbackForTest("ntk", true));
        assertTrue(Utils.shouldAllowExactForegroundFallbackForTest("", true));
    }

    @Test
    public void ntkViewerImageFetchSiteMatchesBrowserContext() {
        assertEquals("cross-site", Utils.secFetchSiteForViewerImageForTest(
                "https://sbxh8.com/webtoon/16968/1463195",
                "https://moamoabon.com/blacktoon/episodes/16968/1463195/p001.jpg"));
        assertEquals("same-origin", Utils.secFetchSiteForViewerImageForTest(
                "https://moamoabon.com/blacktoon/episodes/16968/1463195",
                "https://moamoabon.com/blacktoon/episodes/16968/1463195/p001.jpg"));
    }

    @Test
    public void exactViewerLaunchRequiresPreparedFirstFrame() {
        PreparedViewerLaunch pending = ViewerPreparationCoordinator.statusForResult(
                ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING);
        PreparedViewerLaunch empty = ViewerPreparationCoordinator.statusForResult(
                ViewerWarmupManager.LOAD_EMPTY_IMAGES);
        PreparedViewerLaunch captcha = ViewerPreparationCoordinator.statusForResult(Title.LOAD_CAPTCHA);

        assertFalse(Utils.shouldLaunchExactWithoutPreparedForTest(pending));
        assertFalse(Utils.shouldLaunchExactWithoutPreparedForTest(empty));
        assertFalse(Utils.shouldLaunchExactWithoutPreparedForTest(captcha));
    }

    @Test
    public void viewerIntentWarmupSkipsWhenImagesAlreadyLoaded() {
        assertTrue(Utils.shouldScheduleViewerIntentWarmupForTest(true, true, false));
        assertFalse(Utils.shouldScheduleViewerIntentWarmupForTest(true, true, true));
        assertFalse(Utils.shouldScheduleViewerIntentWarmupForTest(false, true, false));
        assertFalse(Utils.shouldScheduleViewerIntentWarmupForTest(true, false, false));
    }

    @Test
    public void viewerMangaJsonCanOmitEpisodeListForExactLaunch() {
        Title title = new Title("던전 밥", "", "", new ArrayList<>(), "", 10017, MTitle.base_comic);
        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(new Manga(1, "던전 밥 1화", "", MTitle.base_comic));
        episodes.add(new Manga(2, "던전 밥 2화", "", MTitle.base_comic));
        title.setEps(episodes);
        Manga selected = episodes.get(0);
        selected.setTitle(title);
        selected.setTitleId(title.getId());
        selected.setEps(episodes);

        Manga copy = new Gson().fromJson(Utils.toViewerMangaJson(selected, title, false), Manga.class);

        assertEquals(0, Utils.snapshotEpisodes(copy).size());
        assertEquals("던전 밥 1화", copy.getName());
    }

    @Test
    public void readerTitleJsonWindowsLargeEpisodeListsForBinder() {
        Title title = new Title("Long NTK", "/webtoon/400739", "", new ArrayList<>(), "", 400739, MTitle.base_webtoon);
        title.setSourceSite("ntk");
        ArrayList<Manga> episodes = new ArrayList<>();
        for(int i = 0; i < 700; i++) {
            Manga episode = new Manga(900000 + i, "Long NTK " + i,
                    "2026-06-05-" + i + "-".repeat(256), MTitle.base_webtoon);
            episode.setTitleId(title.getId());
            episode.setNtkEpisodePath("/webtoon/400739/" + (1000000 + i));
            episodes.add(episode);
        }
        title.setEps(episodes);
        Manga anchor = episodes.get(300);

        Title copy = new Gson().fromJson(Utils.toViewerTitleJsonForReader(title, anchor, true), Title.class);

        ArrayList<Manga> window = Utils.snapshotEpisodes(copy);
        assertEquals(17, window.size());
        assertEquals(episodes.get(296).getId(), window.get(0).getId());
        assertEquals(episodes.get(312).getId(), window.get(16).getId());
    }

    @Test
    public void viewerLaunchDebounceRejectsRapidDuplicateStarts() {
        assertFalse(Utils.shouldAllowViewerLaunchForTest(1_400L, 1_000L));
        assertTrue(Utils.shouldAllowViewerLaunchForTest(1_450L, 1_000L));
        assertTrue(Utils.shouldAllowViewerLaunchForTest(1_100L, 1_000L, "episode-2", "episode-1"));
        assertFalse(Utils.shouldAllowViewerLaunchForTest(1_100L, 1_000L, "episode-1", "episode-1"));
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
