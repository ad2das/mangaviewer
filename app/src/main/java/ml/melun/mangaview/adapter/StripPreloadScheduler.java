package ml.melun.mangaview.adapter;

import android.os.Handler;
import android.os.SystemClock;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ml.melun.mangaview.glide.ViewerPreloadPolicy;

final class StripPreloadScheduler {
    interface Callbacks {
        boolean canStart();
        boolean isScrollBusy();
        boolean isDataSave();
        List<Object> items();
    }

    private final Handler mainHandler;
    private final StripPreloadScheduleState scheduleState;
    private final StripPreloadWindowRunner windowRunner;
    private final Callbacks callbacks;

    StripPreloadScheduler(Handler mainHandler, StripPreloadScheduleState scheduleState,
                          StripPreloadWindowRunner windowRunner, Callbacks callbacks) {
        this.mainHandler = mainHandler;
        this.scheduleState = scheduleState;
        this.windowRunner = windowRunner;
        this.callbacks = callbacks;
    }

    void preloadAroundScrollPosition(int adapterPosition) {
        if(adapterPosition == RecyclerView.NO_POSITION || !callbacks.canStart())
            return;
        int direction = scheduleState.directionForAround(adapterPosition);
        scheduleState.recordPreloadAnchor(adapterPosition);
        long generation = scheduleState.generation();
        if(callbacks.isScrollBusy()) {
            preloadDirectionalWindow(adapterPosition, direction,
                    ViewerPreloadPolicy.scrollBusyWindow(callbacks.isDataSave()), generation);
            return;
        }
        preloadCriticalWindow(adapterPosition, direction, generation);
        preloadDirectionalWindow(adapterPosition, direction,
                ViewerPreloadPolicy.scrollAheadWindow(callbacks.isDataSave()), generation);
        preloadDirectionalWindow(adapterPosition, -direction,
                new ViewerPreloadPolicy.Window(0, 1, 2, 2), generation);
    }

    void preloadAheadFromBindPosition(int adapterPosition) {
        if(adapterPosition == RecyclerView.NO_POSITION || !callbacks.canStart())
            return;
        int direction = scheduleState.directionForBind();
        if(direction == 0)
            direction = 1;
        ViewerPreloadPolicy.Window window = callbacks.isScrollBusy()
                ? ViewerPreloadPolicy.scrollBusyWindow(callbacks.isDataSave())
                : ViewerPreloadPolicy.scrollAheadWindow(callbacks.isDataSave());
        if(callbacks.isScrollBusy()) {
            preloadSourceDirectionalWindow(adapterPosition + direction, direction, window, scheduleState.generation());
            return;
        }
        preloadDirectionalWindow(adapterPosition + direction, direction, window, scheduleState.generation());
    }

    void schedulePreloadAroundScrollPosition(int adapterPosition) {
        if(adapterPosition == RecyclerView.NO_POSITION || !callbacks.canStart())
            return;
        scheduleState.setPendingPreloadPosition(adapterPosition);
        int direction = scheduleState.directionForAround(adapterPosition);
        if(!callbacks.isScrollBusy() && !isIdlePreloadReady()) {
            scheduleDelayedPreloadAroundScrollPosition();
            return;
        }
        preloadCriticalWindow(adapterPosition, direction, scheduleState.generation());
        if(callbacks.isScrollBusy() || scheduleState.isPreloadScheduled())
            return;
        scheduleState.setPreloadScheduled(true);
        mainHandler.postDelayed(() -> {
            int target = scheduleState.consumePendingPreloadPosition();
            if(target != RecyclerView.NO_POSITION && callbacks.canStart())
                preloadAroundScrollPosition(target);
        }, 24);
    }

    void preloadDirectionalWindow(int adapterPosition, int direction, ViewerPreloadPolicy.Window window) {
        preloadDirectionalWindow(adapterPosition, direction, window, scheduleState.generation());
    }

    void preloadDirectionalWindow(int adapterPosition, int direction,
                                  ViewerPreloadPolicy.Window window, long generation) {
        windowRunner.preloadDirectionalWindow(callbacks.items(), adapterPosition, direction, window, generation, false);
    }

    void preloadSourceDirectionalWindow(int adapterPosition, int direction,
                                        ViewerPreloadPolicy.Window window, long generation) {
        windowRunner.preloadDirectionalWindow(callbacks.items(), adapterPosition, direction, window, generation, true);
    }

    boolean isIdlePreloadReady() {
        return scheduleState.isIdlePreloadReady(callbacks.isScrollBusy(), SystemClock.uptimeMillis());
    }

    private void scheduleDelayedPreloadAroundScrollPosition() {
        if(scheduleState.isPreloadScheduled())
            return;
        scheduleState.setPreloadScheduled(true);
        long delayMs = scheduleState.delayedPreloadDelayMs(SystemClock.uptimeMillis());
        mainHandler.postDelayed(() -> {
            int target = scheduleState.consumePendingPreloadPosition();
            if(target != RecyclerView.NO_POSITION && callbacks.canStart())
                preloadAroundScrollPosition(target);
        }, delayMs);
    }

    private void preloadCriticalWindow(int adapterPosition, int direction, long generation) {
        if(adapterPosition == RecyclerView.NO_POSITION || !callbacks.canStart() || callbacks.isScrollBusy())
            return;
        int decodedLimit = callbacks.isDataSave() ? 1 : 2;
        preloadDirectionalWindow(adapterPosition, direction,
                new ViewerPreloadPolicy.Window(decodedLimit, decodedLimit, decodedLimit, decodedLimit),
                generation);
    }
}
