package ml.melun.mangaview.source.ntk

import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the response of the viewer's image API into page requests.
 *
 * Page URLs are opaque capabilities. Some provider CDNs deliberately give image payloads a font
 * or extensionless pathname, so identity must not be inferred from a filename. A protected
 * manifest is authoritative only when it came from the exact image API and forms the exact page
 * sequence declared by the episode document. The data layer still verifies the downloaded bytes
 * before publishing anything to the decoder.
 */
internal class NtkBrowserManifestParser {
    fun parse(
        payload: String,
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> {
        val envelope = JSONObject(payload)
        validateIdentity(envelope, document, descriptor)
        return parseEnvelope(envelope, descriptor.expectedPageCount)
    }

    fun parse(payload: String, expectedPageCount: Int? = null): List<NtkPageRequest> {
        val envelope = JSONObject(payload)
        return parseEnvelope(envelope, expectedPageCount)
    }

    private fun parseEnvelope(
        envelope: JSONObject,
        expectedPageCount: Int?,
    ): List<NtkPageRequest> {
        val endpoint = envelope.optString("endpoint", "")
        require(endpoint in IMAGE_API_PATHS) { "NTK capture did not come from an image API" }
        require(envelope.optBoolean("ok", false)) { "NTK image API response was not successful" }
        require(responseCanContainJson(envelope.optString("responseContentType", ""))) {
            "NTK image API returned a non-JSON document"
        }
        val responsePath = runCatching {
            URI(envelope.optString("responseUrl", "")).path
        }.getOrNull()
        require(responsePath.isNullOrBlank() || responsePath == endpoint) {
            "NTK image API response identity changed"
        }

        val array = envelope.optJSONArray("images") ?: JSONArray()
        if (expectedPageCount != null) {
            return exactDeclaredPages(array, expectedPageCount)
        }
        val pages = sortedMapOf<Int, NtkPageRequest>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val page = item.optInt("page", 0)
            if (page <= 0 || explicitNonImageMime(item)) continue
            val candidates = candidates(item)
                .mapNotNull { candidate -> evidencedPageUrl(candidate, item) }
                .distinct()
            val primary = candidates.firstOrNull() ?: continue
            pages.putIfAbsent(page, NtkPageRequest(primary, candidates.drop(1)))
        }
        require(pages.isNotEmpty()) { "NTK browser returned no image pages" }
        return pages.values.toList()
    }

    private fun validateIdentity(
        envelope: JSONObject,
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ) {
        require(envelope.optString("endpoint", "") == descriptor.apiPath) {
            "NTK captured endpoint does not match the viewer descriptor"
        }
        require(envelope.optString("requestMethod", "").equals("POST", ignoreCase = true)) {
            "NTK image manifest was not requested with POST"
        }
        require(responseCanContainJson(envelope.optString("requestContentType", ""))) {
            "NTK image manifest request was not JSON"
        }
        require(envelope.optString("requestWorkId", "") == descriptor.workId) {
            "NTK image manifest work identity changed"
        }
        require(envelope.optString("requestEpisodeId", "") == descriptor.episodeId) {
            "NTK image manifest episode identity changed"
        }
        val requestToken = envelope.optString("requestToken", "")
        require(requestToken.isNotBlank()) { "NTK image manifest token is missing" }
        val identity = tokenIdentity(requestToken)
        val matchingDynamicIdentity = identity?.let {
            it.workId == descriptor.workId && it.episodeId == descriptor.episodeId
        } == true
        require(requestToken == descriptor.token || matchingDynamicIdentity) {
            "NTK image manifest token identity does not match the viewer descriptor"
        }
        identity?.let {
            require(it.workId == descriptor.workId && it.episodeId == descriptor.episodeId) {
                "NTK image manifest token identity changed"
            }
            val expectedKind = descriptor.apiPath.removePrefix("/api/").removeSuffix("-images")
            require(it.kind == expectedKind) { "NTK image manifest token kind changed" }
        }
        val response = URI(envelope.optString("responseUrl", ""))
        val expectedOrigin = URI(document.origin)
        require(
            response.scheme == expectedOrigin.scheme &&
                response.authority == expectedOrigin.authority &&
                response.path == descriptor.apiPath
        ) { "NTK image manifest response changed origin or path" }
    }

    private fun tokenIdentity(token: String): TokenIdentity? = runCatching {
        val payload = token.substringBefore('.')
        val decoded = String(decodeBase64Url(payload), StandardCharsets.UTF_8)
        val claims = JSONObject(decoded)
        TokenIdentity(
            workId = claims.getString("w"),
            episodeId = claims.getString("e"),
            kind = claims.getString("t"),
        )
    }.getOrNull()

    private fun decodeBase64Url(value: String): ByteArray {
        val encoded = value.trimEnd('=')
        require(encoded.length % 4 != 1) { "Invalid base64url length" }
        val output = ByteArray(encoded.length * 6 / 8)
        var accumulator = 0
        var bitCount = 0
        var outputIndex = 0
        encoded.forEach { character ->
            accumulator = (accumulator shl 6) or base64UrlDigit(character)
            bitCount += 6
            if (bitCount >= 8) {
                bitCount -= 8
                output[outputIndex++] = (accumulator shr bitCount).toByte()
                accumulator = accumulator and ((1 shl bitCount) - 1)
            }
        }
        require(bitCount == 0 || accumulator == 0) { "Invalid base64url tail" }
        return output.copyOf(outputIndex)
    }

    private fun base64UrlDigit(character: Char): Int = when (character) {
        in 'A'..'Z' -> character - 'A'
        in 'a'..'z' -> character - 'a' + 26
        in '0'..'9' -> character - '0' + 52
        '-' -> 62
        '_' -> 63
        else -> throw IllegalArgumentException("Invalid base64url character")
    }

    private fun exactDeclaredPages(
        array: JSONArray,
        expectedPageCount: Int,
    ): List<NtkPageRequest> {
        require(expectedPageCount > 0) { "NTK expected page count must be positive" }
        require(array.length() == expectedPageCount) {
            "NTK image API returned ${array.length()} entries; expected $expectedPageCount"
        }
        val pages = sortedMapOf<Int, NtkPageRequest>()
        for (index in 0 until array.length()) {
            val item = requireNotNull(array.optJSONObject(index)) {
                "NTK image API page entry is not an object"
            }
            val page = item.optInt("page", 0)
            require(page in 1..expectedPageCount) { "NTK image API page number is out of range" }
            val urls = candidates(item).mapNotNull(::opaqueHttpUrl).distinct()
            require(urls.isNotEmpty()) { "NTK image API page $page has no HTTP candidates" }
            require(pages.put(page, NtkPageRequest(urls.first(), urls.drop(1))) == null) {
                "NTK image API contains duplicate page $page"
            }
        }
        require(pages.keys.toList() == (1..expectedPageCount).toList()) {
            "NTK image API page sequence is incomplete"
        }
        return pages.values.toList()
    }

    private fun candidates(item: JSONObject): List<String> = buildList {
        item.optString("src", "").takeIf(String::isNotBlank)?.let(::add)
        val alternatives = item.optJSONArray("srcCandidates") ?: JSONArray()
        for (slot in 0 until alternatives.length()) {
            alternatives.optString(slot).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun opaqueHttpUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme?.lowercase() !in HTTP_SCHEMES || uri.host.isNullOrBlank()) return null
        value.trim()
    }.getOrNull()

    private fun evidencedPageUrl(value: String, item: JSONObject): String? = runCatching {
        val normalized = opaqueHttpUrl(value) ?: return null
        val uri = URI(normalized)
        val path = uri.path.orEmpty().lowercase()
        val query = uri.rawQuery.orEmpty().lowercase()
        val host = uri.host.lowercase()
        if (NON_IMAGE_EXTENSION.containsMatchIn(path) ||
            BLOCKED_IDENTITY.containsMatchIn(path) ||
            BLOCKED_HOST_LABEL.containsMatchIn(host)) return null

        val hasImageMime = explicitMimes(item).any(::supportedImageMime)
        val hasImageExtension = IMAGE_EXTENSION.containsMatchIn(path)
        val hasContentPath = CONTENT_PATH_TOKENS.any(path::contains)
        val hasImageQuery = IMAGE_QUERY.containsMatchIn(query)
        normalized.takeIf {
            hasImageMime || hasImageExtension || hasContentPath || hasImageQuery
        }
    }.getOrNull()

    private fun explicitNonImageMime(item: JSONObject): Boolean {
        val mimes = explicitMimes(item)
        return mimes.isNotEmpty() && mimes.none(::supportedImageMime)
    }

    private fun explicitMimes(item: JSONObject): List<String> = MIME_KEYS.mapNotNull { key ->
        item.optString(key, "")
            .substringBefore(';')
            .trim()
            .lowercase()
            .takeIf { '/' in it }
    }

    private fun supportedImageMime(value: String): Boolean = value in SUPPORTED_IMAGE_MIMES

    private fun responseCanContainJson(value: String): Boolean {
        val mime = value.substringBefore(';').trim().lowercase()
        return mime.isBlank() || mime == "application/json" || mime == "text/json" ||
            mime.endsWith("+json") || mime == "text/plain"
    }

    private companion object {
        val IMAGE_API_PATHS = setOf("/api/webtoon-images", "/api/manhwa-images")
        val HTTP_SCHEMES = setOf("http", "https")
        val MIME_KEYS = listOf("contentType", "mimeType", "mime")
        val SUPPORTED_IMAGE_MIMES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")
        val IMAGE_EXTENSION = Regex("\\.(?:jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
        val NON_IMAGE_EXTENSION = Regex(
            "\\.(?:woff2?|ttf|otf|eot|css|m?js|json|wasm|svg|ico|html?|xml|txt|map|" +
                "mp4|webm|m3u8|mp3|ogg|wav)$",
            RegexOption.IGNORE_CASE,
        )
        val BLOCKED_IDENTITY = Regex(
            "(?:^|[/_.-])(?:ads?|advert(?:isement)?s?|banner|sponsor|promo|logo|icon|sprite|" +
                "font|avatar|thumbnail|tracker|tracking|analytics|pixel|placeholder|loading)" +
                "(?:$|[/_.-])",
            RegexOption.IGNORE_CASE,
        )
        val BLOCKED_HOST_LABEL = Regex(
            "(?:^|\\.)(?:ads?|advert(?:ising)?|tracker|tracking|analytics)(?:\\.|$)",
            RegexOption.IGNORE_CASE,
        )
        val CONTENT_PATH_TOKENS = listOf(
            "/webtoon_uploads/", "/manhwa_uploads/", "/comic_uploads/", "/manga_uploads/",
            "/blacktoon/episodes/", "/episodes/", "/chapters/", "/pages/", "/token/",
        )
        val IMAGE_QUERY = Regex(
            "(?:^|&)(?:format|fmt|type|extension|ext)=(?:jpe?g|png|webp)(?:&|$)",
            RegexOption.IGNORE_CASE,
        )
    }

    private data class TokenIdentity(
        val workId: String,
        val episodeId: String,
        val kind: String,
    )
}
