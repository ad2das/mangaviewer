package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MangaEpisodeNavigationTest {
    @Test
    public void nextEp_usesTitleEpisodesWhenCurrentEpisodeHasNoEpisodeList() {
        Title title = titleWithEpisodes(40, 30, 20, 10);
        Manga current = new Manga(30, "30", "", base_webtoon);
        current.setTitle(title);

        assertEquals(40, current.nextEp().getId());
        assertEquals(20, current.prevEp().getId());
    }

    @Test
    public void nextEp_prefersLargerTitleEpisodeListOverPartialEpisodeList() {
        Title title = titleWithEpisodes(50, 40, 30, 20, 10);
        Manga current = new Manga(30, "30", "", base_webtoon);
        current.setTitle(title);

        List<Manga> partial = new ArrayList<>();
        partial.add(new Manga(40, "40", "", base_webtoon));
        partial.add(current);
        partial.add(new Manga(20, "20", "", base_webtoon));
        current.setEps(partial);

        assertEquals(40, current.nextEp().getId());
        assertEquals(20, current.prevEp().getId());
    }

    @Test
    public void nextEp_usesPartialEpisodeListWhenTitleEpisodesDoNotContainCurrentEpisode() {
        Title title = titleWithEpisodes(100, 90, 80, 70);
        Manga current = new Manga(50, "50", "", base_webtoon);
        current.setTitle(title);

        List<Manga> partial = new ArrayList<>();
        partial.add(new Manga(60, "60", "", base_webtoon));
        partial.add(current);
        partial.add(new Manga(40, "40", "", base_webtoon));
        current.setEps(partial);

        assertEquals(60, current.nextEp().getId());
        assertEquals(40, current.prevEp().getId());
    }

    @Test
    public void nextEpAndPrevEp_returnNullAtActualBoundaries() {
        Title title = titleWithEpisodes(30, 20, 10);
        Manga newest = new Manga(30, "30", "", base_webtoon);
        Manga oldest = new Manga(10, "10", "", base_webtoon);
        newest.setTitle(title);
        oldest.setTitle(title);

        assertNull(newest.nextEp());
        assertNull(oldest.prevEp());
    }

    private Title titleWithEpisodes(int... ids) {
        Title title = new Title("title", "", "", new ArrayList<>(), "", 1, base_webtoon);
        List<Manga> episodes = new ArrayList<>();
        for(int id : ids) {
            Manga episode = new Manga(id, String.valueOf(id), "", base_webtoon);
            episode.setTitle(title);
            episodes.add(episode);
        }
        title.setEps(episodes);
        return title;
    }
}
