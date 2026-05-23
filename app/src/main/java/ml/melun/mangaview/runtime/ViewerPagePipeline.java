package ml.melun.mangaview.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.bumptech.glide.Priority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;

import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

public final class ViewerPagePipeline {
    public interface Listener {
        void onEpisodePrepared(Manga episode);
        void onEpisodePrepareFailed(Manga episode, int result);
    }

    private static final int PREPARED_LIMIT = 192;
    private static final int IN_FLIGHT_LIMIT = 96;
    private static final int CANCELLATION_LIMIT = 96;
    private static final int PAGE_BUCKET_SIZE = 8;

    private final Context context;
    private final Title title;
    private final int width;
    private final boolean autoCut;
    private final boolean reverse;
    private final boolean dataSave;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Map<String, Integer> preparedStrengths = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final List<AppDispatchers.TaskHandle> handles = new ArrayList<>();
    private final List<MangaRepository.Cancellation> cancellations = new ArrayList<>();
    private volatile boolean released = false;

    public ViewerPagePipeline(Context context, Title title, int width, boolean autoCut, boolean reverse,
                              boolean dataSave, Listener listener) {
        this.context = context == null ? null : context.getApplicationContext();
        this.title = title;
        this.width = width;
        this.autoCut = autoCut;
        this.reverse = reverse;
        this.dataSave = dataSave;
        this.listener = listener;
    }

    public void release() {
        released = true;
        List<AppDispatchers.TaskHandle> handleSnapshot;
        List<MangaRepository.Cancellation> cancellationSnapshot;
        synchronized (this) {
            handleSnapshot = new ArrayList<>(handles);
            cancellationSnapshot = new ArrayList<>(cancellations);
            handles.clear();
            cancellations.clear();
            inFlight.clear();
            preparedStrengths.clear();
        }
        for(MangaRepository.Cancellation cancellation : cancellationSnapshot)
            if(cancellation != null)
                cancellation.cancel();
        for(AppDispatchers.TaskHandle handle : handleSnapshot)
            if(handle != null)
                handle.cancel();
    }

    public void prepareCurrentWindow(Manga target, int pageIndex) {
        prepareEpisode(target, pageIndex, forwardUrlWindow(dataSave), initialDiskWindow(dataSave),
                forwardDecodedWindow(dataSave), Priority.IMMEDIATE);
    }

    public void prepareScrollWindow(Manga target, int pageIndex, int direction, boolean busy) {
        if(hasPreparedImages(target) && busy)
            return;
        if(busy && dataSave)
            prepareEpisode(target, pageIndex, 4, 0, 0, Priority.HIGH);
        else if(busy)
            prepareEpisode(target, pageIndex, 8, 0, 0, Priority.HIGH);
        else
            prepareEpisode(target, pageIndex, forwardUrlWindow(dataSave), scrollDiskWindow(dataSave),
                    idleDecodedWindow(dataSave), Priority.IMMEDIATE);
    }

    public void prepareNextEpisode(Manga target) {
        prepareEpisode(target, 0, forwardUrlWindow(dataSave), forwardDiskWindow(dataSave),
                boundaryDecodedWindow(dataSave), Priority.IMMEDIATE);
    }

    public void prepareFutureNextEpisode(Manga target) {
        prepareEpisode(target, 0, forwardUrlWindow(dataSave), futureDiskWindow(dataSave),
                futureDecodedWindow(dataSave), Priority.HIGH);
    }

    public void preparePreviousEpisode(Manga target) {
        prepareEpisode(target, 0, dataSave ? 4 : 12, dataSave ? 3 : 8,
                0, Priority.HIGH);
    }

    public boolean hasPreparedImages(Manga target) {
        List<String> images = MangaRepository.imageUrls(target, context);
        return images != null && images.size() > 0;
    }

    private void prepareEpisode(Manga target, int pageIndex, int urlLimit, int diskLimit,
                                int decodedLimit, Priority priority) {
        if(released || context == null || target == null || !target.isOnline())
            return;
        attachTitle(target);
        int normalizedPage = Math.max(0, pageIndex);
        int normalizedUrlLimit = Math.max(1, urlLimit);
        int normalizedDiskLimit = Math.max(0, diskLimit);
        int normalizedDecodedLimit = Math.max(0, Math.min(decodedLimit, normalizedDiskLimit));
        if(hasPreparedImages(target) && normalizedDiskLimit == 0 && normalizedDecodedLimit == 0)
            return;
        int strength = requestStrength(normalizedUrlLimit, normalizedDiskLimit, normalizedDecodedLimit, priority);
        String key = requestKey(target, normalizedPage);
        String flightKey = key + ":" + normalizedUrlLimit + ":" + normalizedDiskLimit + ":" + normalizedDecodedLimit;
        synchronized (this) {
            Integer prepared = preparedStrengths.get(key);
            if(prepared != null && prepared >= strength)
                return;
            if(!inFlight.add(flightKey))
                return;
            trimSet(inFlight, IN_FLIGHT_LIMIT);
        }
        MangaRepository.Cancellation cancellation = MangaRepository.cancellation();
        synchronized (this) {
            cancellations.add(cancellation);
            trimCancellationsLocked();
        }
        AppDispatchers.TaskHandle handle = AppDispatchers.submitImageWarmup(() -> {
            int result = LOAD_OK;
            long startedAt = SystemClock.elapsedRealtime();
            try {
                if(!released && !cancellation.isCancelled())
                    result = prepareEpisodeOnWorker(target, normalizedPage, normalizedDiskLimit,
                            normalizedDecodedLimit, priority, cancellation);
            } catch (Exception e) {
                result = Title.LOAD_ERROR;
                if(!released && !cancellation.isCancelled())
                    ml.melun.mangaview.report.CrashReporter.record(e);
            } finally {
                synchronized (ViewerPagePipeline.this) {
                    inFlight.remove(flightKey);
                    cancellations.remove(cancellation);
                    if(result == LOAD_OK && hasPreparedImages(target)) {
                        preparedStrengths.put(key, strength);
                        trimMap(preparedStrengths, PREPARED_LIMIT);
                    }
                }
            }
            int finalResult = result;
            if(finalResult == LOAD_OK && hasPreparedImages(target))
                ViewerWarmupManager.logMetric("viewer_pipeline_episode_ready_ms",
                        SystemClock.elapsedRealtime() - startedAt);
            postResult(target, finalResult);
        });
        synchronized (this) {
            handles.add(handle);
            trimHandlesLocked();
        }
    }

    private int prepareEpisodeOnWorker(Manga target, int pageIndex, int diskLimit, int decodedLimit,
                                       Priority priority, MangaRepository.Cancellation cancellation) throws Exception {
        int result = LOAD_OK;
        if(MangaRepository.imageUrls(target, context).size() == 0) {
            if(decodedLimit > 0)
                result = ViewerWarmupManager.prepareFirstFrameBackgroundDirectOnly(context, target, title,
                        pageIndex, width, autoCut, reverse, cancellation);
            else
                result = ViewerWarmupManager.prepareFirstFrameSourceOnlyDirectOnly(context, target, title,
                        pageIndex, width, autoCut, reverse, cancellation);
        }
        if(result == LOAD_OK && MangaRepository.imageUrls(target, context).size() > 0) {
            ViewerWarmupManager.cacheLoadedContinueSnapshot(context, target, target, title, pageIndex, pageIndex);
            if(diskLimit > 0 || decodedLimit > 0)
                ViewerWarmupManager.preloadLoadedImages(context, target, pageIndex, width, autoCut, reverse,
                        diskLimit, priority, decodedLimit);
        }
        return result;
    }

    private void postResult(Manga target, int result) {
        if(listener == null || released)
            return;
        mainHandler.post(() -> {
            if(released)
                return;
            if(result == LOAD_OK && hasPreparedImages(target))
                listener.onEpisodePrepared(target);
            else
                listener.onEpisodePrepareFailed(target, result);
        });
    }

    private void attachTitle(Manga target) {
        if(title == null || target == null)
            return;
        target.setTitle(title);
        target.setTitleId(title.getId());
        List<Manga> episodes = Utils.snapshotEpisodes(title);
        if(episodes.size() == 0)
            return;
        target.setEps(episodes);
        for(Manga episode : episodes) {
            if(episode == null)
                continue;
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episode.setEps(episodes);
        }
    }

    private String requestKey(Manga manga, int pageIndex) {
        return episodeKey(manga, title) + ":" + pageBucket(pageIndex);
    }

    private static int pageBucket(int pageIndex) {
        return Math.max(0, pageIndex) / PAGE_BUCKET_SIZE;
    }

    private static String episodeKey(Manga manga, Title title) {
        if(manga == null)
            return "";
        String source = title == null || title.getSourceSite() == null ? "" : title.getSourceSite().trim();
        String path = manga.getNtkEpisodePath();
        int titleId = title == null ? manga.getTitleId() : title.getId();
        return source + ":" + safe(path) + ":" + manga.getBaseMode() + ":" + titleId + ":" + manga.getId();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static int requestStrength(int urlLimit, int diskLimit, int decodedLimit, Priority priority) {
        int priorityWeight = priority == Priority.IMMEDIATE ? 3 : (priority == Priority.HIGH ? 2 : 1);
        return Math.max(1, urlLimit) * 10000
                + Math.max(1, diskLimit) * 100
                + Math.max(0, decodedLimit) * 10
                + priorityWeight;
    }

    static int requestStrengthForTest(int urlLimit, int diskLimit, int decodedLimit, Priority priority) {
        return requestStrength(urlLimit, diskLimit, decodedLimit, priority);
    }

    static boolean shouldScheduleRequestForTest(Integer preparedStrength, int requestedStrength, boolean inFlight) {
        return !inFlight && (preparedStrength == null || preparedStrength < requestedStrength);
    }

    static int pageBucketForTest(int pageIndex) {
        return pageBucket(pageIndex);
    }

    public static int forwardUrlWindow(boolean dataSave) {
        return dataSave ? 6 : 24;
    }

    public static int initialDiskWindow(boolean dataSave) {
        return dataSave ? 2 : 6;
    }

    public static int scrollDiskWindow(boolean dataSave) {
        return dataSave ? 2 : 6;
    }

    public static int forwardDiskWindow(boolean dataSave) {
        return dataSave ? 3 : 8;
    }

    public static int forwardDecodedWindow(boolean dataSave) {
        return 0;
    }

    public static int idleDecodedWindow(boolean dataSave) {
        return 0;
    }

    public static int boundaryDecodedWindow(boolean dataSave) {
        return 0;
    }

    public static int futureDiskWindow(boolean dataSave) {
        return dataSave ? 1 : 4;
    }

    public static int futureDecodedWindow(boolean dataSave) {
        return 0;
    }

    public static int nextEpisodeDepth(boolean dataSave) {
        return dataSave ? 1 : 3;
    }

    public static int previousEpisodeDepth(boolean dataSave) {
        return 0;
    }

    private void trimHandlesLocked() {
        for(Iterator<AppDispatchers.TaskHandle> iterator = handles.iterator(); iterator.hasNext();) {
            AppDispatchers.TaskHandle handle = iterator.next();
            if(handle == null || handle.isDone())
                iterator.remove();
        }
        while(handles.size() > CANCELLATION_LIMIT)
            handles.remove(0);
    }

    private void trimCancellationsLocked() {
        while(cancellations.size() > CANCELLATION_LIMIT) {
            MangaRepository.Cancellation cancellation = cancellations.remove(0);
            if(cancellation != null)
                cancellation.cancel();
        }
    }

    private static <T> void trimSet(Set<T> set, int limit) {
        while(set.size() > limit) {
            Iterator<T> iterator = set.iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }

    private static <K, V> void trimMap(Map<K, V> map, int limit) {
        while(map.size() > limit) {
            Iterator<K> iterator = map.keySet().iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }
}
