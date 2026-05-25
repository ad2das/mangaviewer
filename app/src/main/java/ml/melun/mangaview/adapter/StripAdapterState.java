package ml.melun.mangaview.adapter;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ml.melun.mangaview.mangaview.Decoder;

final class StripAdapterState {
    List<Object> items;
    int count = 0;
    boolean scrollBusy = false;
    boolean released = false;
    final StripPreloadState preloadState = new StripPreloadState(StripAdapter.PRELOAD_TRACK_LIMIT);
    final StripDisplayState displayState = new StripDisplayState(StripAdapter.PRELOAD_TRACK_LIMIT);
    StripDecodedBitmapCache decodedBitmapCache;
    final Map<String, Decoder> decoders = new HashMap<>();
    final StripImageRetryState imageRetryState = new StripImageRetryState(StripAdapter.IMAGE_LOAD_RETRY_LIMIT);
    final StripPreviewState previewState = new StripPreviewState();
    final StripPageHeightTracker pageHeightTracker = new StripPageHeightTracker();
    final StripPreloadScheduleState preloadScheduleState = new StripPreloadScheduleState();
    final StripCurrentPageState currentPageState = new StripCurrentPageState();
    final StripAttachedHolderState attachedHolderState = new StripAttachedHolderState();
    final Handler mainHandler = new Handler(Looper.getMainLooper());
}
