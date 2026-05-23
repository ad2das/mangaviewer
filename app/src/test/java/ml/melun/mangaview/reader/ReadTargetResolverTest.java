package ml.melun.mangaview.reader;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ReadTargetResolverTest {
    @Test
    public void likelyTargetUsesBookmarkWhenPresent() {
        List<Manga> episodes = episodes(3);

        assertSame(episodes.get(1), ReadTargetResolver.likelyReadTarget(episodes, 2));
    }

    @Test
    public void likelyTargetFallsBackToFirstReadableEpisode() {
        List<Manga> episodes = episodes(3);

        assertSame(episodes.get(2), ReadTargetResolver.likelyReadTarget(episodes, -1));
        assertEquals(2, ReadTargetResolver.firstReadableEpisodeIndex(episodes));
    }

    private static List<Manga> episodes(int count) {
        ArrayList<Manga> episodes = new ArrayList<>();
        for(int i = 0; i < count; i++)
            episodes.add(new Manga(i + 1, (i + 1) + "화", "", base_comic));
        return episodes;
    }
}
