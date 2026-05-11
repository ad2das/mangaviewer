package ml.melun.mangaview.mangaview;

import org.junit.Test;

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
    public void displayEpisodeCountUsesNtkReleaseWhenStoredCountIsStale() {
        Title title = new Title("성순 엑스터시", "", "", null, "13화", 36716, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setReadingProgress(7, 43, 49);

        assertEquals(13, title.getDisplayEpisodeCount(0));
    }
}
