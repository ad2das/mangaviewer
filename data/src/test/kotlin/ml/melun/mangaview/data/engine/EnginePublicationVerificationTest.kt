package ml.melun.mangaview.data.engine

import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EnginePositionPort
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The publication path seeds the verification cache: transfer() digested exactly the bytes it wrote
 * to a process-private staging name, the prepared length was checked, and rename preserved size and
 * mtime, so the first find of a freshly published page must trust that digest instead of re-reading
 * the body. Any later change of size or mtime still forces a full re-verification, and a fresh
 * owner on the same root digs once on its first find.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnginePublicationVerificationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun publishSeedsVerificationSoFindsDoNotRedigest() = runTest {
        val store = newStore(temporary.newFolder(), MemoryIndex())
        store.publish(store.prepare(id, "v1", Body(payload).opened())).close()

        assertEquals("publish must not leave a pending digest", 0, store.pageFileStats().digestPasses)
        repeat(3) { checkNotNull(store.find(id, "v1")).close() }
        assertEquals("finds must reuse the publish digest", 0, store.pageFileStats().digestPasses)
    }

    @Test fun tamperingAfterPublishForcesAReVerificationAndFails() = runTest {
        val store = newStore(temporary.newFolder(), MemoryIndex())
        store.publish(store.prepare(id, "v1", Body(payload).opened())).close()

        val lease = checkNotNull(store.find(id, "v1"))
        val file = lease.page.file
        lease.close()
        assertEquals(0, store.pageFileStats().digestPasses)

        val mutated = payload.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        file.writeBytes(mutated)
        check(file.setLastModified(file.lastModified() + 60_000L)) { "cannot age the tampered file" }

        assertNull("a body changed after publication must not validate", store.find(id, "v1"))
        assertEquals("the changed stamp must force a full digest", 1, store.pageFileStats().digestPasses)
    }

    @Test fun freshOwnerOnTheSameRootDigestsOnFirstFind() = runTest {
        val root = temporary.newFolder()
        val index = MemoryIndex()
        val first = newStore(root, index)
        first.publish(first.prepare(id, "v1", Body(payload).opened())).close()

        val restarted = newStore(root, index)
        restarted.recover()
        assertEquals(0, restarted.pageFileStats().digestPasses)
        checkNotNull(restarted.find(id, "v1")).close()
        assertEquals("a fresh owner must verify once", 1, restarted.pageFileStats().digestPasses)
    }

    private suspend fun TestScope.newStore(root: File, index: EnginePublicationIndex) = EngineRawStorage(
        root, index, StandardTestDispatcher(testScheduler, "storage"), VerificationPositions, LocalFileOps(), { 100L }, {},
    )

    private val id = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "30001"), "9"), 3)

    private val payload = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=",
    )

    private object VerificationPositions : EnginePositionPort {
        override suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) = Unit
        override suspend fun load(episodeId: EpisodeId): SourceAnchor? = null
    }

    private class Body(private val payload: ByteArray) : PageByteStream {
        private var cursor = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            if (cursor == payload.size) return -1
            val count = minOf(byteCount, payload.size - cursor)
            payload.copyInto(destination, offset, cursor, cursor + count)
            cursor += count
            return count
        }
        override fun close() = Unit
        fun opened() = OpenedPage(this, payload.size.toLong(), "image/png", null, null)
    }
}
