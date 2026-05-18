package ml.melun.mangaview.fragment;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MainSearchTest {
    @Test
    public void ntkEpisodeClickPrefersStoredSameTitleWhenSearchLinkIsStale() {
        Title searched = new Title("Sky Invasion", "", "", null, "255화", 3540, base_comic);
        searched.setSourceSite("ntk");

        Title stored = new Title("Sky Invasion", "", "", null, "258화", 34, base_comic);
        stored.setSourceSite("ntk");
        stored.setReadingProgress(255, 1, 258);

        Title resolved = MainSearch.chooseStoredTitleForEpisodeForTest(
                searched, Collections.singletonList(stored), new ArrayList<>());

        assertEquals(34, resolved.getId());
    }

    @Test
    public void ntkEpisodeClickDoesNotBorrowDifferentSourceTitle() {
        Title searched = new Title("Sky Invasion", "", "", null, "255화", 3540, base_comic);
        searched.setSourceSite("ntk");

        Title stored = new Title("Sky Invasion", "", "", null, "258화", 34, base_comic);
        stored.setSourceSite("wfwf");
        stored.setReadingProgress(255, 1, 258);
        List<MTitle> recent = Collections.singletonList(stored);

        assertNull(MainSearch.chooseStoredTitleForEpisodeForTest(searched, recent, new ArrayList<>()));
    }
    @Test
    public void librarySearchHandlesNullPersistedFields() {
        Title title = new Title(null, "", null, null, null, 1, base_comic);

        assertFalse(MainSearch.matchesLibraryQueryForTest(title, "sky"));
        assertEquals("", MainSearch.normalizedTitleNameForTest(title));
    }

    @Test
    public void emptyOfflineLibraryDoesNotReloadAfterLoaded() {
        assertTrue(MainSearch.shouldLoadOfflineTitlesForTest(0, false, false));
        assertTrue(MainSearch.shouldLoadOfflineTitlesForTest(3, false, false));
        assertFalse(MainSearch.shouldLoadOfflineTitlesForTest(3, true, false));
        assertFalse(MainSearch.shouldLoadOfflineTitlesForTest(3, false, true));
        assertFalse(MainSearch.shouldLoadOfflineTitlesForTest(1, false, false));
    }
}
