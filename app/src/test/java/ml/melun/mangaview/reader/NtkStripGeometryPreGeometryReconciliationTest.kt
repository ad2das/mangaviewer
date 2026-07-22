package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class NtkStripGeometryPreGeometryReconciliationTest {
    @Test
    fun randomizedPlansReconcileByteForByteIntoFinalGeometry() {
        val random = Random(0x5A17C0DEL)
        repeat(250) { iteration ->
            val pageCount = 1 + random.nextInt(12)
            val assets = List(pageCount) { page ->
                "https://images.example/$iteration/p$page.jpg"
            }
            val seal = NtkEpisodeManifestSeal.create("/webtoon/$iteration/1", 23L, assets)
            val episode = NtkEpisodeToken(iteration + 1L)
            val plans = assets.indices.map { page ->
                val width = 320 + random.nextInt(1_200)
                val height = 1 + random.nextInt(7_500)
                NtkSourceTileLayout.create(
                    episode,
                    metadata(seal, page, width, height),
                    1 + random.nextInt(1_600)
                )
            }
            // A manifest has one immutable tiling policy; normalize the randomized first value.
            val tileHeight = plans.first().tileSourceHeightPx
            val normalized = plans.mapIndexed { page, plan ->
                NtkSourceTileLayout.create(
                    episode,
                    metadata(seal, page, plan.sourceWidth, plan.sourceHeight),
                    tileHeight
                )
            }
            val geometry = NtkStripGeometry.createFromPreGeometryPlans(
                episode,
                1080,
                seal,
                normalized
            )

            assertEquals(NtkSourceTileLayout.rootDigest(normalized), geometry.preGeometryRootDigest)
            assertEquals(normalized.sumOf { it.tiles.size }, geometry.tileCount)
            assertEquals(normalized.sumOf { it.totalRgbaBytes }, geometry.totalRgbaBytes)
            normalized.forEachIndexed { pageIndex, plan ->
                val page = geometry.pages[pageIndex]
                assertEquals(plan.sourceWidth, page.asset.sourceWidth)
                assertEquals(plan.sourceHeight, page.asset.sourceHeight)
                assertEquals(plan.tiles.size, page.tiles.size)
                plan.tiles.zip(page.tiles).forEach { (source, final) ->
                    assertEquals(source.key, final.key)
                    assertEquals(source.sourceTop, final.sourceTop)
                    assertEquals(source.sourceBottom, final.sourceBottom)
                    assertEquals(source.rgbaBytes, geometry.rgbaBytes(final.key))
                }
            }
        }
    }

    @Test
    fun assetOrderAndPlanOrderMutationsFailClosed() {
        val assets = listOf(
            "https://images.example/a.jpg",
            "https://images.example/b.jpg"
        )
        val seal = NtkEpisodeManifestSeal.create("/manhwa/4/5", 7L, assets)
        val episode = NtkEpisodeToken(9L)
        val plans = assets.indices.map { page ->
            NtkSourceTileLayout.create(episode, metadata(seal, page, 720, 1_800 + page))
        }

        assertRejected {
            NtkStripGeometry.createFromPreGeometryPlans(episode, 1080, seal, plans.reversed())
        }
        val swappedManifest = NtkEpisodeManifestSeal.create(
            seal.episodePath,
            seal.revision,
            assets.reversed()
        )
        assertRejected {
            NtkStripGeometry.createFromPreGeometryPlans(
                episode,
                1080,
                swappedManifest,
                plans
            )
        }
    }

    @Test
    fun rootDigestChangesForAnySourceBoundaryMutation() {
        val assets = listOf("https://images.example/a.jpg")
        val seal = NtkEpisodeManifestSeal.create("/manhwa/8/9", 3L, assets)
        val episode = NtkEpisodeToken(11L)
        val first = NtkSourceTileLayout.create(episode, metadata(seal, 0, 764, 2_225), 1_024)
        val second = NtkSourceTileLayout.create(episode, metadata(seal, 0, 764, 2_226), 1_024)

        assertNotEquals(first.planDigest, second.planDigest)
        assertNotEquals(
            NtkSourceTileLayout.rootDigest(listOf(first)),
            NtkSourceTileLayout.rootDigest(listOf(second))
        )
    }

    private fun metadata(
        seal: NtkEpisodeManifestSeal,
        pageIndex: Int,
        width: Int,
        height: Int
    ): NtkSourceMetadata {
        val encodedLength = 200_000L + pageIndex
        return NtkSourceMetadata.createStrict(
            manifestRevision = seal.revision,
            manifestDigest = seal.digestSha256,
            pageIndex = pageIndex,
            canonicalAsset = seal.normalizedCanonicalAssets[pageIndex],
            sourceWidth = width,
            sourceHeight = height,
            authority = NtkSourceMetadataAuthority.createStrict(
                acquisition = NtkMetadataAcquisition.PRIMARY_BODY_TEE,
                responseIdentityDigest = digest("response-$pageIndex-$width-$height"),
                byteWitnessSha256 = digest("witness-$pageIndex-$width-$height"),
                byteWitnessLength = 150L,
                encodedLength = encodedLength,
                strongValidatorDigest = digest("validator-$pageIndex-$width-$height"),
                imageFormat = "jpeg"
            )
        )
    }

    private fun digest(value: String): String = NtkStripDigests.sha256Tokens(value)

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("authority mutation must fail closed", rejected)
    }
}
