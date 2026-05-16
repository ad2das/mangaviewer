package ml.melun.mangaview.glide;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import ml.melun.mangaview.mangaview.Manga;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void coldNetworkViewerOpenDoesNotBlockOnFirstDecode() {
        assertFalse(ViewerWarmupManager.shouldDecodeFirstPagesBlockingForTest(false, false));
    }

    @Test
    public void cachedViewerOpenCanUseBlockingDecodeForInstantFirstFrame() {
        assertTrue(ViewerWarmupManager.shouldDecodeFirstPagesBlockingForTest(true, false));
        assertFalse(ViewerWarmupManager.shouldDecodeFirstPagesBlockingForTest(false, true));
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
}
