package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MangaTest {
    @Test
    public void safeUrlReturnsNullForMissingManga() {
        assertNull(Manga.safeUrl(null));
    }

    @Test
    public void safeUrlReturnsNullWhenUrlAccessFails() {
        Manga manga = new Manga(1, "episode", "", MTitle.base_comic) {
            @Override
            public String getUrl() {
                throw new RuntimeException("url unavailable");
            }
        };

        assertNull(Manga.safeUrl(manga));
    }

    @Test
    public void safeUrlReturnsMangaUrl() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);

        assertEquals("/comic/12", Manga.safeUrl(manga));
    }

    @Test
    public void offlineImagesReturnEmptyListWithoutPath() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);
        manga.setMode(1);

        assertTrue(manga.getImgs(null).isEmpty());
    }

    @Test
    public void scaledDimensionNeverDropsBelowOnePixel() {
        assertEquals(1, Decoder.scaledDimensionForTest(1, 0.01f));
        assertEquals(50, Decoder.scaledDimensionForTest(100, 0.5f));
    }

    @Test
    public void decodeGridUsesAtLeastOnePixelCells() {
        assertEquals(1, Decoder.gridCellSizeForTest(3, 5));
        assertEquals(20, Decoder.gridCellSizeForTest(100, 5));
    }
}
