package ml.melun.mangaview.repository;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;

import static org.junit.Assert.assertEquals;

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
}
