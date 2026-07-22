package ml.melun.mangaview.reader

import kotlin.math.max

data class NtkEpisodeToken(val value: Long)

data class NtkPageAsset(
    val pageIndex: Int,
    val canonicalAsset: String,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    init {
        require(pageIndex >= 0)
        require(canonicalAsset.isNotBlank())
        require(sourceWidth > 0 && sourceHeight > 0)
    }
}

data class NtkStripTileKey(
    val episode: NtkEpisodeToken,
    val pageIndex: Int,
    val slotIndex: Int
)

data class NtkStripTileGeometry(
    val key: NtkStripTileKey,
    val sourceTop: Int,
    val sourceBottom: Int,
    val contentTopPx: Long,
    val contentBottomPx: Long
)

data class NtkStripPageGeometry(
    val asset: NtkPageAsset,
    val contentTopPx: Long,
    val contentBottomPx: Long,
    val renderedHeightPx: Int,
    val tiles: List<NtkStripTileGeometry>
)

/** The sole production authority for source-relative tile boundaries. */
internal object NtkSourceTileLayout {
    fun create(
        episode: NtkEpisodeToken,
        metadata: NtkSourceMetadata,
        tileSourceHeightPx: Int = ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX
    ): NtkPreGeometryPagePlan {
        metadata.requireProductionAuthority()
        require(episode.value > 0L)
        require(tileSourceHeightPx > 0)
        val slotCount = Math.addExact(metadata.sourceHeight, tileSourceHeightPx - 1) /
            tileSourceHeightPx
        val tiles = ArrayList<NtkPreGeometryTilePlan>(slotCount)
        for (slot in 0 until slotCount) {
            val sourceTop = Math.multiplyExact(slot, tileSourceHeightPx)
            val sourceBottom = minOf(
                metadata.sourceHeight,
                Math.addExact(sourceTop, tileSourceHeightPx)
            )
            val rgbaBytes = Math.multiplyExact(
                Math.multiplyExact(
                    metadata.sourceWidth.toLong(),
                    (sourceBottom - sourceTop).toLong()
                ),
                4L
            )
            tiles += NtkPreGeometryTilePlan.create(
                key = NtkStripTileKey(episode, metadata.pageIndex, slot),
                sourceTop = sourceTop,
                sourceBottom = sourceBottom,
                rgbaBytes = rgbaBytes
            )
        }
        return NtkPreGeometryPagePlan.create(
            episode = episode,
            metadata = metadata,
            tileSourceHeightPx = tileSourceHeightPx,
            tiles = tiles
        )
    }

    fun rootDigest(plans: List<NtkPreGeometryPagePlan>): String =
        NtkStripDigests.sha256Tokens(buildList {
            add("ntk-pregeometry-root-v1")
            plans.forEach { add(it.planDigest) }
        })
}

/**
 * Immutable, whole-manifest geometry. Pixel residency is deliberately not represented here.
 * Strict callers bind a sealed manifest through [create]; the legacy overload remains only for
 * existing callers while they migrate and still creates a single immutable snapshot.
 */
class NtkStripGeometry private constructor(
    val episode: NtkEpisodeToken,
    val viewportWidthPx: Int,
    val manifestRevision: Long,
    val manifestDigest: String,
    val geometryDigest: String,
    val preGeometryRootDigest: String,
    pages: List<NtkStripPageGeometry>
) {
    val pages: List<NtkStripPageGeometry> = pages.toList()
    private val tilesByOrdinal: List<NtkStripTileGeometry> = this.pages.flatMap { it.tiles }
    private val ordinalByKey: Map<NtkStripTileKey, Int> = buildMap(tilesByOrdinal.size) {
        tilesByOrdinal.forEachIndexed { ordinal, tile ->
            check(put(tile.key, ordinal) == null) { "Duplicate strip tile key ${tile.key}" }
        }
    }
    val contentHeightPx: Long = this.pages.lastOrNull()?.contentBottomPx ?: 0L
    val tileCount: Int = tilesByOrdinal.size
    val largestTileRgbaBytes: Long = this.pages.asSequence()
        .flatMap { page -> page.tiles.asSequence().map { tile -> tileRgbaBytes(page, tile) } }
        .maxOrNull() ?: 0L
    /**
     * Exact logical GL_RGBA8 level-0 storage requested by the scene.
     * This is GPU inventory/proof, not a Java/Dalvik heap charge.
     */
    val totalRgbaBytes: Long = this.pages.asSequence()
        .flatMap { page -> page.tiles.asSequence().map { tile -> tileRgbaBytes(page, tile) } }
        .fold(0L, Math::addExact)
    val gpuSceneFormat: NtkGpuSceneFormat = NtkGpuSceneFormat.RGBA8_UNORM
    val gpuSceneDigest: String = NtkStripDigests.sha256Tokens(buildList {
        add("ntk-gpu-scene-v1")
        add(geometryDigest)
        add(preGeometryRootDigest)
        add(gpuSceneFormat.name)
        add(tileCount.toString())
        tilesByOrdinal.forEach { tile ->
            val page = this@NtkStripGeometry.pages[tile.key.pageIndex]
            add(tile.key.pageIndex.toString())
            add(tile.key.slotIndex.toString())
            add(page.asset.sourceWidth.toString())
            add((tile.sourceBottom - tile.sourceTop).toString())
            add(tile.contentTopPx.toString())
            add(tile.contentBottomPx.toString())
            add(tileRgbaBytes(page, tile).toString())
        }
    })

    init {
        require(manifestRevision >= 0L)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(NtkStripDigests.isSha256(geometryDigest))
        require(NtkStripDigests.isSha256(preGeometryRootDigest))
        require(NtkStripDigests.isSha256(gpuSceneDigest))
    }

    fun tile(key: NtkStripTileKey): NtkStripTileGeometry? =
        pages.getOrNull(key.pageIndex)?.tiles?.getOrNull(key.slotIndex)?.takeIf { it.key == key }

    /** Stable manifest-order dense ordinal used by immutable demand/protection masks. */
    fun tileOrdinal(key: NtkStripTileKey): Int = ordinalByKey[key]
        ?: throw IllegalArgumentException("Tile does not belong to this geometry: $key")

    fun keyAtOrdinal(ordinal: Int): NtkStripTileKey = tileAtOrdinal(ordinal).key

    fun tileAtOrdinal(ordinal: Int): NtkStripTileGeometry = tilesByOrdinal.getOrElse(ordinal) {
        throw IllegalArgumentException("Tile ordinal $ordinal is outside 0 until $tileCount")
    }

    fun emptyTileMask(): NtkTileMask = NtkTileMask.empty(tileCount)

    fun tileMask(keys: Iterable<NtkStripTileKey>): NtkTileMask =
        NtkTileMask.of(tileCount, keys.map(::tileOrdinal))

    /** Dense equivalent of [tilesIntersecting], retaining exact half-open interval semantics. */
    fun tileMaskIntersecting(startPx: Long, endPx: Long): NtkTileMask =
        NtkTileMask.of(tileCount, tilesIntersecting(startPx, endPx).map { tileOrdinal(it.key) })

    fun pageIndices(mask: NtkTileMask): IntArray = pageIndices(mask, IntArray(0))

    /**
     * Projects a tile mask to ordered-distinct pages without replacing reducer priority with
     * manifest sorting. Ordinals named by [preferredTileOrdinals] are consumed first, in their
     * exact order; any masked ordinals omitted by that order are appended in stable mask order.
     */
    fun pageIndices(mask: NtkTileMask, preferredTileOrdinals: IntArray): IntArray {
        require(mask.tileCount == tileCount)
        require(preferredTileOrdinals.all { it in 0 until tileCount })
        val orderedPages = LinkedHashSet<Int>()
        preferredTileOrdinals.forEach { ordinal ->
            if (ordinal in mask) orderedPages += keyAtOrdinal(ordinal).pageIndex
        }
        mask.forEach { ordinal -> orderedPages += keyAtOrdinal(ordinal).pageIndex }
        return orderedPages.toIntArray()
    }

    fun pageAt(contentPx: Long): NtkStripPageGeometry? {
        if (contentPx < 0L || contentPx >= contentHeightPx) return null
        var low = 0
        var high = pages.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val page = pages[middle]
            when {
                contentPx < page.contentTopPx -> high = middle - 1
                contentPx >= page.contentBottomPx -> low = middle + 1
                else -> return page
            }
        }
        return null
    }

    /** Returns every tile intersecting the half-open content interval in manifest order. */
    fun tilesIntersecting(startPx: Long, endPx: Long): List<NtkStripTileGeometry> {
        require(startPx >= 0L)
        require(endPx >= startPx)
        if (startPx == endPx || startPx >= contentHeightPx || pages.isEmpty()) return emptyList()
        val clampedEnd = minOf(endPx, contentHeightPx)
        val result = ArrayList<NtkStripTileGeometry>()
        for (page in pages) {
            if (page.contentBottomPx <= startPx) continue
            if (page.contentTopPx >= clampedEnd) break
            for (tile in page.tiles) {
                if (tile.contentBottomPx <= startPx) continue
                if (tile.contentTopPx >= clampedEnd) break
                result += tile
            }
        }
        return result
    }

    fun rgbaBytes(key: NtkStripTileKey): Long {
        val page = pages.getOrNull(key.pageIndex) ?: return 0L
        val tile = page.tiles.getOrNull(key.slotIndex)?.takeIf { it.key == key } ?: return 0L
        return tileRgbaBytes(page, tile)
    }

    /** Duplicate keys are counted once because residency keys form a set. */
    fun rgbaBytes(keys: Iterable<NtkStripTileKey>): Long {
        val unique = LinkedHashSet<NtkStripTileKey>()
        keys.forEach(unique::add)
        return unique.fold(0L) { total, key -> Math.addExact(total, rgbaBytes(key)) }
    }

    fun rgbaBytes(mask: NtkTileMask): Long {
        require(mask.tileCount == tileCount)
        return mask.fold(0L) { total, ordinal ->
            Math.addExact(total, rgbaBytes(keyAtOrdinal(ordinal)))
        }
    }

    /** Resolves a persisted page-relative position after immutable scaled geometry exists. */
    fun initialViewportTopPx(pageIndex: Int, pageOffsetPx: Int, viewportHeightPx: Int): Long {
        require(pageIndex in pages.indices)
        require(pageOffsetPx >= 0)
        require(viewportHeightPx > 0)
        val requestedTop = Math.addExact(pages[pageIndex].contentTopPx, pageOffsetPx.toLong())
        val maximumScroll = (contentHeightPx - viewportHeightPx.toLong()).coerceAtLeast(0L)
        return requestedTop.coerceIn(0L, maximumScroll)
    }

    private fun tileRgbaBytes(
        page: NtkStripPageGeometry,
        tile: NtkStripTileGeometry
    ): Long = Math.multiplyExact(
        Math.multiplyExact(page.asset.sourceWidth.toLong(), (tile.sourceBottom - tile.sourceTop).toLong()),
        4L
    )

    companion object {
        /** Sole production constructor: final strip positions reconcile immutable source plans. */
        @JvmStatic
        fun createFromPreGeometryPlans(
            episode: NtkEpisodeToken,
            viewportWidthPx: Int,
            manifestSeal: NtkEpisodeManifestSeal,
            plans: List<NtkPreGeometryPagePlan>
        ): NtkStripGeometry {
            require(episode.value > 0L)
            require(viewportWidthPx > 0)
            require(manifestSeal.isStructurallyComplete) { "Manifest must be structurally exact" }
            require(plans.size == manifestSeal.pageCount)
            require(plans.map { it.sourceKey.pageIndex } == plans.indices.toList())
            require(plans.all { plan ->
                plan.episode == episode &&
                    plan.manifestRevision == manifestSeal.revision &&
                    plan.manifestDigest == manifestSeal.digestSha256 &&
                    plan.sourceKey.manifestDigest == manifestSeal.digestSha256 &&
                    plan.sourceKey.canonicalAssetDigest ==
                        NtkStripDigests.canonicalAssetDigestSha256(
                            manifestSeal.normalizedCanonicalAssets[plan.sourceKey.pageIndex]
                        ) &&
                    plan.planDigest == plan.computedPlanDigest
            }) { "Pre-geometry plans do not match sealed manifest authority" }
            require(plans.map { it.tileSourceHeightPx }.distinct().size == 1) {
                "Pre-geometry tile height mutated across the manifest"
            }
            val preGeometryRootDigest = NtkSourceTileLayout.rootDigest(plans)
            var pageTop = 0L
            val pages = plans.map { plan ->
                val canonicalAsset = manifestSeal.normalizedCanonicalAssets[plan.sourceKey.pageIndex]
                val renderedHeightLong = max(
                    1L,
                    (plan.sourceHeight.toLong() * viewportWidthPx + plan.sourceWidth / 2L) /
                        plan.sourceWidth
                )
                require(renderedHeightLong <= Int.MAX_VALUE) {
                    "Rendered page exceeds geometry range"
                }
                val renderedHeight = renderedHeightLong.toInt()
                val pageBottom = Math.addExact(pageTop, renderedHeightLong)
                val tiles = plan.tiles.map { tilePlan ->
                    val contentTop = pageTop +
                        tilePlan.sourceTop.toLong() * renderedHeight / plan.sourceHeight
                    val projectedBottom = if (tilePlan.sourceBottom == plan.sourceHeight) {
                        pageBottom
                    } else {
                        pageTop + tilePlan.sourceBottom.toLong() * renderedHeight /
                            plan.sourceHeight
                    }
                    val contentBottom = max(contentTop + 1L, projectedBottom)
                    require(contentBottom <= pageBottom)
                    NtkStripTileGeometry(
                        key = tilePlan.key,
                        sourceTop = tilePlan.sourceTop,
                        sourceBottom = tilePlan.sourceBottom,
                        contentTopPx = contentTop,
                        contentBottomPx = contentBottom
                    )
                }
                NtkStripPageGeometry(
                    asset = NtkPageAsset(
                        pageIndex = plan.sourceKey.pageIndex,
                        canonicalAsset = canonicalAsset,
                        sourceWidth = plan.sourceWidth,
                        sourceHeight = plan.sourceHeight
                    ),
                    contentTopPx = pageTop,
                    contentBottomPx = pageBottom,
                    renderedHeightPx = renderedHeight,
                    tiles = tiles
                ).also { pageTop = pageBottom }
            }
            val geometryDigest = geometryDigest(
                viewportWidthPx = viewportWidthPx,
                manifestRevision = manifestSeal.revision,
                manifestDigest = manifestSeal.digestSha256,
                preGeometryRootDigest = preGeometryRootDigest,
                pages = pages
            )
            return NtkStripGeometry(
                episode = episode,
                viewportWidthPx = viewportWidthPx,
                manifestRevision = manifestSeal.revision,
                manifestDigest = manifestSeal.digestSha256,
                geometryDigest = geometryDigest,
                preGeometryRootDigest = preGeometryRootDigest,
                pages = pages
            )
        }

        /** Compatibility overload for current pipeline and Java instrumentation callers. */
        @JvmStatic
        @JvmOverloads
        fun create(
            episode: NtkEpisodeToken,
            viewportWidthPx: Int,
            assets: List<NtkPageAsset>,
            tileSourceHeightPx: Int = ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX
        ): NtkStripGeometry {
            val manifestDigest = legacyManifestDigest(assets)
            return build(
                episode = episode,
                viewportWidthPx = viewportWidthPx,
                assets = assets,
                manifestRevision = 0L,
                manifestDigest = manifestDigest,
                tileSourceHeightPx = tileSourceHeightPx
            )
        }

        /** Strict constructor: a complete claimable seal must exactly match ordered metadata. */
        @JvmStatic
        @JvmOverloads
        fun create(
            episode: NtkEpisodeToken,
            viewportWidthPx: Int,
            assets: List<NtkPageAsset>,
            manifestSeal: NtkEpisodeManifestSeal,
            tileSourceHeightPx: Int = ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX
        ): NtkStripGeometry {
            require(manifestSeal.isStructurallyComplete) { "Manifest must be structurally exact" }
            require(assets.size == manifestSeal.pageCount)
            require(assets.map { it.pageIndex } == assets.indices.toList())
            require(
                assets.map { NtkStripDigests.canonicalAsset(it.canonicalAsset) } ==
                    manifestSeal.normalizedCanonicalAssets
            ) { "Geometry metadata does not match sealed manifest order" }
            return build(
                episode = episode,
                viewportWidthPx = viewportWidthPx,
                assets = assets,
                manifestRevision = manifestSeal.revision,
                manifestDigest = manifestSeal.digestSha256,
                tileSourceHeightPx = tileSourceHeightPx
            )
        }

        private fun build(
            episode: NtkEpisodeToken,
            viewportWidthPx: Int,
            assets: List<NtkPageAsset>,
            manifestRevision: Long,
            manifestDigest: String,
            tileSourceHeightPx: Int
        ): NtkStripGeometry {
            require(viewportWidthPx > 0)
            require(tileSourceHeightPx > 0)
            require(assets.isNotEmpty())
            require(assets.map { it.pageIndex } == assets.indices.toList())
            var pageTop = 0L
            val pages = assets.map { asset ->
                val renderedHeightLong = max(
                    1L,
                    (asset.sourceHeight.toLong() * viewportWidthPx + asset.sourceWidth / 2L) /
                        asset.sourceWidth
                )
                require(renderedHeightLong <= Int.MAX_VALUE) { "Rendered page exceeds geometry range" }
                val renderedHeight = renderedHeightLong.toInt()
                val pageBottom = Math.addExact(pageTop, renderedHeightLong)
                val slotCount = (asset.sourceHeight + tileSourceHeightPx - 1) / tileSourceHeightPx
                val tiles = ArrayList<NtkStripTileGeometry>(slotCount)
                for (slot in 0 until slotCount) {
                    val sourceTop = slot * tileSourceHeightPx
                    val sourceBottom = minOf(asset.sourceHeight, sourceTop + tileSourceHeightPx)
                    // Adjacent boundaries use the same integer projection, so they cannot gap.
                    val contentTop = pageTop + sourceTop.toLong() * renderedHeight / asset.sourceHeight
                    val projectedBottom = if (sourceBottom == asset.sourceHeight) pageBottom else
                        pageTop + sourceBottom.toLong() * renderedHeight / asset.sourceHeight
                    val contentBottom = max(contentTop + 1L, projectedBottom)
                    require(contentBottom <= pageBottom)
                    tiles += NtkStripTileGeometry(
                        NtkStripTileKey(episode, asset.pageIndex, slot),
                        sourceTop,
                        sourceBottom,
                        contentTop,
                        contentBottom
                    )
                }
                NtkStripPageGeometry(
                    asset.copy(canonicalAsset = NtkStripDigests.canonicalAsset(asset.canonicalAsset)),
                    pageTop,
                    pageBottom,
                    renderedHeight,
                    tiles.toList()
                ).also { pageTop = pageBottom }
            }
            val geometryDigest = geometryDigest(
                viewportWidthPx,
                manifestRevision,
                manifestDigest,
                legacyPreGeometryRootDigest(assets, pages),
                pages
            )
            val preGeometryRootDigest = legacyPreGeometryRootDigest(assets, pages)
            return NtkStripGeometry(
                episode,
                viewportWidthPx,
                manifestRevision,
                manifestDigest,
                geometryDigest,
                preGeometryRootDigest,
                pages
            )
        }

        private fun legacyManifestDigest(assets: List<NtkPageAsset>): String =
            NtkStripDigests.sha256Tokens(buildList {
                add("ntk-legacy-geometry-manifest-v1")
                add(assets.size.toString())
                assets.forEach { asset ->
                    add(asset.pageIndex.toString())
                    add(NtkStripDigests.canonicalAsset(asset.canonicalAsset))
                }
            })

        private fun geometryDigest(
            viewportWidthPx: Int,
            manifestRevision: Long,
            manifestDigest: String,
            preGeometryRootDigest: String,
            pages: List<NtkStripPageGeometry>
        ): String = NtkStripDigests.sha256Tokens(buildList {
            add("ntk-strip-geometry-v2")
            add(manifestRevision.toString())
            add(manifestDigest)
            add(preGeometryRootDigest)
            add(viewportWidthPx.toString())
            pages.forEach { page ->
                add(page.asset.pageIndex.toString())
                add(page.asset.canonicalAsset)
                add(page.asset.sourceWidth.toString())
                add(page.asset.sourceHeight.toString())
                add(page.contentTopPx.toString())
                add(page.contentBottomPx.toString())
                add(page.renderedHeightPx.toString())
                page.tiles.forEach { tile ->
                    add(tile.key.slotIndex.toString())
                    add(tile.sourceTop.toString())
                    add(tile.sourceBottom.toString())
                    add(tile.contentTopPx.toString())
                    add(tile.contentBottomPx.toString())
                }
            }
        })

        private fun legacyPreGeometryRootDigest(
            assets: List<NtkPageAsset>,
            pages: List<NtkStripPageGeometry>
        ): String = NtkStripDigests.sha256Tokens(buildList {
            add("ntk-legacy-pregeometry-root-v1")
            assets.forEachIndexed { index, asset ->
                add(index.toString())
                add(NtkStripDigests.canonicalAsset(asset.canonicalAsset))
                add(asset.sourceWidth.toString())
                add(asset.sourceHeight.toString())
                pages[index].tiles.forEach { tile ->
                    add(tile.key.slotIndex.toString())
                    add(tile.sourceTop.toString())
                    add(tile.sourceBottom.toString())
                }
            }
        })
    }
}
