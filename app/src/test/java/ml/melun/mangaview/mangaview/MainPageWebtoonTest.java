package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MainPageWebtoonTest {
    @Test
    public void parseWolfTitles_filtersMixedSearchResultsForWebtoon() {
        ArrayList<Title> titles = MainPageWebtoon.parseWolfTitles(
                Jsoup.parse(mixedSearchHtml()), base_webtoon, 0);

        assertEquals(1, titles.size());
        assertEquals("웹툰 결과", titles.get(0).getName());
        assertEquals(101, titles.get(0).getId());
        assertEquals(base_webtoon, titles.get(0).getBaseMode());
        assertEquals("/list?toon=101", titles.get(0).getUrl());
    }

    @Test
    public void parseWolfTitles_filtersMixedSearchResultsForComic() {
        ArrayList<Title> titles = MainPageWebtoon.parseWolfTitles(
                Jsoup.parse(mixedSearchHtml()), base_comic, 0);

        assertEquals(1, titles.size());
        assertEquals("만화 결과", titles.get(0).getName());
        assertEquals(202, titles.get(0).getId());
        assertEquals(base_comic, titles.get(0).getBaseMode());
        assertEquals("/cl?toon=202", titles.get(0).getUrl());
    }

    @Test
    public void parseWolfTitles_infersSearchResultGenresWhenSourceOmitsTags() {
        ArrayList<Title> webtoons = MainPageWebtoon.parseWolfTitles(
                Jsoup.parse(searchItem("/list?toon=301", "이세계 액션 웹툰")), base_webtoon, 0);
        ArrayList<Title> comics = MainPageWebtoon.parseWolfTitles(
                Jsoup.parse(searchItem("/cl?toon=302", "학원 러브코미디 만화")), base_comic, 0);

        assertTrue(webtoons.get(0).getTags().contains("판타지"));
        assertTrue(webtoons.get(0).getTags().contains("액션"));
        assertTrue(comics.get(0).getTags().contains("학원"));
        assertTrue(comics.get(0).getTags().contains("러브코미디"));

        ArrayList<Title> englishComics = MainPageWebtoon.parseWolfTitles(
                Jsoup.parse(searchItem("/cl?toon=303", "Fate / stay night")), base_comic, 0);
        assertTrue(englishComics.get(0).getTags().contains("액션"));
        assertTrue(englishComics.get(0).getTags().contains("이세계"));
    }

    @Test
    public void parseWolfTitles_readsNtkLazyThumbnailAttributes() {
        ArrayList<Title> dataSrcset = MainPageWebtoon.parseWolfTitles(
                Jsoup.parse("<li><a href=\"/webtoon/501\"><img src=\"data:image/gif;base64,AA\" data-srcset=\"/data/webtoon/thumb-320.jpg 320w, /data/webtoon/thumb-640.jpg 640w\"><p class=\"subject\">NTK 웹툰</p></a></li>"),
                base_webtoon, 0);
        ArrayList<Title> background = MainPageWebtoon.parseWolfTitles(
                Jsoup.parse("<li><a href=\"/manhwa/601\"><span style=\"background-image:url('/data/manhwa/thumb.jpg')\"></span><p class=\"subject\">NTK 만화</p></a></li>"),
                base_comic, 0);

        assertEquals("/data/webtoon/thumb-320.jpg", dataSrcset.get(0).getThumb());
        assertEquals("/data/manhwa/thumb.jpg", background.get(0).getThumb());
    }

    @Test
    public void parseWolfTitles_readsNtkCardContainerAndSkipsEpisodeLinks() {
        ArrayList<Title> titles = MainPageWebtoon.parseWolfTitles(
                Jsoup.parse("<section>"
                        + "<div class=\"card\">"
                        + "<a class=\"episode\" href=\"/manhwa/700/12\">12화</a>"
                        + "<a class=\"cover\" href=\"/manhwa/700\"><img data-src=\"/covers/700.jpg\"></a>"
                        + "<div class=\"meta\"><a class=\"title\" href=\"/manhwa/700\">스릴러 작품</a></div>"
                        + "</div>"
                        + "<a href=\"/manhwa/701/3\">다른 작품 3화</a>"
                        + "</section>"),
                base_comic, 0);

        assertEquals(1, titles.size());
        assertEquals(700, titles.get(0).getId());
        assertEquals("스릴러 작품", titles.get(0).getName());
        assertEquals("/covers/700.jpg", titles.get(0).getThumb());
    }

    @Test
    public void ntkPaginationFindsNextLinksAndFallbackCandidates() {
        assertEquals("/manhwa?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC&page=2",
                Search.findNtkNextPagePathForTest(
                        "<nav><a href=\"?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC&page=2\">2</a></nav>",
                        "/manhwa?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC",
                        2));
        assertEquals("/manhwa/page/3?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC",
                Search.findNtkNextPagePathForTest(
                        "<a class=\"next\" href=\"https://ntk01.com/manhwa/page/3?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC\">다음</a>",
                        "/manhwa?page=2&g=%EC%8A%A4%EB%A6%B4%EB%9F%AC",
                        3));
        assertEquals("/manhwa?page=2&g=%EC%8A%A4%EB%A6%B4%EB%9F%AC",
                Search.findNtkNextPagePathForTest(
                        "<a rel=\"next\" href=\"?page=2\">다음</a>",
                        "/manhwa?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC",
                        2));
        assertEquals("/manhwa/page/3?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC",
                Search.findNtkNextPagePathForTest(
                        "<a rel=\"next\" href=\"/manhwa/page/3?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC\">다음</a>",
                        "/manhwa/page/2?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC",
                        3));

        ArrayList<String> candidates = Search.ntkPageCandidatesForTest("/manhwa?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC", 2);
        assertEquals("/manhwa?page=2&g=%EC%8A%A4%EB%A6%B4%EB%9F%AC", candidates.get(0));
        assertTrue(candidates.contains("/manhwa?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC&page=2"));
        assertTrue(candidates.contains("/manhwa?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC&p=2"));
        assertTrue(candidates.contains("/manhwa/page/2?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC"));
    }

    @Test
    public void ntkCategoryUsesApiPaginationAndParsesWorks() throws Exception {
        assertEquals("/api/manhwa-list?status=&g=%EC%8A%A4%EB%A6%B4%EB%9F%AC&page=2&pageSize=30&withTotal=1",
                Search.ntkCategoryApiPathForTest("/manhwa?g=%EC%8A%A4%EB%A6%B4%EB%9F%AC", 2, base_comic));
        assertEquals("/api/works?status=ing&tag=%EC%8A%A4%EB%A6%B4%EB%9F%AC&page=3&pageSize=30&withTotal=1",
                Search.ntkCategoryApiPathForTest("/ing?tag=%EC%8A%A4%EB%A6%B4%EB%9F%AC", 3, base_webtoon));
        assertEquals("/api/works?status=end&tag=%EC%8A%A4%EB%A6%B4%EB%9F%AC&page=4&pageSize=30&withTotal=1",
                Search.ntkCategoryApiPathForTest("/end?tag=%EC%8A%A4%EB%A6%B4%EB%9F%AC", 4, base_webtoon));

        String apiBody =
                "{\"works\":["
                        + "{\"sourceWorkId\":\"3587\",\"id\":\"u-ignore\",\"title\":\"데빌맨\",\"thumbnailUrl\":\"/covers/3587.webp\",\"genre\":\"스릴러, 액션\",\"latestEpisodeNumber\":5},"
                        + "{\"sourceWorkId\":\"u-moszr294-sxhn\",\"title\":\"앱에서 열 수 없는 항목\",\"thumbnailUrl\":\"/covers/bad.webp\"}"
                        + "],\"page\":2,\"hasMore\":true,\"pageSize\":30,\"total\":308}";
        ArrayList<Title> titles = Search.parseNtkApiTitlesForTest(
                apiBody,
                base_comic);

        assertEquals(1, titles.size());
        assertEquals(3587, titles.get(0).getId());
        assertEquals("데빌맨", titles.get(0).getName());
        assertEquals("/covers/3587.webp", titles.get(0).getThumb());
        assertEquals("5화", titles.get(0).getRelease());
        assertEquals(base_comic, titles.get(0).getBaseMode());
        assertEquals("ntk", titles.get(0).getSourceSite());
        assertTrue(titles.get(0).getTags().contains("스릴러"));
        assertTrue(titles.get(0).getTags().contains("액션"));
        assertEquals(308, Search.parseNtkApiTotalForTest(apiBody, base_comic));
    }

    @Test
    public void enhanceComicClassification_backfillsGenreSectionsFromInferredTags() {
        Ranking<Title> recent = new Ranking<>("정렬|최신순|/cm?type1=complete&type2=recent&o=n");
        recent.add(new Title("학원 러브코미디 만화", "", "", new ArrayList<>(), "", 401, base_comic));
        Ranking<Title> school = new Ranking<>("장르별|학원|/cm?type1=genre&type2=%C7%D0%BF%F8&o=n");
        List<Ranking<?>> sections = new ArrayList<>();
        sections.add(recent);
        sections.add(school);

        MainPageWebtoon.enhanceComicClassification(sections);

        assertEquals(1, school.size());
        assertEquals(401, school.get(0).getId());
    }

    @Test
    public void enhanceWebtoonClassification_backfillsEmptySectionsFromClassificationDb() {
        MainPageWebtoon.clearClassificationDbForTest();
        try {
            MainPageWebtoon.putClassificationDbTitleForTest(501, "드라마 웹툰", false, "드라마");
            MainPageWebtoon.putClassificationDbTitleForTest(502, "액션 웹툰", false, "액션");

            Ranking<Title> recent = new Ranking<>("연재웹툰|신작|/ing");
            Ranking<Title> drama = new Ranking<>("장르별|드라마|/ing?tag=%EB%93%9C%EB%9D%BC%EB%A7%88");
            List<Ranking<?>> sections = new ArrayList<>();
            sections.add(recent);
            sections.add(drama);

            MainPageWebtoon.enhanceWebtoonClassification(sections);

            assertEquals(2, recent.size());
            assertEquals(501, recent.get(0).getId());
            assertEquals(1, drama.size());
            assertEquals(501, drama.get(0).getId());
        } finally {
            MainPageWebtoon.clearClassificationDbForTest();
        }
    }

    @Test
    public void genreFromCategoryPath_decodesGenreFilters() {
        assertEquals("학원", Search.genreFromCategoryPath(
                "/cm?type1=genre&type2=%C7%D0%BF%F8&o=n", base_comic));
        assertEquals("학원", Search.genreFromCategoryPath(
                "/manhwa?g=%ED%95%99%EC%9B%90", base_comic));
        assertEquals("로맨스", Search.genreFromCategoryPath(
                "/ing?tag=%EB%A1%9C%EB%A7%A8%EC%8A%A4", base_webtoon));
        assertEquals("성인", Search.genreFromCategoryPath(
                "/ing?type1=genre&o=n", base_webtoon));
        assertEquals("", Search.genreFromCategoryPath(
                "/end?type1=genre&type2=&o=f", base_webtoon));
        assertEquals("", Search.genreFromCategoryPath(
                "/cm?type1=complete&type2=recent&o=n", base_comic));
    }

    @Test
    public void ntkWebtoonGenreFiltersExposeFullGenreList() {
        String[] genres = MainPageWebtoon.NTK_WEBTOON_FILTER_GROUPS[2];
        String[] completedGenres = MainPageWebtoon.NTK_WEBTOON_FILTER_GROUPS[3];

        assertEquals(18, genres.length);
        assertEquals(18, completedGenres.length);
        assertTrue(containsFilter(genres, "성인", "/ing?tag=%EC%84%B1%EC%9D%B8"));
        assertTrue(containsFilter(genres, "BL", "/ing?tag=BL"));
        assertTrue(containsFilter(genres, "BL/GL", "/ing?tag=BL%2FGL"));
        assertTrue(containsFilter(genres, "코미디", "/ing?tag=%EC%BD%94%EB%AF%B8%EB%94%94"));
        assertTrue(containsFilter(genres, "스토리", "/ing?tag=%EC%8A%A4%ED%86%A0%EB%A6%AC"));
        assertTrue(containsFilter(completedGenres, "스토리", "/end?tag=%EC%8A%A4%ED%86%A0%EB%A6%AC"));
    }

    @Test
    public void ntkComicGenreFiltersExposeFullGenreList() {
        String[] genres = MainPageWebtoon.NTK_COMIC_FILTER_GROUPS[1];

        assertEquals(39, genres.length);
        assertTrue(containsFilter(genres, "17", "/manhwa?g=17"));
        assertTrue(containsFilter(genres, "TS", "/manhwa?g=TS"));
        assertTrue(containsFilter(genres, "로맨틱코미디", "/manhwa?g=%EB%A1%9C%EB%A7%A8%ED%8B%B1%EC%BD%94%EB%AF%B8%EB%94%94"));
        assertTrue(containsFilter(genres, "BL/GL", "/manhwa?g=BL%2FGL"));
        assertTrue(containsFilter(genres, "이세계", "/manhwa?g=%EC%9D%B4%EC%84%B8%EA%B3%84"));
        assertTrue(containsFilter(genres, "어드벤처", "/manhwa?g=%EC%96%B4%EB%93%9C%EB%B2%A4%EC%B2%98"));
        assertTrue(containsFilter(genres, "애니화", "/manhwa?g=%EC%95%A0%EB%8B%88%ED%99%94"));
    }

    @Test
    public void normalizeNtkPathPreservesNtkSortAndFilterParams() {
        assertEquals("/ing?sort=hot", MainPageWebtoon.normalizeNtkPathForTest("/ing?sort=hot"));
        assertEquals("/end?sort=hot", MainPageWebtoon.normalizeNtkPathForTest("/end?sort=hot"));
        assertEquals("/manhwa?sort=hot", MainPageWebtoon.normalizeNtkPathForTest("/manhwa?sort=hot"));
        assertEquals("/ing?tag=%EB%A1%9C%EB%A7%A8%EC%8A%A4", MainPageWebtoon.normalizeNtkPathForTest("/ing?tag=%EB%A1%9C%EB%A7%A8%EC%8A%A4"));
        assertEquals("/manhwa?g=%EC%9D%B4%EC%84%B8%EA%B3%84", MainPageWebtoon.normalizeNtkPathForTest("/manhwa?g=%EC%9D%B4%EC%84%B8%EA%B3%84"));
    }

    @Test
    public void classificationDbGenreLookupSupportsPaging() {
        MainPageWebtoon.clearClassificationDbForTest();
        try {
            MainPageWebtoon.putClassificationDbTitleForTest(1, "A", false, "action");
            MainPageWebtoon.putClassificationDbTitleForTest(2, "B", false, "action");
            MainPageWebtoon.putClassificationDbTitleForTest(3, "C", false, "action");

            ArrayList<Title> first = MainPageWebtoon.getClassificationDbTitlesByGenre("action", 0, 2);
            ArrayList<Title> second = MainPageWebtoon.getClassificationDbTitlesByGenre("action", 2, 2);

            assertEquals(2, first.size());
            assertEquals(1, first.get(0).getId());
            assertEquals(2, first.get(1).getId());
            assertEquals(1, second.size());
            assertEquals(3, second.get(0).getId());

            MainPageWebtoon.putClassificationDbTitleForTest(11, "D", true, "anime");

            ArrayList<Title> comics = MainPageWebtoon.getComicClassificationDbTitlesByGenre("anime", 0, 10);
            assertEquals(1, comics.size());
            assertEquals(11, comics.get(0).getId());
            assertEquals(base_comic, comics.get(0).getBaseMode());
        } finally {
            MainPageWebtoon.clearClassificationDbForTest();
        }
    }

    @Test
    public void classificationDbGenreLookupUsesCaseInsensitiveIndex() {
        MainPageWebtoon.clearClassificationDbForTest();
        try {
            MainPageWebtoon.putClassificationDbTitleForTest(7, "Indexed", false, "Action");

            ArrayList<Title> titles = MainPageWebtoon.getClassificationDbTitlesByGenre("action", 0, 10);

            assertEquals(1, titles.size());
            assertEquals(7, titles.get(0).getId());
        } finally {
            MainPageWebtoon.clearClassificationDbForTest();
        }
    }

    private String mixedSearchHtml() {
        return "<article class=\"searchItem\">"
                + "<a href=\"/list?toon=101&title=%EC%9B%B9%ED%88%B0\"><h6 class=\"searchDetailTitle\">웹툰 결과</h6></a>"
                + "<div class=\"searchPng\" style=\"background-image:url('/webtoon.jpg')\"></div>"
                + "</article>"
                + "<article class=\"searchItem\">"
                + "<a href=\"/cl?toon=202&title=%EB%A7%8C%ED%99%94\"><h6 class=\"searchDetailTitle\">만화 결과</h6></a>"
                + "<div class=\"searchPng\" style=\"background-image:url('/comic.jpg')\"></div>"
                + "</article>";
    }

    private String searchItem(String href, String title) {
        return "<article class=\"searchItem\">"
                + "<a href=\"" + href + "\"><h6 class=\"searchDetailTitle\">" + title + "</h6></a>"
                + "<div class=\"searchPng\" style=\"background-image:url('/thumb.jpg')\"></div>"
                + "</article>";
    }

    private boolean containsFilter(String[] filters, String label, String path) {
        String expected = "|" + label + "|" + path;
        for(String filter : filters)
            if(filter.endsWith(expected))
                return true;
        return false;
    }
}
