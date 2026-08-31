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
    fun resolvedAckStaysOnItsLaneAndTheNextEpisodeUsesTheOtherLane() = runTest {
        val first = PoolLane()
        val second = PoolLane()
        val pool = NtkAccessGatewayPool(listOf(first, second))
        val current = "/webtoon/1/current"
        val next = "/webtoon/1/next"

        pool.prepare(ORIGIN, current, PreparationIntent.INITIAL_VIEW)
        pool.resolve(document(current), DESCRIPTOR)
        assertTrue(pool.awaitAuthorization(ORIGIN, current))
        pool.prepare(ORIGIN, next, PreparationIntent.ADJACENT_FORWARD)

        assertEquals(listOf(current), first.prepared)
        assertEquals(listOf(next), second.prepared)
        pool.pageAccessEstablished(ORIGIN, current)
        assertEquals(listOf(current), first.established)
    }

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

    @Test
    fun directManifestCompletionReleasesItsLeaseButKeepsAuthorizationResident() = runTest {
        val lane = PoolLane()
        val pool = NtkAccessGatewayPool(listOf(lane))
        val direct = "/manhwa/1/direct"
        val following = "/manhwa/2/following"

        pool.prepare(ORIGIN, direct, PreparationIntent.INITIAL_VIEW)
        pool.manifestResolutionFinished(ORIGIN, direct)
        assertTrue(pool.awaitAuthorization(ORIGIN, direct))

        pool.prepare(ORIGIN, following, PreparationIntent.INITIAL_VIEW)
        assertEquals(listOf(direct, following), lane.prepared)
        pool.manifestResolutionFinished(ORIGIN, following)
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
    val established = mutableListOf<String>()

    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) {
        prepared += episodePath
    }

    override suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> = listOf(NtkPageRequest("https://images.test/page.jpg"))

    override fun pageAccessEstablished(origin: String, episodePath: String) {
        established += episodePath
    }
}
