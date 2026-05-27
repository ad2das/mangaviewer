package ml.melun.mangaview.activity;

import org.junit.Test;

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
        assertEquals(2, ReaderV2Activity.pageGapForBaseModeForTest(MTitle.base_comic));
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
    public void delayedPreviousPrependDoesNotRevealAfterNewInteraction() {
        long boundaryInteraction = 1000L;

        assertTrue(ReaderV2Activity.shouldRevealPrependedBoundaryForTest(
                true,
                ReaderSurfaceView.DIRECTION_PREVIOUS,
                boundaryInteraction,
                boundaryInteraction));
        assertFalse(ReaderV2Activity.shouldRevealPrependedBoundaryForTest(
                true,
                ReaderSurfaceView.DIRECTION_PREVIOUS,
                boundaryInteraction,
                boundaryInteraction + 1L));
        assertFalse(ReaderV2Activity.shouldRevealPrependedBoundaryForTest(
                true,
                ReaderSurfaceView.DIRECTION_NEXT,
                boundaryInteraction,
                boundaryInteraction));
    }

    @Test
    public void displayPolicyCleansEpisodeNamesForPickerLabels() {
        Manga episode = new Manga(7, "Some Title 12화", "", MTitle.base_comic);
        assertEquals("Some Title 12화", ReaderDisplayPolicy.INSTANCE.fastDisplayEpisodeTitle(episode, null));
        assertEquals("Some Title 12화", ReaderDisplayPolicy.INSTANCE.episodeDisplayName(
                episode, java.util.Collections.singletonList(episode), 0, null));
    }
}
