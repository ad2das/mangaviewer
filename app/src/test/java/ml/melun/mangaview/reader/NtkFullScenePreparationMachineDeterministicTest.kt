package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkFullScenePreparationMachineDeterministicTest {
    @Test
    fun sourceDecodeAndDetachedUploadCompleteWithoutSurface() {
        val h = NtkDetachedPreparationMachineHarness()
        h.openDetached()
        h.seedGeometry()
        h.publishMetadata(0)

        val completed = decodePage(h, 0)
        val install = completed.install
        assertNull(install.surfaceToken)
        h.acknowledgeInstall(install)
        h.releaseLease(completed.release)

        assertNull(h.snapshot.publishedSurface)
        assertEquals(NtkFullScenePreparationPhase.GEOMETRY_READY, h.snapshot.phase)
        assertEquals(
            NtkPreparationTileState.NATIVE_PREPARED,
            h.snapshot.tileRecords.values.single().state
        )
        assertEquals(1L, h.snapshot.counters.installAckCount)
    }

    @Test
    fun positiveSurfaceAdoptsDetachedInventoryExactlyOnce() {
        val h = preparedBeforeSurface()

        val adoption = h.publishSurface().singleOf<
            NtkFullScenePreparationCommand.AdoptDetachedPreparation
        >()
        assertEquals(
            NtkFullScenePreparationPhase.SURFACE_BIND_QUEUED,
            h.snapshot.phase
        )
        assertEquals(1, adoption.request.preparedTileKeys.size)

        val after = h.acknowledgeAdoption(adoption)
        assertTrue(after.none {
            it is NtkFullScenePreparationCommand.AdoptDetachedPreparation
        })
        assertEquals(NtkFullScenePreparationPhase.SURFACE_BOUND, h.snapshot.phase)
        assertEquals(
            NtkPreparationTileState.RESIDENT,
            h.snapshot.tileRecords.values.single().state
        )
        assertEquals(1L, h.snapshot.counters.geometryBindCount)
    }

    @Test
    fun surfaceMayArriveBeforeDetachedPreparationWithoutStartingWindowWork() {
        val h = NtkDetachedPreparationMachineHarness()
        h.publishSurface()
        h.seedGeometry()
        h.publishMetadata(0)

        val open = h.publishBody(0)
            .singleOf<NtkFullScenePreparationCommand.OpenBodyLease>()
        val start = h.openLease(open)
            .singleOf<NtkFullScenePreparationCommand.StartDecode>()
        h.startDecode(start)
        val completed = h.completeDecode(start)
        assertTrue(completed.none {
            it is NtkFullScenePreparationCommand.InstallPrepared
        })
        val release = completed.singleOf<NtkFullScenePreparationCommand.ReleaseBodyLease>()

        val opened = h.openDetached()
        val install = opened.singleOf<NtkFullScenePreparationCommand.InstallPrepared>()
        assertNull(install.surfaceToken)
        val adoption = h.acknowledgeInstall(install).singleOf<
            NtkFullScenePreparationCommand.AdoptDetachedPreparation
        >()
        h.releaseLease(release)
        h.acknowledgeAdoption(adoption)

        assertEquals(NtkFullScenePreparationPhase.SURFACE_BOUND, h.snapshot.phase)
        assertEquals(1L, h.snapshot.counters.geometryBindCount)
    }

    @Test
    fun bodyThatFinishesAfterAdoptionInstallsDirectlyIntoExactSurface() {
        val h = NtkDetachedPreparationMachineHarness(pageCount = 2)
        h.openDetached()
        h.seedGeometry()
        h.publishMetadata(0)
        h.publishMetadata(1)

        val first = decodePage(h, 0)
        h.acknowledgeInstall(first.install)
        h.releaseLease(first.release)
        val adoption = h.publishSurface().singleOf<
            NtkFullScenePreparationCommand.AdoptDetachedPreparation
        >()
        h.acknowledgeAdoption(adoption)

        val second = decodePage(h, 1)
        assertNotNull(second.install.surfaceToken)
        assertEquals(
            h.surface.surfaceEpoch,
            second.install.surfaceToken?.surfaceEpoch
        )
        h.acknowledgeInstall(second.install)
        h.releaseLease(second.release)

        assertTrue(h.snapshot.tileRecords.values.all {
            it.state == NtkPreparationTileState.RESIDENT
        })
    }

    @Test
    fun completeResidentDrainPublishesOneFullSceneSeal() {
        val h = preparedBeforeSurface()
        val adoption = h.publishSurface().singleOf<
            NtkFullScenePreparationCommand.AdoptDetachedPreparation
        >()
        h.acknowledgeAdoption(adoption)

        val close = h.send(NtkFullScenePreparationEvent.SourceDrained(h.sourceProof()))
        assertEquals(
            listOf(NtkFullScenePreparationCommand.ClosePreparationAdmissions),
            close
        )
        assertEquals(NtkFullScenePreparationPhase.QUIESCING, h.snapshot.phase)

        val published = h.finishDrain().singleOf<
            NtkFullScenePreparationCommand.PublishSeal
        >()
        assertEquals(NtkFullScenePreparationPhase.SEALED, h.snapshot.phase)
        assertEquals(h.surface.surfaceEpoch, published.seal.surfaceEpoch)
        assertEquals(1, published.seal.pageCount)
        assertEquals(1, published.seal.tileCount)
        assertTrue(published.seal.resourceCycleLedger.isValid)
    }

    @Test
    fun staleSurfaceOrDetachedTokenFailsClosed() {
        val wrongSurface = NtkDetachedPreparationMachineHarness().also { h ->
            h.openDetached()
            h.send(
                NtkFullScenePreparationEvent.SurfacePublished(
                    h.surface.copy(engineGeneration = h.engineGeneration + 1L)
                )
            )
        }
        assertEquals(NtkFullScenePreparationPhase.FAILED, wrongSurface.snapshot.phase)

        val wrongToken = NtkDetachedPreparationMachineHarness().also { h ->
            h.send(
                NtkFullScenePreparationEvent.DetachedPreparationOpened(
                    h.token.copy(preparationGeneration = h.preparationGeneration + 1L)
                )
            )
        }
        assertEquals(NtkFullScenePreparationPhase.FAILED, wrongToken.snapshot.phase)
    }

    @Test
    fun surfaceLossCannotRetainPreparedAuthority() {
        val h = preparedBeforeSurface()
        h.publishSurface()
        val commands = h.send(NtkFullScenePreparationEvent.SurfaceLost(h.surface))

        assertEquals(NtkFullScenePreparationPhase.FAILED, h.snapshot.phase)
        assertTrue(commands.single() is
            NtkFullScenePreparationCommand.ReleasePreparationAuthority)
    }

    @Test
    fun firstThreeNormalDecodesAreIssuedAsOneCohort() {
        val h = NtkDetachedPreparationMachineHarness(pageCount = 3)
        h.openDetached()
        h.seedGeometry()
        repeat(3) { h.publishMetadata(it) }

        val opens = (0 until 3).map { page ->
            h.publishBody(page)
                .singleOf<NtkFullScenePreparationCommand.OpenBodyLease>()
        }
        assertTrue(h.openLease(opens[0]).none {
            it is NtkFullScenePreparationCommand.StartDecode ||
                it is NtkFullScenePreparationCommand.StartDecodeCohort
        })
        assertTrue(h.openLease(opens[1]).none {
            it is NtkFullScenePreparationCommand.StartDecode ||
                it is NtkFullScenePreparationCommand.StartDecodeCohort
        })
        val cohort = h.openLease(opens[2])
            .singleOf<NtkFullScenePreparationCommand.StartDecodeCohort>()

        assertEquals(3, cohort.decodes.size)
        assertEquals(setOf(0, 1, 2), cohort.decodes.map {
            it.admission.key.pageIndex
        }.toSet())
        assertTrue(h.snapshot.activeDecoderTasks.size == 3)
    }

    private data class PageCompletion(
        val commands: List<NtkFullScenePreparationCommand>,
        val install: NtkFullScenePreparationCommand.InstallPrepared,
        val release: NtkFullScenePreparationCommand.ReleaseBodyLease
    )

    private fun decodePage(
        h: NtkDetachedPreparationMachineHarness,
        pageIndex: Int
    ): PageCompletion {
        val open = h.publishBody(pageIndex)
            .singleOf<NtkFullScenePreparationCommand.OpenBodyLease>()
        val start = h.openLease(open)
            .singleOf<NtkFullScenePreparationCommand.StartDecode>()
        h.startDecode(start)
        val commands = h.completeDecode(start)
        val installs = commands.filterIsInstance<
            NtkFullScenePreparationCommand.InstallPrepared
        >()
        val releases = commands.filterIsInstance<
            NtkFullScenePreparationCommand.ReleaseBodyLease
        >()
        check(installs.size == 1 && releases.size == 1) {
            "decode commands=$commands phase=${h.snapshot.phase} " +
                "tiles=${h.snapshot.tileRecords.values.map { it.state }} " +
                "leases=${h.snapshot.bodyLeaseLedger} token=${h.snapshot.nativePreparationToken} " +
                "upload=${h.snapshot.uploadInFlight}"
        }
        return PageCompletion(
            commands = commands,
            install = installs.single(),
            release = releases.single()
        )
    }

    private fun preparedBeforeSurface(): NtkDetachedPreparationMachineHarness =
        NtkDetachedPreparationMachineHarness().also { h ->
            h.openDetached()
            h.seedGeometry()
            h.publishMetadata(0)
            val completed = decodePage(h, 0)
            h.acknowledgeInstall(completed.install)
            h.releaseLease(completed.release)
            assertFalse(h.snapshot.tileRecords.isEmpty())
        }

    private inline fun <reified T> List<*>.singleOf(): T =
        filterIsInstance<T>().single()
}
