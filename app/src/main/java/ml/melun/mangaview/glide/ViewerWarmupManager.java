package ml.melun.mangaview.glide;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerfTrace;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

public class ViewerWarmupManager {
    public static final int LOAD_EMPTY_IMAGES = -2;
    private static final String TAG = "ViewerPerf";
    private static final int ACTIVE_LIMIT = 40;
    private static final int DECODED_TARGET_LIMIT = 48;
    private static final int SNAPSHOT_LIMIT = 64;
    private static final long SNAPSHOT_TTL_MS = 2 * 60 * 1000L;
    private static final Map<String, WarmupState> activeWarmups = new HashMap<>();
    private static final LinkedHashMap<String, WarmupSnapshot> snapshots = new LinkedHashMap<>(SNAPSHOT_LIMIT, 0.75f, true);
    private static final Map<String, CustomTarget<Bitmap>> decodedTargets = new HashMap<>();
    private static final LruCache<String, Bitmap> decodedBitmapCache = new LruCache<String, Bitmap>(decodedCacheSizeKb()) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 1 : Math.max(1, value.getByteCount() / 1024);
        }
    };

    public static void warmup(Context context, Manga manga, Title title) {
        if(context == null || manga == null || !manga.isOnline())
            return;
        if(title != null) {
            manga.setTitle(title);
            manga.setTitleId(title.getId());
        } else {
            title = manga.getTitle();
        }
        int width = viewerWidth(context);
        int pageIndex = manga.useBookmark() ? p.getViewerBookmark(manga) : 0;
        if(pageIndex < 0)
            pageIndex = 0;
        String key = episodeKey(manga, title);
        WarmupState state = markActive(key);
        if(state == null)
            return;
        Context appContext = context.getApplicationContext();
        int startPage = pageIndex;
        AppDispatchers.submitImageWarmup(() -> {
            int result = LOAD_OK;
            try {
                if(manga.getImgs(appContext) == null || manga.getImgs(appContext).size() == 0)
                    result = manga.fetchForViewerInitial(getHttpClient());
                if(result == LOAD_OK)
                    cacheSnapshot(key, manga);
                preloadWindow(appContext, manga, startPage, width, false, p.getReverse(), ViewerPreloadPolicy.firstFrameWindow(p.getDataSave()));
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            } finally {
                finishActive(key, state, result);
            }
        });
    }

    public static int applyWarmupResult(Manga target, long waitMs) {
        if(target == null || !target.isOnline())
            return LOAD_OK;
        String key = episodeKey(target, target.getTitle());
        if(applySnapshot(key, target))
            return LOAD_OK;
        WarmupState state;
        synchronized (ViewerWarmupManager.class) {
            state = activeWarmups.get(key);
        }
        if(state == null)
            return LOAD_OK;
        try {
            state.done.await(Math.max(0, waitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        applySnapshot(key, target);
        return state.result;
    }

    public static int prepareFirstFrame(Context context, Manga manga, Title title, int pageIndex, int width,
                                        boolean autoCut, boolean reverse, MangaRepository.Cancellation cancellation) throws Exception {
        if(context == null || manga == null || !manga.isOnline())
            return LOAD_OK;
        if(title != null) {
            manga.setTitle(title);
            manga.setTitleId(title.getId());
        } else {
            title = manga.getTitle();
        }
        int normalizedPage = normalizePageIndex(manga, context, pageIndex);
        String key = episodeKey(manga, title);
        long urlStart = SystemClock.elapsedRealtime();
        boolean snapshotHit = applySnapshot(key, manga);
        int result = LOAD_OK;
        if(!snapshotHit && !hasImages(manga, context)) {
            result = MangaRepository.fetchViewerInitial(manga, cancellation);
            if(result == LOAD_OK)
                cacheSnapshot(key, manga);
        }
        if(result == LOAD_OK && !hasImages(manga, context)) {
            result = MangaRepository.fetchManga(manga);
            if(result == LOAD_OK)
                cacheSnapshot(key, manga);
        }
        if(result == LOAD_OK && hasImages(manga, context)) {
            normalizedPage = normalizePageIndex(manga, context, normalizedPage);
            logMetric("viewer_first_url_ms", SystemClock.elapsedRealtime() - urlStart);
            preloadWindow(context, manga, normalizedPage, width, autoCut, reverse, ViewerPreloadPolicy.firstFrameWindow(p.getDataSave()));
        } else if(result == LOAD_OK) {
            logMetric("viewer_empty_images", manga.getId());
            result = LOAD_EMPTY_IMAGES;
        }
        return result;
    }

    public static Bitmap getDecodedBitmap(PageItem page, boolean autoCut, boolean reverse, int width) {
        String key = decodedPageKey(page, autoCut, reverse, width);
        if(key.length() == 0)
            return null;
        synchronized (ViewerWarmupManager.class) {
            Bitmap cached = decodedBitmapCache.get(key);
            if(cached != null && !cached.isRecycled())
                return cached;
            if(cached != null)
                decodedBitmapCache.remove(key);
        }
        return null;
    }

    public static void clearDecodedWork(Context context) {
        List<CustomTarget<Bitmap>> targets;
        synchronized (ViewerWarmupManager.class) {
            targets = new ArrayList<>(decodedTargets.values());
            decodedTargets.clear();
            decodedBitmapCache.evictAll();
        }
        if(context == null)
            return;
        for(CustomTarget<Bitmap> target : targets) {
            try {
                Glide.with(context).clear(target);
            } catch (Exception ignored) {
            }
        }
    }

    public static void preloadLoadedImages(Context context, Manga manga, int pageIndex, int width, boolean autoCut, boolean reverse, int limit, Priority priority) {
        preloadLoadedImages(context, manga, pageIndex, width, autoCut, reverse, limit, priority, p.getDataSave() ? 1 : 2);
    }

    public static void preloadLoadedImages(Context context, Manga manga, int pageIndex, int width, boolean autoCut,
                                           boolean reverse, int limit, Priority priority, int decodedLimit) {
        if(context == null || manga == null || !manga.isOnline())
            return;
        List<String> images = manga.getImgs(context);
        if(images == null || images.size() == 0)
            return;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        int end = Math.min(images.size(), pageIndex + Math.max(1, limit));
        for(int i = pageIndex; i < end; i++) {
            PageItem page = new PageItem(i, images.get(i), manga);
            RequestOptions options = viewerOptions(page, autoCut, reverse, width);
            boolean cacheDecoded = i - pageIndex < decodedLimit;
            if(cacheDecoded) {
                preloadDecoded(context, page, options, priority, autoCut, reverse, width);
                continue;
            }
            Glide.with(context)
                    .asBitmap()
                    .priority(priority)
                    .apply(options)
                    .load(Utils.getGlideUrl(images.get(i), manga.getBaseMode()))
                    .preload();
        }
    }

    public static void preloadWindow(Context context, Manga manga, int pageIndex, int width, boolean autoCut,
                                     boolean reverse, ViewerPreloadPolicy.Window window) {
        if(context == null || manga == null || !manga.isOnline() || window == null)
            return;
        List<String> images = manga.getImgs(context);
        if(images == null || images.size() == 0)
            return;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        int preloaded = 0;
        int end = Math.min(images.size(), pageIndex + window.totalLimit);
        for(int i = pageIndex; i < end; i++) {
            PageItem page = new PageItem(i, images.get(i), manga);
            RequestOptions options = viewerOptions(page, autoCut, reverse, width);
            int tier = ViewerPreloadPolicy.tierForOffset(window, preloaded);
            if(tier == ViewerPreloadPolicy.TIER_DECODED) {
                preloadDecoded(context, page, options, Priority.IMMEDIATE, autoCut, reverse, width);
            } else {
                Glide.with(context)
                        .asBitmap()
                        .priority(priorityForTier(tier))
                        .apply(options)
                        .load(Utils.getGlideUrl(images.get(i), manga.getBaseMode()))
                        .preload();
            }
            preloaded++;
        }
    }

    public static String decodedPageKey(PageItem page, boolean autoCut, boolean reverse, int width) {
        if(page == null || page.manga == null)
            return "";
        return page.pageKey(autoCut, reverse, width);
    }

    private static RequestOptions viewerOptions(PageItem page, boolean autoCut, boolean reverse, int width) {
        RequestOptions options = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .downsample(DownsampleStrategy.AT_MOST)
                .override(Math.max(width, 1), Target.SIZE_ORIGINAL);
        if(page != null)
            options = options.transform(new ViewerPageTransformation(page, autoCut, reverse, width));
        return options;
    }

    private static void preloadDecoded(Context context, PageItem page, RequestOptions options, Priority priority, boolean autoCut, boolean reverse, int width) {
        if(page == null || page.manga == null || page.img == null)
            return;
        String key = decodedPageKey(page, autoCut, reverse, width);
        if(key.length() == 0)
            return;
        synchronized (ViewerWarmupManager.class) {
            Bitmap cached = decodedBitmapCache.get(key);
            if(cached != null && !cached.isRecycled())
                return;
            if(decodedTargets.containsKey(key))
                return;
        }
        long decodeStart = SystemClock.elapsedRealtime();
        CustomTarget<Bitmap> target = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(Bitmap resource, Transition<? super Bitmap> transition) {
                synchronized (ViewerWarmupManager.class) {
                    decodedTargets.remove(key);
                    if(resource != null && !resource.isRecycled())
                        decodedBitmapCache.put(key, detachBitmap(resource, width));
                }
                if(page.index == 0)
                    logMetric("viewer_first_decode_ms", SystemClock.elapsedRealtime() - decodeStart);
            }

            @Override
            public void onLoadCleared(Drawable placeholder) {
                synchronized (ViewerWarmupManager.class) {
                    decodedTargets.remove(key);
                }
            }

            @Override
            public void onLoadFailed(Drawable errorDrawable) {
                synchronized (ViewerWarmupManager.class) {
                    decodedTargets.remove(key);
                }
            }
        };
        synchronized (ViewerWarmupManager.class) {
            decodedTargets.put(key, target);
            trimDecodedTargets();
        }
        Glide.with(context)
                .asBitmap()
                .priority(priority)
                .apply(options)
                .load(Utils.getGlideUrl(page.img, page.manga.getBaseMode()))
                .into(target);
    }

    private static Priority priorityForTier(int tier) {
        if(tier == ViewerPreloadPolicy.TIER_DECODED || tier == ViewerPreloadPolicy.TIER_IMMEDIATE)
            return Priority.IMMEDIATE;
        if(tier == ViewerPreloadPolicy.TIER_HIGH)
            return Priority.HIGH;
        return Priority.NORMAL;
    }

    private static int normalizePageIndex(Manga manga, Context context, int pageIndex) {
        List<String> images = manga == null ? null : manga.getImgs(context);
        if(images == null || images.size() == 0)
            return Math.max(0, pageIndex);
        if(pageIndex < 0 || pageIndex >= images.size())
            return 0;
        return pageIndex;
    }

    private static boolean hasImages(Manga manga, Context context) {
        List<String> images = manga == null ? null : manga.getImgs(context);
        return images != null && images.size() > 0;
    }

    public static void logMetric(String name, long valueMs) {
        Log.d(TAG, name + "=" + valueMs);
        PerfTrace.mark(name, valueMs);
    }

    private static Bitmap detachBitmap(Bitmap source, int width) {
        if(source == null)
            return Bitmap.createBitmap(Math.max(width, 1), 1, Bitmap.Config.ARGB_8888);
        Bitmap.Config config = source.getConfig() == null ? Bitmap.Config.ARGB_8888 : source.getConfig();
        return source.copy(config, false);
    }

    private static int viewerWidth(Context context) {
        if(context instanceof Activity)
            return Utils.getScreenSize(((Activity) context).getWindowManager().getDefaultDisplay());
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.min(Math.max(metrics.widthPixels, metrics.heightPixels), 3000);
    }

    private static String episodeKey(Manga manga, Title title) {
        int titleId = title == null ? manga.getTitleId() : title.getId();
        return manga.getBaseMode() + ":" + titleId + ":" + manga.getId();
    }

    private static synchronized WarmupState markActive(String key) {
        if(activeWarmups.containsKey(key))
            return null;
        WarmupState state = new WarmupState();
        activeWarmups.put(key, state);
        trimActive();
        return state;
    }

    private static synchronized void finishActive(String key, WarmupState state, int result) {
        state.result = result;
        activeWarmups.remove(key);
        state.done.countDown();
    }

    private static synchronized void cacheSnapshot(String key, Manga manga) {
        WarmupSnapshot snapshot = new WarmupSnapshot(manga);
        if(snapshot.images.size() == 0)
            return;
        snapshots.put(key, snapshot);
        trimSnapshots();
    }

    private static synchronized boolean applySnapshot(String key, Manga target) {
        WarmupSnapshot snapshot = snapshots.get(key);
        if(snapshot == null)
            return false;
        if(System.currentTimeMillis() - snapshot.createdAt > SNAPSHOT_TTL_MS) {
            snapshots.remove(key);
            return false;
        }
        return snapshot.applyTo(target);
    }

    private static void trimActive() {
        while(activeWarmups.size() > ACTIVE_LIMIT) {
            Iterator<String> iterator = activeWarmups.keySet().iterator();
            if(!iterator.hasNext())
                return;
            WarmupState removed = activeWarmups.remove(iterator.next());
            if(removed != null)
                removed.done.countDown();
        }
    }

    private static void trimSnapshots() {
        while(snapshots.size() > SNAPSHOT_LIMIT) {
            Iterator<String> iterator = snapshots.keySet().iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }

    private static void trimDecodedTargets() {
        while(decodedTargets.size() > DECODED_TARGET_LIMIT) {
            Iterator<String> iterator = decodedTargets.keySet().iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }

    private static int decodedCacheSizeKb() {
        int maxMemoryKb = (int)(Runtime.getRuntime().maxMemory() / 1024);
        boolean dataSave = p != null && p.getDataSave();
        int targetKb = maxMemoryKb / (dataSave ? 20 : 10);
        int minKb = dataSave ? 3 * 1024 : 6 * 1024;
        int maxKb = dataSave ? 10 * 1024 : 24 * 1024;
        return Math.max(minKb, Math.min(targetKb, maxKb));
    }

    private static class WarmupState {
        final CountDownLatch done = new CountDownLatch(1);
        volatile int result = LOAD_OK;
    }

    private static class WarmupSnapshot {
        final int baseMode;
        final int titleId;
        final int episodeId;
        final String name;
        final int seed;
        final Title title;
        final ArrayList<String> images;
        final ArrayList<Manga> episodes;
        final long createdAt = System.currentTimeMillis();

        WarmupSnapshot(Manga source) {
            baseMode = source.getBaseMode();
            titleId = source.getTitleId();
            episodeId = source.getId();
            name = source.getName();
            seed = source.getSeed();
            title = source.getTitle();
            List<String> sourceImages = source.getImgs(null);
            images = sourceImages == null ? new ArrayList<>() : new ArrayList<>(sourceImages);
            List<Manga> sourceEpisodes = source.getEps();
            episodes = sourceEpisodes == null ? new ArrayList<>() : new ArrayList<>(sourceEpisodes);
        }

        boolean applyTo(Manga target) {
            if(target == null
                    || target.getId() != episodeId
                    || target.getBaseMode() != baseMode
                    || target.getTitleId() != titleId)
                return false;
            Manga copy = new Manga(episodeId, name, target.getDate(), baseMode);
            copy.setTitleId(titleId);
            copy.setSeed(seed);
            copy.setImgs(images);
            copy.setEps(episodes);
            copy.setTitle(title);
            return target.copyViewerStateFrom(copy);
        }
    }
}
