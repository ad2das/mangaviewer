package ml.melun.mangaview.data.engine

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.data.db.ViewerDatabase
import ml.melun.mangaview.data.db.ViewerDatabaseFactory
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineStorageInstrumentedTest {
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "publication"), "1"), 0)
    private val bytes = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=",
        Base64.DEFAULT,
    )

    @Test fun realFileSyncAndRoomRecoveryAtEveryPublicationBoundary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (boundary in EnginePublicationStep.entries) {
            val name = "engine-storage-${System.nanoTime()}.db"
            val root = File(context.cacheDir, "engine-storage-${System.nanoTime()}")
            var db = ViewerDatabaseFactory(name).open(context)
            try {
                val storage = store(root, db) { if (it == boundary) throw IOException("injected boundary") }
                val prepared = storage.prepare(id, "v1", body())
                try { storage.publish(prepared); fail("Expected boundary failure") } catch (_: IOException) { }
                db.close()
                db = ViewerDatabaseFactory(name).open(context)
                val recovered = store(root, db)
                recovered.recover()
                val lease = recovered.find(id, "v1")
                if (boundary == EnginePublicationStep.FILE_SYNCED) assertNull(lease)
                else {
                    assertNotNull(lease)
                    assertArrayEquals(bytes, lease!!.page.file.readBytes())
                    assertEquals(bytes.size.toLong(), recovered.trimTo(0))
                    lease.close()
                    assertEquals(0L, recovered.trimTo(0))
                }
                assertEquals(0, recovered.ownership().pendingPublications)
            } finally {
                db.close()
                context.deleteDatabase(name)
                check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                root.deleteRecursively()
            }
        }
    }

    @Test fun roomCommitRollsBackBothMetadataAndJournalDeletionOnFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "engine-atomic-${System.nanoTime()}.db"
        val root = File(context.cacheDir, "engine-atomic-${System.nanoTime()}")
        val db = ViewerDatabaseFactory(name).open(context)
        try {
            val storage = store(root, db)
            val prepared = storage.prepare(id, "v1", body())
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER abort_publication_delete " +
                "BEFORE DELETE ON engine_publications BEGIN SELECT RAISE(ABORT, 'injected'); END")
            var failed = false
            try { storage.publish(prepared) } catch (_: android.database.sqlite.SQLiteException) { failed = true }
            assertTrue(failed)
            assertNull(db.engine().page(PageCacheKey.of(id), "v1"))
            assertEquals(1, db.engine().publications().size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_publication_delete")
            storage.recover()
            checkNotNull(storage.find(id, "v1")).use { assertArrayEquals(bytes, it.page.file.readBytes()) }
            assertEquals(0, storage.ownership().preparedPages)
            assertEquals(0, storage.ownership().fileLeases)
        } finally {
            db.close()
            context.deleteDatabase(name)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    private fun store(root: File, db: ViewerDatabase,
        checkpoint: suspend (EnginePublicationStep) -> Unit = {}) = EngineRawStorage(
        root, RoomEnginePublicationIndex { db }, Dispatchers.IO,
        EnginePositionStore({ db }, Dispatchers.IO), checkpoint = checkpoint,
    )

    private fun body(): OpenedPage = OpenedPage(object : PageByteStream {
        var position = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            if (position == bytes.size) return -1
            val count = minOf(byteCount, bytes.size - position)
            bytes.copyInto(destination, offset, position, position + count)
            position += count
            return count
        }
        override fun close() = Unit
    }, bytes.size.toLong(), "image/png", null, null)
}
