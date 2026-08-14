package ml.melun.mangaview.ntkack

import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import ml.melun.mangaview.util.NtkBase64
import java.util.Locale

/**
 * Fail-closed parser for the server's explicit, short-lived nginx trusted-grant contract.
 *
 * This validator does not treat a local cookie, `trusted`, or `ackValid` in isolation as proof.
 * Authority requires the complete JSON contract, response markers, and the raw `Set-Cookie`
 * evidence from that same response. ACK confirmation proves only the exact local request binding
 * and a fresh server response grant; the temporary endpoint does not verify request signatures.
 */
object NtkAckTrustedGrantValidator {
    const val TRUST_INTERVAL_MS = 300_000L
    internal const val ACK_CONFIRMATION_INTERVAL_TOLERANCE_MS = 1_000L

    data class ResponseEvidence(
        val status: Int,
        val bodyBytes: ByteArray,
        val headers: Map<String, List<String>>,
        val cookies: List<NtkAckCookie>,
    )

    data class AckConfirmRequest(
        val method: String,
        val endpoint: String,
        val bodyBytes: ByteArray,
    )

    data class AdAckGrantEvidence(
        val scope: String,
        val expiresAtEpochMs: Long,
        val responseUrl: String,
        val tokenDigestSha256: String,
        val payloadDigestSha256: String,
        val signatureDigestSha256: String,
        val setCookieDigestSha256: String,
    )

    data class ChallengeEvidence(
        val scope: String,
        val expiresAtEpochMs: Long,
        val intervalMs: Long,
        val subjectKind: String,
        val successCount: Long,
        val temporaryBypassMarker: String,
        val responseHeaderDigestSha256: String,
        val responseBodyDigestSha256: String,
        val trustMetadataDigestSha256: String,
        val adAckGrant: AdAckGrantEvidence,
    )

    data class AckConfirmationEvidence(
        val scope: String,
        val requestKeyId: String,
        val expiresAtEpochMs: Long,
        val impressionSeen: Long,
        val impressionExpected: Long,
        val impressionMinSeen: Long,
        val temporaryBypassMarker: String,
        val responseHeaderDigestSha256: String,
        val requestBodyDigestSha256: String,
        val responseBodyDigestSha256: String,
        val adAckGrant: AdAckGrantEvidence,
    )

    @JvmStatic
    fun validateChallenge(
        expectedOrigin: String,
        expectedPath: String,
        nowEpochMs: Long,
        response: ResponseEvidence,
    ): ChallengeEvidence {
        validateAuthorityScope(expectedOrigin, expectedPath, nowEpochMs)
        require(response.status == 200) { "Trusted challenge was not HTTP 200" }
        val body = parseObject(response.bodyBytes, "trusted challenge")
        requireExactTrue(body, "ok")
        requireExactTrue(body, "trusted")
        requireExactTrue(body, "ackValid")
        require(!body.has("challenge")) { "Trusted challenge response is hybrid" }
        require(!body.has("temporary") && !body.has("impression")) {
            "Trusted challenge response contains confirmation fields"
        }
        val scope = requireString(body, "scope")
        require(scope == expectedPath) { "Trusted challenge scope mismatch" }
        val expiresAt = requireLong(body, "exp")
        val trust = requireObject(body, "trust")
        val intervalMs = requireLong(trust, "intervalMs")
        val subjectKind = requireString(trust, "subjectKind")
        val successCount = requireLong(trust, "successCount")
        require(intervalMs == TRUST_INTERVAL_MS) { "Unexpected trusted interval" }
        require(subjectKind == "nginx-temp") { "Unexpected trusted subject" }
        require(successCount == 999L) { "Unexpected trusted success count" }
        requireShortFuture(expiresAt, nowEpochMs, intervalMs, "Trusted challenge")
        val marker = validateMarkerHeaders(response.headers)
        val grant = validateAdAckGrant(
            expectedOrigin,
            expectedPath,
            expiresAt,
            nowEpochMs,
            "/api/ad/challenge",
            response.headers,
            response.cookies,
        )
        val trustDigest = sha256Utf8("$intervalMs\n$subjectKind\n$successCount")
        return ChallengeEvidence(
            scope,
            expiresAt,
            intervalMs,
            subjectKind,
            successCount,
            marker.value,
            marker.digestSha256,
            sha256(response.bodyBytes),
            trustDigest,
            grant,
        )
    }

    @JvmStatic
    fun validateAckConfirmation(
        expectedOrigin: String,
        expectedPath: String,
        expectedKeyId: String,
        nowEpochMs: Long,
        request: AckConfirmRequest,
        response: ResponseEvidence,
    ): AckConfirmationEvidence {
        validateAuthorityScope(expectedOrigin, expectedPath, nowEpochMs)
        require(expectedKeyId.isNotBlank()) { "ACK confirmation key id is missing" }
        require(request.method == "POST" && request.endpoint == "/api/ad/ack") {
            "ACK confirmation endpoint mismatch"
        }
        val requestBody = parseObject(request.bodyBytes, "ACK confirmation request")
        require(objectKeys(requestBody) == setOf("path", "requestKeyId")) {
            "ACK confirmation request body is not exact"
        }
        require(requireString(requestBody, "path") == expectedPath) {
            "ACK confirmation request path mismatch"
        }
        require(requireString(requestBody, "requestKeyId") == expectedKeyId) {
            "ACK confirmation request key mismatch"
        }

        require(response.status == 200) { "ACK confirmation was not HTTP 200" }
        val body = parseObject(response.bodyBytes, "ACK confirmation")
        requireExactTrue(body, "ok")
        requireExactTrue(body, "temporary")
        require(!body.has("challenge") && !body.has("trusted") &&
            !body.has("ackValid") && !body.has("trust")) {
            "ACK confirmation response is hybrid"
        }
        val expiresAt = requireLong(body, "exp")
        requireShortFuture(
            expiresAt,
            nowEpochMs,
            TRUST_INTERVAL_MS,
            "ACK confirmation",
            ACK_CONFIRMATION_INTERVAL_TOLERANCE_MS,
        )
        val impression = requireObject(body, "impression")
        requireExactTrue(impression, "ok")
        val seen = requireLong(impression, "seen")
        val expected = requireLong(impression, "expected")
        val minSeen = requireLong(impression, "minSeen")
        require(seen == 4L && expected == 4L && minSeen == 2L) {
            "ACK confirmation impression mismatch"
        }
        val marker = validateMarkerHeaders(response.headers)
        val grant = validateAdAckGrant(
            expectedOrigin,
            expectedPath,
            expiresAt,
            nowEpochMs,
            "/api/ad/ack",
            response.headers,
            response.cookies,
        )
        return AckConfirmationEvidence(
            expectedPath,
            expectedKeyId,
            expiresAt,
            seen,
            expected,
            minSeen,
            marker.value,
            marker.digestSha256,
            sha256(request.bodyBytes),
            sha256(response.bodyBytes),
            grant,
        )
    }

    private fun validateAuthorityScope(origin: String, path: String, nowEpochMs: Long) {
        require(nowEpochMs > 0L) { "Invalid evidence clock" }
        val uri = runCatching { URI(origin) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid trusted origin")
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.port < 0 &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
            "Trusted origin is not normalized HTTPS"
        }
        require(origin == "https://${uri.host.lowercase(Locale.ROOT)}") {
            "Trusted origin is not canonical"
        }
        require(path.matches(Regex("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$"))) {
            "Invalid trusted episode path"
        }
    }

    private data class MarkerEvidence(
        val value: String,
        val digestSha256: String,
    )

    private fun validateMarkerHeaders(headers: Map<String, List<String>>): MarkerEvidence {
        require(singleHeader(headers, "x-ntk-ad-valid") == "1") {
            "Missing trusted validity marker"
        }
        val marker = singleHeader(headers, "x-ntk-ad-temp-bypass")
        require(marker.matches(Regex("^nginx-[0-9]{8}$"))) {
            "Invalid temporary bypass marker"
        }
        // Commit only the two authority-bearing headers in a fixed order. Header map iteration,
        // casing, transport-only headers, and CDN additions must not change the proof identity.
        return MarkerEvidence(
            marker,
            sha256Utf8("x-ntk-ad-valid:1\nx-ntk-ad-temp-bypass:$marker"),
        )
    }

    private fun validateAdAckGrant(
        origin: String,
        path: String,
        expiresAt: Long,
        nowEpochMs: Long,
        responseEndpoint: String,
        headers: Map<String, List<String>>,
        cookies: List<NtkAckCookie>,
    ): AdAckGrantEvidence {
        val rawCandidates = headerValues(headers, "set-cookie")
            .mapNotNull { raw -> parseRawSetCookie(raw)?.let { raw to it } }
            .filter { (_, parsed) -> parsed.name == "ad_ack" }
        require(rawCandidates.size == 1) { "Expected one same-response ad_ack Set-Cookie" }
        val (raw, parsedRaw) = rawCandidates.single()
        require(parsedRaw.attributes.containsKey("secure")) { "ad_ack is not Secure" }
        require(parsedRaw.attributes.containsKey("httponly")) { "ad_ack is not HttpOnly" }
        require(parsedRaw.attributes["samesite"].equals("Lax", ignoreCase = true)) {
            "ad_ack SameSite is not Lax"
        }
        require(parsedRaw.attributes["path"] == "/") { "ad_ack Path is not root" }

        val parsedCookies = cookies.filter { it.name == "ad_ack" }
        require(parsedCookies.size == 1) { "Expected one parsed same-response ad_ack" }
        val cookie = parsedCookies.single()
        require(cookie.value == parsedRaw.value && cookie.value.isNotBlank()) {
            "Raw and parsed ad_ack differ"
        }
        require(cookie.secure && cookie.path == "/") { "Parsed ad_ack scope is invalid" }
        if (cookie.expiresAtEpochMs > 0L) {
            require(cookie.expiresAtEpochMs > nowEpochMs) { "Parsed ad_ack is expired" }
        }
        val originUri = URI(origin)
        require(cookie.domain.trim().removePrefix(".").lowercase(Locale.ROOT) ==
            originUri.host.lowercase(Locale.ROOT)) { "Parsed ad_ack domain mismatch" }
        val responseUri = runCatching { URI(cookie.responseUrl) }.getOrNull()
            ?: throw IllegalArgumentException("ad_ack response URL is invalid")
        require(responseUri.scheme == "https" &&
            responseUri.host?.lowercase(Locale.ROOT) == originUri.host.lowercase(Locale.ROOT) &&
            responseUri.port < 0 && responseUri.rawPath == responseEndpoint &&
            responseUri.rawQuery == null && responseUri.rawFragment == null) {
            "ad_ack did not come from the expected response"
        }
        val rawDigest = sha256Utf8(raw)
        require(cookie.setCookieDigestSha256 == rawDigest) {
            "ad_ack raw Set-Cookie digest mismatch"
        }
        parsedRaw.attributes["domain"]?.let { domain ->
            require(domain.trim().removePrefix(".").lowercase(Locale.ROOT) ==
                originUri.host.lowercase(Locale.ROOT)) { "Raw ad_ack domain mismatch" }
        }

        val token = validateAdAckToken(cookie.value, path, expiresAt)
        return AdAckGrantEvidence(
            token.scope,
            token.expiresAtEpochMs,
            cookie.responseUrl,
            sha256Utf8(cookie.value),
            sha256(token.payloadBytes),
            sha256(token.signatureBytes),
            rawDigest,
        )
    }

    private data class RawCookie(
        val name: String,
        val value: String,
        val attributes: Map<String, String?>,
    )

    private fun parseRawSetCookie(raw: String): RawCookie? {
        val parts = raw.split(';').map(String::trim)
        if (parts.isEmpty()) return null
        val firstEquals = parts[0].indexOf('=')
        if (firstEquals <= 0) return null
        val name = parts[0].substring(0, firstEquals)
        val value = parts[0].substring(firstEquals + 1)
        val attributes = LinkedHashMap<String, String?>()
        for (index in 1 until parts.size) {
            val part = parts[index]
            if (part.isEmpty()) continue
            val equals = part.indexOf('=')
            val key = (if (equals < 0) part else part.substring(0, equals))
                .trim().lowercase(Locale.ROOT)
            val attributeValue = if (equals < 0) null else part.substring(equals + 1).trim()
            require(key.isNotEmpty() && !attributes.containsKey(key)) {
                "Ambiguous Set-Cookie attribute"
            }
            attributes[key] = attributeValue
        }
        return RawCookie(name, value, attributes)
    }

    private data class TokenEvidence(
        val scope: String,
        val expiresAtEpochMs: Long,
        val payloadBytes: ByteArray,
        val signatureBytes: ByteArray,
    )

    private fun validateAdAckToken(value: String, expectedPath: String, expectedExp: Long): TokenEvidence {
        val separator = value.indexOf('.')
        require(separator > 0 && separator == value.lastIndexOf('.') && separator < value.lastIndex) {
            "ad_ack is not a two-segment token"
        }
        val payloadText = value.substring(0, separator)
        val signatureText = value.substring(separator + 1)
        val payloadBytes = decodeCanonicalBase64Url(payloadText, "ad_ack payload")
        val signatureBytes = decodeCanonicalBase64Url(signatureText, "ad_ack signature")
        require(signatureBytes.size == 32) { "ad_ack signature shape is invalid" }
        val payload = parseObject(payloadBytes, "ad_ack payload")
        val scope = requireString(payload, "scope")
        val exp = requireLong(payload, "exp")
        require(scope == expectedPath) { "ad_ack payload scope mismatch" }
        require(exp == expectedExp) { "ad_ack payload expiration mismatch" }
        return TokenEvidence(scope, exp, payloadBytes, signatureBytes)
    }

    private fun decodeCanonicalBase64Url(value: String, label: String): ByteArray {
        require(value.matches(Regex("^[A-Za-z0-9_-]+$"))) { "$label is not base64url" }
        val decoded = runCatching { NtkBase64.decodeUrl(value) }.getOrElse {
            throw IllegalArgumentException("$label is not decodable", it)
        }
        require(NtkBase64.encodeUrlWithoutPadding(decoded) == value) {
            "$label is not canonical base64url"
        }
        return decoded
    }

    private fun requireShortFuture(
        exp: Long,
        now: Long,
        interval: Long,
        label: String,
        upperBoundToleranceMs: Long = 0L,
    ) {
        require(exp > now) { "$label grant is expired" }
        require(upperBoundToleranceMs >= 0L) { "Invalid grant interval tolerance" }
        val lifetimeMs = exp - now
        val maximumLifetimeMs = interval + upperBoundToleranceMs
        require(lifetimeMs <= maximumLifetimeMs) {
            "$label grant interval is too long " +
                "(lifetimeMs=$lifetimeMs, intervalMs=$interval, " +
                "toleranceMs=$upperBoundToleranceMs, excessMs=${lifetimeMs - maximumLifetimeMs})"
        }
    }

    private fun parseObject(bytes: ByteArray, label: String): JSONObject {
        require(bytes.isNotEmpty()) { "$label body is empty" }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrElse {
            throw IllegalArgumentException("$label is not UTF-8", it)
        }
        return runCatching { JSONObject(text) }.getOrElse {
            throw IllegalArgumentException("$label is not a JSON object", it)
        }
    }

    private fun requireExactTrue(json: JSONObject, name: String) {
        require(json.has(name) && json.get(name) is Boolean && json.getBoolean(name)) {
            "$name is not exactly true"
        }
    }

    private fun requireString(json: JSONObject, name: String): String {
        require(json.has(name) && json.get(name) is String) { "$name is not a string" }
        return json.getString(name).also { require(it.isNotBlank()) { "$name is blank" } }
    }

    private fun requireLong(json: JSONObject, name: String): Long {
        require(json.has(name)) { "$name is missing" }
        val value = json.get(name)
        require(value is Number) { "$name is not numeric" }
        val longValue = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == longValue.toDouble()) {
            "$name is not an integer"
        }
        return longValue
    }

    private fun requireObject(json: JSONObject, name: String): JSONObject {
        require(json.has(name) && json.get(name) is JSONObject) { "$name is not an object" }
        return json.getJSONObject(name)
    }

    private fun objectKeys(json: JSONObject): Set<String> {
        val keys = LinkedHashSet<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) keys += iterator.next()
        return keys
    }

    private fun singleHeader(headers: Map<String, List<String>>, name: String): String {
        val values = headerValues(headers, name)
        require(values.size == 1) { "Expected one $name header" }
        return values.single().trim()
    }

    private fun headerValues(headers: Map<String, List<String>>, name: String): List<String> =
        headers.entries
            .filter { it.key.equals(name, ignoreCase = true) }
            .flatMap { it.value }

    private fun sha256Utf8(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
