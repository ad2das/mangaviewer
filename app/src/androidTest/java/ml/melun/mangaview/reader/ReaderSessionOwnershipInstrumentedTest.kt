package ml.melun.mangaview.reader

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Manga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ReaderSessionOwnershipInstrumentedTest {
    @Test
    fun delayedCleanupCannotRecycleCurrentOwnedBitmapIdentity() {
        val session = newSession("current-owned-bitmap-release")
        val bitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val delivered = mutableDeliveredBitmaps(session)
        val owned = mutableDeliveredOwned(session)

        try {
            delivered[0] = bitmap
            owned.add(0)

            invokePrivate(
                session,
                "releaseBitmapToPoolOrRecycle",
                arrayOf(Bitmap::class.java),
                bitmap,
            )

            assertFalse("Current owned delivery was recycled by stale cleanup", bitmap.isRecycled)

            delivered.clear()
            owned.clear()
            invokePrivate(
                session,
                "releaseBitmapToPoolOrRecycle",
                arrayOf(Bitmap::class.java),
                bitmap,
            )
            assertTrue("Stale unreferenced delivery was not retired", bitmap.isRecycled)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { session.cancel() }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun delayedCleanupCannotRecycleCurrentOwnedTileIdentity() {
        val session = newSession("current-owned-release")
        val bitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val tiles = mutableDeliveredTiles(session)
        val owned = mutableDeliveredOwned(session)

        try {
            tiles[0] = listOf(fullPageTile(bitmap))
            owned.add(0)

            invokePrivate(
                session,
                "releaseBitmapToPoolOrRecycle",
                arrayOf(Bitmap::class.java),
                bitmap,
            )

            assertFalse("Current owned delivery was recycled by stale cleanup", bitmap.isRecycled)

            tiles.clear()
            owned.clear()
            invokePrivate(
                session,
                "releaseBitmapToPoolOrRecycle",
                arrayOf(Bitmap::class.java),
                bitmap,
            )
            assertTrue("Stale unreferenced delivery was not retired", bitmap.isRecycled)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { session.cancel() }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun delayedCleanupCannotRecycleExternallyOwnedIdentity() {
        val session = newSession("external-release")
        val bitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val external = externallyOwnedBitmaps(session)

        try {
            external.add(bitmap)
            invokePrivate(
                session,
                "releaseBitmapToPoolOrRecycle",
                arrayOf(Bitmap::class.java),
                bitmap,
            )

            assertFalse("External Surface owner lost its immutable bitmap", bitmap.isRecycled)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { session.cancel() }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun exactInstalledPreparedStoreTilePageIsReportedAsBorrowedWinner() {
        val bitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val tiles = listOf(fullPageTile(bitmap))
        val adopted = AdoptedDrawableRegistry().apply {
            assertTrue(adoptPreparedStoreTiles(0, PAGE_WIDTH, PAGE_HEIGHT, tiles))
        }
        val gate = ownershipGate(adopted)

        try {
            assertTrue(
                gate.isExactPreparedStoreTilePageInstalled(
                    0,
                    PAGE_WIDTH,
                    PAGE_HEIGHT,
                    tiles
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun differentPreparedStoreTileIdentityIsNotReportedAsBorrowedWinner() {
        val installedBitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val candidateBitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val installedTiles = listOf(fullPageTile(installedBitmap))
        val candidateTiles = listOf(fullPageTile(candidateBitmap))
        val adopted = AdoptedDrawableRegistry().apply {
            assertTrue(adoptPreparedStoreTiles(0, PAGE_WIDTH, PAGE_HEIGHT, installedTiles))
        }
        val gate = ownershipGate(adopted)

        try {
            assertFalse(
                gate.isExactPreparedStoreTilePageInstalled(
                    0,
                    PAGE_WIDTH,
                    PAGE_HEIGHT,
                    candidateTiles
                )
            )
        } finally {
            installedBitmap.recycle()
            candidateBitmap.recycle()
        }
    }

    @Test
    fun exactInstalledSessionTilePageIsNotReportedAsBorrowedStoreWinner() {
        val bitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val tiles = listOf(fullPageTile(bitmap))
        val adopted = AdoptedDrawableRegistry().apply {
            assertTrue(adoptReaderSessionTiles(0, PAGE_WIDTH, PAGE_HEIGHT, tiles))
        }
        val gate = ownershipGate(adopted)

        try {
            assertFalse(
                gate.isExactPreparedStoreTilePageInstalled(
                    0,
                    PAGE_WIDTH,
                    PAGE_HEIGHT,
                    tiles
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun lateBorrowedPreparedTileCannotReplaceOrRecycleInstalledSessionTile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val installed = AtomicBoolean(false)
        val downstream = RecordingListener(installed)
        val adopted = AdoptedDrawableRegistry()
        val gate = ReaderSessionListenerGate(
            generation = 1,
            isActive = { it == 1 },
            adopted = adopted,
            installed = InstalledDrawableQuery { installed.get() },
            downstream = downstream
        )
        val session = ReaderSession(
            context = instrumentation.targetContext,
            manga = Manga(1, "ownership-race", "", MTitle.base_comic),
            title = null,
            viewerWidth = PAGE_WIDTH,
            viewerHeight = PAGE_HEIGHT,
            autoCut = false,
            reverse = false,
            preparedKey = null,
            startAtFirstPage = true,
            listener = gate
        )
        val sessionBitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val preparedBitmap = immutableBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val sessionTiles = listOf(fullPageTile(sessionBitmap))
        val preparedTiles = listOf(fullPageTile(preparedBitmap))
        val preparedPage = ReaderPreparedStore.PreparedTilePage(
            PAGE_WIDTH,
            PAGE_HEIGHT,
            preparedTiles,
            ReaderPreparedStore.PreparedOriginalProof(
                "late-prepared-original",
                ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
                PAGE_WIDTH,
                PAGE_HEIGHT,
                1,
                false
            )
        )

        try {
            instrumentation.runOnMainSync {
                invokePrivate(
                    session,
                    "installImages",
                    arrayOf(List::class.java, Int::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
                    listOf("https://example.invalid/page.jpg"), 0, false, false
                )
                val installedPage = checkNotNull(
                    invokePrivate(
                        session,
                        "pageRef",
                        arrayOf(Int::class.javaPrimitiveType!!),
                        0,
                    ),
                )

                // This is the ownership state produced when a Session decode wins, followed by
                // the common listener gate publishing and adopting that exact tile identity.
                invokePrivate(
                    session,
                    "trackDeliveredTiles",
                    arrayOf(Int::class.javaPrimitiveType!!, installedPage.javaClass,
                        List::class.java,
                        Boolean::class.javaPrimitiveType!!),
                    0, installedPage, sessionTiles, true
                )
                gate.onPageTilesReady(0, PAGE_WIDTH, PAGE_HEIGHT, sessionTiles)

                assertTrue(installed.get())
                assertEquals(DrawableOrigin.READER_SESSION, adopted.origin(0))
                assertEquals(1, downstream.tileDeliveries)

                // A borrowed Store page arrives after the renderer already has the Session page.
                // ReaderSession must consult the gate and leave both ownership domains untouched.
                invokePrivate(
                    session,
                    "deliverPreparedSourceTilePage",
                    arrayOf(Int::class.javaPrimitiveType!!,
                        ReaderPreparedStore.PreparedTilePage::class.java),
                    0, preparedPage
                )

                val trackedTiles = deliveredTiles(session)
                assertSame(sessionTiles, trackedTiles[0])
                assertTrue(deliveredOwned(session).contains(0))
                assertEquals(DrawableOrigin.READER_SESSION, adopted.origin(0))
                assertTrue(
                    adopted.matches(
                        0,
                        AdoptedDrawableIdentity.fullQualityTiles(
                            PAGE_WIDTH,
                            PAGE_HEIGHT,
                            sessionTiles
                        )!!
                    )
                )
                assertEquals(1, downstream.tileDeliveries)
                assertFalse(sessionBitmap.isRecycled)
                assertFalse(preparedBitmap.isRecycled)
                assertTrue(trackedTiles[0].orEmpty().none { it.bitmap === preparedBitmap })
            }
        } finally {
            instrumentation.runOnMainSync { session.cancel() }
            if (!preparedBitmap.isRecycled) preparedBitmap.recycle()
        }
    }

    private fun fullPageTile(bitmap: Bitmap) = ReaderTile(
        sourceTop = 0,
        sourceBottom = PAGE_HEIGHT,
        sourceWidth = PAGE_WIDTH,
        sourceHeight = PAGE_HEIGHT,
        bitmap = bitmap
    )

    private fun immutableBitmap(width: Int, height: Int): Bitmap {
        val mutable = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val immutable = mutable.copy(Bitmap.Config.ARGB_8888, false)
        mutable.recycle()
        return immutable
    }

    private fun ownershipGate(adopted: AdoptedDrawableRegistry): ReaderSessionListenerGate {
        val installed = AtomicBoolean(true)
        return ReaderSessionListenerGate(
            generation = 1,
            isActive = { it == 1 },
            adopted = adopted,
            installed = InstalledDrawableQuery { installed.get() },
            downstream = RecordingListener(installed)
        )
    }

    private fun newSession(name: String): ReaderSession {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ReaderSession(
            context = context,
            manga = Manga(1, name, "", MTitle.base_comic),
            title = null,
            viewerWidth = PAGE_WIDTH,
            viewerHeight = PAGE_HEIGHT,
            autoCut = false,
            reverse = false,
            preparedKey = null,
            startAtFirstPage = true,
            listener = RecordingListener(AtomicBoolean(false)),
        )
    }

    private fun invokePrivate(
        target: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any
    ): Any? {
        val method = target.javaClass.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deliveredTiles(session: ReaderSession): Map<Int, List<ReaderTile>> {
        val field = ReaderSession::class.java.getDeclaredField("deliveredTiles")
        field.isAccessible = true
        return field.get(session) as Map<Int, List<ReaderTile>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun deliveredOwned(session: ReaderSession): Set<Int> {
        val field = ReaderSession::class.java.getDeclaredField("deliveredOwned")
        field.isAccessible = true
        return field.get(session) as Set<Int>
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableDeliveredBitmaps(session: ReaderSession): MutableMap<Int, Bitmap> {
        val field = ReaderSession::class.java.getDeclaredField("deliveredBitmaps")
        field.isAccessible = true
        return field.get(session) as MutableMap<Int, Bitmap>
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableDeliveredTiles(session: ReaderSession): MutableMap<Int, List<ReaderTile>> {
        val field = ReaderSession::class.java.getDeclaredField("deliveredTiles")
        field.isAccessible = true
        return field.get(session) as MutableMap<Int, List<ReaderTile>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableDeliveredOwned(session: ReaderSession): MutableSet<Int> {
        val field = ReaderSession::class.java.getDeclaredField("deliveredOwned")
        field.isAccessible = true
        return field.get(session) as MutableSet<Int>
    }

    @Suppress("UNCHECKED_CAST")
    private fun externallyOwnedBitmaps(session: ReaderSession): MutableSet<Bitmap> {
        val field = ReaderSession::class.java.getDeclaredField("externallyOwnedBitmaps")
        field.isAccessible = true
        return field.get(session) as MutableSet<Bitmap>
    }

    private class RecordingListener(
        private val installed: AtomicBoolean
    ) : ReaderSession.Listener {
        var tileDeliveries = 0

        override fun onPagesReady(count: Int) = Unit
        override fun onPagesAppended(count: Int) = Unit
        override fun onPagesPrepended(count: Int, insertedCount: Int, holdUntilReadyCount: Int) = Unit
        override fun onPagesRemoved(startIndex: Int, removedCount: Int, totalCount: Int) = Unit
        override fun onInitialPage(index: Int) = Unit
        override fun onPageLoading(index: Int) = Unit
        override fun onPageBoundsReady(index: Int, width: Int, height: Int) = Unit
        override fun onPageReady(index: Int, bitmap: Bitmap) = Unit

        override fun onPageTilesReady(
            index: Int,
            pageWidth: Int,
            pageHeight: Int,
            tiles: List<ReaderTile>
        ) {
            tileDeliveries++
            installed.set(true)
        }

        override fun onPageCard(index: Int, title: String) = Unit
        override fun onPageError(index: Int, message: String) = Unit
        override fun onPageCleared(index: Int) = Unit
        override fun onMessage(message: String) = Unit
        override fun onCaptchaRequired(manga: Manga) = Unit

        override fun onBoundaryAppendFinished(
            anchor: Int,
            direction: Int,
            silent: Boolean,
            suppressedCaptcha: Boolean
        ) = Unit
    }

    private companion object {
        const val PAGE_WIDTH = 32
        const val PAGE_HEIGHT = 32
    }
}
