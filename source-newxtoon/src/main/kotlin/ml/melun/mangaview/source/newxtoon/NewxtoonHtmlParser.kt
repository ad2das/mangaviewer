package ml.melun.mangaview.source.newxtoon

import ml.melun.mangaview.source.SourceGenre
import org.jsoup.Jsoup

internal data class NewxtoonSeriesCard(val id: String, val title: String, val thumbnailUrl: String?)
internal data class NewxtoonChapter(val id: String, val title: String)
internal data class NewxtoonPage(val url: String, val width: Int?, val height: Int?)

/** Pure HTML parsing for the Newxtoon server-rendered pages. */
internal class NewxtoonHtmlParser(private val origin: String) {
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
            val title = anchor.text().trim()
            if (title.isEmpty()) continue
            val thumbnail = anchor.selectFirst("img[src]")?.let { image ->
                image.absUrl("src").ifBlank { image.attr("src") }.ifBlank { null }
            }
            result.putIfAbsent(id, NewxtoonSeriesCard(id, title, thumbnail))
        }
        return result.values.toList()
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
