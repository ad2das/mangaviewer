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
    public void cleanNtkEpisodeTitleRemovesDateAndBadges() {
        String title = Title.cleanNtkEpisodeTitleForTest(
                "<a href=\"/manhwa/10/20\">NEW 20화 26.05.09 ▶ 보기</a>");

        assertEquals("20화", title);
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
}
