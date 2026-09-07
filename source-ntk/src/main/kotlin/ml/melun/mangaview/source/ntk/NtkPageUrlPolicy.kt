package ml.melun.mangaview.source.ntk

import java.net.URI

internal fun validateNtkDocumentIdentity(document: NtkEpisodeDocument, expectedPath: String) {
    val final = URI(document.finalUrl)
    val origin = URI(document.origin)
    require(final.scheme == origin.scheme && final.authority == origin.authority) {
        "NTK episode document changed origin after resolution"
    }
    require(final.path == expectedPath) {
        "NTK episode document redirected to another episode"
    }
}

internal fun resolveNtkPageUrl(base: URI, value: String): String =
    if (value.startsWith("https://") || value.startsWith("http://")) value
    else base.resolve(value).toString()

internal fun ntkFetchSite(referer: String, target: String): String = runCatching {
    val from = URI(referer)
    val to = URI(target)
    if (from.scheme == to.scheme && from.authority == to.authority) "same-origin" else "cross-site"
}.getOrDefault("cross-site")
