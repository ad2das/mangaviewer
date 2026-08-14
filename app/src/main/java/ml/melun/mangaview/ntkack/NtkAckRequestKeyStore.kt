package ml.melun.mangaview.ntkack

import android.os.SystemClock
import java.nio.charset.StandardCharsets
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import ml.melun.mangaview.util.NtkBase64

/** Service-process-only server request key and one-shot exact-sign capability. */
class NtkAckRequestKeyStore(
    private val keyPair: KeyPair = generateKeyPair(),
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private data class Capability(
        val proofId: String,
        val identity: NtkAckFlightIdentity,
        val endpoint: String,
        var quiescenceDigestSha256: String = "",
        var consumed: Boolean = false,
    )

    @Volatile
    var requestKeyId: String = ""
        private set
    @Volatile
    var expiresAtEpochMs: Long = 0L
        private set
    @Volatile
    var serverTimeOffsetMs: Long = 0L
        private set
    private var activeIdentity: NtkAckFlightIdentity? = null
    private var capability: Capability? = null
    private var ackSignedFlightId: String = ""

    fun publicKey(): java.security.PublicKey = keyPair.public

    fun publicJwk(): Map<String, Any> {
        val key = keyPair.public as ECPublicKey
        return linkedMapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "ext" to true,
            "key_ops" to listOf("verify"),
            "x" to NtkBase64.encodeUrlWithoutPadding(unsignedFixed32(key.w.affineX)),
            "y" to NtkBase64.encodeUrlWithoutPadding(unsignedFixed32(key.w.affineY)),
        )
    }

    @Synchronized
    fun bindRegisteredKey(
        identity: NtkAckFlightIdentity,
        keyId: String,
        serverTimeOffsetMs: Long,
        expiresAtEpochMs: Long,
    ) {
        check(activeIdentity == identity) { "Stale ACK flight cannot bind a request key" }
        require(keyId.isNotBlank())
        requestKeyId = keyId
        this.serverTimeOffsetMs = serverTimeOffsetMs
        this.expiresAtEpochMs = expiresAtEpochMs
    }

    @Synchronized
    fun isRegistered(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        requestKeyId.isNotBlank() && (expiresAtEpochMs <= 0L || expiresAtEpochMs - (nowEpochMs + serverTimeOffsetMs) > 30_000L)

    @Synchronized
    fun beginFlight(identity: NtkAckFlightIdentity) {
        require(identity.protocolVersion == NtkAckProtocol.VERSION)
        activeIdentity = identity
        capability = null
        ackSignedFlightId = ""
    }

    /** Invalidates only the matching owner while preserving a reusable registered public key. */
    @Synchronized
    fun invalidateFlight(identity: NtkAckFlightIdentity) {
        if (activeIdentity != identity) return
        activeIdentity = null
        capability = null
        ackSignedFlightId = ""
    }

    @Synchronized
    fun signAckRequest(identity: NtkAckFlightIdentity, bodyBytes: ByteArray): NtkAckServerSignature {
        check(activeIdentity == identity) { "Stale ACK flight cannot sign an ACK request" }
        require(isRegistered()) { "Request key is not registered" }
        require(identity.protocolVersion == NtkAckProtocol.VERSION)
        require(bodyBytes.isNotEmpty())
        check(ackSignedFlightId.isEmpty()) { "ACK request was already signed" }
        val signature = signServerRequest("POST", "/api/ad/ack", identity.episodePath, bodyBytes)
        ackSignedFlightId = identity.flightId
        return signature
    }

    @Synchronized
    fun authorizeExactCapability(
        proof: NtkAckProof,
    ) {
        val proofIdentity = proof.identity()
        check(activeIdentity == proofIdentity) { "Stale ACK proof cannot authorize exact signing" }
        require(proof.requestKeyId == requestKeyId && requestKeyId.isNotBlank())
        require(proof.proofId.isSha256())
        when (proof.proofMode) {
            NtkAckProtocol.PROOF_MODE_FULL_CHALLENGE ->
                require(proof.ackStatus == 200 && proof.ackOutcome in setOf("ok", "acked"))
            NtkAckProtocol.PROOF_MODE_TRUSTED_SERVER_GRANT -> require(
                proof.ackStatus == 200 && proof.ackOutcome == "trusted-grant" &&
                    proof.trustedChallengeHeaderDigestSha256.isSha256() &&
                    proof.trustedAckHeaderDigestSha256.isSha256() &&
                    proof.cookieGrants.any { it.name == "ad_ack" },
            ) { "Trusted server grant cannot authorize exact signing" }
            else -> throw IllegalArgumentException("Unknown ACK proof mode")
        }
        check(capability == null) { "Exact-sign capability already exists" }
        capability = Capability(
            proof.proofId,
            proofIdentity,
            expectedImageEndpoint(proof.episodePath),
        )
    }

    @Synchronized
    fun markQuiesced(seal: NtkAckQuiescenceSeal) {
        val current = checkNotNull(capability) { "No exact-sign capability" }
        check(activeIdentity == current.identity) { "ACK flight no longer owns exact signing" }
        require(seal.flightId == current.identity.flightId)
        require(seal.generation == current.identity.generation)
        require(seal.ackProofDigestSha256 == current.proofId)
        require(seal.webViewDestroyed && seal.activeTransportCalls == 0)
        require(seal.rendererGoneObserved || seal.rendererAbsentBeforeDestroy)
        current.quiescenceDigestSha256 = seal.envelopeDigestSha256
    }

    @Synchronized
    fun signExact(request: NtkAckSignRequest): NtkAckSignature {
        val current = checkNotNull(capability) { "No exact-sign capability" }
        check(activeIdentity == current.identity) { "ACK flight no longer owns exact signing" }
        check(!current.consumed) { "Exact-sign capability was already consumed" }
        check(current.quiescenceDigestSha256.isSha256()) { "ACK browser is not quiesced" }
        require(request.protocolVersion == NtkAckProtocol.VERSION)
        require(request.proofId == current.proofId)
        require(request.flightId == current.identity.flightId)
        require(request.generation == current.identity.generation)
        require(request.authEpoch == current.identity.authEpoch)
        require(request.origin == current.identity.origin)
        require(request.episodePath == current.identity.episodePath)
        require(request.method == "POST")
        require(request.endpoint == current.endpoint)
        require(request.requestIdentityDigestSha256.isSha256())
        require(request.imagesTokenDigestSha256.isSha256())
        require(request.bodyBytes.isNotEmpty())
        val signed = signServerRequest(request.method, request.endpoint, request.episodePath, request.bodyBytes)
        val bodyDigest = signed.bodyDigestSha256
        current.consumed = true
        return NtkAckSignature(
            NtkAckProtocol.VERSION,
            current.proofId,
            current.identity.flightId,
            current.identity.generation,
            current.identity.authEpoch,
            current.identity.origin,
            current.identity.episodePath,
            request.method,
            current.endpoint,
            request.requestIdentityDigestSha256,
            bodyDigest,
            requestKeyId,
            signed.timestamp,
            signed.nonce,
            "p1363",
            signed.signatureValue,
            current.quiescenceDigestSha256,
            elapsedRealtimeNanos(),
        )
    }

    @Synchronized
    fun clear() {
        requestKeyId = ""
        serverTimeOffsetMs = 0L
        expiresAtEpochMs = 0L
        activeIdentity = null
        capability = null
        ackSignedFlightId = ""
    }

    companion object {
        private val P256_ORDER = BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
            16,
        )
        private val P256_HALF_ORDER: BigInteger = P256_ORDER.shiftRight(1)

        fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    private fun expectedImageEndpoint(path: String): String = when {
        path.startsWith("/manhwa/") -> "/api/manhwa-images"
        path.startsWith("/webtoon/") -> "/api/webtoon-images"
        else -> throw IllegalArgumentException("Unsupported strict episode path")
    }

    private fun NtkAckProof.identity() = NtkAckFlightIdentity(
        protocolVersion,
        flightId,
        generation,
        authEpoch,
        origin,
        episodePath,
    )

    private fun signServerRequest(
        method: String,
        endpoint: String,
        scope: String,
        bodyBytes: ByteArray,
    ): NtkAckServerSignature {
        val timestamp = (System.currentTimeMillis() + serverTimeOffsetMs).toString()
        val nonceBytes = ByteArray(24).also(SecureRandom()::nextBytes)
        val nonce = NtkBase64.encodeUrlWithoutPadding(nonceBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
        val bodyHashBase64Url = NtkBase64.encodeUrlWithoutPadding(digest)
        val canonical = "ntk-brsig-v1\n$method\n$endpoint\n$scope\n" +
            "$requestKeyId\n$timestamp\n$nonce\n$bodyHashBase64Url"
        val signature = signP1363(
            keyPair.private as ECPrivateKey,
            canonical.toByteArray(StandardCharsets.UTF_8),
        )
        return NtkAckServerSignature(
            requestKeyId,
            timestamp,
            nonce,
            NtkBase64.encodeUrlWithoutPadding(signature),
            NtkAckProofCodec.sha256Hex(bodyBytes),
        )
    }

    private fun signP1363(privateKey: ECPrivateKey, value: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(value)
            sign()
        }
        var offset = 0
        require(der[offset++].toInt() == 0x30)
        val sequenceLength = der[offset++].toInt() and 0xff
        if (sequenceLength and 0x80 != 0) offset += sequenceLength and 0x7f
        require(der[offset++].toInt() == 0x02)
        val rLength = der[offset++].toInt() and 0xff
        val r = der.copyOfRange(offset, offset + rLength)
        offset += rLength
        require(der[offset++].toInt() == 0x02)
        val sLength = der[offset++].toInt() and 0xff
        var s = der.copyOfRange(offset, offset + sLength)
        val sValue = BigInteger(1, s)
        if (sValue > P256_HALF_ORDER) s = unsignedFixed32(P256_ORDER - sValue)
        return ByteArray(64).also { output ->
            copyUnsignedFixed(r, output, 0)
            copyUnsignedFixed(s, output, 32)
        }
    }

    private fun unsignedFixed32(value: BigInteger): ByteArray = ByteArray(32).also {
        copyUnsignedFixed(value.toByteArray(), it, 0)
    }

    private fun copyUnsignedFixed(raw: ByteArray, output: ByteArray, outputOffset: Int) {
        var start = 0
        while (start < raw.size - 1 && raw[start].toInt() == 0) start++
        val length = raw.size - start
        val copied = minOf(32, length)
        System.arraycopy(raw, start + length - copied, output, outputOffset + 32 - copied, copied)
    }

}

data class NtkAckServerSignature(
    val keyId: String,
    val timestamp: String,
    val nonce: String,
    val signatureValue: String,
    val bodyDigestSha256: String,
) {
    fun headers(): Map<String, String> = linkedMapOf(
        "x-ntk-key-id" to keyId,
        "x-ntk-ts" to timestamp,
        "x-ntk-nonce" to nonce,
        "x-ntk-sig" to signatureValue,
    )
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
