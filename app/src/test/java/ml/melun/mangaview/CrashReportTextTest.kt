package ml.melun.mangaview

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTextTest {
    private fun decodeQuery(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { pair ->
            val key = pair.substringBefore('=')
            key to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8.name())
        }

    @Test fun usesRecordedSummaryFirstAndFallsBackToReasonThenKind() {
        assertEquals(
            "java.lang.IllegalStateException: boom",
            CrashReportText.summary("kind=crash\nsummary=java.lang.IllegalStateException: boom\n\nmore"),
        )
        assertEquals("비정상 종료: native-crash", CrashReportText.summary("kind=exit\nreason=native-crash (5)\nrest"))
        assertEquals("앱 크래시", CrashReportText.summary("kind=crash\n\njava.lang.RuntimeException"))
        assertEquals("앱 오류", CrashReportText.summary("no headers here"))
    }

    @Test fun buildsPrefilledIssueUrlWithTitleAndBody() {
        val report = "kind=crash\nsummary=java.lang.RuntimeException: boom\ntime=now\n\nstack"
        val query = decodeQuery(CrashReportText.issueUrl(report, baseUrl = "https://example.test/issues/new"))
        assertEquals("[Crash] java.lang.RuntimeException: boom", query.getValue("title"))
        assertEquals(report, query.getValue("body"))
    }

    @Test fun truncatesLongBodyAndTellsTheUserToPasteFromClipboard() {
        val report = "kind=crash\nsummary=x\n" + "a".repeat(10_000)
        val body = CrashReportText.issueBody(report)
        assertTrue(body.length < report.length)
        assertTrue(body.endsWith(CrashReportText.TRUNCATED_BODY_NOTE))
        val short = "kind=exit\nsummary=y\nshort"
        assertEquals(short, CrashReportText.issueBody(short))
    }

    @Test fun capsTitleAndPreviewLength() {
        val title = CrashReportText.issueTitle("kind=crash\nsummary=" + "e".repeat(400))
        assertTrue(title.length <= 120)
        val preview = CrashReportText.preview("kind=exit\nsummary=z\n" + "b".repeat(10_000))
        assertTrue(preview.length < 4_100)
        assertTrue(preview.endsWith("(전체 내용은 복사와 리포트에 포함됩니다)"))
        assertEquals("kind=exit\nsummary=z\ntail", CrashReportText.preview("kind=exit\nsummary=z\ntail"))
    }
}
