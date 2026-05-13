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
}
