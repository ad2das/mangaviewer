package ml.melun.mangaview.activity;

import org.junit.Test;

import ml.melun.mangaview.mangaview.MTitle;

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
}
