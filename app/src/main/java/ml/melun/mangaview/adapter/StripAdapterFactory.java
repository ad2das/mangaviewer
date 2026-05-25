package ml.melun.mangaview.adapter;

import android.content.Context;

import ml.melun.mangaview.activity.InfiniteScrollCallback;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;

public final class StripAdapterFactory {
    private StripAdapterFactory() {
    }

    public static StripAdapterSession create(Context context, boolean cut, int width,
                                             Title title, InfiniteScrollCallback callback) {
        StripAdapter adapter = new StripAdapter(new StripAdapterRuntime(), new StripAdapterState(),
                context, cut, width, title, callback);
        StripAdapterComponent.install(adapter.runtime, adapter.state, host(adapter), context, cut, width, title, callback,
                new StripAdapterConfig(ml.melun.mangaview.MainApplication.p.getReverse(),
                        ml.melun.mangaview.MainApplication.p.getDataSave()));
        return new StripAdapterSession(adapter);
    }

    private static StripAdapterComponent.Host host(StripAdapter adapter) {
        return new StripAdapterComponent.Host() {
            @Override void dispatchItemClick() { adapter.dispatchItemClick(); }
            @Override void notifyItemChanged(int position) { adapter.notifyItemChanged(position); }
            @Override void notifyItemChanged(int position, Object payload) { adapter.notifyItemChanged(position, payload); }
            @Override void notifyItemRangeInserted(int start, int count) { adapter.notifyItemRangeInserted(start, count); }
            @Override void notifyItemRangeRemoved(int start, int count) { adapter.notifyItemRangeRemoved(start, count); }
            @Override void setHasStableIds(boolean hasStableIds) { adapter.setHasStableIds(hasStableIds); }
            @Override int viewType(int position) { return adapter.getItemViewType(position); }
            @Override void glideBind(StripImageViewHolder holder, int position) { adapter.glideBind(holder, position); }
            @Override void clearCurrentIfRemoving(int start, int endExclusive) {
                adapter.clearCurrentIfRemoving(start, endExclusive);
            }
            @Override void clearCurrentPage() { adapter.clearCurrentPage(); }
            @Override void trimReusablePageStateToLoadedItems() { adapter.trimReusablePageStateToLoadedItems(); }
            @Override int findFirstMatchingPagePosition(PageItem page) {
                return adapter.findFirstMatchingPagePosition(page);
            }
            @Override int findFirstPagePosition(Manga manga) { return adapter.findFirstPagePosition(manga); }
            @Override void logNextPageCacheHitOnce(PageItem item) { adapter.logNextPageCacheHitOnce(item); }
        };
    }
}
