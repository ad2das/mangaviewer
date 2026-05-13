package ml.melun.mangaview.repository;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import ml.melun.mangaview.model.UrlUpdateResult;
import ml.melun.mangaview.mangaview.Bookmark;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MainPage;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Ranking;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedList;
import ml.melun.mangaview.mangaview.UpdatedManga;
import ml.melun.mangaview.mangaview.WfwfDomainResolver;
import okhttp3.Response;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;

public final class MangaRepository {
    private static final long HOME_TTL_MS = 45_000L;
    private static final long SECTION_TTL_MS = 2 * 60_000L;
    private static final long NTK_HOME_TTL_MS = 10 * 60_000L;
    private static final long NTK_SECTION_TTL_MS = 10 * 60_000L;
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, FutureTask<Object>> IN_FLIGHT = new ConcurrentHashMap<>();

    private MangaRepository() {
    }

    public static Cancellation cancellation() {
        return new Cancellation();
    }

    public static Search createSearch(String query, int mode, int baseMode) {
        return new Search(query, mode, baseMode);
    }

    public static MainPageWebtoon createWebtoonParser(int baseMode) {
        return new MainPageWebtoon(baseMode);
    }

    public static MainPage loadComicHome(Cancellation cancellation) throws Exception {
        return loadComicHome(group(cancellation));
    }

    public static MainPage loadComicHome(CustomHttpClient.RequestGroup requestGroup) throws Exception {
        boolean ntk = getHttpClient().isNtk();
        String key = "home:" + (ntk ? "ntk:" : "wfwf:") + "comic";
        return cached(key, ntk ? NTK_HOME_TTL_MS : HOME_TTL_MS,
                () -> getHttpClient().runWithRequestGroup(requestGroup, () -> new MainPage(getHttpClient())));
    }

    public static int search(Search search, Cancellation cancellation) throws Exception {
        return search(search, group(cancellation));
    }

    public static int search(Search search, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null)
            return search.fetch(getHttpClient());
        return getHttpClient().runWithRequestGroup(requestGroup, () -> search.fetch(getHttpClient()));
    }

    public static int fetchBookmark(Bookmark bookmark, Cancellation cancellation) throws Exception {
        return fetchBookmark(bookmark, group(cancellation));
    }

    public static int fetchBookmark(Bookmark bookmark, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null)
            return bookmark.fetch(getHttpClient());
        return getHttpClient().runWithRequestGroup(requestGroup, () -> bookmark.fetch(getHttpClient()));
    }

    public static ArrayList<UpdatedManga> loadUpdates(UpdatedList updated, Cancellation cancellation) throws Exception {
        return loadUpdates(updated, group(cancellation));
    }

    public static ArrayList<UpdatedManga> loadUpdates(UpdatedList updated, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null) {
            updated.fetch(getHttpClient());
        } else {
            getHttpClient().runWithRequestGroup(requestGroup, () -> {
                updated.fetch(getHttpClient());
                return true;
            });
        }
        return updated.getResult();
    }

    public static int fetchEpisodes(Title title) {
        return title.fetchEps(getHttpClient());
    }

    public static int fetchManga(Manga manga) {
        return manga.fetch(getHttpClient());
    }

    public static int fetchViewerInitial(Manga manga, Cancellation cancellation) throws Exception {
        return fetchViewerInitial(manga, group(cancellation));
    }

    public static int fetchViewerInitial(Manga manga, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null)
            return manga.fetchForViewerInitial(getHttpClient());
        return getHttpClient().runWithRequestGroup(requestGroup, () -> manga.fetchForViewerInitial(getHttpClient()));
    }

    public static Ranking<Title> loadWebtoonSection(MainPageWebtoon parser, String title, String path, int baseMode,
                                                    Cancellation cancellation) throws Exception {
        return loadWebtoonSection(parser, title, path, baseMode, group(cancellation));
    }

    public static Ranking<Title> loadWebtoonSection(MainPageWebtoon parser, String title, String path, int baseMode,
                                                    CustomHttpClient.RequestGroup requestGroup) throws Exception {
        boolean ntk = getHttpClient().isNtk();
        String key = "section:" + (ntk ? "ntk:" : "wfwf:") + baseMode + ':' + path;
        return cached(key, ntk ? NTK_SECTION_TTL_MS : SECTION_TTL_MS, () -> {
            if(requestGroup == null)
                return parser.parseWolfTitle(getHttpClient(), title, path, baseMode);
            return getHttpClient().runWithRequestGroup(requestGroup,
                    () -> parser.parseWolfTitle(getHttpClient(), title, path, baseMode));
        });
    }

    public static List<String> imageUrls(Manga manga, Context context) {
        return usableImageUrls(manga == null ? null : manga.getImgs(context));
    }

    static List<String> usableImageUrlsForTest(List<String> images) {
        return usableImageUrls(images);
    }

    private static List<String> usableImageUrls(List<String> images) {
        if(images == null)
            return null;
        ArrayList<String> usable = new ArrayList<>(images.size());
        for(String image : images) {
            if(image != null && image.trim().length() > 0)
                usable.add(image);
        }
        return usable;
    }

    public static void backfillRecentProgress(int limit) {
        p.backfillRecentProgress(getHttpClient(), limit);
    }

    public static String resolveUrl(String path) {
        return getHttpClient().getUrl(path);
    }

    private static CustomHttpClient.RequestGroup group(Cancellation cancellation) {
        return cancellation == null ? null : cancellation.group;
    }

    public static final class Cancellation {
        private final CustomHttpClient.RequestGroup group = new CustomHttpClient.RequestGroup();

        private Cancellation() {
        }

        public void cancel() {
            group.cancel();
        }

        public boolean isCancelled() {
            return group.isCancelled();
        }
    }

    public static UrlUpdateResult updateUrl(String fetchUrl) {
        String result;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", getHttpClient().agent);
            String root = WfwfDomainResolver.toRoot(fetchUrl);
            if(WfwfDomainResolver.isSupportedNumberedUrl(root)) {
                headers.put("Referer", root);
                result = WfwfDomainResolver.resolve(getHttpClient().client, root, headers);
                if(result == null)
                    return new UrlUpdateResult(false, "");
                String resolvedRoot = WfwfDomainResolver.toRoot(result);
                if(isNtkRoot(resolvedRoot)) {
                    p.setNtkSitePreset(resolvedRoot);
                } else {
                    p.setWebtoonUrl(resolvedRoot);
                    p.setDefUrl(resolvedRoot + "/cm");
                    p.setUrl(resolvedRoot + "/cm");
                }
                getHttpClient().resetCookie();
                getHttpClient().clearPageCache();
                return new UrlUpdateResult(true, result);
            }

            Response response = null;
            try {
                response = getHttpClient().get(fetchUrl, headers);
                if(response == null || response.code() != 302)
                    return new UrlUpdateResult(false, "");
                result = response.header("Location");
                if(result == null || result.length() == 0)
                    return new UrlUpdateResult(false, "");
                p.setUrl(result);
                return new UrlUpdateResult(true, result);
            } finally {
                if(response != null)
                    response.close();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return new UrlUpdateResult(false, "");
        }
    }

    private static boolean isNtkRoot(String root) {
        if(root == null)
            return false;
        String lower = root.toLowerCase();
        return lower.contains("://ntk")
                || lower.contains("://newtoki")
                || lower.contains("://sbxh")
                || lower.contains("://www.sbxh");
    }

    @SuppressWarnings("unchecked")
    private static <T> T cached(String key, long ttlMs, Callable<T> loader) throws Exception {
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(key);
        if(cached != null && now - cached.loadedAt < ttlMs)
            return (T) cached.value;

        FutureTask<Object> task = new FutureTask<>(() -> loader.call());
        FutureTask<Object> running = IN_FLIGHT.putIfAbsent(key, task);
        if(running == null) {
            running = task;
            task.run();
        }
        try {
            Object value = running.get();
            if(value != null)
                CACHE.put(key, new CacheEntry(value, System.currentTimeMillis()));
            return (T) value;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if(cause instanceof Exception)
                throw (Exception) cause;
            throw new RuntimeException(cause);
        } finally {
            IN_FLIGHT.remove(key, running);
        }
    }

    private static final class CacheEntry {
        final Object value;
        final long loadedAt;

        CacheEntry(Object value, long loadedAt) {
            this.value = value;
            this.loadedAt = loadedAt;
        }
    }
}
