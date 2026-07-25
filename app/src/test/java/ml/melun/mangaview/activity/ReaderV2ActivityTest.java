package ml.melun.mangaview.activity;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.reader.ReaderSurfaceView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderV2ActivityTest {
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
    public void transitionCardHeightIsOnlySlightlyLargerThanText() {
        assertEquals(168f, ReaderSurfaceView.transitionCardPageHeightForTest(), 0.001f);
    }

    @Test
    public void displayPolicyCleansEpisodeNamesForPickerLabels() {
        Manga episode = new Manga(7, "Some Title 12화", "", MTitle.base_comic);
        assertEquals("Some Title 12화", ReaderDisplayPolicy.INSTANCE.fastDisplayEpisodeTitle(episode, null));
        assertEquals("Some Title 12화", ReaderDisplayPolicy.INSTANCE.episodeDisplayName(
                episode, java.util.Collections.singletonList(episode), 0, null));
    }
}
