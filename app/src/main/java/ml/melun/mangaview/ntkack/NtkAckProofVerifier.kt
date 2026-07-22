package ml.melun.mangaview.ntkack

import java.security.PublicKey

/** Main-process verifier for remote ACK evidence and browser quiescence. */
object NtkAckProofVerifier {
    data class VerifiedService(
        val hello: NtkAckServiceHello,
        val publicKey: PublicKey,
    )

    fun verifyHelloOrThrow(
        hello: NtkAckServiceHello,
        expectedServicePid: Int? = null,
    ): VerifiedService {
        require(hello.protocolVersion == NtkAckProtocol.VERSION) { "ACK protocol mismatch" }
        require(hello.serviceInstanceId.isNotBlank()) { "ACK service instance is missing" }
        require(hello.servicePid > 0) { "ACK service PID is invalid" }
        require(hello.webViewCreatedPid == 0 || hello.webViewCreatedPid == hello.servicePid) {
            "ACK WebView is not service-owned"
        }
        require(hello.dataDirectorySuffix == NtkAckProtocol.DATA_DIRECTORY_SUFFIX) {
            "ACK WebView storage mode mismatch"
        }
        if (expectedServicePid != null) {
            require(hello.servicePid == expectedServicePid) { "ACK service PID changed" }
        }
        return VerifiedService(hello, NtkAckProofCodec.decodePublicKey(hello.proofPublicKeyX509))
    }

    fun verifyOrThrow(
        proof: NtkAckProof,
        service: VerifiedService,
        request: NtkAckRequest,
        expectedPackageName: String,
        expectedSigningCertificateDigestSha256: String,
    ): NtkAckProof {
        require(proof.serviceInstanceId == service.hello.serviceInstanceId) {
            "ACK proof service instance mismatch"
        }
        require(proof.flightId == request.flightId) { "ACK proof flight mismatch" }
        require(proof.generation == request.generation) { "ACK proof generation mismatch" }
        require(proof.authEpoch == request.authEpoch) { "ACK proof auth epoch mismatch" }
        require(proof.origin == request.origin) { "ACK proof origin mismatch" }
        require(proof.episodePath == request.episodePath) { "ACK proof path mismatch" }
        require(proof.requestNonce.contentEquals(request.requestNonce)) { "ACK proof nonce mismatch" }
        require(proof.packageName == expectedPackageName) { "ACK proof package mismatch" }
        require(
            proof.appSigningCertificateDigestSha256 == expectedSigningCertificateDigestSha256,
        ) { "ACK proof signing certificate mismatch" }
        require(proof.userAgentDigestSha256 == NtkAckProofCodec.sha256Utf8(request.userAgent)) {
            "ACK proof user agent mismatch"
        }
        require(proof.viewportDigestSha256 == NtkAckProofCodec.viewportDigest(request.viewport)) {
            "ACK proof viewport mismatch"
        }
        require(request.requestNonce.size == 32) { "ACK request nonce length is invalid" }
        require(proof.challengeStatus == 200) { "ACK challenge was not HTTP 200" }
        require(
            proof.challengeRequestDigestSha256.isSha256() &&
                proof.challengeResponseDigestSha256.isSha256(),
        ) { "ACK challenge transcript digest is invalid" }
        when (proof.proofMode) {
            NtkAckProtocol.PROOF_MODE_FULL_CHALLENGE -> verifyFullChallengeProof(proof)
            NtkAckProtocol.PROOF_MODE_TRUSTED_SERVER_GRANT ->
                verifyTrustedServerGrantProof(proof, request)
            else -> throw IllegalArgumentException("Unknown ACK proof mode")
        }
        NtkAckCookieBoundary.validateGrants(request.origin, request.episodePath, proof.cookieGrants)
        require(proof.cookieGrantDigestSha256 == NtkAckProofCodec.cookieGrantDigest(proof.cookieGrants)) {
            "ACK cookie grant digest mismatch"
        }
        require(proof.completedAtElapsedNanos >= proof.startedAtElapsedNanos) {
            "ACK proof time ordering is invalid"
        }
        require(NtkAckProofCodec.verifyProof(proof, service.publicKey)) {
            "ACK proof signature is invalid"
        }
        return proof
    }

    private fun verifyFullChallengeProof(proof: NtkAckProof) {
        require(proof.trustedScopeDigestSha256.isEmpty()) { "Full ACK carried trusted scope" }
        require(proof.trustedObservedAtEpochMs == 0L && proof.trustedExpiresAtEpochMs == 0L) {
            "Full ACK carried trusted lifetime"
        }
        require(proof.trustedIntervalMs == 0L && proof.trustedSuccessCount == 0) {
            "Full ACK carried trusted counters"
        }
        require(
            proof.trustedSubjectKind.isEmpty() &&
                proof.trustedChallengeHeaderDigestSha256.isEmpty() &&
                proof.trustedChallengeCookiePayloadDigestSha256.isEmpty() &&
                proof.trustedAckHeaderDigestSha256.isEmpty() &&
                proof.trustedAckCookiePayloadDigestSha256.isEmpty(),
        ) { "Full ACK mixed trusted-grant evidence" }
        require(proof.challengeTokenDigestSha256.isSha256()) { "ACK challenge token is missing" }
        require(proof.guardVersion.isNotBlank()) { "ACK guard version is missing" }
        require(proof.guardJsDigestSha256.isSha256() && proof.guardWasmDigestSha256.isSha256()) {
            "ACK guard pair digest is invalid"
        }
        require(proof.guardTpDigestSha256.isSha256()) { "ACK guard result is missing" }
        require(proof.requiredObservationCount > 0) { "ACK required no metric observations" }
        require(proof.observed2xxCount == proof.requiredObservationCount) {
            "ACK metric observations are incomplete"
        }
        require(
            proof.canaryRequestDigestSha256.isSha256() &&
                proof.canaryResponseDigestSha256.isSha256() &&
                proof.canaryStatus == 200,
        ) { "ACK canary evidence is invalid" }
        require(
            proof.ackRequestBodyDigestSha256.isSha256() &&
                proof.ackResponseBodyDigestSha256.isSha256() &&
                proof.ackStatus == 200 && proof.ackOutcome in setOf("ok", "acked"),
        ) { "ACK server outcome is invalid" }
    }

    private fun verifyTrustedServerGrantProof(proof: NtkAckProof, request: NtkAckRequest) {
        require(proof.challengeTokenDigestSha256.isEmpty()) { "Trusted grant carried a challenge token" }
        require(
            proof.guardVersion.isEmpty() && proof.guardJsDigestSha256.isEmpty() &&
                proof.guardWasmDigestSha256.isEmpty() && proof.guardTpDigestSha256.isEmpty(),
        ) { "Trusted grant mixed guard evidence" }
        require(
            proof.observationSetDigestSha256.isEmpty() && proof.requiredObservationCount == 0 &&
                proof.observed2xxCount == 0,
        ) { "Trusted grant mixed metric evidence" }
        require(
            proof.canaryRequestDigestSha256.isEmpty() &&
                proof.canaryResponseDigestSha256.isEmpty() && proof.canaryStatus == 0,
        ) { "Trusted grant mixed canary evidence" }
        require(proof.trustedScopeDigestSha256 == NtkAckProofCodec.sha256Utf8(request.episodePath)) {
            "Trusted grant scope mismatch"
        }
        require(proof.trustedObservedAtEpochMs > 0L) { "Trusted grant observation time is missing" }
        require(proof.trustedIntervalMs in 1_000L..600_000L) { "Trusted grant interval is invalid" }
        require(proof.trustedExpiresAtEpochMs > proof.trustedObservedAtEpochMs) {
            "Trusted grant is expired"
        }
        require(
            proof.trustedExpiresAtEpochMs - proof.trustedObservedAtEpochMs <=
                proof.trustedIntervalMs + 30_000L,
        ) { "Trusted grant lifetime exceeds its interval" }
        require(proof.trustedSuccessCount > 0) { "Trusted grant success evidence is missing" }
        require(proof.trustedSubjectKind == "nginx-temp") { "Trusted grant subject is invalid" }
        require(
            proof.trustedChallengeHeaderDigestSha256.isSha256() &&
                proof.trustedChallengeCookiePayloadDigestSha256.isSha256() &&
                proof.trustedAckHeaderDigestSha256.isSha256() &&
                proof.trustedAckCookiePayloadDigestSha256.isSha256(),
        ) { "Trusted grant header or cookie evidence is invalid" }
        require(
            proof.ackRequestBodyDigestSha256.isSha256() &&
                proof.ackResponseBodyDigestSha256.isSha256() &&
                proof.ackStatus == 200 && proof.ackOutcome == "trusted-grant",
        ) { "Trusted grant confirmation is invalid" }
        val adAck = proof.cookieGrants.singleOrNull { it.name == "ad_ack" }
            ?: throw IllegalArgumentException("Trusted grant requires exactly one ad_ack cookie")
        require(adAck.secure && adAck.path == "/") { "Trusted ad_ack cookie scope is invalid" }
        require(adAck.responseUrl == "${request.origin}/api/ad/challenge") {
            "Trusted ad_ack was not issued by the challenge response"
        }
        require(adAck.expiresAtEpochMs > proof.trustedObservedAtEpochMs) {
            "Trusted ad_ack cookie is expired"
        }
    }

    fun verifyQuiescenceOrThrow(
        seal: NtkAckQuiescenceSeal,
        proof: NtkAckProof,
        service: VerifiedService,
    ): NtkAckQuiescenceSeal {
        require(seal.serviceInstanceId == proof.serviceInstanceId)
        require(seal.flightId == proof.flightId)
        require(seal.generation == proof.generation)
        require(seal.authEpoch == proof.authEpoch)
        require(seal.origin == proof.origin)
        require(seal.episodePath == proof.episodePath)
        require(seal.ackProofDigestSha256 == proof.envelopeDigestSha256)
        require(seal.servicePid == service.hello.servicePid)
        require(seal.webViewDestroyed) { "ACK WebView was not destroyed" }
        require(seal.activeTransportCalls == 0) { "ACK transport is still active" }
        require(seal.rendererGoneObserved || seal.rendererAbsentBeforeDestroy) {
            "ACK renderer termination is unproved"
        }
        require(seal.completedAtElapsedNanos >= proof.completedAtElapsedNanos) {
            "ACK quiescence preceded proof"
        }
        require(NtkAckProofCodec.verifyQuiescence(seal, service.publicKey)) {
            "ACK quiescence signature is invalid"
        }
        return seal
    }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
