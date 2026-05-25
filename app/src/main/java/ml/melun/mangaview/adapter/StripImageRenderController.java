package ml.melun.mangaview.adapter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;

import java.util.List;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.model.PageItem;

final class StripImageRenderController {
    interface Host {
        boolean released();
        boolean scrollBusy();
        boolean autoCut();
        boolean reverse();
        int width();
        List<Object> items();
        StripScrollPreloadController scrollPreloadController();
    }

    private static final boolean AUTO_PROMOTE_PREVIEW_FULL_QUALITY = true;
    private static final boolean RENDER_ONLY_PRELOADS = StripImagePolicy.RENDER_ONLY_PRELOADS;

    private final Context context;
    private final Host host;
    private final StripDisplayState displayState;
    private final StripPreviewState previewState;
    private final StripPageHeightTracker pageHeightTracker;
    private final StripRebindScheduler rebindScheduler;

    StripImageRenderController(Context context, Host host, StripDisplayState displayState,
                               StripPreviewState previewState, StripPageHeightTracker pageHeightTracker,
                               StripRebindScheduler rebindScheduler) {
        this.context = context;
        this.host = host;
        this.displayState = displayState;
        this.previewState = previewState;
        this.pageHeightTracker = pageHeightTracker;
        this.rebindScheduler = rebindScheduler;
    }

    void bindBitmap(StripImageViewHolder holder, PageItem item, String pageKey, Bitmap bitmap) {
        bindBitmap(holder, item, pageKey, bitmap, false);
    }

    void bindBitmap(StripImageViewHolder holder, PageItem item, String pageKey, Bitmap bitmap, boolean fastDraw) {
        if(!isDisplayBitmapUsable(bitmap)) {
            clearFrameBitmap(holder);
            return;
        }
        boolean hadKnownHeight = hasKnownPageHeight(pageKey);
        rememberPageHeight(pageKey, bitmap);
        applyPageHeight(holder, null, pageKey, !host.scrollBusy() || hadKnownHeight);
        holder.frame.setViewerBitmap(bitmap, fastDraw);
    }

    void bindPreviewBitmap(StripImageViewHolder holder, PageItem item, String pageKey, Bitmap bitmap) {
        previewState.markPreviewOnly(pageKey);
        bindBitmap(holder, item, pageKey, bitmap, true);
        if(!host.scrollBusy() && AUTO_PROMOTE_PREVIEW_FULL_QUALITY)
            schedulePreviewFullRebind(previewFullRebindDelayMs(item));
    }

    void clearFrameBitmap(StripImageViewHolder holder) {
        if(holder != null && holder.frame != null)
            holder.frame.setViewerBitmap(null);
    }

    void rememberPageHeight(String pageKey, Bitmap bitmap) {
        pageHeightTracker.remember(pageKey, bitmap, host.width(), host.scrollBusy());
    }

    void seedPageHeightFromWarmup(PageItem item) {
        if(item == null)
            return;
        String pageKey = pageBindKey(item);
        if(hasKnownPageHeight(pageKey))
            return;
        Bitmap bitmap = ViewerWarmupManager.getDecodedBitmap(item, host.autoCut(), host.reverse(), host.width());
        if(!isDisplayBitmapUsable(bitmap))
            return;
        rememberPageHeight(pageKey, bitmap);
    }

    void applyKnownHeight(StripImageViewHolder holder, PageItem item, String pageKey) {
        applyPageHeight(holder, item, pageKey, true);
    }

    private void applyPageHeight(StripImageViewHolder holder, PageItem item, String pageKey,
                                 boolean allowKnownCorrection) {
        Integer knownHeight = pageHeightTracker.knownHeight(pageKey);
        boolean hasKnownHeight = knownHeight != null && knownHeight > 0;
        if(hasKnownHeight && !allowKnownCorrection)
            pageHeightTracker.markPending(pageKey);
        int targetHeight = pageKey == null
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : (hasKnownHeight && allowKnownCorrection ? knownHeight : estimatedPageHeight(item));
        StripPageHeightApplier.apply(holder, targetHeight);
    }

    boolean hasKnownPageHeight(String pageKey) {
        return pageHeightTracker.hasKnownHeight(pageKey);
    }

    private int estimatedPageHeight(PageItem item) {
        return pageHeightTracker.estimatedHeight(host.autoCut(),
                item == null ? PageItem.FIRST : item.side, host.width());
    }

    void schedulePendingHeightCorrections() {
        rebindScheduler.schedulePendingHeightCorrections();
    }

    void schedulePreviewFullRebind(long extraDelayMs) {
        rebindScheduler.schedulePreviewFullRebind(extraDelayMs);
    }

    long previewFullRebindDelayMs(PageItem item) {
        return rebindScheduler.previewFullRebindDelayMs(item);
    }

    boolean isEpisodePreviewActive(PageItem item) {
        return rebindScheduler.isEpisodePreviewActive(item);
    }

    void clearPageHeightState() {
        pageHeightTracker.clear();
    }

    void markDisplayedAndPreload(StripImageViewHolder holder, PageItem item, String pageKey) {
        if(!isHolderStillBound(holder, item, pageKey))
            return;
        boolean logFirstVisible = displayState.markDisplayedAndShouldLogFirstVisible(pageKey);
        int position = holder.getAdapterPosition();
        if(!RENDER_ONLY_PRELOADS && !host.scrollBusy() && position != RecyclerView.NO_POSITION) {
            StripScrollPreloadController preloadController = host.scrollPreloadController();
            if(preloadController != null)
                preloadController.preloadAheadFromBindPosition(position);
        }
        if(logFirstVisible)
            ViewerWarmupManager.logMetric("viewer_first_visible_ms",
                    android.os.SystemClock.elapsedRealtime() - holder.bindStartedAtMs);
    }

    RequestOptions imageOptions(PageItem item) {
        return StripImageRequestPolicy.imageOptions(context, item, host.scrollBusy(),
                host.autoCut(), host.reverse(), host.width());
    }

    RequestOptions previewOptions(PageItem item) {
        return StripImageRequestPolicy.previewOptions(context, item, host.scrollBusy(),
                host.autoCut(), host.reverse(), host.width());
    }

    Object imageModel(PageItem item) {
        return StripImageRequestPolicy.imageModel(item);
    }

    void clearImageTarget(StripImageViewHolder holder) {
        holder.boundPageKey = null;
        holder.bindGeneration++;
        if(holder.imageTarget == null)
            return;
        CustomTarget<Bitmap> target = holder.imageTarget;
        holder.imageTarget = null;
        clearFrameBitmap(holder);
        if(isContextDestroyed())
            return;
        try {
            Glide.with(holder.frame).clear(target);
        } catch (IllegalArgumentException e) {
            // RecyclerView can recycle children while the viewer Activity is already destroyed.
        }
    }

    boolean isContextDestroyed() {
        if(context instanceof Activity) {
            Activity activity = (Activity) context;
            return activity.isFinishing() || activity.isDestroyed();
        }
        return false;
    }

    boolean canStartGlideRequest() {
        return !host.released() && !isContextDestroyed();
    }

    boolean isActiveHolder(StripImageViewHolder holder, PageItem item, CustomTarget<Bitmap> target,
                           String pageKey, int bindGeneration) {
        return !host.released()
                && holder.imageTarget == target
                && holder.bindGeneration == bindGeneration
                && pageKey != null
                && pageKey.equals(holder.boundPageKey)
                && isHolderStillBound(holder, item, pageKey);
    }

    boolean isHolderStillBound(StripImageViewHolder holder, PageItem item, String pageKey) {
        List<Object> items = host.items();
        if(holder == null || item == null || pageKey == null || items == null)
            return false;
        int position = holder.getAdapterPosition();
        return position != RecyclerView.NO_POSITION
                && position < items.size()
                && items.get(position) instanceof PageItem
                && pageKey.equals(pageBindKey((PageItem) items.get(position)));
    }

    String preloadKey(PageItem page) {
        return StripPageKeyPolicy.preloadKey(page, host.autoCut(), host.reverse(), host.width());
    }

    String decodedCacheKey(PageItem page) {
        return StripPageKeyPolicy.decodedCacheKey(page, host.autoCut(), host.reverse(), host.width());
    }

    String decodedPreviewCacheKey(PageItem page) {
        return StripPageKeyPolicy.decodedPreviewCacheKey(page, host.autoCut(), host.reverse(),
                StripImageRequestPolicy.previewWidth(host.width()));
    }

    String pageBindKey(PageItem page) {
        return StripPageKeyPolicy.bindKey(page, host.autoCut(), host.reverse(), host.width());
    }

    void clearDecodedTarget(CustomTarget<Bitmap> target) {
        try {
            Glide.with(context).clear(target);
        } catch (IllegalArgumentException ignored) {
        }
    }

    static boolean isDisplayBitmapUsable(Bitmap bitmap) {
        return StripBitmapDisplayPolicy.isDisplayBitmapUsable(bitmap);
    }

    static Bitmap copyBitmapForDisplay(Bitmap bitmap) {
        return StripBitmapDisplayPolicy.copyBitmapForDisplay(bitmap);
    }

    static int estimatedPageHeight(boolean autoCut, int side, int width,
                                   long pageHeightTotal, int pageHeightSampleCount) {
        return StripPageHeightTracker.estimatedHeight(autoCut, side, width,
                pageHeightTotal, pageHeightSampleCount);
    }

    static int previewWidth(int viewerWidth) {
        return StripImageRequestPolicy.previewWidth(viewerWidth);
    }

    static DiskCacheStrategy viewerDiskCacheStrategy(boolean scrollBusy) {
        return StripImageRequestPolicy.viewerDiskCacheStrategy(scrollBusy);
    }
}
