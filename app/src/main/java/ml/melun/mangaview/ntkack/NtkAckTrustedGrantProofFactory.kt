package ml.melun.mangaview.ntkack

import org.json.JSONObject

/** Builds the fail-closed proof for the server's explicit trusted-grant branch. */
object NtkAckTrustedGrantProofFactory {
    fun createUnsigned(
        request: NtkAckRequest,
        serviceInstanceId: String,
        packageName: String,
        signingCertificateDigestSha256: String,
        requestKeyId: String,
        challengeRequestBody: ByteArray,
        challenge: NtkAckTrustedGrantValidator.ChallengeEvidence,
        confirmation: NtkAckTrustedGrantValidator.AckConfirmationEvidence,
        cumulativeResponseGrants: List<NtkAckCookie>,
        observedAtEpochMs: Long,
        startedAtElapsedNanos: Long,
        completedAtElapsedNanos: Long,
    ): NtkAckProof {
        require(request.protocolVersion == NtkAckProtocol.VERSION)
        require(serviceInstanceId.isNotBlank() && packageName.isNotBlank())
        require(signingCertificateDigestSha256.isSha256())
        require(requestKeyId.isNotBlank() && requestKeyId == confirmation.requestKeyId)
        require(challenge.scope == request.episodePath && confirmation.scope == request.episodePath)
        require(challenge.intervalMs == NtkAckTrustedGrantValidator.TRUST_INTERVAL_MS)
        require(challenge.successCount in 1..Int.MAX_VALUE.toLong())
        require(challenge.subjectKind == "nginx-temp")
        require(challenge.responseHeaderDigestSha256.isSha256())
        require(confirmation.responseHeaderDigestSha256.isSha256())
        require(challenge.adAckGrant.responseUrl == "${request.origin}/api/ad/challenge")
        require(confirmation.adAckGrant.responseUrl == "${request.origin}/api/ad/ack")
        require(
            observedAtEpochMs > 0L &&
                challenge.expiresAtEpochMs > observedAtEpochMs &&
                confirmation.expiresAtEpochMs > observedAtEpochMs,
        )
        require(
            confirmation.expiresAtEpochMs - observedAtEpochMs <= challenge.intervalMs + 30_000L,
        ) { "Trusted confirmation lifetime exceeds challenge authority" }
        require(completedAtElapsedNanos >= startedAtElapsedNanos)

        val challengeJson = JSONObject(challengeRequestBody.toString(Charsets.UTF_8))
        require(challengeJson.keys().asSequence().toSet() == setOf("path")) {
            "Trusted challenge request body is not exact"
        }
        require(challengeJson.get("path") is String && challengeJson.getString("path") == request.episodePath) {
            "Trusted challenge request path mismatch"
        }

        val grants = NtkAckCookieBoundary.validateGrants(
            request.origin,
            request.episodePath,
            cumulativeResponseGrants,
        )
        val adAck = grants.singleOrNull { it.name == "ad_ack" }
            ?: throw IllegalArgumentException("Trusted challenge requires one response-local ad_ack")
        require(adAck.responseUrl == challenge.adAckGrant.responseUrl)
        require(adAck.setCookieDigestSha256 == challenge.adAckGrant.setCookieDigestSha256)
        require(NtkAckProofCodec.sha256Utf8(adAck.value) == challenge.adAckGrant.tokenDigestSha256)

        return NtkAckProof(
            protocolVersion = NtkAckProtocol.VERSION,
            proofId = "",
            serviceInstanceId = serviceInstanceId,
            flightId = request.flightId,
            generation = request.generation,
            authEpoch = request.authEpoch,
            requestNonce = request.requestNonce,
            packageName = packageName,
            appSigningCertificateDigestSha256 = signingCertificateDigestSha256,
            origin = request.origin,
            episodePath = request.episodePath,
            userAgentDigestSha256 = NtkAckProofCodec.sha256Utf8(request.userAgent),
            viewportDigestSha256 = NtkAckProofCodec.viewportDigest(request.viewport),
            proofMode = NtkAckProtocol.PROOF_MODE_TRUSTED_SERVER_GRANT,
            challengeRequestDigestSha256 = NtkAckProofCodec.sha256Hex(challengeRequestBody),
            challengeResponseDigestSha256 = challenge.responseBodyDigestSha256,
            challengeStatus = 200,
            trustedScopeDigestSha256 = NtkAckProofCodec.sha256Utf8(request.episodePath),
            trustedObservedAtEpochMs = observedAtEpochMs,
            trustedExpiresAtEpochMs = challenge.expiresAtEpochMs,
            trustedIntervalMs = challenge.intervalMs,
            trustedSuccessCount = challenge.successCount.toInt(),
            trustedSubjectKind = challenge.subjectKind,
            trustedChallengeHeaderDigestSha256 = challenge.responseHeaderDigestSha256,
            trustedChallengeCookiePayloadDigestSha256 = challenge.adAckGrant.payloadDigestSha256,
            trustedAckHeaderDigestSha256 = confirmation.responseHeaderDigestSha256,
            trustedAckCookiePayloadDigestSha256 = confirmation.adAckGrant.payloadDigestSha256,
            challengeTokenDigestSha256 = "",
            guardVersion = "",
            guardJsDigestSha256 = "",
            guardWasmDigestSha256 = "",
            guardTpDigestSha256 = "",
            observationSetDigestSha256 = "",
            requiredObservationCount = 0,
            observed2xxCount = 0,
            canaryRequestDigestSha256 = "",
            canaryResponseDigestSha256 = "",
            canaryStatus = 0,
            requestKeyId = requestKeyId,
            ackRequestBodyDigestSha256 = confirmation.requestBodyDigestSha256,
            ackResponseBodyDigestSha256 = confirmation.responseBodyDigestSha256,
            ackStatus = 200,
            ackOutcome = "trusted-grant",
            cookieGrantDigestSha256 = NtkAckProofCodec.cookieGrantDigest(grants),
            cookieGrants = grants,
            startedAtElapsedNanos = startedAtElapsedNanos,
            completedAtElapsedNanos = completedAtElapsedNanos,
            canonicalEnvelope = byteArrayOf(),
            envelopeDigestSha256 = "",
            signature = byteArrayOf(),
        )
    }

    private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
}
