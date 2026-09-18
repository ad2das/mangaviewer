package ml.melun.mangaview.activity

import java.io.IOException
import ml.melun.mangaview.engine.content.PageHttpException
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerFailureMessagesTest {
    @Test fun httpFailuresKeepTheStatusInKoreanCopy() {
        assertEquals("페이지를 불러오지 못했습니다 (HTTP 403)", viewerFailureMessage(PageHttpException(403)))
        assertEquals("페이지를 불러오지 못했습니다 (HTTP 500)", viewerFailureMessage(PageHttpException(500)))
    }

    @Test fun koreanProviderMessagesPassThrough() {
        assertEquals("회차 응답이 늦어지고 있습니다", viewerFailureMessage(IOException("회차 응답이 늦어지고 있습니다")))
    }

    @Test fun englishProviderMessagesFallBackToKoreanCopy() {
        assertEquals(
            "페이지를 불러오지 못했습니다",
            viewerFailureMessage(IOException("Every NTK document protocol failed (SSLException:x)")),
        )
        assertEquals(
            "페이지를 불러오지 못했습니다",
            viewerFailureMessage(IOException("Page request returned HTTP 403")),
        )
    }

    @Test fun blankOrMissingMessagesFallBackToKoreanCopy() {
        assertEquals("페이지를 불러오지 못했습니다", viewerFailureMessage(IOException("")))
        assertEquals("페이지를 불러오지 못했습니다", viewerFailureMessage(RuntimeException()))
    }
}
