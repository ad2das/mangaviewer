package ml.melun.mangaview.adapter;

import android.content.Context;
import android.app.Activity;
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
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
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
    int __seed;
    Decoder d;
    int width;
    int count = 0;
    final static int MaxStackSize = 3;
    private static final int PRELOAD_AHEAD_COUNT = 6;
    private static final int DATA_SAVE_PRELOAD_AHEAD_COUNT = 2;
    private static final int PRELOAD_TRACK_LIMIT = 200;
    ViewerActivity.InfiniteScrollCallback callback;
    Title title;

    List<Object> items;
    private final Set<String> preloadedImages = new LinkedHashSet<>();
    private final LruCache<String, Bitmap> decodedBitmapCache;

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

    // data is passed into the constructor
    public StripAdapter(Context context, Manga manga, Boolean cut, int width, Title title, ViewerActivity.InfiniteScrollCallback callback) {
        autoCut = cut;
        this.callback = callback;
        this.mInflater = LayoutInflater.from(context);
        mainContext = context;
        reverse = p.getReverse();
        __seed = manga.getSeed();
        d = new Decoder(manga.getSeed(), manga.getId());
        this.width = width;
        this.title = title;
        this.decodedBitmapCache = new LruCache<String, Bitmap>(decodedCacheSizeKb()) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
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
        clearCurrentPage();
        count = 0;
        notifyItemRangeRemoved(0, size);
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
            ((ImgViewHolder)holder).frame.setImageResource(R.drawable.placeholder);
            ((ImgViewHolder)holder).refresh.setVisibility(View.VISIBLE);
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
        clearImageTarget(holder);
        PageItem item = ((PageItem)items.get(pos));
        Object url = getImageModel(item);
        holder.frame.setMinimumHeight(Math.max(width, 1));
        String cacheKey = decodedCacheKey(item);
        Bitmap cached = decodedBitmapCache.get(cacheKey);
        if(cached != null && !cached.isRecycled()) {
            holder.frame.setMinimumHeight(0);
            holder.frame.setImageBitmap(cached);
            holder.refresh.setVisibility(View.GONE);
            return;
        }
        if (autoCut) {
            CustomTarget<Bitmap> imageTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap bitmap, Transition<? super Bitmap> transition) {
                    if(!isActiveHolder(holder, item, this))
                        return;
                    holder.frame.setMinimumHeight(0);
                    bitmap = d.decode(bitmap, width);
                    Bitmap displayBitmap;
                    int width = bitmap.getWidth();
                    int height = bitmap.getHeight();
                    if (width > height) {
                        if (item.side == PageItem.FIRST) {
                            if (reverse)
                                displayBitmap = Bitmap.createBitmap(bitmap, 0, 0, width / 2, height);
                            else
                                displayBitmap = Bitmap.createBitmap(bitmap, width / 2, 0, width / 2, height);
                        } else {
                            if (reverse)
                                displayBitmap = Bitmap.createBitmap(bitmap, width / 2, 0, width / 2, height);
                            else
                                displayBitmap = Bitmap.createBitmap(bitmap, 0, 0, width / 2, height);
                        }
                    } else {
                        if (item.side == PageItem.FIRST) {
                            displayBitmap = bitmap;
                        } else {
                            displayBitmap = Bitmap.createBitmap(bitmap.getWidth(), 1, Bitmap.Config.ARGB_8888);
                        }
                    }
                    decodedBitmapCache.put(cacheKey, displayBitmap);
                    holder.frame.setImageBitmap(displayBitmap);
                    holder.refresh.setVisibility(View.GONE);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    if(holder.imageTarget != this)
                        return;
                    holder.frame.setMinimumHeight(Math.max(width, 1));
                    holder.frame.setImageDrawable(placeholder);
                    holder.refresh.setVisibility(View.VISIBLE);
                }

                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    if(holder.imageTarget != this)
                        return;
                    holder.frame.setMinimumHeight(Math.max(width, 1));
                    holder.frame.setImageResource(R.drawable.placeholder);
                    holder.refresh.setVisibility(View.VISIBLE);
                }
            };
            holder.imageTarget = imageTarget;
            //set image to holder view
            Glide.with(holder.frame)
                    .asBitmap()
                    .apply(viewerImageOptions())
                    .load(url)
                    .placeholder(R.drawable.placeholder)
                    .into(imageTarget);
        } else {
            CustomTarget<Bitmap> imageTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                    if(!isActiveHolder(holder, item, this))
                        return;
                    holder.frame.setMinimumHeight(0);
                    resource = d.decode(resource, width);
                    decodedBitmapCache.put(cacheKey, resource);
                    holder.frame.setImageBitmap(resource);
                    holder.refresh.setVisibility(View.GONE);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    if(holder.imageTarget != this)
                        return;
                    holder.frame.setMinimumHeight(Math.max(width, 1));
                    holder.frame.setImageDrawable(placeholder);
                    holder.refresh.setVisibility(View.VISIBLE);
                }

                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    if(holder.imageTarget != this)
                        return;
                    holder.frame.setMinimumHeight(Math.max(width, 1));
                    holder.frame.setImageResource(R.drawable.placeholder);
                    holder.refresh.setVisibility(View.VISIBLE);
                }
            };
            holder.imageTarget = imageTarget;
            Glide.with(holder.frame)
                    .asBitmap()
                    .apply(viewerImageOptions())
                    .load(url)
                    .into(imageTarget);
        }
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
        if(holder.imageTarget == null)
            return;
        CustomTarget<Bitmap> target = holder.imageTarget;
        holder.imageTarget = null;
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

    private boolean isActiveHolder(ImgViewHolder holder, PageItem item, CustomTarget<Bitmap> target) {
        return holder.imageTarget == target && isHolderStillBound(holder, item);
    }

    private boolean isHolderStillBound(ImgViewHolder holder, PageItem item) {
        int position = holder.getAdapterPosition();
        return position != RecyclerView.NO_POSITION
                && position < items.size()
                && items.get(position) == item;
    }

    private void preloadAhead(int adapterPosition) {
        int preloaded = 0;
        int preloadLimit = p.getDataSave() ? DATA_SAVE_PRELOAD_AHEAD_COUNT : PRELOAD_AHEAD_COUNT;
        for(int i = adapterPosition + 1; i < items.size() && preloaded < preloadLimit; i++) {
            Object next = items.get(i);
            if(next instanceof PageItem) {
                preloadPage((PageItem) next);
                preloaded++;
            }
        }
    }

    private void preloadPage(PageItem page) {
        String key = preloadKey(page);
        if(!preloadedImages.add(key))
            return;
        trimPreloadTracker();
        Glide.with(mainContext)
                .asBitmap()
                .apply(viewerImageOptions())
                .load(getImageModel(page))
                .preload();
    }

    private String preloadKey(PageItem page) {
        if(page == null || page.manga == null)
            return "";
        return page.manga.getBaseMode() + ":" + page.img;
    }

    private String decodedCacheKey(PageItem page) {
        if(page == null || page.manga == null)
            return "";
        return page.manga.getBaseMode() + ":" + page.manga.getId() + ":" + width + ":" + page.side + ":" + page.img;
    }

    private int decodedCacheSizeKb() {
        int maxMemoryKb = (int)(Runtime.getRuntime().maxMemory() / 1024);
        int targetKb = maxMemoryKb / (p.getDataSave() ? 16 : 8);
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
            preloadAhead(layoutPos);
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
            imageHolder.frame.setMinimumHeight(Math.max(width, 1));
            imageHolder.frame.setImageResource(R.drawable.placeholder);
            imageHolder.refresh.setVisibility(View.VISIBLE);
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
