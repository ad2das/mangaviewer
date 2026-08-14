package ml.melun.mangaview.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Manga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NtkStrictSourceSessionRollingStartInstrumentedTest {
    @Test
    fun rollingSessionStartFutureCompletesWithTheFiniteOpeningWave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binding = binding(pageCount = 5)
        val manga = Manga(EPISODE_ID.toInt(), "rolling-start", "", MTitle.base_comic).apply {
            ntkEpisodePath = PATH
        }
        val closeBarrier = AtomicReference<NtkQuarantineCloseBarrierProof>()
        val closeComplete = CountDownLatch(1)
        val terminalFailure = AtomicReference<Throwable>()
        NtkQuarantineSourceOwnershipRegistry.resetForTest()

        val session = NtkStrictSourceSession(
            context = context,
            manga = manga,
            planBinding = binding,
            initialPageIndex = 0,
            executionBootstrap = NtkStrictSourceExecutionBootstrap(),
            onQuarantineCloseBarrier = { proof ->
                closeBarrier.set(proof)
                closeComplete.countDown()
            },
            onExactCloseBarrier = { error("Exact ownership is not installed in this test") },
            onTerminalFailure = { failure -> terminalFailure.compareAndSet(null, failure) },
            rollingAdmission = true
        )

        try {
            val proof = session.enqueueStartQuarantined().get(5, TimeUnit.SECONDS)

            assertEquals(binding.pageCount, proof.initialWaveCount)
            assertEquals(binding.pageCount, proof.submittedOperationCount)
            assertTrue(proof.physicalCallCountAtProof in 0..binding.pageCount)
            assertEquals(0, proof.duplicatePhysicalCallCount)
        } finally {
            session.requestClose(null)
            assertTrue("quarantine session did not close", closeComplete.await(10, TimeUnit.SECONDS))
            assertNotNull(closeBarrier.get())
            NtkQuarantineSourceOwnershipRegistry.resetForTest()
        }
    }

    private fun binding(pageCount: Int): NtkQuarantinePlanBinding {
        val token = "rolling-start-token"
        val identity = NtkViewerImageRequestIdentity.create(
            "manhwa",
            "/api/manhwa-images",
            WORK_ID,
            EPISODE_ID,
            token
        )
        val body = """{"sourceWorkId":"$WORK_ID","episodeId":"$EPISODE_ID","pages":$pageCount}"""
            .toByteArray()
        // Port 1 is deliberately closed. The start future proves actor admission before physical
        // completion callbacks can run; finally cancels the finite local-only wave and awaits drain.
        val assets = (1..pageCount).map { "https://127.0.0.1:1/page-$it.jpg" }
        val proof = NtkEpisodeDocumentPlanProof.create(
            PATH,
            DISCOVERY_GENERATION,
            "https://newtoki.example$PATH",
            "https://newtoki.example$PATH",
            NtkStripDigests.sha256Tokens("rolling-start-headers"),
            body,
            body,
            (1..pageCount).toList(),
            assets,
            identity
        )
        return NtkQuarantinePlanBinding.from(
            NtkProvisionalEpisodePlan.create(proof, token, assets)
        )
    }

    private companion object {
        const val WORK_ID = "910001"
        const val EPISODE_ID = "920001"
        const val PATH = "/manhwa/$WORK_ID/$EPISODE_ID"
        const val DISCOVERY_GENERATION = 990001L
    }
}
