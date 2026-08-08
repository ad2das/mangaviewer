package ml.melun.mangaview.reader

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiAdjacentWebtoonRunwayTailProfileTest {

    private val manifestDigest = "a".repeat(64)

    private fun assetDigest(pageIndex: Int): String =
        (('0'.code + pageIndex % 10).toChar()).toString().repeat(64)

    private fun freeze(
        gate: NtkDirectWifiShortWebtoonTailPermitGate =
            NtkDirectWifiShortWebtoonTailPermitGate(4),
        predecessorGate: NtkDirectWifiAdjacentWebtoonPredecessorGate =
            NtkDirectWifiAdjacentWebtoonPredecessorGate(),
        episodePath: String = "/webtoon/work/next",
        pageCount: Int = 96,
        discoveryGeneration: Long = 73L,
        rollingAdmission: Boolean = true,
        directWifiTransport: Boolean = true,
        cellularResilientTransport: Boolean = false,
        adjacentPrefetch: Boolean = true,
    ) = NtkDirectWifiAdjacentWebtoonRunwayTailProfile.freeze(
        episodePath = episodePath,
        manifestDigest = manifestDigest,
        discoveryGeneration = discoveryGeneration,
        pageCount = pageCount,
        rollingAdmission = rollingAdmission,
        directWifiTransport = directWifiTransport,
        cellularResilientTransport = cellularResilientTransport,
        adjacentPrefetch = adjacentPrefetch,
        predecessorGate = predecessorGate,
        permitGate = gate,
    )

    @Test
    fun freezesOnlyTheRollingDirectWifiAdjacentWebtoonRole() {
        assertNotNull(freeze())
        assertNull(freeze(directWifiTransport = false))
        assertNull(freeze(cellularResilientTransport = true))
        assertNull(freeze(adjacentPrefetch = false))
        assertNull(freeze(rollingAdmission = false))
        assertNull(freeze(episodePath = "/manhwa/work/next"))
        assertNull(freeze(discoveryGeneration = 0L))
        assertNull(freeze(pageCount = 0))
        assertEquals(
            4,
            NtkDirectWifiAdjacentWebtoonRunwayTailProfile
                .GLOBAL_MAX_CONCURRENT_EXTRA_TAILS,
        )
    }

    @Test
    fun onlyP0ThroughP3ReceiveTheSeparateAdjacentRunwayRole() {
        val profile = requireNotNull(freeze(pageCount = 20))

        for (pageIndex in 0..3) {
            val tag = requireNotNull(profile.tagForPage(pageIndex, assetDigest(pageIndex)))
            assertEquals("/webtoon/work/next", tag.normalizedEpisodePath)
            assertEquals(manifestDigest, tag.manifestDigest)
            assertEquals(73L, tag.discoveryGeneration)
            assertEquals(pageIndex, tag.pageIndex)
            assertEquals(20, tag.pageCount)
            assertEquals(assetDigest(pageIndex), tag.canonicalAssetDigest)
            assertEquals(1, tag.maximumExtraTailRequests)
        }
        assertNull(profile.tagForPage(4, assetDigest(4)))
        assertNull(profile.tagForPage(19, assetDigest(9)))
        assertNull(profile.tagForPage(0, ""))
    }

    @Test
    fun logicalImageTelemetryStartsAfterSuccessfulHeadersOnlyForFrozenDirectWifi() {
        val policy = NtkStrictLogicalImageTelemetryPolicy

        assertTrue(policy.afterSuccessfulHeaders(true, false))
        assertFalse(policy.afterSuccessfulHeaders(false, false))
        assertFalse(policy.afterSuccessfulHeaders(true, true))
        assertFalse(policy.afterSuccessfulHeaders(false, true))
    }

    @Test
    fun predecessorCompletionOpensTheGateWithoutBurningAnEarlyClaim() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(1)
        val profile = requireNotNull(freeze(gate = gate))
        val tag = requireNotNull(profile.tagForPage(0, assetDigest(0)))
        val live = AtomicBoolean(false)

        assertFalse(tag.isPredecessorComplete())
        assertNull(tag.tryAcquireExtraTail(live))
        assertEquals(1, gate.availablePermitsForTest())

        profile.markPredecessorComplete()
        assertTrue(tag.isPredecessorComplete())
        val lease = requireNotNull(tag.tryAcquireExtraTail(live))
        assertEquals(0, gate.availablePermitsForTest())
        assertNull(tag.tryAcquireExtraTail(live))
        lease.close()
        lease.close()
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun adjacentRunwayBudgetIsBoundedAndCancellationCannotLeakAPermit() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(2)
        val profile = requireNotNull(freeze(gate = gate))
        profile.markPredecessorComplete()
        val p0 = requireNotNull(profile.tagForPage(0, assetDigest(0)))
        val p1 = requireNotNull(profile.tagForPage(1, assetDigest(1)))
        val p2 = requireNotNull(profile.tagForPage(2, assetDigest(2)))

        assertNull(p0.tryAcquireExtraTail(AtomicBoolean(true)))
        assertEquals(2, gate.availablePermitsForTest())
        val first = requireNotNull(p1.tryAcquireExtraTail(AtomicBoolean(false)))
        val second = requireNotNull(p2.tryAcquireExtraTail(AtomicBoolean(false)))
        assertEquals(0, gate.availablePermitsForTest())
        first.close()
        second.close()
        assertEquals(2, gate.availablePermitsForTest())
    }

    @Test
    fun p0SingleSuffixCannotStartBeforePredecessorAndUsesOneAtomicBudget() {
        val gate = NtkDirectWifiShortWebtoonTailPermitGate(1)
        val predecessor = NtkDirectWifiAdjacentWebtoonPredecessorGate()
        val profile = requireNotNull(freeze(gate = gate, predecessorGate = predecessor))
        val p0 = requireNotNull(profile.tagForPage(0, assetDigest(0)))
        val p1 = requireNotNull(profile.tagForPage(1, assetDigest(1)))
        val running = AtomicBoolean(false)

        assertNull(p0.tryAcquireExtraTails(1, running))
        assertEquals(1, gate.availablePermitsForTest())
        profile.markPredecessorComplete()

        assertNull(p0.tryAcquireExtraTails(4, running))
        val group = requireNotNull(p0.tryAcquireExtraTails(1, running))
        assertEquals(0, gate.availablePermitsForTest())
        assertNull(p1.tryAcquireExtraTail(running))
        group.close()
        group.close()
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun frozenIdentityIsBoundBeforeTheMutablePredecessorGateOpens() {
        val profile = requireNotNull(freeze(pageCount = 20))
        val tag = requireNotNull(profile.tagForPage(3, assetDigest(3)))

        assertTrue(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.matchesFrozenProfile(
                profile,
                "/webtoon/work/next",
                20,
                manifestDigest,
                hasValidQuarantineIdentity = false,
            )
        )
        assertTrue(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.matchesFrozenIdentity(
                profile,
                tag,
                "/webtoon/work/next",
                20,
                3,
                manifestDigest,
                quarantineCanonicalAssetDigest = null,
            )
        )
        assertNull(tag.tryAcquireExtraTail(AtomicBoolean(false)))

        profile.markPredecessorComplete()
        fun matches(
            pageIndex: Int = 3,
            pageCount: Int = 20,
            path: String = "/webtoon/work/next",
            strictDigest: String? = manifestDigest,
            quarantineDigest: String? = null,
        ) = NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.matchesFrozenIdentity(
            profile,
            tag,
            path,
            pageCount,
            pageIndex,
            strictDigest,
            quarantineDigest,
        )

        assertTrue(matches())
        assertTrue(matches(strictDigest = null, quarantineDigest = assetDigest(3)))
        assertFalse(matches(pageIndex = 2))
        assertFalse(matches(pageIndex = 4))
        assertFalse(matches(pageCount = 21))
        assertFalse(matches(path = "/webtoon/other/next"))
        assertFalse(matches(strictDigest = "f".repeat(64)))
        assertFalse(matches(strictDigest = null, quarantineDigest = assetDigest(2)))
    }

    @Test
    fun boundarySampleKeepsOneExhaustiveDisjointSuffix() {
        val delivered = 128L * 1024L
        val total = 2L * 1024L * 1024L

        assertFalse(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.shouldStart(
                399L,
                delivered,
                total,
                pageIndex = 0,
            )
        )
        assertTrue(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.shouldStart(
                400L,
                delivered,
                total,
                pageIndex = 0,
            )
        )
        assertFalse(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.shouldStart(
                599L,
                delivered,
                total,
                pageIndex = 1,
            )
        )
        assertTrue(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.shouldStart(
                600L,
                delivered,
                total,
                pageIndex = 1,
            )
        )
        assertFalse(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.shouldStart(
                400L,
                768L * 1024L,
                total,
                pageIndex = 0,
            )
        )

        val split = requireNotNull(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.disjointTailStart(
                delivered,
                total,
            )
        )
        assertTrue(split > delivered)
        assertTrue(split - delivered >=
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.MIN_PRIMARY_GAP_BYTES)
        assertTrue(total - split >=
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.MIN_TAIL_BYTES)
        assertEquals(total - delivered, (split - delivered) + (total - split))
        assertEquals(split - 1L, (delivered until split).last)
        assertEquals(split, (split until total).first)

        val suffix = split until total
        assertEquals(split, suffix.first)
        assertEquals(total - 1L, suffix.last)
        assertEquals(total - split, suffix.last - suffix.first + 1L)
    }

    @Test
    fun visibleP0KeepsTheMeasuredLargerShareOnItsHealthyPrefixOnly() {
        val delivered = 515_144L
        val total = 3_803_487L
        val p0Split = requireNotNull(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.disjointTailStart(
                delivered,
                total,
                pageIndex = 0,
            )
        )
        val laterSplit = requireNotNull(
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.disjointTailStart(
                delivered,
                total,
                pageIndex = 1,
            )
        )

        assertEquals(2_652_566L, p0Split)
        assertEquals(1_830_481L, laterSplit)
        assertTrue(p0Split > laterSplit)
        assertEquals(total - delivered, (p0Split - delivered) + (total - p0Split))
        assertTrue(total - p0Split >=
            NtkDirectWifiAdjacentWebtoonProjectedTailPolicy.MIN_TAIL_BYTES)

        val p0Suffixes = NtkDirectWifiAdjacentWebtoonProjectedTailPolicy
            .disjointTailSegments(delivered, total, pageIndex = 0, maximumSuffixes = 1)
        val laterSuffixes = NtkDirectWifiAdjacentWebtoonProjectedTailPolicy
            .disjointTailSegments(delivered, total, pageIndex = 1, maximumSuffixes = 1)
        assertEquals(1, p0Suffixes.size)
        assertEquals(1, laterSuffixes.size)
        assertEquals(p0Split, p0Suffixes.first().first)
        assertEquals(total - 1L, p0Suffixes.last().last)
        assertEquals(
            total - p0Split,
            p0Suffixes.sumOf { range -> range.last - range.first + 1L },
        )
    }
}
