package ml.melun.mangaview.repository;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
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
import ml.melun.mangaview.reader.ReaderWarmupCoordinator;
import ml.melun.mangaview.runtime.PrefetchCoordinator;
import okhttp3.Response;

import static ml.melun.mangaview.MainApplication.appContext;
import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;

public final class MangaRepository {
    private static final long HOME_TTL_MS = 45_000L;
    private static final long SECTION_TTL_MS = 2 * 60_000L;
    private static final long NTK_HOME_TTL_MS = 10 * 60_000L;
    private static final long NTK_SECTION_TTL_MS = 10 * 60_000L;
    private static final long VIEWER_FETCH_TTL_MS = 2 * 60_000L;
    private static final int VIEWER_FETCH_CACHE_LIMIT = 96;
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, FutureTask<Object>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ViewerFetchTask> VIEWER_FETCH_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ViewerFetchCacheEntry> VIEWER_FETCH_CACHE = new ConcurrentHashMap<>();

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
        return fetchEpisodes(title, false);
    }

    public static int fetchEpisodes(Title title, Cancellation cancellation) {
        return fetchEpisodes(title, false, true, group(cancellation));
    }

    public static int fetchEpisodesBackground(Title title) {
        return fetchEpisodes(title, false, false);
    }

    public static int fetchEpisodesBackground(Title title, Cancellation cancellation) {
        return fetchEpisodes(title, false, false, group(cancellation));
    }

    public static int fetchEpisodesForeground(Title title) {
        return fetchEpisodes(title, true, true);
    }

    public static int fetchEpisodesForeground(Title title, Cancellation cancellation) {
        return fetchEpisodes(title, true, true, group(cancellation));
    }

    private static int fetchEpisodes(Title title, boolean allowWolfWebViewFallback) {
        return fetchEpisodes(title, allowWolfWebViewFallback, true);
    }

    private static int fetchEpisodes(Title title, boolean allowWolfWebViewFallback, boolean reportFailure) {
        return fetchEpisodes(title, allowWolfWebViewFallback, reportFailure, null);
    }

    private static int fetchEpisodes(Title title, boolean allowWolfWebViewFallback, boolean reportFailure,
                                     CustomHttpClient.RequestGroup requestGroup) {
        if(requestGroup == null)
            requestGroup = new CustomHttpClient.RequestGroup();
        if(allowWolfWebViewFallback)
            requestGroup.allowWolfWebViewFallback();
        if(requestGroup.isCancelled())
            return Title.LOAD_ERROR;
        try {
            int result = getHttpClient().runWithRequestGroup(requestGroup, () -> title.fetchEps(getHttpClient()));
            if(result == Title.LOAD_OK && !requestGroup.isCancelled())
                primeForegroundWfwfEntry(title, allowWolfWebViewFallback);
            return result;
        } catch (Exception e) {
            if(reportFailure && shouldReportEpisodeFetchFailure(e))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return Title.LOAD_ERROR;
        }
    }

    private static void primeForegroundWfwfEntry(Title title, boolean allowWolfWebViewFallback) {
        if(appContext == null)
            return;
        Manga episode = foregroundWfwfPrimeEpisode(title, allowWolfWebViewFallback);
        if(episode == null)
            return;
        List<Manga> episodes = Title.orderedEpisodeSnapshot(title.getEps());
        if(episodes != null)
            title.setEps(episodes);
        episode.setMode(title.getBaseMode());
        episode.setTitle(title);
        episode.setTitleId(title.getId());
        ReaderWarmupCoordinator.primeExactImmediate(appContext, episode, title);
    }

    private static int foregroundWfwfPrimeIndex(Title title, boolean allowWolfWebViewFallback) {
        if(!allowWolfWebViewFallback || title == null)
            return -1;
        String source = title.getSourceSite();
        if(source == null || !"wfwf".equals(source.trim().toLowerCase(Locale.ROOT)))
            return -1;
        List<Manga> episodes = Title.orderedEpisodeSnapshot(title.getEps());
        return PrefetchCoordinator.firstEpisodeIndex(episodes);
    }

    private static Manga foregroundWfwfPrimeEpisode(Title title, boolean allowWolfWebViewFallback) {
        int index = foregroundWfwfPrimeIndex(title, allowWolfWebViewFallback);
        if(index < 0 || title == null)
            return null;
        List<Manga> episodes = Title.orderedEpisodeSnapshot(title.getEps());
        if(episodes == null || index >= episodes.size())
            return null;
        Manga episode = episodes.get(index);
        if(episode == null)
            return null;
        if(episode.getId() > 1 && title.getId() > 0) {
            Manga firstEpisode = new Manga(1, "1", "", episode.getBaseMode());
            firstEpisode.setMode(title.getBaseMode());
            firstEpisode.setTitle(title);
            firstEpisode.setTitleId(title.getId());
            return firstEpisode;
        }
        return episode;
    }

    static int foregroundWfwfPrimeIndexForTest(Title title, boolean allowWolfWebViewFallback) {
        return foregroundWfwfPrimeIndex(title, allowWolfWebViewFallback);
    }

    static Manga foregroundWfwfPrimeEpisodeForTest(Title title, boolean allowWolfWebViewFallback) {
        return foregroundWfwfPrimeEpisode(title, allowWolfWebViewFallback);
    }

    static boolean shouldReportEpisodeFetchFailure(Throwable failure) {
        if(failure == null)
            return false;
        String message = failure.getMessage();
        if(message != null && message.startsWith("Request failed:"))
            return false;
        return !(failure instanceof java.io.IOException);
    }

    public static boolean shouldReportSearchFailure(Throwable failure) {
        if(failure == null)
            return false;
        String message = failure.getMessage();
        if(message != null && message.startsWith("Request failed:"))
            return false;
        return !(failure instanceof java.io.IOException);
    }

    public static int fetchManga(Manga manga) {
        try {
            return fetchViewerSingleFlight(manga, null, false);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return Title.LOAD_ERROR;
        }
    }

    public static int fetchViewerInitial(Manga manga, Cancellation cancellation) throws Exception {
        return fetchViewerInitial(manga, group(cancellation));
    }

    public static int fetchViewerInitialWithMode(Manga manga, Cancellation cancellation, String mode) throws Exception {
        CustomHttpClient.RequestGroup requestGroup = group(cancellation);
        if(manga == null)
            return Title.LOAD_ERROR;
        if(requestGroup != null && requestGroup.isCancelled())
            return Title.LOAD_ERROR;
        CustomHttpClient client = getHttpClient();
        if(requestGroup == null)
            return Manga.fetchWithTemporaryNtkViewerFetchMode(manga, client, mode);
        return client.runWithRequestGroup(requestGroup,
                () -> Manga.fetchWithTemporaryNtkViewerFetchMode(manga, client, mode));
    }

    public static int fetchViewerInitial(Manga manga, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        return fetchViewerSingleFlight(manga, requestGroup, true);
    }

    private static int fetchViewerSingleFlight(Manga manga, CustomHttpClient.RequestGroup requestGroup,
                                               boolean viewerInitial) throws Exception {
        if(manga == null)
            return Title.LOAD_ERROR;
        if(requestGroup != null && requestGroup.isCancelled())
            return Title.LOAD_ERROR;
        CustomHttpClient client = getHttpClient();
        boolean forceDirectKey = viewerInitial
                && !client.isNtk()
                && (requestGroup == null || !requestGroup.allowsWolfWebViewFallback());
        String key = viewerFetchKey(client, manga, forceDirectKey);
        ViewerFetchResult cached = cachedViewerFetch(key);
        if(cached != null) {
            if(cached.manga != manga)
                manga.copyViewerStateFrom(cached.manga);
            return cached.result;
        }
        FutureTask<ViewerFetchResult> task = new FutureTask<>(() -> {
            int result;
            if(requestGroup == null) {
                result = manga.fetchForViewerInitial(client);
            } else if(!client.isNtk() && !requestGroup.allowsWolfWebViewFallback()) {
                result = client.runWithFetchMode(CustomHttpClient.FetchMode.DIRECT_ONLY,
                        () -> client.runWithRequestGroup(requestGroup, () -> manga.fetchForViewerInitial(client)));
            } else {
                result = client.runWithRequestGroup(requestGroup, () -> manga.fetchForViewerInitial(client));
            }
            return new ViewerFetchResult(result, manga);
        });
        boolean foreground = requestGroup != null && requestGroup.isUserVisible();
        boolean webViewPriority = requestGroup != null && requestGroup.prioritizesWebViewFallback();
        ViewerFetchTask candidate = new ViewerFetchTask(task, requestGroup, foreground);
        ViewerFetchTask running = reserveViewerFetch(key, candidate, foreground, webViewPriority, !client.isNtk());
        if(running == candidate) {
            task.run();
        }
        try {
            ViewerFetchResult fetched = running.task.get();
            if(fetched != null && fetched.manga != manga)
                manga.copyViewerStateFrom(fetched.manga);
            cacheViewerFetch(key, fetched);
            return fetched == null ? Title.LOAD_ERROR : fetched.result;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if(cause instanceof Exception)
                throw (Exception) cause;
            throw new RuntimeException(cause);
        } catch (CancellationException e) {
            return Title.LOAD_ERROR;
        } finally {
            VIEWER_FETCH_IN_FLIGHT.remove(key, running);
        }
    }

    private static ViewerFetchTask reserveViewerFetch(String key, ViewerFetchTask candidate,
                                                      boolean foreground, boolean webViewPriority,
                                                      boolean allowPriorityReplace) {
        while(true) {
            ViewerFetchTask existing = VIEWER_FETCH_IN_FLIGHT.putIfAbsent(key, candidate);
            if(existing == null)
                return candidate;
            if(!allowPriorityReplace
                    || !shouldReplaceViewerFetchForPriority(foreground, existing.foreground, webViewPriority))
                return existing;
            existing.cancel();
            VIEWER_FETCH_IN_FLIGHT.remove(key, existing);
        }
    }

    static boolean shouldReplaceViewerFetchForPriorityForTest(boolean foreground,
                                                              boolean existingForeground,
                                                              boolean webViewPriority) {
        return shouldReplaceViewerFetchForPriority(foreground, existingForeground, webViewPriority);
    }

    private static boolean shouldReplaceViewerFetchForPriority(boolean foreground,
                                                               boolean existingForeground,
                                                               boolean webViewPriority) {
        return webViewPriority && foreground && !existingForeground;
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
            return new ArrayList<>();
        ArrayList<String> usable = new ArrayList<>(images.size());
        java.util.LinkedHashSet<String> ntkSeen = new java.util.LinkedHashSet<>();
        for(String image : images) {
            if(image == null)
                continue;
            String trimmed = image.trim();
            if(trimmed.length() == 0)
                continue;
            String ntkKey = ntkImageDedupKey(trimmed);
            if(ntkKey.length() > 0 && !ntkSeen.add(ntkKey))
                continue;
            usable.add(trimmed);
        }
        return usable;
    }

    private static String ntkImageDedupKey(String image) {
        if(image == null)
            return "";
        String normalized = image.trim()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("&amp;", "&");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if(!lower.contains("toonflix.app")
                && !lower.matches("(?s).*://(?:www\\.)?pl\\d+\\.com/.*"))
            return "";
        String proxied = ntkProxiedImageUrl(normalized);
        if(proxied.length() > 0) {
            normalized = proxied;
            lower = normalized.toLowerCase(Locale.ROOT);
        }
        int fragment = normalized.indexOf('#');
        if(fragment >= 0)
            normalized = normalized.substring(0, fragment);
        int query = normalized.indexOf('?');
        if(query >= 0) {
            String pathOnly = normalized.substring(0, query);
            String pathLower = pathOnly.toLowerCase(Locale.ROOT);
            if(pathLower.matches("(?s).*\\.(jpg|jpeg|png|webp|gif)$"))
                normalized = pathOnly;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String ntkProxiedImageUrl(String value) {
        if(value == null || value.length() == 0)
            return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)(?:[?&]|&amp;)url=([^\\s\"'<>&,]+\\.(?:jpg|jpeg|png|webp|gif)(?:%3F[^\\s\"'<>&,]*)?)"
        ).matcher(value);
        if(!matcher.find())
            return "";
        try {
            return java.net.URLDecoder.decode(matcher.group(1), "UTF-8");
        } catch (Exception ignored) {
            return matcher.group(1);
        }
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

    private static String viewerFetchKey(CustomHttpClient client, Manga manga, boolean forceDirectMode) {
        boolean ntk = client != null && client.isNtk();
        String site = ntk ? "ntk" : "wfwf";
        String fetchMode = ntk ? "shared"
                : forceDirectMode || (client != null && client.isDirectOnlyFetchMode()) ? "direct" : "allow";
        String url = "";
        try {
            url = Manga.safeUrl(manga);
        } catch (Exception ignored) {
        }
        return "viewerFetch:" + site + ':' + fetchMode + ':' + manga.getBaseMode()
                + ':' + manga.getTitleId() + ':' + manga.getId() + ':' + url;
    }

    private static ViewerFetchResult cachedViewerFetch(String key) {
        ViewerFetchCacheEntry entry = VIEWER_FETCH_CACHE.get(key);
        if(entry == null)
            return null;
        if(!isCacheFresh(entry.loadedAt, System.currentTimeMillis(), VIEWER_FETCH_TTL_MS)) {
            VIEWER_FETCH_CACHE.remove(key, entry);
            return null;
        }
        return entry.result;
    }

    private static void cacheViewerFetch(String key, ViewerFetchResult result) {
        if(key == null || result == null || result.result != Title.LOAD_OK || result.manga == null
                || result.manga.isFetchInProgress() || imageUrls(result.manga, null).size() == 0)
            return;
        ViewerFetchResult snapshot = snapshotViewerFetchResult(result);
        if(snapshot == null || imageUrls(snapshot.manga, null).size() == 0)
            return;
        VIEWER_FETCH_CACHE.put(key, new ViewerFetchCacheEntry(snapshot, System.currentTimeMillis()));
        trimViewerFetchCache();
    }

    private static ViewerFetchResult snapshotViewerFetchResult(ViewerFetchResult result) {
        if(result == null || result.manga == null)
            return null;
        Manga snapshot = snapshotViewerManga(result.manga);
        return snapshot == null ? null : new ViewerFetchResult(result.result, snapshot);
    }

    private static Manga snapshotViewerManga(Manga source) {
        if(source == null)
            return null;
        if(source.isFetchInProgress())
            return null;
        Manga snapshot = new Manga(source.getId(), source.getName(), source.getDate(), source.getBaseMode());
        snapshot.setMode(source.getMode());
        snapshot.setTitle(source.getTitle());
        snapshot.setTitleId(source.getTitleId());
        snapshot.setNtkEpisodePath(source.getNtkEpisodePath());
        snapshot.setNtkImageEpisodeId(source.getNtkImageEpisodeId());
        snapshot.setNtkImageCount(source.getNtkImageCount());
        snapshot.copyViewerStateFrom(source);
        return snapshot;
    }

    static Manga snapshotViewerMangaForTest(Manga source) {
        return snapshotViewerManga(source);
    }

    private static void trimViewerFetchCache() {
        if(VIEWER_FETCH_CACHE.size() <= VIEWER_FETCH_CACHE_LIMIT)
            return;
        long now = System.currentTimeMillis();
        for(Map.Entry<String, ViewerFetchCacheEntry> entry : VIEWER_FETCH_CACHE.entrySet()) {
            ViewerFetchCacheEntry value = entry.getValue();
            if(value == null || !isCacheFresh(value.loadedAt, now, VIEWER_FETCH_TTL_MS))
                VIEWER_FETCH_CACHE.remove(entry.getKey(), value);
        }
        if(VIEWER_FETCH_CACHE.size() <= VIEWER_FETCH_CACHE_LIMIT)
            return;
        int remove = VIEWER_FETCH_CACHE.size() - VIEWER_FETCH_CACHE_LIMIT;
        for(String entryKey : VIEWER_FETCH_CACHE.keySet()) {
            VIEWER_FETCH_CACHE.remove(entryKey);
            if(--remove <= 0)
                break;
        }
    }

    public static final class Cancellation {
        private final CustomHttpClient.RequestGroup group = new CustomHttpClient.RequestGroup();

        private Cancellation() {
        }

        public void cancel() {
            group.cancel();
        }

        public Cancellation prioritizeWebViewFallback() {
            group.prioritizeWebViewFallback();
            return this;
        }

        public Cancellation allowWolfWebViewFallback() {
            group.allowWolfWebViewFallback();
            return this;
        }

        public Cancellation userVisible() {
            group.userVisible();
            return this;
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
                    return new UrlUpdateResult(false, "", fetchUrl);
                return new UrlUpdateResult(true, result, fetchUrl);
            }

            Response response = null;
            try {
                response = getHttpClient().get(fetchUrl, headers);
                if(response == null || response.code() != 302)
                    return new UrlUpdateResult(false, "", fetchUrl);
                result = response.header("Location");
                if(result == null || result.length() == 0)
                    return new UrlUpdateResult(false, "", fetchUrl);
                return new UrlUpdateResult(true, result, fetchUrl);
            } finally {
                if(response != null)
                    response.close();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return new UrlUpdateResult(false, "", fetchUrl);
        }
    }

    public static boolean applyUrlUpdate(String fetchUrl, UrlUpdateResult result) {
        if(fetchUrl == null || result == null || !result.getSuccess() ||
                result.getUrl() == null || result.getUrl().length() == 0 ||
                !result.isForRequest(fetchUrl))
            return false;
        try {
            String root = WfwfDomainResolver.toRoot(fetchUrl);
            if(WfwfDomainResolver.isSupportedNumberedUrl(root)) {
                String resolvedRoot = WfwfDomainResolver.toRoot(result.getUrl());
                if(isNtkRoot(resolvedRoot)) {
                    p.setNtkSitePreset(resolvedRoot);
                } else {
                    p.setWebtoonUrl(resolvedRoot);
                    p.setDefUrl(resolvedRoot + "/cm");
                    p.setUrl(resolvedRoot + "/cm");
                }
                getHttpClient().resetCookie();
                getHttpClient().clearPageCache();
            } else {
                p.setUrl(result.getUrl());
            }
            return true;
        } catch (RuntimeException exception) {
            ml.melun.mangaview.report.CrashReporter.record(exception);
            return false;
        }
    }

    private static boolean isNtkRoot(String root) {
        if(root == null)
            return false;
        String lower = root.toLowerCase(Locale.ROOT);
        return lower.contains("://ntk")
                || lower.contains("://newto")
                || lower.contains("://newtoki")
                || lower.contains("://sbxh")
                || lower.contains("://www.sbxh")
                || lower.contains("://toonflix")
                || lower.contains(".toonflix.app");
    }

    @SuppressWarnings("unchecked")
    private static <T> T cached(String key, long ttlMs, Callable<T> loader) throws Exception {
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(key);
        if(cached != null && isCacheFresh(cached.loadedAt, now, ttlMs))
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

    static boolean isCacheFreshForTest(long loadedAt, long now, long ttlMs) {
        return isCacheFresh(loadedAt, now, ttlMs);
    }

    private static boolean isCacheFresh(long loadedAt, long now, long ttlMs) {
        return loadedAt <= now && now - loadedAt < ttlMs;
    }

    private static final class CacheEntry {
        final Object value;
        final long loadedAt;

        CacheEntry(Object value, long loadedAt) {
            this.value = value;
            this.loadedAt = loadedAt;
        }
    }

    private static final class ViewerFetchResult {
        final int result;
        final Manga manga;

        ViewerFetchResult(int result, Manga manga) {
            this.result = result;
            this.manga = manga;
        }
    }

    private static final class ViewerFetchCacheEntry {
        final ViewerFetchResult result;
        final long loadedAt;

        ViewerFetchCacheEntry(ViewerFetchResult result, long loadedAt) {
            this.result = result;
            this.loadedAt = loadedAt;
        }
    }

    private static final class ViewerFetchTask {
        final FutureTask<ViewerFetchResult> task;
        final CustomHttpClient.RequestGroup requestGroup;
        final boolean foreground;

        ViewerFetchTask(FutureTask<ViewerFetchResult> task,
                        CustomHttpClient.RequestGroup requestGroup,
                        boolean foreground) {
            this.task = task;
            this.requestGroup = requestGroup;
            this.foreground = foreground;
        }

        void cancel() {
            if(requestGroup != null)
                requestGroup.cancel();
            task.cancel(true);
        }
    }
}
