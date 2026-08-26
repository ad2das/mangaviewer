package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.os.Looper
import ml.melun.mangaview.mangaview.Manga

enum class DrawableOrigin {
    PREPARED_STORE,
    READER_SESSION
}

/**
 * Identity of a drawable installed into a reader surface.
 *
 * Bitmap identity is deliberately referential. A page decoded twice into equal pixels still owns
 * two different resources and must not be treated as the already-installed producer resource.
 */
class AdoptedDrawableIdentity private constructor(
    internal val kind: Kind,
    internal val pageWidth: Int,
    internal val pageHeight: Int,
    internal val tileGeometry: IntArray,
    internal val resources: Array<Any>
) {
    internal enum class Kind {
        LEGACY_INDEX,
        BITMAP,
        FULL_QUALITY_TILES,
        TOKEN
    }

    internal fun sameAs(other: AdoptedDrawableIdentity): Boolean {
        if (kind != other.kind || pageWidth != other.pageWidth || pageHeight != other.pageHeight) {
            return false
        }
        if (!tileGeometry.contentEquals(other.tileGeometry) || resources.size != other.resources.size) {
            return false
        }
        return resources.indices.all { resources[it] === other.resources[it] }
    }

    companion object {
        internal fun legacyIndex(): AdoptedDrawableIdentity = AdoptedDrawableIdentity(
            Kind.LEGACY_INDEX,
            0,
            0,
            IntArray(0),
            emptyArray()
        )

        fun bitmap(bitmap: Bitmap): AdoptedDrawableIdentity = bitmapResource(
            bitmap,
            bitmap.width,
            bitmap.height
        )

        internal fun bitmapResource(
            resource: Any,
            width: Int,
            height: Int
        ): AdoptedDrawableIdentity = AdoptedDrawableIdentity(
            Kind.BITMAP,
            width,
            height,
            IntArray(0),
            arrayOf(resource)
        )

        fun fullQualityTiles(
            pageWidth: Int,
            pageHeight: Int,
            tiles: List<ReaderTile>
        ): AdoptedDrawableIdentity? {
            if (!isValidFullQualityTilePage(pageWidth, pageHeight, tiles)) return null
            val geometry = IntArray(tiles.size * 4)
            val resources = arrayOfNulls<Any>(tiles.size)
            tiles.forEachIndexed { index, tile ->
                val offset = index * 4
                geometry[offset] = tile.sourceTop
                geometry[offset + 1] = tile.sourceBottom
                geometry[offset + 2] = tile.sourceWidth
                geometry[offset + 3] = tile.sourceHeight
                resources[index] = tile.bitmap
            }
            @Suppress("UNCHECKED_CAST")
            return validatedFullQualityTileResources(
                pageWidth,
                pageHeight,
                geometry,
                resources as Array<Any>
            )
        }

        internal fun validatedFullQualityTileResources(
            pageWidth: Int,
            pageHeight: Int,
            geometry: IntArray,
            resources: Array<Any>
        ): AdoptedDrawableIdentity {
            require(pageWidth > 0 && pageHeight > 0)
            require(geometry.size == resources.size * 4 && resources.isNotEmpty())
            return AdoptedDrawableIdentity(
                Kind.FULL_QUALITY_TILES,
                pageWidth,
                pageHeight,
                geometry,
                resources
            )
        }

        /** Test/integration identity for a resource whose validity was checked by its owner. */
        fun token(token: Any): AdoptedDrawableIdentity = AdoptedDrawableIdentity(
            Kind.TOKEN,
            0,
            0,
            IntArray(0),
            arrayOf(token)
        )

        private fun isValidFullQualityTilePage(
            pageWidth: Int,
            pageHeight: Int,
            tiles: List<ReaderTile>
        ): Boolean {
            if (pageWidth <= 0 || pageHeight <= 0 || tiles.isEmpty()) return false
            var expectedTop = 0
            for (tile in tiles) {
                val bitmap = tile.bitmap
                val span = tile.sourceBottom - tile.sourceTop
                val tail = tile.sourceBottom == pageHeight
                if (tile.sourceWidth != pageWidth || tile.sourceHeight != pageHeight ||
                    tile.sourceTop != expectedTop || span <= 0 || tile.sourceBottom > pageHeight ||
                    (!tail && span != REQUIRED_TILE_SOURCE_HEIGHT) ||
                    (tail && span > REQUIRED_TILE_SOURCE_HEIGHT) ||
                    !bitmap.hasImmutableExactPixelConfig() ||
                    !tile.hasExactSourcePixelStorage()
                ) {
                    return false
                }
                expectedTop = tile.sourceBottom
            }
            return expectedTop == pageHeight
        }

        private const val REQUIRED_TILE_SOURCE_HEIGHT =
            ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX
    }
}

/**
 * Page-indexed ownership of drawables already adopted by a render surface.
 *
 * The default policy is used by the progressive inline reader: the first valid, installed tile
 * page wins and later producers cannot replace it. ReaderV2's older complete-bitmap fast path uses
 * [Policy.LEGACY_PREPARED_BITMAP_MATCH] to preserve its exact replacement behaviour.
 */
class AdoptedDrawableRegistry(
    val policy: Policy = Policy.FIRST_VALID_FULL_QUALITY_TILE,
    private val structurePolicy: StructurePolicy = StructurePolicy.SHIFT_INDEXES
) {
    enum class Policy {
        FIRST_VALID_FULL_QUALITY_TILE,
        LEGACY_PREPARED_BITMAP_MATCH
    }

    enum class StructurePolicy {
        SHIFT_INDEXES,
        INVALIDATE_ALL
    }

    data class Entry(
        val origin: DrawableOrigin,
        val identity: AdoptedDrawableIdentity
    )

    private val entries = LinkedHashMap<Int, Entry>()

    @Synchronized
    fun adopt(
        index: Int,
        origin: DrawableOrigin,
        identity: AdoptedDrawableIdentity
    ): Boolean {
        if (index < 0) return false
        val existing = entries[index]
        if (existing != null) return existing.origin == origin && existing.identity.sameAs(identity)
        entries[index] = Entry(origin, identity)
        return true
    }

    /**
     * Rebinds bookkeeping after the downstream owner has already ACKed this exact resource.
     * This is intentionally stronger than [adopt]: a recycled Surface winner leaves a historical
     * entry behind, and rejecting its replacement would make every repaired Bitmap get recycled
     * by the caller even though the Surface now owns it.
     */
    @Synchronized
    internal fun replaceWithCurrentAuthoritative(
        index: Int,
        origin: DrawableOrigin,
        identity: AdoptedDrawableIdentity
    ): Boolean {
        if (index < 0) return false
        entries[index] = Entry(origin, identity)
        return true
    }

    fun adoptPreparedStoreTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean {
        val identity = AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles) ?: return false
        return adopt(index, DrawableOrigin.PREPARED_STORE, identity)
    }

    internal fun adoptReaderSessionTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean {
        val identity = AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles) ?: return false
        return adopt(index, DrawableOrigin.READER_SESSION, identity)
    }

    fun adoptPreparedStoreBitmap(index: Int, bitmap: Bitmap): Boolean {
        return adopt(index, DrawableOrigin.PREPARED_STORE, AdoptedDrawableIdentity.bitmap(bitmap))
    }

    internal fun markLegacyPreparedStoreIndex(index: Int) {
        if (index < 0) return
        synchronized(this) {
            entries.putIfAbsent(
                index,
                Entry(DrawableOrigin.PREPARED_STORE, AdoptedDrawableIdentity.legacyIndex())
            )
        }
    }

    @Synchronized
    fun entry(index: Int): Entry? = entries[index]

    @Synchronized
    fun origin(index: Int): DrawableOrigin? = entries[index]?.origin

    @Synchronized
    fun contains(index: Int): Boolean = entries.containsKey(index)

    @Synchronized
    fun hasAny(): Boolean = entries.isNotEmpty()

    @Synchronized
    fun matches(index: Int, identity: AdoptedDrawableIdentity): Boolean {
        return entries[index]?.identity?.sameAs(identity) == true
    }

    @Synchronized
    internal fun initialPrerenderResult(
        index: Int,
        candidate: AdoptedDrawableIdentity?,
        continuous: Boolean,
        installed: Boolean
    ): ReaderSession.InitialPrerenderResult {
        val entry = entries[index] ?: return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        val winner = when (policy) {
            Policy.LEGACY_PREPARED_BITMAP_MATCH -> candidate != null && entry.identity.sameAs(candidate)
            Policy.FIRST_VALID_FULL_QUALITY_TILE -> installed &&
                entry.identity.kind == AdoptedDrawableIdentity.Kind.FULL_QUALITY_TILES
        }
        if (!winner) return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        return if (continuous) {
            ReaderSession.InitialPrerenderResult.RENDERED_ONLY
        } else {
            ReaderSession.InitialPrerenderResult.RENDERED_AND_COMMIT
        }
    }

    @Synchronized
    fun remove(index: Int, origin: DrawableOrigin? = null): Boolean {
        val existing = entries[index] ?: return false
        if (origin != null && existing.origin != origin) return false
        entries.remove(index)
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun onPagesPrepended(insertedCount: Int) {
        if (entries.isEmpty()) return
        if (structurePolicy == StructurePolicy.INVALIDATE_ALL) {
            entries.clear()
            return
        }
        if (insertedCount <= 0) return
        val shifted = LinkedHashMap<Int, Entry>(entries.size)
        entries.forEach { (index, entry) -> shifted[index + insertedCount] = entry }
        entries.clear()
        entries.putAll(shifted)
    }

    @Synchronized
    fun onPagesRemoved(startIndex: Int, removedCount: Int) {
        if (entries.isEmpty()) return
        if (structurePolicy == StructurePolicy.INVALIDATE_ALL) {
            entries.clear()
            return
        }
        if (startIndex < 0 || removedCount <= 0) return
        val endExclusive = startIndex.toLong() + removedCount.toLong()
        val shifted = LinkedHashMap<Int, Entry>(entries.size)
        entries.forEach { (index, entry) ->
            when {
                index < startIndex -> shifted[index] = entry
                index.toLong() >= endExclusive -> shifted[index - removedCount] = entry
            }
        }
        entries.clear()
        entries.putAll(shifted)
    }
}

fun interface InstalledDrawableQuery {
    fun isPageDrawableInstalled(index: Int): Boolean
}

/**
 * Common generation and drawable-ownership gate for ReaderSession callbacks.
 */
class ReaderSessionListenerGate(
    private val generation: Int,
    private val isActive: (Int) -> Boolean,
    private val adopted: AdoptedDrawableRegistry,
    private val installed: InstalledDrawableQuery,
    private val downstream: ReaderSession.Listener
) : ReaderSession.Listener {
    private fun active(): Boolean = isActive(generation)

    private fun installedEntry(index: Int): AdoptedDrawableRegistry.Entry? {
        val entry = adopted.entry(index) ?: return null
        // The strict reader may have transferred an immutable original into its generation-bound
        // Surface install queue. That queue owns the exact bitmap identities and is therefore a
        // valid delivery ACK even during the few milliseconds before its batched physical commit.
        // Legacy/prepared paths still require the real Surface query below.
        return entry.takeIf {
            installed.isPageDrawableInstalled(index) ||
                (adopted.policy == AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE &&
                    downstream.isPageAuthoritativeDrawableInstalled(index))
        }
    }

    private fun suppressLegacyIndex(index: Int): Boolean {
        return adopted.policy == AdoptedDrawableRegistry.Policy.LEGACY_PREPARED_BITMAP_MATCH &&
            adopted.contains(index)
    }

    private fun suppressSessionTile(index: Int): Boolean {
        return adopted.policy == AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE &&
            installedEntry(index) != null
    }

    private fun sameAdoptedBitmap(index: Int, bitmap: Bitmap): Boolean {
        return adopted.matches(index, AdoptedDrawableIdentity.bitmap(bitmap))
    }

    override fun onPagesReady(count: Int) {
        if (active()) downstream.onPagesReady(count)
    }

    override fun onPagesAppended(count: Int) {
        if (active()) downstream.onPagesAppended(count)
    }

    override fun onPreparedAdjacentPagesAppended(count: Int) {
        if (active()) downstream.onPreparedAdjacentPagesAppended(count)
    }

    override fun onAdjacentExactP0HeadReady(
        publication: NtkAdjacentExactP0HeadPublication,
    ): Boolean = active() && downstream.onAdjacentExactP0HeadReady(publication)

    override fun onAdjacentExactP0TailReady(
        delta: NtkAdjacentExactP0Delta,
    ): Boolean = active() && downstream.onAdjacentExactP0TailReady(delta)

    override fun onAdjacentExactRunwayBatchReady(
        publication: NtkAdjacentExactRunwayBatchPublication,
    ): Boolean {
        if (!active() ||
            adopted.policy != AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE
        ) return false
        if (!downstream.onAdjacentExactRunwayBatchReady(publication)) return false
        publication.pages.forEach { page ->
            val identity = AdoptedDrawableIdentity.fullQualityTiles(
                page.pageWidth,
                page.pageHeight,
                page.tiles,
            ) ?: return false
            if (!adopted.replaceWithCurrentAuthoritative(
                    page.displayPageIndex,
                    DrawableOrigin.READER_SESSION,
                    identity,
                )
            ) return false
        }
        return publication.pages.all { page ->
            downstream.isPageAuthoritativeDrawableInstalled(
                page.displayPageIndex,
                page.pageWidth,
                page.pageHeight,
                page.tiles,
            )
        }
    }

    override fun onPagesPrepended(count: Int, insertedCount: Int, holdUntilReadyCount: Int) {
        if (!active()) return
        adopted.onPagesPrepended(insertedCount)
        downstream.onPagesPrepended(count, insertedCount, holdUntilReadyCount)
    }

    override fun onPagesRemoved(startIndex: Int, removedCount: Int, totalCount: Int) {
        if (!active()) return
        adopted.onPagesRemoved(startIndex, removedCount)
        downstream.onPagesRemoved(startIndex, removedCount, totalCount)
    }

    override fun onInitialPage(index: Int) {
        if (active() &&
            (adopted.policy == AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE ||
                !adopted.hasAny())
        ) {
            downstream.onInitialPage(index)
        }
    }

    override fun onPageLoading(index: Int) {
        if (active() && !suppressInstalledVisualMutation(index)) downstream.onPageLoading(index)
    }

    override fun onPageBoundsReady(index: Int, width: Int, height: Int) {
        if (active() && !suppressInstalledVisualMutation(index)) {
            downstream.onPageBoundsReady(index, width, height)
        }
    }

    override fun onPageReady(index: Int, bitmap: Bitmap) {
        if (!active() || suppressBitmap(index, bitmap)) return
        downstream.onPageReady(index, bitmap)
    }

    override fun onPageProofReady(index: Int, bitmap: Bitmap) {
        if (!active() || suppressBitmap(index, bitmap)) return
        downstream.onPageProofReady(index, bitmap)
    }

    override fun onPageTilesReady(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        deliverTiles(index, pageWidth, pageHeight, tiles, downstream::onPageTilesReady)
    }

    override fun onPageDecodedRenderReady(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean = active() && downstream.onPageDecodedRenderReady(
        index,
        pageWidth,
        pageHeight,
        tiles
    )

    override fun isStrictAuthoritativeWorkerHandoffActive(): Boolean =
        active() && downstream.isStrictAuthoritativeWorkerHandoffActive()

    override fun onPageAuthoritativeTilesReady(
        index: Int,
        sourceIndex: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        proof: ReaderPreparedStore.PreparedOriginalProof
    ): Boolean {
        if (!active() ||
            adopted.policy != AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE
        ) return false
        val identity = AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles)
            ?: return false
        if (!downstream.onPageAuthoritativeTilesReady(
                index,
                sourceIndex,
                pageWidth,
                pageHeight,
                tiles,
                proof
            )
        ) return false

        // Record ownership only after the downstream Surface has acknowledged the exact resource.
        // This makes the following suppression query and every later delivery refer to the same
        // immutable bitmap identities; a failed install can still be retried normally.
        adopted.replaceWithCurrentAuthoritative(
            index,
            DrawableOrigin.READER_SESSION,
            identity
        )
        return adopted.matches(index, identity) &&
            downstream.isPageAuthoritativeDrawableInstalled(
                index,
                pageWidth,
                pageHeight,
                tiles
            )
    }

    override fun onPageLaunchRunwayTilesReady(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ) {
        deliverTiles(index, pageWidth, pageHeight, tiles, downstream::onPageLaunchRunwayTilesReady)
    }

    override fun onPageProofTilesReady(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ) {
        deliverTiles(index, pageWidth, pageHeight, tiles, downstream::onPageProofTilesReady)
    }

    override fun isPageDrawableInstalled(index: Int): Boolean {
        return active() && (installed.isPageDrawableInstalled(index) ||
            (adopted.policy == AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE &&
                downstream.isPageAuthoritativeDrawableInstalled(index)))
    }

    override fun isExactPreparedStoreTilePageInstalled(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean {
        if (!active()) return false
        val entry = installedEntry(index) ?: return false
        if (entry.origin != DrawableOrigin.PREPARED_STORE) return false
        val identity = AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles)
            ?: return false
        return entry.identity.sameAs(identity)
    }

    override fun isPageAuthoritativeDrawableInstalled(index: Int): Boolean {
        if (!active() || adopted.policy != AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE) {
            return false
        }
        // The registry proves which immutable resource won, but it is historical bookkeeping.
        // A Bitmap can be recycled or a Surface generation can be replaced after that adoption.
        // Strict source suppression must therefore require both the recorded winner and the
        // downstream owner's current-state proof; otherwise an invalid page can never re-decode.
        return installedEntry(index)?.identity?.kind == AdoptedDrawableIdentity.Kind.FULL_QUALITY_TILES &&
            downstream.isPageAuthoritativeDrawableInstalled(index)
    }

    override fun isPageAuthoritativeDrawableInstalled(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean {
        if (!active() || adopted.policy != AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE) {
            return false
        }
        val candidate = AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles)
            ?: return false
        val entry = installedEntry(index) ?: return false
        if (!entry.identity.sameAs(candidate)) return false
        return downstream.isPageAuthoritativeDrawableInstalled(
            index,
            pageWidth,
            pageHeight,
            tiles
        )
    }

    override fun areAllAuthoritativeDrawablesInstalled(pageCount: Int): Boolean {
        if (!active() || pageCount <= 0 ||
            adopted.policy != AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE
        ) return false
        return downstream.areAllAuthoritativeDrawablesInstalled(pageCount)
    }

    override fun currentStrictForwardSuffixProofRevision(
        episodePath: String,
        discoveryGeneration: Long,
        manifestDigest: String,
        pageCount: Int,
        firstSource: Int,
        lastSource: Int,
    ): Long {
        if (!active()) return 0L
        return downstream.currentStrictForwardSuffixProofRevision(
            episodePath,
            discoveryGeneration,
            manifestDigest,
            pageCount,
            firstSource,
            lastSource,
        )
    }

    override fun onStrictRollingHistoricalSceneActivated() {
        if (active()) downstream.onStrictRollingHistoricalSceneActivated()
    }

    override fun onInitialPageDecoded(
        index: Int,
        bitmap: Bitmap
    ): ReaderSession.InitialPrerenderResult {
        if (!active()) return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        val adoptedResult = adoptedInitialResult(index, AdoptedDrawableIdentity.bitmap(bitmap), false)
        if (adoptedResult != ReaderSession.InitialPrerenderResult.NOT_RENDERED) return adoptedResult
        return onMainThreadOrNotRendered { downstream.onInitialPageDecoded(index, bitmap) }
    }

    override fun onInitialPageTilesDecoded(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): ReaderSession.InitialPrerenderResult {
        if (!active()) return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        val candidate = AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles)
        val adoptedResult = adoptedInitialResult(index, candidate, false)
        if (adoptedResult != ReaderSession.InitialPrerenderResult.NOT_RENDERED) return adoptedResult
        val result = onMainThreadOrNotRendered {
            downstream.onInitialPageTilesDecoded(index, pageWidth, pageHeight, tiles)
        }
        adoptRenderedSessionTiles(index, pageWidth, pageHeight, tiles, result)
        return result
    }

    override fun onInitialContinuousPageDecoded(
        index: Int,
        bitmap: Bitmap
    ): ReaderSession.InitialPrerenderResult {
        if (!active()) return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        val adoptedResult = adoptedInitialResult(index, AdoptedDrawableIdentity.bitmap(bitmap), true)
        if (adoptedResult != ReaderSession.InitialPrerenderResult.NOT_RENDERED) return adoptedResult
        return onMainThreadOrNotRendered { downstream.onInitialContinuousPageDecoded(index, bitmap) }
    }

    override fun onInitialContinuousPageTilesDecoded(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): ReaderSession.InitialPrerenderResult {
        if (!active()) return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        val candidate = AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles)
        val adoptedResult = adoptedInitialResult(index, candidate, true)
        if (adoptedResult != ReaderSession.InitialPrerenderResult.NOT_RENDERED) return adoptedResult
        val result = onMainThreadOrNotRendered {
            downstream.onInitialContinuousPageTilesDecoded(index, pageWidth, pageHeight, tiles)
        }
        adoptRenderedSessionTiles(index, pageWidth, pageHeight, tiles, result)
        return result
    }

    override fun onPageCard(index: Int, title: String) {
        if (active()) downstream.onPageCard(index, title)
    }

    override fun onPageError(index: Int, message: String) {
        if (active() && !suppressInstalledVisualMutation(index)) downstream.onPageError(index, message)
    }

    override fun onPageCleared(index: Int) {
        if (!active()) return
        val entry = adopted.entry(index)
        if (entry?.origin == DrawableOrigin.PREPARED_STORE || suppressLegacyIndex(index)) return
        if (entry?.origin == DrawableOrigin.READER_SESSION) {
            // The renderer must release its borrowed Session bitmap/tile references before the
            // origin is forgotten and the producer is allowed to clean up its owned resources.
            downstream.onPageCleared(index)
            adopted.remove(index, DrawableOrigin.READER_SESSION)
            return
        }
        downstream.onPageCleared(index)
    }

    override fun onPageRollingEvicted(index: Int) {
        if (!active()) return
        val entry = adopted.entry(index)
        if (entry?.origin == DrawableOrigin.PREPARED_STORE || suppressLegacyIndex(index)) return
        // Rolling eviction intentionally preserves the downstream historical all-ready ledger,
        // but it must retire the renderer's current bitmap identities and forget this adoption so
        // the same canonical page can be decoded and adopted again when it reaches the runway.
        downstream.onPageRollingEvicted(index)
        if (entry?.origin == DrawableOrigin.READER_SESSION) {
            adopted.remove(index, DrawableOrigin.READER_SESSION)
        }
    }

    override fun onPageHostPressureRollingEvicted(index: Int) {
        if (!active()) return
        val entry = adopted.entry(index)
        if (entry?.origin == DrawableOrigin.PREPARED_STORE || suppressLegacyIndex(index)) return
        downstream.onPageHostPressureRollingEvicted(index)
        if (entry?.origin == DrawableOrigin.READER_SESSION) {
            adopted.remove(index, DrawableOrigin.READER_SESSION)
        }
    }

    override fun onSessionOwnedBitmapRetirement(bitmaps: List<Bitmap>): Boolean {
        // This is a View-lifetime cleanup sink, not a visual mutation. A stale Session can still
        // have GlobalRefs in a renderer being joined, so generation gating must never turn this
        // callback into an unsafe direct-recycle fallback.
        return downstream.onSessionOwnedBitmapRetirement(bitmaps)
    }

    override fun onMessage(message: String) {
        if (active()) downstream.onMessage(message)
    }

    override fun onCaptchaRequired(manga: Manga) {
        if (active()) downstream.onCaptchaRequired(manga)
    }

    override fun onAdjacentExactManifestRequired(
        manga: Manga,
        predecessorEpisodePath: String,
    ) {
        if (active()) {
            downstream.onAdjacentExactManifestRequired(manga, predecessorEpisodePath)
        }
    }

    override fun onForwardAdjacentPathResolved(
        predecessorEpisodePath: String,
        episodePath: String,
        claimRevision: Long,
    ) {
        if (active()) {
            downstream.onForwardAdjacentPathResolved(
                predecessorEpisodePath,
                episodePath,
                claimRevision,
            )
        }
    }

    override fun onBoundaryAppendFinished(
        anchor: Int,
        direction: Int,
        silent: Boolean,
        suppressedCaptcha: Boolean
    ) {
        if (active()) downstream.onBoundaryAppendFinished(anchor, direction, silent, suppressedCaptcha)
    }

    private fun suppressInstalledVisualMutation(index: Int): Boolean {
        return suppressLegacyIndex(index) ||
            (adopted.policy == AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE &&
                installedEntry(index) != null)
    }

    private fun suppressBitmap(index: Int, bitmap: Bitmap): Boolean {
        if (sameAdoptedBitmap(index, bitmap)) return true
        return adopted.policy == AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE &&
            installedEntry(index) != null
    }

    private fun adoptedInitialResult(
        index: Int,
        candidate: AdoptedDrawableIdentity?,
        continuous: Boolean
    ): ReaderSession.InitialPrerenderResult {
        val isInstalled = if (adopted.policy == AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE) {
            installed.isPageDrawableInstalled(index)
        } else {
            false
        }
        return adopted.initialPrerenderResult(index, candidate, continuous, isInstalled)
    }

    private inline fun onMainThreadOrNotRendered(
        callback: () -> ReaderSession.InitialPrerenderResult
    ): ReaderSession.InitialPrerenderResult {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            callback()
        } else {
            ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
    }

    private fun deliverTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        callback: (Int, Int, Int, List<ReaderTile>) -> Unit
    ) {
        if (!active() || suppressSessionTile(index)) return
        callback(index, pageWidth, pageHeight, tiles)
        if (adopted.policy == AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE &&
            installed.isPageDrawableInstalled(index)
        ) {
            adopted.adoptReaderSessionTiles(index, pageWidth, pageHeight, tiles)
        }
    }

    private fun adoptRenderedSessionTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        result: ReaderSession.InitialPrerenderResult
    ) {
        if (adopted.policy != AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE ||
            result == ReaderSession.InitialPrerenderResult.NOT_RENDERED ||
            !installed.isPageDrawableInstalled(index)
        ) {
            return
        }
        adopted.adoptReaderSessionTiles(index, pageWidth, pageHeight, tiles)
    }
}
