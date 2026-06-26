package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.util.ArrayList;

import static ml.melun.mangaview.mangaview.MTitle.base_auto;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NtkKeywordSearchPolicyTest {
    @Test
    public void apiPathsCoverSelectedKindsAndClampPageSize() {
        ArrayList<String> auto = NtkKeywordSearchPolicy.keywordApiPaths("one piece", base_auto, 0, 500);
        ArrayList<String> webtoon = NtkKeywordSearchPolicy.keywordApiPaths("hero", base_webtoon, 2, 3);
        ArrayList<String> comic = NtkKeywordSearchPolicy.keywordApiPaths("hero", base_comic, 3, 12);

        assertEquals(2, auto.size());
        assertTrue(auto.get(0).contains("keyword=one+piece"));
        assertTrue(auto.get(0).contains("page=1"));
        assertTrue(auto.get(0).contains("pageSize=120"));
        assertEquals(1, webtoon.size());
        assertTrue(webtoon.get(0).startsWith("/api/works?"));
        assertTrue(webtoon.get(0).contains("pageSize=120"));
        assertEquals(1, comic.size());
        assertTrue(comic.get(0).startsWith("/api/manhwa-list?"));
    }

    @Test
    public void autoModeSplitsPerKindLimit() {
        assertEquals(50, NtkKeywordSearchPolicy.perKindLimit(base_auto, 100));
        assertEquals(10, NtkKeywordSearchPolicy.perKindLimit(base_auto, 12));
        assertEquals(100, NtkKeywordSearchPolicy.perKindLimit(base_webtoon, 100));
        assertEquals(0, NtkKeywordSearchPolicy.perKindLimit(base_auto, 0));
    }

    @Test
    public void multiPathApiPreservesHasMore() {
        assertTrue(NtkKeywordSearchPolicy.apiHasMore(2, true));
        assertFalse(NtkKeywordSearchPolicy.apiHasMore(2, false));
        assertFalse(NtkKeywordSearchPolicy.apiHasMore(0, true));
    }

    @Test
    public void keywordMatchingIgnoresCaseAndWhitespaceOnly() {
        Title matching = new Title("One Piece", "", "", new ArrayList<>(), "", 1, base_comic);
        Title unrelated = new Title("Piece Maker", "", "", new ArrayList<>(), "", 2, base_comic);
        ArrayList<Title> titles = new ArrayList<>();
        titles.add(matching);
        titles.add(unrelated);

        ArrayList<Title> filtered = NtkKeywordSearchPolicy.filterResults(titles, "onepiece", 10);

        assertEquals(1, filtered.size());
        assertEquals(1, filtered.get(0).getId());
        assertTrue(NtkKeywordSearchPolicy.matchesKeyword(matching, "onepiece"));
        assertFalse(NtkKeywordSearchPolicy.matchesKeyword(unrelated, "onepiece"));
    }
}
