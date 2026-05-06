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
    public void genreFromCategoryPath_decodesGenreFilters() {
        assertEquals("학원", Search.genreFromCategoryPath(
                "/cm?type1=genre&type2=%C7%D0%BF%F8&o=n", base_comic));
        assertEquals("성인", Search.genreFromCategoryPath(
                "/ing?type1=genre&o=n", base_webtoon));
        assertEquals("", Search.genreFromCategoryPath(
                "/end?type1=genre&type2=&o=f", base_webtoon));
        assertEquals("", Search.genreFromCategoryPath(
                "/cm?type1=complete&type2=recent&o=n", base_comic));
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
}
