package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MangaNtkImageApiTest {
    @Test
    public void slugViewerImageApiDoesNotPromoteSyntheticTitleId() {
        String body = "{\"imagesToken\":\"token\",\"imageMetas\":[{\"page\":1}]}";

        assertEquals("", Manga.ntkViewerCanonicalWorkIdForImageApiForTest(
                body, "/webtoon/u-bt-aw0260-574c7e11/bt-aw0260-1", 913843212,
                "u-bt-aw0260-574c7e11"));
    }

    @Test
    public void slugViewerImageApiUsesSourceWorkIdWhenPresent() {
        String body = "{\"imagesToken\":\"token\",\"imageMetas\":[{\"page\":1}],"
                + "\"sourceWorkId\":\"61393986\"}";

        assertEquals("61393986", Manga.ntkViewerCanonicalWorkIdForImageApiForTest(
                body, "/webtoon/u-slug-1196612/1196612", 913843212,
                "u-slug-1196612"));
    }
}
