package ml.melun.mangaview.repository;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MangaRepositoryTest {
    @Test
    public void imageUrlsDropMissingImages() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic) {
            @Override
            public synchronized List<String> getImgs(android.content.Context context) {
                return Arrays.asList("a.jpg", null, "", "   ", "b.jpg");
            }
        };

        assertEquals(Arrays.asList("a.jpg", "b.jpg"), MangaRepository.imageUrls(manga, null));
    }

    @Test
    public void imageUrlsReturnEmptyListForMissingManga() {
        assertEquals(0, MangaRepository.imageUrls(null, null).size());
    }

    @Test
    public void viewerFetchSnapshotDoesNotShareImageList() {
        Manga source = new Manga(12, "episode", "today", MTitle.base_comic);
        source.setTitleId(99);
        source.setImgs(new java.util.ArrayList<>(Arrays.asList("a.jpg", "b.jpg")));

        Manga snapshot = MangaRepository.snapshotViewerMangaForTest(source);
        source.getImgs(null).clear();

        assertEquals(Arrays.asList("a.jpg", "b.jpg"), snapshot.getImgs(null));
        assertEquals(0, MangaRepository.imageUrls(source, null).size());
    }

    @Test
    public void cacheFreshnessRejectsExpiredAndFutureEntries() {
        long now = 10_000L;
        long ttl = 1_000L;

        assertTrue(MangaRepository.isCacheFreshForTest(now - 999L, now, ttl));
        assertFalse(MangaRepository.isCacheFreshForTest(now - 1001L, now, ttl));
        assertFalse(MangaRepository.isCacheFreshForTest(now + 1L, now, ttl));
    }
}
