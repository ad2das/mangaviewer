package ml.melun.mangaview.adapter;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ml.melun.mangaview.activity.InfiniteScrollCallback;
import ml.melun.mangaview.model.PageItem;

final class StripAttachmentController {
    interface Host {
        List<Object> items();
        boolean scrollBusy();
        String pageBindKey(PageItem page);
        void clearImageTarget(StripImageViewHolder holder);
        void applyKnownHeight(StripImageViewHolder holder, PageItem item, String pageKey);
        void clearFrameBitmap(StripImageViewHolder holder);
    }

    private final Context context;
    private final InfiniteScrollCallback callback;
    private final Host host;
    private final StripCurrentPageState currentPageState;
    private final StripAttachedHolderState attachedHolderState;
    private final StripDisplayState displayState;
    private final StripPreviewState previewState;
    private final StripScrollPreloadController scrollPreloadController;

    StripAttachmentController(Context context, InfiniteScrollCallback callback, Host host,
                              StripCurrentPageState currentPageState,
                              StripAttachedHolderState attachedHolderState,
                              StripDisplayState displayState, StripPreviewState previewState,
                              StripScrollPreloadController scrollPreloadController) {
        this.context = context;
        this.callback = callback;
        this.host = host;
        this.currentPageState = currentPageState;
        this.attachedHolderState = attachedHolderState;
        this.displayState = displayState;
        this.previewState = previewState;
        this.scrollPreloadController = scrollPreloadController;
    }

    PageItem currentVisiblePage() {
        return currentPageState.currentVisiblePage(host.items());
    }

    void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
        int layoutPos = holder.getAdapterPosition();
        List<Object> items = host.items();
        if(items == null || layoutPos == RecyclerView.NO_POSITION || layoutPos >= items.size())
            return;
        Object row = items.get(layoutPos);
        if(row instanceof PageItem) {
            PageItem page = (PageItem) row;
            if(!isAttachableImagePage(page))
                return;
            String pageKey = host.pageBindKey(page);
            if(holder instanceof StripImageViewHolder) {
                StripImageViewHolder imageHolder = (StripImageViewHolder) holder;
                attachedHolderState.add(imageHolder);
                imageHolder.frame.setFastBitmapDraw(host.scrollBusy() || previewState.isPreviewOnly(pageKey));
            }
            boolean shouldUpdateCurrentInfo = currentPageState.attachPage(page);
            if(!host.scrollBusy() && displayState.containsDisplayed(pageKey))
                scrollPreloadController.preloadAroundScrollPosition(layoutPos);
            if(shouldUpdateCurrentInfo)
                dispatchPageAttached(page);
        } else if(row instanceof InfoItem) {
            currentPageState.attachInfo();
        }
    }

    void onViewRecycled(RecyclerView.ViewHolder holder) {
        if(holder instanceof StripImageViewHolder) {
            StripImageViewHolder imageHolder = (StripImageViewHolder) holder;
            attachedHolderState.remove(imageHolder);
            host.clearImageTarget(imageHolder);
            imageHolder.boundPageKey = null;
            host.applyKnownHeight(imageHolder, null, null);
            host.clearFrameBitmap(imageHolder);
            imageHolder.refresh.setVisibility(View.GONE);
        }
    }

    void onViewDetachedFromWindow(RecyclerView.ViewHolder holder) {
        if(holder instanceof StripImageViewHolder)
            attachedHolderState.remove((StripImageViewHolder) holder);
    }

    static boolean isAttachableImagePage(PageItem item) {
        return item != null && item.manga != null;
    }

    private void dispatchPageAttached(PageItem page) {
        if(callback != null)
            callback.updateInfo(page.manga);
    }
}
