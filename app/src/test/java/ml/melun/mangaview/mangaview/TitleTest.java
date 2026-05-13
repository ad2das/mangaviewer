package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class TitleTest {
    @Test
    public void cleanNtkEpisodeTitleRemovesReadFromFirstEpisodeAction() {
        String title = Title.cleanNtkEpisodeTitleForTest(
                "<a href=\"/webtoon/10/1\"><span class=\"subject\">1화</span><span>첫화부터 정주행</span></a>");

        assertEquals("1화", title);
    }

    @Test
    public void cleanNtkEpisodeTitleIgnoresStandaloneReadFromFirstEpisodeAction() {
        String title = Title.cleanNtkEpisodeTitleForTest(
                "<a href=\"/webtoon/10/1\">첫화부터 정주행</a>");

        assertEquals("", title);
    }

    @Test
    public void cleanNtkEpisodeTitleIgnoresEmojiOnlyReadFromFirstEpisodeAction() {
        String title = Title.cleanNtkEpisodeTitleForTest(
                "<a href=\"/manhwa/10/1\">📖 첫화부터 정주행</a>");

        assertEquals("", title);
    }

    @Test
    public void cleanNtkEpisodeTitleRemovesDateAndBadges() {
        String title = Title.cleanNtkEpisodeTitleForTest(
                "<a href=\"/manhwa/10/20\">NEW 20화 26.05.09 ▶ 보기</a>");

        assertEquals("20화", title);
    }

    @Test
    public void normalizeNtkEpisodePathKeepsSlugEpisodes() {
        String path = Title.normalizeNtkEpisodePathForTest(
                "https://ntk01.com/manhwa/2/u-mox2ur2h-6upc?from=latest", "manhwa", 2);

        assertEquals("/manhwa/2/u-mox2ur2h-6upc", path);
    }

    @Test
    public void ntkEpisodeSortIdUsesVisibleEpisodeNumberForSlugEpisodes() {
        int sortId = Title.ntkEpisodeSortIdForTest(
                "<a class=\"ep-row-v2-link\" href=\"/manhwa/2/u-mox2ur2h-6upc\">"
                        + "<span class=\"ep-row-v2-no\">1292</span>"
                        + "<strong>1182화</strong>"
                        + "</a>",
                "/manhwa/2/u-mox2ur2h-6upc",
                "manhwa");

        assertEquals(1292, sortId);
    }

    @Test
    public void titleMinimizePreservesSourceSite() {
        Title title = new Title("title", "", "", null, "", 10, MTitle.base_webtoon);
        title.setSourceSite("ntk");

        MTitle minimized = title.minimize();
        Title restored = new Title(minimized);

        assertEquals("ntk", minimized.getSourceSite());
        assertEquals("ntk", restored.getSourceSite());
    }

    @Test
    public void titleNameFallsBackToEmptyStringWhenMissing() {
        Title title = new Title(null, "", "", null, "", 10, MTitle.base_webtoon);
        title.setName(null);

        assertEquals("", title.getName());
    }

    @Test
    public void titleEqualsReturnsFalseForOtherTypes() {
        Title title = new Title("title", "", "", null, "", 10, MTitle.base_webtoon);

        assertEquals(false, title.equals("title"));
        assertEquals(false, title.equals(null));
    }

    @Test
    public void displayEpisodeCountUsesNtkReleaseWhenStoredCountIsStale() {
        Title title = new Title("성순 엑스터시", "", "", null, "13화", 36716, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setReadingProgress(7, 43, 49);

        assertEquals(13, title.getDisplayEpisodeCount(0));
    }

    @Test
    public void legacyEpisodeParserKeepsRowsWithMissingDetails() {
        List<Manga> episodes = Title.parseLegacyEpisodesForTest(
                "<ul class=\"list-body\">"
                        + "<li class=\"list-item\"><a class=\"item-subject\" href=\"/webtoon/12\">12화</a></li>"
                        + "<li class=\"list-item\"><a class=\"item-subject\" href=\"/webtoon/11\"><span>11화</span></a><div class=\"item-details\"><span>2026.05.01</span></div></li>"
                        + "<li class=\"list-item\"><a class=\"item-subject\" href=\"/webtoon/not-an-id\">깨진 행</a></li>"
                        + "</ul>",
                MTitle.base_webtoon);

        assertEquals(2, episodes.size());
        assertEquals(12, episodes.get(0).getId());
        assertEquals("", episodes.get(0).getDate());
        assertEquals(11, episodes.get(1).getId());
        assertEquals("2026.05.01", episodes.get(1).getDate());
    }

    @Test
    public void legacyInfoRootFallsBackToDocumentWhenHeaderIsMissing() {
        assertEquals("episode", Title.legacyInfoRootTextForTest(
                "<html><body><ul class=\"list-body\"><li class=\"list-item\">episode</li></ul></body></html>",
                "ul.list-body li"));
    }
}
