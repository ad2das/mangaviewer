package ml.melun.mangaview.adapter;

import android.content.Context;
import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;

import ml.melun.mangaview.R;
import ml.melun.mangaview.activity.ViewerActivity;
import ml.melun.mangaview.glide.ViewerPageTransformation;
import ml.melun.mangaview.glide.ViewerPreloadPolicy;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.interfaces.StringCallback;
import ml.melun.mangaview.mangaview.Decoder;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;
import ml.melun.mangaview.repository.MangaRepository;


public class StripAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final LayoutInflater mInflater;
    private final Context mainContext;
    private StripAdapter.ItemClickListener mClickListener;
    boolean autoCut;
    boolean reverse;
    int width;
    int count = 0;
    final static int MaxStackSize = 3;
    private static final int PRELOAD_AHEAD_COUNT = 6;
    private static final int DATA_SAVE_PRELOAD_AHEAD_COUNT = 4;
    private static final int INITIAL_PRELOAD_AHEAD_COUNT = 5;
    private static final int PRELOAD_TRACK_LIMIT = 500;
    private static final int DECODED_PRELOAD_ACTIVE_LIMIT = 2;
    private static final int IMAGE_LOAD_RETRY_LIMIT = 3;
    private static final long SCROLL_IDLE_PRELOAD_DELAY_MS = 180L;
    private static final long SCROLL_IDLE_HEIGHT_CORRECTION_DELAY_MS = 240L;
    private static final String PAYLOAD_HEIGHT = "height";
    ViewerActivity.InfiniteScrollCallback callback;
    Title title;

    List<Object> items;
    private final Set<String> preloadedImages = new LinkedHashSet<>();
    private final Set<String> displayedImages = new LinkedHashSet<>();
    private final LruCache<String, CachedBitmap> decodedBitmapCache;
    private final Map<String, Decoder> decoders = new HashMap<>();
    private final Map<String, CustomTarget<Bitmap>> decodedPreloadTargets = new HashMap<>();
    private final Map<String, Integer> pageHeights = new HashMap<>();
    private final Map<String, Integer> failedImageRetries = new HashMap<>();
    private final Set<String> pendingHeightCorrections = new LinkedHashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int lastPreloadAnchorPosition = RecyclerView.NO_POSITION;
    private int pendingPreloadPosition = RecyclerView.NO_POSITION;
    private int pendingPreloadDirection = 1;
    private int lastScrollDirection = 1;
    private long pageHeightTotal = 0L;
    private int pageHeightSampleCount = 0;
    private long preloadGeneration = 0L;
    private long idlePreloadReadyAtMs = 0L;
    private boolean pendingPreloadScheduled = false;
    private boolean pendingHeightCorrectionScheduled = false;
    private boolean scrollBusy = false;
    private boolean released = false;
    private boolean firstVisibleLogged = false;

    public List<Object> getItems(){
        return items;
    }

    public void setScrollBusy(boolean scrollBusy) {
        if(released)
            return;
        boolean changed = this.scrollBusy != scrollBusy;
        this.scrollBusy = scrollBusy;
        if(changed && scrollBusy) {
            preloadGeneration++;
            clearDecodedPreloadTargets();
        } else if(changed) {
            idlePreloadReadyAtMs = android.os.SystemClock.uptimeMillis() + scrollIdlePreloadDelayMs();
        }
        if(!scrollBusy) {
            schedulePendingHeightCorrections();
            if(pendingPreloadPosition != RecyclerView.NO_POSITION)
                schedulePreloadAroundScrollPosition(pendingPreloadPosition);
        }
    }

    public void onScrollAnchor(int adapterPosition, int direction, boolean busy) {
        if(released || adapterPosition == RecyclerView.NO_POSITION)
            return;
        int normalizedDirection = direction < 0 ? -1 : 1;
        boolean anchorChanged = pendingPreloadPosition != adapterPosition
                || lastPreloadAnchorPosition != adapterPosition
                || lastScrollDirection != normalizedDirection;
        scrollBusy = busy;
        lastScrollDirection = normalizedDirection;
        pendingPreloadDirection = normalizedDirection;
        pendingPreloadPosition = adapterPosition;
        if(anchorChanged)
            preloadGeneration++;
        if(!busy && !isIdlePreloadReady()) {
            schedulePreloadAroundScrollPosition(adapterPosition);
            return;
        }
        preloadCriticalWindow(adapterPosition, normalizedDirection, preloadGeneration);
        if(!busy)
            schedulePreloadAroundScrollPosition(adapterPosition);
    }


    public static class InfoItem{
        public InfoItem(Manga prev, Manga next) {
            if(next == null && prev != null)
                this.next = prev.nextEp();
            else
                this.next = next;
            if(prev == null && next != null)
                this.prev = next.prevEp();
            else
                this.prev = prev;
        }

        public Manga next;
        public Manga prev;
    }

    @Override
    public long getItemId(int position) {
        if(items == null || position < 0 || position >= items.size())
            return RecyclerView.NO_ID;
        Object o = items.get(position);
        if(o instanceof PageItem)
            return pageStableId((PageItem)o);
        if(o instanceof InfoItem)
            return infoStableId((InfoItem)o);
        return RecyclerView.NO_ID;
    }

    private long pageStableId(PageItem item) {
        long episode = episodeStableId(item.manga);
        long image = item.img == null ? 0L : item.img.hashCode();
        return (episode * 1000003L) ^ (((long)item.index) << 17) ^ (((long)item.side) << 1) ^ image;
    }

    private long infoStableId(InfoItem item) {
        return Long.MIN_VALUE
                ^ (episodeStableId(item.prev) * 1000003L)
                ^ episodeStableId(item.next);
    }

    private long episodeStableId(Manga manga) {
        if(manga == null)
            return 0L;
        return (((long)manga.getBaseMode()) << 48)
                ^ (((long)manga.getTitleId() & 0xffffL) << 32)
                ^ (manga.getId() & 0xffffffffL);
    }

    public void appendManga(Manga m){
        if(items == null)
            items = new ArrayList<>();
        if(hasMangaLoaded(m))
            return;
        int prevsize = items.size();
        List<String> imgs = MangaRepository.imageUrls(m, mainContext);
        if(imgs == null || imgs.size() == 0)
            return;
        if(items.size() == 0)
            items.add(new InfoItem(m.prevEp(), m));
        for(int i=0; i<imgs.size(); i++){
            items.add(new PageItem(i,imgs.get(i),m));
            if(autoCut)
                items.add(new PageItem(i,imgs.get(i),m,PageItem.SECOND));
        }
        items.add(new InfoItem(m, m.nextEp()));
        notifyItemRangeInserted(prevsize, items.size()-prevsize);
        count = loadedEpisodeCount();
        if(count > MaxStackSize){
            popFirst();
        }
    }

    public int insertManga(Manga m){
        if(items == null || items.size() == 0) {
            appendManga(m);
            return 0;
        }
        if(hasMangaLoaded(m))
            return 0;
        int prevsize = items.size();
        List<String> imgs = MangaRepository.imageUrls(m, mainContext);
        if(imgs == null || imgs.size() == 0)
            return 0;
        for(int i=imgs.size()-1; i>=0; i--){
            if(autoCut)
                items.add(0, new PageItem(i,imgs.get(i),m,PageItem.SECOND));
            items.add(0,new PageItem(i,imgs.get(i),m));
        }
        items.add(0, new InfoItem(null, m));

        int inserted = items.size()-prevsize;
        notifyItemRangeInserted(0, inserted);
        count = loadedEpisodeCount();

        if(count > MaxStackSize){
            popLast();
        }
        return inserted;
    }

    public void refreshInfoItems() {
        if(items == null)
            return;
        for(int i = 0; i < items.size(); i++) {
            if(items.get(i) instanceof InfoItem)
                notifyItemChanged(i);
        }
    }

    public int findLastPagePosition(Manga m) {
        if(m == null || items == null)
            return RecyclerView.NO_POSITION;
        for(int i = items.size() - 1; i >= 0; i--) {
            Object item = items.get(i);
            if(item instanceof PageItem && sameManga(((PageItem)item).manga, m))
                return i;
        }
        return RecyclerView.NO_POSITION;
    }

    public boolean hasMangaLoaded(Manga m) {
        return findFirstPagePosition(m) != RecyclerView.NO_POSITION;
    }

    public int findFirstPagePosition(Manga m) {
        if(m == null || items == null)
            return RecyclerView.NO_POSITION;
        for(int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if(item instanceof PageItem && sameManga(((PageItem)item).manga, m))
                return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private int findFirstMatchingPagePosition(PageItem page) {
        if(page == null || items == null)
            return RecyclerView.NO_POSITION;
        for(int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if(item instanceof PageItem) {
                PageItem other = (PageItem) item;
                if(sameManga(other.manga, page.manga)
                        && other.index == page.index
                        && other.side == page.side
                        && (page.img == null || page.img.length() == 0 || page.img.equals(other.img)))
                    return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    public int findPagePosition(PageItem page) {
        int position = findFirstMatchingPagePosition(page);
        if(position != RecyclerView.NO_POSITION)
            return position;
        return page == null ? RecyclerView.NO_POSITION : findFirstPagePosition(page.manga);
    }

    public int findExactPagePosition(PageItem page) {
        return findFirstMatchingPagePosition(page);
    }

    private boolean sameManga(Manga a, Manga b) {
        return a != null && b != null
                && a.getId() == b.getId()
                && a.getTitleId() == b.getTitleId()
                && a.getBaseMode() == b.getBaseMode();
    }

    private int loadedEpisodeCount() {
        if(items == null)
            return 0;
        Set<String> loaded = new LinkedHashSet<>();
        for(Object item : items) {
            if(item instanceof PageItem) {
                Manga manga = ((PageItem) item).manga;
                if(manga != null)
                    loaded.add(PageItem.episodeKey(manga));
            }
        }
        return loaded.size();
    }

    public void popFirst(){
        int size = 0;
        for(int i=1; i<items.size(); i++){
            if(items.get(i) instanceof InfoItem){
                size = i;
                break;
            }
        }
        if (size > 0) {
            clearCurrentIfRemoving(0, size);
            items.subList(0, size).clear();
            count = loadedEpisodeCount();
            trimReusablePageStateToLoadedItems();
            notifyItemRangeRemoved(0,size);
        }
    }

    public void popLast(){
        int originalSize = items.size();
        int rsize = -1;
        for(int i=originalSize-2; i>=0; i--){
            if(items.get(i) instanceof InfoItem){
                rsize = i;
                break;
            }
        }
        if (rsize >= 0 && originalSize > rsize + 1) {
            int removeStart = rsize + 1;
            int removeCount = originalSize - removeStart;
            clearCurrentIfRemoving(removeStart, originalSize);
            items.subList(removeStart, originalSize).clear();
            count = loadedEpisodeCount();
            trimReusablePageStateToLoadedItems();
            notifyItemRangeRemoved(removeStart, removeCount);
        }
    }

    private void clearCurrentIfRemoving(int start, int endExclusive) {
        if(current == null || items == null)
            return;
        int end = Math.min(endExclusive, items.size());
        for(int i = Math.max(0, start); i < end; i++) {
            if(items.get(i) == current) {
                clearCurrentPage();
                return;
            }
        }
    }

    private void clearCurrentPage() {
        current = null;
        currentMangaId = -1;
        needUpdate = true;
    }

    private boolean containsCurrentPage() {
        if(current == null || items == null)
            return false;
        for(Object item : items) {
            if(item == current)
                return true;
        }
        return false;
    }

    public PageItem getPageAtPosition(int position) {
        if(items == null || position < 0 || position >= items.size())
            return null;
        Object item = items.get(position);
        return item instanceof PageItem ? (PageItem)item : null;
    }

    // data is passed into the constructor
    public StripAdapter(Context context, Manga manga, Boolean cut, int width, Title title, ViewerActivity.InfiniteScrollCallback callback) {
        autoCut = cut;
        this.callback = callback;
        this.mInflater = LayoutInflater.from(context);
        mainContext = context;
        reverse = p.getReverse();
        this.width = width;
        this.title = title;
        this.decodedBitmapCache = new LruCache<String, CachedBitmap>(decodedCacheSizeKb()) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull CachedBitmap value) {
                return value.sizeKb;
            }
        };
        setHasStableIds(true);
        appendManga(manga);
    }



    public void preloadAll(){
        for(Object o : items) {
            if(o instanceof PageItem) {
                preloadPage((PageItem) o);
            }
        }
    }

    public void preloadAroundPage(PageItem page, int aheadCount) {
        if(page == null || items == null)
            return;
        int start = findFirstMatchingPagePosition(page);
        if(start == RecyclerView.NO_POSITION)
            return;
        ViewerPreloadPolicy.Window policy = ViewerPreloadPolicy.scrollAheadWindow(p.getDataSave());
        preloadDirectionalWindow(start, 1, clampWindow(policy, aheadCount));
        preloadDirectionalWindow(start - 1, -1, reversePreloadWindow());
    }

    public void preloadInitialAroundPage(PageItem page) {
        if(page == null || items == null)
            return;
        int start = findFirstMatchingPagePosition(page);
        if(start == RecyclerView.NO_POSITION)
            start = findFirstPagePosition(page.manga);
        if(start == RecyclerView.NO_POSITION)
            return;
        preloadDirectionalWindow(start, 1, ViewerPreloadPolicy.initialScrollWindow(p.getDataSave()));
        preloadDirectionalWindow(start - 1, -1, reversePreloadWindow());
    }

    final static int IMG = 0;
    final static int INFO = 1;

    @Override
    public int getItemViewType(int position) {
        if(items == null || position < 0 || position >= items.size())
            return INFO;
        if(items.get(position) instanceof PageItem)
            return IMG;
        else if(items.get(position) instanceof InfoItem)
            return INFO;
        else
            return -1;
    }

    public void removeAll(){
        if(items == null || items.size() == 0)
            return;
        int size = items.size();
        items.clear();
        preloadedImages.clear();
        decodedBitmapCache.evictAll();
        clearPageHeightState();
        decoders.clear();
        clearDecodedPreloadTargets();
        clearCurrentPage();
        count = 0;
        notifyItemRangeRemoved(0, size);
    }

    public void release() {
        released = true;
        pendingPreloadPosition = RecyclerView.NO_POSITION;
        pendingPreloadScheduled = false;
        pendingHeightCorrectionScheduled = false;
        mainHandler.removeCallbacksAndMessages(null);
        clearDecodedPageState();
    }

    @Override
    @NonNull
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if(viewType == IMG) {
            View view = mInflater.inflate(R.layout.item_strip, parent, false);
            return new ImgViewHolder(view);
        }else{
            //INFO
            View view = mInflater.inflate(R.layout.item_strip_info, parent, false);
            return new InfoViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int pos) {
        if(items == null || pos < 0 || pos >= items.size())
            return;
        int type = getItemViewType(pos);
        if(type == IMG) {
            glideBind((ImgViewHolder)holder, pos);
        }else if(type == INFO){
            //INFO
            ((InfoViewHolder) holder).loading.setVisibility(View.INVISIBLE);
            InfoItem info = (InfoItem)items.get(pos);
            Manga prev = info.prev;
            Manga next = info.next;

            if(prev == null && next != null){
                prev = next.prevEp();
            }else if(next == null && prev != null){
                next = prev.nextEp();
            }

            ((InfoViewHolder) holder).prevInfo.setText(prev == null ? "첫 화" : prev.getName());
            ((InfoViewHolder) holder).nextInfo.setText(next == null ? "마지막 화" : next.getName());

            if(pos == 0){
                return;
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos, @NonNull List<Object> payloads) {
        if(payloads != null && payloads.contains(PAYLOAD_HEIGHT) && holder instanceof ImgViewHolder
                && items != null && pos >= 0 && pos < items.size() && items.get(pos) instanceof PageItem) {
            ImgViewHolder imageHolder = (ImgViewHolder) holder;
            PageItem page = (PageItem) items.get(pos);
            String pageKey = pageBindKey(page);
            if(pageKey.equals(imageHolder.boundPageKey)) {
                applyKnownHeight(imageHolder, page, pageKey);
                return;
            }
        }
        super.onBindViewHolder(holder, pos, payloads);
    }



    void glideBind(ImgViewHolder holder, int pos){
        PageItem item = ((PageItem)items.get(pos));
        Object url = getImageModel(item);
        String pageKey = pageBindKey(item);
        if(!pageKey.equals(holder.boundPageKey))
            clearImageTarget(holder);
        int bindGeneration = ++holder.bindGeneration;
        holder.boundPageKey = pageKey;
        holder.bindStartedAtMs = android.os.SystemClock.elapsedRealtime();
        applyKnownHeight(holder, item, pageKey);
        String cacheKey = decodedCacheKey(item);
        CachedBitmap cached = decodedBitmapCache.get(cacheKey);
        if(cached != null && cached.isUsable() && isHolderStillBound(holder, item, pageKey)) {
            if(item.index > 0)
                ViewerWarmupManager.logMetric("viewer_next_page_cache_hit", 1);
            failedImageRetries.remove(pageKey);
            if(!isDisplayBitmapUsable(cached.bitmap)) {
                decodedBitmapCache.remove(cacheKey);
                handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                return;
            }
            bindBitmap(holder, pageKey, cached.bitmap);
            holder.refresh.setVisibility(View.GONE);
            markDisplayedAndPreload(holder, item, pageKey);
            return;
        }
        if(cached != null)
            decodedBitmapCache.remove(cacheKey);
        Bitmap warmupCached = ViewerWarmupManager.getDecodedBitmap(item, autoCut, reverse, width);
        if(warmupCached != null && !warmupCached.isRecycled() && isHolderStillBound(holder, item, pageKey)) {
            if(item.index > 0)
                ViewerWarmupManager.logMetric("viewer_next_page_cache_hit", 1);
            failedImageRetries.remove(pageKey);
            if(!isDisplayBitmapUsable(warmupCached)) {
                handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                return;
            }
            bindBitmap(holder, pageKey, warmupCached);
            holder.refresh.setVisibility(View.GONE);
            cacheDisplayedBitmap(cacheKey, warmupCached);
            markDisplayedAndPreload(holder, item, pageKey);
            return;
        }
        cancelDecodedPreload(cacheKey);
        holder.frame.setImageDrawable(null);
        holder.refresh.setVisibility(View.GONE);
        if (autoCut) {
            long bindStart = android.os.SystemClock.elapsedRealtime();
            CustomTarget<Bitmap> imageTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap bitmap, Transition<? super Bitmap> transition) {
                    if(!isActiveHolder(holder, item, this, pageKey, bindGeneration))
                        return;
                    failedImageRetries.remove(pageKey);
                    if(!isDisplayBitmapUsable(bitmap)) {
                        handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                        return;
                    }
                    bindBitmap(holder, pageKey, bitmap);
                    holder.refresh.setVisibility(View.GONE);
                    if(item.index == 0)
                        ViewerWarmupManager.logMetric("viewer_first_bind_ms", android.os.SystemClock.elapsedRealtime() - bindStart);
                    markDisplayedAndPreload(holder, item, pageKey);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    if(!isActiveHolder(holder, item, this, pageKey, bindGeneration))
                        return;
                    applyKnownHeight(holder, item, pageKey);
                    holder.frame.setImageDrawable(null);
                    holder.refresh.setVisibility(View.GONE);
                }

                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    if(!isActiveHolder(holder, item, this, pageKey, bindGeneration))
                        return;
                    handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                }
            };
            holder.imageTarget = imageTarget;
            //set image to holder view
            Glide.with(holder.frame)
                    .asBitmap()
                    .priority(Priority.IMMEDIATE)
                    .apply(viewerImageOptions(item))
                    .load(url)
                    .into(imageTarget);
        } else {
            long bindStart = android.os.SystemClock.elapsedRealtime();
            CustomTarget<Bitmap> imageTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                    if(!isActiveHolder(holder, item, this, pageKey, bindGeneration))
                        return;
                    failedImageRetries.remove(pageKey);
                    if(!isDisplayBitmapUsable(resource)) {
                        handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                        return;
                    }
                    bindBitmap(holder, pageKey, resource);
                    holder.refresh.setVisibility(View.GONE);
                    if(item.index == 0)
                        ViewerWarmupManager.logMetric("viewer_first_bind_ms", android.os.SystemClock.elapsedRealtime() - bindStart);
                    markDisplayedAndPreload(holder, item, pageKey);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    if(!isActiveHolder(holder, item, this, pageKey, bindGeneration))
                        return;
                    applyKnownHeight(holder, item, pageKey);
                    holder.frame.setImageDrawable(null);
                    holder.refresh.setVisibility(View.GONE);
                }

                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    if(!isActiveHolder(holder, item, this, pageKey, bindGeneration))
                        return;
                    handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                }
            };
            holder.imageTarget = imageTarget;
            Glide.with(holder.frame)
                    .asBitmap()
                    .priority(Priority.IMMEDIATE)
                    .apply(viewerImageOptions(item))
                    .load(url)
                    .into(imageTarget);
        }
    }

    private void handleImageLoadFailed(ImgViewHolder holder, PageItem item, String pageKey, int bindGeneration) {
        applyKnownHeight(holder, item, pageKey);
        holder.frame.setImageDrawable(null);
        if(scheduleImageRetry(holder, item, pageKey, bindGeneration)) {
            holder.refresh.setVisibility(View.GONE);
            return;
        }
        holder.refresh.setVisibility(View.VISIBLE);
    }

    private boolean scheduleImageRetry(ImgViewHolder holder, PageItem item, String pageKey, int bindGeneration) {
        int attempts = failedImageRetries.containsKey(pageKey) ? failedImageRetries.get(pageKey) : 0;
        if(!shouldRetryImageLoad(released, pageKey, attempts))
            return false;
        int nextAttempt = attempts + 1;
        failedImageRetries.put(pageKey, nextAttempt);
        ViewerWarmupManager.logMetric("viewer_image_retry", nextAttempt);
        long delayMs = imageRetryDelayMs(nextAttempt);
        mainHandler.postDelayed(() -> {
            if(released || !isHolderStillBound(holder, item, pageKey) || holder.bindGeneration != bindGeneration)
                return;
            int position = holder.getAdapterPosition();
            if(position != RecyclerView.NO_POSITION)
                notifyItemChanged(position);
        }, delayMs);
        return true;
    }

    private static boolean shouldRetryImageLoad(boolean released, String pageKey, int attempts) {
        return !released
                && pageKey != null
                && pageKey.length() > 0
                && attempts < IMAGE_LOAD_RETRY_LIMIT;
    }

    private static long imageRetryDelayMs(int nextAttempt) {
        if(nextAttempt <= 1)
            return 220L;
        if(nextAttempt == 2)
            return 650L;
        return 1200L;
    }

    private void bindBitmap(ImgViewHolder holder, String pageKey, Bitmap bitmap) {
        if(!isDisplayBitmapUsable(bitmap)) {
            holder.frame.setImageDrawable(null);
            return;
        }
        boolean hadKnownHeight = hasKnownPageHeight(pageKey);
        rememberPageHeight(pageKey, bitmap);
        applyPageHeight(holder, null, pageKey, !scrollBusy || hadKnownHeight);
        holder.frame.setImageBitmap(bitmap);
    }

    private void cacheDisplayedBitmap(String cacheKey, Bitmap bitmap) {
        if(!shouldCacheDisplayedBitmap(cacheKey, isDisplayBitmapUsable(bitmap)))
            return;
        putDecodedBitmap(cacheKey, bitmap);
    }

    static boolean shouldCacheDisplayedBitmapForTest(String cacheKey, boolean holderActive, boolean bitmapUsable) {
        return holderActive && shouldCacheDisplayedBitmap(cacheKey, bitmapUsable);
    }

    static boolean shouldRetryImageLoadForTest(boolean released, String pageKey, int attempts) {
        return shouldRetryImageLoad(released, pageKey, attempts);
    }

    static long imageRetryDelayMsForTest(int nextAttempt) {
        return imageRetryDelayMs(nextAttempt);
    }

    private static boolean shouldCacheDisplayedBitmap(String cacheKey, boolean bitmapUsable) {
        return cacheKey != null && cacheKey.length() > 0 && bitmapUsable;
    }

    static boolean isDisplayBitmapUsableForTest(Bitmap bitmap) {
        return isDisplayBitmapUsable(bitmap);
    }

    private static boolean isDisplayBitmapUsable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
    }

    private static Bitmap copyBitmapForDisplay(Bitmap bitmap) {
        if(!isDisplayBitmapUsable(bitmap))
            return null;
        try {
            Bitmap.Config config = bitmap.getConfig() == null ? Bitmap.Config.ARGB_8888 : bitmap.getConfig();
            Bitmap copy = bitmap.copy(config, false);
            return isDisplayBitmapUsable(copy) ? copy : null;
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    private void rememberPageHeight(String pageKey, Bitmap bitmap) {
        if(pageKey == null || pageKey.length() == 0 || bitmap == null || bitmap.isRecycled())
            return;
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        if(bitmapWidth <= 0 || bitmapHeight <= 0)
            return;
        int targetHeight = Math.max(1, Math.round((float)Math.max(width, 1) * bitmapHeight / bitmapWidth));
        Integer previousHeight = pageHeights.put(pageKey, targetHeight);
        if(previousHeight == null) {
            pageHeightTotal += targetHeight;
            pageHeightSampleCount++;
        } else {
            pageHeightTotal += targetHeight - previousHeight;
        }
        if(scrollBusy)
            pendingHeightCorrections.add(pageKey);
    }

    private void applyKnownHeight(ImgViewHolder holder, PageItem item, String pageKey) {
        applyPageHeight(holder, item, pageKey, true);
    }

    private void applyPageHeight(ImgViewHolder holder, PageItem item, String pageKey, boolean allowKnownCorrection) {
        if(holder == null || holder.frame == null)
            return;
        Integer knownHeight = pageKey == null ? null : pageHeights.get(pageKey);
        boolean hasKnownHeight = knownHeight != null && knownHeight > 0;
        if(hasKnownHeight && !allowKnownCorrection)
            pendingHeightCorrections.add(pageKey);
        int targetHeight = pageKey == null
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : (hasKnownHeight && allowKnownCorrection ? knownHeight : estimatedPageHeight(item));
        applyHeight(holder.itemView, targetHeight, false);
        applyHeight(holder.frame, targetHeight == ViewGroup.LayoutParams.WRAP_CONTENT
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : ViewGroup.LayoutParams.MATCH_PARENT, true);
        int minHeight = targetHeight == ViewGroup.LayoutParams.WRAP_CONTENT ? 0 : targetHeight;
        holder.itemView.setMinimumHeight(minHeight);
        holder.frame.setMinimumHeight(minHeight);
        holder.appliedItemHeight = targetHeight;
    }

    private void applyHeight(View view, int height, boolean matchWidth) {
        if(view == null)
            return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if(params == null)
            return;
        int targetWidth = matchWidth ? ViewGroup.LayoutParams.MATCH_PARENT : params.width;
        if(params.height != height || params.width != targetWidth) {
            params.width = targetWidth;
            params.height = height;
            view.setLayoutParams(params);
        }
    }

    private boolean hasKnownPageHeight(String pageKey) {
        return pageKey != null && pageHeights.containsKey(pageKey);
    }

    private int estimatedPageHeight(PageItem item) {
        return estimatedPageHeight(autoCut, item == null ? PageItem.FIRST : item.side, width, pageHeightTotal, pageHeightSampleCount);
    }

    private static int estimatedPageHeight(boolean autoCut, int side, int width, long pageHeightTotal, int pageHeightSampleCount) {
        if(autoCut && side == PageItem.SECOND)
            return 1;
        if(pageHeightSampleCount > 0)
            return Math.max(width, Math.round((float) pageHeightTotal / pageHeightSampleCount));
        return Math.max(width, Math.round(width * 1.45f));
    }

    static int estimatedPageHeightForTest(boolean autoCut, int side, int width, long pageHeightTotal, int pageHeightSampleCount) {
        return estimatedPageHeight(autoCut, side, width, pageHeightTotal, pageHeightSampleCount);
    }

    private void schedulePendingHeightCorrections() {
        if(released || pendingHeightCorrections.isEmpty() || pendingHeightCorrectionScheduled)
            return;
        pendingHeightCorrectionScheduled = true;
        mainHandler.postDelayed(() -> {
            pendingHeightCorrectionScheduled = false;
            flushPendingHeightCorrections();
        }, scrollIdleHeightCorrectionDelayMs());
    }

    private void flushPendingHeightCorrections() {
        if(released || scrollBusy || pendingHeightCorrections.isEmpty() || items == null)
            return;
        Set<String> keys = new LinkedHashSet<>(pendingHeightCorrections);
        pendingHeightCorrections.clear();
        int anchor = lastPreloadAnchorPosition != RecyclerView.NO_POSITION
                ? lastPreloadAnchorPosition
                : Math.max(0, Math.min(pendingPreloadPosition, items.size() - 1));
        int start = Math.max(0, anchor - 6);
        int end = Math.min(items.size() - 1, anchor + 6);
        int notified = notifyHeightCorrectionsInRange(keys, start, end, 8);
        if(notified == 0)
            notifyHeightCorrectionsInRange(keys, 0, items.size() - 1, 4);
    }

    private int notifyHeightCorrectionsInRange(Set<String> keys, int start, int end, int limit) {
        int notified = 0;
        for(int i = start; i <= end && notified < limit; i++) {
            Object item = items.get(i);
            if(item instanceof PageItem && keys.contains(pageBindKey((PageItem) item))) {
                notifyItemChanged(i, PAYLOAD_HEIGHT);
                notified++;
            }
        }
        return notified;
    }

    private void clearPageHeightState() {
        pageHeights.clear();
        pendingHeightCorrections.clear();
        pageHeightTotal = 0L;
        pageHeightSampleCount = 0;
    }

    private void markDisplayedAndPreload(ImgViewHolder holder, PageItem item, String pageKey) {
        if(!isHolderStillBound(holder, item, pageKey))
            return;
        displayedImages.add(pageKey);
        trimDisplayedTracker();
        if(mainContext instanceof ViewerActivity)
            ((ViewerActivity) mainContext).onViewerPageDisplayed(item);
        if(shouldLogFirstVisible(firstVisibleLogged)) {
            firstVisibleLogged = true;
            ViewerWarmupManager.logMetric("viewer_first_visible_ms", android.os.SystemClock.elapsedRealtime() - holder.bindStartedAtMs);
        }
        int layoutPos = holder.getAdapterPosition();
        if(layoutPos != RecyclerView.NO_POSITION)
            schedulePreloadAroundScrollPosition(layoutPos);
    }

    static boolean shouldLogFirstVisibleForTest(boolean alreadyLogged) {
        return shouldLogFirstVisible(alreadyLogged);
    }

    private static boolean shouldLogFirstVisible(boolean alreadyLogged) {
        return !alreadyLogged;
    }

    private RequestOptions viewerImageOptions(PageItem item) {
        RequestOptions options = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .downsample(DownsampleStrategy.AT_MOST)
                .override(Math.max(width, 1), Target.SIZE_ORIGINAL);
        if(item != null)
            options = options.transform(new ViewerPageTransformation(item, autoCut, reverse, width));
        return options;
    }

    private Object getImageModel(PageItem item) {
        return imageModel(item);
    }

    static Object imageModelForTest(PageItem item) {
        return imageModel(item);
    }

    private static Object imageModel(PageItem item) {
        if(item == null)
            return "";
        if(!isUsableImageUrl(item.img))
            return "";
        if(item.manga == null)
            return item.img;
        return item.manga.isOnline() ? getGlideUrl(item.img, item.manga.getBaseMode()) : item.img;
    }

    static boolean isAttachableImagePageForTest(PageItem item) {
        return isAttachableImagePage(item);
    }

    private static boolean isAttachableImagePage(PageItem item) {
        return item != null && item.manga != null;
    }

    private void clearImageTarget(ImgViewHolder holder) {
        holder.boundPageKey = null;
        holder.bindGeneration++;
        if(holder.imageTarget == null)
            return;
        CustomTarget<Bitmap> target = holder.imageTarget;
        holder.imageTarget = null;
        holder.frame.setImageDrawable(null);
        if(isContextDestroyed())
            return;
        try {
            Glide.with(holder.frame).clear(target);
        } catch (IllegalArgumentException e) {
            // RecyclerView can recycle children while the viewer Activity is already destroyed.
        }
    }

    private boolean isContextDestroyed() {
        if(mainContext instanceof Activity) {
            Activity activity = (Activity) mainContext;
            return activity.isFinishing() || activity.isDestroyed();
        }
        return false;
    }

    private boolean canStartGlideRequest() {
        return !released && !isContextDestroyed();
    }

    private boolean isActiveHolder(ImgViewHolder holder, PageItem item, CustomTarget<Bitmap> target, String pageKey, int bindGeneration) {
        return !released
                && holder.imageTarget == target
                && holder.bindGeneration == bindGeneration
                && pageKey != null
                && pageKey.equals(holder.boundPageKey)
                && isHolderStillBound(holder, item, pageKey);
    }

    private boolean isHolderStillBound(ImgViewHolder holder, PageItem item, String pageKey) {
        if(holder == null || item == null || pageKey == null || items == null)
            return false;
        int position = holder.getAdapterPosition();
        return position != RecyclerView.NO_POSITION
                && position < items.size()
                && items.get(position) instanceof PageItem
                && pageKey.equals(pageBindKey((PageItem) items.get(position)));
    }

    private void preloadAroundScrollPosition(int adapterPosition) {
        if(adapterPosition == RecyclerView.NO_POSITION || !canStartGlideRequest())
            return;
        int direction = pendingPreloadDirection != 0
                ? pendingPreloadDirection
                : (lastPreloadAnchorPosition != RecyclerView.NO_POSITION && adapterPosition < lastPreloadAnchorPosition ? -1 : 1);
        lastPreloadAnchorPosition = adapterPosition;
        long generation = preloadGeneration;
        preloadCriticalWindow(adapterPosition, direction, generation);
        preloadDirectionalWindow(adapterPosition, direction, ViewerPreloadPolicy.scrollAheadWindow(p.getDataSave()), generation);
        preloadDirectionalWindow(adapterPosition, -direction, new ViewerPreloadPolicy.Window(0, 1, 2, 2), generation);
    }

    private void schedulePreloadAroundScrollPosition(int adapterPosition) {
        if(adapterPosition == RecyclerView.NO_POSITION || !canStartGlideRequest())
            return;
        pendingPreloadPosition = adapterPosition;
        int direction = pendingPreloadDirection != 0
                ? pendingPreloadDirection
                : (lastPreloadAnchorPosition != RecyclerView.NO_POSITION && adapterPosition < lastPreloadAnchorPosition ? -1 : 1);
        if(!scrollBusy && !isIdlePreloadReady()) {
            scheduleDelayedPreloadAroundScrollPosition();
            return;
        }
        preloadCriticalWindow(adapterPosition, direction, preloadGeneration);
        if(scrollBusy)
            return;
        if(pendingPreloadScheduled)
            return;
        pendingPreloadScheduled = true;
        mainHandler.postDelayed(() -> {
            int target = pendingPreloadPosition;
            pendingPreloadPosition = RecyclerView.NO_POSITION;
            pendingPreloadScheduled = false;
            if(target != RecyclerView.NO_POSITION && canStartGlideRequest())
                preloadAroundScrollPosition(target);
        }, 24);
    }

    private void scheduleDelayedPreloadAroundScrollPosition() {
        if(pendingPreloadScheduled)
            return;
        pendingPreloadScheduled = true;
        long delayMs = Math.max(24L, idlePreloadReadyAtMs - android.os.SystemClock.uptimeMillis());
        mainHandler.postDelayed(() -> {
            int target = pendingPreloadPosition;
            pendingPreloadPosition = RecyclerView.NO_POSITION;
            pendingPreloadScheduled = false;
            if(target != RecyclerView.NO_POSITION && canStartGlideRequest())
                preloadAroundScrollPosition(target);
        }, delayMs);
    }

    private void preloadCriticalWindow(int adapterPosition, int direction, long generation) {
        if(adapterPosition == RecyclerView.NO_POSITION || !canStartGlideRequest())
            return;
        if(scrollBusy) {
            preloadDirectionalWindow(adapterPosition, direction, ViewerPreloadPolicy.scrollBusyWindow(p.getDataSave()), generation);
            return;
        }
        int decodedLimit = p.getDataSave() ? 1 : 2;
        preloadDirectionalWindow(adapterPosition, direction, new ViewerPreloadPolicy.Window(decodedLimit, decodedLimit, decodedLimit, decodedLimit), generation);
    }

    private void preloadDirectionalWindow(int adapterPosition, int direction, ViewerPreloadPolicy.Window window) {
        preloadDirectionalWindow(adapterPosition, direction, window, preloadGeneration);
    }

    private void preloadDirectionalWindow(int adapterPosition, int direction, ViewerPreloadPolicy.Window window, long generation) {
        if(items == null || window == null || direction == 0 || !canStartGlideRequest())
            return;
        int preloaded = 0;
        int position = adapterPosition;
        while(position >= 0 && position < items.size() && preloaded < window.totalLimit) {
            Object next = items.get(position);
            if(next instanceof PageItem) {
                int tier = ViewerPreloadPolicy.tierForOffset(window, preloaded);
                if(tier == ViewerPreloadPolicy.TIER_DECODED)
                    preloadPageIntoDecodedCache((PageItem) next, Priority.IMMEDIATE, generation);
                else
                    preloadPage((PageItem) next, priorityForTier(tier));
                preloaded++;
            }
            position += direction;
        }
    }

    private ViewerPreloadPolicy.Window clampWindow(ViewerPreloadPolicy.Window policy, int totalLimit) {
        int limit = Math.max(1, Math.min(policy.totalLimit, Math.max(1, totalLimit)));
        return new ViewerPreloadPolicy.Window(
                Math.min(policy.decodedLimit, limit),
                Math.min(policy.immediateLimit, limit),
                Math.min(policy.highLimit, limit),
                limit
        );
    }

    private ViewerPreloadPolicy.Window reversePreloadWindow() {
        return p.getDataSave()
                ? new ViewerPreloadPolicy.Window(0, 1, 2, 2)
                : new ViewerPreloadPolicy.Window(1, 2, 4, 4);
    }

    private Priority priorityForTier(int tier) {
        if(tier == ViewerPreloadPolicy.TIER_DECODED || tier == ViewerPreloadPolicy.TIER_IMMEDIATE)
            return Priority.IMMEDIATE;
        if(tier == ViewerPreloadPolicy.TIER_HIGH)
            return Priority.HIGH;
        return Priority.NORMAL;
    }

    private void preloadPage(PageItem page) {
        preloadPage(page, Priority.LOW);
    }

    private void preloadPage(PageItem page, Priority priority) {
        if(!canStartGlideRequest())
            return;
        String key = preloadKey(page);
        if(key.length() == 0)
            return;
        if(!preloadedImages.add(key))
            return;
        trimPreloadTracker();
        try {
            Glide.with(mainContext)
                    .asBitmap()
                    .priority(priority)
                    .apply(viewerImageOptions(page))
                    .load(getImageModel(page))
                    .listener(new RequestListener<Bitmap>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                            preloadedImages.remove(key);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                            return false;
                        }
                    })
                    .preload();
        } catch (IllegalArgumentException e) {
            preloadedImages.remove(key);
        }
    }

    private void preloadPageIntoDecodedCache(PageItem page, Priority priority) {
        preloadPageIntoDecodedCache(page, priority, preloadGeneration);
    }

    static int preloadAheadCountForTest() {
        return PRELOAD_AHEAD_COUNT;
    }

    static int initialPreloadAheadCountForTest() {
        return INITIAL_PRELOAD_AHEAD_COUNT;
    }

    static int decodedPreloadActiveLimitForTest() {
        return DECODED_PRELOAD_ACTIVE_LIMIT;
    }

    static long scrollIdlePreloadDelayMsForTest() {
        return scrollIdlePreloadDelayMs();
    }

    static long scrollIdleHeightCorrectionDelayMsForTest() {
        return scrollIdleHeightCorrectionDelayMs();
    }

    private void preloadPageIntoDecodedCache(PageItem page, Priority priority, long generation) {
        if(!canStartGlideRequest())
            return;
        int activeLimit = scrollBusy ? (p.getDataSave() ? 0 : 1) : DECODED_PRELOAD_ACTIVE_LIMIT;
        if(activeLimit <= 0 || decodedPreloadTargets.size() >= activeLimit) {
            preloadPage(page, Priority.HIGH);
            return;
        }
        String key = decodedCacheKey(page);
        if(key == null || key.length() == 0)
            return;
        CachedBitmap cached = decodedBitmapCache.get(key);
        if(cached != null && cached.isUsable())
            return;
        if(cached != null)
            decodedBitmapCache.remove(key);
        String requestKey = decodedPreloadRequestKey(key);
        if(!preloadedImages.add(requestKey))
            return;
        trimPreloadTracker();
        CustomTarget<Bitmap> target = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                decodedPreloadTargets.remove(requestKey);
                if(generation != preloadGeneration) {
                    preloadedImages.remove(requestKey);
                    return;
                }
                if(resource == null || resource.isRecycled() || isContextDestroyed())
                    return;
                Bitmap displayBitmap = copyBitmapForDisplay(resource);
                if(displayBitmap == null)
                    return;
                putDecodedBitmap(key, displayBitmap);
                rememberPageHeight(key, displayBitmap);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                decodedPreloadTargets.remove(requestKey);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                decodedPreloadTargets.remove(requestKey);
                preloadedImages.remove(requestKey);
            }
        };
        decodedPreloadTargets.put(requestKey, target);
        if(!canStartGlideRequest()) {
            decodedPreloadTargets.remove(requestKey);
            preloadedImages.remove(requestKey);
            return;
        }
        try {
            Glide.with(mainContext)
                    .asBitmap()
                    .priority(priority)
                    .apply(viewerImageOptions(page))
                    .load(getImageModel(page))
                    .into(target);
        } catch (IllegalArgumentException e) {
            decodedPreloadTargets.remove(requestKey);
            preloadedImages.remove(requestKey);
        }
    }

    private String preloadKey(PageItem page) {
        if(page == null || page.manga == null || !isUsableImageUrl(page.img))
            return "";
        return pageBindKey(page);
    }

    private boolean isIdlePreloadReady() {
        return scrollBusy || android.os.SystemClock.uptimeMillis() >= idlePreloadReadyAtMs;
    }

    private static long scrollIdlePreloadDelayMs() {
        return SCROLL_IDLE_PRELOAD_DELAY_MS;
    }

    private static long scrollIdleHeightCorrectionDelayMs() {
        return SCROLL_IDLE_HEIGHT_CORRECTION_DELAY_MS;
    }

    private String decodedCacheKey(PageItem page) {
        return pageBindKey(page);
    }

    private String pageBindKey(PageItem page) {
        if(page == null || page.manga == null || !isUsableImageUrl(page.img))
            return "";
        return page.pageKey(autoCut, reverse, width);
    }

    private static boolean isUsableImageUrl(String image) {
        return image != null && image.trim().length() > 0;
    }

    private Decoder decoderFor(PageItem page) {
        Manga manga = page == null ? null : page.manga;
        int seed = manga == null ? 0 : manga.getSeed();
        int id = manga == null ? 0 : manga.getId();
        String key = (manga == null ? 0 : manga.getBaseMode()) + ":" + id + ":" + seed;
        Decoder decoder = decoders.get(key);
        if(decoder == null) {
            decoder = new Decoder(seed, id);
            decoders.put(key, decoder);
        }
        return decoder;
    }

    private void clearDecodedPageState() {
        preloadedImages.clear();
        failedImageRetries.clear();
        decodedBitmapCache.evictAll();
        clearPageHeightState();
        decoders.clear();
        clearDecodedPreloadTargets();
    }

    private void trimReusablePageStateToLoadedItems() {
        if(items == null || items.size() == 0) {
            clearDecodedPageState();
            return;
        }
        Set<String> activePageKeys = activePageKeys();
        if(activePageKeys.isEmpty()) {
            clearDecodedPageState();
            return;
        }
        Set<String> activePreloadKeys = activePreloadKeys(activePageKeys);
        preloadedImages.retainAll(activePreloadKeys);
        displayedImages.retainAll(activePageKeys);
        failedImageRetries.keySet().retainAll(activePageKeys);
        pageHeights.keySet().retainAll(activePageKeys);
        pendingHeightCorrections.retainAll(activePageKeys);
        recomputePageHeightAggregate();
        decoders.clear();
        trimDecodedPreloadTargets(activePreloadKeys);
    }

    private Set<String> activePageKeys() {
        Set<String> active = new LinkedHashSet<>();
        if(items == null)
            return active;
        for(Object item : items) {
            if(item instanceof PageItem) {
                String key = pageBindKey((PageItem) item);
                if(key.length() > 0)
                    active.add(key);
            }
        }
        return active;
    }

    private static Set<String> activePreloadKeys(Set<String> pageKeys) {
        Set<String> preloadKeys = new LinkedHashSet<>();
        if(pageKeys == null)
            return preloadKeys;
        for(String pageKey : pageKeys) {
            if(pageKey == null || pageKey.length() == 0)
                continue;
            preloadKeys.add(pageKey);
            preloadKeys.add(decodedPreloadRequestKey(pageKey));
        }
        return preloadKeys;
    }

    private static String decodedPreloadRequestKey(String pageKey) {
        return "decoded:" + pageKey;
    }

    private void cancelDecodedPreload(String pageKey) {
        if(pageKey == null || pageKey.length() == 0)
            return;
        String requestKey = decodedPreloadRequestKey(pageKey);
        CustomTarget<Bitmap> target = decodedPreloadTargets.remove(requestKey);
        if(target == null)
            return;
        preloadedImages.remove(requestKey);
        if(isContextDestroyed())
            return;
        try {
            Glide.with(mainContext).clear(target);
        } catch (IllegalArgumentException ignored) {
        }
    }

    static boolean shouldRetainTrackedPreloadForLoadedPageForTest(String trackedKey, Set<String> activePageKeys) {
        return activePreloadKeys(activePageKeys).contains(trackedKey);
    }

    private void recomputePageHeightAggregate() {
        pageHeightTotal = 0L;
        pageHeightSampleCount = 0;
        for(Integer height : pageHeights.values()) {
            if(height != null && height > 0) {
                pageHeightTotal += height;
                pageHeightSampleCount++;
            }
        }
    }

    private void trimDecodedPreloadTargets(Set<String> activePreloadKeys) {
        if(decodedPreloadTargets.size() == 0)
            return;
        List<CustomTarget<Bitmap>> staleTargets = new ArrayList<>();
        Iterator<Map.Entry<String, CustomTarget<Bitmap>>> iterator = decodedPreloadTargets.entrySet().iterator();
        while(iterator.hasNext()) {
            Map.Entry<String, CustomTarget<Bitmap>> entry = iterator.next();
            if(activePreloadKeys == null || !activePreloadKeys.contains(entry.getKey())) {
                staleTargets.add(entry.getValue());
                iterator.remove();
            }
        }
        if(staleTargets.isEmpty() || isContextDestroyed())
            return;
        for(CustomTarget<Bitmap> target : staleTargets) {
            try {
                Glide.with(mainContext).clear(target);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void clearDecodedPreloadTargets() {
        if(decodedPreloadTargets.size() == 0)
            return;
        List<CustomTarget<Bitmap>> targets = new ArrayList<>(decodedPreloadTargets.values());
        decodedPreloadTargets.clear();
        if(isContextDestroyed())
            return;
        for(CustomTarget<Bitmap> target : targets) {
            try {
                Glide.with(mainContext).clear(target);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private int decodedCacheSizeKb() {
        int maxMemoryKb = (int)(Runtime.getRuntime().maxMemory() / 1024);
        int targetKb = maxMemoryKb / (p.getDataSave() ? 16 : 8);
        int minKb = p.getDataSave() ? 4 * 1024 : 8 * 1024;
        int maxKb = p.getDataSave() ? 12 * 1024 : 32 * 1024;
        return Math.max(minKb, Math.min(targetKb, maxKb));
    }

    private void putDecodedBitmap(String key, Bitmap bitmap) {
        if(key == null || key.length() == 0 || bitmap == null || bitmap.isRecycled())
            return;
        decodedBitmapCache.put(key, new CachedBitmap(bitmap, bitmapSizeKb(bitmap)));
    }

    private static int bitmapSizeKb(Bitmap bitmap) {
        if(bitmap == null)
            return 1;
        return Math.max(1, bitmap.getByteCount() / 1024);
    }

    private static class CachedBitmap {
        final Bitmap bitmap;
        final int sizeKb;

        CachedBitmap(Bitmap bitmap, int sizeKb) {
            this.bitmap = bitmap;
            this.sizeKb = Math.max(1, sizeKb);
        }

        boolean isUsable() {
            return bitmap != null && !bitmap.isRecycled();
        }
    }

    private void trimPreloadTracker() {
        while(preloadedImages.size() > PRELOAD_TRACK_LIMIT) {
            Iterator<String> iterator = preloadedImages.iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }

    private void trimDisplayedTracker() {
        while(displayedImages.size() > PRELOAD_TRACK_LIMIT) {
            Iterator<String> iterator = displayedImages.iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }

    // total number of rows
    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    public PageItem getCurrentVisiblePage(){
        if(containsCurrentPage())
            return current;
        clearCurrentPage();
        return current;
    }

    PageItem current;
    int currentMangaId = -1;

    boolean needUpdate = true;

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        //handle bookmark
        int layoutPos = holder.getAdapterPosition();
        if(items == null || layoutPos == RecyclerView.NO_POSITION || layoutPos >= items.size())
            return;
        int type = getItemViewType(layoutPos);
        if(type == IMG) {
            PageItem pi = (PageItem) items.get(layoutPos);
            if(!isAttachableImagePage(pi))
                return;
            current = pi;
            if(displayedImages.contains(pageBindKey(pi)))
                preloadAroundScrollPosition(layoutPos);
            if(needUpdate || currentMangaId != pi.manga.getId()){
                needUpdate = false;
                currentMangaId = pi.manga.getId();
                if(mainContext instanceof ViewerActivity)
                    ((ViewerActivity) mainContext).onViewerPageAttached(pi);
                else if(callback != null)
                    callback.updateInfo(pi.manga);
            }
        } else if(type == INFO){
            needUpdate = true;
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if(holder instanceof ImgViewHolder) {
            ImgViewHolder imageHolder = (ImgViewHolder) holder;
            clearImageTarget(imageHolder);
            imageHolder.boundPageKey = null;
            applyKnownHeight(imageHolder, null, null);
            imageHolder.frame.setImageDrawable(null);
            imageHolder.refresh.setVisibility(View.GONE);
        }
    }

//
//    @Override
//    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
//        //remove unnecessary items
//        int type = holder.getItemViewType();
//        if(type == INFO){
//            PosData d = getImgPos(holder.getLayoutPosition());
//            // last info pos
//            if(d.setPos == data.size()) return;
//            else if(d.setPos == currentPos.setPos)
//                popFirst();
//            else if(d.setPos > currentPos.setPos)
//                popLast();
//        }
//    }


    // stores and recycles views as they are scrolled off screen
    public class ImgViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        ImageView frame;
        ImageButton refresh;
        CustomTarget<Bitmap> imageTarget;
        String boundPageKey;
        int bindGeneration = 0;
        long bindStartedAtMs = 0L;
        int appliedItemHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        ImgViewHolder(View itemView) {
            super(itemView);
            frame = itemView.findViewById(R.id.frame);
            refresh = itemView.findViewById(R.id.refreshButton);
            refresh.setOnClickListener(v -> {
                //refresh image
                int position = getAdapterPosition();
                if(position != RecyclerView.NO_POSITION)
                    notifyItemChanged(position);
            });
            itemView.setOnClickListener(this);
            itemView.setOnLongClickListener(this);
        }
        @Override
        public void onClick(View view) {
            if (mClickListener != null) mClickListener.onItemClick();
        }

        @Override
        public boolean onLongClick(View v) {
            return false;
        }
    }

    public class InfoViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{
        TextView prevInfo, nextInfo;
        ProgressBar loading;
        InfoViewHolder(View itemView) {
            super(itemView);
            prevInfo = itemView.findViewById(R.id.prevEpInfo);
            nextInfo = itemView.findViewById(R.id.nextEpInfo);
            loading = itemView.findViewById(R.id.infoLoading);
            itemView.setOnClickListener(this);
        }
        @Override
        public void onClick(View view) {
            if (mClickListener != null) mClickListener.onItemClick();
        }
    }

    // allows clicks events to be caught
    public void setClickListener(StripAdapter.ItemClickListener itemClickListener) {
        this.mClickListener = itemClickListener;
    }

    public interface ItemClickListener {
        void onItemClick();
    }

}
