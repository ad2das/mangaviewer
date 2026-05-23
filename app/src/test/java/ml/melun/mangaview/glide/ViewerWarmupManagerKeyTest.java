package ml.melun.mangaview.glide;

import org.junit.Test;

import java.util.Collections;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ViewerWarmupManagerKeyTest {
    @Test
    public void snapshotKeySeparatesSitesWithSameNumericIds() {
        Manga ntk = manga("ntk", "/manhwa/8605/70947");
        Manga wolf = manga("wfwf", "/cv?toon=8605&num=1");

        assertNotEquals(
                ViewerWarmupManager.episodeKeyForTest(ntk, ntk.getTitle()),
                ViewerWarmupManager.episodeKeyForTest(wolf, wolf.getTitle()));
    }

    @Test
    public void continueKeySeparatesNtkEpisodePathsWithSameNumericIds() {
        Manga first = manga("ntk", "/manhwa/8605/first-path");
        Manga second = manga("ntk", "/manhwa/8605/second-path");

        assertNotEquals(
                ViewerWarmupManager.continueWarmupKeyForTest(first, first.getTitle(), 0),
                ViewerWarmupManager.continueWarmupKeyForTest(second, second.getTitle(), 0));
    }

    @Test
    public void pathlessContinueKeyMatchesMinimalNtkResume() {
        Manga loaded = manga("ntk", "/manhwa/8605/first-path");
        Manga minimalResume = manga("ntk", "");

        assertNotEquals(
                ViewerWarmupManager.continueWarmupKeyForTest(loaded, loaded.getTitle(), 0),
                ViewerWarmupManager.pathlessContinueWarmupKeyForTest(loaded, loaded.getTitle(), 0));
        assertEquals(
                ViewerWarmupManager.pathlessContinueWarmupKeyForTest(loaded, loaded.getTitle(), 0),
                ViewerWarmupManager.continueWarmupKeyForTest(minimalResume, minimalResume.getTitle(), 0));
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
