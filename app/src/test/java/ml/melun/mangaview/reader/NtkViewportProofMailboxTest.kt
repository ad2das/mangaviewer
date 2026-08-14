package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NtkViewportProofMailboxTest {
    @Test
    fun tenThousandOffersQueueOneActorRunnableAndPreserveExactCoverage() {
        val scheduled = ArrayDeque<Runnable>()
        val scheduleCount = AtomicInteger()
        val batches = ArrayList<NtkViewportProofBatch>()
        val mailbox = NtkViewportProofMailbox(
            pageCount = 4,
            scheduleDrain = { runnable ->
                scheduleCount.incrementAndGet()
                scheduled.addLast(runnable)
                true
            },
            consumeBatch = batches::add
        )

        repeat(10_000) { index ->
            assertTrue(
                mailbox.offer(
                    sample(
                        sequence = index + 1L,
                        startPx = index.toLong(),
                        endPx = index + 2L,
                        firstPage = index % 4,
                        viewportComplete = index % 10 != 0,
                        runwayComplete = index % 25 != 0
                    )
                )
            )
        }

        assertEquals(1, scheduleCount.get())
        assertEquals(1, scheduled.size)
        assertTrue(mailbox.hasScheduledDrainForTesting())
        scheduled.removeFirst().run()

        assertEquals(1, batches.size)
        val batch = batches.single()
        assertEquals(10_000L, batch.offerCount)
        assertEquals(1_000L, batch.viewportDefectCount)
        assertEquals(400L, batch.runwayDefectCount)
        assertEquals(
            listOf(NtkPresentedContentInterval(0L, 10_001L)),
            batch.presentedIntervals
        )
        assertEquals(4, batch.presentedPages.cardinality())
        assertFalse(mailbox.hasScheduledDrainForTesting())
    }

    @Test
    fun offerDuringDrainIsConsumedBySameRunnableWithoutLostWakeup() {
        val scheduled = ArrayDeque<Runnable>()
        val scheduleCount = AtomicInteger()
        val batches = ArrayList<NtkViewportProofBatch>()
        val consumerEntered = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)
        val consumerCalls = AtomicInteger()
        val failure = AtomicReference<Throwable?>(null)
        val mailbox = NtkViewportProofMailbox(
            pageCount = 2,
            scheduleDrain = { runnable ->
                scheduleCount.incrementAndGet()
                scheduled.addLast(runnable)
                true
            },
            consumeBatch = { batch ->
                synchronized(batches) { batches += batch }
                if (consumerCalls.getAndIncrement() == 0) {
                    consumerEntered.countDown()
                    check(releaseConsumer.await(2, TimeUnit.SECONDS))
                }
            },
            onDrainFailure = failure::set
        )

        assertTrue(mailbox.offer(sample(1L, 0L, 100L, 0)))
        val drainThread = Thread(scheduled.removeFirst(), "viewport-mailbox-test")
        drainThread.start()
        assertTrue(consumerEntered.await(2, TimeUnit.SECONDS))

        assertTrue(mailbox.offer(sample(2L, 100L, 200L, 1)))
        assertEquals(1, scheduleCount.get())
        releaseConsumer.countDown()
        drainThread.join(2_000L)

        assertFalse(drainThread.isAlive)
        assertEquals(null, failure.get())
        val consumed = synchronized(batches) { batches.toList() }
        assertEquals(2L, consumed.sumOf { it.offerCount })
        val coverage = NtkMergedPresentedCoverage().apply {
            consumed.forEach { addAll(it.presentedIntervals) }
        }
        assertEquals(
            listOf(NtkPresentedContentInterval(0L, 200L)),
            coverage.snapshot()
        )
        assertFalse(mailbox.hasScheduledDrainForTesting())
    }

    @Test
    fun terminalFlushConsumesPendingAggregateExactlyOnceBeforeQueuedDrain() {
        val scheduled = ArrayDeque<Runnable>()
        val batches = ArrayList<NtkViewportProofBatch>()
        val mailbox = NtkViewportProofMailbox(
            pageCount = 3,
            scheduleDrain = { runnable -> scheduled.addLast(runnable); true },
            consumeBatch = batches::add
        )

        assertTrue(mailbox.offer(sample(1L, 0L, 75L, 0)))
        assertTrue(mailbox.offer(sample(2L, 75L, 150L, 1, viewportComplete = false)))
        assertTrue(mailbox.offer(sample(3L, 150L, 225L, 2, runwayComplete = false)))

        mailbox.flushPendingOnConsumerThread()

        assertEquals(1, batches.size)
        assertEquals(3L, batches.single().offerCount)
        assertEquals(1L, batches.single().viewportDefectCount)
        assertEquals(1L, batches.single().runwayDefectCount)
        assertEquals(
            listOf(NtkPresentedContentInterval(0L, 225L)),
            batches.single().presentedIntervals
        )
        assertEquals(3, batches.single().presentedPages.cardinality())
        // The already queued runnable still owns the single-flight claim until it observes empty.
        assertTrue(mailbox.hasScheduledDrainForTesting())
        scheduled.removeFirst().run()
        assertEquals(1, batches.size)
        assertFalse(mailbox.hasScheduledDrainForTesting())
    }

    @Test
    fun cancelDropsPendingAggregateAndNeverReschedulesQueuedDrain() {
        val scheduled = ArrayDeque<Runnable>()
        val scheduleCount = AtomicInteger()
        val batches = ArrayList<NtkViewportProofBatch>()
        val mailbox = NtkViewportProofMailbox(
            pageCount = 1,
            scheduleDrain = { runnable ->
                scheduleCount.incrementAndGet()
                scheduled.addLast(runnable)
                true
            },
            consumeBatch = batches::add
        )

        assertTrue(mailbox.offer(sample(1L, 0L, 100L, 0)))
        mailbox.cancel()
        scheduled.removeFirst().run()

        assertTrue(batches.isEmpty())
        assertFalse(mailbox.hasScheduledDrainForTesting())
        assertFalse(mailbox.offer(sample(2L, 100L, 200L, 0)))
        assertEquals(1, scheduleCount.get())
    }

    @Test
    fun rejectedSoleDrainDropsPendingOwnershipAndFailsClosed() {
        val failures = ArrayList<Throwable>()
        val batches = ArrayList<NtkViewportProofBatch>()
        val scheduleCount = AtomicInteger()
        val mailbox = NtkViewportProofMailbox(
            pageCount = 1,
            scheduleDrain = {
                scheduleCount.incrementAndGet()
                false
            },
            consumeBatch = batches::add,
            onScheduleFailure = failures::add
        )

        assertFalse(mailbox.offer(sample(1L, 0L, 100L, 0)))
        assertEquals(1, scheduleCount.get())
        assertEquals(1, failures.size)
        assertFalse(mailbox.hasScheduledDrainForTesting())
        mailbox.flushPendingOnConsumerThread()
        assertTrue(batches.isEmpty())
        assertFalse(mailbox.offer(sample(2L, 100L, 200L, 0)))
        assertEquals(1, scheduleCount.get())
    }

    @Test
    fun negativeContentStartIsRecordedForActorFailureWithoutProducerThrow() {
        val scheduled = ArrayDeque<Runnable>()
        val batches = ArrayList<NtkViewportProofBatch>()
        val mailbox = NtkViewportProofMailbox(
            pageCount = 1,
            scheduleDrain = { runnable -> scheduled.addLast(runnable); true },
            consumeBatch = batches::add
        )

        assertTrue(
            mailbox.offerEvidence(
                evidence(
                    visibleContentStartPx = -1L,
                    visibleContentEndPx = 100L,
                    firstVisiblePage = 0,
                    lastVisiblePage = 0
                )
            )
        )
        scheduled.removeFirst().run()

        assertEquals(1, batches.size)
        assertTrue(batches.single().hasInvalidContentInterval)
        assertTrue(batches.single().presentedIntervals.isEmpty())
    }

    @Test
    fun reversedPageRangeIsRecordedForActorFailureWithoutBitSetThrow() {
        val scheduled = ArrayDeque<Runnable>()
        val batches = ArrayList<NtkViewportProofBatch>()
        val mailbox = NtkViewportProofMailbox(
            pageCount = 3,
            scheduleDrain = { runnable -> scheduled.addLast(runnable); true },
            consumeBatch = batches::add
        )

        assertTrue(
            mailbox.offerEvidence(
                evidence(
                    visibleContentStartPx = 0L,
                    visibleContentEndPx = 100L,
                    firstVisiblePage = 2,
                    lastVisiblePage = 1
                )
            )
        )
        scheduled.removeFirst().run()

        assertEquals(1, batches.size)
        assertTrue(batches.single().hasInvalidPageRange)
        assertTrue(batches.single().presentedPages.isEmpty)
    }

    private fun evidence(
        visibleContentStartPx: Long,
        visibleContentEndPx: Long,
        firstVisiblePage: Int,
        lastVisiblePage: Int
    ): NtkViewportProofEvidence = NtkViewportProofEvidence(
        surfaceEpoch = 7L,
        isBindingSeed = false,
        proofPresent = true,
        visibleContentStartPx = visibleContentStartPx,
        visibleContentEndPx = visibleContentEndPx,
        firstVisiblePage = firstVisiblePage,
        lastVisiblePage = lastVisiblePage,
        viewportOriginalComplete = true,
        runwayOriginalComplete = true
    )

    private fun sample(
        sequence: Long,
        startPx: Long,
        endPx: Long,
        firstPage: Int,
        lastPage: Int = firstPage,
        viewportComplete: Boolean = true,
        runwayComplete: Boolean = true
    ): NtkViewportSample = NtkViewportSample(
        surfaceEpoch = 7L,
        frameSequence = sequence,
        gestureId = 1L,
        appliedInputSequence = sequence,
        topPx = startPx,
        velocityPxPerSecond = 1f,
        predictedStopPx = startPx,
        presentedProof = NtkPresentedFrameProof(
            authority = 1L,
            sceneVersion = sequence,
            viewportOriginalComplete = viewportComplete,
            runwayOriginalComplete = runwayComplete,
            visibleContentStartPx = startPx,
            visibleContentEndPx = endPx,
            firstVisiblePage = firstPage,
            lastVisiblePage = lastPage,
            firstVisibleGapPx = 0L,
            residentContinuousStartPx = 0L,
            residentContinuousEndPx = endPx,
            frameSequence = sequence
        )
    )
}
