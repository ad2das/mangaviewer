package ml.melun.mangaview.source.newxtoon

import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceGenre
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class NewxtoonSeriesCard(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val subtitle: String? = null,
    val status: SeriesStatus? = null,
)
data class NewxtoonChapter(val id: String, val title: String)
data class NewxtoonPage(val url: String, val width: Int?, val height: Int?)
data class NewxtoonSeriesDetails(
    val status: SeriesStatus?,
    val description: String?,
    val authors: String?,
)

/** Pure HTML parsing for the Newxtoon server-rendered pages. */
class NewxtoonHtmlParser(private val origin: String) {
    private val seriesLink = Regex("""(?:https?://[^/]+)?/comics/(\d+)(?:[?#].*)?$""")
    private val pageLink = Regex("""[?&]page=(\d+)""")
    private val genreLink = Regex("""[?&]genre=(\d+)""")

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

    fun seriesCards(html: String): List<NewxtoonSeriesCard> {
        val document = Jsoup.parse(html, origin)
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
}
