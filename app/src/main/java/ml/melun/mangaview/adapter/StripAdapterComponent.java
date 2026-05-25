package ml.melun.mangaview.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;

import com.bumptech.glide.Priority;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;

import java.util.List;

import ml.melun.mangaview.activity.InfiniteScrollCallback;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;

final class StripAdapterComponent {
    abstract static class Host {
        abstract void dispatchItemClick();
        abstract void notifyItemChanged(int position);
        abstract void notifyItemChanged(int position, Object payload);
        abstract void notifyItemRangeInserted(int start, int count);
        abstract void notifyItemRangeRemoved(int start, int count);
        abstract void setHasStableIds(boolean hasStableIds);
        abstract int viewType(int position);
        abstract void glideBind(StripImageViewHolder holder, int position);
        abstract void clearCurrentIfRemoving(int start, int endExclusive);
        abstract void clearCurrentPage();
        abstract void trimReusablePageStateToLoadedItems();
        abstract int findFirstMatchingPagePosition(PageItem page);
        abstract int findFirstPagePosition(Manga manga);
        abstract void logNextPageCacheHitOnce(PageItem item);
    }

    private StripAdapterComponent() {
    }

    static void install(StripAdapterRuntime runtime, StripAdapterState state, Host host, Context context, Boolean cut, int width,
                        Title title, InfiniteScrollCallback callback, StripAdapterConfig config) {
        runtime.autoCut = cut;
        runtime.callback = callback;
        runtime.config = config;
        LayoutInflater inflater = LayoutInflater.from(context);
        runtime.viewHolderFactory = new StripViewHolderFactory(inflater, host::dispatchItemClick,
                host::notifyItemChanged);
        runtime.mainContext = context;
        runtime.reverse = config.reverse;
        runtime.width = width;
        runtime.title = title;
        state.decodedBitmapCache = new StripDecodedBitmapCache(config.dataSave);
        runtime.reusablePageStateTrimmer = reusablePageStateTrimmer(runtime, state);
        runtime.decodedPreloadDelegate = decodedPreloadDelegate(runtime, state);
        runtime.rebindScheduler = rebindScheduler(runtime, state, host);
        runtime.imageRenderController = imageRenderController(runtime, state);
        runtime.itemListMutator = itemListMutator(runtime, state, host);
        runtime.imageBinder = imageBinder(runtime, state, host);
        runtime.bindController = new StripBindController(state, runtime, host);
        runtime.preloadRequester = preloadRequester(runtime, state);
        runtime.preloadWindowRunner = preloadWindowRunner(runtime, state);
        runtime.preloadScheduler = preloadScheduler(runtime, state);
        runtime.scrollPreloadController = scrollPreloadController(runtime, state, host);
        runtime.attachmentController = attachmentController(runtime, state, callback);
        runtime.releaseController = new StripReleaseController(state, runtime);
        host.setHasStableIds(true);
    }

    private static StripReusablePageStateTrimmer reusablePageStateTrimmer(StripAdapterRuntime runtime, StripAdapterState state) {
        return new StripReusablePageStateTrimmer(state.preloadState, state.displayState,
                state.imageRetryState, state.previewState, state.pageHeightTracker,
                state.decodedBitmapCache, state.decoders,
                new StripReusablePageStateTrimmer.Callbacks() {
            @Override public boolean isContextDestroyed() {
                return runtime.imageRenderController.isContextDestroyed();
            }

            @Override public void clearDecodedTarget(CustomTarget<Bitmap> target) {
                runtime.imageRenderController.clearDecodedTarget(target);
            }
        });
    }

    private static StripDecodedPreloadDelegate decodedPreloadDelegate(StripAdapterRuntime runtime, StripAdapterState state) {
        return new StripDecodedPreloadDelegate(state.preloadState, state.preloadScheduleState,
                state.decodedBitmapCache, new StripDecodedPreloadDelegate.Callbacks() {
            @Override public boolean isContextDestroyed() {
                return runtime.imageRenderController.isContextDestroyed();
            }

            @Override public Bitmap copyForDisplay(Bitmap resource) {
                return StripImageRenderController.copyBitmapForDisplay(resource);
            }

            @Override public void rememberHeight(String heightKey, Bitmap bitmap) {
                runtime.imageRenderController.rememberPageHeight(heightKey, bitmap);
            }
        });
    }

    private static StripRebindScheduler rebindScheduler(StripAdapterRuntime runtime, StripAdapterState state, Host host) {
        return new StripRebindScheduler(state.mainHandler, state.pageHeightTracker, state.previewState,
                state.preloadScheduleState, new StripRebindScheduler.Callbacks() {
            @Override public boolean isReleased() { return state.released; }
            @Override public boolean isScrollBusy() { return state.scrollBusy; }
            @Override public List<Object> items() { return state.items; }
            @Override public String pageKey(PageItem page) {
                return runtime.imageRenderController.pageBindKey(page);
            }

            @Override public void notifyHeightChanged(int position) {
                host.notifyItemChanged(position, StripAdapter.PAYLOAD_HEIGHT);
            }

            @Override public void notifyFullRebind(int position) {
                host.notifyItemChanged(position);
            }
        }, StripAdapter.SCROLL_IDLE_HEIGHT_CORRECTION_DELAY_MS, StripAdapter.PREVIEW_FULL_REBIND_BATCH,
                StripAdapter.PREVIEW_FULL_REBIND_RADIUS, StripAdapter.PREVIEW_FULL_REBIND_DELAY_MS,
                StripAdapter.PREVIEW_FULL_STABLE_IDLE_MS);
    }

    private static StripImageRenderController imageRenderController(StripAdapterRuntime runtime, StripAdapterState state) {
        return new StripImageRenderController(runtime.mainContext, new StripImageRenderController.Host() {
            @Override public boolean released() { return state.released; }
            @Override public boolean scrollBusy() { return state.scrollBusy; }
            @Override public boolean autoCut() { return runtime.autoCut; }
            @Override public boolean reverse() { return runtime.reverse; }
            @Override public int width() { return runtime.width; }
            @Override public List<Object> items() { return state.items; }

            @Override public StripScrollPreloadController scrollPreloadController() {
                return runtime.scrollPreloadController;
            }
        }, state.displayState, state.previewState, state.pageHeightTracker, runtime.rebindScheduler);
    }

    private static StripItemListMutator itemListMutator(StripAdapterRuntime runtime, StripAdapterState state, Host host) {
        return new StripItemListMutator(runtime.mainContext, state.mainHandler,
                new StripItemListMutator.Callbacks() {
            @Override public boolean autoCut() { return runtime.autoCut; }
            @Override public List<Object> items() { return state.items; }
            @Override public void setItems(List<Object> items) { state.items = items; }
            @Override public void setCount(int count) { state.count = count; }

            @Override public void seedPageHeightFromWarmup(PageItem item) {
                runtime.imageRenderController.seedPageHeightFromWarmup(item);
            }

            @Override public void clearCurrentIfRemoving(int start, int endExclusive) {
                host.clearCurrentIfRemoving(start, endExclusive);
            }

            @Override public void clearCurrentPage() {
                host.clearCurrentPage();
            }

            @Override public void trimReusablePageStateToLoadedItems() {
                host.trimReusablePageStateToLoadedItems();
            }

            @Override public void clearReusablePageState() {
                runtime.reusablePageStateTrimmer.clearAll();
            }

            @Override public void notifyItemRangeInserted(int start, int count) {
                host.notifyItemRangeInserted(start, count);
            }

            @Override public void notifyItemRangeRemoved(int start, int count) {
                host.notifyItemRangeRemoved(start, count);
            }

            @Override public void notifyItemChanged(int position) {
                host.notifyItemChanged(position);
            }
        }, StripAdapter.MaxStackSize, StripAdapter.APPEND_BATCH_ITEMS, StripAdapter.APPEND_BATCH_DELAY_MS);
    }

    private static StripImageBinder imageBinder(StripAdapterRuntime runtime, StripAdapterState state, Host host) {
        return new StripImageBinder(state.mainHandler, state.decodedBitmapCache, state.previewState,
                state.imageRetryState, new StripImageBinder.Callbacks() {
            @Override public PageItem itemAt(int position) { return (PageItem) state.items.get(position); }
            @Override public Object imageModel(PageItem item) { return runtime.imageRenderController.imageModel(item); }
            @Override public String pageBindKey(PageItem item) { return runtime.imageRenderController.pageBindKey(item); }

            @Override public String decodedCacheKey(PageItem item) {
                return runtime.imageRenderController.decodedCacheKey(item);
            }

            @Override public String decodedPreviewCacheKey(PageItem item) {
                return runtime.imageRenderController.decodedPreviewCacheKey(item);
            }

            @Override public RequestOptions imageOptions(PageItem item) {
                return runtime.imageRenderController.imageOptions(item);
            }

            @Override public RequestOptions previewOptions(PageItem item) {
                return runtime.imageRenderController.previewOptions(item);
            }

            @Override public boolean isScrollBusy() { return state.scrollBusy; }
            @Override public boolean isReleased() { return state.released; }
            @Override public boolean autoPromotePreviewFullQuality() {
                return StripAdapter.AUTO_PROMOTE_PREVIEW_FULL_QUALITY;
            }
            @Override public boolean autoCut() { return runtime.autoCut; }
            @Override public boolean reverse() { return runtime.reverse; }
            @Override public int width() { return runtime.width; }

            @Override public boolean isEpisodePreviewActive(PageItem item) {
                return runtime.imageRenderController.isEpisodePreviewActive(item);
            }

            @Override public boolean isHolderStillBound(StripImageViewHolder holder, PageItem item, String pageKey) {
                return runtime.imageRenderController.isHolderStillBound(holder, item, pageKey);
            }

            @Override public boolean isActiveHolder(StripImageViewHolder holder, PageItem item,
                                                    StripBitmapBindTarget target, String pageKey, int bindGeneration) {
                return runtime.imageRenderController.isActiveHolder(holder, item, target, pageKey, bindGeneration);
            }

            @Override public boolean isBitmapUsable(Bitmap bitmap) {
                return StripImageRenderController.isDisplayBitmapUsable(bitmap);
            }

            @Override public void clearImageTarget(StripImageViewHolder holder) {
                runtime.imageRenderController.clearImageTarget(holder);
            }

            @Override public void applyKnownHeight(StripImageViewHolder holder, PageItem item, String pageKey) {
                runtime.imageRenderController.applyKnownHeight(holder, item, pageKey);
            }

            @Override public void clearFrameBitmap(StripImageViewHolder holder) {
                runtime.imageRenderController.clearFrameBitmap(holder);
            }

            @Override public void bindBitmap(StripImageViewHolder holder, PageItem item, String pageKey, Bitmap bitmap) {
                runtime.imageRenderController.bindBitmap(holder, item, pageKey, bitmap);
            }

            @Override public void bindPreviewBitmap(StripImageViewHolder holder, PageItem item,
                                                    String pageKey, Bitmap bitmap) {
                runtime.imageRenderController.bindPreviewBitmap(holder, item, pageKey, bitmap);
            }

            @Override public void markDisplayedAndPreload(StripImageViewHolder holder, PageItem item, String pageKey) {
                runtime.imageRenderController.markDisplayedAndPreload(holder, item, pageKey);
            }

            @Override public void logNextPageCacheHitOnce(PageItem item) {
                host.logNextPageCacheHitOnce(item);
            }

            @Override public void schedulePreviewFullRebind(long extraDelayMs) {
                runtime.imageRenderController.schedulePreviewFullRebind(extraDelayMs);
            }

            @Override public long previewFullRebindDelayMs(PageItem item) {
                return runtime.imageRenderController.previewFullRebindDelayMs(item);
            }

            @Override public void notifyItemChanged(int position) {
                host.notifyItemChanged(position);
            }
        });
    }

    private static StripPreloadScheduler preloadScheduler(StripAdapterRuntime runtime, StripAdapterState state) {
        return new StripPreloadScheduler(state.mainHandler, state.preloadScheduleState,
                runtime.preloadWindowRunner, new StripPreloadScheduler.Callbacks() {
            @Override public boolean canStart() {
                return runtime.imageRenderController.canStartGlideRequest();
            }

            @Override public boolean isScrollBusy() { return state.scrollBusy; }
            @Override public boolean isDataSave() { return runtime.config.dataSave; }
            @Override public List<Object> items() { return state.items; }
        });
    }

    private static StripPreloadWindowRunner preloadWindowRunner(StripAdapterRuntime runtime, StripAdapterState state) {
        return new StripPreloadWindowRunner(new StripPreloadWindowRunner.Delegate() {
            @Override public boolean canStart() { return runtime.imageRenderController.canStartGlideRequest(); }
            @Override public long generation() { return state.preloadScheduleState.generation(); }

            @Override public void preloadDecoded(PageItem page, Priority priority, long generation, boolean preview) {
                runtime.preloadRequester.preloadPageIntoDecodedCache(page, priority, generation, preview);
            }

            @Override public void preloadSource(PageItem page, Priority priority) {
                runtime.preloadRequester.preloadPageSourceOnly(page, priority);
            }
        });
    }

    private static StripPreloadRequester preloadRequester(StripAdapterRuntime runtime, StripAdapterState state) {
        return new StripPreloadRequester(runtime.mainContext, state.preloadState,
                state.decodedBitmapCache, runtime.decodedPreloadDelegate,
                new StripPreloadRequester.Callbacks() {
            @Override public boolean canStart() {
                return runtime.imageRenderController.canStartGlideRequest();
            }

            @Override public boolean isDataSave() { return runtime.config.dataSave; }
            @Override public Object imageModel(PageItem page) { return runtime.imageRenderController.imageModel(page); }
            @Override public RequestOptions imageOptions(PageItem page) {
                return runtime.imageRenderController.imageOptions(page);
            }

            @Override public RequestOptions previewOptions(PageItem page) {
                return runtime.imageRenderController.previewOptions(page);
            }

            @Override public String preloadKey(PageItem page) {
                return runtime.imageRenderController.preloadKey(page);
            }

            @Override public String decodedCacheKey(PageItem page) {
                return runtime.imageRenderController.decodedCacheKey(page);
            }

            @Override public String decodedPreviewCacheKey(PageItem page) {
                return runtime.imageRenderController.decodedPreviewCacheKey(page);
            }
        }, StripAdapter.DECODED_PRELOAD_ACTIVE_LIMIT);
    }

    private static StripScrollPreloadController scrollPreloadController(StripAdapterRuntime runtime, StripAdapterState state, Host host) {
        return new StripScrollPreloadController(new StripScrollPreloadController.Host() {
            @Override public boolean released() { return state.released; }
            @Override public boolean scrollBusy() { return state.scrollBusy; }
            @Override public void setScrollBusy(boolean scrollBusy) { state.scrollBusy = scrollBusy; }
            @Override public List<Object> items() { return state.items; }

            @Override public int findFirstMatchingPagePosition(PageItem page) {
                return host.findFirstMatchingPagePosition(page);
            }

            @Override public int findFirstPagePosition(Manga manga) {
                return host.findFirstPagePosition(manga);
            }

            @Override public void schedulePendingHeightCorrections() {
                runtime.imageRenderController.schedulePendingHeightCorrections();
            }

            @Override public void schedulePreviewFullRebind(long extraDelayMs) {
                runtime.imageRenderController.schedulePreviewFullRebind(extraDelayMs);
            }
        }, state.preloadScheduleState, state.attachedHolderState, state.previewState,
                runtime.preloadScheduler, runtime.preloadRequester);
    }

    private static StripAttachmentController attachmentController(StripAdapterRuntime runtime, StripAdapterState state,
                                                                  InfiniteScrollCallback callback) {
        return new StripAttachmentController(runtime.mainContext, callback,
                new StripAttachmentController.Host() {
            @Override public List<Object> items() { return state.items; }
            @Override public boolean scrollBusy() { return state.scrollBusy; }
            @Override public String pageBindKey(PageItem page) {
                return runtime.imageRenderController.pageBindKey(page);
            }

            @Override public void clearImageTarget(StripImageViewHolder holder) {
                runtime.imageRenderController.clearImageTarget(holder);
            }

            @Override public void applyKnownHeight(StripImageViewHolder holder, PageItem item, String pageKey) {
                runtime.imageRenderController.applyKnownHeight(holder, item, pageKey);
            }

            @Override public void clearFrameBitmap(StripImageViewHolder holder) {
                runtime.imageRenderController.clearFrameBitmap(holder);
            }
        }, state.currentPageState, state.attachedHolderState, state.displayState,
                state.previewState, runtime.scrollPreloadController);
    }
}
