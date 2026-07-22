package ml.melun.mangaview.ntkack

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Locale

/** Canonical, length-prefixed proof and quiescence envelopes. */
object NtkAckProofCodec {
    fun signProof(draft: NtkAckProof, proofKey: KeyPair): NtkAckProof {
        require(draft.protocolVersion == NtkAckProtocol.VERSION)
        val canonical = canonicalProofEnvelope(draft)
        val digest = sha256Hex(canonical)
        return draft.copy(
            proofId = digest,
            canonicalEnvelope = canonical,
            envelopeDigestSha256 = digest,
            signature = sign(canonical, proofKey.private),
        )
    }

    fun verifyProof(proof: NtkAckProof, publicKey: PublicKey): Boolean {
        if (proof.protocolVersion != NtkAckProtocol.VERSION) return false
        val canonical = canonicalProofEnvelope(proof)
        val digest = sha256Hex(canonical)
        return proof.canonicalEnvelope.contentEquals(canonical) &&
            proof.envelopeDigestSha256 == digest &&
            proof.proofId == digest &&
            verify(canonical, proof.signature, publicKey)
    }

    fun canonicalProofEnvelope(proof: NtkAckProof): ByteArray = canonical(
        NtkAckProtocol.PROOF_DOMAIN,
        "protocolVersion" to intBytes(proof.protocolVersion),
        "serviceInstanceId" to utf8(proof.serviceInstanceId),
        "flightId" to utf8(proof.flightId),
        "generation" to longBytes(proof.generation),
        "authEpoch" to longBytes(proof.authEpoch),
        "requestNonce" to proof.requestNonce,
        "packageName" to utf8(proof.packageName),
        "appSigningCertificateDigest" to utf8(proof.appSigningCertificateDigestSha256),
        "origin" to utf8(proof.origin),
        "episodePath" to utf8(proof.episodePath),
        "userAgentDigest" to utf8(proof.userAgentDigestSha256),
        "viewportDigest" to utf8(proof.viewportDigestSha256),
        "proofMode" to utf8(proof.proofMode),
        "challengeRequestDigest" to utf8(proof.challengeRequestDigestSha256),
        "challengeResponseDigest" to utf8(proof.challengeResponseDigestSha256),
        "challengeStatus" to intBytes(proof.challengeStatus),
        "trustedScopeDigest" to utf8(proof.trustedScopeDigestSha256),
        "trustedObservedAtEpochMs" to longBytes(proof.trustedObservedAtEpochMs),
        "trustedExpiresAtEpochMs" to longBytes(proof.trustedExpiresAtEpochMs),
        "trustedIntervalMs" to longBytes(proof.trustedIntervalMs),
        "trustedSuccessCount" to intBytes(proof.trustedSuccessCount),
        "trustedSubjectKind" to utf8(proof.trustedSubjectKind),
        "trustedChallengeHeaderDigest" to utf8(proof.trustedChallengeHeaderDigestSha256),
        "trustedChallengeCookiePayloadDigest" to utf8(proof.trustedChallengeCookiePayloadDigestSha256),
        "trustedAckHeaderDigest" to utf8(proof.trustedAckHeaderDigestSha256),
        "trustedAckCookiePayloadDigest" to utf8(proof.trustedAckCookiePayloadDigestSha256),
        "challengeTokenDigest" to utf8(proof.challengeTokenDigestSha256),
        "guardVersion" to utf8(proof.guardVersion),
        "guardJsSha256" to utf8(proof.guardJsDigestSha256),
        "guardWasmSha256" to utf8(proof.guardWasmDigestSha256),
        "guardTpSha256" to utf8(proof.guardTpDigestSha256),
        "observationSetDigest" to utf8(proof.observationSetDigestSha256),
        "requiredObservationCount" to intBytes(proof.requiredObservationCount),
        "observed2xxCount" to intBytes(proof.observed2xxCount),
        "canaryRequestDigest" to utf8(proof.canaryRequestDigestSha256),
        "canaryResponseDigest" to utf8(proof.canaryResponseDigestSha256),
        "canaryStatus" to intBytes(proof.canaryStatus),
        "requestKeyId" to utf8(proof.requestKeyId),
        "ackRequestBodyDigest" to utf8(proof.ackRequestBodyDigestSha256),
        "ackResponseBodyDigest" to utf8(proof.ackResponseBodyDigestSha256),
        "ackStatus" to intBytes(proof.ackStatus),
        "ackOutcome" to utf8(proof.ackOutcome),
        "cookieGrantDigest" to utf8(proof.cookieGrantDigestSha256),
        "startedAtElapsedNanos" to longBytes(proof.startedAtElapsedNanos),
        "completedAtElapsedNanos" to longBytes(proof.completedAtElapsedNanos),
    )

    fun signQuiescence(
        draft: NtkAckQuiescenceSeal,
        proofKey: KeyPair,
    ): NtkAckQuiescenceSeal {
        require(draft.protocolVersion == NtkAckProtocol.VERSION)
        val canonical = canonicalQuiescenceEnvelope(draft)
        return draft.copy(
            canonicalEnvelope = canonical,
            envelopeDigestSha256 = sha256Hex(canonical),
            signature = sign(canonical, proofKey.private),
        )
    }

    fun verifyQuiescence(seal: NtkAckQuiescenceSeal, publicKey: PublicKey): Boolean {
        if (seal.protocolVersion != NtkAckProtocol.VERSION) return false
        val canonical = canonicalQuiescenceEnvelope(seal)
        return seal.canonicalEnvelope.contentEquals(canonical) &&
            seal.envelopeDigestSha256 == sha256Hex(canonical) &&
            verify(canonical, seal.signature, publicKey)
    }

    fun canonicalQuiescenceEnvelope(seal: NtkAckQuiescenceSeal): ByteArray = canonical(
        NtkAckProtocol.QUIESCENCE_DOMAIN,
        "protocolVersion" to intBytes(seal.protocolVersion),
        "serviceInstanceId" to utf8(seal.serviceInstanceId),
        "flightId" to utf8(seal.flightId),
        "generation" to longBytes(seal.generation),
        "authEpoch" to longBytes(seal.authEpoch),
        "origin" to utf8(seal.origin),
        "episodePath" to utf8(seal.episodePath),
        "ackProofDigest" to utf8(seal.ackProofDigestSha256),
        "servicePid" to intBytes(seal.servicePid),
        "rendererPid" to intBytes(seal.rendererPid),
        "webViewDestroyed" to booleanBytes(seal.webViewDestroyed),
        "rendererGoneObserved" to booleanBytes(seal.rendererGoneObserved),
        "rendererAbsentBeforeDestroy" to booleanBytes(seal.rendererAbsentBeforeDestroy),
        "activeTransportCalls" to intBytes(seal.activeTransportCalls),
        "completedAtElapsedNanos" to longBytes(seal.completedAtElapsedNanos),
    )

    fun cookieGrantDigest(grants: List<NtkAckCookie>): String {
        val sorted = grants.sortedWith(
            compareBy<NtkAckCookie>(
                { it.responseUrl }, { it.name }, { it.domain }, { it.path }, { it.value },
            ),
        )
        val fields = ArrayList<Pair<String, ByteArray>>(sorted.size * 8)
        sorted.forEachIndexed { index, cookie ->
            val prefix = "cookie[$index]."
            fields += prefix + "name" to utf8(cookie.name)
            fields += prefix + "value" to utf8(cookie.value)
            fields += prefix + "responseUrl" to utf8(cookie.responseUrl)
            fields += prefix + "domain" to utf8(cookie.domain)
            fields += prefix + "path" to utf8(cookie.path)
            fields += prefix + "secure" to booleanBytes(cookie.secure)
            fields += prefix + "expiresAt" to longBytes(cookie.expiresAtEpochMs)
            fields += prefix + "setCookieDigest" to utf8(cookie.setCookieDigestSha256)
        }
        return sha256Hex(canonical("ntk-ack-cookie-grant-v1", *fields.toTypedArray()))
    }

    fun viewportDigest(viewport: NtkAckViewport): String = sha256Hex(
        canonical(
            "ntk-ack-viewport-v1",
            "widthPx" to intBytes(viewport.widthPx),
            "heightPx" to intBytes(viewport.heightPx),
            "densityDpi" to intBytes(viewport.densityDpi),
            "insetLeftPx" to intBytes(viewport.insetLeftPx),
            "insetTopPx" to intBytes(viewport.insetTopPx),
            "insetRightPx" to intBytes(viewport.insetRightPx),
            "insetBottomPx" to intBytes(viewport.insetBottomPx),
        ),
    )

    fun observationSetDigest(observationDigests: Set<String>): String {
        require(observationDigests.isNotEmpty())
        require(observationDigests.all(String::isSha256))
        val fields = observationDigests.sorted().mapIndexed { index, digest ->
            "observation[$index]" to utf8(digest)
        }
        return sha256Hex(canonical("ntk-ack-observation-set-v1", *fields.toTypedArray()))
    }

    fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }

    fun sha256Utf8(value: String): String = sha256Hex(utf8(value))

    fun decodePublicKey(x509: ByteArray): PublicKey = java.security.KeyFactory
        .getInstance("EC")
        .generatePublic(java.security.spec.X509EncodedKeySpec(x509))

    private fun canonical(domain: String, vararg fields: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            writeField(data, "domain", utf8(domain))
            fields.forEach { (name, value) -> writeField(data, name, value) }
        }
        return output.toByteArray()
    }

    private fun writeField(output: DataOutputStream, name: String, value: ByteArray) {
        val nameBytes = utf8(name)
        output.writeInt(nameBytes.size)
        output.write(nameBytes)
        output.writeInt(value.size)
        output.write(value)
    }

    private fun sign(value: ByteArray, privateKey: PrivateKey): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(value)
            sign()
        }

    private fun verify(value: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(value)
                verify(signature)
            }
        }.getOrDefault(false)

    private fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
    private fun intBytes(value: Int): ByteArray = ByteArrayOutputStream(4).also {
        DataOutputStream(it).use { data -> data.writeInt(value) }
    }.toByteArray()
    private fun longBytes(value: Long): ByteArray = ByteArrayOutputStream(8).also {
        DataOutputStream(it).use { data -> data.writeLong(value) }
    }.toByteArray()
    private fun booleanBytes(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
