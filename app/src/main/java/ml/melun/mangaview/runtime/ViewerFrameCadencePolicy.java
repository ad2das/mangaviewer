package ml.melun.mangaview.runtime;

/** Pure causal classification for native viewer presentation cadence. */
final class ViewerFrameCadencePolicy {
    private static final long ISOLATED_INPUT_STALL_NANOS = 100_000_000L;

    private ViewerFrameCadencePolicy() {
    }

    /**
     * Distinguishes a renderer/input backlog from a gap in physical input samples.
     *
     * <p>Touch hardware is allowed to deliver MOVE samples less frequently than display refresh.
     * Treating the interval between two such samples as a missed app frame makes sparse input
     * look like permanent jank. The native strip renderer carries the complete input envelope
     * that caused each presented frame; a genuinely blocked frame retains an oldest event whose
     * age exceeds the refresh deadline. Missing or malformed causal evidence remains
     * fail-closed.</p>
     */
    static boolean isDemandBackedSlowInterval(
            long intervalNanos,
            long refreshPeriodNanos,
            long presentedAtNanos,
            long inputOldestNanos,
            long inputNewestNanos) {
        return isDemandBackedSlowInterval(
                intervalNanos,
                refreshPeriodNanos,
                presentedAtNanos,
                inputOldestNanos,
                inputNewestNanos,
                0L,
                0L);
    }

    static boolean isDemandBackedSlowInterval(
            long intervalNanos,
            long refreshPeriodNanos,
            long presentedAtNanos,
            long inputOldestNanos,
            long inputNewestNanos,
            long inputReceivedOldestNanos,
            long inputReceivedNewestNanos) {
        long slowThreshold = refreshPeriodNanos + refreshPeriodNanos / 2L;
        if(intervalNanos <= slowThreshold)
            return false;
        // MotionEvent.eventTime remains the end-to-end input-latency clock. Emulator injection
        // can deliver an old event to the app much later, however; that system queue did not
        // create renderer demand. Classify app presentation cadence from the dispatch-receipt
        // envelope when present while retaining the hardware envelope in the immutable proof.
        boolean validReceipt = inputReceivedOldestNanos > 0L &&
                inputReceivedNewestNanos >= inputReceivedOldestNanos &&
                presentedAtNanos >= inputReceivedNewestNanos;
        long demandOldestNanos = validReceipt
                ? inputReceivedOldestNanos
                : inputOldestNanos;
        long demandNewestNanos = validReceipt
                ? inputReceivedNewestNanos
                : inputNewestNanos;
        if(demandOldestNanos <= 0L || demandNewestNanos < demandOldestNanos ||
                presentedAtNanos < demandNewestNanos)
            return true;
        if(demandNewestNanos - demandOldestNanos >= refreshPeriodNanos / 2L) {
            // More than one physical input sample was merged into this frame. The renderer had
            // continuous demand, so the display-refresh deadline is the correct latency bound.
            // A few microseconds of batching jitter is still one physical input sample and must
            // not turn a sparse real touch (or UiAutomation swipe step) into synthetic jank.
            return presentedAtNanos - demandOldestNanos > slowThreshold;
        }
        // A single sample after a long input-free gap does not ask the app to synthesize the
        // missing frames. It is still a real stall when that isolated event itself takes 100 ms
        // to become visible; this is also the corruption guard used by device smoke tests.
        return presentedAtNanos - demandNewestNanos >= ISOLATED_INPUT_STALL_NANOS;
    }
}
