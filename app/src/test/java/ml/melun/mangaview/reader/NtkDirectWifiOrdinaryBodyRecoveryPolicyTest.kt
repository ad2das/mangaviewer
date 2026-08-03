package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiOrdinaryBodyRecoveryPolicyTest {
    private data class Admission(
        val hasQuarantineIdentity: Boolean = true,
        val capturedDirectWifiNetworkHandle: Long? = 42L,
        val cellularResilientTransport: Boolean = false,
        val episodePath: String = "/manhwa/22399/3184288",
        val foregroundEpisodePath: String = episodePath,
        val extension: String = "jpg",
        val mixedFormatEpisode: Boolean = false,
        val rangeReplica: Boolean = true,
    ) {
        fun enabled(): Boolean = NtkDirectWifiOrdinaryBodyRecoveryPolicy.shouldEnable(
            hasQuarantineIdentity = hasQuarantineIdentity,
            capturedDirectWifiNetworkHandle = capturedDirectWifiNetworkHandle,
            cellularResilientTransport = cellularResilientTransport,
            episodePath = episodePath,
            foregroundEpisodePath = foregroundEpisodePath,
            extension = extension,
            mixedFormatEpisode = mixedFormatEpisode,
            rangeReplica = rangeReplica,
        )
    }

    @Test
    fun currentDirectWifiQuarantineJpegIsTheOnlyAdmittedShape() {
        assertTrue(Admission().enabled())
        assertTrue(Admission(extension = "JPEG").enabled())

        listOf(
            Admission(hasQuarantineIdentity = false),
            Admission(capturedDirectWifiNetworkHandle = null),
            Admission(cellularResilientTransport = true),
            Admission(episodePath = "/webtoon/22399/3184288"),
            Admission(foregroundEpisodePath = "/manhwa/22399/3184289"),
            Admission(extension = "png"),
            Admission(extension = "webp"),
            Admission(mixedFormatEpisode = true),
            Admission(rangeReplica = false),
        ).forEach { admission -> assertFalse(admission.enabled()) }
    }

    @Test
    fun idleRecoveryHasOneShortContinuationAndTheMeasuredGlobalBound() {
        assertEquals(3_000L, NtkDirectWifiOrdinaryBodyRecoveryPolicy.NO_PROGRESS_MS)
        assertEquals(
            1,
            NtkDirectWifiOrdinaryBodyRecoveryPolicy.MAX_CONTINUATIONS_PER_BODY,
        )
        assertEquals(
            12,
            NtkDirectWifiOrdinaryBodyRecoveryPolicy.MAX_CONCURRENT_CONTINUATIONS,
        )
    }

    @Test
    fun productionWiringKeepsTheTreatmentIdleOnlyAndWifiBound() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        ).readText()
        val wrapStart = source.indexOf("private fun maybeWrapStalledReplicaBody(")
        val wrapEnd = source.indexOf("override fun enqueue(", wrapStart)
        assertTrue(wrapStart >= 0)
        assertTrue(wrapEnd > wrapStart)
        val wrapper = source.substring(wrapStart, wrapEnd)

        assertTrue(wrapper.contains(
            "if (directWifiOrdinaryBody && directWifiIdleSuffixNetwork == null) return response"
        ))
        assertTrue(wrapper.contains("directWifiIdleSuffixNetwork != null -> 0L"))
        assertTrue(wrapper.contains("projectedTailFetcher = if (manhwaBody &&"))
        assertTrue(wrapper.contains("directWifiIdleSuffixNetwork == null &&"))
        assertTrue(wrapper.contains(
            "NtkDirectWifiOrdinaryBodyRecoveryPolicy.MAX_CONTINUATIONS_PER_BODY"
        ))
        assertTrue(wrapper.contains("capturedDirectWifiNetwork = directWifiIdleSuffixNetwork"))
        assertTrue(wrapper.contains("MainApplication.activeNtkForegroundViewerPath()"))

        val bodyStart = source.indexOf("private class NtkStalledReplicaResponseBody(")
        val bodyEnd = source.indexOf("private class NtkReplicaFailoverCallFactory(", bodyStart)
        assertTrue(bodyStart >= 0)
        assertTrue(bodyEnd > bodyStart)
        val body = source.substring(bodyStart, bodyEnd)
        assertTrue(body.contains("if (!hasRequiredDirectWifiNetwork()) return false"))
        assertTrue(body.contains(
            "liveNetwork?.networkHandle == capturedNetwork.networkHandle"
        ))
        assertTrue(body.contains(
            "!MainApplication.isNtkForegroundViewerPath(requiredForegroundEpisodePath)"
        ))
        assertTrue(body.contains("clickOwnedDirectWifiRangeClient(capturedNetwork)"))
        assertTrue(body.contains("header(\"X-MangaViewer-Wifi-Bound\", \"1\")"))
    }

    @Test
    fun explicitCanonicalMissUsesWifiOnlyPngFirstRecoveryAndRetainsCanonicalMirrors() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        ).readText()
        val callStart = source.indexOf("private class NtkReplicaFailoverCall(")
        val callEnd = source.indexOf("private data class NtkManhwaRangeSegment(", callStart)
        assertTrue(callStart >= 0)
        assertTrue(callEnd > callStart)
        val call = source.substring(callStart, callEnd)

        val recovery = call.substringAfter(
            "shouldPrioritizePngAfterCanonicalMiss("
        ).substringBefore("if (\n                        retryableMiss &&\n                        index == attemptCandidates.lastIndex")
        assertTrue(recovery.contains("isDirectWifiClickOwnedOrdinaryManhwaJpeg()"))
        assertTrue(recovery.contains("interleaveExtensions = true"))
        assertTrue(recovery.contains("val earlyPngCandidate = extensionFallbacks.firstOrNull"))
        assertTrue(recovery.contains("val remainingCanonical = attemptCandidates.drop(index + 1)"))
        assertTrue(recovery.contains("attemptCandidates.add(earlyPngCandidate)"))
        assertTrue(recovery.contains("attemptCandidates.addAll(remainingCanonical)"))
        assertTrue(
            recovery.indexOf("attemptCandidates.add(earlyPngCandidate)") <
                recovery.indexOf("attemptCandidates.addAll(remainingCanonical)")
        )
        assertFalse(recovery.contains("attemptCandidates.addAll(extensionFallbacks)"))
        assertTrue(call.contains(
            "filterNot { it.url.toString() in earlyExtensionRecoveryUrls }"
        ))
    }
}
