package ml.melun.mangaview.reader

import android.util.Log
import ml.melun.mangaview.mangaview.CustomHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

class NtkManifestEvidenceException(message: String) : IllegalArgumentException(message)

class NtkDocumentRouteResponseException(
    val status: Int,
) : IllegalArgumentException("NTK document origin returned recoverable HTTP status=$status")

data class NtkExactViewerImageApiEnvelope(
    val request: CustomHttpClient.NtkBoundHttpRequest,
    val response: CustomHttpClient.NtkBoundHttpResponse,
    val orderedAssets: List<String>,
    val orderedSourcePages: List<Int>,
    val sourceSlotCount: Int,
    val selectedHeadersDigestSha256: String,
    val orderedAssetsDigestSha256: String,
    val documentPlanProofDigestSha256: String,
    val viewerImageRequestIdentityDigestSha256: String,
    val orderedAssetSelectionPolicyVersion: String,
    /** Exact per-page replicas carried by the same fully consumed, identity-bound API response. */
    val orderedReplicaCandidates: List<List<String>>,
) {
    init {
        require(orderedAssets.isNotEmpty())
        require(orderedAssets.none(String::isBlank))
        require(orderedAssets.toSet().size == orderedAssets.size)
        require(sourceSlotCount in orderedAssets.size..1_000)
        require(orderedSourcePages.size == orderedAssets.size)
        require(orderedReplicaCandidates.size == orderedAssets.size)
        require(orderedReplicaCandidates.all { candidates ->
            candidates.isNotEmpty() &&
                candidates.none(String::isBlank) &&
                candidates.toSet().size == candidates.size
        })
        require(orderedAssets.indices.all { index ->
            orderedAssets[index] in orderedReplicaCandidates[index]
        })
        require(
            orderedSourcePages.all { it in 1..sourceSlotCount } &&
                orderedSourcePages.zipWithNext().all { (first, second) -> first < second }
        )
        require(NtkStripDigests.isSha256(selectedHeadersDigestSha256))
        require(NtkStripDigests.isSha256(orderedAssetsDigestSha256))
        require(NtkStripDigests.isSha256(documentPlanProofDigestSha256))
        require(NtkStripDigests.isSha256(viewerImageRequestIdentityDigestSha256))
        require(
            orderedAssetSelectionPolicyVersion ==
                NtkViewerImageApiManifestProof.ORDERED_ASSET_SELECTION_POLICY_VERSION
        )
    }
}

object NtkEpisodeDocumentPlanParser {
    private val invocationCount = AtomicLong()
    private val numericEpisodePath =
        Regex("""^/(manhwa|webtoon)/(\d{1,12})/(\d{1,12})$""", RegexOption.IGNORE_CASE)
    private val anyEpisodePath =
        Regex("""^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)$""", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun parse(
        lease: NtkDiscoveryLease,
        episodePath: String,
        response: CustomHttpClient.NtkBoundHttpResponse
    ): NtkEpisodeDocumentPlanDraft {
        invocationCount.incrementAndGet()
        val normalizedPath = NtkStripDigests.normalizeEpisodePath(episodePath)
        if (response.status in 500..599) {
            throw NtkDocumentRouteResponseException(response.status)
        }
        if (normalizedPath != lease.episodePath ||
            response.request == null ||
            response.request.method != "GET" ||
            response.status != 200 ||
            !response.consumedToEof ||
            response.bodyBytes.isEmpty()
        ) fail("Document response was not one complete 200 GET")
        val pathMatch = anyEpisodePath.matchEntire(normalizedPath)
            ?: fail("Unsupported episode path")
        val document = response.bodyBytes.toString(Charsets.UTF_8)
        val componentPayloads = if (response.isReactServerComponent()) {
            flightStreamComponentPayloads(document)
        } else {
            directComponentPayloads(document) + flightComponentPayloads(document)
        }
        if (componentPayloads.size != 1) {
            fail("Expected exactly one episode component payload, found ${componentPayloads.size}")
        }
        val componentPayload = componentPayloads.single()
        val component = runCatching { JSONObject(componentPayload) }
            .getOrElse { fail("Episode component payload is not structural JSON") }
        val sourceWorkId = component.optString("sourceWorkId", "").trim()
        val episodeId = component.optString("episodeId", "").trim()
        val imagesToken = component.optString("imagesToken", "").trim()
            .ifEmpty { component.optString("token", "").trim() }
        val imageMetas = component.optJSONArray("imageMetas")
            ?: component.optJSONArray("images")
            ?: fail("Episode component lacks imageMetas/images")
        if (sourceWorkId.isEmpty() || episodeId.isEmpty() || imagesToken.isEmpty() ||
            imageMetas.length() !in 1..1_000
        ) fail("Episode component identity/cardinality is invalid")
        val orderedPages = ArrayList<Int>(imageMetas.length())
        for (index in 0 until imageMetas.length()) {
            val meta = imageMetas.optJSONObject(index)
                ?: fail("imageMetas[$index] is not an object")
            if (!meta.has("page")) fail("imageMetas[$index] lacks page")
            val page = meta.optInt("page", Int.MIN_VALUE)
            if (page != index + 1) fail("imageMetas page order is not exact 1..N")
            orderedPages += page
        }
        val explicitCounts = listOf(
            "imageCount",
            "imagesCount",
            "totalImages",
            "totalImageCount",
            "pageCount",
            "totalPages",
            "numberOfPages"
        ).filter(component::has).map { component.optInt(it, -1) }
        if (explicitCounts.any { it != orderedPages.size }) {
            fail("Document explicit count conflicts with imageMetas")
        }
        val claims = tokenClaims(imagesToken)
        if (claims.optString("w", "") != sourceWorkId ||
            claims.optString("e", "") != episodeId
        ) fail("imagesToken identity does not match component identity")
        val tokenSegment = claims.optString("t", "").trim().lowercase()
        val segment = pathMatch.groupValues[1].lowercase()
        if (tokenSegment.isNotEmpty() && tokenSegment != segment) {
            fail("imagesToken segment does not match episode path")
        }
        numericEpisodePath.matchEntire(normalizedPath)?.let { numeric ->
            if (numeric.groupValues[2] != sourceWorkId ||
                numeric.groupValues[3] != episodeId
            ) fail("Numeric episode path identity mismatch")
        }
        val expectedEndpoint = if (segment == "webtoon") {
            "/api/webtoon-images"
        } else {
            "/api/manhwa-images"
        }
        val endpoint = component.optString("imageApiPath", "").trim()
            .ifEmpty { expectedEndpoint }
        if (endpoint != expectedEndpoint) {
            fail("Episode component image API path does not match its segment")
        }
        val requestIdentity = NtkViewerImageRequestIdentity.create(
            segment,
            endpoint,
            sourceWorkId,
            episodeId,
            imagesToken
        )
        return NtkEpisodeDocumentPlanDraft(
            normalizedEpisodePath = normalizedPath,
            discoveryGeneration = lease.generation.value,
            canonicalRequestUrl = response.requestUrl,
            canonicalFinalUrl = response.finalUrl,
            selectedHeadersDigestSha256 = selectedHeadersDigest(response.responseHeaders),
            responseBody = response.bodyBytes,
            componentPayload = componentPayload.toByteArray(Charsets.UTF_8),
            orderedPages = orderedPages,
            requestIdentity = requestIdentity,
            imagesToken = imagesToken
        )
    }

    @JvmStatic
    fun invocationCount(): Long = invocationCount.get()

    @JvmStatic
    fun resetInvocationCountForTest() {
        invocationCount.set(0L)
    }

    /**
     * Extracts only a finite numeric page count from an already-complete bound document response.
     * This is a request-admission hint, never manifest authority: [parse] still performs the full
     * Jsoup/React structural validation before any image can be published. Returning null simply
     * keeps the existing full-parse gate.
     */
    @JvmStatic
    fun completeNumericPageCountHint(
        lease: NtkDiscoveryLease,
        episodePath: String,
        response: CustomHttpClient.NtkBoundHttpResponse,
    ): Int? = runCatching {
        val normalizedPath = NtkStripDigests.normalizeEpisodePath(episodePath)
        check(normalizedPath == lease.episodePath)
        check(response.request?.method == "GET")
        check(response.status == 200 && response.consumedToEof && response.bodyBytes.isNotEmpty())
        val pathMatch = numericEpisodePath.matchEntire(normalizedPath) ?: return@runCatching null
        val document = response.bodyBytes.toString(Charsets.UTF_8)
        val payloads = when {
            response.isReactServerComponent() -> flightStreamComponentPayloads(document)
            document.trimStart().let { it.startsWith('{') || it.startsWith('[') } ->
                ArrayList<String>().also { collectComponentPayloads(parseWholeJson(document.trim()), it) }
            else -> fastHtmlComponentPayloads(document)
        }
        check(payloads.size == 1)
        val component = JSONObject(payloads.single())
        val sourceWorkId = component.optString("sourceWorkId", "").trim()
        val episodeId = component.optString("episodeId", "").trim()
        check(sourceWorkId == pathMatch.groupValues[2])
        check(episodeId == pathMatch.groupValues[3])
        val imageMetas = component.optJSONArray("imageMetas")
            ?: component.optJSONArray("images")
            ?: error("Viewer component has no image metadata array")
        val pageCount = imageMetas.length()
        check(pageCount in 1..NtkSourceLanePolicy.MAX_EPISODE_PAGES)
        for (index in 0 until pageCount) {
            val meta = imageMetas.optJSONObject(index) ?: error("Non-object image metadata")
            check(meta.has("page") && meta.optInt("page", Int.MIN_VALUE) == index + 1)
        }
        val explicitCounts = listOf(
            "imageCount",
            "imagesCount",
            "totalImages",
            "totalImageCount",
            "pageCount",
            "totalPages",
            "numberOfPages",
        ).filter(component::has).map { component.optInt(it, -1) }
        check(explicitCounts.all { it == pageCount })
        val imagesToken = component.optString("imagesToken", "").trim()
            .ifEmpty { component.optString("token", "").trim() }
        check(imagesToken.isNotEmpty())
        val claims = tokenClaims(imagesToken)
        check(claims.optString("w", "") == sourceWorkId)
        check(claims.optString("e", "") == episodeId)
        val tokenSegment = claims.optString("t", "").trim().lowercase()
        check(tokenSegment.isEmpty() || tokenSegment == pathMatch.groupValues[1].lowercase())
        pageCount
    }.getOrNull()

    private fun fastHtmlComponentPayloads(document: String): List<String> {
        val directPayloads = ArrayList<String>()
        val flightStream = StringBuilder()
        var cursor = 0
        while (cursor < document.length) {
            val scriptStart = document.indexOf("<script", cursor, ignoreCase = true)
            if (scriptStart < 0) break
            val nameEnd = scriptStart + "<script".length
            if (nameEnd < document.length &&
                !document[nameEnd].isWhitespace() && document[nameEnd] != '>'
            ) {
                cursor = nameEnd
                continue
            }
            val tagEnd = document.indexOf('>', nameEnd)
            if (tagEnd < 0) return emptyList()
            val scriptEnd = document.indexOf("</script>", tagEnd + 1, ignoreCase = true)
            if (scriptEnd < 0) return emptyList()
            val openTag = document.substring(scriptStart, tagEnd + 1)
            val source = document.substring(tagEnd + 1, scriptEnd).trim()
            if (SCRIPT_VIEWER_ID_ATTRIBUTE.containsMatchIn(openTag) &&
                SCRIPT_JSON_TYPE_ATTRIBUTE.containsMatchIn(openTag)
            ) {
                if (source.isEmpty()) return emptyList()
                collectComponentPayloads(parseWholeJson(source), directPayloads)
            }
            if (source.contains(FLIGHT_PUSH_MARKER)) {
                appendExactFlightPushes(source, flightStream)
            }
            cursor = scriptEnd + "</script>".length
        }
        val flightPayloads = if (flightStream.isEmpty()) {
            emptyList()
        } else {
            flightStreamComponentPayloads(flightStream)
        }
        return directPayloads + flightPayloads
    }

    /**
     * A direct component is trusted only when it is the complete JSON value of the dedicated
     * viewer-data script (or the whole response for JSON fixtures). Arbitrary JavaScript objects
     * are not document-plan evidence.
     */
    private fun directComponentPayloads(document: String): List<String> {
        val payloads = ArrayList<String>()
        val trimmed = document.trim()
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            collectComponentPayloads(parseWholeJson(trimmed), payloads)
            return payloads
        }
        val parsed = Jsoup.parse(document)
        parsed.select("script#theme-viewer-data").forEach { script ->
            if (!script.attr("type").equals("application/json", ignoreCase = true)) return@forEach
            val scriptData = script.data().trim()
            if (scriptData.isEmpty()) fail("theme-viewer-data is empty")
            collectComponentPayloads(parseWholeJson(scriptData), payloads)
        }
        return payloads
    }

    /**
     * React Flight type-1 pushes are fragments of one byte-preserving logical stream. Extract only
     * exact inline push statements, concatenate without delimiters, frame the stream, and inspect
     * structural JSON model rows. Length-delimited text records can never provide authority.
     */
    private fun flightComponentPayloads(document: String): List<String> {
        val stream = StringBuilder()
        Jsoup.parse(document).select("script").forEach { script ->
            val source = script.data().trim()
            if (source.contains(FLIGHT_PUSH_MARKER)) {
                appendExactFlightPushes(source, stream)
            }
        }
        if (stream.isEmpty()) return emptyList()

        return flightStreamComponentPayloads(stream)
    }

    /**
     * Frames a complete raw React Server Component response (or the byte-equivalent stream
     * reconstructed from HTML type-1 pushes). A raw response is accepted only when the bound
     * HTTP Content-Type selected it, so arbitrary HTML or JavaScript text cannot be promoted to
     * document authority.
     */
    private fun flightStreamComponentPayloads(stream: CharSequence): List<String> {
        val payloads = ArrayList<String>()
        var cursor = 0
        while (cursor < stream.length) {
            while (cursor < stream.length && (stream[cursor] == '\r' || stream[cursor] == '\n')) {
                cursor++
            }
            if (cursor >= stream.length) break
            val rowStart = cursor
            while (cursor < stream.length && stream[cursor].isHexDigit()) cursor++
            if (cursor >= stream.length || stream[cursor] != ':') {
                fail("Malformed React Flight row framing")
            }
            val hasRowId = cursor > rowStart
            cursor++
            if (!hasRowId) {
                if (!stream.startsWith("HL", cursor)) {
                    fail("Unsupported id-less React Flight row")
                }
                while (cursor < stream.length && stream[cursor] != '\n' && stream[cursor] != '\r') {
                    cursor++
                }
                continue
            }
            if (cursor < stream.length && stream[cursor] == 'T') {
                cursor = skipLengthDelimitedText(stream, cursor + 1)
                continue
            }
            var rowEnd = cursor
            while (rowEnd < stream.length && stream[rowEnd] != '\n' && stream[rowEnd] != '\r') {
                rowEnd++
            }
            val model = stream.substring(cursor, rowEnd).trim()
            if (model.startsWith('{') || model.startsWith('[')) {
                collectComponentPayloads(parseWholeJson(model), payloads)
            }
            cursor = rowEnd
        }
        return payloads
    }

    private fun CustomHttpClient.NtkBoundHttpResponse.isReactServerComponent(): Boolean {
        val contentTypes = responseHeaders.entries
            .filter { it.key.equals("content-type", ignoreCase = true) }
            .flatMap { it.value }
        return contentTypes.any { value ->
            value.substringBefore(';').trim().equals("text/x-component", ignoreCase = true)
        }
    }

    private fun appendExactFlightPushes(source: String, stream: StringBuilder) {
        var cursor = 0
        var pushes = 0
        while (cursor < source.length) {
            while (cursor < source.length && (source[cursor].isWhitespace() || source[cursor] == ';')) {
                cursor++
            }
            if (cursor >= source.length) break
            if (!source.startsWith(FLIGHT_PUSH_MARKER, cursor)) {
                fail("React Flight push script contains non-transport JavaScript")
            }
            var arrayStart = cursor + FLIGHT_PUSH_MARKER.length
            while (arrayStart < source.length && source[arrayStart].isWhitespace()) arrayStart++
            if (arrayStart >= source.length || source[arrayStart] != '[') {
                fail("React Flight push argument is not an array")
            }
            val arrayEnd = jsonArrayEnd(source, arrayStart)
            if (arrayEnd < 0) fail("React Flight push array is incomplete")
            var close = arrayEnd + 1
            while (close < source.length && source[close].isWhitespace()) close++
            if (close >= source.length || source[close] != ')') {
                fail("React Flight push call is incomplete")
            }
            val envelope = runCatching { JSONArray(source.substring(arrayStart, arrayEnd + 1)) }
                .getOrElse { fail("React Flight push argument is not structural JSON") }
            val type = envelope.optInt(0, -1)
            if (type == 1) {
                if (envelope.length() != 2 || envelope.opt(1) !is String) {
                    fail("Malformed React Flight type-1 push")
                }
                stream.append(envelope.getString(1))
            }
            pushes++
            cursor = close + 1
        }
        if (pushes == 0) fail("React Flight marker was not an executable push")
    }

    private fun skipLengthDelimitedText(stream: CharSequence, lengthStart: Int): Int {
        var cursor = lengthStart
        while (cursor < stream.length && stream[cursor].isHexDigit()) cursor++
        if (cursor == lengthStart || cursor >= stream.length || stream[cursor] != ',') {
            fail("Malformed React Flight text record length")
        }
        val byteLength = stream.subSequence(lengthStart, cursor).toString()
            .toLongOrNull(16)
            ?: fail("React Flight text record length overflow")
        if (byteLength < 0L || byteLength > Int.MAX_VALUE) {
            fail("React Flight text record is too large")
        }
        cursor++
        var consumedBytes = 0L
        while (consumedBytes < byteLength && cursor < stream.length) {
            val codePoint = Character.codePointAt(stream, cursor)
            val encodedBytes = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (consumedBytes + encodedBytes > byteLength) {
                fail("React Flight text record splits a UTF-8 code point")
            }
            consumedBytes += encodedBytes
            cursor += Character.charCount(codePoint)
        }
        if (consumedBytes != byteLength) fail("React Flight text record is truncated")
        return cursor
    }

    private fun parseWholeJson(value: String): Any {
        return runCatching {
            val tokener = JSONTokener(value)
            val parsed = tokener.nextValue()
            if (parsed !is JSONObject && parsed !is JSONArray) {
                fail("Document model is not an object or array")
            }
            if (tokener.nextClean() != 0.toChar()) fail("Document model has trailing data")
            parsed
        }.getOrElse { error ->
            if (error is NtkManifestEvidenceException) throw error
            fail("Document model is not structural JSON")
        }
    }

    private fun collectComponentPayloads(value: Any?, payloads: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                if (isEpisodeComponent(value)) {
                    payloads += value.toString()
                } else {
                    val keys = value.keys()
                    while (keys.hasNext()) collectComponentPayloads(value.opt(keys.next()), payloads)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectComponentPayloads(value.opt(index), payloads)
                }
            }
        }
    }

    private fun isEpisodeComponent(value: JSONObject): Boolean =
        value.has("sourceWorkId") &&
            value.has("episodeId") &&
            (value.has("imageMetas") || value.has("images")) &&
            (value.has("imagesToken") || value.has("token"))

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun jsonArrayEnd(value: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val char = value[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                    if (depth < 0) return -1
                }
            }
        }
        return -1
    }

    private val SCRIPT_VIEWER_ID_ATTRIBUTE =
        Regex("""(?is)\bid\s*=\s*(["'])theme-viewer-data\1""")
    private val SCRIPT_JSON_TYPE_ATTRIBUTE =
        Regex("""(?is)\btype\s*=\s*(["'])application/json\1""")
    private const val FLIGHT_PUSH_MARKER = "self.__next_f.push("

    private fun tokenClaims(token: String): JSONObject {
        return runCatching {
            val parts = token.split('.')
            if (parts.size != 2 || parts.any(String::isBlank)) fail("Invalid imagesToken")
            val payload = parts[0] + "=".repeat((4 - parts[0].length % 4) % 4)
            JSONObject(Base64.getUrlDecoder().decode(payload).toString(Charsets.UTF_8))
        }.getOrElse { fail("Invalid imagesToken payload") }
    }
}

/** Extracts only the signed image-request identity from an incomplete RSC document prefix. */
object NtkViewerImageRequestSeedParser {
    private val plainToken = Regex("""\"(?:imagesToken|token)\"\s*:\s*\"([^\"]+)\"""")
    private val escapedToken =
        Regex("""\\\"(?:imagesToken|token)\\\"\s*:\s*\\\"([^\\\"]+)\\\"""")
    private val episodePath =
        Regex("""^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)$""", RegexOption.IGNORE_CASE)

    fun parseIfPresent(
        lease: NtkDiscoveryLease,
        path: String,
        bodyPrefix: ByteArray,
    ): NtkViewerImageRequestSeed? {
        if (bodyPrefix.isEmpty()) return null
        val normalizedPath = NtkStripDigests.normalizeEpisodePath(path)
        if (normalizedPath != lease.episodePath) return null
        val pathMatch = episodePath.matchEntire(normalizedPath) ?: return null
        val body = bodyPrefix.toString(Charsets.UTF_8)
        val token = plainToken.find(body)?.groupValues?.getOrNull(1)
            ?: escapedToken.find(body)?.groupValues?.getOrNull(1)
            ?: return null
        val claims = tokenClaimsOrNull(token) ?: return null
        val workId = claims.optString("w", "").trim()
        val episodeId = claims.optString("e", "").trim()
        val segment = pathMatch.groupValues[1].lowercase()
        if (workId.isEmpty() || episodeId.isEmpty()) return null
        val tokenSegment = claims.optString("t", "").trim().lowercase()
        if (tokenSegment.isNotEmpty() && tokenSegment != segment) return null
        val pathWork = pathMatch.groupValues[2]
        val pathEpisode = pathMatch.groupValues[3]
        if (pathWork.all(Char::isDigit) && pathEpisode.all(Char::isDigit) &&
            (pathWork != workId || pathEpisode != episodeId)
        ) return null
        val endpoint = if (segment == "webtoon") {
            "/api/webtoon-images"
        } else {
            "/api/manhwa-images"
        }
        val identity = runCatching {
            NtkViewerImageRequestIdentity.create(
                segment,
                endpoint,
                workId,
                episodeId,
                token,
            )
        }.getOrNull() ?: return null
        return NtkViewerImageRequestSeed(
            normalizedEpisodePath = normalizedPath,
            discoveryGeneration = lease.generation.value,
            requestIdentity = identity,
            imagesToken = token,
        )
    }

    private fun tokenClaimsOrNull(token: String): JSONObject? = runCatching {
        val parts = token.split('.')
        require(parts.size == 2 && parts.none(String::isBlank))
        val payload = parts[0] + "=".repeat((4 - parts[0].length % 4) % 4)
        JSONObject(Base64.getUrlDecoder().decode(payload).toString(Charsets.UTF_8))
    }.getOrNull()
}

object NtkViewerImageApiAuthorityParser {
    private val invocationCount = AtomicLong()

    @JvmStatic
    fun parse(
        plan: NtkProvisionalEpisodePlan,
        request: CustomHttpClient.NtkBoundHttpRequest,
        response: CustomHttpClient.NtkBoundHttpResponse
    ): NtkExactViewerImageApiEnvelope {
        invocationCount.incrementAndGet()
        val identity = plan.proof.requestIdentity
        if (request.method != "POST" ||
            response.request !== request ||
            request.bodyBytes.isEmpty() ||
            response.status != 200 ||
            !response.consumedToEof ||
            response.bodyBytes.isEmpty()
        ) fail("Image API exchange is not one complete 200 POST")
        val requestUri = trustedUri(request.url)
        val finalUri = trustedUri(response.finalUrl)
        if (requestUri.host.lowercase() != finalUri.host.lowercase() ||
            requestUri.path != identity.normalizedEndpointPath ||
            finalUri.path != identity.normalizedEndpointPath
        ) fail("Image API endpoint/final URL escaped the plan identity")
        val requestJson = runCatching { JSONObject(request.bodyBytes.toString(Charsets.UTF_8)) }
            .getOrElse { fail("Image API request body is not JSON") }
        val requestToken = requestJson.optString("token", "").trim()
        if (requestJson.optString("workId", "") != identity.normalizedSourceWorkId ||
            requestJson.optString("episodeId", "") != identity.normalizedEpisodeId ||
            NtkStripDigests.sha256Bytes(requestToken.toByteArray(Charsets.UTF_8)) !=
            identity.imagesTokenSha256
        ) fail("Image API request identity does not match document plan")
        val root = runCatching { JSONObject(response.bodyBytes.toString(Charsets.UTF_8)) }
            .getOrElse { fail("Image API response body is not JSON") }
        if (!root.optBoolean("ok", false) ||
            root.optBoolean("ad_ack_required", false) ||
            root.has("error")
        ) fail("Image API response did not grant exact assets")
        val images = root.optJSONArray("images") ?: fail("Image API response lacks images")
        if (images.length() != plan.pageCount) fail("Image API page count mismatch")
        val selectedSlots = selectRenderableSlots(
            images,
            allowExplicitNonRenderableSlots = false,
        )
        val assets = selectedSlots.orderedAssets
        if (assets.toSet().size != assets.size) fail("Image API canonical assets are not unique")
        val orderedDigest = NtkEpisodeManifestSeal.computeDigestSha256(
            plan.proof.normalizedEpisodePath,
            assets.size,
            assets
        )
        return NtkExactViewerImageApiEnvelope(
            request = request,
            response = response,
            orderedAssets = assets,
            orderedSourcePages = selectedSlots.orderedSourcePages,
            sourceSlotCount = images.length(),
            selectedHeadersDigestSha256 = selectedHeadersDigest(response.responseHeaders),
            orderedAssetsDigestSha256 = orderedDigest,
            documentPlanProofDigestSha256 = plan.proof.proofDigestSha256,
            viewerImageRequestIdentityDigestSha256 = identity.identityDigestSha256,
            orderedAssetSelectionPolicyVersion =
                NtkViewerImageApiManifestProof.ORDERED_ASSET_SELECTION_POLICY_VERSION,
            orderedReplicaCandidates = selectedSlots.orderedReplicaCandidates,
        )
    }

    @JvmStatic
    fun parse(
        draft: NtkEpisodeDocumentPlanDraft,
        request: CustomHttpClient.NtkBoundHttpRequest,
        response: CustomHttpClient.NtkBoundHttpResponse
    ): NtkExactViewerImageApiEnvelope {
        invocationCount.incrementAndGet()
        val identity = draft.requestIdentity
        if (request.method != "POST" ||
            response.request !== request ||
            request.bodyBytes.isEmpty() ||
            response.status != 200 ||
            !response.consumedToEof ||
            response.bodyBytes.isEmpty()
        ) fail("Image API exchange is not one complete 200 POST")
        val requestUri = trustedUri(request.url)
        val finalUri = trustedUri(response.finalUrl)
        if (requestUri.host.lowercase() != finalUri.host.lowercase() ||
            requestUri.path != identity.normalizedEndpointPath ||
            finalUri.path != identity.normalizedEndpointPath
        ) fail("Image API endpoint/final URL escaped the plan identity")
        val requestJson = runCatching { JSONObject(request.bodyBytes.toString(Charsets.UTF_8)) }
            .getOrElse { fail("Image API request body is not JSON") }
        val requestToken = requestJson.optString("token", "").trim()
        if (requestJson.optString("workId", "") != identity.normalizedSourceWorkId ||
            requestJson.optString("episodeId", "") != identity.normalizedEpisodeId ||
            NtkStripDigests.sha256Bytes(requestToken.toByteArray(Charsets.UTF_8)) !=
            identity.imagesTokenSha256
        ) fail("Image API request identity does not match document plan")
        val root = runCatching { JSONObject(response.bodyBytes.toString(Charsets.UTF_8)) }
            .getOrElse { fail("Image API response body is not JSON") }
        if (!root.optBoolean("ok", false) ||
            root.optBoolean("ad_ack_required", false) ||
            root.has("error")
        ) fail("Image API response did not grant exact assets")
        val images = root.optJSONArray("images") ?: fail("Image API response lacks images")
        if (images.length() != draft.pageCount) fail("Image API page count mismatch")
        val selectedSlots = selectRenderableSlots(
            images,
            allowExplicitNonRenderableSlots = true,
        )
        val assets = selectedSlots.orderedAssets
        if (assets.toSet().size != assets.size) fail("Image API canonical assets are not unique")
        val orderedDigest = NtkEpisodeManifestSeal.computeDigestSha256(
            draft.normalizedEpisodePath,
            assets.size,
            assets
        )
        val provisionalProofDigest = NtkEpisodeDocumentPlanProof.create(
            draft.normalizedEpisodePath,
            draft.discoveryGeneration,
            draft.canonicalRequestUrl,
            draft.canonicalFinalUrl,
            draft.selectedHeadersDigestSha256,
            draft.responseBody,
            draft.componentPayload,
            selectedSlots.orderedSourcePages,
            assets,
            draft.requestIdentity
        ).proofDigestSha256
        return NtkExactViewerImageApiEnvelope(
            request = request,
            response = response,
            orderedAssets = assets,
            orderedSourcePages = selectedSlots.orderedSourcePages,
            sourceSlotCount = images.length(),
            selectedHeadersDigestSha256 = selectedHeadersDigest(response.responseHeaders),
            orderedAssetsDigestSha256 = orderedDigest,
            documentPlanProofDigestSha256 = provisionalProofDigest,
            viewerImageRequestIdentityDigestSha256 = identity.identityDigestSha256,
            orderedAssetSelectionPolicyVersion =
                NtkViewerImageApiManifestProof.ORDERED_ASSET_SELECTION_POLICY_VERSION,
            orderedReplicaCandidates = selectedSlots.orderedReplicaCandidates,
        )
    }

    @JvmStatic
    fun invocationCount(): Long = invocationCount.get()

    @JvmStatic
    fun resetInvocationCountForTest() {
        invocationCount.set(0L)
    }

    private data class SelectedRenderableSlots(
        val orderedAssets: List<String>,
        val orderedSourcePages: List<Int>,
        val orderedReplicaCandidates: List<List<String>>,
    )

    private data class SelectedCanonicalAsset(
        val canonical: String,
        val exactReplicas: List<String>,
    )

    private fun selectRenderableSlots(
        images: JSONArray,
        allowExplicitNonRenderableSlots: Boolean,
    ): SelectedRenderableSlots {
        logReplicaTopology(images)
        val assets = ArrayList<String>(images.length())
        val sourcePages = ArrayList<Int>(images.length())
        val replicaCandidates = ArrayList<List<String>>(images.length())
        val excludedPages = ArrayList<Int>()
        for (index in 0 until images.length()) {
            val image = images.optJSONObject(index) ?: fail("images[$index] is not an object")
            val sourcePage = index + 1
            if (!image.has("page") || image.optInt("page", Int.MIN_VALUE) != sourcePage) {
                fail("Image API page slots are not exact 1..N")
            }
            val selected = selectCanonicalAsset(image, index)
            if (selected == null) {
                if (allowExplicitNonRenderableSlots &&
                    hasOnlyExplicitNonRenderableTrustedAssets(image)
                ) {
                    excludedPages += sourcePage
                    continue
                }
                fail("Image API page $sourcePage lacks a canonical renderable asset")
            }
            assets += NtkStripDigests.canonicalAsset(selected.canonical)
            sourcePages += sourcePage
            replicaCandidates += selected.exactReplicas
        }
        if (assets.isEmpty()) fail("Image API contains no canonical renderable assets")
        if (excludedPages.isNotEmpty()) {
            runCatching {
                Log.w(
                    "ViewerPerf",
                    "reader_image_api_excluded_nonrenderable_slots " +
                        "sourceSlots=${images.length()},renderable=${assets.size}," +
                        "sourcePages=${excludedPages.joinToString("|")}"
                )
            }
        }
        return SelectedRenderableSlots(assets, sourcePages, replicaCandidates)
    }

    private fun selectCanonicalAsset(image: JSONObject, pageIndex: Int): SelectedCanonicalAsset? {
        val trusted = linkedSetOf<String>()
        image.optString("src", "").trim()
            .takeIf(::isTrustedCanonicalAsset)
            ?.let(trusted::add)
        image.optJSONArray("srcCandidates")?.let { candidates ->
            for (index in 0 until candidates.length()) {
                candidates.optString(index, "").trim()
                    .takeIf(::isTrustedCanonicalAsset)
                    ?.let(trusted::add)
            }
        }
        if (trusted.isEmpty()) return null
        if (trusted.size == 1) {
            val only = trusted.single()
            return SelectedCanonicalAsset(only, listOf(only))
        }

        // The API explicitly publishes these as interchangeable per-page origins. Keep the first
        // two pages on one deterministic origin: short first pages commonly expose both pages in
        // the opening viewport, and sharing page zero's already-proven host-local H2 pool avoids
        // making the first complete viewport wait for a second cold CDN connection. Page one is
        // still a normal post-entry image request and starts only after page zero publishes; this
        // is neither hidden preload nor duplicate transport. Stripe the remaining episode evenly
        // so the full-scene deadline retains its measured multi-origin throughput.
        val byHost = trusted.groupBy { URI(it).host.lowercase() }.toSortedMap()
        val preferredHost = if (pageIndex < 2) {
            byHost.keys.first()
        } else {
            byHost.keys.elementAt(pageIndex % byHost.size)
        }
        val canonical = checkNotNull(byHost.getValue(preferredHost).firstOrNull())
        return SelectedCanonicalAsset(canonical, trusted.toList())
    }

    private fun logReplicaTopology(images: JSONArray) {
        var trustedCandidates = 0
        var pagesWithMultipleCandidates = 0
        var maxCandidatesPerPage = 0
        val hostCounts = linkedMapOf<String, Int>()
        for (pageIndex in 0 until images.length()) {
            val image = images.optJSONObject(pageIndex) ?: continue
            val candidates = linkedSetOf<String>()
            image.optString("src", "").trim()
                .takeIf(::isTrustedCanonicalAsset)
                ?.let(candidates::add)
            image.optJSONArray("srcCandidates")?.let { replicas ->
                for (candidateIndex in 0 until replicas.length()) {
                    replicas.optString(candidateIndex, "").trim()
                        .takeIf(::isTrustedCanonicalAsset)
                        ?.let(candidates::add)
                }
            }
            trustedCandidates += candidates.size
            if (candidates.size > 1) pagesWithMultipleCandidates += 1
            maxCandidatesPerPage = maxOf(maxCandidatesPerPage, candidates.size)
            candidates.forEach { candidate ->
                val host = URI(candidate).host.lowercase()
                hostCounts[host] = (hostCounts[host] ?: 0) + 1
            }
        }
        val hosts = hostCounts.entries.joinToString(";") { (host, count) -> "$host:$count" }
        runCatching {
            Log.d(
                "ViewerPerf",
                "reader_image_api_replica_topology pages=${images.length()}," +
                    "trustedCandidates=$trustedCandidates," +
                    "pagesWithMultiple=$pagesWithMultipleCandidates," +
                    "maxPerPage=$maxCandidatesPerPage,hosts=$hosts"
            )
        }
    }

    private fun isTrustedCanonicalAsset(value: String): Boolean =
        isTrustedApiAsset(value) && !isExplicitlyNonRenderableAsset(value)

    private fun hasOnlyExplicitNonRenderableTrustedAssets(image: JSONObject): Boolean {
        val trusted = linkedSetOf<String>()
        image.optString("src", "").trim()
            .takeIf(::isTrustedApiAsset)
            ?.let(trusted::add)
        image.optJSONArray("srcCandidates")?.let { candidates ->
            for (index in 0 until candidates.length()) {
                candidates.optString(index, "").trim()
                    .takeIf(::isTrustedApiAsset)
                    ?.let(trusted::add)
            }
        }
        return trusted.isNotEmpty() && trusted.all(::isExplicitlyNonRenderableAsset)
    }

    private fun isExplicitlyNonRenderableAsset(value: String): Boolean {
        val path = runCatching { URI(value).rawPath.orEmpty().lowercase() }
            .getOrDefault("")
        return path.endsWith(".svg") || path.endsWith(".svgz")
    }

    private fun isTrustedApiAsset(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val path = uri.rawPath.orEmpty()
        // This parser only sees assets from the identity-bound, fully consumed 200 response of
        // the signed viewer image API. Unlike generic document scraping, a board_uploads path in
        // this envelope is an authoritative page slot rather than an arbitrary banner. Current
        // manhwa responses legitimately publish their ordered originals from that CDN namespace.
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            path.startsWith('/') &&
            path.length > 1 &&
            !path.contains("/../") &&
            !path.startsWith("/api/", ignoreCase = true)
    }

    private fun trustedUri(value: String): URI {
        val uri = runCatching { URI(value) }.getOrNull() ?: fail("Invalid HTTPS URL")
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            fail("Untrusted HTTPS URL")
        }
        return uri
    }
}

internal fun selectedHeadersDigest(headers: Map<String, List<String>>?): String {
    val selected = listOf("content-type", "etag", "last-modified", "cache-control")
    val normalized = selected.map { name ->
        val values = headers.orEmpty().entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value.orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty)
        "$name=${values.joinToString(",")}"
    }
    return NtkStripDigests.sha256Tokens(listOf("ntk-selected-headers-v1") + normalized)
}

private fun fail(message: String): Nothing = throw NtkManifestEvidenceException(message)
