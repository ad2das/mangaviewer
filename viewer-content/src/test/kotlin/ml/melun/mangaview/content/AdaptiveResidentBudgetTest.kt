package ml.melun.mangaview.content

import ml.melun.mangaview.viewer.session.DemandClass
import ml.melun.mangaview.viewer.session.SemanticViewportAnchor
import ml.melun.mangaview.viewer.session.SourceRangeFraction
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveResidentBudgetTest {
    @Test
    fun budgetsFollowPhysicalRamWithTheSpecifiedFallbackAndCeiling() {
        val mib = 1024L * 1024L
        assertEquals(128L * mib, adaptiveResidentBudgetBytes(null))
        assertEquals(128L * mib, adaptiveResidentBudgetBytes(0L))
        assertEquals(64L * mib, adaptiveResidentBudgetBytes(2048L * mib))
        assertEquals(256L * mib, adaptiveResidentBudgetBytes(8192L * mib))
        assertEquals(384L * mib, adaptiveResidentBudgetBytes(32768L * mib))
    }

    @Test
    fun pressureEvictsUnownedAndWarmTexturesButRetainsVisibleRows() {
        val fixture = PipelineFixture(pageCount = 2)
        val visible = PageRecord(fixture.manifest.pages[0], demand = DemandTarget(
            DemandClass.VISIBLE, SourceRangeFraction(0L, SemanticViewportAnchor.Q32_ONE / 2), 0))
        val warm = PageRecord(fixture.manifest.pages[1], demand = DemandTarget(
            DemandClass.CURRENT_FORWARD_NEAR, SourceRangeFraction(0L, SemanticViewportAnchor.Q32_ONE), 1))
        visible.residents = listOf(TextureRef(visible.page.id, 1, 1, 0, 800, 1600, 4096),
            TextureRef(visible.page.id, 1, 2, 800, 1600, 1600, 4096))
        warm.residents = listOf(TextureRef(warm.page.id, 1, 3, 0, 1600, 1600, 4096))
        val released = mutableListOf<Long>()
        val port = object : TextureUploadPort {
            override suspend fun upload(rendererEpoch: Long, pixels: CpuTileLease): TextureRef = error("unused")
            override fun release(texture: TextureRef) { released += texture.key }
        }
        evictColdResidents(listOf(visible, warm), 1L, ContentPipelineSink {}, port)
        assertEquals(listOf(2L, 3L), released)
        assertEquals(listOf(1L), visible.residents.map { it.key })
        assertEquals(emptyList<TextureRef>(), warm.residents)
    }

    @Test
    fun warmDecodeCannotCycleByEvictingHigherPriorityRows() {
        val fixture = PipelineFixture(pageCount = 2)
        val visible = PageRecord(fixture.manifest.pages[0], demand = target(DemandClass.VISIBLE, 0))
        visible.residents = listOf(TextureRef(visible.page.id, 1, 1, 0, 1600, 1600, 80))
        val warm = PageRecord(fixture.manifest.pages[1], demand = target(DemandClass.CURRENT_FORWARD_NEAR, 1))
        val plan = PipelineDecodePlan(warm, requireNotNull(warm.demand), fixture.encoded(warm.page.id),
            SourceRowRange(0, 1600), hard = false)
        assertFalse(canAdmitDecode(plan, 40, listOf(visible, warm), emptyList(), 100))
        visible.demand = target(DemandClass.BEHIND, 2)
        assertTrue(canAdmitDecode(plan, 40, listOf(visible, warm), emptyList(), 100))
    }

    @Test
    fun aCancelledNativeLaneStillReservesMemoryUntilPhysicalTermination() {
        val fixture = PipelineFixture(pageCount = 2)
        val retired = PageRecord(fixture.manifest.pages[0], decode = DecodeState.Decoding(
            1, SourceRowRange(0, 1600), true, Job(), cancelRequested = true, reservedByteCount = 80))
        val current = PageRecord(fixture.manifest.pages[1], demand = target(DemandClass.VISIBLE, 0))
        val plan = PipelineDecodePlan(current, requireNotNull(current.demand), fixture.encoded(current.page.id),
            SourceRowRange(0, 1600), hard = true)
        assertFalse(canAdmitDecode(plan, 40, listOf(current), listOf(retired), 100))
        assertTrue(canAdmitDecode(plan, 40, listOf(current), emptyList(), 100))
    }

    private fun target(kind: DemandClass, rank: Int) = DemandTarget(kind,
        SourceRangeFraction(0L, SemanticViewportAnchor.Q32_ONE), rank)
}
