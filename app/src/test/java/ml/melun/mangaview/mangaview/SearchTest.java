package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import static ml.melun.mangaview.mangaview.MTitle.base_auto;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchTest {
    @Test
    public void expectedSearchNetworkFailuresDoNotReportAsCrashes() {
        assertFalse(Search.shouldReportSearchFailureForTest(
                new Exception("Request failed: /api/manhwa-list?status=completed")));
        assertFalse(Search.shouldReportSearchFailureForTest(
                new IOException("Network is unreachable")));
        assertTrue(Search.shouldReportSearchFailureForTest(
                new IllegalStateException("parser invariant failed")));
    }

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
    public void wfwfFastSearchParserBackfillsClassificationTags() {
        MainPageWebtoon.clearClassificationDbForTest();
        try {
            MainPageWebtoon.putClassificationDbTitleForTest(12683, "Jagan", true, "\uC561\uC158", "\uC2A4\uB9B4\uB7EC");

            ArrayList<Title> titles = Search.parseWfwfSearchHtmlFastForTest(
                    "<article class='searchItem'><a href='/cl?toon=12683' class='searchLink'>"
                            + "<div class='searchPng' style='background-image:url(https://i.example/12683.jpg)'></div>"
                            + "<div class='searchDetail'><h6 class='searchDetailTitle'>Jagan</h6></div>"
                            + "</a></article>",
                    base_comic, 20);

            assertEquals(1, titles.size());
            assertEquals(Arrays.asList("\uC561\uC158", "\uC2A4\uB9B4\uB7EC"), titles.get(0).getTags());
        } finally {
            MainPageWebtoon.clearClassificationDbForTest();
        }
    }

    @Test
    public void ntkAutoKeywordSearchUsesBothApiLists() {
        ArrayList<String> paths = Search.ntkKeywordApiPathsForTest("onepunch", base_auto, 1, 120);

        assertEquals(2, paths.size());
        assertEquals("/api/works?keyword=onepunch&page=1&pageSize=120&withTotal=1", paths.get(0));
        assertEquals("/api/manhwa-list?keyword=onepunch&page=1&pageSize=120&withTotal=1", paths.get(1));
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
    public void ntkKeywordSearchMergesHtmlBeforeFilteredApiResults() {
        ArrayList<Title> html = new ArrayList<>();
        html.add(ntkTitle("One Piece", 100, base_comic, "/manhwa/100"));
        html.add(ntkTitle("One Piece Special", 101, base_comic, "/manhwa/101"));
        html.add(ntkTitle("One Piece Spin Off", 102, base_comic, "/manhwa/102"));
        ArrayList<Title> api = new ArrayList<>();
        api.add(ntkTitle("One Piece", 100, base_comic, "/manhwa/100"));
        api.add(ntkTitle("One Piece API Only", 103, base_comic, "/manhwa/103"));

        ArrayList<Title> merged = Search.mergeNtkHybridKeywordTitlesForTest(html, api);

        assertEquals(4, merged.size());
        assertEquals("/manhwa/100", merged.get(0).getPath());
        assertEquals("/manhwa/101", merged.get(1).getPath());
        assertEquals("/manhwa/102", merged.get(2).getPath());
        assertEquals("/manhwa/103", merged.get(3).getPath());
    }

    @Test
    public void ntkKeywordSearchKeepsHtmlResultsWhenApiIsEmpty() {
        ArrayList<Title> html = new ArrayList<>();
        html.add(ntkTitle("One Piece", 100, base_comic, "/manhwa/100"));
        html.add(ntkTitle("One Piece Special", 101, base_comic, "/manhwa/101"));
        html.add(ntkTitle("One Piece Spin Off", 102, base_comic, "/manhwa/102"));

        ArrayList<Title> merged = Search.mergeNtkHybridKeywordTitlesForTest(html, new ArrayList<>());

        assertEquals(3, merged.size());
    }

    @Test
    public void ntkKeywordApiFilterDropsUnrelatedGenericRows() {
        ArrayList<Title> titles = new ArrayList<>();
        titles.add(ntkTitle("Unrelated Result", 1, base_comic, "/manhwa/1"));

        assertEquals(0, Search.filterNtkKeywordResultsForTest(titles, "onepunch", 0).size());
    }

    @Test
    public void ntkKeywordApiFilterMatchesTitlesOnly() {
        ArrayList<Title> titles = new ArrayList<>();
        Title byTag = new Title("\uC5C9\uB6B1\uD55C \uC791\uD488", "", "",
                Arrays.asList("\uC2A4\uD53C\uB4DC"), "", 1, base_webtoon);
        byTag.setSourceSite("ntk");
        byTag.setPath("/webtoon/1");
        Title byRelease = new Title("\uB2E4\uB978 \uC791\uD488", "", "", new ArrayList<>(),
                "\uC2A4\uD53C 10\uD654", 2, base_webtoon);
        byRelease.setSourceSite("ntk");
        byRelease.setPath("/webtoon/2");
        titles.add(byTag);
        titles.add(byRelease);
        titles.add(ntkTitle("\uC2A4\uD53C\uB9BF \uD551\uAC70\uC2A4", 3, base_webtoon, "/webtoon/3"));

        ArrayList<Title> filtered = Search.filterNtkKeywordResultsForTest(titles, "\uC2A4\uD53C", 0);

        assertEquals(1, filtered.size());
        assertEquals(3, filtered.get(0).getId());
    }

    @Test
    public void ntkKeywordApiEmptyIsNotAuthoritativeWhenApiReturnsGenericRows() {
        assertEquals(true, Search.isNtkKeywordApiEmptyAuthoritativeForTest(2, 2, 0, 0));
        assertEquals(false, Search.isNtkKeywordApiEmptyAuthoritativeForTest(2, 2, 15518, 60));
        assertEquals(false, Search.isNtkKeywordApiEmptyAuthoritativeForTest(1, 2, 0, 0));
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

    private static Title ntkTitle(String name, int id, int baseMode, String path) {
        Title title = new Title(name, "", "", new ArrayList<>(), "", id, baseMode);
        title.setSourceSite("ntk");
        title.setPath(path);
        return title;
    }
}
