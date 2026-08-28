package ml.melun.mangaview.reader

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictPublishedBodyPinLifecycleTest {
    @Test
    fun stalePinnedBodyIsNeverAdmittedToTrim() {
        assertTrue(ReaderImageCacheTrimAdmissionPolicy.canDelete(true, 1_000L, 2_000L, 0))
        assertFalse(ReaderImageCacheTrimAdmissionPolicy.canDelete(true, 1_000L, 2_000L, 1))
        assertFalse(ReaderImageCacheTrimAdmissionPolicy.canDelete(true, 3_000L, 2_000L, 0))
        assertFalse(ReaderImageCacheTrimAdmissionPolicy.canDelete(false, 1_000L, 2_000L, 0))
    }

    @Test
    fun acceptedPinsReleaseOnlyAtFinalRetirementAfterBodyLeasesDrain() {
        val lifecycle = NtkStrictPublishedBodyPinLifecycle()
        val firstClosed = AtomicInteger()
        val secondClosed = AtomicInteger()
        lifecycle.retain(0, AutoCloseable { firstClosed.incrementAndGet() })
        lifecycle.retain(1, AutoCloseable { secondClosed.incrementAndGet() })

        try {
            lifecycle.releaseAtFinalRetirement(activeBodyLeaseCount = 1)
            throw AssertionError("An active body lease must block pin retirement")
        } catch (_: IllegalStateException) {
        }
        assertEquals(2, lifecycle.retainedCount())
        assertEquals(0, firstClosed.get())
        assertEquals(0, secondClosed.get())

        assertEquals(2, lifecycle.releaseAtFinalRetirement(activeBodyLeaseCount = 0))
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(1, firstClosed.get())
        assertEquals(1, secondClosed.get())
    }

    @Test
    fun sessionArchitecturePinsBeforePublicationAndReleasesAfterClosedTransition() {
        val source = readRepositoryFile(
            "app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt"
        )
        val accept = functionSlice(source, "private fun acceptExactBody(", "private object LogReady")
        assertOrdered(
            accept,
            "leaseAcceptedStrictPublishedBody(",
            "publishedBodyPins.retain(",
            "page.publishedBody = published",
            "SourceEvent.BodyPublished(descriptor)"
        )
        val openLease = functionSlice(
            source,
            "private fun openBodyLease(",
            "private fun maybeCompletePreparationDrainActor("
        )
        assertOrdered(
            openLease,
            "synchronized(bodyLeaseAdmissionLock)",
            "activeBodyLeaseCount.incrementAndGet()",
            "leaseAcceptedStrictPublishedBody("
        )

        val retirement = functionSlice(
            source,
            "private fun maybeFinishClosedActor()",
            "private fun recordSubmissionActor("
        )
        assertOrdered(
            retirement,
            "activeBodyLeaseCount.get() != 0",
            "publishedBodyPins.releaseAtFinalRetirement(",
            "NtkQuarantineSourceOwnershipRegistry.release(",
            "phase = SessionPhase.Closed"
        )

        val cacheSource = readRepositoryFile(
            "app/src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        )
        val fileLease = functionSlice(
            cacheSource,
            "class FileLease internal constructor(",
            "class Cancellation"
        )
        assertTrue(fileLease.contains("closed.compareAndSet(false, true)"))
        val volatileClear = functionSlice(
            cacheSource,
            "private fun clearVolatileStateInternal(",
            "fun cancelNtkEpisodeVolatile("
        )
        assertFalse(volatileClear.contains("activeReads.clear()"))
        assertFalse(volatileClear.contains("cacheWriteLocks.clear()"))

        val trackedBody = functionSlice(
            cacheSource,
            "private interface NtkRangeContinuationProvenance",
            "private fun cancelActiveNtkEpisodeCallsByKey("
        )
        assertTrue(
            trackedBody.contains(
                ") : ResponseBody(), NtkRangeContinuationProvenance"
            )
        )
        val completionBody = functionSlice(
            cacheSource,
            "private class CompletionResponseBody(",
            "private class NtkH1RecoveryPermitResponseBody("
        )
        assertTrue(
            completionBody.contains(
                ") : ResponseBody(), NtkRangeContinuationProvenance"
            )
        )
        assertTrue(
            completionBody.contains("(delegate as? NtkRangeContinuationProvenance)")
        )
        assertTrue(completionBody.contains("?.usedAnyContinuation() == true"))
        assertTrue(completionBody.contains("override fun source(): BufferedSource = delegateSource"))
        assertTrue(completionBody.contains("fun markVerifiedEof(verifiedBytes: Long)"))
        assertFalse(completionBody.contains("object : ForwardingSource("))
        assertFalse(completionBody.contains("}.buffer()"))

        val customHttp = readRepositoryFile(
            "app/src/main/java/ml/melun/mangaview/mangaview/CustomHttpClient.java"
        )
        val fallbackLifetime = functionSlice(
            customHttp,
            "private Response retainFallbackCallUntilBodyComplete(",
            "private boolean shouldLogNtkExactImageSuccess("
        )
        assertTrue(fallbackLifetime.contains("BufferedSource source = delegate.source()"))
        assertFalse(fallbackLifetime.contains("Okio.buffer("))
        assertFalse(fallbackLifetime.contains("new ForwardingSource("))

        val quicRecovery = functionSlice(
            cacheSource,
            "private fun executeExactQuicImageRecovery(",
            "private fun executeUnknownLengthExactQuicPrefixRange("
        )
        assertOrdered(
            quicRecovery,
            "val recoveryAttempt =",
            "NtkExactImagePhysicalAttempt(recoveryAttempt)",
            ".request(recoveryRequest)"
        )

        val cachedLookup = functionSlice(
            cacheSource,
            "private fun strictCachedPublishedBodyLocked(",
            "private data class StrictEncodedOriginalProofV2("
        )
        assertOrdered(
            cachedLookup,
            "touchCacheHitForLru(file)",
            "return strictPublishedBody(file, metadata, proof)"
        )

        val strictSpool = functionSlice(
            cacheSource,
            "fun spoolStrictPublishedBody(",
            "private fun fileStartsWith("
        )
        assertOrdered(
            strictSpool,
            "val cachedBeforeCall =",
            "strictCachedPublishedBodyLocked(",
            "if (cachedBeforeCall != null)",
            "onMetadata(cachedBeforeCall.metadata)",
            "return cachedBeforeCall",
            "val request =",
            "newTrackedNtkEpisodeCall("
        )
        assertOrdered(
            strictSpool,
            "proof.requireProductionAuthority(acceptedMetadata)",
            "proofReadyAtNs = SystemClock.elapsedRealtimeNanos()",
            "publishedAtNs = SystemClock.elapsedRealtimeNanos()",
            "succeeded = true",
            "onPhysicalBodyProven?.let { sink ->",
            "physicalAttemptOrdinal =",
            "NtkExactImagePhysicalAttempt::class.java",
            "usedRangeContinuation =",
            "usedAnyContinuation() == true"
        )
        assertOrdered(
            strictSpool,
            "bodyEofAtNs = SystemClock.elapsedRealtimeNanos()",
            "bodyDigest = NtkStripDigests.bytesToLowerHex(fullDigest.digest())",
            "call.markVerifiedBodyEof(encodedLength)",
            "succeeded = true"
        )
        assertTrue(
            strictSpool.indexOf("return cachedBeforeCall") <
                strictSpool.indexOf("onPhysicalBodyProven?.let { sink ->")
        )

        val quarantineSpool = functionSlice(
            cacheSource,
            "internal fun spoolQuarantinedEncodedOriginal(",
            "fun predecodeQuarantinedOriginalAsync("
        )
        assertOrdered(
            quarantineSpool,
            "val bodyDigest =",
            "val metadata = NtkQuarantineMetadataEvidence(",
            "metadataSink(metadata)",
            "val completedAtNs = SystemClock.elapsedRealtimeNanos()",
            "onPhysicalBodyProven?.let { sink ->",
            "physicalAttemptOrdinal =",
            "NtkExactImagePhysicalAttempt::class.java",
            "usedRangeContinuation =",
            "usedAnyContinuation() == true",
            "succeeded = true"
        )
        assertTrue(
            quarantineSpool.indexOf("call.markVerifiedBodyEof(encodedLength)") <
                quarantineSpool.indexOf("succeeded = true")
        )

        val adoption = functionSlice(
            cacheSource,
            "fun adoptQuarantinedEncodedOriginal(",
            "/**\n     * Persists a memory-published exact body",
        )
        assertTrue(adoption.contains("val activeReadCount = activeReads[cacheKey]?.get() ?: 0"))
        assertTrue(adoption.contains("existingByteProofBeforeLookup.encodedSha256"))
        assertTrue(adoption.contains("existingEncodedProofBeforeLookup.encodedSha256"))
        assertOrdered(
            adoption,
            "if (identicalLeasedBody)",
            "registerResidentStrictPublishedBody(",
            "return@withCacheWriteLock strictPublishedBody(",
            "retireUnprovedStrictCacheTarget(cacheKey, finalFile)",
        )
    }

    private fun functionSlice(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        check(startIndex >= 0 && endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun assertOrdered(source: String, vararg needles: String) {
        var cursor = -1
        needles.forEach { needle ->
            val next = source.indexOf(needle, cursor + 1)
            assertTrue("Missing or out of order: $needle", next > cursor)
            cursor = next
        }
    }

    private fun readRepositoryFile(relativePath: String): String {
        var cursor = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(cursor, relativePath)
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Repository file not found: $relativePath")
    }
}
