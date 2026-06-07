package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TitleAdapterTest {
    @Test
    public void validTitlePositionRejectsOutOfRangeRows() {
        assertFalse(TitleAdapter.isValidTitlePositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(TitleAdapter.isValidTitlePositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validTitlePositionRejectsMissingData() {
        assertFalse(TitleAdapter.isValidTitlePositionForTest(null, 0));
        assertFalse(TitleAdapter.isValidTitlePositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validTitlePositionAcceptsExistingRow() {
        assertTrue(TitleAdapter.isValidTitlePositionForTest(Arrays.asList("a", "b"), 1));
    }

    @Test
    public void ntkWebtoonProgressDisplaysWhenLoadedCountExceedsReleaseCount() {
        Title title = new Title("Long Webtoon", "", "", null, "5", 36716, MTitle.base_webtoon);
        title.setSourceSite("ntk");
        title.setReadingProgress(210, 18, 120);

        assertEquals(103, TitleAdapter.watchedEpisodeCountForTest(title));
        assertTrue(TitleAdapter.readingProgressPercentForTest(title) > 0);
    }
}
