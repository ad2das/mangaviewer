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
    public void ntkAutoKeywordSearchUsesBothApiLists() {
        ArrayList<String> paths = Search.ntkKeywordApiPathsForTest("onepunch", base_auto, 1, 120);

        assertEquals(2, paths.size());
        assertEquals("/api/works?keyword=onepunch&page=1&pageSize=30&withTotal=1", paths.get(0));
        assertEquals("/api/manhwa-list?keyword=onepunch&page=1&pageSize=30&withTotal=1", paths.get(1));
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
}
