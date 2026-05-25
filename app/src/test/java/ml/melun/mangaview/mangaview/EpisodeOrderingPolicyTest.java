package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;

public class EpisodeOrderingPolicyTest {
    @Test
    public void visibleEpisodeNumberIgnoresSpecialEpisodes() {
        assertEquals(-1.0, EpisodeOrderingPolicy.visibleEpisodeNumber("작품 번외편 3화"), 0.0001);
        assertEquals(-1.0, EpisodeOrderingPolicy.visibleEpisodeNumber("작품 프롤로그 1화"), 0.0001);
    }

    @Test
    public void hyphenPartEpisodeSortsWithinParentEpisode() {
        assertEquals(11.0002, EpisodeOrderingPolicy.visibleEpisodeNumber("마왕의 딸 11-2화"), 0.0001);
        assertEquals(12.0, EpisodeOrderingPolicy.visibleEpisodeNumber("마왕의 딸 12화"), 0.0001);
    }

    @Test
    public void episodeRangesUseHighestVisibleNumber() {
        assertEquals(6.0, EpisodeOrderingPolicy.visibleEpisodeNumber("작품 05, 06화"), 0.0001);
        assertEquals(7.0, EpisodeOrderingPolicy.visibleEpisodeNumber("작품 3~7화"), 0.0001);
    }

    @Test
    public void sortingOnlyReordersContiguousNumberedBlocks() {
        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(new Manga(1, "작품 1화", "", 0));
        episodes.add(new Manga(2, "작품 3화", "", 0));
        episodes.add(new Manga(3, "작품 2화", "", 0));
        episodes.add(new Manga(4, "작품 번외편", "", 0));
        episodes.add(new Manga(5, "작품 4화", "", 0));

        EpisodeOrderingPolicy.sortByVisibleEpisodeNumber(episodes);

        assertEquals("작품 3화", episodes.get(0).getName());
        assertEquals("작품 2화", episodes.get(1).getName());
        assertEquals("작품 1화", episodes.get(2).getName());
        assertEquals("작품 번외편", episodes.get(3).getName());
        assertEquals("작품 4화", episodes.get(4).getName());
    }
}
