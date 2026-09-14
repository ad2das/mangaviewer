package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.viewer.FixedPx

/**
 * Quantizes ordered raw pointer segments into [FixedPx] without repeated-drain rounding drift.
 *
 * State persists across drains and is reset only by [begin] (a new gesture, after the previous one
 * was flushed) or by a true sign reversal in the raw input. [rebase] never resets: a pointer
 * identity change is not input. Within one same-direction run the emitted units telescope to
 * `round(runPixels * UNITS_PER_PIXEL)`, so regrouping the same raw samples (per event vs per frame)
 * is invariant, direction order is preserved, and nothing is replayed against a boundary.
 *
 * A nonzero raw segment that quantizes to zero deliberately returns [FixedPx.ZERO] instead of
 * being skipped: the sink receipt and its raw trace segment stay intact, while the engine
 * naturally observes no movement.
 */
internal class PointerDeltaQuantizer {
    private var runPixels = 0.0
    private var emittedUnits = 0L

    /** The [FixedPx] delta to apply for one raw segment; [FixedPx.ZERO] for a sub-quantum segment. */
    fun apply(segmentPixels: Double): FixedPx {
        if (segmentPixels == 0.0) return FixedPx.ZERO
        if (runPixels != 0.0 && (runPixels > 0.0) != (segmentPixels > 0.0)) {
            runPixels = 0.0
            emittedUnits = 0L
        }
        runPixels += segmentPixels
        val target = FixedPx.fromPixels(runPixels)
        val delta = target.units - emittedUnits
        emittedUnits = target.units
        return FixedPx(delta)
    }

    /** A new gesture starts a fresh run; the previous run was already finalized by its emissions. */
    fun begin() {
        runPixels = 0.0
        emittedUnits = 0L
    }

    /** Pointer identity change inside a gesture: not input, so the active run survives. */
    fun rebase() = Unit
}
