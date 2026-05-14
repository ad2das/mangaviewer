package ml.melun.mangaview.glide;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

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
}
