package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MangaNtkImageApiTest {
    @Test
    public void slugViewerImageApiUsesRouteWorkIdInsteadOfSyntheticTitleId() {
        String body = "{\"imagesToken\":\"token\",\"imageMetas\":[{\"page\":1}]}";

        assertEquals("u-bt-aw0260-574c7e11", Manga.ntkViewerCanonicalWorkIdForImageApiForTest(
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

    @Test
    public void currentThemeViewerPayloadUsesImageApiToken() {
        String token = "eyJ3IjoiMzY1MjUiLCJlIjoiMTgwNzQyNCIsInQiOiJtYW5od2EifQ.sig";
        String body = "<script type=\"application/json\" id=\"theme-viewer-data\">"
                + "{\"sourceWorkId\":\"36525\",\"episodeId\":\"1807424\",\"token\":\"" + token + "\","
                + "\"imageApiPath\":\"/api/manhwa-images\","
                + "\"images\":[{\"width\":null,\"height\":null,\"page\":1}]}</script>";

        assertTrue(Manga.hasNtkViewerImageApiPayloadForTest(body));
        assertFalse(Manga.isNtkViewerConfirmedEmptyPayloadForTest(body, "/manhwa/36525/1807424"));
        assertEquals(token, Manga.ntkViewerImagesTokenForTest(body));
    }
}
