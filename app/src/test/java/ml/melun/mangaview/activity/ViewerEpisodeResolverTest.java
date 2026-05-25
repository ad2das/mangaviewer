package ml.melun.mangaview.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

public class ViewerEpisodeResolverTest {
    @Test
    public void resolverDoesNotUseStaleViewerEpisodesAfterTitleSwitch() {
        Title oldTitle = title(1, "wfwf");
        List<Manga> staleEpisodes = episodes(oldTitle, 100);
        Title newTitle = title(2, "ntk");
        List<Manga> newEpisodes = episodes(newTitle, 3);
        newTitle.setEps(newEpisodes);
        Manga current = newEpisodes.get(1);

        List<Manga> resolved = ViewerEpisodeResolver.episodeListFor(current, staleEpisodes, newTitle);

        assertEquals(newEpisodes.size(), resolved.size());
        assertTrue(resolved.contains(current));
        assertFalse(resolved.contains(staleEpisodes.get(0)));
    }

    @Test
    public void nextCandidateUsesCurrentTitleEpisodesWhenViewerEpisodesAreStale() {
        Title oldTitle = title(1, "wfwf");
        List<Manga> staleEpisodes = episodes(oldTitle, 100);
        Title newTitle = title(2, "ntk");
        List<Manga> newEpisodes = episodes(newTitle, 3);
        newTitle.setEps(newEpisodes);
        Manga current = newEpisodes.get(1);

        Manga next = ViewerEpisodeResolver.nextCandidate(current, staleEpisodes, newTitle, Manga::sameEpisodeIdentity);

        assertEquals(newEpisodes.get(0), next);
    }

    private static Title title(int id, String source) {
        Title title = new Title("작품" + id, "", "", new ArrayList<>(), "", id, MTitle.base_comic);
        title.setSourceSite(source);
        return title;
    }

    private static List<Manga> episodes(Title title, int count) {
        ArrayList<Manga> episodes = new ArrayList<>();
        for(int i = count; i >= 1; i--) {
            Manga episode = new Manga(i, title.getName() + " " + i + "화", "", MTitle.base_comic);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episodes.add(episode);
        }
        for(Manga episode : episodes)
            episode.setEps(episodes);
        title.setEps(episodes);
        return episodes;
    }
}
