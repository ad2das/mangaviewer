package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkWebtoonReplicaAssetTest {
    @Test
    fun immutableReplicaPathKeepsOriginalFirstAndCoversEveryProductionHost() {
        val path = "/signed/qc/Y2Y1ZjE5LXY0NTMy.css"
        val candidates = ReaderImageCache.strictWebtoonReplicaUrlsForTest(
            "https://xiaomichina.com$path"
        )

        assertEquals(
            listOf(
                "https://xiaomichina.com$path",
                "https://f1spard.site$path",
                "https://shaomoi.org$path",
            ),
            candidates,
        )
    }

    @Test
    fun queryBearingOrForeignAssetsDoNotGainReplicaAuthority() {
        assertEquals(
            listOf("https://xiaomichina.com/image.jpg?token=mutable"),
            ReaderImageCache.strictWebtoonReplicaUrlsForTest(
                "https://xiaomichina.com/image.jpg?token=mutable"
            ),
        )
        assertEquals(
            listOf("https://example.com/image.jpg"),
            ReaderImageCache.strictWebtoonReplicaUrlsForTest(
                "https://example.com/image.jpg"
            ),
        )
    }

    @Test
    fun immutableManhwaPageUsesOnlyTheFiniteReplicaSet() {
        assertEquals(
            listOf(
                "https://booktoki8.org/manhwa/25277/294039/p009.jpeg",
                "https://booktoki9.org/manhwa/25277/294039/p009.jpeg",
                "https://mana.apihost93.com/manhwa/25277/294039/p009.jpeg",
                "https://aws-cdn1.site/manhwa/25277/294039/p009.jpeg",
            ),
            ReaderImageCache.strictReplicaUrlsForTest(
                "https://booktoki8.org/manhwa/25277/294039/p009.jpeg"
            ),
        )
        assertEquals(
            listOf("https://booktoki8.org/not-immutable/p009.jpeg"),
            ReaderImageCache.strictReplicaUrlsForTest(
                "https://booktoki8.org/not-immutable/p009.jpeg"
            ),
        )
    }

    @Test
    fun manhwaRecoveryRotatesRangeCapableMirrorsBeforeAwsFallback() {
        val candidates = ReaderImageCache.strictReplicaUrlsForTest(
            "https://booktoki9.org/manhwa/9298/84734/p093.jpg",
            pageIndex = 92,
        )

        assertEquals("https://booktoki9.org/manhwa/9298/84734/p093.jpg", candidates.first())
        assertEquals("https://aws-cdn1.site/manhwa/9298/84734/p093.jpg", candidates.last())
        assertEquals(
            setOf("booktoki8.org", "mana.apihost93.com"),
            candidates.subList(1, 3).map { java.net.URI(it).host }.toSet(),
        )
    }
}
