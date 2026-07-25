package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkManhwaReplicaAssetTest {
    private val canonical = "https://booktoki9.org/manhwa/24123/240338/p001.jpg"

    @Test
    fun `signed api replica origins are equivalent for the exact immutable page`() {
        assertTrue(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                canonical,
                "https://booktoki8.org/manhwa/24123/240338/p001.jpg",
            ),
        )
        assertTrue(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                canonical,
                "https://mana.apihost93.com/manhwa/24123/240338/p001.jpg",
            ),
        )
        assertTrue(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                canonical,
                "https://aws-cdn1.site/manhwa/24123/240338/p001.jpg",
            ),
        )
        assertTrue(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                "https://booktoki8.org/manhwa/2640/5667/p002.gif",
                "https://booktoki9.org/manhwa/2640/5667/p002.gif",
            ),
        )
        assertTrue(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                "https://booktoki8.org/manhwa/2640/5667/p002.gif",
                "https://mana.apihost93.com/manhwa/2640/5667/p002.jpg",
            ),
        )
    }

    @Test
    fun `replica equivalence remains closed to identity changes`() {
        assertFalse(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                canonical,
                "https://evil.example/manhwa/24123/240338/p001.jpg",
            ),
        )
        assertFalse(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                canonical,
                "https://booktoki8.org/manhwa/24123/240338/p002.jpg",
            ),
        )
        assertFalse(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                canonical,
                "https://booktoki8.org/manhwa/24123/240338/p001.jpg?variant=other",
            ),
        )
        assertFalse(
            ReaderImageCache.areEquivalentManhwaReplicaAssets(
                canonical,
                "http://booktoki8.org/manhwa/24123/240338/p001.jpg",
            ),
        )
    }

    @Test
    fun `zero length image head cannot shadow the next real extension`() {
        assertFalse(
            ReaderImageCache.isUsableClickOwnedManhwaProbeResponse(
                200,
                "image/gif",
                0L,
            ),
        )
        assertTrue(
            ReaderImageCache.isUsableClickOwnedManhwaProbeResponse(
                200,
                "image/gif",
                110_518L,
            ),
        )
        assertTrue(
            ReaderImageCache.isUsableClickOwnedManhwaProbeResponse(
                200,
                "image/jpeg",
                null,
            ),
        )
        assertFalse(
            ReaderImageCache.isUsableClickOwnedManhwaProbeResponse(
                404,
                "image/jpeg",
                110_518L,
            ),
        )
    }

    @Test
    fun `gif immutable pages retain the full replica failover ring`() {
        val candidates = ReaderImageCache.strictReplicaUrlsForTest(
            "https://booktoki8.org/manhwa/23622/227436/p002.gif",
            pageIndex = 1,
        )
        assertEquals(4, candidates.size)
        assertTrue(candidates.any { it.contains("booktoki8.org") })
        assertTrue(candidates.any { it.contains("mana.apihost93.com") })
        assertTrue(candidates.any { it.contains("booktoki9.org") })
        assertTrue(candidates.any { it.contains("aws-cdn1.site") })
    }

    @Test
    fun `terminal jpg replica ring exposes bounded immutable extension fallbacks`() {
        val candidates = ReaderImageCache.strictManhwaExtensionFallbackUrlsForTest(
            "https://booktoki8.org/manhwa/23891/1706358/p034.jpg",
            pageIndex = 33,
        )

        assertEquals(16, candidates.size)
        assertTrue(candidates.take(4).all { it.endsWith("/p034.gif") })
        assertTrue(candidates.any { it.endsWith("/p034.webp") })
        assertTrue(candidates.any { it.endsWith("/p034.png") })
        assertTrue(candidates.any { it.endsWith("/p034.jpeg") })
        assertTrue(candidates.all {
            it.contains("/manhwa/23891/1706358/p034.")
        })
    }
}
