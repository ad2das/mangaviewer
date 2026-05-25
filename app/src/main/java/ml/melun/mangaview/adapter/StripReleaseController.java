package ml.melun.mangaview.adapter;

final class StripReleaseController {
    private final StripAdapterState state;
    private final StripAdapterRuntime runtime;

    StripReleaseController(StripAdapterState state, StripAdapterRuntime runtime) {
        this.state = state;
        this.runtime = runtime;
    }

    void release() {
        state.released = true;
        state.preloadScheduleState.clearPendingSchedules();
        state.previewState.clear();
        state.mainHandler.removeCallbacksAndMessages(null);
        runtime.reusablePageStateTrimmer.clearAll();
    }
}
