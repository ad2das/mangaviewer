package ml.melun.mangaview.macrobenchmark

/** Pure contract shared by the host arguments and the physical input producer. */
internal object ResumeTraversalPlan {
    val supportedPercents: Set<Int> = linkedSetOf(25, 50, 90)

    const val resumeOffsetPx: Int = -420
    const val startYFraction: Double = 0.86
    const val endYFraction: Double = 0.14
    const val strokeDurationMs: Long = 224L
    const val cycleDurationMs: Long = 240L
    const val sampleIntervalMs: Long = 16L

    val viewportDistancePerGesture: Double
        get() = startYFraction - endYFraction

    val plannedViewportPerSecond: Double
        get() = viewportDistancePerGesture * 1_000.0 / cycleDurationMs

    fun resumePage(pageCount: Int, percent: Int): Int {
        require(pageCount > 0) { "pageCount must be positive" }
        require(percent in supportedPercents) {
            "resume percent must be one of ${supportedPercents.joinToString()}"
        }
        return ((pageCount.toLong() * percent) / 100L)
            .toInt()
            .coerceIn(0, pageCount - 1)
    }

    fun forwardPageCount(pageCount: Int, resumePage: Int): Int {
        require(pageCount > 0) { "pageCount must be positive" }
        require(resumePage in 0 until pageCount) {
            "resumePage=$resumePage is outside 0 until $pageCount"
        }
        return pageCount - resumePage
    }

    fun gestureSamples(): List<GestureSample> = buildList {
        add(GestureSample(TouchAction.DOWN, 0L, startYFraction))
        var offset = sampleIntervalMs
        while (offset < strokeDurationMs) {
            val progress = offset.toDouble() / strokeDurationMs.toDouble()
            add(
                GestureSample(
                    TouchAction.MOVE,
                    offset,
                    startYFraction + (endYFraction - startYFraction) * progress,
                )
            )
            offset += sampleIntervalMs
        }
        add(GestureSample(TouchAction.UP, strokeDurationMs, endYFraction))
    }
}

internal enum class TouchAction {
    DOWN,
    MOVE,
    UP,
}

internal data class GestureSample(
    val action: TouchAction,
    val offsetMs: Long,
    val yFraction: Double,
)

/** Keeps producer startup outside the immutable physical-input schedule. */
internal object ContinuousInputSchedulePolicy {
    fun initialScheduleStartMs(producerReadyUptimeMs: Long): Long {
        require(producerReadyUptimeMs >= 0L) { "producer uptime must not be negative" }
        return producerReadyUptimeMs
    }

    fun plannedSampleTimeMs(
        scheduleStartMs: Long,
        gesture: Int,
        sampleOffsetMs: Long,
    ): Long {
        require(scheduleStartMs >= 0L) { "schedule start must not be negative" }
        require(gesture >= 0) { "gesture must not be negative" }
        require(sampleOffsetMs >= 0L) { "sample offset must not be negative" }
        return scheduleStartMs + gesture * ResumeTraversalPlan.cycleDurationMs + sampleOffsetMs
    }
}

/** Enforces prepare -> channel arm -> immutable schedule release. */
internal class ContinuousInputStartOrder {
    private var phase = Phase.CREATED

    @Synchronized
    fun markPrepared() {
        check(phase == Phase.CREATED) { "input producer prepare order was $phase" }
        phase = Phase.PREPARED
    }

    @Synchronized
    fun markChannelArmed() {
        check(phase == Phase.PREPARED) { "p0 channel arm order was $phase" }
        phase = Phase.CHANNEL_ARMED
    }

    @Synchronized
    fun markScheduleReleased() {
        check(phase == Phase.CHANNEL_ARMED) { "input schedule release order was $phase" }
        phase = Phase.RELEASED
    }

    @Synchronized
    fun isReleased(): Boolean = phase == Phase.RELEASED

    private enum class Phase {
        CREATED,
        PREPARED,
        CHANNEL_ARMED,
        RELEASED,
    }
}

internal object ActualImageTimestampPolicy {
    fun exactFirstActualAtOrNull(
        clickAtNanos: Long,
        actualAtNanos: Long?,
        actualPresentedAtNanos: Long?,
    ): Long? {
        if (clickAtNanos <= 0L || actualAtNanos == null || actualPresentedAtNanos == null) {
            return null
        }
        if (actualAtNanos <= 0L || actualAtNanos < clickAtNanos) return null
        if (actualPresentedAtNanos <= 0L || actualPresentedAtNanos < clickAtNanos) return null
        if (actualPresentedAtNanos < actualAtNanos) return null
        return actualAtNanos
    }
}

internal data class ActualImageCandidateEvidence(
    val description: String,
    val observedAtNanos: Long,
)

/** Selects the exact immutable resume identity from every simultaneously exposed actual node. */
internal object ActualImageCandidatePolicy {
    private val identityPattern = Regex("^actual:(.+):(\\d+):(\\d+)(?:;.*)?$")

    fun select(
        descriptions: Iterable<String>,
        clickAtNanos: Long,
        expectedEpisodePath: String?,
        expectedSourcePage: Int?,
    ): ActualImageCandidateEvidence? {
        for (description in descriptions) {
            val identity = identityPattern.matchEntire(description) ?: continue
            if (expectedEpisodePath != null &&
                identity.groupValues[1] != expectedEpisodePath
            ) continue
            if (expectedSourcePage != null) {
                if (description.telemetryValue("firstActualEpisode") != expectedEpisodePath) {
                    continue
                }
                if (description.telemetryLong("firstActualSourcePage")?.toInt() !=
                    expectedSourcePage
                ) continue
            }
            val observedAtNanos = ActualImageTimestampPolicy.exactFirstActualAtOrNull(
                clickAtNanos = clickAtNanos,
                actualAtNanos = description.telemetryLong("actualAtNanos"),
                actualPresentedAtNanos = description.telemetryLong("actualPresentedAtNanos"),
            ) ?: continue
            return ActualImageCandidateEvidence(description, observedAtNanos)
        }
        return null
    }

    private fun String.telemetryLong(field: String): Long? =
        Regex("(?:^|;)${Regex.escape(field)}=(\\d+)(?:;|$)")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

    private fun String.telemetryValue(field: String): String? =
        Regex("(?:^|;)${Regex.escape(field)}=([^;]+)(?:;|$)")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
}

internal object HarnessPollingPolicy {
    // The committed node embeds its exact app timestamp, so accessibility observation frequency
    // does not determine the reported first-image time.
    const val actualImagePollMs: Long = 125L
    // Terminal failure is a diagnostic side channel, not the success clock. A second full tree
    // query on every 125 ms success poll kept system_server busy through the first gesture and
    // produced repeatable 66 ms input wake lateness. Check it promptly once, then at 1 Hz.
    const val terminalFailurePollMs: Long = 1_000L

    fun nextTerminalFailurePollAtMs(queryCompletedAtMs: Long): Long =
        queryCompletedAtMs + terminalFailurePollMs

    // Continuous qualification already has exact physical IPC plus semantic event/commit data.
    // A full UiAutomator tree snapshot would only compete with the input producer and system_server.
    fun shouldPollActualImageTree(continuousInputPresent: Boolean): Boolean =
        !continuousInputPresent
}

/** Maps each production content mode to its visible home RecyclerView, with a safe fallback. */
internal object HomeContinueRecyclerPolicy {
    private const val webtoonRecycler = "main_recycler"
    private const val comicRecycler = "main_comic_recycler"

    fun resourceNames(workType: String): List<String> = when (workType) {
        "webtoon" -> listOf(webtoonRecycler, comicRecycler)
        "manhwa" -> listOf(comicRecycler, webtoonRecycler)
        else -> throw IllegalArgumentException("Unsupported work type: $workType")
    }
}

/** Separates target-app behavior from corruption introduced by the input-producing harness. */
internal object ContinuousInputCadencePolicy {
    private const val minimumAchievedViewportPerSecond = 2.75
    private const val maximumAchievedViewportPerSecond = 3.35

    fun interGestureIdleMs(
        previousUpCallFinishedMs: Long,
        nextDownCallStartedMs: Long,
    ): Long? {
        if (previousUpCallFinishedMs <= 0L || nextDownCallStartedMs < previousUpCallFinishedMs) {
            return null
        }
        return nextDownCallStartedMs - previousUpCallFinishedMs
    }

    fun producerPriorityInvalidReason(
        requiredPriority: Int,
        actualPriority: Int,
    ): String? = if (actualPriority > requiredPriority) {
        "input producer priority=$actualPriority is less favorable than required=$requiredPriority"
    } else {
        null
    }

    fun staleSampleInvalidReason(
        scheduleLatenessMs: Long,
        maxScheduleLatenessMs: Long,
    ): String? = if (scheduleLatenessMs < 0L ||
        scheduleLatenessMs > maxScheduleLatenessMs
    ) {
        "stale input sample scheduleLateness=${scheduleLatenessMs}ms>" +
            "${maxScheduleLatenessMs}ms; catch-up injection is forbidden"
    } else {
        null
    }

    fun injectionCallInvalidReason(
        callDurationMs: Long,
        maxCallDurationMs: Long,
    ): String? = if (callDurationMs < 0L || callDurationMs > maxCallDurationMs) {
        "injectionCall=${callDurationMs}ms>${maxCallDurationMs}ms; " +
            "overdue MOVE catch-up is forbidden"
    } else {
        null
    }

    fun infrastructureInvalidReason(
        gestureCount: Int,
        achievedViewportPerSecond: Double,
        maxScheduleLatenessMs: Long,
        maxInjectionCallMs: Long,
        maxInterGestureIdleMs: Long,
        maxScheduleLatenessLimitMs: Long,
        maxInjectionCallLimitMs: Long,
        maxInterGestureIdleLimitMs: Long,
    ): String? {
        val problems = buildList {
            if (gestureCount <= 0) add("no physical forward gesture was injected")
            if (!achievedViewportPerSecond.isFinite() ||
                achievedViewportPerSecond !in
                    minimumAchievedViewportPerSecond..maximumAchievedViewportPerSecond
            ) {
                add("achievedViewportPerSecond=$achievedViewportPerSecond")
            }
            if (maxScheduleLatenessMs < 0L ||
                maxScheduleLatenessMs > maxScheduleLatenessLimitMs
            ) {
                add(
                    "scheduleLateness=${maxScheduleLatenessMs}ms>" +
                        "${maxScheduleLatenessLimitMs}ms"
                )
            }
            if (maxInjectionCallMs < 0L || maxInjectionCallMs > maxInjectionCallLimitMs) {
                add("injectionCall=${maxInjectionCallMs}ms>${maxInjectionCallLimitMs}ms")
            }
            if (maxInterGestureIdleMs < 0L ||
                maxInterGestureIdleMs > maxInterGestureIdleLimitMs
            ) {
                add(
                    "interGestureIdle=${maxInterGestureIdleMs}ms>" +
                        "${maxInterGestureIdleLimitMs}ms"
                )
            }
        }
        return problems.takeIf { it.isNotEmpty() }
            ?.joinToString(
                prefix = "input infrastructure was not trustworthy: ",
                separator = "; ",
            )
    }
}

internal data class ResumeTailAllImagesEvidence(
    val pageCount: Int,
    val readyAtNanos: Long,
)

/**
 * Recovers only self-authenticating resume-to-tail completion evidence from the exact launch
 * frame. This lets an independent later boundary/IPC failure remain a failure without erasing an
 * already-observed all-images measurement. Wrong episode/page/count or non-monotonic timestamps
 * fail closed and are left for the normal post-traversal UI proof.
 */
internal object ResumeTailAllImagesEvidencePolicy {
    private val readyPattern =
        Regex("(?:^|;)allReady=(\\d+);allReadyAtNanos=(\\d+)(?:;|$)")

    fun fromActualDescription(
        description: String,
        expectedEpisodePath: String,
        expectedResumePage: Int,
        expectedForwardPageCount: Int,
        clickElapsedNanos: Long,
        observedElapsedNanos: Long,
    ): ResumeTailAllImagesEvidence? {
        if (expectedEpisodePath.isBlank() || expectedResumePage < 0 ||
            expectedForwardPageCount <= 0 || clickElapsedNanos <= 0L ||
            observedElapsedNanos < clickElapsedNanos
        ) return null
        val identity = description.substringBefore(';')
        if (!identity.startsWith("actual:")) return null
        val identityParts = identity.removePrefix("actual:").split(':')
        if (identityParts.size < 3) return null
        val sourceIndex = identityParts[identityParts.lastIndex - 1].toIntOrNull() ?: return null
        val episodePath = identityParts.dropLast(2).joinToString(":")
        if (episodePath != expectedEpisodePath || sourceIndex != expectedResumePage) return null

        val ready = readyPattern.find(description) ?: return null
        val pageCount = ready.groupValues[1].toIntOrNull() ?: return null
        val readyAtNanos = ready.groupValues[2].toLongOrNull() ?: return null
        if (pageCount != expectedForwardPageCount) return null
        if (readyAtNanos !in clickElapsedNanos..observedElapsedNanos) return null
        return ResumeTailAllImagesEvidence(pageCount, readyAtNanos)
    }
}

internal data class AdjacentForwardEvidence(
    val adjacentWorkStartedAtNanos: Long = 0L,
    val adjacentRunwayReadyAtNanos: Long = 0L,
    val adjacentRunwayTargetEpisode: String = "",
    val adjacentRunwayPageCount: Int = 0,
    val adjacentTotalPageCount: Int = 0,
    val forwardBoundaryReachedAtNanos: Long = 0L,
    val firstAdjacentActualAtNanos: Long = 0L,
    val firstAdjacentActualEpisode: String = "",
)

/**
 * Monotonic evidence reducer for the complete forward-adjacent observation stream.
 *
 * Accessibility descriptions are immutable snapshots of individual committed frames. A later p3
 * snapshot can legitimately contain zero for a one-shot that was already present on the launch or
 * p0 snapshot, so zero must never erase earlier evidence. Presentation timestamps can be revised to
 * an earlier compositor time by an out-of-order callback; the earliest non-zero value is canonical.
 */
internal class AdjacentForwardEvidenceAccumulator(
    private val expectedAdjacentEpisodePath: String,
) {
    init {
        require(expectedAdjacentEpisodePath.isNotBlank())
    }

    private var current = AdjacentForwardEvidence()

    val snapshot: AdjacentForwardEvidence
        get() = current

    fun observeActualDescription(description: String) {
        if (!description.startsWith("actual:")) return
        val runwayReadyAt = description.telemetryLong("adjacentRunwayReadyAtNanos")
        val runwayTarget = description.telemetryString("adjacentRunwayTargetEpisode")
            .takeUnless { it == "unknown" }
            .orEmpty()
        val runwayPageCount = description.telemetryLong("adjacentRunwayPageCount").toInt()
        val totalPageCount = description.telemetryLong("adjacentTotalPageCount").toInt()
        val firstAdjacentAt = description.telemetryLong("firstAdjacentActualAtNanos")
        val firstAdjacentEpisode = description.telemetryString("firstAdjacentActualEpisode")
            .takeUnless { it == "unknown" }
            .orEmpty()
        val exactRunway = runwayReadyAt > 0L &&
            runwayTarget == expectedAdjacentEpisodePath
        val exactFirstAdjacent = firstAdjacentAt > 0L &&
            firstAdjacentEpisode == expectedAdjacentEpisodePath
        current = current.copy(
            adjacentWorkStartedAtNanos = earliestPositive(
                current.adjacentWorkStartedAtNanos,
                description.telemetryLong("adjacentWorkStartedAtNanos"),
            ),
            adjacentRunwayReadyAtNanos = earliestPositive(
                current.adjacentRunwayReadyAtNanos,
                runwayReadyAt.takeIf { exactRunway } ?: 0L,
            ),
            adjacentRunwayTargetEpisode = chooseExactIdentity(
                current.adjacentRunwayTargetEpisode,
                runwayTarget.takeIf { exactRunway }.orEmpty(),
            ),
            adjacentRunwayPageCount = maxOf(
                current.adjacentRunwayPageCount,
                runwayPageCount.takeIf { exactRunway } ?: 0,
            ),
            adjacentTotalPageCount = maxOf(
                current.adjacentTotalPageCount,
                totalPageCount.takeIf { exactRunway } ?: 0,
            ),
            forwardBoundaryReachedAtNanos = earliestPositive(
                current.forwardBoundaryReachedAtNanos,
                description.telemetryLong("forwardBoundaryReachedAtNanos"),
            ),
            firstAdjacentActualAtNanos = earliestPositive(
                current.firstAdjacentActualAtNanos,
                firstAdjacentAt.takeIf { exactFirstAdjacent } ?: 0L,
            ),
            firstAdjacentActualEpisode = chooseExactIdentity(
                current.firstAdjacentActualEpisode,
                firstAdjacentEpisode.takeIf { exactFirstAdjacent }.orEmpty(),
            ),
        )
    }

    fun observeExactP0Ipc(payload: AdjacentP0IpcPayload) {
        if (payload.episodePath != expectedAdjacentEpisodePath || payload.sourceIndex != 0 ||
            payload.presentedAtNanos <= 0L
        ) return
        current = current.copy(
            firstAdjacentActualAtNanos = earliestPositive(
                current.firstAdjacentActualAtNanos,
                payload.presentedAtNanos,
            ),
            firstAdjacentActualEpisode = expectedAdjacentEpisodePath,
        )
    }

    fun observeExactRunwayReadyIpc(payload: AdjacentRunwayReadyIpcPayload) {
        if (payload.episodePath != expectedAdjacentEpisodePath ||
            payload.readyAtNanos <= 0L || payload.pageCount != 4 ||
            payload.totalPageCount < payload.pageCount
        ) return
        current = current.copy(
            adjacentRunwayReadyAtNanos = earliestPositive(
                current.adjacentRunwayReadyAtNanos,
                payload.readyAtNanos,
            ),
            adjacentRunwayTargetEpisode = expectedAdjacentEpisodePath,
            adjacentRunwayPageCount = maxOf(
                current.adjacentRunwayPageCount,
                payload.pageCount,
            ),
            adjacentTotalPageCount = maxOf(
                current.adjacentTotalPageCount,
                payload.totalPageCount,
            ),
        )
    }

    private fun earliestPositive(left: Long, right: Long): Long = when {
        left <= 0L -> right.coerceAtLeast(0L)
        right <= 0L -> left
        else -> minOf(left, right)
    }

    private fun chooseExactIdentity(left: String, right: String): String = when {
        left.isNotBlank() -> left
        right.isNotBlank() -> right
        else -> ""
    }

    private fun String.telemetryLong(field: String): Long =
        Regex("(?:^|;)${Regex.escape(field)}=(\\d+)(?:;|$)")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L

    private fun String.telemetryString(field: String): String =
        Regex("(?:^|;)${Regex.escape(field)}=([^;]*)(?:;|$)")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
}

internal object AdjacentSourceProgressPolicy {
    const val maxSemanticLagMs: Long = 240L
    const val maxGesturesFromSignalToSemanticProof: Int = 1

    fun invalidReason(
        sourceIndex: Int,
        presentedAtNanos: Long,
        semanticObservedAtNanos: Long,
        gesturesAtSignal: Int,
        gesturesAtSemanticProof: Int,
    ): String? {
        if (sourceIndex !in 0..3) return "source=$sourceIndex is outside p0-p3"
        if (presentedAtNanos <= 0L || semanticObservedAtNanos < presentedAtNanos) {
            return "source=$sourceIndex presented/semantic timestamps were invalid"
        }
        val semanticLagNanos = semanticObservedAtNanos - presentedAtNanos
        if (semanticLagNanos > maxSemanticLagMs * 1_000_000L) {
            return "source=$sourceIndex semanticLag=${semanticLagNanos / 1_000_000.0}ms>" +
                "${maxSemanticLagMs}ms"
        }
        if (gesturesAtSignal < 0 || gesturesAtSemanticProof < gesturesAtSignal) {
            return "source=$sourceIndex gesture checkpoints were invalid"
        }
        val gestureAdvance = gesturesAtSemanticProof - gesturesAtSignal
        if (gestureAdvance > maxGesturesFromSignalToSemanticProof) {
            return "source=$sourceIndex semantic proof advanced $gestureAdvance gestures after " +
                "presentation>${maxGesturesFromSignalToSemanticProof}"
        }
        return null
    }
}

/**
 * Reconstructs only the exact `actual:` identity already published by the benchmark app before
 * its semantic-commit IPC. This is an observer fallback for Android builds that coalesce the
 * matching accessibility event; callers expose it only after the independently accepted
 * compositor checkpoint and exact semantic IPC are bound.
 */
internal object AdjacentSemanticCommitDescriptionPolicy {
    fun build(
        episodePath: String,
        sourceIndex: Int,
        viewerGeneration: Long,
        presentedAtNanos: Long,
        firstAdjacentPresentedAtNanos: Long,
        forwardBoundaryReachedAtNanos: Long = 0L,
    ): String {
        require(episodePath.startsWith("/webtoon/") || episodePath.startsWith("/manhwa/"))
        require(sourceIndex in 0..3)
        require(viewerGeneration > 0L)
        require(presentedAtNanos > 0L)
        val firstAt = firstAdjacentPresentedAtNanos.coerceAtLeast(0L)
        val firstEpisode = if (firstAt > 0L) episodePath else "unknown"
        return "actual:$episodePath:$sourceIndex:$viewerGeneration" +
            ";actualAtNanos=$presentedAtNanos" +
            ";actualPresentedAtNanos=$presentedAtNanos" +
            ";adjacentWorkStartedAtNanos=0" +
            ";adjacentRunwayReadyAtNanos=0" +
            ";adjacentRunwayTargetEpisode=unknown" +
            ";adjacentRunwayPageCount=0" +
            ";adjacentTotalPageCount=0" +
            ";forwardBoundaryReachedAtNanos=" +
            forwardBoundaryReachedAtNanos.coerceAtLeast(0L) +
            ";firstAdjacentActualAtNanos=$firstAt" +
            ";firstAdjacentActualEpisode=$firstEpisode"
    }
}

/** Accessibility callbacks may arrive after a later physical IPC; wait for the missing source. */
internal object AdjacentSemanticTraversalOrderPolicy {
    fun shouldDefer(sourceIndex: Int, observedSourceCount: Int): Boolean =
        sourceIndex in 0..3 && observedSourceCount in 0..3 &&
            sourceIndex > observedSourceCount
}

/**
 * Records physical, defect-free presentation of the requested next episode's initial pages.
 *
 * `adjacentRunwayPageCount` remains useful corroborating telemetry, but it cannot complete this
 * gate. Each required source must itself appear in an `actual:` state produced by a committed,
 * identity-valid, full-quality viewport while real forward input is running. This prevents a
 * background-ready p0-p3 count from being mistaken for seamless reading UX.
 */
internal class AdjacentEpisodeProofGate(
    private val expectedEpisodePath: String,
    private val requiredRunwayPageCount: Int,
    private val requireSourceProgress: Boolean = true,
) {
    init {
        require(expectedEpisodePath.isNotBlank())
        require(requiredRunwayPageCount > 0)
    }

    var boundaryEntered: Boolean = false
        private set
    var boundaryDescription: String = ""
        private set
    var runwayDescription: String = ""
        private set
    var runwayDrawableCount: Int = 0
        private set
    var preparedRunwayPageCount: Int = 0
        private set
    var preparedRunwayDescription: String = ""
        private set

    val forwardEvidence = AdjacentForwardEvidenceAccumulator(expectedEpisodePath)

    private val physicallyObservedSources = BooleanArray(requiredRunwayPageCount)
    private val sourcePresentedAtNanos = LongArray(requiredRunwayPageCount)
    private val sourceGesturesAtPresentation = IntArray(requiredRunwayPageCount) { -1 }
    private val sourceSemanticObservedAtNanos = LongArray(requiredRunwayPageCount)
    private val sourceGesturesAtSemanticProof = IntArray(requiredRunwayPageCount) { -1 }

    var sourceProgressFailure: String? = null
        private set

    val isComplete: Boolean
        get() = boundaryEntered && runwayDrawableCount == requiredRunwayPageCount

    val observedSourceIndices: List<Int>
        get() = physicallyObservedSources.indices.filter(physicallyObservedSources::get)

    val presentedTimestamps: List<Long>
        get() = sourcePresentedAtNanos.toList()

    val gesturesAtPresentation: List<Int>
        get() = sourceGesturesAtPresentation.toList()

    val semanticObservedTimestamps: List<Long>
        get() = sourceSemanticObservedAtNanos.toList()

    val gesturesAtSemanticProof: List<Int>
        get() = sourceGesturesAtSemanticProof.toList()

    val sourceProgressComplete: Boolean
        get() = !requireSourceProgress || sourceProgressFailure == null &&
            sourcePresentedAtNanos.all { it > 0L } &&
            sourceSemanticObservedAtNanos.all { it > 0L } &&
            sourceGesturesAtPresentation.all { it >= 0 } &&
            sourceGesturesAtSemanticProof.all { it >= 0 }

    fun observe(
        actualEpisodePath: String,
        actualSourceIndex: Int,
        adjacentTotalPageCount: Int,
        adjacentRunwayPageCount: Int,
        adjacentRunwayTargetEpisode: String,
        firstAdjacentActualAtNanos: Long,
        firstAdjacentActualEpisode: String,
        description: String,
        presentedAtNanos: Long = 0L,
        gesturesAtPresentation: Int = -1,
        semanticObservedAtNanos: Long = 0L,
        gesturesAtSemanticProof: Int = -1,
    ): AdjacentProofUpdate {
        forwardEvidence.observeActualDescription(description)
        val enteredNow = !boundaryEntered &&
            actualEpisodePath == expectedEpisodePath &&
            actualSourceIndex == 0
        if (enteredNow) {
            boundaryEntered = true
            boundaryDescription = description
        }

        val physicalSourceObservedNow = boundaryEntered &&
            actualEpisodePath == expectedEpisodePath &&
            actualSourceIndex in physicallyObservedSources.indices &&
            !physicallyObservedSources[actualSourceIndex]
        if (physicalSourceObservedNow) {
            val expectedSource = physicallyObservedSources.count { it }
            val progressReason = when {
                actualSourceIndex != expectedSource ->
                    "adjacent sources were not presented in order: expected=$expectedSource " +
                        "actual=$actualSourceIndex"
                !requireSourceProgress -> null
                actualSourceIndex > 0 && presentedAtNanos <
                    sourcePresentedAtNanos[actualSourceIndex - 1] ->
                    "adjacent source presentation timestamps were not monotonic at " +
                        "source=$actualSourceIndex"
                else -> AdjacentSourceProgressPolicy.invalidReason(
                    sourceIndex = actualSourceIndex,
                    presentedAtNanos = presentedAtNanos,
                    semanticObservedAtNanos = semanticObservedAtNanos,
                    gesturesAtSignal = gesturesAtPresentation,
                    gesturesAtSemanticProof = gesturesAtSemanticProof,
                )
            }
            if (progressReason == null) {
                physicallyObservedSources[actualSourceIndex] = true
                sourcePresentedAtNanos[actualSourceIndex] = presentedAtNanos
                sourceGesturesAtPresentation[actualSourceIndex] = gesturesAtPresentation
                sourceSemanticObservedAtNanos[actualSourceIndex] = semanticObservedAtNanos
                sourceGesturesAtSemanticProof[actualSourceIndex] = gesturesAtSemanticProof
                runwayDrawableCount = physicallyObservedSources.count { it }
                runwayDescription = description
            } else if (sourceProgressFailure == null) {
                sourceProgressFailure = progressReason
            }
        }

        val exactRunwayReady = adjacentTotalPageCount >= requiredRunwayPageCount &&
            adjacentRunwayPageCount == requiredRunwayPageCount &&
            adjacentRunwayTargetEpisode == expectedEpisodePath &&
            firstAdjacentActualAtNanos > 0L &&
            firstAdjacentActualEpisode == expectedEpisodePath
        if (exactRunwayReady) {
            preparedRunwayPageCount = adjacentRunwayPageCount
            preparedRunwayDescription = description
        }

        return AdjacentProofUpdate(
            boundaryEnteredNow = enteredNow,
            physicalSourceObservedNow = physicalSourceObservedNow && sourceProgressFailure == null,
            complete = isComplete,
            sourceProgressFailure = sourceProgressFailure,
        )
    }
}

internal data class AdjacentProofUpdate(
    val boundaryEnteredNow: Boolean,
    val physicalSourceObservedNow: Boolean,
    val complete: Boolean,
    val sourceProgressFailure: String?,
)

/** Pure validity contract for the benchmark's p0 observation path. */
internal object AdjacentP0TimingPolicy {
    const val maxDetectionLagMs: Long = 240L

    fun detectionLagNanos(
        firstAdjacentActualAtNanos: Long,
        harnessObservedAtNanos: Long,
    ): Long? {
        if (firstAdjacentActualAtNanos <= 0L || harnessObservedAtNanos <= 0L) return null
        return harnessObservedAtNanos - firstAdjacentActualAtNanos
    }

    fun status(
        firstAdjacentActualAtNanos: Long,
        harnessObservedAtNanos: Long,
    ): AdjacentP0MeasurementStatus {
        val lagNanos = detectionLagNanos(
            firstAdjacentActualAtNanos,
            harnessObservedAtNanos,
        ) ?: return AdjacentP0MeasurementStatus.UNMEASURED
        return if (lagNanos in 0L..maxDetectionLagMs * 1_000_000L) {
            AdjacentP0MeasurementStatus.VALID
        } else {
            AdjacentP0MeasurementStatus.MEASUREMENT_INVALID
        }
    }
}

internal enum class AdjacentP0MeasurementStatus {
    UNMEASURED,
    VALID,
    MEASUREMENT_INVALID,
}

internal data class AdjacentP0IpcPayload(
    val nonce: String,
    val caseId: String,
    val episodePath: String,
    val sourceIndex: Int,
    val presentedAtNanos: Long,
    val senderAtNanos: Long,
    val viewerGeneration: Long,
)

internal data class AdjacentRunwayReadyIpcPayload(
    val nonce: String,
    val caseId: String,
    val episodePath: String,
    val readyAtNanos: Long,
    val pageCount: Int,
    val totalPageCount: Int,
    val senderAtNanos: Long,
    val viewerGeneration: Long,
)

/** Exact identity/count/clock contract for the app-owned p0-p3 drawable-residency signal. */
internal object AdjacentRunwayReadyIpcSignalPolicy {
    fun rejection(
        expectedNonce: String,
        expectedCaseId: String,
        expectedEpisodePath: String,
        expectedRunwayPageCount: Int,
        expectedViewerGeneration: Long?,
        payload: AdjacentRunwayReadyIpcPayload,
        receivedAtNanos: Long,
    ): AdjacentP0IpcRejectReason = when {
        payload.nonce != expectedNonce -> AdjacentP0IpcRejectReason.NONCE
        payload.caseId != expectedCaseId -> AdjacentP0IpcRejectReason.CASE_ID
        payload.episodePath != expectedEpisodePath -> AdjacentP0IpcRejectReason.EPISODE_PATH
        payload.pageCount != expectedRunwayPageCount ||
            payload.totalPageCount < payload.pageCount -> AdjacentP0IpcRejectReason.PAGE_COUNT
        payload.viewerGeneration <= 0L ||
            expectedViewerGeneration?.let { payload.viewerGeneration != it } == true ->
            AdjacentP0IpcRejectReason.GENERATION
        payload.readyAtNanos <= 0L -> AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP
        payload.senderAtNanos < payload.readyAtNanos ->
            AdjacentP0IpcRejectReason.SENDER_TIMESTAMP
        receivedAtNanos < payload.senderAtNanos -> AdjacentP0IpcRejectReason.RECEIVER_TIMESTAMP
        else -> AdjacentP0IpcRejectReason.NONE
    }
}

internal data class AdjacentSemanticCommitPayload(
    val nonce: String,
    val caseId: String,
    val episodePath: String,
    val sourceIndex: Int,
    val presentedAtNanos: Long,
    val semanticPublishedAtNanos: Long,
    val forwardBoundaryReachedAtNanos: Long = 0L,
    val senderAtNanos: Long,
    val viewerGeneration: Long,
)

/** A semantic phase is usable only when it is an exact continuation of physical IPC proof. */
internal object AdjacentSemanticCommitSignalPolicy {
    fun rejection(
        expectedNonce: String,
        expectedCaseId: String,
        expectedEpisodePath: String,
        physicalPayload: AdjacentP0IpcPayload,
        semanticPayload: AdjacentSemanticCommitPayload,
        receivedAtNanos: Long,
    ): AdjacentP0IpcRejectReason = when {
        semanticPayload.nonce != expectedNonce -> AdjacentP0IpcRejectReason.NONCE
        semanticPayload.caseId != expectedCaseId -> AdjacentP0IpcRejectReason.CASE_ID
        semanticPayload.episodePath != expectedEpisodePath ->
            AdjacentP0IpcRejectReason.EPISODE_PATH
        semanticPayload.sourceIndex !in 0..3 -> AdjacentP0IpcRejectReason.SOURCE_INDEX
        semanticPayload.viewerGeneration <= 0L -> AdjacentP0IpcRejectReason.GENERATION
        semanticPayload.presentedAtNanos <= 0L ->
            AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP
        semanticPayload.semanticPublishedAtNanos < semanticPayload.presentedAtNanos ->
            AdjacentP0IpcRejectReason.SEMANTIC_TIMESTAMP
        semanticPayload.sourceIndex == 0 &&
            (semanticPayload.forwardBoundaryReachedAtNanos <= 0L ||
                semanticPayload.forwardBoundaryReachedAtNanos >
                    semanticPayload.presentedAtNanos) ->
            AdjacentP0IpcRejectReason.BOUNDARY_TIMESTAMP
        semanticPayload.senderAtNanos < semanticPayload.semanticPublishedAtNanos ->
            AdjacentP0IpcRejectReason.SENDER_TIMESTAMP
        receivedAtNanos < semanticPayload.senderAtNanos ->
            AdjacentP0IpcRejectReason.RECEIVER_TIMESTAMP
        semanticPayload.nonce != physicalPayload.nonce ||
            semanticPayload.caseId != physicalPayload.caseId ||
            semanticPayload.episodePath != physicalPayload.episodePath ||
            semanticPayload.sourceIndex != physicalPayload.sourceIndex ||
            semanticPayload.viewerGeneration != physicalPayload.viewerGeneration ||
            semanticPayload.presentedAtNanos != physicalPayload.presentedAtNanos ->
            AdjacentP0IpcRejectReason.PHYSICAL_MISMATCH
        else -> AdjacentP0IpcRejectReason.NONE
    }
}

internal data class AdjacentP0IpcStageLags(
    val presentedToSenderNanos: Long,
    val senderToReceiverNanos: Long,
    val receiverToAcceptanceNanos: Long,
) {
    val presentedToAcceptanceNanos: Long
        get() = presentedToSenderNanos + senderToReceiverNanos + receiverToAcceptanceNanos
}

/** Pure decomposition used to distinguish app callback backlog from IPC acceptance handling. */
internal object AdjacentP0IpcTimingPolicy {
    fun stageLags(
        payload: AdjacentP0IpcPayload,
        receivedAtNanos: Long,
        acceptedAtNanos: Long,
    ): AdjacentP0IpcStageLags? {
        if (payload.presentedAtNanos <= 0L ||
            payload.senderAtNanos < payload.presentedAtNanos ||
            receivedAtNanos < payload.senderAtNanos ||
            acceptedAtNanos < receivedAtNanos
        ) return null
        return AdjacentP0IpcStageLags(
            presentedToSenderNanos = payload.senderAtNanos - payload.presentedAtNanos,
            senderToReceiverNanos = receivedAtNanos - payload.senderAtNanos,
            receiverToAcceptanceNanos = acceptedAtNanos - receivedAtNanos,
        )
    }
}

/** Pure identity and cross-process monotonic-clock contract for the p0 broadcast. */
internal object AdjacentP0IpcSignalPolicy {
    fun rejection(
        expectedNonce: String,
        expectedCaseId: String,
        expectedEpisodePath: String,
        payload: AdjacentP0IpcPayload,
        receivedAtNanos: Long,
    ): AdjacentP0IpcRejectReason = when {
        payload.nonce != expectedNonce -> AdjacentP0IpcRejectReason.NONCE
        payload.caseId != expectedCaseId -> AdjacentP0IpcRejectReason.CASE_ID
        payload.episodePath != expectedEpisodePath -> AdjacentP0IpcRejectReason.EPISODE_PATH
        payload.sourceIndex != 0 -> AdjacentP0IpcRejectReason.SOURCE_INDEX
        payload.viewerGeneration <= 0L -> AdjacentP0IpcRejectReason.GENERATION
        payload.presentedAtNanos <= 0L -> AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP
        payload.senderAtNanos < payload.presentedAtNanos ->
            AdjacentP0IpcRejectReason.SENDER_TIMESTAMP
        receivedAtNanos < payload.senderAtNanos -> AdjacentP0IpcRejectReason.RECEIVER_TIMESTAMP
        else -> AdjacentP0IpcRejectReason.NONE
    }
}

/**
 * Prevents a prepared/armed channel from proving p0 before the first physical DOWN begins.
 * A terminal Continue is the sole exception: its remaining current image can be shorter than the
 * viewport, so an exact p0 compositor checkpoint is legitimately part of the first opaque frame.
 */
internal object AdjacentP0AfterInputStartPolicy {
    fun rejection(
        firstDownInjectionStartedAtNanos: Long,
        presentedAtNanos: Long,
        acceptedAtNanos: Long,
        allowTerminalResumeInitialViewport: Boolean = false,
    ): AdjacentP0IpcRejectReason = if (
        !allowTerminalResumeInitialViewport && (
        firstDownInjectionStartedAtNanos <= 0L ||
        presentedAtNanos < firstDownInjectionStartedAtNanos ||
        acceptedAtNanos < firstDownInjectionStartedAtNanos
        )
    ) {
        AdjacentP0IpcRejectReason.EARLY_SIGNAL
    } else {
        AdjacentP0IpcRejectReason.NONE
    }
}

/** Exact identity/clock contract for the benchmark-only p1-p3 presentation checkpoints. */
internal object AdjacentRunwayIpcSignalPolicy {
    fun rejection(
        expectedNonce: String,
        expectedCaseId: String,
        expectedEpisodePath: String,
        expectedViewerGeneration: Long,
        expectedSourceIndex: Int,
        payload: AdjacentP0IpcPayload,
        receivedAtNanos: Long,
    ): AdjacentP0IpcRejectReason = when {
        payload.nonce != expectedNonce -> AdjacentP0IpcRejectReason.NONCE
        payload.caseId != expectedCaseId -> AdjacentP0IpcRejectReason.CASE_ID
        payload.episodePath != expectedEpisodePath -> AdjacentP0IpcRejectReason.EPISODE_PATH
        payload.sourceIndex != expectedSourceIndex -> AdjacentP0IpcRejectReason.SOURCE_ORDER
        payload.viewerGeneration <= 0L ||
            payload.viewerGeneration != expectedViewerGeneration ->
            AdjacentP0IpcRejectReason.GENERATION
        payload.presentedAtNanos <= 0L -> AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP
        payload.senderAtNanos < payload.presentedAtNanos ->
            AdjacentP0IpcRejectReason.SENDER_TIMESTAMP
        receivedAtNanos < payload.senderAtNanos -> AdjacentP0IpcRejectReason.RECEIVER_TIMESTAMP
        else -> AdjacentP0IpcRejectReason.NONE
    }
}

internal data class AdjacentSemanticObservationSelection(
    val observedAtNanos: Long,
    val mode: String,
)

/** Binds a semantic actual-state revision to one exact physical compositor checkpoint. */
internal object AdjacentSemanticRevisionBindingPolicy {
    fun matchesPhysicalCheckpoint(
        physicalPresentedAtNanos: Long,
        semanticActualPresentedAtNanos: Long,
    ): Boolean = physicalPresentedAtNanos > 0L &&
        semanticActualPresentedAtNanos == physicalPresentedAtNanos
}

/**
 * Chooses a post-compositor semantic observation without rewriting an early accessibility event.
 * The independent 240 ms gate remains in [AdjacentSourceProgressPolicy].
 */
internal object AdjacentSemanticObservationPolicy {
    const val EVENT_TIME = "ACCESSIBILITY_EVENT_TIME"
    const val CALLBACK_FLOOR = "CALLBACK_FLOOR"
    const val SEMANTIC_COMMIT_TIME = "SEMANTIC_COMMIT_TIME"
    const val UIAUTOMATOR_FALLBACK = "UIAUTOMATOR_POLL_FALLBACK"

    fun select(
        presentedAtNanos: Long,
        acceptedAtNanos: Long,
        eventPublishedAtNanos: Long?,
        eventCallbackAtNanos: Long?,
        semanticCommitPublishedAtNanos: Long? = null,
        uiObservedAtNanos: Long,
    ): AdjacentSemanticObservationSelection {
        if (eventPublishedAtNanos != null &&
            eventCallbackAtNanos != null &&
            eventPublishedAtNanos >= presentedAtNanos
        ) {
            return AdjacentSemanticObservationSelection(eventPublishedAtNanos, EVENT_TIME)
        }
        if (eventPublishedAtNanos != null &&
            eventCallbackAtNanos != null &&
            eventPublishedAtNanos < presentedAtNanos &&
            eventCallbackAtNanos >= presentedAtNanos
        ) {
            return AdjacentSemanticObservationSelection(
                maxOf(eventCallbackAtNanos, acceptedAtNanos),
                CALLBACK_FLOOR,
            )
        }
        if (semanticCommitPublishedAtNanos != null &&
            semanticCommitPublishedAtNanos >= presentedAtNanos
        ) {
            return AdjacentSemanticObservationSelection(
                semanticCommitPublishedAtNanos,
                SEMANTIC_COMMIT_TIME,
            )
        }
        return AdjacentSemanticObservationSelection(
            uiObservedAtNanos,
            UIAUTOMATOR_FALLBACK,
        )
    }
}

internal enum class AdjacentP0IpcRejectReason {
    NONE,
    NONCE,
    CASE_ID,
    EPISODE_PATH,
    SOURCE_INDEX,
    GENERATION,
    PRESENTED_TIMESTAMP,
    SENDER_TIMESTAMP,
    RECEIVER_TIMESTAMP,
    SOURCE_ORDER,
    EARLY_SIGNAL,
    DUPLICATE,
    PHASE,
    SEMANTIC_TIMESTAMP,
    BOUNDARY_TIMESTAMP,
    PHYSICAL_MISMATCH,
    PAGE_COUNT,
}
