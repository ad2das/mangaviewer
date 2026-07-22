package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpisodeAdapterTest {
    @Test
    public void validEpisodePositionRejectsHeaderAndOutOfRangeRows() {
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 0));
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 3));
    }

    @Test
    public void validEpisodePositionRejectsMissingData() {
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(null, 1));
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Collections.emptyList(), 1));
    }

    @Test
    public void validEpisodePositionAcceptsExistingEpisodeRow() {
        assertTrue(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void deferredThumbnailClearSkipsRowAndPlaceholderStates() {
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("loaded-key", false));
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("pending:loaded-key", true));
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("placeholder", true));
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("empty", true));
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest(null, true));
    }

    @Test
    public void deferredThumbnailClearKeepsHeaderReplacementClean() {
        assertTrue(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("loaded-key", true));
    }

    @Test
    public void ntkDisplayReleaseUsesVisibleLatestEpisodeNameWhenApiNumberIsInternal() {
        List<Manga> episodes = Collections.singletonList(new Manga(1293, "1183화", "26.05.22", MTitle.base_comic));

        assertEquals("1183화", EpisodeAdapter.displayReleaseForNtkForTest("ntk", "1293화", episodes));
    }

    @Test
    public void confirmedEmptyEpisodeOverviewExplainsZeroNtkEpisodes() {
        String overview = EpisodeAdapter.confirmedEmptyEpisodeOverviewForTest();

        assertTrue(overview.contains("\uD68C\uCC28\uAC00 \uC544\uC9C1 \uC5C6\uC2B5\uB2C8\uB2E4"));
        assertTrue(overview.contains("NTK"));
        assertTrue(overview.contains("0\uAC1C"));
    }

    @Test
    public void everyValidNtkEpisodePathIsImmediatelyPressEligible() {
        assertTrue(EpisodeAdapter.isValidNtkExactEpisodePathForTest(
                "/manhwa/25883/309911"));
        assertTrue(EpisodeAdapter.isValidNtkExactEpisodePathForTest(
                "/webtoon/123/kp-random-slug"));
        assertTrue(EpisodeAdapter.isValidNtkExactEpisodePathForTest(
                "/WEBTOON/work/episode"));
    }

    @Test
    public void invalidOrNonEpisodeNtkPathsStayDisabled() {
        assertFalse(EpisodeAdapter.isValidNtkExactEpisodePathForTest(null));
        assertFalse(EpisodeAdapter.isValidNtkExactEpisodePathForTest(""));
        assertFalse(EpisodeAdapter.isValidNtkExactEpisodePathForTest("/manhwa/25883"));
        assertFalse(EpisodeAdapter.isValidNtkExactEpisodePathForTest(
                "/manhwa/25883/309911/extra"));
        assertFalse(EpisodeAdapter.isValidNtkExactEpisodePathForTest(
                "/other/25883/309911"));
    }
}
