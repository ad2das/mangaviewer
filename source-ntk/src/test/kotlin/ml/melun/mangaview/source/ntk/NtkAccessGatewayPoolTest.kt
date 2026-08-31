package ml.melun.mangaview.source.ntk

import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PreparationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAccessGatewayPoolTest {
    @Test
    fun twoDocumentsOwnIndependentLanesAndAThirdWaitsForRelease() = runTest {
        val first = PoolLane()
        val second = PoolLane()
        val pool = NtkAccessGatewayPool(listOf(first, second))

        pool.prepare(ORIGIN, "/webtoon/1/a", PreparationIntent.INITIAL_VIEW)
        pool.prepare(ORIGIN, "/webtoon/1/b", PreparationIntent.ADJACENT_FORWARD)
        assertEquals(1, first.prepared.size)
        assertEquals(1, second.prepared.size)

        val third = async {
            pool.prepare(ORIGIN, "/webtoon/1/c", PreparationIntent.ADJACENT_FORWARD)
        }
        yield()
        assertFalse(third.isCompleted)

        pool.resolve(document("/webtoon/1/a"), DESCRIPTOR)
        third.await()
        assertTrue(first.prepared.contains("/webtoon/1/c"))

        pool.resolve(document("/webtoon/1/b"), DESCRIPTOR)
        pool.resolve(document("/webtoon/1/c"), DESCRIPTOR)
        pool.close()
    }

    private fun document(path: String) = NtkEpisodeDocument(
        origin = ORIGIN,
        path = path,
        html = "",
    )

    private companion object {
        const val ORIGIN = "https://ntk.test"
        val DESCRIPTOR = NtkViewerDescriptor(
            "work",
            "episode",
            "token",
            "/api/webtoon-images",
            1,
        )
    }
}

private class PoolLane : NtkAccessGateway {
    val prepared = mutableListOf<String>()

    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) {
        prepared += episodePath
    }

    override suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> = listOf(NtkPageRequest("https://images.test/page.jpg"))
}
