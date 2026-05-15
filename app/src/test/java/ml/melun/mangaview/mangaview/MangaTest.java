package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

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
    public void offlineImagesSkipDownloadArtifacts() throws Exception {
        File dir = Files.createTempDirectory("offline-images").toFile();
        File image = new File(dir, "0001.jpg");
        File part = new File(dir, "0002.jpg.part");
        File flag = new File(dir, "downloading");
        File text = new File(dir, "note.txt");
        try {
            image.createNewFile();
            part.createNewFile();
            flag.createNewFile();
            text.createNewFile();

            Manga manga = new Manga(12, "episode", "", MTitle.base_comic);
            manga.setMode(1);
            manga.setOfflinePath(dir.getAbsolutePath());

            List<String> images = manga.getImgs(null);

            assertEquals(1, images.size());
            assertEquals(image.getAbsolutePath(), images.get(0));
        } finally {
            image.delete();
            part.delete();
            flag.delete();
            text.delete();
            dir.delete();
        }
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

    @Test
    public void ntkPageImagesSkipBoardUploadAds() {
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/blacktoon/episodes/1/12712/p001.jpg\">"));

        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/board_uploads/2026/05/15/ad.png\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/blacktoon/thumbs/15741.png?v2\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<div class=\"banner\"><img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\"></div>"));
    }
}
