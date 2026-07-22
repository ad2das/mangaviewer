package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPagePipelineStateTest {
    private fun pipeline() = ReaderPagePipeline(episodeEpoch = 7L, pageCount = 31)

    private fun bytesReady(pipeline: ReaderPagePipeline, index: Int = 0): String {
        val request = pipeline.requestDrawable(index, ReaderPagePipeline.Demand.VISIBLE)
        val lease = assertNotNull(request.lease).let { request.lease!! }
        val asset = "https://cdn/episode/p${index.toString().padStart(3, '0')}.jpg"
        assertTrue(pipeline.acceptBytes(ReaderPagePipeline.ByteCompletion(7L, index, asset, asset, lease.leaseId)))
        return asset
    }

    @Test
    fun rejectsWrongEpochAndWrongLeaseCompletion() {
        val pipeline = pipeline()
        val request = pipeline.requestBytes(0, ReaderPagePipeline.Demand.VISIBLE)
        val lease = request.lease!!
        val asset = "https://cdn/episode/p000.jpg"

        assertFalse(pipeline.acceptBytes(ReaderPagePipeline.ByteCompletion(8L, 0, asset, asset, lease.leaseId)))
        assertFalse(pipeline.acceptBytes(ReaderPagePipeline.ByteCompletion(7L, 0, asset, asset, lease.leaseId + 1)))
        assertEquals(ReaderPagePipeline.Stage.BYTE_IN_FLIGHT, pipeline.pageSnapshot(0)!!.stage)
        assertEquals(2L, pipeline.invariantSnapshot().rejectedStaleCompletions)
    }

    @Test
    fun onePageCannotIssueTwoDecodeLeases() {
        val pipeline = pipeline()
        bytesReady(pipeline)
        val first = pipeline.requestDrawable(0, ReaderPagePipeline.Demand.VISIBLE)
        val second = pipeline.requestDrawable(0, ReaderPagePipeline.Demand.PHYSICAL_RUNWAY)

        assertEquals(ReaderPagePipeline.RequestDisposition.STARTED_DECODE, first.disposition)
        assertEquals(ReaderPagePipeline.RequestDisposition.JOINED_DECODE, second.disposition)
        assertNotNull(first.lease)
        assertEquals(1, pipeline.invariantSnapshot().activeDecodeOwners)
    }

    @Test
    fun duplicateInstallIsRejectedAfterCommit() {
        val pipeline = pipeline()
        val asset = bytesReady(pipeline)
        val decode = pipeline.requestDrawable(0, ReaderPagePipeline.Demand.VISIBLE).lease!!
        val identity = ReaderPagePipeline.TileIdentity(asset, 1080, 4096)
        assertTrue(pipeline.acceptTiles(ReaderPagePipeline.TileCompletion(7L, 0, asset, decode.leaseId, identity)))
        pipeline.updatePhysicalWindow(ReaderPagePipeline.PhysicalWindow.contiguous(0, 3))
        val install = pipeline.queueInstall(0, surfaceEpoch = 11L)!!

        assertTrue(pipeline.confirmInstalled(0, identity, 11L, install.leaseId))
        assertFalse(pipeline.confirmInstalled(0, identity, 11L, install.leaseId))
        assertEquals(ReaderPagePipeline.Stage.INSTALLED, pipeline.pageSnapshot(0)!!.stage)
    }

    @Test
    fun offPhysicalPageCannotQueueSurfaceInstall() {
        val pipeline = pipeline()
        val asset = bytesReady(pipeline, 9)
        val decode = pipeline.requestDrawable(9, ReaderPagePipeline.Demand.ROLLING_PROOF_METADATA).lease!!
        val identity = ReaderPagePipeline.TileIdentity(asset, 1080, 4096)
        assertTrue(pipeline.acceptTiles(ReaderPagePipeline.TileCompletion(7L, 9, asset, decode.leaseId, identity)))
        pipeline.updatePhysicalWindow(ReaderPagePipeline.PhysicalWindow.contiguous(0, 3))

        assertEquals(null, pipeline.queueInstall(9, 11L))
        assertEquals(ReaderPagePipeline.Stage.TILES_READY, pipeline.pageSnapshot(9)!!.stage)
    }

    @Test
    fun retireRejectsEveryLateCompletion() {
        val pipeline = pipeline()
        val request = pipeline.requestBytes(0, ReaderPagePipeline.Demand.VISIBLE)
        val asset = "https://cdn/episode/p000.jpg"
        pipeline.retire("episode_changed")

        assertFalse(pipeline.acceptBytes(ReaderPagePipeline.ByteCompletion(7L, 0, asset, asset, request.lease!!.leaseId)))
        assertEquals(ReaderPagePipeline.RequestDisposition.RETIRED,
            pipeline.requestDrawable(0, ReaderPagePipeline.Demand.VISIBLE).disposition)
        assertEquals(ReaderPagePipeline.Stage.RETIRED, pipeline.pageSnapshot(0)!!.stage)
    }
}
