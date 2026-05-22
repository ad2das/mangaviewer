package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TitleTest {
    @Test
    public void expectedEpisodeFetchFailuresDoNotReportAsCrashes() {
        assertFalse(Title.shouldReportFetchFailure(new Exception("Request failed: /cl?toon=10001")));
        assertFalse(Title.shouldReportFetchFailure(new IOException("Network is unreachable")));
        assertTrue(Title.shouldReportFetchFailure(new IllegalStateException("parser invariant failed")));
    }

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
    public void ntkApiTitlePathUsesResolvedSourceWorkPath() {
        assertEquals("/manhwa/u-moo205z1-yvf4", Title.ntkApiTitlePathForTest("manhwa", "u-moo205z1-yvf4/"));
        assertEquals("/manhwa/u-moo205z1-yvf4", Title.ntkApiTitlePathForTest("manhwa", "https://ntk01.com/manhwa/u-moo205z1-yvf4?x=1"));
        assertEquals("/webtoon/17801", Title.ntkApiTitlePathForTest("webtoon", "17801"));
    }

    @Test
    public void ntkSearchTitlePathMatchesCurrentIdFromSearchHtml() {
        String html = "<a class=\"card\" href=\"/manhwa/34911\">"
                + "<span class=\"title\">Girl Friend Request Works</span>"
                + "<span>17 Love Comedy 36</span></a>"
                + "<a class=\"card\" href=\"/manhwa/17082\"><span class=\"title\">Other Title</span></a>";

        assertEquals("/manhwa/34911",
                Title.ntkSearchTitlePathForTest(html, "manhwa", "Girl Friend Request..."));
    }

    @Test
    public void ntkTitleMissingPageIgnoresNextErrorTokensWhenEpisodesExist() {
        assertFalse(Title.looksLikeNtkMissingPageForTest(
                "<html><body><script>self.__next_f.push([\"$\",\"__next_error__\"])</script>"
                        + "<a class=\"ep-row-v2-link\" href=\"/manhwa/34911/u-mp8ngtgm-gp0d\"><strong>27화</strong></a>"
                        + "</body></html>"));

        assertTrue(Title.looksLikeNtkMissingPageForTest(
                "<html id=\"__next_error__\"><script>self.__next_f.push([\"NEXT_HTTP_ERROR_FALLBACK\",404])</script></html>"));
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
    public void wolfEpisodeParserIgnoresFirstEpisodeShortcut() {
        List<Manga> episodes = Title.parseWolfEpisodesForTest(
                "<section id=\"content\">"
                        + "<div class=\"btn\"><a href=\"/cv?toon=10017&num=1\" class=\"view_open\">첫화보기</a></div>"
                        + "<div class=\"webtoon-bbs-list bbs-list\"><ul>"
                        + "<li><a href=\"/cv?toon=10017&num=92&title=92화\" class=\"view_open\">"
                        + "<div class=\"list-box\"><div class=\"num\">92</div><div class=\"subject\">92화</div><span class=\"date\">2026.05.22</span></div></a></li>"
                        + "<li><a href=\"/cv?toon=10017&num=2&title=2화\" class=\"view_open\">"
                        + "<div class=\"list-box\"><div class=\"num\">2</div><div class=\"subject\">2화</div></div></a></li>"
                        + "<li><a href=\"/cv?toon=10017&num=1&title=1화\" class=\"view_open\">"
                        + "<div class=\"list-box\"><div class=\"num\">1</div><div class=\"subject\">1화</div></div></a></li>"
                        + "</ul></div></section>",
                10017,
                "/cv?toon=",
                MTitle.base_comic);

        assertEquals(3, episodes.size());
        assertEquals(92, episodes.get(0).getId());
        assertEquals(2, episodes.get(1).getId());
        assertEquals(1, episodes.get(2).getId());
        assertEquals(2, episodes.get(2).nextEp().getId());
    }

    @Test
    public void legacyInfoRootFallsBackToDocumentWhenHeaderIsMissing() {
        assertEquals("episode", Title.legacyInfoRootTextForTest(
                "<html><body><ul class=\"list-body\"><li class=\"list-item\">episode</li></ul></body></html>",
                "ul.list-body li"));
    }

    @Test
    public void legacyRecommendCountIgnoresMissingOrInvalidMarkup() {
        assertEquals(0, Title.legacyRecommendCountForTest("<table class=\"table\"></table>"));
        assertEquals(0, Title.legacyRecommendCountForTest("<table class=\"table\"><tr><td><button class=\"btn-red\"><b>n/a</b></button></td></tr></table>"));
        assertEquals(42, Title.legacyRecommendCountForTest("<table class=\"table\"><tr><td><button class=\"btn-red\"><b>42</b></button></td></tr></table>"));
    }
}
