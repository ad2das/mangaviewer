package ml.melun.mangaview.activity;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.reader.ReaderSurfaceView;
import ml.melun.mangaview.reader.ReaderSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ReaderV2ActivityTest {
    @Test
    public void shortWebtoonReverseHintRequiresBusyPhysicalMotionBeyondNoise() {
        assertEquals(0, ReaderV2Activity.directWifiShortWebtoonDirectionHintForTest(
                true, 100, 99));
        assertEquals(ReaderSurfaceView.DIRECTION_PREVIOUS,
                ReaderV2Activity.directWifiShortWebtoonDirectionHintForTest(
                        true, 100, 98));
        assertEquals(0, ReaderV2Activity.directWifiShortWebtoonDirectionHintForTest(
                false, 100, 90));
        assertEquals(0, ReaderV2Activity.directWifiShortWebtoonDirectionHintForTest(
                true, 100, 101));
        assertEquals(0, ReaderV2Activity.directWifiShortWebtoonDirectionHintForTest(
                true, Integer.MIN_VALUE, 90));
    }

    @Test
    public void malformedOrMissingReaderPayloadFailsClosedWithoutThrowing() {
        assertFalse(ReaderV2Activity.hasValidReaderMangaExtraForTest(null));
        assertFalse(ReaderV2Activity.hasValidReaderMangaExtraForTest(""));
        assertFalse(ReaderV2Activity.hasValidReaderMangaExtraForTest("{not-json"));
        assertTrue(ReaderV2Activity.hasValidReaderMangaExtraForTest(
                "{\"id\":7,\"name\":\"episode\",\"baseMode\":1}"));
    }

    @Test
    public void pageGapForBaseMode_removesWebtoonSeparator() {
        assertEquals(0, ReaderV2Activity.pageGapForBaseModeForTest(MTitle.base_webtoon));
        assertEquals(0, ReaderV2Activity.pageGapForBaseModeForTest(MTitle.base_comic));
    }

    @Test
    public void adjacentButton_staysEnabledWhenMissingEpisodeCanBeFetched() {
        assertTrue(ReaderV2Activity.shouldEnableAdjacentButtonForTest(false, true));
        assertTrue(ReaderV2Activity.shouldEnableAdjacentButtonForTest(true, false));
        assertFalse(ReaderV2Activity.shouldEnableAdjacentButtonForTest(false, false));
    }

    @Test
    public void firstDrawableMetricRequiresCurrentVisiblePage() {
        assertTrue(ReaderV2Activity.shouldMarkFirstDrawableForTest(0, 0));
        assertTrue(ReaderV2Activity.shouldMarkFirstDrawableForTest(5, 5));
        assertFalse(ReaderV2Activity.shouldMarkFirstDrawableForTest(5, 0));
        assertFalse(ReaderV2Activity.shouldMarkFirstDrawableForTest(1, 0));
    }

    @Test
    public void delayedPreviousPrependRevealUsesStartedPrependRequest() {
        assertTrue(ReaderV2Activity.shouldRevealPrependedBoundaryForTest(
                1,
                11));
        assertFalse(ReaderV2Activity.shouldRevealPrependedBoundaryForTest(
                0,
                11));
        assertFalse(ReaderV2Activity.shouldRevealPrependedBoundaryForTest(
                1,
                0));
    }

    @Test
    public void previousNearBoundaryDoesNotPrepareAutomatically() {
        assertFalse(ReaderV2Activity.shouldPrepareNearBoundaryForTest(
                ReaderSurfaceView.DIRECTION_PREVIOUS));
        assertTrue(ReaderV2Activity.shouldPrepareNearBoundaryForTest(
                ReaderSurfaceView.DIRECTION_NEXT));
    }

    @Test
    public void busyNextBoundaryIsRetriedWithoutWaitingForAnotherEdgeGesture() {
        assertTrue(ReaderV2Activity.shouldRetryBusyBoundaryAppendForTest(
                ReaderSurfaceView.DIRECTION_NEXT,
                ReaderSession.AppendStartResult.BUSY));
        assertFalse(ReaderV2Activity.shouldRetryBusyBoundaryAppendForTest(
                ReaderSurfaceView.DIRECTION_PREVIOUS,
                ReaderSession.AppendStartResult.BUSY));
        assertFalse(ReaderV2Activity.shouldRetryBusyBoundaryAppendForTest(
                ReaderSurfaceView.DIRECTION_NEXT,
                ReaderSession.AppendStartResult.STARTED));
    }

    @Test
    public void attachedPagesConsumeBusyRetryWithoutAutoRequestingTheFollowingEpisode() {
        assertTrue(ReaderV2Activity.shouldCompleteNextBoundaryGrowthForTest(
                12, 10, true, 0));
        assertTrue(ReaderV2Activity.shouldCompleteNextBoundaryGrowthForTest(
                12, 10, false, ReaderSurfaceView.DIRECTION_NEXT));
        assertFalse(ReaderV2Activity.shouldCompleteNextBoundaryGrowthForTest(
                10, 10, true, ReaderSurfaceView.DIRECTION_NEXT));
        assertFalse(ReaderV2Activity.shouldCompleteNextBoundaryGrowthForTest(
                12, 10, false, ReaderSurfaceView.DIRECTION_PREVIOUS));
    }

    @Test
    public void completedNtkBoundaryRevealsOnlyForAReaderParkedAtTheOldTail() {
        assertTrue(ReaderV2Activity.shouldRevealCompletedNtkBoundaryGrowthForTest(
                true, true, 14, 15));
        assertTrue(ReaderV2Activity.shouldRevealCompletedNtkBoundaryGrowthForTest(
                true, true, 15, 15));
        assertFalse(ReaderV2Activity.shouldRevealCompletedNtkBoundaryGrowthForTest(
                false, true, 14, 15));
        assertFalse(ReaderV2Activity.shouldRevealCompletedNtkBoundaryGrowthForTest(
                true, false, 14, 15));
        assertFalse(ReaderV2Activity.shouldRevealCompletedNtkBoundaryGrowthForTest(
                true, true, 13, 15));
        assertFalse(ReaderV2Activity.shouldRevealCompletedNtkBoundaryGrowthForTest(
                true, true, 0, 0));
    }

    @Test
    public void newReaderReusesClickGenerationOnlyBeforeAnyActivityClaimsIt() {
        assertTrue(ReaderV2Activity.shouldReuseStrictTelemetryForActivityCreateForTest(
                true, true, false));

        assertFalse(ReaderV2Activity.shouldReuseStrictTelemetryForActivityCreateForTest(
                true, true, true));
        assertFalse(ReaderV2Activity.shouldReuseStrictTelemetryForActivityCreateForTest(
                false, true, false));
        assertFalse(ReaderV2Activity.shouldReuseStrictTelemetryForActivityCreateForTest(
                true, false, false));

        assertTrue(ReaderV2Activity.strictActivityOwnerOverlapsForTest(
                42L, "/webtoon/12868/1348822", 42L, "/webtoon/12868/1348822"));
        assertFalse(ReaderV2Activity.strictActivityOwnerOverlapsForTest(
                43L, "/webtoon/12868/1348822", 42L, "/webtoon/12868/1348822"));
        assertFalse(ReaderV2Activity.strictActivityOwnerOverlapsForTest(
                42L, "/webtoon/12868/1348822", 42L, "/webtoon/12868/other"));
    }

    @Test
    public void surfaceOwnsRestoreForEveryDirectWifiStrictEpisode() {
        assertTrue(ReaderV2Activity.shouldLetSurfaceOwnDirectWifiStrictEpisodeRestoreForTest(
                "/webtoon/12868/1348822", true, false));
        assertTrue(ReaderV2Activity.shouldLetSurfaceOwnDirectWifiStrictEpisodeRestoreForTest(
                "/manhwa/12868/1348822", true, false));
        assertFalse(ReaderV2Activity.shouldLetSurfaceOwnDirectWifiStrictEpisodeRestoreForTest(
                "/webtoon/12868/1348822", true, true));
        assertFalse(ReaderV2Activity.shouldLetSurfaceOwnDirectWifiStrictEpisodeRestoreForTest(
                "/webtoon/12868/1348822", false, false));
        assertFalse(ReaderV2Activity.shouldLetSurfaceOwnDirectWifiStrictEpisodeRestoreForTest(
                "/legacy/12868/1348822", true, false));
    }

    @Test
    public void persistedNextSnapshotRestoresOnlyAfterExactDirectWifiCompletion() {
        Title title = new Title("target", "", "", null, "", 8391, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setResumeNtkEpisodePath("/manhwa/8391/66773");
        title.setResumeNtkNextEpisodeIdentity(
                "/manhwa/8391/66779", 66779, "next", "8391", "66779", 4);

        Manga restored = ReaderV2Activity.persistedDirectWifiStrictNextSnapshotForTest(
                title, "/manhwa/8391/66773", true, false, true);

        assertNotNull(restored);
        assertEquals("/manhwa/8391/66779", restored.getNtkEpisodePath());
        assertEquals("8391", restored.getNtkImageWorkId());
        assertEquals("66779", restored.getNtkImageEpisodeId());
        assertEquals(4, restored.getNtkImageCount());
        assertNull(ReaderV2Activity.persistedDirectWifiStrictNextSnapshotForTest(
                title, "/manhwa/8391/66773", true, false, false));
        assertNull(ReaderV2Activity.persistedDirectWifiStrictNextSnapshotForTest(
                title, "/manhwa/8391/other", true, false, true));
        assertNull(ReaderV2Activity.persistedDirectWifiStrictNextSnapshotForTest(
                title, "/manhwa/8391/66773", false, false, true));
        assertNull(ReaderV2Activity.persistedDirectWifiStrictNextSnapshotForTest(
                title, "/manhwa/8391/66773", true, true, true));
    }

    @Test
    public void resumeNextIdentitySnapshotFreezesOneCompleteTuple() {
        Title title = new Title("target", "", "", null, "", 8391, MTitle.base_webtoon);
        title.setResumeNtkNextEpisodeIdentity(
                "/webtoon/8391/66779", 66779, "next", "8391", "66779", 4);

        MTitle.ResumeNtkNextEpisodeIdentitySnapshot snapshot =
                title.snapshotResumeNtkNextEpisodeIdentity();
        title.clearResumeNtkNextEpisodeIdentity();

        assertTrue(snapshot.isComplete());
        assertEquals("/webtoon/8391/66779", snapshot.path);
        assertEquals(66779, snapshot.id);
        assertEquals("8391", snapshot.workId);
        assertEquals("66779", snapshot.episodeId);
        assertEquals(4, snapshot.imageCount);
        assertFalse(title.snapshotResumeNtkNextEpisodeIdentity().isComplete());
    }

    @Test
    public void progressEpisodeIndexMatchesNtkPathWhenEpisodeIdDiffers() {
        Manga first = new Manga(101, "Episode 10", "", MTitle.base_webtoon);
        first.setNtkEpisodePath("/webtoon/1/10");
        Manga second = new Manga(102, "Episode 9", "", MTitle.base_webtoon);
        second.setNtkEpisodePath("/webtoon/1/9");
        Manga selected = new Manga(999, "Episode 9", "", MTitle.base_webtoon);
        selected.setNtkEpisodePath("/webtoon/1/9");

        assertEquals(2, ReaderV2Activity.progressEpisodeIndexForTest(
                Arrays.asList(first, second), selected, -1));
    }

    @Test
    public void progressEpisodeIdUsesCanonicalEpisodeWhenPathMatches() {
        Manga first = new Manga(101, "Episode 10", "", MTitle.base_webtoon);
        first.setNtkEpisodePath("/webtoon/1/10");
        Manga second = new Manga(102, "Episode 9", "", MTitle.base_webtoon);
        second.setNtkEpisodePath("/webtoon/1/9");
        Manga selected = new Manga(999, "Episode 9", "", MTitle.base_webtoon);
        selected.setNtkEpisodePath("/webtoon/1/9");

        assertEquals(102, ReaderV2Activity.progressEpisodeIdForTest(
                Arrays.asList(first, second), selected, -1));
    }

    @Test
    public void progressEpisodeIndexMatchesVisibleNumberWhenEpisodeIdDiffers() {
        Manga first = new Manga(101, "Episode 10", "", MTitle.base_webtoon);
        Manga second = new Manga(102, "Episode 9", "", MTitle.base_webtoon);
        Manga selected = new Manga(999, "Read Episode 9", "", MTitle.base_webtoon);

        assertEquals(2, ReaderV2Activity.progressEpisodeIndexForTest(
                Arrays.asList(first, second), selected, -1));
    }

    @Test
    public void progressEpisodeIdUsesCanonicalEpisodeWhenVisibleNumberMatches() {
        Manga first = new Manga(101, "Episode 10", "", MTitle.base_webtoon);
        Manga second = new Manga(102, "Episode 9", "", MTitle.base_webtoon);
        Manga selected = new Manga(999, "Read Episode 9", "", MTitle.base_webtoon);

        assertEquals(102, ReaderV2Activity.progressEpisodeIdForTest(
                Arrays.asList(first, second), selected, -1));
    }

    @Test
    public void visitedEpisodeRestoresAnySavedPageOffsetOrSplitSide() {
        assertTrue(ReaderV2Activity.shouldStartAtFirstPageForBookmarkForTest(0, 0, 0));
        assertFalse(ReaderV2Activity.shouldStartAtFirstPageForBookmarkForTest(7, 0, 0));
        assertFalse(ReaderV2Activity.shouldStartAtFirstPageForBookmarkForTest(0, -420, 0));
        assertFalse(ReaderV2Activity.shouldStartAtFirstPageForBookmarkForTest(0, 0, 1));
    }

    @Test
    public void initialRestoreKeepsNegativeOffsetWithinFirstPage() {
        assertTrue(ReaderV2Activity.needsInitialRestorePositionForTest(0, -420));
        assertFalse(ReaderV2Activity.needsInitialRestorePositionForTest(0, 0));
    }

    @Test
    public void incompleteOnlineEpisodePickerRefreshesBeforeShowingList() {
        assertTrue(ReaderV2Activity.shouldRefreshEpisodePickerListForTest(
                true, 1, 42, true));
        assertTrue(ReaderV2Activity.shouldRefreshEpisodePickerListForTest(
                true, 12, 42, true));
        assertTrue(ReaderV2Activity.shouldRefreshEpisodePickerListForTest(
                true, 12, 0, true));
        assertFalse(ReaderV2Activity.shouldRefreshEpisodePickerListForTest(
                true, 42, 42, true));
        assertFalse(ReaderV2Activity.shouldRefreshEpisodePickerListForTest(
                false, 1, 42, true));
    }

    @Test
    public void episodePickerMergeNeverDropsPreviouslyKnownEpisodes() {
        Manga newest = new Manga(30, "30화", "", MTitle.base_comic);
        newest.setNtkEpisodePath("/manhwa/7/300");
        Manga middle = new Manga(20, "20화", "", MTitle.base_comic);
        middle.setNtkEpisodePath("/manhwa/7/200");
        Manga oldest = new Manga(10, "10화", "", MTitle.base_comic);
        oldest.setNtkEpisodePath("/manhwa/7/100");

        List<Manga> merged = ReaderV2Activity.mergeEpisodeSnapshotsForTest(
                Arrays.asList(newest, middle),
                Arrays.asList(middle, oldest));

        assertEquals(3, merged.size());
        assertTrue(merged.stream().anyMatch(
                episode -> "/manhwa/7/300".equals(episode.getNtkEpisodePath())));
        assertTrue(merged.stream().anyMatch(
                episode -> "/manhwa/7/200".equals(episode.getNtkEpisodePath())));
        assertTrue(merged.stream().anyMatch(
                episode -> "/manhwa/7/100".equals(episode.getNtkEpisodePath())));
    }

    @Test
    public void continuousStrictDiscoveryKeepsLaunchTelemetryOwnerAcrossMultipleEpisodes() {
        String launch = "/manhwa/work/episode-10";
        String current = "/manhwa/work/episode-11";
        String target = "/manhwa/work/episode-12";

        assertEquals(
                launch,
                ReaderV2Activity.resolveStrictDiscoveryOwnerPathForTest(
                        target, launch, current, true));
        assertEquals(
                current,
                ReaderV2Activity.resolveStrictDiscoveryOwnerPathForTest(
                        target, launch, current, false));
    }

    @Test
    public void transitionCardHeightIsOnlySlightlyLargerThanText() {
        assertEquals(168f, ReaderSurfaceView.transitionCardPageHeightForTest(), 0.001f);
    }

    @Test
    public void activeReverseDefersOnlyAForeignEpisodeTailAppend() {
        assertTrue(ReaderV2Activity.shouldDeferForeignEpisodeAppendForActiveInputForTest(
                false, true, 0L));
        assertTrue(ReaderV2Activity.shouldDeferForeignEpisodeAppendForActiveInputForTest(
                false, false, 120L));
        assertFalse(ReaderV2Activity.shouldDeferForeignEpisodeAppendForActiveInputForTest(
                true, true, 120L));
        assertFalse(ReaderV2Activity.shouldDeferForeignEpisodeAppendForActiveInputForTest(
                false, false, 0L));
        assertFalse("Forward input at the current tail must publish an already-ready next runway",
                ReaderV2Activity.shouldDeferForeignEpisodeAppendForActiveInputForTest(
                        false, true, 2_600L, true));
    }

    @Test
    public void displayPolicyCleansEpisodeNamesForPickerLabels() {
        Manga episode = new Manga(7, "Some Title 12화", "", MTitle.base_comic);
        assertEquals("Some Title 12화", ReaderDisplayPolicy.INSTANCE.fastDisplayEpisodeTitle(episode, null));
        assertEquals("Some Title 12화", ReaderDisplayPolicy.INSTANCE.episodeDisplayName(
                episode, java.util.Collections.singletonList(episode), 0, null));
    }
}
