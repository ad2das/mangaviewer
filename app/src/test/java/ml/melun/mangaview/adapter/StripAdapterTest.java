package ml.melun.mangaview.adapter;

import org.junit.Test;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.model.PageItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StripAdapterTest {
    @Test
    public void imagePageRejectsMissingPageData() {
        assertFalse(StripAdapter.isAttachableImagePageForTest(null));
        assertFalse(StripAdapter.isAttachableImagePageForTest(new PageItem(0, "img", null)));
    }

    @Test
    public void imagePageAcceptsCompletePageData() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);

        assertTrue(StripAdapter.isAttachableImagePageForTest(new PageItem(0, "img", manga)));
    }

    @Test
    public void imageModelFallsBackToRawImageWithoutManga() {
        assertEquals("", StripAdapter.imageModelForTest(null));
        assertEquals("img", StripAdapter.imageModelForTest(new PageItem(0, "img", null)));
    }

    @Test
    public void imageModelRejectsBlankImageWithManga() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);

        assertEquals("", StripAdapter.imageModelForTest(new PageItem(0, "   ", manga)));
    }

    @Test
    public void displayedBitmapCacheRequiresValidKeyAndBitmap() {
        assertFalse(StripAdapter.shouldCacheDisplayedBitmapForTest("", true, true));
        assertFalse(StripAdapter.shouldCacheDisplayedBitmapForTest("page", false, true));
        assertFalse(StripAdapter.shouldCacheDisplayedBitmapForTest("page", true, false));
        assertTrue(StripAdapter.shouldCacheDisplayedBitmapForTest("page", true, true));
    }

    @Test
    public void transientImageFailuresRetryTwiceOnlyForActivePages() {
        assertTrue(StripAdapter.shouldRetryImageLoadForTest(false, "page", 0));
        assertTrue(StripAdapter.shouldRetryImageLoadForTest(false, "page", 1));
        assertFalse(StripAdapter.shouldRetryImageLoadForTest(false, "page", 2));
        assertFalse(StripAdapter.shouldRetryImageLoadForTest(true, "page", 0));
        assertFalse(StripAdapter.shouldRetryImageLoadForTest(false, "", 0));
        assertEquals(350L, StripAdapter.imageRetryDelayMsForTest(1));
        assertEquals(900L, StripAdapter.imageRetryDelayMsForTest(2));
    }

    @Test
    public void autoCutSecondPageDoesNotReserveFullHeightBeforeDecode() {
        assertEquals(1, StripAdapter.estimatedPageHeightForTest(true, PageItem.SECOND, 1000, 3000L, 2));
        assertEquals(1500, StripAdapter.estimatedPageHeightForTest(true, PageItem.FIRST, 1000, 3000L, 2));
        assertEquals(1500, StripAdapter.estimatedPageHeightForTest(false, PageItem.SECOND, 1000, 3000L, 2));
    }
}
