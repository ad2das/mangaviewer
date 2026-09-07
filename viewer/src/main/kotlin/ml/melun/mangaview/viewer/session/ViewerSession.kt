package ml.melun.mangaview.viewer.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.LayoutLedger
import ml.melun.mangaview.viewer.ScrollMutationCause
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.saturatingMultiplyNonNegative

class ViewerSession(
    viewport: Viewport,
    generation: Long = 1L,
    private val demandEngine: DemandEngine = DemandEngine(),
    private val sceneBuilder: SceneBuilder = SceneBuilder(),
) {
    private val ownerThread = Thread.currentThread()
    private var mutableState = ViewerSessionState(
        generation = generation,
        lifecycleEpoch = 1L,
        viewport = viewport,
        timeline = EpisodeTimeline.EMPTY,
        layout = null,
        runway = runway(viewport, true),
        scroll = CanonicalScroll(),
        opening = OpeningBasis(),
        visuals = emptyMap(),
        geometryRevision = 0L,
        sceneRevision = 0L,
        viewportRevision = 0L,
        foreground = true,
        surfaceAttached = false,
        scrollRevision = 0L,
        scrollCause = ScrollMutationCause.EPISODE_NAVIGATION,
        userInputRevision = 0L,
    )

    val state: ViewerSessionState
        get() {
            requireOwner()
            return mutableState
        }

    fun savedPositionResolved(position: ReadingPosition?): SessionChange = update { prior ->
        check(!prior.opening.savedPositionResolved) { "Saved position resolved twice" }
        resolveOpening(
            prior.copy(
                opening = prior.opening.copy(
                    savedPositionResolved = true,
                    savedPosition = position,
                ),
            ),
        )
    }

    fun initialManifestResolved(manifest: EpisodeManifest): SessionChange = update { prior ->
        check(prior.timeline.isEmpty) { "Initial manifest resolved twice" }
        val timeline = EpisodeTimeline.start(manifest)
        val withGeometry = prior.copy(
            timeline = timeline,
            layout = LayoutLedger.create(timeline.pages, prior.viewport.width),
            runway = runway(prior.viewport, manifest.nextEpisodeId != null),
            geometryRevision = prior.geometryRevision + 1L,
            sceneRevision = prior.sceneRevision + 1L,
            viewportRevision = prior.viewportRevision + 1L,
        )
        resolveOpening(withGeometry)
    }

    fun applyUserInput(
        delta: FixedPx,
        velocityPixelsPerSecond: Float,
    ): SessionChange = update { prior ->
        val available = extendPendingGeometry(prior, delta)
        val nextScroll = ScrollModel.applyInput(
            available.scroll,
            delta,
            velocityPixelsPerSecond,
            available.maximumScroll,
        )
        available.copy(
            scroll = nextScroll,
            opening = if (prior.opening.applied) prior.opening else prior.opening.copy(
                accumulatedInput = prior.opening.accumulatedInput + delta,
                inputFloor = FixedPx((prior.opening.inputFloor + delta).units.coerceAtLeast(0L)),
            ),
            viewportRevision = prior.viewportRevision + 1L,
            scrollRevision = prior.scrollRevision + 1L,
            scrollCause = ScrollMutationCause.USER_INPUT,
            userInputRevision = prior.userInputRevision + 1L,
        )
    }

    fun viewportChanged(viewport: Viewport): SessionChange = update { prior ->
        if (prior.viewport == viewport) return@update prior
        val oldLayout = prior.layout
        if (oldLayout == null) {
            return@update prior.copy(
                viewport = viewport,
                runway = runway(viewport, prior.runway.nextEpisodeExpected),
                viewportRevision = prior.viewportRevision + 1L,
            )
        }
        val oldAnchor = ScrollModel.anchor(prior.scroll, prior.viewport, oldLayout)
        val layout = oldLayout.reflow(viewport.width)
        val provisional = prior.copy(
            viewport = viewport,
            layout = layout,
            runway = runway(viewport, prior.runway.nextEpisodeExpected),
            geometryRevision = prior.geometryRevision + 1L,
            sceneRevision = prior.sceneRevision + 1L,
            viewportRevision = prior.viewportRevision + 1L,
        )
        val restored = oldAnchor?.let { anchor ->
                ScrollModel.restore(
                    anchor.centeredIn(viewport),
                    layout,
                    provisional.maximumScroll,
                    prior.scroll.velocityPixelsPerSecond,
                )
            } ?: prior.scroll
        provisional.copy(
            scroll = restored,
            scrollRevision = prior.scrollRevision + if (restored == prior.scroll) 0L else 1L,
            scrollCause = if (restored == prior.scroll) prior.scrollCause else {
                ScrollMutationCause.GEOMETRY_CORRECTION
            },
        )
    }

    fun resolvePageDimensions(
        pageId: PageId,
        dimensions: PageDimensions,
    ): SessionChange = geometryTransaction { layout -> layout.resolve(pageId, dimensions) }

    fun appendEpisode(manifest: EpisodeManifest): SessionChange = update { prior ->
        val oldLayout = requireNotNull(prior.layout) { "Initial manifest is not ready" }
        val timeline = prior.timeline.append(manifest)
        val layout = oldLayout.append(manifest.pages)
        prior.copy(
            timeline = timeline,
            layout = layout,
            runway = runway(prior.viewport, manifest.nextEpisodeId != null),
            geometryRevision = prior.geometryRevision + 1L,
            sceneRevision = prior.sceneRevision + 1L,
            viewportRevision = prior.viewportRevision + 1L,
        )
    }

    fun navigateEpisode(episodeId: EpisodeId): SessionChange = update { prior ->
        val episode = prior.timeline.episodes.getOrNull(
            prior.timeline.episodeIndex(episodeId) ?: -1,
        ) ?: return@update prior
        val pageId = episode.manifest.pages.first().id
        prior.copy(
            scroll = ScrollModel.navigate(
                pageId,
                FixedPx.ZERO,
                FixedPx.ZERO,
                requireNotNull(prior.layout),
                prior.maximumScroll,
            ),
            viewportRevision = prior.viewportRevision + 1L,
            scrollRevision = prior.scrollRevision + 1L,
            scrollCause = ScrollMutationCause.EPISODE_NAVIGATION,
        )
    }

    fun visualReady(pageId: PageId, band: VisualBand): SessionChange = update { prior ->
        if (prior.layout?.contains(pageId) != true) return@update prior
        val existing = prior.visuals[pageId].orEmpty()
        val withoutOverlap = existing.filter {
            it.sourceBottomPx <= band.sourceTopPx || it.sourceTopPx >= band.sourceBottomPx
        }
        prior.copy(
            visuals = prior.visuals + (pageId to (withoutOverlap + band).sortedBy(VisualBand::sourceTopPx)),
            sceneRevision = prior.sceneRevision + 1L,
            viewportRevision = prior.viewportRevision + 1L,
        )
    }

    fun visualEvicted(pageId: PageId, key: VisualKey): SessionChange = update { prior ->
        val existing = prior.visuals[pageId] ?: return@update prior
        val retained = existing.filterNot { it.key == key }
        if (retained.size == existing.size) return@update prior
        prior.copy(
            visuals = if (retained.isEmpty()) prior.visuals - pageId else {
                prior.visuals + (pageId to retained)
            },
            sceneRevision = prior.sceneRevision + 1L,
            viewportRevision = prior.viewportRevision + 1L,
        )
    }

    fun visualsInvalidated(): SessionChange = update { prior ->
        if (prior.visuals.isEmpty()) prior else prior.copy(
            visuals = emptyMap(),
            sceneRevision = prior.sceneRevision + 1L,
            viewportRevision = prior.viewportRevision + 1L,
        )
    }

    fun surfaceAttached(): SessionChange = update { prior ->
        if (prior.surfaceAttached) prior else prior.copy(
            surfaceAttached = true,
            lifecycleEpoch = prior.lifecycleEpoch + 1L,
            sceneRevision = prior.sceneRevision + 1L,
            viewportRevision = prior.viewportRevision + 1L,
        )
    }

    fun surfaceDetached(): SessionChange = update { prior ->
        if (!prior.surfaceAttached) prior else prior.copy(
            surfaceAttached = false,
            lifecycleEpoch = prior.lifecycleEpoch + 1L,
        )
    }

    fun enterBackground(): SessionChange = update { prior ->
        if (!prior.foreground) prior else prior.copy(
            foreground = false,
            scroll = prior.scroll.copy(velocityPixelsPerSecond = 0F),
            lifecycleEpoch = prior.lifecycleEpoch + 1L,
        )
    }

    fun enterForeground(): SessionChange = update { prior ->
        if (prior.foreground) prior else prior.copy(
            foreground = true,
            lifecycleEpoch = prior.lifecycleEpoch + 1L,
            sceneRevision = prior.sceneRevision + 1L,
            viewportRevision = prior.viewportRevision + 1L,
        )
    }

    fun positionForPersistence(): ReadingPosition? {
        requireOwner()
        val layout = mutableState.layout ?: return null
        val pageId = layout.pageAt(mutableState.scroll.contentOffset) ?: return null
        val top = layout.topOf(pageId) ?: return null
        return ReadingPosition(
            pageId,
            (mutableState.scroll.contentOffset.units - top.units).coerceAtLeast(0L),
        )
    }

    private fun geometryTransaction(transform: (LayoutLedger) -> LayoutLedger): SessionChange =
        update { prior ->
            val oldLayout = prior.layout ?: return@update prior
            val anchor = ScrollModel.anchor(prior.scroll, prior.viewport, oldLayout)
            val layout = transform(oldLayout)
            if (layout === oldLayout) return@update prior
            val provisional = prior.copy(
                layout = layout,
                geometryRevision = prior.geometryRevision + 1L,
                sceneRevision = prior.sceneRevision + 1L,
                viewportRevision = prior.viewportRevision + 1L,
            )
            val restored = anchor?.let {
                    ScrollModel.restore(
                        it,
                        layout,
                        provisional.maximumScroll,
                        prior.scroll.velocityPixelsPerSecond,
                    )
                } ?: prior.scroll
            provisional.copy(
                scroll = restored,
                scrollRevision = prior.scrollRevision + if (restored == prior.scroll) 0L else 1L,
                scrollCause = if (restored == prior.scroll) prior.scrollCause else {
                    ScrollMutationCause.GEOMETRY_CORRECTION
                },
            )
        }

    private fun resolveOpening(state: ViewerSessionState): ViewerSessionState {
        val opening = state.opening
        val initialLayout = state.layout
        if (opening.applied || !opening.savedPositionResolved || initialLayout == null) return state
        val saved = opening.savedPosition
        val layout = saved?.let { initialLayout.reserveUnknownHeight(it.pageId,
            FixedPx(it.offsetInPageUnits) + state.viewport.height +
                FixedPx(opening.accumulatedInput.units.coerceAtLeast(0L))) } ?: initialLayout
        val available = state.copy(layout = layout)
        val base = if (saved != null && layout.contains(saved.pageId)) {
            ScrollModel.navigate(
                saved.pageId,
                FixedPx(saved.offsetInPageUnits),
                FixedPx(saved.viewportOffsetUnits),
                layout,
                available.maximumScroll,
            )
        } else {
            CanonicalScroll()
        }
        val restored = ScrollModel.applyInput(
                base,
                maxOf(base.contentOffset + opening.accumulatedInput, opening.inputFloor) - base.contentOffset,
                state.scroll.velocityPixelsPerSecond,
                available.maximumScroll,
            )
        return available.copy(
            scroll = restored,
            opening = opening.copy(applied = true),
            geometryRevision = state.geometryRevision + if (layout === initialLayout) 0L else 1L,
            sceneRevision = state.sceneRevision + 1L,
            viewportRevision = state.viewportRevision + 1L,
            scrollRevision = state.scrollRevision + if (restored == state.scroll) 0L else 1L,
            scrollCause = if (opening.accumulatedInput != FixedPx.ZERO) {
                ScrollMutationCause.USER_INPUT
            } else {
                ScrollMutationCause.EPISODE_NAVIGATION
            },
        )
    }

    private fun extendPendingGeometry(state: ViewerSessionState, delta: FixedPx): ViewerSessionState {
        val end = maxOf(FixedPx.ZERO, state.scroll.contentOffset + delta) + state.viewport.height
        val savedId = state.opening.savedPosition?.pageId
        val layout = state.layout
        val expanded = if (savedId != null && layout != null && layout.pageAt(state.scroll.contentOffset) == savedId) {
            layout.reserveUnknownHeight(savedId, end - requireNotNull(layout.topOf(savedId)))
        } else layout
        val realHeight = expanded?.totalHeight ?: FixedPx.ZERO
        val runway = if (state.runway.nextEpisodeExpected && end > realHeight + state.runway.height) {
            state.runway.copy(height = end - realHeight + state.viewport.height)
        } else state.runway
        return if (expanded === layout && runway == state.runway) state else state.copy(
            layout = expanded,
            runway = runway,
            geometryRevision = state.geometryRevision + 1L,
            sceneRevision = state.sceneRevision + 1L,
        )
    }

    private fun update(transform: (ViewerSessionState) -> ViewerSessionState): SessionChange {
        requireOwner()
        val prior = mutableState
        val next = transform(prior)
        mutableState = next
        if (next === prior || next == prior) return SessionChange(next, emptyList())
        val effects = buildList {
            add(SessionEffect.DemandChanged(demandEngine.snapshot(next)))
            if (next.foreground && next.surfaceAttached) {
                add(SessionEffect.SceneChanged(sceneBuilder.build(next)))
            }
        }
        return SessionChange(next, effects)
    }

    private fun requireOwner() {
        check(Thread.currentThread() === ownerThread) { "ViewerSession accessed from another thread" }
    }

    private companion object {
        fun GeometryAnchor.centeredIn(viewport: Viewport): GeometryAnchor {
            val center = FixedPx(viewport.height.units / 2L)
            return when (this) {
                is SemanticViewportAnchor -> copy(viewportOffset = center)
                is RunwayViewportAnchor -> copy(viewportOffset = center)
            }
        }

        fun runway(viewport: Viewport, nextExpected: Boolean): TerminalRunway = TerminalRunway(
            height = if (nextExpected) {
                FixedPx(saturatingMultiplyNonNegative(viewport.height.units, 2))
            } else {
                FixedPx.ZERO
            },
            nextEpisodeExpected = nextExpected,
        )
    }
}
