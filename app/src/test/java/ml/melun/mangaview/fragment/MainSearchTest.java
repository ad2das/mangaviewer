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
    public void siteChangeClearsOnlyOnlineCatalogueState() {
        assertTrue(MainSearch.shouldClearOnlineResultsForSiteChange(false, true, true));
        assertTrue(MainSearch.shouldClearOnlineResultsForSiteChange(true, true, true));
        assertTrue(MainSearch.shouldClearOnlineResultsForSiteChange(true, false, true));
        assertFalse(MainSearch.shouldClearOnlineResultsForSiteChange(true, false, false));
    }

    @Test
    public void onlineResultMustMatchTheCurrentlySelectedSource() {
        Title ntk = new Title("NTK", "", "", null, "", 1, base_comic);
        ntk.setSourceSite("ntk");
        Title wfwf = new Title("WFWF", "", "", null, "", 2, base_comic);
        wfwf.setSourceSite("wfwf");
        Title unbound = new Title("unknown", "", "", null, "", 3, base_comic);

        assertTrue(MainSearch.onlineResultMatchesActiveSite(ntk, true));
        assertFalse(MainSearch.onlineResultMatchesActiveSite(ntk, false));
        assertTrue(MainSearch.onlineResultMatchesActiveSite(wfwf, false));
        assertFalse(MainSearch.onlineResultMatchesActiveSite(wfwf, true));
        assertFalse(MainSearch.onlineResultMatchesActiveSite(unbound, true));
    }

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
    public void ntkEpisodeClickKeepsStoredProgressWhenTitleIdMatches() {
        Title clicked = new Title("Sky Invasion", "", "", null, "5", 34, base_comic);
        clicked.setSourceSite("ntk");

        Title stored = new Title("Sky Invasion", "", "", null, "5", 34, base_comic);
        stored.setSourceSite("ntk");
        stored.setReadingProgress(210, 18, 120);

        Title resolved = MainSearch.chooseStoredTitleForEpisodeForTest(
                clicked, Collections.singletonList(stored), new ArrayList<>());

        assertEquals(34, resolved.getId());
        assertEquals(210, resolved.getBookmarkEpisodeId());
        assertEquals(18, resolved.getBookmarkEpisodeIndex());
        assertEquals(120, resolved.getEpisodeCount());
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

    @Test
    public void libraryFavoriteWithStalePathIsNotTreatedAsOfflineSaved() {
        Title favorite = new Title("Stored Path Leak", "", "", null, "", 11, base_comic);
        favorite.setPath("/missing/offline/title");
        favorite.setSourceSite("ntk");

        assertFalse(MainSearch.isOfflineTitleForLibraryForTest(favorite, new ArrayList<>()));
    }

    @Test
    public void libraryOfflineTitleRequiresActualOfflineMatch() {
        Title favorite = new Title("Stored Path Leak", "", "", null, "", 11, base_comic);
        favorite.setPath("/offline/title");
        favorite.setSourceSite("ntk");

        Title offline = new Title("Stored Path Leak", "", "", null, "", 11, base_comic);
        offline.setPath("/offline/title");
        offline.setSourceSite("ntk");

        assertTrue(MainSearch.isOfflineTitleForLibraryForTest(
                favorite, Collections.singletonList(offline)));
    }
}
