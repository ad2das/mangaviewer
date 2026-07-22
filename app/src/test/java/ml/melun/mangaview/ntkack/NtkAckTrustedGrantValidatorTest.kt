package ml.melun.mangaview.ntkack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

class NtkAckTrustedGrantValidatorTest {
    @Test
    fun exactTrustedChallengeReturnsNormalizedEvidence() {
        val evidence = NtkAckTrustedGrantValidator.validateChallenge(
            ORIGIN,
            PATH,
            NOW,
            challengeResponse(),
        )

        assertEquals(PATH, evidence.scope)
        assertEquals(EXP, evidence.expiresAtEpochMs)
        assertEquals(300_000L, evidence.intervalMs)
        assertEquals("nginx-temp", evidence.subjectKind)
        assertEquals(999L, evidence.successCount)
        assertEquals(MARKER, evidence.temporaryBypassMarker)
        assertEquals(
            sha256("x-ntk-ad-valid:1\nx-ntk-ad-temp-bypass:$MARKER"),
            evidence.responseHeaderDigestSha256,
        )
        assertEquals(PATH, evidence.adAckGrant.scope)
        assertEquals(EXP, evidence.adAckGrant.expiresAtEpochMs)
        assertEquals("$ORIGIN/api/ad/challenge", evidence.adAckGrant.responseUrl)
    }

    @Test
    fun trustedChallengeDoesNotInheritConfirmationClockTolerance() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            NtkAckTrustedGrantValidator.validateChallenge(
                ORIGIN,
                PATH,
                NOW,
                challengeResponseAt(NOW + 300_001L),
            )
        }

        assertTrue(error.message.orEmpty().contains("Trusted challenge grant interval is too long"))
        assertTrue(error.message.orEmpty().contains("toleranceMs=0"))
        assertTrue(error.message.orEmpty().contains("excessMs=1"))
    }

    @Test
    fun trustedChallengeRequiresEveryBooleanScopeExpiryAndTrustField() {
        val mutations = listOf(
            challengeJson().put("ok", false),
            challengeJson().put("trusted", false),
            challengeJson().put("ackValid", false),
            challengeJson().put("scope", "/webtoon/850236/other"),
            challengeJson().put("exp", NOW),
            challengeJson().put("exp", NOW + 300_001L),
            challengeJson().put("trust", JSONObject(challengeJson().getJSONObject("trust").toString()).put("intervalMs", 299_999L)),
            challengeJson().put("trust", JSONObject(challengeJson().getJSONObject("trust").toString()).put("subjectKind", "browser")),
            challengeJson().put("trust", JSONObject(challengeJson().getJSONObject("trust").toString()).put("successCount", 998)),
        )
        mutations.forEach { body -> assertChallengeRejected(response = challengeResponse(body = body)) }

        listOf("ok", "trusted", "ackValid", "scope", "exp", "trust").forEach { field ->
            assertChallengeRejected(response = challengeResponse(body = challengeJson().also { it.remove(field) }))
        }
        listOf("intervalMs", "subjectKind", "successCount").forEach { field ->
            val body = challengeJson()
            body.getJSONObject("trust").remove(field)
            assertChallengeRejected(response = challengeResponse(body = body))
        }
    }

    @Test
    fun trustedChallengeRejectsHybridAndCoercedJsonTypes() {
        assertChallengeRejected(
            response = challengeResponse(body = challengeJson().put("challenge", JSONObject().put("token", "x"))),
        )
        assertChallengeRejected(
            response = challengeResponse(body = challengeJson().put("temporary", true)),
        )
        assertChallengeRejected(
            response = challengeResponse(body = challengeJson().put("ackValid", "true")),
        )
        assertChallengeRejected(
            response = challengeResponse(body = challengeJson().put("exp", EXP.toString())),
        )
        assertChallengeRejected(
            response = challengeResponse(
                status = 204,
                body = challengeJson(),
            ),
        )
    }

    @Test
    fun trustedChallengeRequiresBothExactResponseMarkers() {
        assertChallengeRejected(
            response = challengeResponse(headers = challengeHeaders().minus("x-ntk-ad-valid")),
        )
        assertChallengeRejected(
            response = challengeResponse(headers = challengeHeaders() + ("x-ntk-ad-valid" to listOf("true"))),
        )
        assertChallengeRejected(
            response = challengeResponse(headers = challengeHeaders().minus("x-ntk-ad-temp-bypass")),
        )
        assertChallengeRejected(
            response = challengeResponse(headers = challengeHeaders() + ("x-ntk-ad-temp-bypass" to listOf("nginx-current"))),
        )
        assertChallengeRejected(
            response = challengeResponse(headers = challengeHeaders() + ("X-NTK-AD-VALID" to listOf("1"))),
        )
    }

    @Test
    fun rawAndParsedAdAckMustComeFromTheSameChallengeResponse() {
        val missingRaw = challengeHeaders().minus("Set-Cookie")
        assertChallengeRejected(response = challengeResponse(headers = missingRaw))

        val missingParsed = challengeResponse().copy(cookies = emptyList())
        assertChallengeRejected(response = missingParsed)

        val wrongEndpoint = challengeResponse().copy(
            cookies = listOf(parsedCookie("/api/ad/ack")),
        )
        assertChallengeRejected(response = wrongEndpoint)

        val wrongDigest = challengeResponse().copy(
            cookies = listOf(parsedCookie("/api/ad/challenge").copy(setCookieDigestSha256 = "00".repeat(32))),
        )
        assertChallengeRejected(response = wrongDigest)

        val duplicateRaw = challengeHeaders() + ("Set-Cookie" to listOf(RAW_COOKIE, RAW_COOKIE))
        assertChallengeRejected(response = challengeResponse(headers = duplicateRaw))

        val duplicateParsed = challengeResponse().copy(
            cookies = listOf(parsedCookie("/api/ad/challenge"), parsedCookie("/api/ad/challenge")),
        )
        assertChallengeRejected(response = duplicateParsed)
    }

    @Test
    fun adAckRawAttributesAreAllMandatoryAndExact() {
        val mutations = listOf(
            RAW_COOKIE.replace("; Secure", ""),
            RAW_COOKIE.replace("; HttpOnly", ""),
            RAW_COOKIE.replace("SameSite=Lax", "SameSite=None"),
            RAW_COOKIE.replace("Path=/", "Path=/webtoon"),
        )
        mutations.forEach { raw ->
            val response = challengeResponse(
                headers = challengeHeaders(raw),
                cookies = listOf(parsedCookie("/api/ad/challenge", raw)),
            )
            assertChallengeRejected(response = response)
        }
    }

    @Test
    fun adAckTokenRequiresCanonicalTwoSegmentsExactScopeExpAnd32ByteSignature() {
        val wrongTokens = listOf(
            token(PATH, EXP) + ".third",
            token("/webtoon/850236/other", EXP),
            token(PATH, EXP + 1L),
            token(PATH, EXP, signatureSize = 31),
            token(PATH, EXP).replace('.', '='),
        )
        wrongTokens.forEach { wrong ->
            val raw = rawCookie(wrong)
            assertChallengeRejected(
                response = challengeResponse(
                    headers = challengeHeaders(raw),
                    cookies = listOf(parsedCookie("/api/ad/challenge", raw, wrong)),
                ),
            )
        }
    }

    @Test
    fun exactAckConfirmationBindsOnlyPathAndKeyAndFreshGrant() {
        val evidence = NtkAckTrustedGrantValidator.validateAckConfirmation(
            ORIGIN,
            PATH,
            KEY_ID,
            NOW,
            confirmRequest(),
            confirmResponse(),
        )

        assertEquals(PATH, evidence.scope)
        assertEquals(KEY_ID, evidence.requestKeyId)
        assertEquals(EXP, evidence.expiresAtEpochMs)
        assertEquals(4L, evidence.impressionSeen)
        assertEquals(4L, evidence.impressionExpected)
        assertEquals(2L, evidence.impressionMinSeen)
        assertEquals(
            sha256("x-ntk-ad-valid:1\nx-ntk-ad-temp-bypass:$MARKER"),
            evidence.responseHeaderDigestSha256,
        )
        assertEquals("$ORIGIN/api/ad/ack", evidence.adAckGrant.responseUrl)
    }

    @Test
    fun ackConfirmationAllowsOnlyOneSecondOfClockQuantization() {
        val accepted = NtkAckTrustedGrantValidator.validateAckConfirmation(
            ORIGIN,
            PATH,
            KEY_ID,
            NOW,
            confirmRequest(),
            confirmResponseAt(NOW + 301_000L),
        )
        assertEquals(NOW + 301_000L, accepted.expiresAtEpochMs)

        val error = assertThrows(IllegalArgumentException::class.java) {
            NtkAckTrustedGrantValidator.validateAckConfirmation(
                ORIGIN,
                PATH,
                KEY_ID,
                NOW,
                confirmRequest(),
                confirmResponseAt(NOW + 301_001L),
            )
        }
        assertTrue(error.message.orEmpty().contains("ACK confirmation grant interval is too long"))
        assertTrue(error.message.orEmpty().contains("toleranceMs=1000"))
        assertTrue(error.message.orEmpty().contains("excessMs=1"))

        assertConfirmRejected(response = confirmResponseAt(NOW))
    }

    @Test
    fun trustedChallengeAndFreshConfirmationBuildAVerifiableIsolatedProof() {
        val request = NtkAckRequest(
            protocolVersion = NtkAckProtocol.VERSION,
            flightId = "91e8daa1-9222-48fd-94be-a0308c4eab59",
            generation = 1L,
            authEpoch = 1L,
            requestNonce = ByteArray(32) { it.toByte() },
            origin = ORIGIN,
            episodePath = PATH,
            userAgent = "Mozilla/5.0 trusted-grant-test",
            uaMetadata = "Android|mobile",
            viewport = NtkAckViewport(1080, 2340, 440),
            seedCookies = emptyList(),
            deadlineElapsedRealtimeNanos = Long.MAX_VALUE,
            clientPid = 1234,
        )
        val challengeBody = JSONObject().put("path", PATH).toString().toByteArray()
        val confirmationBody = JSONObject()
            .put("path", PATH)
            .put("requestKeyId", KEY_ID)
            .toString()
            .toByteArray()
        val challengeResponse = challengeResponse()
        val challenge = NtkAckTrustedGrantValidator.validateChallenge(
            ORIGIN,
            PATH,
            NOW,
            challengeResponse,
        )
        val confirmationResponse = confirmResponseAt(NOW + 301_000L)
        val confirmation = NtkAckTrustedGrantValidator.validateAckConfirmation(
            ORIGIN,
            PATH,
            KEY_ID,
            NOW,
            NtkAckTrustedGrantValidator.AckConfirmRequest("POST", "/api/ad/ack", confirmationBody),
            confirmationResponse,
        )
        val certificateDigest = "ab".repeat(32)
        val auxiliaryChallengeGrants = listOf(
            auxiliaryGrant("ad_ack_c", "challenge-control"),
            auxiliaryGrant("ad_guard_l", "guard-level"),
            auxiliaryGrant("ntk_ve", "viewer-evidence"),
        )
        val confirmationExportGrants = auxiliaryChallengeGrants + confirmationResponse.cookies
        val unsigned = NtkAckTrustedGrantProofFactory.createUnsigned(
            request = request,
            serviceInstanceId = "trusted-service",
            packageName = "ml.melun.mangaview",
            signingCertificateDigestSha256 = certificateDigest,
            requestKeyId = KEY_ID,
            challengeRequestBody = challengeBody,
            challenge = challenge,
            confirmation = confirmation,
            cumulativeResponseGrants = auxiliaryChallengeGrants + challengeResponse.cookies,
            observedAtEpochMs = NOW,
            startedAtElapsedNanos = 100L,
            completedAtElapsedNanos = 200L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckTrustedGrantProofFactory.createUnsigned(
                request = request,
                serviceInstanceId = "trusted-service",
                packageName = "ml.melun.mangaview",
                signingCertificateDigestSha256 = certificateDigest,
                requestKeyId = KEY_ID,
                challengeRequestBody = challengeBody,
                challenge = challenge,
                confirmation = confirmation,
                cumulativeResponseGrants = confirmationExportGrants,
                observedAtEpochMs = NOW,
                startedAtElapsedNanos = 100L,
                completedAtElapsedNanos = 200L,
            )
        }
        val proofKey = NtkAckRequestKeyStore.generateKeyPair()
        val proof = NtkAckProofCodec.signProof(unsigned, proofKey)
        val service = NtkAckProofVerifier.verifyHelloOrThrow(
            NtkAckServiceHello(
                NtkAckProtocol.VERSION,
                "trusted-service",
                2222,
                proofKey.public.encoded,
                NtkAckProtocol.DATA_DIRECTORY_SUFFIX,
                2222,
                90L,
            ),
        )

        NtkAckProofVerifier.verifyOrThrow(
            proof,
            service,
            request,
            "ml.melun.mangaview",
            certificateDigest,
        )
        assertEquals(NtkAckProtocol.PROOF_MODE_TRUSTED_SERVER_GRANT, proof.proofMode)
        assertEquals("trusted-grant", proof.ackOutcome)
        assertEquals(EXP, proof.trustedExpiresAtEpochMs)
        assertEquals("", proof.challengeTokenDigestSha256)
        val exportedAdAck = proof.cookieGrants.single { it.name == "ad_ack" }
        assertEquals("$ORIGIN/api/ad/challenge", exportedAdAck.responseUrl)
        assertEquals(challengeResponse.cookies.single().value, exportedAdAck.value)
        assertTrue(exportedAdAck.value != confirmationResponse.cookies.single().value)
        assertEquals(
            confirmation.adAckGrant.payloadDigestSha256,
            proof.trustedAckCookiePayloadDigestSha256,
        )
        assertEquals(
            setOf("ad_ack", "ad_ack_c", "ad_guard_l", "ntk_ve"),
            proof.cookieGrants.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("$ORIGIN/api/ad/challenge"),
            proof.cookieGrants.filter { it.name != "ad_ack" }.map { it.responseUrl }.toSet(),
        )

        val confirmationExport = NtkAckProofCodec.signProof(
            unsigned.copy(
                cookieGrantDigestSha256 = NtkAckProofCodec.cookieGrantDigest(confirmationExportGrants),
                cookieGrants = confirmationExportGrants,
            ),
            proofKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckProofVerifier.verifyOrThrow(
                confirmationExport,
                service,
                request,
                "ml.melun.mangaview",
                certificateDigest,
            )
        }
    }

    @Test
    fun ackConfirmationRequestIsExactAndDoesNotInventServerSignatureProof() {
        val wrongRequests = listOf(
            confirmRequest(body = JSONObject().put("path", "/webtoon/850236/other").put("requestKeyId", KEY_ID)),
            confirmRequest(body = JSONObject().put("path", PATH).put("requestKeyId", "other-key")),
            confirmRequest(body = JSONObject().put("path", PATH).put("requestKeyId", KEY_ID).put("challengeResponseDigestSha256", "11".repeat(32))),
            confirmRequest(body = JSONObject().put("path", PATH)),
            confirmRequest(method = "GET"),
            confirmRequest(endpoint = "/api/manhwa-images"),
        )
        wrongRequests.forEach { request -> assertConfirmRejected(request = request) }
    }

    @Test
    fun ackConfirmationRequiresExactResponseAndImpressionContract() {
        val bodies = listOf(
            confirmJson().put("ok", false),
            confirmJson().put("temporary", false),
            confirmJson().put("exp", NOW),
            confirmJson().put("exp", NOW + 301_001L),
            confirmJson().put("impression", JSONObject(confirmJson().getJSONObject("impression").toString()).put("ok", false)),
            confirmJson().put("impression", JSONObject(confirmJson().getJSONObject("impression").toString()).put("seen", 3)),
            confirmJson().put("impression", JSONObject(confirmJson().getJSONObject("impression").toString()).put("expected", 5)),
            confirmJson().put("impression", JSONObject(confirmJson().getJSONObject("impression").toString()).put("minSeen", 1)),
        )
        bodies.forEach { assertConfirmRejected(response = confirmResponse(body = it)) }
        listOf("ok", "temporary", "exp", "impression").forEach { field ->
            assertConfirmRejected(response = confirmResponse(body = confirmJson().also { it.remove(field) }))
        }
        listOf("ok", "seen", "expected", "minSeen").forEach { field ->
            val body = confirmJson()
            body.getJSONObject("impression").remove(field)
            assertConfirmRejected(response = confirmResponse(body = body))
        }
        assertConfirmRejected(response = confirmResponse(status = 201))
    }

    @Test
    fun ackConfirmationRejectsChallengeTrustedAndAckValidHybrids() {
        listOf(
            "challenge" to JSONObject(),
            "trusted" to true,
            "ackValid" to true,
            "trust" to JSONObject().put("intervalMs", 300_000),
        ).forEach { (name, value) ->
            assertConfirmRejected(response = confirmResponse(body = confirmJson().put(name, value)))
        }
    }

    @Test
    fun confirmationMarkersAndGrantMustBeFromTheAckResponse() {
        assertConfirmRejected(
            response = confirmResponse(headers = challengeHeaders().minus("x-ntk-ad-valid")),
        )
        assertConfirmRejected(
            response = confirmResponse(headers = challengeHeaders().minus("x-ntk-ad-temp-bypass")),
        )
        assertConfirmRejected(
            response = confirmResponse(cookies = listOf(parsedCookie("/api/ad/challenge"))),
        )
        assertConfirmRejected(response = confirmResponse(cookies = emptyList()))
    }

    private fun assertChallengeRejected(
        response: NtkAckTrustedGrantValidator.ResponseEvidence = challengeResponse(),
    ) {
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckTrustedGrantValidator.validateChallenge(ORIGIN, PATH, NOW, response)
        }
    }

    private fun assertConfirmRejected(
        request: NtkAckTrustedGrantValidator.AckConfirmRequest = confirmRequest(),
        response: NtkAckTrustedGrantValidator.ResponseEvidence = confirmResponse(),
    ) {
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckTrustedGrantValidator.validateAckConfirmation(
                ORIGIN,
                PATH,
                KEY_ID,
                NOW,
                request,
                response,
            )
        }
    }

    private fun challengeResponse(
        status: Int = 200,
        body: JSONObject = challengeJson(),
        headers: Map<String, List<String>> = challengeHeaders(),
        cookies: List<NtkAckCookie> = listOf(parsedCookie("/api/ad/challenge")),
    ) = NtkAckTrustedGrantValidator.ResponseEvidence(
        status,
        body.toString().toByteArray(),
        headers,
        cookies,
    )

    private fun challengeResponseAt(expiresAtEpochMs: Long): NtkAckTrustedGrantValidator.ResponseEvidence {
        val raw = rawCookie(token(PATH, expiresAtEpochMs))
        return challengeResponse(
            body = challengeJson().put("exp", expiresAtEpochMs),
            headers = challengeHeaders(raw),
            cookies = listOf(
                parsedCookie(
                    "/api/ad/challenge",
                    raw,
                    expiresAtEpochMs = expiresAtEpochMs,
                ),
            ),
        )
    }

    private fun confirmResponse(
        status: Int = 200,
        body: JSONObject = confirmJson(),
        headers: Map<String, List<String>> = challengeHeaders(),
        cookies: List<NtkAckCookie> = listOf(parsedCookie("/api/ad/ack")),
    ) = NtkAckTrustedGrantValidator.ResponseEvidence(
        status,
        body.toString().toByteArray(),
        headers,
        cookies,
    )

    private fun confirmResponseAt(expiresAtEpochMs: Long): NtkAckTrustedGrantValidator.ResponseEvidence {
        val raw = rawCookie(token(PATH, expiresAtEpochMs))
        return confirmResponse(
            body = confirmJson().put("exp", expiresAtEpochMs),
            headers = challengeHeaders(raw),
            cookies = listOf(
                parsedCookie(
                    "/api/ad/ack",
                    raw,
                    expiresAtEpochMs = expiresAtEpochMs,
                ),
            ),
        )
    }

    private fun confirmRequest(
        method: String = "POST",
        endpoint: String = "/api/ad/ack",
        body: JSONObject = JSONObject().put("path", PATH).put("requestKeyId", KEY_ID),
    ) = NtkAckTrustedGrantValidator.AckConfirmRequest(
        method,
        endpoint,
        body.toString().toByteArray(),
    )

    private fun challengeJson() = JSONObject()
        .put("ok", true)
        .put("ackValid", true)
        .put("scope", PATH)
        .put("exp", EXP)
        .put("trusted", true)
        .put(
            "trust",
            JSONObject()
                .put("intervalMs", 300_000)
                .put("subjectKind", "nginx-temp")
                .put("successCount", 999),
        )

    private fun confirmJson() = JSONObject()
        .put("ok", true)
        .put("temporary", true)
        .put("exp", EXP)
        .put(
            "impression",
            JSONObject()
                .put("ok", true)
                .put("seen", 4)
                .put("expected", 4)
                .put("minSeen", 2),
        )

    private fun challengeHeaders(rawCookie: String = RAW_COOKIE): Map<String, List<String>> = linkedMapOf(
        "x-ntk-ad-valid" to listOf("1"),
        "x-ntk-ad-temp-bypass" to listOf(MARKER),
        "Set-Cookie" to listOf(rawCookie),
    )

    private fun parsedCookie(
        endpoint: String,
        raw: String = RAW_COOKIE,
        value: String = raw.substringAfter("ad_ack=").substringBefore(';'),
        expiresAtEpochMs: Long = EXP,
    ) = NtkAckCookie(
        name = "ad_ack",
        value = value,
        responseUrl = ORIGIN + endpoint,
        domain = "sbxh9.com",
        path = "/",
        secure = true,
        expiresAtEpochMs = expiresAtEpochMs,
        setCookieDigestSha256 = sha256(raw),
    )

    private fun auxiliaryGrant(name: String, value: String): NtkAckCookie {
        val raw = "$name=$value; Path=/; Secure; HttpOnly; SameSite=Lax"
        return NtkAckCookie(
            name = name,
            value = value,
            responseUrl = "$ORIGIN/api/ad/challenge",
            domain = "sbxh9.com",
            path = "/",
            secure = true,
            expiresAtEpochMs = EXP,
            setCookieDigestSha256 = sha256(raw),
        )
    }

    companion object {
        private const val ORIGIN = "https://sbxh9.com"
        private const val PATH = "/webtoon/850236/nv-850236-11"
        private const val KEY_ID = "key-850236"
        private const val NOW = 1_800_000_000_000L
        private const val EXP = NOW + 299_000L
        private const val MARKER = "nginx-20260717"

        private fun token(path: String, exp: Long, signatureSize: Int = 32): String {
            val payload = JSONObject().put("scope", path).put("exp", exp)
                .toString().toByteArray()
            val signature = ByteArray(signatureSize) { (it + 1).toByte() }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." +
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        }

        private fun rawCookie(value: String) =
            "ad_ack=$value; Path=/; Secure; HttpOnly; SameSite=Lax"

        private val TOKEN = token(PATH, EXP)
        private val RAW_COOKIE = rawCookie(TOKEN)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
