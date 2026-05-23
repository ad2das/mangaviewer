package ml.melun.mangaview.glide;

import org.junit.Test;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.Arrays;
import java.util.Collections;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ViewerWarmupManagerTest {
    @Test
    public void usablePageImageRejectsMissingUrls() {
        assertFalse(ViewerWarmupManager.isUsablePageImageForTest(null));
        assertFalse(ViewerWarmupManager.isUsablePageImageForTest(""));
        assertFalse(ViewerWarmupManager.isUsablePageImageForTest("   "));
    }

    @Test
    public void usablePageImageAcceptsNonBlankUrls() {
        assertTrue(ViewerWarmupManager.isUsablePageImageForTest("/image/1.jpg"));
    }

    @Test
    public void usablePageImageRejectsNtkBoardUploadBanners() {
        assertFalse(ViewerWarmupManager.isUsablePageImageForTest(
                "https://i.toonflix.app/board_uploads/2026/05/15/ad.png"));
    }

    @Test
    public void usableImageListRejectsOnlyBlankUrls() {
        assertFalse(ViewerWarmupManager.hasUsableImagesForTest(null));
        assertFalse(ViewerWarmupManager.hasUsableImagesForTest(Collections.emptyList()));
        assertFalse(ViewerWarmupManager.hasUsableImagesForTest(Arrays.asList("", "   ", null)));
    }

    @Test
    public void usableImageListAcceptsAnyNonBlankUrl() {
        assertTrue(ViewerWarmupManager.hasUsableImagesForTest(Arrays.asList("", "/image/1.jpg")));
    }

    @Test
    public void usableImageListRejectsOnlyNtkBoardUploadBanners() {
        assertFalse(ViewerWarmupManager.hasUsableImagesForTest(Arrays.asList(
                "https://i.toonflix.app/board_uploads/2026/05/15/ad.png",
                "https://i.toonflix.app/board_uploads/2026/05/15/ad2.png")));
    }

    @Test
    public void diskSnapshotFreshnessRejectsExpiredSnapshots() {
        long now = 30 * 60 * 1000L;

        assertFalse(ViewerWarmupManager.isDiskSnapshotFreshForTest(now - 21 * 60 * 1000L, now));
        assertTrue(ViewerWarmupManager.isDiskSnapshotFreshForTest(now - 19 * 60 * 1000L, now));
    }

    @Test
    public void diskSnapshotFreshnessRejectsFutureTimestamps() {
        long now = 30 * 60 * 1000L;

        assertFalse(ViewerWarmupManager.isDiskSnapshotFreshForTest(now + 1, now));
    }

    @Test
    public void staleDiskSnapshotsAreUsableForColdStart() {
        long now = 24L * 60L * 60L * 1000L;

        assertFalse(ViewerWarmupManager.isDiskSnapshotFreshForTest(now - 21L * 60L * 1000L, now));
        assertTrue(ViewerWarmupManager.isDiskSnapshotUsableForColdStartForTest(now - 21L * 60L * 1000L, now));
        assertFalse(ViewerWarmupManager.isDiskSnapshotUsableForColdStartForTest(now - 8L * 24L * 60L * 60L * 1000L, now));
        assertFalse(ViewerWarmupManager.isDiskSnapshotUsableForColdStartForTest(now + 1, now));
    }

    @Test
    public void metricLoggingStaysOffWhenDebugTagsAreNotLoggable() {
        assertFalse(ViewerWarmupManager.shouldLogMetricForTest(false, false));
    }

    @Test
    public void metricLoggingCanBeEnabledByEitherDebugTag() {
        assertTrue(ViewerWarmupManager.shouldLogMetricForTest(true, false));
        assertTrue(ViewerWarmupManager.shouldLogMetricForTest(false, true));
    }

    @Test
    public void sourceMatchAllowsOnlyNtkTitlesOnNtkSite() {
        assertTrue(ViewerWarmupManager.sourceMatchesCurrentSiteForTest("ntk", true));
        assertFalse(ViewerWarmupManager.sourceMatchesCurrentSiteForTest("wfwf", true));
    }

    @Test
    public void sourceMatchSkipsNtkTitlesOffNtkSite() {
        assertTrue(ViewerWarmupManager.sourceMatchesCurrentSiteForTest("wfwf", false));
        assertFalse(ViewerWarmupManager.sourceMatchesCurrentSiteForTest("ntk", false));
    }

    @Test
    public void visibleContinueWarmupAllowsCrossSiteCards() {
        assertTrue(ViewerWarmupManager.shouldWarmupContinueForSiteForTest("ntk", false, true));
        assertTrue(ViewerWarmupManager.shouldWarmupContinueForSiteForTest("wfwf", true, true));
        assertFalse(ViewerWarmupManager.shouldWarmupContinueForSiteForTest("ntk", false, false));
    }

    @Test
    public void sourceMatchKeepsLegacyBlankSourcesCompatible() {
        assertTrue(ViewerWarmupManager.sourceMatchesCurrentSiteForTest("", true));
        assertTrue(ViewerWarmupManager.sourceMatchesCurrentSiteForTest(null, false));
    }

    @Test
    public void exactEpisodeLaunchAllowsNtkDirectWarmupOnNtkSite() {
        assertTrue(ViewerWarmupManager.shouldWarmupExactEpisodeOnLaunchForTest("ntk", true));
        assertTrue(ViewerWarmupManager.shouldWarmupExactEpisodeOnLaunchForTest("", true));
        assertTrue(ViewerWarmupManager.shouldWarmupExactEpisodeOnLaunchForTest(null, true));
    }

    @Test
    public void exactEpisodeLaunchKeepsLegacyWarmupOffNtkSite() {
        assertFalse(ViewerWarmupManager.shouldWarmupExactEpisodeOnLaunchForTest("wfwf", false));
        assertFalse(ViewerWarmupManager.shouldWarmupExactEpisodeOnLaunchForTest("", false));
        assertFalse(ViewerWarmupManager.shouldWarmupExactEpisodeOnLaunchForTest(null, false));
        assertFalse(ViewerWarmupManager.shouldWarmupExactEpisodeOnLaunchForTest("ntk", false));
    }

    @Test
    public void ntkBackgroundWarmupUsesDirectOnlyInsteadOfSkipping() {
        assertFalse(ViewerWarmupManager.shouldSkipNtkWarmupForTest(true));
        assertFalse(ViewerWarmupManager.shouldSkipNtkWarmupForTest(false));
    }

    @Test
    public void backgroundWarmupUsesDirectOnlyForWolfAndNtk() {
        assertTrue(ViewerWarmupManager.shouldUseDirectOnlyBackgroundWarmupForTest("wfwf"));
        assertTrue(ViewerWarmupManager.shouldUseDirectOnlyBackgroundWarmupForTest("ntk"));
    }

    @Test
    public void crossSiteWarmupUsesSourceSitePreset() {
        assertEquals(CustomHttpClient.NTK_COMIC_URL, ViewerWarmupManager.sourceSitePresetForWarmupForTest("ntk")[0]);
        assertEquals(CustomHttpClient.NTK_WEBTOON_URL, ViewerWarmupManager.sourceSitePresetForWarmupForTest("ntk")[1]);
        assertEquals(CustomHttpClient.DEFAULT_COMIC_URL, ViewerWarmupManager.sourceSitePresetForWarmupForTest("wfwf")[0]);
        assertEquals(CustomHttpClient.WEBTOON_URL, ViewerWarmupManager.sourceSitePresetForWarmupForTest("wfwf")[1]);
    }

    @Test
    public void initialViewerFetchRetriesOnlyRecoverableEmptyErrors() {
        assertTrue(ViewerWarmupManager.shouldRetryInitialViewerFetchForTest(Title.LOAD_ERROR, false, false));
        assertFalse(ViewerWarmupManager.shouldRetryInitialViewerFetchForTest(Title.LOAD_ERROR, true, false));
        assertFalse(ViewerWarmupManager.shouldRetryInitialViewerFetchForTest(Title.LOAD_ERROR, false, true));
        assertFalse(ViewerWarmupManager.shouldRetryInitialViewerFetchForTest(Title.LOAD_CAPTCHA, false, false));
        assertFalse(ViewerWarmupManager.shouldRetryInitialViewerFetchForTest(Title.LOAD_OK, false, false));
    }

    @Test
    public void coldNetworkViewerOpenDoesNotBlockOnFirstDecode() {
        assertFalse(ViewerWarmupManager.shouldDecodeFirstPagesBlockingForTest(false, false));
    }

    @Test
    public void cachedViewerOpenCanUseBlockingDecodeForInstantFirstFrame() {
        assertTrue(ViewerWarmupManager.shouldDecodeFirstPagesBlockingForTest(true, false));
        assertFalse(ViewerWarmupManager.shouldDecodeFirstPagesBlockingForTest(false, true));
    }

    @Test
    public void blockingDecodeTimeoutOnlyChecksForFastCacheHits() {
        assertEquals(650L, ViewerWarmupManager.blockingDecodeTimeoutMsForTest(true));
        assertEquals(250L, ViewerWarmupManager.blockingDecodeTimeoutMsForTest(false));
    }

    @Test
    public void continueWarmupWaitsBrieflyForFirstDecodedFrame() {
        assertEquals(1800L, ViewerWarmupManager.continueFirstFrameWaitMsForTest(false));
        assertEquals(1200L, ViewerWarmupManager.continueFirstFrameWaitMsForTest(true));
    }

    @Test
    public void viewerRequestsCacheTransformedResourcesForColdReentry() {
        assertSame(DiskCacheStrategy.ALL, ViewerWarmupManager.viewerDiskCacheStrategyForTest());
    }

    @Test
    public void backgroundWarmupDoesNotUseBlockingDecode() {
        assertFalse(ViewerWarmupManager.shouldDecodeFirstPagesBlockingForTest(false, true, false));
    }

    @Test
    public void exactPreparedSnapshotRequiresSameEpisode() {
        Manga requested = new Manga(348, "274화", "", 1);
        requested.setTitleId(8605);
        Manga same = new Manga(348, "274화", "", 1);
        same.setTitleId(8605);
        Manga differentEpisode = new Manga(2, "2화", "", 1);
        differentEpisode.setTitleId(8605);
        Manga differentTitle = new Manga(348, "274화", "", 1);
        differentTitle.setTitleId(1);

        assertTrue(ViewerWarmupManager.samePreparedEpisodeForTest(same, requested));
        assertFalse(ViewerWarmupManager.samePreparedEpisodeForTest(differentEpisode, requested));
        assertFalse(ViewerWarmupManager.samePreparedEpisodeForTest(differentTitle, requested));
    }

    @Test
    public void pagePreloadDedupeSuppressesImmediateDuplicates() {
        ViewerWarmupManager.clearRecentPagePreloadsForTest();

        assertTrue(ViewerWarmupManager.markPagePreloadForTest("page:1", 1000L));
        assertFalse(ViewerWarmupManager.markPagePreloadForTest("page:1", 1200L));
        assertTrue(ViewerWarmupManager.markPagePreloadForTest("page:1", 2601L));
    }

    @Test
    public void onlineWarmupSkipsWhenNetworkUnavailable() {
        assertTrue(ViewerWarmupManager.shouldSkipOnlineWarmup(true, true));
        assertFalse(ViewerWarmupManager.shouldSkipOnlineWarmup(false, true));
        assertFalse(ViewerWarmupManager.shouldSkipOnlineWarmup(true, false));
    }

    @Test
    public void visibleContinueWarmupCanBeSuppressedDuringViewerEntry() {
        assertTrue(ViewerWarmupManager.shouldSuppressVisibleContinueWarmupForTest(1000L, 1500L));
        assertFalse(ViewerWarmupManager.shouldSuppressVisibleContinueWarmupForTest(1500L, 1500L));
        assertTrue(ViewerWarmupManager.shouldSuppressVisibleContinueWarmupForTest(1500L, 1500L, true));
        assertFalse(ViewerWarmupManager.shouldSuppressVisibleContinueWarmupForTest(1500L, 1500L, false));
    }

    @Test
    public void decodedWarmupActiveLimitCoversFastFling() {
        assertTrue(ViewerWarmupManager.decodedTargetActiveSoftLimitForTest() >= 16);
    }
}
