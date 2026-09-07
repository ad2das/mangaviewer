package ml.melun.mangaview.source.ntk

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NtkStaticResourceDiskStoreTest {
    private val root = Files.createTempDirectory("ntk-static-store-").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun reusesOnlyAHashedUnexpiredAssetFromTheSameOrigin() {
        var now = 10_000L
        val store = NtkStaticResourceDiskStore({ root }, { now }, maximumAgeMillis = 1_000L)
        val resource = resource(byteArrayOf(1, 2, 3, 4))

        store.save(resource)
        val loaded = store.load(ORIGIN, PATH, 1_024, MIME)

        assertEquals(resource.path, loaded?.path)
        assertArrayEquals(resource.bytes, loaded?.bytes)
        now += 1_001L
        assertNull(store.load(ORIGIN, PATH, 1_024, MIME))
    }

    @Test
    fun anotherOriginCannotReadOrInvalidateTheOriginalAsset() {
        val store = NtkStaticResourceDiskStore({ root }, { 10_000L }, maximumAgeMillis = 1_000L)
        store.save(resource(byteArrayOf(5, 6, 7)))

        assertNull(store.load("https://different.example", PATH, 1_024, MIME))
        assertNotNull(store.load(ORIGIN, PATH, 1_024, MIME))

        store.save(resource(byteArrayOf(8, 9, 10)))
        requireNotNull(root.listFiles()).single().appendBytes(byteArrayOf(99))
        assertNull(store.load(ORIGIN, PATH, 1_024, MIME))
        assertFalse(root.listFiles().orEmpty().any(File::isFile))
    }

    @Test
    fun portsRemainSeparateAndProviderFreshnessCanExpireBeforeLocalMaximumAge() {
        var now = 10_000L
        val store = NtkStaticResourceDiskStore({ root }, { now }, maximumAgeMillis = 1_000L)
        store.save(resource(byteArrayOf(1)))
        store.save(resource(byteArrayOf(2)).copy(originalPort = 8443, expiresAtMillis = 10_050L))
        assertArrayEquals(byteArrayOf(1), store.load(ORIGIN, PATH, 1_024, MIME)?.bytes)
        assertArrayEquals(byteArrayOf(2), store.load("$ORIGIN:8443", PATH, 1_024, MIME)?.bytes)
        now = 10_051L
        assertNull(store.load("$ORIGIN:8443", PATH, 1_024, MIME))
        assertNotNull(store.load(ORIGIN, PATH, 1_024, MIME))
    }

    private fun resource(bytes: ByteArray) = NtkStaticResource(
        path = PATH,
        originalHost = "toki31.com",
        finalHost = "toki31.com",
        mimeType = MIME,
        bytes = bytes,
    )

    private companion object {
        const val ORIGIN = "https://toki31.com"
        const val PATH = "/wasm/ad-guard/ad_guard_bg.wasm"
        const val MIME = "application/wasm"
    }
}
