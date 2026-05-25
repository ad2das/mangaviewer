package ml.melun.mangaview.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import android.view.ViewGroup;
import java.util.List;

import ml.melun.mangaview.activity.InfiniteScrollCallback;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;


public class StripAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    final StripAdapterRuntime runtime;
    final StripAdapterState state;
    final static int MaxStackSize = 3;
    static final int PRELOAD_AHEAD_COUNT = StripImagePolicy.PRELOAD_AHEAD_COUNT;
    static final int INITIAL_PRELOAD_AHEAD_COUNT = StripImagePolicy.INITIAL_PRELOAD_AHEAD_COUNT;
    static final int PRELOAD_TRACK_LIMIT = 128;
    static final int DECODED_PRELOAD_ACTIVE_LIMIT = StripImagePolicy.DECODED_PRELOAD_ACTIVE_LIMIT;
    static final int IMAGE_LOAD_RETRY_LIMIT = 3;
    static final long SCROLL_IDLE_PRELOAD_DELAY_MS = StripImagePolicy.SCROLL_IDLE_PRELOAD_DELAY_MS;
    static final long SCROLL_IDLE_HEIGHT_CORRECTION_DELAY_MS = StripImagePolicy.SCROLL_IDLE_HEIGHT_CORRECTION_DELAY_MS;
    static final int PREVIEW_FULL_REBIND_BATCH = 1;
    static final int PREVIEW_FULL_REBIND_RADIUS = 2;
    static final long PREVIEW_FULL_REBIND_DELAY_MS = 650L;
    static final long PREVIEW_FULL_STABLE_IDLE_MS = 1800L;
    static final int APPEND_BATCH_ITEMS = 4;
    static final long APPEND_BATCH_DELAY_MS = 32L;
    static final boolean AUTO_PROMOTE_PREVIEW_FULL_QUALITY = true;
    static final boolean RENDER_ONLY_PRELOADS = StripImagePolicy.RENDER_ONLY_PRELOADS;
    static final String PAYLOAD_HEIGHT = "height";
    void setScrollState(int scrollState) {
        if(!runtimeReady())
            return;
        runtime.scrollPreloadController.setScrollState(scrollState);
    }

    void setScrollBusy(boolean scrollBusy) {
        if(!runtimeReady())
            return;
        runtime.scrollPreloadController.setScrollBusy(scrollBusy);
    }

    void preferPreviewImages(Manga manga, long durationMs) {
        if(manga == null || durationMs <= 0L)
            return;
        state.previewState.markEpisodePreviewOnly(new PageItem(0, "", manga),
                android.os.SystemClock.uptimeMillis(), durationMs);
    }

    void onScrollAnchor(int adapterPosition, int direction, boolean busy) {
        if(!runtimeReady())
            return;
        runtime.scrollPreloadController.onScrollAnchor(adapterPosition, direction, busy);
    }

    @Override
    public long getItemId(int position) {
        if(state.items == null || position < 0 || position >= state.items.size())
            return RecyclerView.NO_ID;
        return StripStableIdPolicy.itemId(state.items.get(position));
    }

    void appendManga(Manga m){
        runtime.itemListMutator.appendManga(m);
    }

    void appendMangaIncremental(Manga m) {
        runtime.itemListMutator.appendMangaIncremental(m);
    }

    int insertManga(Manga m){
        return runtime.itemListMutator.insertManga(m);
    }

    void refreshInfoItems() {
        runtime.itemListMutator.refreshInfoItems();
    }

    int findLastPagePosition(Manga m) {
        return runtime.itemListMutator.findLastPagePosition(m);
    }

    boolean hasMangaLoaded(Manga m) {
        return runtime.itemListMutator.hasMangaLoaded(m);
    }

    int findFirstPagePosition(Manga m) {
        return runtime.itemListMutator.findFirstPagePosition(m);
    }

    int findFirstMatchingPagePosition(PageItem page) {
        return runtime.itemListMutator.findFirstMatchingPagePosition(page);
    }

    public int findPagePosition(PageItem page) {
        return runtime.itemListMutator.findPagePosition(page);
    }

    public int findExactPagePosition(PageItem page) {
        return runtime.itemListMutator.findExactPagePosition(page);
    }

    void popFirst(){
        runtime.itemListMutator.popFirst();
    }

    void popLast(){
        runtime.itemListMutator.popLast();
    }

    void clearCurrentIfRemoving(int start, int endExclusive) {
        state.currentPageState.clearIfRemoving(state.items, start, endExclusive);
    }

    void clearCurrentPage() {
        state.currentPageState.clear();
    }

    PageItem getPageAtPosition(int position) {
        return runtime.itemListMutator.getPageAtPosition(position);
    }

    StripAdapter(StripAdapterRuntime runtime, StripAdapterState state, Context context, Boolean cut,
                 int width, Title title, InfiniteScrollCallback callback) {
        this.runtime = runtime;
        this.state = state;
    }



    void preloadAll(){
        if(!runtimeReady())
            return;
        runtime.scrollPreloadController.preloadAll();
    }

    void preloadAroundPage(PageItem page, int aheadCount) {
        if(!runtimeReady())
            return;
        runtime.scrollPreloadController.preloadAroundPage(page, aheadCount);
    }

    void preloadInitialAroundPage(PageItem page) {
        if(!runtimeReady())
            return;
        runtime.scrollPreloadController.preloadInitialAroundPage(page);
    }

    final static int IMG = 0;
    final static int INFO = 1;

    @Override
    public int getItemViewType(int position) {
        return StripViewTypePolicy.viewType(state.items, position);
    }

    void removeAll(){
        runtime.itemListMutator.removeAll();
    }

    void release() {
        if(runtime.releaseController == null)
            return;
        runtime.releaseController.release();
    }

    @Override
    @NonNull
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return runtime.viewHolderFactory.create(parent, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int pos) {
        runtime.bindController.bind(holder, pos);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos, @NonNull List<Object> payloads) {
        if(runtime.bindController.bindPayload(holder, pos, payloads))
            return;
        super.onBindViewHolder(holder, pos, payloads);
    }



    void glideBind(StripImageViewHolder holder, int pos){
        runtime.imageBinder.glideBind(holder, pos);
    }

    void logNextPageCacheHitOnce(PageItem item) {
        if(!state.displayState.markNextPageCacheHit(item))
            return;
        ViewerWarmupManager.logMetric("viewer_next_page_cache_hit", 1);
    }

    void trimReusablePageStateToLoadedItems() {
        runtime.reusablePageStateTrimmer.trimToLoadedItems(state.items, runtime.autoCut, runtime.reverse, runtime.width);
    }

    private boolean runtimeReady() {
        return !state.released && runtime.scrollPreloadController != null;
    }

    // total number of rows
    @Override
    public int getItemCount() {
        return state.items == null ? 0 : state.items.size();
    }

    public PageItem getCurrentVisiblePage(){
        return runtime.attachmentController.currentVisiblePage();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        runtime.attachmentController.onViewAttachedToWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        runtime.attachmentController.onViewRecycled(holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        runtime.attachmentController.onViewDetachedFromWindow(holder);
        super.onViewDetachedFromWindow(holder);
    }

    // allows clicks events to be caught
    public void setClickListener(StripAdapter.ItemClickListener itemClickListener) {
        runtime.clickListener = itemClickListener;
    }

    void dispatchItemClick() {
        if(runtime.clickListener != null)
            runtime.clickListener.onItemClick();
    }

    public interface ItemClickListener {
        void onItemClick();
    }

}
