package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void nextEpAndPrevEp_useFetchedEpisodesAfterTitleIsAttached() {
        Title title = new Title("title", "", "", new ArrayList<>(), "", 1, base_webtoon);
        Manga current = new Manga(30, "30", "", base_webtoon);
        List<Manga> parsedEpisodes = new ArrayList<>();
        parsedEpisodes.add(new Manga(40, "40", "", base_webtoon));
        parsedEpisodes.add(current);
        parsedEpisodes.add(new Manga(20, "20", "", base_webtoon));
        current.eps = parsedEpisodes;

        current.setTitle(title);

        assertEquals(40, current.nextEp().getId());
        assertEquals(20, current.prevEp().getId());
    }

    @Test
    public void nextEpAndPrevEp_allowEpisodeListWithMissingTitleIds() {
        Title title = new Title("title", "", "", new ArrayList<>(), "", 1, base_webtoon);
        Manga current = new Manga(30, "30", "", base_webtoon);
        current.setTitle(title);
        current.setTitleId(title.getId());
        List<Manga> parsedEpisodes = new ArrayList<>();
        parsedEpisodes.add(new Manga(40, "40", "", base_webtoon));
        parsedEpisodes.add(current);
        parsedEpisodes.add(new Manga(20, "20", "", base_webtoon));
        current.eps = parsedEpisodes;

        assertEquals(40, current.nextEp().getId());
        assertEquals(20, current.prevEp().getId());
    }

    @Test
    public void progressEpisodes_restoreNavigationWhenEpisodeListIsMissing() {
        Title title = new Title("title", "", "", new ArrayList<>(), "", 1, base_webtoon);
        title.setReadingProgress(177, 1, 177);
        Manga current = new Manga(177, "(177/177) 176화", "", base_webtoon);

        title.ensureProgressEpisodes(current);

        assertNull(current.nextEp());
        assertEquals(176, current.prevEp().getId());
        assertEquals(177, title.getEpsCount());
    }

    @Test
    public void progressEpisodes_replaceShortParsedListWithFullProgressList() {
        Title title = new Title("title", "", "", new ArrayList<>(), "", 1, base_webtoon);
        title.setReadingProgress(54, 43, 96);
        Manga current = new Manga(54, "54화", "", base_webtoon);
        List<Manga> partial = new ArrayList<>();
        partial.add(new Manga(55, "55화", "", base_webtoon));
        partial.add(current);
        partial.add(new Manga(53, "53화", "", base_webtoon));
        title.setEps(partial);

        title.ensureProgressEpisodes(current);

        assertEquals(96, title.getEpsCount());
        assertEquals(55, current.nextEp().getId());
        assertEquals(53, current.prevEp().getId());
    }

    @Test
    public void ntkProgressEpisodesDoNotReplaceLoadedEpisodeList() {
        Title title = new Title("one punch", "", "", new ArrayList<>(), "349", 8605, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setReadingProgress(273, 77, 349);
        Manga current = new Manga(273, "273", "", MTitle.base_comic);
        current.setTitle(title);
        List<Manga> parsed = new ArrayList<>();
        parsed.add(new Manga(275, "275", "", MTitle.base_comic));
        parsed.add(new Manga(274, "274", "", MTitle.base_comic));
        parsed.add(current);
        parsed.add(new Manga(272, "272", "", MTitle.base_comic));
        title.setEps(parsed);

        title.ensureProgressEpisodes(current);

        assertEquals(4, title.getEpsCount());
        assertEquals(274, current.nextEp().getId());
        assertEquals(272, current.prevEp().getId());
    }

    @Test
    public void wfwfNavigationDisambiguatesDuplicateEpisodeIdsByName() {
        Title title = new Title("서머타임 렌더링", "", "", new ArrayList<>(), "", 10017, MTitle.base_comic);
        title.setSourceSite("wfwf");
        Manga ninetyThree = new Manga(2, "서머타임 렌더링 93화", "", MTitle.base_comic);
        Manga next = new Manga(3, "서머타임 렌더링 03, 04화", "", MTitle.base_comic);
        Manga listedCurrent = new Manga(2, "서머타임 렌더링 02화", "", MTitle.base_comic);
        Manga previous = new Manga(1, "서머타임 렌더링 01화", "", MTitle.base_comic);
        List<Manga> episodes = new ArrayList<>();
        episodes.add(ninetyThree);
        episodes.add(next);
        episodes.add(listedCurrent);
        episodes.add(previous);
        title.setEps(episodes);

        Manga current = new Manga(2, "(2/144) 서머타임 렌더링 02화", "", MTitle.base_comic);
        current.setTitle(title);

        assertFalse(Manga.sameEpisodeIdentity(ninetyThree, current));
        assertTrue(Manga.sameEpisodeIdentity(listedCurrent, current));
        assertEquals("서머타임 렌더링 03, 04화", current.nextEp().getName());
        assertEquals("서머타임 렌더링 01화", current.prevEp().getName());
    }

    @Test
    public void ntkNavigationDisambiguatesEpisodeNumbersFromInternalIds() {
        Title title = new Title("서머타임 렌더링", "", "", new ArrayList<>(), "", 7843, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga actualNinetyOne = new Manga(95, "서머타임 렌더링 91화", "", MTitle.base_comic);
        actualNinetyOne.setNtkEpisodePath("/manhwa/7843/1666521");
        actualNinetyOne.setTitle(title);
        Manga pathlessGeneratedNinetyFive = new Manga(95, "95화", "", MTitle.base_comic);
        pathlessGeneratedNinetyFive.setTitle(title);
        Manga sameNinetyOne = new Manga(95, "91화", "", MTitle.base_comic);
        sameNinetyOne.setNtkEpisodePath("/manhwa/7843/1666521");
        sameNinetyOne.setTitle(title);

        assertFalse(Manga.sameEpisodeIdentity(actualNinetyOne, pathlessGeneratedNinetyFive));
        assertTrue(Manga.sameEpisodeIdentity(actualNinetyOne, sameNinetyOne));
    }

    @Test
    public void ntkNavigationRequiresMatchingEpisodePathWhenAvailable() {
        Title title = new Title("서머타임 렌더링", "", "", new ArrayList<>(), "", 7843, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga ninetyOne = new Manga(95, "91화", "", MTitle.base_comic);
        ninetyOne.setNtkEpisodePath("/manhwa/7843/1666521");
        ninetyOne.setTitle(title);
        Manga otherPath = new Manga(95, "91화", "", MTitle.base_comic);
        otherPath.setNtkEpisodePath("/manhwa/7843/1660630");
        otherPath.setTitle(title);

        assertFalse(Manga.sameEpisodeIdentity(ninetyOne, otherPath));
    }

    @Test
    public void cleanViewerEpisodeNameRemovesToolbarProgressPrefix() {
        assertEquals("서머타임 렌더링 92화", Manga.cleanViewerEpisodeName("(97/144) 서머타임 렌더링 92화"));
        assertEquals("서머타임 렌더링 02화", Manga.cleanViewerEpisodeName("서머타임 렌더링 02화"));
    }

    @Test
    public void parseEpisodeId_acceptsSluggedViewerPaths() {
        assertEquals(123, Manga.parseEpisodeId("/webtoon/123", "webtoon/"));
        assertEquals(123, Manga.parseEpisodeId("/webtoon/123/title-slug", "webtoon/"));
        assertEquals(123, Manga.parseEpisodeId("https://example.com/webtoon/123?x=1", "webtoon/"));
        assertEquals(-1, Manga.parseEpisodeId("/webtoon/title", "webtoon/"));
    }

    @Test
    public void parseEpisodeOptionId_skipsInvalidSelectValues() {
        assertEquals(77, Manga.parseEpisodeOptionId("77"));
        assertEquals(77, Manga.parseEpisodeOptionId(" 77 "));
        assertEquals(-1, Manga.parseEpisodeOptionId(""));
        assertEquals(-1, Manga.parseEpisodeOptionId("latest"));
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
