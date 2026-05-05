package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.junit.Test;

import java.util.ArrayList;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;

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
}
