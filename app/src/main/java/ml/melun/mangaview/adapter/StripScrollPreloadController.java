package ml.melun.mangaview.adapter;

import static ml.melun.mangaview.MainApplication.p;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Priority;

import java.util.List;

import ml.melun.mangaview.glide.ViewerPreloadPolicy;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.model.PageItem;

final class StripScrollPreloadController {
    interface Host {
        boolean released();
        boolean scrollBusy();
        void setScrollBusy(boolean scrollBusy);
        List<Object> items();
        int findFirstMatchingPagePosition(PageItem page);
        int findFirstPagePosition(Manga manga);
        void schedulePendingHeightCorrections();
        void schedulePreviewFullRebind(long extraDelayMs);
    }

    private static final boolean AUTO_PROMOTE_PREVIEW_FULL_QUALITY = true;
    private static final boolean RENDER_ONLY_PRELOADS = StripImagePolicy.RENDER_ONLY_PRELOADS;

    private final Host host;
    private final StripPreloadScheduleState preloadScheduleState;
    private final StripAttachedHolderState attachedHolderState;
    private final StripPreviewState previewState;
    private final StripPreloadScheduler preloadScheduler;
    private final StripPreloadRequester preloadRequester;

    StripScrollPreloadController(Host host, StripPreloadScheduleState preloadScheduleState,
                                 StripAttachedHolderState attachedHolderState, StripPreviewState previewState,
                                 StripPreloadScheduler preloadScheduler,
                                 StripPreloadRequester preloadRequester) {
        this.host = host;
        this.preloadScheduleState = preloadScheduleState;
        this.attachedHolderState = attachedHolderState;
        this.previewState = previewState;
        this.preloadScheduler = preloadScheduler;
        this.preloadRequester = preloadRequester;
    }

    void setScrollState(int scrollState) {
        setScrollBusy(scrollState != RecyclerView.SCROLL_STATE_IDLE);
    }

    void setScrollBusy(boolean scrollBusy) {
        if(host.released())
            return;
        boolean changed = host.scrollBusy() != scrollBusy;
        host.setScrollBusy(scrollBusy);
        if(changed)
            applyFastDrawToAttachedHolders(scrollBusy);
        if(changed && !scrollBusy)
            preloadScheduleState.recordIdle(android.os.SystemClock.uptimeMillis(),
                    StripImagePolicy.SCROLL_IDLE_PRELOAD_DELAY_MS);
        if(!scrollBusy) {
            host.schedulePendingHeightCorrections();
            if(AUTO_PROMOTE_PREVIEW_FULL_QUALITY)
                host.schedulePreviewFullRebind(0L);
            if(!RENDER_ONLY_PRELOADS && preloadScheduleState.hasPendingPreloadPosition())
                schedulePreloadAroundScrollPosition(preloadScheduleState.pendingPreloadPosition());
        }
    }

    private void applyFastDrawToAttachedHolders(boolean fastDraw) {
        attachedHolderState.applyFastDraw(fastDraw, previewState::isPreviewOnly);
    }

    void onScrollAnchor(int adapterPosition, int direction, boolean busy) {
        if(host.released() || adapterPosition == RecyclerView.NO_POSITION)
            return;
        StripPreloadScheduleState.AnchorUpdate anchorUpdate =
                preloadScheduleState.recordAnchor(adapterPosition, direction);
        host.setScrollBusy(busy);
        if(RENDER_ONLY_PRELOADS)
            return;
        if(busy) {
            long now = android.os.SystemClock.uptimeMillis();
            if(shouldRunBusyPreload(preloadScheduleState.lastBusyPreloadPosition(), adapterPosition,
                    preloadScheduleState.busyPreloadElapsedMs(now))) {
                if(anchorUpdate.anchorChanged)
                    preloadScheduleState.bumpGeneration();
                preloadScheduleState.recordBusyPreload(adapterPosition, now);
            }
            return;
        }
        if(anchorUpdate.anchorChanged)
            preloadScheduleState.bumpGeneration();
        schedulePreloadAroundScrollPosition(adapterPosition);
    }

    void preloadAll() {
        List<Object> items = host.items();
        if(items == null)
            return;
        for(Object o : items) {
            if(o instanceof PageItem)
                preloadRequester.preloadPageSourceOnly((PageItem) o, Priority.LOW);
        }
    }

    void preloadAroundPage(PageItem page, int aheadCount) {
        if(page == null || host.items() == null)
            return;
        int start = host.findFirstMatchingPagePosition(page);
        if(start == RecyclerView.NO_POSITION)
            return;
        ViewerPreloadPolicy.Window policy = ViewerPreloadPolicy.scrollAheadWindow(p.getDataSave());
        preloadDirectionalWindow(start, 1, clampWindow(policy, aheadCount));
        preloadDirectionalWindow(start - 1, -1, reversePreloadWindow());
    }

    void preloadInitialAroundPage(PageItem page) {
        if(page == null || host.items() == null)
            return;
        int start = host.findFirstMatchingPagePosition(page);
        if(start == RecyclerView.NO_POSITION)
            start = host.findFirstPagePosition(page.manga);
        if(start == RecyclerView.NO_POSITION)
            return;
        preloadDirectionalWindow(start, 1, ViewerPreloadPolicy.initialScrollWindow(p.getDataSave()));
        preloadDirectionalWindow(start - 1, -1, reversePreloadWindow());
    }

    void preloadAroundScrollPosition(int adapterPosition) {
        preloadScheduler.preloadAroundScrollPosition(adapterPosition);
    }

    void preloadAheadFromBindPosition(int adapterPosition) {
        preloadScheduler.preloadAheadFromBindPosition(adapterPosition);
    }

    private void schedulePreloadAroundScrollPosition(int adapterPosition) {
        preloadScheduler.schedulePreloadAroundScrollPosition(adapterPosition);
    }

    private void preloadDirectionalWindow(int adapterPosition, int direction, ViewerPreloadPolicy.Window window) {
        preloadScheduler.preloadDirectionalWindow(adapterPosition, direction, window);
    }

    private ViewerPreloadPolicy.Window clampWindow(ViewerPreloadPolicy.Window policy, int totalLimit) {
        return StripPreloadWindowPolicy.clamp(policy, totalLimit);
    }

    private ViewerPreloadPolicy.Window reversePreloadWindow() {
        return StripPreloadWindowPolicy.reverseWindow(p.getDataSave());
    }

    static boolean shouldRunBusyPreload(int previousPosition, int nextPosition, long elapsedMs) {
        return StripImagePolicy.shouldRunBusyPreload(previousPosition, nextPosition, elapsedMs);
    }
}
