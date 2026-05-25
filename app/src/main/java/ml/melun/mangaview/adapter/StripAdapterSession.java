package ml.melun.mangaview.adapter;

import androidx.recyclerview.widget.RecyclerView;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.model.PageItem;

public final class StripAdapterSession {
    private final StripAdapter adapter;

    StripAdapterSession(StripAdapter adapter) {
        this.adapter = adapter;
    }

    public RecyclerView.Adapter<RecyclerView.ViewHolder> recyclerAdapter() {
        return adapter;
    }

    public void setClickListener(StripAdapter.ItemClickListener clickListener) {
        adapter.setClickListener(clickListener);
    }

    public void notifyDataSetChanged() {
        adapter.notifyDataSetChanged();
    }

    public int itemCount() {
        return adapter.getItemCount();
    }

    public int getItemCount() {
        return itemCount();
    }

    public void refreshInfoItems() {
        adapter.refreshInfoItems();
    }

    public boolean hasMangaLoaded(Manga manga) {
        return adapter.hasMangaLoaded(manga);
    }

    public int findFirstPagePosition(Manga manga) {
        return adapter.findFirstPagePosition(manga);
    }

    public int findLastPagePosition(Manga manga) {
        return adapter.findLastPagePosition(manga);
    }

    public int findPagePosition(PageItem page) {
        return adapter.findPagePosition(page);
    }

    public int findExactPagePosition(PageItem page) {
        return adapter.findExactPagePosition(page);
    }

    public PageItem pageAtPosition(int position) {
        return adapter.getPageAtPosition(position);
    }

    public PageItem currentVisiblePage() {
        return adapter.getCurrentVisiblePage();
    }

    public PageItem getCurrentVisiblePage() {
        return currentVisiblePage();
    }

    public void setScrollState(int scrollState) {
        adapter.setScrollState(scrollState);
    }

    public void onScrollAnchor(int adapterPosition, int direction, boolean busy) {
        adapter.onScrollAnchor(adapterPosition, direction, busy);
    }

    public void preloadInitialAroundPage(PageItem page) {
        adapter.preloadInitialAroundPage(page);
    }

    public void preloadAroundPage(PageItem page, int aheadCount) {
        adapter.preloadAroundPage(page, aheadCount);
    }

    public void preferPreviewImages(Manga manga, long durationMs) {
        adapter.preferPreviewImages(manga, durationMs);
    }

    public void appendManga(Manga manga) {
        adapter.appendManga(manga);
    }

    public void appendMangaIncremental(Manga manga) {
        adapter.appendMangaIncremental(manga);
    }

    public int insertManga(Manga manga) {
        return adapter.insertManga(manga);
    }

    public void release() {
        adapter.release();
    }
}
