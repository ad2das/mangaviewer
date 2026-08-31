package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition

enum class ScrollMutationCause {
    USER_INPUT,
    EPISODE_NAVIGATION,
    GEOMETRY_CORRECTION,
}

data class ScrollSnapshot(
    val contentOffset: FixedPx,
    val anchor: ReadingPosition,
    val lastCause: ScrollMutationCause,
    val revision: Long,
)

class ScrollController {
    fun initial(
        ledger: LayoutLedger,
        viewport: Viewport,
        pageId: PageId = ledger.entries.first().spec.id,
    ): ScrollSnapshot = navigate(ledger, viewport, pageId, FixedPx.ZERO, 0L)

    fun scrollBy(
        ledger: LayoutLedger,
        viewport: Viewport,
        current: ScrollSnapshot,
        delta: FixedPx,
    ): ScrollSnapshot {
        val maximum = maximumOffset(ledger, viewport)
        val nextOffset = (current.contentOffset + delta).coerceIn(FixedPx.ZERO, maximum)
        if (nextOffset == current.contentOffset) return current
        return snapshotAt(
            ledger = ledger,
            contentOffset = nextOffset,
            cause = ScrollMutationCause.USER_INPUT,
            revision = saturatingAdd(current.revision, 1L),
        )
    }

    fun navigate(
        ledger: LayoutLedger,
        viewport: Viewport,
        pageId: PageId,
        offsetInPage: FixedPx,
        revision: Long,
    ): ScrollSnapshot {
        val pageTop = requireNotNull(ledger.topOf(pageId)) { "Navigation page is not in the ledger" }
        val pageHeight = requireNotNull(ledger.heightOf(pageId))
        val safeOffset = offsetInPage.coerceIn(FixedPx.ZERO, FixedPx(pageHeight.units - 1L))
        val contentOffset = (pageTop + safeOffset).coerceIn(
            FixedPx.ZERO,
            maximumOffset(ledger, viewport),
        )
        return snapshotAt(
            ledger = ledger,
            contentOffset = contentOffset,
            cause = ScrollMutationCause.EPISODE_NAVIGATION,
            revision = revision,
        )
    }

    fun preserveAnchor(
        ledger: LayoutLedger,
        viewport: Viewport,
        current: ScrollSnapshot,
    ): ScrollSnapshot {
        val originalIndex = requireNotNull(ledger.indexOf(current.anchor.pageId)) {
            "Geometry correction cannot preserve a page outside the ledger"
        }
        val relocated = relocateForwardOverflow(
            ledger,
            originalIndex,
            current.anchor.offsetInPageUnits,
        )
        val top = ledger.topAt(relocated.index)
        val requested = FixedPx(saturatingSubtract(
            saturatingAdd(top.units, relocated.offsetUnits),
            current.anchor.viewportOffsetUnits,
        ))
        val corrected = requested.coerceIn(FixedPx.ZERO, maximumOffset(ledger, viewport))
        val viewportOffset = saturatingSubtract(
            saturatingAdd(top.units, relocated.offsetUnits),
            corrected.units,
        )
        val anchor = ReadingPosition(
            pageId = ledger.entries[relocated.index].spec.id,
            offsetInPageUnits = relocated.offsetUnits,
            viewportOffsetUnits = viewportOffset,
        )
        if (corrected == current.contentOffset && anchor == current.anchor) return current
        return current.copy(
            contentOffset = corrected,
            anchor = anchor,
            lastCause = ScrollMutationCause.GEOMETRY_CORRECTION,
            revision = saturatingAdd(current.revision, 1L),
        )
    }

    fun capture(ledger: LayoutLedger, current: ScrollSnapshot): ScrollSnapshot =
        snapshotAt(
            ledger = ledger,
            contentOffset = current.contentOffset,
            cause = current.lastCause,
            revision = current.revision,
        )

    private fun snapshotAt(
        ledger: LayoutLedger,
        contentOffset: FixedPx,
        cause: ScrollMutationCause,
        revision: Long,
    ): ScrollSnapshot {
        val pageId = requireNotNull(ledger.pageAt(contentOffset))
        val top = requireNotNull(ledger.topOf(pageId))
        return ScrollSnapshot(
            contentOffset = contentOffset,
            anchor = ReadingPosition(pageId, contentOffset.units - top.units),
            lastCause = cause,
            revision = revision,
        )
    }

    private fun maximumOffset(ledger: LayoutLedger, viewport: Viewport): FixedPx =
        FixedPx((ledger.totalHeight.units - viewport.height.units).coerceAtLeast(0L))

    private fun relocateForwardOverflow(
        ledger: LayoutLedger,
        initialIndex: Int,
        initialOffsetUnits: Long,
    ): RelocatedAnchor {
        var index = initialIndex
        var offset = initialOffsetUnits
        while (index < ledger.entries.lastIndex) {
            val height = ledger.entries[index].height.units
            if (offset < height) return RelocatedAnchor(index, offset)
            offset -= height
            index += 1
        }
        val finalHeight = ledger.entries[index].height.units
        return RelocatedAnchor(index, offset.coerceAtMost(finalHeight - 1L))
    }

    private data class RelocatedAnchor(
        val index: Int,
        val offsetUnits: Long,
    )
}
