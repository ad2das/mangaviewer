package ml.melun.mangaview.engine.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cost-regression proof for the bounded backward walk and the identity-keyed page index: the
 * counters are test-only evidence that a small reverse move deep inside a 5 x 400-page retained
 * window touches only the pages it actually crosses, while the frozen pre-fix reference on the
 * same geometry still walks the whole window.
 */
class DocumentGeometryWalkCostTest {
    @Test
    fun smallBackwardMoveTouchesOnlyThePagesItCrosses() {
        val geometry = deepWindowGeometry()
        val anchor = requireNotNull(geometry.anchor)
        val delta = BigRational.of(-7_000_000L)

        // Warm the metric and index caches the way a forward fling does before a reverse move.
        geometry.move(pageScreenLength(720, 4000, 1080) * BigRational.of(2L))
        geometry.anchor = anchor

        val walkBefore = geometry.backwardWalkPages
        val buildsBefore = geometry.pageIndices.builds
        val scansBefore = geometry.pageIndices.scans
        val lookupsBefore = geometry.pageIndices.lookups

        val actual = geometry.move(delta)
        val actualAnchor = geometry.anchor

        val walkedPages = geometry.backwardWalkPages - walkBefore
        assertTrue("backward walk stepped $walkedPages pages", walkedPages in 1L..4L)
        assertEquals(0L, geometry.pageIndices.builds - buildsBefore)
        assertEquals(0L, geometry.pageIndices.scans - scansBefore)
        val lookups = geometry.pageIndices.lookups - lookupsBefore
        assertTrue("index lookups=$lookups", lookups <= 8L)

        // The frozen reference on the same geometry still pays the whole retained-window walk.
        geometry.anchor = anchor
        val reference = ReferenceGeometryMove(geometry)
        val expected = reference.move(delta)
        assertEquals(expected, actual)
        assertEquals(geometry.anchor, actualAnchor)
        assertTrue("reference walked ${reference.walkPages} pages", reference.walkPages > 1_000L)
    }

    @Test
    fun resolveNavigationRebuildsTheIndexOncePerIdentityChange() {
        val geometry = deepWindowGeometry()
        val delta = BigRational.of(-7_000_000L)
        geometry.move(delta)

        val anchor = requireNotNull(geometry.anchor)
        val episodeId = anchor.pageId.episodeId
        val manifest = requireNotNull(geometry.manifests[episodeId])
        val buildsBefore = geometry.pageIndices.builds
        val scansBefore = geometry.pageIndices.scans

        geometry.resolveNavigation(episodeId, manifest.previousEpisodeId, manifest.nextEpisodeId)
        geometry.anchor = anchor
        geometry.move(delta)

        assertEquals(1L, geometry.pageIndices.builds - buildsBefore)
        assertEquals(manifest.pages.size.toLong(), geometry.pageIndices.scans - scansBefore)
    }
}
