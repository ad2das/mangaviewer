package ml.melun.mangaview.reader

import java.util.Collections
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictPreparationProtocolTest {
    @Test
    fun preparationGenerationContinuesPastRetiredReducerInstance() {
        val reducer = NtkStrictPreparationProtocol(initialPreparationGeneration = 41L)
        val fixture = fixture()
        reducer.onPlanReserved(fixture.plan, fixture.demand)
        val transition = reducer.onManifestPromoted(fixture.manifest)
        assertEquals(42L, transition.preparationGeneration)
        assertTrue(transition.startPreparation)
    }

    @Test
    fun promotedManifestStartsPreparationWithoutSurface() {
        val reducer = NtkStrictPreparationProtocol()
        val fixture = fixture()

        assertFalse(
            reducer.onPlanReserved(fixture.plan, fixture.demand).startPreparation
        )
        val transition = reducer.onManifestPromoted(fixture.manifest)

        assertTrue(transition.startPreparation)
        assertFalse(transition.adoptDetachedPreparationToSurface)
        assertEquals(1L, transition.preparationGeneration)
        assertEquals(null, reducer.snapshot().surface)
    }

    @Test
    fun allArrivalOrdersConvergeToOneExactJoin() {
        repeat(10_000) { seed ->
            val fixture = fixture()
            val reducer = NtkStrictPreparationProtocol()
            reducer.onPlanReserved(fixture.plan, fixture.demand)
            val actions = mutableListOf<(NtkStrictPreparationProtocol) -> Unit>(
                { it.onManifestPromoted(fixture.manifest) },
                { it.onDetachedEngineReady(fixture.engine) }
            )
            Collections.shuffle(actions, Random(seed.toLong()))
            actions.forEach { it(reducer) }
            val generation = reducer.snapshot().preparationGeneration
            assertEquals(1L, generation)
            val opened = fixture.opened.copy(preparationGeneration = generation)
            val geometry = fixture.geometry.copy(preparationGeneration = generation)
            val inventory = fixture.inventory.copy(preparation = opened)
            val tail = mutableListOf<(NtkStrictPreparationProtocol) -> Unit>(
                { it.onDetachedPreparationOpened(opened) },
                { it.onGeometrySeedAvailable(geometry) },
                { it.onSurfacePublished(fixture.surface) },
                { it.onPreparedInventory(inventory) }
            )
            Collections.shuffle(tail, Random(seed.toLong() xor -7046029254386353131L))
            var adoptionCount = 0
            var join: NtkStrictSurfacePreparationJoinIdentity? = null
            tail.forEach { action ->
                val before = reducer.snapshot().adoptionDispatched
                action(reducer)
                val after = reducer.snapshot().adoptionDispatched
                if (!before && after) {
                    adoptionCount++
                    join = checkNotNull(exactJoin(reducer))
                }
            }

            assertEquals(1, adoptionCount)
            assertNotNull(join)
            assertFalse(reducer.snapshot().failed)
            reducer.onSurfacePreparationBound(checkNotNull(join))
            assertEquals(join, reducer.snapshot().joined)
            assertFalse(
                reducer.onPreparedInventory(inventory).adoptDetachedPreparationToSurface
            )
        }
    }

    @Test
    fun staleEngineDemandManifestOrSurfaceFailsClosed() {
        val cases = listOf<(Fixture) -> Unit>(
            { fixture ->
                val reducer = primed(fixture)
                assertTrue(
                    reducer.onDetachedEngineReady(
                        fixture.engine.copy(engineGeneration = 99L)
                    ).failed
                )
            },
            { fixture ->
                val reducer = NtkStrictPreparationProtocol()
                assertTrue(
                    reducer.onPlanReserved(
                        fixture.plan,
                        fixture.demand.copy(demandGeneration = 99L)
                    ).let {
                        reducer.onDetachedEngineReady(fixture.engine).failed
                    }
                )
            },
            { fixture ->
                val reducer = NtkStrictPreparationProtocol()
                reducer.onPlanReserved(fixture.plan, fixture.demand)
                val wrongPlan = fixture.plan.copy(controllerGeneration = 2)
                val wrongManifest = fixture.manifest.copy(plan = wrongPlan)
                assertTrue(reducer.onManifestPromoted(wrongManifest).failed)
            },
            { fixture ->
                val reducer = primed(fixture)
                val wrong = fixture.surface.copy(
                    surfaceEpoch = fixture.surface.surfaceEpoch + 1L
                )
                reducer.onSurfacePublished(fixture.surface)
                assertTrue(reducer.onSurfacePublished(wrong).failed)
            }
        )
        cases.forEach { test ->
            val fixture = fixture()
            test(fixture)
        }
    }

    private fun primed(fixture: Fixture): NtkStrictPreparationProtocol =
        NtkStrictPreparationProtocol().also {
            it.onPlanReserved(fixture.plan, fixture.demand)
            it.onManifestPromoted(fixture.manifest)
            it.onDetachedEngineReady(fixture.engine)
        }

    private fun exactJoin(
        reducer: NtkStrictPreparationProtocol
    ): NtkStrictSurfacePreparationJoinIdentity? {
        val snapshot = reducer.snapshot()
        val preparation = snapshot.detachedPreparation ?: return null
        val inventory = snapshot.inventory ?: return null
        val surface = snapshot.surface ?: return null
        val geometry = snapshot.geometry ?: return null
        return NtkStrictSurfacePreparationJoinIdentity(
            preparation = preparation,
            inventoryDigest = inventory.preparedInventoryDigest,
            preparedTileCount = inventory.preparedTileCount,
            surface = surface,
            geometry = geometry
        )
    }

    private data class Fixture(
        val plan: NtkStrictPlanIdentity,
        val demand: NtkStrictDemandIdentity,
        val manifest: NtkStrictManifestIdentity,
        val engine: NtkStrictDetachedEngineIdentity,
        val opened: NtkStrictDetachedPreparationIdentity,
        val inventory: NtkStrictPreparedInventoryIdentity,
        val geometry: NtkStrictGeometrySeedIdentity,
        val surface: NtkStrictPublishedSurfaceIdentity
    )

    private fun fixture(): Fixture {
        val path = "/manhwa/33727/1692251"
        val asset = "https://example.test/image/1.jpg"
        val plan = NtkStrictPlanIdentity(
            normalizedEpisodePath = path,
            controllerGeneration = 1,
            discoveryGeneration = 7L,
            documentPlanDigest = digest("plan"),
            imageRequestIdentityDigest = digest("request"),
            responseBoundProofDigest = digest("response"),
            pageCount = 1
        )
        val demand = NtkStrictDemandIdentity(3L, 1, plan.documentPlanDigest)
        val manifest = NtkStrictManifestIdentity(
            plan = plan,
            manifestRevision = 2L,
            manifestDigest = NtkEpisodeManifestSeal.computeDigestSha256(
                path,
                1,
                listOf(asset)
            ),
            responseBoundProofDigest = plan.responseBoundProofDigest,
            orderedCanonicalAssets = listOf(asset)
        )
        val engine = NtkStrictDetachedEngineIdentity(11L, demand)
        val opened = NtkStrictDetachedPreparationIdentity(11L, 1L, manifest, 17L)
        val inventory = NtkStrictPreparedInventoryIdentity(opened, digest("inventory"), 1)
        val geometry = NtkStrictGeometrySeedIdentity(
            preparationGeneration = 1L,
            geometryRevision = 5L,
            viewportWidth = 1080,
            geometryDigest = digest("geometry"),
            geometryTileCount = 1
        )
        val surface = NtkStrictPublishedSurfaceIdentity(
            engineGeneration = 11L,
            demand = demand,
            attachGeneration = 13L,
            surfaceEpoch = 19L,
            geometryRevision = 5L,
            width = 1080,
            height = 2340
        )
        return Fixture(
            plan,
            demand,
            manifest,
            engine,
            opened,
            inventory,
            geometry,
            surface
        )
    }

    private fun digest(token: String): String =
        NtkStripDigests.sha256Tokens("test", token)
}
