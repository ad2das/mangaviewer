package ml.melun.mangaview.runtime;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;

import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;

public class PrefetchCoordinatorTest {
    @Test
    public void viewerTargetsFavorResumeAndNextEpisodesOnly() throws Exception {
        List<Manga> episodes = episodes(60, 50, 40, 30, 20, 10);
        Method method = PrefetchCoordinator.class.getDeclaredMethod("viewerTargets", List.class, int.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Integer> targets = (List<Integer>) method.invoke(null, episodes, 3, 4);

        assertEquals(asList(2, 1, 0), targets);
    }

    @Test
    public void viewerTargetsSkipMissingEpisodes() throws Exception {
        List<Manga> episodes = episodes(60, 50, 40, 30, 20, 10);
        episodes.set(1, null);
        Method method = PrefetchCoordinator.class.getDeclaredMethod("viewerTargets", List.class, int.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Integer> targets = (List<Integer>) method.invoke(null, episodes, 3, 4);

        assertEquals(asList(2, 0), targets);
    }

    private static List<Manga> episodes(int... ids) {
        ArrayList<Manga> episodes = new ArrayList<>();
        for(int id : ids)
            episodes.add(new Manga(id, String.valueOf(id), "", base_webtoon));
        return episodes;
    }

    private static List<Integer> asList(int... values) {
        ArrayList<Integer> list = new ArrayList<>();
        for(int value : values)
            list.add(value);
        return list;
    }
}
