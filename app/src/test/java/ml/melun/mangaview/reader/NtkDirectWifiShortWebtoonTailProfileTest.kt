package ml.melun.mangaview.reader

import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiShortWebtoonTailProfileTest {

    private val manifestDigest = "a".repeat(64)

    private fun assetDigest(pageIndex: Int): String =
        (('0'.code + pageIndex % 10).toChar()).toString().repeat(64)

    private fun freeze(
        gate: NtkDirectWifiShortWebtoonTailPermitGate =
            NtkDirectWifiShortWebtoonTailPermitGate(4),
        episodePath: String = "/webtoon/work/episode",
        pageCount: Int = 8,
        rollingAdmission: Boolean = true,
        directWifiTransport: Boolean = true,
        cellularResilientTransport: Boolean = false,
        currentForegroundViewerGeneration: Long = 41L,
        adjacentPrefetch: Boolean = false,
    ) = NtkDirectWifiShortWebtoonTailProfile.freeze(
        episodePath = episodePath,
        manifestDigest = manifestDigest,
        pageCount = pageCount,
        rollingAdmission = rollingAdmission,
        directWifiTransport = directWifiTransport,
        cellularResilientTransport = cellularResilientTransport,
        currentForegroundViewerGeneration = currentForegroundViewerGeneration,
        adjacentPrefetch = adjacentPrefetch,
        permitGate = gate,
    )

    @Test
    fun freezesOnlyTheCurrentRollingDirectWifiShortWebtoonProfile() {
        assertNotNull(freeze())
        assertNull(freeze(directWifiTransport = false))
        assertNull(freeze(cellularResilientTransport = true))
        assertNull(freeze(currentForegroundViewerGeneration = 0L))
        assertNull(freeze(adjacentPrefetch = true))
        assertNull(freeze(rollingAdmission = false))
        assertNull(freeze(episodePath = "/manhwa/work/episode"))
        assertNull(freeze(pageCount = 1))
        assertNull(freeze(pageCount = 9))
    }

    @Test
    fun pageZeroNeverReceivesATailAndEveryTagIsBoundToOneExactPageIdentity() {
        val profile = requireNotNull(freeze(pageCount = 8))

        assertNull(profile.tagForPage(0, assetDigest(0)))
        assertNull(profile.tagForPage(8, assetDigest(8)))
        assertNull(profile.tagForPage(1, ""))

        val tag = requireNotNull(profile.tagForPage(7, assetDigest(7)))
        assertEquals("/webtoon/work/episode", tag.normalizedEpisodePath)
        assertEquals(manifestDigest, tag.manifestDigest)
        assertEquals(41L, tag.viewerGeneration)
        assertEquals(7, tag.pageIndex)
        assertEquals(8, tag.pageCount)
        assertEquals(assetDigest(7), tag.canonicalAssetDigest)
        assertEquals(1, tag.maximumExtraTailRequests)
    }

    @Test
    fun oneTagCanClaimOnlyOneExtraTailAndTheGlobalGateNeverOvercommits() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(2)
        val profile = requireNotNull(freeze(gate = gate, pageCount = 4))
        val first = requireNotNull(profile.tagForPage(1, assetDigest(1)))
        val second = requireNotNull(profile.tagForPage(2, assetDigest(2)))
        val third = requireNotNull(profile.tagForPage(3, assetDigest(3)))
        val running = AtomicBoolean(false)

        val firstLease = requireNotNull(first.tryAcquireExtraTail(running))
        val secondLease = requireNotNull(second.tryAcquireExtraTail(running))
        assertEquals(0, gate.availablePermitsForTest())
        assertNull(third.tryAcquireExtraTail(running))

        firstLease.close()
        firstLease.close()
        assertEquals(1, gate.availablePermitsForTest())
        val thirdLease = requireNotNull(third.tryAcquireExtraTail(running))
        assertEquals(0, gate.availablePermitsForTest())
        assertNull(first.tryAcquireExtraTail(running))

        secondLease.close()
        thirdLease.close()
        assertEquals(2, gate.availablePermitsForTest())
    }

    @Test
    fun cancelledWorkCannotConsumeTheGlobalTailBudget() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(1)
        val profile = requireNotNull(freeze(gate = gate, pageCount = 3))
        val cancelled = requireNotNull(profile.tagForPage(1, assetDigest(1)))
        val live = requireNotNull(profile.tagForPage(2, assetDigest(2)))

        assertNull(cancelled.tryAcquireExtraTail(AtomicBoolean(true)))
        assertEquals(1, gate.availablePermitsForTest())
        val liveLease = live.tryAcquireExtraTail(AtomicBoolean(false))
        assertNotNull(liveLease)
        assertEquals(0, gate.availablePermitsForTest())
        liveLease?.close()
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun currentLogicalBodyClaimsAndReleasesOnlyOneSuffixExactlyOnce() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(1)
        val profile = requireNotNull(freeze(gate = gate, pageCount = 3))
        val first = requireNotNull(profile.tagForPage(1, assetDigest(1)))
        val second = requireNotNull(profile.tagForPage(2, assetDigest(2)))
        val running = AtomicBoolean(false)

        assertNull(first.tryAcquireExtraTails(4, running))
        val group = requireNotNull(first.tryAcquireExtraTails(1, running))
        assertEquals(0, gate.availablePermitsForTest())
        assertNull(second.tryAcquireExtraTails(1, running))
        assertNull(first.tryAcquireExtraTails(1, running))

        group.close()
        group.close()
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun currentTailLeaseOwnerWaitsForReleaseAndEveryDistinctPhysicalTask() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(1)
        val lease = requireNotNull(gate.tryAcquire())
        val closeCalls = AtomicInteger(0)
        val owner = NtkDirectWifiWebtoonTailLeaseOwner(
            Closeable {
                closeCalls.incrementAndGet()
                lease.close()
            },
            physicalTaskCount = 2,
        )

        owner.requestRelease()
        owner.requestRelease()
        owner.physicalTerminated(0)
        owner.physicalTerminated(0)
        assertEquals(0, closeCalls.get())
        assertEquals(0, gate.availablePermitsForTest())

        owner.physicalTerminated(1)
        assertEquals(1, closeCalls.get())
        assertEquals(1, gate.availablePermitsForTest())

        owner.physicalTerminated(1)
        owner.requestRelease()
        assertEquals(1, closeCalls.get())
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun currentTailLeaseOwnerAlsoClosesOnceWhenPhysicalTerminationWinsTheOrder() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(1)
        val lease = requireNotNull(gate.tryAcquire())
        val closeCalls = AtomicInteger(0)
        val owner = NtkDirectWifiWebtoonTailLeaseOwner(
            Closeable {
                closeCalls.incrementAndGet()
                lease.close()
            },
            physicalTaskCount = 1,
        )

        owner.physicalTerminated()
        owner.physicalTerminated()
        assertEquals(0, closeCalls.get())
        assertEquals(0, gate.availablePermitsForTest())

        owner.requestRelease()
        owner.requestRelease()
        assertEquals(1, closeCalls.get())
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun cancelledPublishedOwnerCanRetireTheWholeGroupBeforeAnyTaskStarts() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(2)
        val lease = requireNotNull(gate.tryAcquire(2))
        val owner = NtkDirectWifiWebtoonTailLeaseOwner(
            lease,
            physicalTaskCount = 2,
        )

        owner.requestRelease()
        owner.retireAllUnstartedPhysicalTasks()
        owner.retireAllUnstartedPhysicalTasks()

        assertEquals(2, gate.availablePermitsForTest())
    }

    @Test
    fun segmentCancellationClosesPhysicalRegistrationOnEitherSideOfTheRace() {
        val cancelledBeforeRegistration = AtomicInteger(0)
        val before = NtkDirectWifiWebtoonTailSegmentCancellation<Any> {
            cancelledBeforeRegistration.incrementAndGet()
        }
        val beforePhysical = Any()

        before.requestCancellation()
        assertFalse(before.register(beforePhysical))
        before.requestCancellation()
        assertEquals(1, cancelledBeforeRegistration.get())

        val cancelledAfterRegistration = AtomicInteger(0)
        val after = NtkDirectWifiWebtoonTailSegmentCancellation<Any> {
            cancelledAfterRegistration.incrementAndGet()
        }
        val afterPhysical = Any()

        assertTrue(after.register(afterPhysical))
        after.requestCancellation()
        after.requestCancellation()
        after.clear(afterPhysical)
        assertEquals(1, cancelledAfterRegistration.get())
    }

    @Test
    fun segmentCancellationClosesExactlyOnePhysicalRegistrationUnderConcurrentRaces() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(300) {
                val cancelCalls = AtomicInteger(0)
                val cancellation = NtkDirectWifiWebtoonTailSegmentCancellation<Any> {
                    cancelCalls.incrementAndGet()
                }
                val physical = Any()
                val start = CountDownLatch(1)
                val registration = executor.submit(java.util.concurrent.Callable {
                    check(start.await(2L, TimeUnit.SECONDS))
                    cancellation.register(physical)
                })
                val cancel = executor.submit {
                    check(start.await(2L, TimeUnit.SECONDS))
                    cancellation.requestCancellation()
                }

                start.countDown()
                registration.get(2L, TimeUnit.SECONDS)
                cancel.get(2L, TimeUnit.SECONDS)
                assertTrue(cancellation.isCancellationRequestedForTest())
                assertEquals(1, cancelCalls.get())
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun currentTailIntegrationRechecksCancellationAtBothPublicationBoundaries() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        ).readText()

        assertTrue(
            source.contains(
                "owner.requestRelease()\n" +
                    "                            owner.retireAllUnstartedPhysicalTasks()"
            )
        )
        assertTrue(
            source.contains(
                "if (directWifiWebtoonTail && cancelled.get()) {\n" +
                    "                projectedTail = null\n" +
                    "                segments.forEach(::cancelProjectedTailSegment)"
            )
        )
        assertTrue(source.contains("tail.segments.forEach(::cancelProjectedTailSegment)"))
        assertTrue(
            source.contains(
                "directWifiAdjacentWebtoonTailTag != null) &&\n" +
                    "                tail.segments.any { segment -> !segment.task.isDone }"
            )
        )
        assertTrue(source.contains("abandonProjectedTailForHealthyPrimary()"))
        assertTrue(
            source.contains("directWifiCancellation.requestCancellation()")
        )
        assertTrue(
            source.contains(
                "frozenTailTag != null || frozenAdjacentTailTag != null"
            )
        )
        assertTrue(
            source.contains(
                "directWifiAdjacentWebtoonTailTag != null\n" +
                    "                        ) {\n" +
                    "                            projectedTail?.segments" +
                    "?.forEach(::cancelProjectedTailSegment)"
            )
        )
    }

    @Test
    fun currentTailLeaseOwnerClosesExactlyOnceUnderDuplicateConcurrentSignals() {
        val executor = Executors.newFixedThreadPool(6)
        try {
            repeat(200) {
                val closeCalls = AtomicInteger(0)
                val owner = NtkDirectWifiWebtoonTailLeaseOwner(
                    Closeable { closeCalls.incrementAndGet() },
                    physicalTaskCount = 2,
                )
                val start = CountDownLatch(1)
                val actions = listOf<() -> Unit>(
                    { owner.requestRelease() },
                    { owner.requestRelease() },
                    { owner.physicalTerminated(0) },
                    { owner.physicalTerminated(0) },
                    { owner.physicalTerminated(1) },
                    { owner.physicalTerminated(1) },
                )
                val futures = actions.map { action ->
                    executor.submit {
                        check(start.await(2L, TimeUnit.SECONDS))
                        action()
                    }
                }

                start.countDown()
                futures.forEach { it.get(2L, TimeUnit.SECONDS) }
                assertEquals(1, closeCalls.get())
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun frozenProfileAndTailMustMatchTheExactLogicalRequestIdentity() {
        val profile = requireNotNull(freeze(pageCount = 8))
        val tag = requireNotNull(profile.tagForPage(2, assetDigest(2)))

        assertTrue(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.matchesFrozenProfile(
                profile,
                "/webtoon/work/episode",
                8,
                manifestDigest,
                hasValidQuarantineIdentity = false,
            )
        )
        assertTrue(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.matchesFrozenProfile(
                profile,
                "/webtoon/work/episode",
                8,
                strictManifestDigest = null,
                hasValidQuarantineIdentity = true,
            )
        )
        assertFalse(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.matchesFrozenProfile(
                profile,
                "/webtoon/other/episode",
                8,
                manifestDigest,
                hasValidQuarantineIdentity = false,
            )
        )

        fun matches(
            pageIndex: Int = 2,
            pageCount: Int = 8,
            path: String = "/webtoon/work/episode",
            strictDigest: String? = manifestDigest,
            quarantineDigest: String? = null,
        ) = NtkDirectWifiShortWebtoonProjectedTailPolicy.matchesFrozenIdentity(
            tag,
            path,
            pageCount,
            pageIndex,
            strictDigest,
            quarantineDigest,
        )

        assertTrue(matches())
        assertTrue(matches(strictDigest = null, quarantineDigest = assetDigest(2)))
        assertFalse(matches(pageIndex = 0))
        assertFalse(matches(pageIndex = 3))
        assertFalse(matches(pageCount = 9))
        assertFalse(matches(path = "/webtoon/other/episode"))
        assertFalse(matches(strictDigest = "f".repeat(64)))
        assertFalse(matches(strictDigest = null, quarantineDigest = assetDigest(3)))
    }

    @Test
    fun slowLargeBodySplitsItsUntouchedRemainderIntoBoundedContiguousSuffixes() {
        val delivered = 128L * 1024L
        val total = 2L * 1024L * 1024L

        assertTrue(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.shouldStart(
                bodyElapsedMs = 1_800L,
                deliveredBytes = delivered,
                expectedLength = total,
            )
        )
        val split = requireNotNull(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.disjointTailStart(
                delivered,
                total,
            )
        )
        val remaining = total - delivered
        assertEquals(
            delivered + remaining *
                NtkDirectWifiShortWebtoonProjectedTailPolicy.PRIMARY_SHARE_PERCENT / 100L,
            split,
        )
        assertTrue(split > delivered)
        assertTrue(split - delivered >=
            NtkDirectWifiShortWebtoonProjectedTailPolicy.MIN_PRIMARY_GAP_BYTES)
        assertTrue(total - split >=
            NtkDirectWifiShortWebtoonProjectedTailPolicy.MIN_TAIL_BYTES)
        assertEquals(total - delivered, (split - delivered) + (total - split))
        assertEquals(split - 1L, (delivered until split).last)
        assertEquals(split, (split until total).first)

        val suffixes = NtkDirectWifiShortWebtoonProjectedTailPolicy
            .disjointTailSegments(delivered, total, maximumSuffixes = 4)
        assertEquals(4, suffixes.size)
        assertEquals(split, suffixes.first().first)
        assertEquals(total - 1L, suffixes.last().last)
        suffixes.zipWithNext().forEach { (left, right) ->
            assertEquals(left.last + 1L, right.first)
        }
        assertEquals(
            total - split,
            suffixes.sumOf { range -> range.last - range.first + 1L },
        )
        assertTrue(suffixes.all { range ->
            range.last - range.first + 1L >=
                NtkDirectWifiShortWebtoonProjectedTailPolicy.MIN_TAIL_BYTES
        })

        assertFalse(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.shouldStart(
                bodyElapsedMs = 1_800L,
                deliveredBytes = 768L * 1024L,
                expectedLength = total,
            )
        )
        assertFalse(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.shouldStart(
                bodyElapsedMs = 1_799L,
                deliveredBytes = delivered,
                expectedLength = total,
            )
        )
        assertNull(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.disjointTailStart(
                deliveredBytes = 32L * 1024L,
                expectedLength = 128L * 1024L,
            )
        )
    }
}
