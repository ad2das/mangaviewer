package ml.melun.mangaview.reader

import android.content.Context
import ml.melun.mangaview.mangaview.Manga
import java.net.URI

/**
 * Builds exact source authority only from already parsed immutable evidence.
 *
 * Document-plan and viewer-API JSON parsing deliberately live in [NtkManifestEvidenceParser].
 */
object NtkManifestAuthorityFactory {
    data class TokenBoundDocumentAuthority(
        val plan: NtkProvisionalEpisodePlan,
        val manifest: NtkAuthoritativeManifest,
    )

    private val strongCount = Regex(
        """["'](?:imageCount|imagesCount|totalImages|totalImageCount|pageCount|totalPages|numberOfPages)["']\s*:\s*(\d{1,4})""",
        RegexOption.IGNORE_CASE
    )
    private val absoluteGeneratedAsset = Regex(
        """https://[^\s"'<>]+/[^\s"'<>]*/([A-Za-z_-]*)(\d{1,6})\.(jpg|jpeg|png|webp)(?:[?#][^\s"'<>]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val relativeGeneratedAsset = Regex(
        """/(?:manhwa|webtoon|blacktoon/episodes|black/episodes|wt/episodes)/[^\s"'<>]+/([A-Za-z_-]*)(\d{1,6})\.(jpg|jpeg|png|webp)(?:[?#][^\s"'<>]*)?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Builds the finite numeric-manhwa asset table from the identity already proven by the
     * episode document parser. This is a production route contract, not a work/test allow-list:
     * every numeric manhwa episode must satisfy the same token, component, path, page-order and
     * generated-path invariants before authority can be returned.
     */
    @JvmStatic
    fun createTokenBoundGeneratedManhwaDocumentAuthority(
        lease: NtkDiscoveryLease?,
        draft: NtkEpisodeDocumentPlanDraft?,
    ): TokenBoundDocumentAuthority? {
        if (lease == null || draft == null ||
            draft.normalizedEpisodePath != lease.episodePath ||
            draft.discoveryGeneration != lease.generation.value
        ) return null
        val route = Regex("^/manhwa/(\\d{1,12})/(\\d{1,12})$")
            .matchEntire(draft.normalizedEpisodePath) ?: return null
        val identity = draft.requestIdentity
        if (identity.normalizedSegment != "manhwa" ||
            identity.normalizedEndpointPath != "/api/manhwa-images" ||
            identity.normalizedSourceWorkId != route.groupValues[1] ||
            identity.normalizedEpisodeId != route.groupValues[2]
        ) return null
        val spec = runCatching {
            NtkGeneratedAssetSpec(
                canonicalOrigin = NtkTokenBoundGeneratedManifestProof.CANONICAL_MANHWA_ORIGIN,
                canonicalDirectory =
                    "/manhwa/${identity.normalizedSourceWorkId}/${identity.normalizedEpisodeId}",
                filePrefix = "p",
                firstPageNumber = 1,
                zeroPadWidth = 3,
                extension = "jpg",
                pageCount = draft.pageCount,
            )
        }.getOrNull() ?: return null
        val plan = runCatching {
            draft.bindSpeculativeCandidates(spec.canonicalAssets())
        }.getOrNull() ?: return null
        val seal = runCatching {
            NtkEpisodeManifestSeal.create(
                lease.episodePath,
                lease.generation.value,
                plan.normalizedOrderedCanonicalAssets,
            )
        }.getOrNull() ?: return null
        if (seal.digestSha256 != plan.orderedAssetsDigestSha256) return null
        val proof = runCatching {
            NtkTokenBoundGeneratedManifestProof.create(plan.proof, spec, seal)
        }.getOrNull() ?: return null
        val manifest = runCatching { NtkAuthoritativeManifest(seal, proof) }
            .getOrNull() ?: return null
        return TokenBoundDocumentAuthority(plan, manifest)
    }

    @JvmStatic
    fun createObservedNumericReplicaDocumentAuthority(
        lease: NtkDiscoveryLease?,
        draft: NtkEpisodeDocumentPlanDraft?,
        orderedObservedAssets: List<String>?,
    ): TokenBoundDocumentAuthority? {
        if (lease == null || draft == null || orderedObservedAssets == null ||
            draft.normalizedEpisodePath != lease.episodePath ||
            draft.discoveryGeneration != lease.generation.value ||
            orderedObservedAssets.size != draft.pageCount
        ) return null
        val plan = runCatching { draft.bindSpeculativeCandidates(orderedObservedAssets) }
            .getOrNull() ?: return null
        val seal = runCatching {
            NtkEpisodeManifestSeal.create(
                lease.episodePath,
                lease.generation.value,
                plan.normalizedOrderedCanonicalAssets,
            )
        }.getOrNull() ?: return null
        val proof = runCatching {
            NtkObservedNumericReplicaManifestProof.create(plan.proof, seal)
        }.getOrNull() ?: return null
        return TokenBoundDocumentAuthority(plan, NtkAuthoritativeManifest(seal, proof))
    }

    @JvmStatic
    fun installObservedNumericReplicaDocumentAuthority(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        authority: TokenBoundDocumentAuthority?,
    ): NtkManifestInstallResult {
        if (context == null || manga == null || lease == null || authority == null ||
            authority.manifest.proof !is NtkObservedNumericReplicaManifestProof
        ) return rejectedResponse()
        return NtkSourceSpoolRegistry.promoteDocumentPlanToExact(
            context,
            manga,
            lease,
            authority.plan.proof.proofDigestSha256,
            authority.manifest,
        )
    }

    @JvmStatic
    fun installTokenBoundGeneratedManhwaDocumentAuthority(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        authority: TokenBoundDocumentAuthority?,
    ): NtkManifestInstallResult {
        if (context == null || manga == null || lease == null || authority == null) {
            return rejectedResponse()
        }
        return NtkSourceSpoolRegistry.promoteDocumentPlanToExact(
            context,
            manga,
            lease,
            authority.plan.proof.proofDigestSha256,
            authority.manifest,
        )
    }

    /**
     * The non-API exact-document case remains legal only when the response itself contains the
     * finite count, page-1 canonical asset and complete generator specification.
     */
    @JvmStatic
    fun installExplicitGeneratedEpisodeDocument(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        canonicalRequestUrl: String?,
        canonicalFinalUrl: String?,
        httpStatus: Int,
        responseBody: String?
    ): NtkManifestInstallResult {
        if (context == null || manga == null || lease == null || httpStatus != 200 ||
            canonicalRequestUrl.isNullOrBlank() || canonicalFinalUrl.isNullOrBlank() ||
            responseBody.isNullOrBlank()
        ) return rejectedResponse()
        val body = responseBody
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
        val counts = strongCount.findAll(body)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..1_000 }
            .toSet()
        if (counts.size != 1) return rejectedResponse()
        val pageCount = counts.single()
        val candidate = absoluteGeneratedAsset.find(body)?.value
            ?: relativeGeneratedAsset.find(body)?.value?.let { relative ->
                runCatching { URI(canonicalFinalUrl).resolve(relative).toString() }.getOrNull()
            }
            ?: return rejectedResponse()
        val canonicalExample = candidate.substringBefore('?').substringBefore('#')
        val uri = runCatching { URI(canonicalExample) }.getOrNull() ?: return rejectedResponse()
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return rejectedResponse()
        }
        val fileName = uri.path.substringAfterLast('/')
        val match = Regex(
            "^([A-Za-z_-]*)(\\d{1,6})\\.(jpg|jpeg|png|webp)$",
            RegexOption.IGNORE_CASE
        ).matchEntire(fileName) ?: return rejectedResponse()
        val pageToken = match.groupValues[2]
        val firstPage = pageToken.toIntOrNull() ?: return rejectedResponse()
        if (firstPage != 1) return rejectedResponse()
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val spec = runCatching {
            NtkGeneratedAssetSpec(
                canonicalOrigin = "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port",
                canonicalDirectory = uri.path.substringBeforeLast('/'),
                filePrefix = match.groupValues[1],
                firstPageNumber = firstPage,
                zeroPadWidth = pageToken.length,
                extension = match.groupValues[3].lowercase(),
                pageCount = pageCount
            )
        }.getOrNull() ?: return rejectedResponse()
        val seal = NtkEpisodeManifestSeal.create(
            lease.episodePath,
            lease.generation.value,
            spec.canonicalAssets(),
            pageCount
        )
        val proof = runCatching {
            NtkEpisodeDocumentGeneratedManifestProof.create(
                lease.episodePath,
                lease.generation.value,
                canonicalRequestUrl.trim(),
                canonicalFinalUrl.trim(),
                NtkStripDigests.sha256Tokens("ntk-selected-headers-v1"),
                responseBody.toByteArray(Charsets.UTF_8),
                spec,
                seal
            )
        }.getOrNull() ?: return rejectedResponse()
        return NtkSourceSpoolRegistry.installExplicitExact(
            context,
            manga,
            lease,
            NtkAuthoritativeManifest(seal, proof)
        )
    }

    @JvmStatic
    fun installViewerImageApiEnvelope(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        plan: NtkProvisionalEpisodePlan?,
        envelope: NtkExactViewerImageApiEnvelope?
    ): NtkManifestInstallResult {
        if (context == null || manga == null) return rejectedResponse()
        val manifest = createViewerImageApiManifest(lease, plan, envelope)
            ?: return rejectedResponse()
        return NtkSourceSpoolRegistry.promoteDocumentPlanToExact(
            context,
            manga,
            lease,
            plan?.proof?.proofDigestSha256,
            manifest
        )
    }

    /** Builds immutable exact authority without publishing it, for pre-install cache adoption. */
    @JvmStatic
    fun createViewerImageApiManifest(
        lease: NtkDiscoveryLease?,
        plan: NtkProvisionalEpisodePlan?,
        envelope: NtkExactViewerImageApiEnvelope?
    ): NtkAuthoritativeManifest? {
        if (lease == null || plan == null || envelope == null ||
            plan.proof.normalizedEpisodePath != lease.episodePath ||
            plan.proof.discoveryGeneration != lease.generation.value ||
            envelope.documentPlanProofDigestSha256 != plan.proof.proofDigestSha256 ||
            envelope.viewerImageRequestIdentityDigestSha256 !=
            plan.proof.requestIdentity.identityDigestSha256 ||
            envelope.orderedAssets.size != plan.pageCount ||
            envelope.orderedAssets.map(NtkStripDigests::canonicalAsset) !=
            plan.normalizedOrderedCanonicalAssets ||
            envelope.orderedAssetsDigestSha256 != plan.orderedAssetsDigestSha256 ||
            envelope.response.request !== envelope.request
        ) return null
        val seal = runCatching {
            NtkEpisodeManifestSeal.create(
                lease.episodePath,
                lease.generation.value,
                envelope.orderedAssets
            )
        }.getOrNull() ?: return null
        if (seal.digestSha256 != envelope.orderedAssetsDigestSha256) return null
        val proof = runCatching {
            NtkViewerImageApiManifestProof.create(
                episodePath = lease.episodePath,
                discoveryGeneration = lease.generation.value,
                canonicalRequestUrl = envelope.response.requestUrl,
                canonicalFinalUrl = envelope.response.finalUrl,
                selectedHeadersDigestSha256 = envelope.selectedHeadersDigestSha256,
                requestBody = envelope.request.bodyBytes,
                responseBody = envelope.response.bodyBytes,
                documentPlanProofDigestSha256 = envelope.documentPlanProofDigestSha256,
                viewerImageRequestIdentityDigestSha256 =
                    envelope.viewerImageRequestIdentityDigestSha256,
                responseConsumedToEof = envelope.response.consumedToEof,
                orderedAssetSelectionPolicyVersion =
                    envelope.orderedAssetSelectionPolicyVersion,
                orderedAssets = envelope.orderedAssets,
                seal = seal
            )
        }.getOrNull() ?: return null
        return NtkAuthoritativeManifest(seal, proof)
    }

    @JvmStatic
    fun rejectedResponse(): NtkManifestInstallResult = NtkManifestInstallResult(
        NtkManifestInstallStatus.INVALID_EXACT_PROOF,
        null,
        NtkSourceState.ABSENT
    )
}
