package ml.melun.mangaview.viewer

import kotlinx.collections.immutable.persistentMapOf
import ml.melun.mangaview.core.PageId

class ViewerReducer(
    private val scrollController: ScrollController,
    private val workScheduler: WorkScheduler,
    private val pixelBudgetPlanner: PixelBudgetPlanner = PixelBudgetPlanner(),
    private val episodeAppendScheduler: EpisodeAppendScheduler = EpisodeAppendScheduler(
        scrollController = scrollController,
    ),
) {
    private val episodeAppendReducer = EpisodeAppendReducer(scrollController)
    private val pageEvents = ViewerPageEventReducer(scrollController)
    private val scrollWorkWindow = PixelWindowPolicy()

    fun reduce(current: ViewerState?, event: ViewerEvent): Reduction? {
        require(event.atNanos >= 0L) { "Event time must not be negative" }
        if (event is ViewerEvent.OpenEpisode) return open(current, event)
        val existing = current ?: return null
        val eventTime = maxOf(existing.lastEventNanos, event.atNanos)
        if (event is ViewerEvent.UserScroll) {
            val retryBecameEligible = existing.nextRetryDeadlineNanos?.let { it <= eventTime } == true
            val scrolled = userScroll(existing, event, eventTime)
            val base = retargetInitialFetch(scrolled)
            return if (base.commands.isNotEmpty() ||
                requiresScrollMaintenance(existing, base.state, retryBecameEligible)
            ) {
                finish(base)
            } else {
                base
            }
        }
        val timed = advanceRetryClock(existing, eventTime)
        val eventScoped = timed.copy(
            frameTimelineVsyncId = INVALID_FRAME_TIMELINE_VSYNC_ID,
            expectedPresentationTimeNanos = 0L,
        )
        val base = reduceExisting(eventScoped, event)
        return finish(base)
    }

    fun prepareEpisode(manifest: ml.melun.mangaview.core.EpisodeManifest, viewportWidth: FixedPx):
        PreparedViewerEpisode = prepareViewerEpisode(manifest, viewportWidth)

    private fun requiresScrollMaintenance(
        before: ViewerState,
        after: ViewerState,
        retryBecameEligible: Boolean,
    ): Boolean {
        if (retryBecameEligible) return true
        if ((before.velocityUnitsPerSecond < 0L) != (after.velocityUnitsPerSecond < 0L)) return true
        val previous = scrollWorkWindow.window(before)
        val current = scrollWorkWindow.window(after)
        return boundaryPage(before, previous.visibleStartUnits) != boundaryPage(after, current.visibleStartUnits) ||
            boundaryPage(before, previous.visibleEndUnits - 1L) !=
            boundaryPage(after, current.visibleEndUnits - 1L) ||
            boundaryPage(before, previous.retainedStartUnits) !=
            boundaryPage(after, current.retainedStartUnits) ||
            boundaryPage(before, previous.retainedEndUnits - 1L) !=
            boundaryPage(after, current.retainedEndUnits - 1L)
    }

    private fun boundaryPage(state: ViewerState, offsetUnits: Long): PageId {
        val maximum = (state.layout.totalHeight.units - 1L).coerceAtLeast(0L)
        return requireNotNull(state.layout.pageAt(FixedPx(offsetUnits.coerceIn(0L, maximum))))
    }
    private fun open(current: ViewerState?, event: ViewerEvent.OpenEpisode): Reduction {
        val prepared = event.preparedEpisode?.also {
            require(it.manifest === event.manifest) { "Prepared episode does not match its manifest" }
        } ?: prepareViewerEpisode(event.manifest, event.viewport.width)
        val layout = if (prepared.layout.viewportWidth == event.viewport.width) {
            prepared.layout
        } else {
            prepared.layout.reflow(event.viewport.width)
        }
        val scroll = event.initialPosition
            ?.takeIf { layout.contains(it.pageId) }
            ?.let { position ->
                scrollController.navigate(
                    layout,
                    event.viewport,
                    position.pageId,
                    FixedPx(position.offsetInPageUnits),
                    revision = 0L,
                )
            }
            ?: scrollController.initial(layout, event.viewport)
        val finalScroll = if (event.initialScroll == FixedPx.ZERO) {
            scroll
        } else {
            scrollController.scrollBy(
                layout,
                event.viewport,
                scroll,
                event.initialScroll,
            )
        }
        val initialTarget = pageAtViewportCenter(layout, event.viewport, finalScroll)
        val state = initialState(event, layout, finalScroll, initialTarget, prepared.pages).copy(
            userInputRevision = if (finalScroll.contentOffset != scroll.contentOffset) 1L else 0L,
            velocityUnitsPerSecond = if (event.initialScroll == FixedPx.ZERO) {
                0L
            } else {
                event.initialVelocityUnitsPerSecond
            },
        )
        val cancellation = current?.let(::retireState).orEmpty()
        return finish(Reduction(state, cancellation))
    }

    private fun pageAtViewportCenter(
        layout: LayoutLedger,
        viewport: Viewport,
        scroll: ScrollSnapshot,
    ): PageId {
        val maximum = (layout.totalHeight.units - 1L).coerceAtLeast(0L)
        val center = saturatingAdd(scroll.contentOffset.units, viewport.height.units / 2L)
        return requireNotNull(layout.pageAt(FixedPx(center.coerceIn(0L, maximum))))
    }

    private fun retargetInitialFetch(state: ViewerState): Reduction {
        if (state.firstResponseReceived || state.initialFetchRetargeted) {
            return Reduction(state, emptyList())
        }
        val target = pageAtViewportCenter(state.layout, state.viewport, state.scroll)
        if (target == state.initialTargetPageId) return Reduction(state, emptyList())
        val previous = state.ownership.fetches.values.singleOrNull()
        val ownership = previous?.let(state.ownership::release) ?: state.ownership
        return Reduction(
            state.copy(
                initialTargetPageId = target,
                ownership = ownership,
                initialFetchRetargeted = true,
            ),
            previous?.let { listOf(ViewerCommand.CancelFetch(it)) }.orEmpty(),
        )
    }

    private fun initialState(
        event: ViewerEvent.OpenEpisode,
        layout: LayoutLedger,
        scroll: ScrollSnapshot,
        initialTargetPageId: PageId,
        pages: kotlinx.collections.immutable.PersistentMap<PageId, PageRuntime>,
    ): ViewerState = ViewerState(
            generation = event.generation,
            manifests = listOf(event.manifest),
            pageOrder = event.manifest.pages.map { it.id },
            pages = pages,
            initialTargetPageId = initialTargetPageId,
            layout = layout,
            scroll = scroll,
            userInputRevision = 0L,
            viewport = event.viewport,
            visibility = ViewerVisibility.FOREGROUND,
            surfaceAttached = false,
            velocityUnitsPerSecond = 0L,
            ownership = WorkOwnership(),
            episodeAppends = emptyMap(),
            episodeProgress = mapOf(
                event.manifest.id to EpisodeProgress(
                    event.manifest.pages.size,
                    0,
                    event.manifest.pages.last().id,
                ),
            ),
            coldFetchSweep = ColdFetchSweep.create(
                event.manifest.pages.size,
                requireNotNull(layout.indexOf(initialTargetPageId)),
            ),
            residentPageIds = emptyList(),
            residentBytes = 0L,
            firstResponseReceived = false,
            networkConcurrency = 1,
            retryDeadlines = persistentMapOf(),
            nextRetryDeadlineNanos = null,
            lastEventNanos = event.atNanos,
            interactionActive = event.initialInteractionActive,
            startupMotionPending = event.initialInteractionActive,
        )

    private fun reduceExisting(state: ViewerState, event: ViewerEvent): Reduction = when (event) {
        is ViewerEvent.OpenEpisode -> error("Open events are handled before existing state")
        is ViewerEvent.NextEpisodeSucceeded -> Reduction(episodeAppendReducer.succeeded(state, event), emptyList())
        is ViewerEvent.NextEpisodeFailed -> Reduction(episodeAppendReducer.failed(state, event), emptyList())
        is ViewerEvent.UserScroll -> Reduction(userScroll(state, event), emptyList())
        is ViewerEvent.InteractionChanged -> interactionChanged(state, event)
        is ViewerEvent.ViewportChanged -> Reduction(resize(state, event.viewport), emptyList())
        is ViewerEvent.FetchResponseStarted -> Reduction(pageEvents.fetchResponseStarted(state, event), emptyList())
        is ViewerEvent.FetchSucceeded -> Reduction(pageEvents.fetchSucceeded(state, event), emptyList())
        is ViewerEvent.FetchFailed -> Reduction(pageEvents.fetchFailed(state, event), emptyList())
        is ViewerEvent.DecodeSucceeded -> pageEvents.decodeSucceeded(state, event)
        is ViewerEvent.DecodeFailed -> Reduction(pageEvents.decodeFailed(state, event), emptyList())
        is ViewerEvent.RetryWakeup -> Reduction(state, emptyList())
        is ViewerEvent.EnterBackground -> enterBackground(state)
        is ViewerEvent.ReturnForeground -> Reduction(
            state.copy(visibility = ViewerVisibility.FOREGROUND),
            emptyList(),
        )
        is ViewerEvent.SurfaceAttachmentChanged -> Reduction(
            state.copy(surfaceAttached = event.attached),
            emptyList(),
        )
        is ViewerEvent.EvictPage -> evict(state, event.generation, event.pageId)
    }

    private fun userScroll(
        state: ViewerState,
        event: ViewerEvent.UserScroll,
        eventTimeNanos: Long = maxOf(state.lastEventNanos, event.atNanos),
    ): ViewerState {
        require(event.frameTimelineVsyncId >= INVALID_FRAME_TIMELINE_VSYNC_ID) {
            "Frame timeline VSYNC id is invalid"
        }
        val scroll = scrollController.scrollBy(state.layout, state.viewport, state.scroll, event.delta)
        val displaced = event.delta != FixedPx.ZERO && scroll.contentOffset != state.scroll.contentOffset
        val velocity = when {
            event.velocityUnitsPerSecond != 0L -> event.velocityUnitsPerSecond
            event.delta > FixedPx.ZERO -> 1L
            event.delta < FixedPx.ZERO -> -1L
            else -> state.velocityUnitsPerSecond
        }
        val currentRetryDeadline = state.nextRetryDeadlineNanos
        val nextRetryDeadline = when {
            currentRetryDeadline == null -> null
            currentRetryDeadline > eventTimeNanos -> currentRetryDeadline
            else -> state.retryDeadlines.values.asSequence()
                .filter { it > eventTimeNanos }
                .minOrNull()
        }
        return state.copy(
            scroll = scroll,
            userInputRevision = if (displaced) {
                saturatingAdd(state.userInputRevision, 1L)
            } else {
                state.userInputRevision
            },
            velocityUnitsPerSecond = velocity,
            lastEventNanos = eventTimeNanos,
            nextRetryDeadlineNanos = nextRetryDeadline,
            frameTimelineVsyncId = event.frameTimelineVsyncId,
            expectedPresentationTimeNanos = event.expectedPresentationTimeNanos,
        )
    }

    private fun resize(state: ViewerState, viewport: Viewport): ViewerState {
        val ledger = state.layout.reflow(viewport.width)
        val scroll = scrollController.preserveAnchor(ledger, viewport, state.scroll)
        return state.copy(layout = ledger, viewport = viewport, scroll = scroll)
    }

    private fun interactionChanged(
        state: ViewerState,
        event: ViewerEvent.InteractionChanged,
    ): Reduction {
        val startedBeforePixels = event.active && state.residentPageIds.isEmpty()
        return Reduction(
            state.copy(
                interactionActive = event.active,
                startupMotionPending = event.active &&
                    (state.startupMotionPending || startedBeforePixels),
            ),
            emptyList(),
        )
    }

    private fun enterBackground(state: ViewerState): Reduction {
        val cancellations = state.ownership.decodes.values.map(ViewerCommand::CancelDecode)
        val frozen = scrollController.capture(state.layout, state.scroll)
        return Reduction(
            state.copy(
                visibility = ViewerVisibility.BACKGROUND,
                scroll = frozen,
                ownership = state.ownership.clearDecodes(),
                interactionActive = false,
                startupMotionPending = false,
            ),
            cancellations,
        )
    }

    private fun evict(state: ViewerState, generation: Long, pageId: PageId): Reduction {
        if (generation != state.generation || pageId in visiblePageIds(state)) {
            return Reduction(state, emptyList())
        }
        val runtime = state.pages[pageId] ?: return Reduction(state, emptyList())
        val pixel = runtime.pixel ?: return Reduction(state, emptyList())
        if (runtime.encoded == null) return Reduction(state, emptyList())
        val evicted = runtime.copy(
            residency = PageResidency.VERIFIED,
            pixel = null,
            isPresented = false,
        )
        return Reduction(
            state.replacePage(pageId, evicted),
            listOf(ViewerCommand.ReleasePixel(pixel)),
        )
    }

    private fun finish(base: Reduction): Reduction {
        val presented = markPresented(base.state)
        val trimmed = pixelBudgetPlanner.trim(presented)
        val scheduled = workScheduler.schedule(trimmed.state)
        val appendScheduled = episodeAppendScheduler.schedule(scheduled.state)
        return Reduction(
            appendScheduled.state,
            base.commands + trimmed.commands + scheduled.commands + appendScheduled.commands,
        )
    }

    private fun markPresented(state: ViewerState): ViewerState {
        if (!state.surfaceAttached || state.visibility != ViewerVisibility.FOREGROUND) return state
        val visible = visiblePageIds(state)
        var changed = state
        var newlyPresented = false
        visible.forEach { pageId ->
            val runtime = changed.pages.getValue(pageId)
            if (!runtime.isPresented && coversVisiblePage(changed, pageId, runtime.pixel)) {
                val presented = runtime.advance(PageMilestone.PRESENTED).copy(isPresented = true)
                changed = changed.replacePage(pageId, presented)
                newlyPresented = true
            }
        }
        return if (newlyPresented && !changed.hasPresentedContent) {
            changed.copy(hasPresentedContent = true)
        } else {
            changed
        }
    }

    private fun visiblePageIds(state: ViewerState): Set<PageId> {
        val start = state.scroll.contentOffset
        val end = FixedPx(saturatingAdd(start.units, state.viewport.height.units))
        return state.layout.indicesIntersecting(start, end).mapTo(mutableSetOf()) { state.pageOrder[it] }
    }

    private fun coversVisiblePage(state: ViewerState, pageId: PageId, pixel: PixelRef?): Boolean {
        pixel ?: return false
        val index = state.layout.indexOf(pageId) ?: return false
        val pageTop = state.layout.topAt(index).units
        val pageHeight = state.layout.entries[index].height.units
        val viewportStart = state.scroll.contentOffset.units
        val viewportEnd = saturatingAdd(viewportStart, state.viewport.height.units)
        val visibleStart = maxOf(pageTop, viewportStart)
        val visibleEnd = minOf(Math.addExact(pageTop, pageHeight), viewportEnd)
        var coveredThrough = visibleStart
        pixel.tiles.sortedBy { it.sourceTopPx }.forEach { tile ->
            val top = Math.addExact(
                pageTop,
                multiplyDivideFloorExact(pageHeight, tile.sourceTopPx, pixel.dimensions.heightPx),
            )
            val bottom = Math.addExact(
                pageTop,
                multiplyDivideFloorExact(pageHeight, tile.sourceBottomPx, pixel.dimensions.heightPx),
            )
            if (bottom > coveredThrough) {
                if (top > coveredThrough) return false
                coveredThrough = bottom
                if (coveredThrough >= visibleEnd) return true
            }
        }
        return false
    }

    private fun retireState(state: ViewerState): List<ViewerCommand> = buildList {
        add(ViewerCommand.CancelGeneration(state.generation))
        state.residentPageIds.mapNotNull { state.pages.getValue(it).pixel }
            .forEach { add(ViewerCommand.ReleasePixel(it)) }
    }

}
