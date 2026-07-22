package ml.melun.mangaview.reader

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkFullScenePreparationMachineInterleavingTest {
    @Test
    fun tenThousandSurfaceIndependentArrivalOrdersConvergeToOneSeal() {
        val baseline = run(seed = 0L)

        repeat(10_000) { seed ->
            val actual = run(seed.toLong())
            assertEquals("seal seed=$seed", baseline.sealDigest, actual.sealDigest)
            assertEquals(baseline.geometryDigest, actual.geometryDigest)
            assertEquals(baseline.nativeInventoryDigest, actual.nativeInventoryDigest)
            assertEquals(1L, actual.installAckCount)
            assertEquals(1L, actual.geometryBindCount)
        }
    }

    private data class Result(
        val sealDigest: String,
        val geometryDigest: String,
        val nativeInventoryDigest: String,
        val installAckCount: Long,
        val geometryBindCount: Long
    )

    private fun run(seed: Long): Result {
        val random = Random(seed)
        val h = NtkDetachedPreparationMachineHarness()
        var tokenSent = false
        var seedSent = false
        var surfaceSent = false
        var metadataSent = false
        var bodySent = false
        var sourceSent = false
        var closeReady = false
        var decodeStarted = false
        var pendingOpen: NtkFullScenePreparationCommand.OpenBodyLease? = null
        var pendingStart: NtkFullScenePreparationCommand.StartDecode? = null
        var running: NtkFullScenePreparationCommand.StartDecode? = null
        var pendingRelease: NtkFullScenePreparationCommand.ReleaseBodyLease? = null
        var pendingInstall: NtkFullScenePreparationCommand.InstallPrepared? = null
        var pendingAdoption:
            NtkFullScenePreparationCommand.AdoptDetachedPreparation? = null
        var published: NtkPreparedFullSceneSeal? = null

        fun accept(commands: List<NtkFullScenePreparationCommand>) {
            commands.forEach { command ->
                when (command) {
                    is NtkFullScenePreparationCommand.OpenBodyLease -> {
                        check(pendingOpen == null)
                        pendingOpen = command
                    }
                    is NtkFullScenePreparationCommand.StartDecode -> {
                        check(pendingStart == null)
                        pendingStart = command
                    }
                    is NtkFullScenePreparationCommand.StartDecodeCohort ->
                        error("Single-page interleaving fixture emitted a three-wide cohort")
                    is NtkFullScenePreparationCommand.ReleaseBodyLease -> {
                        check(pendingRelease == null)
                        pendingRelease = command
                    }
                    is NtkFullScenePreparationCommand.InstallPrepared -> {
                        check(pendingInstall == null)
                        pendingInstall = command
                    }
                    is NtkFullScenePreparationCommand.AdoptDetachedPreparation -> {
                        check(pendingAdoption == null)
                        pendingAdoption = command
                    }
                    NtkFullScenePreparationCommand.ClosePreparationAdmissions -> {
                        closeReady = true
                    }
                    is NtkFullScenePreparationCommand.PublishSeal -> published = command.seal
                    is NtkFullScenePreparationCommand.NotifyGeometrySealed -> Unit
                    is NtkFullScenePreparationCommand.ReleasePreparationAuthority ->
                        error("seed=$seed failed: ${command.reason}")
                }
            }
            check(h.snapshot.phase != NtkFullScenePreparationPhase.FAILED) {
                "seed=$seed failure=${h.snapshot.failureReason}"
            }
        }

        var turns = 0
        while (published == null) {
            check(++turns < 200) { "seed=$seed did not converge" }
            val actions = ArrayList<Int>(13)
            if (!tokenSent) actions += 0
            if (!seedSent) actions += 1
            if (!surfaceSent) actions += 2
            if (!metadataSent) actions += 3
            if (metadataSent && !bodySent) actions += 4
            if (pendingOpen != null) actions += 5
            if (pendingStart != null && !decodeStarted) actions += 6
            if (running != null) actions += 7
            if (pendingRelease != null) actions += 8
            if (pendingInstall != null) actions += 9
            if (pendingAdoption != null) actions += 10
            if (!sourceSent && bodySent &&
                h.snapshot.bodyLeaseLedger.activeCount == 0
            ) actions += 11
            if (closeReady) actions += 12
            check(actions.isNotEmpty()) { "seed=$seed has no causal action" }

            when (actions[random.nextInt(actions.size)]) {
                0 -> {
                    tokenSent = true
                    accept(h.openDetached())
                }
                1 -> {
                    seedSent = true
                    accept(h.seedGeometry())
                }
                2 -> {
                    surfaceSent = true
                    accept(h.publishSurface())
                }
                3 -> {
                    metadataSent = true
                    accept(h.publishMetadata(0))
                }
                4 -> {
                    bodySent = true
                    accept(h.publishBody(0))
                }
                5 -> {
                    val command = checkNotNull(pendingOpen)
                    pendingOpen = null
                    accept(h.openLease(command))
                }
                6 -> {
                    val command = checkNotNull(pendingStart)
                    pendingStart = null
                    decodeStarted = true
                    running = command
                    accept(h.startDecode(command))
                }
                7 -> {
                    val command = checkNotNull(running)
                    running = null
                    accept(h.completeDecode(command))
                }
                8 -> {
                    val command = checkNotNull(pendingRelease)
                    pendingRelease = null
                    accept(h.releaseLease(command))
                }
                9 -> {
                    val command = checkNotNull(pendingInstall)
                    pendingInstall = null
                    accept(h.acknowledgeInstall(command))
                }
                10 -> {
                    val command = checkNotNull(pendingAdoption)
                    pendingAdoption = null
                    accept(h.acknowledgeAdoption(command))
                }
                11 -> {
                    sourceSent = true
                    accept(h.send(NtkFullScenePreparationEvent.SourceDrained(h.sourceProof())))
                }
                12 -> {
                    closeReady = false
                    accept(h.finishDrain())
                }
            }
        }

        val seal = checkNotNull(published)
        assertEquals(NtkFullScenePreparationPhase.SEALED, h.snapshot.phase)
        assertTrue(seal.resourceCycleLedger.isValid)
        return Result(
            sealDigest = seal.sealDigest,
            geometryDigest = seal.geometryDigest,
            nativeInventoryDigest = seal.nativeInventoryDigest,
            installAckCount = seal.counters.installAckCount,
            geometryBindCount = seal.counters.geometryBindCount
        )
    }
}
