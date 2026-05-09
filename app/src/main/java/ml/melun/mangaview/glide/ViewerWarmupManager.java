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
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.ViewerResumeResolver;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;
import ml.melun.mangaview.repository.CacheFileStore;
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
    private static final long DISK_SNAPSHOT_TTL_MS = 20 * 60 * 1000L;
    private static final long CONTINUE_WARMUP_DEBOUNCE_MS = 800L;
    private static final Gson GSON = new Gson();
    private static final Map<String, WarmupState> activeWarmups = new HashMap<>();
    private static final LinkedHashMap<String, WarmupSnapshot> snapshots = new LinkedHashMap<>(SNAPSHOT_LIMIT, 0.75f, true);
    private static final LinkedHashMap<String, WarmupSnapshot> continueSnapshots = new LinkedHashMap<>(SNAPSHOT_LIMIT, 0.75f, true);
    private static final LinkedHashMap<String, Long> recentContinueWarmups = new LinkedHashMap<>(SNAPSHOT_LIMIT, 0.75f, true);
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
        if(shouldSkipNtkWarmup())
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
                    cacheSnapshot(appContext, key, manga);
                preloadWindow(appContext, manga, startPage, width, false, p.getReverse(), ViewerPreloadPolicy.firstFrameWindow(p.getDataSave()));
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            } finally {
                finishActive(key, state, result);
            }
        });
    }

    public static void warmupContinue(Context context, Manga manga, Title title) {
        warmupContinue(context, manga, title, false);
    }

    public static void warmupContinueImmediate(Context context, Manga manga, Title title) {
        warmupContinue(context, manga, title, true);
    }

    public static void warmupSavedContinues(Context context, int limit) {
        if(context == null || p == null)
            return;
        List<MTitle> recent = Utils.snapshotList(p.getRecent());
        if(recent == null || recent.size() == 0)
            return;
        int warmed = 0;
        for(MTitle item : recent) {
            if(item == null || item.getId() <= 0)
                continue;
            Title title = item instanceof Title ? (Title) item : new Title(item);
            int bookmark = p.getBookmark(title);
            if(bookmark <= 0)
                bookmark = title.getBookmark();
            if(bookmark <= 0)
                bookmark = item.getBookmarkEpisodeId();
            if(bookmark <= 0)
                continue;
            title.setBookmark(bookmark);
            Manga manga = new Manga(bookmark, "", "", title.getBaseMode());
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            warmupContinueImmediate(context, manga, title);
            warmed++;
            if(limit > 0 && warmed >= limit)
                return;
        }
    }

    private static void warmupContinue(Context context, Manga manga, Title title, boolean immediate) {
        if(context == null || manga == null || !manga.isOnline())
            return;
        if(shouldSkipNtkWarmup())
            return;
        if(title != null) {
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(title);
            if(episodes.size() > 0)
                manga.setEps(episodes);
        } else {
            title = manga.getTitle();
        }
        Title warmupTitle = title;
        Context appContext = context.getApplicationContext();
        int width = viewerWidth(context);
        int firstPage = manga.useBookmark() ? p.getViewerBookmark(manga) : 0;
        if(firstPage < 0)
            firstPage = 0;
        int startPage = firstPage;
        String scheduleKey = continueWarmupKey(manga, title, startPage);
        if(!immediate && !shouldScheduleContinueWarmup(scheduleKey))
            return;
        AppDispatchers.submitImageWarmup(() -> {
            try {
                Manga target = manga;
                Title currentTitle = warmupTitle != null ? warmupTitle : target.getTitle();
                if(currentTitle != null && Utils.snapshotEpisodes(currentTitle).size() <= 1) {
                    int result = MangaRepository.fetchEpisodes(currentTitle);
                    if(result == LOAD_OK)
                        attachTitle(currentTitle, target);
                }
                boolean skipTarget = ViewerResumeResolver.shouldResolveBeforeDirectFetch(target, currentTitle);
                List<Manga> candidates = ViewerResumeResolver.candidates(target, currentTitle, skipTarget);
                if(candidates.size() == 0)
                    candidates.add(target);
                for(Manga candidate : candidates) {
                    int page = ViewerResumeResolver.sameManga(candidate, target) ? startPage : 0;
                    int result = prepareFirstFrame(appContext, candidate, currentTitle, page, width, false, p.getReverse(), MangaRepository.cancellation());
                    if(result == LOAD_OK && hasImages(candidate, appContext)) {
                        preloadLoadedImages(appContext, candidate, page, width, false, p.getReverse(), p.getDataSave() ? 8 : 24, Priority.IMMEDIATE, p.getDataSave() ? 2 : 3);
                        cacheContinueSnapshot(appContext, scheduleKey, candidate);
                        logMetric("viewer_continue_warmup_ready", candidate.getId());
                        return;
                    }
                }
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        });
    }

    public static Manga prepareClickFirstFrame(Context context, Manga manga, Title title, boolean autoCut, boolean reverse) {
        if(context == null || manga == null)
            return null;
        if(!manga.isOnline())
            return manga;
        if(shouldSkipNtkWarmup())
            return manga;
        if(title != null) {
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(title);
            if(episodes.size() > 0)
                manga.setEps(episodes);
        } else {
            title = manga.getTitle();
        }
        Context appContext = context.getApplicationContext();
        int width = viewerWidth(context);
        int firstPage = manga.useBookmark() ? p.getViewerBookmark(manga) : 0;
        if(firstPage < 0)
            firstPage = 0;
        String scheduleKey = continueWarmupKey(manga, title, firstPage);
        Manga warmed = continueSnapshotManga(appContext, scheduleKey, manga);
        if(warmed != null) {
            if(title != null)
                attachTitle(title, warmed);
            if(hasDecodedFrame(appContext, warmed, firstPage, width, autoCut, reverse)) {
                preloadLoadedImages(appContext, warmed, firstPage, width, autoCut, reverse, p.getDataSave() ? 8 : 24, Priority.IMMEDIATE, p.getDataSave() ? 2 : 3);
                logMetric("viewer_click_continue_snapshot", warmed.getId());
                return warmed;
            }
        }
        try {
            Manga target = manga;
            Title currentTitle = title != null ? title : target.getTitle();
            if(currentTitle != null && Utils.snapshotEpisodes(currentTitle).size() <= 1) {
                int result = MangaRepository.fetchEpisodes(currentTitle);
                if(result == LOAD_OK)
                    attachTitle(currentTitle, target);
            }
            boolean skipTarget = ViewerResumeResolver.shouldResolveBeforeDirectFetch(target, currentTitle);
            List<Manga> candidates = ViewerResumeResolver.candidates(target, currentTitle, skipTarget);
            if(candidates.size() == 0)
                candidates.add(target);
            for(Manga candidate : candidates) {
                if(candidate == null)
                    continue;
                if(currentTitle != null)
                    attachTitle(currentTitle, candidate);
                int page = ViewerResumeResolver.sameManga(candidate, target) ? firstPage : 0;
                int result = prepareFirstFrame(appContext, candidate, currentTitle, page, width, autoCut, reverse, MangaRepository.cancellation());
                if(result != LOAD_OK || !hasImages(candidate, appContext))
                    continue;
                preloadLoadedImages(appContext, candidate, page, width, autoCut, reverse, p.getDataSave() ? 8 : 24, Priority.IMMEDIATE, p.getDataSave() ? 2 : 3);
                if(hasDecodedFrame(appContext, candidate, page, width, autoCut, reverse)) {
                    logMetric("viewer_click_ready", candidate.getId());
                    cacheContinueSnapshot(appContext, scheduleKey, candidate);
                    return candidate;
                } else {
                    logMetric("viewer_click_url_ready", candidate.getId());
                }
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        logMetric("viewer_click_first_frame_miss", manga.getId());
        return null;
    }

    public static Manga usePreparedFirstFrame(Context context, Manga manga, Title title, boolean autoCut, boolean reverse) {
        if(context == null || manga == null)
            return null;
        if(!manga.isOnline())
            return manga;
        if(title != null) {
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(title);
            if(episodes.size() > 0)
                manga.setEps(episodes);
        } else {
            title = manga.getTitle();
        }
        int width = viewerWidth(context);
        int firstPage = manga.useBookmark() ? p.getViewerBookmark(manga) : 0;
        if(firstPage < 0)
            firstPage = 0;
        Manga warmed = continueSnapshotManga(context, continueWarmupKey(manga, title, firstPage), manga);
        if(warmed != null) {
            if(title != null)
                attachTitle(title, warmed);
            if(hasDecodedFrame(context, warmed, firstPage, width, autoCut, reverse)) {
                preloadLoadedImages(context, warmed, firstPage, width, autoCut, reverse, p.getDataSave() ? 8 : 24, Priority.IMMEDIATE, p.getDataSave() ? 2 : 3);
                logMetric("viewer_click_immediate_snapshot", warmed.getId());
                return warmed;
            }
            if(hasImages(warmed, context)) {
                preloadLoadedImages(context, warmed, firstPage, width, autoCut, reverse, p.getDataSave() ? 8 : 24, Priority.IMMEDIATE, p.getDataSave() ? 2 : 3);
                logMetric("viewer_click_immediate_url_snapshot", warmed.getId());
                return warmed;
            }
        }
        if(hasImages(manga, context) && hasDecodedFrame(context, manga, firstPage, width, autoCut, reverse)) {
            preloadLoadedImages(context, manga, firstPage, width, autoCut, reverse, p.getDataSave() ? 8 : 24, Priority.IMMEDIATE, p.getDataSave() ? 2 : 3);
            logMetric("viewer_click_immediate_decoded", manga.getId());
            return manga;
        }
        if(hasImages(manga, context)) {
            preloadLoadedImages(context, manga, firstPage, width, autoCut, reverse, p.getDataSave() ? 8 : 24, Priority.IMMEDIATE, p.getDataSave() ? 2 : 3);
            logMetric("viewer_click_immediate_url", manga.getId());
            return manga;
        }
        return null;
    }

    public static int applyWarmupResult(Manga target, long waitMs) {
        if(target == null || !target.isOnline())
            return LOAD_OK;
        String key = episodeKey(target, target.getTitle());
        if(applySnapshot(null, key, target))
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
        applySnapshot(null, key, target);
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
        boolean snapshotHit = applySnapshot(context, key, manga);
        if(!snapshotHit)
            snapshotHit = waitForActiveSnapshot(context, key, manga, 220);
        int result = LOAD_OK;
        if(!snapshotHit && !hasImages(manga, context)) {
            result = MangaRepository.fetchViewerInitial(manga, cancellation);
            if(result == LOAD_OK)
                cacheSnapshot(context, key, manga);
        }
        if(result == LOAD_OK && !hasImages(manga, context)) {
            result = MangaRepository.fetchManga(manga);
            if(result == LOAD_OK)
                cacheSnapshot(context, key, manga);
        }
        if(result == LOAD_OK && hasImages(manga, context)) {
            normalizedPage = normalizePageIndex(manga, context, normalizedPage);
            logMetric("viewer_first_url_ms", SystemClock.elapsedRealtime() - urlStart);
            decodeFirstPagesBlocking(context, manga, normalizedPage, width, autoCut, reverse);
            preloadWindow(context, manga, normalizedPage, width, autoCut, reverse, ViewerPreloadPolicy.firstFrameWindow(p.getDataSave()));
        } else if(result == LOAD_OK) {
            logMetric("viewer_empty_images", manga.getId());
            result = LOAD_EMPTY_IMAGES;
        }
        return result;
    }

    private static void attachTitle(Title title, Manga target) {
        if(title == null || target == null)
            return;
        target.setTitle(title);
        target.setTitleId(title.getId());
        List<Manga> episodes = Utils.snapshotEpisodes(title);
        if(episodes.size() == 0)
            return;
        target.setEps(episodes);
        for(Manga episode : episodes) {
            if(episode != null) {
                episode.setTitle(title);
                episode.setTitleId(title.getId());
                episode.setEps(episodes);
            }
        }
    }

    private static boolean waitForActiveSnapshot(Context context, String key, Manga target, long waitMs) {
        WarmupState state;
        synchronized (ViewerWarmupManager.class) {
            state = activeWarmups.get(key);
        }
        if(state == null)
            return false;
        try {
            state.done.await(Math.max(0, waitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return applySnapshot(context, key, target);
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
                        decodedBitmapCache.put(key, resource);
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

    private static boolean decodeFirstPagesBlocking(Context context, Manga manga, int pageIndex, int width, boolean autoCut, boolean reverse) {
        List<String> images = manga == null ? null : manga.getImgs(context);
        if(context == null || manga == null || images == null || images.size() == 0)
            return false;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        int decodedLimit = p.getDataSave() ? 2 : 3;
        int end = Math.min(images.size(), pageIndex + decodedLimit);
        long startedAt = SystemClock.elapsedRealtime();
        boolean firstReady = false;
        for(int i = pageIndex; i < end; i++) {
            PageItem page = new PageItem(i, images.get(i), manga);
            boolean decoded = decodePageBlocking(context, page, viewerOptions(page, autoCut, reverse, width), autoCut, reverse, width, i == pageIndex);
            if(i == pageIndex)
                firstReady = decoded || hasDecodedFrame(context, manga, pageIndex, width, autoCut, reverse);
        }
        logMetric("viewer_first_decode_blocking_ms", SystemClock.elapsedRealtime() - startedAt);
        return firstReady;
    }

    private static boolean decodePageBlocking(Context context, PageItem page, RequestOptions options,
                                           boolean autoCut, boolean reverse, int width, boolean firstPage) {
        if(page == null || page.manga == null || page.img == null)
            return false;
        String key = decodedPageKey(page, autoCut, reverse, width);
        if(key.length() == 0)
            return false;
        synchronized (ViewerWarmupManager.class) {
            Bitmap cached = decodedBitmapCache.get(key);
            if(cached != null && !cached.isRecycled())
                return true;
            if(cached != null)
                decodedBitmapCache.remove(key);
        }
        FutureTarget<Bitmap> target = null;
        boolean cachedResult = false;
        long timeoutMs = firstPage ? 6000L : 700L;
        long decodeStart = SystemClock.elapsedRealtime();
        try {
            target = Glide.with(context)
                    .asBitmap()
                    .priority(Priority.IMMEDIATE)
                    .apply(options)
                    .load(Utils.getGlideUrl(page.img, page.manga.getBaseMode()))
                    .submit();
            Bitmap bitmap = target.get(timeoutMs, TimeUnit.MILLISECONDS);
            if(bitmap != null && !bitmap.isRecycled()) {
                synchronized (ViewerWarmupManager.class) {
                    decodedBitmapCache.put(key, bitmap);
                }
                cachedResult = true;
                if(firstPage)
                    logMetric("viewer_first_decode_sync_ms", SystemClock.elapsedRealtime() - decodeStart);
            }
        } catch (Exception ignored) {
        } finally {
            if(target != null && !cachedResult) {
                try {
                    Glide.with(context).clear(target);
                } catch (Exception ignored) {
                }
            }
        }
        return cachedResult;
    }

    private static boolean hasDecodedFrame(Context context, Manga manga, int pageIndex, int width, boolean autoCut, boolean reverse) {
        List<String> images = manga == null ? null : manga.getImgs(context);
        if(images == null || images.size() == 0)
            return false;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        PageItem page = new PageItem(pageIndex, images.get(pageIndex), manga);
        Bitmap cached = getDecodedBitmap(page, autoCut, reverse, width);
        return cached != null && !cached.isRecycled();
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

    private static synchronized void cacheSnapshot(Context context, String key, Manga manga) {
        WarmupSnapshot snapshot = new WarmupSnapshot(manga);
        if(snapshot.images.size() == 0)
            return;
        snapshots.put(key, snapshot);
        writeDiskSnapshot(context, "viewerSnapshotV1_", key, snapshot);
        trimSnapshots();
    }

    private static synchronized void cacheContinueSnapshot(Context context, String key, Manga manga) {
        WarmupSnapshot snapshot = new WarmupSnapshot(manga);
        if(snapshot.images.size() == 0)
            return;
        continueSnapshots.put(key, snapshot);
        writeDiskSnapshot(context, "viewerContinueSnapshotV1_", key, snapshot);
        trimContinueSnapshots();
    }

    private static synchronized Manga continueSnapshotManga(Context context, String key, Manga fallback) {
        WarmupSnapshot snapshot = continueSnapshots.get(key);
        if(snapshot == null) {
            snapshot = readDiskSnapshot(context, "viewerContinueSnapshotV1_", key);
            if(snapshot != null)
                continueSnapshots.put(key, snapshot);
        }
        if(snapshot == null)
            return null;
        if(System.currentTimeMillis() - snapshot.createdAt > SNAPSHOT_TTL_MS) {
            continueSnapshots.remove(key);
            snapshot = readDiskSnapshot(context, "viewerContinueSnapshotV1_", key);
            if(snapshot == null)
                return null;
            continueSnapshots.put(key, snapshot);
        }
        return snapshot.toManga(fallback);
    }

    private static synchronized boolean applySnapshot(Context context, String key, Manga target) {
        WarmupSnapshot snapshot = snapshots.get(key);
        if(snapshot == null) {
            snapshot = readDiskSnapshot(context, "viewerSnapshotV1_", key);
            if(snapshot != null)
                snapshots.put(key, snapshot);
        }
        if(snapshot == null)
            return false;
        if(System.currentTimeMillis() - snapshot.createdAt > SNAPSHOT_TTL_MS) {
            snapshots.remove(key);
            snapshot = readDiskSnapshot(context, "viewerSnapshotV1_", key);
            if(snapshot == null)
                return false;
            snapshots.put(key, snapshot);
        }
        return snapshot.applyTo(target);
    }

    private static void writeDiskSnapshot(Context context, String prefix, String key, WarmupSnapshot snapshot) {
        if(context == null || snapshot == null || snapshot.images.size() == 0)
            return;
        try {
            CacheFileStore.write(context.getApplicationContext(), prefix + key, GSON.toJson(new PersistedSnapshot(snapshot)));
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static WarmupSnapshot readDiskSnapshot(Context context, String prefix, String key) {
        if(context == null || key == null)
            return null;
        try {
            String json = CacheFileStore.read(context.getApplicationContext(), prefix + key);
            if(json == null || json.length() == 0)
                return null;
            PersistedSnapshot persisted = GSON.fromJson(json, PersistedSnapshot.class);
            if(persisted == null || persisted.images == null || persisted.images.size() == 0)
                return null;
            if(System.currentTimeMillis() - persisted.createdAt > DISK_SNAPSHOT_TTL_MS)
                return null;
            return new WarmupSnapshot(persisted);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    private static synchronized boolean shouldScheduleContinueWarmup(String key) {
        long now = SystemClock.uptimeMillis();
        Long last = recentContinueWarmups.get(key);
        if(last != null && now - last < CONTINUE_WARMUP_DEBOUNCE_MS)
            return false;
        recentContinueWarmups.put(key, now);
        while(recentContinueWarmups.size() > SNAPSHOT_LIMIT) {
            Iterator<String> iterator = recentContinueWarmups.keySet().iterator();
            if(!iterator.hasNext())
                break;
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    private static String continueWarmupKey(Manga manga, Title title, int startPage) {
        int titleId = title == null ? manga.getTitleId() : title.getId();
        return manga.getBaseMode() + ":" + titleId + ":" + manga.getId() + ":" + Math.max(0, startPage);
    }

    private static boolean shouldSkipNtkWarmup() {
        return getHttpClient().isNtk() && !getHttpClient().hasCloudflareClearance();
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

    private static void trimContinueSnapshots() {
        while(continueSnapshots.size() > SNAPSHOT_LIMIT) {
            Iterator<String> iterator = continueSnapshots.keySet().iterator();
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
        int targetKb = maxMemoryKb / (dataSave ? 8 : 4);
        int minKb = dataSave ? 12 * 1024 : 32 * 1024;
        int maxKb = dataSave ? 32 * 1024 : 96 * 1024;
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
        final long createdAt;

        WarmupSnapshot(Manga source) {
            baseMode = source.getBaseMode();
            titleId = source.getTitleId();
            episodeId = source.getId();
            name = source.getName();
            seed = source.getSeed();
            title = source.getTitle();
            List<String> sourceImages = source.getImgs(null);
            images = Utils.snapshotList(sourceImages);
            episodes = Utils.snapshotEpisodes(source);
            createdAt = System.currentTimeMillis();
        }

        WarmupSnapshot(PersistedSnapshot source) {
            baseMode = source.baseMode;
            titleId = source.titleId;
            episodeId = source.episodeId;
            name = source.name;
            seed = source.seed;
            title = source.title;
            images = source.images == null ? new ArrayList<>() : new ArrayList<>(source.images);
            episodes = source.episodes == null ? new ArrayList<>() : new ArrayList<>(source.episodes);
            createdAt = source.createdAt;
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

        Manga toManga(Manga fallback) {
            Manga copy = new Manga(episodeId, name, fallback == null ? "" : fallback.getDate(), baseMode);
            copy.setTitleId(titleId);
            copy.setSeed(seed);
            copy.setImgs(images);
            copy.setEps(episodes);
            copy.setTitle(title);
            return copy;
        }
    }

    private static class PersistedSnapshot {
        int baseMode;
        int titleId;
        int episodeId;
        String name;
        int seed;
        Title title;
        ArrayList<String> images;
        ArrayList<Manga> episodes;
        long createdAt;

        PersistedSnapshot() {
        }

        PersistedSnapshot(WarmupSnapshot source) {
            baseMode = source.baseMode;
            titleId = source.titleId;
            episodeId = source.episodeId;
            name = source.name;
            seed = source.seed;
            title = source.title == null ? null : new Title(source.title.minimize());
            images = source.images == null ? new ArrayList<>() : new ArrayList<>(source.images);
            episodes = slimEpisodes(source.episodes);
            createdAt = source.createdAt;
        }

        private static ArrayList<Manga> slimEpisodes(List<Manga> sourceEpisodes) {
            ArrayList<Manga> copies = new ArrayList<>();
            if(sourceEpisodes == null)
                return copies;
            for(Manga episode : sourceEpisodes) {
                if(episode == null)
                    continue;
                Manga copy = new Manga(episode.getId(), episode.getName(), episode.getDate(), episode.getBaseMode());
                copy.addThumb(episode.getThumb());
                copy.setMode(episode.getMode());
                copy.setTitleId(episode.getTitleId());
                copies.add(copy);
            }
            return copies;
        }
    }
}
