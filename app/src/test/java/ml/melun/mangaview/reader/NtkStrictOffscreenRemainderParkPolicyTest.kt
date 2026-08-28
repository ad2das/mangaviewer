package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictOffscreenRemainderParkPolicyTest {
    private val canonicalRunway = listOf(0, 1, 2, 3)
    private val recoverableRunway = setOf(0, 1, 2, 3)

    @Test
    fun exactAttachedAndRecoverableP0ThroughP3MayParkOffscreen() {
        assertTrue(
            NtkStrictOffscreenRemainderParkPolicy.shouldPark(
                cancelled = false,
                directWifiStrictAdjacent = true,
                viewportInsideEpisode = false,
                episodePath = "/manhwa/2/exact-next",
                claimLive = true,
                requiredRunwayPages = 4,
                orderedAttachedSourceIndexes = canonicalRunway,
                liveSourceIndexes = setOf(0, 1),
                recoverableSourceIndexes = recoverableRunway,
            ),
        )
    }

    @Test
    fun livePixelsWithoutReopenableBodiesOrCanonicalOrderCannotPretendToBePrepared() {
        assertFalse(
            NtkStrictOffscreenRemainderParkPolicy.hasPreparedRunway(
                episodePath = "/manhwa/2/exact-next",
                claimLive = true,
                requiredRunwayPages = 4,
                orderedAttachedSourceIndexes = canonicalRunway,
                liveSourceIndexes = recoverableRunway,
                recoverableSourceIndexes = emptySet(),
            ),
        )
        assertFalse(
            NtkStrictOffscreenRemainderParkPolicy.hasPreparedRunway(
                episodePath = "/manhwa/2/exact-next",
                claimLive = true,
                requiredRunwayPages = 4,
                orderedAttachedSourceIndexes = listOf(0, 2, 1, 3),
                liveSourceIndexes = emptySet(),
                recoverableSourceIndexes = recoverableRunway,
            ),
        )
        assertFalse(
            NtkStrictOffscreenRemainderParkPolicy.hasPreparedRunway(
                episodePath = "/manhwa/2/exact-next",
                claimLive = true,
                requiredRunwayPages = 4,
                orderedAttachedSourceIndexes = canonicalRunway,
                liveSourceIndexes = recoverableRunway,
                recoverableSourceIndexes = setOf(0, 1, 2),
            ),
        )
    }

    @Test
    fun parkingStopsWhenTheOwnerIsDeadOrTheViewportHasEntered() {
        fun decision(
            cancelled: Boolean = false,
            directWifi: Boolean = true,
            inside: Boolean = false,
            claimLive: Boolean = true,
        ) = NtkStrictOffscreenRemainderParkPolicy.shouldPark(
            cancelled = cancelled,
            directWifiStrictAdjacent = directWifi,
            viewportInsideEpisode = inside,
            episodePath = "/webtoon/7/exact-next",
            claimLive = claimLive,
            requiredRunwayPages = 4,
            orderedAttachedSourceIndexes = canonicalRunway,
            liveSourceIndexes = emptySet(),
            recoverableSourceIndexes = recoverableRunway,
        )

        assertFalse(decision(cancelled = true))
        assertFalse(decision(directWifi = false))
        assertFalse(decision(inside = true))
        assertFalse(decision(claimLive = false))
    }

    @Test
    fun activeExactChapterPublishesOnlyFourSourcesAheadOfThePhysicalViewport() {
        fun parked(current: Int, missing: Int, moving: Boolean = true) =
            NtkStrictAdjacentProgressiveRunwayPolicy.shouldPark(
                viewportInsideEpisode = true,
                foregroundMotionActive = moving,
                currentSourceIndex = current,
                firstMissingSourceIndex = missing,
                forwardSourceRunway = 4,
            )

        assertTrue("physical motion parks every non-visible forward decode", parked(current = 0, missing = 4))
        assertTrue("p5 must wait until the physical viewport advances", parked(current = 0, missing = 5))
        assertTrue("moving to p1 keeps the next frontier parked until idle", parked(current = 1, missing = 5))
        assertTrue("an unrelated stale predecessor anchor cannot publish the suffix", parked(-1, 4))
        assertFalse(
            "a physically entered chapter completes its verified suffix once motion retires",
            parked(current = 0, missing = 5, moving = false),
        )
        assertFalse(
            NtkStrictAdjacentProgressiveRunwayPolicy.shouldPark(
                viewportInsideEpisode = false,
                foregroundMotionActive = true,
                currentSourceIndex = 0,
                firstMissingSourceIndex = 12,
                forwardSourceRunway = 4,
            ),
        )
    }

    @Test
    fun launchRunwayMayFinishBeforeFirstScrollButLaterTailsNeedEntryAndIdle() {
        fun parked(inside: Boolean, moving: Boolean, started: Boolean = true) =
            NtkStrictAdjacentProgressiveRunwayPolicy.shouldParkIncompleteInitialTail(
                viewportInsideEpisode = inside,
                foregroundMotionActive = moving,
                physicalScrollEverStarted = started,
            )

        assertFalse(
            "the launch successor may complete p0-p3 before any reader scroll exists",
            parked(false, false, started = false),
        )
        assertTrue("after first scroll, offscreen p1+ stays private during apparent idle", parked(false, false))
        assertTrue("offscreen p1+ stays private during motion", parked(false, true))
        assertTrue("entered p1+ still waits for the current gesture to retire", parked(true, true))
        assertFalse("an entered idle episode may publish its exact p1+ tail", parked(true, false))
    }

    @Test
    fun directAdjacentPixelsUseOneOffscreenPhysicalAndIdleRunwayContract() {
        fun deferred(
            inside: Boolean,
            source: Int,
            current: Int = 0,
            physical: Boolean = false,
            moving: Boolean = false,
        ) = NtkDirectAdjacentPixelAdmissionPolicy.shouldDefer(
            viewportInsideEpisode = inside,
            sourceIndex = source,
            currentSourceIndex = current,
            physicalIntent = physical,
            foregroundMotionActive = moving,
            idleForwardRunway = 4,
        )

        assertTrue("offscreen p0 waits while the predecessor moves", deferred(false, 0, moving = true))
        assertFalse("offscreen p0 may fill after motion retires", deferred(false, 0))
        assertTrue("offscreen p1 stays encoded even during apparent idle", deferred(false, 1))
        assertTrue("offscreen p2+ stays encoded", deferred(false, 2))
        assertFalse("the next real Surface blocker bypasses motion deferral", deferred(true, 2, 1, true, true))
        assertTrue("a distant placeholder intent cannot jump the source frontier", deferred(true, 5, 1, true, true))
        assertTrue("near-forward work waits during motion", deferred(true, 3, 1, moving = true))
        assertFalse("near-forward work may fill during idle", deferred(true, 3, 1))
        assertTrue("idle work remains bounded to four sources", deferred(true, 6, 1))
    }

    @Test
    fun previouslyPresentedPhysicalAnchorRepairsAnEvictedVisibleWindowWithoutTrustingColdPlaceholders() {
        assertEquals(
            12,
            NtkDirectAdjacentViewportSourcePolicy.resolve(
                installedPhysicalSource = -1,
                installedBoundarySource = -1,
                previouslyPresentedPhysicalAnchorSource = 12,
            ),
        )
        assertEquals(
            -1,
            NtkDirectAdjacentViewportSourcePolicy.resolve(
                installedPhysicalSource = -1,
                installedBoundarySource = -1,
                previouslyPresentedPhysicalAnchorSource = -1,
            ),
        )
        assertEquals(
            13,
            NtkDirectAdjacentViewportSourcePolicy.resolve(
                installedPhysicalSource = 13,
                installedBoundarySource = 9,
                previouslyPresentedPhysicalAnchorSource = 12,
            ),
        )
    }

    @Test
    fun racedWakeIsRetainedUntilTheCurrentOwnerReleases() {
        val latch = NtkPathEventWakeLatch()
        val path = "/manhwa/2/exact-next"

        assertTrue(latch.tryAcquire(path))
        assertFalse(latch.tryAcquire(path))
        assertTrue("A wake racing the owner must request one fresh turn", latch.release(path))
        assertTrue(latch.tryAcquire(path))
        assertFalse("No timer-like self-redrive is created without a raced event", latch.release(path))
    }

    @Test
    fun wakeOwnershipIsPathScopedAndCancellationDropsItsRetainedEdge() {
        val latch = NtkPathEventWakeLatch()
        assertTrue(latch.tryAcquire("/manhwa/2/a"))
        assertTrue(latch.tryAcquire("/manhwa/2/b"))
        assertFalse(latch.tryAcquire("/manhwa/2/a"))

        latch.cancel("/manhwa/2/a")
        assertTrue(latch.tryAcquire("/manhwa/2/a"))
        assertFalse(latch.release("/manhwa/2/a"))
        assertFalse(latch.release("/manhwa/2/b"))
    }

    @Test
    fun replacingARefsSizedRetryRetiresTheOldKeyAndOnlyNewestOwnerWakes() {
        val registry = NtkPathScheduledRetryRegistry<Any>()
        val path = "/manhwa/2/exact-next"
        val sevenRefsKey = "remaining|$path|7"
        val nineRefsKey = "remaining|$path|9"
        val old = checkNotNull(registry.register(path, sevenRefsKey, Any()))
        val newest = checkNotNull(registry.register(path, nineRefsKey, Any()))

        assertSame(old.owner, newest.replaced)
        assertFalse(registry.hasRetryKey(sevenRefsKey))
        assertTrue(registry.hasRetryKey(nineRefsKey))
        var wakeCount = 0
        if (registry.claim(old.owner)) wakeCount++
        if (registry.claim(newest.owner)) wakeCount++

        assertEquals(1, wakeCount)
        assertFalse(registry.hasRetryKey(sevenRefsKey))
        assertFalse(registry.hasRetryKey(nineRefsKey))
        assertEquals(0, registry.ownerCount())
    }

    @Test
    fun staleDrainCannotEraseANewerOwnerThatReusesTheSameRetryKey() {
        val registry = NtkPathScheduledRetryRegistry<Any>()
        val path = "/manhwa/2/exact-next"
        val oldKey = "remaining|$path|7"
        val middleKey = "remaining|$path|9"
        val old = checkNotNull(registry.register(path, oldKey, Any()))
        checkNotNull(registry.register(path, middleKey, Any()))
        val newest = checkNotNull(registry.register(path, oldKey, Any()))

        assertFalse(registry.claim(old.owner))
        assertTrue("ABA-new retry key must remain reserved", registry.hasRetryKey(oldKey))
        assertNull(registry.register(path, oldKey, Any()))
        assertTrue(registry.claim(newest.owner))
        assertFalse(registry.hasRetryKey(oldKey))
        assertEquals(0, registry.ownerCount())
    }
}
