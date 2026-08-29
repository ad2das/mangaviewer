package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiAdjacentRunwayTailHandoffTest {
    @Test
    fun handsOffOnlyTheNextContiguousPageInsideTheInitialRunway() {
        fun should(
            installed: Set<Int> = setOf(0, 1, 2),
            ready: Int = 3,
            required: Int = 4,
            path: String = "/webtoon/work/next",
            directWifiStrictAdjacent: Boolean = true,
            cancelled: Boolean = false,
        ) = NtkDirectWifiAdjacentRunwayTailHandoffPolicy.shouldHandoff(
            cancelled = cancelled,
            directWifiStrictAdjacent = directWifiStrictAdjacent,
            episodePath = path,
            requiredRunwayPageCount = required,
            installedSourceIndexes = installed,
            firstReadySourceIndex = ready,
        )

        assertTrue(should())
        assertTrue(should(installed = setOf(0), ready = 1))
        assertTrue(
            should(
                installed = setOf(0),
                ready = 1,
                required = 5,
                path = "/manhwa/work/next",
            )
        )
        assertFalse(should(installed = setOf(0, 1, 2, 3), ready = 4))
        assertFalse(should(installed = setOf(0, 2), ready = 3))
        assertFalse(should(ready = 4))
        assertTrue(should(path = "/manhwa/work/next"))
        assertFalse(should(path = "/novel/work/next"))
        assertFalse(should(directWifiStrictAdjacent = false))
        assertFalse(should(cancelled = true))

        assertTrue(
            NtkDirectWifiAdjacentRunwayTailHandoffPolicy.shouldOwnWait(
                cancelled = false,
                directWifiStrictAdjacent = true,
                episodePath = "/webtoon/work/next",
                requiredRunwayPageCount = 4,
                installedSourceIndexes = setOf(0, 1, 2),
            )
        )
    }

    @Test
    fun sourceCohortCountsEveryAutoCutSideWithoutCountingItAsAnotherSource() {
        fun refs(sources: List<Int>, requiredSources: Int) =
            NtkAdjacentRunwaySourceCohortPolicy.leadingRefCount(sources, requiredSources)

        assertTrue(refs(listOf(0, 1, 2), 1) == 1)
        assertTrue(refs(listOf(0, 0, 1, 2), 1) == 2)
        assertTrue(refs(listOf(1, 1, 2, 3, 4), 4) == 5)
        assertTrue(refs(listOf(1, 1, 2, 3, 4, 5), 4) == 5)
        assertTrue(refs(listOf(1, 3, 4), 2) == 0)
        assertTrue(refs(listOf(1, 1), 2) == 0)
        assertTrue(refs(listOf(0, 0, 0, 1), 1) == 0)
        val requiredTailSources =
            NtkDirectWifiAdjacentAtomicRunwayTailPolicy.requiredReadyCount(
                directWifiStrictAdjacent = true,
                episodePath = "/manhwa/work/next",
                requiredRunwayPageCount = 5,
                installedSourceIndexes = setOf(0),
            )
        assertTrue(requiredTailSources == 1)
        assertTrue(refs(listOf(1, 1, 2, 3, 4), requiredTailSources) == 2)
    }

    @Test
    fun publishesTheBoundedTailOnlyWhenEveryRemainingRunwayBodyIsReady() {
        fun required(
            installed: Set<Int>,
            direct: Boolean = true,
            path: String = "/webtoon/work/next",
        ) = NtkDirectWifiAdjacentAtomicRunwayTailPolicy.requiredReadyCount(
            directWifiStrictAdjacent = direct,
            episodePath = path,
            requiredRunwayPageCount = 4,
            installedSourceIndexes = installed,
        )

        assertTrue(required(setOf(0)) == 3)
        assertTrue(required(setOf(0, 1)) == 2)
        assertTrue(required(setOf(0, 1, 2)) == 1)
        assertTrue(required(emptySet()) == 1)
        assertTrue(required(setOf(0), direct = false) == 1)
        assertTrue(required(setOf(0), path = "/novel/work/next") == 1)
    }

    @Test
    fun attachesOnlyTheCompleteManhwaP0SourceCohort() {
        fun attached(
            path: String = "/manhwa/work/next",
            sources: List<Int> = listOf(0, 1, 2, 3),
            ready: List<Boolean> = listOf(true, true, true, true),
            direct: Boolean = true,
            exactDescriptorOnly: Boolean = false,
            sides: List<Int> = sources.map { 0 },
        ) = NtkDirectWifiAdjacentInitialAtomicRunwayPolicy.attachedImagePageCount(
            directWifiStrictAdjacent = direct,
            episodePath = path,
            sourceIndexes = sources,
            publishable = ready,
            maximumRunwayPages = 4,
            strictExactDescriptorOnly = exactDescriptorOnly,
            sourceSides = sides,
        )

        assertTrue(attached() == 1)
        assertTrue(attached(sources = listOf(0, 1), ready = listOf(true, true)) == 1)
        assertTrue(attached(ready = listOf(true, true, true, false)) == 1)
        assertTrue(attached(sources = listOf(0, 2, 1, 3)) == 1)
        assertTrue(attached(path = "/webtoon/work/next") == 1)
        assertTrue(attached(direct = false) == 1)
        assertTrue(attached(exactDescriptorOnly = true) == 1)
        assertTrue(
            attached(
                ready = listOf(true, true, false, true),
                exactDescriptorOnly = true,
            ) == 1,
        )
        assertTrue(
            attached(
                ready = listOf(false, true, true, true),
                exactDescriptorOnly = true,
            ) == 0,
        )
        assertTrue(
            attached(
                sources = listOf(0, 1),
                ready = listOf(true, true),
                exactDescriptorOnly = true,
            ) == 1,
        )
        assertTrue(
            attached(
                sources = listOf(0, 0, 1, 2),
                sides = listOf(0, 1, 0, 0),
                exactDescriptorOnly = true,
            ) == 2,
        )
        assertTrue(
            attached(
                sources = listOf(0, 0, 1, 2, 3),
                ready = listOf(true, true, true, true, true),
                sides = listOf(0, 1, 0, 0, 0),
                exactDescriptorOnly = true,
            ) == 2,
        )
        assertTrue(
            attached(
                sources = listOf(0, 0, 1, 2),
                ready = listOf(true, true, false, true),
                sides = listOf(0, 1, 0, 0),
                exactDescriptorOnly = true,
            ) == 2,
        )
        assertTrue(
            attached(
                sources = listOf(0, 0, 1, 2),
                ready = listOf(true, false, true, true),
                sides = listOf(0, 1, 0, 0),
                exactDescriptorOnly = true,
            ) == 0,
        )
        assertTrue(
            attached(
                sources = listOf(0, 0, 1, 1),
                sides = listOf(0, 1, 0, 1),
                exactDescriptorOnly = true,
            ) == 2,
        )
        assertTrue(
            attached(
                sources = listOf(0, 0, 0, 1),
                sides = listOf(0, 1, 1, 0),
                exactDescriptorOnly = true,
            ) == 0,
        )
        assertTrue(
            attached(
                sources = listOf(0, 0, 1, 2),
                sides = listOf(0, 0, 0, 0),
                exactDescriptorOnly = true,
            ) == 0,
        )
        assertTrue(
            attached(
                sources = listOf(0, 2, 3, 4),
                exactDescriptorOnly = true,
            ) == 0,
        )
        assertTrue(attached(path = "/webtoon/work/next", exactDescriptorOnly = true) == 1)
        assertTrue(attached(direct = false, exactDescriptorOnly = true) == 1)
    }

    @Test
    fun mainCommitUsesOneShotControlHandoffBeforeTheNormalTimer() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val publish = block("private fun appendRemainingAdjacentRunwayRefs(", source)
        val handoff = block(
            "private fun tryHandoffDirectWifiAdjacentInitialRunwayTail(",
            source,
        )
        val ready = block(
            "private fun isDirectWifiAdjacentInitialRunwayTailReady(",
            source,
        )
        val snapshot = block(
            "private fun directWifiAdjacentInitialRunwayTailSnapshot(",
            source,
        )
        val deferredRecheck = block(
            "private fun deferStrictRemainingAdjacentWakeAfterRegistration(",
            source,
        )
        val batch = block("private fun prepareAdjacentRunwayDrawableBatch(", source)
        val initial = block("private fun appendResolvedEpisodeInitialRunway(", source)
        val runwayCount = block(
            "private fun initialAdjacentAppendRunwayRefCount(\n" +
                "        refs: List<PageRef>,\n" +
                "        strictExactDescriptorOnly: Boolean,",
            source,
        )
        val partialPolicy = block("fun attachedImagePageCount(", source)
        val p0Wake = block("private fun wakeInitialAdjacentManhwaRunwayAppend(", source)
        val publishLimit = block("private fun remainingAdjacentRunwayPublishPages(", source)
        val tailReady = block(
            "private fun directWifiAdjacentAtomicRunwayTailReadyRefCount(",
            source,
        )
        val refresh = block("private fun refreshRemainingAdjacentRunwayRefs(", source)
        val defer = block(
            "private fun shouldDeferRemainingAdjacentRunwayForActiveInput(",
            source,
        )

        assertTrue(publish.contains("tryHandoffDirectWifiAdjacentInitialRunwayTail("))
        assertTrue(
            publish.indexOf("tryHandoffDirectWifiAdjacentInitialRunwayTail(") <
                publish.lastIndexOf("scheduleRemainingAdjacentRunwayAppend(")
        )
        assertTrue(handoff.contains("directWifiAdjacentInitialRunwayTailHandoffs.add(path)"))
        assertTrue(handoff.contains("scheduleRemainingAdjacentRunwayAppend("))
        assertTrue(handoff.contains("waitingStrictRemainingAdjacentAppends[path] = waiting"))
        assertTrue(handoff.contains("isDirectWifiAdjacentInitialRunwayTailReady(target, refs, path)"))
        assertTrue(handoff.contains("wakeStrictRemainingAdjacentAppend(path)"))
        assertTrue(handoff.contains("directWifiAdjacentInitialRunwayTailHandoffs.remove(path)"))
        assertTrue(ready.contains("directWifiAdjacentInitialRunwayTailSnapshot("))
        assertTrue(snapshot.contains("isDirectWifiStrictAdjacentRunwayProfile(target, path)"))
        assertTrue(snapshot.contains("isAdjacentStrictSourceClaimLive(path, claim)"))
        assertTrue(snapshot.contains("requiredInitialAdjacentRunwayPages(target)"))
        assertTrue(snapshot.contains("isAdjacentRunwayRefPublishable"))
        assertTrue(publish.contains("waitingStrictRemainingAdjacentAppends[waitingPath] = waiting"))
        assertTrue(publish.contains("deferStrictRemainingAdjacentWakeAfterRegistration("))
        assertTrue(publish.contains("readyCount < atomicTailReadyRefCount"))
        assertTrue(
            publish.indexOf("waitingStrictRemainingAdjacentAppends[waitingPath] = waiting") <
                publish.indexOf("deferStrictRemainingAdjacentWakeAfterRegistration(")
        )
        assertTrue(deferredRecheck.contains("isDirectWifiAdjacentInitialRunwayTailReady("))
        assertTrue(deferredRecheck.contains("control.execute"))
        assertTrue(deferredRecheck.contains("waitingStrictRemainingAdjacentAppends[path] === waiting"))
        assertTrue(deferredRecheck.contains("wakeStrictRemainingAdjacentAppend(path)"))
        assertTrue(batch.contains("reason == \"initial_strict_source\""))
        assertTrue(batch.contains("val directWifiAtomicRunwayTail ="))
        assertTrue(batch.contains("val directWifiAtomicInitialManhwaRunway ="))
        assertTrue(batch.contains("indexedPages.size in 2 until atomicRunwayPageLimit"))
        assertTrue(batch.contains("indexedPages.size in 2..atomicRunwayPageLimit"))
        assertTrue(batch.contains("(1..indexedPages.size).toList()"))
        assertTrue(batch.contains("indexedPages.forEach"))
        assertTrue(initial.contains("tryHandoffDirectWifiAdjacentInitialRunwayTail("))
        assertTrue(
            initial.indexOf("tryHandoffDirectWifiAdjacentInitialRunwayTail(") <
                initial.indexOf("scheduleRemainingAdjacentRunwayAppend(")
        )
        assertTrue(
            partialPolicy.contains(
                "NtkAdjacentRunwaySourceCohortPolicy.leadingRefCount("
            )
        )
        assertTrue(partialPolicy.contains("publishable.take(exactLeadingSourceRefCount).all"))
        assertTrue(runwayCount.contains("imageRefs.map { strictAdjacentBodyDescriptor(it) != null }"))
        assertTrue(runwayCount.contains("val requiredRunwaySources ="))
        assertTrue(runwayCount.contains("NtkAdjacentRunwaySourceCohortPolicy.leadingRefCount("))
        assertTrue(runwayCount.contains("allImageRefs.map { it.sourceIndex }"))
        assertTrue(runwayCount.contains("val runway = if (cardOffset == 0) imageRunway + 1 else imageRunway"))
        assertTrue(initial.contains("requireStrictDescriptor = strictExactDescriptorOnly"))
        assertTrue(initial.contains("val remainingRefs = refs.drop(runwayCount)"))
        assertTrue(initial.contains("target,\n                            remainingRefs,"))
        assertTrue(p0Wake.contains("sourceIndex !in 0 until requiredRunwayPages"))
        assertFalse(p0Wake.contains("(0 until requiredRunwayPages).all"))
        assertTrue(publish.contains("readyCount < atomicTailReadyRefCount"))
        assertTrue(publish.contains("val nextSourceRefCount ="))
        assertTrue(publish.contains("remainingAdjacentRunwayPublishPages("))
        assertTrue(publishLimit.contains("nextSourceRefCount.coerceAtLeast(1)"))
        assertTrue(publishLimit.contains("installedAdjacentRunwaySourceIndexes(target).size"))
        assertFalse(publishLimit.contains("installedDrawablePageCountForEpisode(target)"))
        assertTrue(tailReady.contains("NtkDirectWifiAdjacentAtomicRunwayTailPolicy.requiredReadyCount("))
        assertTrue(tailReady.contains("NtkAdjacentRunwaySourceCohortPolicy.leadingRefCount("))
        assertTrue(tailReady.contains("candidates.map { it.sourceIndex }"))
        assertTrue(refresh.contains("val oldRefs = refs"))
        assertTrue(refresh.contains("oldRefs.groupBy { it.sourceIndex }"))
        assertTrue(refresh.contains("val sourceRepresentatives ="))
        assertTrue(refresh.contains("val refreshImagesBySource ="))
        assertTrue(refresh.contains("it.sourceIndex to it.side"))
        assertFalse(refresh.contains(".associateBy { it.sourceIndex }"))
        assertTrue(defer.contains("installedAdjacentRunwaySourceIndexes(target).size"))
        assertFalse(defer.contains("installedDrawablePageCountForEpisode(target)"))
        assertTrue(
            initial.indexOf("commitAdjacentRunwayDrawableBatch(drawableBatch)") <
                initial.indexOf("tryHandoffDirectWifiAdjacentInitialRunwayTail(")
        )
    }

    @Test
    fun initialRunwayAppendClaimsOneDecodeOwnerBeforeDoingBitmapWork() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val initial = block("private fun appendResolvedEpisodeInitialRunway(", source)
        val claim = initial.indexOf(
            "preparingInitialAdjacentRunways.putIfAbsent(targetPath, candidate)"
        )
        val racedPreparedTake = initial.indexOf(
            "val racedPreparedBatch = takePreparedInitialAdjacentRunwayBatch("
        )
        val decode = initial.indexOf("preparedBatch = prepareAdjacentRunwayDrawableBatch(")
        val staleFence = initial.indexOf(
            "isForwardAdjacentStructurePublicationCandidateCurrent(target)"
        )
        val structureCommit = initial.indexOf("pages.addAll(structureRefs)")
        val release = initial.lastIndexOf(
            "finishInitialAdjacentRunwayPreparation(targetPath, preparation)"
        )

        assertTrue(initial.contains(
            "while (preparedBatch == null && appendDecodePreparation == null)"
        ))
        assertTrue(claim >= 0)
        assertTrue(staleFence in 0 until claim)
        assertTrue(racedPreparedTake > claim)
        assertTrue(decode > racedPreparedTake)
        assertTrue(structureCommit > decode)
        assertTrue(release > structureCommit)
        assertTrue(initial.contains("append_adjacent_runway_join_prefetch"))
        assertTrue(initial.contains("finally {\n            appendDecodePreparation?.let"))

        val candidateFence = block(
            "private fun isForwardAdjacentStructurePublicationCandidateCurrent(",
            source,
        )
        val commitFence = block(
            "private fun claimForwardAdjacentStructurePublication(\n        target: Manga,\n        context:",
            source,
        )
        assertTrue(candidateFence.contains("NtkForwardAdjacentTargetClaimPolicy.sameTarget("))
        assertFalse(candidateFence.contains("selected.target === target"))
        assertTrue(commitFence.contains("append_adjacent_completion_target_alias_joined"))
        assertFalse(commitFence.contains("stale_target_object_rejected"))
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
