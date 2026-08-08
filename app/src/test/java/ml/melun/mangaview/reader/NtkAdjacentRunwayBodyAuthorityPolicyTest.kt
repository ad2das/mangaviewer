package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentRunwayBodyAuthorityPolicyTest {
    private val sessionSource = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun exactManifestRejectsGenericCacheWithoutStrictBodyProof() {
        assertFalse(
            NtkAdjacentRunwayBodyAuthorityPolicy.isPublishable(
                strictManifestAuthority = true,
                descriptorReady = false,
                strictBodyReady = false,
                genericCacheReady = true,
            ),
        )
        assertFalse(
            NtkAdjacentRunwayBodyAuthorityPolicy
                .mayConsumeUnprovenPreparedOrGenericCache(strictManifestAuthority = true),
        )
    }

    @Test
    fun exactManhwaRunwayRequiresItsLiveDescriptor() {
        assertTrue(
            NtkAdjacentRunwayBodyAuthorityPolicy.isPublishable(
                strictManifestAuthority = true,
                descriptorReady = true,
                strictBodyReady = false,
                genericCacheReady = false,
            ),
        )
        assertFalse(
            NtkAdjacentRunwayBodyAuthorityPolicy.isPublishable(
                strictManifestAuthority = true,
                descriptorReady = false,
                strictBodyReady = true,
                genericCacheReady = false,
            ),
        )
    }

    @Test
    fun nonProfileStrictBodyMayStillPublish() {
        assertTrue(
            NtkAdjacentRunwayBodyAuthorityPolicy.isPublishable(
                strictManifestAuthority = false,
                descriptorReady = false,
                strictBodyReady = true,
                genericCacheReady = false,
            ),
        )
    }

    @Test
    fun legacyRunwayMayStillUseItsGenericCache() {
        assertTrue(
            NtkAdjacentRunwayBodyAuthorityPolicy.isPublishable(
                strictManifestAuthority = false,
                descriptorReady = false,
                strictBodyReady = false,
                genericCacheReady = true,
            ),
        )
        assertTrue(
            NtkAdjacentRunwayBodyAuthorityPolicy
                .mayConsumeUnprovenPreparedOrGenericCache(strictManifestAuthority = false),
        )
    }

    @Test
    fun exactManhwaAdmissionAndFinalDecodeAreDescriptorOnlyAndEmulatorScoped() {
        val scope = block("private fun requiresStrictAdjacentExactBody(", sessionSource)
        val readiness = block("private fun isAdjacentRunwayRefPublishable(", sessionSource)
        val delivery = block("private fun prepareAdjacentRunwayDelivery(", sessionSource)
        val batch = block("private fun prepareAdjacentRunwayDrawableBatch(", sessionSource)
        val descriptor = block("private fun strictAdjacentBodyDescriptor(", sessionSource)
        val append = block("private fun appendResolvedEpisodeInitialRunway(", sessionSource)
        val initialPreparation = block("private fun prepareInitialTailAdjacentRunway(", sessionSource)

        assertTrue(scope.contains("hostGpuEmulatorRuntime"))
        assertTrue(scope.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(scope.contains("path.startsWith(\"/manhwa/\")"))
        assertTrue(
            readiness.indexOf("if (strictManifestAuthority) return false") <
                readiness.indexOf("strictAdjacentPublishedBody(ref)"),
        )
        assertTrue(delivery.contains("val strictBody = if (strictExactBodyRequired)"))
        assertTrue(delivery.contains("Adjacent exact body has no generation-bound descriptor"))
        assertTrue(batch.contains("requireStrictDescriptor: Boolean = false"))
        assertTrue(batch.contains("requireStrictDescriptor,"))
        assertTrue(batch.contains("Adjacent exact descriptor transport changed"))
        assertTrue(descriptor.contains("ref.manifestDigest == authority.seal.digestSha256"))
        assertTrue(descriptor.contains("ref.manifestPageCount == authority.seal.pageCount"))
        assertTrue(descriptor.contains("ref.canonicalAsset == canonical"))
        assertTrue(append.contains("strictExactInitialManhwaRunwayAuthority(target) != null"))
        assertTrue(append.contains("if (!strictExactDescriptorOnly) return false"))
        assertTrue(
            append.indexOf("if (strictExactDescriptorOnly)") <
                append.indexOf("startAdjacentForegroundStreamsForRefs("),
        )
        assertTrue(append.contains("if (!strictExactDescriptorOnly) {\n                startAdjacentForegroundStreamsForRefs("))
        assertTrue(append.contains("strictExactDescriptorOnly && !isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(
            initialPreparation.indexOf("if (strictExactManhwaLeaseRequired)") <
                initialPreparation.indexOf("prepareAdjacentRunwayDrawableBatch("),
        )
    }

    @Test
    fun exactClaimFailureCannotFallThroughToGenericInitialFetch() {
        val preparation = block("private fun prepareInitialTailAdjacentRunway(", sessionSource)
        val reject = preparation.indexOf(
            "if (exactAuthority != null && strictExactManhwaLeaseRequired",
        )
        val legacyFetch = preparation.indexOf("fetchInitialAdjacentRunwayFile(")
        assertTrue(reject >= 0)
        assertTrue(legacyFetch > reject)
        val rejectionBranch = preparation.substring(reject, legacyFetch)
        assertTrue(rejectionBranch.contains("preparingInitialAdjacentRunways.remove"))
        assertTrue(rejectionBranch.contains("return false"))
    }

    private fun block(signature: String, source: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val brace = source.indexOf('{', start)
        require(brace >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Missing closing brace: $signature")
    }
}
