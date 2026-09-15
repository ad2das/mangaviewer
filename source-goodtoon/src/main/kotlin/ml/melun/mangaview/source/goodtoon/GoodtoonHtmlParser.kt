package ml.melun.mangaview.source.goodtoon

import java.net.URI
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSeriesDetails
import ml.melun.mangaview.source.SeriesStatus
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class GoodtoonViewerChapter(
    val slug: String,
    val title: String,
)

/** Pure DOM parsing for the Madara-based GoodToon catalog, series, chapter list and viewer. */
class GoodtoonHtmlParser {
    /** Catalog/search cards. The provider card markup carries no status; the route supplies it. */
    fun series(
        document: Document,
        seriesId: (GoodtoonSeriesKey) -> SeriesId,
        status: SeriesStatus? = null,
    ): List<SourceSeries> {
        val seen = LinkedHashMap<String, SourceSeries>()
        for (card in document.select("a.card[href], a[href][class*=card]")) {
            val slug = seriesSlug(card.attr("href")) ?: continue
            val title = card.selectFirst(".subject")?.text()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: card.selectFirst(".thumb img[alt]")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: continue
            val subtitle = card.selectFirst(".genre")?.text()?.trim()?.takeIf(String::isNotBlank)
            seen.putIfAbsent(
                slug,
                SourceSeries(
                    id = seriesId(GoodtoonSeriesKey(slug)),
                    title = title,
                    subtitle = subtitle,
                    thumbnailKey = thumbnail(card),
                    status = status,
                ),
            )
        }
        return seen.values.toList()
    }

    /**
     * Chapter fragment returned by `/manga/<slug>/ajax/chapters/?t=N`; newest first.
     * Delivery preserves provider document order, deduplicated by chapter slug on first occurrence.
     * The parsed sequence number is metadata only and never reorders this list.
     */
    fun chapters(document: Document, seriesId: SeriesId, seriesKey: GoodtoonSeriesKey): List<SourceEpisode> {
        val seen = LinkedHashMap<String, SourceEpisode>()
        for (link in chapterLinks(document)) {
            val slugs = chapterSlugs(link.attr("href")) ?: continue
            if (slugs.first != seriesKey.slug) continue
            val title = chapterTitle(link).ifBlank { slugs.second }
            val sequence = sequenceNumber(title, slugs.second)
            seen.putIfAbsent(
                slugs.second,
                SourceEpisode(
                    id = EpisodeId(seriesId, slugs.second),
                    title = title,
                    publishedAtEpochMillis = releaseDate(link),
                    sequenceNumber = sequence,
                ),
            )
        }
        return seen.values.toList()
    }

    fun mergeChapters(pages: List<List<SourceEpisode>>): List<SourceEpisode> {
        val seen = LinkedHashMap<String, SourceEpisode>()
        pages.flatten().forEach { episode -> seen.putIfAbsent(episode.id.remoteKey, episode) }
        return seen.values.toList()
    }

    fun details(document: Document): SourceSeriesDetails = SourceSeriesDetails(
        status = status(document),
        description = textOrNull(document.selectFirst(".manga-summary-desc, #manga-desc")),
        authors = textOrNull(document.selectFirst(".manga-summary-author .author-text")),
    )

    /** Absolute page image URLs of a viewer document, in reading order. */
    fun pageImages(document: Document, finalDocumentUrl: URI): List<String> {
        val records = imageRecords(document)
        val urls = LinkedHashSet<String>()
        records.forEach { element ->
            imageSource(element)?.let { resolved ->
                resolveImage(resolved, finalDocumentUrl)?.let(urls::add)
            }
        }
        return urls.toList()
    }

    /** Episode selected in the viewer's chapter dropdown; doubles as episode identity. */
    fun viewerChapter(document: Document, seriesKey: GoodtoonSeriesKey): GoodtoonViewerChapter? {
        val options = document.select("select.chapter-select-nav option")
        val selected = options.firstOrNull { it.hasAttr("selected") } ?: options.firstOrNull() ?: return null
        val slugs = chapterSlugs(selected.attr("value")) ?: return null
        if (slugs.first != seriesKey.slug) return null
        val title = selected.text().trim().ifBlank { slugs.second }
        return GoodtoonViewerChapter(slugs.second, title)
    }

    /** Full chapter dropdown of a viewer document, newest first. */
    fun viewerChapters(document: Document, seriesKey: GoodtoonSeriesKey): List<GoodtoonViewerChapter> =
        document.select("select.chapter-select-nav option").mapNotNull { option ->
            val slugs = chapterSlugs(option.attr("value")) ?: return@mapNotNull null
            if (slugs.first != seriesKey.slug) return@mapNotNull null
            GoodtoonViewerChapter(slugs.second, option.text().trim().ifBlank { slugs.second })
        }

    private fun status(document: Document): SeriesStatus? =
        document.select(".summary-meta-row .meta-item .meta-value")
            .asSequence()
            .map { it.text().trim() }
            .firstNotNullOfOrNull { label ->
                when (label) {
                    "연재중", "연재" -> SeriesStatus.ONGOING
                    "완결" -> SeriesStatus.COMPLETED
                    "휴재" -> SeriesStatus.HIATUS
                    else -> null
                }
            }

    private fun thumbnail(card: Element): String? =
        card.select(".thumb img, img").firstNotNullOfOrNull { image ->
            if (isPlatformIcon(image)) return@firstNotNullOfOrNull null
            imageSource(image)
        }

    private fun chapterLinks(document: Document): List<Element> {
        val primary = document.select("li.wp-manga-chapter a[href]")
        if (primary.isNotEmpty()) return primary
        return document.select(".listing-chapters_wrap a[href], #manga-chapters-holder a[href]")
    }

    private fun imageRecords(document: Document): List<Element> {
        val primary = document.select("img.wp-manga-chapter-img")
        if (primary.isNotEmpty()) return primary
        val secondary = document.select(".reading-content img, .page-break img, #images img")
        if (secondary.isNotEmpty()) return secondary
        return document.select("body img[data-src]")
    }

    private fun imageSource(element: Element): String? =
        IMAGE_ATTRIBUTES.firstNotNullOfOrNull { attribute ->
            element.attr(attribute).trim().takeIf(String::isNotBlank)
        }

    private fun resolveImage(value: String, finalDocumentUrl: URI): String? {
        val lower = value.lowercase()
        if (lower.startsWith("data:")) return null
        if (!IMAGE_FILE_PATTERN.matches(lower)) return null
        val resolved = runCatching { finalDocumentUrl.resolve(value) }.getOrNull() ?: return null
        if (resolved.scheme !in setOf("http", "https") || resolved.host.isNullOrBlank()) return null
        return resolved.toString()
    }

    private fun isPlatformIcon(element: Element): Boolean {
        val source = element.attr("src")
        return element.hasClass("platform-icon") || source.contains("/icons/") ||
            source.contains("/themes/")
    }

    private fun chapterTitle(link: Element): String =
        link.clone()
            .also { it.select("span.up-badge-inline, span.up-badge, .ep-date-ov, .chapter-release-date").remove() }
            .text()
            .replace(WHITESPACE, " ")
            .trim()

    private fun textOrNull(element: Element?): String? =
        element?.text()?.replace(WHITESPACE, " ")?.trim()?.takeIf(String::isNotBlank)

    private fun chapterSlugs(href: String): Pair<String, String>? {
        val path = runCatching { URI(href) }.getOrNull()?.path ?: return null
        val match = CHAPTER_PATH.matchEntire(path) ?: return null
        val series = urlDecodeOrNull(match.groupValues[1])?.takeIf(String::isNotBlank) ?: return null
        val episode = urlDecodeOrNull(match.groupValues[2])?.takeIf(String::isNotBlank) ?: return null
        return series to episode
    }

    private fun seriesSlug(href: String): String? {
        val uri = runCatching { URI(href) }.getOrNull() ?: return null
        if (uri.scheme != null && uri.scheme !in setOf("http", "https")) return null
        val match = SERIES_PATH.matchEntire(uri.path ?: return null) ?: return null
        return urlDecodeOrNull(match.groupValues[1])?.takeIf(String::isNotBlank)
    }

    private fun sequenceNumber(title: String, slug: String): Double? {
        val match = SEQUENCE.find(title) ?: return slug.toDoubleOrNull()
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun releaseDate(link: Element): Long? {
        val value = link.selectFirst(".chapter-release-date i")?.text()
            ?: link.parents().firstOrNull()?.selectFirst(".chapter-release-date i")?.text()
            ?: return null
        val match = RELEASE_DATE.find(value.trim()) ?: return null
        val calendar = GregorianCalendar(
            2000 + match.groupValues[1].toInt(),
            match.groupValues[2].toInt() - 1,
            match.groupValues[3].toInt(),
        )
        calendar.timeZone = TimeZone.getTimeZone("Asia/Seoul")
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private companion object {
        val SERIES_PATH = Regex("/manga/([^/]+)/?")
        val CHAPTER_PATH = Regex("/manga/([^/]+)/([^/]+)/?")
        val SEQUENCE = Regex("([0-9]+)\\s*화")
        val RELEASE_DATE = Regex("([0-9]{2})\\.([0-9]{2})\\.([0-9]{2})")
        val IMAGE_FILE_PATTERN = Regex(".*\\.(?:jpe?g|png|webp|gif)(?:[?#].*)?$")
        val WHITESPACE = Regex("\\s+")
        val IMAGE_ATTRIBUTES = listOf("data-src", "data-lazy-src", "data-original", "src")
    }
}
