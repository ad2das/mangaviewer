package ml.melun.mangaview.runtime;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;

import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrefetchCoordinatorTest {
    @Test
    public void viewerTargetsFavorResumeAndNextEpisodesOnly() throws Exception {
        List<Manga> episodes = episodes(60, 50, 40, 30, 20, 10);
        Method method = PrefetchCoordinator.class.getDeclaredMethod("viewerTargets", List.class, int.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Integer> targets = (List<Integer>) method.invoke(null, episodes, 3, 4);

        assertEquals(asList(2, 1, 3, 5), targets);
    }

    @Test
    public void viewerTargetsSkipMissingEpisodes() throws Exception {
        List<Manga> episodes = episodes(60, 50, 40, 30, 20, 10);
        episodes.set(1, null);
        Method method = PrefetchCoordinator.class.getDeclaredMethod("viewerTargets", List.class, int.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Integer> targets = (List<Integer>) method.invoke(null, episodes, 3, 4);

        assertEquals(asList(2, 3, 5, 0), targets);
    }

    @Test
    public void viewerTargetsWarmVisibleFirstEpisodeWithoutBookmark() throws Exception {
        List<Manga> episodes = episodes(60, 50, 40, 30, 20, 10);
        Method method = PrefetchCoordinator.class.getDeclaredMethod("viewerTargets", List.class, int.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Integer> targets = (List<Integer>) method.invoke(null, episodes, -1, 3);

        assertEquals(asList(5, 0, 4), targets);
    }

    @Test
    public void viewerTargetsIncludeVisibleRowsEvenWithDeepBookmark() throws Exception {
        List<Manga> episodes = episodes(90, 80, 70, 60, 50, 40, 30, 20, 10);
        Method method = PrefetchCoordinator.class.getDeclaredMethod("viewerTargets", List.class, int.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Integer> targets = (List<Integer>) method.invoke(null, episodes, 7, 4);

        assertEquals(asList(6, 5, 7, 8), targets);
    }

    @Test
    public void viewerTargetsIncludeOlderAdjacentRowsNearTopBookmark() throws Exception {
        List<Manga> episodes = episodes(122, 121, 120, 119, 118);
        Method method = PrefetchCoordinator.class.getDeclaredMethod("viewerTargets", List.class, int.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Integer> targets = (List<Integer>) method.invoke(null, episodes, 2, 4);

        assertEquals(asList(1, 0, 2, 4), targets);
    }

    @Test
    public void visibleEpisodeTargetsIncludeVisibleRowsAndAhead() {
        List<Manga> episodes = episodes(122, 121, 120, 119, 118, 117);

        List<Integer> targets = PrefetchCoordinator.visibleEpisodeTargets(episodes, 1, 4, 2, 5);

        assertEquals(asList(0, 1, 2, 3, 4), targets);
    }

    @Test
    public void episodePrefetchLimitWarmsLikelyEntriesWhenDataSaverIsOff() {
        assertEquals(1, PrefetchCoordinator.episodePrefetchLimitForTest(true, false));
        assertEquals(1, PrefetchCoordinator.episodePrefetchLimitForTest(false, false));
        assertEquals(1, PrefetchCoordinator.episodePrefetchLimitForTest(false, true));
        assertEquals(1, PrefetchCoordinator.episodePrefetchLimitForTest(true, true, true));
        assertEquals(1, PrefetchCoordinator.episodePrefetchLimitForTest(false, true, true));
    }

    @Test
    public void ntkPrefetchSkipsUnverifiedNtkTitles() {
        assertFalse(PrefetchCoordinator.shouldSkipNtkPrefetchForTest("ntk", true, true));
        assertTrue(PrefetchCoordinator.shouldSkipNtkPrefetchForTest("", true, true));
        assertTrue(PrefetchCoordinator.shouldSkipNtkPrefetchForTest(null, true, true));
        assertFalse(PrefetchCoordinator.shouldSkipNtkPrefetchForTest("wfwf", true, true));
        assertFalse(PrefetchCoordinator.shouldSkipNtkPrefetchForTest("ntk", false, false));
    }

    @Test
    public void wolfBackgroundPrefetchAllowsExplicitWfwfSources() {
        assertFalse(PrefetchCoordinator.shouldSkipWolfBackgroundPrefetchForTest("wfwf", false, false));
        assertFalse(PrefetchCoordinator.shouldSkipWolfBackgroundPrefetchForTest("", false, false));
        assertFalse(PrefetchCoordinator.shouldSkipWolfBackgroundPrefetchForTest(null, false, false));
        assertFalse(PrefetchCoordinator.shouldSkipWolfBackgroundPrefetchForTest("ntk", true, true));
        assertFalse(PrefetchCoordinator.shouldSkipWolfBackgroundPrefetchForTest("ntk", false, false));
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
