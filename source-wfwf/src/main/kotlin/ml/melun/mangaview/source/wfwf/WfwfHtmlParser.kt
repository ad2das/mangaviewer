package ml.melun.mangaview.source.wfwf

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class WfwfViewerMetadata(
    val title: String?,
    val previousEpisodeKey: String?,
    val nextEpisodeKey: String?,
    val navigationKnown: Boolean,
)

class WfwfHtmlParser {
    fun search(document: Document, sourceSeriesId: (WfwfSeriesKey) -> SeriesId): List<SourceSeries> {
        val found = linkedMapOf<String, SourceSeries>()
        document.select("a[href]").forEach { link ->
            val key = seriesKey(link.attr("href")) ?: return@forEach
            val title = title(link)
            val candidate = SourceSeries(
                id = sourceSeriesId(key),
                title = title.ifBlank { "#${key.titleId}" },
                subtitle = subtitle(link),
                thumbnailKey = imageUrl(link),
            )
            val old = found[key.encode()]
            if (old == null || (old.title.startsWith('#') && title.isNotBlank())) {
                found[key.encode()] = candidate.copy(thumbnailKey = candidate.thumbnailKey ?: old?.thumbnailKey)
            } else if (old.thumbnailKey == null && candidate.thumbnailKey != null) {
                found[key.encode()] = old.copy(thumbnailKey = candidate.thumbnailKey)
            }
        }
        return found.values.toList()
    }

    fun episodes(document: Document, seriesId: SeriesId, key: WfwfSeriesKey): List<SourceEpisode> {
        val parsed = linkedMapOf<Long, SourceEpisode>()
        document.select("a[href*='toon='][href*='num=']").forEach { link ->
            val href = link.attr("href")
            if (queryLong(href, "toon") != key.titleId) return@forEach
            val episodeNumber = queryLong(href, "num") ?: return@forEach
            if (episodeNumber <= 0L) return@forEach
            val title = episodeTitle(link)
            if (!isEpisodeEntry(link, title)) return@forEach
            val candidate = SourceEpisode(
                id = EpisodeId(seriesId, episodeNumber.toString()),
                title = title.ifBlank { episodeNumber.toString() },
                publishedAtEpochMillis = null,
                sequenceNumber = episodeNumber.toDouble(),
            )
            val existing = parsed[episodeNumber]
            if (existing == null || episodeTitleQuality(candidate.title) > episodeTitleQuality(existing.title)) {
                parsed[episodeNumber] = candidate
            }
        }
        return orderEpisodes(parsed.values.toList())
    }

    fun catalogPageNumbers(document: Document, key: WfwfSeriesKey): List<Int> =
        document.select("a[href*='toon='][href*='pg=']").mapNotNull { link ->
            val href = link.attr("href")
            if (queryLong(href, "toon") != key.titleId) return@mapNotNull null
            queryLong(href, "pg")?.takeIf { it in 1..MAX_CATALOG_PAGES }?.toInt()
        }.plus(1).distinct().sorted()

    fun mergeEpisodePages(pages: List<List<SourceEpisode>>): List<SourceEpisode> =
        orderEpisodes(pages.flatten().distinctBy(SourceEpisode::id))

    private fun orderEpisodes(episodes: List<SourceEpisode>): List<SourceEpisode> =
        episodes.sortedWith(compareByDescending<SourceEpisode> {
            it.sequenceNumber ?: visibleNumber(it.title)
        }.thenByDescending {
            it.id.remoteKey.toLongOrNull() ?: 0L
        })

    fun pageImages(document: Document): List<String> {
        val primary = document.select(CONTENT_IMAGE_SELECTORS)
            .filterNot(::hasBlockedContext)
            .flatMap(::imageCandidates)
            .filter(::isPageImage)
        val candidates = if (primary.isNotEmpty()) primary else {
            document.select("body img")
                .filter(::hasLazyPageSource)
                .filterNot(::hasBlockedContext)
                .flatMap(::imageCandidates)
                .filter(::isPageImage)
        }
        return candidates.map { URI(document.baseUri()).resolve(it).toString() }.distinct()
    }

    internal fun viewerMetadata(
        document: Document,
        key: WfwfSeriesKey,
        currentEpisodeKey: String,
    ): WfwfViewerMetadata {
        val title = document.selectFirst(".vbar-title .vt-name, .vt-name")
            ?.text()
            ?.clean()
            ?.takeIf(String::isNotEmpty)
        val navigation = document.selectFirst(".vnav-row")
            ?: return WfwfViewerMetadata(title, null, null, navigationKnown = false)
        val current = currentEpisodeKey.toLongOrNull()
            ?: return WfwfViewerMetadata(title, null, null, navigationKnown = false)
        val neighbors = navigation.select("a.vnav-btn[href*='toon='][href*='num=']")
            .mapNotNull { link ->
                val href = link.attr("href")
                val seriesKey = queryLong(href, "toon")
                val episodeKey = queryLong(href, "num")
                episodeKey?.takeIf { seriesKey == key.titleId && it > 0L && it != current }
            }
            .distinct()
        return WfwfViewerMetadata(
            title = title,
            previousEpisodeKey = neighbors.filter { it < current }.maxOrNull()?.toString(),
            nextEpisodeKey = neighbors.filter { it > current }.minOrNull()?.toString(),
            navigationKnown = true,
        )
    }

    private fun seriesKey(href: String): WfwfSeriesKey? {
        val id = queryLong(href, "toon") ?: pathId(href) ?: return null
        val lower = href.lowercase()
        val kind = when {
            "/cl?" in lower || "/cv?" in lower || "/manhwa/" in lower -> WfwfKind.COMIC
            "/list?" in lower || "/view?" in lower || "/webtoon/" in lower -> WfwfKind.WEBTOON
            else -> return null
        }
        return WfwfSeriesKey(kind, id)
    }

    private fun imageCandidates(element: Element): List<String> = IMAGE_ATTRIBUTES.mapNotNull { attribute ->
        element.attr(attribute).trim().takeIf(String::isNotEmpty)
    }

    private fun hasLazyPageSource(element: Element): Boolean =
        LAZY_IMAGE_ATTRIBUTES.any { element.hasAttr(it) }

    private fun hasBlockedContext(element: Element): Boolean {
        val ownMarker = "${element.attr("alt")} ${element.attr("title")} ${element.className()}"
            .lowercase()
        if (BLOCKED_CONTEXT_TOKENS.any(ownMarker::contains)) return true
        var current = element.parent()
        while (current != null) {
            val marker = "${current.id()} ${current.className()}".lowercase()
            if (BLOCKED_CONTEXT_TOKENS.any(marker::contains)) return true
            current = current.parent()
        }
        return false
    }

    private fun isPageImage(value: String): Boolean {
        val lower = value.lowercase()
        if (BLOCKED_IMAGE_TOKENS.any(lower::contains)) return false
        return lower.matches(Regex(".*\\.(?:jpe?g|png|webp)(?:[?#].*)?$")) ||
            listOf("/data/", "/toon/", "/webtoon/", "/comic/").any(lower::contains)
    }

    private fun title(link: Element): String {
        val own = link.selectFirst(TITLE_SELECTORS)?.text()?.clean().orEmpty()
        if (own.isNotEmpty()) return own
        val imageLabel = link.selectFirst("img")?.let { image ->
            image.attr("alt").clean().ifEmpty { image.attr("title").clean() }
        }.orEmpty()
        if (imageLabel.isNotEmpty()) return imageLabel
        link.ownText().clean().takeIf(String::isNotEmpty)?.let { return it }
        var context = link.parent()
        repeat(3) {
            val candidate = context?.selectFirst(TITLE_SELECTORS)?.text()?.clean().orEmpty()
            if (candidate.isNotEmpty()) return candidate
            context = context?.parent()
        }
        return ""
    }

    private fun episodeTitle(link: Element): String =
        link.selectFirst(".subject")?.ownText()?.clean() ?: link.ownText().clean()

    private fun isEpisodeEntry(link: Element, title: String): Boolean {
        if (NON_EPISODE_LABELS.any(title::contains)) return false
        var context: Element? = link
        repeat(4) {
            val marker = "${context?.id().orEmpty()} ${context?.className().orEmpty()}".lowercase()
            if (NON_EPISODE_CONTEXT.any(marker::contains)) return false
            context = context?.parent()
        }
        return true
    }

    private fun episodeTitleQuality(title: String): Int =
        (if (Regex("[0-9]+(?:\\.[0-9]+)?\\s*화").containsMatchIn(title)) 2 else 0) +
            (if (title.any(Char::isLetter)) 1 else 0)

    private fun imageUrl(link: Element): String? {
        var context: Element? = link
        repeat(4) {
            context?.selectFirst("img")?.let { image ->
                IMAGE_ATTRIBUTES.firstNotNullOfOrNull {
                    image.attr(it).trim().takeIf(String::isNotEmpty)
                }?.let { return it }
            }
            context = context?.parent()
        }
        return null
    }

    private fun subtitle(link: Element): String? {
        var context: Element? = link
        repeat(4) {
            val value = context?.selectFirst(SUBTITLE_SELECTORS)?.text()?.clean().orEmpty()
            if (value.isNotEmpty()) return value
            context = context?.parent()
        }
        return null
    }

    private fun queryLong(href: String, key: String): Long? = runCatching {
        Regex("(?:[?&])${Regex.escape(key)}=([0-9]+)").find(href)?.groupValues?.get(1)?.toLong()
    }.getOrNull()

    private fun pathId(href: String): Long? =
        Regex("/(?:manhwa|webtoon)/([0-9]+)", RegexOption.IGNORE_CASE)
            .find(href)?.groupValues?.get(1)?.toLongOrNull()

    private fun visibleNumber(value: String): Double =
        Regex("([0-9]+(?:\\.[0-9]+)?)").find(value)?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0

    private fun String.clean(): String = replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val CONTENT_IMAGE_SELECTORS =
            ".viewer-wrap img, .viewer-content img, .comic-viewer img, .webtoon-viewer img, " +
                "div.image-view img, div.view-padding img, section.webtoon-body img, " +
                "div.toon-view img, #toon_img img, #viewer img, article.reader img"
        const val TITLE_SELECTORS =
            "h1, h2, h3, h4, .title, .subject, .toon-title, .webtoon-title, .item-title, " +
                ".post-title, .name, strong, [data-title]"
        const val SUBTITLE_SELECTORS =
            ".genre, .genres, .author, .writer, .artist, .meta, [data-genre], [data-author]"
        val LAZY_IMAGE_ATTRIBUTES = listOf("data-original", "data-src", "data-lazy-src", "data-url")
        val IMAGE_ATTRIBUTES = LAZY_IMAGE_ATTRIBUTES + "src"
        val BLOCKED_CONTEXT_TOKENS = listOf(
            "광고", "advert", "banner", "sponsor", "popup", "quick", "floating", "recommend",
        )
        val BLOCKED_IMAGE_TOKENS = listOf(
            "sprite", "logo", "banner", "advert", "sponsor", "popup", "/ad/", "/ads/", "blank", "loading",
        )
        val NON_EPISODE_LABELS = listOf(
            "최신화 보기", "첫화부터", "처음부터", "정주행", "이어보기", "전체보기", "목록으로",
        )
        val NON_EPISODE_CONTEXT = listOf(
            "quick-read", "quick_read", "shortcut", "hero-action", "read-action",
        )
        const val MAX_CATALOG_PAGES = 100L
    }
}
