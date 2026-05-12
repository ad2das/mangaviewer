package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MainPageTest {
    @Test
    public void parseComicId_acceptsCommonComicPathShapes() {
        assertEquals(123, MainPage.parseComicId("/comic/123"));
        assertEquals(123, MainPage.parseComicId("/comic/123/"));
        assertEquals(123, MainPage.parseComicId("/comic/123/title-slug"));
        assertEquals(123, MainPage.parseComicId("/comic/123-title-slug"));
        assertEquals(123, MainPage.parseComicId("https://example.com/comic/123?foo=bar"));
        assertEquals(123, MainPage.parseComicId("/comic/123#comments"));
    }

    @Test
    public void parseComicId_rejectsInvalidComicPaths() {
        assertEquals(-1, MainPage.parseComicId(null));
        assertEquals(-1, MainPage.parseComicId("/comic/abc"));
        assertEquals(-1, MainPage.parseComicId("/webtoon/123"));
        assertEquals(-1, MainPage.parseComicId("/comic/"));
    }
}
