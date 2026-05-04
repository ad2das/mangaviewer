package ml.melun.mangaview.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
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
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.Set;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;

import ml.melun.mangaview.R;
import ml.melun.mangaview.activity.ViewerActivity;
import ml.melun.mangaview.interfaces.StringCallback;
import ml.melun.mangaview.mangaview.Decoder;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;


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
    private static final int DATA_SAVE_PRELOAD_AHEAD_COUNT = 2;
    private static final int PRELOAD_BEHIND_COUNT = 6;
    private static final int PRELOAD_TRACK_LIMIT = 200;
    ViewerActivity.InfiniteScrollCallback callback;
    Title title;

    List<Object> items;
    private final Set<String> preloadedImages = new LinkedHashSet<>();
    private final Set<String> decodedPreloads = new LinkedHashSet<>();
    private final List<Future<?>> decodedPreloadTasks = new ArrayList<>();
    private final ExecutorService decodedPreloadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService displayDecodeExecutor = Executors.newFixedThreadPool(2);
    private final Map<String, Decoder> decoders = new HashMap<>();
    private final Map<String, Integer> decodedHeights = new HashMap<>();
    private final LruCache<String, Bitmap> decodedBitmapCache;
    private int estimatedPageHeight;

    public List<Object> getItems(){
        return items;
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
        return (episode * 1000003L) ^ (((long)item.index) << 1) ^ item.side;
    }

    private long infoStableId(InfoItem item) {
        return Long.MIN_VALUE
                ^ (episodeStableId(item.prev) * 1000003L)
                ^ episodeStableId(item.next);
    }

    private long episodeStableId(Manga manga) {
        if(manga == null)
            return 0L;
        return (((long)manga.getBaseMode()) << 32) ^ (manga.getId() & 0xffffffffL);
    }

    public void appendManga(Manga m){
        if(items == null)
            items = new ArrayList<>();
        if(hasMangaLoaded(m))
            return;
        int prevsize = items.size();
        List<String> imgs = m.getImgs(mainContext);
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
        count++;
        if(count>MaxStackSize){
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
        List<String> imgs = m.getImgs(mainContext);
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
        count++;

        if(count>MaxStackSize){
            popLast();
        }
        return inserted;
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

    private boolean sameManga(Manga a, Manga b) {
        return a != null && b != null
                && a.getId() == b.getId()
                && a.getBaseMode() == b.getBaseMode();
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
            count--;
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
            count--;
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

    public boolean isDisplayReady(int position) {
        if(items == null || position < 0 || position >= items.size())
            return true;
        Object item = items.get(position);
        if(!(item instanceof PageItem))
            return true;
        String cacheKey = decodedCacheKey((PageItem)item);
        Bitmap cached = getCachedBitmap(cacheKey);
        if(cached != null && !cached.isRecycled())
            return true;
        preloadPage((PageItem)item);
        return false;
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
        this.estimatedPageHeight = Math.max(width, context.getResources().getDisplayMetrics().heightPixels);
        this.decodedBitmapCache = new LruCache<String, Bitmap>(decodedCacheSizeKb()) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }

            @Override
            protected void entryRemoved(boolean evicted, @NonNull String key, @NonNull Bitmap oldValue, @Nullable Bitmap newValue) {
                if(evicted)
                    decodedPreloads.remove(key);
            }
        };
        setHasStableIds(true);
        appendManga(manga);
    }



    public void preloadAll(){
        if(items == null)
            return;
        for(Object o : items) {
            if(o instanceof PageItem) {
                preloadPage((PageItem) o);
            }
        }
    }

    public void preloadAround(PageItem page) {
        int position = findPagePosition(page);
        if(position == RecyclerView.NO_POSITION)
            return;
        preloadPage((PageItem) items.get(position));
        preloadAhead(position);
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
        decodedPreloads.clear();
        clearDecodedPreloadTasks();
        decoders.clear();
        decodedHeights.clear();
        decodedBitmapCache.evictAll();
        clearCurrentPage();
        count = 0;
        notifyItemRangeRemoved(0, size);
    }

    public void release() {
        if(items != null)
            items.clear();
        preloadedImages.clear();
        decodedPreloads.clear();
        clearDecodedPreloadTasks();
        decodedPreloadExecutor.shutdownNow();
        displayDecodeExecutor.shutdownNow();
        decoders.clear();
        decodedHeights.clear();
        decodedBitmapCache.evictAll();
        clearCurrentPage();
        count = 0;
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
            ((ImgViewHolder)holder).refresh.setVisibility(View.GONE);
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



    void glideBind(ImgViewHolder holder, int pos){
        PageItem item = ((PageItem)items.get(pos));
        if(holder.boundItem != null && samePage(holder.boundItem, item) && holder.imageTask != null)
            return;
        clearImageTarget(holder, false);
        Object url = getImageModel(item);
        String cacheKey = decodedCacheKey(item);
        applyKnownHeight(holder, cacheKey);
        Bitmap cached = getCachedBitmap(cacheKey);
        if(cached != null && !cached.isRecycled()) {
            applyBitmapHeight(holder, cacheKey, cached);
            holder.frame.setImageBitmap(cached);
            holder.refresh.setVisibility(View.GONE);
            holder.loading.setVisibility(View.GONE);
            preloadAroundPosition(pos);
            return;
        }
        holder.frame.setImageDrawable(null);
        holder.refresh.setVisibility(View.GONE);
        holder.loading.setVisibility(View.VISIBLE);
        loadDecodedIntoHolder(holder, item, url, cacheKey, pos);
    }

    private RequestOptions viewerImageOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .downsample(DownsampleStrategy.AT_MOST)
                .override(Math.max(width, 1), Target.SIZE_ORIGINAL);
    }

    private Object getImageModel(PageItem item) {
        return item.manga.isOnline() ? getGlideUrl(item.img, item.manga.getBaseMode()) : item.img;
    }

    private void clearImageTarget(ImgViewHolder holder) {
        clearImageTarget(holder, true);
    }

    private void clearImageTarget(ImgViewHolder holder, boolean resetHeight) {
        if(holder.imageTask != null)
            holder.imageTask.cancel(true);
        holder.imageTask = null;
        if(holder.imageFutureTarget != null) {
            try {
                Glide.with(mainContext.getApplicationContext()).clear(holder.imageFutureTarget);
            } catch (Exception ignored) {
            }
        }
        holder.imageFutureTarget = null;
        holder.boundItem = null;
        if(resetHeight)
            applyHolderHeight(holder, estimatedPageHeight);
        holder.frame.setImageDrawable(null);
        holder.refresh.setVisibility(View.GONE);
        holder.loading.setVisibility(View.GONE);
    }

    private void loadDecodedIntoHolder(ImgViewHolder holder, PageItem item, Object model, String cacheKey, int position) {
        holder.boundItem = item;
        FutureTarget<Bitmap> target = Glide.with(mainContext.getApplicationContext())
                .asBitmap()
                .apply(viewerImageOptions())
                .load(model)
                .submit(Math.max(width, 1), Target.SIZE_ORIGINAL);
        holder.imageFutureTarget = target;
        try {
            Future<?> task = displayDecodeExecutor.submit(() -> {
                try {
                    Bitmap bitmap = target.get();
                    if(bitmap == null || bitmap.isRecycled())
                        throw new IllegalStateException("Empty viewer bitmap");
                    Bitmap glideBitmap = bitmap;
                    Bitmap decoded = decoderFor(item).decode(bitmap, width);
                    Bitmap displayBitmap = buildDisplayBitmap(item, decoded);
                    displayBitmap = retainIfGlideOwned(displayBitmap, glideBitmap);
                    putCachedBitmap(cacheKey, displayBitmap);
                    rememberBitmapHeight(cacheKey, displayBitmap);
                    Bitmap finalBitmap = displayBitmap;
                    holder.frame.post(() -> {
                        if(holder.boundItem != item || holder.imageFutureTarget != target)
                            return;
                        holder.imageTask = null;
                        holder.imageFutureTarget = null;
                        applyBitmapHeight(holder, cacheKey, finalBitmap);
                        holder.frame.setImageBitmap(finalBitmap);
                        holder.refresh.setVisibility(View.GONE);
                        holder.loading.setVisibility(View.GONE);
                        preloadAroundPosition(position);
                    });
                } catch (Exception e) {
                    holder.frame.post(() -> {
                        if(holder.boundItem != item || holder.imageFutureTarget != target)
                            return;
                        holder.imageTask = null;
                        holder.imageFutureTarget = null;
                        applyHolderHeight(holder, estimatedPageHeight);
                        holder.frame.setImageResource(R.drawable.placeholder);
                        holder.refresh.setVisibility(View.VISIBLE);
                        holder.loading.setVisibility(View.GONE);
                    });
                } finally {
                    try {
                        Glide.with(mainContext.getApplicationContext()).clear(target);
                    } catch (Exception ignored) {
                    }
                }
            });
            holder.imageTask = task;
        } catch (RejectedExecutionException e) {
            try {
                Glide.with(mainContext.getApplicationContext()).clear(target);
            } catch (Exception ignored) {
            }
            holder.imageFutureTarget = null;
            holder.frame.setImageResource(R.drawable.placeholder);
            holder.refresh.setVisibility(View.VISIBLE);
            holder.loading.setVisibility(View.GONE);
        }
    }

    private Bitmap retainIfGlideOwned(Bitmap displayBitmap, Bitmap glideBitmap) {
        if(displayBitmap == null || displayBitmap.isRecycled() || displayBitmap != glideBitmap)
            return displayBitmap;
        try {
            return displayBitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (OutOfMemoryError e) {
            return displayBitmap;
        }
    }

    private Bitmap buildDisplayBitmap(PageItem item, Bitmap bitmap) {
        if(!autoCut)
            return bitmap;
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        if(bitmapWidth > bitmapHeight) {
            if(item.side == PageItem.FIRST)
                return createSplitBitmap(bitmap, reverse);
            return createSplitBitmap(bitmap, !reverse);
        }
        if(item.side == PageItem.FIRST)
            return bitmap;
        return Bitmap.createBitmap(bitmap.getWidth(), 1, Bitmap.Config.ARGB_8888);
    }

    private synchronized Bitmap getCachedBitmap(String cacheKey) {
        return decodedBitmapCache.get(cacheKey);
    }

    private synchronized void putCachedBitmap(String cacheKey, Bitmap bitmap) {
        decodedBitmapCache.put(cacheKey, bitmap);
    }

    private void preloadAhead(int adapterPosition) {
        int preloaded = 0;
        int preloadLimit = p.getDataSave() ? DATA_SAVE_PRELOAD_AHEAD_COUNT : PRELOAD_AHEAD_COUNT;
        if(items == null)
            return;
        for(int i = adapterPosition + 1; i < items.size() && preloaded < preloadLimit; i++) {
            Object next = items.get(i);
            if(next instanceof PageItem) {
                preloadPage((PageItem) next);
                preloaded++;
            }
        }
    }

    private void preloadBehind(int adapterPosition) {
        int preloaded = 0;
        if(items == null)
            return;
        for(int i = adapterPosition - 1; i >= 0 && preloaded < PRELOAD_BEHIND_COUNT; i--) {
            Object previous = items.get(i);
            if(previous instanceof PageItem) {
                preloadPage((PageItem) previous);
                preloaded++;
            }
        }
    }

    private void preloadAroundPosition(int adapterPosition) {
        preloadAhead(adapterPosition);
        preloadBehind(adapterPosition);
    }

    private void preloadPage(PageItem page) {
        String key = preloadKey(page);
        if(preloadedImages.add(key)) {
            trimPreloadTracker();
            Glide.with(mainContext.getApplicationContext())
                    .asBitmap()
                    .apply(viewerImageOptions())
                    .load(getImageModel(page))
                    .preload();
        }
        preloadDecodedPage(page);
    }

    private void preloadDecodedPage(PageItem page) {
        String cacheKey = decodedCacheKey(page);
        Bitmap cached = getCachedBitmap(cacheKey);
        if(cached != null && !cached.isRecycled())
            return;
        if(!decodedPreloads.add(cacheKey))
            return;
        FutureTarget<Bitmap> target = Glide.with(mainContext.getApplicationContext())
                .asBitmap()
                .apply(viewerImageOptions())
                .load(getImageModel(page))
                .submit(Math.max(width, 1), Target.SIZE_ORIGINAL);
        try {
            Future<?> task = decodedPreloadExecutor.submit(() -> {
                try {
                    Bitmap bitmap = target.get();
                    if(bitmap == null || bitmap.isRecycled()) {
                        decodedPreloads.remove(cacheKey);
                        return;
                    }
                    Bitmap glideBitmap = bitmap;
                    Bitmap decoded = decoderFor(page).decode(bitmap, width);
                    Bitmap displayBitmap = buildDisplayBitmap(page, decoded);
                    displayBitmap = retainIfGlideOwned(displayBitmap, glideBitmap);
                    putCachedBitmap(cacheKey, displayBitmap);
                    rememberBitmapHeight(cacheKey, displayBitmap);
                    trimDecodedPreloadTracker();
                } catch (Exception e) {
                    decodedPreloads.remove(cacheKey);
                } finally {
                    try {
                        Glide.with(mainContext.getApplicationContext()).clear(target);
                    } catch (Exception ignored) {
                    }
                    trimDecodedPreloadTasks();
                }
            });
            synchronized (decodedPreloadTasks) {
                decodedPreloadTasks.add(task);
            }
        } catch (RejectedExecutionException e) {
            decodedPreloads.remove(cacheKey);
            try {
                Glide.with(mainContext.getApplicationContext()).clear(target);
            } catch (Exception ignored) {
            }
        }
    }

    private String preloadKey(PageItem page) {
        if(page == null || page.manga == null)
            return "";
        return page.manga.getBaseMode() + ":" + page.img;
    }

    private String decodedCacheKey(PageItem page) {
        if(page == null || page.manga == null)
            return "";
        return page.manga.getBaseMode() + ":" + page.manga.getId() + ":" + page.manga.getSeed() + ":" + width + ":" + page.side + ":" + page.img;
    }

    private void rememberBitmapHeight(String cacheKey, Bitmap bitmap) {
        if(cacheKey == null || bitmap == null || bitmap.isRecycled())
            return;
        synchronized (decodedHeights) {
            int height = Math.max(1, bitmap.getHeight());
            decodedHeights.put(cacheKey, height);
            estimatedPageHeight = height;
        }
    }

    private void applyKnownHeight(ImgViewHolder holder, String cacheKey) {
        Integer height;
        synchronized (decodedHeights) {
            height = decodedHeights.get(cacheKey);
        }
        applyHolderHeight(holder, height == null ? estimatedPageHeight : Math.max(1, height));
    }

    private void applyBitmapHeight(ImgViewHolder holder, String cacheKey, Bitmap bitmap) {
        rememberBitmapHeight(cacheKey, bitmap);
        applyHolderHeight(holder, bitmap == null || bitmap.isRecycled() ? estimatedPageHeight : Math.max(1, bitmap.getHeight()));
    }

    private void applyHolderHeight(ImgViewHolder holder, int height) {
        int safeHeight = Math.max(1, height);
        holder.itemView.setMinimumHeight(safeHeight);
        holder.frame.setMinimumHeight(safeHeight);
        ViewGroup.LayoutParams itemParams = holder.itemView.getLayoutParams();
        if(itemParams != null && itemParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            itemParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            holder.itemView.setLayoutParams(itemParams);
        }
        ViewGroup.LayoutParams frameParams = holder.frame.getLayoutParams();
        if(frameParams != null && frameParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            frameParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            holder.frame.setLayoutParams(frameParams);
        }
    }

    private Bitmap createSplitBitmap(Bitmap bitmap, boolean leftSide) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int leftWidth = width / 2;
        int rightWidth = width - leftWidth;
        if(leftSide)
            return Bitmap.createBitmap(bitmap, 0, 0, leftWidth, height);
        return Bitmap.createBitmap(bitmap, leftWidth, 0, rightWidth, height);
    }

    private Decoder decoderFor(PageItem page) {
        String key = decoderKey(page == null ? null : page.manga);
        synchronized (decoders) {
            Decoder decoder = decoders.get(key);
            if(decoder == null) {
                Manga manga = page == null ? null : page.manga;
                decoder = new Decoder(manga == null ? 0 : manga.getSeed(), manga == null ? 0 : manga.getId());
                decoders.put(key, decoder);
            }
            return decoder;
        }
    }

    private String decoderKey(Manga manga) {
        if(manga == null)
            return "0:0:0";
        return manga.getBaseMode() + ":" + manga.getId() + ":" + manga.getSeed();
    }

    public int findPagePosition(PageItem page) {
        if(page == null || items == null)
            return RecyclerView.NO_POSITION;
        int fallbackPosition = RecyclerView.NO_POSITION;
        for(int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if(item instanceof PageItem && samePage((PageItem)item, page))
                return i;
            if(item instanceof PageItem && fallbackPosition == RecyclerView.NO_POSITION && samePageIgnoringSide((PageItem)item, page))
                fallbackPosition = i;
        }
        return fallbackPosition;
    }

    private boolean samePage(PageItem a, PageItem b) {
        return a != null && b != null
                && a.index == b.index
                && a.side == b.side
                && sameManga(a.manga, b.manga);
    }

    private boolean samePageIgnoringSide(PageItem a, PageItem b) {
        return a != null && b != null
                && a.index == b.index
                && sameManga(a.manga, b.manga);
    }

    private int decodedCacheSizeKb() {
        int maxMemoryKb = (int)(Runtime.getRuntime().maxMemory() / 1024);
        int targetKb = maxMemoryKb / (p.getDataSave() ? 20 : 12);
        int minKb = p.getDataSave() ? 4 * 1024 : 8 * 1024;
        int maxKb = p.getDataSave() ? 12 * 1024 : 32 * 1024;
        return Math.max(minKb, Math.min(targetKb, maxKb));
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

    private void trimDecodedPreloadTracker() {
        while(decodedPreloads.size() > PRELOAD_TRACK_LIMIT) {
            Iterator<String> iterator = decodedPreloads.iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }

    private void trimDecodedPreloadTasks() {
        synchronized (decodedPreloadTasks) {
            Iterator<Future<?>> iterator = decodedPreloadTasks.iterator();
            while(iterator.hasNext()) {
                Future<?> task = iterator.next();
                if(task == null || task.isDone() || task.isCancelled())
                    iterator.remove();
            }
        }
    }

    private void clearDecodedPreloadTasks() {
        synchronized (decodedPreloadTasks) {
            for(Future<?> task : new ArrayList<>(decodedPreloadTasks))
                if(task != null)
                    task.cancel(true);
            decodedPreloadTasks.clear();
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
        int layoutPos = holder.getLayoutPosition();
        if(items == null || layoutPos == RecyclerView.NO_POSITION || layoutPos >= items.size())
            return;
        int type = getItemViewType(layoutPos);
        if(type == IMG) {
            PageItem pi = (PageItem) items.get(layoutPos);
            current = pi;
            preloadAroundPosition(layoutPos);
            if(pi.manga.useBookmark()){
                int index = pi.index;
                if (index == 0) {
                    p.removeViewerBookmark(pi.manga);
                } else {
                    p.setViewerBookmark(pi.manga, index);
                }
            }
            p.setBookmark(title, pi.manga.getId());
            if(needUpdate || currentMangaId != pi.manga.getId()){
                needUpdate = false;
                currentMangaId = pi.manga.getId();
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
            applyHolderHeight(imageHolder, estimatedPageHeight);
            imageHolder.frame.setImageDrawable(null);
            imageHolder.refresh.setVisibility(View.GONE);
            imageHolder.loading.setVisibility(View.GONE);
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
        TextView loading;
        PageItem boundItem;
        FutureTarget<Bitmap> imageFutureTarget;
        Future<?> imageTask;
        ImgViewHolder(View itemView) {
            super(itemView);
            frame = itemView.findViewById(R.id.frame);
            refresh = itemView.findViewById(R.id.refreshButton);
            loading = itemView.findViewById(R.id.stripLoading);
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
