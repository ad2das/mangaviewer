package ml.melun.mangaview.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedDrawDispatchQueueTest {

    @Test
    fun committedProofAdvancesOnlyAfterAcceptedActivityDelivery() {
        val queue = queue()
        val first = proof(1L, "/manhwa/1/one", 0, 0)
        val second = proof(2L, "/manhwa/1/two", 0, 1)

        assertTrue(queue.offer(first, false, 0L).shouldPost)
        assertTrue(queue.beginRun())
        assertEquals(first, queue.pollReady(0L)?.proof)
        assertNull(queue.latestDeliveredProof())
        queue.recordDeliveryResult(true, first)
        queue.finishRun(0L)
        assertEquals(first, queue.latestDeliveredProof())
        assertEquals(first, queue.latestDeliveredStableViewportProof())

        assertTrue(queue.offer(second, false, 20_000_000L).shouldPost)
        assertTrue(queue.beginRun())
        assertEquals(second, queue.pollReady(20_000_000L)?.proof)
        queue.recordDeliveryResult(false, second)
        queue.finishRun(20_000_000L)
        assertEquals(first, queue.latestDeliveredProof())
        assertEquals(first, queue.latestDeliveredStableViewportProof())

        queue.clearAfterScheduledCallbackRemoval()
        assertNull(queue.latestDeliveredProof())
        assertNull(queue.latestDeliveredStableViewportProof())
    }

    @Test
    fun acceptedSparseProofCannotReplaceStableCommittedViewport() {
        val queue = queue()
        val stable = proof(1L, "episode-a", 3, 3)
        val sparse = proof(
            2L,
            "episode-a",
            4,
            4,
            coverage = stable.coverage.copy(missingPx = 40, drawablePx = 60),
        )

        queue.recordDeliveryResult(true, stable)
        queue.recordDeliveryResult(true, sparse)

        assertEquals(sparse, queue.latestDeliveredProof())
        assertEquals(stable, queue.latestDeliveredStableViewportProof())
    }
    @Test
    fun tenThousandSameIdentityOffersReserveOneRunnerAndDeliverLatest() {
        val queue = queue()
        var scheduleClaims = 0
        repeat(10_000) { index ->
            if (queue.offer(proof(index.toLong(), "episode-a", 8, 12), false, 0L).shouldPost) {
                scheduleClaims++
            }
        }

        assertEquals(1, scheduleClaims)
        assertTrue(queue.beginRun())
        val delivered = queue.pollReady(100_000_000L)
        assertNotNull(delivered)
        assertEquals(9_999L, delivered!!.proof.sequence)
        queue.recordDeliveryResult(true)
        assertFalse(queue.finishRun(100_000_000L).shouldPost)

        val snapshot = queue.snapshot()
        assertEquals(10_000L, snapshot.offers)
        assertEquals(9_999L, snapshot.sameSemanticCoalesced)
        assertEquals(1L, snapshot.selected)
        assertEquals(1L, snapshot.delivered)
        assertEquals(1, snapshot.maxQueueDepth)
        assertEquals(1, snapshot.maxConcurrentRunnerOwners)
    }

    @Test
    fun semanticAndP0P4TransitionsRemainOrderedAndUnique() {
        val queue = queue()
        val offers = listOf(
            proof(1L, "episode-a", 19, 31),
            proof(2L, "episode-b", 0, 32),
            proof(3L, "episode-b", 1, 33),
            proof(4L, "episode-b", 2, 34),
            // Same semantic slot: only its physically newer proof should survive.
            proof(5L, "episode-b", 2, 34, scrollOffset = 44f),
            proof(6L, "episode-b", 3, 35),
            proof(7L, "episode-b", 4, 36),
        )
        offers.forEachIndexed { index, item ->
            assertEquals(index == 0, queue.offer(item, false, 0L).shouldPost)
        }

        assertTrue(queue.beginRun())
        val sequences = ArrayList<Long>()
        while (true) {
            val delivery = queue.pollReady(200_000_000L) ?: break
            sequences += delivery.proof.sequence
            queue.recordDeliveryResult(true)
        }
        assertFalse(queue.finishRun(200_000_000L).shouldPost)

        assertEquals(listOf(1L, 2L, 3L, 5L, 6L, 7L), sequences)
        assertEquals(0L, queue.snapshot().criticalOverflowFailures)
    }

    @Test
    fun defectiveSameIdentityFrameCannotOverwriteUndeliveredCleanProof() {
        val queue = queue()
        val clean = proof(1L, "episode-b", 0, 32)
        val defective = proof(
            2L,
            "episode-b",
            0,
            32,
            coverage = clean.coverage.copy(
                drawablePx = 0,
                missingPx = 100,
                drawableItems = 0,
            ),
        )

        assertTrue(queue.offer(clean, false, 0L).shouldPost)
        assertFalse(queue.offer(defective, false, 0L).shouldPost)
        assertTrue(queue.beginRun())
        assertEquals(1L, queue.pollReady(0L)!!.proof.sequence)
        queue.recordDeliveryResult(true)
        assertEquals(2L, queue.pollReady(0L)!!.proof.sequence)
        queue.recordDeliveryResult(false)
        assertFalse(queue.finishRun(0L).shouldPost)
        assertEquals(0L, queue.snapshot().sameSemanticCoalesced)
    }

    @Test
    fun structureOnlyProgressReplacesPendingProtectedIdentityWithNewestProof() {
        val queue = queue()
        assertTrue(queue.offer(proof(1L, "episode-b", 0, 32, structure = 11L), false, 0L).shouldPost)
        assertFalse(queue.offer(proof(2L, "episode-b", 0, 32, structure = 12L), false, 0L).shouldPost)

        val snapshot = queue.snapshot()
        assertEquals(1, snapshot.pendingSemanticTransitions)
        assertEquals(1, snapshot.maxQueueDepth)
        assertEquals(1L, snapshot.sameSemanticCoalesced)
        assertTrue(queue.beginRun())
        assertEquals(2L, queue.pollReady(0L)!!.proof.sequence)
        queue.recordDeliveryResult(true)
        assertFalse(queue.finishRun(0L).shouldPost)
    }

    @Test
    fun protectedTransitionFollowedByOrdinaryPublicationBurstKeepsOneLatestSlot() {
        val queue = queue()
        var scheduleClaims = 0
        if (queue.offer(proof(1L, "episode-b", 0, 32), false, 0L).shouldPost) {
            scheduleClaims++
        }
        repeat(10_000) { index ->
            val sourcePage = 5 + index % 90
            val sequence = index + 2L
            if (queue.offer(
                    proof(
                        sequence,
                        "episode-b",
                        sourcePage,
                        40 + sourcePage,
                        scrollOffset = index.toFloat(),
                        structure = 12L + index,
                    ),
                    false,
                    index * 1_000L,
                ).shouldPost
            ) {
                scheduleClaims++
            }
        }

        val pending = queue.snapshot()
        assertEquals(1, scheduleClaims)
        assertEquals(1, pending.pendingSemanticTransitions)
        assertTrue(pending.ordinaryPending)
        assertEquals(2, pending.maxQueueDepth)
        assertEquals(0L, pending.criticalOverflowFailures)

        assertTrue(queue.beginRun())
        assertEquals(1L, queue.pollReady(0L)!!.proof.sequence)
        queue.recordDeliveryResult(true)
        assertEquals(null, queue.pollReady(0L))
        val repost = queue.finishRun(0L)
        assertTrue(repost.shouldPost)
        assertEquals(CADENCE_NS, repost.delayNanos)
        assertTrue(queue.beginRun())
        assertEquals(10_001L, queue.pollReady(CADENCE_NS)!!.proof.sequence)
        queue.recordDeliveryResult(true)
        assertFalse(queue.finishRun(CADENCE_NS).shouldPost)
    }

    @Test
    fun ordinarySequentialFramesAreDeliveredOnlyAtSixteenMillisecondCadence() {
        val queue = queue()
        var scheduledAt = Long.MIN_VALUE
        val deliveredAt = ArrayList<Long>()

        repeat(10_000) { index ->
            val now = index * 1_000_000L
            val offer = queue.offer(
                proof(index.toLong(), "episode-a", 8, 12, scrollOffset = index.toFloat()),
                false,
                now,
            )
            if (offer.shouldPost) scheduledAt = now + offer.delayNanos
            while (scheduledAt != Long.MIN_VALUE && scheduledAt <= now) {
                assertTrue(queue.beginRun())
                queue.pollReady(scheduledAt)?.let {
                    deliveredAt += scheduledAt
                    queue.recordDeliveryResult(true)
                }
                val repost = queue.finishRun(scheduledAt)
                scheduledAt = if (repost.shouldPost) {
                    scheduledAt + repost.delayNanos.coerceAtLeast(1L)
                } else {
                    Long.MIN_VALUE
                }
            }
        }
        if (scheduledAt != Long.MIN_VALUE) {
            assertTrue(queue.beginRun())
            queue.pollReady(scheduledAt)?.let {
                deliveredAt += scheduledAt
                queue.recordDeliveryResult(true)
            }
            queue.finishRun(scheduledAt)
        }

        assertTrue(deliveredAt.size in 620..627)
        deliveredAt.zipWithNext().forEach { (first, second) ->
            assertTrue("ordinary callback interval was ${second - first}", second - first >= CADENCE_NS)
        }
        assertEquals(1, queue.snapshot().maxConcurrentRunnerOwners)
    }

    @Test
    fun lifecycleClearCancelsScheduledOwnerAndPreservesRunningOwnerWakeup() {
        val queue = queue()
        assertTrue(queue.offer(proof(1L, "episode-a", 0, 0), false, 0L).shouldPost)
        queue.clearAfterScheduledCallbackRemoval()
        assertFalse(queue.beginRun())
        assertEquals(0, queue.snapshot().pendingSemanticTransitions)

        assertTrue(queue.offer(proof(2L, "episode-b", 0, 0, lifecycle = 2L), false, 0L).shouldPost)
        assertTrue(queue.beginRun())
        val retired = queue.pollReady(10L)
        assertEquals(2L, retired!!.proof.sequence)

        // Model clearFramePipeLocked while this main callback is still executing. The running
        // owner must cover a new-lifecycle offer instead of allowing a second Runnable reservation.
        queue.clearAfterScheduledCallbackRemoval()
        val newOffer = queue.offer(
            proof(3L, "episode-c", 0, 0, lifecycle = 3L),
            false,
            10L,
        )
        assertFalse(newOffer.shouldPost)
        queue.recordDeliveryResult(false)
        val repost = queue.finishRun(10L)
        assertTrue(repost.shouldPost)
        assertTrue(queue.beginRun())
        assertEquals(3L, queue.pollReady(11L)!!.proof.sequence)
        queue.recordDeliveryResult(true)
        assertFalse(queue.finishRun(11L).shouldPost)

        val snapshot = queue.snapshot()
        assertEquals(1L, snapshot.staleDropped)
        assertEquals(1L, snapshot.delivered)
        assertEquals(1, snapshot.maxConcurrentRunnerOwners)
    }

    @Test
    fun concurrentOfferAndRunnerFinishCannotLoseWakeup() {
        repeat(250) { iteration ->
            val queue = queue()
            assertTrue(queue.offer(proof(1L, "episode-a", 8, 8), false, 0L).shouldPost)
            assertTrue(queue.beginRun())
            assertNotNull(queue.pollReady(0L))

            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val finish = executor.submit<CompletedDrawDispatchQueue.Repost> {
                    start.await()
                    queue.finishRun(1L)
                }
                val offer = executor.submit<CompletedDrawDispatchQueue.OfferResult> {
                    start.await()
                    queue.offer(
                        proof(2L + iteration, "episode-a", 8, 8, scrollOffset = 1f),
                        false,
                        1L,
                    )
                }
                start.countDown()
                val finishResult = finish.get(5, TimeUnit.SECONDS)
                val offerResult = offer.get(5, TimeUnit.SECONDS)
                assertTrue(finishResult.shouldPost || offerResult.shouldPost)
                assertTrue(queue.snapshot().runnerScheduled)
                assertTrue(queue.beginRun())
                val due = queue.pollReady(CADENCE_NS)
                if (due == null) {
                    val repost = queue.finishRun(CADENCE_NS)
                    assertTrue(repost.shouldPost)
                    assertTrue(queue.beginRun())
                    assertNotNull(queue.pollReady(CADENCE_NS + repost.delayNanos))
                }
                queue.recordDeliveryResult(true)
                assertFalse(queue.finishRun(CADENCE_NS * 2L).shouldPost)
            } finally {
                executor.shutdownNow()
            }
            assertEquals(1, queue.snapshot().maxConcurrentRunnerOwners)
        }
    }

    @Test
    fun protectedOverflowIsFailClosedAndNeverSilent() {
        val queue = CompletedDrawDispatchQueue(
            maxSemanticTransitions = 2,
            ordinaryCadenceNanos = CADENCE_NS,
        )
        assertFalse(queue.offer(proof(1L, "episode-a", 0, 0), false, 0L).criticalOverflowed)
        assertFalse(queue.offer(proof(2L, "episode-b", 0, 1), false, 0L).criticalOverflowed)
        assertTrue(queue.offer(proof(3L, "episode-c", 0, 2), false, 0L).criticalOverflowed)

        val snapshot = queue.snapshot()
        assertEquals(1L, snapshot.criticalOverflowFailures)
        assertEquals(2, snapshot.pendingSemanticTransitions)
        assertTrue(snapshot.maxQueueDepth <= 2)
        assertTrue(queue.beginRun())
        assertEquals(2L, queue.pollReady(0L)!!.proof.sequence)
        assertEquals(3L, queue.pollReady(0L)!!.proof.sequence)
    }

    @Test
    fun concurrentBurstStillHasOneScheduledOwnerAndOneLatestSlot() {
        val queue = queue()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val scheduleClaims = AtomicInteger()
        try {
            val tasks = (0 until 8).map { worker ->
                executor.submit {
                    start.await()
                    repeat(1_250) { offset ->
                        val sequence = worker * 1_250L + offset
                        if (queue.offer(
                                proof(sequence, "episode-a", 8, 12, scrollOffset = sequence.toFloat()),
                                false,
                                0L,
                            ).shouldPost
                        ) {
                            scheduleClaims.incrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, scheduleClaims.get())
        assertEquals(1, queue.snapshot().maxQueueDepth)
        assertEquals(1, queue.snapshot().maxConcurrentRunnerOwners)
        assertTrue(queue.beginRun())
        assertNotNull(queue.pollReady(100_000_000L))
    }

    private fun queue(): CompletedDrawDispatchQueue = CompletedDrawDispatchQueue(
        maxSemanticTransitions = 64,
        ordinaryCadenceNanos = CADENCE_NS,
    )

    private fun proof(
        sequence: Long,
        episodePath: String,
        sourcePage: Int,
        displayPage: Int,
        scrollOffset: Float = 0f,
        lifecycle: Long = 1L,
        structure: Long = 11L,
        coverage: ReaderSurfaceView.VisibleCoverageSnapshot? = null,
    ): ReaderSurfaceView.CompletedDrawProof {
        val identity = ReaderSurfaceView.CommittedPageIdentity(
            displayPageIndex = displayPage,
            normalizedEpisodePath = episodePath,
            sourcePageIndex = sourcePage,
            canonicalAsset = "$episodePath/p${sourcePage.toString().padStart(3, '0')}.jpg",
            manifestDigest = "digest-$episodePath",
            manifestPageCount = 100,
        )
        return ReaderSurfaceView.CompletedDrawProof(
            sequence = sequence,
            completedUptimeNanos = sequence + 1L,
            hardwareAccelerated = true,
            coverage = coverage ?: ReaderSurfaceView.VisibleCoverageSnapshot(
                viewportPx = 100,
                drawablePx = 100,
                missingPx = 0,
                placeholderPx = 0,
                drawableItems = 1,
                totalItems = 1,
                visibleLoading = 0,
                visibleErrors = 0,
                visibleCards = 0,
                busy = true,
                pageCount = 100,
                physicalViewportPx = 100,
            ),
            frameToken = sequence + 1L,
            desiredVersion = sequence + 1L,
            drawnVersion = sequence + 1L,
            committedVersion = sequence + 1L,
            structureEpoch = structure,
            visiblePageIndexes = intArrayOf(displayPage),
            visiblePageIdentities = listOf(identity),
            surfaceControlLatchObserved = true,
            surfaceLifecycleEpoch = lifecycle,
            presentedUptimeNanos = sequence + 1L,
            scrollOffsetPx = scrollOffset,
            firstVisiblePageTopPx = 0f,
            visiblePageTopPx = floatArrayOf(0f),
        )
    }

    private companion object {
        const val CADENCE_NS = 16_000_000L
    }
}
