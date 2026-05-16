package ml.melun.mangaview.model;

import org.junit.Test;

import java.util.Collections;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertNotEquals;

public class PageItemTest {
    @Test
    public void episodeKeySeparatesSitesWithSameNumericIds() {
        Manga ntk = manga("ntk", "/manhwa/8605/70947");
        Manga wolf = manga("wfwf", "/cv?toon=8605&num=1");

        assertNotEquals(PageItem.episodeKey(ntk), PageItem.episodeKey(wolf));
    }

    private static Manga manga(String sourceSite, String path) {
        Title title = new Title("same", "", "", Collections.emptyList(), "", 8605, MTitle.base_comic);
        title.setSourceSite(sourceSite);
        title.setPath(path);
        Manga manga = new Manga(1, "episode", "", MTitle.base_comic);
        manga.setTitle(title);
        manga.setTitleId(title.getId());
        if("ntk".equals(sourceSite))
            manga.setNtkEpisodePath(path);
        return manga;
    }
}
