package ml.melun.mangaview.source.newxtoon

import java.io.IOException
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceGenre
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class NewxtoonSeriesCard(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val subtitle: String? = null,
    val status: SeriesStatus? = null,
)
data class NewxtoonChapter(val id: String, val title: String)
data class NewxtoonChapterPage(val chapters: List<NewxtoonChapter>, val nextPage: Int?)
data class NewxtoonChapterPagination(val url: String, val nextPage: Int?)
data class NewxtoonPage(val url: String, val width: Int?, val height: Int?)
data class NewxtoonSeriesDetails(
    val status: SeriesStatus?,
    val description: String?,
    val authors: String?,
)

/** A neighboring chapter the reader page linked through its own previous/next controls. */
data class NewxtoonReaderChapter(val seriesKey: String, val chapterKey: String)
data class NewxtoonReaderNavigation(val previous: NewxtoonReaderChapter?, val next: NewxtoonReaderChapter?)

/** Pure HTML parsing for the Newxtoon server-rendered pages. */
class NewxtoonHtmlParser(private val origin: String) {
    private val seriesLink = Regex("""(?:https?://[^/]+)?/comics/(\d+)(?:[?#].*)?$""")
    // Search pages render follow-up links as `&amp;page=` inside href attributes.
    private val pageLink = Regex("""[?&](?:amp;)?page=(\d+)""")
    private val genreLink = Regex("""[?&]genre=(\d+)""")
    private val chapterLink = Regex("""/comics/([^/?#]+)/chapters/([^/?#]+)""")

    fun genres(html: String): List<SourceGenre> {
        val document = Jsoup.parse(html, origin)
        val result = linkedMapOf<String, SourceGenre>()
        for (anchor in document.select("a[href*=genre=]")) {
            val id = genreLink.find(anchor.attr("href"))?.groupValues?.get(1) ?: continue
            val label = anchor.text().trim()
            if (label.isEmpty()) continue
            result.putIfAbsent(id, SourceGenre("genre:$id", label))
        }
        return result.values.toList()
    }

    fun seriesCards(html: String): List<NewxtoonSeriesCard> = seriesCards(Jsoup.parse(html, origin))

    fun seriesCards(document: Document): List<NewxtoonSeriesCard> {
        val result = linkedMapOf<String, NewxtoonSeriesCard>()
        for (anchor in document.select("a[href]")) {
            val id = seriesLink.matchEntire(anchor.attr("href").trim())?.groupValues?.get(1) ?: continue
            val title = anchor.selectFirst("h3")?.text()?.trim().orEmpty()
                .ifEmpty { anchor.text().trim() }
            if (title.isEmpty()) continue
            val cover = anchor.selectFirst("img.cover-image") ?: anchor.selectFirst("img[src]")
            val thumbnail = cover?.let { image ->
                image.absUrl("src").ifBlank { image.attr("src") }.ifBlank { null }
            }
            val subtitle = anchor.attr("aria-label").trim()
                .split(",")
                .map(String::trim)
                .drop(1)
                .filter(String::isNotEmpty)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" · ")
            val status = cardStatus(anchor)
            result.putIfAbsent(id, NewxtoonSeriesCard(id, title, thumbnail, subtitle, status))
        }
        return result.values.toList()
    }

    fun searchCards(html: String): List<NewxtoonSeriesCard> = searchCards(Jsoup.parse(html, origin))

    fun searchCards(document: Document): List<NewxtoonSeriesCard> {
        val cards = seriesCards(document)
        check(cards.isNotEmpty() || document.selectFirst("#page-search[name=q], #search-result-title") != null) {
            "뉴엑스툰 검색 응답을 확인할 수 없습니다. 다시 시도해 주세요"
        }
        return cards
    }

    fun seriesDetails(html: String): NewxtoonSeriesDetails {
        val document = Jsoup.parse(html, origin)
        val status = document.select("div > strong").firstNotNullOfOrNull { strong ->
            statusFrom(strong.text().trim())
        }
        val description = document.selectFirst("p[data-comic-description]")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val authors = document.select("h1#comic-title + p a")
            .map { it.text().trim() }
            .filter(String::isNotEmpty)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
        return NewxtoonSeriesDetails(status, description, authors)
    }

    private fun statusFrom(label: String): SeriesStatus? = when {
        label.contains("완결") -> SeriesStatus.COMPLETED
        label.contains("휴재") || label.contains("연재") -> SeriesStatus.ONGOING
        else -> null
    }

    /** The catalog marks completion inside the episode line, e.g. "43화(완결)". */
    private fun cardStatus(anchor: Element): SeriesStatus? {
        val paragraphs = anchor.select("p").map { it.text().trim() }
        if (paragraphs.any { it.contains("완결") }) return SeriesStatus.COMPLETED
        return if (paragraphs.any { it.contains("휴재") || it.contains("화") }) SeriesStatus.ONGOING else null
    }

    fun nextPage(html: String, current: Int): Int? {
        val pages = pageLink.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return if (pages.any { it > current }) current + 1 else null
    }

    /** Search must never follow pagination from scripts, other queries or unrelated catalogs. */
    fun nextSearchPage(html: String, query: String, current: Int): Int? =
        nextSearchPage(Jsoup.parse(html, origin), query, current)

    fun nextSearchPage(document: Document, query: String, current: Int): Int? =
        document.select("a[href]").mapNotNull { link ->
            val uri = runCatching { java.net.URI(link.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
            if (uri.path != "/search" || uri.host != java.net.URI(origin).host) return@mapNotNull null
            val params = runCatching { uri.rawQuery.orEmpty().split('&').associate { part ->
                java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            } }.getOrNull() ?: return@mapNotNull null
            if (params["q"] != query) return@mapNotNull null
            params["page"]?.toIntOrNull()?.takeIf { it > current }
        }.minOrNull()?.let { current + 1 }

    fun title(html: String): String? {
        val document = Jsoup.parse(html, origin)
        val meta = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        val source = meta?.takeIf { it.isNotEmpty() } ?: document.title()
        return source.substringBefore(" — ").substringBefore(" · ").trim().ifEmpty { null }
    }

    fun chapters(html: String): List<NewxtoonChapter> {
        val document = Jsoup.parse(html, origin)
        return document.select("a.chapter-item[data-chapter-id]").mapNotNull { anchor ->
            val id = anchor.attr("data-chapter-id").trim()
            if (id.isEmpty()) return@mapNotNull null
            val title = anchor.selectFirst("span.truncate")?.text()?.trim().orEmpty()
                .ifEmpty { anchor.text().trim() }
            NewxtoonChapter(id, title.ifEmpty { id })
        }.distinctBy(NewxtoonChapter::id)
    }

    /**
     * The series page embeds only the first chapter page next to the feed URL that serves the
     * remaining pages (`/comics/{id}/chapters`). A blank next page means the embedded list is all.
     */
    fun chapterPagination(html: String): NewxtoonChapterPagination? {
        val document = Jsoup.parse(html, origin)
        val scope = document.selectFirst("[data-chapters-url]") ?: return null
        val url = scope.absUrl("data-chapters-url").ifBlank { scope.attr("data-chapters-url") }.trim()
        if (url.isEmpty()) return null
        return NewxtoonChapterPagination(url, scope.attr("data-chapter-next-page").trim().toIntOrNull())
    }

    /** The chapter list header advertises the full chapter count, for example "회차 목록 총 185화". */
    fun chapterTotal(html: String): Int? {
        val document = Jsoup.parse(html, origin)
        val scope = document.selectFirst("#mobile-chapters-title, #chapters-title") ?: return null
        return TOTAL_CHAPTERS.find(scope.text())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Chapters per feed page, as declared by the series page. */
    fun chapterPageSize(html: String): Int? {
        val document = Jsoup.parse(html, origin)
        return document.selectFirst("[data-chapter-page-size]")
            ?.attr("data-chapter-page-size")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** The chapter feed returns rendered anchors plus the next page number, or null at the end. */
    fun chapterPage(json: String): NewxtoonChapterPage {
        // A truncated or malformed feed must surface as IOException so the fetch lane treats it
        // as a retryable transport failure instead of an unchecked crash that kills the list.
        try {
            val rendered = jsonString(json, "html").orEmpty()
            val nextPage = NEXT_PAGE_FIELD.find(json)?.groupValues?.get(1)
                ?.takeIf { it != "null" }
                ?.toIntOrNull()
            return NewxtoonChapterPage(chapters(rendered), nextPage)
        } catch (malformed: IllegalArgumentException) {
            throw IOException("NEWXTOON chapter feed is malformed", malformed)
        }
    }

    fun pages(html: String): List<NewxtoonPage> {
        val document = Jsoup.parse(html, origin)
        val result = mutableListOf<NewxtoonPage>()
        for (frame in document.select("div.reader-page-frame")) {
            val image = frame.selectFirst("img[src]") ?: continue
            val url = image.absUrl("src").ifBlank { image.attr("src") }
            if (url.isEmpty()) continue
            result += NewxtoonPage(url, image.attr("width").toIntOrNull(), image.attr("height").toIntOrNull())
        }
        return result
    }

    /**
     * The reader page links its previous and next chapter directly (`reader-side-previous` /
     * `reader-side-next`). When rendered, those controls are the document's own adjacency, so a
     * missing side means the series end rather than an unknown neighbor. Null when the page
     * renders no reader controls at all.
     */
    fun readerNavigation(html: String): NewxtoonReaderNavigation? {
        val document = Jsoup.parse(html, origin)
        val previous = document.selectFirst("a.reader-side-previous")?.let(::readerChapter)
        val next = document.selectFirst("a.reader-side-next")?.let(::readerChapter)
        if (previous == null && next == null) return null
        return NewxtoonReaderNavigation(previous, next)
    }

    private fun readerChapter(anchor: Element): NewxtoonReaderChapter? {
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        val match = chapterLink.find(href) ?: return null
        val series = match.groupValues[1].trim()
        val chapter = match.groupValues[2].trim()
        if (series.isEmpty() || chapter.isEmpty()) return null
        return NewxtoonReaderChapter(series, chapter)
    }

    /** Reads a top-level string field without pulling in a JSON dependency. */
    private fun jsonString(json: String, field: String): String? {
        val marker = "\"$field\""
        var index = json.indexOf(marker)
        while (index >= 0) {
            // An escaped quote inside a string value contains the marker verbatim (\"html\") —
            // the preceding backslash disqualifies it as a real field key.
            if (index > 0 && json[index - 1] == '\\') {
                index = json.indexOf(marker, index + marker.length)
                continue
            }
            var cursor = index + marker.length
            while (cursor < json.length && json[cursor].isWhitespace()) cursor += 1
            if (cursor < json.length && json[cursor] == ':') {
                cursor += 1
                while (cursor < json.length && json[cursor].isWhitespace()) cursor += 1
                if (cursor < json.length && json[cursor] == '"') return unescapeJson(json, cursor + 1)
            }
            index = json.indexOf(marker, index + marker.length)
        }
        return null
    }

    private fun unescapeJson(json: String, start: Int): String {
        val result = StringBuilder()
        var cursor = start
        while (cursor < json.length) {
            val character = json[cursor]
            if (character == '"') return result.toString()
            if (character != '\\') {
                result.append(character)
                cursor += 1
                continue
            }
            require(cursor + 1 < json.length) { "Truncated JSON escape in chapter feed" }
            val escaped = json[cursor + 1]
            if (escaped == 'u') {
                require(cursor + 5 < json.length) { "Truncated JSON unicode escape in chapter feed" }
                val codeUnit = json.substring(cursor + 2, cursor + 6).toIntOrNull(16)
                    ?: throw IllegalArgumentException("Malformed JSON unicode escape in chapter feed")
                result.append(codeUnit.toChar())
                cursor += 6
            } else {
                result.append(JSON_ESCAPES[escaped] ?: escaped)
                cursor += 2
            }
        }
        throw IllegalArgumentException("Unterminated JSON string in chapter feed")
    }

    private companion object {
        val NEXT_PAGE_FIELD = Regex(""""next_page"\s*:\s*(\d+|null)""")
        val TOTAL_CHAPTERS = Regex("""총\s*(\d+)\s*화""")
        val JSON_ESCAPES = mapOf(
            '"' to '"', '\\' to '\\', '/' to '/', 'n' to '\n', 'r' to '\r', 't' to '\t',
            'b' to '\b', 'f' to '\u000C',
        )
    }
}
