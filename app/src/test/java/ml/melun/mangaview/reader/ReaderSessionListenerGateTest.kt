package ml.melun.mangaview.reader

import android.graphics.Bitmap
import ml.melun.mangaview.mangaview.Manga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionListenerGateTest {
    @Test
    fun bitmapAndTileIdentityUseResourceIdentityAndGeometry() {
        val bitmapResource = Any()
        val firstBitmap = AdoptedDrawableIdentity.bitmapResource(bitmapResource, 764, 1800)
        val sameBitmap = AdoptedDrawableIdentity.bitmapResource(bitmapResource, 764, 1800)
        val otherBitmap = AdoptedDrawableIdentity.bitmapResource(Any(), 764, 1800)
        val tileResources = arrayOf(Any(), Any())
        val geometry = intArrayOf(0, 512, 764, 900, 512, 900, 764, 900)
        val firstTiles = AdoptedDrawableIdentity.validatedFullQualityTileResources(
            764,
            900,
            geometry,
            tileResources
        )
        val sameTiles = AdoptedDrawableIdentity.validatedFullQualityTileResources(
            764,
            900,
            geometry.copyOf(),
            tileResources.copyOf()
        )
        val otherGeometry = AdoptedDrawableIdentity.validatedFullQualityTileResources(
            764,
            900,
            intArrayOf(0, 500, 764, 900, 500, 900, 764, 900),
            tileResources.copyOf()
        )

        val registry = AdoptedDrawableRegistry()
        registry.adopt(0, DrawableOrigin.PREPARED_STORE, firstBitmap)
        registry.adopt(1, DrawableOrigin.PREPARED_STORE, firstTiles)

        assertTrue(registry.matches(0, sameBitmap))
        assertFalse(registry.matches(0, otherBitmap))
        assertTrue(registry.matches(1, sameTiles))
        assertFalse(registry.matches(1, otherGeometry))
    }

    @Test
    fun installedPreparedTileReturnsInitialCommitThenRenderedOnly() {
        val registry = AdoptedDrawableRegistry()
        val identity = AdoptedDrawableIdentity.validatedFullQualityTileResources(
            764,
            512,
            intArrayOf(0, 512, 764, 512),
            arrayOf(Any())
        )
        registry.adopt(0, DrawableOrigin.PREPARED_STORE, identity)

        assertEquals(
            ReaderSession.InitialPrerenderResult.RENDERED_AND_COMMIT,
            registry.initialPrerenderResult(0, null, continuous = false, installed = true)
        )
        assertEquals(
            ReaderSession.InitialPrerenderResult.RENDERED_ONLY,
            registry.initialPrerenderResult(0, null, continuous = true, installed = true)
        )
        assertEquals(
            ReaderSession.InitialPrerenderResult.NOT_RENDERED,
            registry.initialPrerenderResult(0, null, continuous = false, installed = false)
        )
    }

    @Test
    fun registryKeepsFirstInstalledOwnerAndIdentity() {
        val registry = AdoptedDrawableRegistry()
        val storeResource = Any()
        val sessionResource = Any()

        assertTrue(
            registry.adopt(
                3,
                DrawableOrigin.PREPARED_STORE,
                AdoptedDrawableIdentity.token(storeResource)
            )
        )
        assertFalse(
            registry.adopt(
                3,
                DrawableOrigin.READER_SESSION,
                AdoptedDrawableIdentity.token(sessionResource)
            )
        )
        assertEquals(DrawableOrigin.PREPARED_STORE, registry.origin(3))
        assertTrue(registry.matches(3, AdoptedDrawableIdentity.token(storeResource)))
        assertFalse(registry.matches(3, AdoptedDrawableIdentity.token(Any())))
    }

    @Test
    fun registryShiftsOnPrependAndCompactsOnRemove() {
        val registry = AdoptedDrawableRegistry()
        val prepared = Any()
        val session = Any()
        registry.adopt(1, DrawableOrigin.PREPARED_STORE, AdoptedDrawableIdentity.token(prepared))
        registry.adopt(4, DrawableOrigin.READER_SESSION, AdoptedDrawableIdentity.token(session))

        registry.onPagesPrepended(2)

        assertEquals(DrawableOrigin.PREPARED_STORE, registry.origin(3))
        assertEquals(DrawableOrigin.READER_SESSION, registry.origin(6))
        assertNull(registry.origin(1))

        registry.onPagesRemoved(startIndex = 2, removedCount = 3)

        assertEquals(DrawableOrigin.READER_SESSION, registry.origin(3))
        assertFalse(registry.matches(3, AdoptedDrawableIdentity.token(prepared)))
        assertTrue(registry.matches(3, AdoptedDrawableIdentity.token(session)))
    }

    @Test
    fun legacyRegistryInvalidatesOnStructureMutation() {
        val registry = AdoptedDrawableRegistry(
            policy = AdoptedDrawableRegistry.Policy.LEGACY_PREPARED_BITMAP_MATCH,
            structurePolicy = AdoptedDrawableRegistry.StructurePolicy.INVALIDATE_ALL
        )
        registry.adopt(2, DrawableOrigin.PREPARED_STORE, AdoptedDrawableIdentity.token(Any()))

        registry.onPagesPrepended(1)

        assertFalse(registry.hasAny())

        registry.adopt(2, DrawableOrigin.PREPARED_STORE, AdoptedDrawableIdentity.token(Any()))
        registry.onPagesRemoved(startIndex = -1, removedCount = 0)

        assertFalse(registry.hasAny())
    }

    @Test
    fun staleGenerationDropsCallbacksAndDoesNotMutateRegistry() {
        val downstream = RecordingListener()
        val registry = AdoptedDrawableRegistry().apply {
            adopt(2, DrawableOrigin.PREPARED_STORE, AdoptedDrawableIdentity.token(Any()))
        }
        val gate = ReaderSessionListenerGate(
            generation = 7,
            isActive = { false },
            adopted = registry,
            installed = InstalledDrawableQuery { true },
            downstream = downstream
        )

        gate.onPagesReady(10)
        gate.onPagesPrepended(12, 2, 0)
        gate.onPagesRemoved(0, 1, 11)
        gate.onPageLoading(2)
        gate.onMessage("stale")

        assertTrue(downstream.events.isEmpty())
        assertEquals(DrawableOrigin.PREPARED_STORE, registry.origin(2))
    }

    @Test
    fun installedPreparedDrawableSuppressesVisualMutation() {
        val downstream = RecordingListener()
        val registry = AdoptedDrawableRegistry().apply {
            adopt(2, DrawableOrigin.PREPARED_STORE, AdoptedDrawableIdentity.token(Any()))
        }
        val gate = ReaderSessionListenerGate(
            generation = 4,
            isActive = { it == 4 },
            adopted = registry,
            installed = InstalledDrawableQuery { it == 2 },
            downstream = downstream
        )

        gate.onPageLoading(2)
        gate.onPageError(2, "late")
        gate.onPageCleared(2)
        gate.onPageLoading(3)

        assertEquals(listOf("loading:3"), downstream.events)
        assertTrue(gate.isPageDrawableInstalled(2))
        assertFalse(gate.isPageDrawableInstalled(3))
        assertEquals(DrawableOrigin.PREPARED_STORE, registry.origin(2))
    }

    @Test
    fun strictDrawableAdoptionDoesNotSuppressRestoredInitialPage() {
        val downstream = RecordingListener()
        val registry = AdoptedDrawableRegistry().apply {
            adopt(2, DrawableOrigin.READER_SESSION, AdoptedDrawableIdentity.token(Any()))
        }
        val gate = ReaderSessionListenerGate(
            generation = 1,
            isActive = { true },
            adopted = registry,
            installed = InstalledDrawableQuery { true },
            downstream = downstream
        )

        gate.onInitialPage(2)

        assertEquals(listOf("initial:2"), downstream.events)
    }

    @Test
    fun legacyPreparedDrawableStillSuppressesInitialPageMutation() {
        val downstream = RecordingListener()
        val registry = AdoptedDrawableRegistry(
            policy = AdoptedDrawableRegistry.Policy.LEGACY_PREPARED_BITMAP_MATCH
        ).apply {
            adopt(2, DrawableOrigin.PREPARED_STORE, AdoptedDrawableIdentity.token(Any()))
        }
        val gate = ReaderSessionListenerGate(
            generation = 1,
            isActive = { true },
            adopted = registry,
            installed = InstalledDrawableQuery { true },
            downstream = downstream
        )

        gate.onInitialPage(2)

        assertTrue(downstream.events.isEmpty())
    }

    @Test
    fun onlyInlineFullQualityTileWinnerIsAuthoritativeForDecodeSuppression() {
        val fullQuality = AdoptedDrawableIdentity.validatedFullQualityTileResources(
            pageWidth = 100,
            pageHeight = 512,
            geometry = intArrayOf(0, 512, 100, 512),
            resources = arrayOf(Any())
        )
        val inlineRegistry = AdoptedDrawableRegistry().apply {
            adopt(2, DrawableOrigin.PREPARED_STORE, fullQuality)
        }
        var downstreamOwnsPage = true
        val inlineDownstream = object : RecordingListener() {
            override fun isPageAuthoritativeDrawableInstalled(index: Int): Boolean =
                downstreamOwnsPage && index == 2
        }
        val inlineGate = ReaderSessionListenerGate(
            generation = 1,
            isActive = { true },
            adopted = inlineRegistry,
            installed = InstalledDrawableQuery { it == 2 },
            downstream = inlineDownstream
        )
        val legacyRegistry = AdoptedDrawableRegistry(
            policy = AdoptedDrawableRegistry.Policy.LEGACY_PREPARED_BITMAP_MATCH
        ).apply {
            adopt(2, DrawableOrigin.PREPARED_STORE, fullQuality)
        }
        val legacyGate = ReaderSessionListenerGate(
            generation = 1,
            isActive = { true },
            adopted = legacyRegistry,
            installed = InstalledDrawableQuery { it == 2 },
            downstream = RecordingListener()
        )

        assertTrue(inlineGate.isPageAuthoritativeDrawableInstalled(2))
        assertFalse(inlineGate.isPageAuthoritativeDrawableInstalled(3))
        assertFalse(legacyGate.isPageAuthoritativeDrawableInstalled(2))
        downstreamOwnsPage = false
        assertFalse(inlineGate.isPageAuthoritativeDrawableInstalled(2))
    }

    @Test
    fun generationBoundAuthoritativeInstallQueueOwnsDeliveryBeforePhysicalBatchCommit() {
        val identity = AdoptedDrawableIdentity.validatedFullQualityTileResources(
            pageWidth = 100,
            pageHeight = 512,
            geometry = intArrayOf(0, 512, 100, 512),
            resources = arrayOf(Any())
        )
        val registry = AdoptedDrawableRegistry().apply {
            adopt(7, DrawableOrigin.READER_SESSION, identity)
        }
        val downstream = object : RecordingListener() {
            override fun isPageAuthoritativeDrawableInstalled(index: Int): Boolean = index == 7
        }
        val gate = ReaderSessionListenerGate(
            generation = 3,
            isActive = { it == 3 },
            adopted = registry,
            installed = InstalledDrawableQuery { false },
            downstream = downstream
        )

        assertTrue(gate.isPageDrawableInstalled(7))
        assertTrue(gate.isPageAuthoritativeDrawableInstalled(7))
        assertFalse(gate.isPageDrawableInstalled(8))
    }

    @Test
    fun downstreamAckCanReplaceAStaleAuthoritativeResourceIdentity() {
        val stale = AdoptedDrawableIdentity.validatedFullQualityTileResources(
            pageWidth = 100,
            pageHeight = 512,
            geometry = intArrayOf(0, 512, 100, 512),
            resources = arrayOf(Any())
        )
        val current = AdoptedDrawableIdentity.validatedFullQualityTileResources(
            pageWidth = 100,
            pageHeight = 512,
            geometry = intArrayOf(0, 512, 100, 512),
            resources = arrayOf(Any())
        )
        val registry = AdoptedDrawableRegistry().apply {
            adopt(79, DrawableOrigin.READER_SESSION, stale)
        }

        assertFalse(registry.matches(79, current))
        assertTrue(registry.replaceWithCurrentAuthoritative(
            79,
            DrawableOrigin.READER_SESSION,
            current
        ))
        assertTrue(registry.matches(79, current))
        assertFalse(registry.matches(79, stale))
    }

    @Test
    fun clearingSessionDrawableForwardsSurfaceClearThenInvalidatesRegistry() {
        val downstream = RecordingListener()
        val registry = AdoptedDrawableRegistry().apply {
            adopt(5, DrawableOrigin.READER_SESSION, AdoptedDrawableIdentity.token(Any()))
        }
        val gate = ReaderSessionListenerGate(
            generation = 1,
            isActive = { true },
            adopted = registry,
            installed = InstalledDrawableQuery { false },
            downstream = downstream
        )

        gate.onPageCleared(5)

        assertEquals(listOf("cleared:5"), downstream.events)
        assertNull(registry.origin(5))
    }

    @Test
    fun rollingEvictionPreservesItsDistinctDownstreamEventAndInvalidatesRegistry() {
        val downstream = RecordingListener()
        val registry = AdoptedDrawableRegistry().apply {
            adopt(5, DrawableOrigin.READER_SESSION, AdoptedDrawableIdentity.token(Any()))
        }
        val gate = ReaderSessionListenerGate(
            generation = 1,
            isActive = { true },
            adopted = registry,
            installed = InstalledDrawableQuery { false },
            downstream = downstream
        )

        gate.onStrictRollingHistoricalSceneActivated()
        gate.onPageRollingEvicted(5)

        assertEquals(listOf("rolling-mode", "rolling-evicted:5"), downstream.events)
        assertNull(registry.origin(5))
    }

    private open class RecordingListener : ReaderSession.Listener {
        val events = ArrayList<String>()

        override fun onPagesReady(count: Int) {
            events += "ready:$count"
        }

        override fun onPagesAppended(count: Int) {
            events += "appended:$count"
        }

        override fun onPagesPrepended(count: Int, insertedCount: Int, holdUntilReadyCount: Int) {
            events += "prepended:$count:$insertedCount:$holdUntilReadyCount"
        }

        override fun onPagesRemoved(startIndex: Int, removedCount: Int, totalCount: Int) {
            events += "removed:$startIndex:$removedCount:$totalCount"
        }

        override fun onInitialPage(index: Int) {
            events += "initial:$index"
        }

        override fun onPageLoading(index: Int) {
            events += "loading:$index"
        }

        override fun onPageBoundsReady(index: Int, width: Int, height: Int) {
            events += "bounds:$index:$width:$height"
        }

        override fun onPageReady(index: Int, bitmap: Bitmap) {
            events += "bitmap:$index"
        }

        override fun onPageTilesReady(
            index: Int,
            pageWidth: Int,
            pageHeight: Int,
            tiles: List<ReaderTile>
        ) {
            events += "tiles:$index"
        }

        override fun onPageCard(index: Int, title: String) {
            events += "card:$index"
        }

        override fun onPageError(index: Int, message: String) {
            events += "error:$index"
        }

        override fun onPageCleared(index: Int) {
            events += "cleared:$index"
        }

        override fun onStrictRollingHistoricalSceneActivated() {
            events += "rolling-mode"
        }

        override fun onPageRollingEvicted(index: Int) {
            events += "rolling-evicted:$index"
        }

        override fun onMessage(message: String) {
            events += "message:$message"
        }

        override fun onCaptchaRequired(manga: Manga) {
            events += "captcha"
        }

        override fun onBoundaryAppendFinished(
            anchor: Int,
            direction: Int,
            silent: Boolean,
            suppressedCaptcha: Boolean
        ) {
            events += "boundary:$anchor:$direction"
        }
    }
}
