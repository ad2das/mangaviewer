package ml.melun.mangaview.reader

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderImageCacheStrictSpoolPublicationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun crossDeviceBodyIsCopiedOnlyToDestinationSideTemp() {
        val spoolDir = temporaryFolder.newFolder("no-backup", "strict-spool")
        val cacheDir = temporaryFolder.newFolder("cache", "reader")
        val source = File(spoolDir, "active.tmp").apply {
            writeBytes(ByteArray(4096) { index -> (index and 0xff).toByte() })
        }
        val finalFile = File(cacheDir, "body.img").apply {
            writeBytes(byteArrayOf(9, 8, 7))
        }

        var renameAttempts = 0
        val staged = ReaderImageCache.stageStrictEncodedBodyForTest(
            source,
            finalFile,
            source.length()
        ) { _, _ ->
            renameAttempts++
            false
        }
        try {
            assertEquals(1, renameAttempts)
            assertEquals(cacheDir.canonicalFile, staged.parentFile!!.canonicalFile)
            assertTrue(staged.name.endsWith(".tmp"))
            assertArrayEquals(source.readBytes(), staged.readBytes())
            assertTrue(source.isFile)
            // Publication has not happened yet; an interrupted copy cannot expose a partial final.
            assertArrayEquals(byteArrayOf(9, 8, 7), finalFile.readBytes())
        } finally {
            staged.delete()
        }
    }

    @Test
    fun sameFilesystemBodyIsRenamedToDestinationSideTempWithoutCopy() {
        val spoolDir = temporaryFolder.newFolder("no-backup-fast", "strict-spool")
        val cacheDir = temporaryFolder.newFolder("cache-fast", "reader")
        val expected = ByteArray(4096) { index -> ((index * 17) and 0xff).toByte() }
        val source = File(spoolDir, "active.tmp").apply { writeBytes(expected) }
        val finalFile = File(cacheDir, "body.img").apply {
            writeBytes(byteArrayOf(9, 8, 7))
        }

        var renameAttempts = 0
        val staged = ReaderImageCache.stageStrictEncodedBodyForTest(
            source,
            finalFile,
            expected.size.toLong()
        ) { from, destination ->
            renameAttempts++
            Files.move(
                from.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        }
        try {
            assertEquals(1, renameAttempts)
            assertFalse(source.exists())
            assertEquals(cacheDir.canonicalFile, staged.parentFile!!.canonicalFile)
            assertArrayEquals(expected, staged.readBytes())
            // The second, same-directory publication rename has not run yet.
            assertArrayEquals(byteArrayOf(9, 8, 7), finalFile.readBytes())
        } finally {
            staged.delete()
        }
    }

    @Test
    fun invalidSourceLengthCreatesNoDestinationArtifact() {
        val spoolDir = temporaryFolder.newFolder("spool")
        val cacheDir = temporaryFolder.newFolder("destination")
        val source = File(spoolDir, "active.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val finalFile = File(cacheDir, "body.img")

        try {
            ReaderImageCache.stageStrictEncodedBodyForTest(source, finalFile, 4L) { _, _ ->
                throw AssertionError("Invalid input must fail before rename")
            }
            throw AssertionError("Expected strict source length validation to fail")
        } catch (_: FileNotFoundException) {
        }

        assertFalse(finalFile.exists())
        assertTrue(cacheDir.listFiles().isNullOrEmpty())
        assertTrue(source.isFile)
    }

    @Test
    fun orphanDestinationPublishTempsAreAgeFilteredAndWorkBounded() {
        val cacheDir = temporaryFolder.newFolder("publish-orphans")
        val stale = (0 until 5).map { index ->
            File(cacheDir, "body.img.strict.publish.$index.tmp").apply {
                writeBytes(byteArrayOf(index.toByte()))
                assertTrue(setLastModified(1_000L))
            }
        }
        val fresh = File(cacheDir, "fresh.img.strict.publish.1.tmp").apply {
            writeBytes(byteArrayOf(7))
            assertTrue(setLastModified(3_000L))
        }
        val unrelated = File(cacheDir, "unrelated.tmp").apply {
            writeBytes(byteArrayOf(8))
            assertTrue(setLastModified(1_000L))
        }

        assertEquals(
            0,
            ReaderImageCache.cleanupStaleStrictPublishTempsForTest(
                cacheDir,
                staleBeforeMs = 2_000L,
                maxScanned = 0,
                maxDeletes = 10
            )
        )
        assertEquals(
            2,
            ReaderImageCache.cleanupStaleStrictPublishTempsForTest(
                cacheDir,
                staleBeforeMs = 2_000L,
                maxScanned = 100,
                maxDeletes = 2
            )
        )
        assertEquals(3, stale.count(File::exists))
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
    }
}
