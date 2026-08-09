package ml.melun.mangaview.macrobenchmark

import android.os.Process
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast pure contract tests packaged beside the macro test without launching the target app. */
class ResumeTraversalPlanTest {
    @Test
    fun semanticCommitFallbackPreservesExactNodeIdentityAndP0Timestamp() {
        val description = AdjacentSemanticCommitDescriptionPolicy.build(
            episodePath = "/webtoon/12868/1348822",
            sourceIndex = 2,
            viewerGeneration = 73L,
            presentedAtNanos = 44_300_000_000L,
            firstAdjacentPresentedAtNanos = 41_000_000_000L,
        )

        assertTrue(description.startsWith("actual:/webtoon/12868/1348822:2:73;"))
        assertTrue(description.contains(";actualPresentedAtNanos=44300000000;"))
        assertTrue(description.contains(";firstAdjacentActualAtNanos=41000000000;"))
        assertTrue(description.endsWith(
            ";firstAdjacentActualEpisode=/webtoon/12868/1348822"
        ))
        val accumulator = AdjacentForwardEvidenceAccumulator("/webtoon/12868/1348822")
        accumulator.observeActualDescription(description)
        assertEquals(41_000_000_000L, accumulator.snapshot.firstAdjacentActualAtNanos)
        assertEquals(
            "/webtoon/12868/1348822",
            accumulator.snapshot.firstAdjacentActualEpisode,
        )
    }

    @Test
    fun canonicalPercentsMapToForwardPageOrdinals() {
        assertEquals(5, ResumeTraversalPlan.resumePage(20, 25))
        assertEquals(10, ResumeTraversalPlan.resumePage(20, 50))
        assertEquals(18, ResumeTraversalPlan.resumePage(20, 90))
        assertEquals(2, ResumeTraversalPlan.forwardPageCount(20, 18))
    }

    @Test
    fun tailResumeIsClampedInsideTheManifest() {
        assertEquals(0, ResumeTraversalPlan.resumePage(1, 90))
        assertThrows(IllegalArgumentException::class.java) {
            ResumeTraversalPlan.resumePage(20, 75)
        }
    }

    @Test
    fun gesturePlanIsExactlyThreeViewportsPerSecond() {
        val samples = ResumeTraversalPlan.gestureSamples()
        assertEquals(TouchAction.DOWN, samples.first().action)
        assertEquals(0L, samples.first().offsetMs)
        assertEquals(ResumeTraversalPlan.startYFraction, samples.first().yFraction, 0.0)
        assertEquals(TouchAction.UP, samples.last().action)
        assertEquals(ResumeTraversalPlan.strokeDurationMs, samples.last().offsetMs)
        assertEquals(ResumeTraversalPlan.endYFraction, samples.last().yFraction, 0.0)
        assertTrue(samples.zipWithNext().all { (left, right) ->
            right.offsetMs - left.offsetMs == ResumeTraversalPlan.sampleIntervalMs
        })
        assertEquals(3.0, ResumeTraversalPlan.plannedViewportPerSecond, 0.000_001)
    }

    @Test
    fun preparedProducerOwnsOneImmediateAbsoluteScheduleBaseline() {
        val scheduleStart = ContinuousInputSchedulePolicy.initialScheduleStartMs(10_000L)
        assertEquals(10_000L, scheduleStart)
        assertEquals(
            10_000L,
            ContinuousInputSchedulePolicy.plannedSampleTimeMs(
                scheduleStartMs = scheduleStart,
                gesture = 0,
                sampleOffsetMs = 0L,
            ),
        )
        // Later gestures are still tied to the original baseline; there is no lateness rebase.
        assertEquals(
            10_736L,
            ContinuousInputSchedulePolicy.plannedSampleTimeMs(
                scheduleStartMs = scheduleStart,
                gesture = 3,
                sampleOffsetMs = 16L,
            ),
        )
    }

    @Test
    fun inputScheduleCannotReleaseBeforePreparedChannelArm() {
        val order = ContinuousInputStartOrder()
        assertThrows(IllegalStateException::class.java) { order.markScheduleReleased() }
        order.markPrepared()
        assertThrows(IllegalStateException::class.java) { order.markScheduleReleased() }
        order.markChannelArmed()
        order.markScheduleReleased()
        assertTrue(order.isReleased())
        assertThrows(IllegalStateException::class.java) { order.markScheduleReleased() }
    }

    @Test
    fun firstActualNeedsExactPostClickActualAndPresentationTimestamps() {
        val clickAt = 1_000L
        assertEquals(
            1_100L,
            ActualImageTimestampPolicy.exactFirstActualAtOrNull(
                clickAtNanos = clickAt,
                actualAtNanos = 1_100L,
                actualPresentedAtNanos = 1_200L,
            ),
        )
        assertEquals(
            null,
            ActualImageTimestampPolicy.exactFirstActualAtOrNull(clickAt, null, 1_200L),
        )
        assertEquals(
            null,
            ActualImageTimestampPolicy.exactFirstActualAtOrNull(clickAt, 0L, 1_200L),
        )
        assertEquals(
            null,
            ActualImageTimestampPolicy.exactFirstActualAtOrNull(clickAt, 999L, 1_200L),
        )
        assertEquals(
            null,
            ActualImageTimestampPolicy.exactFirstActualAtOrNull(clickAt, 1_100L, 999L),
        )
        assertEquals(
            null,
            ActualImageTimestampPolicy.exactFirstActualAtOrNull(clickAt, 1_200L, 1_100L),
        )
    }

    @Test
    fun exactResumeCandidateWinsEvenWhenPlusOneNodeIsEnumeratedFirst() {
        val plusOne =
            "actual:/webtoon/64839107/1579645:105:1;actualAtNanos=1100;" +
                "actualPresentedAtNanos=1300;" +
                "firstActualEpisode=/webtoon/64839107/1579645;" +
                "firstActualSourcePage=105;edge=middle"
        val exact =
            "actual:/webtoon/64839107/1579645:105:1;actualAtNanos=1100;" +
                "actualPresentedAtNanos=1200;" +
                "firstActualEpisode=/webtoon/64839107/1579645;" +
                "firstActualSourcePage=104"

        assertEquals(
            ActualImageCandidateEvidence(exact, 1_100L),
            ActualImageCandidatePolicy.select(
                descriptions = listOf(plusOne, exact),
                clickAtNanos = 1_000L,
                expectedEpisodePath = "/webtoon/64839107/1579645",
                expectedSourcePage = 104,
            ),
        )
    }

    @Test
    fun resumeCandidateDoesNotRelaxExpectedPageToPlusOne() {
        val plusOne =
            "actual:/webtoon/848465/nv-848465-2:94:1;actualAtNanos=1100;" +
                "actualPresentedAtNanos=1200;" +
                "firstActualEpisode=/webtoon/848465/nv-848465-2;" +
                "firstActualSourcePage=94"

        assertEquals(
            null,
            ActualImageCandidatePolicy.select(
                descriptions = listOf(plusOne),
                clickAtNanos = 1_000L,
                expectedEpisodePath = "/webtoon/848465/nv-848465-2",
                expectedSourcePage = 93,
            ),
        )
    }

    @Test
    fun continuousInputUsesExactAsyncSemanticsWithoutUiAutomatorTreePolling() {
        assertEquals(
            false,
            HarnessPollingPolicy.shouldPollActualImageTree(continuousInputPresent = true),
        )
        assertEquals(
            true,
            HarnessPollingPolicy.shouldPollActualImageTree(continuousInputPresent = false),
        )
        assertEquals(125L, HarnessPollingPolicy.actualImagePollMs)
        assertEquals(1_000L, HarnessPollingPolicy.terminalFailurePollMs)
        assertEquals(2_500L, HarnessPollingPolicy.nextTerminalFailurePollAtMs(1_500L))
    }

    @Test
    fun homeContinueRecyclerLookupPrioritizesTheSelectedProductionMode() {
        assertEquals(
            listOf("main_comic_recycler", "main_recycler"),
            HomeContinueRecyclerPolicy.resourceNames("manhwa"),
        )
        assertEquals(
            listOf("main_recycler", "main_comic_recycler"),
            HomeContinueRecyclerPolicy.resourceNames("webtoon"),
        )
    }

    @Test
    fun homeContinueRecyclerLookupRejectsUnknownWorkTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeContinueRecyclerPolicy.resourceNames("novel")
        }
    }

    @Test
    fun interGestureGapStartsAfterUpInjectionCompletes() {
        assertEquals(
            16L,
            ContinuousInputCadencePolicy.interGestureIdleMs(
                previousUpCallFinishedMs = 224L,
                nextDownCallStartedMs = 240L,
            ),
        )
        // A 157ms UP call is its own infrastructure metric; none of those 157ms is idle time.
        assertEquals(
            0L,
            ContinuousInputCadencePolicy.interGestureIdleMs(
                previousUpCallFinishedMs = 257L,
                nextDownCallStartedMs = 257L,
            ),
        )
        assertEquals(
            null,
            ContinuousInputCadencePolicy.interGestureIdleMs(
                previousUpCallFinishedMs = 0L,
                nextDownCallStartedMs = 240L,
            ),
        )
    }

    @Test
    fun urgentDisplayOrMoreFavorableProducerPriorityIsAccepted() {
        assertEquals(
            null,
            ContinuousInputCadencePolicy.producerPriorityInvalidReason(
                requiredPriority = -8,
                actualPriority = -8,
            ),
        )
        assertEquals(
            null,
            ContinuousInputCadencePolicy.producerPriorityInvalidReason(
                requiredPriority = -8,
                actualPriority = -10,
            ),
        )
        assertTrue(
            requireNotNull(
                ContinuousInputCadencePolicy.producerPriorityInvalidReason(
                    requiredPriority = -8,
                    actualPriority = 0,
                )
            ).contains("priority=0")
        )
    }

    @Test
    fun instrumentationProcessCanPromoteInputProducerToUrgentDisplay() {
        val actualPriority = AtomicInteger(Int.MAX_VALUE)
        val failure = AtomicReference<Throwable?>(null)
        val producer = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                actualPriority.set(Process.getThreadPriority(Process.myTid()))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        producer.start()
        producer.join(5_000L)

        assertTrue("priority probe thread did not finish", !producer.isAlive)
        failure.get()?.let { throw AssertionError("urgent-display promotion failed", it) }
        assertTrue(
            "actual priority=${actualPriority.get()}",
            actualPriority.get() <= Process.THREAD_PRIORITY_URGENT_DISPLAY,
        )
    }

    @Test
    fun staleSampleIsRejectedBeforeCatchUpInjection() {
        assertEquals(
            null,
            ContinuousInputCadencePolicy.staleSampleInvalidReason(
                scheduleLatenessMs = 64L,
                maxScheduleLatenessMs = 64L,
            ),
        )
        val reason = requireNotNull(
            ContinuousInputCadencePolicy.staleSampleInvalidReason(
                scheduleLatenessMs = 65L,
                maxScheduleLatenessMs = 64L,
            )
        )
        assertTrue(reason.contains("scheduleLateness=65ms>64ms"))
        assertTrue(reason.contains("catch-up injection is forbidden"))
    }

    @Test
    fun slowInjectionCallIsRejectedBeforeTheNextOverdueMove() {
        assertEquals(
            null,
            ContinuousInputCadencePolicy.injectionCallInvalidReason(
                callDurationMs = 64L,
                maxCallDurationMs = 64L,
            ),
        )
        val reason = requireNotNull(
            ContinuousInputCadencePolicy.injectionCallInvalidReason(
                callDurationMs = 65L,
                maxCallDurationMs = 64L,
            )
        )
        assertTrue(reason.contains("injectionCall=65ms>64ms"))
        assertTrue(reason.contains("overdue MOVE catch-up is forbidden"))
    }

    @Test
    fun cleanReaderRateInputHasNoInfrastructureInvalidReason() {
        assertEquals(
            null,
            ContinuousInputCadencePolicy.infrastructureInvalidReason(
                gestureCount = 123,
                achievedViewportPerSecond = 3.0,
                maxScheduleLatenessMs = 16L,
                maxInjectionCallMs = 12L,
                maxInterGestureIdleMs = 16L,
                maxScheduleLatenessLimitMs = 64L,
                maxInjectionCallLimitMs = 64L,
                maxInterGestureIdleLimitMs = 64L,
            ),
        )
    }

    @Test
    fun v29CadenceContaminationIsAnInfrastructureInvalidReason() {
        val reason = requireNotNull(
            ContinuousInputCadencePolicy.infrastructureInvalidReason(
                gestureCount = 123,
                achievedViewportPerSecond = 3.001265818426346,
                maxScheduleLatenessMs = 4_371L,
                maxInjectionCallMs = 199L,
                maxInterGestureIdleMs = 157L,
                maxScheduleLatenessLimitMs = 64L,
                maxInjectionCallLimitMs = 64L,
                maxInterGestureIdleLimitMs = 64L,
            )
        )

        assertTrue(reason.contains("scheduleLateness=4371ms>64ms"))
        assertTrue(reason.contains("injectionCall=199ms>64ms"))
        assertTrue(reason.contains("interGestureIdle=157ms>64ms"))
    }

    @Test
    fun exactLaunchDescriptionPreservesIndependentResumeTailCompletion() {
        val clickAt = 10_000_000_000L
        val readyAt = clickAt + 4_845_000_000L
        val observedAt = clickAt + 4_943_000_000L
        val description =
            "actual:/webtoon/12868/1346337:7:1;actualAtNanos=$observedAt;edge=bottom;" +
                "allReady=1;allReadyAtNanos=$readyAt;adjacentWorkStartedAtNanos=0"

        val evidence = ResumeTailAllImagesEvidencePolicy.fromActualDescription(
            description = description,
            expectedEpisodePath = "/webtoon/12868/1346337",
            expectedResumePage = 7,
            expectedForwardPageCount = 1,
            clickElapsedNanos = clickAt,
            observedElapsedNanos = observedAt,
        )

        assertEquals(1, evidence?.pageCount)
        assertEquals(readyAt, evidence?.readyAtNanos)
        assertTrue(requireNotNull(evidence).readyAtNanos - clickAt <= 8_000_000_000L)
        assertTrue(evidence.readyAtNanos - clickAt > 4_000_000_000L)
    }

    @Test
    fun resumeTailEvidenceFailsClosedOnWrongIdentityCountOrClock() {
        val clickAt = 10_000L
        val observedAt = 20_000L
        fun evidence(
            path: String = "/webtoon/work/current",
            source: Int = 18,
            count: Int = 2,
            readyAt: Long = 19_000L,
        ) = ResumeTailAllImagesEvidencePolicy.fromActualDescription(
            description = "actual:$path:$source:1;allReady=$count;allReadyAtNanos=$readyAt",
            expectedEpisodePath = "/webtoon/work/current",
            expectedResumePage = 18,
            expectedForwardPageCount = 2,
            clickElapsedNanos = clickAt,
            observedElapsedNanos = observedAt,
        )

        assertTrue(evidence() != null)
        assertTrue(evidence(path = "/webtoon/work/wrong") == null)
        assertTrue(evidence(source = 17) == null)
        assertTrue(evidence(count = 20) == null)
        assertTrue(evidence(readyAt = clickAt - 1L) == null)
        assertTrue(evidence(readyAt = observedAt + 1L) == null)
    }

    @Test
    fun adjacentPageZeroStartsButCannotCompletePhysicalRunwayProof() {
        val gate = AdjacentEpisodeProofGate("/next", 5, 4)

        val entry = gate.observe(
            actualEpisodePath = "/next",
            actualSourceIndex = 0,
            adjacentTotalPageCount = 8,
            adjacentRunwayPageCount = 4,
            adjacentRunwayTargetEpisode = "/next",
            firstAdjacentActualAtNanos = 10L,
            firstAdjacentActualEpisode = "/next",
            description = "next-p0",
            presentedAtNanos = 1_000_000_000L,
            gesturesAtPresentation = 1,
            semanticObservedAtNanos = 1_010_000_000L,
            gesturesAtSemanticProof = 1,
        )

        assertTrue(entry.boundaryEnteredNow)
        assertTrue(gate.boundaryEntered)
        assertEquals("next-p0", gate.boundaryDescription)
        assertTrue(!entry.complete)
        assertEquals(1, gate.runwayDrawableCount)
        assertEquals(4, gate.preparedRunwayPageCount)
        assertEquals(listOf(0), gate.observedSourceIndices)
    }

    @Test
    fun runwayTelemetryCannotReplacePhysicalPages() {
        val gate = AdjacentEpisodeProofGate("/next", 5, 4)

        val runwayOnly = gate.observe(
            actualEpisodePath = "/current",
            actualSourceIndex = 19,
            adjacentTotalPageCount = 8,
            adjacentRunwayPageCount = 4,
            adjacentRunwayTargetEpisode = "/next",
            firstAdjacentActualAtNanos = 10L,
            firstAdjacentActualEpisode = "/next",
            description = "runway-before-entry",
        )

        assertTrue(!runwayOnly.boundaryEnteredNow)
        assertTrue(!runwayOnly.complete)
        assertTrue(!gate.boundaryEntered)
        assertEquals(4, gate.preparedRunwayPageCount)
        assertEquals(0, gate.runwayDrawableCount)

        val wrongPage = gate.observe(
            actualEpisodePath = "/next",
            actualSourceIndex = 1,
            adjacentTotalPageCount = 8,
            adjacentRunwayPageCount = 4,
            adjacentRunwayTargetEpisode = "/next",
            firstAdjacentActualAtNanos = 10L,
            firstAdjacentActualEpisode = "/next",
            description = "next-p1",
        )
        assertTrue(!wrongPage.complete)
        assertEquals(0, gate.runwayDrawableCount)

        val pageZero = gate.observe(
            actualEpisodePath = "/next",
            actualSourceIndex = 0,
            adjacentTotalPageCount = 8,
            adjacentRunwayPageCount = 4,
            adjacentRunwayTargetEpisode = "/next",
            firstAdjacentActualAtNanos = 10L,
            firstAdjacentActualEpisode = "/next",
            description = "next-p0",
            presentedAtNanos = 1_000_000_000L,
            gesturesAtPresentation = 1,
            semanticObservedAtNanos = 1_010_000_000L,
            gesturesAtSemanticProof = 1,
        )
        assertTrue(pageZero.boundaryEnteredNow)
        assertTrue(!pageZero.complete)
        assertEquals(listOf(0), gate.observedSourceIndices)
    }

    @Test
    fun eachP0ThroughP4CompletesRegardlessOfPresentationOrder() {
        val gate = AdjacentEpisodeProofGate("/next", 5, 4)
        val presentationOrder = listOf(0, 2, 4, 1, 3)
        for ((ordinal, source) in presentationOrder.withIndex()) {
            val presentedAt = 1_000_000_000L + source * 1_000_000_000L
            val update = gate.observe(
                actualEpisodePath = "/next",
                actualSourceIndex = source,
                adjacentTotalPageCount = 8,
                adjacentRunwayPageCount = if (source == 4) 4 else 0,
                adjacentRunwayTargetEpisode = if (source == 4) "/next" else "",
                firstAdjacentActualAtNanos = 10L,
                firstAdjacentActualEpisode = "/next",
                description = "next-p$source",
                presentedAtNanos = presentedAt,
                gesturesAtPresentation = source * 2,
                semanticObservedAtNanos = presentedAt + 10_000_000L,
                gesturesAtSemanticProof = source * 2,
            )
            assertEquals(source == 0, update.boundaryEnteredNow)
            assertEquals(ordinal == presentationOrder.lastIndex, update.complete)
        }

        assertTrue(gate.isComplete)
        assertEquals(5, gate.runwayDrawableCount)
        assertEquals(4, gate.preparedRunwayPageCount)
        assertEquals(listOf(0, 1, 2, 3, 4), gate.observedSourceIndices)
        assertEquals("next-p3", gate.runwayDescription)
    }

    @Test
    fun shortPagesPresentedInTheSameFrameStillCompleteExactRunwayProof() {
        val gate = AdjacentEpisodeProofGate("/next", 5, 4)
        val sharedPresentedAt = 1_000_000_000L
        for (source in 0..4) {
            val update = gate.observe(
                actualEpisodePath = "/next",
                actualSourceIndex = source,
                adjacentTotalPageCount = 8,
                adjacentRunwayPageCount = 4,
                adjacentRunwayTargetEpisode = "/next",
                firstAdjacentActualAtNanos = sharedPresentedAt,
                firstAdjacentActualEpisode = "/next",
                description = "same-frame-p$source",
                presentedAtNanos = sharedPresentedAt,
                gesturesAtPresentation = 12,
                semanticObservedAtNanos = sharedPresentedAt + 10_000_000L,
                gesturesAtSemanticProof = 12,
            )
            assertEquals(source == 4, update.complete)
        }

        assertTrue(gate.isComplete)
        assertTrue(gate.sourceProgressComplete)
        assertEquals(listOf(0, 1, 2, 3, 4), gate.observedSourceIndices)
        assertEquals(List(5) { sharedPresentedAt }, gate.presentedTimestamps)
    }

    @Test
    fun skippedPhysicalPageCannotBeFilledByPreparedTelemetry() {
        val gate = AdjacentEpisodeProofGate("/next", 5, 4)
        for (source in listOf(0, 1, 3, 4)) {
            val presentedAt = 1_000_000_000L + source * 1_000_000_000L
            gate.observe(
                actualEpisodePath = "/next",
                actualSourceIndex = source,
                adjacentTotalPageCount = 8,
                adjacentRunwayPageCount = 4,
                adjacentRunwayTargetEpisode = "/next",
                firstAdjacentActualAtNanos = 10L,
                firstAdjacentActualEpisode = "/next",
                description = "next-p$source",
                presentedAtNanos = presentedAt,
                gesturesAtPresentation = source * 2,
                semanticObservedAtNanos = presentedAt + 10_000_000L,
                gesturesAtSemanticProof = source * 2,
            )
        }

        assertTrue(!gate.isComplete)
        assertEquals(4, gate.runwayDrawableCount)
        assertEquals(4, gate.preparedRunwayPageCount)
        assertEquals(listOf(0, 1, 3, 4), gate.observedSourceIndices)
        assertEquals(null, gate.sourceProgressFailure)
    }

    @Test
    fun adjacentEvidenceMergesEverySnapshotWithoutZeroRegression() {
        val accumulator = AdjacentForwardEvidenceAccumulator("/next")
        accumulator.observeActualDescription(
            "actual:/current:7:1;adjacentWorkStartedAtNanos=110;" +
                "adjacentRunwayReadyAtNanos=90;adjacentRunwayTargetEpisode=/wrong;" +
                "adjacentRunwayPageCount=99;adjacentTotalPageCount=99;" +
                "forwardBoundaryReachedAtNanos=0;firstAdjacentActualAtNanos=95;" +
                "firstAdjacentActualEpisode=/wrong"
        )
        accumulator.observeActualDescription(
            "actual:/next:0:1;adjacentWorkStartedAtNanos=0;" +
                "adjacentRunwayReadyAtNanos=0;adjacentRunwayTargetEpisode=unknown;" +
                "adjacentRunwayPageCount=0;adjacentTotalPageCount=0;" +
                "forwardBoundaryReachedAtNanos=200;firstAdjacentActualAtNanos=250;" +
                "firstAdjacentActualEpisode=/next"
        )
        accumulator.observeExactP0Ipc(
            validP0IpcPayload().copy(episodePath = "/next", presentedAtNanos = 250L)
        )
        accumulator.observeActualDescription(
            "actual:/next:3:1;adjacentWorkStartedAtNanos=0;" +
                "adjacentRunwayReadyAtNanos=300;adjacentRunwayTargetEpisode=/next;" +
                "adjacentRunwayPageCount=4;adjacentTotalPageCount=8;" +
                "forwardBoundaryReachedAtNanos=0;firstAdjacentActualAtNanos=0;" +
                "firstAdjacentActualEpisode=unknown"
        )
        // A stale later snapshot is allowed to contain zeros but cannot erase proof.
        accumulator.observeActualDescription(
            "actual:/next:3:1;adjacentWorkStartedAtNanos=0;" +
                "adjacentRunwayReadyAtNanos=0;adjacentRunwayTargetEpisode=unknown;" +
                "adjacentRunwayPageCount=0;adjacentTotalPageCount=0;" +
                "forwardBoundaryReachedAtNanos=0;firstAdjacentActualAtNanos=0;" +
                "firstAdjacentActualEpisode=unknown"
        )

        assertEquals(
            AdjacentForwardEvidence(
                adjacentWorkStartedAtNanos = 110L,
                adjacentRunwayReadyAtNanos = 300L,
                adjacentRunwayTargetEpisode = "/next",
                adjacentRunwayPageCount = 4,
                adjacentTotalPageCount = 8,
                forwardBoundaryReachedAtNanos = 200L,
                firstAdjacentActualAtNanos = 250L,
                firstAdjacentActualEpisode = "/next",
            ),
            accumulator.snapshot,
        )
    }

    @Test
    fun exactRunwayReadyIpcSurvivesCoalescedAccessibilitySnapshots() {
        val accumulator = AdjacentForwardEvidenceAccumulator("/next")
        accumulator.observeExactRunwayReadyIpc(
            validRunwayReadyIpcPayload().copy(episodePath = "/wrong")
        )
        accumulator.observeExactRunwayReadyIpc(validRunwayReadyIpcPayload())
        accumulator.observeActualDescription(
            "actual:/next:3:1;adjacentRunwayReadyAtNanos=0;" +
                "adjacentRunwayTargetEpisode=unknown;adjacentRunwayPageCount=0;" +
                "adjacentTotalPageCount=0"
        )

        assertEquals(1_050_000_000L, accumulator.snapshot.adjacentWorkStartedAtNanos)
        assertEquals(1_100_000_000L, accumulator.snapshot.adjacentRunwayReadyAtNanos)
        assertEquals("/next", accumulator.snapshot.adjacentRunwayTargetEpisode)
        assertEquals(4, accumulator.snapshot.adjacentRunwayPageCount)
        assertEquals(22, accumulator.snapshot.adjacentTotalPageCount)
    }

    @Test
    fun exactFivePageRunwayUsesTheCallersPreparedRunwayContract() {
        val accumulator = AdjacentForwardEvidenceAccumulator("/next", 5)
        accumulator.observeExactRunwayReadyIpc(
            validRunwayReadyIpcPayload().copy(pageCount = 5)
        )

        assertEquals(5, accumulator.snapshot.adjacentRunwayPageCount)
        assertEquals(1_100_000_000L, accumulator.snapshot.adjacentRunwayReadyAtNanos)
    }

    @Test
    fun sourceProgressFailsClosedOnSemanticLagOrGestureOvershoot() {
        assertTrue(
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 2,
                presentedAtNanos = 1_000_000_000L,
                semanticObservedAtNanos = 1_240_000_001L,
                gesturesAtSignal = 10,
                gesturesAtSemanticProof = 10,
            )?.contains("semanticLag") == true
        )
        assertTrue(
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 2,
                presentedAtNanos = 1_000_000_000L,
                semanticObservedAtNanos = 1_010_000_000L,
                gesturesAtSignal = 10,
                gesturesAtSemanticProof = 12,
            )?.contains("advanced 2 gestures") == true
        )
        assertEquals(
            null,
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 2,
                presentedAtNanos = 1_000_000_000L,
                semanticObservedAtNanos = 1_240_000_000L,
                gesturesAtSignal = 10,
                gesturesAtSemanticProof = 11,
            ),
        )
    }

    @Test
    fun p0DetectionAt240MillisecondsIsValid() {
        val actualAt = 1_000_000_000L
        val observedAt = actualAt + 240_000_000L

        assertEquals(
            240_000_000L,
            AdjacentP0TimingPolicy.detectionLagNanos(actualAt, observedAt),
        )
        assertEquals(
            AdjacentP0MeasurementStatus.VALID,
            AdjacentP0TimingPolicy.status(actualAt, observedAt),
        )
    }

    @Test
    fun p0DetectionBeyond240MillisecondsIsMeasurementInvalid() {
        val actualAt = 1_000_000_000L

        assertEquals(
            AdjacentP0MeasurementStatus.MEASUREMENT_INVALID,
            AdjacentP0TimingPolicy.status(actualAt, actualAt + 240_000_001L),
        )
        assertEquals(
            AdjacentP0MeasurementStatus.MEASUREMENT_INVALID,
            AdjacentP0TimingPolicy.status(actualAt, actualAt - 1L),
        )
    }

    @Test
    fun p0DetectionNeedsBothAppAndHarnessTimestamps() {
        assertEquals(
            AdjacentP0MeasurementStatus.UNMEASURED,
            AdjacentP0TimingPolicy.status(0L, 1_000_000_000L),
        )
        assertEquals(
            AdjacentP0MeasurementStatus.UNMEASURED,
            AdjacentP0TimingPolicy.status(1_000_000_000L, 0L),
        )
    }

    @Test
    fun p0IpcAcceptsOnlyExactPageZeroIdentity() {
        val payload = validP0IpcPayload()
        assertEquals(
            AdjacentP0IpcRejectReason.NONE,
            rejectP0Ipc(payload),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.NONCE,
            rejectP0Ipc(payload.copy(nonce = "wrong")),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.CASE_ID,
            rejectP0Ipc(payload.copy(caseId = "wrong")),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.EPISODE_PATH,
            rejectP0Ipc(payload.copy(episodePath = "/next-wrong")),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.SOURCE_INDEX,
            rejectP0Ipc(payload.copy(sourceIndex = 1)),
        )
    }

    @Test
    fun p0PhysicalCheckpointMustFollowFirstDownInjectionStart() {
        val firstDownAt = 1_000_000_000L
        assertEquals(
            AdjacentP0IpcRejectReason.NONE,
            AdjacentP0AfterInputStartPolicy.rejection(
                firstDownInjectionStartedAtNanos = firstDownAt,
                presentedAtNanos = firstDownAt,
                acceptedAtNanos = firstDownAt + 1L,
            ),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.EARLY_SIGNAL,
            AdjacentP0AfterInputStartPolicy.rejection(
                firstDownInjectionStartedAtNanos = 0L,
                presentedAtNanos = firstDownAt + 1L,
                acceptedAtNanos = firstDownAt + 2L,
            ),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.EARLY_SIGNAL,
            AdjacentP0AfterInputStartPolicy.rejection(
                firstDownInjectionStartedAtNanos = firstDownAt,
                presentedAtNanos = firstDownAt - 1L,
                acceptedAtNanos = firstDownAt + 1L,
            ),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.EARLY_SIGNAL,
            AdjacentP0AfterInputStartPolicy.rejection(
                firstDownInjectionStartedAtNanos = firstDownAt,
                presentedAtNanos = firstDownAt + 1L,
                acceptedAtNanos = firstDownAt - 1L,
            ),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.NONE,
            AdjacentP0AfterInputStartPolicy.rejection(
                firstDownInjectionStartedAtNanos = 0L,
                presentedAtNanos = firstDownAt - 1L,
                acceptedAtNanos = firstDownAt,
                allowTerminalResumeInitialViewport = true,
            ),
        )
    }

    @Test
    fun p0IpcRejectsInvalidGenerationAndClockOrdering() {
        val payload = validP0IpcPayload()
        assertEquals(
            AdjacentP0IpcRejectReason.GENERATION,
            rejectP0Ipc(payload.copy(viewerGeneration = 0L)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP,
            rejectP0Ipc(payload.copy(presentedAtNanos = 0L)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.SENDER_TIMESTAMP,
            rejectP0Ipc(payload.copy(senderAtNanos = payload.presentedAtNanos - 1L)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.RECEIVER_TIMESTAMP,
            rejectP0Ipc(payload, receivedAtNanos = payload.senderAtNanos - 1L),
        )
    }

    @Test
    fun runwayIpcAcceptsAnyP1ThroughP4OrderButRequiresP0Generation() {
        val payload = validP0IpcPayload().copy(sourceIndex = 2)
        fun reject(
            candidate: AdjacentP0IpcPayload = payload,
            expectedViewerGeneration: Long = 1L,
        ) = AdjacentRunwayIpcSignalPolicy.rejection(
            expectedNonce = "0123456789abcdef0123456789abcdef",
            expectedCaseId = "case",
            expectedEpisodePath = "/next",
            expectedViewerGeneration = expectedViewerGeneration,
            requiredPhysicalPageCount = 5,
            payload = candidate,
            receivedAtNanos = 1_002_000_000L,
        )

        assertEquals(AdjacentP0IpcRejectReason.NONE, reject())
        assertEquals(AdjacentP0IpcRejectReason.NONE, reject(payload.copy(sourceIndex = 4)))
        assertEquals(
            AdjacentP0IpcRejectReason.SOURCE_INDEX,
            reject(payload.copy(sourceIndex = 5)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.GENERATION,
            reject(expectedViewerGeneration = 2L),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.EPISODE_PATH,
            reject(payload.copy(episodePath = "/wrong")),
        )
    }

    @Test
    fun runwayReadyIpcRequiresExactIdentityCountGenerationAndClock() {
        val payload = validRunwayReadyIpcPayload()
        fun reject(
            candidate: AdjacentRunwayReadyIpcPayload = payload,
            expectedViewerGeneration: Long? = 1L,
            receivedAtNanos: Long = 1_102_000_000L,
        ) = AdjacentRunwayReadyIpcSignalPolicy.rejection(
            expectedNonce = payload.nonce,
            expectedCaseId = payload.caseId,
            expectedEpisodePath = payload.episodePath,
            expectedRunwayPageCount = 4,
            expectedViewerGeneration = expectedViewerGeneration,
            payload = candidate,
            receivedAtNanos = receivedAtNanos,
        )

        assertEquals(AdjacentP0IpcRejectReason.NONE, reject())
        assertEquals(
            AdjacentP0IpcRejectReason.NONE,
            reject(expectedViewerGeneration = null),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.PAGE_COUNT,
            reject(payload.copy(pageCount = 3)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.PAGE_COUNT,
            reject(payload.copy(totalPageCount = 3)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.GENERATION,
            reject(expectedViewerGeneration = 2L),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP,
            reject(payload.copy(adjacentWorkStartedAtNanos = 0L)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP,
            reject(payload.copy(adjacentWorkStartedAtNanos = payload.readyAtNanos + 1L)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.SENDER_TIMESTAMP,
            reject(payload.copy(senderAtNanos = payload.readyAtNanos - 1L)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.RECEIVER_TIMESTAMP,
            reject(receivedAtNanos = payload.senderAtNanos - 1L),
        )
    }

    @Test
    fun p0IpcTimingSeparatesProducerBroadcastAndHarnessAcceptance() {
        val payload = validP0IpcPayload().copy(
            presentedAtNanos = 1_000_000_000L,
            senderAtNanos = 1_015_000_000L,
        )
        val lags = requireNotNull(
            AdjacentP0IpcTimingPolicy.stageLags(
                payload,
                receivedAtNanos = 1_035_000_000L,
                acceptedAtNanos = 1_040_000_000L,
            )
        )

        assertEquals(15_000_000L, lags.presentedToSenderNanos)
        assertEquals(20_000_000L, lags.senderToReceiverNanos)
        assertEquals(5_000_000L, lags.receiverToAcceptanceNanos)
        assertEquals(40_000_000L, lags.presentedToAcceptanceNanos)
        assertEquals(
            AdjacentP0MeasurementStatus.VALID,
            AdjacentP0TimingPolicy.status(
                payload.presentedAtNanos,
                payload.presentedAtNanos + lags.presentedToAcceptanceNanos,
            ),
        )
    }

    @Test
    fun p0IpcTimingFailsClosedOnOutOfOrderStages() {
        val payload = validP0IpcPayload()

        assertEquals(
            null,
            AdjacentP0IpcTimingPolicy.stageLags(
                payload,
                receivedAtNanos = payload.senderAtNanos - 1L,
                acceptedAtNanos = payload.senderAtNanos + 1L,
            ),
        )
        assertEquals(
            null,
            AdjacentP0IpcTimingPolicy.stageLags(
                payload,
                receivedAtNanos = payload.senderAtNanos,
                acceptedAtNanos = payload.senderAtNanos - 1L,
            ),
        )
    }

    @Test
    fun semanticObservationUsesEventCreationOnlyAfterPhysicalPresentation() {
        val selected = AdjacentSemanticObservationPolicy.select(
            presentedAtNanos = 1_000_000_000L,
            acceptedAtNanos = 1_008_000_000L,
            eventPublishedAtNanos = 1_021_000_000L,
            eventCallbackAtNanos = 1_090_000_000L,
            semanticCommitPublishedAtNanos = 1_030_000_000L,
            uiObservedAtNanos = 1_150_000_000L,
        )

        assertEquals(AdjacentSemanticObservationPolicy.EVENT_TIME, selected.mode)
        assertEquals(1_021_000_000L, selected.observedAtNanos)
        assertEquals(
            null,
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 0,
                presentedAtNanos = 1_000_000_000L,
                semanticObservedAtNanos = selected.observedAtNanos,
                gesturesAtSignal = 20,
                gesturesAtSemanticProof = 20,
            ),
        )
    }

    @Test
    fun semanticRevisionMustMatchTheExactPhysicalPresentationTimestamp() {
        val physicalPresentedAt = 1_000_000_000L
        assertTrue(
            AdjacentSemanticRevisionBindingPolicy.matchesPhysicalCheckpoint(
                physicalPresentedAtNanos = physicalPresentedAt,
                semanticActualPresentedAtNanos = physicalPresentedAt,
            )
        )
        assertEquals(
            false,
            AdjacentSemanticRevisionBindingPolicy.matchesPhysicalCheckpoint(
                physicalPresentedAtNanos = physicalPresentedAt,
                semanticActualPresentedAtNanos = physicalPresentedAt - 1L,
            ),
        )
        assertEquals(
            false,
            AdjacentSemanticRevisionBindingPolicy.matchesPhysicalCheckpoint(
                physicalPresentedAtNanos = 0L,
                semanticActualPresentedAtNanos = 0L,
            ),
        )
    }

    @Test
    fun semanticObservationPreservesNegativeLeadAndUsesCallbackAcceptanceFloor() {
        val selected = AdjacentSemanticObservationPolicy.select(
            presentedAtNanos = 1_000_000_000L,
            acceptedAtNanos = 1_075_000_000L,
            eventPublishedAtNanos = 980_000_000L,
            eventCallbackAtNanos = 1_040_000_000L,
            semanticCommitPublishedAtNanos = 1_050_000_000L,
            uiObservedAtNanos = 1_180_000_000L,
        )

        assertEquals(AdjacentSemanticObservationPolicy.CALLBACK_FLOOR, selected.mode)
        assertEquals(1_075_000_000L, selected.observedAtNanos)
        assertEquals(-20.0, (980_000_000L - 1_000_000_000L) / 1_000_000.0, 0.0)
        assertEquals(
            null,
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 1,
                presentedAtNanos = 1_000_000_000L,
                semanticObservedAtNanos = selected.observedAtNanos,
                gesturesAtSignal = 20,
                gesturesAtSemanticProof = 21,
            ),
        )
    }

    @Test
    fun semanticObservationDoesNotClampEventAndCallbackThatBothLeadPresentation() {
        val selected = AdjacentSemanticObservationPolicy.select(
            presentedAtNanos = 1_000_000_000L,
            acceptedAtNanos = 1_010_000_000L,
            eventPublishedAtNanos = 960_000_000L,
            eventCallbackAtNanos = 980_000_000L,
            uiObservedAtNanos = 1_060_000_000L,
        )

        assertEquals(AdjacentSemanticObservationPolicy.UIAUTOMATOR_FALLBACK, selected.mode)
        assertEquals(1_060_000_000L, selected.observedAtNanos)
    }

    @Test
    fun v34MissingAccessibilityEventUsesExactSemanticCommitInsteadOfLatePoll() {
        val presented = 43_262_265_969_300L
        val selected = AdjacentSemanticObservationPolicy.select(
            presentedAtNanos = presented,
            acceptedAtNanos = 43_262_331_211_800L,
            eventPublishedAtNanos = null,
            eventCallbackAtNanos = null,
            semanticCommitPublishedAtNanos = presented + 82_000_000L,
            uiObservedAtNanos = 43_262_590_680_400L,
        )

        assertEquals(AdjacentSemanticObservationPolicy.SEMANTIC_COMMIT_TIME, selected.mode)
        assertEquals(presented + 82_000_000L, selected.observedAtNanos)
        assertEquals(
            null,
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 2,
                presentedAtNanos = presented,
                semanticObservedAtNanos = selected.observedAtNanos,
                gesturesAtSignal = 72,
                gesturesAtSemanticProof = 72,
            ),
        )
    }

    @Test
    fun v34EarlyEventAndCallbackUseExactSemanticCommitInsteadOfVeryLatePoll() {
        val presented = 43_305_064_139_500L
        val selected = AdjacentSemanticObservationPolicy.select(
            presentedAtNanos = presented,
            acceptedAtNanos = 43_305_103_263_300L,
            eventPublishedAtNanos = 43_305_017_222_300L,
            eventCallbackAtNanos = 43_305_035_769_900L,
            semanticCommitPublishedAtNanos = presented + 71_000_000L,
            uiObservedAtNanos = 43_306_661_099_400L,
        )

        assertEquals(AdjacentSemanticObservationPolicy.SEMANTIC_COMMIT_TIME, selected.mode)
        assertEquals(presented + 71_000_000L, selected.observedAtNanos)
        assertEquals(
            null,
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 2,
                presentedAtNanos = presented,
                semanticObservedAtNanos = selected.observedAtNanos,
                gesturesAtSignal = 69,
                gesturesAtSemanticProof = 69,
            ),
        )
    }

    @Test
    fun semanticCommitKeepsThe240msAndSingleGestureGatesUnchanged() {
        val presented = 1_000_000_000L
        val late = AdjacentSemanticObservationPolicy.select(
            presentedAtNanos = presented,
            acceptedAtNanos = presented + 20_000_000L,
            eventPublishedAtNanos = null,
            eventCallbackAtNanos = null,
            semanticCommitPublishedAtNanos = presented + 241_000_000L,
            uiObservedAtNanos = presented + 900_000_000L,
        )
        assertTrue(
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 2,
                presentedAtNanos = presented,
                semanticObservedAtNanos = late.observedAtNanos,
                gesturesAtSignal = 10,
                gesturesAtSemanticProof = 10,
            )!!.contains("240ms")
        )

        val prompt = late.copy(observedAtNanos = presented + 80_000_000L)
        assertTrue(
            AdjacentSourceProgressPolicy.invalidReason(
                sourceIndex = 2,
                presentedAtNanos = presented,
                semanticObservedAtNanos = prompt.observedAtNanos,
                gesturesAtSignal = 10,
                gesturesAtSemanticProof = 12,
            )!!.contains("gestures")
        )
    }

    @Test
    fun semanticCommitSignalRequiresExactPhysicalIdentityAndPresentedTimestamp() {
        val physical = validP0IpcPayload().copy(sourceIndex = 2)
        val semantic = AdjacentSemanticCommitPayload(
            nonce = physical.nonce,
            caseId = physical.caseId,
            episodePath = physical.episodePath,
            sourceIndex = physical.sourceIndex,
            presentedAtNanos = physical.presentedAtNanos,
            semanticPublishedAtNanos = physical.presentedAtNanos + 40_000_000L,
            senderAtNanos = physical.presentedAtNanos + 41_000_000L,
            viewerGeneration = physical.viewerGeneration,
        )
        fun reject(candidate: AdjacentSemanticCommitPayload) =
            AdjacentSemanticCommitSignalPolicy.rejection(
                expectedNonce = physical.nonce,
                expectedCaseId = physical.caseId,
                expectedEpisodePath = physical.episodePath,
                physicalPayload = physical,
                semanticPayload = candidate,
                receivedAtNanos = physical.presentedAtNanos + 42_000_000L,
            )

        assertEquals(AdjacentP0IpcRejectReason.NONE, reject(semantic))
        assertEquals(
            AdjacentP0IpcRejectReason.NONCE,
            reject(semantic.copy(nonce = "wrong")),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.EPISODE_PATH,
            reject(semantic.copy(episodePath = "/wrong")),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.PHYSICAL_MISMATCH,
            reject(semantic.copy(sourceIndex = 1)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.PHYSICAL_MISMATCH,
            reject(semantic.copy(viewerGeneration = 2L)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.PHYSICAL_MISMATCH,
            reject(semantic.copy(presentedAtNanos = physical.presentedAtNanos + 1L)),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.SEMANTIC_TIMESTAMP,
            reject(
                semantic.copy(
                    semanticPublishedAtNanos = semantic.presentedAtNanos - 1L,
                )
            ),
        )
        val physicalP0 = validP0IpcPayload()
        val semanticP0 = semantic.copy(
            sourceIndex = 0,
            forwardBoundaryReachedAtNanos = physicalP0.presentedAtNanos - 1L,
        )
        assertEquals(
            AdjacentP0IpcRejectReason.NONE,
            AdjacentSemanticCommitSignalPolicy.rejection(
                expectedNonce = physicalP0.nonce,
                expectedCaseId = physicalP0.caseId,
                expectedEpisodePath = physicalP0.episodePath,
                physicalPayload = physicalP0,
                semanticPayload = semanticP0,
                receivedAtNanos = physicalP0.presentedAtNanos + 42_000_000L,
            ),
        )
        assertEquals(
            AdjacentP0IpcRejectReason.BOUNDARY_TIMESTAMP,
            AdjacentSemanticCommitSignalPolicy.rejection(
                expectedNonce = physicalP0.nonce,
                expectedCaseId = physicalP0.caseId,
                expectedEpisodePath = physicalP0.episodePath,
                physicalPayload = physicalP0,
                semanticPayload = semanticP0.copy(forwardBoundaryReachedAtNanos = 0L),
                receivedAtNanos = physicalP0.presentedAtNanos + 42_000_000L,
            ),
        )
    }

    private fun validP0IpcPayload() = AdjacentP0IpcPayload(
        nonce = "0123456789abcdef0123456789abcdef",
        caseId = "case",
        episodePath = "/next",
        sourceIndex = 0,
        presentedAtNanos = 1_000_000_000L,
        senderAtNanos = 1_001_000_000L,
        viewerGeneration = 1L,
    )

    private fun validRunwayReadyIpcPayload() = AdjacentRunwayReadyIpcPayload(
        nonce = "0123456789abcdef0123456789abcdef",
        caseId = "case",
        episodePath = "/next",
        adjacentWorkStartedAtNanos = 1_050_000_000L,
        readyAtNanos = 1_100_000_000L,
        pageCount = 4,
        totalPageCount = 22,
        senderAtNanos = 1_101_000_000L,
        viewerGeneration = 1L,
    )

    private fun rejectP0Ipc(
        payload: AdjacentP0IpcPayload,
        receivedAtNanos: Long = 1_002_000_000L,
    ) = AdjacentP0IpcSignalPolicy.rejection(
        expectedNonce = "0123456789abcdef0123456789abcdef",
        expectedCaseId = "case",
        expectedEpisodePath = "/next",
        payload = payload,
        receivedAtNanos = receivedAtNanos,
    )
}
