package ml.melun.mangaview.reader

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Shared immutable contracts for the strict native rolling-strip path. */
object NtkStripDigests {
    private const val SHA_256_HEX_LENGTH = 64
    private val LOWER_HEX = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun normalizeEpisodePath(value: String): String {
        var normalized = value.trim().replace('\\', '/')
        if (normalized.isEmpty()) return ""
        normalized = try {
            val uri = URI(normalized)
            if (uri.isAbsolute) uri.rawPath.orEmpty() else normalized
        } catch (_: Exception) {
            normalized
        }
        normalized = normalized.substringBefore('#').substringBefore('?')
        normalized = normalized.replace(Regex("/{2,}"), "/")
        if (!normalized.startsWith('/')) normalized = "/$normalized"
        while (normalized.length > 1 && normalized.endsWith('/')) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }

    @JvmStatic
    fun canonicalAsset(value: String): String = value.trim()

    /** Stable identity used by strict source ownership without retaining a source URL. */
    @JvmStatic
    fun canonicalAssetDigestSha256(value: String): String {
        val canonical = canonicalAsset(value)
        require(canonical.isNotBlank())
        return sha256Tokens("ntk-canonical-asset-v1", canonical)
    }

    /** Length-prefixing makes the ordered digest unambiguous without changing its inputs. */
    @JvmStatic
    fun sha256Tokens(vararg tokens: String): String = sha256Tokens(tokens.asIterable())

    @JvmStatic
    fun sha256Tokens(tokens: Iterable<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (token in tokens) {
            val bytes = token.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return bytesToLowerHex(digest.digest())
    }

    @JvmStatic
    fun isSha256(value: String): Boolean =
        value.length == SHA_256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    @JvmStatic
    fun sha256Bytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .let(::bytesToLowerHex)

    @JvmStatic
    fun bytesToLowerHex(value: ByteArray): String {
        val output = CharArray(value.size * 2)
        for (index in value.indices) {
            val byte = value[index].toInt() and 0xff
            output[index * 2] = LOWER_HEX[byte ushr 4]
            output[index * 2 + 1] = LOWER_HEX[byte and 0x0f]
        }
        return String(output)
    }

    /**
     * Digest creation is on the cold viewer critical path once per page and request identity.
     * String.format allocates a Formatter and performs locale work for every single byte; a
     * 114-page episode consequently spent hundreds of milliseconds formatting SHA-256 values.
     * This lookup emits the identical lowercase representation with one fixed-size allocation.
     */
}

/** A seal contains only the immutable ordered manifest. Authority lives in a typed proof. */
data class NtkEpisodeManifestSeal(
    val episodePath: String,
    val revision: Long,
    val canonicalAssets: List<String>,
    val pageCount: Int,
    val digestSha256: String
) {
    val normalizedEpisodePath: String = NtkStripDigests.normalizeEpisodePath(episodePath)
    val normalizedCanonicalAssets: List<String> = canonicalAssets.map(NtkStripDigests::canonicalAsset)

    init {
        require(normalizedEpisodePath.isNotBlank())
        require(revision >= 0L)
        require(pageCount >= 0)
        require(normalizedCanonicalAssets.none { it.isBlank() })
        require(digestSha256 == digestSha256.lowercase())
        require(NtkStripDigests.isSha256(digestSha256))
    }

    val computedDigestSha256: String
        get() = computeDigestSha256(normalizedEpisodePath, pageCount, normalizedCanonicalAssets)

    val isStructurallyComplete: Boolean
        get() = pageCount > 0 &&
            normalizedCanonicalAssets.size == pageCount &&
            digestSha256 == computedDigestSha256

    /** The ruling treats an identical digest as a no-op even if a source repeats a revision. */
    fun hasSameAuthority(other: NtkEpisodeManifestSeal): Boolean =
        normalizedEpisodePath == other.normalizedEpisodePath &&
            digestSha256 == other.digestSha256

    companion object {
        @JvmStatic
        fun create(
            episodePath: String,
            revision: Long,
            canonicalAssets: List<String>,
            pageCount: Int = canonicalAssets.size
        ): NtkEpisodeManifestSeal {
            val normalizedPath = NtkStripDigests.normalizeEpisodePath(episodePath)
            val assets = canonicalAssets.map(NtkStripDigests::canonicalAsset).toList()
            return NtkEpisodeManifestSeal(
                episodePath = normalizedPath,
                revision = revision,
                canonicalAssets = assets,
                pageCount = pageCount,
                digestSha256 = computeDigestSha256(normalizedPath, pageCount, assets)
            )
        }

        @JvmStatic
        fun computeDigestSha256(
            episodePath: String,
            pageCount: Int,
            canonicalAssets: List<String>
        ): String = NtkStripDigests.sha256Tokens(
            buildList {
                add("ntk-episode-manifest-v1")
                add(NtkStripDigests.normalizeEpisodePath(episodePath))
                add(pageCount.toString())
                canonicalAssets.forEach { add(NtkStripDigests.canonicalAsset(it)) }
            }
        )
    }
}

@JvmInline
value class NtkDiscoveryGeneration(val value: Long) {
    init {
        require(value > 0L)
    }
}

enum class NtkSourceState {
    ABSENT,
    DISCOVERING,
    RESERVED,
    OWNED_PRECLAIM,
    OWNED_BINDING,
    OWNED_STAGED,
    OWNED_ACTIVE,
    TERMINAL_CLOSING,
    TERMINAL_CLOSED
}

enum class NtkPlanState {
    NONE,
    PLAN_RESERVED,
    PROMOTED,
    TERMINAL
}

data class NtkViewerImageRequestIdentity(
    val segment: String,
    val endpointPath: String,
    val sourceWorkId: String,
    val episodeId: String,
    val imagesTokenSha256: String,
    val identityDigestSha256: String
) {
    val normalizedSegment: String = segment.trim().lowercase(Locale.ROOT)
    val normalizedEndpointPath: String = endpointPath.trim()
    val normalizedSourceWorkId: String = sourceWorkId.trim()
    val normalizedEpisodeId: String = episodeId.trim()

    init {
        require(normalizedSegment == "manhwa" || normalizedSegment == "webtoon")
        require(normalizedEndpointPath.startsWith('/'))
        require(normalizedSourceWorkId.isNotBlank())
        require(normalizedEpisodeId.isNotBlank())
        require(NtkStripDigests.isSha256(imagesTokenSha256))
        require(NtkStripDigests.isSha256(identityDigestSha256))
        require(identityDigestSha256 == computedIdentityDigestSha256)
    }

    val computedIdentityDigestSha256: String
        get() = computeDigestSha256(
            normalizedSegment,
            normalizedEndpointPath,
            normalizedSourceWorkId,
            normalizedEpisodeId,
            imagesTokenSha256
        )

    companion object {
        @JvmStatic
        fun create(
            segment: String,
            endpointPath: String,
            sourceWorkId: String,
            episodeId: String,
            imagesToken: String
        ): NtkViewerImageRequestIdentity {
            val normalizedSegment = segment.trim().lowercase(Locale.ROOT)
            val normalizedEndpoint = endpointPath.trim()
            val work = sourceWorkId.trim()
            val episode = episodeId.trim()
            val tokenDigest = NtkStripDigests.sha256Bytes(imagesToken.trim().toByteArray(Charsets.UTF_8))
            return NtkViewerImageRequestIdentity(
                segment = normalizedSegment,
                endpointPath = normalizedEndpoint,
                sourceWorkId = work,
                episodeId = episode,
                imagesTokenSha256 = tokenDigest,
                identityDigestSha256 = computeDigestSha256(
                    normalizedSegment,
                    normalizedEndpoint,
                    work,
                    episode,
                    tokenDigest
                )
            )
        }

        @JvmStatic
        fun computeDigestSha256(
            segment: String,
            endpointPath: String,
            sourceWorkId: String,
            episodeId: String,
            imagesTokenSha256: String
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-viewer-image-request-identity-v1",
            segment.trim().lowercase(Locale.ROOT),
            endpointPath.trim(),
            sourceWorkId.trim(),
            episodeId.trim(),
            imagesTokenSha256
        )
    }
}

data class NtkEpisodeDocumentPlanProof(
    val normalizedEpisodePath: String,
    val discoveryGeneration: Long,
    val canonicalRequestUrl: String,
    val canonicalFinalUrl: String,
    val httpStatus: Int,
    val selectedHeadersDigestSha256: String,
    val responseBodySha256: String,
    val responseBodyLength: Long,
    val responseConsumedToEof: Boolean,
    val responseIdentityDigestSha256: String,
    val parserSchema: String,
    val componentPayloadCount: Int,
    val componentPayloadDigestSha256: String,
    val pageCount: Int,
    val orderedPageNumbersDigestSha256: String,
    val orderedAssetsDigestSha256: String,
    val sourceRequestPolicyVersion: String,
    val requestIdentity: NtkViewerImageRequestIdentity,
    val proofDigestSha256: String
) {
    init {
        require(NtkStripDigests.normalizeEpisodePath(normalizedEpisodePath) == normalizedEpisodePath)
        require(discoveryGeneration > 0L)
        require(
            httpStatus == 200 &&
                responseConsumedToEof &&
                responseBodyLength > 0L &&
                parserSchema == PARSER_SCHEMA &&
                componentPayloadCount == 1 &&
                pageCount in 1..1_000 &&
                isTrustedManifestUrl(canonicalRequestUrl) &&
                isTrustedManifestUrl(canonicalFinalUrl) &&
                NtkStripDigests.isSha256(selectedHeadersDigestSha256) &&
                NtkStripDigests.isSha256(responseBodySha256) &&
                NtkStripDigests.isSha256(componentPayloadDigestSha256) &&
                NtkStripDigests.isSha256(orderedPageNumbersDigestSha256) &&
                NtkStripDigests.isSha256(orderedAssetsDigestSha256) &&
                sourceRequestPolicyVersion == SOURCE_REQUEST_POLICY_VERSION &&
                NtkStripDigests.isSha256(proofDigestSha256)
        ) { "Invalid document plan proof" }
        require(responseIdentityDigestSha256 == computeResponseIdentityDigest(
            canonicalRequestUrl,
            canonicalFinalUrl,
            selectedHeadersDigestSha256,
            responseBodyLength,
            responseBodySha256
        ))
        require(proofDigestSha256 == computeProofDigest(
            normalizedEpisodePath,
            discoveryGeneration,
            responseIdentityDigestSha256,
            componentPayloadDigestSha256,
            pageCount,
            orderedPageNumbersDigestSha256,
            orderedAssetsDigestSha256,
            sourceRequestPolicyVersion,
            requestIdentity.identityDigestSha256
        ))
    }

    companion object {
        const val PARSER_SCHEMA = "ntk-document-plan-v2"

        @JvmStatic
        fun create(
            episodePath: String,
            discoveryGeneration: Long,
            canonicalRequestUrl: String,
            canonicalFinalUrl: String,
            selectedHeadersDigestSha256: String,
            responseBody: ByteArray,
            componentPayload: ByteArray,
            orderedPages: List<Int>,
            orderedCanonicalAssets: List<String>,
            requestIdentity: NtkViewerImageRequestIdentity
        ): NtkEpisodeDocumentPlanProof {
            val normalizedPath = NtkStripDigests.normalizeEpisodePath(episodePath)
            require(discoveryGeneration > 0L)
            require(
                orderedPages.size in 1..1_000 &&
                    orderedPages.all { it in 1..1_000 } &&
                    orderedPages.zipWithNext().all { (first, second) -> first < second }
            )
            val assets = orderedCanonicalAssets.map(NtkStripDigests::canonicalAsset)
            require(assets.size == orderedPages.size && assets.none(String::isBlank))
            require(assets.toSet().size == assets.size)
            val requestUrl = canonicalRequestUrl.trim()
            val finalUrl = canonicalFinalUrl.trim()
            val bodyDigest = NtkStripDigests.sha256Bytes(responseBody)
            val payloadDigest = NtkStripDigests.sha256Bytes(componentPayload)
            val pagesDigest = NtkStripDigests.sha256Tokens(
                listOf("ntk-ordered-page-numbers-v1") + orderedPages.map(Int::toString)
            )
            val assetsDigest = NtkEpisodeManifestSeal.computeDigestSha256(
                normalizedPath,
                assets.size,
                assets
            )
            val responseIdentity = computeResponseIdentityDigest(
                requestUrl,
                finalUrl,
                selectedHeadersDigestSha256,
                responseBody.size.toLong(),
                bodyDigest
            )
            val proofDigest = computeProofDigest(
                normalizedPath,
                discoveryGeneration,
                responseIdentity,
                payloadDigest,
                orderedPages.size,
                pagesDigest,
                assetsDigest,
                SOURCE_REQUEST_POLICY_VERSION,
                requestIdentity.identityDigestSha256
            )
            return NtkEpisodeDocumentPlanProof(
                normalizedEpisodePath = normalizedPath,
                discoveryGeneration = discoveryGeneration,
                canonicalRequestUrl = requestUrl,
                canonicalFinalUrl = finalUrl,
                httpStatus = 200,
                selectedHeadersDigestSha256 = selectedHeadersDigestSha256,
                responseBodySha256 = bodyDigest,
                responseBodyLength = responseBody.size.toLong(),
                responseConsumedToEof = true,
                responseIdentityDigestSha256 = responseIdentity,
                parserSchema = PARSER_SCHEMA,
                componentPayloadCount = 1,
                componentPayloadDigestSha256 = payloadDigest,
                pageCount = orderedPages.size,
                orderedPageNumbersDigestSha256 = pagesDigest,
                orderedAssetsDigestSha256 = assetsDigest,
                sourceRequestPolicyVersion = SOURCE_REQUEST_POLICY_VERSION,
                requestIdentity = requestIdentity,
                proofDigestSha256 = proofDigest
            )
        }

        private fun computeResponseIdentityDigest(
            requestUrl: String,
            finalUrl: String,
            selectedHeadersDigestSha256: String,
            responseBodyLength: Long,
            responseBodySha256: String
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-document-response-v1",
            "GET",
            requestUrl,
            finalUrl,
            "200",
            selectedHeadersDigestSha256,
            responseBodyLength.toString(),
            responseBodySha256
        )

        private fun computeProofDigest(
            normalizedEpisodePath: String,
            discoveryGeneration: Long,
            responseIdentityDigestSha256: String,
            componentPayloadDigestSha256: String,
            pageCount: Int,
            orderedPageNumbersDigestSha256: String,
            orderedAssetsDigestSha256: String,
            sourceRequestPolicyVersion: String,
            requestIdentityDigestSha256: String
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-document-plan-proof-v2",
            PARSER_SCHEMA,
            normalizedEpisodePath,
            discoveryGeneration.toString(),
            responseIdentityDigestSha256,
            componentPayloadDigestSha256,
            pageCount.toString(),
            orderedPageNumbersDigestSha256,
            orderedAssetsDigestSha256,
            sourceRequestPolicyVersion,
            requestIdentityDigestSha256
        )

        const val SOURCE_REQUEST_POLICY_VERSION = "ntk-quarantine-source-request-v2"
    }
}

data class NtkEpisodeDocumentPlanDraft(
    val normalizedEpisodePath: String,
    val discoveryGeneration: Long,
    val canonicalRequestUrl: String,
    val canonicalFinalUrl: String,
    val selectedHeadersDigestSha256: String,
    val responseBody: ByteArray,
    val componentPayload: ByteArray,
    val orderedPages: List<Int>,
    val requestIdentity: NtkViewerImageRequestIdentity,
    val imagesToken: String
) {
    init {
        require(NtkStripDigests.normalizeEpisodePath(normalizedEpisodePath) == normalizedEpisodePath)
        require(discoveryGeneration > 0L)
        require(canonicalRequestUrl.isNotBlank() && canonicalFinalUrl.isNotBlank())
        require(NtkStripDigests.isSha256(selectedHeadersDigestSha256))
        require(responseBody.isNotEmpty() && componentPayload.isNotEmpty())
        require(orderedPages.size in 1..1_000 && orderedPages == (1..orderedPages.size).toList())
        require(imagesToken.isNotBlank())
        require(
            NtkStripDigests.sha256Bytes(imagesToken.trim().toByteArray(Charsets.UTF_8)) ==
                requestIdentity.imagesTokenSha256
        )
    }

    val pageCount: Int
        get() = orderedPages.size

    fun bind(evidence: NtkQuarantineAssetEvidence): NtkProvisionalEpisodePlan {
        require(evidence.normalizedEpisodePath == normalizedEpisodePath)
        require(evidence.discoveryGeneration == discoveryGeneration)
        require(evidence.viewerRequestIdentityDigest == requestIdentity.identityDigestSha256)
        require(evidence.orderedSourcePages.all { it in orderedPages })
        val proof = NtkEpisodeDocumentPlanProof.create(
            normalizedEpisodePath,
            discoveryGeneration,
            canonicalRequestUrl,
            canonicalFinalUrl,
            selectedHeadersDigestSha256,
            responseBody,
            componentPayload,
            evidence.orderedSourcePages,
            evidence.normalizedOrderedCanonicalAssets,
            requestIdentity
        )
        return NtkProvisionalEpisodePlan.create(
            proof,
            imagesToken,
            evidence.normalizedOrderedCanonicalAssets
        )
    }

    /**
     * Builds a private, authority-free request identity for one click-owned candidate flight.
     * The result must never be published as a viewer plan: only a later exact API manifest may
     * adopt bytes whose canonical asset is identical. This keeps the document from becoming image
     * authority while allowing the current viewport's connection/body work to overlap ACK.
     */
    internal fun bindSpeculativeCandidates(
        orderedCandidateAssets: List<String>
    ): NtkProvisionalEpisodePlan {
        require(orderedCandidateAssets.size == pageCount)
        val candidates = orderedCandidateAssets.map(NtkStripDigests::canonicalAsset)
        val proof = NtkEpisodeDocumentPlanProof.create(
            normalizedEpisodePath,
            discoveryGeneration,
            canonicalRequestUrl,
            canonicalFinalUrl,
            selectedHeadersDigestSha256,
            responseBody,
            componentPayload,
            orderedPages,
            candidates,
            requestIdentity
        )
        return NtkProvisionalEpisodePlan.create(proof, imagesToken, candidates)
    }
}

/**
 * Private request-only identity extracted from the bytes of the in-flight click-owned document.
 *
 * It cannot reserve a plan, install a manifest, or publish an image.  The complete document is
 * still consumed and parsed, and its authoritative draft must match this identity byte-for-byte
 * before the concurrently fetched API response may be used.
 */
data class NtkViewerImageRequestSeed(
    val normalizedEpisodePath: String,
    val discoveryGeneration: Long,
    val requestIdentity: NtkViewerImageRequestIdentity,
    val imagesToken: String,
) {
    init {
        require(NtkStripDigests.normalizeEpisodePath(normalizedEpisodePath) == normalizedEpisodePath)
        require(discoveryGeneration > 0L)
        require(imagesToken.isNotBlank())
        require(
            NtkStripDigests.sha256Bytes(imagesToken.trim().toByteArray(Charsets.UTF_8)) ==
                requestIdentity.imagesTokenSha256
        )
    }

    fun matches(draft: NtkEpisodeDocumentPlanDraft): Boolean =
        normalizedEpisodePath == draft.normalizedEpisodePath &&
            discoveryGeneration == draft.discoveryGeneration &&
            imagesToken == draft.imagesToken &&
            requestIdentity.identityDigestSha256 == draft.requestIdentity.identityDigestSha256
}

data class NtkProvisionalEpisodePlan(
    val proof: NtkEpisodeDocumentPlanProof,
    val imagesToken: String,
    val orderedCanonicalAssets: List<String>,
    val orderedAssetsDigestSha256: String,
    val bindingDigestSha256: String
) {
    val normalizedOrderedCanonicalAssets =
        orderedCanonicalAssets.map(NtkStripDigests::canonicalAsset)

    init {
        require(imagesToken.isNotBlank())
        require(
            NtkStripDigests.sha256Bytes(imagesToken.trim().toByteArray(Charsets.UTF_8)) ==
                proof.requestIdentity.imagesTokenSha256
        )
        require(normalizedOrderedCanonicalAssets.size == proof.pageCount)
        require(normalizedOrderedCanonicalAssets.none(String::isBlank))
        require(normalizedOrderedCanonicalAssets.toSet().size == normalizedOrderedCanonicalAssets.size)
        require(
            orderedAssetsDigestSha256 == NtkEpisodeManifestSeal.computeDigestSha256(
                proof.normalizedEpisodePath,
                normalizedOrderedCanonicalAssets.size,
                normalizedOrderedCanonicalAssets
            )
        )
        require(orderedAssetsDigestSha256 == proof.orderedAssetsDigestSha256)
        require(bindingDigestSha256 == computeBindingDigest(
            proof.proofDigestSha256,
            proof.requestIdentity.identityDigestSha256,
            orderedAssetsDigestSha256,
            proof.sourceRequestPolicyVersion
        ))
    }

    val pageCount: Int
        get() = proof.pageCount

    companion object {
        @JvmStatic
        fun create(
            proof: NtkEpisodeDocumentPlanProof,
            imagesToken: String,
            orderedCanonicalAssets: List<String>
        ): NtkProvisionalEpisodePlan {
            val assets = orderedCanonicalAssets.map(NtkStripDigests::canonicalAsset)
            val assetsDigest = NtkEpisodeManifestSeal.computeDigestSha256(
                proof.normalizedEpisodePath,
                assets.size,
                assets
            )
            return NtkProvisionalEpisodePlan(
                proof,
                imagesToken,
                assets,
                assetsDigest,
                computeBindingDigest(
                    proof.proofDigestSha256,
                    proof.requestIdentity.identityDigestSha256,
                    assetsDigest,
                    proof.sourceRequestPolicyVersion
                )
            )
        }

        @JvmStatic
        fun computeBindingDigest(
            planProofDigest: String,
            viewerRequestIdentityDigest: String,
            orderedAssetsDigest: String,
            sourceRequestPolicyVersion: String
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-quarantine-plan-binding-v1",
            planProofDigest,
            viewerRequestIdentityDigest,
            orderedAssetsDigest,
            sourceRequestPolicyVersion
        )
    }
}

enum class NtkQuarantineState {
    NONE,
    SPOOLING,
    PROMOTION_FROZEN,
    EXACT_ADOPTING,
    EXACT_ADOPTED,
    ABORTING,
    CLOSED
}

enum class NtkQuarantinePageState {
    QUEUED,
    CALL_ACTIVE,
    BODY_SEALED,
    EXACT_ADOPTING,
    EXACT_OWNED,
    FAILED,
    CLOSED
}

data class NtkQuarantineAssetEvidence(
    val episodePath: String,
    val discoveryGeneration: Long,
    val viewerRequestIdentityDigest: String,
    val orderedCanonicalAssets: List<String>,
    val orderedSourcePages: List<Int>,
    val orderedAssetsDigest: String,
    val sourceRequestPolicyVersion: String,
    val responseConsumedToEof: Boolean,
    val responseBodySha256: String,
    val evidenceDigest: String
) {
    val normalizedEpisodePath = NtkStripDigests.normalizeEpisodePath(episodePath)
    val normalizedOrderedCanonicalAssets =
        orderedCanonicalAssets.map(NtkStripDigests::canonicalAsset)

    init {
        require(normalizedEpisodePath == episodePath)
        require(discoveryGeneration > 0L)
        require(NtkStripDigests.isSha256(viewerRequestIdentityDigest))
        require(normalizedOrderedCanonicalAssets.isNotEmpty())
        require(normalizedOrderedCanonicalAssets.none(String::isBlank))
        require(normalizedOrderedCanonicalAssets.toSet().size == normalizedOrderedCanonicalAssets.size)
        require(orderedSourcePages.size == normalizedOrderedCanonicalAssets.size)
        require(
            orderedSourcePages.all { it in 1..1_000 } &&
                orderedSourcePages.zipWithNext().all { (first, second) -> first < second }
        )
        require(
            orderedAssetsDigest == NtkEpisodeManifestSeal.computeDigestSha256(
                normalizedEpisodePath,
                normalizedOrderedCanonicalAssets.size,
                normalizedOrderedCanonicalAssets
            )
        )
        require(sourceRequestPolicyVersion == NtkEpisodeDocumentPlanProof.SOURCE_REQUEST_POLICY_VERSION)
        require(responseConsumedToEof)
        require(NtkStripDigests.isSha256(responseBodySha256))
        require(evidenceDigest == computeDigest(
            normalizedEpisodePath,
            discoveryGeneration,
            viewerRequestIdentityDigest,
            orderedSourcePages,
            orderedAssetsDigest,
            sourceRequestPolicyVersion,
            responseBodySha256
        ))
    }

    companion object {
        @JvmStatic
        fun create(
            episodePath: String,
            discoveryGeneration: Long,
            viewerRequestIdentityDigest: String,
            orderedCanonicalAssets: List<String>,
            responseBody: ByteArray
        ): NtkQuarantineAssetEvidence = createWithSourcePages(
            episodePath,
            discoveryGeneration,
            viewerRequestIdentityDigest,
            orderedCanonicalAssets,
            (1..orderedCanonicalAssets.size).toList(),
            responseBody,
        )

        @JvmStatic
        fun createWithSourcePages(
            episodePath: String,
            discoveryGeneration: Long,
            viewerRequestIdentityDigest: String,
            orderedCanonicalAssets: List<String>,
            orderedSourcePages: List<Int>,
            responseBody: ByteArray
        ): NtkQuarantineAssetEvidence {
            val path = NtkStripDigests.normalizeEpisodePath(episodePath)
            val assets = orderedCanonicalAssets.map(NtkStripDigests::canonicalAsset)
            val assetsDigest = NtkEpisodeManifestSeal.computeDigestSha256(
                path,
                assets.size,
                assets
            )
            val bodyDigest = NtkStripDigests.sha256Bytes(responseBody)
            val policy = NtkEpisodeDocumentPlanProof.SOURCE_REQUEST_POLICY_VERSION
            return NtkQuarantineAssetEvidence(
                path,
                discoveryGeneration,
                viewerRequestIdentityDigest,
                assets,
                orderedSourcePages,
                assetsDigest,
                policy,
                true,
                bodyDigest,
                computeDigest(
                    path,
                    discoveryGeneration,
                    viewerRequestIdentityDigest,
                    orderedSourcePages,
                    assetsDigest,
                    policy,
                    bodyDigest
                )
            )
        }

        private fun computeDigest(
            episodePath: String,
            discoveryGeneration: Long,
            viewerRequestIdentityDigest: String,
            orderedSourcePages: List<Int>,
            orderedAssetsDigest: String,
            sourceRequestPolicyVersion: String,
            responseBodySha256: String
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-quarantine-asset-evidence-v2",
            episodePath,
            discoveryGeneration.toString(),
            viewerRequestIdentityDigest,
            NtkStripDigests.sha256Tokens(
                listOf("ntk-quarantine-source-pages-v1") +
                    orderedSourcePages.map(Int::toString)
            ),
            orderedAssetsDigest,
            sourceRequestPolicyVersion,
            responseBodySha256
        )
    }
}

data class NtkQuarantinePlanBinding(
    val episodePath: String,
    val discoveryGeneration: Long,
    val planProofDigest: String,
    val viewerRequestIdentityDigest: String,
    val orderedCanonicalAssets: List<String>,
    val orderedAssetsDigest: String,
    val pageCount: Int,
    val sourceRequestPolicyVersion: String,
    val bindingDigest: String
) {
    val normalizedOrderedCanonicalAssets =
        orderedCanonicalAssets.map(NtkStripDigests::canonicalAsset)

    init {
        require(NtkStripDigests.normalizeEpisodePath(episodePath) == episodePath)
        require(discoveryGeneration > 0L)
        require(NtkStripDigests.isSha256(planProofDigest))
        require(NtkStripDigests.isSha256(viewerRequestIdentityDigest))
        require(pageCount == normalizedOrderedCanonicalAssets.size && pageCount in 1..1_000)
        require(normalizedOrderedCanonicalAssets.none(String::isBlank))
        require(orderedAssetsDigest == NtkEpisodeManifestSeal.computeDigestSha256(
            episodePath,
            pageCount,
            normalizedOrderedCanonicalAssets
        ))
        require(sourceRequestPolicyVersion == NtkEpisodeDocumentPlanProof.SOURCE_REQUEST_POLICY_VERSION)
        require(bindingDigest == NtkProvisionalEpisodePlan.computeBindingDigest(
            planProofDigest,
            viewerRequestIdentityDigest,
            orderedAssetsDigest,
            sourceRequestPolicyVersion
        ))
    }

    companion object {
        @JvmStatic
        fun from(plan: NtkProvisionalEpisodePlan): NtkQuarantinePlanBinding =
            NtkQuarantinePlanBinding(
                episodePath = plan.proof.normalizedEpisodePath,
                discoveryGeneration = plan.proof.discoveryGeneration,
                planProofDigest = plan.proof.proofDigestSha256,
                viewerRequestIdentityDigest =
                    plan.proof.requestIdentity.identityDigestSha256,
                orderedCanonicalAssets = plan.normalizedOrderedCanonicalAssets,
                orderedAssetsDigest = plan.orderedAssetsDigestSha256,
                pageCount = plan.pageCount,
                sourceRequestPolicyVersion = plan.proof.sourceRequestPolicyVersion,
                bindingDigest = plan.bindingDigestSha256
            )

        /** Private request ownership derived from exact episode-list count metadata after click. */
        internal fun fromClickPayloadHint(
            episodePath: String,
            discoveryGeneration: Long,
            payloadHintDigest: String,
            orderedCandidateAssets: List<String>,
        ): NtkQuarantinePlanBinding {
            require(NtkStripDigests.isSha256(payloadHintDigest))
            val normalizedPath = NtkStripDigests.normalizeEpisodePath(episodePath)
            val assets = orderedCandidateAssets.map(NtkStripDigests::canonicalAsset)
            require(assets.isNotEmpty())
            val orderedDigest = NtkEpisodeManifestSeal.computeDigestSha256(
                normalizedPath,
                assets.size,
                assets,
            )
            val planProofDigest = NtkStripDigests.sha256Tokens(
                "ntk-click-payload-count-plan-v1",
                normalizedPath,
                discoveryGeneration.toString(),
                assets.size.toString(),
                payloadHintDigest,
            )
            val requestIdentityDigest = NtkStripDigests.sha256Tokens(
                "ntk-click-payload-count-request-v1",
                normalizedPath,
                discoveryGeneration.toString(),
                payloadHintDigest,
            )
            val policy = NtkEpisodeDocumentPlanProof.SOURCE_REQUEST_POLICY_VERSION
            return NtkQuarantinePlanBinding(
                episodePath = normalizedPath,
                discoveryGeneration = discoveryGeneration,
                planProofDigest = planProofDigest,
                viewerRequestIdentityDigest = requestIdentityDigest,
                orderedCanonicalAssets = assets,
                orderedAssetsDigest = orderedDigest,
                pageCount = assets.size,
                sourceRequestPolicyVersion = policy,
                bindingDigest = NtkProvisionalEpisodePlan.computeBindingDigest(
                    planProofDigest,
                    requestIdentityDigest,
                    orderedDigest,
                    policy,
                ),
            )
        }
    }
}

enum class NtkExactManifestProofKind {
    EPISODE_DOCUMENT_GENERATED,
    TOKEN_BOUND_GENERATED,
    VIEWER_IMAGE_API
}

data class NtkGeneratedAssetSpec(
    val canonicalOrigin: String,
    val canonicalDirectory: String,
    val filePrefix: String,
    val firstPageNumber: Int,
    val zeroPadWidth: Int,
    val extension: String,
    val pageCount: Int,
    val generatorVersion: String = "ntk-generated-cdn-v1"
) {
    init {
        val origin = URI(canonicalOrigin)
        require(origin.scheme.equals("https", ignoreCase = true))
        require(!origin.host.isNullOrBlank())
        require(canonicalOrigin.trimEnd('/') == canonicalOrigin)
        require(canonicalDirectory.startsWith('/'))
        require(!canonicalDirectory.endsWith('/'))
        require(filePrefix.isNotBlank() && '/' !in filePrefix)
        require(firstPageNumber > 0)
        require(zeroPadWidth > 0)
        require(extension.matches(Regex("[a-zA-Z0-9]+")))
        require(pageCount > 0)
        require(generatorVersion == "ntk-generated-cdn-v1")
    }

    fun canonicalAssets(): List<String> = List(pageCount) { offset ->
        val page = firstPageNumber + offset
        "$canonicalOrigin$canonicalDirectory/$filePrefix${page.toString().padStart(zeroPadWidth, '0')}.${extension.lowercase(Locale.ROOT)}"
    }

    val digestSha256: String
        get() = NtkStripDigests.sha256Tokens(
            "ntk-generated-asset-spec-v1",
            generatorVersion,
            canonicalOrigin,
            canonicalDirectory,
            filePrefix,
            firstPageNumber.toString(),
            zeroPadWidth.toString(),
            extension.lowercase(Locale.ROOT),
            pageCount.toString()
        )
}

sealed interface NtkExactManifestProof {
    val kind: NtkExactManifestProofKind
    val episodePath: String
    val discoveryGeneration: Long
    val httpMethod: String
    val httpStatus: Int
    val canonicalRequestUrl: String
    val canonicalFinalUrl: String
    val requestUrlDigestSha256: String
    val finalUrlDigestSha256: String
    val selectedHeadersDigestSha256: String
    val responseBodySha256: String
    val responseBodyLength: Long
    val responseBodyConsumedToEof: Boolean
    val responseIdentityDigestSha256: String
    val parserSchema: String
    val pageCount: Int
    val orderedAssetsDigestSha256: String
    val manifestDigestSha256: String
    val extractionDigestSha256: String
    val proofDigestSha256: String

    fun isValidFor(seal: NtkEpisodeManifestSeal): Boolean
}

private fun isTrustedManifestUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private fun commonExactManifestProofValid(
    proof: NtkExactManifestProof,
    seal: NtkEpisodeManifestSeal,
    expectedHttpMethod: String,
    expectedParserSchema: String,
    expectedExtractionDigest: String
): Boolean {
    val normalizedPath = NtkStripDigests.normalizeEpisodePath(proof.episodePath)
    if (!seal.isStructurallyComplete || normalizedPath != seal.normalizedEpisodePath) return false
    if (proof.discoveryGeneration <= 0L || proof.httpMethod != expectedHttpMethod || proof.httpStatus != 200) return false
    if (!proof.responseBodyConsumedToEof || proof.responseBodyLength <= 0L) return false
    if (!isTrustedManifestUrl(proof.canonicalRequestUrl) || !isTrustedManifestUrl(proof.canonicalFinalUrl)) return false
    if (proof.parserSchema != expectedParserSchema || proof.pageCount != seal.pageCount) return false
    if (proof.manifestDigestSha256 != seal.digestSha256 ||
        proof.orderedAssetsDigestSha256 != seal.digestSha256
    ) return false
    val requestDigest = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", proof.canonicalRequestUrl)
    val finalDigest = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", proof.canonicalFinalUrl)
    if (proof.requestUrlDigestSha256 != requestDigest || proof.finalUrlDigestSha256 != finalDigest) return false
    if (!NtkStripDigests.isSha256(proof.selectedHeadersDigestSha256) ||
        !NtkStripDigests.isSha256(proof.responseBodySha256)
    ) return false
    val responseIdentity = NtkStripDigests.sha256Tokens(
        "ntk-manifest-response-v1",
        proof.httpMethod,
        proof.canonicalRequestUrl,
        proof.canonicalFinalUrl,
        proof.httpStatus.toString(),
        proof.selectedHeadersDigestSha256,
        proof.responseBodyLength.toString(),
        proof.responseBodySha256
    )
    if (proof.responseIdentityDigestSha256 != responseIdentity ||
        proof.extractionDigestSha256 != expectedExtractionDigest
    ) return false
    return proof.proofDigestSha256 == NtkStripDigests.sha256Tokens(
        "ntk-exact-manifest-proof-v1",
        responseIdentity,
        expectedExtractionDigest,
        seal.digestSha256,
        proof.discoveryGeneration.toString()
    )
}

data class NtkEpisodeDocumentGeneratedManifestProof(
    override val episodePath: String,
    override val discoveryGeneration: Long,
    override val httpMethod: String,
    override val httpStatus: Int,
    override val canonicalRequestUrl: String,
    override val canonicalFinalUrl: String,
    override val requestUrlDigestSha256: String,
    override val finalUrlDigestSha256: String,
    override val selectedHeadersDigestSha256: String,
    override val responseBodySha256: String,
    override val responseBodyLength: Long,
    override val responseBodyConsumedToEof: Boolean,
    override val responseIdentityDigestSha256: String,
    override val parserSchema: String,
    val generatedAssetSpec: NtkGeneratedAssetSpec,
    val generatedAssetSpecDigestSha256: String,
    override val pageCount: Int,
    override val orderedAssetsDigestSha256: String,
    override val manifestDigestSha256: String,
    override val extractionDigestSha256: String,
    override val proofDigestSha256: String
) : NtkExactManifestProof {
    override val kind: NtkExactManifestProofKind =
        NtkExactManifestProofKind.EPISODE_DOCUMENT_GENERATED

    override fun isValidFor(seal: NtkEpisodeManifestSeal): Boolean {
        val regenerated = generatedAssetSpec.canonicalAssets().map(NtkStripDigests::canonicalAsset)
        if (generatedAssetSpecDigestSha256 != generatedAssetSpec.digestSha256 ||
            generatedAssetSpec.pageCount != pageCount || regenerated != seal.normalizedCanonicalAssets
        ) return false
        val orderedDigest = NtkEpisodeManifestSeal.computeDigestSha256(episodePath, pageCount, regenerated)
        if (orderedAssetsDigestSha256 != orderedDigest) return false
        val extraction = NtkStripDigests.sha256Tokens(
            "ntk-manifest-extraction-v1",
            parserSchema,
            NtkStripDigests.normalizeEpisodePath(episodePath),
            responseBodySha256,
            generatedAssetSpecDigestSha256,
            orderedDigest
        )
        return commonExactManifestProofValid(
            this,
            seal,
            "GET",
            "ntk-episode-generated-manifest-v1",
            extraction
        )
    }

    companion object {
        @JvmStatic
        fun create(
            episodePath: String,
            discoveryGeneration: Long,
            canonicalRequestUrl: String,
            canonicalFinalUrl: String,
            selectedHeadersDigestSha256: String,
            responseBody: ByteArray,
            generatedAssetSpec: NtkGeneratedAssetSpec,
            seal: NtkEpisodeManifestSeal
        ): NtkEpisodeDocumentGeneratedManifestProof {
            val bodyDigest = NtkStripDigests.sha256Bytes(responseBody)
            val requestUrl = canonicalRequestUrl.trim()
            val finalUrl = canonicalFinalUrl.trim()
            val responseIdentity = NtkStripDigests.sha256Tokens(
                "ntk-manifest-response-v1",
                "GET",
                requestUrl,
                finalUrl,
                "200",
                selectedHeadersDigestSha256,
                responseBody.size.toString(),
                bodyDigest
            )
            val assets = generatedAssetSpec.canonicalAssets()
            val orderedDigest = NtkEpisodeManifestSeal.computeDigestSha256(
                episodePath,
                generatedAssetSpec.pageCount,
                assets
            )
            val extraction = NtkStripDigests.sha256Tokens(
                "ntk-manifest-extraction-v1",
                "ntk-episode-generated-manifest-v1",
                NtkStripDigests.normalizeEpisodePath(episodePath),
                bodyDigest,
                generatedAssetSpec.digestSha256,
                orderedDigest
            )
            val proofDigest = NtkStripDigests.sha256Tokens(
                "ntk-exact-manifest-proof-v1",
                responseIdentity,
                extraction,
                seal.digestSha256,
                discoveryGeneration.toString()
            )
            return NtkEpisodeDocumentGeneratedManifestProof(
                episodePath = NtkStripDigests.normalizeEpisodePath(episodePath),
                discoveryGeneration = discoveryGeneration,
                httpMethod = "GET",
                httpStatus = 200,
                canonicalRequestUrl = requestUrl,
                canonicalFinalUrl = finalUrl,
                requestUrlDigestSha256 = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", requestUrl),
                finalUrlDigestSha256 = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", finalUrl),
                selectedHeadersDigestSha256 = selectedHeadersDigestSha256,
                responseBodySha256 = bodyDigest,
                responseBodyLength = responseBody.size.toLong(),
                responseBodyConsumedToEof = true,
                responseIdentityDigestSha256 = responseIdentity,
                parserSchema = "ntk-episode-generated-manifest-v1",
                generatedAssetSpec = generatedAssetSpec,
                generatedAssetSpecDigestSha256 = generatedAssetSpec.digestSha256,
                pageCount = generatedAssetSpec.pageCount,
                orderedAssetsDigestSha256 = orderedDigest,
                manifestDigestSha256 = seal.digestSha256,
                extractionDigestSha256 = extraction,
                proofDigestSha256 = proofDigest
            ).also { require(it.isValidFor(seal)) }
        }
    }
}

/**
 * Exact generated-asset authority derived from one complete HTTPS episode document.
 *
 * The document parser has already proven that the component identity, the two-part signed image
 * token identity, the numeric route identity, and the ordered 1..N page table agree.  For the
 * numeric manhwa route the production asset mapping is finite and deterministic, so the mapping
 * can be sealed without waiting for the independent ACK/API audit.  Keeping the entire document
 * plan proof in this proof makes that policy self-contained; a bare page count or guessed URL can
 * never construct a valid authority.
 */
data class NtkTokenBoundGeneratedManifestProof(
    val documentPlanProof: NtkEpisodeDocumentPlanProof,
    val generatedAssetSpec: NtkGeneratedAssetSpec,
    val generatedAssetSpecDigestSha256: String,
    val exactResponseIdentityDigestSha256: String,
    override val extractionDigestSha256: String,
    override val proofDigestSha256: String,
) : NtkExactManifestProof {
    override val kind: NtkExactManifestProofKind =
        NtkExactManifestProofKind.TOKEN_BOUND_GENERATED
    override val episodePath: String
        get() = documentPlanProof.normalizedEpisodePath
    override val discoveryGeneration: Long
        get() = documentPlanProof.discoveryGeneration
    override val httpMethod: String
        get() = "GET"
    override val httpStatus: Int
        get() = documentPlanProof.httpStatus
    override val canonicalRequestUrl: String
        get() = documentPlanProof.canonicalRequestUrl
    override val canonicalFinalUrl: String
        get() = documentPlanProof.canonicalFinalUrl
    override val requestUrlDigestSha256: String
        get() = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", canonicalRequestUrl)
    override val finalUrlDigestSha256: String
        get() = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", canonicalFinalUrl)
    override val selectedHeadersDigestSha256: String
        get() = documentPlanProof.selectedHeadersDigestSha256
    override val responseBodySha256: String
        get() = documentPlanProof.responseBodySha256
    override val responseBodyLength: Long
        get() = documentPlanProof.responseBodyLength
    override val responseBodyConsumedToEof: Boolean
        get() = documentPlanProof.responseConsumedToEof
    override val responseIdentityDigestSha256: String
        get() = exactResponseIdentityDigestSha256
    override val parserSchema: String
        get() = PARSER_SCHEMA
    override val pageCount: Int
        get() = documentPlanProof.pageCount
    override val orderedAssetsDigestSha256: String
        get() = documentPlanProof.orderedAssetsDigestSha256
    override val manifestDigestSha256: String
        get() = documentPlanProof.orderedAssetsDigestSha256

    override fun isValidFor(seal: NtkEpisodeManifestSeal): Boolean {
        val identity = documentPlanProof.requestIdentity
        val route = NUMERIC_MANHWA_ROUTE.matchEntire(episodePath) ?: return false
        if (identity.normalizedSegment != "manhwa" ||
            identity.normalizedEndpointPath != "/api/manhwa-images" ||
            identity.normalizedSourceWorkId != route.groupValues[1] ||
            identity.normalizedEpisodeId != route.groupValues[2] ||
            documentPlanProof.pageCount != seal.pageCount ||
            documentPlanProof.orderedAssetsDigestSha256 != seal.digestSha256 ||
            documentPlanProof.sourceRequestPolicyVersion !=
                NtkEpisodeDocumentPlanProof.SOURCE_REQUEST_POLICY_VERSION
        ) return false
        val expectedDirectory = "/manhwa/${identity.normalizedSourceWorkId}/${identity.normalizedEpisodeId}"
        if (generatedAssetSpec.canonicalOrigin != CANONICAL_MANHWA_ORIGIN ||
            generatedAssetSpec.canonicalDirectory != expectedDirectory ||
            generatedAssetSpec.filePrefix != "p" ||
            generatedAssetSpec.firstPageNumber != 1 ||
            generatedAssetSpec.zeroPadWidth != 3 ||
            generatedAssetSpec.extension.lowercase(Locale.ROOT) != "jpg" ||
            generatedAssetSpec.pageCount != pageCount ||
            generatedAssetSpecDigestSha256 != generatedAssetSpec.digestSha256
        ) return false
        val assets = generatedAssetSpec.canonicalAssets().map(NtkStripDigests::canonicalAsset)
        if (assets != seal.normalizedCanonicalAssets) return false
        val expectedResponseIdentity = exactResponseIdentity(documentPlanProof)
        if (exactResponseIdentityDigestSha256 != expectedResponseIdentity) return false
        val expectedExtraction = extractionDigest(
            documentPlanProof,
            generatedAssetSpecDigestSha256,
            seal.digestSha256,
        )
        if (extractionDigestSha256 != expectedExtraction) return false
        return commonExactManifestProofValid(
            this,
            seal,
            "GET",
            PARSER_SCHEMA,
            expectedExtraction,
        )
    }

    companion object {
        const val PARSER_SCHEMA = "ntk-token-bound-generated-manifest-v1"
        const val CANONICAL_MANHWA_ORIGIN = "https://booktoki9.org"
        private val NUMERIC_MANHWA_ROUTE =
            Regex("^/manhwa/(\\d{1,12})/(\\d{1,12})$")

        @JvmStatic
        fun create(
            documentPlanProof: NtkEpisodeDocumentPlanProof,
            generatedAssetSpec: NtkGeneratedAssetSpec,
            seal: NtkEpisodeManifestSeal,
        ): NtkTokenBoundGeneratedManifestProof {
            val responseIdentity = exactResponseIdentity(documentPlanProof)
            val extraction = extractionDigest(
                documentPlanProof,
                generatedAssetSpec.digestSha256,
                seal.digestSha256,
            )
            val proofDigest = NtkStripDigests.sha256Tokens(
                "ntk-exact-manifest-proof-v1",
                responseIdentity,
                extraction,
                seal.digestSha256,
                documentPlanProof.discoveryGeneration.toString(),
            )
            return NtkTokenBoundGeneratedManifestProof(
                documentPlanProof = documentPlanProof,
                generatedAssetSpec = generatedAssetSpec,
                generatedAssetSpecDigestSha256 = generatedAssetSpec.digestSha256,
                exactResponseIdentityDigestSha256 = responseIdentity,
                extractionDigestSha256 = extraction,
                proofDigestSha256 = proofDigest,
            ).also { require(it.isValidFor(seal)) }
        }

        private fun exactResponseIdentity(plan: NtkEpisodeDocumentPlanProof): String =
            NtkStripDigests.sha256Tokens(
                "ntk-manifest-response-v1",
                "GET",
                plan.canonicalRequestUrl,
                plan.canonicalFinalUrl,
                plan.httpStatus.toString(),
                plan.selectedHeadersDigestSha256,
                plan.responseBodyLength.toString(),
                plan.responseBodySha256,
            )

        private fun extractionDigest(
            plan: NtkEpisodeDocumentPlanProof,
            specDigest: String,
            manifestDigest: String,
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-manifest-extraction-v1",
            PARSER_SCHEMA,
            plan.normalizedEpisodePath,
            plan.responseBodySha256,
            plan.componentPayloadDigestSha256,
            plan.requestIdentity.identityDigestSha256,
            plan.proofDigestSha256,
            specDigest,
            manifestDigest,
        )
    }
}

/**
 * Early exact authority for numeric manhwa assets that the committed click has independently
 * observed as real 2xx image responses. The complete episode document binds work, episode,
 * token, ordered page count and discovery generation; the per-page response headers bind the
 * mixed extension table. Encoded bytes remain quarantined until this proof is installed.
 */
data class NtkObservedNumericReplicaManifestProof private constructor(
    val documentPlanProof: NtkEpisodeDocumentPlanProof,
    val observedAssetsDigestSha256: String,
    override val extractionDigestSha256: String,
    override val proofDigestSha256: String,
) : NtkExactManifestProof {
    override val kind: NtkExactManifestProofKind = NtkExactManifestProofKind.TOKEN_BOUND_GENERATED
    override val episodePath: String get() = documentPlanProof.normalizedEpisodePath
    override val discoveryGeneration: Long get() = documentPlanProof.discoveryGeneration
    override val httpMethod: String get() = "GET"
    override val httpStatus: Int get() = documentPlanProof.httpStatus
    override val canonicalRequestUrl: String get() = documentPlanProof.canonicalRequestUrl
    override val canonicalFinalUrl: String get() = documentPlanProof.canonicalFinalUrl
    override val requestUrlDigestSha256: String
        get() = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", canonicalRequestUrl)
    override val finalUrlDigestSha256: String
        get() = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", canonicalFinalUrl)
    override val selectedHeadersDigestSha256: String
        get() = documentPlanProof.selectedHeadersDigestSha256
    override val responseBodySha256: String get() = documentPlanProof.responseBodySha256
    override val responseBodyLength: Long get() = documentPlanProof.responseBodyLength
    override val responseBodyConsumedToEof: Boolean get() = documentPlanProof.responseConsumedToEof
    override val responseIdentityDigestSha256: String
        get() = exactResponseIdentity(documentPlanProof)
    override val parserSchema: String get() = PARSER_SCHEMA
    override val pageCount: Int get() = documentPlanProof.pageCount
    override val orderedAssetsDigestSha256: String get() = documentPlanProof.orderedAssetsDigestSha256
    override val manifestDigestSha256: String get() = documentPlanProof.orderedAssetsDigestSha256

    override fun isValidFor(seal: NtkEpisodeManifestSeal): Boolean {
        val identity = documentPlanProof.requestIdentity
        val route = NUMERIC_MANHWA_ROUTE.matchEntire(episodePath) ?: return false
        if (identity.normalizedSegment != "manhwa" ||
            identity.normalizedEndpointPath != "/api/manhwa-images" ||
            identity.normalizedSourceWorkId != route.groupValues[1] ||
            identity.normalizedEpisodeId != route.groupValues[2] ||
            documentPlanProof.sourceRequestPolicyVersion !=
                NtkEpisodeDocumentPlanProof.SOURCE_REQUEST_POLICY_VERSION ||
            documentPlanProof.pageCount != seal.pageCount ||
            documentPlanProof.orderedAssetsDigestSha256 != seal.digestSha256 ||
            observedAssetsDigestSha256 != seal.digestSha256
        ) return false
        val expectedDirectory = "/manhwa/${identity.normalizedSourceWorkId}/${identity.normalizedEpisodeId}/"
        val assetsValid = seal.normalizedCanonicalAssets.withIndex().all { (index, asset) ->
            val uri = runCatching { URI(asset) }.getOrNull() ?: return@all false
            val expectedName = "p${(index + 1).toString().padStart(3, '0')}"
            uri.scheme.equals("https", ignoreCase = true) &&
                NtkClickOwnedManhwaWavePolicy.isReplicaHost(uri.host.orEmpty()) &&
                uri.path.substringBeforeLast('/', "") + "/" == expectedDirectory &&
                uri.path.substringAfterLast('/').matches(
                    Regex("^${Regex.escape(expectedName)}\\.(?:jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE),
                )
        }
        if (!assetsValid) return false
        val expectedExtraction = extractionDigest(documentPlanProof, seal.digestSha256)
        if (extractionDigestSha256 != expectedExtraction) return false
        return commonExactManifestProofValid(
            this,
            seal,
            "GET",
            PARSER_SCHEMA,
            expectedExtraction,
        )
    }

    companion object {
        const val PARSER_SCHEMA = "ntk-observed-numeric-replica-manifest-v1"
        private val NUMERIC_MANHWA_ROUTE = Regex("^/manhwa/(\\d{1,12})/(\\d{1,12})$")

        fun create(
            documentPlanProof: NtkEpisodeDocumentPlanProof,
            seal: NtkEpisodeManifestSeal,
        ): NtkObservedNumericReplicaManifestProof {
            val responseIdentity = exactResponseIdentity(documentPlanProof)
            val extraction = extractionDigest(documentPlanProof, seal.digestSha256)
            val proofDigest = NtkStripDigests.sha256Tokens(
                "ntk-exact-manifest-proof-v1",
                responseIdentity,
                extraction,
                seal.digestSha256,
                documentPlanProof.discoveryGeneration.toString(),
            )
            return NtkObservedNumericReplicaManifestProof(
                documentPlanProof,
                seal.digestSha256,
                extraction,
                proofDigest,
            ).also { require(it.isValidFor(seal)) }
        }

        private fun exactResponseIdentity(plan: NtkEpisodeDocumentPlanProof): String =
            NtkStripDigests.sha256Tokens(
                "ntk-manifest-response-v1",
                "GET",
                plan.canonicalRequestUrl,
                plan.canonicalFinalUrl,
                plan.httpStatus.toString(),
                plan.selectedHeadersDigestSha256,
                plan.responseBodyLength.toString(),
                plan.responseBodySha256,
            )

        private fun extractionDigest(
            plan: NtkEpisodeDocumentPlanProof,
            manifestDigest: String,
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-manifest-extraction-v1",
            PARSER_SCHEMA,
            plan.normalizedEpisodePath,
            plan.responseBodySha256,
            plan.componentPayloadDigestSha256,
            plan.requestIdentity.identityDigestSha256,
            plan.proofDigestSha256,
            manifestDigest,
        )
    }
}

data class NtkViewerImageApiManifestProof(
    override val episodePath: String,
    override val discoveryGeneration: Long,
    override val httpMethod: String,
    override val httpStatus: Int,
    override val canonicalRequestUrl: String,
    override val canonicalFinalUrl: String,
    override val requestUrlDigestSha256: String,
    override val finalUrlDigestSha256: String,
    override val selectedHeadersDigestSha256: String,
    override val responseBodySha256: String,
    override val responseBodyLength: Long,
    override val responseBodyConsumedToEof: Boolean,
    override val responseIdentityDigestSha256: String,
    override val parserSchema: String,
    val documentPlanProofDigestSha256: String,
    val viewerImageRequestIdentityDigestSha256: String,
    val requestBodySha256: String,
    val requestBodyLength: Long,
    val responseConsumedToEof: Boolean,
    val orderedAssetSelectionPolicyVersion: String,
    override val pageCount: Int,
    override val orderedAssetsDigestSha256: String,
    override val manifestDigestSha256: String,
    override val extractionDigestSha256: String,
    override val proofDigestSha256: String
) : NtkExactManifestProof {
    override val kind: NtkExactManifestProofKind = NtkExactManifestProofKind.VIEWER_IMAGE_API

    override fun isValidFor(seal: NtkEpisodeManifestSeal): Boolean {
        if (!NtkStripDigests.isSha256(documentPlanProofDigestSha256) ||
            !NtkStripDigests.isSha256(viewerImageRequestIdentityDigestSha256) ||
            !NtkStripDigests.isSha256(requestBodySha256) ||
            requestBodyLength <= 0L ||
            !responseConsumedToEof ||
            !responseBodyConsumedToEof ||
            orderedAssetSelectionPolicyVersion != ORDERED_ASSET_SELECTION_POLICY_VERSION
        ) return false
        val extraction = NtkStripDigests.sha256Tokens(
            "ntk-manifest-extraction-v1",
            parserSchema,
            NtkStripDigests.normalizeEpisodePath(episodePath),
            responseBodySha256,
            orderedAssetsDigestSha256,
            documentPlanProofDigestSha256,
            viewerImageRequestIdentityDigestSha256,
            requestBodySha256,
            requestBodyLength.toString(),
            orderedAssetSelectionPolicyVersion
        )
        return commonExactManifestProofValid(this, seal, "POST", "ntk-viewer-image-api-v1", extraction)
    }

    companion object {
        const val ORDERED_ASSET_SELECTION_POLICY_VERSION =
            "ntk-viewer-assets-renderable-balanced-replica-v3"

        @JvmStatic
        fun create(
            episodePath: String,
            discoveryGeneration: Long,
            canonicalRequestUrl: String,
            canonicalFinalUrl: String,
            selectedHeadersDigestSha256: String,
            requestBody: ByteArray,
            responseBody: ByteArray,
            documentPlanProofDigestSha256: String,
            viewerImageRequestIdentityDigestSha256: String,
            responseConsumedToEof: Boolean,
            orderedAssetSelectionPolicyVersion: String,
            orderedAssets: List<String>,
            seal: NtkEpisodeManifestSeal
        ): NtkViewerImageApiManifestProof {
            val requestUrl = canonicalRequestUrl.trim()
            val finalUrl = canonicalFinalUrl.trim()
            val assets = orderedAssets.map(NtkStripDigests::canonicalAsset)
            require(assets.isNotEmpty() && assets.none { it.isBlank() })
            require(seal.normalizedCanonicalAssets == assets)
            require(requestBody.isNotEmpty())
            require(NtkStripDigests.isSha256(documentPlanProofDigestSha256))
            require(NtkStripDigests.isSha256(viewerImageRequestIdentityDigestSha256))
            require(responseConsumedToEof)
            require(orderedAssetSelectionPolicyVersion == ORDERED_ASSET_SELECTION_POLICY_VERSION)
            val requestBodyDigest = NtkStripDigests.sha256Bytes(requestBody)
            val bodyDigest = NtkStripDigests.sha256Bytes(responseBody)
            val responseIdentity = NtkStripDigests.sha256Tokens(
                "ntk-manifest-response-v1",
                "POST",
                requestUrl,
                finalUrl,
                "200",
                selectedHeadersDigestSha256,
                responseBody.size.toString(),
                bodyDigest
            )
            val orderedDigest = NtkEpisodeManifestSeal.computeDigestSha256(
                episodePath,
                assets.size,
                assets
            )
            val extraction = NtkStripDigests.sha256Tokens(
                "ntk-manifest-extraction-v1",
                "ntk-viewer-image-api-v1",
                NtkStripDigests.normalizeEpisodePath(episodePath),
                bodyDigest,
                orderedDigest,
                documentPlanProofDigestSha256,
                viewerImageRequestIdentityDigestSha256,
                requestBodyDigest,
                requestBody.size.toString(),
                orderedAssetSelectionPolicyVersion
            )
            val proofDigest = NtkStripDigests.sha256Tokens(
                "ntk-exact-manifest-proof-v1",
                responseIdentity,
                extraction,
                seal.digestSha256,
                discoveryGeneration.toString()
            )
            return NtkViewerImageApiManifestProof(
                episodePath = NtkStripDigests.normalizeEpisodePath(episodePath),
                discoveryGeneration = discoveryGeneration,
                httpMethod = "POST",
                httpStatus = 200,
                canonicalRequestUrl = requestUrl,
                canonicalFinalUrl = finalUrl,
                requestUrlDigestSha256 = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", requestUrl),
                finalUrlDigestSha256 = NtkStripDigests.sha256Tokens("ntk-manifest-url-v1", finalUrl),
                selectedHeadersDigestSha256 = selectedHeadersDigestSha256,
                responseBodySha256 = bodyDigest,
                responseBodyLength = responseBody.size.toLong(),
                responseBodyConsumedToEof = true,
                responseIdentityDigestSha256 = responseIdentity,
                parserSchema = "ntk-viewer-image-api-v1",
                documentPlanProofDigestSha256 = documentPlanProofDigestSha256,
                viewerImageRequestIdentityDigestSha256 = viewerImageRequestIdentityDigestSha256,
                requestBodySha256 = requestBodyDigest,
                requestBodyLength = requestBody.size.toLong(),
                responseConsumedToEof = responseConsumedToEof,
                orderedAssetSelectionPolicyVersion = orderedAssetSelectionPolicyVersion,
                pageCount = assets.size,
                orderedAssetsDigestSha256 = orderedDigest,
                manifestDigestSha256 = seal.digestSha256,
                extractionDigestSha256 = extraction,
                proofDigestSha256 = proofDigest
            ).also { require(it.isValidFor(seal)) }
        }
    }
}

data class NtkAuthoritativeManifest(
    val seal: NtkEpisodeManifestSeal,
    val proof: NtkExactManifestProof
) {
    init {
        require(isProductionClaimable)
    }

    val isProductionClaimable: Boolean
        get() = seal.isStructurallyComplete && proof.isValidFor(seal)
}

/** Evidence that the initial source wave overlapped exact discovery without duplicate producers. */
data class NtkSourceOverlapProof(
    val planReservedAtMs: Long,
    val firstQuarantineSubmittedAtMs: Long,
    val initialQuarantineWaveSubmittedAtMs: Long,
    val initialWaveCount: Int,
    val exactSealAtMs: Long,
    val ownerClaimedAtMs: Long,
    val completedAtPromotion: Int,
    val activeAtPromotion: Int,
    val queuedAtPromotion: Int,
    val postPromotionStarted: Int,
    val physicalCallCount: Int,
    val duplicatePhysicalCallCount: Int
) {
    init {
        require(planReservedAtMs >= 0L)
        require(firstQuarantineSubmittedAtMs >= planReservedAtMs)
        require(initialQuarantineWaveSubmittedAtMs >= firstQuarantineSubmittedAtMs)
        // A fully click-owned exact body set legitimately needs no second physical source wave.
        require(initialWaveCount >= 0)
        require(exactSealAtMs > firstQuarantineSubmittedAtMs)
        require(ownerClaimedAtMs >= exactSealAtMs)
        require(completedAtPromotion >= 0 && activeAtPromotion >= 0 &&
            queuedAtPromotion >= 0 && postPromotionStarted >= 0)
        require(
            physicalCallCount in completedAtPromotion..
                (completedAtPromotion + activeAtPromotion)
        )
        require(duplicatePhysicalCallCount == 0)
    }

    val overlapBeforeExactMs: Long
        get() = exactSealAtMs - firstQuarantineSubmittedAtMs
}

data class NtkSourceCloseBarrierProof(
    val episodePath: String,
    val discoveryGeneration: Long,
    val manifestDigest: String,
    val sessionId: Long,
    val barrierSerial: Long,
    val remainingCalls: Int,
    val remainingStreams: Int,
    val remainingTeeWriters: Int,
    val remainingMetadataParsers: Int,
    val remainingCachePublishes: Int,
    val remainingDecodes: Int,
    val remainingCallbacks: Int,
    val remainingTemporaryFileLeases: Int,
    val remainingQuarantineCalls: Int,
    val remainingQuarantineFiles: Int,
    val remainingAdoptionTasks: Int,
    val admissionsClosed: Boolean,
    val completedAtMs: Long
) {
    val isComplete: Boolean
        get() = barrierSerial > 0L && completedAtMs > 0L && admissionsClosed &&
            remainingCalls == 0 && remainingStreams == 0 && remainingTeeWriters == 0 &&
            remainingMetadataParsers == 0 && remainingCachePublishes == 0 &&
            remainingDecodes == 0 && remainingCallbacks == 0 &&
            remainingTemporaryFileLeases == 0 && remainingQuarantineCalls == 0 &&
            remainingQuarantineFiles == 0 && remainingAdoptionTasks == 0
}

data class NtkQuarantineCloseBarrierProof(
    val episodePath: String,
    val discoveryGeneration: Long,
    val planBindingDigest: String,
    val sessionId: Long,
    val remainingCalls: Int,
    val remainingStreams: Int,
    val remainingTeeWriters: Int,
    val remainingMetadataParsers: Int,
    val remainingCallbacks: Int,
    val remainingTemporaryFileLeases: Int,
    val remainingAdoptionTasks: Int,
    val admissionsClosed: Boolean,
    val completedAtMs: Long
) {
    val isComplete: Boolean
        get() = completedAtMs > 0L && admissionsClosed &&
            remainingCalls == 0 && remainingStreams == 0 &&
            remainingTeeWriters == 0 && remainingMetadataParsers == 0 &&
            remainingCallbacks == 0 && remainingTemporaryFileLeases == 0 &&
            remainingAdoptionTasks == 0
}

enum class NtkManifestClaimPhase {
    BEFORE_CLAIM,
    BINDING,
    STAGED,
    ACTIVE
}

enum class NtkManifestChangeAction {
    NO_OP,
    ACCEPT_CANDIDATE,
    REPLACE_CANDIDATE,
    IGNORE_UNCLAIMABLE,
    FAIL_CLOSED
}

data class NtkManifestChangeDecision(
    val action: NtkManifestChangeAction,
    val reason: String
)

object NtkManifestAuthorityPolicy {
    @JvmStatic
    fun decide(
        current: NtkAuthoritativeManifest?,
        incoming: NtkAuthoritativeManifest,
        phase: NtkManifestClaimPhase
    ): NtkManifestChangeDecision {
        if (current != null && current.seal.normalizedEpisodePath != incoming.seal.normalizedEpisodePath) {
            return NtkManifestChangeDecision(
                NtkManifestChangeAction.IGNORE_UNCLAIMABLE,
                "different_episode"
            )
        }
        if (current != null && current.seal.hasSameAuthority(incoming.seal) &&
            current.proof.proofDigestSha256 == incoming.proof.proofDigestSha256
        ) {
            return NtkManifestChangeDecision(NtkManifestChangeAction.NO_OP, "identical_manifest")
        }
        if (!incoming.isProductionClaimable) {
            return NtkManifestChangeDecision(
                NtkManifestChangeAction.IGNORE_UNCLAIMABLE,
                "manifest_not_response_bound"
            )
        }
        if (current == null) {
            return NtkManifestChangeDecision(
                NtkManifestChangeAction.ACCEPT_CANDIDATE,
                "first_complete_manifest"
            )
        }
        return when (phase) {
            NtkManifestClaimPhase.BEFORE_CLAIM -> NtkManifestChangeDecision(
                NtkManifestChangeAction.REPLACE_CANDIDATE,
                "exact_manifest_before_owned"
            )
            NtkManifestClaimPhase.BINDING,
            NtkManifestClaimPhase.STAGED,
            NtkManifestClaimPhase.ACTIVE -> NtkManifestChangeDecision(
                NtkManifestChangeAction.FAIL_CLOSED,
                "manifest_replaced_after_owned"
            )
        }
    }
}

enum class NtkSourcePhase {
    METADATA,
    BODY,
    DECODE
}

/** The immutable identity of one page inside a claimed strict manifest. */
data class NtkStrictSourceKey(
    val manifestDigest: String,
    val pageIndex: Int,
    val canonicalAssetDigest: String
) {
    init {
        require(NtkStripDigests.isSha256(manifestDigest))
        require(pageIndex >= 0)
        require(NtkStripDigests.isSha256(canonicalAssetDigest))
    }

    companion object {
        @JvmStatic
        fun create(
            manifestDigest: String,
            pageIndex: Int,
            canonicalAsset: String
        ): NtkStrictSourceKey = NtkStrictSourceKey(
            manifestDigest = manifestDigest,
            pageIndex = pageIndex,
            canonicalAssetDigest = NtkStripDigests.canonicalAssetDigestSha256(canonicalAsset)
        )
    }
}

/** The byte witness from which immutable source geometry was obtained. */
enum class NtkMetadataAcquisition {
    VERIFIED_CACHE_BODY,
    PRIMARY_BODY_TEE,
    ADOPTED_QUARANTINE_FULL_BODY
}

/**
 * Evidence binding source geometry to one response and one encoded body.
 *
 * Compatibility instances are deliberately representable while old fixtures migrate, but only
 * [isProductionAuthoritative] may cross a strict production boundary.
 */
data class NtkSourceMetadataAuthority(
    val acquisition: NtkMetadataAcquisition,
    val responseIdentityDigest: String,
    val byteWitnessSha256: String,
    val byteWitnessLength: Long,
    val encodedLength: Long,
    val strongValidatorDigest: String,
    val imageFormat: String
) {
    init {
        require(responseIdentityDigest.isEmpty() || NtkStripDigests.isSha256(responseIdentityDigest))
        require(byteWitnessSha256.isEmpty() || NtkStripDigests.isSha256(byteWitnessSha256))
        require(byteWitnessLength >= 0L)
        require(encodedLength >= 0L)
        require(strongValidatorDigest.isEmpty() || NtkStripDigests.isSha256(strongValidatorDigest))
        require(imageFormat == imageFormat.trim())
    }

    val hasFullBodyWitness: Boolean
        get() = acquisition == NtkMetadataAcquisition.VERIFIED_CACHE_BODY ||
            acquisition == NtkMetadataAcquisition.ADOPTED_QUARANTINE_FULL_BODY

    val isProductionAuthoritative: Boolean
        get() = NtkStripDigests.isSha256(responseIdentityDigest) &&
            NtkStripDigests.isSha256(byteWitnessSha256) &&
            byteWitnessLength > 0L &&
            encodedLength >= byteWitnessLength &&
            NtkStripDigests.isSha256(strongValidatorDigest) &&
            imageFormat.isNotBlank() &&
            imageFormat == normalizeImageFormat(imageFormat) &&
            (!hasFullBodyWitness || byteWitnessLength == encodedLength)

    fun requireProductionAuthority(): NtkSourceMetadataAuthority = apply {
        require(isProductionAuthoritative) { "Strict source metadata authority is incomplete" }
    }

    companion object {
        @JvmStatic
        fun createStrict(
            acquisition: NtkMetadataAcquisition,
            responseIdentityDigest: String,
            byteWitnessSha256: String,
            byteWitnessLength: Long,
            encodedLength: Long,
            strongValidatorDigest: String,
            imageFormat: String
        ): NtkSourceMetadataAuthority = NtkSourceMetadataAuthority(
            acquisition = acquisition,
            responseIdentityDigest = responseIdentityDigest,
            byteWitnessSha256 = byteWitnessSha256,
            byteWitnessLength = byteWitnessLength,
            encodedLength = encodedLength,
            strongValidatorDigest = strongValidatorDigest,
            imageFormat = normalizeImageFormat(imageFormat)
        ).requireProductionAuthority()

        @JvmStatic
        fun normalizeImageFormat(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}

data class NtkSourceMetadata(
    val manifestRevision: Long,
    val manifestDigest: String,
    val pageIndex: Int,
    val canonicalAsset: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val authority: NtkSourceMetadataAuthority,
    val metadataBindingDigest: String
) {
    init {
        require(manifestRevision >= 0L)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(pageIndex >= 0)
        require(canonicalAsset.isNotBlank())
        require(sourceWidth > 0 && sourceHeight > 0)
        require(metadataBindingDigest.isEmpty() || NtkStripDigests.isSha256(metadataBindingDigest))
    }

    val strictSourceKey: NtkStrictSourceKey
        get() = NtkStrictSourceKey.create(manifestDigest, pageIndex, canonicalAsset)

    val computedMetadataBindingDigest: String
        get() = computeMetadataBindingDigestSha256(
            manifestRevision = manifestRevision,
            manifestDigest = manifestDigest,
            pageIndex = pageIndex,
            canonicalAsset = canonicalAsset,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            authority = authority
        )

    val hasValidMetadataBinding: Boolean
        get() = NtkStripDigests.isSha256(metadataBindingDigest) &&
            metadataBindingDigest == computedMetadataBindingDigest

    val isProductionAuthoritative: Boolean
        get() = canonicalAsset == NtkStripDigests.canonicalAsset(canonicalAsset) &&
            authority.isProductionAuthoritative &&
            hasValidMetadataBinding

    fun requireProductionAuthority(): NtkSourceMetadata = apply {
        require(isProductionAuthoritative) { "Strict source metadata binding is incomplete" }
    }

    fun hasSameAuthority(other: NtkSourceMetadata): Boolean =
        isProductionAuthoritative &&
            other.isProductionAuthoritative &&
            strictSourceKey == other.strictSourceKey &&
            metadataBindingDigest == other.metadataBindingDigest

    companion object {
        @JvmStatic
        fun createStrict(
            manifestRevision: Long,
            manifestDigest: String,
            pageIndex: Int,
            canonicalAsset: String,
            sourceWidth: Int,
            sourceHeight: Int,
            authority: NtkSourceMetadataAuthority
        ): NtkSourceMetadata {
            val asset = NtkStripDigests.canonicalAsset(canonicalAsset)
            authority.requireProductionAuthority()
            return NtkSourceMetadata(
                manifestRevision = manifestRevision,
                manifestDigest = manifestDigest,
                pageIndex = pageIndex,
                canonicalAsset = asset,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                authority = authority,
                metadataBindingDigest = computeMetadataBindingDigestSha256(
                    manifestRevision,
                    manifestDigest,
                    pageIndex,
                    asset,
                    sourceWidth,
                    sourceHeight,
                    authority
                )
            ).requireProductionAuthority()
        }

        @JvmStatic
        fun computeMetadataBindingDigestSha256(
            manifestRevision: Long,
            manifestDigest: String,
            pageIndex: Int,
            canonicalAsset: String,
            sourceWidth: Int,
            sourceHeight: Int,
            authority: NtkSourceMetadataAuthority
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-source-metadata-binding-v1",
            manifestRevision.toString(),
            manifestDigest,
            pageIndex.toString(),
            NtkStripDigests.canonicalAsset(canonicalAsset),
            sourceWidth.toString(),
            sourceHeight.toString(),
            authority.acquisition.name,
            authority.responseIdentityDigest,
            authority.byteWitnessSha256,
            authority.byteWitnessLength.toString(),
            authority.encodedLength.toString(),
            authority.strongValidatorDigest,
            authority.imageFormat
        )
    }
}

/**
 * Immutable source-relative tile layout produced as soon as one page's exact metadata arrives.
 * It deliberately contains no strip-space coordinates, Surface identity, file capability, or
 * decoded pixels, so it may be created before whole-manifest geometry exists.
 */
data class NtkPreGeometryTilePlan(
    val key: NtkStripTileKey,
    val sourceTop: Int,
    val sourceBottom: Int,
    val rgbaBytes: Long,
    val tilePlanDigest: String
) {
    init {
        require(key.episode.value > 0L)
        require(key.pageIndex >= 0 && key.slotIndex >= 0)
        require(sourceTop >= 0 && sourceBottom > sourceTop)
        require(rgbaBytes > 0L)
        require(tilePlanDigest == computedTilePlanDigest)
    }

    val computedTilePlanDigest: String
        get() = computeTilePlanDigest(
            key = key,
            sourceTop = sourceTop,
            sourceBottom = sourceBottom,
            rgbaBytes = rgbaBytes
        )

    companion object {
        fun create(
            key: NtkStripTileKey,
            sourceTop: Int,
            sourceBottom: Int,
            rgbaBytes: Long
        ): NtkPreGeometryTilePlan = NtkPreGeometryTilePlan(
            key = key,
            sourceTop = sourceTop,
            sourceBottom = sourceBottom,
            rgbaBytes = rgbaBytes,
            tilePlanDigest = computeTilePlanDigest(key, sourceTop, sourceBottom, rgbaBytes)
        )

        fun computeTilePlanDigest(
            key: NtkStripTileKey,
            sourceTop: Int,
            sourceBottom: Int,
            rgbaBytes: Long
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-pregeometry-tile-plan-v1",
            key.episode.value.toString(),
            key.pageIndex.toString(),
            key.slotIndex.toString(),
            sourceTop.toString(),
            sourceBottom.toString(),
            rgbaBytes.toString()
        )
    }
}

data class NtkPreGeometryPagePlan(
    val episode: NtkEpisodeToken,
    val manifestRevision: Long,
    val manifestDigest: String,
    val sourceKey: NtkStrictSourceKey,
    val metadataBindingDigest: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val tileSourceHeightPx: Int,
    val tiles: List<NtkPreGeometryTilePlan>,
    val planDigest: String
) {
    val largestTileRgbaBytes: Long = tiles.maxOfOrNull { it.rgbaBytes } ?: 0L
    val totalRgbaBytes: Long = tiles.fold(0L) { total, tile ->
        Math.addExact(total, tile.rgbaBytes)
    }

    init {
        require(episode.value > 0L)
        require(manifestRevision >= 0L)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(sourceKey.manifestDigest == manifestDigest)
        require(NtkStripDigests.isSha256(metadataBindingDigest))
        require(sourceWidth > 0 && sourceHeight > 0 && tileSourceHeightPx > 0)
        require(sourceKey.pageIndex >= 0)
        require(tiles.isNotEmpty())
        require(tiles.map { it.key.slotIndex } == tiles.indices.toList())
        require(tiles.all {
            it.key.episode == episode && it.key.pageIndex == sourceKey.pageIndex
        })
        require(tiles.first().sourceTop == 0)
        require(tiles.last().sourceBottom == sourceHeight)
        require(tiles.zipWithNext().all { (left, right) ->
            left.sourceBottom == right.sourceTop
        })
        require(tiles.all { tile ->
            tile.sourceBottom - tile.sourceTop <= tileSourceHeightPx &&
                tile.rgbaBytes == Math.multiplyExact(
                    Math.multiplyExact(
                        sourceWidth.toLong(),
                        (tile.sourceBottom - tile.sourceTop).toLong()
                    ),
                    4L
                )
        })
        require(totalRgbaBytes == Math.multiplyExact(
            Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong()),
            4L
        ))
        require(planDigest == computedPlanDigest)
    }

    val computedPlanDigest: String
        get() = computePlanDigest(
            manifestRevision = manifestRevision,
            manifestDigest = manifestDigest,
            pageIndex = sourceKey.pageIndex,
            canonicalAssetDigest = sourceKey.canonicalAssetDigest,
            metadataBindingDigest = metadataBindingDigest,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            tileSourceHeightPx = tileSourceHeightPx,
            tiles = tiles
        )

    companion object {
        fun create(
            episode: NtkEpisodeToken,
            metadata: NtkSourceMetadata,
            tileSourceHeightPx: Int,
            tiles: List<NtkPreGeometryTilePlan>
        ): NtkPreGeometryPagePlan = NtkPreGeometryPagePlan(
            episode = episode,
            manifestRevision = metadata.manifestRevision,
            manifestDigest = metadata.manifestDigest,
            sourceKey = metadata.strictSourceKey,
            metadataBindingDigest = metadata.metadataBindingDigest,
            sourceWidth = metadata.sourceWidth,
            sourceHeight = metadata.sourceHeight,
            tileSourceHeightPx = tileSourceHeightPx,
            tiles = tiles.toList(),
            planDigest = computePlanDigest(
                manifestRevision = metadata.manifestRevision,
                manifestDigest = metadata.manifestDigest,
                pageIndex = metadata.pageIndex,
                canonicalAssetDigest = metadata.strictSourceKey.canonicalAssetDigest,
                metadataBindingDigest = metadata.metadataBindingDigest,
                sourceWidth = metadata.sourceWidth,
                sourceHeight = metadata.sourceHeight,
                tileSourceHeightPx = tileSourceHeightPx,
                tiles = tiles
            )
        )

        fun computePlanDigest(
            manifestRevision: Long,
            manifestDigest: String,
            pageIndex: Int,
            canonicalAssetDigest: String,
            metadataBindingDigest: String,
            sourceWidth: Int,
            sourceHeight: Int,
            tileSourceHeightPx: Int,
            tiles: List<NtkPreGeometryTilePlan>
        ): String = NtkStripDigests.sha256Tokens(buildList {
            add("ntk-pregeometry-page-plan-v1")
            add(manifestRevision.toString())
            add(manifestDigest)
            add(pageIndex.toString())
            add(canonicalAssetDigest)
            add(metadataBindingDigest)
            add(sourceWidth.toString())
            add(sourceHeight.toString())
            add(tileSourceHeightPx.toString())
            add(tiles.size.toString())
            tiles.forEach { tile ->
                add(tile.key.slotIndex.toString())
                add(tile.sourceTop.toString())
                add(tile.sourceBottom.toString())
                add(tile.rgbaBytes.toString())
            }
        })
    }
}

/** Exact full-body proof bound to the immutable source-relative page plan. */
data class NtkPreGeometryPageArtifact(
    val plan: NtkPreGeometryPagePlan,
    val responseIdentityDigest: String,
    val encodedSha256: String,
    val encodedLength: Long,
    val bodyProofDigest: String,
    val artifactDigest: String
) {
    init {
        require(NtkStripDigests.isSha256(responseIdentityDigest))
        require(NtkStripDigests.isSha256(encodedSha256))
        require(encodedLength > 0L)
        require(NtkStripDigests.isSha256(bodyProofDigest))
        require(bodyProofDigest == computedBodyProofDigest)
        require(artifactDigest == computedArtifactDigest)
    }

    val computedBodyProofDigest: String
        get() = computeBodyProofDigest(
            plan = plan,
            responseIdentityDigest = responseIdentityDigest,
            encodedSha256 = encodedSha256,
            encodedLength = encodedLength
        )

    val computedArtifactDigest: String
        get() = computeArtifactDigest(
            plan = plan,
            responseIdentityDigest = responseIdentityDigest,
            encodedSha256 = encodedSha256,
            encodedLength = encodedLength
        )

    companion object {
        fun rootDigest(artifacts: List<NtkPreGeometryPageArtifact>): String =
            NtkStripDigests.sha256Tokens(buildList {
                add("ntk-pregeometry-page-artifact-root-v1")
                artifacts.forEach { add(it.artifactDigest) }
            })

        fun create(
            plan: NtkPreGeometryPagePlan,
            metadata: NtkSourceMetadata,
            proof: NtkEncodedOriginalProof
        ): NtkPreGeometryPageArtifact {
            metadata.requireProductionAuthority()
            proof.requireProductionAuthority(metadata)
            require(plan.manifestRevision == metadata.manifestRevision)
            require(plan.manifestDigest == metadata.manifestDigest)
            require(plan.sourceKey == metadata.strictSourceKey)
            require(plan.metadataBindingDigest == metadata.metadataBindingDigest)
            require(plan.sourceWidth == metadata.sourceWidth &&
                plan.sourceHeight == metadata.sourceHeight)
            val bodyDigest = computeBodyProofDigest(
                plan,
                proof.responseIdentityDigest,
                proof.encodedSha256,
                proof.encodedLength
            )
            return NtkPreGeometryPageArtifact(
                plan = plan,
                responseIdentityDigest = proof.responseIdentityDigest,
                encodedSha256 = proof.encodedSha256,
                encodedLength = proof.encodedLength,
                bodyProofDigest = bodyDigest,
                artifactDigest = computeArtifactDigest(
                    plan,
                    proof.responseIdentityDigest,
                    proof.encodedSha256,
                    proof.encodedLength
                )
            )
        }

        fun computeBodyProofDigest(
            plan: NtkPreGeometryPagePlan,
            responseIdentityDigest: String,
            encodedSha256: String,
            encodedLength: Long
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-encoded-original-proof-v1",
            plan.sourceKey.manifestDigest,
            plan.sourceKey.pageIndex.toString(),
            plan.sourceKey.canonicalAssetDigest,
            plan.metadataBindingDigest,
            responseIdentityDigest,
            encodedSha256,
            encodedLength.toString(),
            plan.sourceWidth.toString(),
            plan.sourceHeight.toString()
        )

        fun computeArtifactDigest(
            plan: NtkPreGeometryPagePlan,
            responseIdentityDigest: String,
            encodedSha256: String,
            encodedLength: Long
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-pregeometry-page-artifact-v1",
            plan.planDigest,
            responseIdentityDigest,
            plan.metadataBindingDigest,
            encodedSha256,
            encodedLength.toString(),
            plan.sourceWidth.toString(),
            plan.sourceHeight.toString()
        )
    }
}

data class NtkEncodedOriginalProof(
    val manifestDigest: String,
    val pageIndex: Int,
    val canonicalAsset: String,
    val responseIdentityDigest: String,
    val metadataBindingDigest: String,
    val encodedSha256: String,
    val encodedLength: Long,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    init {
        require(NtkStripDigests.isSha256(manifestDigest))
        require(pageIndex >= 0)
        require(canonicalAsset.isNotBlank())
        require(responseIdentityDigest.isEmpty() || NtkStripDigests.isSha256(responseIdentityDigest))
        require(metadataBindingDigest.isEmpty() || NtkStripDigests.isSha256(metadataBindingDigest))
        require(NtkStripDigests.isSha256(encodedSha256))
        require(encodedLength > 0L)
        require(sourceWidth > 0 && sourceHeight > 0)
    }

    val strictSourceKey: NtkStrictSourceKey
        get() = NtkStrictSourceKey.create(manifestDigest, pageIndex, canonicalAsset)

    fun isProductionAuthoritativeFor(metadata: NtkSourceMetadata): Boolean {
        if (!metadata.isProductionAuthoritative) return false
        if (strictSourceKey != metadata.strictSourceKey) return false
        if (responseIdentityDigest != metadata.authority.responseIdentityDigest) return false
        if (metadataBindingDigest != metadata.metadataBindingDigest) return false
        if (encodedLength != metadata.authority.encodedLength) return false
        if (sourceWidth != metadata.sourceWidth || sourceHeight != metadata.sourceHeight) return false
        if (metadata.authority.hasFullBodyWitness &&
            (encodedSha256 != metadata.authority.byteWitnessSha256 ||
                encodedLength != metadata.authority.byteWitnessLength)
        ) return false
        return NtkStripDigests.isSha256(responseIdentityDigest) &&
            NtkStripDigests.isSha256(metadataBindingDigest)
    }

    fun requireProductionAuthority(metadata: NtkSourceMetadata): NtkEncodedOriginalProof = apply {
        require(isProductionAuthoritativeFor(metadata)) {
            "Encoded-original proof is not bound to strict source metadata"
        }
    }

    companion object {
        @JvmStatic
        fun createStrict(
            metadata: NtkSourceMetadata,
            encodedSha256: String,
            encodedLength: Long,
            sourceWidth: Int = metadata.sourceWidth,
            sourceHeight: Int = metadata.sourceHeight
        ): NtkEncodedOriginalProof {
            metadata.requireProductionAuthority()
            return NtkEncodedOriginalProof(
                manifestDigest = metadata.manifestDigest,
                pageIndex = metadata.pageIndex,
                canonicalAsset = metadata.canonicalAsset,
                responseIdentityDigest = metadata.authority.responseIdentityDigest,
                metadataBindingDigest = metadata.metadataBindingDigest,
                encodedSha256 = encodedSha256,
                encodedLength = encodedLength,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            ).requireProductionAuthority(metadata)
        }

    }
}

enum class NtkStrictSourceOperationKind {
    PRIMARY_FULL_BODY
}

/** Immutable tag carried by every strict network call and its session telemetry. */
data class NtkStrictSourceCallTag(
    val sessionId: Long,
    val manifestDigest: String,
    val operationId: Long,
    val kind: NtkStrictSourceOperationKind,
    val laneIndex: Int,
    val pageIndex: Int,
    val attemptOrdinal: Int,
    val method: String,
    val rangeStart: Long,
    val rangeEnd: Long
) {
    init {
        require(kind == NtkStrictSourceOperationKind.PRIMARY_FULL_BODY)
        require(sessionId > 0L)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(operationId > 0L)
        require(laneIndex in 0 until NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        require(pageIndex >= 0)
        require(attemptOrdinal in 1..NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS)
        require(method == "GET")
        require(rangeStart == -1L && rangeEnd == -1L)
    }

    val isProductionStrict: Boolean
        get() = sessionId > 0L &&
            NtkStripDigests.isSha256(manifestDigest) &&
            operationId > 0L &&
            laneIndex in 0 until NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS &&
            pageIndex >= 0 &&
            attemptOrdinal in 1..NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS &&
            method == "GET" &&
            rangeStart == -1L && rangeEnd == -1L

    companion object {
        @JvmStatic
        fun strict(
            sessionId: Long,
            manifestDigest: String,
            operationId: Long,
            laneIndex: Int,
            pageIndex: Int,
            attemptOrdinal: Int = 1,
        ): NtkStrictSourceCallTag {
            return NtkStrictSourceCallTag(
                sessionId,
                manifestDigest,
                operationId,
                NtkStrictSourceOperationKind.PRIMARY_FULL_BODY,
                laneIndex,
                pageIndex,
                attemptOrdinal,
                "GET",
                -1L,
                -1L
            )
        }

    }
}

data class NtkQuarantineSourceCallIdentity private constructor(
    val sessionId: Long,
    val discoveryGeneration: Long,
    val planBindingDigest: String,
    val pageIndex: Int,
    val canonicalAssetDigest: String,
    val laneIndex: Int,
    val operationId: Long,
    val method: String,
    val attemptOrdinal: Int,
    val rangeStart: Long,
    val rangeEnd: Long,
    val routeKeyHash: String,
    val callFactoryId: String,
    val effectiveRequestDigest: String,
    val identityDigest: String
) {
    init {
        require(sessionId > 0L && discoveryGeneration > 0L)
        require(NtkStripDigests.isSha256(planBindingDigest))
        require(pageIndex >= 0)
        require(NtkStripDigests.isSha256(canonicalAssetDigest))
        require(laneIndex in 0 until NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        require(operationId > 0L)
        require(method == "GET")
        require(attemptOrdinal == 1)
        require(rangeStart == -1L && rangeEnd == -1L)
        require(NtkStripDigests.isSha256(routeKeyHash))
        require(callFactoryId.isNotBlank())
        require(NtkStripDigests.isSha256(effectiveRequestDigest))
        // The private constructor is reachable only through create(), which computes this digest
        // from the same immutable fields. Re-hashing it here doubled actor-side crypto for every
        // page before the initial physical wave could be submitted.
        require(NtkStripDigests.isSha256(identityDigest))
    }

    val isValid: Boolean
        get() = attemptOrdinal == 1 && method == "GET" &&
            rangeStart == -1L && rangeEnd == -1L

    companion object {
        @JvmStatic
        fun create(
            sessionId: Long,
            discoveryGeneration: Long,
            planBindingDigest: String,
            pageIndex: Int,
            canonicalAsset: String,
            laneIndex: Int,
            operationId: Long,
            routeKeyHash: String,
            callFactoryId: String,
            effectiveRequestDigest: String,
            canonicalAssetDigest: String =
                NtkStripDigests.canonicalAssetDigestSha256(canonicalAsset),
        ): NtkQuarantineSourceCallIdentity {
            require(NtkStripDigests.isSha256(canonicalAssetDigest))
            return NtkQuarantineSourceCallIdentity(
                sessionId,
                discoveryGeneration,
                planBindingDigest,
                pageIndex,
                canonicalAssetDigest,
                laneIndex,
                operationId,
                "GET",
                1,
                -1L,
                -1L,
                routeKeyHash,
                callFactoryId,
                effectiveRequestDigest,
                computeIdentityDigest(
                    sessionId,
                    discoveryGeneration,
                    planBindingDigest,
                    pageIndex,
                    canonicalAssetDigest,
                    laneIndex,
                    operationId,
                    routeKeyHash,
                    callFactoryId,
                    effectiveRequestDigest
                )
            )
        }

        private fun computeIdentityDigest(
            sessionId: Long,
            discoveryGeneration: Long,
            planBindingDigest: String,
            pageIndex: Int,
            canonicalAssetDigest: String,
            laneIndex: Int,
            operationId: Long,
            routeKeyHash: String,
            callFactoryId: String,
            effectiveRequestDigest: String
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-quarantine-source-call-v1",
            sessionId.toString(),
            discoveryGeneration.toString(),
            planBindingDigest,
            pageIndex.toString(),
            canonicalAssetDigest,
            laneIndex.toString(),
            operationId.toString(),
            "GET",
            "1",
            "-1",
            "-1",
            routeKeyHash,
            callFactoryId,
            effectiveRequestDigest
        )
    }
}

data class NtkQuarantineMetadataEvidence(
    val networkCallIdentityDigest: String,
    val canonicalAssetDigest: String,
    val requestUrl: String,
    val finalUrl: String,
    val selectedResponseHeadersDigest: String,
    val responseIdentityDigest: String,
    val strongValidatorDigest: String,
    val encodedLength: Long,
    val byteWitnessSha256: String,
    val byteWitnessLength: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val imageFormat: String
) {
    init {
        require(NtkStripDigests.isSha256(networkCallIdentityDigest))
        require(NtkStripDigests.isSha256(canonicalAssetDigest))
        require(requestUrl.isNotBlank() && finalUrl.isNotBlank())
        require(NtkStripDigests.isSha256(selectedResponseHeadersDigest))
        require(NtkStripDigests.isSha256(responseIdentityDigest))
        require(NtkStripDigests.isSha256(strongValidatorDigest))
        require(encodedLength > 0L)
        require(NtkStripDigests.isSha256(byteWitnessSha256))
        require(byteWitnessLength in 1..encodedLength)
        require(sourceWidth > 0 && sourceHeight > 0)
        require(imageFormat == NtkSourceMetadataAuthority.normalizeImageFormat(imageFormat))
    }
}

data class NtkQuarantinedBody(
    val sealedFile: File,
    val pageIndex: Int,
    val canonicalAsset: String,
    val callIdentity: NtkQuarantineSourceCallIdentity,
    val metadataEvidence: NtkQuarantineMetadataEvidence,
    val encodedSha256: String,
    val encodedLength: Long,
    val consumedToEof: Boolean,
    val writerClosed: Boolean,
    /**
     * A click-owned response may remain in memory until the viewer retires. The bytes have the
     * same EOF, header and SHA-256 evidence as a sealed quarantine file; avoiding a file per page
     * keeps cache publication out of the cold render deadline.
     */
    val encodedBytes: ByteArray? = null
) {
    init {
        require(sealedFile.extension == "sealed")
        require(pageIndex == callIdentity.pageIndex)
        require(NtkStripDigests.canonicalAssetDigestSha256(canonicalAsset) ==
            callIdentity.canonicalAssetDigest)
        require(metadataEvidence.networkCallIdentityDigest == callIdentity.identityDigest)
        require(NtkStripDigests.isSha256(encodedSha256))
        require(encodedLength > 0L)
        require(
            (encodedBytes != null && encodedBytes.size.toLong() == encodedLength) ||
                (encodedBytes == null && sealedFile.isFile && sealedFile.length() == encodedLength)
        )
        require(consumedToEof && writerClosed)
    }
}

data class NtkQuarantineAdoptionProof(
    val planBindingDigest: String,
    val networkCallIdentityDigest: String,
    val pageIndex: Int,
    val canonicalAssetDigest: String,
    val responseIdentityDigest: String,
    val encodedSha256: String,
    val encodedLength: Long,
    val exactManifestDigest: String,
    val exactManifestProofDigest: String,
    val adoptionDigest: String
) {
    init {
        require(NtkStripDigests.isSha256(planBindingDigest))
        require(NtkStripDigests.isSha256(networkCallIdentityDigest))
        require(pageIndex >= 0)
        require(NtkStripDigests.isSha256(canonicalAssetDigest))
        require(NtkStripDigests.isSha256(responseIdentityDigest))
        require(NtkStripDigests.isSha256(encodedSha256))
        require(encodedLength > 0L)
        require(NtkStripDigests.isSha256(exactManifestDigest))
        require(NtkStripDigests.isSha256(exactManifestProofDigest))
        require(adoptionDigest == computeDigest(
            planBindingDigest,
            networkCallIdentityDigest,
            pageIndex,
            canonicalAssetDigest,
            responseIdentityDigest,
            encodedSha256,
            encodedLength,
            exactManifestDigest,
            exactManifestProofDigest
        ))
    }

    companion object {
        @JvmStatic
        fun create(
            binding: NtkQuarantinePlanBinding,
            body: NtkQuarantinedBody,
            exactManifest: NtkAuthoritativeManifest
        ): NtkQuarantineAdoptionProof {
            val responseIdentity = body.metadataEvidence.responseIdentityDigest
            return NtkQuarantineAdoptionProof(
                binding.bindingDigest,
                body.callIdentity.identityDigest,
                body.pageIndex,
                body.callIdentity.canonicalAssetDigest,
                responseIdentity,
                body.encodedSha256,
                body.encodedLength,
                exactManifest.seal.digestSha256,
                exactManifest.proof.proofDigestSha256,
                computeDigest(
                    binding.bindingDigest,
                    body.callIdentity.identityDigest,
                    body.pageIndex,
                    body.callIdentity.canonicalAssetDigest,
                    responseIdentity,
                    body.encodedSha256,
                    body.encodedLength,
                    exactManifest.seal.digestSha256,
                    exactManifest.proof.proofDigestSha256
                )
            )
        }

        private fun computeDigest(
            planBindingDigest: String,
            networkCallIdentityDigest: String,
            pageIndex: Int,
            canonicalAssetDigest: String,
            responseIdentityDigest: String,
            encodedSha256: String,
            encodedLength: Long,
            exactManifestDigest: String,
            exactManifestProofDigest: String
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-quarantine-adoption-v1",
            planBindingDigest,
            networkCallIdentityDigest,
            pageIndex.toString(),
            canonicalAssetDigest,
            responseIdentityDigest,
            encodedSha256,
            encodedLength.toString(),
            exactManifestDigest,
            exactManifestProofDigest
        )
    }
}

data class NtkQuarantineStartProof(
    val planReservedAtMs: Long,
    val firstSubmittedAtMs: Long,
    val initialWaveSubmittedAtMs: Long,
    val initialWaveCount: Int,
    val submittedOperationCount: Int,
    val physicalCallCountAtProof: Int,
    val duplicatePhysicalCallCount: Int
) {
    init {
        require(planReservedAtMs > 0L)
        require(firstSubmittedAtMs >= planReservedAtMs)
        require(initialWaveSubmittedAtMs >= firstSubmittedAtMs)
        // Every exact body may already belong to the post-click quarantine wave. In that case a
        // second source producer would be a duplicate download, so the correct initial wave is 0.
        require(initialWaveCount >= 0)
        require(submittedOperationCount == initialWaveCount)
        require(physicalCallCountAtProof in 0..submittedOperationCount)
        require(duplicatePhysicalCallCount == 0)
    }
}

data class NtkPromotionToken(
    val episodePath: String,
    val discoveryGeneration: Long,
    val sessionId: Long,
    val planBindingDigest: String,
    val exactManifestDigest: String,
    val exactProofDigest: String,
    val nonce: Long
) {
    init {
        require(episodePath.startsWith('/'))
        require(discoveryGeneration > 0L)
        require(sessionId > 0L)
        require(NtkStripDigests.isSha256(planBindingDigest))
        require(NtkStripDigests.isSha256(exactManifestDigest))
        require(NtkStripDigests.isSha256(exactProofDigest))
        require(nonce > 0L)
    }
}

data class NtkPromotionSnapshot(
    internal val token: NtkPromotionToken,
    val pageCount: Int,
    val completedPageIndexes: Set<Int>,
    val activePageIndexes: Set<Int>,
    val queuedPageIndexes: Set<Int>,
    val physicalCallCount: Int,
    val duplicatePhysicalCallCount: Int
) {
    val promotionNonce: Long
        get() = token.nonce
    val bindingDigest: String
        get() = token.planBindingDigest

    init {
        require(pageCount > 0)
        require(completedPageIndexes.intersect(activePageIndexes).isEmpty())
        require(completedPageIndexes.intersect(queuedPageIndexes).isEmpty())
        require(activePageIndexes.intersect(queuedPageIndexes).isEmpty())
        require(
            completedPageIndexes + activePageIndexes + queuedPageIndexes ==
                (0 until pageCount).toSet()
        )
        // An active lane owns its one-shot operation before that operation reaches
        // the actual Call.Factory boundary. A completed page must have crossed the
        // boundary; an active page may or may not have crossed it at this exact cut.
        require(
            physicalCallCount in completedPageIndexes.size..
                (completedPageIndexes.size + activePageIndexes.size)
        )
        require(duplicatePhysicalCallCount == 0)
    }
}

sealed interface SourceEvent {
    data class MetadataReady(val metadata: NtkSourceMetadata) : SourceEvent
    /** The exact immutable body proof and its retained-file capability are published together. */
    data class BodyPublished(val descriptor: NtkStrictBodyDescriptor) : SourceEvent
    data class TerminalFailure(
        val pageIndex: Int,
        val phase: NtkSourcePhase,
        val error: Throwable
    ) : SourceEvent {
        init {
            require(pageIndex >= 0)
        }
    }
}

enum class NtkGpuSceneFormat {
    RGBA8_UNORM
}

enum class NtkGpuSceneAdmissionState {
    EMPTY,
    STORAGE_PENDING,
    STORAGE_COMPLETE,
    UPLOAD_PENDING,
    RESIDENT_COMPLETE,
    SEALED,
    FAILED
}

data class NtkGpuSceneCapacityProof(
    val format: NtkGpuSceneFormat,
    val expectedTextureCount: Int,
    val residentTextureCount: Int,
    val expectedLogicalBytes: Long,
    val residentLogicalBytes: Long,
    val sceneDigest: String,
    val lastResourceCompletionNanos: Long,
    val sealFenceCompletionNanos: Long
) {
    val isExact: Boolean
        get() = format == NtkGpuSceneFormat.RGBA8_UNORM &&
            expectedTextureCount > 0 &&
            residentTextureCount == expectedTextureCount &&
            expectedLogicalBytes > 0L &&
            residentLogicalBytes == expectedLogicalBytes &&
            NtkStripDigests.isSha256(sceneDigest) &&
            lastResourceCompletionNanos > 0L &&
            sealFenceCompletionNanos >= lastResourceCompletionNanos
}

data class NtkStageProof(
    val authority: Long,
    val stageNonce: Long,
    val manifestRevision: Long,
    val manifestDigest: String,
    val geometryDigest: String,
    val corridorStartPx: Long,
    val corridorEndPx: Long,
    val sceneVersion: Long,
    val compositionLatchNanos: Long,
    val gpuSceneCapacityProof: NtkGpuSceneCapacityProof
) {
    init {
        require(authority > 0L)
        require(stageNonce > 0L)
        require(manifestRevision >= 0L)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(NtkStripDigests.isSha256(geometryDigest))
        require(corridorStartPx >= 0L && corridorEndPx > corridorStartPx)
        require(sceneVersion > 0L)
        require(compositionLatchNanos > 0L)
        require(gpuSceneCapacityProof.isExact)
    }
}

/** Immutable actor-to-surface publication command. No compatibility layer may mint its leases. */
data class NtkStripTileInstall(
    val authority: Long,
    val key: NtkStripTileKey,
    val resourceRevision: Long,
    val installLease: Long,
    val rgbaBytes: Long,
    val tile: ReaderTile,
    val proof: ReaderPreparedStore.PreparedOriginalProof,
    /** EGL share-group lifetime. A callback from another surface epoch is stale. */
    val surfaceEpoch: Long = 1L,
    /** Immutable decode/publication commitment that owns this install. */
    val admissionId: Long = resourceRevision
) {
    init {
        require(authority > 0L && key.episode.value == authority)
        require(resourceRevision > 0L && installLease > 0L && rgbaBytes > 0L)
        require(surfaceEpoch > 0L && admissionId > 0L)
    }
}

data class NtkStripTileResidentAck(
    val key: NtkStripTileKey,
    val resourceRevision: Long,
    val installLease: Long,
    val rgbaBytes: Long,
    val sceneVersion: Long,
    val success: Boolean,
    val surfaceEpoch: Long = 1L,
    val admissionId: Long = resourceRevision
) {
    init {
        require(resourceRevision > 0L && installLease > 0L && rgbaBytes > 0L)
        require(surfaceEpoch > 0L && admissionId > 0L)
        require(!success || sceneVersion > 0L)
    }
}

/** Stable semantic direction. JNI uses [toNativeValue], never this enum's ordinal. */
enum class NtkScrollDirection {
    FORWARD,
    BACKWARD
}

/** Stable JNI representation; never use enum ordinal across the native boundary. */
fun NtkScrollDirection.toNativeValue(): Int = when (this) {
    NtkScrollDirection.FORWARD -> 1
    NtkScrollDirection.BACKWARD -> -1
}

/**
 * Latest immutable protection authority installed by the native render loop before retirement.
 * [protectedTileOrdinals] is copied so callers cannot mutate a committed policy in place.
 */
class NtkStripProtectionCommit(
    val authority: Long,
    val surfaceEpoch: Long,
    val demandEpoch: Long,
    val basisFrameSequence: Long,
    val basisInputSequence: Long,
    val direction: NtkScrollDirection,
    protectedTileOrdinals: IntArray,
    val protectedDigest: String
) {
    private val protectedOrdinals: IntArray = protectedTileOrdinals.copyOf()
    val protectedTileOrdinals: IntArray get() = protectedOrdinals.copyOf()

    init {
        require(authority > 0L)
        require(surfaceEpoch > 0L)
        require(demandEpoch >= 0L)
        require(basisFrameSequence >= 0L)
        require(basisInputSequence >= 0L)
        require(protectedOrdinals.all { it >= 0 })
        require(protectedOrdinals.contentEquals(protectedOrdinals.distinct().sorted().toIntArray())) {
            "Protected tile ordinals must be unique and sorted"
        }
        require(NtkStripDigests.isSha256(protectedDigest))
    }

    override fun equals(other: Any?): Boolean = other is NtkStripProtectionCommit &&
        authority == other.authority &&
        surfaceEpoch == other.surfaceEpoch &&
        demandEpoch == other.demandEpoch &&
        basisFrameSequence == other.basisFrameSequence &&
        basisInputSequence == other.basisInputSequence &&
        direction == other.direction &&
        protectedOrdinals.contentEquals(other.protectedOrdinals) &&
        protectedDigest == other.protectedDigest

    override fun hashCode(): Int {
        var result = authority.hashCode()
        result = 31 * result + surfaceEpoch.hashCode()
        result = 31 * result + demandEpoch.hashCode()
        result = 31 * result + basisFrameSequence.hashCode()
        result = 31 * result + basisInputSequence.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + protectedOrdinals.contentHashCode()
        result = 31 * result + protectedDigest.hashCode()
        return result
    }

    /**
     * The geometry-binding seed is sequence zero because native is forbidden to submit a window
     * buffer before the authoritative stage frame. That first real frame advances the viewport
     * sequence without admitting input, demand, retirement, or another resource mutation. A
     * protection commit based on the seed therefore remains the exact full-scene protection
     * proof only across this one typed transition; every other basis mismatch remains fatal.
     */
    internal fun provesBindingSeedToSealedStageContinuity(
        viewport: NtkViewportSample?,
        stageProof: NtkStageProof?,
        expectedSurfaceEpoch: Long,
        expectedDemandEpoch: Long,
        expectedInitialTopPx: Long,
        expectedDirection: NtkScrollDirection,
        expectedTileCount: Int
    ): Boolean {
        val sample = viewport ?: return false
        val seal = stageProof ?: return false
        val presented = sample.presentedProof ?: return false
        val allOrdinals = IntArray(expectedTileCount) { it }
        return expectedSurfaceEpoch > 0L && expectedDemandEpoch >= 0L &&
            expectedInitialTopPx >= 0L && expectedTileCount > 0 &&
            authority == seal.authority && surfaceEpoch == expectedSurfaceEpoch &&
            demandEpoch == expectedDemandEpoch && direction == expectedDirection &&
            protectedOrdinals.contentEquals(allOrdinals) &&
            basisFrameSequence == NtkViewportSample.BINDING_SEED_SEQUENCE &&
            basisInputSequence == 0L &&
            sample.surfaceEpoch == expectedSurfaceEpoch && sample.frameSequence == 1L &&
            sample.gestureId == 0L && sample.appliedInputSequence == 0L &&
            sample.topPx == expectedInitialTopPx && sample.velocityPxPerSecond == 0f &&
            sample.predictedStopPx == expectedInitialTopPx &&
            presented.authority == authority && presented.frameSequence == sample.frameSequence &&
            presented.sceneVersion == seal.sceneVersion &&
            presented.viewportOriginalComplete && presented.runwayOriginalComplete &&
            presented.firstVisibleGapPx == -1L &&
            presented.visibleContentStartPx == sample.topPx &&
            presented.residentContinuousStartPx == seal.corridorStartPx &&
            presented.residentContinuousEndPx == seal.corridorEndPx
    }
}

data class NtkStripProtectionAck(
    val commit: NtkStripProtectionCommit,
    val sceneVersion: Long,
    val success: Boolean
) {
    init {
        // Protection authority is valid before the first tile creates scene version 1.
        require(sceneVersion >= 0L)
    }
}

data class NtkStripRetireIntent(
    val authority: Long,
    /** Exact surface epoch at which this immutable resource cycle was admitted. */
    val surfaceEpoch: Long,
    /** Current protection-policy Surface epoch; never rewrites [surfaceEpoch]. */
    val policySurfaceEpoch: Long = surfaceEpoch,
    val demandEpoch: Long,
    val basisFrameSequence: Long,
    val basisInputSequence: Long,
    val key: NtkStripTileKey,
    val resourceRevision: Long,
    val installLease: Long,
    val retireLease: Long,
    val rgbaBytes: Long,
    val protectedDigest: String
) {
    init {
        require(authority > 0L && key.episode.value == authority)
        require(surfaceEpoch > 0L)
        require(policySurfaceEpoch > 0L)
        require(demandEpoch >= 0L)
        require(basisFrameSequence >= 0L && basisInputSequence >= 0L)
        require(resourceRevision > 0L && installLease > 0L && retireLease > 0L)
        require(rgbaBytes > 0L)
        require(NtkStripDigests.isSha256(protectedDigest))
    }
}

enum class NtkStripRetireResultCode {
    DETACHED,
    STALE_POLICY,
    PROTECTED,
    VISIBLE_OR_RUNWAY,
    NOT_RESIDENT,
    FAILED
}

data class NtkStripRetireResult(
    val intent: NtkStripRetireIntent,
    val result: NtkStripRetireResultCode,
    val sceneVersion: Long,
    val retireFenceSerial: Long = 0L
) {
    init {
        require(sceneVersion >= 0L)
        if (result == NtkStripRetireResultCode.DETACHED) {
            require(sceneVersion > 0L && retireFenceSerial > 0L)
        } else {
            require(retireFenceSerial == 0L)
        }
    }
}

/** Surface/engine callback name; the immutable value is the retire result itself. */
typealias NtkStripRetireResultAck = NtkStripRetireResult

enum class NtkStripPhase {
    BINDING,
    PRE_STAGE,
    STAGE_QUIESCING,
    STAGED,
    ACTIVE,
    RETIRING,
    FAILED
}

enum class NtkRunwayContractState {
    PROVABLE_UNDER_MEASURED_ENVELOPE,
    AT_RISK,
    UNPROVABLE_SOURCE,
    UNSATISFIABLE_CPU_TRANSIENT,
    GPU_SCENE_ALLOCATION_FAILED
}

enum class NtkTileLifecycleState {
    ABSENT,
    ADMITTED,
    LEASED,
    DECODING,
    CPU_READY,
    UPLOADING,
    RESIDENT,
    RETIRE_PENDING,
    DETACHED_FENCE_PENDING,
    FAILED
}

/** Immutable dense bit mask indexed by [NtkStripGeometry.tileOrdinal]. */
class NtkTileMask private constructor(
    val tileCount: Int,
    words: LongArray
) : Iterable<Int> {
    private val words: LongArray = words.copyOf()

    val size: Int by lazy(LazyThreadSafetyMode.NONE) { this.words.sumOf(java.lang.Long::bitCount) }
    val isEmpty: Boolean get() = words.all { it == 0L }

    init {
        require(tileCount >= 0)
        require(this.words.size == wordCount(tileCount))
        if (tileCount != 0 && tileCount % Long.SIZE_BITS != 0) {
            val validBits = tileCount % Long.SIZE_BITS
            require(this.words.last() ushr validBits == 0L) { "Mask contains out-of-range ordinals" }
        }
    }

    operator fun contains(ordinal: Int): Boolean = ordinal in 0 until tileCount &&
        words[ordinal / Long.SIZE_BITS] and (1L shl (ordinal % Long.SIZE_BITS)) != 0L

    fun union(other: NtkTileMask): NtkTileMask = combine(other) { left, right -> left or right }
    fun intersect(other: NtkTileMask): NtkTileMask = combine(other) { left, right -> left and right }
    fun subtract(other: NtkTileMask): NtkTileMask = combine(other) { left, right -> left and right.inv() }
    fun intersects(other: NtkTileMask): Boolean {
        requireCompatible(other)
        return words.indices.any { words[it] and other.words[it] != 0L }
    }

    fun isSubsetOf(other: NtkTileMask): Boolean {
        requireCompatible(other)
        return words.indices.all { words[it] and other.words[it].inv() == 0L }
    }

    fun toIntArray(): IntArray {
        val result = IntArray(size)
        var output = 0
        for (ordinal in 0 until tileCount) if (ordinal in this) result[output++] = ordinal
        return result
    }

    override fun iterator(): Iterator<Int> = toIntArray().iterator()

    override fun equals(other: Any?): Boolean = other is NtkTileMask &&
        tileCount == other.tileCount && words.contentEquals(other.words)

    override fun hashCode(): Int = 31 * tileCount + words.contentHashCode()
    override fun toString(): String = "NtkTileMask(tileCount=$tileCount, ordinals=${toIntArray().contentToString()})"

    private inline fun combine(other: NtkTileMask, operation: (Long, Long) -> Long): NtkTileMask {
        requireCompatible(other)
        return NtkTileMask(tileCount, LongArray(words.size) { operation(words[it], other.words[it]) })
    }

    private fun requireCompatible(other: NtkTileMask) {
        require(tileCount == other.tileCount) { "Dense masks belong to different geometries" }
    }

    companion object {
        @JvmStatic
        fun empty(tileCount: Int): NtkTileMask = NtkTileMask(tileCount, LongArray(wordCount(tileCount)))

        @JvmStatic
        fun of(tileCount: Int, ordinals: Iterable<Int>): NtkTileMask {
            val words = LongArray(wordCount(tileCount))
            for (ordinal in ordinals) {
                require(ordinal in 0 until tileCount)
                words[ordinal / Long.SIZE_BITS] = words[ordinal / Long.SIZE_BITS] or
                    (1L shl (ordinal % Long.SIZE_BITS))
            }
            return NtkTileMask(tileCount, words)
        }

        private fun wordCount(tileCount: Int): Int {
            require(tileCount >= 0)
            return if (tileCount == 0) 0 else (tileCount - 1) / Long.SIZE_BITS + 1
        }
    }
}

sealed interface NtkDirectionPhase {
    data object Unset : NtkDirectionPhase
    data class Stable(val direction: NtkScrollDirection) : NtkDirectionPhase
    data class ReversalBridge(
        val from: NtkScrollDirection,
        val to: NtkScrollDirection,
        val previousHard: NtkTileMask,
        val enteredAtFrame: Long
    ) : NtkDirectionPhase {
        init {
            require(from != to)
            require(enteredAtFrame > 0L)
        }
    }
}

data class NtkViewportSample(
    val surfaceEpoch: Long,
    val frameSequence: Long,
    val gestureId: Long,
    val appliedInputSequence: Long,
    val topPx: Long,
    val velocityPxPerSecond: Float,
    val predictedStopPx: Long,
    val presentedProof: NtkPresentedFrameProof? = null
) {
    init {
        require(surfaceEpoch > 0L && frameSequence >= 0L)
        require(gestureId >= 0L && appliedInputSequence >= 0L)
        require(topPx >= 0L && predictedStopPx >= 0L)
        require(velocityPxPerSecond.isFinite())
        require(presentedProof == null || presentedProof.frameSequence == frameSequence)
        if (frameSequence == BINDING_SEED_SEQUENCE) {
            // Native deliberately performs no window swap or frame-ID reservation before the
            // authoritative stage swap. Sequence zero is therefore geometry-bind state, never a
            // presented frame or synthetic physical input. Real native samples remain > 0.
            require(gestureId == 0L && appliedInputSequence == 0L)
            require(velocityPxPerSecond == 0f && predictedStopPx == topPx)
            require(presentedProof == null)
        }
    }

    val isBindingSeed: Boolean
        get() = frameSequence == BINDING_SEED_SEQUENCE

    companion object {
        const val BINDING_SEED_SEQUENCE = 0L

        fun bindingSeed(surfaceEpoch: Long, topPx: Long): NtkViewportSample =
            NtkViewportSample(
                surfaceEpoch = surfaceEpoch,
                frameSequence = BINDING_SEED_SEQUENCE,
                gestureId = 0L,
                appliedInputSequence = 0L,
                topPx = topPx,
                velocityPxPerSecond = 0f,
                predictedStopPx = topPx,
                presentedProof = null
            )
    }
}

data class NtkDemandFingerprint(
    val phase: NtkStripPhase,
    val directionTag: String,
    val hardMask: NtkTileMask,
    val softMask: NtkTileMask,
    val digestSha256: String
) {
    init {
        require(directionTag.isNotBlank())
        require(hardMask.tileCount == softMask.tileCount)
        require(!hardMask.intersects(softMask))
        require(NtkStripDigests.isSha256(digestSha256))
    }
}

data class NtkStripTileFreedAck(
    val authority: Long,
    val surfaceEpoch: Long,
    val demandEpoch: Long,
    val admissionId: Long,
    val key: NtkStripTileKey,
    val resourceRevision: Long,
    val installLease: Long,
    val retireLease: Long,
    val rgbaBytes: Long,
    val protectedDigest: String,
    val freedNanos: Long,
    val success: Boolean
) {
    init {
        require(authority > 0L && key.episode.value == authority)
        require(surfaceEpoch > 0L && demandEpoch >= 0L && admissionId > 0L)
        require(resourceRevision > 0L && installLease > 0L && retireLease > 0L && rgbaBytes > 0L)
        require(NtkStripDigests.isSha256(protectedDigest))
        require(!success || freedNanos > 0L)
    }
}

object NtkRollingResidencyConstants {
    const val PRE_STAGE_DECODE_CONCURRENCY = 3
    /** One immutable region per worker: three permits must mean three real decoder tasks. */
    const val MAX_TILES_PER_DECODE_BATCH = 1
    /** Decoded CPU publications may fill every pre-stage decoder slot. */
    const val MAX_DECODED_TILES_IN_FLIGHT = PRE_STAGE_DECODE_CONCURRENCY
    /** Native scene mutation remains serialized even while CPU decode runs three-wide. */
    const val MAX_NATIVE_UPLOADS_IN_FLIGHT = 1
    const val MIN_SERVICE_HORIZON_MS = 250L
    const val URGENT_SERVICE_SAMPLE_CAPACITY = 64
    const val CPU_DECODED_ABSOLUTE_CAP_BYTES = 32L * 1024L * 1024L
}

data class NtkCpuTransientBudget(
    val policyBytes: Long,
    val usableBytes: Long,
    val transientHardCapBytes: Long,
    val minimumDecodeWindowBytes: Long,
    val largestTileRgbaBytes: Long
) {
    init {
        require(policyBytes > 0L)
        require(usableBytes == policyBytes * 9L / 10L)
        require(transientHardCapBytes >= 0L)
        require(minimumDecodeWindowBytes >= 0L)
        require(largestTileRgbaBytes > 0L)
        require(minimumDecodeWindowBytes <= transientHardCapBytes) {
            "UNSATISFIABLE_CPU_TRANSIENT"
        }
    }

    companion object {
        @JvmStatic
        fun create(policyBytes: Long, largestTileRgbaBytes: Long): NtkCpuTransientBudget {
            require(policyBytes > 0L)
            require(largestTileRgbaBytes > 0L)
            val usable = policyBytes * 9L / 10L
            val hardCap = minOf(
                NtkRollingResidencyConstants.CPU_DECODED_ABSOLUTE_CAP_BYTES,
                usable / 4L
            )
            val minimumWindow = Math.multiplyExact(
                NtkRollingResidencyConstants.PRE_STAGE_DECODE_CONCURRENCY.toLong(),
                largestTileRgbaBytes
            )
            require(minimumWindow <= hardCap) {
                "UNSATISFIABLE_CPU_TRANSIENT"
            }
            return NtkCpuTransientBudget(
                policyBytes = policyBytes,
                usableBytes = usable,
                transientHardCapBytes = hardCap,
                minimumDecodeWindowBytes = minimumWindow,
                largestTileRgbaBytes = largestTileRgbaBytes
            )
        }
    }
}

data class NtkResidencyAccounting(
    val cpuReservedBytes: Long = 0L,
    val cpuDecodedBytes: Long = 0L,
    val gpuUploadReservedBytes: Long = 0L,
    val gpuResidentBytes: Long = 0L,
    val gpuRetirePendingBytes: Long = 0L
) {
    init {
        require(cpuReservedBytes >= 0L)
        require(cpuDecodedBytes >= 0L)
        require(gpuUploadReservedBytes >= 0L)
        require(gpuResidentBytes >= 0L)
        require(gpuRetirePendingBytes >= 0L)
    }

    val cpuChargedBytes: Long
        get() = Math.addExact(cpuReservedBytes, cpuDecodedBytes)

    val gpuLogicalBytes: Long
        get() = listOf(
            gpuUploadReservedBytes,
            gpuResidentBytes,
            gpuRetirePendingBytes
        ).fold(0L, Math::addExact)

    fun isCpuWithin(budget: NtkCpuTransientBudget): Boolean =
        cpuChargedBytes <= budget.transientHardCapBytes

    /** CPU admission is independent of the GPU logical inventory recorded for publication. */
    fun reserveDecode(tileRgbaBytes: Long, budget: NtkCpuTransientBudget): NtkResidencyAccounting? {
        require(tileRgbaBytes > 0L)
        val nextCpu = Math.addExact(cpuChargedBytes, tileRgbaBytes)
        if (nextCpu > budget.transientHardCapBytes) return null
        return copy(
            cpuReservedBytes = Math.addExact(cpuReservedBytes, tileRgbaBytes),
            gpuUploadReservedBytes = Math.addExact(gpuUploadReservedBytes, tileRgbaBytes)
        )
    }

    fun decoderCompleted(tileRgbaBytes: Long): NtkResidencyAccounting {
        require(tileRgbaBytes > 0L && cpuReservedBytes >= tileRgbaBytes)
        return copy(
            cpuReservedBytes = cpuReservedBytes - tileRgbaBytes,
            cpuDecodedBytes = Math.addExact(cpuDecodedBytes, tileRgbaBytes)
        )
    }

    fun decodeFailed(tileRgbaBytes: Long): NtkResidencyAccounting {
        require(tileRgbaBytes > 0L)
        require(cpuReservedBytes >= tileRgbaBytes && gpuUploadReservedBytes >= tileRgbaBytes)
        return copy(
            cpuReservedBytes = cpuReservedBytes - tileRgbaBytes,
            gpuUploadReservedBytes = gpuUploadReservedBytes - tileRgbaBytes
        )
    }

    /** Scene publication owns the GPU copy and recycles the decoded CPU copy. */
    fun publicationAck(tileRgbaBytes: Long): NtkResidencyAccounting {
        require(tileRgbaBytes > 0L)
        require(cpuDecodedBytes >= tileRgbaBytes && gpuUploadReservedBytes >= tileRgbaBytes)
        return copy(
            cpuDecodedBytes = cpuDecodedBytes - tileRgbaBytes,
            gpuUploadReservedBytes = gpuUploadReservedBytes - tileRgbaBytes,
            gpuResidentBytes = Math.addExact(gpuResidentBytes, tileRgbaBytes)
        )
    }

    /** Detach transfers bytes to retire-pending; it deliberately returns no budget. */
    fun detached(tileRgbaBytes: Long): NtkResidencyAccounting {
        require(tileRgbaBytes > 0L && gpuResidentBytes >= tileRgbaBytes)
        return copy(
            gpuResidentBytes = gpuResidentBytes - tileRgbaBytes,
            gpuRetirePendingBytes = Math.addExact(gpuRetirePendingBytes, tileRgbaBytes)
        )
    }

    /** Only the native freed/fence ACK releases GPU accounting. */
    fun freedFenceAck(tileRgbaBytes: Long): NtkResidencyAccounting {
        require(tileRgbaBytes > 0L && gpuRetirePendingBytes >= tileRgbaBytes)
        return copy(gpuRetirePendingBytes = gpuRetirePendingBytes - tileRgbaBytes)
    }
}

data class NtkResidentTile(
    val key: NtkStripTileKey,
    val lastPresentedFrame: Long = Long.MIN_VALUE
)

data class NtkPresentedContentInterval(
    val startPx: Long,
    val endPx: Long
) {
    init {
        require(startPx >= 0L && endPx > startPx)
    }
}

data class NtkPresentedFrameProof(
    val authority: Long,
    val sceneVersion: Long,
    val viewportOriginalComplete: Boolean,
    val runwayOriginalComplete: Boolean,
    val visibleContentStartPx: Long,
    val visibleContentEndPx: Long,
    val firstVisiblePage: Int,
    val lastVisiblePage: Int,
    val firstVisibleGapPx: Long,
    val residentContinuousStartPx: Long,
    val residentContinuousEndPx: Long,
    val frameSequence: Long = sceneVersion
) {
    init {
        require(authority > 0L)
        require(sceneVersion > 0L)
        require(frameSequence > 0L)
        require(visibleContentStartPx >= 0L)
        require(visibleContentEndPx >= visibleContentStartPx)
        require(firstVisiblePage >= 0)
        require(lastVisiblePage >= firstVisiblePage)
        require(firstVisibleGapPx >= -1L)
        require(residentContinuousStartPx >= 0L)
        require(residentContinuousEndPx >= residentContinuousStartPx)
    }
}

data class NtkCurrentResidencySnapshot(
    val residentKeys: Set<NtkStripTileKey>,
    val accounting: NtkResidencyAccounting,
    val continuousStartPx: Long,
    val continuousEndPx: Long
)

/**
 * Immutable qualification evidence joining the active native authority token to the latest
 * renderer-owned frame. This is deliberately separate from page/readiness compatibility
 * projections: a qualification consumer must prove the exact native generation and Surface
 * epoch that produced the sampled GLES frame.
 */
data class NtkNativeAuthorityEvidenceSnapshot(
    val tokenEngineGeneration: Long,
    val tokenAuthorityGeneration: Long,
    val tokenAuthority: Long,
    val tokenManifestRevision: Long,
    val tokenManifestDigest: String,
    val tokenGeometryDigest: String,
    val frameEngineGeneration: Long,
    val frameAuthorityGeneration: Long,
    val frameAuthority: Long,
    val frameSequence: Long,
    val frameSceneVersion: Long,
    val surfaceAttached: Boolean,
    val surfaceEpoch: Long,
    val frameSurfaceEpoch: Long,
    val residentContinuousStartPx: Long,
    val residentContinuousEndPx: Long
) {
    init {
        require(tokenEngineGeneration > 0L)
        require(tokenAuthorityGeneration > 0L)
        require(tokenAuthority > 0L)
        require(tokenManifestRevision >= 0L)
        require(NtkStripDigests.isSha256(tokenManifestDigest))
        require(NtkStripDigests.isSha256(tokenGeometryDigest))
        require(frameEngineGeneration >= 0L)
        require(frameAuthorityGeneration >= 0L)
        require(frameAuthority >= 0L)
        require(frameSequence >= 0L)
        require(frameSceneVersion >= 0L)
        require(surfaceEpoch >= 0L)
        require(frameSurfaceEpoch >= 0L)
        require(residentContinuousStartPx >= 0L)
        require(residentContinuousEndPx >= residentContinuousStartPx)
    }

    val exactTokenFrameMatch: Boolean
        get() = frameEngineGeneration == tokenEngineGeneration &&
            frameAuthorityGeneration == tokenAuthorityGeneration &&
            frameAuthority == tokenAuthority

    val exactSurfaceFrameMatch: Boolean
        get() = surfaceAttached && surfaceEpoch > 0L && frameSurfaceEpoch == surfaceEpoch

    val hasPresentedNativeFrame: Boolean
        get() = frameSequence > 0L && frameSceneVersion > 0L &&
            residentContinuousEndPx > residentContinuousStartPx
}

internal data class NtkNativeAuthorityToken(
    val engineGeneration: Long,
    val authorityGeneration: Long,
    val authority: Long,
    val manifestRevision: Long,
    val manifestDigest: String,
    val geometryDigest: String
) {
    init {
        require(engineGeneration > 0L)
        require(authorityGeneration > 0L)
        require(authority > 0L)
        require(manifestRevision >= 0L)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(NtkStripDigests.isSha256(geometryDigest))
    }
}

/** Canonical cross-language digest for the immutable context-loss authority set. */
internal object NtkRetiredAuthorityDigest {
    internal const val GOLDEN_SHA256 =
        "d3c5d8a03b822d7c181997fd9b1fee466d57e667ae840cec435b0e9c6ad3e727"

    fun compute(tokens: Iterable<NtkNativeAuthorityToken>): String {
        val ordered = tokens.sortedWith(
            compareBy<NtkNativeAuthorityToken> { it.engineGeneration }
                .thenBy { it.authorityGeneration }
                .thenBy { it.authority }
        )
        ordered.zipWithNext().forEach { (left, right) ->
            require(left.engineGeneration != right.engineGeneration ||
                left.authorityGeneration != right.authorityGeneration ||
                left.authority != right.authority
            ) { "Retired authority digest contains a duplicate authority key" }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        ordered.forEach { token ->
            digest.updateI64BigEndian(token.engineGeneration)
            digest.updateI64BigEndian(token.authorityGeneration)
            digest.updateI64BigEndian(token.authority)
            digest.updateI64BigEndian(token.manifestRevision)
            digest.updateUtf8WithU32BigEndianLength(token.manifestDigest)
            digest.updateUtf8WithU32BigEndianLength(token.geometryDigest)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun MessageDigest.updateI64BigEndian(value: Long) {
        for (shift in 56 downTo 0 step 8) update((value ushr shift).toByte())
    }

    private fun MessageDigest.updateUtf8WithU32BigEndianLength(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size.toLong() <= 0xffff_ffffL)
        update((bytes.size ushr 24).toByte())
        update((bytes.size ushr 16).toByte())
        update((bytes.size ushr 8).toByte())
        update(bytes.size.toByte())
        update(bytes)
    }
}

internal enum class NtkNativeDetachDisposition {
    SURFACE_PRESERVED,
    CONTEXT_LOST_RETIRED,
    FAILED
}

/**
 * Exact generation-handoff proof returned by native detach. A successful context-loss result is
 * also the backend lifetime barrier: the old generation owns only immutable CPU proof afterward.
 */
internal data class NtkNativeDetachResult(
    val disposition: NtkNativeDetachDisposition,
    val engineGeneration: Long,
    val surfaceEpoch: Long,
    val backendRetirementSerial: Long,
    val backendRetiredNanos: Long,
    val retiredAuthorityCount: Int,
    val retiredAuthorityDigest: String,
    val retiredBackendRemainingThreadCount: Int,
    val retiredBackendRemainingEglHandleCount: Int,
    val retiredBackendRemainingNativeWindowCount: Int,
    val retiredBackendRemainingSwappyLeaseCount: Int,
    val retiredBackendRemainingJniGlobalRefCount: Int,
    val remainingBitmapGlobalRefCount: Int,
    val remainingNativeCallbackCount: Int
) {
    init {
        require(engineGeneration > 0L)
        require(surfaceEpoch > 0L)
        require(backendRetirementSerial >= 0L)
        require(backendRetiredNanos >= 0L)
        require(listOf(
            retiredAuthorityCount,
            retiredBackendRemainingThreadCount,
            retiredBackendRemainingEglHandleCount,
            retiredBackendRemainingNativeWindowCount,
            retiredBackendRemainingSwappyLeaseCount,
            retiredBackendRemainingJniGlobalRefCount,
            remainingBitmapGlobalRefCount,
            remainingNativeCallbackCount
        ).all { it >= 0 })
        require(retiredAuthorityDigest.isEmpty() ||
            NtkStripDigests.isSha256(retiredAuthorityDigest))
        if (disposition == NtkNativeDetachDisposition.SURFACE_PRESERVED) {
            require(backendRetirementSerial == 0L && backendRetiredNanos == 0L)
            require(retiredAuthorityCount == 0 && retiredAuthorityDigest.isEmpty())
        }
        if (disposition == NtkNativeDetachDisposition.CONTEXT_LOST_RETIRED) {
            require(backendRetirementSerial > 0L && backendRetiredNanos > 0L)
            require(NtkStripDigests.isSha256(retiredAuthorityDigest))
        }
    }

    val resourcesPreserved: Boolean
        get() = disposition == NtkNativeDetachDisposition.SURFACE_PRESERVED

    val hasCompleteRetirementBarrier: Boolean
        get() = disposition == NtkNativeDetachDisposition.CONTEXT_LOST_RETIRED &&
            backendRetirementSerial > 0L && backendRetiredNanos > 0L &&
            retiredBackendRemainingThreadCount == 0 &&
            retiredBackendRemainingEglHandleCount == 0 &&
            retiredBackendRemainingNativeWindowCount == 0 &&
            retiredBackendRemainingSwappyLeaseCount == 0 &&
            retiredBackendRemainingJniGlobalRefCount == 0 &&
            remainingBitmapGlobalRefCount == 0 && remainingNativeCallbackCount == 0
}

internal data class NtkAuthorityReleaseRequest(
    val token: NtkNativeAuthorityToken,
    val reducerSurfaceEpoch: Long,
    val releaseNonce: Long
) {
    init {
        // Zero is the exact detached-preparation scope: no Surface was ever published.
        require(reducerSurfaceEpoch >= 0L)
        require(releaseNonce > 0L)
    }
}

internal enum class NtkPhysicalReleaseDisposition {
    EXPLICIT_DELETE,
    CONTEXT_LOST
}

internal data class NtkNativeAuthorityReleaseAck(
    val request: NtkAuthorityReleaseRequest,
    val disposition: NtkPhysicalReleaseDisposition,
    val admissionCloseSerial: Long,
    val releaseClaimSerial: Long,
    val resourceBarrierSerial: Long,
    val resourceCompletionWatermark: Long,
    val feedbackBarrierSerial: Long,
    val capturedResourceCount: Int,
    val capturedRgbaBytes: Long,
    val capturedResourceDigest: String,
    val releasedResourceCount: Int,
    val releasedRgbaBytes: Long,
    val releasedResourceDigest: String,
    val deletedTextureCount: Int,
    val deletedFenceCount: Int,
    val releasedBitmapGlobalRefCount: Int,
    val drainedUploadCount: Int,
    val drainedRetireCount: Int,
    val remainingCommandCount: Int,
    val remainingResourceCount: Int,
    val remainingRgbaBytes: Long,
    val remainingFenceCount: Int,
    val remainingBitmapGlobalRefCount: Int,
    val remainingNativeCallbackCount: Int,
    val backendRetirementSerial: Long,
    val backendRetiredNanos: Long,
    val retiredBackendRemainingThreadCount: Int,
    val retiredBackendRemainingEglHandleCount: Int,
    val retiredBackendRemainingNativeWindowCount: Int,
    val retiredBackendRemainingSwappyLeaseCount: Int,
    val retiredBackendRemainingJniGlobalRefCount: Int,
    val completedNanos: Long,
    val contextReusable: Boolean,
    val success: Boolean
) {
    init {
        require(listOf(
            admissionCloseSerial,
            releaseClaimSerial,
            resourceBarrierSerial,
            resourceCompletionWatermark,
            feedbackBarrierSerial,
            capturedRgbaBytes,
            releasedRgbaBytes,
            remainingRgbaBytes,
            backendRetirementSerial,
            backendRetiredNanos,
            completedNanos
        ).all { it >= 0L })
        require(listOf(
            capturedResourceCount,
            releasedResourceCount,
            deletedTextureCount,
            deletedFenceCount,
            releasedBitmapGlobalRefCount,
            drainedUploadCount,
            drainedRetireCount,
            remainingCommandCount,
            remainingResourceCount,
            remainingFenceCount,
            remainingBitmapGlobalRefCount,
            remainingNativeCallbackCount,
            retiredBackendRemainingThreadCount,
            retiredBackendRemainingEglHandleCount,
            retiredBackendRemainingNativeWindowCount,
            retiredBackendRemainingSwappyLeaseCount,
            retiredBackendRemainingJniGlobalRefCount
        ).all { it >= 0 })
        require(capturedResourceDigest.isEmpty() ||
            NtkStripDigests.isSha256(capturedResourceDigest))
        require(releasedResourceDigest.isEmpty() ||
            NtkStripDigests.isSha256(releasedResourceDigest))
    }
}

internal data class NtkTerminalPhysicalReleaseProof(
    val nativeAck: NtkNativeAuthorityReleaseAck,
    val remainingKotlinCallbackCount: Int
) {
    init {
        require(remainingKotlinCallbackCount >= 0)
    }
}

internal object NtkTerminalPhysicalReleaseProofValidator {
    fun violation(
        proof: NtkTerminalPhysicalReleaseProof,
        expectedRequest: NtkAuthorityReleaseRequest
    ): String? {
        val ack = proof.nativeAck
        if (ack.request != expectedRequest) return "Terminal release request/token/nonce mismatch"
        if (!ack.success) return "Native authority release ACK was negative"
        if (ack.admissionCloseSerial <= 0L || ack.releaseClaimSerial <= 0L ||
            ack.releaseClaimSerial <= ack.admissionCloseSerial
        ) return "Native authority release claim ordering is invalid"
        if (ack.resourceBarrierSerial <= 0L ||
            ack.resourceCompletionWatermark <= ack.resourceBarrierSerial
        ) return "Native resource completion barrier is incomplete"
        if (ack.feedbackBarrierSerial <= ack.resourceCompletionWatermark) {
            return "Native feedback completion barrier is incomplete"
        }
        if (!NtkStripDigests.isSha256(ack.capturedResourceDigest) ||
            !NtkStripDigests.isSha256(ack.releasedResourceDigest)
        ) return "Native release inventory digest is malformed"
        if (ack.capturedResourceCount != ack.releasedResourceCount ||
            ack.capturedRgbaBytes != ack.releasedRgbaBytes ||
            ack.capturedResourceDigest != ack.releasedResourceDigest
        ) return "Native captured/released inventory does not match"
        if (ack.remainingCommandCount != 0 || ack.remainingResourceCount != 0 ||
            ack.remainingRgbaBytes != 0L || ack.remainingFenceCount != 0 ||
            ack.remainingBitmapGlobalRefCount != 0 || ack.remainingNativeCallbackCount != 0
        ) return "Native authority release retained physical ownership"
        if (ack.retiredBackendRemainingThreadCount != 0 ||
            ack.retiredBackendRemainingEglHandleCount != 0 ||
            ack.retiredBackendRemainingNativeWindowCount != 0 ||
            ack.retiredBackendRemainingSwappyLeaseCount != 0 ||
            ack.retiredBackendRemainingJniGlobalRefCount != 0
        ) return "Native authority release retained retired-backend ownership"
        if (proof.remainingKotlinCallbackCount != 0) {
            return "Kotlin authority callbacks remain after native release"
        }
        if (ack.completedNanos <= 0L) return "Native authority release lacks completion time"
        if (ack.disposition == NtkPhysicalReleaseDisposition.EXPLICIT_DELETE) {
            if (!ack.contextReusable) {
                return "Explicit native release did not preserve the reusable context"
            }
            if (ack.backendRetirementSerial != 0L || ack.backendRetiredNanos != 0L) {
                return "Explicit native release incorrectly claimed backend retirement"
            }
        }
        if (ack.disposition == NtkPhysicalReleaseDisposition.CONTEXT_LOST) {
            if (ack.contextReusable) {
                return "Context-loss release incorrectly claimed a reusable context"
            }
            if (ack.backendRetirementSerial <= 0L || ack.backendRetiredNanos <= 0L) {
                return "Context-loss release lacks detach-time backend retirement proof"
            }
            if (ack.completedNanos != ack.backendRetiredNanos) {
                return "Context-loss completion time was rewritten after backend retirement"
            }
        }
        return null
    }

    fun isValid(
        proof: NtkTerminalPhysicalReleaseProof,
        expectedRequest: NtkAuthorityReleaseRequest
    ): Boolean = violation(proof, expectedRequest) == null
}

data class NtkEpisodeProofSnapshot(
    val manifestRevision: Long,
    val manifestDigest: String,
    val geometryDigest: String,
    val geometryTileCount: Int,
    val contentHeightPx: Long,
    val manifestPages: Int,
    val metadataPages: Int,
    val sourceOriginalProofPages: Int,
    val drawableProofPages: Int,
    val everDecodedTiles: Set<NtkStripTileKey>,
    val everPublishedTiles: Set<NtkStripTileKey>,
    val presentedContentIntervals: List<NtkPresentedContentInterval>,
    val presentedPages: Set<Int>,
    val traversalCommittedPages: Int,
    val traversalMissingPages: Set<Int>,
    val viewportDefectFrames: Long,
    val runwayDefectFrames: Long,
    val preSubmitViewportGap: Long,
    val currentAccounting: NtkResidencyAccounting,
    val peakCpuChargedBytes: Long,
    val peakCpuDecodedBytes: Long,
    val cpuTransientHardCapBytes: Long,
    val gpuSceneCapacityProof: NtkGpuSceneCapacityProof?,
    val exactEpisodeEnd: Boolean,
    val residencyCounters: NtkResidencyCounters = NtkResidencyCounters(),
    val detachedRetireCount: Int = 0,
    val resourceCycleAdmissionCount: Int = 0,
    val resourceCycleReleaseCount: Int = 0,
    val resourceCycleReentryCount: Int = 0,
    val resourceCyclePendingReentryCount: Int = 0,
    val resourceCycleMemoryPressureReleaseCount: Int = 0,
    val resourceCycleContextLossReleaseCount: Int = 0,
    val resourceCycleAuthorityRestartReleaseCount: Int = 0,
    val resourceCycleLedgerDigest: String = "",
    val resourceCycleLedgerValid: Boolean = false,
    val terminalPhysicalReleaseValid: Boolean = false,
    val terminalReleaseEngineGeneration: Long = 0L,
    val terminalReleaseAuthorityGeneration: Long = 0L,
    val terminalReleaseNonce: Long = 0L,
    val terminalReleaseInventoryDigest: String = ""
) {
    init {
        require(manifestRevision >= 0L)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(NtkStripDigests.isSha256(geometryDigest))
        require(geometryTileCount >= 0)
        require(contentHeightPx >= 0L)
        require(manifestPages >= 0)
        require(metadataPages >= 0)
        require(sourceOriginalProofPages >= 0)
        require(drawableProofPages >= 0)
        require(traversalCommittedPages >= 0)
        require(viewportDefectFrames >= 0L && runwayDefectFrames >= 0L)
        require(preSubmitViewportGap >= 0L)
        require(peakCpuChargedBytes >= 0L)
        require(peakCpuDecodedBytes >= 0L && cpuTransientHardCapBytes >= 0L)
        require(listOf(
            detachedRetireCount,
            resourceCycleAdmissionCount,
            resourceCycleReleaseCount,
            resourceCycleReentryCount,
            resourceCyclePendingReentryCount,
            resourceCycleMemoryPressureReleaseCount,
            resourceCycleContextLossReleaseCount,
            resourceCycleAuthorityRestartReleaseCount
        ).all { it >= 0 })
        require(resourceCycleLedgerDigest.isEmpty() ||
            NtkStripDigests.isSha256(resourceCycleLedgerDigest))
        require(terminalReleaseEngineGeneration >= 0L)
        require(terminalReleaseAuthorityGeneration >= 0L)
        require(terminalReleaseNonce >= 0L)
        require(terminalReleaseInventoryDigest.isEmpty() ||
            NtkStripDigests.isSha256(terminalReleaseInventoryDigest))
        if (terminalPhysicalReleaseValid) {
            require(terminalReleaseEngineGeneration > 0L)
            require(terminalReleaseAuthorityGeneration > 0L)
            require(terminalReleaseNonce > 0L)
            require(NtkStripDigests.isSha256(terminalReleaseInventoryDigest))
        }
    }

    val everPublishedTileCount: Int
        get() = everPublishedTiles.size

    val retirePendingBytes: Long
        get() = currentAccounting.gpuRetirePendingBytes

    val mergedPresentedContentIntervals: List<NtkPresentedContentInterval>
        get() = mergePresentedIntervals(presentedContentIntervals)

    val presentedContentCoveragePx: Long
        get() = mergedPresentedContentIntervals.fold(0L) { total, interval ->
            Math.addExact(total, interval.endPx - interval.startPx)
        }

    val coversWholeContent: Boolean
        get() = contentHeightPx > 0L && mergedPresentedContentIntervals.let { merged ->
            merged.size == 1 && merged[0].startPx == 0L && merged[0].endPx >= contentHeightPx
        }

    val isTerminallyValid: Boolean
        get() = manifestPages > 0 &&
            metadataPages == manifestPages &&
            sourceOriginalProofPages == manifestPages &&
            drawableProofPages == manifestPages &&
            geometryTileCount > 0 &&
            everPublishedTileCount == geometryTileCount &&
            coversWholeContent &&
            traversalCommittedPages == manifestPages &&
            traversalMissingPages.isEmpty() &&
            viewportDefectFrames == 0L &&
            runwayDefectFrames == 0L &&
            preSubmitViewportGap == 0L &&
            peakCpuChargedBytes <= cpuTransientHardCapBytes &&
            peakCpuDecodedBytes <= cpuTransientHardCapBytes &&
            gpuSceneCapacityProof?.isExact == true &&
            gpuSceneCapacityProof.residentLogicalBytes == currentAccounting.gpuResidentBytes &&
            currentAccounting.gpuRetirePendingBytes == 0L &&
            detachedRetireCount == 0 &&
            currentAccounting.cpuChargedBytes <= cpuTransientHardCapBytes &&
            resourceCycleAdmissionCount == geometryTileCount &&
            residencyCounters.hardAdmissions == geometryTileCount.toLong() &&
            residencyCounters.softAdmissions == 0L &&
            resourceCycleReleaseCount == 0 &&
            resourceCycleMemoryPressureReleaseCount == 0 &&
            resourceCycleContextLossReleaseCount == 0 &&
            resourceCycleAuthorityRestartReleaseCount == 0 &&
            resourceCycleReentryCount == 0 &&
            resourceCyclePendingReentryCount == 0 &&
            NtkStripDigests.isSha256(resourceCycleLedgerDigest) &&
            resourceCycleLedgerValid &&
            residencyCounters.viewportDelivered <= residencyCounters.viewportOffers &&
            residencyCounters.viewportOffers == residencyCounters.viewportDelivered +
                residencyCounters.viewportCoalesced &&
            residencyCounters.resourceCallbackDemandAdvances == 0L &&
            residencyCounters.sourceDemandOffers == residencyCounters.demandFingerprintChanges &&
            residencyCounters.sourceDemandMailboxMaxDepth <= 1 &&
            residencyCounters.admissionDemandCancellations == 0L &&
            residencyCounters.duplicateAdmissions == 0L &&
            residencyCounters.decodeActiveMaxPreStage <=
                NtkRollingResidencyConstants.PRE_STAGE_DECODE_CONCURRENCY &&
            residencyCounters.decodeActiveMaxPostStage == 0 &&
            residencyCounters.postStageNormalPriorityRegions == 0L &&
            residencyCounters.nativeUploadMax <=
                NtkRollingResidencyConstants.MAX_NATIVE_UPLOADS_IN_FLIGHT &&
            residencyCounters.retireOutstandingMax == 0 &&
            residencyCounters.retireAtZeroShortage == 0L &&
            residencyCounters.retireRequested == 0L &&
            residencyCounters.retireProtectedRequested == 0L &&
            residencyCounters.retireVisibleRequested == 0L &&
            residencyCounters.retireAccepted + residencyCounters.retireVetoes ==
                residencyCounters.retireRequested &&
            residencyCounters.leaseReopenWhileRefPositive == 0L &&
            residencyCounters.staleCompletions == 0L &&
            exactEpisodeEnd

    /**
     * Compose the two non-overlapping schema-6 proof authorities without dropping either side:
     * pipeline owns source/resource lifetime; Surface owns actual presentation/traversal.
     */
    fun composeWithSurfacePresentation(
        surface: NtkEpisodeProofSnapshot
    ): NtkEpisodeProofSnapshot? {
        if (surface.manifestRevision != manifestRevision ||
            surface.manifestDigest != manifestDigest ||
            surface.geometryDigest != geometryDigest ||
            surface.manifestPages != manifestPages ||
            surface.geometryTileCount != geometryTileCount ||
            surface.contentHeightPx != contentHeightPx
        ) return null
        return surface.copy(
            metadataPages = metadataPages,
            sourceOriginalProofPages = sourceOriginalProofPages,
            everDecodedTiles = everDecodedTiles,
            currentAccounting = currentAccounting,
            peakCpuChargedBytes = peakCpuChargedBytes,
            peakCpuDecodedBytes = peakCpuDecodedBytes,
            cpuTransientHardCapBytes = cpuTransientHardCapBytes,
            gpuSceneCapacityProof = gpuSceneCapacityProof,
            exactEpisodeEnd = exactEpisodeEnd && surface.exactEpisodeEnd,
            residencyCounters = residencyCounters,
            detachedRetireCount = detachedRetireCount,
            resourceCycleAdmissionCount = resourceCycleAdmissionCount,
            resourceCycleReleaseCount = resourceCycleReleaseCount,
            resourceCycleReentryCount = resourceCycleReentryCount,
            resourceCyclePendingReentryCount = resourceCyclePendingReentryCount,
            resourceCycleMemoryPressureReleaseCount =
                resourceCycleMemoryPressureReleaseCount,
            resourceCycleContextLossReleaseCount = resourceCycleContextLossReleaseCount,
            resourceCycleAuthorityRestartReleaseCount =
                resourceCycleAuthorityRestartReleaseCount,
            resourceCycleLedgerDigest = resourceCycleLedgerDigest,
            resourceCycleLedgerValid = resourceCycleLedgerValid,
            terminalPhysicalReleaseValid = terminalPhysicalReleaseValid,
            terminalReleaseEngineGeneration = terminalReleaseEngineGeneration,
            terminalReleaseAuthorityGeneration = terminalReleaseAuthorityGeneration,
            terminalReleaseNonce = terminalReleaseNonce,
            terminalReleaseInventoryDigest = terminalReleaseInventoryDigest
        )
    }

    companion object {
        @JvmStatic
        fun mergePresentedIntervals(
            intervals: Iterable<NtkPresentedContentInterval>
        ): List<NtkPresentedContentInterval> {
            val sorted = intervals.sortedWith(
                compareBy<NtkPresentedContentInterval> { it.startPx }.thenBy { it.endPx }
            )
            if (sorted.isEmpty()) return emptyList()
            val merged = ArrayList<NtkPresentedContentInterval>()
            var start = sorted[0].startPx
            var end = sorted[0].endPx
            for (index in 1 until sorted.size) {
                val next = sorted[index]
                if (next.startPx <= end) {
                    end = maxOf(end, next.endPx)
                } else {
                    merged += NtkPresentedContentInterval(start, end)
                    start = next.startPx
                    end = next.endPx
                }
            }
            merged += NtkPresentedContentInterval(start, end)
            return merged
        }
    }
}
