package ml.melun.mangaview.adapter;

import androidx.recyclerview.widget.RecyclerView;

final class StripPreloadScheduleState {
    private int lastPreloadAnchorPosition = RecyclerView.NO_POSITION;
    private int pendingPreloadPosition = RecyclerView.NO_POSITION;
    private int pendingPreloadDirection = 1;
    private int lastScrollDirection = 1;
    private int lastBusyPreloadPosition = RecyclerView.NO_POSITION;
    private long preloadGeneration = 0L;
    private long lastBusyPreloadAtMs = 0L;
    private long idlePreloadReadyAtMs = 0L;
    private boolean pendingPreloadScheduled = false;
    private boolean pendingHeightCorrectionScheduled = false;
    private long lastScrollBusyEndedAtMs = 0L;

    AnchorUpdate recordAnchor(int adapterPosition, int direction) {
        int normalizedDirection = direction < 0 ? -1 : 1;
        boolean anchorChanged = pendingPreloadPosition != adapterPosition
                || lastPreloadAnchorPosition != adapterPosition
                || lastScrollDirection != normalizedDirection;
        lastScrollDirection = normalizedDirection;
        pendingPreloadDirection = normalizedDirection;
        pendingPreloadPosition = adapterPosition;
        return new AnchorUpdate(normalizedDirection, anchorChanged);
    }

    void recordIdle(long nowMs, long preloadDelayMs) {
        lastScrollBusyEndedAtMs = nowMs;
        idlePreloadReadyAtMs = nowMs + preloadDelayMs;
        lastBusyPreloadPosition = RecyclerView.NO_POSITION;
        lastBusyPreloadAtMs = 0L;
    }

    void bumpGeneration() {
        preloadGeneration++;
    }

    long generation() {
        return preloadGeneration;
    }

    int lastBusyPreloadPosition() {
        return lastBusyPreloadPosition;
    }

    long busyPreloadElapsedMs(long nowMs) {
        return nowMs - lastBusyPreloadAtMs;
    }

    void recordBusyPreload(int adapterPosition, long nowMs) {
        lastBusyPreloadPosition = adapterPosition;
        lastBusyPreloadAtMs = nowMs;
    }

    boolean hasPendingPreloadPosition() {
        return pendingPreloadPosition != RecyclerView.NO_POSITION;
    }

    int pendingPreloadPosition() {
        return pendingPreloadPosition;
    }

    void setPendingPreloadPosition(int adapterPosition) {
        pendingPreloadPosition = adapterPosition;
    }

    int consumePendingPreloadPosition() {
        int target = pendingPreloadPosition;
        pendingPreloadPosition = RecyclerView.NO_POSITION;
        pendingPreloadScheduled = false;
        return target;
    }

    int directionForAround(int adapterPosition) {
        return pendingPreloadDirection != 0
                ? pendingPreloadDirection
                : (lastPreloadAnchorPosition != RecyclerView.NO_POSITION && adapterPosition < lastPreloadAnchorPosition ? -1 : 1);
    }

    int directionForBind() {
        return pendingPreloadDirection != 0 ? pendingPreloadDirection : lastScrollDirection;
    }

    void recordPreloadAnchor(int adapterPosition) {
        lastPreloadAnchorPosition = adapterPosition;
    }

    int anchorForItemCount(int itemCount) {
        if(lastPreloadAnchorPosition != RecyclerView.NO_POSITION)
            return lastPreloadAnchorPosition;
        return Math.max(0, Math.min(pendingPreloadPosition, itemCount - 1));
    }

    boolean isIdlePreloadReady(boolean scrollBusy, long nowMs) {
        return scrollBusy || nowMs >= idlePreloadReadyAtMs;
    }

    long delayedPreloadDelayMs(long nowMs) {
        return Math.max(24L, idlePreloadReadyAtMs - nowMs);
    }

    boolean isPreloadScheduled() {
        return pendingPreloadScheduled;
    }

    void setPreloadScheduled(boolean scheduled) {
        pendingPreloadScheduled = scheduled;
    }

    boolean isHeightCorrectionScheduled() {
        return pendingHeightCorrectionScheduled;
    }

    void setHeightCorrectionScheduled(boolean scheduled) {
        pendingHeightCorrectionScheduled = scheduled;
    }

    long stableIdleRemainingMs(long nowMs, long stableIdleMs) {
        if(lastScrollBusyEndedAtMs <= 0L)
            return 0L;
        long elapsedMs = nowMs - lastScrollBusyEndedAtMs;
        return Math.max(0L, stableIdleMs - elapsedMs);
    }

    void clearPendingSchedules() {
        pendingPreloadPosition = RecyclerView.NO_POSITION;
        pendingPreloadScheduled = false;
        pendingHeightCorrectionScheduled = false;
    }

    static final class AnchorUpdate {
        final int direction;
        final boolean anchorChanged;

        AnchorUpdate(int direction, boolean anchorChanged) {
            this.direction = direction;
            this.anchorChanged = anchorChanged;
        }
    }
}
