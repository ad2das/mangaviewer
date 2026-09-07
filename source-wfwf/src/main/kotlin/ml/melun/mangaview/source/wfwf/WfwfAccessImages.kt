package ml.melun.mangaview.source.wfwf

import java.net.URI
import java.util.IdentityHashMap
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class WfwfAccessImage(
    val sourceRecord: String,
    val candidates: List<URI>,
) {
    val primary: URI get() = candidates.first()
}

/** Pure DOM image selection for the WFWF access plan. */
internal object WfwfAccessImages {
    private const val CONTENT_IMAGE_SELECTORS =
        ".viewer-wrap img, .viewer-content img, .comic-viewer img, .webtoon-viewer img, " +
            "div.image-view img, div.view-padding img, section.webtoon-body img, " +
            "div.toon-view img, #toon_img img, #viewer img, article.reader img"
    private val LAZY_IMAGE_ATTRIBUTES = listOf("data-original", "data-src", "data-lazy-src", "data-url")
    private val IMAGE_ATTRIBUTES = LAZY_IMAGE_ATTRIBUTES + "src"
    private val IMAGE_FILE_PATTERN = Regex(".*\\.(?:jpe?g|png|webp)(?:[?#].*)?$")
    private val IMAGE_PATH_HINTS = listOf("/data/", "/toon/", "/webtoon/", "/comic/")
    private val BLOCKED_CONTEXT_TOKENS = listOf(
        "광고", "advert", "banner", "sponsor", "popup", "quick", "floating", "recommend",
    )
    private val BLOCKED_IMAGE_TOKENS = listOf(
        "sprite", "logo", "banner", "advert", "sponsor", "popup", "/ad/", "/ads/", "blank", "loading",
    )

    fun select(document: Document, finalDocumentUrl: URI): List<WfwfAccessImage> {
        val allImages = document.select("img")
        val primary = selectRecords(
            uniqueElements(document.select(CONTENT_IMAGE_SELECTORS).filterNot(::hasBlockedContext)),
            allImages,
            finalDocumentUrl,
        )
        if (primary.isNotEmpty()) return primary
        return selectRecords(
            uniqueElements(document.select("body img")
                .filter(::hasLazyPageSource)
                .filterNot(::hasBlockedContext)),
            allImages,
            finalDocumentUrl,
        )
    }

    private fun selectRecords(
        elements: List<Element>,
        allImages: List<Element>,
        finalDocumentUrl: URI,
    ): List<WfwfAccessImage> = elements.mapNotNull { element ->
        val index = allImages.indexOfFirst { it === element }
        if (index < 0) return@mapNotNull null
        val lazyCandidates = LAZY_IMAGE_ATTRIBUTES.mapNotNull { attribute ->
            resolveCandidate(element.attr(attribute), finalDocumentUrl)
        }
        val primary = lazyCandidates.firstOrNull()
        val source = resolveCandidate(element.attr(IMAGE_ATTRIBUTES.last()), finalDocumentUrl)
        val first = primary ?: source ?: return@mapNotNull null
        val candidates = LinkedHashSet<URI>()
        lazyCandidates.forEach(candidates::add)
        if (source != null && (primary == null || mirrorOf(first, source))) candidates += source
        WfwfAccessImage("img:$index", candidates.toList())
    }

    private fun resolveCandidate(raw: String, finalDocumentUrl: URI): URI? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val lower = value.lowercase()
        if (lower.startsWith("data:") || BLOCKED_IMAGE_TOKENS.any(lower::contains)) return null
        if (!isPageImage(value)) return null
        return runCatching { finalDocumentUrl.resolve(value) }.getOrNull()?.takeIf { uri ->
            uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
        }?.takeIf { !it.host.isNullOrBlank() }
    }

    private fun mirrorOf(primary: URI, source: URI): Boolean =
        primary.rawPath == source.rawPath && primary.rawQuery == source.rawQuery &&
            originOf(primary) != originOf(source)

    private fun originOf(uri: URI): String {
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val port = if (uri.port >= 0) uri.port else when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        return "$scheme://$host:$port"
    }

    private fun uniqueElements(elements: Iterable<Element>): List<Element> {
        val seen = IdentityHashMap<Element, Boolean>()
        return elements.filter { seen.put(it, true) == null }
    }

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

    private fun hasLazyPageSource(element: Element): Boolean =
        LAZY_IMAGE_ATTRIBUTES.any(element::hasAttr)

    private fun isPageImage(value: String): Boolean {
        val lower = value.lowercase()
        return lower.matches(IMAGE_FILE_PATTERN) ||
            IMAGE_PATH_HINTS.any(lower::contains)
    }

}
