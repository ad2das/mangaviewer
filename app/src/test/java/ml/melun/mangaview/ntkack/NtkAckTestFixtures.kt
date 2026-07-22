package ml.melun.mangaview.ntkack

import java.security.KeyPair

internal object NtkAckTestFixtures {
    const val ORIGIN = "https://newtoki.example"
    const val PATH = "/manhwa/33727/1692251"
    const val FLIGHT = "3dcf578c-daf9-42df-afb1-a8ab584cf413"
    const val GENERATION = 41L
    const val AUTH_EPOCH = 7L
    val D0 = "00".repeat(32)
    val D1 = "11".repeat(32)
    val D2 = "22".repeat(32)
    val D3 = "33".repeat(32)
    val D4 = "44".repeat(32)
    val D5 = "55".repeat(32)
    val D6 = "66".repeat(32)
    val D7 = "77".repeat(32)
    val D8 = "88".repeat(32)
    val D9 = "99".repeat(32)
    val DA = "aa".repeat(32)
    val DB = "bb".repeat(32)
    val DC = "cc".repeat(32)
    val DD = "dd".repeat(32)
    val DE = "ee".repeat(32)
    val DF = "ff".repeat(32)

    fun request() = NtkAckRequest(
        protocolVersion = NtkAckProtocol.VERSION,
        flightId = FLIGHT,
        generation = GENERATION,
        authEpoch = AUTH_EPOCH,
        requestNonce = ByteArray(32) { it.toByte() },
        origin = ORIGIN,
        episodePath = PATH,
        userAgent = "Mozilla/5.0 test",
        uaMetadata = "Android|mobile",
        viewport = NtkAckViewport(1080, 2340, 440, 0, 72, 0, 126),
        seedCookies = emptyList(),
        deadlineElapsedRealtimeNanos = Long.MAX_VALUE,
        clientPid = 1234,
    )

    fun grant() = NtkAckCookie(
        name = "ad_ack",
        value = "grant",
        responseUrl = "$ORIGIN/api/ad/ack",
        domain = "newtoki.example",
        path = "/",
        secure = true,
        expiresAtEpochMs = 0L,
        setCookieDigestSha256 = D8,
    )

    fun unsignedProof(request: NtkAckRequest = request()): NtkAckProof {
        val grants = listOf(grant())
        return NtkAckProof(
            protocolVersion = NtkAckProtocol.VERSION,
            proofId = "",
            serviceInstanceId = "service-instance",
            flightId = request.flightId,
            generation = request.generation,
            authEpoch = request.authEpoch,
            requestNonce = request.requestNonce,
            packageName = "ml.melun.mangaview",
            appSigningCertificateDigestSha256 = D0,
            origin = request.origin,
            episodePath = request.episodePath,
            userAgentDigestSha256 = NtkAckProofCodec.sha256Utf8(request.userAgent),
            viewportDigestSha256 = NtkAckProofCodec.viewportDigest(request.viewport),
            proofMode = NtkAckProtocol.PROOF_MODE_FULL_CHALLENGE,
            challengeRequestDigestSha256 = D1,
            challengeResponseDigestSha256 = D2,
            challengeStatus = 200,
            trustedScopeDigestSha256 = "",
            trustedObservedAtEpochMs = 0L,
            trustedExpiresAtEpochMs = 0L,
            trustedIntervalMs = 0L,
            trustedSuccessCount = 0,
            trustedSubjectKind = "",
            trustedChallengeHeaderDigestSha256 = "",
            trustedChallengeCookiePayloadDigestSha256 = "",
            trustedAckHeaderDigestSha256 = "",
            trustedAckCookiePayloadDigestSha256 = "",
            challengeTokenDigestSha256 = D3,
            guardVersion = "guard-v1",
            guardJsDigestSha256 = D4,
            guardWasmDigestSha256 = D5,
            guardTpDigestSha256 = D6,
            observationSetDigestSha256 = D7,
            requiredObservationCount = 2,
            observed2xxCount = 2,
            canaryRequestDigestSha256 = D9,
            canaryResponseDigestSha256 = DA,
            canaryStatus = 200,
            requestKeyId = "key-1",
            ackRequestBodyDigestSha256 = DB,
            ackResponseBodyDigestSha256 = DC,
            ackStatus = 200,
            ackOutcome = "ok",
            cookieGrantDigestSha256 = NtkAckProofCodec.cookieGrantDigest(grants),
            cookieGrants = grants,
            startedAtElapsedNanos = 100L,
            completedAtElapsedNanos = 200L,
            canonicalEnvelope = byteArrayOf(),
            envelopeDigestSha256 = "",
            signature = byteArrayOf(),
        )
    }

    fun signedProof(keyPair: KeyPair, request: NtkAckRequest = request()): NtkAckProof =
        NtkAckProofCodec.signProof(unsignedProof(request), keyPair)

    fun signedSeal(proof: NtkAckProof, keyPair: KeyPair) = NtkAckProofCodec.signQuiescence(
        NtkAckQuiescenceSeal(
            protocolVersion = NtkAckProtocol.VERSION,
            serviceInstanceId = proof.serviceInstanceId,
            flightId = proof.flightId,
            generation = proof.generation,
            authEpoch = proof.authEpoch,
            origin = proof.origin,
            episodePath = proof.episodePath,
            ackProofDigestSha256 = proof.envelopeDigestSha256,
            servicePid = 2345,
            rendererPid = 3456,
            webViewDestroyed = true,
            rendererGoneObserved = true,
            rendererAbsentBeforeDestroy = false,
            activeTransportCalls = 0,
            completedAtElapsedNanos = 300L,
            canonicalEnvelope = byteArrayOf(),
            envelopeDigestSha256 = "",
            signature = byteArrayOf(),
        ),
        keyPair,
    )
}
