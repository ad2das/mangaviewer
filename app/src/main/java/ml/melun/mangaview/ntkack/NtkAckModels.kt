package ml.melun.mangaview.ntkack

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

object NtkAckProtocol {
    const val VERSION = 3
    const val PROOF_DOMAIN = "ntk-ack-proof-v3"
    const val QUIESCENCE_DOMAIN = "ntk-ack-quiescence-v1"
    const val EXACT_SIGNATURE_DOMAIN = "ntk-ack-exact-sign-v1"
    const val PROCESS_SUFFIX = ":ntk_ack"
    const val DATA_DIRECTORY_SUFFIX = "main_process_default_v1"

    const val PROOF_MODE_FULL_CHALLENGE = "full-challenge-ack"
    const val PROOF_MODE_TRUSTED_SERVER_GRANT = "trusted-server-grant"

    const val FAILURE_INVALID_CALLER = 1
    const val FAILURE_PROTOCOL_MISMATCH = 2
    const val FAILURE_INVALID_IDENTITY = 3
    const val FAILURE_SUPERSEDED = 4
    const val FAILURE_CANCELLED = 5
    const val FAILURE_INDETERMINATE_ACK = 6
    const val FAILURE_BINDER_DIED = 7
    const val FAILURE_DEADLINE = 8
    const val FAILURE_PROOF_REJECTED = 9
    const val FAILURE_QUIESCENCE = 10
    const val FAILURE_SIGN_CAPABILITY = 11
    const val FAILURE_INTERNAL = 12
}

@Parcelize
data class NtkAckViewport(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val insetLeftPx: Int = 0,
    val insetTopPx: Int = 0,
    val insetRightPx: Int = 0,
    val insetBottomPx: Int = 0,
) : Parcelable

@Parcelize
data class NtkAckCookie(
    val name: String,
    val value: String,
    val responseUrl: String = "",
    val domain: String = "",
    val path: String = "/",
    val secure: Boolean = true,
    val expiresAtEpochMs: Long = 0L,
    val setCookieDigestSha256: String = "",
) : Parcelable

@Parcelize
data class NtkAckWarmRequest(
    val protocolVersion: Int,
    val authEpoch: Long,
    val userAgent: String,
    val viewport: NtkAckViewport,
    val clientPid: Int,
) : Parcelable

@Parcelize
data class NtkAckRequest(
    val protocolVersion: Int,
    val flightId: String,
    val generation: Long,
    val authEpoch: Long,
    val requestNonce: ByteArray,
    val origin: String,
    val episodePath: String,
    val userAgent: String,
    val uaMetadata: String,
    val viewport: NtkAckViewport,
    val seedCookies: List<NtkAckCookie>,
    val deadlineElapsedRealtimeNanos: Long,
    val clientPid: Int,
) : Parcelable {
    val singleFlightKey: String
        get() = "$origin|$episodePath|$generation|$authEpoch"
}

@Parcelize
data class NtkAckFlightIdentity(
    val protocolVersion: Int,
    val flightId: String,
    val generation: Long,
    val authEpoch: Long,
    val origin: String,
    val episodePath: String,
) : Parcelable {
    val singleFlightKey: String
        get() = "$origin|$episodePath|$generation|$authEpoch"
}

@Parcelize
data class NtkAckClearRequest(
    val protocolVersion: Int,
    val authEpoch: Long,
    val reason: String,
    val clearCloudflareClearance: Boolean,
    val identity: NtkAckFlightIdentity? = null,
) : Parcelable

@Parcelize
data class NtkAckSignRequest(
    val protocolVersion: Int,
    val proofId: String,
    val flightId: String,
    val generation: Long,
    val authEpoch: Long,
    val origin: String,
    val episodePath: String,
    val method: String,
    val endpoint: String,
    val requestIdentityDigestSha256: String,
    val imagesTokenDigestSha256: String,
    val bodyBytes: ByteArray,
    val requestHeaders: List<NtkAckHeader> = emptyList(),
) : Parcelable

@Parcelize
data class NtkAckServiceHello(
    val protocolVersion: Int,
    val serviceInstanceId: String,
    val servicePid: Int,
    val proofPublicKeyX509: ByteArray,
    val dataDirectorySuffix: String,
    val webViewCreatedPid: Int,
    val warmReadyElapsedNanos: Long,
) : Parcelable

@Parcelize
data class NtkAckProof(
    val protocolVersion: Int,
    val proofId: String,
    val serviceInstanceId: String,
    val flightId: String,
    val generation: Long,
    val authEpoch: Long,
    val requestNonce: ByteArray,
    val packageName: String,
    val appSigningCertificateDigestSha256: String,
    val origin: String,
    val episodePath: String,
    val userAgentDigestSha256: String,
    val viewportDigestSha256: String,
    val proofMode: String,
    val challengeRequestDigestSha256: String,
    val challengeResponseDigestSha256: String,
    val challengeStatus: Int,
    val trustedScopeDigestSha256: String,
    val trustedObservedAtEpochMs: Long,
    val trustedExpiresAtEpochMs: Long,
    val trustedIntervalMs: Long,
    val trustedSuccessCount: Int,
    val trustedSubjectKind: String,
    val trustedChallengeHeaderDigestSha256: String,
    val trustedChallengeCookiePayloadDigestSha256: String,
    val trustedAckHeaderDigestSha256: String,
    val trustedAckCookiePayloadDigestSha256: String,
    val challengeTokenDigestSha256: String,
    val guardVersion: String,
    val guardJsDigestSha256: String,
    val guardWasmDigestSha256: String,
    val guardTpDigestSha256: String,
    val observationSetDigestSha256: String,
    val requiredObservationCount: Int,
    val observed2xxCount: Int,
    val canaryRequestDigestSha256: String,
    val canaryResponseDigestSha256: String,
    val canaryStatus: Int,
    val requestKeyId: String,
    val ackRequestBodyDigestSha256: String,
    val ackResponseBodyDigestSha256: String,
    val ackStatus: Int,
    val ackOutcome: String,
    val cookieGrantDigestSha256: String,
    val cookieGrants: List<NtkAckCookie>,
    val startedAtElapsedNanos: Long,
    val completedAtElapsedNanos: Long,
    val canonicalEnvelope: ByteArray,
    val envelopeDigestSha256: String,
    val signature: ByteArray,
) : Parcelable

@Parcelize
data class NtkAckQuiescenceSeal(
    val protocolVersion: Int,
    val serviceInstanceId: String,
    val flightId: String,
    val generation: Long,
    val authEpoch: Long,
    val origin: String,
    val episodePath: String,
    val ackProofDigestSha256: String,
    val servicePid: Int,
    val rendererPid: Int,
    val webViewDestroyed: Boolean,
    val rendererGoneObserved: Boolean,
    val rendererAbsentBeforeDestroy: Boolean,
    val activeTransportCalls: Int,
    val completedAtElapsedNanos: Long,
    val canonicalEnvelope: ByteArray,
    val envelopeDigestSha256: String,
    val signature: ByteArray,
) : Parcelable

@Parcelize
data class NtkAckSignature(
    val protocolVersion: Int,
    val proofId: String,
    val flightId: String,
    val generation: Long,
    val authEpoch: Long,
    val origin: String,
    val episodePath: String,
    val method: String,
    val endpoint: String,
    val requestIdentityDigestSha256: String,
    val bodyDigestSha256: String,
    val requestKeyId: String,
    val timestamp: String,
    val nonce: String,
    val signatureFormat: String,
    val signatureValue: String,
    val quiescenceDigestSha256: String,
    val signedAtElapsedNanos: Long,
) : Parcelable

@Parcelize
data class NtkAckHeader(
    val name: String,
    val values: List<String>,
) : Parcelable

/**
 * One response from the exact image-list request executed on the ACK flight's existing H2 pool.
 * The embedded signature binds the immutable request; the main process verifies it before it is
 * allowed to parse this response as viewer authority.
 */
@Parcelize
data class NtkAckExactExchange(
    val protocolVersion: Int,
    val signature: NtkAckSignature,
    val requestUrl: String,
    val finalUrl: String,
    val status: Int,
    val bodyBytes: ByteArray,
    val responseHeaders: List<NtkAckHeader>,
    val completedAtElapsedNanos: Long,
) : Parcelable

@Parcelize
data class NtkAckFailure(
    val protocolVersion: Int,
    val flightId: String,
    val generation: Long,
    val reasonCode: Int,
    val stage: String,
    val terminal: Boolean,
    val message: String,
    val failedAtElapsedNanos: Long,
) : Parcelable
