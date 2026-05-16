package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.util.ArrayList;

import static ml.melun.mangaview.mangaview.MTitle.base_auto;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SearchTest {
    @Test
    public void wfwfKeywordSearchUsesSingleSharedPathForAutoSearch() {
        assertEquals("/search.html?q=onepunch", Search.wfwfKeywordSearchPathForTest("onepunch"));
    }

    @Test
    public void wfwfFastSearchParserReadsCompactSearchItems() {
        ArrayList<Title> titles = Search.parseWfwfSearchHtmlFastForTest(
                "<article class='searchItem'><a href='/cl?toon=12683' class='searchLink'>"
                        + "<div class='searchPng' style='background-image:url(https://i.example/12683.jpg)'></div>"
                        + "<div class='searchDetail'><h6 class='searchDetailTitle'>Jagan</h6></div>"
                        + "</a></article>",
                base_comic, 20);

        assertEquals(1, titles.size());
        assertEquals(12683, titles.get(0).getId());
        assertEquals(base_comic, titles.get(0).getBaseMode());
        assertEquals("Jagan", titles.get(0).getName());
        assertEquals("https://i.example/12683.jpg", titles.get(0).getThumb());
    }

    @Test
    public void ntkAutoKeywordSearchUsesBothApiLists() {
        ArrayList<String> paths = Search.ntkKeywordApiPathsForTest("onepunch", base_auto, 1, 120);

        assertEquals(2, paths.size());
        assertEquals("/api/works?keyword=onepunch&page=1&pageSize=30&withTotal=1", paths.get(0));
        assertEquals("/api/manhwa-list?keyword=onepunch&page=1&pageSize=30&withTotal=1", paths.get(1));
        assertTrue(Search.shouldFetchNtkKeywordApiPathsInParallelForTest(paths));
    }

    @Test
    public void ntkKeywordSearchKeepsSelectedBaseModeAndPage() {
        ArrayList<String> webtoon = Search.ntkKeywordApiPathsForTest("hero", base_webtoon, 2, 12);
        ArrayList<String> comic = Search.ntkKeywordApiPathsForTest("hero", base_comic, 3, 12);

        assertEquals(1, webtoon.size());
        assertEquals(1, comic.size());
        assertTrue(webtoon.get(0).startsWith("/api/works?"));
        assertTrue(webtoon.get(0).contains("page=2"));
        assertTrue(webtoon.get(0).contains("pageSize=12"));
        assertTrue(comic.get(0).startsWith("/api/manhwa-list?"));
        assertTrue(comic.get(0).contains("page=3"));
        assertTrue(comic.get(0).contains("pageSize=12"));
    }

    @Test
    public void ntkKeywordSearchUsesApiOnlyForKeywordMode() {
        assertTrue(Search.shouldUseNtkKeywordApiForTest(true, 0));
        assertEquals(false, Search.shouldUseNtkKeywordApiForTest(true, 2));
        assertEquals(false, Search.shouldUseNtkKeywordApiForTest(false, 0));
    }

    @Test
    public void ntkKeywordSearchSkipsHtmlFallbackWhenApiCompletedEmpty() {
        assertEquals(false, Search.shouldFallbackToNtkHtmlKeywordSearchForTest(0, true));
        assertEquals(true, Search.shouldFallbackToNtkHtmlKeywordSearchForTest(0, false));
        assertEquals(false, Search.shouldFallbackToNtkHtmlKeywordSearchForTest(1, false));
    }

    @Test
    public void ntkApiParserKeepsSlugSourceWorkIdsOpenable() throws Exception {
        ArrayList<Title> titles = Search.parseNtkApiTitlesForTest(
                "{\"works\":[{\"sourceWorkId\":\"u-moo205z1-yvf4\",\"title\":\"Slug Title\",\"thumbnailUrl\":\"/cover.jpg\"}]}",
                base_comic);

        assertEquals(1, titles.size());
        assertTrue(titles.get(0).getId() > 0);
        assertEquals("/manhwa/u-moo205z1-yvf4", titles.get(0).getPath());
        assertEquals("/manhwa/u-moo205z1-yvf4", titles.get(0).getUrl());
        assertEquals("/manhwa/u-moo205z1-yvf4/u-episode",
                Title.normalizeNtkEpisodePathForTest("/manhwa/u-moo205z1-yvf4/u-episode", "manhwa", "u-moo205z1-yvf4"));
    }
}
