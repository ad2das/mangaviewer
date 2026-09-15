package ml.melun.mangaview

import java.net.URLEncoder

/**
 * Turns a crash report into what the report dialog shows and what the GitHub new-issue form
 * receives. Kept free of Android types so the formatting rules stay unit-testable.
 */
internal object CrashReportText {
    const val ISSUE_URL = "https://github.com/ad2das/mangaviewer/issues/new"

    private const val MAX_TITLE_CHARS = 110
    private const val MAX_SUMMARY_CHARS = 160
    private const val MAX_BODY_CHARS = 4_000
    private const val MAX_PREVIEW_CHARS = 4_000

    const val TRUNCATED_BODY_NOTE =
        "\n\n(로그가 길어 일부만 실렸습니다. 전체 내용은 클립보드에 복사되어 있으니 본문에 붙여넣어 주세요.)"

    /** One-line description of the failure: the recorded summary, else the exit reason, else the kind. */
    fun summary(report: String): String {
        val lines = report.lineSequence().map { it.trim() }
        lines.firstOrNull { it.startsWith(SUMMARY_PREFIX) && it.length > SUMMARY_PREFIX.length }
            ?.let { return it.removePrefix(SUMMARY_PREFIX).take(MAX_SUMMARY_CHARS) }
        lines.firstOrNull { it.startsWith(REASON_PREFIX) && it.length > REASON_PREFIX.length }
            ?.let { return "비정상 종료: " + it.removePrefix(REASON_PREFIX).substringBefore(" (").trim() }
        return when (lines.firstOrNull { it.startsWith(KIND_PREFIX) }?.removePrefix(KIND_PREFIX)) {
            "crash" -> "앱 크래시"
            "exit" -> "비정상 종료"
            else -> "앱 오류"
        }
    }

    /** What the dialog displays; the full report still reaches the clipboard and the issue body. */
    fun preview(report: String): String =
        if (report.length <= MAX_PREVIEW_CHARS) report
        else report.take(MAX_PREVIEW_CHARS) + "\n\n… (전체 내용은 복사와 리포트에 포함됩니다)"

    fun issueTitle(report: String): String = "[Crash] " + summary(report).take(MAX_TITLE_CHARS)

    fun issueBody(report: String): String =
        if (report.length <= MAX_BODY_CHARS) report else report.take(MAX_BODY_CHARS) + TRUNCATED_BODY_NOTE

    fun issueUrl(report: String, baseUrl: String = ISSUE_URL): String =
        baseUrl + "?title=" + encode(issueTitle(report)) + "&body=" + encode(issueBody(report))

    private const val SUMMARY_PREFIX = "summary="
    private const val REASON_PREFIX = "reason="
    private const val KIND_PREFIX = "kind="

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
