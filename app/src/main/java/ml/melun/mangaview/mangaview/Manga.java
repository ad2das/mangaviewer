package ml.melun.mangaview.mangaview;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.jsoup.*;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import okhttp3.Response;
import ml.melun.mangaview.reader.ReaderImageCache;
import ml.melun.mangaview.reader.NtkDiscoveryLease;
import ml.melun.mangaview.reader.NtkSourceSpoolRegistry;
import ml.melun.mangaview.reader.NtkStrictEpisodeDiscoveryCoordinator;

import static ml.melun.mangaview.Utils.documentFileFromUri;
import static ml.melun.mangaview.Utils.useScopedStorageHome;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.MTitle.baseModeStr;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_ERROR;
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.activity.NtkBrowserSessionBroker;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.reader.ReaderImageCache;

    /*
    mode:
    0 = online
    1 = offline - old
    2 = offline - old(moa) (title.data)
    3 = offline - latest(toki) (title.gson)
    4 = offline - new(moa) (title.gson)
     */

public class Manga {
    private static final long PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;
    private static final int NTK_GENERATED_IMAGE_PROBE_BYTES = 8 * 1024;
    private static final int NTK_EARLY_INITIAL_STREAM_PAGES = 4;
    private static final int NTK_EARLY_INITIAL_STREAM_START_COUNT = 4;
    private static final int NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT = 4;
    private static final int NTK_EARLY_PAYLOAD_HEAD_STREAM_PAGES = 4;
    private static final int NTK_FOREGROUND_NATIVE_INITIAL_RUNWAY_API_SKIP_PAGES = 8;
    private static final long NTK_EARLY_INITIAL_STREAM_STAGGER_MS = 120L;
    private static final long NTK_EARLY_INITIAL_STREAM_RETRY_MS = 800L;
    private static final int NTK_EARLY_SPECULATIVE_PAGE_PUBLISH_COUNT = 32;
    private static final int NTK_EARLY_INITIAL_PUBLISH_PAGES = 128;
    private static final int NTK_EARLY_VERIFIED_WEBTOON_STREAM_PAGES = 8;
    // Two bodies beyond the four-page activation proof cover the first immediate physical sweep.
    // Racing p7/p8 here previously delayed p4, so only p6 joins the extension-independent launch
    // race; farther pages still begin from the verified manifest on the bounded source lanes.
    private static final int NTK_EARLY_LAUNCH_RUNWAY_RACE_PAGES = 5;
    // Publish only the page-1 extension probe. Additional launch-runway bodies use the separate
    // unpublished race, which can be atomically replaced by the verified suffix without making a
    // guessed URL authoritative.
    private static final int NTK_SPECULATIVE_INITIAL_STREAM_PAGES = 1;
    private static final long NTK_PAGE_FETCH_LAUNCH_HOLD_POLL_MS = 40L;
    private static final long NTK_PAGE_FETCH_LAUNCH_HOLD_MAX_MS = 3_200L;
    private static final String[] NTK_GENERATED_IMAGE_EXTENSIONS = new String[]{"jpg", "jpeg", "webp", "png"};
    private static final long NTK_EARLY_GENERATED_HEADER_PROBE_MS = 350L;
    private static final String TAG = "ViewerPerf";
    private static volatile String ntkViewerFetchModeOverride = "";
    private static final ThreadLocal<String> ntkThreadFetchModeOverride = new ThreadLocal<>();
    private static final String NTK_IMAGE_HOST_PATTERN =
            "(?:(?:[a-z0-9.-]+\\.)?toonflix\\.app|flysky\\d*m\\.com|moamoabon\\.com|fvcdn\\d*\\.com|aws-cdn\\d*\\.site|apihost\\d*\\.com|booktoki\\d*\\.org|[a-z0-9-]+\\.worldcup\\d+\\.xyz|\\d{5,10}\\.com|img\\.[a-z0-9.-]+|(?:www\\.)?pl\\d+\\.com)";
    private static final Pattern NTK_TEXT_IMAGE_PATTERN = Pattern.compile(
            "(?i)(?:https?:)?//" + NTK_IMAGE_HOST_PATTERN + "/[^\\s\"'<>\\\\]+?\\.(?:jpg|jpeg|png|webp|gif)(?:\\?[^\\s\"'<>\\\\]*)?");
    private static final Pattern NTK_ENCODED_TEXT_IMAGE_PATTERN = Pattern.compile(
            "(?i)https%3A%2F%2F" + NTK_IMAGE_HOST_PATTERN + "%2F[^\\s\"'<>\\\\]+?\\.(?:jpg|jpeg|png|webp|gif)(?:%3F[^\\s\"'<>\\\\]*)?");
    private static final Pattern NTK_NEXT_IMAGE_URL_PARAM_PATTERN = Pattern.compile(
            "(?i)(?:[?&]|&amp;)url=([^\\s\"'<>&,]+\\.(?:jpg|jpeg|png|webp|gif)(?:%3F[^\\s\"'<>&,]*)?)");
    private static final Pattern NTK_NUMBERED_PAGE_IMAGE_PATTERN = Pattern.compile(
            "(?i)(?:(?:https?:)?//" + NTK_IMAGE_HOST_PATTERN + ")?/(?:manhwa|webtoon|comic)/\\d+/[^/?#]+/(?:p)?\\d{1,4}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$");
    private static final Pattern NTK_CURRENT_CDN_PAGE_IMAGE_PATTERN = Pattern.compile(
            "(?i)(?:https?:)?//(?:www\\.)?pl\\d+\\.com/.*/\\d+/\\d+/[^/?#]+\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$");
    private static final Pattern VIEWER_EPISODE_PREFIX_PATTERN = Pattern.compile("^\\(\\s*\\d+\\s*/\\s*\\d+\\s*\\)\\s*");
    private static final Pattern EPISODE_WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern EPISODE_NUMBER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?(?:\\s*[,~～\\-]\\s*\\d+(?:\\.\\d+)?)*)\\s*화");
    private static final Pattern EPISODE_BLOCK_NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final int NTK_DEFAULT_GENERATED_PAGE_COUNT = 64;
    private static final int NTK_MAX_GENERATED_PAGE_COUNT = 300;
    private static final int NTK_GENERATED_INITIAL_VALIDATION_PAGE_COUNT = 5;
    private static final int NTK_CANONICAL_WEBTOON_API_FIRST_MIN_WORK_ID = 800000;
    private static final int NTK_LAST_RESORT_GENERATED_PROBE_LIMIT = 12;
    private static final boolean NTK_GENERATED_TRIM_BEFORE_FIRST_FRAME = false;
    private static final long NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS = 2400L;
    private static final long NTK_API_FALLBACK_DIRECT_RACE_WAIT_MS = 560L;
    private static final long NTK_API_FALLBACK_DIRECT_READY_WAIT_MS = 3200L;
    private static final long NTK_GENERATED_MISS_ACK_GRACE_MS = 650L;
    private static final long NTK_GENERATED_MISS_PAGE_FAST_PATH_MS = 2600L;
    private static final long NTK_STRICT_ACK_FAILED_PAGE_FAST_PATH_MS = 5200L;
    private static final long NTK_EARLY_GENERATED_EXTENSION_WAIT_MS = 1250L;
    private static final long NTK_GENERATED_EXTENSION_CONFIRM_WAIT_MS = 1600L;
    private static final long NTK_WEBVIEW_VIEWER_IMAGES_CACHE_WAIT_MS = 1700L;
    private static final long NTK_GENERATED_EXTENSION_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long NTK_PAGE_FETCH_POLL_MS = 5L;
    private static final Map<String, String> NTK_GENERATED_EXTENSION_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> NTK_GENERATED_EXTENSION_CACHE_TIME = new ConcurrentHashMap<>();
    private static final Map<String, FutureTask<String>> NTK_GENERATED_EXTENSION_FLIGHTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> NTK_VIEWER_API_TOKEN_PREFETCH_FLIGHTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> NTK_KP_SIGNED_TOKEN_ACK_FLIGHTS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> NTK_KP_ACK_READY_PAYLOAD_FLIGHTS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> NTK_KNOWN_SLUG_WEBTOON_API_FLIGHTS = new ConcurrentHashMap<>();

    public static void setNtkViewerFetchModeOverrideForTest(String mode) {
        ntkViewerFetchModeOverride = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    public static void clearNtkViewerFetchModeOverrideForTest() {
        ntkViewerFetchModeOverride = "";
    }

    public static void clearNtkGeneratedExtensionCacheForTest() {
        NTK_GENERATED_EXTENSION_CACHE.clear();
        NTK_GENERATED_EXTENSION_CACHE_TIME.clear();
        for(FutureTask<String> flight : NTK_GENERATED_EXTENSION_FLIGHTS.values())
            flight.cancel(true);
        NTK_GENERATED_EXTENSION_FLIGHTS.clear();
    }

    public static int fetchWithTemporaryNtkViewerFetchMode(Manga manga, CustomHttpClient client, String mode) {
        if(manga == null || client == null)
            return LOAD_ERROR;
        String previous = ntkThreadFetchModeOverride.get();
        ntkThreadFetchModeOverride.set(mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT));
        try {
            return manga.fetch(client);
        } finally {
            if(previous == null)
                ntkThreadFetchModeOverride.remove();
            else
                ntkThreadFetchModeOverride.set(previous);
        }
    }

    private static String ntkViewerFetchModeOverride() {
        String threadMode = ntkThreadFetchModeOverride.get();
        return threadMode == null || threadMode.length() == 0 ? ntkViewerFetchModeOverride : threadMode;
    }

    private static boolean isNtkNativeAckModeOverride() {
        String mode = ntkViewerFetchModeOverride();
        return "native".equals(mode)
                || "native-ack".equals(mode)
                || "native_ack".equals(mode)
                || isNtkStrictNativeAckModeOverride();
    }

    private static boolean isNtkStrictNativeAckModeOverride() {
        String mode = ntkViewerFetchModeOverride();
        return "native-strict".equals(mode)
                || "native_strict".equals(mode);
    }

    private boolean shouldDeferModernNtkGeneratedProbeUntilAck(CustomHttpClient client, String path) {
        return false;
    }

    private static boolean isNtkGeneratedModeOverride() {
        String mode = ntkViewerFetchModeOverride();
        return "generated".equals(mode)
                || "fast".equals(mode)
                || "generated-fast".equals(mode);
    }

    private static boolean isNtkGeneratedImmediateModeOverride() {
        String mode = ntkViewerFetchModeOverride();
        return "fast".equals(mode)
                || "generated-fast".equals(mode);
    }

    private static boolean isNtkApiFallbackModeOverride() {
        String mode = ntkViewerFetchModeOverride();
        return "api".equals(mode)
                || "api-fallback".equals(mode)
                || "api_fallback".equals(mode)
                || "api-strict".equals(mode)
                || "api_strict".equals(mode);
    }

    private static boolean isNtkStrictApiFallbackModeOverride() {
        String mode = ntkViewerFetchModeOverride();
        return "api-strict".equals(mode)
                || "api_strict".equals(mode);
    }

    int baseMode = base_comic;
    int titleId = -1;
    private String ntkEpisodePath = "";
    private String ntkImageEpisodeId = "";
    private String ntkImageWorkId = "";
    private String ntkViewerPayloadHint = "";
    private String ntkViewerParseReason = "";
    private int ntkImageCount;
    private volatile boolean fetchInProgress;

    public Manga(int i, String n, String d, int baseMode) {
        id = i;
        name = n;
        date = d;
        this.baseMode = baseMode;
    }

    public int getBaseMode() {
        return this.baseMode;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void addThumb(String src) {
        thumb = src;
    }

    public String getDate() {
        return date;
    }

    public String getNtkEpisodePath() {
        String path = ntkEpisodePath == null ? "" : ntkEpisodePath;
        if(path.trim().length() > 0)
            return path;
        path = matchingNtkEpisodePath(eps);
        if(path.length() > 0)
            return path;
        return title == null ? "" : matchingNtkEpisodePath(title.getEps());
    }

    public String getNtkViewerParseReason() {
        return ntkViewerParseReason == null ? "" : ntkViewerParseReason;
    }

    public boolean ensureNtkEpisodePathFromIdentity() {
        String path = getNtkEpisodePath();
        if(path.length() > 0) {
            setNtkEpisodePath(path);
            return true;
        }
        return false;
    }

    public boolean hasExplicitNtkEpisodePath() {
        return ntkEpisodePath != null && ntkEpisodePath.trim().length() > 0;
    }

    public void setNtkEpisodePath(String ntkEpisodePath) {
        this.ntkEpisodePath = ntkEpisodePath == null ? "" : ntkEpisodePath.trim();
        if(title != null && this.ntkEpisodePath.length() > 0)
            title.applyNtkTitlePathFromEpisodePath(this.ntkEpisodePath);
    }

    public String getNtkImageEpisodeId() {
        String id = ntkImageEpisodeId == null ? "" : ntkImageEpisodeId.trim();
        if(id.matches("\\d{1,12}"))
            return id;
        String path = getNtkEpisodePath();
        String kpEpisodeId = ntkKpEpisodeIdForPath(path);
        if(kpEpisodeId.matches("\\d{1,12}"))
            return kpEpisodeId;
        CustomHttpClient.NtkCachedImageIdentity identity = CustomHttpClient.cachedNtkImageIdentity(path);
        if(identity != null && identity.episodeId.matches("\\d{1,12}"))
            return identity.episodeId;
        if(id.length() > 0)
            return id;
        if(path.length() == 0)
            return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : "";
    }

    public void setNtkImageEpisodeId(String ntkImageEpisodeId) {
        this.ntkImageEpisodeId = ntkImageEpisodeId == null ? "" : ntkImageEpisodeId.trim();
    }

    public String getNtkImageWorkId() {
        String id = ntkImageWorkId == null ? "" : ntkImageWorkId.trim();
        CustomHttpClient.NtkCachedImageIdentity identity = CustomHttpClient.cachedNtkImageIdentity(getNtkEpisodePath());
        if(identity != null && identity.workId.matches("\\d{1,12}"))
            return identity.workId;
        String pathWorkId = ntkPathWorkId(getNtkEpisodePath());
        if(isNumericNtkViewerEpisodePath(getNtkEpisodePath())
                && pathWorkId.matches("\\d{1,12}"))
            return pathWorkId;
        Title currentTitle = getTitle();
        if(currentTitle != null) {
            String thumbId = ntkViewerThumbWorkId(currentTitle.getThumb());
            if(thumbId.matches("\\d{1,12}")) {
                if(!id.matches("\\d{1,12}") || id.equals(pathWorkId) || !thumbId.equals(pathWorkId))
                    return thumbId;
            }
        }
        if(id.matches("\\d{1,12}") || id.length() > 0)
            return id;
        return "";
    }

    private static String ntkPathWorkId(String path) {
        if(path == null)
            return "";
        Matcher matcher = Pattern.compile("^/(?:webtoon|manhwa)/(\\d{1,12})(?:/|$)").matcher(path.trim());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isNumericNtkViewerEpisodePath(String path) {
        if(path == null)
            return false;
        return Pattern.compile("^/(?:webtoon|manhwa)/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(path.trim()).matches();
    }

    private static String ntkKpEpisodeIdForPath(String path) {
        if(path == null)
            return "";
        Matcher matcher = Pattern.compile("^/webtoon/\\d{1,12}/kp-\\d{1,12}-(\\d{1,12})(?:[/?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(path.trim());
        return matcher.matches() ? matcher.group(1) : "";
    }

    private String ntkViewerApiWorkIdForPath(String path, String tokenWorkId) {
        String token = ntkApiEpisodeIdForPath(tokenWorkId);
        if(isNumericNtkId(token))
            return token;
        String pathWorkId = ntkPathWorkId(path);
        if(isNumericNtkId(pathWorkId))
            return pathWorkId;
        return ntkApiEpisodeIdForPath(getNtkImageWorkId());
    }

    public void setNtkImageWorkId(String ntkImageWorkId) {
        this.ntkImageWorkId = ntkImageWorkId == null ? "" : ntkImageWorkId.trim();
    }

    public String getNtkViewerPayloadHint() {
        return ntkViewerPayloadHint == null ? "" : ntkViewerPayloadHint;
    }

    public void setNtkViewerPayloadHint(String ntkViewerPayloadHint) {
        if(ntkViewerPayloadHint == null) {
            this.ntkViewerPayloadHint = "";
            return;
        }
        String value = ntkViewerPayloadHint.trim();
        this.ntkViewerPayloadHint = value.length() > 120_000 ? value.substring(0, 120_000) : value;
    }

    public boolean hasNtkViewerPayloadHint() {
        return getNtkViewerPayloadHint().length() > 0;
    }

    private boolean hasNtkViewerPayloadImageHints(String path, int minCount) {
        if(path == null || path.length() == 0 || minCount <= 0)
            return false;
        String hint = getNtkViewerPayloadHint();
        if(hint.length() == 0)
            return false;
        try {
            List<String> urls = ntkViewerPayloadImageUrls(hint, path);
            return urls != null && urls.size() >= minCount;
        } catch(Throwable ignored) {
            return false;
        }
    }

    public int getNtkImageCount() {
        if(ntkImageCount > 0)
            return ntkImageCount;
        String path = ntkEpisodePath == null ? "" : ntkEpisodePath.trim();
        CustomHttpClient.NtkCachedImageIdentity identity = CustomHttpClient.cachedNtkImageIdentity(path);
        if(identity != null && identity.count > 0)
            return identity.count;
        int count = matchingNtkImageCount(eps, path);
        if(count > 0)
            return count;
        return title == null ? 0 : matchingNtkImageCount(title.getEps(), path);
    }

    /**
     * Returns a finite page count only when the current episode-list payload binds that count to
     * the exact numeric manhwa path. This is metadata already fetched to render the episode list;
     * callers may use it only after the viewer click and must still compare it with the fresh
     * episode document before publishing pixels.
     */
    public int getExactNtkClickPayloadImageCount(String expectedPath) {
        if(expectedPath == null || !expectedPath.matches("^/manhwa/\\d{1,12}/\\d{1,12}$"))
            return 0;
        String hint = getNtkViewerPayloadHint();
        if(hint.length() == 0)
            return 0;
        String normalized = normalizeNtkViewerPayloadText(hint);
        String[] pathParts = expectedPath.split("/");
        if(pathParts.length != 4)
            return 0;
        String workId = pathParts[2];
        String episodeId = pathParts[3];
        boolean exactPath = Pattern.compile(
                "\"(?:episodePath|scopePath)\"\\s*:\\s*\""
                        + Pattern.quote(expectedPath) + "\"",
                Pattern.CASE_INSENSITIVE).matcher(normalized).find();
        boolean exactIds = Pattern.compile(
                "\"sourceWorkId\"\\s*:\\s*\"?" + Pattern.quote(workId) + "\"?",
                Pattern.CASE_INSENSITIVE).matcher(normalized).find()
                && Pattern.compile(
                "\"(?:episodeId|sourceEpisodeId)\"\\s*:\\s*\"?"
                        + Pattern.quote(episodeId) + "\"?",
                Pattern.CASE_INSENSITIVE).matcher(normalized).find();
        if(!exactPath && !exactIds)
            return 0;
        Matcher declared = Pattern.compile(
                "\"(?:imageCount|imagesCount|totalImages|totalImageCount|pageCount|totalPages|numberOfPages)\""
                        + "\\s*:\\s*(\\d{1,4})",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        int exactCount = 0;
        while(declared.find()) {
            try {
                int candidate = Integer.parseInt(declared.group(1));
                if(candidate > 0) {
                    if(exactCount != 0 && exactCount != candidate)
                        return 0;
                    exactCount = candidate;
                }
            } catch(Exception ignored) {
                return 0;
            }
        }
        if(exactCount <= 0 || exactCount > 120 || ntkViewerMetaPageCount(normalized) != exactCount)
            return 0;
        return exactCount;
    }

    public void setNtkImageCount(int ntkImageCount) {
        this.ntkImageCount = ntkImageCount > 0 ? Math.min(ntkImageCount, NTK_MAX_GENERATED_PAGE_COUNT) : 0;
    }

    private String matchingNtkEpisodePath(List<Manga> episodes) {
        if(episodes == null || episodes.size() == 0)
            return "";
        String currentEpisodeNumber = episodeNumberKey(name);
        List<Manga> snapshot;
        try {
            snapshot = new ArrayList<>(episodes);
        } catch (RuntimeException e) {
            return "";
        }
        for(Manga episode : snapshot) {
            if(episode == null || episode == this || !sameSeriesEpisode(episode))
                continue;
            String path = episode.ntkEpisodePath == null ? "" : episode.ntkEpisodePath.trim();
            if(path.length() == 0)
                continue;
            String episodeNumber = episodeNumberKey(episode.getName());
            if(currentEpisodeNumber.length() > 0 && episodeNumber.length() > 0) {
                if(currentEpisodeNumber.equals(episodeNumber))
                    return path;
                continue;
            }
            if(episode.getId() == id)
                return path;
        }
        return "";
    }

    private int matchingNtkImageCount(List<Manga> episodes, String currentPath) {
        if(episodes == null || episodes.size() == 0)
            return 0;
        String currentEpisodeNumber = episodeNumberKey(name);
        List<Manga> snapshot;
        try {
            snapshot = new ArrayList<>(episodes);
        } catch (RuntimeException e) {
            return 0;
        }
        for(Manga episode : snapshot) {
            if(episode == null || episode == this || !sameSeriesEpisode(episode) || episode.ntkImageCount <= 0)
                continue;
            String path = episode.ntkEpisodePath == null ? "" : episode.ntkEpisodePath.trim();
            if(currentPath.length() > 0 && currentPath.equals(path))
                return episode.ntkImageCount;
            String episodeNumber = episodeNumberKey(episode.getName());
            if(currentEpisodeNumber.length() > 0 && episodeNumber.length() > 0) {
                if(currentEpisodeNumber.equals(episodeNumber))
                    return episode.ntkImageCount;
                continue;
            }
            if(episode.getId() == id)
                return episode.ntkImageCount;
        }
        return 0;
    }

    public void setImgs(List<String> imgs) {
        this.imgs = imgs;
    }

    public boolean copyViewerStateFrom(Manga source) {
        if(source == null
                || source.getId() != getId()
                || source.getBaseMode() != getBaseMode()
                || source.getTitleId() != getTitleId()
                || isFetchInProgress()
                || source.isFetchInProgress())
            return false;
        List<String> sourceImages = source.getImgs(null);
        List<Manga> sourceEpisodes = safeEpisodeCopy(source.getEps());
        int sourceSeed = source.getSeed();
        String sourceName = source.getName();
        Title sourceTitle = source.getTitle();
        int sourceTitleId = source.getTitleId();
        String sourceNtkEpisodePath = source.getNtkEpisodePath();
        if(isFetchInProgress())
            return false;
        synchronized (this) {
            if(isFetchInProgress())
                return false;
            if(sourceImages != null)
                imgs = new ArrayList<>(sourceImages);
            if(sourceEpisodes != null)
                eps = sourceEpisodes;
            seed = sourceSeed;
            if(sourceName != null && sourceName.length() > 0)
                name = sourceName;
            if(sourceTitle != null)
                setTitle(sourceTitle);
            else
                setTitleId(sourceTitleId);
            setNtkEpisodePath(sourceNtkEpisodePath);
            setNtkImageEpisodeId(source.getNtkImageEpisodeId());
            setNtkImageWorkId(source.getNtkImageWorkId());
            setNtkViewerPayloadHint(source.getNtkViewerPayloadHint());
            setNtkImageCount(source.getNtkImageCount());
            return true;
        }
    }

    public boolean isFetchInProgress() {
        return fetchInProgress;
    }

    public String getThumb() {
        if (thumb == null) return "";
        return thumb;
    }

    public synchronized int fetch(CustomHttpClient client) {
        return fetch(client, true, null);
    }

    public synchronized int fetch(CustomHttpClient client, Map<String, String> cookies) {
        return fetch(client, false, cookies);
    }

    public synchronized int fetchForViewerInitial(CustomHttpClient client) {
        return fetch(client, true, null);
    }

    public synchronized int fetch(CustomHttpClient client, boolean doLogin, Map<String, String> cookies) {
        fetchInProgress = true;
        try {
            if(shouldFetchNtk(client))
                return fetchNtk(client);
            if(isComicWolfSource())
                return fetchWolf(client, "/cv?toon=", "/cv?toon=");
            if(isWebtoonWolfSource())
                return fetchWolf(client, "/view?toon=", "/view?toon=");

            mode = 0;
            List<Manga> previousEpisodes = safeEpisodeCopy(eps);
            imgs = new ArrayList<>();
            Set<String> seenImages = new LinkedHashSet<>();
            eps = new ArrayList<>();
            int tries = 0;
            int timeoutRetries = 0;

            while (imgs.size() == 0 && tries < 2) {
                Response r = null;
                try {
                    String path = baseModeStr(baseMode) + '/' + id;
                    String body;
                    if(doLogin && cookies == null) {
                        CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
                        body = page.body;
                        if(page.code == 302 && body.contains("captcha.php"))
                            return LOAD_CAPTCHA;
                    } else {
                        r = client.mget(path, false, cookies);
                        if(r == null)
                            break;
                        String location = r.header("location");
                        if (r.code() == 302 && location != null && location.contains("captcha.php")) {
                            r.close();
                            return LOAD_CAPTCHA;
                        }
                        body = CustomHttpClient.readBody(r);
                        r = null;
                    }
                    if (body.contains("Connect Error: Connection timed out")) {
                        if(++timeoutRetries > MAX_TIMEOUT_RETRIES)
                            break;
                        continue;
                    }
                    timeoutRetries = 0;

                    Document d = Jsoup.parse(body);
                    seed = extractSeed(body, d, null);

                    //name
                    Element titleElement = d.selectFirst("div.toon-title");
                    if(titleElement != null)
                        name = titleElement.ownText();

                    //temp title
                    Element navbar = d.selectFirst("div.toon-nav");
                    if(navbar != null) {
                        Element titleLink = navbar.select("a").last();
                        if(titleLink != null) {
                            int tid = parseEpisodeId(titleLink.attr("href"), baseModeStr(baseMode) + '/');
                            if (title == null && tid > 0) title = new Title(name, "", "", null, "", tid, baseMode);
                        }

                        //eps
                        Element select = navbar.selectFirst("select");
                        if(select != null) {
                            for (Element e : select.select("option")) {
                                int episodeId = parseEpisodeOptionId(e.attr("value"));
                                if (episodeId > 0)
                                    eps.add(new Manga(episodeId, e.ownText(), "", baseMode));
                            }
                        }
                }

                //imgs
                Element scriptElement = findImageScript(d);
                if(scriptElement != null) {
                    int scriptSeed = extractSeed(scriptElement.data(), d, scriptElement);
                    if(scriptSeed > 0)
                        seed = scriptSeed;
                    String script = scriptElement.data();
                    StringBuilder encodedData = new StringBuilder();
                    encodedData.append('%');
                    for (String line : script.split("\n")) {
                        if (line.contains("html_data+=") && line.indexOf('\'') >= 0 && line.lastIndexOf('\'') > line.indexOf('\'')) {
                            encodedData.append(line.substring(line.indexOf('\'') + 1, line.lastIndexOf('\'')).replaceAll("[.]", "%"));
                        }
                    }
                    if (encodedData.lastIndexOf("%") == encodedData.length() - 1)
                        encodedData.deleteCharAt(encodedData.length() - 1);
                    if(encodedData.length() > 0) {
                        String imgdiv = URLDecoder.decode(encodedData.toString(), "UTF-8");

                        Document id = Jsoup.parse(imgdiv);
                        for (Element e : id.select("img")) {
                            String style = e.attr("style");
                            if (style.length() == 0) {
                                boolean flag = false;
                                for (Attribute a : e.attributes()) {
                                    if (a.getKey().contains("data") && addImageIfValid(client, seenImages, a.getValue())) {
                                        flag = true;
                                        break;
                                    }
                                }
                                if (!flag) {
                                    addImageIfValid(client, seenImages, e.attr("src"));
                                }
                            }
                        }
                    }
                }

            } catch (Exception e2) {
                recordFetchException(e2);
            }
            if (r != null) {
                r.close();
            }
            tries++;
        }
            restoreBetterEpisodeList(previousEpisodes);
            attachEpisodeSeriesMetadata();
            return LOAD_OK;
        } finally {
            fetchInProgress = false;
        }
    }

    private int fetchNtk(CustomHttpClient client) {
        mode = 0;
        List<Manga> previousEpisodes = safeEpisodeCopy(eps);
        imgs = new ArrayList<>();
        Set<String> seenImages = new LinkedHashSet<>();
        Set<String> fallbackBoardImages = new LinkedHashSet<>();
        eps = new ArrayList<>();
        final AsyncNtkPageFetch[] pageFetchRef = new AsyncNtkPageFetch[1];
        final AsyncNtkPageFetch[] directPageFetchRef = new AsyncNtkPageFetch[1];
        final AsyncNtkNativeAck[] nativeAckRef = new AsyncNtkNativeAck[1];
        try {
            int tid = title != null && title.getId() > 0 ? title.getId() : titleId;
            if(title != null && title.getId() > 0 && titleId != title.getId())
                titleId = title.getId();
            if(tid <= 0)
                return LOAD_OK;
            String segment = baseMode == MTitle.base_webtoon ? "webtoon" : "manhwa";
            String path = getNtkEpisodePath();
            if(path.length() == 0) {
                ensureNtkEpisodePathFromIdentity();
                path = getNtkEpisodePath();
            }
            if(path.length() == 0) {
                path = "/" + segment + "/" + tid + "/" + id;
                setNtkEpisodePath(path);
            }
            if(installCompleteAuthoritativeNtkManifest(path, seenImages)) {
                logNtkViewerParse("authoritative-manifest-fast", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            boolean nativeAckMode = isNtkNativeAckModeOverride();
            boolean apiFallbackMode = isNtkApiFallbackModeOverride();
            boolean strictApiFallbackMode = isNtkStrictApiFallbackModeOverride();
            final boolean syntheticWebtoonDirectOnlyEpisode = isNtkSyntheticWebtoonEpisodePath(path)
                    && !nativeAckMode
                    && !apiFallbackMode;
            if(!nativeAckMode
                    && !apiFallbackMode
                    && !isNtkManhwaEpisodePath(path)
                    && shouldProbeGeneratedModeBeforeApi(path, getNtkImageCount())
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                logNtkViewerParse("generated-known-fast", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            final boolean apiFirstNtkEpisode = isNtkViewerEpisodePath(path);
            final boolean apiFirstWebtoonEpisode = isNtkWebtoonEpisodePath(path);
            final boolean modernNtkGuardRoot = client.isModernNtkGuardRootForPath(path);
            boolean allowGeneratedImages = !apiFirstNtkEpisode && !nativeAckMode && !apiFallbackMode;
            final boolean skipGeneratedForSlugEpisode = shouldSkipNtkGeneratedForEpisodePath(path);
            final boolean apiFirstCanonicalWebtoonEpisode = shouldPreferNtkApiForCanonicalWebtoonPath(path);
            if(!nativeAckMode
                    && modernNtkGuardRoot
                    && apiFirstNtkEpisode
                    && isNumericNtkGeneratedEpisodePath(path)
                    && !isNtkManhwaEpisodePath(path)
                    && !skipGeneratedForSlugEpisode
                    && !shouldDeferModernNtkGeneratedProbeUntilAck(client, path)
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                logNtkViewerParse("generated-modern-validated-before-api", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(shouldProbeKnownNtkSlugGeneratedBeforeApi(path)
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), true)) {
                logNtkViewerParse("generated-known-slug-metadata-fast", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(addPreservedNtkViewerPayloadCandidates(client, path, seenImages)) {
                logNtkViewerParse("preserved-viewer-payload-fast", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(allowGeneratedImages && !apiFirstNtkEpisode
                    && (apiFirstCanonicalWebtoonEpisode || skipGeneratedForSlugEpisode)
                    && addNtkSlugWebtoonGeneratedImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), true)) {
                logNtkViewerParse("generated-wt-slug", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(allowGeneratedImages && (skipGeneratedForSlugEpisode || apiFirstCanonicalWebtoonEpisode)) {
                allowGeneratedImages = false;
                Log.d(TAG, skipGeneratedForSlugEpisode ? "ntk_generated_skip_slug_api path=" + path
                        + ",imageEpisodeId=" + getNtkImageEpisodeId()
                        : "ntk_generated_skip_canonical_api path=" + path
                        + ",imageCount=" + getNtkImageCount());
            }
            if((isNtkSyntheticWebtoonEpisodePath(path) || shouldSkipNtkGeneratedForEpisodePath(path))
                    && shouldTryUnsignedViewerManifestFirst(client, path))
                startNtkKnownSlugWebtoonImageApiPrefetch(client, path, "fetch_ntk_enter");
            if(isNtkSyntheticWebtoonEpisodePath(path)
                    && path.toLowerCase(Locale.ROOT).startsWith("/webtoon/")
                    && isNumericNtkId(getNtkImageWorkId())
                    && isNumericNtkId(getNtkImageEpisodeId())
                    && getNtkImageCount() > 1) {
                List<String> urls = client.fetchNtkWebtoonUnsignedViewerImageUrls(
                        path, getNtkImageWorkId(), getNtkImageEpisodeId(), null);
                if(urls != null && urls.size() > 0) {
                    for(String url : urls)
                        addImageIfValid(client, seenImages, url);
                    if(imgs.size() > 0) {
                        logNtkViewerParse("api-synthetic-webtoon-metadata-direct", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                }
                if(client.hasRecentNtkViewerImageManifestMissing(path)) {
                    logNtkViewerParse("unavailable", null, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_ERROR;
                }
            }
            final String viewerPath = path;
            Runnable startPageFetchIfNeeded = () -> {
                if(pageFetchRef[0] == null)
                    pageFetchRef[0] = startAsyncNtkPageFetch(client, viewerPath);
            };
            Runnable startDirectPageFetchIfNeeded = () -> {
                if(directPageFetchRef[0] == null)
                    directPageFetchRef[0] = startAsyncNtkPageFetch(client, viewerPath,
                            CustomHttpClient.FetchMode.DIRECT_ONLY);
            };
            Runnable startNativeAckIfNeeded = () -> {
                if(modernNtkGuardRoot)
                    return;
                if(nativeAckRef[0] == null)
                    nativeAckRef[0] = startAsyncNtkNativeAck(client, viewerPath);
            };
            boolean joinForegroundGeneratedOwner = isNumericNtkGeneratedEpisodePath(path)
                    && MainApplication.isNtkForegroundViewerPath(path)
                    && ReaderImageCache.INSTANCE.speculativeNtkGeneratedImageUrls(
                    path, android.os.SystemClock.elapsedRealtime() - 30_000L).size() >= 2;
            if(joinForegroundGeneratedOwner
                    && waitForCompletePreparedGeneratedManifest(path, 2_500L)
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), true)) {
                logNtkViewerParse("generated-modern-foreground-owner-join", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(nativeAckMode
                    && modernNtkGuardRoot
                    && isNtkViewerEpisodePath(path)
                    && isNumericNtkGeneratedEpisodePath(path)
                    && !shouldDeferModernNtkGeneratedProbeUntilAck(client, path)
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                startGeneratedWebtoonRecoveryPageFetchIfNeeded(path, startPageFetchIfNeeded);
                logNtkViewerParse("generated-modern-native-ack-fast-before-api", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            final boolean[] generatedPrimaryValidationMiss = new boolean[]{false};
            Runnable startFallbackFetchIfGeneratedBlocked = () -> {
                generatedPrimaryValidationMiss[0] = true;
                Log.d(TAG, "ntk_generated_miss_start_fallback path=" + viewerPath);
                startNativeAckIfNeeded.run();
                startDirectPageFetchIfNeeded.run();
            };
            if(apiFirstNtkEpisode || skipGeneratedForSlugEpisode || apiFirstCanonicalWebtoonEpisode) {
                if(!syntheticWebtoonDirectOnlyEpisode) {
                    startNativeAckIfNeeded.run();
                    awaitAsyncNtkNativeAckStarted(nativeAckRef[0], 80L);
                }
                startDirectPageFetchIfNeeded.run();
                if(apiFirstNtkEpisode || (skipGeneratedForSlugEpisode && !apiFirstNtkEpisode))
                    if(!syntheticWebtoonDirectOnlyEpisode && !nativeAckMode && !strictApiFallbackMode)
                        startPageFetchIfNeeded.run();
            }
            boolean apiOptimisticGeneratedCandidate = apiFallbackMode
                    && !strictApiFallbackMode
                    && shouldTryNtkGeneratedBeforeApiFallback(path);
            if(apiFallbackMode && !apiOptimisticGeneratedCandidate) {
                startNativeAckIfNeeded.run();
                startDirectPageFetchIfNeeded.run();
                if(!strictApiFallbackMode
                        && !apiFirstNtkEpisode)
                    startPageFetchIfNeeded.run();
            }
            boolean nativeAckCompleted = false;
            boolean ignoreDirectPageFetchForFirstFrame = false;
            boolean apiOptimisticGeneratedFastPath = apiOptimisticGeneratedCandidate
                    && shouldUseOptimisticNtkGeneratedFastPath(path);
            if(apiOptimisticGeneratedCandidate) {
                startDirectPageFetchIfNeeded.run();
                if(!strictApiFallbackMode)
                    startPageFetchIfNeeded.run();
                CustomHttpClient.PageResponse directApiPage =
                        awaitFastNtkApiPageFetch(directPageFetchRef[0], pageFetchRef[0],
                                path, NTK_API_FALLBACK_DIRECT_RACE_WAIT_MS);
                if(addFastNtkApiPageImageCandidates(client, directApiPage, path, seenImages, false)) {
                    logNtkViewerParse("api-optimistic-direct-before-generated", directApiPage, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
            }
            if(apiOptimisticGeneratedFastPath
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                logNtkViewerParse("api-optimistic-generated-fast", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(apiOptimisticGeneratedCandidate
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                logNtkViewerParse("api-optimistic-generated", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(nativeAckMode && modernNtkGuardRoot) {
                boolean deferGeneratedUntilStrictAck =
                        shouldDeferModernNtkGeneratedProbeUntilAck(client, path);
                if(isNtkViewerEpisodePath(path)
                        && isNumericNtkGeneratedEpisodePath(path)
                        && !deferGeneratedUntilStrictAck)
                    startEarlyNtkGeneratedPublishProbeIfNeeded(client, path);
                if(isNtkViewerEpisodePath(path)
                        && isNumericNtkGeneratedEpisodePath(path)
                        && !deferGeneratedUntilStrictAck
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                    startGeneratedWebtoonRecoveryPageFetchIfNeeded(path, startPageFetchIfNeeded);
                    logNtkViewerParse("generated-modern-native-ack-probed-early", null, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                startDirectPageFetchIfNeeded.run();
                if(isNtkWebtoonEpisodePath(path) && !isNtkSyntheticWebtoonEpisodePath(path))
                    startPageFetchIfNeeded.run();
                CustomHttpClient.PageResponse directApiPage =
                        awaitFastNtkApiPageFetch(directPageFetchRef[0], pageFetchRef[0], path,
                                NTK_API_FALLBACK_DIRECT_READY_WAIT_MS);
                if(addFastNtkApiPageImageCandidates(client, directApiPage, path, seenImages,
                        !apiFallbackMode, true)) {
                    logNtkViewerParse("api-modern-native-ack-direct", directApiPage, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                    logNtkViewerParse("api-modern-native-ack-cached", null, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                if(!apiFirstCanonicalWebtoonEpisode
                        && isNumericNtkGeneratedEpisodePath(path)
                        && hasCachedReachableNtkGeneratedImageExtension(path)
                        && !shouldDeferModernNtkGeneratedProbeUntilAck(client, path)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), true)) {
                    logNtkViewerParse("generated-modern-native-ack", directApiPage, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
            }
            if(nativeAckMode && !modernNtkGuardRoot) {
                if(isNtkStrictNativeAckModeOverride()) {
                    nativeAckCompleted = client.performNtkNativeAckBypass(client.getUrl(path), path);
                    if(nativeAckCompleted
                            && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), true)) {
                        logNtkViewerParse("generated-after-ack", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(!nativeAckCompleted && skipGeneratedForSlugEpisode) {
                        ignoreDirectPageFetchForFirstFrame = true;
                        startPageFetchIfNeeded.run();
                        CustomHttpClient.PageResponse earlyPage =
                                awaitFastNtkApiPageFetch(null, pageFetchRef[0], path, NTK_STRICT_ACK_FAILED_PAGE_FAST_PATH_MS);
                        if(isUsableNtkApiPage(earlyPage)
                                && addNtkApiViewerImageCandidates(client, earlyPage.body, path, seenImages, false)) {
                            logNtkViewerParse("api-after-strict-ack-failed", earlyPage, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                    }
                } else {
                    startNativeAckIfNeeded.run();
                    boolean nativeOptimisticGeneratedFastPath = !skipGeneratedForSlugEpisode
                            && !apiFirstCanonicalWebtoonEpisode
                            && shouldUseOptimisticNtkGeneratedFastPath(path);
                    if(nativeOptimisticGeneratedFastPath
                            && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                        logNtkViewerParse("native-ack-optimistic-generated-fast", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(!skipGeneratedForSlugEpisode
                            && !apiFirstCanonicalWebtoonEpisode
                            && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                        logNtkViewerParse("native-ack-optimistic-generated", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    startDirectPageFetchIfNeeded.run();
                    if(apiFirstCanonicalWebtoonEpisode) {
                        if(awaitCachedNtkViewerImageApiCandidatesUntilPageReady(
                                client, path, seenImages, directPageFetchRef[0], null,
                                NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS)) {
                            logNtkViewerParse("api-cached-canonical-generated", null, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                        CustomHttpClient.PageResponse earlyPage =
                                awaitFastNtkApiPageFetch(directPageFetchRef[0], null,
                                        path, NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS);
                        if(addNtkDirectTextImageCandidates(client,
                                earlyPage == null ? null : earlyPage.body, path, seenImages)) {
                            startFirstNtkApiImageStream(client, path, imgs);
                            logNtkViewerParse("api-first-canonical-webtoon-direct-images",
                                    earlyPage, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                        if(addNtkViewerShellGeneratedImageCandidates(client,
                                earlyPage == null ? null : earlyPage.body, path, seenImages, false)) {
                            startFirstNtkApiImageStream(client, path, imgs);
                            logNtkViewerParse("api-first-canonical-webtoon-shell-generated",
                                    earlyPage, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                        if(addFastNtkApiPageImageCandidates(client, earlyPage, path, seenImages,
                                false, true)) {
                            logNtkViewerParse("api-first-canonical-webtoon", earlyPage, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                        if(isUsableNtkApiPage(earlyPage) && hasNtkViewerImageApiPayload(earlyPage.body)) {
                            logNtkViewerParse("api-first-canonical-webtoon-empty", earlyPage, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_ERROR;
                        }
                    }
                    nativeAckCompleted = awaitAsyncNtkNativeAck(nativeAckRef[0],
                            NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS, false);
                    if(nativeAckCompleted
                            && !apiFirstCanonicalWebtoonEpisode
                            && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), true)) {
                        logNtkViewerParse("generated-after-ack", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(nativeAckCompleted) {
                        CustomHttpClient.PageResponse ackedDirectPage =
                                fetchNtkDirectViewerPageAfterAck(client, path);
                        if(addFastNtkApiPageImageCandidates(client, ackedDirectPage, path, seenImages, false)) {
                            logNtkViewerParse("api-after-ack-direct", ackedDirectPage, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                    }
                    if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                        logNtkViewerParse("api-cached-webview", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    CustomHttpClient.PageResponse earlyPage =
                            awaitFastNtkApiPageFetch(directPageFetchRef[0], pageFetchRef[0], path, 0L);
                    if(addFastNtkApiPageImageCandidates(client, earlyPage, path, seenImages, false)) {
                        logNtkViewerParse("api-missing", earlyPage, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                }
            } else if(apiFallbackMode) {
                startNativeAckIfNeeded.run();
                startDirectPageFetchIfNeeded.run();
                if(!strictApiFallbackMode)
                    startPageFetchIfNeeded.run();
                if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                    logNtkViewerParse("api-cached-webview", null, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                CustomHttpClient.PageResponse directApiPage =
                        awaitFastNtkApiPageFetch(directPageFetchRef[0], null, path,
                                NTK_API_FALLBACK_DIRECT_READY_WAIT_MS);
                if(addFastNtkApiPageImageCandidates(client, directApiPage, path, seenImages, false)) {
                    startFirstNtkApiImageStream(client, path, imgs);
                    logNtkViewerParse("api-fallback-direct-before-ack", directApiPage, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                if(!strictApiFallbackMode && !skipGeneratedForSlugEpisode) {
                    nativeAckCompleted = awaitAsyncNtkNativeAck(nativeAckRef[0],
                            NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS, false);
                    if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                        logNtkViewerParse("api-cached-webview", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(nativeAckCompleted
                            && shouldTryNtkGeneratedBeforeApiFallback(path)
                            && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), true)) {
                        logNtkViewerParse("api-accelerated-after-ack", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                }
                if(!nativeAckCompleted && isNtkAccessBlockedForViewer(client, directApiPage)) {
                    if(isNumericNtkGeneratedEpisodePath(path)) {
                        logNtkViewerParse("api-fallback-blocked-continue-generated",
                                directApiPage, path, 0, 0);
                    } else {
                    logNtkViewerParse("api-fallback-blocked-fast", directApiPage, path, 0, 0);
                    return LOAD_CAPTCHA;
                    }
                }
            }
            boolean validateGeneratedFirstImage = true;
            boolean generatedCandidatesChecked = false;
            if(allowGeneratedImages) {
                generatedCandidatesChecked = true;
                boolean optimisticGeneratedFastPath = shouldUseOptimisticNtkGeneratedFastPath(path);
                if((isNtkGeneratedImmediateModeOverride() || optimisticGeneratedFastPath)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                    logNtkViewerParse(isNtkGeneratedImmediateModeOverride()
                            ? "generated-fast" : "generated-optimistic", null, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                if(hasCachedUnreachableNtkGeneratedImageExtension(path)) {
                    startFallbackFetchIfGeneratedBlocked.run();
                } else if(addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), validateGeneratedFirstImage, startFallbackFetchIfGeneratedBlocked)) {
                    logNtkViewerParse("generated-validated", null, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                if(generatedPrimaryValidationMiss[0]) {
                    CustomHttpClient.PageResponse earlyPage =
                            awaitFastNtkApiPageFetch(directPageFetchRef[0], pageFetchRef[0],
                                    path, NTK_GENERATED_MISS_PAGE_FAST_PATH_MS);
                    if(addFastNtkApiPageImageCandidates(client, earlyPage, path, seenImages,
                            !generatedCandidatesChecked && !apiFallbackMode, true)) {
                        logNtkViewerParse("api-missing", earlyPage, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                        logNtkViewerParse("api-cached-webview", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    nativeAckCompleted = awaitAsyncNtkNativeAck(nativeAckRef[0],
                            NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS, false);
                    if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                        logNtkViewerParse("api-cached-webview", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(nativeAckCompleted
                            && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), true)) {
                        logNtkViewerParse("generated-after-miss-ack", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    CustomHttpClient.PageResponse completedPage =
                            completedNtkPageFetch(directPageFetchRef[0], false);
                    if(addFastNtkApiPageImageCandidates(client, completedPage, path, seenImages, false, true)) {
                        logNtkViewerParse("api-after-miss-ack", completedPage, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                } else {
                    startNativeAckIfNeeded.run();
                    nativeAckCompleted = awaitAsyncNtkNativeAck(nativeAckRef[0]);
                }
                if(nativeAckCompleted) {
                    if(addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), true)) {
                        logNtkViewerParse("generated-after-ack", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(generatedPrimaryValidationMiss[0])
                        startPageFetchIfNeeded.run();
                }
            }
            AsyncNtkPageFetch firstFrameDirectFetch =
                    ignoreDirectPageFetchForFirstFrame ? null : directPageFetchRef[0];
            CustomHttpClient.PageResponse page = (apiFallbackMode || firstFrameDirectFetch != null)
                    ? awaitBestNtkApiPageFetch(firstFrameDirectFetch, pageFetchRef[0], client, path)
                    : awaitAsyncNtkPageFetch(pageFetchRef[0], client, path);
            if(installCompleteAuthoritativeNtkManifest(path, seenImages)) {
                logNtkViewerParse("authoritative-manifest-after-page-race", page, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(isNtkViewerUnavailableEpisode(page == null ? null : page.body)) {
                logNtkViewerParse("unavailable", page, path, 0, 0);
                return LOAD_ERROR;
            }
            if(pageFetchRef[0] == null
                    && !isUsableNtkApiPage(page)
                    && (apiFirstNtkEpisode || skipGeneratedForSlugEpisode || nativeAckMode || apiFallbackMode)) {
                startPageFetchIfNeeded.run();
                page = awaitBestNtkApiPageFetch(firstFrameDirectFetch, pageFetchRef[0], client, path);
                if(isNtkViewerUnavailableEpisode(page == null ? null : page.body)) {
                    logNtkViewerParse("unavailable", page, path, 0, 0);
                    return LOAD_ERROR;
                }
            }
            if(installCompleteAuthoritativeNtkManifest(path, seenImages)) {
                logNtkViewerParse("authoritative-manifest-after-page-retry", page, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                logNtkViewerParse("api-cached-webview", page, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(apiFirstCanonicalWebtoonEpisode
                    && isUsableNtkApiPage(page)
                    && hasNtkViewerImageApiPayload(page.body)) {
                if(addNtkDirectTextImageCandidates(client, page.body, path, seenImages)) {
                    startFirstNtkApiImageStream(client, path, imgs);
                    logNtkViewerParse("api-first-canonical-webtoon-direct-images", page, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                if(addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, false)) {
                    startFirstNtkApiImageStream(client, path, imgs);
                    logNtkViewerParse("api-first-canonical-webtoon-shell-generated", page, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                if(!nativeAckCompleted && nativeAckRef[0] != null)
                    nativeAckCompleted = awaitAsyncNtkNativeAck(nativeAckRef[0],
                            NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS, false);
                if(addFastNtkApiPageImageCandidates(client, page, path, seenImages, false, true)) {
                    logNtkViewerParse(nativeAckCompleted
                            ? "api-first-canonical-webtoon-acked"
                            : "api-first-canonical-webtoon", page, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
            }
            if(!nativeAckCompleted && nativeAckRef[0] == null
                    && (client.isCloudflareChallengeResponse(page.code, page.body)
                    || looksLikeNtkBlockedPage(page.body))) {
                startNativeAckIfNeeded.run();
            }
            if(!nativeAckCompleted && nativeAckRef[0] != null
                    && (client.isCloudflareChallengeResponse(page.code, page.body)
                    || looksLikeNtkBlockedPage(page.body))) {
                nativeAckCompleted = awaitAsyncNtkNativeAck(nativeAckRef[0]);
            }
            if(nativeAckCompleted && (client.isCloudflareChallengeResponse(page.code, page.body)
                    || looksLikeNtkBlockedPage(page.body))) {
                page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
            }
            boolean blockedPage = client.isCloudflareChallengeResponse(page.code, page.body)
                    || looksLikeNtkBlockedPage(page.body);
            boolean missingPage = page.code >= 400 || looksLikeNtkMissingPage(page.body);
            if(blockedPage) {
                if(allowGeneratedImages && !generatedCandidatesChecked
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages, ntkGeneratedImageCandidateCount(), validateGeneratedFirstImage)) {
                    logNtkViewerParse("generated-blocked", page, path, 0, 0);
                } else if(isNumericNtkGeneratedEpisodePath(path)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), true)) {
                    logNtkViewerParse("generated-blocked-native", page, path, 0, 0);
                } else {
                    if(nativeAckMode && modernNtkGuardRoot) {
                        try {
                            CustomHttpClient.PageResponse recoveredPage = fetchFreshNtkPage(client, path);
                            if(recoveredPage != null
                                    && (addCachedNtkViewerImageApiCandidates(client, path, seenImages)
                                    || addFastNtkApiPageImageCandidates(client, recoveredPage, path, seenImages,
                                    false, true)
                                    || addNtkApiViewerImageCandidates(client, recoveredPage.body, path,
                                    seenImages, false))) {
                                logNtkViewerParse("api-blocked-modern-recovered", recoveredPage, path, 0, 0);
                                restoreBetterEpisodeList(previousEpisodes);
                                attachEpisodeSeriesMetadata();
                                return LOAD_OK;
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "ntk_blocked_modern_recover_error path=" + path + "," + e);
                        }
                    }
                    if(nativeAckMode && modernNtkGuardRoot
                            && addNtkModernAckProofRecoveryImages(client, path, seenImages)) {
                        logNtkViewerParse("api-blocked-modern-proof-recovered", page, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    logNtkViewerParse("blocked", page, path, 0, 0);
                    return LOAD_CAPTCHA;
                }
            } else if(missingPage) {
                boolean tokenizedViewer = page.code >= 200 && page.code < 400
                        && hasNtkViewerImageApiPayload(page.body);
                if(tokenizedViewer) {
                    if(addNtkApiViewerImageCandidates(client, page.body, path, seenImages,
                            !generatedCandidatesChecked && !apiFallbackMode)) {
                        logNtkViewerParse("api-missing", page, path, 0, 0);
                    } else if(nativeAckMode
                            && client.performNtkNativeAckBypass(client.getUrl(path), path)
                            && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), true)) {
                        logNtkViewerParse("generated-after-ack", page, path, 0, 0);
                    } else {
                        logNtkViewerParse("api-missing-failed", page, path, 0, 0);
                        return LOAD_CAPTCHA;
                    }
                } else if(modernNtkGuardRoot
                        && client.hasRecentNtkServerAckProof(path)
                        && addNtkModernAckProofRecoveryImages(client, path, seenImages)) {
                    logNtkViewerParse("api-missing-modern-proof-recovered", page, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                } else if(page.code >= 200 && page.code < 400
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages, ntkGeneratedImageCandidateCount(), validateGeneratedFirstImage)) {
                    logNtkViewerParse("generated-missing", page, path, 0, 0);
                } else if(addNtkLastResortWebtoonGeneratedImageCandidates(client, page.body, path, seenImages,
                        ntkGeneratedImageCandidateCount())) {
                    logNtkViewerParse("generated-last-resort-missing", page, path, 0, 0);
                } else {
                    logNtkViewerParse("missing", page, path, 0, 0);
                    return LOAD_ERROR;
                }
            } else {
                if(isNtkSyntheticWebtoonEpisodePath(path)) {
                    String syntheticBody = page == null ? null : page.body;
                    String syntheticMergedBody = syntheticBody == null ? null
                            : mergeNtkViewerEpisodeChunkPayload(client, syntheticBody, path,
                            "synthetic_parse");
                    if(syntheticMergedBody != null
                            && hasNtkViewerImageApiPayload(syntheticMergedBody)) {
                        if(addNtkApiViewerImageCandidates(client, syntheticMergedBody, path, seenImages, false)) {
                            logNtkViewerParse("api-synthetic-webtoon", page, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                        boolean tokenPrefetchStarted = startAsyncNtkViewerImageApiFetchFromToken(
                                client, path, syntheticMergedBody, "synthetic-parse");
                        if(tokenPrefetchStarted
                                && awaitCachedNtkViewerImageApiCandidates(client, path, seenImages,
                                NTK_WEBVIEW_VIEWER_IMAGES_CACHE_WAIT_MS)) {
                            logNtkViewerParse("api-synthetic-webtoon-token-prefetch", page, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                        if(client.hasRecentStrictNtkAdAckProof(path)
                                && addNtkApiViewerImageCandidates(client, syntheticMergedBody, path, seenImages, false)) {
                            logNtkViewerParse("api-synthetic-webtoon-after-ack-proof", page, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
                        }
                    }
                    if(syntheticMergedBody != null
                            && syntheticMergedBody != syntheticBody
                            && addNtkLastResortWebtoonGeneratedImageCandidates(client, syntheticMergedBody,
                            path, seenImages, ntkGeneratedImageCandidateCount())) {
                        logNtkViewerParse("generated-synthetic-webtoon-chunk-last-resort", page, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                        logNtkViewerParse("api-synthetic-webtoon-cached", page, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(client.hasRecentStrictNtkAdAckProof(path)
                            && addNtkModernAckProofRecoveryImages(client, path, seenImages)) {
                        logNtkViewerParse("api-synthetic-webtoon-proof-recovered", page, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(addNtkLastResortWebtoonGeneratedImageCandidates(client, syntheticMergedBody, path, seenImages,
                            ntkGeneratedImageCandidateCount())) {
                        logNtkViewerParse("generated-synthetic-webtoon-last-resort", page, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    logNtkViewerParse("api-synthetic-webtoon-empty", page, path, 0, 0);
                    return LOAD_CAPTCHA;
                }
                Document d = Jsoup.parse(page.body);

                Element h1 = d.selectFirst("h1");
                String parsedName = h1 == null ? extractNtkViewerEpisodeName(d) : h1.text().trim();
                if(parsedName.length() > 0 && !isNtkBlockedViewerTitle(parsedName))
                    name = parsedName;

                Elements pageImages = d.select("img");
                addNtkDocumentImageCandidates(client, d, seenImages, fallbackBoardImages);
                addNtkTextImageCandidates(client, page.body, seenImages, fallbackBoardImages);
                boolean confirmedEmptyViewerPayload = isNtkViewerConfirmedEmptyPayload(page.body, path);
                boolean apiAttempted = false;
                if(allowGeneratedImages && !generatedCandidatesChecked)
                    addNtkViewerMetaImageCandidates(client, page.body, path, seenImages);
                if(apiFirstNtkEpisode
                        && !confirmedEmptyViewerPayload
                        && !generatedCandidatesChecked
                        && !isNtkManhwaEpisodePath(path)
                        && !skipGeneratedForSlugEpisode
                        && !apiFirstCanonicalWebtoonEpisode
                        && hasCachedReachableNtkGeneratedImageExtension(path)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), true)) {
                    generatedCandidatesChecked = true;
                    logNtkViewerParse("generated-before-api-empty-page", page, path,
                            pageImages.size(), fallbackBoardImages.size());
                }
                if(apiFirstNtkEpisode && !confirmedEmptyViewerPayload && imgs.size() == 0) {
                    apiAttempted = true;
                    addNtkApiViewerImageCandidates(client, page.body, path, seenImages, false);
                }
                if(!apiFirstNtkEpisode)
                    addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, true);
                compactNtkImageCandidates(page.body, seenImages);
                if(!confirmedEmptyViewerPayload && !apiAttempted
                        && shouldFetchNtkApiViewerImagesForSparseParse(page.body, path, pageImages.size()))
                    addNtkApiViewerImageCandidates(client, page.body, path, seenImages);
                if(imgs.size() == 0) {
                    for(String src : fallbackBoardImages)
                        addImageIfValid(client, seenImages, src);
                }
                compactNtkImageCandidates(page.body, seenImages);
                if(discardLowConfidenceNtkSingleHtmlImage(d, page.body, path, seenImages)) {
                    Log.d(TAG, "ntk_single_html_image_discarded path=" + path
                            + ",imgTags=" + pageImages.size());
                }
                if(imgs.size() == 0
                        && !isNtkManhwaEpisodePath(path)
                        && hasCachedReachableNtkGeneratedImageExtension(path)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), true)) {
                    logNtkViewerParse("generated-empty-page", page, path, pageImages.size(), fallbackBoardImages.size());
                }
                if(imgs.size() == 0
                        && isNtkManhwaEpisodePath(path)
                        && pageImages.size() >= 2
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        pageImages.size(), true)) {
                    logNtkViewerParse("generated-manhwa-imgtags-empty-page", page, path,
                            pageImages.size(), fallbackBoardImages.size());
                }
                if(imgs.size() == 0
                        && !confirmedEmptyViewerPayload
                        && !isNtkManhwaEpisodePath(path)
                        && addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, true)) {
                    logNtkViewerParse("generated-shell-empty-page", page, path, pageImages.size(), fallbackBoardImages.size());
                }
                if(imgs.size() == 0
                        && !isNtkManhwaEpisodePath(path)
                        && addNtkLastResortWebtoonGeneratedImageCandidates(client, page.body, path, seenImages,
                        ntkGeneratedImageCandidateCount())) {
                    logNtkViewerParse("generated-last-resort-empty-page", page, path, pageImages.size(), fallbackBoardImages.size());
                }
                if(imgs.size() > 0 && areCurrentNtkImagesOnlyBoardUploads()
                        && isNtkWebtoonEpisodePath(path)) {
                    int boardOnlyCount = imgs.size();
                    imgs.clear();
                    if(addNtkLastResortWebtoonGeneratedImageCandidates(client, page.body, path, seenImages,
                            ntkGeneratedImageCandidateCount())) {
                        logNtkViewerParse("generated-last-resort-board-only", page, path,
                                pageImages.size(), fallbackBoardImages.size());
                    } else {
                        Log.d(TAG, "ntk_board_only_images_rejected path=" + path
                                + ",count=" + boardOnlyCount);
                    }
                }
                if(imgs.size() == 0 && confirmedEmptyViewerPayload) {
                    logNtkViewerParse("confirmed-empty", page, path, pageImages.size(), fallbackBoardImages.size());
                    return LOAD_ERROR;
                }
                if(imgs.size() == 0) {
                    logNtkViewerParse("empty", page, path, pageImages.size(), fallbackBoardImages.size());
                    return LOAD_ERROR;
                } else {
                    logNtkViewerParse("ok", page, path, pageImages.size(), fallbackBoardImages.size());
                }
            }

            List<Manga> titleEpisodes = title == null ? null : safeEpisodeCopy(title.getEps());
            if(titleEpisodes != null && titleEpisodes.size() > 0) {
                eps = titleEpisodes;
                int seriesTitleId = title != null && title.getId() > 0 ? title.getId() : tid;
                for(Manga ep : eps) {
                    ep.setMode(0);
                    ep.setTitle(title);
                    ep.setTitleId(seriesTitleId);
                }
            }
        } catch (Exception e) {
            if((isCloudflareChallenge(e) || isNtkViewerChallengeFailure(client, e))
                    && addNtkModernAckProofRecoveryImages(client, getNtkEpisodePath(), seenImages)) {
                logNtkViewerParse("api-exception-modern-proof-recovered", null, getNtkEpisodePath(), 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(isCloudflareChallenge(e) || isNtkViewerChallengeFailure(client, e))
                return LOAD_CAPTCHA;
            recordFetchException(e);
        } finally {
            cancelAsyncNtkPageFetch(pageFetchRef[0]);
            cancelAsyncNtkPageFetch(directPageFetchRef[0]);
            cancelAsyncNtkNativeAck(nativeAckRef[0]);
        }
        if((imgs == null || imgs.size() == 0)
                && isNtkViewerEpisodePath(getNtkEpisodePath())
                && isNumericNtkGeneratedEpisodePath(getNtkEpisodePath())
                && addNtkGeneratedPathImageCandidates(client, getNtkEpisodePath(), seenImages,
                Math.max(ntkGeneratedImageCandidateCount(), NTK_DEFAULT_GENERATED_PAGE_COUNT), true)) {
            logNtkViewerParse("generated-final-empty", null, getNtkEpisodePath(), 0, 0);
            restoreBetterEpisodeList(previousEpisodes);
            attachEpisodeSeriesMetadata();
            return LOAD_OK;
        }
        if(imgs == null || imgs.size() == 0) {
            Log.d(TAG, "ntk_viewer_parse_empty_final path=" + getNtkEpisodePath()
                    + ",mode=" + getNtkViewerParseReason());
            restoreBetterEpisodeList(previousEpisodes);
            attachEpisodeSeriesMetadata();
            return LOAD_ERROR;
        }
        restoreBetterEpisodeList(previousEpisodes);
        attachEpisodeSeriesMetadata();
        return LOAD_OK;
    }

    private AsyncNtkPageFetch startAsyncNtkPageFetch(CustomHttpClient client, String path) {
        return startAsyncNtkPageFetch(client, path, null);
    }

    private AsyncNtkPageFetch startAsyncNtkPageFetch(CustomHttpClient client, String path,
                                                     CustomHttpClient.FetchMode fetchMode) {
        AsyncNtkPageFetch fetch = new AsyncNtkPageFetch();
        CustomHttpClient.RequestGroup parentGroup = client == null ? null : client.currentRequestGroup();
        CustomHttpClient.RequestGroup requestGroup = parentGroup == null ? null : parentGroup.child();
        if(requestGroup != null
                && fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY
                && isNtkSyntheticWebtoonEpisodePath(path))
            requestGroup.prioritizeWebViewFallback();
        fetch.requestGroup = requestGroup;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                if(ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path)
                        && (client == null || !client.hasCachedNtkViewerPayload(path))) {
                    if(fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY) {
                        Log.d(TAG, "ntk_page_fetch_launch_hold_bypass_direct mode=direct"
                                + ",path=" + path);
                    } else if(!waitForNtkPageFetchLaunchHold(client, path, fetchMode)) {
                        Log.d(TAG, "ntk_page_fetch_launch_hold_cancelled mode="
                                + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                                + ",ms=" + (System.currentTimeMillis() - startedAt)
                                + ",path=" + path);
                        return;
                    }
                }
                if(hasCompleteAuthoritativeNtkManifest(path) || hasForegroundNativeDirectManifest(path)) {
                    Log.d(TAG, "ntk_page_fetch_skip_complete_manifest mode="
                            + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                            + ",path=" + path);
                    return;
                }
                Log.d(TAG, "ntk_page_fetch_start mode="
                        + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                        + ",path=" + path);
                CustomHttpClient.RequestWork<CustomHttpClient.PageResponse> work = () -> {
                    if(fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY) {
                        AtomicBoolean tokenPrefetchStarted = new AtomicBoolean(false);
                        AtomicBoolean directImageHandoffStarted = new AtomicBoolean(false);
                        AtomicBoolean generatedImageHandoffStarted = new AtomicBoolean(false);
                        return client.mgetNtkViewerPayloadPage(path, PAGE_CACHE_TTL_MS, partialText -> {
                            if(partialText == null || partialText.length() == 0)
                                return;
                            if(hasCompleteAuthoritativeNtkManifest(path))
                                return;
                            boolean hasDirectImageMarker = partialText.contains("toonflix.app/")
                                    || partialText.contains("fvcdn")
                                    || partialText.contains("webtoon_uploads")
                                    || partialText.contains("manhwa_uploads")
                                    || partialText.contains("comic_uploads")
                                    || partialText.contains("%2Fwebtoon_uploads%2F")
                                    || partialText.contains("%2Fmanhwa_uploads%2F")
                                    || partialText.contains("%2Fcomic_uploads%2F")
                                    || partialText.contains("%2Fwebtoon%2F")
                                    || partialText.contains("%2Fmanhwa%2F")
                                    || partialText.contains("%2Fwt%2Fepisodes%2F")
                                    || partialText.contains("%2Fblacktoon%2Fepisodes%2F");
                            if(hasDirectImageMarker
                                    && !shouldSkipNtkPartialDirectImageHandoff(path)
                                    && !directImageHandoffStarted.get()) {
                                List<String> directUrls = ntkDirectPageImageUrlsFromText(partialText,
                                        NTK_EARLY_INITIAL_PUBLISH_PAGES);
                                if(!directUrls.isEmpty() && directImageHandoffStarted.compareAndSet(false, true)) {
                                    Log.d(TAG, "ntk_rsc_direct_image_urls_early path=" + path
                                            + ",count=" + directUrls.size()
                                            + ",partialLen=" + partialText.length()
                                            + ",first=" + ntkLogImageName(directUrls.get(0)));
                                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, directUrls);
                                    startFirstNtkApiImageStream(client, path, directUrls);
                                }
                            }
                            if(shouldStartNtkImageApiTokenPrefetchForPageFetch(path)) {
                                String rawToken = ntkViewerImagesToken(partialText);
                                String normalizedPartialText = normalizeNtkViewerPayloadText(partialText);
                                String token = rawToken.length() > 0 ? rawToken : ntkViewerImagesToken(normalizedPartialText);
                                boolean hasImageApiPayload = hasNtkViewerImageApiPayloadNormalized(normalizedPartialText);
                                if(token.length() > 0 && tokenPrefetchStarted.compareAndSet(false, true)) {
                                    publishKpSignedTokenDirectUrls(
                                            client,
                                            path,
                                            normalizedPartialText,
                                            token,
                                            "native-partial-fast-seed");
                                    Log.d(TAG, "ntk_viewer_api_prefetch_token_early_seed path=" + path
                                            + ",partialLen=" + partialText.length()
                                            + ",normalizedLen=" + normalizedPartialText.length()
                                            + ",hasPayload=" + hasImageApiPayload
                                            + ",tokenLen=" + token.length()
                                            + ",tokenWorkId=" + ntkViewerImagesTokenField(token, "w")
                                            + ",tokenEpisodeId=" + ntkViewerImagesTokenField(token, "e"));
                                    // The partial observer runs inside a cancelable document-race lane.
                                    // Exact API authority must outlive that lane's shutdownNow().
                                    startAsyncNtkViewerImageApiFetchFromToken(
                                            client, path, normalizedPartialText, "page-fetch-partial");
                                }
                                if(!hasImageApiPayload)
                                    return;
                                startEarlyGeneratedNtkImageStreamFromPartial(client, path, normalizedPartialText,
                                        generatedImageHandoffStarted);
                                return;
                            }
                            String normalizedPartialText = normalizeNtkViewerPayloadText(partialText);
                            if(!hasNtkViewerImageApiPayloadNormalized(normalizedPartialText))
                                return;
                            startEarlyGeneratedNtkImageStreamFromPartial(client, path, normalizedPartialText,
                                    generatedImageHandoffStarted);
                        });
                    }
                    return client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
                };
                if(requestGroup != null) {
                    if(fetchMode != null) {
                        fetch.page = client.runWithRequestGroup(requestGroup,
                                () -> client.runWithFetchMode(fetchMode, work));
                    } else {
                        fetch.page = client.runWithRequestGroup(requestGroup, work);
                    }
                } else {
                    fetch.page = fetchMode == null
                            ? work.run()
                            : client.runWithFetchMode(fetchMode, work);
                }
                if(hasCompleteAuthoritativeNtkManifest(path)) {
                    Log.d(TAG, "ntk_page_fetch_stop_authoritative_manifest mode="
                            + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                            + ",bodyLen=" + (fetch.page == null || fetch.page.body == null
                            ? 0 : fetch.page.body.length())
                            + ",ms=" + (System.currentTimeMillis() - startedAt)
                            + ",path=" + path);
                    return;
                }
                boolean pageDoneTokenPrefetchStarted = false;
                if(fetch.page != null && fetch.page.code >= 200 && fetch.page.code < 400
                        && fetch.page.body != null
                        && shouldStartNtkImageApiTokenPrefetchForPageFetch(path)) {
                    pageDoneTokenPrefetchStarted = startAsyncNtkViewerImageApiFetchFromToken(
                            client, path, fetch.page.body, "page_done_raw");
                    String pageDoneBody = fetch.page.body;
                    if(!pageDoneTokenPrefetchStarted) {
                        pageDoneBody = mergeNtkViewerEpisodeChunkPayload(client, fetch.page.body,
                                path, "page_done");
                        pageDoneTokenPrefetchStarted = startAsyncNtkViewerImageApiFetchFromToken(
                                client, path, pageDoneBody, "page_done");
                    }
                    if(!pageDoneTokenPrefetchStarted && pageDoneBody != fetch.page.body) {
                        AtomicBoolean generatedImageHandoffStarted = new AtomicBoolean(false);
                        startEarlyGeneratedNtkImageStreamFromPartial(client, path, pageDoneBody,
                                generatedImageHandoffStarted);
                    }
                }
                Log.d(TAG, "ntk_page_fetch_done mode="
                        + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                        + ",code=" + (fetch.page == null ? 0 : fetch.page.code)
                        + ",bodyLen=" + (fetch.page == null || fetch.page.body == null ? 0 : fetch.page.body.length())
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + ",path=" + path);
                if(fetch.page != null && fetch.page.code >= 200 && fetch.page.code < 400
                        && fetch.page.body != null
                        && shouldPreferNtkApiForCanonicalWebtoonPath(path)) {
                    AtomicBoolean generatedImageHandoffStarted = new AtomicBoolean(false);
                    String generatedBody = mergeNtkViewerEpisodeChunkPayload(client, fetch.page.body,
                            path, "page_done_generated");
                    startEarlyGeneratedNtkImageStreamFromPartial(client, path, generatedBody,
                            generatedImageHandoffStarted);
                }
                if(fetch.page != null && fetch.page.code >= 200 && fetch.page.code < 400
                        && shouldStartNtkImageApiPrefetchForPageFetch(path)
                        && !pageDoneTokenPrefetchStarted)
                    startAsyncNtkViewerImageApiFetch(client, path, fetch.page.body);
            } catch (Exception e) {
                fetch.error = e;
                if(isExpectedFetchCancellation(e) || isCancelledRequestGroup(requestGroup)) {
                    Log.d(TAG, "ntk_page_fetch_cancelled mode="
                            + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                            + ",ms=" + (System.currentTimeMillis() - startedAt)
                            + ",path=" + path);
                } else if(fetchMode != CustomHttpClient.FetchMode.DIRECT_ONLY
                        && isNtkPageFetchRequestFailed(e, path)) {
                    Log.d(TAG, "ntk_page_fetch_superseded mode=allow"
                            + ",ms=" + (System.currentTimeMillis() - startedAt)
                            + ",path=" + path
                            + "," + e);
                } else {
                    Log.d(TAG, "ntk_page_fetch_error mode="
                            + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                            + ",ms=" + (System.currentTimeMillis() - startedAt)
                            + ",path=" + path
                            + "," + e);
                }
            } finally {
                if(parentGroup != null)
                    parentGroup.removeChild(requestGroup);
                fetch.done.countDown();
            }
        }, fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "ntk-page-direct-prefetch" : "ntk-page-prefetch");
        fetch.thread = thread;
        thread.setDaemon(true);
        thread.start();
        return fetch;
    }

    private boolean waitForNtkPageFetchLaunchHold(CustomHttpClient client, String path,
                                                  CustomHttpClient.FetchMode fetchMode) {
        long startedAt = System.currentTimeMillis();
        boolean waited = false;
        boolean interrupted = false;
        boolean bypassGeneratedWebtoonRecovery =
                shouldBypassNtkPageFetchLaunchHoldForGeneratedWebtoonRecovery(path, fetchMode);
        while(!bypassGeneratedWebtoonRecovery
                && ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path)
                && (client == null || !client.hasCachedNtkViewerPayload(path))
                && System.currentTimeMillis() - startedAt < NTK_PAGE_FETCH_LAUNCH_HOLD_MAX_MS) {
            waited = true;
            try {
                Thread.sleep(NTK_PAGE_FETCH_LAUNCH_HOLD_POLL_MS);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                break;
            }
        }
        boolean cachedPayload = client != null && client.hasCachedNtkViewerPayload(path);
        boolean held = ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path) && !cachedPayload;
        Log.d(TAG, "ntk_page_fetch_launch_hold_wait mode="
                + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                + ",path=" + path
                + ",waited=" + waited
                + ",bypassGeneratedWebtoonRecovery=" + bypassGeneratedWebtoonRecovery
                + ",held=" + held
                + ",cachedPayload=" + cachedPayload
                + ",interrupted=" + interrupted
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        if(held) {
            Log.d(TAG, "ntk_page_fetch_launch_hold_bypass_no_payload mode="
                    + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                    + ",path=" + path
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
        }
        return !interrupted;
    }

    private static void startGeneratedWebtoonRecoveryPageFetchIfNeeded(String path, Runnable startPageFetchIfNeeded) {
        if(startPageFetchIfNeeded == null)
            return;
        if(!shouldBypassNtkPageFetchLaunchHoldForGeneratedWebtoonRecovery(path, null))
            return;
        Log.d(TAG, "ntk_generated_webtoon_recovery_page_fetch_start path=" + path);
        startPageFetchIfNeeded.run();
    }

    private static boolean shouldBypassNtkPageFetchLaunchHoldForGeneratedWebtoonRecovery(
            String path, CustomHttpClient.FetchMode fetchMode) {
        return fetchMode != CustomHttpClient.FetchMode.DIRECT_ONLY
                && isNtkWebtoonEpisodePath(path)
                && isNumericNtkGeneratedEpisodePath(path);
    }

    private static boolean shouldStartDirectOnlyNtkImageApiPrefetch(String path) {
        if(path == null || path.length() == 0)
            return false;
        boolean generatedNumericNtk = isNumericNtkGeneratedEpisodePath(path);
        if(generatedNumericNtk)
            return true;
        if(isNtkSyntheticWebtoonEpisodePath(path))
            return false;
        boolean allowImageApiDuringAckHold = shouldAllowNtkImageApiPrefetchDuringAckLaunchHold(path);
        if(isInitialNtkWebtoonAckHoldPath(path)
                && ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path)
                && !generatedNumericNtk
                && !allowImageApiDuringAckHold) {
            Log.d(TAG, "ntk_viewer_api_prefetch_skip_launch_hold path=" + path);
            return false;
        }
        if(allowImageApiDuringAckHold
                && ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path)) {
            Log.d(TAG, "ntk_viewer_api_prefetch_allow_launch_hold path=" + path);
        }
        if(ReaderImageCache.isInitialNtkGeneratedStreamActiveForPath(path)) {
            Log.d(TAG, "ntk_viewer_api_prefetch_skip_initial_stream path=" + path);
            return false;
        }
        return shouldPreferNtkApiForCanonicalWebtoonPath(path)
                || shouldSkipNtkGeneratedForEpisodePath(path);
    }

    private static boolean shouldStartNtkImageApiPrefetchForPageFetch(String path) {
        return shouldStartDirectOnlyNtkImageApiPrefetch(path);
    }

    private static boolean shouldStartNtkImageApiTokenPrefetchForPageFetch(String path) {
        return shouldStartNtkImageApiPrefetchForPageFetch(path)
                || isNtkSyntheticWebtoonEpisodePath(path);
    }

    private static boolean isInitialNtkWebtoonAckHoldPath(String path) {
        return path != null && path.startsWith("/webtoon/");
    }

    private static boolean shouldAllowNtkImageApiPrefetchDuringAckLaunchHold(String path) {
        return isNtkSyntheticWebtoonEpisodePath(path);
    }

    private static boolean shouldSkipCurrentNtkImageApiBecauseGeneratedInitialReady(
            CustomHttpClient client, String path) {
        if(path == null || path.length() == 0)
            return false;
        if(!isNumericNtkGeneratedEpisodePath(path))
            return false;
        int earlyGenerated = ReaderImageCache.INSTANCE.earlyNtkImageUrls(
                path, android.os.SystemClock.elapsedRealtime() - 30000L).size();
        if(earlyGenerated < 3)
            return false;
        boolean reachable = client != null && client.hasReachableRecentEarlyNtkImageUrls(path);
        if(!reachable) {
            Log.d(TAG, "ntk_viewer_api_recent_generated_initial_unreachable path=" + path
                    + ",count=" + earlyGenerated);
        }
        return reachable;
    }

    private static boolean shouldSkipNtkPartialDirectImageHandoff(String path) {
        return isNtkSyntheticWebtoonEpisodePath(path);
    }

    private boolean addPreservedNtkViewerPayloadCandidates(CustomHttpClient client, String path,
                                                           Set<String> seenImages) {
        if(client == null || path == null || path.length() == 0 || seenImages == null)
            return false;
        String hint = getNtkViewerPayloadHint();
        if(hint.length() == 0)
            return false;
        String normalized = normalizeNtkViewerPayloadText(hint);
        int before = imgs == null ? 0 : imgs.size();
        List<String> directUrls = ntkDirectPageImageUrlsFromText(normalized, NTK_EARLY_INITIAL_PUBLISH_PAGES);
        ArrayList<String> contentUrls = new ArrayList<>();
        for(String url : directUrls) {
            if(isNtkContentUploadImageUrl(url)) {
                addImageIfValid(client, seenImages, url);
                contentUrls.add(url);
            }
        }
        if(imgs != null && imgs.size() > before) {
            Log.d(TAG, "ntk_preserved_viewer_direct_images path=" + path
                    + ",count=" + contentUrls.size()
                    + ",first=" + (contentUrls.isEmpty() ? "" : safeLogImage(contentUrls.get(0))));
            startFirstNtkApiImageStream(client, path, contentUrls);
            return true;
        }
        if(hasNtkViewerImageApiPayloadNormalized(normalized)
                && addNtkApiViewerImageCandidates(client, normalized, path, seenImages, false)) {
            Log.d(TAG, "ntk_preserved_viewer_api_payload path=" + path
                    + ",tokenLen=" + ntkViewerImagesToken(normalized).length());
            return true;
        }
        if(isNtkSyntheticWebtoonEpisodePath(path)
                && addNtkLastResortWebtoonGeneratedImageCandidates(
                client, normalized, path, seenImages, ntkGeneratedImageCandidateCount())) {
            Log.d(TAG, "ntk_preserved_viewer_generated_metadata path=" + path
                    + ",hintLen=" + normalized.length());
            return true;
        }
        return false;
    }

    private static boolean isNtkContentUploadImageUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("/webtoon_uploads/")
                || lower.contains("/manhwa_uploads/")
                || lower.contains("/comic_uploads/");
    }

    private boolean addNtkModernAckProofRecoveryImages(CustomHttpClient client, String path,
                                                       Set<String> seenImages) {
        if(client == null || path == null || path.length() == 0 || seenImages == null)
            return false;
        if(!client.isModernNtkGuardRootForPath(path) || !client.hasRecentNtkServerAckProof(path))
            return false;
        long startedAt = System.currentTimeMillis();
        try {
            CustomHttpClient.PageResponse recoveredPage = fetchFreshNtkPage(client, path);
            boolean recovered = addCachedNtkViewerImageApiCandidates(client, path, seenImages)
                    || (recoveredPage != null
                    && addFastNtkApiPageImageCandidates(client, recoveredPage, path, seenImages,
                    false, true))
                    || (recoveredPage != null
                    && addNtkApiViewerImageCandidates(client, recoveredPage.body, path, seenImages,
                    false));
            Log.d(TAG, "ntk_modern_ack_proof_recovery path=" + path
                    + ",recovered=" + recovered
                    + ",code=" + (recoveredPage == null ? 0 : recoveredPage.code)
                    + ",bodyLen=" + (recoveredPage == null || recoveredPage.body == null ? 0 : recoveredPage.body.length())
                    + ",images=" + (imgs == null ? 0 : imgs.size())
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            return recovered;
        } catch (Exception e) {
            Log.d(TAG, "ntk_modern_ack_proof_recovery_error path=" + path
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + "," + e);
            return false;
        }
    }

    private CustomHttpClient.PageResponse fetchNtkDirectViewerPageAfterAck(CustomHttpClient client, String path) {
        if(client == null || path == null || path.length() == 0)
            return null;
        long startedAt = System.currentTimeMillis();
        try {
            CustomHttpClient.PageResponse page = client.runWithFetchMode(
                    CustomHttpClient.FetchMode.DIRECT_ONLY,
                    () -> client.mgetNtkViewerPayloadPage(path, PAGE_CACHE_TTL_MS));
            Log.d(TAG, "ntk_page_fetch_after_ack_direct code=" + (page == null ? 0 : page.code)
                    + ",bodyLen=" + (page == null || page.body == null ? 0 : page.body.length())
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",path=" + path);
            return page;
        } catch (Exception e) {
            Log.d(TAG, "ntk_page_fetch_after_ack_direct_error ms="
                    + (System.currentTimeMillis() - startedAt)
                    + ",path=" + path
                    + "," + e);
            return null;
        }
    }

    private void startAsyncNtkViewerImageApiFetch(CustomHttpClient client, String path, String body) {
        if(client == null || path == null || body == null || !hasNtkViewerImageApiPayload(body))
            return;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                prefetchNtkViewerImageApiCandidates(client, body, path);
                Log.d(TAG, "ntk_viewer_api_prefetch_done path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_api_prefetch_error path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-viewer-image-api-prefetch");
        thread.setDaemon(true);
        thread.start();
    }

    private void startAsyncNtkViewerImageApiFetchFromToken(CustomHttpClient client, String path, String partialBody) {
        startAsyncNtkViewerImageApiFetchFromToken(client, path, partialBody, "partial");
    }

    public boolean startNtkKpPayloadTokenImagePrefetch(CustomHttpClient client, String path,
                                                       String payloadBody, String stage) {
        if(client == null || path == null || payloadBody == null)
            return false;
        if(isStrictSourceAuthorityManaged(path))
            return false;
        if(!isNtkKpWebtoonEpisodePath(path))
            return false;
        return startAsyncNtkViewerImageApiFetchFromToken(
                client,
                path,
                payloadBody,
                stage == null || stage.length() == 0 ? "reader-payload" : stage);
    }

    private String mergeNtkViewerEpisodeChunkPayload(CustomHttpClient client, String body,
                                                     String path, String stage) {
        if(client == null || body == null || body.length() == 0 || path == null || path.length() == 0)
            return body;
        List<String> chunks = ntkViewerEpisodeNextChunkPaths(body, path);
        String aliasPayload = ntkViewerNumericAliasPayload(client, body, path, stage);
        if(chunks.isEmpty() && aliasPayload.length() == 0)
            return body;
        String safeStage = stage == null || stage.length() == 0 ? "unknown" : stage;
        StringBuilder merged = new StringBuilder(body.length() + 32768 + aliasPayload.length());
        merged.append(body);
        if(aliasPayload.length() > 0)
            merged.append('\n').append(aliasPayload);
        int fetched = 0;
        int attempted = 0;
        for(String chunkPath : chunks) {
            if(chunkPath == null || chunkPath.length() == 0)
                continue;
            if(attempted >= 4)
                break;
            attempted++;
            long startedAt = System.currentTimeMillis();
            try {
                CustomHttpClient.PageResponse chunk = client.mgetNtkStaticTextPage(chunkPath,
                        PAGE_CACHE_TTL_MS);
                String chunkBody = chunk == null || chunk.body == null ? "" : chunk.body;
                Log.d(TAG, "ntk_viewer_episode_chunk stage=" + safeStage
                        + ",viewerPath=" + path
                        + ",chunkPath=" + chunkPath
                        + ",code=" + (chunk == null ? 0 : chunk.code)
                        + ",fromCache=" + (chunk != null && chunk.fromCache)
                        + ",bodyLen=" + chunkBody.length()
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                if(chunk != null && chunk.code >= 200 && chunk.code < 400
                        && chunkBody.length() > 0) {
                    merged.append('\n').append(chunkBody);
                    fetched++;
                }
            } catch(Exception e) {
                Log.d(TAG, "ntk_viewer_episode_chunk_error stage=" + safeStage
                        + ",viewerPath=" + path
                        + ",chunkPath=" + chunkPath
                        + "," + e);
            }
        }
        if(fetched == 0 && aliasPayload.length() == 0)
            return body;
        String mergedBody = merged.toString();
        String normalizedMerged = mergeSafeNormalize(mergedBody);
        boolean hasToken = ntkViewerImagesToken(normalizedMerged).length() > 0;
        int pageCount = ntkViewerMetaPageCount(normalizedMerged);
        Log.d(TAG, "ntk_viewer_episode_chunk_merged stage=" + safeStage
                + ",viewerPath=" + path
                + ",chunks=" + fetched
                + ",bodyLen=" + body.length()
                + ",mergedLen=" + mergedBody.length()
                + ",hasToken=" + hasToken
                + ",pageCount=" + pageCount);
        if(isNtkSyntheticWebtoonEpisodePath(path) && !hasToken)
            Log.d(TAG, "ntk_viewer_episode_chunk_no_manifest path=" + path
                    + ",stage=" + safeStage
                    + ",reason=missing_image_props");
        return mergedBody;
    }

    private String ntkViewerNumericAliasPayload(CustomHttpClient client, String body,
                                                String path, String stage) {
        if(client == null || body == null || path == null || !isNtkSyntheticWebtoonEpisodePath(path))
            return "";
        if(ntkViewerImagesToken(mergeSafeNormalize(body)).length() > 0)
            return "";
        String workId = ntkApiEpisodeIdForPath(getNtkImageWorkId());
        String episodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        if(!isNumericNtkId(workId)) {
            Matcher matcher = Pattern.compile("^/webtoon/(\\d+)/[^/?#]+").matcher(path);
            if(matcher.find())
                workId = matcher.group(1);
        }
        if(!isNumericNtkId(workId) || !isNumericNtkId(episodeId))
            return "";
        String aliasPath = "/webtoon/" + workId + "/" + episodeId;
        long startedAt = System.currentTimeMillis();
        try {
            CustomHttpClient.PageResponse alias = client.mgetCachedPage(aliasPath, PAGE_CACHE_TTL_MS);
            String aliasBody = alias == null || alias.body == null ? "" : alias.body;
            String normalized = mergeSafeNormalize(aliasBody);
            boolean hasToken = ntkViewerImagesToken(normalized).length() > 0;
            int pageCount = ntkViewerMetaPageCount(normalized);
            Log.d(TAG, "ntk_viewer_numeric_alias_payload stage="
                    + (stage == null || stage.length() == 0 ? "unknown" : stage)
                    + ",viewerPath=" + path
                    + ",aliasPath=" + aliasPath
                    + ",code=" + (alias == null ? 0 : alias.code)
                    + ",fromCache=" + (alias != null && alias.fromCache)
                    + ",bodyLen=" + aliasBody.length()
                    + ",hasToken=" + hasToken
                    + ",pageCount=" + pageCount
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            if(alias != null && alias.code >= 200 && alias.code < 400
                    && aliasBody.length() > 0 && (hasToken || pageCount > 0))
                return aliasBody;
        } catch(Exception e) {
            Log.d(TAG, "ntk_viewer_numeric_alias_payload_error stage="
                    + (stage == null || stage.length() == 0 ? "unknown" : stage)
                    + ",viewerPath=" + path
                    + ",aliasPath=" + aliasPath
                    + "," + e);
        }
        return "";
    }

    private static String mergeSafeNormalize(String body) {
        return normalizeNtkViewerPayloadText(body == null ? "" : body);
    }

    private static List<String> ntkViewerEpisodeNextChunkPaths(String html, String viewerPath) {
        ArrayList<String> chunks = new ArrayList<>();
        if(html == null || html.length() == 0 || viewerPath == null || viewerPath.length() == 0)
            return chunks;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/").matcher(viewerPath);
        if(!pathMatcher.find())
            return chunks;
        String segment = Pattern.quote(pathMatcher.group(1));
        Pattern pattern = Pattern.compile("(?i)((?:https?://[^\"'<>\\s]+)?"
                + "/[^\"'<>\\s]*/_next/static/chunks/app/" + segment
                + "/(?:%5BsourceWorkId%5D|\\[sourceWorkId\\])"
                + "/(?:%5BepisodeId%5D|\\[episodeId\\])"
                + "/page-[^\"'<>\\s]+?\\.js(?:\\?[^\"'<>\\s]*)?)");
        String searchable = normalizeNtkEmbeddedImageText(html);
        Matcher matcher = pattern.matcher(searchable);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while(matcher.find()) {
            String path = matcher.group(1);
            if(path == null || path.length() == 0)
                continue;
            path = normalizeNtkEmbeddedImageText(path).replace("&amp;", "&");
            if(path.startsWith("/http://") || path.startsWith("/https://"))
                path = path.substring(1);
            if(path.length() > 0 && path.charAt(0) != '/')
                path = "/" + path;
            if(path.length() > 0 && seen.add(path))
                chunks.add(path);
        }
        return chunks;
    }

    private boolean startAsyncNtkViewerImageApiFetchFromToken(CustomHttpClient client, String path,
                                                              String partialBody, String stage) {
        String normalized = normalizeNtkViewerPayloadText(partialBody);
        String token = ntkViewerImagesToken(normalized);
        if(client == null || path == null || partialBody == null || token.length() == 0)
            return false;
        String prefetchStage = stage == null || stage.length() == 0 ? "partial" : stage;
        Log.d(TAG, "ntk_viewer_api_prefetch_token_schedule stage=" + prefetchStage
                + ",path=" + path
                + ",bodyLen=" + partialBody.length()
                + "," + ntkViewerPayloadMarkerSummary(normalized, token));
        publishKpSignedTokenDirectUrls(
                client,
                path,
                normalized,
                token,
                "token-prefetch-" + prefetchStage);
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                prefetchNtkViewerImageApiTokenCandidate(client, normalized, token, path);
                Log.d(TAG, "ntk_viewer_api_prefetch_token_done stage=" + prefetchStage
                        + ",path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_api_prefetch_token_error stage=" + prefetchStage
                        + ",path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-viewer-image-api-token-prefetch");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void prefetchNtkViewerImageApiTokenCandidate(CustomHttpClient client, String body, String path) {
        String normalized = normalizeNtkViewerPayloadText(body);
        String token = ntkViewerImagesToken(normalized);
        prefetchNtkViewerImageApiTokenCandidate(client, normalized, token, path);
    }

    private void prefetchNtkViewerImageApiTokenCandidate(CustomHttpClient client, String normalized,
                                                         String token, String path) {
        long resolveStartedAt = System.currentTimeMillis();
        if(normalized == null)
            normalized = "";
        final String safePath = path == null ? "" : path;
        if(token == null || token.length() == 0) {
            Log.d(TAG, "ntk_viewer_api_prefetch_token_skip reason=missing_token,path=" + safePath);
            return;
        }
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(safePath);
        if(!pathMatcher.find()) {
            Log.d(TAG, "ntk_viewer_api_prefetch_token_skip reason=bad_path,path=" + safePath);
            return;
        }
        String segment = pathMatcher.group(1);
        String pathEpisodeId = pathMatcher.group(3);
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        boolean syntheticTokenEpisode = isNtkSyntheticWebtoonEpisodePath(safePath)
                && tokenEpisodeId.length() > 0;
        String embeddedEpisodeId = syntheticTokenEpisode
                ? ""
                : ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String workId = ntkViewerApiWorkIdForPath(safePath, tokenWorkId);
        String imageWorkId = ntkApiEpisodeIdForPath(getNtkImageWorkId());
        String imageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        if(isNtkSyntheticWebtoonEpisodePath(safePath)
                && "webtoon".equals(segment)
                && isNumericNtkId(imageWorkId)
                && !imageWorkId.equals(workId)
                && !isNumericNtkId(tokenWorkId)
                && !pathMatcher.group(2).matches("\\d{1,12}")) {
            Log.d(TAG, "ntk_viewer_api_prefetch_token_slug_numeric_work path=" + safePath
                    + ",apiWorkId=" + workId
                    + ",imageWorkId=" + imageWorkId
                    + ",episodeId=" + tokenEpisodeId);
            workId = imageWorkId;
        }
        String episodeId;
        if(syntheticTokenEpisode) {
            episodeId = isNumericNtkId(imageEpisodeId) ? imageEpisodeId : tokenEpisodeId;
            if(isNumericNtkId(imageEpisodeId) && !imageEpisodeId.equals(tokenEpisodeId)) {
                Log.d(TAG, "ntk_viewer_api_prefetch_token_slug_numeric_episode path=" + safePath
                        + ",tokenEpisodeId=" + tokenEpisodeId
                        + ",imageEpisodeId=" + imageEpisodeId);
            }
        } else {
            episodeId = ntkPreferredViewerImagesApiEpisodeId(tokenEpisodeId, embeddedEpisodeId, pathEpisodeId);
        }
        if(isNtkWebtoonEpisodePath(safePath) && isNumericNtkGeneratedEpisodePath(safePath)) {
            if(workId.length() == 0)
                workId = pathMatcher.group(2);
            if(episodeId.length() == 0)
                episodeId = pathEpisodeId;
        }
        if(workId.length() == 0 || episodeId.length() == 0) {
            Log.d(TAG, "ntk_viewer_api_prefetch_token_skip reason=missing_ids,path=" + safePath
                    + ",segment=" + segment
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId
                    + ",tokenWorkId=" + tokenWorkId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",embeddedEpisodeId=" + embeddedEpisodeId);
            return;
        }
        boolean kpTokenizedSlug = isNtkKpWebtoonEpisodePath(safePath);
        boolean foregroundNativeViewer = MainApplication.isNtkForegroundViewerPath(safePath);
        String tokenFetchKey = safePath + "|viewer-api-token-prefetch|" + workId + "|" + episodeId
                + (foregroundNativeViewer ? "|foreground|" : "|background|")
                + token.substring(0, Math.min(24, token.length()));
        long nowMs = System.currentTimeMillis();
        Long previousStartedAt = NTK_VIEWER_API_TOKEN_PREFETCH_FLIGHTS.putIfAbsent(tokenFetchKey, nowMs);
        if(previousStartedAt != null) {
            long ageMs = nowMs - previousStartedAt;
            boolean allowStaleRetry = false;
            if(allowStaleRetry
                    && NTK_VIEWER_API_TOKEN_PREFETCH_FLIGHTS.replace(
                    tokenFetchKey, previousStartedAt, nowMs)) {
                Log.d(TAG, "ntk_viewer_api_prefetch_token_retry_stale path=" + safePath
                        + ",ageMs=" + ageMs
                        + ",segment=" + segment
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",tokenWorkId=" + tokenWorkId
                        + ",tokenEpisodeId=" + tokenEpisodeId);
            } else {
                Log.d(TAG, "ntk_viewer_api_prefetch_token_skip reason=already_started,path=" + safePath
                    + ",segment=" + segment
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId
                    + ",tokenWorkId=" + tokenWorkId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",ageMs=" + ageMs);
                return;
            }
        }
        if(kpTokenizedSlug && !client.hasNtkViewerImagesAckReadyForPath(safePath)) {
            Log.d(TAG, "ntk_viewer_api_prefetch_token_kp_ack_gate_skip path=" + safePath
                    + ",reason=api_race_force_acks");
        }
        if(kpTokenizedSlug)
            Log.d(TAG, "ntk_viewer_api_prefetch_token_kp_key_gate_skip path=" + safePath
                    + ",reason=api_race_signs_or_retries");
        Log.d(TAG, "ntk_viewer_api_prefetch_token_fetch path=" + safePath
                + ",segment=" + segment
                + ",workId=" + workId
                + ",episodeId=" + episodeId
                + ",tokenWorkId=" + tokenWorkId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",foreground=" + foregroundNativeViewer
                    + ",resolveMs=" + (System.currentTimeMillis() - resolveStartedAt));
        publishKpSignedTokenDirectUrls(client, safePath, normalized, token, "viewer-api-token-prefetch");
        final String quarantineSegment = segment;
        final String quarantineWorkId = workId;
        final String quarantineEpisodeId = episodeId;
        final String quarantineToken = token;
        try {
            List<String> urls = client.fetchNtkViewerImageUrls(segment, workId, episodeId,
                    token, normalized, safePath, safePath, trustedUrls -> {
                        if(trustedUrls != null && !trustedUrls.isEmpty()) {
                            int expectedCount = ReaderImageCache.trustedNtkImageApiCount(
                                    safePath, android.os.SystemClock.elapsedRealtime() - 30_000L);
                            boolean authoritative = (expectedCount > 0
                                    && trustedUrls.size() >= expectedCount)
                                    || (expectedCount <= 0 && trustedUrls.size() > 1);
                            if(authoritative) {
                                setNtkImageCount(expectedCount > 0 ? expectedCount : trustedUrls.size());
                                primeNtkStripSourceSpool(
                                        client,
                                        trustedUrls,
                                        quarantineSegment,
                                        quarantineWorkId,
                                        quarantineEpisodeId,
                                        quarantineToken
                                );
                                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                                        safePath,
                                        trustedUrls,
                                        "viewer-api-token-callback");
                                NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                                        safePath,
                                        trustedUrls,
                                        "viewer-api-token-callback");
                            }
                        }
                        startFirstNtkApiImageStream(client, safePath, trustedUrls);
                    });
            if(urls != null && !urls.isEmpty()) {
                int expectedCount = ReaderImageCache.trustedNtkImageApiCount(
                        safePath, android.os.SystemClock.elapsedRealtime() - 30_000L);
                boolean authoritative = (expectedCount > 0 && urls.size() >= expectedCount)
                        || (expectedCount <= 0 && urls.size() > 1);
                if(authoritative) {
                    setNtkImageCount(expectedCount > 0 ? expectedCount : urls.size());
                    primeNtkStripSourceSpool(
                            client,
                            urls,
                            quarantineSegment,
                            quarantineWorkId,
                            quarantineEpisodeId,
                            quarantineToken
                    );
                    ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                            safePath,
                            urls,
                            "viewer-api-token-result");
                    NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                            safePath,
                            urls,
                            "viewer-api-token-result");
                }
            }
            if(urls == null || urls.isEmpty())
                NTK_VIEWER_API_TOKEN_PREFETCH_FLIGHTS.remove(tokenFetchKey);
            startFirstNtkApiImageStream(client, safePath, urls);
        } catch(RuntimeException e) {
            NTK_VIEWER_API_TOKEN_PREFETCH_FLIGHTS.remove(tokenFetchKey);
            throw e;
        }
    }

    /** Installs an immutable, generation-bound quarantine candidate; exact authority is separate. */
    private void primeNtkStripSourceSpool(CustomHttpClient client,
                                          List<String> authoritativeUrls,
                                          String segment,
                                          String workId,
                                          String episodeId,
                                          String imagesToken) {
        if(client == null || authoritativeUrls == null || authoritativeUrls.isEmpty())
            return;
        Context context = client.getContext();
        NtkDiscoveryLease lease = NtkSourceSpoolRegistry.currentDiscoveryLease(getNtkEpisodePath());
        if(context == null || lease == null)
            return;
        try {
            String endpoint = "webtoon".equalsIgnoreCase(segment)
                    ? "/api/webtoon-images"
                    : "/api/manhwa-images";
            ml.melun.mangaview.reader.NtkViewerImageRequestIdentity identity =
                    ml.melun.mangaview.reader.NtkViewerImageRequestIdentity.create(
                            segment,
                            endpoint,
                            workId,
                            episodeId,
                            imagesToken
                    );
            StringBuilder responseWitness = new StringBuilder("ntk-eof-assets-v1\n");
            for(String asset : authoritativeUrls)
                responseWitness.append(asset == null ? "" : asset.trim()).append('\n');
            ml.melun.mangaview.reader.NtkQuarantineAssetEvidence evidence =
                    ml.melun.mangaview.reader.NtkQuarantineAssetEvidence.create(
                            lease.getEpisodePath(),
                            lease.generationValue(),
                            identity.getIdentityDigestSha256(),
                            authoritativeUrls,
                            responseWitness.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    );
            NtkSourceSpoolRegistry.observeQuarantineAssetEvidence(lease, evidence);
        } catch(RuntimeException evidenceFailure) {
            Log.d(TAG, "ntk_quarantine_asset_evidence_rejected path="
                    + getNtkEpisodePath() + "," + evidenceFailure);
        }
    }

    private void publishKpSignedTokenDirectUrls(CustomHttpClient client,
                                                String path,
                                                String normalizedPayload,
                                                String token,
                                                String source) {
        if(client == null || path == null || token == null || token.length() == 0)
            return;
        if(!isNtkKpWebtoonEpisodePath(path))
            return;
        boolean canonicalSource = isCanonicalKpSignedTokenDirectSource(source);
        List<String> payloadDirectUrls = ntkViewerPayloadImageUrls(normalizedPayload, path);
        if(payloadDirectUrls != null && payloadDirectUrls.size() >= 4) {
            ArrayList<String> directUrls = new ArrayList<>(payloadDirectUrls);
            if(!canonicalSource) {
                Log.d(TAG, "ntk_kp_payload_direct_publish_skip_provisional path=" + path
                        + ",count=" + directUrls.size()
                        + ",source=" + source
                        + ",first=" + safeLogImage(directUrls.get(0)));
                return;
            }
            ReaderImageCache.rememberTrustedNtkImageApiCount(path, directUrls.size());
            ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                    path,
                    directUrls,
                    "kp-payload-direct-" + source);
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, directUrls);
            NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                    path,
                    directUrls,
                    "kp-payload-direct-" + source);
            startFirstNtkApiImageStream(client, path, directUrls, false);
            Log.d(TAG, "ntk_kp_payload_direct_publish path=" + path
                    + ",count=" + directUrls.size()
                    + ",source=" + source
                    + ",first=" + safeLogImage(directUrls.get(0)));
            return;
        }
        int pageCount = ntkViewerPayloadImageCountFast(normalizedPayload);
        pageCount = Math.max(pageCount, ntkViewerMetaPageCount(normalizedPayload));
        if(pageCount < 4) {
            Log.d(TAG, "ntk_kp_signed_token_direct_publish_skip path=" + path
                    + ",reason=partial_count"
                    + ",count=" + pageCount
                    + ",source=" + source);
            return;
        }
        int safeCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        String base = client.getUrl(path);
        if(base == null || base.length() == 0)
            return;
        while(base.endsWith("/"))
            base = base.substring(0, base.length() - 1);
        List<String> urls = new ArrayList<>(safeCount);
        try {
            String encodedToken = URLEncoder.encode(token, "UTF-8");
            for(int index = 0; index < safeCount; index++)
                urls.add(base + "/api/m/i?a=" + encodedToken + "&i=" + index);
        } catch(Exception e) {
            Log.d(TAG, "ntk_kp_signed_token_direct_publish_error path=" + path
                    + ",source=" + source
                    + ",count=" + safeCount
                    + "," + e);
            return;
        }
        if(canonicalSource) {
            ReaderImageCache.rememberEarlyNtkImageUrls(path, urls);
            Log.d(TAG, "ntk_kp_signed_token_direct_hold_protected_manifest path=" + path
                    + ",count=" + safeCount
                    + ",source=" + source
                    + ",tokenLen=" + token.length());
        } else {
            Log.d(TAG, "ntk_kp_signed_token_direct_skip_provisional_manifest path=" + path
                    + ",count=" + safeCount
                    + ",source=" + source
                    + ",tokenLen=" + token.length());
        }
        String apiAckPath = ntkKpViewerApiAckPath(path);
        startKpSignedTokenNativeAck(client, apiAckPath, source);
    }

    private static boolean isCanonicalKpSignedTokenDirectSource(String source) {
        if(source == null)
            return false;
        String lower = source.toLowerCase(Locale.ROOT);
        if(lower.contains("sourcework-direct"))
            return false;
        return lower.contains("viewer-api-token")
                || lower.contains("token-prefetch")
                || lower.contains("native-api")
                || lower.contains("viewer-api-token-result");
    }

    private static String ntkKpViewerApiAckPath(String path) {
        if(path == null)
            return "";
        Matcher matcher = Pattern.compile("^(/webtoon/\\d{1,12})/kp-[^/?#]+(?:[/?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(path.trim());
        if(matcher.find())
            return matcher.group(1);
        return path;
    }

    private void startKpSignedTokenNativeAck(CustomHttpClient client, String path, String source) {
        if(client == null || path == null || path.length() == 0)
            return;
        if(!isNtkKpWebtoonEpisodePath(path) && !path.matches("(?i)^/webtoon/\\d{1,12}(?:[/?#].*)?$"))
            return;
        long now = System.currentTimeMillis();
        Long previous = NTK_KP_SIGNED_TOKEN_ACK_FLIGHTS.putIfAbsent(path, now);
        if(previous != null && now - previous < 4_000L)
            return;
        if(previous != null)
            NTK_KP_SIGNED_TOKEN_ACK_FLIGHTS.put(path, now);
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            boolean prepared = false;
            boolean direct = false;
            boolean proof = false;
            try {
                prepared = client.performPreparedNtkNativeAck(path);
                proof = client.hasRecentStrictNtkAdAckProof(path);
                if(!prepared && !proof) {
                    String baseUrl = client.getUrl(path);
                    direct = client.performNtkNativeAckBypassIgnoringWebViewInFlight(baseUrl, path, path);
                    proof = client.hasRecentStrictNtkAdAckProof(path);
                }
                Log.d(TAG, "ntk_kp_signed_token_native_ack_done path=" + path
                        + ",source=" + source
                        + ",prepared=" + prepared
                        + ",direct=" + direct
                        + ",proof=" + proof
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                Log.d(TAG, "ntk_kp_signed_token_native_ack_error path=" + path
                        + ",source=" + source
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-kp-signed-token-ack");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        Log.d(TAG, "ntk_kp_signed_token_native_ack_start path=" + path
                + ",source=" + source);
    }

    private static int ntkViewerPayloadImageCountFast(String payload) {
        if(payload == null || payload.length() == 0)
            return 0;
        String normalized = payload
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/");
        int count = Math.max(
                Math.max(regexCount(normalized, "\"page\"\\s*:"), regexCount(normalized, "\\\\\"page\\\\\"\\s*:")),
                Math.max(regexCount(normalized, "\"src\"\\s*:\\s*\""), regexCount(normalized, "\\\\\"src\\\\\"\\s*:\\s*\\\\\"")));
        count = Math.max(count, ntkViewerImageMetasCountFast(normalized));
        count = Math.max(count, ntkViewerExplicitImageCountFast(normalized));
        return Math.max(0, Math.min(count, NTK_MAX_GENERATED_PAGE_COUNT));
    }

    private static int ntkViewerExplicitImageCountFast(String text) {
        if(text == null || text.length() == 0)
            return 0;
        Pattern pattern = Pattern.compile(
                "\"(?:imageCount|imagesCount|totalImages|totalImageCount|pageCount|totalPages|numberOfPages)\"\\s*:\\s*(\\d{1,4})",
                Pattern.CASE_INSENSITIVE);
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while(matcher.find()) {
            try {
                count = Math.max(count, Integer.parseInt(matcher.group(1)));
            } catch(Exception ignored) {
            }
        }
        pattern = Pattern.compile(
                "\\\\\"(?:imageCount|imagesCount|totalImages|totalImageCount|pageCount|totalPages|numberOfPages)\\\\\"\\s*:\\s*(\\d{1,4})",
                Pattern.CASE_INSENSITIVE);
        matcher = pattern.matcher(text);
        while(matcher.find()) {
            try {
                count = Math.max(count, Integer.parseInt(matcher.group(1)));
            } catch(Exception ignored) {
            }
        }
        return count;
    }

    private static int regexCount(String text, String pattern) {
        if(text == null || text.length() == 0)
            return 0;
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        int count = 0;
        while(matcher.find())
            count++;
        return count;
    }

    private static int ntkViewerImageMetasCountFast(String text) {
        if(text == null || text.length() == 0)
            return 0;
        String lower = text.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("imagemetas");
        if(marker < 0)
            return 0;
        int start = text.indexOf('[', marker);
        if(start < 0)
            return 0;
        int depth = 0;
        int count = 0;
        boolean inString = false;
        boolean escaped = false;
        for(int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if(escaped) {
                escaped = false;
                continue;
            }
            if(ch == '\\') {
                escaped = true;
                continue;
            }
            if(ch == '"') {
                inString = !inString;
                continue;
            }
            if(inString)
                continue;
            if(ch == '[') {
                depth++;
            } else if(ch == ']') {
                depth--;
                if(depth <= 0)
                    return count;
            } else if(ch == '{' && depth == 1) {
                count++;
            }
        }
        return count;
    }

    private void prefetchNtkViewerImageApiCandidates(CustomHttpClient client, String body, String path) {
        String normalized = normalizeNtkViewerPayloadText(body);
        String token = ntkViewerImagesToken(normalized);
        if(token == null || token.length() == 0)
            return;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return;
        String segment = pathMatcher.group(1);
        String pathEpisodeId = pathMatcher.group(3);
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        List<String> naverWebtoonUrls = fetchNaverWebtoonImageUrlsForNvEpisode(
                client, tokenEpisodeId, pathEpisodeId, 0);
        if(!naverWebtoonUrls.isEmpty()) {
            Log.d(TAG, "ntk_viewer_api_prefetch_naver_original path=" + path
                    + ",count=" + naverWebtoonUrls.size()
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",first=" + safeLogImage(naverWebtoonUrls.get(0)));
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, naverWebtoonUrls);
            startFirstNtkApiImageStream(client, path, naverWebtoonUrls);
            return;
        }
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        String imageEpisodeId = ntkViewerApiImageEpisodeId(tokenEpisodeId, getNtkImageEpisodeId(),
                pathEpisodeId, embeddedEpisodeId);
        String knownImageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        if(knownImageEpisodeId.length() == 0)
            knownImageEpisodeId = ntkApiEpisodeIdForPath(embeddedEpisodeId);
        String apiEpisodeId = ntkPreferredViewerImagesApiEpisodeId(tokenEpisodeId, imageEpisodeId,
                pathEpisodeId);
        if(isNtkSyntheticWebtoonEpisodePath(path) && tokenEpisodeId.length() > 0)
            apiEpisodeId = tokenEpisodeId;
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String workId = ntkViewerApiWorkIdForPath(path, tokenWorkId);
        String imageWorkId = ntkApiEpisodeIdForPath(getNtkImageWorkId());
        if(isNtkSyntheticWebtoonEpisodePath(path)
                && "webtoon".equals(segment)
                && isNumericNtkId(imageWorkId)
                && !imageWorkId.equals(workId)) {
            Log.d(TAG, "ntk_viewer_api_prefetch_slug_numeric_work path=" + path
                    + ",apiWorkId=" + workId
                    + ",imageWorkId=" + imageWorkId
                    + ",apiEpisodeId=" + apiEpisodeId
                    + ",imageEpisodeId=" + imageEpisodeId);
            workId = imageWorkId;
        }
        String canonicalWorkId = ntkViewerCanonicalWorkIdForImageApi(normalized, path, titleId, workId);
        if(canonicalWorkId.length() > 0 && imageEpisodeId.length() > 0 && apiEpisodeId.equals(imageEpisodeId)) {
            String canonicalAckPath = "/" + segment + "/" + canonicalWorkId + "/" + imageEpisodeId;
            List<String> urls = client.fetchNtkViewerImageUrls(segment, canonicalWorkId, apiEpisodeId,
                    token, normalized, path, canonicalAckPath, trustedUrls ->
                            startFirstNtkApiImageStream(client, path, trustedUrls));
            startFirstNtkApiImageStream(client, path, urls);
            return;
        }
        String ackPath = path;
        if(apiEpisodeId.equals(imageEpisodeId)
                && pathEpisodeId.matches("\\d+")
                && imageEpisodeId.length() > 0 && !imageEpisodeId.equals(pathEpisodeId)
                && workId.matches("\\d+"))
            ackPath = "/" + segment + "/" + workId + "/" + imageEpisodeId;
        List<String> urls = client.fetchNtkViewerImageUrls(segment, workId, apiEpisodeId,
                token, normalized, path, ackPath, trustedUrls ->
                        startFirstNtkApiImageStream(client, path, trustedUrls));
        startFirstNtkApiImageStream(client, path, urls);
        if(urls.isEmpty()
                && knownImageEpisodeId.length() > 0
                && !knownImageEpisodeId.equals(apiEpisodeId)
                && shouldRetryNtkKnownImageEpisodeId(tokenEpisodeId, pathEpisodeId,
                apiEpisodeId, knownImageEpisodeId, getNtkImageCount())
                && workId.matches("\\d+")) {
            Log.d(TAG, "ntk_viewer_api_prefetch_known_image_episode_retry path=" + path
                    + ",apiEpisodeId=" + apiEpisodeId
                    + ",knownImageEpisodeId=" + knownImageEpisodeId);
            String knownAckPath = "/" + segment + "/" + workId + "/" + knownImageEpisodeId;
            urls = client.fetchNtkViewerImageUrls(segment, workId, knownImageEpisodeId,
                    token, normalized, knownAckPath, knownAckPath, trustedUrls ->
                            startFirstNtkApiImageStream(client, path, trustedUrls));
            startFirstNtkApiImageStream(client, path, urls);
        }
    }

    private static boolean isExpectedFetchCancellation(Throwable t) {
        while(t != null) {
            if(t instanceof InterruptedException || t instanceof InterruptedIOException)
                return true;
            String message = t.getMessage();
            if("Canceled".equals(message) || "cancelled".equalsIgnoreCase(message))
                return true;
            Throwable cause = t.getCause();
            if(cause == t)
                return false;
            t = cause;
        }
        return Thread.currentThread().isInterrupted();
    }

    private static boolean isCancelledRequestGroup(CustomHttpClient.RequestGroup requestGroup) {
        return requestGroup != null && requestGroup.isCancelled();
    }

    private static boolean isNtkPageFetchRequestFailed(Throwable t, String path) {
        if(path == null || path.length() == 0)
            return false;
        String expected = "Request failed: " + path;
        while(t != null) {
            if(expected.equals(t.getMessage()))
                return true;
            Throwable cause = t.getCause();
            if(cause == t)
                return false;
            t = cause;
        }
        return false;
    }

    private CustomHttpClient.PageResponse awaitAsyncNtkPageFetch(AsyncNtkPageFetch fetch,
                                                                 CustomHttpClient client,
                                                                 String path) throws Exception {
        if(fetch != null) {
            try {
                long deadline = System.currentTimeMillis() + 14_000L;
                while(fetch.done.getCount() > 0 && System.currentTimeMillis() < deadline) {
                    if(hasCompleteAuthoritativeNtkManifest(path)) {
                        cancelAsyncNtkPageFetch(fetch);
                        return null;
                    }
                    fetch.done.await(Math.min(25L,
                            Math.max(1L, deadline - System.currentTimeMillis())), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            if(hasCompleteAuthoritativeNtkManifest(path)) {
                cancelAsyncNtkPageFetch(fetch);
                return null;
            }
            if(fetch.page != null)
                return fetch.page;
            if(fetch.error != null)
                throw fetch.error;
        }
        return client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
    }

    private CustomHttpClient.PageResponse awaitBestNtkApiPageFetch(AsyncNtkPageFetch directFetch,
                                                                    AsyncNtkPageFetch fallbackFetch,
                                                                    CustomHttpClient client,
                                                                    String path) throws Exception {
        if(hasCompleteAuthoritativeNtkManifest(path)) {
            cancelAsyncNtkPageFetch(directFetch);
            cancelAsyncNtkPageFetch(fallbackFetch);
            return null;
        }
        long deadline = System.currentTimeMillis() + ntkBestPageFetchWaitMs(client, path, fallbackFetch);
        CustomHttpClient.PageResponse direct = null;
        CustomHttpClient.PageResponse fallback = null;
        boolean syntheticWebtoonPath = isNtkSyntheticWebtoonEpisodePath(path);
        while(System.currentTimeMillis() < deadline) {
            if(hasCompleteAuthoritativeNtkManifest(path)) {
                cancelAsyncNtkPageFetch(directFetch);
                cancelAsyncNtkPageFetch(fallbackFetch);
                Log.d(TAG, "ntk_page_fetch_race_stop_authoritative_manifest path=" + path);
                return null;
            }
            if(direct == null)
                direct = completedNtkPageFetch(directFetch, false);
            if(isNtkViewerUnavailableEpisode(direct == null ? null : direct.body)) {
                cancelAsyncNtkPageFetch(fallbackFetch);
                return direct;
            }
            if(isUsableNtkFastPage(direct, path)) {
                if(syntheticWebtoonPath && !isUsableNtkSyntheticWebtoonDirectPage(direct, path)
                        && fallbackFetch != null && fallbackFetch.done.getCount() > 0) {
                    // Synthetic webtoon slugs can expose only the API token first; the shared
                    // WebView page can carry direct image text a few hundred milliseconds later.
                } else if(shouldWaitForNtkHtmlImageFallback(direct, path, fallbackFetch)) {
                    // Some NTK webtoon RSC responses expose only the image API token while the
                    // parallel full page already carries direct page images.
                } else {
                    cancelAsyncNtkPageFetch(fallbackFetch);
                    logNtkViewerParse("api-direct-page", direct, path, 0, 0);
                    return direct;
                }
            }
            if(fallback == null)
                fallback = completedNtkPageFetch(fallbackFetch, true);
            if(shouldPreferNtkHtmlImagePage(direct, fallback, path)) {
                cancelAsyncNtkPageFetch(directFetch);
                return fallback;
            }
            if(syntheticWebtoonPath && isUsableNtkSyntheticWebtoonDirectPage(fallback, path)) {
                cancelAsyncNtkPageFetch(directFetch);
                return fallback;
            }
            if(isUsableNtkFastPage(fallback, path)) {
                cancelAsyncNtkPageFetch(directFetch);
                return fallback;
            }
            if((directFetch == null || directFetch.done.getCount() == 0)
                    && (fallbackFetch == null || fallbackFetch.done.getCount() == 0))
                break;
            try {
                Thread.sleep(NTK_PAGE_FETCH_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        if(direct == null)
            direct = completedNtkPageFetch(directFetch, false);
        if(fallback == null)
            fallback = completedNtkPageFetch(fallbackFetch, true);
        if(isNtkViewerUnavailableEpisode(direct == null ? null : direct.body)) {
            cancelAsyncNtkPageFetch(fallbackFetch);
            return direct;
        }
        if(syntheticWebtoonPath && isUsableNtkSyntheticWebtoonDirectPage(fallback, path)) {
            cancelAsyncNtkPageFetch(directFetch);
            return fallback;
        }
        if(shouldPreferNtkHtmlImagePage(direct, fallback, path)) {
            cancelAsyncNtkPageFetch(directFetch);
            return fallback;
        }
        if(isUsableNtkFastPage(direct, path)) {
            cancelAsyncNtkPageFetch(fallbackFetch);
            logNtkViewerParse("api-direct-page", direct, path, 0, 0);
            return direct;
        }
        if(isUsableNtkFastPage(fallback, path)) {
            cancelAsyncNtkPageFetch(directFetch);
            return fallback;
        }
        return fallback != null ? fallback
                : direct != null ? direct
                : client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
    }

    private static long ntkBestPageFetchWaitMs(CustomHttpClient client, String path,
                                               AsyncNtkPageFetch fallbackFetch) {
        if(client == null || fallbackFetch == null)
            return 14_000L;
        String root = "";
        try {
            root = String.valueOf(client.getUrl(path)).toLowerCase(Locale.ROOT);
        } catch(Exception ignored) {
        }
        if(root.contains("sbxh") || root.contains("toonflix"))
            return 30_000L;
        return 14_000L;
    }

    private CustomHttpClient.PageResponse awaitFastNtkApiPageFetch(AsyncNtkPageFetch directFetch,
                                                                   AsyncNtkPageFetch fallbackFetch,
                                                                   String path,
                                                                   long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        CustomHttpClient.PageResponse direct = null;
        CustomHttpClient.PageResponse fallback = null;
        while(System.currentTimeMillis() < deadline) {
            if(direct == null)
                direct = completedNtkPageFetch(directFetch, false);
            if(isUsableNtkFastPage(direct, path)) {
                if(!shouldWaitForNtkHtmlImageFallback(direct, path, fallbackFetch)) {
                    cancelAsyncNtkPageFetch(fallbackFetch);
                    return direct;
                }
            }
            if(fallback == null)
                fallback = completedNtkPageFetch(fallbackFetch, false);
            if(shouldPreferNtkHtmlImagePage(direct, fallback, path)) {
                cancelAsyncNtkPageFetch(directFetch);
                return fallback;
            }
            if(isUsableNtkFastPage(fallback, path)) {
                cancelAsyncNtkPageFetch(directFetch);
                return fallback;
            }
            if((directFetch == null || directFetch.done.getCount() == 0)
                    && (fallbackFetch == null || fallbackFetch.done.getCount() == 0))
                break;
            try {
                Thread.sleep(NTK_PAGE_FETCH_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        if(direct == null)
            direct = completedNtkPageFetch(directFetch, false);
        if(fallback == null)
            fallback = completedNtkPageFetch(fallbackFetch, false);
        if(shouldPreferNtkHtmlImagePage(direct, fallback, path)) {
            cancelAsyncNtkPageFetch(directFetch);
            return fallback;
        }
        if(isUsableNtkFastPage(direct, path)) {
            cancelAsyncNtkPageFetch(fallbackFetch);
            return direct;
        }
        if(isUsableNtkFastPage(fallback, path)) {
            cancelAsyncNtkPageFetch(directFetch);
            return fallback;
        }
        return null;
    }

    private CustomHttpClient.PageResponse completedNtkPageFetch(AsyncNtkPageFetch fetch,
                                                                 boolean throwOnError) throws Exception {
        if(fetch == null || fetch.done.getCount() > 0)
            return null;
        if(fetch.page != null)
            return fetch.page;
        if(fetch.error != null && throwOnError)
            throw fetch.error;
        return null;
    }

    private void cancelAsyncNtkPageFetch(AsyncNtkPageFetch fetch) {
        if(fetch == null)
            return;
        fetch.cancel();
    }

    private CustomHttpClient.PageResponse awaitAsyncNtkPageFetch(AsyncNtkPageFetch fetch,
                                                                 CustomHttpClient client,
                                                                 String path,
                                                                 long timeoutMs,
                                                                 boolean throwOnError) throws Exception {
        if(fetch != null) {
            try {
                boolean done = fetch.done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
                if(done) {
                    if(fetch.page != null)
                        return fetch.page;
                    if(fetch.error != null && throwOnError)
                        throw fetch.error;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        return client == null ? null : client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
    }

    private static boolean isUsableNtkApiPage(CustomHttpClient.PageResponse page) {
        return page != null && page.code >= 200 && page.code < 400
                && hasNtkViewerImageApiPayload(page.body);
    }

    private static boolean isUsableNtkFastPage(CustomHttpClient.PageResponse page, String path) {
        return isUsableNtkApiPage(page) || isUsableNtkSyntheticWebtoonDirectPage(page, path);
    }

    private static boolean isBlockedNtkDirectPage(CustomHttpClient client, CustomHttpClient.PageResponse page) {
        if(page == null)
            return true;
        return page.code == 403 || (client != null && client.isCloudflareChallengeResponse(page.code, page.body));
    }

    private static boolean shouldWaitForNtkHtmlImageFallback(CustomHttpClient.PageResponse direct,
                                                             String path,
                                                             AsyncNtkPageFetch fallbackFetch) {
        if(isNtkNativeAckModeOverride()
                && isNtkViewerEpisodePath(path)
                && direct != null
                && hasNtkViewerImageApiPayload(direct.body)
                && !isNtkWebtoonEpisodePath(path))
            return false;
        return isNtkApiOnlyFastPage(direct, path)
                && fallbackFetch != null
                && fallbackFetch.done.getCount() > 0;
    }

    private static boolean shouldPreferNtkHtmlImagePage(CustomHttpClient.PageResponse direct,
                                                        CustomHttpClient.PageResponse fallback,
                                                        String path) {
        return isNtkApiOnlyFastPage(direct, path)
                && (isNtkHtmlImagePage(fallback) || isNtkGeneratedShellIdentityPage(fallback, path));
    }

    private static boolean isNtkApiOnlyFastPage(CustomHttpClient.PageResponse page, String path) {
        return isUsableNtkApiPage(page)
                && !isUsableNtkSyntheticWebtoonDirectPage(page, path)
                && !hasNtkPageImageInText(page.body);
    }

    private static boolean isNtkHtmlImagePage(CustomHttpClient.PageResponse page) {
        return page != null
                && page.code >= 200
                && page.code < 400
                && hasNtkPageImageInText(page.body);
    }

    private static boolean isNtkGeneratedShellIdentityPage(CustomHttpClient.PageResponse page, String path) {
        if(page == null || page.code < 200 || page.code >= 400 || page.body == null)
            return false;
        if(!isNtkViewerEpisodePath(path) || isNtkSyntheticWebtoonEpisodePath(path))
            return false;
        String normalized = normalizeNtkViewerPayloadText(page.body);
        if(looksLikeNtkBlockedPage(normalized) || looksLikeNtkMissingPage(normalized))
            return false;
        boolean hasGeneratedIdentity = ntkViewerMetaPageCount(normalized) > 0
                && (ntkViewerSourceWorkId(normalized).length() > 0
                || ntkViewerThumbWorkId(normalized).length() > 0
                || ntkViewerImagesTokenField(ntkViewerImagesToken(normalized), "w").length() > 0
                || ntkViewerEmbeddedImageEpisodeId(normalized, "").length() > 0);
        if(!hasGeneratedIdentity)
            return false;
        return hasNtkViewerImageApiPayloadNormalized(normalized)
                || hasNonEmptyNtkViewerImageMetas(normalized)
                || ntkViewerSourceWorkId(normalized).length() > 0
                || ntkViewerThumbWorkId(normalized).length() > 0;
    }

    private AsyncNtkNativeAck startAsyncNtkNativeAck(CustomHttpClient client, String path) {
        AsyncNtkNativeAck fetch = new AsyncNtkNativeAck();
        CustomHttpClient.RequestGroup requestGroup = client == null ? null : client.currentRequestGroup();
        fetch.requestGroup = requestGroup;
        Thread thread = new Thread(() -> {
            fetch.started.countDown();
            try {
                boolean freshModernAck = client != null && client.isModernNtkGuardRootForPath(path);
                if(requestGroup != null) {
                    fetch.completed = client.runWithRequestGroup(requestGroup,
                            () -> freshModernAck
                                    ? client.performNtkNativeAckBypassFresh(client.getUrl(path), path)
                                    : client.performNtkNativeAckBypass(client.getUrl(path), path));
                } else {
                    fetch.completed = freshModernAck
                            ? client.performNtkNativeAckBypassFresh(client.getUrl(path), path)
                            : client.performNtkNativeAckBypass(client.getUrl(path), path);
                }
            } catch (Exception e) {
                fetch.error = e;
            } finally {
                fetch.done.countDown();
            }
        }, "ntk-native-ack-prefetch");
        thread.setDaemon(true);
        thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
        fetch.thread = thread;
        thread.start();
        return fetch;
    }

    private CustomHttpClient.PageResponse fetchFreshNtkPage(CustomHttpClient client, String path) throws Exception {
        try(Response response = client.mget(path, true)) {
            String body = "";
            if(response.body() != null)
                body = response.body().string();
            return new CustomHttpClient.PageResponse(response.code(), body, false);
        }
    }

    private void awaitAsyncNtkNativeAckStarted(AsyncNtkNativeAck fetch, long timeoutMs) {
        if(fetch == null)
            return;
        try {
            fetch.started.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startDelayedNtkNativeAckWarmup(CustomHttpClient client, String path, long delayMs) {
        if(client == null || path == null || path.length() == 0)
            return;
        CustomHttpClient.RequestGroup requestGroup = client.currentRequestGroup();
        Thread thread = new Thread(() -> {
            try {
                if(delayMs > 0)
                    Thread.sleep(delayMs);
                if(requestGroup != null) {
                    client.runWithRequestGroup(requestGroup,
                            () -> client.performNtkNativeAckBypass(client.getUrl(path), path));
                } else {
                    client.performNtkNativeAckBypass(client.getUrl(path), path);
                }
            } catch (Exception ignored) {
            }
        }, "ntk-native-ack-delayed-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean awaitAsyncNtkNativeAck(AsyncNtkNativeAck fetch) throws Exception {
        return awaitAsyncNtkNativeAck(fetch, 14_000L, true);
    }

    private boolean awaitAsyncNtkNativeAck(AsyncNtkNativeAck fetch, long timeoutMs,
                                           boolean throwOnError) throws Exception {
        if(fetch == null)
            return false;
        try {
            boolean done = fetch.done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
            if(!done)
                return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if(fetch.error != null && throwOnError)
            throw fetch.error;
        return fetch.completed;
    }

    private void cancelAsyncNtkNativeAck(AsyncNtkNativeAck fetch) {
        if(fetch == null)
            return;
        CustomHttpClient.RequestGroup group = fetch.requestGroup;
        if(group != null)
            group.cancel();
        Thread thread = fetch.thread;
        if(thread != null)
            thread.interrupt();
    }

    private static final class AsyncNtkPageFetch {
        final CountDownLatch done = new CountDownLatch(1);
        volatile CustomHttpClient.RequestGroup requestGroup;
        volatile Thread thread;
        volatile CustomHttpClient.PageResponse page;
        volatile Exception error;

        void cancel() {
            CustomHttpClient.RequestGroup group = requestGroup;
            if(group != null)
                group.cancel();
            Thread fetchThread = thread;
            if(fetchThread != null)
                fetchThread.interrupt();
        }
    }

    private static final class AsyncNtkNativeAck {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        volatile CustomHttpClient.RequestGroup requestGroup;
        volatile Thread thread;
        volatile boolean completed;
        volatile Exception error;
    }

    private void addNtkDocumentImageCandidates(CustomHttpClient client, Document d, Set<String> seenImages,
                                               Set<String> fallbackBoardImages) {
        if(d == null)
            return;
        Elements pageImages = d.select("img");
        boolean hasViewerContent = hasNtkViewerContent(d);
        for(Element img : pageImages) {
            for(String attr : new String[]{"data-original", "data-src", "data-lazy-src", "src", "data-srcset", "srcset"}) {
                for(String src : ntkImageAttributeCandidates(img.attr(attr), attr.contains("srcset"))) {
                    if(isNtkPageImage(img, src))
                        addImageIfValid(client, seenImages, src);
                    else if(isNtkFallbackBoardPageImage(img, src))
                        fallbackBoardImages.add(src);
                }
            }
        }
        for(Element preload : d.select("link[rel=preload][as=image][href]")) {
            String src = preload.attr("href");
            if(isNtkPageImage(null, src))
                addImageIfValid(client, seenImages, src);
            else if(hasViewerContent && isNtkBoardUploadImage(src))
                fallbackBoardImages.add(src);
        }
    }

    private void addNtkTextImageCandidates(CustomHttpClient client, String body, Set<String> seenImages,
                                           Set<String> fallbackBoardImages) {
        if(body == null || body.length() == 0)
            return;
        String normalized = normalizeNtkViewerPayloadText(body);
        addNtkTextImageMatches(client, normalized, NTK_TEXT_IMAGE_PATTERN, false, seenImages, fallbackBoardImages);
        addNtkTextImageMatches(client, body, NTK_ENCODED_TEXT_IMAGE_PATTERN, true, seenImages, fallbackBoardImages);
        compactNtkImageCandidates(normalized, seenImages);
    }

    private static List<String> ntkDirectPageImageUrlsFromText(String body, int limit) {
        ArrayList<String> urls = new ArrayList<>();
        if(body == null || body.length() == 0 || limit <= 0)
            return urls;
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        collectNtkDirectPageImageUrls(ordered, normalizeNtkViewerPayloadText(body), NTK_TEXT_IMAGE_PATTERN, false, limit);
        if(ordered.size() < limit)
            collectNtkDirectPageImageUrls(ordered, body, NTK_ENCODED_TEXT_IMAGE_PATTERN, true, limit);
        if(ordered.size() < limit) {
            Matcher proxiedMatcher = NTK_NEXT_IMAGE_URL_PARAM_PATTERN.matcher(body);
            while(proxiedMatcher.find() && ordered.size() < limit) {
                String url = normalizeNtkEmbeddedImageText(decodeNtkUrlParameter(proxiedMatcher.group(1)));
                if(isNtkPageImage(null, url))
                    ordered.add(url);
            }
        }
        urls.addAll(ordered);
        return urls;
    }

    private static void collectNtkDirectPageImageUrls(Set<String> out, String source, Pattern pattern,
                                                       boolean percentEncoded, int limit) {
        if(out == null || source == null || pattern == null || out.size() >= limit)
            return;
        Matcher matcher = pattern.matcher(source);
        while(matcher.find() && out.size() < limit) {
            String url = matcher.group();
            if(percentEncoded) {
                try {
                    url = URLDecoder.decode(url, "UTF-8");
                } catch (Exception ignored) {
                }
            }
            url = normalizeNtkEmbeddedImageText(url);
            if(isNtkPageImage(null, url))
                out.add(url);
        }
    }

    private boolean addNtkDirectTextImageCandidates(CustomHttpClient client, String body,
                                                    String path, Set<String> seenImages) {
        if(body == null || body.length() == 0 || seenImages == null)
            return false;
        int before = imgs == null ? 0 : imgs.size();
        addNtkTextImageCandidates(client, body, seenImages, new LinkedHashSet<>());
        return imgs != null && imgs.size() > before;
    }

    private void addNtkTextImageMatches(CustomHttpClient client, String source, Pattern pattern, boolean percentEncoded,
                                        Set<String> seenImages, Set<String> fallbackBoardImages) {
        Matcher matcher = pattern.matcher(source);
        while(matcher.find()) {
            String url = matcher.group();
            if(percentEncoded) {
                try {
                    url = URLDecoder.decode(url, "UTF-8");
                } catch (Exception ignored) {
                }
            }
            url = normalizeNtkEmbeddedImageText(url);
            if(isNtkPageImage(null, url))
                addImageIfValid(client, seenImages, url);
        }
        Matcher proxiedMatcher = NTK_NEXT_IMAGE_URL_PARAM_PATTERN.matcher(source);
        while(proxiedMatcher.find()) {
            String url = decodeNtkUrlParameter(proxiedMatcher.group(1));
            url = normalizeNtkEmbeddedImageText(url);
            if(isNtkPageImage(null, url))
                addImageIfValid(client, seenImages, url);
        }
    }

    private boolean addNtkViewerMetaImageCandidates(CustomHttpClient client, String body, String path, Set<String> seenImages) {
        if(body == null || path == null || seenImages == null)
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if(!lower.contains("\"imagestoken\"") || !lower.contains("\"imagemetas\""))
            return false;
        if(shouldTryNtkCanonicalGeneratedImagePath(path)) {
            if(hasNtkViewerImageApiPayloadNormalized(normalized)) {
                Log.d(TAG, "ntk_canonical_generated_skip_api_payload path=" + path);
                return false;
            }
            return addNtkCanonicalGeneratedMetaImageCandidates(client, normalized, path, seenImages, "parse");
        }
        if(isNtkSyntheticWebtoonEpisodePath(path)) {
            Matcher slugMatcher = Pattern.compile("^/webtoon/(\\d+)/[^/?#]+").matcher(path);
            String imageEpisodeId = getNtkImageEpisodeId();
            if(slugMatcher.find() && isNumericNtkId(imageEpisodeId)) {
                int pageCount = ntkViewerMetaPageCount(normalized);
                if(pageCount <= 0)
                    pageCount = getNtkImageCount();
                if(pageCount <= 0)
                    pageCount = ntkGeneratedImageCandidateCount();
                return addValidatedNtkGeneratedBaseImages(client, seenImages,
                        "webtoon", slugMatcher.group(1), imageEpisodeId, pageCount);
            }
            return false;
        }
        if(shouldSkipNtkGeneratedForEpisodePath(path))
            return addNtkSlugViewerMetaImageCandidates(client, normalized, path, seenImages);
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String segment = pathMatcher.group(1);
        String workId = pathMatcher.group(2);
        String episodeId = pathMatcher.group(3);
        int pageCount = ntkViewerMetaPageCount(normalized);
        if(pageCount <= 0)
            return false;
        pageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        if(client == null)
            return false;
        String extension = reachableNtkGeneratedImageExtension(client, segment, workId, episodeId, 1);
        if(extension.length() == 0)
            return false;
        int before = imgs == null ? 0 : imgs.size();
        for(int page = 1; page <= pageCount; page++)
            addImageIfValid(client, seenImages, ntkGeneratedImageUrl(segment, workId, episodeId, page, extension));
        return imgs != null && imgs.size() > before;
    }

    private boolean addNtkCanonicalGeneratedMetaImageCandidates(CustomHttpClient client, String normalized,
                                                                String path, Set<String> seenImages,
                                                                String source) {
        if(seenImages == null)
            return false;
        ArrayList<String> urls = reachableNtkCanonicalGeneratedUrls(client, normalized, path, source);
        if(urls.size() == 0)
            return false;
        int before = imgs == null ? 0 : imgs.size();
        for(String url : urls)
            addImageIfValid(client, seenImages, url);
        return imgs != null && imgs.size() > before;
    }

    private ArrayList<String> reachableNtkCanonicalGeneratedUrls(CustomHttpClient client, String normalized,
                                                                 String path, String source) {
        ArrayList<String> urls = new ArrayList<>();
        if(client == null || normalized == null || path == null)
            return urls;
        if(!shouldTryNtkCanonicalGeneratedImagePath(path)) {
            Log.d(TAG, "ntk_canonical_generated_unusable_path path=" + path
                    + ",source=" + source
                    + ",preferCanonical=" + shouldPreferNtkApiForCanonicalWebtoonPath(path)
                    + ",skipSlug=" + shouldSkipNtkGeneratedForEpisodePath(path));
            return urls;
        }
        Matcher pathMatcher = Pattern.compile("^/webtoon/(\\d{6,})/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return urls;
        String workId = pathMatcher.group(1);
        String pathEpisodeId = pathMatcher.group(2);
        String token = ntkViewerImagesToken(normalized);
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        String knownImageEpisodeId = getNtkImageEpisodeId();
        String knownImageWorkId = getNtkImageWorkId();
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        if(isNtkSyntheticWebtoonEpisodePath(path)
                && !isNumericNtkId(ntkApiEpisodeIdForPath(knownImageEpisodeId))
                && !isNumericNtkId(ntkApiEpisodeIdForPath(tokenEpisodeId))) {
            Log.d(TAG, "ntk_canonical_generated_skip_synthetic_api_only path=" + path
                    + ",source=" + source
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",known=" + knownImageEpisodeId);
            return urls;
        }
        String imageEpisodeId = firstNtkImageEpisodeId(tokenEpisodeId, knownImageEpisodeId,
                pathEpisodeId, embeddedEpisodeId);
        if(!isNumericNtkId(imageEpisodeId)) {
            Log.d(TAG, "ntk_canonical_generated_no_image_episode path=" + path
                    + ",source=" + source
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",known=" + knownImageEpisodeId
                    + ",embeddedEpisodeId=" + embeddedEpisodeId);
            return urls;
        }
        Log.d(TAG, "ntk_canonical_generated_identity path=" + path
                + ",source=" + source
                + ",workId=" + workId
                + ",knownImageWorkId=" + knownImageWorkId
                + ",pathEpisodeId=" + pathEpisodeId
                + ",tokenEpisodeId=" + tokenEpisodeId
                + ",known=" + knownImageEpisodeId
                + ",embeddedEpisodeId=" + embeddedEpisodeId
                + ",imageEpisodeId=" + imageEpisodeId);
        int pageCount = ntkViewerMetaPageCount(normalized);
        if(pageCount <= 0)
            pageCount = getNtkImageCount();
        if(pageCount <= 0) {
            Log.d(TAG, "ntk_canonical_generated_no_page_count path=" + path
                    + ",source=" + source
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",bodyLen=" + normalized.length());
            return urls;
        }
        LinkedHashSet<String> workIds = new LinkedHashSet<>();
        addNtkCandidateIfNumeric(workIds, knownImageWorkId);
        addNtkCandidateIfNumeric(workIds, workId);
        String selectedWorkId = "";
        String extension = "";
        for(String candidateWorkId : workIds) {
            extension = reachableNtkGeneratedImageExtension(client, "webtoon", candidateWorkId, imageEpisodeId, 1);
            if(extension.length() > 0) {
                selectedWorkId = candidateWorkId;
                break;
            }
        }
        if(extension.length() == 0) {
            Log.d(TAG, "ntk_canonical_generated_unreachable path=" + path
                    + ",source=" + source
                    + ",workId=" + workId
                    + ",knownImageWorkId=" + knownImageWorkId
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",pageCount=" + pageCount);
            return urls;
        }
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        for(int page = 1; page <= safePageCount; page++)
            urls.add(ntkGeneratedImageUrl("webtoon", selectedWorkId, imageEpisodeId, page, extension));
        Log.d(TAG, "ntk_canonical_generated_urls_ready path=" + path
                + ",source=" + source
                + ",workId=" + selectedWorkId
                + ",pathWorkId=" + workId
                + ",imageEpisodeId=" + imageEpisodeId
                + ",count=" + urls.size()
                + ",extension=" + extension);
        return urls;
    }

    private boolean addNtkSlugViewerMetaImageCandidates(CustomHttpClient client, String normalized,
                                                        String path, Set<String> seenImages) {
        Matcher pathMatcher = Pattern.compile("^/webtoon/\\d+/([^/?#]+)").matcher(path == null ? "" : path);
        if(!pathMatcher.find())
            return false;
        String pathEpisodeId = pathMatcher.group(1);
        int pageCount = ntkViewerMetaPageCount(normalized);
        if(pageCount <= 0)
            return false;
        pageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        String token = ntkViewerImagesToken(normalized);
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        String knownImageEpisodeId = getNtkImageEpisodeId();
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        LinkedHashSet<String> cdnWorkIds = new LinkedHashSet<>();
        addNtkEpisodeCandidate(cdnWorkIds, ntkViewerThumbWorkId(normalized));
        addNtkEpisodeCandidate(cdnWorkIds, ntkViewerSourceWorkId(normalized));
        addNtkEpisodeCandidate(cdnWorkIds, ntkViewerImagesTokenField(token, "w"));
        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        addNtkEpisodeCandidate(episodeIds, knownImageEpisodeId);
        addNtkEpisodeCandidate(episodeIds, embeddedEpisodeId);
        if(isNumericNtkId(tokenEpisodeId))
            addNtkEpisodeCandidate(episodeIds, tokenEpisodeId);
        if(isNumericNtkId(pathEpisodeId))
            addNtkEpisodeCandidate(episodeIds, pathEpisodeId);
        addNtkEpisodeCandidate(episodeIds, tokenEpisodeId);
        addNtkEpisodeCandidate(episodeIds, pathEpisodeId);
        int probes = 0;
        for(String cdnWorkId : cdnWorkIds) {
            for(String episodeId : episodeIds) {
                if(++probes > NTK_LAST_RESORT_GENERATED_PROBE_LIMIT)
                    return false;
                if(addValidatedNtkSlugWebtoonBaseImages(client, seenImages, cdnWorkId, episodeId, pageCount))
                    return true;
                if(cdnWorkId.matches("\\d+")
                        && episodeId.matches("\\d+")
                        && addValidatedNtkGeneratedBaseImages(client, seenImages,
                        "webtoon", cdnWorkId, episodeId, pageCount))
                    return true;
            }
        }
        return false;
    }

    private boolean addFastNtkApiPageImageCandidates(CustomHttpClient client, CustomHttpClient.PageResponse page,
                                                     String path, Set<String> seenImages,
                                                     boolean tryGeneratedMetaFirst) {
        return addFastNtkApiPageImageCandidates(client, page, path, seenImages, tryGeneratedMetaFirst, false);
    }

    private boolean addFastNtkApiPageImageCandidates(CustomHttpClient client, CustomHttpClient.PageResponse page,
                                                     String path, Set<String> seenImages,
                                                     boolean tryGeneratedMetaFirst,
                                                     boolean preferApiPayload) {
        if(!isUsableNtkApiPage(page)) {
            if(!isUsableNtkSyntheticWebtoonDirectPage(page, path))
                return false;
        }
        boolean apiFirstNtkEpisode = isNtkViewerEpisodePath(path);
        boolean webtoonApiFirst = isNtkWebtoonEpisodePath(path);
        boolean canonicalWebtoonApiFirst = shouldPreferNtkApiForCanonicalWebtoonPath(path);
        if(isNtkSyntheticWebtoonEpisodePath(path)
                && hasNtkViewerImageApiPayload(page.body)) {
            if(addNtkApiViewerImageCandidates(client, page.body, path, seenImages, false))
                return true;
            boolean tokenPrefetchStarted = startAsyncNtkViewerImageApiFetchFromToken(
                    client, path, page.body, "synthetic-fast-page");
            if((tokenPrefetchStarted
                    && awaitCachedNtkViewerImageApiCandidates(client, path, seenImages,
                    NTK_WEBVIEW_VIEWER_IMAGES_CACHE_WAIT_MS))
                    || addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                Log.d(TAG, "ntk_synthetic_fast_page_token_cache_hit path=" + path);
                return true;
            }
        }
        if(isNtkSyntheticWebtoonEpisodePath(path))
            return false;
        if(canonicalWebtoonApiFirst && hasNtkViewerImageApiPayload(page.body)) {
            if(addNtkApiViewerImageCandidates(client, page.body, path, seenImages,
                    !apiFirstNtkEpisode && tryGeneratedMetaFirst))
                return true;
            return addNtkViewerMetaImageCandidates(client, page.body, path, seenImages);
        }
        if(!preferApiPayload
                && apiFirstNtkEpisode
                && !shouldSkipNtkGeneratedForEpisodePath(path)
                && addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, false))
            return true;
        if(!preferApiPayload
                && webtoonApiFirst
                && addNtkViewerMetaImageCandidates(client, page.body, path, seenImages))
            return true;
        if(apiFirstNtkEpisode
                && hasNtkViewerImageApiPayload(page.body)
                && addNtkApiViewerImageCandidates(client, page.body, path, seenImages,
                !apiFirstNtkEpisode && tryGeneratedMetaFirst))
            return true;
        if((preferApiPayload || apiFirstNtkEpisode)
                && hasNtkViewerImageApiPayload(page.body)
                && addNtkApiViewerImageCandidates(client, page.body, path, seenImages,
                !apiFirstNtkEpisode && tryGeneratedMetaFirst))
            return true;
        if(webtoonApiFirst
                && addNtkLastResortWebtoonGeneratedImageCandidates(client, page.body, path, seenImages,
                ntkGeneratedImageCandidateCount()))
            return true;
        if(addNtkBoardUploadTextImageCandidates(client, page.body, seenImages)) {
            Log.d(TAG, "ntk_board_uploads_selected_after_generated path=" + path
                    + ",count=" + (imgs == null ? 0 : imgs.size()));
            startFirstNtkApiImageStream(client, path, imgs);
            return true;
        }
        if(!apiFirstNtkEpisode && addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, false))
            return true;
        if(addNtkApiViewerImageCandidates(client, page.body, path, seenImages,
                !apiFirstNtkEpisode && tryGeneratedMetaFirst))
            return true;
        return addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, true);
    }

    private static boolean isUsableNtkSyntheticWebtoonDirectPage(CustomHttpClient.PageResponse page, String path) {
        return isNtkSyntheticWebtoonEpisodePath(path)
                && page != null
                && page.code >= 200
                && page.code < 400
                && page.body != null
                && page.body.length() > 0
                && hasNtkPageImageInText(page.body);
    }

    static boolean isUsableNtkKpDirectPageForTest(String path, int code, String body) {
        return isUsableNtkSyntheticWebtoonDirectPage(new CustomHttpClient.PageResponse(code, body, false), path);
    }

    private boolean addNtkViewerShellGeneratedImageCandidates(CustomHttpClient client, String body,
                                                             String path, Set<String> seenImages,
                                                             boolean validateInitialPages) {
        if(client == null || body == null || path == null || seenImages == null)
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        if(isNtkSyntheticWebtoonEpisodePath(path))
            return false;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        boolean canonicalWebtoonPath = shouldPreferNtkApiForCanonicalWebtoonPath(path);
        String segment = pathMatcher.group(1);
        String pathWorkId = pathMatcher.group(2);
        String pathEpisodeId = pathMatcher.group(3);
        LinkedHashSet<String> workIds = new LinkedHashSet<>();
        String thumbWorkId = ntkViewerThumbWorkId(normalized);
        String sourceWorkId = ntkViewerSourceWorkId(normalized);
        String knownImageWorkId = getNtkImageWorkId();
        String tokenWorkId = ntkViewerImagesTokenField(ntkViewerImagesToken(normalized), "w");
        String tokenEpisodeId = ntkViewerImagesTokenField(ntkViewerImagesToken(normalized), "e");
        String embeddedImageEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        String generatedEpisodeId = canonicalWebtoonPath
                ? firstNtkImageEpisodeId(tokenEpisodeId, getNtkImageEpisodeId(),
                pathEpisodeId, embeddedImageEpisodeId)
                : (isNumericNtkId(tokenEpisodeId) ? tokenEpisodeId : embeddedImageEpisodeId);
        boolean slugEpisodePath = shouldSkipNtkGeneratedForEpisodePath(path);
        boolean hasNumericWorkId = isNumericNtkId(tokenWorkId)
                || isNumericNtkId(thumbWorkId)
                || isNumericNtkId(sourceWorkId)
                || isNumericNtkId(pathWorkId);
        if(slugEpisodePath && (!hasNumericWorkId || !isNumericNtkId(generatedEpisodeId)))
            return false;
        if(slugEpisodePath)
            Log.d(TAG, "ntk_slug_generated_identity path=" + path
                    + ",tokenWorkId=" + tokenWorkId
                    + ",thumbWorkId=" + thumbWorkId
                    + ",sourceWorkId=" + sourceWorkId
                    + ",pathWorkId=" + pathWorkId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",embeddedEpisodeId=" + embeddedImageEpisodeId
                    + ",generatedEpisodeId=" + generatedEpisodeId);
        if(canonicalWebtoonPath) {
            addNtkCandidateIfNumeric(workIds, knownImageWorkId);
            if(shouldUseNtkThumbWorkIdForCanonicalGenerated(pathWorkId, tokenWorkId, sourceWorkId, thumbWorkId))
                addNtkCandidateIfNumeric(workIds, thumbWorkId);
            addNtkCandidateIfNumeric(workIds, tokenWorkId);
            addNtkCandidateIfNumeric(workIds, sourceWorkId);
            addNtkCandidateIfNumeric(workIds, pathWorkId);
        } else {
            if(tokenWorkId.length() > 0)
                workIds.add(tokenWorkId);
            if(thumbWorkId.length() > 0)
                workIds.add(thumbWorkId);
            if(sourceWorkId.length() > 0)
                workIds.add(sourceWorkId);
            if(pathWorkId.length() > 0)
                workIds.add(pathWorkId);
        }
        if(workIds.size() == 0)
            return false;
        String episodeId = generatedEpisodeId.length() > 0 ? generatedEpisodeId : tokenEpisodeId;
        if(episodeId.length() == 0)
            episodeId = getNtkImageEpisodeId();
        if(episodeId.length() == 0)
            episodeId = pathEpisodeId;
        if(episodeId.length() == 0)
            episodeId = embeddedImageEpisodeId;
        if(canonicalWebtoonPath)
            Log.d(TAG, "ntk_canonical_meta_generated_identity path=" + path
                    + ",tokenWorkId=" + tokenWorkId
                    + ",thumbWorkId=" + thumbWorkId
                    + ",knownImageWorkId=" + knownImageWorkId
                    + ",sourceWorkId=" + sourceWorkId
                    + ",pathWorkId=" + pathWorkId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",known=" + getNtkImageEpisodeId()
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",embeddedEpisodeId=" + embeddedImageEpisodeId
                    + ",episodeId=" + episodeId);
        if(canonicalWebtoonPath && isNumericNtkId(episodeId) && !hasNtkViewerImageApiPayloadNormalized(normalized)) {
            for(String workId : workIds) {
                if(!isNumericNtkId(workId))
                    continue;
                startSpeculativeNtkGeneratedInitialStreams(client, segment, workId, episodeId);
            }
        }
        int pageCount = ntkViewerMetaPageCount(normalized);
        if(pageCount <= 0)
            pageCount = ntkGeneratedImageCandidateCount();
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        int validationPageCount = validateInitialPages ? ntkGeneratedInitialValidationPageCount(safePageCount) : 1;
        for(String workId : workIds) {
            if(!workId.matches("\\d+"))
                return "webtoon".equals(segment)
                        && addNtkSlugWebtoonGeneratedImageCandidates(
                        client, path, seenImages, pageCount, validateInitialPages);
            String extension = reachableNtkGeneratedImageExtension(client, segment, workId, episodeId, 1);
            if(extension.length() == 0) {
                if(slugEpisodePath)
                    Log.d(TAG, "ntk_slug_generated_extension_miss path=" + path
                            + ",segment=" + segment
                            + ",workId=" + workId
                            + ",episodeId=" + episodeId);
                continue;
            }
            boolean reachable = true;
            for(int page = 2; client != null && page <= validationPageCount; page++) {
                String cacheKey = ntkGeneratedExtensionCacheKey("webtoon", workId, episodeId, page);
                String cachedExtension = cachedFreshNtkGeneratedImageExtension(cacheKey);
                if(cachedExtension != null) {
                    if(cachedExtension.length() == 0) {
                        reachable = false;
                        break;
                    }
                    if(cachedExtension.equals(extension))
                        continue;
                }
                if(!isNtkGeneratedImageReachable(client,
                        ntkGeneratedImageUrl(segment, workId, episodeId, page, extension))) {
                    cacheNtkGeneratedImageExtension(cacheKey, "");
                    reachable = false;
                    break;
                }
                cacheNtkGeneratedImageExtension(cacheKey, extension);
            }
            if(!reachable)
                continue;
            int before = imgs == null ? 0 : imgs.size();
            for(int page = 1; page <= safePageCount; page++)
                addImageIfValid(client, seenImages,
                        ntkGeneratedImageUrl(segment, workId, episodeId, page, extension));
            if(imgs != null && imgs.size() > before)
                return true;
        }
        return false;
    }

    private boolean addNtkApiViewerImageCandidates(CustomHttpClient client, String body, String path, Set<String> seenImages) {
        return addNtkApiViewerImageCandidates(client, body, path, seenImages, true);
    }

    private boolean addNtkApiViewerImageCandidates(CustomHttpClient client, String body, String path, Set<String> seenImages,
                                                   boolean tryGeneratedMetaFirst) {
        if(client == null || body == null || path == null || seenImages == null)
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        if(!hasNtkViewerImageApiPayloadNormalized(normalized))
            return false;
        if(isNtkViewerConfirmedEmptyPayloadNormalized(normalized, path)) {
            Log.d(TAG, "ntk_viewer_api_skip_empty_image_metas path=" + path);
            return false;
        }
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        if(!isNtkSyntheticWebtoonEpisodePath(path)
                && shouldSkipCurrentNtkImageApiBecauseGeneratedInitialReady(client, path)) {
            Log.d(TAG, "ntk_viewer_api_skip_recent_generated_initial path=" + path);
            return false;
        }
        String token = ntkViewerImagesToken(normalized);
        if(token.length() == 0) {
            Log.d(TAG, "ntk_viewer_api_token_missing path=" + path
                    + ",snippet=" + ntkViewerPayloadSnippet(normalized));
            return false;
        }
        boolean canonicalWebtoonApiFirst = shouldPreferNtkApiForCanonicalWebtoonPath(path);
        boolean syntheticWebtoonPath = isNtkSyntheticWebtoonEpisodePath(path);
        int before = imgs == null ? 0 : imgs.size();
        if(!canonicalWebtoonApiFirst && !syntheticWebtoonPath)
            addNtkTextImageCandidates(client, normalized, seenImages, new LinkedHashSet<>());
        if(imgs != null && imgs.size() > before)
            return true;
        if(!syntheticWebtoonPath
                && !isNtkViewerEpisodePath(path)
                && !shouldPreferNtkApiForCanonicalWebtoonPath(path)
                && (tryGeneratedMetaFirst || shouldSkipNtkGeneratedForEpisodePath(path))) {
            addNtkViewerMetaImageCandidates(client, normalized, path, seenImages);
            if(imgs != null && imgs.size() > before)
                return true;
        }
        if(!canonicalWebtoonApiFirst
                && !syntheticWebtoonPath
                && addNtkBoardUploadTextImageCandidates(client, normalized, seenImages))
            return true;
        if(addCachedNtkViewerImageApiCandidates(client, path, seenImages))
            return true;
        boolean preferNativeApiImageFetch = shouldPreAckBeforeNtkViewerImageApi(path);
        if(!syntheticWebtoonPath
                && !preferNativeApiImageFetch && looksLikeNtkWebViewViewerPayload(normalized)
                && awaitCachedNtkViewerImageApiCandidates(client, path, seenImages, NTK_WEBVIEW_VIEWER_IMAGES_CACHE_WAIT_MS))
            return true;
        if(!isNtkNativeAckModeOverride()
                && !isNtkApiFallbackModeOverride()
                && !isNtkStrictApiFallbackModeOverride()
                && !syntheticWebtoonPath
                && !shouldPreferNtkApiForCanonicalWebtoonPath(path)
                && shouldPreAckBeforeNtkViewerImageApi(path)
                && awaitCachedNtkViewerImageApiCandidates(client, path, seenImages, 650L))
            return true;
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        String pathEpisodeId = pathMatcher.group(3);
        List<String> naverWebtoonUrls = fetchNaverWebtoonImageUrlsForNvEpisode(
                client, tokenEpisodeId, pathEpisodeId, 0);
        if(!naverWebtoonUrls.isEmpty()) {
            Log.d(TAG, "ntk_viewer_api_naver_original_urls path=" + path
                    + ",count=" + naverWebtoonUrls.size()
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",first=" + safeLogImage(naverWebtoonUrls.get(0)));
            for(String url : naverWebtoonUrls)
                addImageIfValid(client, seenImages, url);
            startFirstNtkApiImageStream(client, path, naverWebtoonUrls);
            return imgs != null && imgs.size() > before;
        }
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        String imageEpisodeId = ntkViewerApiImageEpisodeId(tokenEpisodeId, getNtkImageEpisodeId(),
                pathEpisodeId, embeddedEpisodeId);
        String knownImageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        if(knownImageEpisodeId.length() == 0)
            knownImageEpisodeId = ntkApiEpisodeIdForPath(embeddedEpisodeId);
        String apiEpisodeId = ntkPreferredViewerImagesApiEpisodeId(tokenEpisodeId, imageEpisodeId,
                pathEpisodeId);
        if(syntheticWebtoonPath && tokenEpisodeId.length() > 0)
            apiEpisodeId = tokenEpisodeId;
        if(embeddedEpisodeId.length() > 0)
            Log.d(TAG, "ntk_viewer_api_embedded_episode_id path=" + path
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",apiEpisodeId=" + apiEpisodeId);
        String viewerBodyForImageFetch = normalized;
        String segment = pathMatcher.group(1);
        String workId = ntkViewerApiWorkIdForPath(path, tokenWorkId);
        String imageWorkId = ntkApiEpisodeIdForPath(getNtkImageWorkId());
        if(shouldSkipNtkGeneratedForEpisodePath(path)
                && "webtoon".equals(segment)
                && isNumericNtkId(imageWorkId)
                && !imageWorkId.equals(workId)) {
            Log.d(TAG, "ntk_viewer_api_slug_numeric_work_candidate path=" + path
                    + ",apiWorkId=" + workId
                    + ",imageWorkId=" + imageWorkId
                    + ",apiEpisodeId=" + apiEpisodeId
                    + ",imageEpisodeId=" + imageEpisodeId);
            workId = imageWorkId;
        }
        List<String> urls = new ArrayList<>();
        String canonicalWorkId = ntkViewerCanonicalWorkIdForImageApi(normalized, path, titleId, workId);
        if(canonicalWorkId.length() > 0 && imageEpisodeId.length() > 0 && apiEpisodeId.equals(imageEpisodeId)) {
            String canonicalAckPath = "/" + segment + "/" + canonicalWorkId + "/" + imageEpisodeId;
            Log.d(TAG, "ntk_viewer_api_canonical_ack_first path=" + path
                    + ",workId=" + canonicalWorkId
                    + ",ackPath=" + canonicalAckPath);
            urls = client.fetchNtkViewerImageUrls(segment, canonicalWorkId, apiEpisodeId,
                    token, viewerBodyForImageFetch, path, canonicalAckPath, trustedUrls ->
                            startFirstNtkApiImageStream(client, path, trustedUrls));
        }
        if(urls.isEmpty()
                && shouldSkipNtkGeneratedForEpisodePath(path)
                && "webtoon".equals(segment)
                && !hasNtkSlugWebtoonWorkId(path)
                && isNumericNtkId(imageWorkId)
                && isNumericNtkId(imageEpisodeId)
                && !imageWorkId.equals(workId)) {
            Log.d(TAG, "ntk_viewer_api_image_work_first path=" + path
                    + ",workId=" + imageWorkId
                    + ",apiWorkId=" + workId
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",scope=" + path);
            urls = client.fetchNtkViewerImageUrls(segment, imageWorkId, imageEpisodeId,
                    token, viewerBodyForImageFetch, path, path, trustedUrls ->
                            startFirstNtkApiImageStream(client, path, trustedUrls));
        }
        if(urls.isEmpty()) {
            String ackPath = path;
            if(apiEpisodeId.equals(imageEpisodeId)
                    && pathEpisodeId.matches("\\d+")
                    && imageEpisodeId.length() > 0 && !imageEpisodeId.equals(pathEpisodeId)
                    && workId.matches("\\d+"))
                ackPath = "/" + segment + "/" + workId + "/" + imageEpisodeId;
            urls = client.fetchNtkViewerImageUrls(segment, workId, apiEpisodeId,
                    token, viewerBodyForImageFetch, path, ackPath, trustedUrls ->
                            startFirstNtkApiImageStream(client, path, trustedUrls));
        }
        if(urls.isEmpty()
                && shouldSkipNtkGeneratedForEpisodePath(path)
                && "webtoon".equals(segment)
                && !hasNtkSlugWebtoonWorkId(path)
                && isNumericNtkId(imageWorkId)
                && isNumericNtkId(imageEpisodeId)
                && !imageWorkId.equals(workId)) {
            Log.d(TAG, "ntk_viewer_api_image_work_retry path=" + path
                    + ",workId=" + imageWorkId
                    + ",apiWorkId=" + workId
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",scope=" + path);
            urls = client.fetchNtkViewerImageUrls(segment, imageWorkId, imageEpisodeId,
                    token, viewerBodyForImageFetch, path, path, trustedUrls ->
                            startFirstNtkApiImageStream(client, path, trustedUrls));
        }
        if(urls.isEmpty()
                && knownImageEpisodeId.length() > 0
                && !knownImageEpisodeId.equals(apiEpisodeId)
                && shouldRetryNtkKnownImageEpisodeId(tokenEpisodeId, pathEpisodeId,
                apiEpisodeId, knownImageEpisodeId, getNtkImageCount())
                && workId.matches("\\d+")) {
            Log.d(TAG, "ntk_viewer_api_known_image_episode_retry path=" + path
                    + ",apiEpisodeId=" + apiEpisodeId
                    + ",knownImageEpisodeId=" + knownImageEpisodeId
                    + ",ackPath=/" + segment + "/" + workId + "/" + knownImageEpisodeId);
            String knownAckPath = "/" + segment + "/" + workId + "/" + knownImageEpisodeId;
            urls = client.fetchNtkViewerImageUrls(segment, workId, knownImageEpisodeId,
                    token, viewerBodyForImageFetch, knownAckPath, knownAckPath, trustedUrls ->
                            startFirstNtkApiImageStream(client, path, trustedUrls));
        }
        if(urls.size() >= 3 && imgs != null && imgs.size() > 0 && imgs.size() <= 2) {
            if(seenImages != null)
                seenImages.clear();
            imgs.clear();
        }
        int fetchedUrlCount = urls.size();
        urls = normalizeNtkApiViewerImageUrls(urls);
        if(shouldReplaceStaleNtkApiGeneratedUrls(urls, segment, path, pathEpisodeId,
                apiEpisodeId, knownImageEpisodeId)) {
            int pageCount = urls.size();
            if(pageCount <= 0)
                pageCount = ntkViewerMetaPageCount(normalized);
            if(pageCount <= 0)
                pageCount = ntkGeneratedImageCandidateCount();
            Log.d(TAG, "ntk_viewer_api_stale_generated_replace path=" + path
                    + ",apiEpisodeId=" + apiEpisodeId
                    + ",knownImageEpisodeId=" + knownImageEpisodeId
                    + ",pageCount=" + pageCount
                    + ",first=" + (urls.isEmpty() ? "" : safeLogImage(urls.get(0))));
            if(addNtkGeneratedPathImageCandidates(client, path, seenImages, pageCount, true))
                return true;
            Log.d(TAG, "ntk_viewer_api_stale_generated_replace_miss path=" + path
                    + ",apiEpisodeId=" + apiEpisodeId
                    + ",knownImageEpisodeId=" + knownImageEpisodeId);
        }
        Log.d(TAG, "ntk_viewer_api_urls path=" + path
                + ",fetched=" + fetchedUrlCount
                + ",normalized=" + urls.size()
                + ",canonicalWebtoon=" + canonicalWebtoonApiFirst);
        for(String url : urls)
            addImageIfValid(client, seenImages, url);
        startFirstNtkApiImageStream(client, path, urls);
        if(canonicalWebtoonApiFirst && urls.size() >= 3) {
            Log.d(TAG, "ntk_canonical_api_urls_installed path=" + path
                    + ",before=" + before
                    + ",after=" + (imgs == null ? 0 : imgs.size())
                    + ",first=" + (urls.isEmpty() ? "" : urls.get(0).substring(0, Math.min(160, urls.get(0).length()))));
            return imgs != null && imgs.size() > before;
        }
        if(!canonicalWebtoonApiFirst && (imgs == null || imgs.size() == before)) {
            addNtkBoardUploadTextImageCandidates(client, normalized, seenImages);
        }
        if((imgs == null || imgs.size() == before)
                && shouldSkipNtkGeneratedForEpisodePath(path)
                && "webtoon".equals(segment)
                && isNumericNtkId(getNtkImageWorkId())
                && isNumericNtkId(imageEpisodeId)) {
            int pageCount = ntkViewerMetaPageCount(normalized);
            if(pageCount <= 0)
                pageCount = ntkGeneratedImageCandidateCount();
            String generatedWorkId = getNtkImageWorkId();
            Log.d(TAG, "ntk_viewer_api_generated_after_api_miss path=" + path
                    + ",workId=" + generatedWorkId
                    + ",apiWorkId=" + workId
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",pageCount=" + pageCount);
            if(awaitCachedNtkViewerImageApiCandidates(client, path, seenImages, 2200L))
                return true;
            if(addValidatedNtkGeneratedBaseImages(client, seenImages,
                    segment, generatedWorkId, imageEpisodeId, pageCount))
                return true;
            Log.d(TAG, "ntk_viewer_api_generated_after_api_miss_failed path=" + path
                    + ",workId=" + generatedWorkId
                    + ",apiWorkId=" + workId
                    + ",imageEpisodeId=" + imageEpisodeId);
        }
        compactNtkImageCandidates(normalized, seenImages);
        return imgs != null && imgs.size() > before;
    }

    private boolean shouldReplaceStaleNtkApiGeneratedUrls(List<String> urls, String segment, String path,
                                                          String pathEpisodeId, String apiEpisodeId,
                                                          String knownImageEpisodeId) {
        if(urls == null || urls.isEmpty() || segment == null || path == null)
            return false;
        String known = ntkApiEpisodeIdForPath(knownImageEpisodeId);
        String pathEpisode = ntkApiEpisodeIdForPath(pathEpisodeId);
        String apiEpisode = ntkApiEpisodeIdForPath(apiEpisodeId);
        if(!isNumericNtkId(known) || known.equals(pathEpisode) || known.equals(apiEpisode))
            return false;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String workId = pathMatcher.group(2);
        NtkGeneratedUrlIdentity identity = ntkGeneratedUrlIdentity(urls.get(0));
        if(identity == null || !segment.equalsIgnoreCase(identity.segment))
            return false;
        if(!workId.equals(identity.workId))
            return false;
        return identity.episodeId.equals(pathEpisode) || identity.episodeId.equals(apiEpisode);
    }

    private static NtkGeneratedUrlIdentity ntkGeneratedUrlIdentity(String url) {
        if(url == null)
            return null;
        Matcher matcher = Pattern.compile(
                "^https?://[^/]+/(manhwa|webtoon)/(\\d+)/([^/?#]+)/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(url);
        if(matcher.find())
            return new NtkGeneratedUrlIdentity(matcher.group(1).toLowerCase(Locale.ROOT),
                    matcher.group(2), matcher.group(3), parseNtkGeneratedPage(matcher.group(4)));
        matcher = Pattern.compile(
                "^https?://[^/]+/(?:blacktoon|black)/episodes/(\\d+)/([^/?#]+)/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(url);
        if(matcher.find())
            return new NtkGeneratedUrlIdentity("webtoon",
                    matcher.group(1), matcher.group(2), parseNtkGeneratedPage(matcher.group(3)));
        matcher = Pattern.compile(
                "^https?://[^/]+/wt/episodes/([^/?#]+)/([^/?#]+)/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(url);
        if(matcher.find())
            return new NtkGeneratedUrlIdentity("webtoon",
                    matcher.group(1), matcher.group(2), parseNtkGeneratedPage(matcher.group(3)));
        return null;
    }

    private static int parseNtkGeneratedPage(String page) {
        try {
            return Integer.parseInt(page);
        } catch(Exception ignored) {
            return -1;
        }
    }

    private static final class NtkGeneratedUrlIdentity {
        final String segment;
        final String workId;
        final String episodeId;
        final int page;

        NtkGeneratedUrlIdentity(String segment, String workId, String episodeId, int page) {
            this.segment = segment;
            this.workId = workId;
            this.episodeId = episodeId;
            this.page = page;
        }

        String dedupKey() {
            return "ntk-generated:" + segment + "/" + workId + "/" + episodeId + "/p" + page;
        }
    }

    private void startFirstNtkApiImageStream(CustomHttpClient client, String path, List<String> urls) {
        startFirstNtkApiImageStream(
                client, path, urls, true,
                ReaderImageCache.INSTANCE.cacheGenerationForProducer());
    }

    public void startNtkKpDirectManifestHeadStream(CustomHttpClient client, String path,
                                                   List<String> urls, String reason) {
        if(client == null || path == null || urls == null || urls.isEmpty())
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        if(!isNtkKpWebtoonEpisodePath(path))
            return;
        Log.d(TAG, "ntk_kp_direct_manifest_head_stream path=" + path
                + ",count=" + urls.size()
                + ",reason=" + reason
                + ",first=" + safeLogImage(urls.get(0)));
        startFirstNtkApiImageStream(
                client, path, urls, false,
                ReaderImageCache.INSTANCE.cacheGenerationForProducer());
    }

    private void startFirstNtkApiImageStream(CustomHttpClient client, String path, List<String> urls,
                                              boolean publishEarlyUrls) {
        startFirstNtkApiImageStream(
                client, path, urls, publishEarlyUrls,
                ReaderImageCache.INSTANCE.cacheGenerationForProducer());
    }

    private void startFirstNtkApiImageStream(CustomHttpClient client, String path, List<String> urls,
                                              boolean publishEarlyUrls,
                                              long producerGeneration) {
        if(client == null || urls == null || urls.isEmpty())
            return;
        if(isStrictSourceAuthorityManaged(path)) {
            Log.d(TAG, "ntk_first_api_image_stream_fenced_by_strict_authority path=" + path);
            return;
        }
        if(!isNtkViewerEpisodePath(path) && !shouldPreferNtkApiForCanonicalWebtoonPath(path)
                && !shouldSkipNtkGeneratedForEpisodePath(path))
            return;
        urls = limitInitialManhwaGeneratedUrls(path, urls);
        if(isNtkKpWebtoonEpisodePath(path)) {
            ArrayList<String> renderable = new ArrayList<>();
            for(String url : urls) {
                if(isNtkKpNativeRenderableImageUrl(url))
                    renderable.add(url);
            }
            if(renderable.size() != urls.size()) {
                Log.d(TAG, "ntk_first_api_image_stream_filter_kp_non_renderable path=" + path
                        + ",incoming=" + urls.size()
                        + ",renderable=" + renderable.size()
                        + ",first=" + safeLogImage(urls.isEmpty() ? "" : urls.get(0)));
            }
            urls = renderable;
        }
        if(urls.isEmpty())
            return;
        int streamCount = Math.min(ntkInitialApiImageStreamCount(path), urls.size());
        if(isNtkKpWebtoonEpisodePath(path) && containsNtkUploadCdnImageUrl(urls))
            streamCount = 1;
        if(isNaverWebtoonPageImage(urls.get(0)))
            streamCount = 1;
        ArrayList<Integer> streamPositions = new ArrayList<>();
        Set<Integer> streamPages = new LinkedHashSet<>();
        Set<String> streamIdentities = new LinkedHashSet<>();
        for(int i = 0; i < urls.size() && streamPositions.size() < streamCount; i++) {
            String candidate = urls.get(i);
            int page = ntkGeneratedImagePage(candidate);
            if(page > 0 && !streamPages.add(page))
                continue;
            if(page <= 0) {
                String identity = ntkInitialApiImageStreamIdentity(candidate);
                if(identity.length() > 0 && !streamIdentities.add(identity))
                    continue;
            }
            streamPositions.add(i);
        }
        if(streamPositions.isEmpty())
            return;
        int startedStreams = 0;
        if(startNtkInitialForegroundStreamIfAbsent(
                client, path, urls, 0, streamPositions.get(0), producerGeneration))
            startedStreams++;
        if(publishEarlyUrls) {
            try {
                if(isNtkKpWebtoonEpisodePath(path) && containsNtkUploadCdnImageUrl(urls)) {
                    ReaderImageCache.INSTANCE.rememberAuthoritativeNtkImageUrlsFromBrowser(
                            path, urls, "native-api-stream", producerGeneration);
                    NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                            path, urls, "native-api-stream");
                } else {
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                            path, urls, producerGeneration);
                }
            } catch(Exception e) {
                Log.d(TAG, "ntk_early_api_image_urls_error path=" + path + "," + e);
            }
        }
        for(int orderIndex = 1; orderIndex < streamPositions.size(); orderIndex++) {
            if(startNtkInitialForegroundStreamIfAbsent(
                    client, path, urls, orderIndex, streamPositions.get(orderIndex),
                    producerGeneration))
                startedStreams++;
        }
        if(urls.size() > streamPositions.size()) {
            Log.d(TAG, "ntk_first_api_image_stream_defer_adjacent path=" + path
                    + ",selected=" + streamPositions.size()
                    + ",started=" + startedStreams
                    + ",deferred=" + (urls.size() - streamPositions.size()));
        }
    }

    private boolean startNtkInitialForegroundStreamIfAbsent(CustomHttpClient client, String path,
                                                            List<String> urls, int orderIndex,
                                                            int urlIndex,
                                                            long producerGeneration) {
        if(urlIndex < 0 || urlIndex >= urls.size())
            return false;
        String image = urls.get(urlIndex);
        if(image == null || image.length() == 0)
            return false;
        String key = (path == null ? "" : path) + "|" + image;
        if(firstNtkApiImageStreamStarts().putIfAbsent(key, Boolean.TRUE) != null)
            return false;
        startNtkInitialForegroundStream(
                client, path, image, orderIndex, key, true, producerGeneration);
        expireFirstNtkApiImageStreamStart(key, 1800L);
        return true;
    }

    private static boolean isForegroundNtkNativeViewerPath(String path) {
        return path != null && path.length() > 0
                && ml.melun.mangaview.MainApplication.isNtkForegroundViewerPath(path);
    }

    private static boolean hasRecentRenderableEarlyNtkHeadUrls(String path) {
        if(path == null || path.length() == 0)
            return false;
        try {
            List<String> urls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(
                    path,
                    android.os.SystemClock.elapsedRealtime() - 30_000L);
            int expected = Math.min(NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT,
                    urls == null ? 0 : urls.size());
            if(expected <= 0)
                return false;
            for(int i = 0; i < expected; i++) {
                String url = urls.get(i);
                if(url == null || url.length() == 0)
                    return false;
                if(url.toLowerCase(Locale.ROOT).contains("/api/m/i?"))
                    return false;
            }
            return true;
        } catch(Throwable ignored) {
            return false;
        }
    }

    private static int ntkInitialApiImageStreamCount(String path) {
        if((isNtkWebtoonEpisodePath(path) || isNtkViewerEpisodePath(path))
                && ml.melun.mangaview.MainApplication.isNtkForegroundViewerPathActive()
                && !isForegroundNtkNativeViewerPath(path))
            return 1;
        if(isNtkWebtoonEpisodePath(path)
                && isForegroundNtkNativeViewerPath(path)
                && hasRecentRenderableEarlyNtkHeadUrls(path))
            return NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT;
        if(isNtkWebtoonEpisodePath(path) && isForegroundNtkNativeViewerPath(path))
            return NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT;
        if(isNtkWebtoonEpisodePath(path)
                && (isNtkSyntheticWebtoonEpisodePath(path)
                || shouldPreferNtkApiForCanonicalWebtoonPath(path)
                || shouldSkipNtkGeneratedForEpisodePath(path)))
            return NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT;
        if(isNtkWebtoonEpisodePath(path))
            return NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT;
        if(path != null && path.toLowerCase(Locale.ROOT).startsWith("/manhwa/"))
            return NTK_EARLY_INITIAL_STREAM_START_COUNT;
        if(isNtkViewerEpisodePath(path) || shouldPreferNtkApiForCanonicalWebtoonPath(path)
                || shouldSkipNtkGeneratedForEpisodePath(path))
            return 3;
        if(isNtkSyntheticWebtoonEpisodePath(path))
            return NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT;
        return NTK_EARLY_INITIAL_STREAM_START_COUNT;
    }

    private static String ntkInitialApiImageStreamIdentity(String image) {
        if(image == null)
            return "";
        String value = image.trim();
        if(value.length() == 0)
            return "";
        int query = value.indexOf('?');
        if(query >= 0)
            value = value.substring(0, query);
        int hash = value.indexOf('#');
        if(hash >= 0)
            value = value.substring(0, hash);
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return name.toLowerCase(Locale.ROOT);
    }

    private static List<String> limitInitialManhwaGeneratedUrls(String path, List<String> urls) {
        if(path == null || !path.toLowerCase(Locale.ROOT).startsWith("/manhwa/") || urls == null)
            return urls;
        ArrayList<String> limited = new ArrayList<>();
        for(String url : urls) {
            int page = ntkGeneratedImagePage(url);
            if(page <= 0 || page <= NTK_EARLY_INITIAL_STREAM_PAGES)
                limited.add(url);
        }
        return limited;
    }

    private static int ntkGeneratedImagePage(String url) {
        if(url == null)
            return -1;
        Matcher matcher = Pattern.compile("/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(url);
        if(!matcher.find())
            return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch(NumberFormatException e) {
            return -1;
        }
    }

    private void startNtkInitialForegroundStream(CustomHttpClient client, String path,
                                                 String image, int index, String startKey,
                                                 boolean allowConditionRetry,
                                                 long producerGeneration) {
        long delayMs = ntkInitialApiImageStreamDelayMs(path, index);
        Runnable start = () -> {
            try {
                Context context = client.getContext();
                if(context != null) {
                    boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            context, this, image, null, false, null, index,
                            isNtkWebtoonEpisodePath(path)
                                    ? index < NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT
                                    : index < NTK_EARLY_INITIAL_STREAM_START_COUNT,
                            false,
                            producerGeneration);
                    Log.d(TAG, "ntk_first_api_image_stream_start path=" + path
                            + ",started=" + started
                            + ",index=" + index
                            + ",delayMs=" + delayMs
                            + ",image=" + safeLogImage(image));
                    if(!started && startKey != null && startKey.length() > 0) {
                        firstNtkApiImageStreamStarts().remove(startKey);
                    }
                }
            } catch(Exception e) {
                Log.d(TAG, "ntk_first_api_image_stream_error path=" + path + "," + e);
                if(startKey != null && startKey.length() > 0)
                    firstNtkApiImageStreamStarts().remove(startKey);
                if(allowConditionRetry)
                    retryNtkInitialForegroundStream(
                            client, path, image, index, startKey, producerGeneration);
            }
        };
        if(delayMs <= 0L) {
            start.run();
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            start.run();
        }, "ntk-initial-image-stagger");
        thread.setDaemon(true);
        thread.start();
    }

    private void expireFirstNtkApiImageStreamStart(String startKey, long delayMs) {
        if(startKey == null || startKey.length() == 0)
            return;
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(Math.max(250L, delayMs));
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            firstNtkApiImageStreamStarts().remove(startKey);
        }, "ntk-initial-image-guard-expire");
        thread.setDaemon(true);
        thread.start();
    }

    private static long ntkInitialApiImageStreamDelayMs(String path, int index) {
        if(index <= 0)
            return 0L;
        if(isNtkWebtoonEpisodePath(path) && index < NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT)
            return Math.max(0, index) * NTK_EARLY_INITIAL_STREAM_STAGGER_MS;
        if(path != null && path.startsWith("/manhwa/") && index < NTK_EARLY_INITIAL_STREAM_START_COUNT)
            return 0L;
        if(path != null && path.startsWith("/manhwa/") && index <= 2)
            return 0L;
        long staggerMs = NTK_EARLY_INITIAL_STREAM_STAGGER_MS;
        return Math.max(0, index) * staggerMs;
    }

    private void retryNtkInitialForegroundStream(CustomHttpClient client, String path,
                                                 String image, int index, String startKey,
                                                 long producerGeneration) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(NTK_EARLY_INITIAL_STREAM_RETRY_MS);
                if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
                    return;
                startNtkInitialForegroundStream(
                        client, path, image, index, startKey, false, producerGeneration);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch(Exception e) {
                Log.d(TAG, "ntk_first_api_image_stream_retry_error path=" + path + "," + e);
                firstNtkApiImageStreamStarts().remove(startKey);
            }
        }, "ntk-initial-image-retry");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean isNtkGeneratedPageImageUrl(String image) {
        if(image == null)
            return false;
        return Pattern.compile("(?i)^https?://(?:(?:[^/]+\\.)?toonflix\\.app|flysky\\d*m\\.com|moamoabon\\.com|fvcdn\\d*\\.com|aws-cdn\\d*\\.site|apihost\\d*\\.com)/(?:blacktoon/episodes|black/episodes|manhwa|webtoon|wt/episodes)/.*/p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$")
                .matcher(image)
                .find();
    }

    private void startEarlyGeneratedNtkImageStreamFromPartial(CustomHttpClient client, String path,
                                                              String partialText,
                                                              AtomicBoolean started) {
        if(client == null || path == null || partialText == null || started == null)
            return;
        if(shouldUseProtectedNtkViewerApi(client, path)) {
            Log.d(TAG, "ntk_generated_direct_image_url_early_skip_protected_api path=" + path);
            return;
        }
        if(shouldDeferModernNtkGeneratedProbeUntilAck(client, path)) {
            Log.d(TAG, "ntk_generated_direct_image_url_early_defer_ack path=" + path);
            return;
        }
        if(!started.compareAndSet(false, true))
            return;
        Thread thread = new Thread(() -> {
            try {
                List<String> urls = earlyGeneratedNtkImageUrlsFromPartial(
                        client, path, partialText, NTK_EARLY_SPECULATIVE_PAGE_PUBLISH_COUNT);
                if(urls.isEmpty()) {
                    started.set(false);
                    return;
                }
                Log.d(TAG, "ntk_generated_direct_image_url_early path=" + path
                        + ",count=" + urls.size()
                        + ",partialLen=" + partialText.length()
                        + ",first=" + safeLogImage(urls.get(0)));
                startFirstNtkApiImageStream(client, path, urls);
            } catch(Exception e) {
                started.set(false);
                Log.d(TAG, "ntk_generated_direct_image_url_early_error path=" + path + "," + e);
            }
        }, "ntk-generated-first-image");
        thread.setDaemon(true);
        thread.start();
    }

    public void startNtkEarlyViewerApiPrefetch(CustomHttpClient client) {
        NtkStrictEpisodeDiscoveryCoordinator.start(client, this);
    }
    /** Resolves a finite generated manifest immediately after the browser verified its document. */
    public void startNtkAuthenticatedGeneratedManifestProbe(CustomHttpClient client) {
        String path = getNtkEpisodePath();
        if(client == null || path == null || getNtkImageCount() <= 0)
            return;
        startNtkMetadataBackedGeneratedFirstImageStream(
                client, path, "captcha-authenticated-document");
    }

    private boolean waitForCompletePreparedGeneratedManifest(String path, long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        do {
            if(hasCompleteAuthoritativeNtkManifest(path))
                return true;
            if(android.os.SystemClock.elapsedRealtime() >= deadline)
                return false;
            android.os.SystemClock.sleep(25L);
        } while(true);
    }

    private long protectedGeneratedAuthorityWaitMs(String path) {
        // A foreground numeric episode already owns a real native image race.
        // Let that owner either publish its complete manifest or exhaust its
        // bounded attempt before constructing the UI-thread ACK sidecar.  This
        // keeps HTML/guard/WASM allocation and WebView callbacks out of the
        // physical tap -> reader launch interval without postponing the reader.
        return MainApplication.isNtkForegroundViewerPath(path) ? 4_000L : 1_400L;
    }

    public static boolean isNtkEarlyViewerApiPrefetchInFlight(String path) {
        return NtkStrictEpisodeDiscoveryCoordinator.isInFlight(path);
    }

    private static boolean isStrictSourceAuthorityManaged(String path) {
        return path != null && path.length() > 0
                && (NtkSourceSpoolRegistry.isDiscoveryActive(path)
                || NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null);
    }

    public void startNtkKpAckReadyViewerPayloadPrefetch(CustomHttpClient client,
                                                        String path,
                                                        String reason) {
        if(client == null || path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        if(!isNtkKpWebtoonEpisodePath(path))
            return;
        String startKey = path + "|kp-ack-ready-payload";
        if(NTK_KP_ACK_READY_PAYLOAD_FLIGHTS.putIfAbsent(startKey, Boolean.TRUE) != null)
            return;
        AtomicBoolean tokenPrefetchStarted = new AtomicBoolean(false);
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                String cachedBody = client.cachedNtkViewerPayloadBody(path, 120_000L);
                if(handleNtkKpAckReadyPayloadText(client, path, cachedBody,
                        tokenPrefetchStarted, "cached-before-fetch")) {
                    Log.d(TAG, "ntk_kp_ack_payload_prefetch_cached_before_fetch path=" + path
                            + ",reason=" + reason
                            + ",ms=" + (System.currentTimeMillis() - startedAt));
                    return;
                }
                boolean ready = client.hasNtkViewerImagesAckReadyForPath(path);
                Log.d(TAG, "ntk_kp_ack_payload_prefetch_ack_state path=" + path
                        + ",reason=" + reason
                        + ",ready=" + ready
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                CustomHttpClient.PageResponse page = client.runWithFetchMode(
                        CustomHttpClient.FetchMode.DIRECT_ONLY,
                        () -> client.mgetNtkViewerPayloadPage(path, 0L, partialText ->
                                handleNtkKpAckReadyPayloadText(client, path, partialText,
                                        tokenPrefetchStarted, "partial-after-ack")));
                boolean handled = handleNtkKpAckReadyPayloadText(client, path,
                        page == null ? "" : page.body, tokenPrefetchStarted, "complete-after-ack");
                if(!handled && !tokenPrefetchStarted.get()) {
                    String cachedBodyAfterFetch = client.cachedNtkViewerPayloadBody(path, 120_000L);
                    handled = handleNtkKpAckReadyPayloadText(client, path, cachedBodyAfterFetch,
                            tokenPrefetchStarted, "cached-after-fetch");
                }
                Log.d(TAG, "ntk_kp_ack_payload_prefetch_done path=" + path
                        + ",reason=" + reason
                        + ",ready=" + ready
                        + ",handled=" + handled
                        + ",started=" + tokenPrefetchStarted.get()
                        + ",code=" + (page == null ? 0 : page.code)
                        + ",bodyLen=" + (page == null || page.body == null ? 0 : page.body.length())
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                NTK_KP_ACK_READY_PAYLOAD_FLIGHTS.remove(startKey);
            } catch(Exception e) {
                NTK_KP_ACK_READY_PAYLOAD_FLIGHTS.remove(startKey);
                Log.d(TAG, "ntk_kp_ack_payload_prefetch_error path=" + path
                        + ",reason=" + reason
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-kp-ack-ready-payload");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        Log.d(TAG, "ntk_kp_ack_payload_prefetch_start path=" + path
                + ",reason=" + reason);
    }

    private boolean handleNtkKpAckReadyPayloadText(CustomHttpClient client,
                                                   String path,
                                                   String body,
                                                   AtomicBoolean tokenPrefetchStarted,
                                                   String source) {
        if(client == null || path == null || body == null || body.length() == 0
                || tokenPrefetchStarted == null)
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        if(!hasNtkViewerImageApiPayloadNormalized(normalized))
            return false;
        String token = ntkViewerImagesToken(normalized);
        if(token.length() == 0)
            return false;
        NtkBrowserSessionBroker.publishViewerPayload(path, normalized,
                "kp-ack-ready-" + source);
        List<String> partialUrls = earlyGeneratedNtkImageUrlsFromPartial(
                client, path, normalized, NTK_EARLY_INITIAL_PUBLISH_PAGES);
        if(!partialUrls.isEmpty()) {
            Log.d(TAG, "ntk_kp_ack_payload_prefetch_urls path=" + path
                    + ",source=" + source
                    + ",count=" + partialUrls.size()
                    + ",first=" + safeLogImage(partialUrls.get(0)));
            if(isCanonicalKpSignedTokenDirectSource(source))
                startFirstNtkApiImageStream(client, path, partialUrls, false);
            else
                Log.d(TAG, "ntk_kp_ack_payload_prefetch_urls_hold_visible path=" + path
                        + ",source=" + source
                        + ",count=" + partialUrls.size());
        }
        if(tokenPrefetchStarted.compareAndSet(false, true)) {
            Log.d(TAG, "ntk_kp_ack_payload_prefetch_token path=" + path
                    + ",source=" + source
                    + ",bodyLen=" + body.length()
                    + ",normalizedLen=" + normalized.length()
                    + ",tokenLen=" + token.length()
                    + ",tokenWorkId=" + ntkViewerImagesTokenField(token, "w")
                    + ",tokenEpisodeId=" + ntkViewerImagesTokenField(token, "e"));
            prefetchNtkViewerImageApiTokenCandidate(client, normalized, token, path);
        }
        return true;
    }

    private void startNtkKpSlugUnsignedViewerApiPrefetch(CustomHttpClient client, String path, String reason) {
        if(client == null || path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        if(!isNtkKpWebtoonEpisodePath(path))
            return;
        String workId = getNtkImageWorkId();
        String episodeId = getNtkImageEpisodeId();
        if(!isNumericNtkId(workId) || !isNumericNtkId(episodeId)) {
            Matcher matcher = Pattern.compile("^/webtoon/(\\d{1,12})/kp-\\d{1,12}-(\\d{1,12})(?:[/?#].*)?$",
                    Pattern.CASE_INSENSITIVE).matcher(path);
            if(matcher.find()) {
                workId = matcher.group(1);
                episodeId = matcher.group(2);
            }
        }
        if(!isNumericNtkId(workId) || !isNumericNtkId(episodeId)) {
            Log.d(TAG, "ntk_kp_unsigned_prefetch_skip path=" + path
                    + ",reason=missing_identity"
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId);
            return;
        }
        String startKey = path + "|kp-unsigned-viewer-api|" + reason;
        if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
            return;
        final String finalWorkId = workId;
        final String finalEpisodeId = episodeId;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                List<String> urls = client.fetchNtkWebtoonUnsignedViewerImageUrls(
                        path, finalWorkId, finalEpisodeId, fetchedUrls -> {
                            if(fetchedUrls != null && fetchedUrls.size() > 0) {
                                setNtkImageCount(fetchedUrls.size());
                                if(!containsNtkUploadCdnImageUrl(fetchedUrls)) {
                                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, fetchedUrls);
                                    ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                                            path,
                                            fetchedUrls,
                                            "kp-unsigned-callback-" + reason);
                                    NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                                            path,
                                            fetchedUrls,
                                            "kp-unsigned-callback-" + reason);
                                } else {
                                    Log.d(TAG, "ntk_kp_unsigned_prefetch_count_only path=" + path
                                            + ",reason=" + reason
                                            + ",count=" + fetchedUrls.size());
                                }
                                startFirstNtkApiImageStream(client, path, fetchedUrls);
                            }
                        });
                if(urls != null && urls.size() > 0) {
                    setNtkImageCount(urls.size());
                    if(!containsNtkUploadCdnImageUrl(urls)) {
                        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
                        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                                path,
                                urls,
                                "kp-unsigned-" + reason);
                        NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                                path,
                                urls,
                                "kp-unsigned-" + reason);
                    } else {
                        Log.d(TAG, "ntk_kp_unsigned_prefetch_count_only path=" + path
                                + ",reason=" + reason
                                + ",count=" + urls.size());
                    }
                    startFirstNtkApiImageStream(client, path, urls);
                }
                Log.d(TAG, "ntk_kp_unsigned_prefetch_done path=" + path
                        + ",reason=" + reason
                        + ",workId=" + finalWorkId
                        + ",episodeId=" + finalEpisodeId
                        + ",count=" + (urls == null ? 0 : urls.size())
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                firstNtkApiImageStreamStarts().remove(startKey);
                Log.d(TAG, "ntk_kp_unsigned_prefetch_error path=" + path
                        + ",reason=" + reason
                        + ",workId=" + finalWorkId
                        + ",episodeId=" + finalEpisodeId
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-kp-unsigned-prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        Log.d(TAG, "ntk_kp_unsigned_prefetch_start path=" + path
                + ",reason=" + reason
                + ",workId=" + finalWorkId
                + ",episodeId=" + finalEpisodeId);
    }

    public void startNtkKpForegroundSyntheticViewerApiPrefetch(CustomHttpClient client, String reason) {
        String path = getNtkEpisodePath();
        if(client == null || path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        if(!isNtkKpWebtoonEpisodePath(path))
            return;
        String source = reason == null || reason.length() == 0 ? "foreground" : reason;
        startNtkKpSlugSyntheticViewerApiPrefetch(client, path, source);
    }

    private void startNtkNumericSyntheticViewerApiPrefetch(CustomHttpClient client, String path,
                                                           String reason) {
        if(client == null || path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        Matcher matcher = Pattern.compile("^/(webtoon|manhwa)/(\\d{1,12})/(\\d{1,12})(?:[/?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(path);
        if(!matcher.find())
            return;
        String segment = matcher.group(1).toLowerCase(Locale.ROOT);
        String workId = matcher.group(2);
        String episodeId = matcher.group(3);
        String source = reason == null || reason.length() == 0 ? "foreground" : reason;
        String startKey = path + "|numeric-synthetic-viewer-api|" + source;
        if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
            return;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                JSONObject tokenJson = new JSONObject();
                tokenJson.put("w", workId);
                tokenJson.put("e", episodeId);
                tokenJson.put("t", segment);
                tokenJson.put("exp", System.currentTimeMillis() + 10 * 60 * 1000L);
                String token = Base64.encodeToString(
                        tokenJson.toString().getBytes(StandardCharsets.UTF_8),
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                String syntheticBody = "{\"imagesToken\":\"" + token + "\"}";
                List<String> urls = client.fetchNtkViewerImageUrls(
                        segment,
                        workId,
                        episodeId,
                        token,
                        syntheticBody,
                        path,
                        path,
                        trustedUrls -> {
                            if(trustedUrls != null && !trustedUrls.isEmpty()) {
                                setNtkImageCount(trustedUrls.size());
                                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                                        path,
                                        trustedUrls,
                                        "numeric-synthetic-token-callback-" + source);
                                ReaderImageCache.rememberTrustedNtkImageApiCount(path, trustedUrls.size());
                                NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                                        path,
                                        trustedUrls,
                                        "numeric-synthetic-token-callback-" + source);
                                startFirstNtkApiImageStream(client, path, trustedUrls, false);
                            }
                        });
                if(urls != null && !urls.isEmpty()) {
                    setNtkImageCount(urls.size());
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
                    ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                            path,
                            urls,
                            "numeric-synthetic-token-result-" + source);
                    ReaderImageCache.rememberTrustedNtkImageApiCount(path, urls.size());
                    NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                            path,
                            urls,
                            "numeric-synthetic-token-result-" + source);
                    startFirstNtkApiImageStream(client, path, urls, false);
                } else {
                    firstNtkApiImageStreamStarts().remove(startKey);
                }
                Log.d(TAG, "ntk_numeric_synthetic_token_prefetch_done path=" + path
                        + ",reason=" + source
                        + ",segment=" + segment
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",tokenLen=" + token.length()
                        + ",count=" + (urls == null ? 0 : urls.size())
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                firstNtkApiImageStreamStarts().remove(startKey);
                Log.d(TAG, "ntk_numeric_synthetic_token_prefetch_error path=" + path
                        + ",reason=" + source
                        + ",segment=" + segment
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-numeric-synthetic-token-prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        Log.d(TAG, "ntk_numeric_synthetic_token_prefetch_start path=" + path
                + ",reason=" + source
                + ",segment=" + segment
                + ",workId=" + workId
                + ",episodeId=" + episodeId);
    }

    private static boolean shouldUseNtkNumericSyntheticTokenPrefetch() {
        return false;
    }

    public void startNtkKpViewerImageScoutPrefetch(CustomHttpClient client, String reason) {
        String path = getNtkEpisodePath();
        if(client == null || path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        if(!isNtkKpWebtoonEpisodePath(path))
            return;
        Log.d(TAG, "ntk_kp_viewer_image_scout_prefetch_request path=" + path
                + ",reason=" + reason);
        startNtkViewerImageScoutPrefetch(client, path);
    }

    private static boolean containsNtkUploadCdnImageUrl(List<String> urls) {
        if(urls == null)
            return false;
        for(String url : urls) {
            String value = url == null ? "" : url.toLowerCase(Locale.ROOT);
            if(value.contains("/webtoon_uploads/")
                    || value.contains("/manhwa_uploads/")
                    || value.contains("/comic_uploads/")
                    || value.contains("/board_uploads/")
                    || value.contains("/cv/")
                    || value.contains("/mx/")
                    || value.contains("/qc/")
                    || value.contains("/rs/"))
                return true;
        }
        return false;
    }

    private static boolean containsNtkNativeUnsafeUploadPayloadUrl(List<String> urls) {
        if(urls == null)
            return false;
        for(String url : urls) {
            if(isNtkNativeUnsafeUploadPayloadUrl(url))
                return true;
        }
        return false;
    }

    private static boolean isNtkNativeUnsafeUploadPayloadUrl(String url) {
        String value = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int query = value.indexOf('?');
        if(query >= 0)
            value = value.substring(0, query);
        int hash = value.indexOf('#');
        if(hash >= 0)
            value = value.substring(0, hash);
        if(!value.matches(".*\\.(?:txt|xml|json|css|js|woff|woff2)$"))
            return false;
        if(isNtkKpCvDescriptorImageUrl(value)
                && (value.contains("/cv/") || value.contains("/mx/")
                || value.contains("/qc/") || value.contains("/rs/")))
            return true;
        return (value.contains("/webtoon_uploads/")
                || value.contains("/manhwa_uploads/")
                || value.contains("/comic_uploads/"));
    }

    private static boolean isNtkKpNativeRenderableImageUrl(String url) {
        String value = url == null ? "" : url.toLowerCase(Locale.ROOT).trim();
        if(value.length() == 0)
            return false;
        if(isNtkKpCvDescriptorImageUrl(value))
            return true;
        if(value.contains("/api/m/i?"))
            return false;
        if(isNtkNativeUnsafeUploadPayloadUrl(value))
            return false;
        return value.matches("(?s).*\\.(?:jpg|jpeg|png|webp|avif)(?:[?#].*)?$");
    }

    private static boolean isNtkKpCvDescriptorImageUrl(String url) {
        String value = url == null ? "" : url.toLowerCase(Locale.ROOT).trim();
        if(value.length() == 0)
            return false;
        return value.matches("(?i)^(?:https?:)?//[^/?#]+/.*/(?:cv|mx|qc|rs)/[^/?#]+\\.(?:txt|xml|json|css|js|woff|woff2)(?:[?#].*)?$");
    }

    private void startNtkKpSlugSyntheticViewerApiPrefetch(CustomHttpClient client, String path, String reason) {
        if(client == null || path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        if(!isNtkKpWebtoonEpisodePath(path))
            return;
        String workId = getNtkImageWorkId();
        String episodeId = getNtkImageEpisodeId();
        if(!isNumericNtkId(workId) || !isNumericNtkId(episodeId)) {
            Matcher matcher = Pattern.compile("^/webtoon/(\\d{1,12})/kp-\\d{1,12}-(\\d{1,12})(?:[/?#].*)?$",
                    Pattern.CASE_INSENSITIVE).matcher(path);
            if(matcher.find()) {
                workId = matcher.group(1);
                episodeId = matcher.group(2);
            }
        }
        if(!isNumericNtkId(workId) || !isNumericNtkId(episodeId)) {
            Log.d(TAG, "ntk_kp_synthetic_token_prefetch_skip path=" + path
                    + ",reason=missing_identity"
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId);
            return;
        }
        String startKey = path + "|kp-synthetic-viewer-api|" + reason;
        if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
            return;
        final String finalWorkId = workId;
        final String finalEpisodeId = episodeId;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                String tokenEpisodeId = "kp-" + finalWorkId + "-" + finalEpisodeId;
                JSONObject tokenJson = new JSONObject();
                tokenJson.put("w", finalWorkId);
                tokenJson.put("e", tokenEpisodeId);
                tokenJson.put("t", "webtoon");
                tokenJson.put("exp", System.currentTimeMillis() + 10 * 60 * 1000L);
                String token = Base64.encodeToString(
                        tokenJson.toString().getBytes(StandardCharsets.UTF_8),
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                String syntheticBody = "{\"imagesToken\":\"" + token + "\"}";
                List<String> urls = client.fetchNtkViewerImageUrls(
                        "webtoon",
                        finalWorkId,
                        tokenEpisodeId,
                        token,
                        syntheticBody,
                        path,
                        path,
                        trustedUrls -> {
                            if(trustedUrls != null && !trustedUrls.isEmpty()) {
                                if(trustedUrls.size() == 1) {
                                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, trustedUrls);
                                    startFirstNtkApiImageStream(client, path, trustedUrls, false);
                                } else {
                                    ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                                            path,
                                            trustedUrls,
                                            "kp-synthetic-token-callback");
                                    ReaderImageCache.rememberTrustedNtkImageApiCount(path, trustedUrls.size());
                                    NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                                            path,
                                            trustedUrls,
                                            "kp-synthetic-token-callback");
                                    List<String> head = trustedUrls.size() > NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT
                                            ? new ArrayList<>(trustedUrls.subList(0, NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT))
                                            : trustedUrls;
                                    startFirstNtkApiImageStream(client, path, head, false);
                                }
                            }
                        });
                if(urls != null && !urls.isEmpty()) {
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
                    ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                            path,
                            urls,
                            "kp-synthetic-token-result");
                    ReaderImageCache.rememberTrustedNtkImageApiCount(path, urls.size());
                    NtkBrowserSessionBroker.INSTANCE.publishAuthoritativeImageUrls(
                            path,
                            urls,
                            "kp-synthetic-token-result");
                } else {
                    firstNtkApiImageStreamStarts().remove(startKey);
                }
                if(urls != null && !urls.isEmpty()) {
                    List<String> head = urls.size() > NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT
                            ? new ArrayList<>(urls.subList(0, NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT))
                            : urls;
                    startFirstNtkApiImageStream(client, path, head, false);
                }
                Log.d(TAG, "ntk_kp_synthetic_token_prefetch_done path=" + path
                        + ",reason=" + reason
                        + ",workId=" + finalWorkId
                        + ",episodeId=" + tokenEpisodeId
                        + ",tokenLen=" + token.length()
                        + ",count=" + (urls == null ? 0 : urls.size())
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                firstNtkApiImageStreamStarts().remove(startKey);
                Log.d(TAG, "ntk_kp_synthetic_token_prefetch_error path=" + path
                        + ",reason=" + reason
                        + ",workId=" + finalWorkId
                        + ",episodeId=" + finalEpisodeId
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-kp-synthetic-token-prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        Log.d(TAG, "ntk_kp_synthetic_token_prefetch_start path=" + path
                + ",reason=" + reason
                + ",workId=" + finalWorkId
                + ",episodeId=" + finalEpisodeId);
    }

    private void startNtkViewerImageScoutPrefetch(CustomHttpClient client, String path) {
        if(client == null || path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        Matcher matcher = Pattern.compile("^/(manhwa|webtoon)/(\\d{1,12})/(?:kp-\\d{1,12}-)?(\\d{1,12})(?:[/?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(path);
        if(!matcher.find())
            return;
        String kind = matcher.group(1).toLowerCase(Locale.ROOT);
        String workId = matcher.group(2);
        String pathEpisodeId = matcher.group(3);
        String knownImageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        String episodeId = isNumericNtkId(knownImageEpisodeId)
                ? knownImageEpisodeId
                : pathEpisodeId;
        String startKey = path + "|viewer-image-scout";
        if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
            return;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                ArrayList<String> urls = NtkWebViewFallbackManager.get(MainApplication.appContext)
                        .fetchViewerImageUrls(client.agent, client.getUrl(path), path, path,
                                new java.util.HashMap<>(), kind, workId, episodeId, "",
                                client.getCookieHeader(), "");
                Log.d(TAG, "ntk_viewer_image_scout_prefetch_done path=" + path
                        + ",kind=" + kind
                        + ",count=" + (urls == null ? 0 : urls.size())
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                firstNtkApiImageStreamStarts().remove(startKey);
                Log.d(TAG, "ntk_viewer_image_scout_prefetch_error path=" + path
                        + ",kind=" + kind
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-viewer-image-scout");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        Log.d(TAG, "ntk_viewer_image_scout_prefetch_start path=" + path
                + ",kind=" + kind
                + ",workId=" + workId
                + ",episodeId=" + episodeId
                + ",pathEpisodeId=" + pathEpisodeId
                + ",knownImageEpisodeId=" + knownImageEpisodeId);
    }

    private void startNtkMetadataBackedGeneratedFirstImageStream(CustomHttpClient client, String path,
                                                                 String reason) {
        if(client == null || path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        Matcher matcher = Pattern.compile("^/(manhwa|webtoon)/(\\d{1,12})/(?:kp-\\d{1,12}-)?(\\d{1,12})(?:[/?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(path);
        if(!matcher.find())
            return;
        String segment = matcher.group(1).toLowerCase(Locale.ROOT);
        String pathWorkId = matcher.group(2);
        String pathEpisodeId = matcher.group(3);
        String imageWorkId = ntkApiEpisodeIdForPath(getNtkImageWorkId());
        if(!isNumericNtkId(imageWorkId))
            imageWorkId = pathWorkId;
        String knownImageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        String imageEpisodeId = isNumericNtkId(knownImageEpisodeId)
                ? knownImageEpisodeId
                : pathEpisodeId;
        if(!isNumericNtkId(imageWorkId) || !isNumericNtkId(imageEpisodeId))
            return;
        if("webtoon".equals(segment) && hasNtkViewerPayloadImageHints(path, 4)) {
            Log.d(TAG, "ntk_metadata_generated_first_stream_skip path=" + path
                    + ",reason=payload_direct_hint"
                    + ",workId=" + imageWorkId
                    + ",episodeId=" + imageEpisodeId);
            return;
        }
        if("webtoon".equals(segment)
                && !imageWorkId.equals(pathWorkId)
                && !isMetadataBackedInitialGeneratedCandidate(segment, imageWorkId, imageEpisodeId)) {
            Log.d(TAG, "ntk_metadata_generated_first_stream_skip path=" + path
                    + ",reason=unverified_mismatch"
                    + ",workId=" + imageWorkId
                    + ",pathWorkId=" + pathWorkId
                    + ",episodeId=" + imageEpisodeId);
            return;
        }
        String verifiedFirstExtension = cachedFreshNtkGeneratedImageExtension(
                ntkGeneratedExtensionCacheKey(segment, imageWorkId, imageEpisodeId, 1));
        boolean hasVerifiedFirstExtension = verifiedFirstExtension != null
                && verifiedFirstExtension.length() > 0;
        String firstExtension = hasVerifiedFirstExtension
                ? verifiedFirstExtension
                : unverifiedInitialNtkGeneratedExtension(segment);
        String firstImage = ntkGeneratedImageUrl(segment, imageWorkId, imageEpisodeId, 1,
                firstExtension);
        String startKey = path + "|metadata-generated-first|" + imageWorkId + "|" + imageEpisodeId;
        if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
            return;
        // A guessed suffix used to launch a second, extension-racing reader stack here. On the
        // current numeric NTK format that means real .jpeg episodes first spend connections and
        // CPU on .jpg 404s, then register the same pages again after the probe succeeds. Resolve
        // the one immutable suffix and hand the resulting whole manifest directly to the strip
        // source owner instead.
        if(!hasVerifiedFirstExtension) {
            startKnownCountNtkGeneratedManifestProbe(
                    client, path, segment, pathWorkId, pathEpisodeId, reason);
            Log.d(TAG, "ntk_metadata_generated_manifest_probe_only path=" + path
                    + ",reason=" + reason
                    + ",workId=" + imageWorkId
                    + ",episodeId=" + imageEpisodeId);
            return;
        }
        if(getNtkImageCount() > 0) {
            publishVerifiedEarlyNtkGeneratedImages(
                    client, segment, imageWorkId, imageEpisodeId,
                    verifiedFirstExtension, 1, 1);
            return;
        }
        if(isNumericNtkGeneratedEpisodePath(path)) {
            // Another Manga instance can observe the verified suffix before the title count has
            // been copied into it. It must join the manifest resolver, never resurrect the old
            // per-page stream stack merely because this local field is momentarily zero.
            startKnownCountNtkGeneratedManifestProbe(
                    client, path, segment, pathWorkId, pathEpisodeId, reason);
            return;
        }
        try {
            // Page 1 remains the extension authority. The unpublished race below may fetch the
            // rest of the launch runway early, but it never publishes a guessed suffix and its
            // extension-independent streams are superseded by the later exact manifest.
            int limit = hasVerifiedFirstExtension
                    ? Math.min(NTK_EARLY_WEBTOON_HEAD_STREAM_COUNT,
                    Math.max(1, NTK_EARLY_INITIAL_STREAM_START_COUNT))
                    : 1;
            int knownCount = getNtkImageCount();
            if(knownCount > 0)
                limit = Math.min(limit, knownCount);
            ArrayList<String> headUrls = ntkInitialGeneratedImageUrlsWithKnownExtensions(
                    segment,
                    imageWorkId,
                    imageEpisodeId,
                    firstExtension,
                    1,
                    limit,
                    false);
            if(headUrls == null || headUrls.isEmpty()) {
                headUrls = new ArrayList<>();
                headUrls.add(firstImage);
            }
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, headUrls);
            startFirstNtkApiImageStream(client, path, headUrls, false);
            if(!hasVerifiedFirstExtension && client.getContext() != null) {
                int runwayLast = knownCount > 0
                        ? Math.min(NTK_EARLY_LAUNCH_RUNWAY_RACE_PAGES, knownCount)
                        : NTK_EARLY_LAUNCH_RUNWAY_RACE_PAGES;
                int runwayStarted = ReaderImageCache.INSTANCE.startUnpublishedInitialGeneratedRunwayRace(
                        client.getContext(), this, firstImage, 2, runwayLast);
                Log.d(TAG, "ntk_metadata_generated_unpublished_runway_race path=" + path
                        + ",reason=" + reason
                        + ",range=2-" + runwayLast
                        + ",started=" + runwayStarted);
            }
            Log.d(TAG, "ntk_metadata_generated_first_stream_start path=" + path
                    + ",reason=" + reason
                    + ",segment=" + segment
                    + ",workId=" + imageWorkId
                    + ",episodeId=" + imageEpisodeId
                    + ",extensionVerified=" + hasVerifiedFirstExtension
                    + ",count=" + headUrls.size()
                    + ",image=" + safeLogImage(firstImage));
            startKnownCountNtkGeneratedManifestProbe(
                    client, path, segment, pathWorkId, pathEpisodeId, reason);
        } catch(Exception e) {
            firstNtkApiImageStreamStarts().remove(startKey);
            Log.d(TAG, "ntk_metadata_generated_first_stream_error path=" + path
                    + ",reason=" + reason
                    + ",workId=" + imageWorkId
                    + ",episodeId=" + imageEpisodeId
                    + "," + e);
        }
    }

    /**
     * Resolves the real generated-image extension while the episode list is still visible.
     *
     * A numeric episode path and a finite count supplied by the live title feed are enough to
     * identify every page once page 1 proves the CDN extension.  Starting this probe beside the
     * viewer-document request avoids waiting for the same count to be published a second time by
     * the slower signed image API.
     */
    private void startKnownCountNtkGeneratedManifestProbe(CustomHttpClient client,
                                                           String path,
                                                           String segment,
                                                           String pathWorkId,
                                                           String pathEpisodeId,
                                                           String reason) {
        int knownCount = getNtkImageCount();
        if(client == null || knownCount <= 0 || knownCount > NTK_MAX_GENERATED_PAGE_COUNT)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        if(path == null || !path.matches("(?i)^/(?:manhwa|webtoon)/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$"))
            return;
        if(!isNumericNtkId(pathWorkId) || !isNumericNtkId(pathEpisodeId))
            return;
        String startKey = path + "|known-count-generated-manifest|" + knownCount;
        if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
            return;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                String extension = reachableEarlyNtkGeneratedImageExtension(
                        client, segment, pathWorkId, pathEpisodeId, knownCount);
                if(extension.length() == 0) {
                    firstNtkApiImageStreamStarts().remove(startKey);
                    Log.d(TAG, "ntk_known_count_generated_manifest_probe_miss path=" + path
                            + ",count=" + knownCount
                            + ",reason=" + reason
                            + ",ms=" + (System.currentTimeMillis() - startedAt));
                    return;
                }
                // A cached extension returns without publishing from the resolver. Publish the
                // verified finite manifest explicitly; active stream keys make this idempotent
                // when the resolver already published it on the cold path.
                publishVerifiedEarlyNtkGeneratedImages(
                        client, segment, pathWorkId, pathEpisodeId, extension, 1, 1);
                Log.d(TAG, "ntk_known_count_generated_manifest_probe_ready path=" + path
                        + ",count=" + knownCount
                        + ",extension=" + extension
                        + ",reason=" + reason
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                firstNtkApiImageStreamStarts().remove(startKey);
                Log.d(TAG, "ntk_known_count_generated_manifest_probe_error path=" + path
                        + ",count=" + knownCount
                        + ",reason=" + reason
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-known-count-generated-manifest");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    private void startNtkSlugWebtoonShellScoutPrefetch(CustomHttpClient client, String path,
                                                       String shellHtml, String stage) {
        if(client == null || path == null || path.length() == 0
                || shellHtml == null || shellHtml.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        Matcher matcher = Pattern.compile("^/(webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return;
        String kind = matcher.group(1);
        String workId = matcher.group(2);
        String episodeId = matcher.group(3);
        String safeStage = stage == null || stage.length() == 0 ? "unknown" : stage;
        String startKey = path + "|slug-shell-scout|" + safeStage;
        if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
            return;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                ArrayList<String> urls = NtkWebViewFallbackManager.get(MainApplication.appContext)
                        .fetchViewerImageUrls(client.agent, client.getUrl(path), path, path,
                                new java.util.HashMap<>(), kind, workId, episodeId,
                                "__shell_scout__", client.getCookieHeader(), shellHtml);
                Log.d(TAG, "ntk_slug_webtoon_shell_scout_done path=" + path
                        + ",stage=" + safeStage
                        + ",count=" + (urls == null ? 0 : urls.size())
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                Log.d(TAG, "ntk_slug_webtoon_shell_scout_error path=" + path
                        + ",stage=" + safeStage
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-slug-webtoon-shell-scout");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        Log.d(TAG, "ntk_slug_webtoon_shell_scout_start path=" + path
                + ",stage=" + safeStage
                + ",htmlLen=" + shellHtml.length());
    }

    public void startNtkEarlyViewerUnsignedGeneratedPrefetch(CustomHttpClient client, String reason) {
        String path = getNtkEpisodePath();
        if(client == null || path == null || path.length() == 0 || !isNtkWebtoonEpisodePath(path)
                || !isNumericNtkGeneratedEpisodePath(path))
            return;
        if(isStrictSourceAuthorityManaged(path))
            return;
        if(hasForegroundNativeDirectManifest(path)) {
            Log.d(TAG, "ntk_early_viewer_api_unsigned_generated_skip reason=foreground_direct_manifest,path="
                    + path + ",reason=" + reason);
            return;
        }
        Thread unsignedThread = new Thread(() -> {
            long unsignedStartedAt = System.currentTimeMillis();
            try {
                String imageWorkId = getNtkImageWorkId();
                String imageEpisodeId = getNtkImageEpisodeId();
                String pathEpisodeId = ntkGeneratedEpisodeIdForPath(path);
                LinkedHashSet<String> episodeCandidates = new LinkedHashSet<>();
                if(isNumericNtkId(pathEpisodeId))
                    episodeCandidates.add(pathEpisodeId);
                if(isNumericNtkId(imageEpisodeId) && (!isNumericNtkId(pathEpisodeId)
                        || imageEpisodeId.equals(pathEpisodeId)))
                    episodeCandidates.add(imageEpisodeId);
                List<String> urls = new ArrayList<>();
                String usedEpisodeId = "";
                for(String candidateEpisodeId : episodeCandidates) {
                    urls = client.fetchNtkWebtoonUnsignedViewerImageUrls(
                            path, imageWorkId, candidateEpisodeId, fetchedUrls -> {
                                if(fetchedUrls != null && fetchedUrls.size() > 0)
                                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, fetchedUrls);
                            });
                    usedEpisodeId = candidateEpisodeId;
                    if(urls != null && !urls.isEmpty())
                        break;
                }
                Log.d(TAG, "ntk_early_viewer_api_unsigned_generated_done path=" + path
                        + ",reason=" + reason
                        + ",workId=" + imageWorkId
                        + ",episodeId=" + usedEpisodeId
                        + ",pathEpisodeId=" + pathEpisodeId
                        + ",knownEpisodeId=" + imageEpisodeId
                        + ",count=" + (urls == null ? 0 : urls.size())
                        + ",ms=" + (System.currentTimeMillis() - unsignedStartedAt));
            } catch(Exception e) {
                Log.d(TAG, "ntk_early_viewer_api_unsigned_generated_error path=" + path
                        + ",reason=" + reason
                        + ",ms=" + (System.currentTimeMillis() - unsignedStartedAt)
                        + "," + e);
            }
        }, "ntk-early-viewer-api-unsigned-generated-" + reason);
        unsignedThread.setDaemon(true);
        unsignedThread.setPriority(Thread.NORM_PRIORITY + 1);
        unsignedThread.start();
        Log.d(TAG, "ntk_early_viewer_api_unsigned_generated_start path=" + path
                + ",reason=" + reason);
    }

    private boolean shouldDelayGeneratedSlugEarlyViewerApiPrefetch(String path) {
        return false;
    }

    /**
     * Returns the exact count only when the process-wide cache owns a complete, authoritative
     * manifest for this episode.  A count disagreement is deliberately not resolved here: the
     * normal viewer payload path must arbitrate it instead of this fast path guessing a winner.
     */
    private int completeAuthoritativeNtkManifestCount(String path) {
        if(path == null || path.length() == 0)
            return 0;
        int metadataCount = Math.max(0, getNtkImageCount());
        int trustedCount = ReaderImageCache.INSTANCE.trustedNtkImageApiCount(path, 0L);
        if(metadataCount > 0 && trustedCount > 0 && metadataCount != trustedCount)
            return 0;
        int expectedCount = trustedCount > 0 ? trustedCount : metadataCount;
        if(expectedCount <= 0)
            return 0;
        List<String> urls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(path, 0L);
        if(urls == null || urls.size() != expectedCount)
            return 0;
        return ReaderImageCache.INSTANCE.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                path, expectedCount, 0L) ? expectedCount : 0;
    }

    private boolean hasCompleteAuthoritativeNtkManifest(String path) {
        return completeAuthoritativeNtkManifestCount(path) > 0;
    }

    /**
     * Installs a stable second snapshot of the authoritative manifest into this Manga.  This is
     * intentionally an exact replacement, not an append: partial candidates discovered while the
     * document request was racing cannot survive after the cache proves the complete page list.
     */
    private boolean installCompleteAuthoritativeNtkManifest(String path, Set<String> seenImages) {
        if(seenImages == null)
            return false;
        int expectedCount = completeAuthoritativeNtkManifestCount(path);
        if(expectedCount <= 0)
            return false;
        List<String> cachedUrls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(path, 0L);
        if(cachedUrls == null || cachedUrls.size() != expectedCount
                || !ReaderImageCache.INSTANCE.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                path, expectedCount, 0L))
            return false;
        ArrayList<String> exactUrls = new ArrayList<>(cachedUrls);
        LinkedHashSet<String> exactKeys = new LinkedHashSet<>();
        for(String url : exactUrls) {
            if(url == null || url.trim().length() == 0)
                return false;
            String key = ntkImageDedupKey(url);
            if(key.length() == 0 || !exactKeys.add(key))
                return false;
        }
        // Re-read after validation so a concurrent publisher cannot mix an old count with a new
        // URL list.  Equality is cheap compared with parsing the full viewer document.
        List<String> stableUrls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(path, 0L);
        if(stableUrls == null || !exactUrls.equals(stableUrls))
            return false;
        imgs = exactUrls;
        seenImages.clear();
        seenImages.addAll(exactKeys);
        setNtkImageCount(expectedCount);
        NtkGeneratedUrlIdentity generatedIdentity = ntkGeneratedUrlIdentity(exactUrls.get(0));
        if(generatedIdentity != null) {
            setNtkImageWorkId(generatedIdentity.workId);
            setNtkImageEpisodeId(generatedIdentity.episodeId);
        }
        Log.d(TAG, "ntk_authoritative_manifest_install path=" + path
                + ",count=" + expectedCount
                + ",generatedIdentity=" + (generatedIdentity != null)
                + ",first=" + safeLogImage(exactUrls.get(0)));
        return true;
    }

    private boolean hasForegroundNativeDirectManifest(String path) {
        if(path == null || path.length() == 0)
            return false;
        if(!MainApplication.isNtkForegroundViewerPath(path))
            return false;
        int expected = getNtkImageCount();
        long minCreatedAt = android.os.SystemClock.elapsedRealtime() - 30_000L;
        int trustedCount = ReaderImageCache.INSTANCE.trustedNtkImageApiCount(path, minCreatedAt);
        if(expected <= 0)
            expected = trustedCount;
        if(expected <= 0)
            return false;
        if(!ReaderImageCache.INSTANCE.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                path,
                expected,
                minCreatedAt)) {
            Log.d(TAG, "ntk_foreground_native_direct_manifest_untrusted path=" + path
                    + ",expected=" + expected);
            return false;
        }
        List<String> urls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(path, 0L);
        if(urls == null || urls.size() < expected)
            return false;
        for(int i = 0; i < expected; i++) {
            String url = urls.get(i);
            if(url == null || url.length() == 0 || url.toLowerCase(Locale.ROOT).contains("/api/m/i?"))
                return false;
        }
        return true;
    }

    private boolean hasForegroundNativeInitialRunway(String path) {
        if(path == null || path.length() == 0)
            return false;
        if(!MainApplication.isNtkForegroundViewerPath(path))
            return false;
        long minCreatedAt = android.os.SystemClock.elapsedRealtime() - 30_000L;
        List<String> urls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(path, minCreatedAt);
        if(urls == null || urls.size() < NTK_FOREGROUND_NATIVE_INITIAL_RUNWAY_API_SKIP_PAGES)
            return false;
        for(int i = 0; i < NTK_FOREGROUND_NATIVE_INITIAL_RUNWAY_API_SKIP_PAGES; i++) {
            if(!isRenderableForegroundRunwayUrl(urls.get(i)))
                return false;
        }
        return true;
    }

    private static boolean isRenderableForegroundRunwayUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        String value = url.toLowerCase(Locale.ROOT);
        if(value.contains("/api/m/i?"))
            return false;
        int query = value.indexOf('?');
        if(query >= 0)
            value = value.substring(0, query);
        int hash = value.indexOf('#');
        if(hash >= 0)
            value = value.substring(0, hash);
        return value.matches(".*\\.(?:jpg|jpeg|png|webp|gif|avif)$");
    }

    private static boolean isNtkNaverOriginalSyntheticWebtoonPath(String path) {
        if(path == null || path.length() == 0)
            return false;
        String episodeId = ntkGeneratedEpisodeIdForPath(path);
        if(episodeId == null || episodeId.length() == 0) {
            Matcher matcher = Pattern.compile("^/webtoon/[^/?#]+/([^/?#]+)(?:[/?#].*)?$").matcher(path);
            if(matcher.find())
                episodeId = matcher.group(1);
        }
        return episodeId != null
                && episodeId.matches("(?i)^(?:naver|nv)-\\d{5,}-\\d+$");
    }

    private void startNtkEarlyViewerApiCacheWatcher(CustomHttpClient client, String path,
                                                    AtomicBoolean tokenPrefetchStarted) {
        if(client == null || path == null || path.length() == 0 || tokenPrefetchStarted == null)
            return;
        Thread watcher = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            long deadline = startedAt + 12000L;
            while(System.currentTimeMillis() < deadline) {
                try {
                    String cachedBody = client.cachedNtkViewerPayloadBody(path, 120000L);
                    String normalized = normalizeNtkViewerPayloadText(cachedBody);
                    if(hasNtkViewerImageApiPayloadNormalized(normalized)) {
                        String token = ntkViewerImagesToken(normalized);
                        List<String> payloadUrls = ntkViewerPayloadImageUrls(normalized, path);
                        if(!payloadUrls.isEmpty()) {
                            if(token.length() > 0)
                                tokenPrefetchStarted.compareAndSet(false, true);
                            NtkBrowserSessionBroker.publishViewerPayload(
                                    path, normalized, "cached-payload-direct");
                            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, payloadUrls);
                            ReaderImageCache.rememberTrustedNtkImageApiCount(path, payloadUrls.size());
                            ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                                    path, payloadUrls, "cached-payload-direct");
                            CustomHttpClient.rememberCachedNtkImageIdentityFromUrls(
                                    path, ntkViewerPathSegment(path), payloadUrls, payloadUrls.size());
                            List<String> headStreamUrls = payloadUrls;
                            if(payloadUrls.size() > NTK_EARLY_PAYLOAD_HEAD_STREAM_PAGES)
                                headStreamUrls = new ArrayList<>(
                                        payloadUrls.subList(0, NTK_EARLY_PAYLOAD_HEAD_STREAM_PAGES));
                            startFirstNtkApiImageStream(client, path, headStreamUrls, false);
                            Log.d(TAG, "ntk_early_viewer_payload_urls_ready path=" + path
                                    + ",count=" + payloadUrls.size()
                                    + ",stream=" + headStreamUrls.size()
                                    + ",first=" + safeLogImage(payloadUrls.get(0))
                                    + ",ms=" + (System.currentTimeMillis() - startedAt));
                            return;
                        }
                        if(tokenPrefetchStarted.get())
                            return;
                        if(token.length() == 0)
                            return;
                        if(!tokenPrefetchStarted.compareAndSet(false, true))
                            return;
                        Log.d(TAG, "ntk_early_viewer_api_prefetch_cached path=" + path
                                + ",bodyLen=" + cachedBody.length()
                                + ",tokenLen=" + token.length()
                                + ",ms=" + (System.currentTimeMillis() - startedAt));
                        prefetchNtkViewerImageApiTokenCandidate(client, normalized, token, path);
                        return;
                    }
                    Thread.sleep(30L);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch(Exception e) {
                    Log.d(TAG, "ntk_early_viewer_api_prefetch_cached_error path=" + path
                            + ",ms=" + (System.currentTimeMillis() - startedAt)
                            + "," + e);
                    return;
                }
            }
            Log.d(TAG, "ntk_early_viewer_api_prefetch_cached_done path=" + path
                    + ",started=" + tokenPrefetchStarted.get()
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
        }, "ntk-early-viewer-api-cache-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    public void startNtkStartupImagePrewarm(CustomHttpClient client) {
        Log.d(TAG, "ntk_startup_image_prewarm_skip reason=requires_committed_click");
    }

    public static List<String> ntkViewerPayloadImageUrls(String body, String path) {
        String normalized = normalizeNtkViewerPayloadText(body);
        String token = ntkViewerImagesToken(normalized);
        return ntkViewerPayloadGeneratedImageUrls(normalized, token, path);
    }

    public static int ntkViewerPayloadPageCount(String body) {
        return ntkViewerMetaPageCount(normalizeNtkViewerPayloadText(body));
    }

    private static List<String> ntkViewerPayloadGeneratedImageUrls(String normalized,
                                                                   String token,
                                                                   String path) {
        ArrayList<String> urls = new ArrayList<>();
        if(normalized == null || normalized.length() == 0 || path == null || path.length() == 0)
            return urls;
        Matcher pathMatcher = Pattern.compile("^/(webtoon|manhwa)/([^/?#]+)/([^/?#]+)")
                .matcher(path);
        if(!pathMatcher.find())
            return urls;
        String segment = pathMatcher.group(1).toLowerCase(Locale.ROOT);
        String pathWorkId = pathMatcher.group(2);
        String pathEpisodeId = pathMatcher.group(3);
        LinkedHashSet<String> directUrls = new LinkedHashSet<>();
        String lowerNormalized = normalized.toLowerCase(Locale.ROOT);
        boolean hasProtectedImageApi = lowerNormalized.contains("imageapipath")
                && (lowerNormalized.contains("/api/webtoon-images")
                || lowerNormalized.contains("/api/manhwa-images"));
        Matcher direct = Pattern.compile(
                "(?i)https?://[^\"'<>\\\\\\s]+/(?:(?:blacktoon/episodes|black/episodes|wt/episodes|manhwa|webtoon)/[^\"'<>\\\\\\s]+/p\\d{3}|(?:webtoon_uploads|manhwa_uploads|comic_uploads|board_uploads)/[^\"'<>\\\\\\s]+|[^\"'<>\\\\\\s]*/(?:cv|mx|qc|rs)/[^\"'<>\\\\\\s]+)\\.(?:jpg|jpeg|png|webp|gif|avif)(?:[?#][^\"'<>\\\\\\s]*)?")
                .matcher(normalized);
        while(direct.find() && directUrls.size() < 512) {
            String candidate = normalizeNtkEmbeddedImageText(direct.group());
            boolean boardUpload = candidate.toLowerCase(Locale.ROOT).contains("/board_uploads/");
            if(boardUpload && (hasProtectedImageApi
                    || isNtkPageChromeBannerContext(normalized, direct.start(), direct.end())))
                continue;
            directUrls.add(candidate);
        }
        if(directUrls.size() > 1) {
            urls.addAll(directUrls);
            return urls;
        }
        return urls;
    }

    private static boolean isNtkPageChromeBannerContext(String payload, int matchStart, int matchEnd) {
        if(payload == null || payload.length() == 0)
            return false;
        int start = Math.max(0, matchStart - 420);
        int end = Math.min(payload.length(), matchEnd + 220);
        String context = payload.substring(start, end).toLowerCase(Locale.ROOT);
        return context.contains("data-banner-id")
                || context.contains("data-banner-href")
                || context.contains("thema-home-banner-button");
    }

    private static String ntkViewerPathSegment(String path) {
        if(path == null)
            return "";
        Matcher matcher = Pattern.compile("^/(webtoon|manhwa)/", Pattern.CASE_INSENSITIVE)
                .matcher(path);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private void startNtkSlugWebtoonWtBytePrefetch(CustomHttpClient client, String path) {
        if(client == null || path == null || path.length() == 0)
            return;
        Matcher matcher = Pattern.compile("^/webtoon/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$").matcher(path);
        if(!matcher.find())
            return;
        String slug = matcher.group(1);
        String episodeId = ntkGeneratedEpisodeIdForPath(path);
        if(episodeId.length() == 0)
            episodeId = matcher.group(2);
        if(slug == null || slug.length() == 0 || slug.matches("\\d+")
                || episodeId.length() == 0 || !episodeId.matches("\\d+"))
            return;
        String streamKey = path + "|slug-wt-byte-prefetch";
        if(firstNtkApiImageStreamStarts().putIfAbsent(streamKey, Boolean.TRUE) != null)
            return;
        int count = getNtkImageCount() > 0 ? getNtkImageCount() : NTK_EARLY_INITIAL_STREAM_PAGES;
        int limit = Math.max(1, Math.min(count, NTK_EARLY_INITIAL_STREAM_PAGES));
        ArrayList<String> urls = new ArrayList<>();
        for(int page = 1; page <= limit; page++) {
            urls.add("https://fifa.worldcup73.xyz/wt/episodes/" + slug + "/" + episodeId
                    + "/p" + String.format(Locale.ROOT, "%03d", page) + ".jpg");
        }
        Log.d(TAG, "ntk_slug_wt_byte_prefetch_start path=" + path
                + ",count=" + urls.size()
                + ",first=" + safeLogImage(urls.get(0)));
        try {
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
        } catch(Exception e) {
            Log.d(TAG, "ntk_slug_wt_byte_prefetch_publish_error path=" + path + "," + e);
        }
        startFirstNtkApiImageStream(client, path, urls, false);
        startNtkSlugWebtoonWtFullCountProbe(client, path, slug, episodeId, limit);
    }

    private void startNtkSlugWebtoonWtFullCountProbe(CustomHttpClient client, String path,
                                                     String slug, String episodeId,
                                                     int initialPublishedCount) {
        int knownCount = getNtkImageCount() > 0 ? getNtkImageCount() : NTK_MAX_GENERATED_PAGE_COUNT;
        int maxProbe = Math.max(initialPublishedCount, Math.min(knownCount, NTK_MAX_GENERATED_PAGE_COUNT));
        if(maxProbe <= initialPublishedCount)
            return;
        String probeKey = path + "|slug-wt-full-count-probe|" + maxProbe;
        if(firstNtkApiImageStreamStarts().putIfAbsent(probeKey, Boolean.TRUE) != null)
            return;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                int actualCount = discoverNtkSlugWebtoonWtImageCount(client, slug, episodeId,
                        initialPublishedCount, maxProbe);
                if(actualCount <= initialPublishedCount) {
                    Log.d(TAG, "ntk_slug_wt_full_count_probe_skip path=" + path
                            + ",initial=" + initialPublishedCount
                            + ",actual=" + actualCount
                            + ",max=" + maxProbe
                            + ",ms=" + (System.currentTimeMillis() - startedAt));
                    return;
                }
                ArrayList<String> fullUrls = new ArrayList<>();
                for(int page = 1; page <= actualCount; page++) {
                    fullUrls.add("https://fifa.worldcup73.xyz/wt/episodes/" + slug + "/" + episodeId
                            + "/p" + String.format(Locale.ROOT, "%03d", page) + ".jpg");
                }
                setNtkImageCount(actualCount);
                ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, fullUrls);
                startFirstNtkApiImageStream(client, path, fullUrls);
                Log.d(TAG, "ntk_slug_wt_full_count_probe_publish path=" + path
                        + ",count=" + actualCount
                        + ",initial=" + initialPublishedCount
                        + ",max=" + maxProbe
                        + ",first=" + safeLogImage(fullUrls.get(0))
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                firstNtkApiImageStreamStarts().remove(probeKey);
                Log.d(TAG, "ntk_slug_wt_full_count_probe_error path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-slug-wt-full-count-probe");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    private int discoverNtkSlugWebtoonWtImageCount(CustomHttpClient client, String slug,
                                                   String episodeId, int knownReachable,
                                                   int maxProbe) {
        int low = Math.max(0, knownReachable);
        int high = Math.max(low, maxProbe);
        AtomicBoolean stopSignal = new AtomicBoolean(false);
        String highUrl = "https://fifa.worldcup73.xyz/wt/episodes/" + slug + "/" + episodeId
                + "/p" + String.format(Locale.ROOT, "%03d", high) + ".jpg";
        if(isNtkGeneratedImageReachableFast(client, highUrl, stopSignal))
            return high;
        int left = low + 1;
        int right = high - 1;
        int lastReachable = low;
        while(left <= right) {
            int mid = (left + right) / 2;
            AtomicBoolean midStop = new AtomicBoolean(false);
            String probe = "https://fifa.worldcup73.xyz/wt/episodes/" + slug + "/" + episodeId
                    + "/p" + String.format(Locale.ROOT, "%03d", mid) + ".jpg";
            if(isNtkGeneratedImageReachableFast(client, probe, midStop)) {
                lastReachable = mid;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return lastReachable;
    }

    private boolean startNtkKnownSlugWebtoonImageApiPrefetch(CustomHttpClient client, String path,
                                                             String stage) {
        return startNtkKnownSlugWebtoonImageApiPrefetch(client, path, null, stage);
    }

    private boolean startNtkKnownSlugWebtoonImageApiPrefetch(CustomHttpClient client, String path,
                                                             AtomicBoolean tokenPrefetchStarted,
                                                             String stage) {
        if(client == null || path == null || path.length() == 0)
            return false;
        if(isStrictSourceAuthorityManaged(path))
            return false;
        Matcher matcher = Pattern.compile("^/webtoon/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(path);
        if(!matcher.find())
            return false;
        String pathWorkId = matcher.group(1);
        String pathEpisodeId = matcher.group(2);
        boolean slugWebtoon = !pathWorkId.matches("\\d{1,12}")
                || !pathEpisodeId.matches("\\d{1,12}");
        if(!slugWebtoon)
            return false;
        String imageWorkId = pathWorkId.matches("\\d{1,12}")
                ? pathWorkId
                : ntkApiEpisodeIdForPath(getNtkImageWorkId());
        String imageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        String pathApiEpisodeId = ntkApiEpisodeIdForPath(pathEpisodeId);
        if(imageEpisodeId.length() == 0 && pathApiEpisodeId.length() > 0)
            imageEpisodeId = pathApiEpisodeId;
        if(imageEpisodeId.length() == 0 && pathEpisodeId.matches("\\d{1,12}"))
            imageEpisodeId = pathEpisodeId;
        if(imageEpisodeId.length() == 0 && pathEpisodeId.length() > 0)
            imageEpisodeId = pathEpisodeId;
        String safeStage = stage == null ? "" : stage;
        if(!imageWorkId.matches("\\d{1,12}") || imageEpisodeId.length() == 0) {
            Log.d(TAG, "ntk_early_viewer_api_identity_skip reason=missing_numeric_identity"
                    + ",stage=" + safeStage
                    + ",path=" + path
                    + ",imageWorkId=" + imageWorkId
                    + ",imageEpisodeId=" + imageEpisodeId);
            return false;
        }
        String startKey = path + "|known-slug-webtoon-image-api|" + imageWorkId + "|" + imageEpisodeId;
        if(NTK_KNOWN_SLUG_WEBTOON_API_FLIGHTS.putIfAbsent(startKey, Boolean.TRUE) != null) {
            Log.d(TAG, "ntk_early_viewer_api_identity_skip reason=already_started"
                    + ",stage=" + safeStage
                    + ",path=" + path
                    + ",workId=" + imageWorkId
                    + ",episodeId=" + imageEpisodeId);
            return false;
        }
        String safeWorkId = imageWorkId;
        String safeEpisodeId = imageEpisodeId;
        AtomicBoolean delivered = new AtomicBoolean(false);
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                CustomHttpClient.NtkViewerImageUrlsCallback callback = trustedUrls -> {
                    if(trustedUrls == null || trustedUrls.isEmpty())
                        return;
                    if(delivered.compareAndSet(false, true)) {
                        if(tokenPrefetchStarted != null)
                            tokenPrefetchStarted.compareAndSet(false, true);
                        Log.d(TAG, "ntk_early_viewer_api_identity_first_urls"
                                + ",stage=" + safeStage
                                + ",path=" + path
                                + ",workId=" + safeWorkId
                                + ",episodeId=" + safeEpisodeId
                                + ",count=" + trustedUrls.size()
                                + ",first=" + safeLogImage(trustedUrls.get(0))
                                + ",ms=" + (System.currentTimeMillis() - startedAt));
                    }
                    startFirstNtkApiImageStream(client, path, trustedUrls);
                };
                List<String> urls = client.fetchNtkWebtoonUnsignedViewerImageUrls(
                        path, safeWorkId, safeEpisodeId, callback);
                if(urls != null && !urls.isEmpty()) {
                    if(delivered.compareAndSet(false, true)
                            && tokenPrefetchStarted != null)
                        tokenPrefetchStarted.compareAndSet(false, true);
                    setNtkImageCount(urls.size());
                    startFirstNtkApiImageStream(client, path, urls);
                    Log.d(TAG, "ntk_early_viewer_api_identity_done"
                            + ",stage=" + safeStage
                            + ",path=" + path
                            + ",workId=" + safeWorkId
                            + ",episodeId=" + safeEpisodeId
                            + ",count=" + urls.size()
                            + ",first=" + safeLogImage(urls.get(0))
                            + ",ms=" + (System.currentTimeMillis() - startedAt));
                } else {
                    NTK_KNOWN_SLUG_WEBTOON_API_FLIGHTS.remove(startKey);
                    Log.d(TAG, "ntk_early_viewer_api_identity_miss"
                            + ",stage=" + safeStage
                            + ",path=" + path
                            + ",workId=" + safeWorkId
                            + ",episodeId=" + safeEpisodeId
                            + ",ms=" + (System.currentTimeMillis() - startedAt));
                }
            } catch(Exception e) {
                NTK_KNOWN_SLUG_WEBTOON_API_FLIGHTS.remove(startKey);
                Log.d(TAG, "ntk_early_viewer_api_identity_error"
                        + ",stage=" + safeStage
                        + ",path=" + path
                        + ",workId=" + safeWorkId
                        + ",episodeId=" + safeEpisodeId
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-known-slug-webtoon-image-api");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        Log.d(TAG, "ntk_early_viewer_api_identity_start"
                + ",stage=" + safeStage
                + ",path=" + path
                + ",workId=" + safeWorkId
                + ",episodeId=" + safeEpisodeId);
        return true;
    }

    private void startNtkVerifiedSlugWebtoonInitialImageProbe(CustomHttpClient client, String path) {
        Matcher pathMatcher = Pattern.compile("^/webtoon/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$").matcher(path);
        if(!pathMatcher.find())
            return;
        String slug = pathMatcher.group(1);
        String episodeId = ntkGeneratedEpisodeIdForPath(path);
        if(episodeId.length() == 0)
            episodeId = pathMatcher.group(2);
        if(slug == null || slug.length() == 0 || slug.matches("\\d+")
                || episodeId.length() == 0 || isNtkKpEpisodeId(episodeId))
            return;
        int pageCount = getNtkImageCount();
        if(pageCount <= 0)
            pageCount = ntkGeneratedImageCandidateCount();
        final LinkedHashSet<String> probeSlugs = ntkSlugWebtoonWorkCandidates(slug);
        final String probeEpisodeId = episodeId;
        final int probePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        String streamKey = path + "|verified-slug-path-probe";
        if(firstNtkApiImageStreamStarts().putIfAbsent(streamKey, Boolean.TRUE) != null)
            return;
        Thread thread = new Thread(() -> {
            try {
                for(String probeSlug : probeSlugs) {
                    String extension = reachableNtkSlugWebtoonImageExtension(client, probeSlug, probeEpisodeId, 1);
                    if(extension.length() == 0)
                        continue;
                    ArrayList<String> urls = new ArrayList<>();
                    int limit = Math.min(NTK_EARLY_INITIAL_STREAM_PAGES, probePageCount);
                    for(int page = 1; page <= limit; page++)
                        urls.add(ntkSlugWebtoonImageUrl(probeSlug, probeEpisodeId, page, extension));
                    if(urls.isEmpty())
                        return;
                    Log.d(TAG, "ntk_slug_direct_image_url_path_probe path=" + path
                            + ",slug=" + probeSlug
                            + ",candidates=" + probeSlugs
                            + ",episodeId=" + probeEpisodeId
                            + ",count=" + urls.size()
                            + ",extension=" + extension
                            + ",first=" + safeLogImage(urls.get(0)));
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
                    startFirstNtkApiImageStream(client, path, urls);
                    return;
                }
            } catch(Exception e) {
                Log.d(TAG, "ntk_slug_direct_image_url_path_probe_error path=" + path + "," + e);
            }
        }, "ntk-slug-generated-path-initial");
        thread.setDaemon(true);
        thread.start();
    }

    private LinkedHashSet<String> ntkSlugWebtoonWorkCandidates(String slug) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addNtkSlugCandidate(candidates, slug);
        addNtkEpisodeCandidate(candidates, getNtkImageWorkId());
        if(slug != null) {
            Matcher withoutHash = Pattern.compile("(?i)^(.+)-[0-9a-f]{6,12}$").matcher(slug);
            if(withoutHash.find()) {
                String baseSlug = withoutHash.group(1);
                addNtkSlugCandidate(candidates, baseSlug);
                if(baseSlug.startsWith("u-"))
                    addNtkSlugCandidate(candidates, baseSlug.substring(2));
            }
            if(slug.startsWith("u-"))
                addNtkSlugCandidate(candidates, slug.substring(2));
        }
        return candidates;
    }

    private ArrayList<String> ntkInitialGeneratedImageUrls(String segment, String workId,
                                                           String episodeId, String extension) {
        ArrayList<String> urls = new ArrayList<>();
        String safeExtension = normalizeNtkGeneratedImageExtension(extension);
        if(segment == null || workId == null || episodeId == null || safeExtension.length() == 0)
            return urls;
        int limit = NTK_EARLY_INITIAL_STREAM_PAGES;
        int knownCount = getNtkImageCount();
        if(knownCount > 0)
            limit = Math.min(limit, knownCount);
        int startPage = ntkInitialGeneratedStartPage(segment, workId, episodeId, safeExtension);
        for(int page = startPage; page <= limit; page++)
            urls.add(ntkGeneratedImageUrl(segment, workId, episodeId, page, safeExtension));
        return urls;
    }

    private List<String> earlyGeneratedNtkImageUrlsFromPartial(CustomHttpClient client, String path,
                                                              String partialText, int limit) {
        ArrayList<String> urls = new ArrayList<>();
        if(client == null || path == null || partialText == null || limit <= 0)
            return urls;
        if(shouldUseProtectedNtkViewerApi(client, path)) {
            Log.d(TAG, "ntk_generated_partial_skip_protected_api path=" + path);
            return urls;
        }
        long minTrustedAt = android.os.SystemClock.elapsedRealtime() - 30_000L;
        int recentTrustedCount = ReaderImageCache.trustedNtkImageApiCount(path, minTrustedAt);
        if(isNtkKpWebtoonEpisodePath(path) && recentTrustedCount > 0 && recentTrustedCount < 8) {
            Log.d(TAG, "ntk_generated_partial_ignore_small_kp_trusted path=" + path
                    + ",trusted=" + recentTrustedCount);
            recentTrustedCount = 0;
        }
        if(recentTrustedCount > 0) {
            List<String> cachedTrustedUrls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(
                    path, minTrustedAt);
            if(cachedTrustedUrls != null && cachedTrustedUrls.size() >= recentTrustedCount) {
                Log.d(TAG, "ntk_generated_partial_skip_cached_trusted path=" + path
                        + ",count=" + cachedTrustedUrls.size()
                        + ",trusted=" + recentTrustedCount);
                return new ArrayList<>(cachedTrustedUrls);
            }
        }
        String normalized = normalizeNtkViewerPayloadText(partialText);
        int previousPageCount = getNtkImageCount();
        int parsedPageCount = ntkViewerMetaPageCount(normalized);
        int pageCount = parsedPageCount;
        if(pageCount <= 0)
            pageCount = previousPageCount;
        if(pageCount <= 0)
            pageCount = ntkGeneratedImageCandidateCount();
        if(pageCount > 0) {
            if(previousPageCount > 0
                    && previousPageCount != NTK_DEFAULT_GENERATED_PAGE_COUNT
                    && previousPageCount != pageCount) {
                Log.d(TAG, "ntk_generated_partial_preserve_known_count path=" + path
                        + ",known=" + previousPageCount
                        + ",partial=" + parsedPageCount
                        + ",using=" + previousPageCount);
                pageCount = previousPageCount;
            } else if(pageCount > previousPageCount || previousPageCount <= 0
                    || previousPageCount == NTK_DEFAULT_GENERATED_PAGE_COUNT) {
                setNtkImageCount(pageCount);
            }
        }
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return urls;
        String segment = pathMatcher.group(1);
        String pathWorkId = pathMatcher.group(2);
        String pathEpisodeId = pathMatcher.group(3);
        String token = ntkViewerImagesToken(normalized);
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String thumbWorkId = ntkViewerThumbWorkId(normalized);
        String sourceWorkId = ntkViewerSourceWorkId(normalized);
        LinkedHashSet<String> workIds = new LinkedHashSet<>();
        List<String> verifiedApiUrlsForPath = ReaderImageCache.INSTANCE.cachedNtkApiFallbackImages(path);
        int verifiedApiCountForPath = verifiedApiUrlsForPath == null ? 0 : verifiedApiUrlsForPath.size();
        if(verifiedApiCountForPath > 0 && pageCount > verifiedApiCountForPath) {
            Log.d(TAG, "ntk_generated_partial_clamp_to_verified_api path=" + path
                    + ",pageCount=" + pageCount
                    + ",verified=" + verifiedApiCountForPath);
            pageCount = verifiedApiCountForPath;
            setNtkImageCount(pageCount);
        }
        if(shouldPreferNtkApiForCanonicalWebtoonPath(path)) {
            if(shouldUseNtkThumbWorkIdForCanonicalGenerated(pathWorkId, tokenWorkId, sourceWorkId, thumbWorkId))
                addNtkCandidateIfNumeric(workIds, thumbWorkId);
            addNtkCandidateIfNumeric(workIds, tokenWorkId);
            addNtkCandidateIfNumeric(workIds, sourceWorkId);
            addNtkCandidateIfNumeric(workIds, pathWorkId);
        } else {
            addNtkCandidateIfNumeric(workIds, tokenWorkId);
            addNtkCandidateIfNumeric(workIds, thumbWorkId);
            addNtkCandidateIfNumeric(workIds, sourceWorkId);
            addNtkCandidateIfNumeric(workIds, pathWorkId);
        }
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        List<String> naverWebtoonUrls = fetchNaverWebtoonImageUrlsForNvEpisode(
                client, tokenEpisodeId, pathEpisodeId, limit);
        if(!naverWebtoonUrls.isEmpty()) {
            Log.d(TAG, "ntk_generated_partial_naver_original path=" + path
                    + ",count=" + naverWebtoonUrls.size()
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",first=" + safeLogImage(naverWebtoonUrls.get(0)));
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, naverWebtoonUrls);
            return naverWebtoonUrls;
        }
        List<String> payloadDirectUrls = ntkViewerPayloadImageUrls(normalized, path);
        if(payloadDirectUrls != null && !payloadDirectUrls.isEmpty()) {
            int directExpectedCount = pageCount;
            if(verifiedApiCountForPath > 0 && verifiedApiCountForPath < payloadDirectUrls.size())
                directExpectedCount = verifiedApiCountForPath;
            else if(previousPageCount > 0 && previousPageCount != NTK_DEFAULT_GENERATED_PAGE_COUNT)
                directExpectedCount = previousPageCount;
            int directLimit = directExpectedCount > 0
                    ? Math.min(payloadDirectUrls.size(), Math.min(directExpectedCount, NTK_MAX_GENERATED_PAGE_COUNT))
                    : Math.min(payloadDirectUrls.size(), NTK_MAX_GENERATED_PAGE_COUNT);
            ArrayList<String> directUrls = new ArrayList<>(payloadDirectUrls.subList(0, directLimit));
            if(!directUrls.isEmpty()) {
                ArrayList<String> earlyUrls = directUrls;
                if(pageCount > directUrls.size()) {
                    earlyUrls = appendMetadataBackedGeneratedTailUrls(path, segment,
                            pathWorkId, pathEpisodeId, tokenWorkId, thumbWorkId, sourceWorkId,
                            embeddedEpisodeId, tokenEpisodeId, directUrls, pageCount);
                }
                if(pageCount > 0 && pageCount >= earlyUrls.size())
                    setNtkImageCount(pageCount);
                else if(pageCount <= 0 || getNtkImageCount() <= 0)
                    setNtkImageCount(earlyUrls.size());
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        directUrls,
                        "payload-direct-urls");
                if(earlyUrls.size() > directUrls.size())
                    ReaderImageCache.rememberMetadataBackedTailEarlyNtkImageUrls(path, earlyUrls);
                else
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, earlyUrls);
                Log.d(TAG, "ntk_generated_partial_payload_direct_urls path=" + path
                        + ",count=" + directUrls.size()
                        + ",early=" + earlyUrls.size()
                        + ",pageCount=" + pageCount
                        + ",first=" + safeLogImage(directUrls.get(0)));
                return earlyUrls;
            }
        }
        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        String generatedKnownEpisodeId = ntkKnownImageEpisodeIdForGeneratedCandidate(tokenEpisodeId, pathEpisodeId);
        if(shouldPreferNtkApiForCanonicalWebtoonPath(path)) {
            addNtkCandidateIfNumeric(episodeIds, tokenEpisodeId);
            addNtkCandidateIfNumeric(episodeIds, generatedKnownEpisodeId);
            addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
            addNtkCandidateIfNumeric(episodeIds,
                    ntkMetadataImageEpisodeIdForGeneratedCandidate(embeddedEpisodeId, tokenEpisodeId, pathEpisodeId));
        } else {
            addNtkCandidateIfNumeric(episodeIds, tokenEpisodeId);
            addNtkCandidateIfNumeric(episodeIds, generatedKnownEpisodeId);
            addNtkCandidateIfNumeric(episodeIds,
                    ntkMetadataImageEpisodeIdForGeneratedCandidate(embeddedEpisodeId, tokenEpisodeId, pathEpisodeId));
            addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
        }
        for(String episodeId : episodeIds)
            addNtkGeneratedWorkIdsFromText(workIds, normalized, episodeId);
        Log.d(TAG, "ntk_generated_partial_candidates path=" + path
                + ",pageCount=" + pageCount
                + ",tokenWorkId=" + tokenWorkId
                + ",thumbWorkId=" + thumbWorkId
                + ",sourceWorkId=" + sourceWorkId
                + ",pathWorkId=" + pathWorkId
                + ",tokenEpisodeId=" + tokenEpisodeId
                + ",knownEpisodeId=" + getNtkImageEpisodeId()
                + ",embeddedEpisodeId=" + embeddedEpisodeId
                + ",pathEpisodeId=" + pathEpisodeId
                + ",workIds=" + workIds
                + ",episodeIds=" + episodeIds
                + ",srcSnippet=" + ntkViewerSnippetAround(normalized, "\"src\"", 180)
                + ",metaSnippet=" + ntkViewerSnippetAround(normalized, "\"imageMetas\"", 220));
        boolean canonicalWebtoon = shouldPreferNtkApiForCanonicalWebtoonPath(path);
        if("webtoon".equals(segment) && pathWorkId.matches("\\d{1,12}") && !canonicalWebtoon) {
            List<String> workTitleWtUrls = earlyWorkTitleWtImageUrlsFromPartial(
                    client, path, normalized, pathEpisodeId, tokenEpisodeId, pageCount,
                    NTK_MAX_GENERATED_PAGE_COUNT);
            if(!workTitleWtUrls.isEmpty())
                return workTitleWtUrls;
        }
        if(isNtkSyntheticWebtoonEpisodePath(path)
                && tokenEpisodeId.length() > 0
                && !isNumericNtkId(tokenEpisodeId)
                && hasNtkViewerImageApiPayloadNormalized(normalized)
                && !hasNtkPageImageInText(normalized)) {
            List<String> slugUrls = earlySlugWebtoonImageUrlsFromPartial(client, path,
                    pathWorkId, tokenEpisodeId, pathEpisodeId, pageCount, limit);
            if(!slugUrls.isEmpty()) {
                Log.d(TAG, "ntk_generated_partial_slug_verified path=" + path
                        + ",count=" + slugUrls.size()
                        + ",slug=" + pathWorkId
                        + ",episodeId=" + tokenEpisodeId
                        + ",first=" + safeLogImage(slugUrls.get(0)));
                ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, slugUrls);
                return slugUrls;
            }
            Log.d(TAG, "ntk_generated_partial_skip_api_only_slug path=" + path
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",workIds=" + workIds
                    + ",episodeIds=" + episodeIds);
            if(!isNtkKpEpisodeId(tokenEpisodeId) || workIds.isEmpty() || episodeIds.isEmpty())
                return urls;
            Log.d(TAG, "ntk_generated_partial_kp_continue_direct_probe path=" + path
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",workIds=" + workIds
                    + ",episodeIds=" + episodeIds);
            String kpWorkId = firstNumericNtkCandidate(workIds);
            String kpEpisodeId = firstNumericNtkCandidate(episodeIds);
            int kpLimit = Math.min(Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT), limit);
            if(isNumericNtkId(kpWorkId) && isNumericNtkId(kpEpisodeId) && kpLimit > 0) {
                Log.d(TAG, "ntk_generated_partial_kp_direct_jpg_manifest_skip path=" + path
                        + ",reason=unverified_404_prone,use_api_hash_stream"
                        + ",pageCount=" + pageCount
                        + ",workId=" + kpWorkId
                        + ",episodeId=" + kpEpisodeId);
                return urls;
            }
        }
        if(workIds.isEmpty() || episodeIds.isEmpty())
            return urls;
        int safeLimit = Math.min(Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT), limit);
        if(!canonicalWebtoon && isNumericNtkId(tokenWorkId) && isNumericNtkId(tokenEpisodeId)) {
            String cachedExtension = cachedFreshNtkGeneratedImageExtension(
                    ntkGeneratedExtensionCacheKey(segment, tokenWorkId, tokenEpisodeId, 1));
            if(cachedExtension != null && cachedExtension.length() > 0) {
                ArrayList<String> speculativeUrls = ntkInitialGeneratedImageUrlsWithKnownExtensions(
                        segment, tokenWorkId, tokenEpisodeId, cachedExtension, 1, safeLimit, false);
                if(!speculativeUrls.isEmpty()) {
                    Log.d(TAG, "ntk_generated_speculative_urls_early path=" + path
                            + ",count=" + speculativeUrls.size()
                            + ",extension=" + cachedExtension
                            + ",workId=" + tokenWorkId
                            + ",episodeId=" + tokenEpisodeId
                            + ",first=" + safeLogImage(speculativeUrls.get(0)));
                    try {
                        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, speculativeUrls);
                    } catch(Exception e) {
                        Log.d(TAG, "ntk_generated_speculative_urls_remember_error path=" + path + "," + e);
                    }
                    startFirstNtkApiImageStream(client, path, speculativeUrls);
                }
            } else {
                Log.d(TAG, "ntk_generated_speculative_urls_skip_unverified_extension path=" + path
                        + ",workId=" + tokenWorkId
                        + ",episodeId=" + tokenEpisodeId);
            }
        } else if(canonicalWebtoon) {
            String speculativeWorkId = firstNumericNtkCandidate(workIds);
            String speculativeEpisodeId = firstNumericNtkCandidate(episodeIds);
            if(isNumericNtkId(speculativeWorkId) && isNumericNtkId(speculativeEpisodeId)) {
                String verifiedExtension = cachedFreshNtkGeneratedImageExtension(
                        ntkGeneratedExtensionCacheKey(segment, speculativeWorkId, speculativeEpisodeId, 1));
                if(verifiedExtension != null && verifiedExtension.length() > 0) {
                    ArrayList<String> speculativeUrls = ntkInitialGeneratedImageUrlsWithKnownExtensions(
                            segment, speculativeWorkId, speculativeEpisodeId, verifiedExtension, 1, safeLimit, false);
                    if(!speculativeUrls.isEmpty()) {
                        Log.d(TAG, "ntk_generated_speculative_urls_canonical_verified path=" + path
                                + ",count=" + speculativeUrls.size()
                                + ",extension=" + verifiedExtension
                                + ",workId=" + speculativeWorkId
                                + ",episodeId=" + speculativeEpisodeId
                                + ",first=" + safeLogImage(speculativeUrls.get(0)));
                        try {
                            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, speculativeUrls);
                        } catch(Exception e) {
                            Log.d(TAG, "ntk_generated_speculative_urls_canonical_remember_error path=" + path + "," + e);
                        }
                        return speculativeUrls;
                    }
                }
            }
            Log.d(TAG, "ntk_generated_speculative_urls_skip_canonical path=" + path
                    + ",tokenWorkId=" + tokenWorkId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",reason=await_verified_extension");
        }
        for(String workId : workIds) {
            for(String episodeId : episodeIds) {
                String extension = reachableEarlyNtkGeneratedImageExtension(client, segment, workId,
                        episodeId, pageCount);
                if(extension.length() == 0)
                    continue;
                Log.d(TAG, "ntk_generated_direct_extension_identity path=" + path
                        + ",segment=" + segment
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",extension=" + extension
                        + ",thumbWorkId=" + thumbWorkId
                        + ",sourceWorkId=" + sourceWorkId
                        + ",tokenWorkId=" + tokenWorkId);
                int startPage = ntkInitialGeneratedStartPage(segment, workId, episodeId, extension);
                startAsyncNtkGeneratedInitialExtensionValidation(client, segment, workId,
                        episodeId, extension, safeLimit);
                urls.addAll(ntkInitialGeneratedImageUrlsWithKnownExtensions(segment, workId,
                        episodeId, extension, startPage, safeLimit));
                return urls;
            }
        }
        return urls;
    }

    private List<String> earlySlugWebtoonImageUrlsFromPartial(CustomHttpClient client, String path,
                                                              String pathWorkId,
                                                              String tokenEpisodeId,
                                                              String pathEpisodeId,
                                                              int pageCount, int limit) {
        ArrayList<String> urls = new ArrayList<>();
        if(isNtkSyntheticWebtoonEpisodePath(path)) {
            Log.d(TAG, "ntk_generated_partial_skip_slug_probe_api_only path=" + path
                    + ",tokenEpisodeId=" + tokenEpisodeId);
            return urls;
        }
        if(client == null || path == null || pathWorkId == null || pathWorkId.length() == 0
                || pathWorkId.matches("\\d+") || pageCount <= 0 || limit <= 0)
            return urls;
        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        addNtkEpisodeCandidate(episodeIds, tokenEpisodeId);
        addNtkEpisodeCandidate(episodeIds, pathEpisodeId);
        addNtkEpisodeCandidate(episodeIds,
                ntkKnownImageEpisodeIdForGeneratedCandidate(tokenEpisodeId, pathEpisodeId));
        int safeLimit = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        for(String episodeId : episodeIds) {
            if(episodeId == null || episodeId.length() == 0 || isNtkKpEpisodeId(episodeId))
                continue;
            String extension = reachableNtkSlugWebtoonImageExtension(client, pathWorkId, episodeId, 1);
            if(extension.length() == 0)
                continue;
            int validationPageCount = Math.min(ntkGeneratedInitialValidationPageCount(pageCount), safeLimit);
            boolean valid = true;
            for(int page = 2; page <= validationPageCount; page++) {
                if(!isNtkGeneratedImageReachable(client,
                        ntkSlugWebtoonImageUrl(pathWorkId, episodeId, page, extension))) {
                    valid = false;
                    break;
                }
            }
            if(!valid)
                continue;
            for(int page = 1; page <= safeLimit; page++)
                urls.add(ntkSlugWebtoonImageUrl(pathWorkId, episodeId, page, extension));
            return urls;
        }
        return urls;
    }

    private ArrayList<String> appendMetadataBackedGeneratedTailUrls(String path, String segment,
                                                                    String pathWorkId,
                                                                    String pathEpisodeId,
                                                                    String tokenWorkId,
                                                                    String thumbWorkId,
                                                                    String sourceWorkId,
                                                                    String embeddedEpisodeId,
                                                                    String tokenEpisodeId,
                                                                    ArrayList<String> directUrls,
                                                                    int pageCount) {
        ArrayList<String> urls = new ArrayList<>(directUrls == null ? new ArrayList<>() : directUrls);
        if(path == null || segment == null || pageCount <= urls.size()
                || pageCount > NTK_MAX_GENERATED_PAGE_COUNT)
            return urls;
        if(!"webtoon".equalsIgnoreCase(segment) && !"manhwa".equalsIgnoreCase(segment))
            return urls;
        LinkedHashSet<String> workIds = new LinkedHashSet<>();
        addNtkCandidateIfNumeric(workIds, getNtkImageWorkId());
        addNtkCandidateIfNumeric(workIds, thumbWorkId);
        addNtkCandidateIfNumeric(workIds, sourceWorkId);
        if(tokenWorkId == null || !tokenWorkId.equals(pathWorkId))
            addNtkCandidateIfNumeric(workIds, tokenWorkId);
        addNtkCandidateIfNumeric(workIds, pathWorkId);
        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        addNtkCandidateIfNumeric(episodeIds, getNtkImageEpisodeId());
        addNtkCandidateIfNumeric(episodeIds, tokenEpisodeId);
        addNtkCandidateIfNumeric(episodeIds,
                ntkMetadataImageEpisodeIdForGeneratedCandidate(embeddedEpisodeId, tokenEpisodeId, pathEpisodeId));
        addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
        String workId = firstNumericNtkCandidate(workIds);
        String episodeId = firstNumericNtkCandidate(episodeIds);
        if(workId.length() == 0 || episodeId.length() == 0)
            return urls;
        String extension = "webtoon".equalsIgnoreCase(segment) ? "jpeg" : "jpg";
        int fromPage = urls.size() + 1;
        for(int page = fromPage; page <= pageCount; page++)
            urls.add(ntkGeneratedImageUrl(segment, workId, episodeId, page, extension));
        Log.d(TAG, "ntk_generated_payload_direct_tail_expand path=" + path
                + ",from=" + directUrls.size()
                + ",to=" + urls.size()
                + ",pageCount=" + pageCount
                + ",workId=" + workId
                + ",episodeId=" + episodeId
                + ",extension=" + extension);
        return urls;
    }

    private int ntkInitialGeneratedStartPage(String segment, String workId, String episodeId,
                                             String extension) {
        String safeExtension = normalizeNtkGeneratedImageExtension(extension);
        if(segment == null || workId == null || episodeId == null || safeExtension.length() == 0)
            return 1;
        String pageOne = cachedFreshNtkGeneratedImageExtension(
                ntkGeneratedExtensionCacheKey(segment, workId, episodeId, 1));
        String pageTwo = cachedFreshNtkGeneratedImageExtension(
                ntkGeneratedExtensionCacheKey(segment, workId, episodeId, 2));
        if("".equals(pageOne) && safeExtension.equals(pageTwo)) {
            Log.d(TAG, "ntk_generated_initial_start_page_skip_unreachable_page1 segment=" + segment
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId
                    + ",extension=" + safeExtension);
            return 2;
        }
        return 1;
    }

    private String reachableEarlyNtkGeneratedImageExtension(CustomHttpClient client, String segment,
                                                            String workId, String episodeId) {
        return reachableEarlyNtkGeneratedImageExtension(client, segment, workId, episodeId, 0);
    }

    private String reachableEarlyNtkGeneratedImageExtension(CustomHttpClient client, String segment,
                                                            String workId, String episodeId,
                                                            int pageCountHint) {
        int primaryProbePage = 1;
        String extension = reachableEarlyNtkGeneratedImageExtensionForPage(client, segment,
                workId, episodeId, primaryProbePage);
        if(extension.length() > 0)
            return extension;
        if(pageCountHint != 1)
            return reachableEarlyNtkGeneratedImageExtensionForPage(client, segment, workId, episodeId, 2);
        return "";
    }

    private String reachableEarlyNtkGeneratedImageExtensionForPage(CustomHttpClient client, String segment,
                                                                   String workId, String episodeId,
                                                                   int probePage) {
        return reachableEarlyNtkGeneratedImageExtensionForPage(
                client, segment, workId, episodeId, probePage,
                ReaderImageCache.INSTANCE.cacheGenerationForProducer());
    }

    private String reachableEarlyNtkGeneratedImageExtensionForPage(CustomHttpClient client, String segment,
                                                                   String workId, String episodeId,
                                                                   int probePage,
                                                                   long producerGeneration) {
        if(client == null || segment == null || workId == null || episodeId == null)
            return "";
        String path = getNtkEpisodePath();
        if(shouldDeferModernNtkGeneratedProbeUntilAck(client, path)) {
            Log.d(TAG, "ntk_generated_direct_extension_probe_defer_ack path=" + path
                    + ",segment=" + segment
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId
                    + ",probePage=" + probePage);
            return "";
        }
        String cacheKey = ntkGeneratedExtensionCacheKey(segment, workId, episodeId, probePage);
        String cached = cachedFreshNtkGeneratedImageExtension(cacheKey);
        if(cached != null && cached.length() == 0
                && isTrustedKnownNtkGeneratedCandidate(workId, episodeId)) {
            Log.d(TAG, "ntk_generated_empty_extension_retry_known_metadata path=" + path
                    + ",segment=" + segment
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId
                    + ",probePage=" + probePage);
        } else if(cached != null) {
            return cached;
        }
        if("webtoon".equalsIgnoreCase(segment)
                && shouldPreferNtkApiForCanonicalWebtoonPath(path)
                && !isTrustedKnownNtkGeneratedCandidate(workId, episodeId)) {
            cacheNtkGeneratedImageExtension(cacheKey, "");
            return "";
        }
        final FutureTask<String>[] taskHolder = new FutureTask[1];
        FutureTask<String> task = new FutureTask<String>(() ->
                reachableEarlyNtkGeneratedImageExtensionForPageUnshared(
                        client, segment, workId, episodeId, probePage, cacheKey,
                        producerGeneration)) {
            @Override
            protected void done() {
                NTK_GENERATED_EXTENSION_FLIGHTS.remove(cacheKey, taskHolder[0]);
            }
        };
        taskHolder[0] = task;
        FutureTask<String> running = NTK_GENERATED_EXTENSION_FLIGHTS.putIfAbsent(cacheKey, task);
        boolean owner = running == null;
        if(owner) {
            running = task;
            Thread thread = new Thread(task, "ntk-generated-extension-flight");
            thread.setDaemon(true);
            thread.start();
        } else {
            ViewerWarmupManager.logMetric("ntk_generated_extension_probe_join", 1L);
        }
        try {
            String result = running.get(NTK_EARLY_GENERATED_EXTENSION_WAIT_MS, TimeUnit.MILLISECONDS);
            return result == null ? "" : result;
        } catch(Exception ignored) {
            cached = cachedFreshNtkGeneratedImageExtension(cacheKey);
            return cached == null ? "" : cached;
        }
    }

    private String reachableEarlyNtkGeneratedImageExtensionForPageUnshared(CustomHttpClient client, String segment,
                                                                           String workId, String episodeId,
                                                                           int probePage, String cacheKey,
                                                                           long producerGeneration) {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch remaining = new CountDownLatch(NTK_GENERATED_IMAGE_EXTENSIONS.length);
        AtomicBoolean winner = new AtomicBoolean(false);
        String[] hit = new String[]{""};
        long startedAt = System.currentTimeMillis();
        startSpeculativeNtkGeneratedInitialStreams(
                client, segment, workId, episodeId, producerGeneration);
        for(String extension : NTK_GENERATED_IMAGE_EXTENSIONS) {
            Thread thread = new Thread(() -> {
                try {
                    if(winner.get())
                        return;
                    String probe = ntkGeneratedImageUrl(segment, workId, episodeId, probePage, extension);
                    if(awaitCachedNtkGeneratedImageAvailable(client, probe, 80L)
                            && winner.compareAndSet(false, true)) {
                        hit[0] = extension;
                        cacheNtkGeneratedImageExtension(cacheKey, extension);
                        publishVerifiedEarlyNtkGeneratedImages(
                                client, segment, workId, episodeId, extension, probePage, 1,
                                producerGeneration);
                        done.countDown();
                        return;
                    }
                    if(isNtkGeneratedImageReachableFast(client, probe, winner)
                            && winner.compareAndSet(false, true)) {
                        hit[0] = extension;
                        cacheNtkGeneratedImageExtension(cacheKey, extension);
                        publishVerifiedEarlyNtkGeneratedImages(
                                client, segment, workId, episodeId, extension, probePage, 1,
                                producerGeneration);
                        done.countDown();
                    }
                } catch(Exception ignored) {
                } finally {
                    remaining.countDown();
                    if(remaining.getCount() == 0)
                        done.countDown();
                }
            }, "ntk-generated-ext-" + extension);
            thread.setDaemon(true);
            thread.start();
        }
        try {
            done.await(NTK_EARLY_GENERATED_EXTENSION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String result = hit[0] == null ? "" : hit[0];
        if(result.length() == 0) {
            result = reachableNtkGeneratedImageExtensionConfirmPass(
                    client, segment, workId, episodeId, probePage, cacheKey);
        }
        Log.d(TAG, "ntk_generated_direct_extension_probe path=" + segment + "/" + workId + "/" + episodeId
                + ",extension=" + result
                + ",probePage=" + probePage
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        if(result.length() > 0)
            publishVerifiedEarlyNtkGeneratedImages(
                    client, segment, workId, episodeId, result, probePage, 1,
                    producerGeneration);
        return result;
    }

    private void publishVerifiedEarlyNtkGeneratedImages(CustomHttpClient client, String segment,
                                                         String workId, String episodeId,
                                                         String extension, int startPage, int verifiedCount) {
        publishVerifiedEarlyNtkGeneratedImages(
                client, segment, workId, episodeId, extension, startPage, verifiedCount,
                ReaderImageCache.INSTANCE.cacheGenerationForProducer());
    }

    private void publishVerifiedEarlyNtkGeneratedImages(CustomHttpClient client, String segment,
                                                         String workId, String episodeId,
                                                         String extension, int startPage, int verifiedCount,
                                                         long producerGeneration) {
        String safeExtension = normalizeNtkGeneratedImageExtension(extension);
        if(client == null || segment == null || workId == null || episodeId == null
                || safeExtension.length() == 0 || verifiedCount <= 0)
            return;
        String path = getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return;
        if(isStrictSourceAuthorityManaged(path)) {
            Log.d(TAG, "ntk_generated_verified_urls_fenced_by_strict_authority path=" + path);
            return;
        }
        int declaredCount = Math.max(0, getNtkImageCount());
        int knownCount = declaredCount > 0 ? declaredCount : ntkGeneratedImageCandidateCount();
        // Once page 1 has established the real extension, every URL in the
        // server-provided episode count is equally authoritative. Publishing
        // only the initial runway here made the reader wait for the slower
        // document/API manifest before it could request the rest of the
        // current episode.
        int limit = Math.max(1, knownCount);
        int safeStartPage = Math.max(1, startPage);
        ArrayList<String> urls = ntkInitialGeneratedImageUrlsWithKnownExtensions(segment, workId,
                episodeId, safeExtension, safeStartPage, safeStartPage + limit - 1, false);
        if(urls.isEmpty())
            urls.add(ntkGeneratedImageUrl(segment, workId, episodeId, safeStartPage, safeExtension));
        Log.d(TAG, "ntk_generated_verified_urls_early path=" + path
                + ",count=" + urls.size()
                + ",extension=" + safeExtension
                + ",startPage=" + safeStartPage
                + ",first=" + safeLogImage(urls.get(0)));
        boolean completeManifest = false;
        try {
            ReaderImageCache.INSTANCE.rememberVerifiedEarlyNtkGeneratedImageUrls(
                    path, urls, producerGeneration);
            long manifestMinCreatedAt = android.os.SystemClock.elapsedRealtime() - 30_000L;
            if(safeStartPage == 1 && declaredCount > 0 && urls.size() == declaredCount
                    && ReaderImageCache.INSTANCE.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                    path, declaredCount, manifestMinCreatedAt)) {
                // This is the server-declared episode length paired with a page-1 reachability
                // proof for one immutable generated extension, not the speculative default cap.
                ReaderImageCache.INSTANCE.rememberTrustedNtkImageApiCount(path, declaredCount);
                completeManifest = true;
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_generated_verified_urls_remember_error path=" + path + "," + e);
        }
        if(completeManifest)
            return;
        Context context = client.getContext();
        if(context != null) {
            // Fill the launch window first. The exact tail is then submitted on ReaderImageCache's
            // background lane while the episode list is still visible; bitmap decode remains
            // viewport-bounded inside ReaderSession.
            int streamCount = Math.min(urls.size(),
                    NTK_FOREGROUND_NATIVE_INITIAL_RUNWAY_API_SKIP_PAGES);
            for(int i = 0; i < streamCount; i++) {
                String url = urls.get(i);
                try {
                    boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            context, this, url, null, false, null, i, true, true,
                            producerGeneration);
                    Log.d(TAG, "ntk_generated_verified_stream_start path=" + path
                            + ",started=" + started
                            + ",index=" + i
                            + ",image=" + safeLogImage(url));
                } catch(Exception e) {
                    Log.d(TAG, "ntk_generated_verified_stream_error path=" + path
                            + ",image=" + safeLogImage(url)
                            + "," + e);
                }
            }
            if(urls.size() > streamCount) {
                boolean tailScheduled = ReaderImageCache.INSTANCE
                        .startVerifiedGeneratedEpisodeTailPrefetch(
                                context, this, urls, streamCount, producerGeneration);
                Log.d(TAG, "ntk_generated_verified_stream_tail_prefetch path=" + path
                        + ",started=" + streamCount
                        + ",tail=" + (urls.size() - streamCount)
                        + ",scheduled=" + tailScheduled);
            }
        }
        if(!("webtoon".equalsIgnoreCase(segment) && isForegroundNtkNativeViewerPath(path)))
            startFirstNtkApiImageStream(
                    client, path, urls, false, producerGeneration);
    }

    private void startAsyncNtkGeneratedInitialExtensionValidation(CustomHttpClient client,
                                                                  String segment, String workId,
                                                                  String episodeId, String defaultExtension,
                                                                  int pageCount) {
        if(client == null || segment == null || workId == null || episodeId == null
                || normalizeNtkGeneratedImageExtension(defaultExtension).length() == 0)
            return;
        int validationPageCount = "webtoon".equalsIgnoreCase(segment)
                ? 1
                : Math.min(Math.min(pageCount, NTK_EARLY_INITIAL_PUBLISH_PAGES),
                ntkGeneratedInitialValidationPageCount(pageCount));
        if(validationPageCount <= 1)
            return;
        String key = getNtkEpisodePath() + "|initial-ext-validate|" + segment + "|"
                + workId + "|" + episodeId + "|" + defaultExtension + "|" + validationPageCount;
        if(firstNtkApiImageStreamStarts().putIfAbsent(key, Boolean.TRUE) != null)
            return;
        Thread thread = new Thread(() -> {
            try {
                validateNtkGeneratedInitialPageExtensions(client, segment, workId, episodeId,
                        defaultExtension, validationPageCount);
            } catch(Exception e) {
                Log.d(TAG, "ntk_generated_initial_validation_async_error path="
                        + getNtkEpisodePath() + "," + e);
            }
        }, "ntk-generated-initial-ext-async");
        thread.setDaemon(true);
        thread.start();
    }

    private ArrayList<String> ntkInitialGeneratedImageUrlsWithKnownExtensions(String segment,
                                                                              String workId,
                                                                              String episodeId,
                                                                              String fallbackExtension,
                                                                              int startPage,
                                                                              int endPage) {
        return ntkInitialGeneratedImageUrlsWithKnownExtensions(
                segment, workId, episodeId, fallbackExtension, startPage, endPage,
                "manhwa".equalsIgnoreCase(segment));
    }

    private ArrayList<String> ntkInitialGeneratedImageUrlsWithKnownExtensions(String segment,
                                                                              String workId,
                                                                              String episodeId,
                                                                              String fallbackExtension,
                                                                              int startPage,
                                                                              int endPage,
                                                                              boolean requireVerifiedAfterStart) {
        ArrayList<String> urls = new ArrayList<>();
        String safeFallback = normalizeNtkGeneratedImageExtension(fallbackExtension);
        if(segment == null || workId == null || episodeId == null || safeFallback.length() == 0)
            return urls;
        int safeStart = Math.max(1, startPage);
        int safeEnd = Math.max(safeStart, endPage);
        for(int page = safeStart; page <= safeEnd; page++) {
            String extension = cachedFreshNtkGeneratedImageExtension(
                    ntkGeneratedExtensionCacheKey(segment, workId, episodeId, page));
            if(extension == null || extension.length() == 0) {
                if(requireVerifiedAfterStart && page != safeStart)
                    continue;
                extension = safeFallback;
            }
            urls.add(ntkGeneratedImageUrl(segment, workId, episodeId, page, extension));
        }
        return urls;
    }

    private void startSpeculativeNtkGeneratedInitialStreams(CustomHttpClient client, String segment,
                                                            String workId, String episodeId) {
        startSpeculativeNtkGeneratedInitialStreams(
                client, segment, workId, episodeId,
                ReaderImageCache.INSTANCE.cacheGenerationForProducer());
    }

    private void startSpeculativeNtkGeneratedInitialStreams(CustomHttpClient client, String segment,
                                                            String workId, String episodeId,
                                                            long producerGeneration) {
        if(client == null || segment == null || workId == null || episodeId == null)
            return;
        Context context = client.getContext();
        if(context == null)
            return;
        int limit = NTK_SPECULATIVE_INITIAL_STREAM_PAGES;
        int knownCount = getNtkImageCount();
        if(knownCount > 0)
            limit = Math.min(limit, knownCount);
        String path = getNtkEpisodePath();
        if(path != null && path.matches(
                "(?i)^/manhwa/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$")) {
            // Numeric manhwa has an explicit extension-authority probe. Guessed .jpg streams
            // race the same immutable assets under the wrong identity and are not a producer.
            return;
        }
        if(shouldSkipUnverifiedCanonicalWebtoonPathWorkSpeculation(path, segment, workId, episodeId))
            return;
        if("webtoon".equalsIgnoreCase(segment) && hasNtkViewerPayloadImageHints(path, 4)) {
            Log.d(TAG, "ntk_generated_speculative_stream_skip_payload_direct_hint path=" + path
                    + ",segment=" + segment
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId);
            return;
        }
        boolean foregroundNativeViewer = isForegroundNtkNativeViewerPath(path);
        if(("webtoon".equalsIgnoreCase(segment) || "manhwa".equalsIgnoreCase(segment))
                && shouldStartUnverifiedInitialGeneratedJpgStream(segment, workId, episodeId, 1)) {
            String firstExtension = unverifiedInitialNtkGeneratedExtension(segment);
            String firstImage = ntkGeneratedImageUrl(segment, workId, episodeId, 1, firstExtension);
            String firstKey = (path == null ? "" : path) + "|" + firstImage;
            boolean metadataBackedFirstImage =
                    isMetadataBackedInitialGeneratedCandidate(segment, workId, episodeId);
            if(firstNtkApiImageStreamStarts().putIfAbsent(firstKey, Boolean.TRUE) == null) {
                startNtkInitialForegroundStream(
                        client, path, firstImage, 0, firstKey, true, producerGeneration);
                if(!metadataBackedFirstImage) {
                    try {
                        ArrayList<String> firstOnly = new ArrayList<>();
                        firstOnly.add(firstImage);
                        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                                path, firstOnly, producerGeneration);
                    } catch(Exception e) {
                        Log.d(TAG, "ntk_generated_speculative_first_jpg_early_error path=" + path + "," + e);
                    }
                }
                Log.d(TAG, "ntk_generated_speculative_first_jpg_stream path=" + path
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",metadataBacked=" + metadataBackedFirstImage
                        + ",image=" + safeLogImage(firstImage));
            } else if(foregroundNativeViewer) {
                if(!metadataBackedFirstImage) {
                    try {
                        ArrayList<String> firstOnly = new ArrayList<>();
                        firstOnly.add(firstImage);
                        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                                path, firstOnly, producerGeneration);
                    } catch(Exception e) {
                        Log.d(TAG, "ntk_generated_speculative_first_jpg_early_error path=" + path + "," + e);
                    }
                }
                Log.d(TAG, "ntk_generated_speculative_first_jpg_skip_foreground_viewer path=" + path
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",metadataBacked=" + metadataBackedFirstImage
                        + ",image=" + safeLogImage(firstImage));
            }
            int initialLimit = Math.min(("webtoon".equalsIgnoreCase(segment)
                    || "manhwa".equalsIgnoreCase(segment))
                    ? NTK_EARLY_INITIAL_STREAM_START_COUNT : 3, limit);
            ArrayList<String> speculativeUrls = ntkInitialGeneratedImageUrlsWithKnownExtensions(
                    segment, workId, episodeId, firstExtension, 1, initialLimit, false);
            if(!speculativeUrls.isEmpty()) {
                if(!metadataBackedFirstImage) {
                    try {
                        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                                path, speculativeUrls, producerGeneration);
                        Log.d(TAG, "ntk_generated_speculative_urls_initial_jpg path=" + path
                                + ",segment=" + segment
                                + ",count=" + speculativeUrls.size()
                                + ",workId=" + workId
                                + ",episodeId=" + episodeId);
                    } catch(Exception e) {
                        Log.d(TAG, "ntk_generated_speculative_urls_initial_jpg_error path="
                                + path + ",segment=" + segment + "," + e);
                    }
                    startFirstNtkApiImageStream(
                            client, path, speculativeUrls, true, producerGeneration);
                } else {
                    try {
                        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                                path, speculativeUrls, producerGeneration);
                        Log.d(TAG, "ntk_generated_speculative_urls_initial_jpg_metadata_head path=" + path
                                + ",segment=" + segment
                                + ",count=" + speculativeUrls.size()
                                + ",workId=" + workId
                                + ",episodeId=" + episodeId);
                    } catch(Exception e) {
                        Log.d(TAG, "ntk_generated_speculative_urls_initial_jpg_metadata_head_error path="
                                + path + ",segment=" + segment + "," + e);
                    }
                    startFirstNtkApiImageStream(
                            client, path, speculativeUrls, false, producerGeneration);
                }
            }
        }
        int speculativeLimit = "webtoon".equalsIgnoreCase(segment)
                ? Math.min(NTK_EARLY_INITIAL_STREAM_START_COUNT, limit)
                : ("manhwa".equalsIgnoreCase(segment)
                ? Math.min(NTK_EARLY_INITIAL_STREAM_START_COUNT, limit)
                : Math.min(1, limit));
        if (getNtkImageCount() > 0 && isNumericNtkId(workId) && isNumericNtkId(episodeId)) {
            String verifiedExtension = cachedFreshNtkGeneratedImageExtension(
                    ntkGeneratedExtensionCacheKey(segment, workId, episodeId, 1));
            if(verifiedExtension == null || verifiedExtension.length() == 0) {
                Log.d(TAG, "ntk_generated_speculative_urls_skip_unverified_count_hint path=" + path
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId);
            } else {
            ArrayList<String> speculativeUrls = ntkInitialGeneratedImageUrlsWithKnownExtensions(
                    segment, workId, episodeId, verifiedExtension, 1,
                    Math.min(speculativeLimit, getNtkImageCount()), false);
            if(!speculativeUrls.isEmpty()) {
                try {
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                            path, speculativeUrls, producerGeneration);
                    Log.d(TAG, "ntk_generated_speculative_urls_verified_count_hint path=" + path
                            + ",count=" + speculativeUrls.size()
                            + ",workId=" + workId
                            + ",episodeId=" + episodeId
                            + ",extension=" + verifiedExtension);
                } catch(Exception e) {
                    Log.d(TAG, "ntk_generated_speculative_urls_unverified_count_hint_error path="
                            + path + "," + e);
                }
                startFirstNtkApiImageStream(
                        client, path, speculativeUrls, false, producerGeneration);
            }
            }
        }
        for(int page = 1; page <= speculativeLimit; page++) {
            String[] extensions = ntkSpeculativeGeneratedStreamExtensions(segment, workId, episodeId, page);
            if(extensions.length == 0) {
                Log.d(TAG, "ntk_generated_speculative_stream_skip_unverified_extension path="
                        + getNtkEpisodePath()
                        + ",segment=" + segment
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",page=" + page);
                continue;
            }
            for(String extension : extensions) {
                String safeExtension = normalizeNtkGeneratedImageExtension(extension);
                if(safeExtension.length() == 0)
                    continue;
                String image = ntkGeneratedImageUrl(segment, workId, episodeId, page, safeExtension);
                String key = (path == null ? "" : path) + "|" + image;
                boolean metadataBackedFirstImage =
                        page == 1 && isMetadataBackedInitialGeneratedCandidate(segment, workId, episodeId);
                if(foregroundNativeViewer && !metadataBackedFirstImage) {
                    if(page == 1 && path != null && path.length() > 0) {
                        try {
                            ArrayList<String> initialEarlyUrls = new ArrayList<>();
                            initialEarlyUrls.add(image);
                            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                                    path, initialEarlyUrls, producerGeneration);
                        } catch(Exception e) {
                            Log.d(TAG, "ntk_generated_speculative_stream_early_error path="
                                    + getNtkEpisodePath() + ",page=" + page + "," + e);
                        }
                    }
                }
                if(firstNtkApiImageStreamStarts().putIfAbsent(key, Boolean.TRUE) != null)
                    continue;
                try {
                    boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            context, this, image, null, false, null, -1, true, false,
                            producerGeneration);
                    if(started && page == 1 && path != null && path.length() > 0
                            && !metadataBackedFirstImage) {
                        ArrayList<String> initialEarlyUrls = new ArrayList<>();
                        initialEarlyUrls.add(image);
                        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                                path, initialEarlyUrls, producerGeneration);
                    }
                    Log.d(TAG, "ntk_generated_speculative_stream_start path=" + getNtkEpisodePath()
                            + ",started=" + started
                            + ",page=" + page
                            + ",extension=" + safeExtension
                            + ",metadataBacked=" + metadataBackedFirstImage
                            + ",image=" + safeLogImage(image));
                } catch(Exception e) {
                    Log.d(TAG, "ntk_generated_speculative_stream_error path=" + getNtkEpisodePath()
                            + ",page=" + page
                            + ",extension=" + safeExtension
                            + "," + e);
                }
            }
        }
        if(limit > speculativeLimit) {
            Log.d(TAG, "ntk_generated_speculative_stream_defer_adjacent path=" + getNtkEpisodePath()
                    + ",startedPages=" + speculativeLimit
                    + ",deferredPages=" + (limit - speculativeLimit));
        }
    }

    private boolean shouldSkipUnverifiedCanonicalWebtoonPathWorkSpeculation(String path, String segment,
                                                                            String workId, String episodeId) {
        if(path == null || !"webtoon".equalsIgnoreCase(segment)
                || workId == null || episodeId == null)
            return false;
        Matcher matcher = Pattern.compile("^/webtoon/(\\d+)/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return false;
        String pathWorkId = matcher.group(1);
        String pathEpisodeId = matcher.group(2);
        if(pathWorkId.length() <= 5)
            return false;
        String kpEpisodeId = ntkApiEpisodeIdForPath(pathEpisodeId);
        if(kpEpisodeId.length() > 0 && pathWorkId.equals(workId) && kpEpisodeId.equals(episodeId)) {
            Log.d(TAG, "ntk_generated_speculative_skip_kp_api_only path=" + path
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId);
            return true;
        }
        if(pathWorkId.equals(workId)) {
            String cached = cachedFreshNtkGeneratedImageExtension(
                    ntkGeneratedExtensionCacheKey(segment, workId, episodeId, 1));
            if(cached != null && cached.length() > 0)
                return false;
            Log.d(TAG, "ntk_generated_speculative_skip_unverified_canonical_path_work path=" + path
                    + ",workId=" + workId
                    + ",pathWorkId=" + pathWorkId
                    + ",episodeId=" + episodeId);
            return true;
        }
        boolean trustedKnownSlugMetadata = isTrustedKnownNtkGeneratedCandidate(workId, episodeId);
        if(trustedKnownSlugMetadata) {
            Log.d(TAG, "ntk_generated_speculative_allow_known_slug_metadata path=" + path
                    + ",workId=" + workId
                    + ",pathWorkId=" + pathWorkId
                    + ",episodeId=" + episodeId);
            return false;
        }
        String cached = cachedFreshNtkGeneratedImageExtension(
                ntkGeneratedExtensionCacheKey(segment, workId, episodeId, 1));
        if(cached != null && cached.length() > 0)
            return false;
        Log.d(TAG, "ntk_generated_speculative_skip_unverified_canonical_work path=" + path
                + ",workId=" + workId
                + ",pathWorkId=" + pathWorkId
                + ",episodeId=" + episodeId);
        return true;
    }

    private void startEarlyNtkGeneratedPublishProbeIfNeeded(CustomHttpClient client, String path) {
        if(client == null || path == null || path.length() == 0)
            return;
        if(shouldDeferModernNtkGeneratedProbeUntilAck(client, path)) {
            Log.d(TAG, "ntk_generated_early_publish_probe_defer_ack path=" + path);
            return;
        }
        Matcher matcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return;
        String segment = matcher.group(1);
        String pathWorkId = matcher.group(2);
        String pathEpisodeId = matcher.group(3);
        LinkedHashSet<String> workIds = ntkGeneratedWorkIdCandidatesForPath(segment, pathWorkId);
        LinkedHashSet<String> episodeIds = ntkGeneratedEpisodeIdCandidatesForPath(path, pathEpisodeId);
        if(workIds.isEmpty() || episodeIds.isEmpty())
            return;
        String probeKey = path + "|generated-early-publish-probe";
        if(firstNtkApiImageStreamStarts().putIfAbsent(probeKey, Boolean.TRUE) != null)
            return;
        final long producerGeneration = ReaderImageCache.INSTANCE.cacheGenerationForProducer();
        Thread thread = new Thread(() -> {
            try {
                long startedAt = System.currentTimeMillis();
                for(String workId : workIds) {
                    for(String episodeId : episodeIds) {
                        String extension = reachableNtkGeneratedImageExtension(client, segment, workId,
                                episodeId, 1, null, producerGeneration);
                        if(extension.length() == 0)
                            continue;
                        Log.d(TAG, "ntk_generated_early_publish_probe path=" + path
                                + ",workId=" + workId
                                + ",episodeId=" + episodeId
                                + ",extension=" + extension
                                + ",ms=" + (System.currentTimeMillis() - startedAt));
                        ArrayList<String> urls = ntkInitialGeneratedImageUrls(segment, workId,
                                episodeId, extension);
                        if(!urls.isEmpty()) {
                            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(
                                    path, urls, producerGeneration);
                            startFirstNtkApiImageStream(
                                    client, path, urls, true, producerGeneration);
                        }
                        return;
                    }
                }
                Log.d(TAG, "ntk_generated_early_publish_probe path=" + path
                        + ",extension="
                        + ",workIds=" + workIds
                        + ",episodeIds=" + episodeIds
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                Log.d(TAG, "ntk_generated_early_publish_probe_error path=" + path + "," + e);
            }
        }, "ntk-generated-early-publish-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private String[] ntkSpeculativeGeneratedStreamExtensions(String segment, String workId,
                                                             String episodeId, int page) {
        String cached = cachedFreshNtkGeneratedImageExtension(
                ntkGeneratedExtensionCacheKey(segment, workId, episodeId, page));
        if(cached != null && cached.length() > 0)
            return new String[]{cached};
        if(page > 1 && page <= NTK_EARLY_VERIFIED_WEBTOON_STREAM_PAGES
                && ("webtoon".equalsIgnoreCase(segment) || "manhwa".equalsIgnoreCase(segment))) {
            String firstPageCached = cachedFreshNtkGeneratedImageExtension(
                    ntkGeneratedExtensionCacheKey(segment, workId, episodeId, 1));
            if(firstPageCached != null && firstPageCached.length() > 0)
                return new String[]{firstPageCached};
        }
        if(shouldStartUnverifiedInitialGeneratedJpgStream(segment, workId, episodeId, page))
            return new String[]{unverifiedInitialNtkGeneratedExtension(segment)};
        if(page == 1 && isNumericNtkId(workId) && isNumericNtkId(episodeId)) {
            Log.d(TAG, "ntk_generated_speculative_stream_unverified_candidates path="
                    + getNtkEpisodePath()
                    + ",segment=" + segment
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId
                    + ",page=" + page
                    + ",extensions=none,reason=wait_verified_probe");
        }
        return new String[0];
    }

    private boolean shouldStartUnverifiedInitialGeneratedJpgStream(String segment, String workId,
                                                                   String episodeId, int page) {
        if(page != 1
                || !isNumericNtkId(workId) || !isNumericNtkId(episodeId))
            return false;
        String path = getNtkEpisodePath();
        Matcher matcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/(\\d+)(?:[/?#].*)?$").matcher(path == null ? "" : path);
        if(!matcher.find() || !matcher.group(3).equals(episodeId))
            return false;
        String pathSegment = matcher.group(1);
        if(!pathSegment.equalsIgnoreCase(segment))
            return false;
        if(!"webtoon".equalsIgnoreCase(segment) && !"manhwa".equalsIgnoreCase(segment))
            return false;
        String pathWorkId = matcher.group(2);
        String pathEpisodeId = matcher.group(3);
        String recordedEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        if("manhwa".equalsIgnoreCase(segment)
                && workId.equals(pathWorkId)
                && episodeId.equals(pathEpisodeId)) {
            return true;
        }
        boolean knownExactNumericCandidate = workId.equals(pathWorkId)
                && isNumericNtkId(recordedEpisodeId)
                && recordedEpisodeId.equals(episodeId)
                && getNtkImageCount() > 0;
        boolean metadataBackedFirstImage =
                isMetadataBackedInitialGeneratedCandidate(segment, workId, episodeId);
        if("webtoon".equalsIgnoreCase(segment)
                && metadataBackedFirstImage
                && MainApplication.isNtkForegroundViewerPath(path)) {
            return false;
        }
        CustomHttpClient.NtkCachedImageIdentity identity =
                CustomHttpClient.cachedNtkImageIdentity(path);
        if(pathWorkId.matches("\\d{1,12}")
                && !knownExactNumericCandidate
                && !metadataBackedFirstImage
                && (identity == null
                || !workId.equals(identity.workId)
                || !episodeId.equals(identity.episodeId))) {
            return false;
        }
        boolean mismatchedRecordedEpisode = isNumericNtkId(recordedEpisodeId)
                && !recordedEpisodeId.equals(episodeId);
        if((getNtkImageCount() > NTK_DEFAULT_GENERATED_PAGE_COUNT && !knownExactNumericCandidate)
                || mismatchedRecordedEpisode) {
            return false;
        }
        return true;
    }

    boolean shouldStartUnverifiedInitialGeneratedJpgStreamForTest(
            String segment, String workId, String episodeId, int page) {
        return shouldStartUnverifiedInitialGeneratedJpgStream(segment, workId, episodeId, page);
    }

    private boolean isMetadataBackedInitialGeneratedCandidate(String segment, String workId,
                                                              String episodeId) {
        if(!"webtoon".equalsIgnoreCase(segment)
                || !isNumericNtkId(workId) || !isNumericNtkId(episodeId))
            return false;
        String path = getNtkEpisodePath();
        Matcher matcher = Pattern.compile("^/webtoon/(\\d+)/(\\d+)(?:[/?#].*)?$")
                .matcher(path == null ? "" : path);
        if(!matcher.find() || !episodeId.equals(matcher.group(2)))
            return false;
        String metadataWorkId = ntkApiEpisodeIdForPath(getNtkImageWorkId());
        if(!isNumericNtkId(metadataWorkId) || !metadataWorkId.equals(workId))
            return false;
        String metadataEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        return metadataEpisodeId.length() == 0 || metadataEpisodeId.equals(episodeId);
    }

    private boolean isTrustedKnownNtkGeneratedCandidate(String workId, String episodeId) {
        String knownImageWorkId = ntkApiEpisodeIdForPath(getNtkImageWorkId());
        String knownImageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        return getNtkImageCount() > 0
                && isNumericNtkId(knownImageWorkId)
                && isNumericNtkId(knownImageEpisodeId)
                && knownImageWorkId.equals(workId)
                && knownImageEpisodeId.equals(episodeId);
    }

    private String unverifiedInitialNtkGeneratedExtension(String segment) {
        if("webtoon".equalsIgnoreCase(segment))
            return "jpeg";
        return "jpg";
    }

    private String reachableNtkGeneratedImageExtensionConfirmPass(CustomHttpClient client, String segment,
                                                                  String workId, String episodeId,
                                                                  int probePage, String cacheKey) {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch remaining = new CountDownLatch(NTK_GENERATED_IMAGE_EXTENSIONS.length);
        AtomicBoolean winner = new AtomicBoolean(false);
        String[] hit = new String[]{""};
        long startedAt = System.currentTimeMillis();
        for(String extension : NTK_GENERATED_IMAGE_EXTENSIONS) {
            Thread thread = new Thread(() -> {
                try {
                    if(winner.get())
                        return;
                    String probe = ntkGeneratedImageUrl(segment, workId, episodeId, probePage, extension);
                    if(isNtkGeneratedImageReachable(client, probe, false)
                            && winner.compareAndSet(false, true)) {
                        hit[0] = extension;
                        cacheNtkGeneratedImageExtension(cacheKey, extension);
                        done.countDown();
                    }
                } catch(Exception ignored) {
                } finally {
                    remaining.countDown();
                    if(remaining.getCount() == 0)
                        done.countDown();
                }
            }, "ntk-generated-ext-confirm-" + extension);
            thread.setDaemon(true);
            thread.start();
        }
        try {
            done.await(NTK_GENERATED_EXTENSION_CONFIRM_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String result = hit[0] == null ? "" : hit[0];
        Log.d(TAG, "ntk_generated_direct_extension_confirm path=" + segment + "/" + workId + "/" + episodeId
                + ",extension=" + result
                + ",probePage=" + probePage
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        return result;
    }

    private ConcurrentHashMap<String, Boolean> firstNtkApiImageStreamStarts() {
        if(firstNtkApiImageStreamStarts == null)
            firstNtkApiImageStreamStarts = new ConcurrentHashMap<>();
        return firstNtkApiImageStreamStarts;
    }

    private static String safeLogImage(String image) {
        if(image == null)
            return "";
        int query = image.indexOf('?');
        String clean = query >= 0 ? image.substring(0, query) : image;
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private static String safeLogImageUrl(String image) {
        if(image == null)
            return "";
        try {
            URI uri = URI.create(image);
            String host = uri.getHost();
            String path = uri.getPath();
            if(host != null && path != null)
                return host + path;
        } catch(Exception ignored) {
        }
        int query = image.indexOf('?');
        return query >= 0 ? image.substring(0, query) : image;
    }

    private static String ntkViewerCanonicalWorkIdForImageApi(String normalized, String path,
                                                              int titleId, String apiWorkId) {
        if(path == null)
            return "";
        String pathWorkId = ntkSlugWebtoonWorkId(path);
        if(pathWorkId.length() > 0) {
            if(imageApiWorkIdIsNumeric(apiWorkId) && !apiWorkId.equals(pathWorkId))
                return "";
            String sourceWorkId = ntkViewerSourceWorkId(normalized);
            if(sourceWorkId.length() > 0)
                return sourceWorkId;
            String refId = ntkViewerRefId(normalized);
            if(refId.length() > 0)
                return refId;
            return pathWorkId;
        }
        String refId = ntkViewerRefId(normalized);
        if(refId.length() > 0 && !refId.equals(apiWorkId)) {
            Log.d(TAG, "ntk_viewer_api_ref_work_id path=" + path
                    + ",apiWorkId=" + apiWorkId
                    + ",refId=" + refId);
            return refId;
        }
        String sourceWorkId = ntkViewerSourceWorkId(normalized);
        if(sourceWorkId.length() > 0)
            return sourceWorkId;
        if(imageApiWorkIdIsNumeric(apiWorkId))
            return "";
        return "";
    }

    static String ntkViewerCanonicalWorkIdForImageApiForTest(String normalized, String path,
                                                             int titleId, String apiWorkId) {
        return ntkViewerCanonicalWorkIdForImageApi(normalized, path, titleId, apiWorkId);
    }

    private static boolean imageApiWorkIdIsNumeric(String apiWorkId) {
        return apiWorkId != null && apiWorkId.matches("\\d+");
    }

    private static boolean hasNtkSlugWebtoonWorkId(String path) {
        return ntkSlugWebtoonWorkId(path).length() > 0;
    }

    private static String ntkSlugWebtoonWorkId(String path) {
        if(path == null || path.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("^/webtoon/([^/?#]+)/[^/?#]+").matcher(path);
        if(!matcher.find())
            return "";
        String workId = matcher.group(1);
        return workId != null && !workId.matches("\\d+") ? workId : "";
    }

    private boolean awaitCachedNtkViewerImageApiCandidates(CustomHttpClient client, String path,
                                                           Set<String> seenImages, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while(System.currentTimeMillis() < deadline) {
            if(addCachedNtkViewerImageApiCandidates(client, path, seenImages))
                return true;
            if(Thread.currentThread().isInterrupted())
                return false;
            try {
                Thread.sleep(35L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return addCachedNtkViewerImageApiCandidates(client, path, seenImages);
    }

    private boolean awaitCachedNtkViewerImageApiCandidatesUntilPageReady(CustomHttpClient client,
                                                                         String path,
                                                                         Set<String> seenImages,
                                                                         AsyncNtkPageFetch directFetch,
                                                                         AsyncNtkPageFetch fallbackFetch,
                                                                         long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while(System.currentTimeMillis() < deadline) {
            if(addCachedNtkViewerImageApiCandidates(client, path, seenImages))
                return true;
            CustomHttpClient.PageResponse direct = completedNtkPageFetch(directFetch, false);
            if(isUsableNtkFastPage(direct, path))
                return false;
            CustomHttpClient.PageResponse fallback = completedNtkPageFetch(fallbackFetch, false);
            if(isUsableNtkFastPage(fallback, path))
                return false;
            if((directFetch == null || directFetch.done.getCount() == 0)
                    && (fallbackFetch == null || fallbackFetch.done.getCount() == 0))
                return false;
            if(Thread.currentThread().isInterrupted())
                return false;
            try {
                Thread.sleep(NTK_PAGE_FETCH_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return addCachedNtkViewerImageApiCandidates(client, path, seenImages);
    }

    private boolean addCachedNtkViewerImageApiCandidates(CustomHttpClient client, String path, Set<String> seenImages) {
        if(path == null || seenImages == null)
            return false;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        Context context = MainApplication.appContext;
        if(context == null)
            return false;
        ArrayList<String> urls = NtkWebViewFallbackManager.get(context).cachedViewerImageUrls(
                pathMatcher.group(1), pathMatcher.group(2), pathMatcher.group(3), path);
        if(urls.size() == 0)
            return false;
        int before = imgs == null ? 0 : imgs.size();
        urls = normalizeNtkApiViewerImageUrls(urls);
        for(String url : urls)
            addImageIfValid(client, seenImages, url);
        return imgs != null && imgs.size() > before;
    }

    private static ArrayList<String> normalizeNtkApiViewerImageUrls(List<String> urls) {
        if(urls == null || urls.isEmpty())
            return new ArrayList<>();
        Matcher generatedMatcher = null;
        Pattern generatedPattern = Pattern.compile(
                "^(https?://i\\.toonflix\\.app/(?:blacktoon/episodes/\\d+/[^/?#]+|(?:manhwa|webtoon)/\\d+/[^/?#]+|wt/episodes/[^/?#]+/[^/?#]+))/p(\\d{3})\\.(jpg|jpeg|png|webp)([?#].*)?$",
                Pattern.CASE_INSENSITIVE);
        for(String url : urls) {
            Matcher matcher = generatedPattern.matcher(url == null ? "" : url);
            if(matcher.find()) {
                generatedMatcher = matcher;
                break;
            }
        }
        if(generatedMatcher == null)
            return new ArrayList<>(urls);
        String base = generatedMatcher.group(1);
        String extension = generatedMatcher.group(3);
        ArrayList<String> normalized = new ArrayList<>(urls.size());
        int replaced = 0;
        for(int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if(shouldReplaceNtkApiViewerImageUrl(url)) {
                normalized.add(String.format(Locale.ROOT, "%s/p%03d.%s", base, i + 1, extension));
                replaced++;
            } else {
                normalized.add(url);
            }
        }
        if(replaced > 0)
            Log.d(TAG, "ntk_api_viewer_image_urls_normalized replaced=" + replaced
                    + ",count=" + urls.size()
                    + ",base=" + base);
        return normalized;
    }

    private static boolean shouldReplaceNtkApiViewerImageUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("://img") && lower.contains("newtoki")
                || lower.contains(".newtoki")
                || lower.matches(".*\\.(svg)(?:[?#].*)?$");
    }

    private boolean shouldPreAckBeforeNtkViewerImageApi(String path) {
        if(isNtkSyntheticWebtoonEpisodePath(path))
            return false;
        return shouldSkipNtkGeneratedForEpisodePath(path)
                || shouldPreferNtkApiForCanonicalWebtoonPath(path)
                || isNtkApiFallbackModeOverride()
                || isNtkNativeAckModeOverride();
    }

    private static boolean looksLikeNtkWebViewViewerPayload(String body) {
        if(body == null)
            return false;
        String trimmed = body.trim();
        if(trimmed.length() == 0)
            return false;
        String lower = trimmed.substring(0, Math.min(trimmed.length(), 512)).toLowerCase(Locale.ROOT);
        return lower.startsWith("<!doctype html")
                || lower.startsWith("<html")
                || lower.contains("ntkviewerquicbridge");
    }

    private static String ntkApiEpisodeIdFromPathEpisodeId(String episodeId) {
        if(episodeId == null)
            return "";
        String trimmed = episodeId.trim();
        Matcher kpMatcher = Pattern.compile("^kp-\\d{1,12}-(\\d{1,12})$",
                Pattern.CASE_INSENSITIVE).matcher(trimmed);
        if(kpMatcher.find())
            return kpMatcher.group(1);
        Matcher nvMatcher = Pattern.compile("^(?:naver|nv)-\\d{5,}-(\\d+)$",
                Pattern.CASE_INSENSITIVE).matcher(trimmed);
        if(nvMatcher.find())
            return nvMatcher.group(1);
        return trimmed;
    }

    private boolean addNtkBoardUploadTextImageCandidates(CustomHttpClient client, String body, Set<String> seenImages) {
        if(body == null || body.length() == 0 || seenImages == null)
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Matcher arrayMatcher = Pattern.compile("\"images\"\\s*:\\s*\\[([\\s\\S]*?)\\]\\s*(?:,|\\})",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        Pattern pageSrcPattern = Pattern.compile(
                "\"page\"\\s*:\\s*\\d{1,4}[^\\{\\}\\[\\]]{0,600}?\"src\"\\s*:\\s*\"(https?://[^\"<>]+/(?:webtoon_uploads|manhwa_uploads|comic_uploads|board_uploads)/[^\"<>]+\\.(?:jpg|jpeg|png|webp|gif)(?:\\?[^\"<>]*)?)\"",
                Pattern.CASE_INSENSITIVE);
        while(arrayMatcher.find()) {
            Matcher pageSrcMatcher = pageSrcPattern.matcher(arrayMatcher.group(1));
            while(pageSrcMatcher.find()) {
                String url = normalizeNtkEmbeddedImageText(pageSrcMatcher.group(1));
                if(isNtkPageUploadTextImage(url))
                    ordered.add(url);
            }
            if(!ordered.isEmpty())
                break;
        }
        if(ordered.size() < 3) {
            boolean explicitBoardPages = hasNtkViewerBoardUploadImageInText(normalized);
            Matcher directMatcher = Pattern.compile(
                    "https?://[^\"'<>\\\\\\s]+/(?:webtoon_uploads|manhwa_uploads|comic_uploads|board_uploads)/[^\"'<>\\\\\\s]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^\"'<>\\\\\\s]*)?",
                    Pattern.CASE_INSENSITIVE).matcher(normalized);
            while(directMatcher.find()) {
                String url = normalizeNtkEmbeddedImageText(directMatcher.group());
                if(url.toLowerCase(Locale.ROOT).contains("/board_uploads/") && !explicitBoardPages)
                    continue;
                ordered.add(url);
            }
        }
        if(ordered.size() < 3)
            return false;
        int before = imgs == null ? 0 : imgs.size();
        for(String url : ordered)
            addImageIfValid(client, seenImages, url);
        return imgs != null && imgs.size() > before;
    }

    private static boolean isNtkPageUploadTextImage(String url) {
        if(url == null || url.length() == 0)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if(lower.contains("/webtoon_uploads/") || lower.contains("/manhwa_uploads/")
                || lower.contains("/comic_uploads/"))
            return true;
        return lower.contains("/board_uploads/")
                && !lower.contains("banner")
                && !lower.contains("advert")
                && !lower.contains("sponsor")
                && !lower.contains("popup");
    }

    private static boolean hasNtkViewerImageApiPayload(String body) {
        return hasNtkViewerImageApiPayloadNormalized(normalizeNtkViewerPayloadText(body));
    }

    private static boolean hasNtkViewerImageApiPayloadNormalized(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return false;
        String lower = normalized.toLowerCase(Locale.ROOT);
        if(ntkViewerImagesToken(normalized).length() == 0)
            return false;
        if(lower.contains("\"imagestoken\"") && lower.contains("\"imagemetas\""))
            return true;
        return lower.contains("\"imageapipath\"")
                && (lower.contains("\"/api/webtoon-images\"")
                || lower.contains("\"/api/manhwa-images\""))
                && lower.contains("\"sourceworkid\"")
                && lower.contains("\"episodeid\"")
                && lower.contains("\"images\"");
    }

    private static boolean isNtkViewerImageMetasExplicitlyEmpty(String body) {
        return isNtkViewerImageMetasExplicitlyEmptyNormalized(normalizeNtkViewerPayloadText(body));
    }

    private static boolean isNtkViewerImageMetasExplicitlyEmptyNormalized(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return false;
        if(ntkViewerImagesToken(normalized).length() == 0)
            return false;
        Matcher matcher = Pattern.compile("\"imageMetas\"\\s*:\\s*\\[\\s*\\]").matcher(normalized);
        if(matcher.find())
            return true;
        matcher = Pattern.compile("\\\\\"imageMetas\\\\\"\\s*:\\s*\\[\\s*\\]").matcher(normalized);
        return matcher.find();
    }

    private static boolean isNtkViewerConfirmedEmptyPayload(String body, String path) {
        return isNtkViewerConfirmedEmptyPayloadNormalized(normalizeNtkViewerPayloadText(body), path);
    }

    private static boolean isNtkViewerConfirmedEmptyPayloadNormalized(String normalized, String path) {
        if(normalized == null || normalized.length() == 0)
            return false;
        if(hasNonEmptyNtkViewerImageMetas(normalized))
            return false;
        if(isNtkViewerImageMetasExplicitlyEmptyNormalized(normalized))
            return true;
        return normalized.contains("\"episodePath\":\"")
                && normalized.contains("\"initial\":[]")
                && normalized.contains("\"bestInitial\":[]")
                && normalized.contains("\"totalRoots\":0");
    }

    private static boolean hasNonEmptyNtkViewerImageMetas(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return false;
        Matcher matcher = Pattern.compile("\"imageMetas\"\\s*:\\s*\\[\\s*\\{").matcher(normalized);
        if(matcher.find())
            return true;
        matcher = Pattern.compile("\\\\\"imageMetas\\\\\"\\s*:\\s*\\[\\s*\\{").matcher(normalized);
        return matcher.find();
    }

    private static boolean isNtkViewerUnavailableEpisode(String body) {
        if(body == null || body.length() == 0)
            return false;
        return body.contains("ep_unavailable")
                || (body.contains("ep-notice") && body.contains("이미지 오류"));
    }

    private boolean shouldFetchNtkApiViewerImagesForSparseParse(String body, String path, int imgTagCount) {
        if(!hasNtkViewerImageApiPayload(body))
            return false;
        int parsed = imgs == null ? 0 : imgs.size();
        if(parsed == 0)
            return true;
        if(shouldSkipNtkGeneratedForEpisodePath(path) && parsed < 3)
            return true;
        int knownCount = getNtkImageCount();
        if(knownCount > 0 && parsed < Math.min(knownCount, 3))
            return true;
        return imgTagCount >= 8 && parsed < 3;
    }

    private boolean discardLowConfidenceNtkSingleHtmlImage(Document document, String body, String path,
                                                           Set<String> seenImages) {
        if(!isLowConfidenceNtkSingleHtmlImage(document, body, path, imgs))
            return false;
        imgs.clear();
        if(seenImages != null)
            seenImages.clear();
        return true;
    }

    private static boolean isLowConfidenceNtkSingleHtmlImage(Document document, String body, String path,
                                                            List<String> images) {
        if(images == null || images.size() != 1)
            return false;
        if(!isNtkWebtoonEpisodePath(path) || isNtkSyntheticWebtoonEpisodePath(path))
            return false;
        String image = images.get(0);
        if(image == null || !image.toLowerCase(Locale.ROOT).contains("/webtoon_uploads/"))
            return false;
        if(hasNtkViewerContent(document))
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        if(hasNtkViewerImageApiPayloadNormalized(normalized) || hasNonEmptyNtkViewerImageMetas(normalized))
            return false;
        return ntkViewerMetaPageCount(normalized) <= 1;
    }

    private boolean addNtkGeneratedPathImageCandidates(CustomHttpClient client, String path, Set<String> seenImages, int pageCount) {
        return addNtkGeneratedPathImageCandidates(client, path, seenImages, pageCount, false);
    }

    private boolean addNtkGeneratedPathImageCandidates(CustomHttpClient client, String path, Set<String> seenImages, int pageCount,
                                                      boolean validateFirstImage) {
        return addNtkGeneratedPathImageCandidates(client, path, seenImages, pageCount, validateFirstImage, null);
    }

    private boolean addNtkGeneratedPathImageCandidates(CustomHttpClient client, String path, Set<String> seenImages, int pageCount,
                                                      boolean validateFirstImage, Runnable onPrimaryValidationMiss) {
        if(path == null || seenImages == null || pageCount <= 0)
            return false;
        if(isStrictSourceAuthorityManaged(path)) {
            Log.d(TAG, "ntk_generated_path_candidates_fenced_by_strict_authority path=" + path);
            return false;
        }
        if(shouldUseProtectedNtkViewerApi(client, path)) {
            Log.d(TAG, "ntk_generated_path_candidates_skip_protected_api path=" + path);
            return false;
        }
        if(shouldSkipNtkGeneratedForEpisodePath(path) && !isNumericNtkId(getNtkImageEpisodeId())) {
            Log.d(TAG, "ntk_generated_skip_slug_api path=" + path
                    + ",imageEpisodeId=" + getNtkImageEpisodeId());
            return false;
        }
        if(shouldSkipNtkGeneratedForEpisodePath(path) && !isNumericNtkId(getNtkImageWorkId())) {
            Log.d(TAG, "ntk_generated_skip_slug_missing_image_work path=" + path
                    + ",imageEpisodeId=" + getNtkImageEpisodeId()
                    + ",imageCount=" + getNtkImageCount());
            if(onPrimaryValidationMiss != null)
                onPrimaryValidationMiss.run();
            return false;
        }
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String segment = pathMatcher.group(1);
        String pathWorkId = pathMatcher.group(2);
        String pathEpisodeId = pathMatcher.group(3);
        int before = imgs == null ? 0 : imgs.size();
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        if(client == null)
            return false;
        LinkedHashSet<String> workIds = ntkGeneratedWorkIdCandidatesForPath(segment, pathWorkId);
        LinkedHashSet<String> episodeIds = ntkGeneratedEpisodeIdCandidatesForPath(path, pathEpisodeId);
        if(shouldSkipNtkGeneratedForEpisodePath(path)
                && isNumericNtkId(getNtkImageWorkId())
                && isNumericNtkId(getNtkImageEpisodeId())) {
            Log.d(TAG, "ntk_slug_metadata_generated_probe path=" + path
                    + ",segment=" + segment
                    + ",imageWorkId=" + getNtkImageWorkId()
                    + ",imageEpisodeId=" + getNtkImageEpisodeId()
                    + ",imageCount=" + getNtkImageCount()
                    + ",pageCount=" + pageCount);
        }
        if(workIds.isEmpty() || episodeIds.isEmpty())
            return false;
        String workId = "";
        String imageEpisodeId = "";
        String imageExtension = "";
        AtomicBoolean primaryMissReported = new AtomicBoolean(false);
        Runnable primaryMiss = validateFirstImage && onPrimaryValidationMiss != null
                ? () -> {
                    if(primaryMissReported.compareAndSet(false, true))
                        onPrimaryValidationMiss.run();
                }
                : null;
        for(String candidateWorkId : workIds) {
            for(String candidateEpisodeId : episodeIds) {
                if(shouldSkipUnverifiedCanonicalWebtoonPathWorkSpeculation(path, segment,
                        candidateWorkId, candidateEpisodeId))
                    continue;
                String extension = trustedPrimaryNtkGeneratedExtension(path, segment, pathWorkId,
                        pathEpisodeId, candidateWorkId, candidateEpisodeId);
                if(extension.length() > 0) {
                    String cacheKey = ntkGeneratedExtensionCacheKey(segment, candidateWorkId, candidateEpisodeId, 1);
                    cacheNtkGeneratedImageExtension(cacheKey, extension);
                    publishVerifiedEarlyNtkGeneratedImages(client, segment, candidateWorkId, candidateEpisodeId,
                            extension, 1, 1);
                    Log.d(TAG, "ntk_generated_primary_extension_trusted path=" + path
                            + ",segment=" + segment
                            + ",workId=" + candidateWorkId
                            + ",episodeId=" + candidateEpisodeId
                            + ",extension=" + extension);
                } else {
                    extension = reachableNtkGeneratedImageExtension(client, segment, candidateWorkId,
                            candidateEpisodeId, 1, primaryMiss);
                }
                if(extension.length() == 0)
                    continue;
                workId = candidateWorkId;
                imageEpisodeId = candidateEpisodeId;
                imageExtension = extension;
                if(!candidateWorkId.equals(pathWorkId) || !candidateEpisodeId.equals(pathEpisodeId)) {
                    Log.d(TAG, "ntk_generated_metadata_id_fallback path=" + path
                            + ",pathWorkId=" + pathWorkId
                            + ",workId=" + candidateWorkId
                            + ",pathEpisodeId=" + pathEpisodeId
                            + ",imageEpisodeId=" + candidateEpisodeId
                            + ",extension=" + extension);
                }
                break;
            }
            if(imageExtension.length() > 0)
                break;
        }
        if(imageExtension.length() == 0)
            return addNtkSlugWebtoonGeneratedImageCandidates(
                    client, path, seenImages, pageCount, true);
        if(validateFirstImage) {
            Map<Integer, String> validatedPageExtensions = new LinkedHashMap<>();
            validatedPageExtensions.put(1, imageExtension);
            int validationPageCount = "webtoon".equalsIgnoreCase(segment)
                    && !shouldSkipNtkGeneratedForEpisodePath(path)
                    ? 1
                    : ntkGeneratedInitialValidationPageCount(safePageCount);
            Map<Integer, String> checkedPageExtensions = validateNtkGeneratedInitialPageExtensions(
                    client, segment, workId, imageEpisodeId, imageExtension, validationPageCount);
            for(int page = 2; page <= validationPageCount; page++) {
                String pageExtension = checkedPageExtensions.get(page);
                if(pageExtension == null)
                    pageExtension = "";
                if(pageExtension.length() == 0) {
                    int partialPageCount = page - 1;
                    if(partialPageCount >= 1) {
                        if(partialPageCount >= NTK_EARLY_INITIAL_PUBLISH_PAGES
                                && safePageCount > validationPageCount) {
                            rememberEarlyValidatedNtkGeneratedPages(client, path, segment, workId, imageEpisodeId,
                                    imageExtension, validatedPageExtensions, partialPageCount);
                            String pageTwoExtension = validatedPageExtensions.get(2);
                            String pageCountExtension = pageTwoExtension != null && !pageTwoExtension.equals(imageExtension)
                                    ? pageTwoExtension
                                    : imageExtension;
                            Log.d(TAG, "ntk_generated_page_count_probe_deferred path=" + path
                                    + ",partialPageCount=" + partialPageCount
                                    + ",safePageCount=" + safePageCount);
                        }
                        logNtkViewerParse("generated-trim-pages-" + safePageCount + "-to-" + partialPageCount,
                                null, getNtkEpisodePath(), 0, 0);
                        rememberEarlyValidatedNtkGeneratedPages(client, path, segment, workId, imageEpisodeId,
                                imageExtension, validatedPageExtensions, validationPageCount);
                        addValidatedNtkGeneratedPages(client, seenImages, segment, workId, imageEpisodeId,
                                imageExtension, validatedPageExtensions, validationPageCount);
                        publishValidatedNtkGeneratedTailProof(path, segment, workId, imageEpisodeId,
                                imageExtension, validatedPageExtensions, partialPageCount, page);
                        if(onPrimaryValidationMiss != null)
                            onPrimaryValidationMiss.run();
                        return imgs != null && imgs.size() > before;
                    }
                    if(onPrimaryValidationMiss != null)
                        onPrimaryValidationMiss.run();
                    return addNtkSlugWebtoonGeneratedImageCandidates(
                            client, path, seenImages, pageCount, true);
                }
                validatedPageExtensions.put(page, pageExtension);
            }
            rememberEarlyValidatedNtkGeneratedPages(client, path, segment, workId, imageEpisodeId,
                    imageExtension, validatedPageExtensions, validationPageCount);
            String pageTwoExtension = validatedPageExtensions.get(2);
            String pageCountExtension = pageTwoExtension != null && !pageTwoExtension.equals(imageExtension)
                    ? pageTwoExtension
                    : imageExtension;
            if(safePageCount > validationPageCount) {
                Log.d(TAG, "ntk_generated_page_count_probe_deferred path=" + path
                        + ",validated=" + validationPageCount
                        + ",safePageCount=" + safePageCount);
            }
            for(int page = 1; page <= safePageCount; page++) {
                String extension = validatedPageExtensions.containsKey(page)
                        ? validatedPageExtensions.get(page)
                        : page > 1 && pageTwoExtension != null && !pageTwoExtension.equals(imageExtension)
                        ? pageTwoExtension
                        : imageExtension;
                addImageIfValid(client, seenImages,
                        ntkGeneratedImageUrl(segment, workId, imageEpisodeId, page, extension));
            }
            return imgs != null && imgs.size() > before;
        }
        for(int page = 1; page <= safePageCount; page++) {
            String pageCacheKey = ntkGeneratedExtensionCacheKey(segment, workId, imageEpisodeId, page);
            String cachedPageExtension = cachedFreshNtkGeneratedImageExtension(pageCacheKey);
            if(cachedPageExtension != null && cachedPageExtension.length() == 0)
                continue;
            String extension = cachedPageExtension != null && cachedPageExtension.length() > 0
                    ? cachedPageExtension
                    : imageExtension;
            String src = ntkGeneratedImageUrl(segment, workId, imageEpisodeId, page, extension);
            addImageIfValid(client, seenImages, src);
        }
        return imgs != null && imgs.size() > before;
    }

    /**
     * Publishes the finite generated manifest proved by the initial page validator.
     *
     * The validator already treats the first unreachable page as the episode tail and returns
     * only the preceding pages to the real reader.  Keep that same result in the process-wide
     * prepared-manifest cache as well; otherwise an EpisodeActivity opened a moment later sees
     * the URLs and compressed files but no authoritative count, so it needlessly starts a second
     * network resolver instead of decoding those files for its attached reader surface.
     */
    private void publishValidatedNtkGeneratedTailProof(String path,
                                                        String segment,
                                                        String workId,
                                                        String imageEpisodeId,
                                                        String defaultExtension,
                                                        Map<Integer, String> validatedPageExtensions,
                                                        int pageCount,
                                                        int firstMissingPage) {
        if(path == null || path.length() == 0 || pageCount <= 0
                || validatedPageExtensions == null || firstMissingPage != pageCount + 1)
            return;
        String missingCacheKey = ntkGeneratedExtensionCacheKey(
                segment, workId, imageEpisodeId, firstMissingPage);
        String missingExtension = cachedFreshNtkGeneratedImageExtension(missingCacheKey);
        // An absent validation result can also mean timeout/interruption. Only persist a finite
        // manifest when the validator explicitly cached the next page as unreachable.
        if(missingExtension == null || missingExtension.length() != 0)
            return;
        ArrayList<String> authoritativeUrls = new ArrayList<>(pageCount);
        for(int page = 1; page <= pageCount; page++) {
            String extension = validatedPageExtensions.get(page);
            if((extension == null || extension.length() == 0) && page == 1)
                extension = defaultExtension;
            extension = normalizeNtkGeneratedImageExtension(extension);
            if(extension.length() == 0)
                return;
            authoritativeUrls.add(ntkGeneratedImageUrl(
                    segment, workId, imageEpisodeId, page, extension));
        }
        if(authoritativeUrls.size() != pageCount)
            return;
        setNtkImageCount(pageCount);
        ReaderImageCache.INSTANCE.rememberAuthoritativeNtkImageUrlsFromBrowser(
                path,
                authoritativeUrls,
                "generated-initial-tail-proof");
        Log.d(TAG, "ntk_generated_tail_proof_publish path=" + path
                + ",count=" + pageCount
                + ",missingPage=" + firstMissingPage);
    }

    private String trustedPrimaryNtkGeneratedExtension(String path, String segment, String pathWorkId,
                                                       String pathEpisodeId, String candidateWorkId,
                                                       String candidateEpisodeId) {
        return "";
    }

    private void rememberEarlyValidatedNtkGeneratedPages(CustomHttpClient client, String path,
                                                         String segment, String workId,
                                                         String imageEpisodeId, String defaultExtension,
                                                         Map<Integer, String> validatedPageExtensions,
                                                         int validationPageCount) {
        if(path == null || segment == null || workId == null || imageEpisodeId == null
                || validatedPageExtensions == null || validationPageCount <= 0)
            return;
        int safeCount = Math.min(validationPageCount, NTK_EARLY_INITIAL_PUBLISH_PAGES);
        ArrayList<String> urls = new ArrayList<>();
        for(int page = 1; page <= safeCount; page++) {
            String extension = validatedPageExtensions.get(page);
            if((extension == null || extension.length() == 0) && page == 1)
                extension = defaultExtension;
            if(extension == null || extension.length() == 0)
                continue;
            extension = normalizeNtkGeneratedImageExtension(extension);
            if(extension.length() == 0)
                continue;
            urls.add(ntkGeneratedImageUrl(segment, workId, imageEpisodeId, page, extension));
        }
        if(urls.isEmpty())
            return;
        Log.d(TAG, "ntk_generated_validated_urls_early path=" + path
                + ",count=" + urls.size()
                + ",first=" + safeLogImage(urls.get(0)));
        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
        startFirstNtkApiImageStream(client, path, urls);
    }

    private Map<Integer, String> validateNtkGeneratedInitialPageExtensions(CustomHttpClient client,
                                                                           String segment,
                                                                           String workId,
                                                                           String imageEpisodeId,
                                                                           String imageExtension,
                                                                           int validationPageCount) {
        Map<Integer, String> result = new ConcurrentHashMap<>();
        if(validationPageCount <= 1)
            return result;
        CountDownLatch done = new CountDownLatch(validationPageCount - 1);
        long startedAt = System.currentTimeMillis();
        for(int page = 2; page <= validationPageCount; page++) {
            final int probePage = page;
            Thread thread = new Thread(() -> {
                try {
                    String cacheKey = ntkGeneratedExtensionCacheKey(segment, workId, imageEpisodeId, probePage);
                    String cachedExtension = cachedFreshNtkGeneratedImageExtension(cacheKey);
                    if(cachedExtension != null) {
                        result.put(probePage, cachedExtension);
                        return;
                    }
                    String primaryUrl = ntkGeneratedImageUrl(segment, workId, imageEpisodeId, probePage, imageExtension);
                    if(awaitCachedNtkGeneratedImageAvailable(client, primaryUrl, 360L)) {
                        cacheNtkGeneratedImageExtension(cacheKey, imageExtension);
                        result.put(probePage, imageExtension);
                        publishValidatedEarlyNtkGeneratedImages(client, segment, workId, imageEpisodeId,
                                imageExtension, result);
                        return;
                    }
                    int quickHeaderReachability = ntkGeneratedImageQuickHeaderReachability(client, primaryUrl);
                    if(quickHeaderReachability > 0) {
                        cacheNtkGeneratedImageExtension(cacheKey, imageExtension);
                        result.put(probePage, imageExtension);
                        publishValidatedEarlyNtkGeneratedImages(client, segment, workId, imageEpisodeId,
                                imageExtension, result);
                        return;
                    }
                    if(quickHeaderReachability == 0) {
                        cacheNtkGeneratedImageExtension(cacheKey, "");
                        result.put(probePage, "");
                        return;
                    }
                    if(isNtkGeneratedImageReachable(client, primaryUrl)) {
                        cacheNtkGeneratedImageExtension(cacheKey, imageExtension);
                        result.put(probePage, imageExtension);
                        publishValidatedEarlyNtkGeneratedImages(client, segment, workId, imageEpisodeId,
                                imageExtension, result);
                        return;
                    }
                    String pageExtension = reachableNtkGeneratedImageExtension(
                            client, segment, workId, imageEpisodeId, probePage);
                    if(pageExtension.length() == 0)
                        cacheNtkGeneratedImageExtension(cacheKey, "");
                    result.put(probePage, pageExtension);
                    publishValidatedEarlyNtkGeneratedImages(client, segment, workId, imageEpisodeId,
                            imageExtension, result);
                } catch(Exception ignored) {
                } finally {
                    done.countDown();
                }
            }, "ntk-generated-initial-validate-" + probePage);
            thread.setDaemon(true);
            thread.start();
        }
        try {
            done.await(Math.max(1600L, NTK_EARLY_GENERATED_EXTENSION_WAIT_MS + 700L), TimeUnit.MILLISECONDS);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.d(TAG, "ntk_generated_initial_validation_parallel path=" + segment + "/" + workId + "/" + imageEpisodeId
                + ",pages=" + validationPageCount
                + ",checked=" + result.size()
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        return result;
    }

    private boolean isCachedNtkGeneratedImageAvailable(CustomHttpClient client, String src) {
        if(client == null || src == null || src.length() == 0)
            return false;
        try {
            return ReaderImageCache.INSTANCE.cachedExactFile(client.getContext(), this, src) != null;
        } catch(Exception ignored) {
            return false;
        }
    }

    private boolean awaitCachedNtkGeneratedImageAvailable(CustomHttpClient client, String src, long waitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMs);
        while(true) {
            if(isCachedNtkGeneratedImageAvailable(client, src))
                return true;
            if(System.currentTimeMillis() >= deadline)
                return false;
            try {
                Thread.sleep(16L);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void publishValidatedEarlyNtkGeneratedImages(CustomHttpClient client, String segment,
                                                         String workId, String imageEpisodeId,
                                                         String defaultExtension,
                                                         Map<Integer, String> pageExtensions) {
        if(pageExtensions == null || pageExtensions.isEmpty())
            return;
        int maxCount = 1;
        for(int page = 2; page <= NTK_EARLY_INITIAL_PUBLISH_PAGES; page++) {
            String extension = pageExtensions.get(page);
            if(extension != null && extension.length() > 0)
                maxCount = page;
        }
        if(maxCount <= 1)
            return;
        String path = getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return;
        ArrayList<String> urls = new ArrayList<>();
        urls.add(ntkGeneratedImageUrl(segment, workId, imageEpisodeId, 1,
                normalizeNtkGeneratedImageExtension(defaultExtension)));
        for(int page = 2; page <= maxCount; page++) {
            String extension = pageExtensions.get(page);
            if(extension == null || extension.length() == 0)
                continue;
            extension = normalizeNtkGeneratedImageExtension(extension);
            if(extension.length() == 0)
                continue;
            urls.add(ntkGeneratedImageUrl(segment, workId, imageEpisodeId, page, extension));
        }
        if(urls.size() <= 1)
            return;
        Log.d(TAG, "ntk_generated_validated_urls_incremental path=" + path
                + ",count=" + urls.size()
                + ",first=" + safeLogImage(urls.get(0)));
        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
        startFirstNtkApiImageStream(client, path, urls);
    }

    private int ntkGeneratedImageQuickHeaderReachability(CustomHttpClient client, String src) {
        if(client == null || src == null || src.length() == 0)
            return -1;
        if(shouldDeferModernNtkGeneratedProbeUntilAck(client, getNtkEpisodePath()))
            return -1;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
        String referer = client.getUrl(src.contains("/webtoon/")
                || src.contains("/blacktoon/episodes/")
                || src.contains("/wt/episodes/")
                ? MTitle.base_webtoon : MTitle.base_comic);
        headers.put("Referer", referer);
        headers.put("User-Agent", client.agent);
        String cookie = client.getCookieHeader();
        if(cookie != null && cookie.length() > 0)
            headers.put("Cookie", cookie);
        headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Sec-Fetch-Dest", "image");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("Sec-Fetch-Site", secFetchSiteForImageReferer(referer, src));
        headers.put("Accept-Encoding", "identity");
        return client.ntkImageHeaderReachability(src, headers, NTK_EARLY_GENERATED_HEADER_PROBE_MS);
    }

    private List<String> fetchNaverWebtoonImageUrlsForNvEpisode(CustomHttpClient client,
                                                                String tokenEpisodeId,
                                                                String pathEpisodeId,
                                                                int limit) {
        ArrayList<String> urls = new ArrayList<>();
        if(client == null)
            return urls;
        String nvEpisode = "";
        if(tokenEpisodeId != null && tokenEpisodeId.matches("(?i)^(?:naver|nv)-\\d{5,}-\\d+$"))
            nvEpisode = tokenEpisodeId.trim();
        else if(pathEpisodeId != null && pathEpisodeId.matches("(?i)^(?:naver|nv)-\\d{5,}-\\d+$"))
            nvEpisode = pathEpisodeId.trim();
        if(nvEpisode.length() == 0)
            return urls;
        Matcher matcher = Pattern.compile("(?i)^(?:naver|nv)-(\\d{5,})-(\\d+)$").matcher(nvEpisode);
        if(!matcher.find())
            return urls;
        String titleId = matcher.group(1);
        String episodeNo = matcher.group(2);
        String desktopDetailUrl = "https://comic.naver.com/webtoon/detail?titleId="
                + titleId + "&no=" + episodeNo;
        String mobileDetailUrl = "https://m.comic.naver.com/webtoon/detail?titleId="
                + titleId + "&no=" + episodeNo;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/125.0.0.0 Safari/537.36");
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Referer", "https://comic.naver.com/webtoon/list?titleId=" + titleId);
            String[] fastUrls = new String[]{desktopDetailUrl, mobileDetailUrl};
            for(String detailUrl : fastUrls) {
                try {
                    CustomHttpClient.PageResponse page = client.fetchExternalViewerPageFast(
                            detailUrl, headers);
                    if(appendNaverWebtoonPageImages(urls, page, titleId, episodeNo, limit)) {
                        Log.d(TAG, "ntk_naver_original_fetch episode=" + nvEpisode
                                + ",source=external-fast"
                                + ",url=" + detailUrl
                                + ",code=" + page.code
                                + ",count=" + urls.size()
                                + ",limit=" + limit
                                + ",first=" + (urls.isEmpty() ? "" : safeLogImage(urls.get(0))));
                        return urls;
                    }
                    Log.d(TAG, "ntk_naver_original_fetch_miss episode=" + nvEpisode
                            + ",source=external-fast"
                            + ",url=" + detailUrl
                            + ",code=" + (page == null ? -1 : page.code)
                            + ",count=" + urls.size());
                } catch(Exception fastError) {
                    Log.d(TAG, "ntk_naver_original_fetch_fast_error episode=" + nvEpisode
                            + ",url=" + detailUrl
                            + "," + fastError);
                }
                urls.clear();
            }
            CustomHttpClient.PageResponse page = client.probeNtkFragmentedOkHttpForTest(
                    desktopDetailUrl, "GET", headers, null);
            if(!appendNaverWebtoonPageImages(urls, page, titleId, episodeNo, limit)) {
                Log.d(TAG, "ntk_naver_original_fetch_miss episode=" + nvEpisode
                        + ",source=fragmented"
                        + ",code=" + (page == null ? -1 : page.code));
                return urls;
            }
            Log.d(TAG, "ntk_naver_original_fetch episode=" + nvEpisode
                    + ",source=fragmented"
                    + ",code=" + page.code
                    + ",count=" + urls.size()
                    + ",limit=" + limit
                    + ",first=" + (urls.isEmpty() ? "" : safeLogImage(urls.get(0))));
        } catch(Exception e) {
            Log.d(TAG, "ntk_naver_original_fetch_error episode=" + nvEpisode + "," + e);
        }
        return urls;
    }

    public List<String> fetchNaverWebtoonOriginalImageUrlsForNtkPath(CustomHttpClient client, int limit) {
        String path = getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return new ArrayList<>();
        Matcher matcher = Pattern.compile("(?i)^/webtoon/[^/?#]+/((?:naver|nv)-\\d{5,}-\\d+)(?:[/?#].*)?$")
                .matcher(path.trim());
        if(!matcher.find())
            return new ArrayList<>();
        return fetchNaverWebtoonImageUrlsForNvEpisode(client, null, matcher.group(1), limit);
    }

    private static boolean appendNaverWebtoonPageImages(List<String> urls,
                                                        CustomHttpClient.PageResponse page,
                                                        String titleId,
                                                        String episodeNo,
                                                        int limit) {
        if(urls == null || page == null || page.code < 200 || page.code >= 300
                || page.body == null || page.body.length() == 0)
            return false;
        String body = page.body.replace("\\/", "/").replace("&amp;", "&");
        Pattern imagePattern = Pattern.compile(
                "https://image-comic\\.pstatic\\.net/webtoon/"
                        + Pattern.quote(titleId) + "/"
                        + Pattern.quote(episodeNo)
                        + "/[^\"'<>\\\\\\s]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^\"'<>\\\\\\s]*)?",
                Pattern.CASE_INSENSITIVE);
        Matcher imageMatcher = imagePattern.matcher(body);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for(String existing : urls) {
            if(existing != null)
                seen.add(existing);
        }
        int safeLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        while(imageMatcher.find() && seen.size() < safeLimit) {
            String url = imageMatcher.group();
            if(isNaverWebtoonPageImage(url) && seen.add(url))
                urls.add(url);
        }
        return !urls.isEmpty();
    }

    private static String secFetchSiteForImageReferer(String referer, String imageUrl) {
        try {
            URI refererUri = URI.create(referer);
            URI imageUri = URI.create(imageUrl);
            String refererHost = refererUri.getHost();
            String imageHost = imageUri.getHost();
            if(refererHost == null || imageHost == null)
                return "same-origin";
            return refererHost.equalsIgnoreCase(imageHost) ? "same-origin" : "cross-site";
        } catch(Exception ignored) {
            return "same-origin";
        }
    }

    private void addValidatedNtkGeneratedPages(CustomHttpClient client, Set<String> seenImages,
                                               String segment, String workId, String imageEpisodeId,
                                               String primaryExtension,
                                               Map<Integer, String> validatedPageExtensions,
                                               int pageCount) {
        String pageTwoExtension = validatedPageExtensions == null ? null : validatedPageExtensions.get(2);
        for(int page = 1; page <= pageCount; page++) {
            String extension = validatedPageExtensions != null && validatedPageExtensions.containsKey(page)
                    ? validatedPageExtensions.get(page)
                    : page > 1 && pageTwoExtension != null && !pageTwoExtension.equals(primaryExtension)
                    ? pageTwoExtension
                    : primaryExtension;
            if(extension == null || extension.length() == 0)
                continue;
            addImageIfValid(client, seenImages,
                    ntkGeneratedImageUrl(segment, workId, imageEpisodeId, page, extension));
        }
    }

    private boolean addNtkSlugWebtoonGeneratedImageCandidates(CustomHttpClient client, String path,
                                                              Set<String> seenImages, int pageCount,
                                                              boolean validateFirstImage) {
        if(client == null || path == null || seenImages == null || pageCount <= 0)
            return false;
        if(isNtkSyntheticWebtoonEpisodePath(path)) {
            Log.d(TAG, "ntk_slug_generated_skip_api_only path=" + path);
            return false;
        }
        Matcher pathMatcher = Pattern.compile("^/webtoon/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$").matcher(path);
        if(!pathMatcher.find())
            return false;
        String pathWorkId = pathMatcher.group(1);
        String episodeId = pathMatcher.group(2);
        if(isNtkKpEpisodeId(episodeId))
            return false;
        if(pathWorkId.matches("\\d+"))
            return false;
        String slug = pathWorkId.matches("\\d+") ? "" : pathWorkId;
        if(slug.length() == 0)
            slug = ntkCanonicalWebtoonSlugCandidate(title == null ? "" : title.getPath(),
                    title == null ? "" : title.getName());
        if(slug.length() == 0)
            slug = ntkCanonicalWebtoonSlugCandidate("", name);
        if(slug.length() == 0)
            return false;
        String extension = reachableNtkSlugWebtoonImageExtension(client, slug, episodeId, 1);
        if(extension.length() == 0)
            return false;
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        if(validateFirstImage) {
            int validationPageCount = ntkGeneratedInitialValidationPageCount(safePageCount);
            for(int page = 2; page <= validationPageCount; page++) {
                String next = ntkSlugWebtoonImageUrl(slug, episodeId, page, extension);
                if(!isNtkGeneratedImageReachable(client, next))
                    return false;
            }
        }
        int before = imgs == null ? 0 : imgs.size();
        for(int page = 1; page <= safePageCount; page++)
            addImageIfValid(client, seenImages, ntkSlugWebtoonImageUrl(slug, episodeId, page, extension));
        return imgs != null && imgs.size() > before;
    }

    private boolean addNtkLastResortWebtoonGeneratedImageCandidates(CustomHttpClient client, String body,
                                                                    String path, Set<String> seenImages,
                                                                    int pageCount) {
        if(client == null || path == null || seenImages == null || pageCount <= 0)
            return false;
        Matcher pathMatcher = Pattern.compile("^/webtoon/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String pathWorkId = pathMatcher.group(1);
        String pathEpisodeId = pathMatcher.group(2);
        if(isNtkKpEpisodeId(pathEpisodeId))
            return false;
        String normalized = normalizeNtkViewerPayloadText(body == null ? "" : body);
        String token = ntkViewerImagesToken(normalized);
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String sourceWorkId = ntkViewerSourceWorkId(normalized);
        String thumbWorkId = ntkViewerThumbWorkId(normalized);
        LinkedHashSet<String> numericWorkIds = new LinkedHashSet<>();
        addNtkCandidateIfNumeric(numericWorkIds, tokenWorkId);
        addNtkCandidateIfNumeric(numericWorkIds, sourceWorkId);
        addNtkCandidateIfNumeric(numericWorkIds, getNtkImageWorkId());
        addNtkCandidateIfNumeric(numericWorkIds, pathWorkId);
        addNtkCandidateIfNumeric(numericWorkIds, String.valueOf(titleId));
        if(title != null)
            addNtkCandidateIfNumeric(numericWorkIds, String.valueOf(title.getId()));
        addNtkCandidateIfNumeric(numericWorkIds, thumbWorkId);

        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        addNtkCandidateIfNumeric(episodeIds, tokenEpisodeId);
        addNtkCandidateIfNumeric(episodeIds,
                ntkKnownImageEpisodeIdForGeneratedCandidate(tokenEpisodeId, pathEpisodeId));
        addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        addNtkCandidateIfNumeric(episodeIds,
                ntkMetadataImageEpisodeIdForGeneratedCandidate(embeddedEpisodeId, tokenEpisodeId, pathEpisodeId));
        LinkedHashSet<String> slugEpisodeIds = new LinkedHashSet<>(episodeIds);
        addNtkEpisodeCandidate(slugEpisodeIds, tokenEpisodeId);
        addNtkEpisodeCandidate(slugEpisodeIds,
                ntkKnownImageEpisodeIdForGeneratedCandidate(tokenEpisodeId, pathEpisodeId));
        addNtkEpisodeCandidate(slugEpisodeIds, getNtkImageEpisodeId());
        addNtkEpisodeCandidate(slugEpisodeIds, pathEpisodeId);
        addNtkEpisodeCandidate(slugEpisodeIds,
                ntkMetadataImageEpisodeIdForGeneratedCandidate(embeddedEpisodeId, tokenEpisodeId, pathEpisodeId));

        LinkedHashSet<String> slugs = new LinkedHashSet<>();
        slugs.addAll(ntkSlugWebtoonWorkCandidates(pathWorkId));
        addNtkSlugCandidate(slugs, ntkCanonicalWebtoonSlugCandidate(title == null ? "" : title.getPath(),
                title == null ? "" : title.getName()));
        addNtkSlugCandidate(slugs, ntkCanonicalWebtoonSlugCandidate("", name));
        addNtkSlugCandidate(slugs, ntkViewerWtEpisodeSlug(normalized));

        int before = imgs == null ? 0 : imgs.size();
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        int probes = 0;
        for(String workId : numericWorkIds) {
            for(String episodeId : episodeIds) {
                if(++probes > NTK_LAST_RESORT_GENERATED_PROBE_LIMIT)
                    return imgs != null && imgs.size() > before;
                if(addValidatedNtkGeneratedBaseImages(client, seenImages, "webtoon", workId, episodeId, safePageCount))
                    return true;
            }
        }
        for(String slug : slugs) {
            for(String episodeId : slugEpisodeIds) {
                if(++probes > NTK_LAST_RESORT_GENERATED_PROBE_LIMIT)
                    return imgs != null && imgs.size() > before;
                if(addValidatedNtkSlugWebtoonBaseImages(client, seenImages, slug, episodeId, safePageCount))
                    return true;
            }
        }
        return imgs != null && imgs.size() > before;
    }

    private boolean addValidatedNtkGeneratedBaseImages(CustomHttpClient client, Set<String> seenImages,
                                                       String segment, String workId, String episodeId,
                                                       int pageCount) {
        String path = getNtkEpisodePath();
        if(addCachedNtkViewerImageApiCandidates(client, path, seenImages))
            return true;
        AtomicBoolean apiUrlsInstalled = new AtomicBoolean(false);
        if(client == null)
            return false;
        if("webtoon".equals(segment) && shouldPreferNtkApiForCanonicalWebtoonPath(path)) {
            List<String> earlyUrls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(
                    path, android.os.SystemClock.elapsedRealtime() - 30000L);
            for(String earlyUrl : earlyUrls) {
                if(earlyUrl != null && earlyUrl.contains("/wt/episodes/")) {
                    Log.d(TAG, "ntk_canonical_generated_skip_verified_wt path=" + path
                            + ",workId=" + workId
                            + ",episodeId=" + episodeId
                            + ",earlyCount=" + earlyUrls.size());
                    return false;
                }
            }
            Log.d(TAG, "ntk_canonical_generated_skip_api_first path=" + path
                    + ",workId=" + workId
                    + ",episodeId=" + episodeId);
            return false;
        }
        String extension = reachableNtkGeneratedImageExtension(client, segment, workId, episodeId, 1,
                () -> apiUrlsInstalled.set(addCachedNtkViewerImageApiCandidates(client, path, seenImages)));
        if(apiUrlsInstalled.get() || addCachedNtkViewerImageApiCandidates(client, path, seenImages))
            return true;
        if(extension.length() == 0)
            return false;
        int safePageCount = shouldValidateNtkGeneratedInitialPages()
                ? reachableNtkGeneratedPageCount(client, segment, workId, episodeId, extension, pageCount)
                : pageCount;
        if(addCachedNtkViewerImageApiCandidates(client, path, seenImages))
            return true;
        int before = imgs == null ? 0 : imgs.size();
        for(int page = 1; page <= safePageCount; page++)
            addImageIfValid(client, seenImages, ntkGeneratedImageUrl(segment, workId, episodeId, page, extension));
        return imgs != null && imgs.size() > before;
    }

    private boolean addValidatedNtkSlugWebtoonBaseImages(CustomHttpClient client, Set<String> seenImages,
                                                         String slug, String episodeId, int pageCount) {
        if(client == null)
            return false;
        if(isNtkSyntheticWebtoonEpisodePath(getNtkEpisodePath())) {
            Log.d(TAG, "ntk_slug_base_skip_api_only path=" + getNtkEpisodePath()
                    + ",slug=" + slug
                    + ",episodeId=" + episodeId);
            return false;
        }
        String extension = reachableNtkSlugWebtoonImageExtension(client, slug, episodeId, 1);
        if(extension.length() == 0)
            return false;
        int safePageCount = shouldValidateNtkGeneratedInitialPages()
                ? reachableNtkSlugWebtoonPageCount(client, slug, episodeId, extension, pageCount)
                : pageCount;
        int before = imgs == null ? 0 : imgs.size();
        for(int page = 1; page <= safePageCount; page++)
            addImageIfValid(client, seenImages, ntkSlugWebtoonImageUrl(slug, episodeId, page, extension));
        return imgs != null && imgs.size() > before;
    }

    private static void addNtkCandidateIfNumeric(LinkedHashSet<String> candidates, String value) {
        if(candidates == null || value == null)
            return;
        String trimmed = value.trim();
        if(trimmed.matches("\\d+"))
            candidates.add(trimmed);
    }

    private static String firstNumericNtkCandidate(LinkedHashSet<String> candidates) {
        if(candidates == null)
            return "";
        for(String candidate : candidates) {
            if(isNumericNtkId(candidate))
                return candidate;
        }
        return "";
    }

    private static boolean shouldUseNtkThumbWorkIdForCanonicalGenerated(String pathWorkId,
                                                                        String tokenWorkId,
                                                                        String sourceWorkId,
                                                                        String thumbWorkId) {
        if(!isNumericNtkId(thumbWorkId))
            return false;
        return true;
    }

    private static void addNtkSlugCandidate(LinkedHashSet<String> candidates, String value) {
        if(candidates == null || value == null)
            return;
        String trimmed = value.trim();
        if(trimmed.length() > 0 && !trimmed.matches("\\d+"))
            candidates.add(trimmed);
    }

    private static void addNtkEpisodeCandidate(LinkedHashSet<String> candidates, String value) {
        if(candidates == null || value == null)
            return;
        String trimmed = value.trim();
        if(trimmed.length() > 0)
            candidates.add(trimmed);
    }

    private static String ntkViewerWtEpisodeSlug(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("/wt/episodes/([^/?#]+)/[^/?#]+/p\\d{3}\\.",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        return matcher.find() ? matcher.group(1) : "";
    }

    private List<String> earlyWorkTitleWtImageUrlsFromPartial(CustomHttpClient client,
                                                              String path,
                                                              String normalized,
                                                              String pathEpisodeId,
                                                              String tokenEpisodeId,
                                                              int pageCount,
                                                              int limit) {
        ArrayList<String> urls = new ArrayList<>();
        if(path != null && path.matches("^/webtoon/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$"))
            return urls;
        String slug = ntkViewerWorkTitleSlug(normalized);
        if(slug.length() == 0 || slug.matches("\\d{1,12}"))
            return urls;
        String episodeId = tokenEpisodeId != null && tokenEpisodeId.matches("\\d{1,12}")
                ? tokenEpisodeId
                : pathEpisodeId;
        if(episodeId == null || !episodeId.matches("\\d{1,12}"))
            return urls;
        int safePageCount = pageCount > 0 ? pageCount : getNtkImageCount();
        if(safePageCount <= 0)
            safePageCount = ntkGeneratedImageCandidateCount();
        safePageCount = Math.min(Math.min(safePageCount, NTK_MAX_GENERATED_PAGE_COUNT), Math.max(1, limit));
        if(safePageCount <= 0)
            return urls;
        for(int page = 1; page <= safePageCount; page++) {
            urls.add("https://fifa.worldcup73.xyz/wt/episodes/" + slug + "/" + episodeId
                    + "/p" + String.format(Locale.ROOT, "%03d", page) + ".jpg");
        }
        List<String> initialUrls = urls.size() > NTK_EARLY_INITIAL_PUBLISH_PAGES
                ? new ArrayList<>(urls.subList(0, NTK_EARLY_INITIAL_PUBLISH_PAGES))
                : urls;
        Log.d(TAG, "ntk_work_title_wt_partial_urls path=" + path
                + ",slug=" + slug
                + ",episodeId=" + episodeId
                + ",count=" + urls.size()
                + ",initialCount=" + initialUrls.size()
                + ",first=" + safeLogImage(urls.get(0)));
        String publishKey = "work-title-wt|" + (path == null ? "" : path) + "|" + slug + "|" + episodeId;
        if(firstNtkApiImageStreamStarts().putIfAbsent(publishKey, Boolean.TRUE) != null)
            return urls;
        startFirstNtkApiImageStream(client, path, initialUrls, false);
        try {
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, initialUrls);
        } catch(Exception e) {
            Log.d(TAG, "ntk_work_title_wt_partial_urls_remember_error path=" + path + "," + e);
        }
        if(urls.size() > initialUrls.size()) {
            Thread publishFull = new Thread(() -> {
                try {
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
                    Log.d(TAG, "ntk_work_title_wt_full_urls_remember path=" + path
                            + ",count=" + urls.size());
                } catch(Exception e) {
                    Log.d(TAG, "ntk_work_title_wt_full_urls_remember_error path=" + path + "," + e);
                }
            }, "ntk-work-title-wt-full-urls");
            publishFull.setDaemon(true);
            publishFull.start();
        }
        return urls;
    }

    private static String ntkViewerWorkTitleSlug(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("\"workTitle\"\\s*:\\s*\"([^\"\\\\]{1,120})\"",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(matcher.find())
            return sanitizeNtkWtSlug(matcher.group(1));
        matcher = Pattern.compile("\\\\\"workTitle\\\\\"\\s*:\\s*\\\\\"([^\"\\\\]{1,120})\\\\\"",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        return matcher.find() ? sanitizeNtkWtSlug(matcher.group(1)) : "";
    }

    private static String sanitizeNtkWtSlug(String value) {
        if(value == null)
            return "";
        String trimmed = value.trim();
        if(trimmed.length() == 0 || trimmed.length() > 120)
            return "";
        if(trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0
                || trimmed.indexOf('?') >= 0 || trimmed.indexOf('#') >= 0)
            return "";
        return trimmed;
    }

    private static String ntkGeneratedImageUrl(String segment, String workId, String episodeId, int page, String extension) {
        String safeExtension = normalizeNtkGeneratedImageExtension(extension);
        if(safeExtension.length() == 0)
            throw new IllegalArgumentException("Missing NTK generated image extension");
        if("webtoon".equals(segment)) {
            return String.format(Locale.ROOT,
                    "http://fifa.worldcup73.xyz/black/episodes/%s/%s/p%03d.%s",
                    workId, episodeId, page, safeExtension);
        }
        if("manhwa".equals(segment)) {
            return String.format(Locale.ROOT,
                    "https://booktoki9.org/manhwa/%s/%s/p%03d.%s",
                    workId, episodeId, page, safeExtension);
        }
        return String.format(Locale.ROOT,
                "http://apihost93.com/%s/%s/%s/p%03d.%s",
                segment, workId, episodeId, page, safeExtension);
    }

    private static String ntkSlugWebtoonImageUrl(String slug, String episodeId, int page, String extension) {
        String safeExtension = normalizeNtkGeneratedImageExtension(extension);
        if(safeExtension.length() == 0)
            throw new IllegalArgumentException("Missing NTK slug image extension");
        String safeSlug = slug == null ? "" : slug.trim();
        if(safeSlug.matches("\\d+")) {
            return String.format(Locale.ROOT,
                    "https://moamoabon.com/blacktoon/episodes/%s/%s/p%03d.%s",
                    safeSlug, episodeId, page, safeExtension);
        }
        return String.format(Locale.ROOT,
                "https://i.toonflix.app/wt/episodes/%s/%s/p%03d.%s",
                safeSlug, episodeId, page, safeExtension);
    }

    private static String normalizeNtkGeneratedImageExtension(String extension) {
        if(extension == null)
            return "";
        String trimmed = extension.trim().toLowerCase(Locale.ROOT);
        return trimmed.matches("jpg|jpeg|webp|png") ? trimmed : "";
    }

    private static String ntkViewerThumbWorkId(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("/(?:blacktoon/)?thumbs/(\\d{1,12})\\.(?:png|jpg|jpeg|webp)",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("https?://(?:[^/]+\\.)?(?:g\\d+cm\\.net|scloud\\d+\\.com|vcloud\\d+\\.com|cloudfront\\.net)/(\\d{1,12})/[^\"'<>\\s]+\\.(?:png|jpg|jpeg|webp)",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String ntkViewerSourceWorkId(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("\"sourceWorkId\"\\s*:\\s*\"(\\d{1,12})\"",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\"sourceWorkId\"\\s*:\\s*(\\d{1,12})",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\\\\\"sourceWorkId\\\\\"\\s*:\\s*\\\\\"(\\d{1,12})\\\\\"",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\\\\\"sourceWorkId\\\\\"\\s*:\\s*(\\d{1,12})",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String ntkViewerRefId(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("\"refId\"\\s*:\\s*\"(\\d{1,12})\"",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\"refId\"\\s*:\\s*(\\d{1,12})",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\\\\\"refId\\\\\"\\s*:\\s*\\\\\"(\\d{1,12})\\\\\"",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\\\\\"refId\\\\\"\\s*:\\s*(\\d{1,12})",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void addNtkGeneratedWorkIdsFromText(LinkedHashSet<String> workIds,
                                                       String normalized,
                                                       String imageEpisodeId) {
        if(workIds == null || normalized == null || normalized.length() == 0
                || !isNumericNtkId(imageEpisodeId))
            return;
        int before = workIds.size();
        Pattern directPattern = Pattern.compile(
                "(?i)i\\.toonflix\\.app/(?:blacktoon/episodes|manhwa|webtoon)/(\\d{1,12})/"
                        + Pattern.quote(imageEpisodeId)
                        + "/p\\d{3}\\.(?:jpg|jpeg|png|webp)");
        Matcher directMatcher = directPattern.matcher(normalized);
        while(directMatcher.find() && workIds.size() - before < 8)
            addNtkCandidateIfNumeric(workIds, directMatcher.group(1));
        Matcher episodeMatcher = Pattern.compile(Pattern.quote(imageEpisodeId)).matcher(normalized);
        int windows = 0;
        while(episodeMatcher.find() && windows++ < 8 && workIds.size() - before < 8) {
            int start = Math.max(0, episodeMatcher.start() - 900);
            int end = Math.min(normalized.length(), episodeMatcher.end() + 900);
            String window = normalized.substring(start, end);
            addFirstNtkGeneratedWorkIdsFromWindow(workIds, window, before, 8);
        }
        if(workIds.size() > before)
            Log.d(TAG, "ntk_generated_text_work_candidates episodeId=" + imageEpisodeId
                    + ",added=" + (workIds.size() - before)
                    + ",workIds=" + workIds);
    }

    private static void addFirstNtkGeneratedWorkIdsFromWindow(LinkedHashSet<String> workIds,
                                                              String window,
                                                              int before,
                                                              int maxAdded) {
        if(workIds == null || window == null || window.length() == 0)
            return;
        String[] patterns = new String[]{
                "\"refId\"\\s*:\\s*\"?(\\d{1,12})\"?",
                "\"sourceWorkId\"\\s*:\\s*\"?(\\d{1,12})\"?",
                "\"workId\"\\s*:\\s*\"?(\\d{1,12})\"?",
                "\"w\"\\s*:\\s*\"?(\\d{1,12})\"?",
                "/(?:blacktoon/)?thumbs/(\\d{1,12})\\.(?:png|jpg|jpeg|webp)"
        };
        for(String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(window);
            while(matcher.find() && workIds.size() - before < maxAdded)
                addNtkCandidateIfNumeric(workIds, matcher.group(1));
            if(workIds.size() - before >= maxAdded)
                return;
        }
    }

    private static String ntkApiEpisodeIdForPath(String pathEpisodeId) {
        if(pathEpisodeId == null)
            return "";
        String trimmed = pathEpisodeId.trim();
        if(trimmed.length() == 0)
            return "";
        if(trimmed.matches("\\d+"))
            return trimmed;
        return ntkApiEpisodeIdFromPathEpisodeId(trimmed);
    }

    private static String ntkViewerApiImageEpisodeId(String tokenEpisodeId, String knownImageEpisodeId,
                                                     String pathEpisodeId, String embeddedEpisodeId) {
        String rawTokenEpisode = tokenEpisodeId == null ? "" : tokenEpisodeId.trim();
        String rawPathEpisode = pathEpisodeId == null ? "" : pathEpisodeId.trim();
        String tokenImageEpisodeId = rawTokenEpisode.matches("\\d+") ? rawTokenEpisode : "";
        String knownImageEpisode = ntkApiEpisodeIdForPath(knownImageEpisodeId);
        String embeddedImageEpisode = ntkApiEpisodeIdForPath(embeddedEpisodeId);
        String pathImageEpisode = rawPathEpisode.matches("\\d+") ? rawPathEpisode : "";
        if(isNumericNtkId(tokenImageEpisodeId))
            return tokenImageEpisodeId;
        if(isNumericNtkId(knownImageEpisode))
            return knownImageEpisode;
        if(isNumericNtkId(pathImageEpisode))
            return pathImageEpisode;
        if(isNumericNtkId(embeddedImageEpisode))
            return embeddedImageEpisode;
        if(tokenImageEpisodeId.length() > 0)
            return tokenImageEpisodeId;
        if(knownImageEpisode.length() > 0)
            return knownImageEpisode;
        if(embeddedImageEpisode.length() > 0)
            return embeddedImageEpisode;
        return pathImageEpisode;
    }

    private static String ntkPreferredViewerImagesApiEpisodeId(String tokenEpisodeId,
                                                               String imageEpisodeId,
                                                               String pathEpisodeId) {
        String path = pathEpisodeId == null ? "" : pathEpisodeId.trim();
        String rawTokenEpisode = tokenEpisodeId == null ? "" : tokenEpisodeId.trim();
        String tokenImageEpisode = rawTokenEpisode.matches("\\d+") ? rawTokenEpisode : "";
        String resolvedImageEpisode = ntkApiEpisodeIdForPath(imageEpisodeId);
        if(isNumericNtkId(tokenImageEpisode))
            return tokenImageEpisode;
        if(!path.matches("\\d+") && isNumericNtkId(resolvedImageEpisode))
            return resolvedImageEpisode;
        if(resolvedImageEpisode.length() > 0)
            return resolvedImageEpisode;
        if(rawTokenEpisode.length() > 0)
            return rawTokenEpisode;
        return path;
    }

    private static boolean shouldRetryNtkKnownImageEpisodeId(String tokenEpisodeId,
                                                             String pathEpisodeId,
                                                             String apiEpisodeId,
                                                             String knownImageEpisodeId,
                                                             int knownImageCount) {
        String tokenImageEpisode = ntkApiEpisodeIdForPath(tokenEpisodeId);
        String pathImageEpisode = ntkApiEpisodeIdForPath(pathEpisodeId);
        String apiImageEpisode = ntkApiEpisodeIdForPath(apiEpisodeId);
        String knownImageEpisode = ntkApiEpisodeIdForPath(knownImageEpisodeId);
        if(knownImageEpisode.length() == 0 || knownImageEpisode.equals(apiImageEpisode))
            return false;
        String tokenEpisode = tokenEpisodeId == null ? "" : tokenEpisodeId.trim();
        if(tokenEpisode.length() > 0
                && tokenImageEpisode.length() > 0
                && !isNumericNtkId(tokenImageEpisode)
                && !knownImageEpisode.equals(tokenImageEpisode))
            return false;
        boolean trustedKnownImageMetadata = knownImageCount > 0;
        if(isNumericNtkId(tokenImageEpisode)
                && !knownImageEpisode.equals(tokenImageEpisode))
            return false;
        if(isNumericNtkId(pathImageEpisode)
                && pathImageEpisode.equals(apiImageEpisode)
                && !knownImageEpisode.equals(pathImageEpisode)
                && !trustedKnownImageMetadata)
            return false;
        return true;
    }

    private static String firstNtkImageEpisodeId(String tokenEpisodeId, String knownImageEpisodeId,
                                                 String pathEpisodeId, String embeddedEpisodeId) {
        String tokenImageEpisodeId = ntkApiEpisodeIdForPath(tokenEpisodeId);
        String knownImageEpisode = ntkApiEpisodeIdForPath(knownImageEpisodeId);
        String pathImageEpisode = ntkApiEpisodeIdForPath(pathEpisodeId);
        String embeddedImageEpisode = ntkApiEpisodeIdForPath(embeddedEpisodeId);
        if(isNumericNtkId(tokenImageEpisodeId))
            return tokenImageEpisodeId;
        if(isNumericNtkId(knownImageEpisode))
            return knownImageEpisode;
        if(isNumericNtkId(pathImageEpisode))
            return pathImageEpisode;
        if(isNumericNtkId(embeddedImageEpisode))
            return embeddedImageEpisode;
        if(tokenImageEpisodeId.length() > 0)
            return tokenImageEpisodeId;
        if(knownImageEpisode.length() > 0)
            return knownImageEpisode;
        if(pathImageEpisode.length() > 0)
            return pathImageEpisode;
        return embeddedImageEpisode;
    }

    private static String ntkGeneratedEpisodeIdForPath(String path) {
        if(path == null || path.length() == 0)
            return "";
        String kpEpisodeId = ntkKpEpisodeIdForPath(path);
        if(kpEpisodeId.length() > 0)
            return kpEpisodeId;
        Matcher matcher = Pattern.compile("^/(?:manhwa|webtoon)/[^/?#]+/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return "";
        String pathEpisodeId = matcher.group(1);
        return ntkApiEpisodeIdFromPathEpisodeId(pathEpisodeId);
    }

    private LinkedHashSet<String> ntkGeneratedWorkIdCandidatesForPath(String segment, String pathWorkId) {
        LinkedHashSet<String> workIds = new LinkedHashSet<>();
        addNtkCandidateIfNumeric(workIds, getNtkImageWorkId());
        addNtkCandidateIfNumeric(workIds, pathWorkId);
        if(title != null)
            addNtkCandidateIfNumeric(workIds, String.valueOf(title.getId()));
        addNtkCandidateIfNumeric(workIds, String.valueOf(titleId));
        return workIds;
    }

    private LinkedHashSet<String> ntkGeneratedEpisodeIdCandidatesForPath(String path, String pathEpisodeId) {
        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        String generatedPathEpisodeId = ntkGeneratedEpisodeIdForPath(path);
        String numericPathEpisodeId = isNumericNtkId(generatedPathEpisodeId)
                ? generatedPathEpisodeId
                : (isNumericNtkId(pathEpisodeId) ? pathEpisodeId : "");
        if(numericPathEpisodeId.length() > 0) {
            addNtkCandidateIfNumeric(episodeIds, numericPathEpisodeId);
            String knownImageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
            if(numericPathEpisodeId.equals(knownImageEpisodeId))
                addNtkCandidateIfNumeric(episodeIds, knownImageEpisodeId);
            return episodeIds;
        }
        addNtkCandidateIfNumeric(episodeIds, getNtkImageEpisodeId());
        addNtkCandidateIfNumeric(episodeIds, generatedPathEpisodeId);
        addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
        addNtkCandidateIfNumeric(episodeIds,
                ntkKnownImageEpisodeIdForGeneratedCandidate("", pathEpisodeId));
        return episodeIds;
    }

    private String ntkKnownImageEpisodeIdForGeneratedCandidate(String tokenEpisodeId, String pathEpisodeId) {
        return ntkMetadataImageEpisodeIdForGeneratedCandidate(
                getNtkImageEpisodeId(), tokenEpisodeId, pathEpisodeId);
    }

    private static String ntkMetadataImageEpisodeIdForGeneratedCandidate(String candidateEpisodeId,
                                                                         String tokenEpisodeId,
                                                                         String pathEpisodeId) {
        String candidateEpisode = ntkApiEpisodeIdForPath(candidateEpisodeId);
        if(candidateEpisode.length() == 0)
            return "";
        if(isNumericNtkId(candidateEpisode))
            return candidateEpisode;
        String tokenImageEpisode = ntkApiEpisodeIdForPath(tokenEpisodeId);
        String pathImageEpisode = ntkApiEpisodeIdForPath(pathEpisodeId);
        if(isNumericNtkId(tokenImageEpisode) && !candidateEpisode.equals(tokenImageEpisode))
            return "";
        if(isNumericNtkId(pathImageEpisode) && !candidateEpisode.equals(pathImageEpisode))
            return "";
        return candidateEpisode;
    }

    private String reachableNtkGeneratedImageExtension(CustomHttpClient client, String segment, String workId,
                                                       String episodeId, int page) {
        return reachableNtkGeneratedImageExtension(
                client, segment, workId, episodeId, page, null,
                ReaderImageCache.INSTANCE.cacheGenerationForProducer());
    }

    private String reachableNtkGeneratedImageExtension(CustomHttpClient client, String segment, String workId,
                                                       String episodeId, int page, Runnable onPrimaryValidationMiss) {
        return reachableNtkGeneratedImageExtension(
                client, segment, workId, episodeId, page, onPrimaryValidationMiss,
                ReaderImageCache.INSTANCE.cacheGenerationForProducer());
    }

    private String reachableNtkGeneratedImageExtension(CustomHttpClient client, String segment, String workId,
                                                       String episodeId, int page,
                                                       Runnable onPrimaryValidationMiss,
                                                       long producerGeneration) {
        String cacheKey = ntkGeneratedExtensionCacheKey(segment, workId, episodeId, page);
        String cached = cachedFreshNtkGeneratedImageExtension(cacheKey);
        if(cached != null) {
            if(cached.length() == 0 && isTrustedKnownNtkGeneratedCandidate(workId, episodeId)) {
                Log.d(TAG, "ntk_generated_empty_extension_retry_known_metadata path=" + getNtkEpisodePath()
                        + ",segment=" + segment
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",page=" + page);
            } else if(cached.length() == 0 && client != null
                    && client.hasRecentStrictNtkAdAckProof(getNtkEpisodePath())) {
                Log.d(TAG, "ntk_generated_empty_extension_retry_after_ack path=" + getNtkEpisodePath()
                        + ",segment=" + segment
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",page=" + page);
            } else {
                if(cached.length() == 0 && onPrimaryValidationMiss != null)
                    onPrimaryValidationMiss.run();
                return cached;
            }
        }
        String path = getNtkEpisodePath();
        if(shouldSkipUnverifiedCanonicalWebtoonPathWorkSpeculation(path, segment, workId, episodeId)) {
            cacheNtkGeneratedImageExtension(cacheKey, "");
            if(onPrimaryValidationMiss != null)
                onPrimaryValidationMiss.run();
            return "";
        }
        String extension = reachableEarlyNtkGeneratedImageExtensionForPage(
                client, segment, workId, episodeId, page, producerGeneration);
        if(extension.length() > 0)
            return extension;
        if(onPrimaryValidationMiss != null)
            onPrimaryValidationMiss.run();
        return "";
    }

    private String reachableNtkSlugWebtoonImageExtension(CustomHttpClient client, String cdnWorkId,
                                                         String episodeId, int page) {
        String safeCdnWorkId = cdnWorkId == null ? "" : cdnWorkId.trim();
        String cacheSegment = safeCdnWorkId.matches("\\d+") ? "wt-num-v2" : "wt";
        String cacheKey = ntkGeneratedExtensionCacheKey(cacheSegment, safeCdnWorkId, episodeId, page);
        String cached = cachedFreshNtkGeneratedImageExtension(cacheKey);
        if(cached != null)
            return cached;
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch remaining = new CountDownLatch(NTK_GENERATED_IMAGE_EXTENSIONS.length);
        AtomicBoolean winner = new AtomicBoolean(false);
        String[] hit = new String[]{""};
        long startedAt = System.currentTimeMillis();
        for(String extension : NTK_GENERATED_IMAGE_EXTENSIONS) {
            Thread thread = new Thread(() -> {
                try {
                    if(winner.get())
                        return;
                    String probe = ntkSlugWebtoonImageUrl(safeCdnWorkId, episodeId, page, extension);
                    Log.d(TAG, "ntk_slug_generated_extension_probe_candidate slug=" + safeCdnWorkId
                            + ",episodeId=" + episodeId
                            + ",extension=" + extension
                            + ",page=" + page
                            + ",url=" + safeLogImageUrl(probe));
                    if(isNtkGeneratedImageReachableFast(client, probe, winner)
                            && winner.compareAndSet(false, true)) {
                        hit[0] = extension;
                        cacheNtkGeneratedImageExtension(cacheKey, extension);
                        done.countDown();
                    }
                } catch(Exception ignored) {
                } finally {
                    remaining.countDown();
                    if(remaining.getCount() == 0)
                        done.countDown();
                }
            }, "ntk-slug-generated-ext-" + extension);
            thread.setDaemon(true);
            thread.start();
        }
        try {
            done.await(NTK_EARLY_GENERATED_EXTENSION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String result = hit[0] == null ? "" : hit[0];
        Log.d(TAG, "ntk_slug_generated_extension_probe slug=" + cdnWorkId
                + ",episodeId=" + episodeId
                + ",extension=" + result
                + ",page=" + page
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        if(result.length() == 0)
            cacheNtkGeneratedImageExtension(cacheKey, "");
        return result;
    }

    private static void cacheNtkGeneratedImageExtension(String cacheKey, String extension) {
        if(cacheKey == null)
            return;
        NTK_GENERATED_EXTENSION_CACHE.put(cacheKey, extension == null ? "" : extension);
        NTK_GENERATED_EXTENSION_CACHE_TIME.put(cacheKey, System.currentTimeMillis());
    }

    private static String ntkGeneratedExtensionCacheKey(String segment, String workId, String episodeId, int page) {
        return (segment == null ? "" : segment) + "/" + (workId == null ? "" : workId)
                + "/" + (episodeId == null ? "" : episodeId) + "/" + page;
    }

    private static String cachedFreshNtkGeneratedImageExtension(String cacheKey) {
        Long cachedAt = NTK_GENERATED_EXTENSION_CACHE_TIME.get(cacheKey);
        if(cachedAt == null || System.currentTimeMillis() - cachedAt >= NTK_GENERATED_EXTENSION_CACHE_TTL_MS)
            return null;
        return NTK_GENERATED_EXTENSION_CACHE.get(cacheKey);
    }

    private static int ntkGeneratedInitialValidationPageCount(int pageCount) {
        if(pageCount <= 1)
            return pageCount;
        return Math.min(pageCount, NTK_GENERATED_INITIAL_VALIDATION_PAGE_COUNT);
    }

    private boolean shouldValidateNtkGeneratedInitialPages() {
        return NTK_GENERATED_TRIM_BEFORE_FIRST_FRAME || getNtkImageCount() <= 0;
    }

    private boolean hasCachedReachableNtkGeneratedImageExtension(String path) {
        if(path == null || path.length() == 0 || shouldSkipNtkGeneratedForEpisodePath(path))
            return false;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String imageEpisodeId = ntkGeneratedEpisodeIdForPath(path);
        if(imageEpisodeId.length() == 0)
            imageEpisodeId = pathMatcher.group(3);
        int validationPageCount = ntkGeneratedInitialValidationPageCount(ntkGeneratedImageCandidateCount());
        String firstExtension = "";
        for(int page = 1; page <= validationPageCount; page++) {
            String cacheKey = ntkGeneratedExtensionCacheKey(pathMatcher.group(1), pathMatcher.group(2), imageEpisodeId, page);
            String extension = cachedFreshNtkGeneratedImageExtension(cacheKey);
            if(extension == null || extension.length() == 0)
                return false;
            if(firstExtension.length() == 0)
                firstExtension = extension;
            else if(!firstExtension.equals(extension))
                return false;
        }
        return true;
    }

    private boolean hasCachedUnreachableNtkGeneratedImageExtension(String path) {
        if(path == null || path.length() == 0 || shouldSkipNtkGeneratedForEpisodePath(path))
            return false;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String imageEpisodeId = ntkGeneratedEpisodeIdForPath(path);
        if(imageEpisodeId.length() == 0)
            imageEpisodeId = pathMatcher.group(3);
        int validationPageCount = ntkGeneratedInitialValidationPageCount(ntkGeneratedImageCandidateCount());
        for(int page = 1; page <= validationPageCount; page++) {
            String cacheKey = ntkGeneratedExtensionCacheKey(pathMatcher.group(1), pathMatcher.group(2), imageEpisodeId, page);
            String extension = cachedFreshNtkGeneratedImageExtension(cacheKey);
            if(extension != null && extension.length() == 0)
                return true;
        }
        return false;
    }

    private boolean shouldTryNtkGeneratedBeforeApiFallback(String path) {
        if(hasCachedUnreachableNtkGeneratedImageExtension(path))
            return false;
        if(hasCachedReachableNtkGeneratedImageExtension(path))
            return true;
        return false;
    }

    private boolean shouldUseOptimisticNtkGeneratedFastPath(String path) {
        return shouldProbeKnownGeneratedBeforeApiFallback(path, getNtkImageCount())
                && !hasCachedUnreachableNtkGeneratedImageExtension(path);
    }

    private boolean shouldOpenKnownNtkGeneratedPathWithoutValidation(String path) {
        if(NTK_GENERATED_TRIM_BEFORE_FIRST_FRAME)
            return false;
        return shouldProbeKnownGeneratedBeforeApiFallback(path, getNtkImageCount())
                && hasCachedReachableNtkGeneratedImageExtension(path)
                && !hasCachedUnreachableNtkGeneratedImageExtension(path);
    }

    private boolean shouldValidateNtkGeneratedInitialCandidates(String path) {
        if(!isNumericNtkGeneratedEpisodePath(path))
            return true;
        return true;
    }

    private static boolean shouldProbeKnownGeneratedBeforeApiFallback(String path, int imageCount) {
        if(imageCount <= 0)
            return false;
        return isNumericNtkGeneratedEpisodePath(path);
    }

    private boolean shouldProbeKnownNtkSlugGeneratedBeforeApi(String path) {
        return shouldSkipNtkGeneratedForEpisodePath(path)
                && !isNtkProtectedWebtoonSourceEpisodePath(path)
                && getNtkImageCount() > 0
                && isNumericNtkId(getNtkImageWorkId())
                && isNumericNtkId(getNtkImageEpisodeId())
                && isNtkViewerEpisodePath(path);
    }

    private static boolean isNtkProtectedWebtoonSourceEpisodePath(String path) {
        if(path == null || path.length() == 0)
            return false;
        Matcher matcher = Pattern.compile("^/webtoon/\\d+/(?:naver|nv)-\\d{5,}-[^/?#]+",
                Pattern.CASE_INSENSITIVE).matcher(path);
        return matcher.find();
    }

    private static boolean shouldProbeGeneratedModeBeforeApi(String path, int imageCount) {
        return false;
    }

    private static boolean isNumericNtkGeneratedEpisodePath(String path) {
        if(path == null || shouldSkipNtkGeneratedForEpisodePath(path))
            return false;
        return Pattern.compile("^/(?:manhwa|webtoon)/\\d+/\\d+(?:[/?#].*)?$").matcher(path).find();
    }

    private int reachableNtkGeneratedPageCount(CustomHttpClient client, String segment, String workId,
                                               String episodeId, String extension, int pageCount) {
        if(pageCount <= 1)
            return pageCount;
        int low = 1;
        int high = pageCount;
        if(pageCount >= 16) {
            int[] probes = uniqueNtkPageCountProbes(pageCount);
            boolean[] completed = new boolean[probes.length];
            boolean[] reachable = new boolean[probes.length];
            CountDownLatch done = new CountDownLatch(probes.length);
            for(int i = 0; i < probes.length; i++) {
                final int index = i;
                Thread thread = new Thread(() -> {
                    try {
                        reachable[index] = isNtkGeneratedPageReachableForCount(
                                client, segment, workId, episodeId, probes[index], extension);
                        completed[index] = true;
                    } finally {
                        done.countDown();
                    }
                }, "ntk-generated-page-probe");
                thread.setDaemon(true);
                thread.start();
            }
            try {
                done.await(4_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int bestReachable = 1;
            int firstUnreachable = pageCount + 1;
            for(int i = 0; i < probes.length; i++) {
                if(!completed[i])
                    continue;
                if(reachable[i])
                    bestReachable = Math.max(bestReachable, probes[i]);
                else
                    firstUnreachable = Math.min(firstUnreachable, probes[i]);
            }
            if(bestReachable >= pageCount)
                return pageCount;
            if(firstUnreachable <= pageCount) {
                low = bestReachable + 1;
                high = firstUnreachable - 1;
            } else {
                low = bestReachable + 1;
                high = pageCount;
            }
        } else if(isNtkGeneratedImageReachable(client, ntkGeneratedImageUrl(segment, workId, episodeId, pageCount, extension))) {
            return pageCount;
        } else {
            high = pageCount - 1;
        }
        int best = 1;
        while(low <= high) {
            int mid = low + (high - low) / 2;
            if(isNtkGeneratedPageReachableForCount(client, segment, workId, episodeId, mid, extension)) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if(best < pageCount)
            logNtkViewerParse("generated-trim-pages-" + pageCount + "-to-" + best, null, getNtkEpisodePath(), 0, 0);
        return best;
    }

    private int reachableNtkSlugWebtoonPageCount(CustomHttpClient client, String slug,
                                                 String episodeId, String extension, int pageCount) {
        if(pageCount <= 1)
            return pageCount;
        int low = 1;
        int high = pageCount;
        if(pageCount >= 16) {
            int[] probes = uniqueNtkPageCountProbes(pageCount);
            boolean[] completed = new boolean[probes.length];
            boolean[] reachable = new boolean[probes.length];
            CountDownLatch done = new CountDownLatch(probes.length);
            for(int i = 0; i < probes.length; i++) {
                final int index = i;
                Thread thread = new Thread(() -> {
                    try {
                        reachable[index] = isNtkSlugWebtoonPageReachableForCount(
                                client, slug, episodeId, probes[index], extension);
                        completed[index] = true;
                    } finally {
                        done.countDown();
                    }
                }, "ntk-slug-page-probe");
                thread.setDaemon(true);
                thread.start();
            }
            try {
                done.await(4_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int bestReachable = 1;
            int firstUnreachable = pageCount + 1;
            for(int i = 0; i < probes.length; i++) {
                if(!completed[i])
                    continue;
                if(reachable[i])
                    bestReachable = Math.max(bestReachable, probes[i]);
                else
                    firstUnreachable = Math.min(firstUnreachable, probes[i]);
            }
            if(bestReachable >= pageCount)
                return pageCount;
            if(firstUnreachable <= pageCount) {
                low = bestReachable + 1;
                high = firstUnreachable - 1;
            } else {
                low = bestReachable + 1;
                high = pageCount;
            }
        } else if(isNtkSlugWebtoonPageReachableForCount(client, slug, episodeId, pageCount, extension)) {
            return pageCount;
        } else {
            high = pageCount - 1;
        }
        int best = 1;
        while(low <= high) {
            int mid = low + (high - low) / 2;
            if(isNtkSlugWebtoonPageReachableForCount(client, slug, episodeId, mid, extension)) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if(best < pageCount)
            logNtkViewerParse("slug-generated-trim-pages-" + pageCount + "-to-" + best,
                    null, getNtkEpisodePath(), 0, 0);
        return best;
    }

    private boolean isNtkGeneratedPageReachableForCount(CustomHttpClient client, String segment,
                                                        String workId, String episodeId,
                                                        int page, String extension) {
        String src = ntkGeneratedImageUrl(segment, workId, episodeId, page, extension);
        int quick = ntkGeneratedImageQuickHeaderReachability(client, src);
        if(quick > 0)
            return true;
        if(quick == 0)
            return false;
        return isNtkGeneratedImageReachable(client, src, false);
    }

    private boolean isNtkSlugWebtoonPageReachableForCount(CustomHttpClient client, String slug,
                                                          String episodeId, int page,
                                                          String extension) {
        String src = ntkSlugWebtoonImageUrl(slug, episodeId, page, extension);
        int quick = ntkGeneratedImageQuickHeaderReachability(client, src);
        if(quick > 0)
            return true;
        if(quick == 0)
            return false;
        return isNtkGeneratedImageReachable(client, src, false);
    }

    private static int[] uniqueNtkPageCountProbes(int pageCount) {
        int[] raw = new int[]{
                Math.max(2, pageCount / 4),
                Math.max(2, pageCount / 2),
                Math.max(2, (pageCount * 3) / 4),
                pageCount
        };
        ArrayList<Integer> unique = new ArrayList<>();
        for(int value : raw) {
            int clamped = Math.max(2, Math.min(pageCount, value));
            if(!unique.contains(clamped))
                unique.add(clamped);
        }
        int[] result = new int[unique.size()];
        for(int i = 0; i < unique.size(); i++)
            result[i] = unique.get(i);
        return result;
    }

    private boolean isNtkGeneratedImageReachable(CustomHttpClient client, String src) {
        return isNtkGeneratedImageReachable(client, src, true);
    }

    private boolean isNtkGeneratedImageReachable(CustomHttpClient client, String src, boolean logMiss) {
        if(client == null || src == null || src.length() == 0)
            return false;
        if(shouldSkipCanonicalWebtoonNonWtImageProbe(src))
            return false;
        if(shouldDeferModernNtkGeneratedProbeUntilAck(client, getNtkEpisodePath()))
            return false;
        Response response = null;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            String referer = client.getUrl(src.contains("/webtoon/")
                    || src.contains("/blacktoon/episodes/")
                    || src.contains("/wt/episodes/")
                    ? MTitle.base_webtoon : MTitle.base_comic);
            headers.put("Referer", referer);
            headers.put("User-Agent", client.agent);
            String cookie = client.getCookieHeader();
            if(cookie != null && cookie.length() > 0)
                headers.put("Cookie", cookie);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Sec-Fetch-Dest", "image");
            headers.put("Sec-Fetch-Mode", "no-cors");
            headers.put("Sec-Fetch-Site", secFetchSiteForImageReferer(referer, src));
            headers.put("range", "bytes=0-" + (NTK_GENERATED_IMAGE_PROBE_BYTES - 1));
            response = client.get(src, headers);
            int code = response == null ? 0 : response.code();
            String contentType = response == null || response.body() == null
                    ? "" : String.valueOf(response.body().contentType()).toLowerCase(Locale.ROOT);
            boolean imageResponse = contentType.startsWith("image/");
            boolean ok = (code >= 200 && code < 300 || code == 206)
                    && imageResponse
                    && hasUsableNtkGeneratedImageProbe(response);
            if(!ok && code == 403) {
                response.close();
                response = null;
                headers.remove("range");
                response = client.get(src, headers);
                code = response == null ? 0 : response.code();
                contentType = response == null || response.body() == null
                        ? "" : String.valueOf(response.body().contentType()).toLowerCase(Locale.ROOT);
                imageResponse = contentType.startsWith("image/");
                ok = (code >= 200 && code < 300 || code == 206)
                        && imageResponse
                        && hasUsableNtkGeneratedImageProbe(response);
            }
            if(!ok && logMiss)
                logNtkViewerParse("generated-unreachable-" + code + "-" + generatedImageDebugSuffix(src),
                        null, getNtkEpisodePath(), 0, 0);
            if(!ok && logMiss) {
                Log.d(TAG, "ntk_generated_reachability_miss path=" + getNtkEpisodePath()
                        + ",code=" + code
                        + ",contentType=" + contentType
                        + ",url=" + src);
            }
            return ok;
        } catch(Exception e) {
            if(logMiss) {
                logNtkViewerParse("generated-unreachable-error", null, getNtkEpisodePath(), 0, 0);
                Log.d(TAG, "ntk_generated_reachability_error path=" + getNtkEpisodePath()
                        + ",url=" + src
                        + ",error=" + e);
            }
            return false;
        } finally {
            if(response != null)
                response.close();
        }
    }

    private boolean isNtkGeneratedImageReachableFast(CustomHttpClient client, String src,
                                                     AtomicBoolean stopSignal) {
        if(client == null || src == null || src.length() == 0)
            return false;
        if(shouldSkipCanonicalWebtoonNonWtImageProbe(src))
            return false;
        if(shouldDeferModernNtkGeneratedProbeUntilAck(client, getNtkEpisodePath()))
            return false;
        AtomicBoolean localDone = new AtomicBoolean(false);
        FutureTask<Boolean> rangeProbe = new FutureTask<>(() -> {
            try {
                Thread.sleep(90L);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if(localDone.get() || (stopSignal != null && stopSignal.get()))
                return false;
            return isNtkGeneratedImageReachableRange(client, src, stopSignal);
        });
        Thread rangeThread = new Thread(rangeProbe, "ntk-generated-ext-range");
        rangeThread.setDaemon(true);
        rangeThread.start();
        try {
            if(stopSignal != null && stopSignal.get())
                return false;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            String referer = client.getUrl(src.contains("/webtoon/")
                    || src.contains("/blacktoon/episodes/")
                    || src.contains("/wt/episodes/")
                    ? MTitle.base_webtoon : MTitle.base_comic);
            headers.put("Referer", referer);
            headers.put("User-Agent", client.agent);
            String cookie = client.getCookieHeader();
            if(cookie != null && cookie.length() > 0)
                headers.put("Cookie", cookie);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Sec-Fetch-Dest", "image");
            headers.put("Sec-Fetch-Mode", "no-cors");
            headers.put("Sec-Fetch-Site", secFetchSiteForImageReferer(referer, src));
            headers.put("Accept-Encoding", "identity");
            int headerReachability = client.ntkImageHeaderReachability(
                    src, headers, Math.min(NTK_EARLY_GENERATED_HEADER_PROBE_MS,
                            NTK_EARLY_GENERATED_EXTENSION_WAIT_MS));
            if(headerReachability > 0) {
                localDone.set(true);
                return true;
            }
            if(headerReachability == 0) {
                localDone.set(true);
                return false;
            }
            if(stopSignal != null && stopSignal.get())
                return false;
            try {
                return rangeProbe.get(Math.max(1L, NTK_EARLY_GENERATED_EXTENSION_WAIT_MS
                        - NTK_EARLY_GENERATED_HEADER_PROBE_MS), TimeUnit.MILLISECONDS);
            } catch(Exception ignored) {
                return false;
            }
        } catch(Exception ignored) {
            return false;
        } finally {
            localDone.set(true);
        }
    }

    private boolean isNtkGeneratedImageReachableRange(CustomHttpClient client, String src,
                                                      AtomicBoolean stopSignal) {
        if(client == null || src == null || src.length() == 0)
            return false;
        if(shouldDeferModernNtkGeneratedProbeUntilAck(client, getNtkEpisodePath()))
            return false;
        if(stopSignal != null && stopSignal.get())
            return false;
        Response response = null;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            String referer = client.getUrl(src.contains("/webtoon/")
                    || src.contains("/blacktoon/episodes/")
                    || src.contains("/wt/episodes/")
                    ? MTitle.base_webtoon : MTitle.base_comic);
            headers.put("Referer", referer);
            headers.put("User-Agent", client.agent);
            String cookie = client.getCookieHeader();
            if(cookie != null && cookie.length() > 0)
                headers.put("Cookie", cookie);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Sec-Fetch-Dest", "image");
            headers.put("Sec-Fetch-Mode", "no-cors");
            headers.put("Sec-Fetch-Site", secFetchSiteForImageReferer(referer, src));
            headers.put("Accept-Encoding", "identity");
            headers.put("range", "bytes=0-255");
            response = client.get(src, headers);
            int code = response == null ? 0 : response.code();
            String contentType = response == null || response.body() == null
                    ? "" : String.valueOf(response.body().contentType()).toLowerCase(Locale.ROOT);
            byte[] bytes = response == null || response.body() == null
                    ? new byte[0] : readNtkGeneratedImageProbeBytes(response.body().byteStream());
            return (code >= 200 && code < 300 || code == 206)
                    && contentType.startsWith("image/")
                    && looksLikeNtkGeneratedImageBytes(bytes);
        } catch(Exception ignored) {
            return false;
        } finally {
            if(response != null)
                response.close();
        }
    }

    private static boolean looksLikeNtkGeneratedImageBytes(byte[] bytes) {
        if(bytes == null || bytes.length < 4)
            return false;
        int b0 = bytes[0] & 0xff;
        int b1 = bytes[1] & 0xff;
        int b2 = bytes[2] & 0xff;
        int b3 = bytes[3] & 0xff;
        if(b0 == 0xff && b1 == 0xd8)
            return true;
        if(bytes.length >= 8 && b0 == 0x89 && b1 == 0x50 && b2 == 0x4e && b3 == 0x47)
            return true;
        if(b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46)
            return true;
        return b0 == 0x47 && b1 == 0x49 && b2 == 0x46;
    }

    private static boolean hasUsableNtkGeneratedImageProbe(Response response) throws IOException {
        if(response == null || response.body() == null)
            return false;
        byte[] bytes = readNtkGeneratedImageProbeBytes(response.body().byteStream());
        if(!looksLikeNtkGeneratedImageBytes(bytes))
            return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if(options.outWidth <= 0 || options.outHeight <= 0)
            return false;
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inPreferredConfig = Bitmap.Config.RGB_565;
        decodeOptions.inSampleSize = sampledNtkGeneratedProbeDecodeSize(options.outWidth, options.outHeight);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decodeOptions);
        if(bitmap == null)
            return false;
        bitmap.recycle();
        return true;
    }

    private static int sampledNtkGeneratedProbeDecodeSize(int width, int height) {
        int maxDim = Math.max(width, height);
        int sample = 1;
        while(maxDim / sample > 256)
            sample <<= 1;
        return sample;
    }

    private static byte[] readNtkGeneratedImageProbeBytes(InputStream stream) throws IOException {
        if(stream == null)
            return new byte[0];
        byte[] buffer = new byte[NTK_GENERATED_IMAGE_PROBE_BYTES];
        int offset = 0;
        while(offset < buffer.length) {
            int read = stream.read(buffer, offset, buffer.length - offset);
            if(read < 0)
                break;
            offset += read;
            if(read == 0)
                break;
        }
        return offset == buffer.length ? buffer : Arrays.copyOf(buffer, offset);
    }

    private static boolean isNtkAccessBlockedForViewer(CustomHttpClient client, CustomHttpClient.PageResponse page) {
        if(client == null || client.hasNtkAccessProof())
            return false;
        if(client.hasRecentNtkHardBlock() || client.hasRecentCloudflareChallenge())
            return true;
        return page != null && (page.code == 403 || client.isCloudflareChallengeResponse(page.code, page.body));
    }

    private static String generatedImageDebugSuffix(String src) {
        if(src == null || src.length() == 0)
            return "empty";
        Matcher matcher = Pattern.compile("/(manhwa|webtoon)/(\\d+)/([^/]+)/p(\\d{3})\\.([a-z0-9]+)").matcher(src);
        if(matcher.find())
            return matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3)
                    + "-p" + matcher.group(4) + "-" + matcher.group(5);
        int slash = src.lastIndexOf('/');
        return slash >= 0 && slash + 1 < src.length() ? src.substring(slash + 1) : "url";
    }

    private int ntkGeneratedImageCandidateCount() {
        int count = getNtkImageCount();
        return count > 0 ? count : NTK_DEFAULT_GENERATED_PAGE_COUNT;
    }

    private boolean shouldUseImmediateNtkGeneratedFastPath(String path) {
        return shouldUseImmediateNtkGeneratedFastPath(baseMode, path, getNtkImageCount());
    }

    private static boolean shouldUseImmediateNtkGeneratedFastPath(int baseMode, String path, int imageCount) {
        return imageCount > 0
                && isNumericNtkGeneratedEpisodePath(path)
                && !shouldPreferNtkApiForCanonicalWebtoonPath(path);
    }

    public static boolean shouldUseGeneratedAppendBeforeApi(int baseMode, String path, int imageCount) {
        return shouldUseImmediateNtkGeneratedFastPath(baseMode, path, imageCount);
    }

    private static boolean isNtkWebtoonEpisodePath(String path) {
        return path != null && path.matches("^/webtoon/[^/?#]+/[^/?#]+.*");
    }

    private static boolean isNtkManhwaEpisodePath(String path) {
        return path != null && path.matches("^/manhwa/[^/?#]+/[^/?#]+.*");
    }

    private static boolean isNtkViewerEpisodePath(String path) {
        return path != null && path.matches("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+.*");
    }

    private static boolean shouldPreferNtkApiForCanonicalWebtoonPath(String path) {
        return shouldPreferNtkApiForCanonicalWebtoonPath(path, NTK_CANONICAL_WEBTOON_API_FIRST_MIN_WORK_ID);
    }

    private static boolean shouldUseProtectedNtkViewerApi(CustomHttpClient client, String path) {
        if(shouldPreferNtkApiForCanonicalWebtoonPath(path))
            return true;
        return client != null
                && isNtkViewerEpisodePath(path)
                && isNumericNtkGeneratedEpisodePath(path)
                && client.isModernNtkGuardRootForPath(path);
    }

    private static boolean hasFiniteNumericManhwaStripAuthority(String path, int imageCount) {
        return imageCount > 0 && path != null
                && path.matches("(?i)^/manhwa/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$");
    }

    static boolean hasFiniteNumericManhwaStripAuthorityForTest(String path, int imageCount) {
        return hasFiniteNumericManhwaStripAuthority(path, imageCount);
    }

    static boolean shouldPreferNtkApiForCanonicalWebtoonPathForTest(String path) {
        return shouldPreferNtkApiForCanonicalWebtoonPath(path);
    }

    private static boolean shouldPreferNtkApiForCanonicalWebtoonPath(String path, int minimumCanonicalId) {
        if(path == null)
            return false;
        Matcher matcher = Pattern.compile("^/webtoon/(\\d+)/\\d+(?:[/?#].*)?$").matcher(path);
        if(!matcher.find())
            return false;
        try {
            return Integer.parseInt(matcher.group(1)) >= minimumCanonicalId;
        } catch(Exception ignored) {
            return true;
        }
    }

    private static boolean shouldTryNtkCanonicalGeneratedImagePath(String path) {
        return shouldPreferNtkApiForCanonicalWebtoonPath(path);
    }

    private static String ntkCanonicalWebtoonSlugCandidate(String titlePath, String titleName) {
        String path = titlePath == null ? "" : titlePath.trim();
        Matcher pathMatcher = Pattern.compile("^/webtoon/([^/?#]+)(?:[/?#].*)?$").matcher(path);
        if(pathMatcher.find()) {
            String slug = pathMatcher.group(1);
            if(slug != null && slug.length() > 0 && !slug.matches("\\d+"))
                return slug;
        }
        String nameCandidate = titleName == null ? "" : titleName.trim();
        if(nameCandidate.length() == 0)
            return "";
        nameCandidate = nameCandidate.replaceAll("\\s+", "-");
        nameCandidate = nameCandidate.replaceAll("-{2,}", "-");
        nameCandidate = nameCandidate.replaceAll("^-|-$", "");
        return nameCandidate.matches("\\d+") ? "" : nameCandidate;
    }

    private static boolean shouldSkipNtkGeneratedForEpisodePath(String path) {
        if(path == null || path.length() == 0)
            return false;
        Matcher matcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return false;
        String segment = matcher.group(1);
        String workPathId = matcher.group(2);
        String episodePathId = matcher.group(3);
        if("webtoon".equalsIgnoreCase(segment) && !workPathId.matches("\\d+"))
            return true;
        return !episodePathId.matches("\\d+");
    }

    private static boolean isNtkKpWebtoonEpisodePath(String path) {
        if(path == null || path.length() == 0)
            return false;
        Matcher matcher = Pattern.compile("^/webtoon/[^/?#]+/([^/?#]+)").matcher(path);
        return matcher.find() && isNtkKpEpisodeId(matcher.group(1));
    }

    private static boolean shouldTryUnsignedViewerManifestFirst(CustomHttpClient client, String path) {
        if(isNtkKpWebtoonEpisodePath(path))
            return false;
        return true;
    }

    private static boolean isNtkSyntheticWebtoonEpisodePath(String path) {
        return isNtkWebtoonEpisodePath(path) && shouldSkipNtkGeneratedForEpisodePath(path);
    }

    private static boolean isNtkKpEpisodeId(String episodeId) {
        return episodeId != null && episodeId.toLowerCase(Locale.ROOT).startsWith("kp-");
    }

    private String generatedPageCountSource() {
        return getNtkImageCount() > 0 ? "known" : "default";
    }

    private static int ntkViewerMetaPageCount(String body) {
        if(body == null || body.length() == 0)
            return 0;
        String metaText = ntkViewerImageMetasText(body);
        String source = metaText.length() > 0 ? metaText : body;
        int maxPage = 0;
        int maxZeroBased = -1;
        boolean sawZeroBased = false;
        Matcher matcher = Pattern.compile(
                "\"(page|pageNo|pageNumber|pageIndex|sort|order|index|no|count|total|imageCount|imagesCount|totalImages|totalImageCount|pageCount|totalPages|numberOfPages)\"\\s*:\\s*(\\d{1,4})",
                Pattern.CASE_INSENSITIVE).matcher(source);
        while(matcher.find()) {
            try {
                String field = matcher.group(1).toLowerCase(Locale.ROOT);
                int value = Integer.parseInt(matcher.group(2));
                if("index".equals(field) || "pageindex".equals(field)
                        || "sort".equals(field) || "order".equals(field)) {
                    if(value == 0)
                        sawZeroBased = true;
                    maxZeroBased = Math.max(maxZeroBased, value);
                } else {
                    maxPage = Math.max(maxPage, value);
                }
            } catch (Exception ignored) {
            }
        }
        if(sawZeroBased && maxZeroBased >= 0)
            maxPage = Math.max(maxPage, maxZeroBased + 1);
        int objectCount = ntkViewerImageMetasObjectCount(metaText);
        if(objectCount > 1)
            maxPage = Math.max(maxPage, objectCount);
        return maxPage;
    }

    private static String ntkViewerImageMetasText(String body) {
        return ntkViewerArrayText(body, "imageMetas");
    }

    private static String ntkViewerArrayText(String body, String fieldName) {
        if(body == null || body.length() == 0)
            return "";
        if(fieldName == null || fieldName.length() == 0)
            return "";
        String quoted = "\"" + fieldName + "\"";
        String escaped = "\\\"" + fieldName + "\\\"";
        int marker = body.indexOf(quoted);
        if(marker < 0)
            marker = body.indexOf(escaped);
        if(marker < 0)
            return "";
        int start = body.indexOf('[', marker);
        if(start < 0)
            return "";
        int depth = 0;
        for(int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if(c == '[')
                depth++;
            else if(c == ']') {
                depth--;
                if(depth == 0)
                    return body.substring(start, i + 1);
            }
            if(i - start > 80_000)
                break;
        }
        return body.substring(start, Math.min(body.length(), start + 80_000));
    }

    private static int ntkViewerImageMetasObjectCount(String metaText) {
        if(metaText == null || metaText.length() == 0)
            return 0;
        int count = 0;
        int depth = 0;
        for(int i = 0; i < metaText.length(); i++) {
            char c = metaText.charAt(i);
            if(c == '{') {
                depth++;
                if(depth == 1)
                    count++;
            } else if(c == '}' && depth > 0) {
                depth--;
            }
        }
        return count;
    }

    private static String ntkViewerImagesToken(String body) {
        if(body == null || body.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("\"imagesToken\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\\\\\"imagesToken\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\"").matcher(body);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\\\\\"token\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\"").matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String ntkViewerImagesTokenField(String token, String field) {
        if(token == null || token.length() == 0 || field == null || field.length() == 0)
            return "";
        try {
            String[] parts = token.split("\\.");
            if(parts.length < 1)
                return "";
            String payload = parts[0];
            int padding = (4 - payload.length() % 4) % 4;
            StringBuilder padded = new StringBuilder(payload);
            for(int i = 0; i < padding; i++)
                padded.append('=');
            byte[] decoded = Base64.decode(padded.toString(), Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            return json.optString(field, "");
        } catch(Exception ignored) {
            return "";
        }
    }

    private static String ntkViewerEmbeddedImageEpisodeId(String body, String pathEpisodeId) {
        if(body == null || body.length() == 0)
            return "";
        String skip = pathEpisodeId == null ? "" : pathEpisodeId.trim();
        String id = firstNtkViewerEmbeddedImageEpisodeId(body,
                Pattern.compile("\"episodeId\"\\s*:\\s*\"(\\d{1,12})\""), skip);
        if(id.length() > 0)
            return id;
        return firstNtkViewerEmbeddedImageEpisodeId(body,
                Pattern.compile("\\\\\"episodeId\\\\\"\\s*:\\s*\\\\\"(\\d{1,12})\\\\\""), skip);
    }

    private static String firstNtkViewerEmbeddedImageEpisodeId(String body, Pattern pattern, String skip) {
        Matcher matcher = pattern.matcher(body);
        while(matcher.find()) {
            String id = matcher.group(1);
            if(id != null && id.length() > 0 && !id.equals(skip))
                return id;
        }
        return "";
    }

    private static boolean isNumericNtkId(String value) {
        return value != null && value.matches("\\d+");
    }

    private static String ntkViewerPayloadSnippet(String body) {
        if(body == null || body.length() == 0)
            return "";
        String lower = body.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("imagestoken");
        if(index < 0)
            index = lower.indexOf("imagemetas");
        if(index < 0)
            return "";
        int start = Math.max(0, index - 160);
        int end = Math.min(body.length(), index + 360);
        return body.substring(start, end).replace('\n', ' ').replace('\r', ' ');
    }

    private static String ntkViewerPayloadMarkerSummary(String body, String token) {
        if(body == null)
            body = "";
        String normalized = normalizeNtkViewerPayloadText(body);
        int src = firstIndexOfAny(normalized, "\"src\"", "\\\"src\\\"", "toonflix.app/");
        int imageMetas = firstIndexOfAny(normalized, "\"imageMetas\"", "\\\"imageMetas\\\"");
        int imagesToken = firstIndexOfAny(normalized, "\"imagesToken\"", "\\\"imagesToken\\\"",
                "\"token\"", "\\\"token\\\"");
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        return "srcIndex=" + src
                + ",imageMetasIndex=" + imageMetas
                + ",imagesTokenIndex=" + imagesToken
                + ",tokenWorkId=" + tokenWorkId
                + ",tokenEpisodeId=" + tokenEpisodeId
                + "," + ntkViewerImagesTokenDebugSummary(token);
    }

    private static String ntkViewerImagesTokenDebugSummary(String token) {
        if(token == null || token.length() == 0)
            return "tokenLen=0";
        try {
            String[] parts = token.split("\\.");
            String payload = parts.length > 0 ? parts[0] : "";
            int padding = (4 - payload.length() % 4) % 4;
            StringBuilder padded = new StringBuilder(payload);
            for(int i = 0; i < padding; i++)
                padded.append('=');
            byte[] decoded = Base64.decode(padded.toString(), Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            ArrayList<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = json.keys();
            while(iterator.hasNext())
                keys.add(iterator.next());
            java.util.Collections.sort(keys);
            return "tokenLen=" + token.length()
                    + ",tokenParts=" + parts.length
                    + ",tokenJsonLen=" + decoded.length
                    + ",tokenKeys=" + keys
                    + ",tokenIat=" + json.optString("iat", "")
                    + ",tokenExp=" + json.optString("exp", "")
                    + ",tokenT=" + json.optString("t", "")
                    + ",tokenS_len=" + json.optString("s", "").length()
                    + ",tokenSig_len=" + json.optString("sig", "").length()
                    + ",tokenHash_len=" + json.optString("hash", "").length();
        } catch(Exception e) {
            return "tokenLen=" + token.length() + ",tokenDecode=error";
        }
    }

    private static int firstIndexOfAny(String value, String... needles) {
        if(value == null || value.length() == 0 || needles == null)
            return -1;
        int best = -1;
        for(String needle : needles) {
            if(needle == null || needle.length() == 0)
                continue;
            int index = value.indexOf(needle);
            if(index >= 0 && (best < 0 || index < best))
                best = index;
        }
        return best;
    }

    private static String ntkViewerSnippetAround(String value, String needle, int radius) {
        if(value == null || value.length() == 0 || needle == null || needle.length() == 0)
            return "";
        int index = value.indexOf(needle);
        if(index < 0)
            return "";
        int safeRadius = Math.max(40, Math.min(radius, 400));
        int start = Math.max(0, index - safeRadius);
        int end = Math.min(value.length(), index + needle.length() + safeRadius);
        return value.substring(start, end)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ');
    }

    private static String normalizeNtkEmbeddedImageText(String source) {
        if(source == null)
            return "";
        return source.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("&#x2F;", "/")
                .replace("&#x2f;", "/")
                .replace("&amp;", "&")
                .replace("&quot;", "\"");
    }

    private static String ntkLogImageName(String url) {
        if(url == null || url.length() == 0)
            return "";
        int end = url.indexOf('?');
        String value = end >= 0 ? url.substring(0, end) : url;
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static List<String> ntkImageAttributeCandidates(String value, boolean srcset) {
        ArrayList<String> candidates = new ArrayList<>();
        if(value == null || value.trim().length() == 0)
            return candidates;
        if(srcset) {
            for(String entry : value.split(",")) {
                String trimmed = entry == null ? "" : entry.trim();
                if(trimmed.length() == 0)
                    continue;
                String[] parts = trimmed.split("\\s+", 2);
                addNtkNormalizedAttributeCandidate(candidates, parts[0]);
            }
        } else {
            addNtkNormalizedAttributeCandidate(candidates, value);
        }
        return candidates;
    }

    private static void addNtkNormalizedAttributeCandidate(List<String> candidates, String value) {
        if(candidates == null || value == null)
            return;
        String normalized = normalizeNtkEmbeddedImageText(value.trim());
        if(normalized.length() == 0)
            return;
        String proxied = ntkProxiedImageUrl(normalized);
        String candidate = proxied.length() > 0 ? proxied : normalized;
        if(candidate.length() > 0 && !candidates.contains(candidate))
            candidates.add(candidate);
    }

    private static String ntkProxiedImageUrl(String value) {
        if(value == null || value.length() == 0)
            return "";
        Matcher matcher = NTK_NEXT_IMAGE_URL_PARAM_PATTERN.matcher(value);
        if(!matcher.find())
            return "";
        return decodeNtkUrlParameter(matcher.group(1));
    }

    private static String decodeNtkUrlParameter(String value) {
        if(value == null || value.length() == 0)
            return "";
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String normalizeNtkViewerPayloadText(String source) {
        String normalized = normalizeNtkEmbeddedImageText(source);
        for(int i = 0; i < 4; i++) {
            String next = normalized
                    .replace("\\\\\"", "\"")
                    .replace("\\\"", "\"")
                    .replace("\\\\/", "/")
                    .replace("\\/", "/")
                    .replace("\\\\u002F", "/")
                    .replace("\\\\u002f", "/");
            if(next.equals(normalized))
                break;
            normalized = next;
        }
        return normalized;
    }

    private void logNtkViewerParse(String reason, CustomHttpClient.PageResponse page, String path, int imgTagCount, int fallbackCount) {
        ntkViewerParseReason = reason == null ? "" : reason;
        if(!Log.isLoggable(TAG, Log.DEBUG) && "ok".equals(reason))
            return;
        String sample = page == null || page.body == null ? "" : page.body.replace('\n', ' ').replace('\r', ' ');
        if(sample.length() > 220)
            sample = sample.substring(0, 220);
        Log.d(TAG, "ntk_viewer_parse reason=" + reason
                + ",id=" + id
                + ",titleId=" + getTitleId()
                + ",path=" + path
                + ",code=" + (page == null ? 0 : page.code)
                + ",fromCache=" + (page != null && page.fromCache)
                + ",bodyLen=" + (page == null || page.body == null ? 0 : page.body.length())
                + ",imgTags=" + imgTagCount
                + ",fallbackImages=" + fallbackCount
                + ",images=" + (imgs == null ? 0 : imgs.size())
                + ",firstImage=" + firstImageSample()
                + ",sample=" + sample);
    }

    private String firstImageSample() {
        if(imgs == null || imgs.size() == 0 || imgs.get(0) == null)
            return "";
        String sample = imgs.get(0);
        return sample.length() > 160 ? sample.substring(0, 160) : sample;
    }

    private static boolean looksLikeNtkBlockedPage(String body) {
        if(body == null || body.length() == 0)
            return true;
        if(hasNtkViewerImageApiPayload(body))
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        if(ntkViewerSourceWorkId(normalized).length() > 0
                || ntkViewerThumbWorkId(normalized).length() > 0
                || hasNtkPageImageInText(normalized))
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("webpage not available")
                || lower.contains("net::err_")
                || lower.contains("err_connection_reset")
                || lower.contains("err_name_not_resolved")
                || lower.contains("err_timed_out")
                || lower.contains("just a moment")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("/cdn-cgi/challenge-platform")
                || lower.contains("cf-challenge")
                || lower.contains("cf_chl")
                || lower.contains("cf-chl")
                || lower.contains("_cf_chl")
                || lower.contains("cf-mitigated")
                || lower.contains("cf-turnstile")
                || lower.contains("cf_clearance")
                || lower.contains("cf-ray")
                || lower.contains("turnstile")
                || lower.contains("verifying you are human")
                || lower.contains("verify you are human")
                || (lower.contains("cloudflare") && lower.contains("security service"))
                || isNtkBlockedViewerTitle(lower);
    }

    private static boolean isNtkBlockedViewerTitle(String value) {
        if(value == null)
            return false;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.contains("개발자 도구 차단")
                || lower.contains("developer tools blocked")
                || lower.contains("developer tool blocked")
                || lower.contains("devtools blocked")
                || lower.contains("devtool blocked");
    }

    private static boolean looksLikeNtkMissingPage(String body) {
        if(body == null || body.length() == 0)
            return true;
        if(hasNtkViewerImageApiPayload(body))
            return false;
        if(hasNtkPageImageInText(body))
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        if(ntkViewerSourceWorkId(normalized).length() > 0
                || ntkViewerThumbWorkId(normalized).length() > 0
                || ntkViewerMetaPageCount(normalized) > 0)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.matches("(?s).*next_http_error_fallback[^\\]]*(?:404|410).*")
                || lower.matches("(?s).*<html[^>]+id=[\"']__next_error__[\"'].*")
                || lower.contains("404: this page could not be found")
                || body.contains("\uC791\uD488\uC744 \uCC3E\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4")
                || body.contains("\uD68C\uCC28 \uC5C6\uC74C");
    }

    private static boolean hasNtkPageImageInText(String body) {
        String normalized = normalizeNtkViewerPayloadText(body);
        if(hasNtkPageImageMatch(normalized, NTK_TEXT_IMAGE_PATTERN, false))
            return true;
        if(hasNtkPageImageMatch(body, NTK_ENCODED_TEXT_IMAGE_PATTERN, true))
            return true;
        return hasNtkViewerBoardUploadImageInText(normalized);
    }

    private static boolean hasNtkPageImageMatch(String source, Pattern pattern, boolean percentEncoded) {
        Matcher matcher = pattern.matcher(source);
        while(matcher.find()) {
            String url = matcher.group();
            if(percentEncoded) {
                try {
                    url = URLDecoder.decode(url, "UTF-8");
                } catch (Exception ignored) {
                }
            }
            if(isNtkPageImage(null, normalizeNtkEmbeddedImageText(url)))
                return true;
        }
        return false;
    }

    private static boolean hasNtkViewerBoardUploadImageInText(String body) {
        if(body == null || body.length() == 0)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        if(lower.contains("vw-imgs") && lower.contains("/board_uploads/") && lower.contains("alt=\"page "))
            return true;
        return lower.matches("(?s).*\"images\"\\s*:\\s*\\[.*\"page\"\\s*:\\s*\\d+\\s*,\\s*\"src\"\\s*:\\s*\"https?://[^\"<>]+/board_uploads/[^\"<>]+\\.(?:jpg|jpeg|png|webp).*");
    }

    private static String extractNtkViewerEpisodeName(Document d) {
        if(d == null)
            return "";
        Element episodeNo = d.selectFirst(".vw-ep strong");
        if(episodeNo != null) {
            String no = episodeNo.text().trim();
            if(no.matches("[0-9]+(?:\\.[0-9]+)?"))
                return no + "화";
            if(no.length() > 0)
                return no;
        }
        Element titleMeta = d.selectFirst("meta[property=og:title]");
        String rawTitle = titleMeta == null ? d.title() : titleMeta.attr("content");
        if(rawTitle == null)
            return "";
        rawTitle = rawTitle.replace("| 뉴토끼", "").trim();
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?\\s*화)").matcher(rawTitle);
        if(matcher.find())
            return matcher.group(1).replace(" ", "");
        return "";
    }

    private static boolean isCloudflareChallenge(Exception e) {
        String message = e == null ? null : e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("cloudflare");
    }

    private static boolean isNtkViewerChallengeFailure(CustomHttpClient client, Exception e) {
        return client != null
                && isNtkViewerChallengeFailure(client.isNtk(), e, client.hasRecentCloudflareChallenge());
    }

    static boolean isNtkViewerChallengeFailureForTest(boolean ntk, Exception e) {
        return isNtkViewerChallengeFailure(ntk, e, false);
    }

    static boolean isNtkViewerChallengeFailureForTest(boolean ntk, Exception e,
                                                      boolean recentCloudflareChallenge) {
        return isNtkViewerChallengeFailure(ntk, e, recentCloudflareChallenge);
    }

    private static boolean isNtkViewerChallengeFailure(boolean ntk, Exception e,
                                                       boolean recentCloudflareChallenge) {
        if(!ntk)
            return false;
        if(isCloudflareChallenge(e))
            return true;
        return recentCloudflareChallenge && isNtkViewerPageRequestFailure(e);
    }

    private static boolean isNtkViewerPageRequestFailure(Exception e) {
        String message = e == null ? null : e.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.startsWith("request failed: /webtoon/")
                || lower.startsWith("request failed: /manhwa/")
                || lower.startsWith("request failed: /api/works")
                || lower.startsWith("request failed: /search?");
    }

    static boolean isRecoverableNetworkFetchFailureForTest(Throwable e) {
        return isRecoverableNetworkFetchFailure(e);
    }

    private static void recordFetchException(Exception e) {
        if(isExpectedFetchCancellation(e)) {
            Log.d(TAG, "manga_fetch_cancelled expected");
            return;
        }
        if(isRecoverableNetworkFetchFailure(e)) {
            Log.w(TAG, "manga_fetch_network_failure " + e.getMessage());
            return;
        }
        ml.melun.mangaview.report.CrashReporter.record(e);
    }

    private static boolean isRecoverableNetworkFetchFailure(Throwable e) {
        String message = e == null ? null : e.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.startsWith("request failed:")
                || lower.contains("network is unreachable")
                || lower.contains("failed to connect")
                || lower.contains("connection reset")
                || lower.contains("timeout");
    }

    static int parseEpisodeId(String href, String marker) {
        if(href == null || marker == null || marker.length() == 0)
            return -1;
        int start = href.indexOf(marker);
        if(start < 0)
            return -1;
        start += marker.length();
        int end = start;
        while(end < href.length() && Character.isDigit(href.charAt(end)))
            end++;
        if(end == start)
            return -1;
        try {
            return Integer.parseInt(href.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int parseEpisodeOptionId(String value) {
        if(value == null)
            return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Element findImageScript(Document d) {
        Elements paddings = d.select("div.view-padding");
        for(Element padding : paddings) {
            Element script = padding.selectFirst("script");
            if(script != null && script.data().contains("html_data+="))
                return script;
        }
        return d.selectFirst("script:containsData(html_data+=)");
    }

    private int extractSeed(String source, Document document, Element scriptElement) {
        int parsed = extractSeedFromText(source);
        if(parsed > 0)
            return parsed;
        if(scriptElement != null) {
            parsed = extractSeedFromText(scriptElement.html());
            if(parsed > 0)
                return parsed;
        }
        if(document != null) {
            for(Element script : document.select("script")) {
                parsed = extractSeedFromText(script.data());
                if(parsed > 0)
                    return parsed;
                parsed = extractSeedFromText(script.html());
                if(parsed > 0)
                    return parsed;
            }
        }
        return seed;
    }

    private int extractSeedFromText(String source) {
        if(source == null || source.length() == 0)
            return 0;
        String[] patterns = {
                "(?:var\\s+)?view_cnt\\s*=\\s*['\\\"]?(\\d+)",
                "(?:var\\s+)?viewCnt\\s*=\\s*['\\\"]?(\\d+)",
                "['\\\"]view_cnt['\\\"]\\s*[:=]\\s*['\\\"]?(\\d+)",
                "['\\\"]viewCnt['\\\"]\\s*[:=]\\s*['\\\"]?(\\d+)"
        };
        for(String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(source);
            if(matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (Exception e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private int fetchWolf(CustomHttpClient client, String viewPath, String epPath) {
        mode = 0;
        List<Manga> previousEpisodes = safeEpisodeCopy(eps);
        imgs = new ArrayList<>();
        Set<String> seenImages = new LinkedHashSet<>();
        eps = new ArrayList<>();
        boolean attemptedPage = false;

        for(int attempt = 0; attempt < 2; attempt++) {
            try {
            int titleId = this.titleId;
            if(titleId <= 0 && title != null)
                titleId = title.getId();
            if(titleId <= 0)
                return LOAD_OK;

            attemptedPage = true;
            CustomHttpClient.PageResponse page = client.mgetCachedPage(viewPath + titleId + "&num=" + id, PAGE_CACHE_TTL_MS);
            Document d = Jsoup.parse(page.body);

            try {
                Element header = d.selectFirst("div.image-view h2 span");
                if(header != null)
                    name = cleanViewerEpisodeName(header.ownText());
            }catch (Exception e){}

            addWolfImageCandidates(client, d, seenImages);

            List<Manga> titleEpisodes = title == null ? null : safeEpisodeCopy(title.getEps());
            if(titleEpisodes != null && titleEpisodes.size() > 0) {
                eps = titleEpisodes;
                for(Manga ep : eps) {
                    ep.setMode(0);
                    ep.setTitle(title);
                    ep.setTitleId(titleId);
                }
            } else {
                Manga next = wolfEpisode(d.selectFirst("section.webtoon-bottom li.next a[href^=\"" + epPath + titleId + "\"]"), titleId);
                Manga prev = wolfEpisode(d.selectFirst("section.webtoon-bottom li.prev a[href^=\"" + epPath + titleId + "\"]"), titleId);
                if(next != null)
                    eps.add(next);
                eps.add(this);
                if(prev != null)
                    eps.add(prev);
            }
            if(imgs.size() > 0 && !hasReachableWolfPageImage(client)) {
                imgs.clear();
                seenImages.clear();
                eps.clear();
                client.clearPageCache();
                if(attempt == 0) {
                    client.resolveWfwfDomainNow();
                    continue;
                }
            }
            if(imgs.size() == 0 && attempt == 0 && client.resolveWfwfDomainNow()) {
                imgs.clear();
                seenImages.clear();
                eps.clear();
                continue;
            }
            break;
            } catch (Exception e) {
                if(isCloudflareChallenge(e))
                    return LOAD_CAPTCHA;
                recordFetchException(e);
                break;
            }
        }
        restoreBetterEpisodeList(previousEpisodes);
        attachEpisodeSeriesMetadata();
        if(attemptedPage && imgs.size() == 0)
            return LOAD_ERROR;
        return LOAD_OK;
    }

    private boolean hasReachableWolfPageImage(CustomHttpClient client) {
        return hasUsableWolfPageImages();
    }

    public synchronized boolean ensureReachablePageImages(CustomHttpClient client) {
        if(client == null || !isOnline())
            return true;
        if(!(isComicWolfSource() || isWebtoonWolfSource()))
            return true;
        return hasUsableWolfPageImages();
    }

    static boolean hasUsableWolfPageImagesForTest(List<String> images) {
        Manga manga = new Manga(1, "test", "", MTitle.base_comic);
        manga.imgs = images == null ? null : new ArrayList<>(images);
        return manga.hasUsableWolfPageImages();
    }

    private boolean hasUsableWolfPageImages() {
        if(imgs == null || imgs.size() == 0)
            return false;
        for(String img : imgs)
            if(img != null && img.trim().length() > 0)
                return true;
        return false;
    }

    private void addWolfImageCandidates(CustomHttpClient client, Document document, Set<String> seenImages) {
        if(document == null)
            return;
        int before = imgs == null ? 0 : imgs.size();
        Elements images = document.select("div.image-view img, div.view-padding img, section.webtoon-body img, div.toon-view img, article img, main img");
        addWolfImageElements(client, images, seenImages);
        int afterPrimary = imgs == null ? 0 : imgs.size();
        if(afterPrimary == before)
            addWolfImageElements(client, document.select("body img"), seenImages);
    }

    private void addWolfImageElements(CustomHttpClient client, Elements images, Set<String> seenImages) {
        if(images == null)
            return;
        for(Element img : images) {
            for(String attr : new String[]{"data-original", "data-src", "data-lazy-src", "data-url", "src"}) {
                String src = img.attr(attr);
                if(isWolfPageImage(img, src))
                    addImageIfValid(client, seenImages, src);
            }
        }
    }

    private boolean isWolfPageImage(Element img, String src) {
        if(src == null)
            return false;
        String lower = src.toLowerCase(Locale.ROOT);
        if(lower.length() == 0
                || lower.contains("sprite")
                || lower.contains("logo")
                || lower.contains("banner")
                || lower.contains("advert")
                || lower.contains("sponsor")
                || lower.contains("popup")
                || lower.contains("/ad/")
                || lower.contains("/ads/")
                || lower.contains("blank")
                || lower.contains("loading"))
            return false;
        if(hasWolfAdToken(lower))
            return false;
        String cls = img == null ? "" : img.className().toLowerCase(Locale.ROOT);
        String id = img == null ? "" : img.id().toLowerCase(Locale.ROOT);
        String alt = img == null ? "" : img.attr("alt").toLowerCase(Locale.ROOT);
        if(cls.contains("logo") || cls.contains("banner") || hasWolfAdToken(cls)
                || id.contains("logo") || id.contains("banner") || hasWolfAdToken(id)
                || alt.contains("logo") || alt.contains("banner") || hasWolfAdToken(alt)
                || hasWolfBlockedAncestor(img))
            return false;
        return lower.matches(".*\\.(jpg|jpeg|png|webp|gif)(\\?.*)?$")
                || lower.contains("/data/")
                || lower.contains("/toon/")
                || lower.contains("/webtoon/")
                || lower.contains("/comic/");
    }

    private static boolean isNtkPageImage(Element img, String src) {
        if(!isImageSourceCandidate(src))
            return false;
        if(!isAllowedNtkImageHost(src))
            return false;
        String lower = src.trim().toLowerCase(Locale.ROOT);
        if(isNtkKpCvDescriptorImageUrl(lower))
            return true;
        if(lower.contains("/board_uploads/")
                || isDisallowedNtkContentImageUrl(lower))
            return false;
        String context = ntkImageContext(img);
        if(hasNtkBlockedImageContext(context))
            return false;
        return lower.contains("/webtoon_uploads/")
                || lower.contains("/manhwa_uploads/")
                || lower.contains("/comic_uploads/")
                || lower.contains("/blacktoon/episodes/")
                || lower.contains("/black/episodes/")
                || lower.contains("/wt/episodes/")
                || isNtkNumberedPageImage(lower)
                || isNtkCurrentCdnPageImage(lower)
                || isNtkRootHashPageImage(lower);
    }

    private static boolean isNtkNumberedPageImage(String src) {
        if(src == null || src.length() == 0)
            return false;
        return NTK_NUMBERED_PAGE_IMAGE_PATTERN.matcher(src).matches();
    }

    private static boolean isNtkCurrentCdnPageImage(String src) {
        if(src == null || src.length() == 0)
            return false;
        return NTK_CURRENT_CDN_PAGE_IMAGE_PATTERN.matcher(src).matches();
    }

    private static boolean isNtkRootHashPageImage(String src) {
        if(src == null || src.length() == 0)
            return false;
        return src.matches("(?i)^(?:https?:)?//(?:flysky\\d*m\\.com|moamoabon\\.com|fvcdn\\d*\\.com|aws-cdn\\d*\\.site|apihost\\d*\\.com)/[a-z0-9_-]{16,}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$");
    }

    private static boolean isNtkFallbackBoardPageImage(Element img, String src) {
        if(!isImageSourceCandidate(src))
            return false;
        if(!isAllowedNtkImageHost(src))
            return false;
        String lower = src.trim().toLowerCase(Locale.ROOT);
        if(isDisallowedNtkContentImageUrl(lower))
            return false;
        if(!isNtkBoardUploadImage(lower))
            return false;
        String context = ntkImageContext(img);
        return hasNtkViewerImageContext(context) && !hasNtkBlockedImageContext(context);
    }

    private static boolean isNtkBoardUploadImage(String src) {
        if(src == null)
            return false;
        String lower = src.trim().toLowerCase(Locale.ROOT);
        return lower.contains("/board_uploads/")
                && lower.matches(".*\\.(jpg|jpeg|png|webp|gif)(\\?.*)?$");
    }

    private boolean areCurrentNtkImagesOnlyBoardUploads() {
        if(imgs == null || imgs.size() == 0)
            return false;
        for(String src : imgs) {
            if(!isNtkBoardUploadImage(src))
                return false;
        }
        return true;
    }

    private static boolean isAllowedNtkImageHost(String src) {
        if(src == null)
            return false;
        String lower = src.trim().toLowerCase(Locale.ROOT);
        if(lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("//")) {
            Matcher matcher = Pattern.compile("^(?:https?:)?//([^/?#]+)").matcher(lower);
            if(!matcher.find())
                return false;
            String host = matcher.group(1);
            if(host.contains("naver") || host.contains("pstatic"))
                return false;
            if(isNtkUploadImagePath(lower))
                return true;
            return host.matches(NTK_IMAGE_HOST_PATTERN);
        }
        return true;
    }

    private static boolean isNtkUploadImagePath(String src) {
        if(src == null)
            return false;
        String lower = src.toLowerCase(Locale.ROOT);
        return (lower.contains("/webtoon_uploads/")
                || lower.contains("/manhwa_uploads/")
                || lower.contains("/comic_uploads/"))
                && lower.matches(".*\\.(jpg|jpeg|png|webp|txt|xml)(?:[?#].*)?$");
    }

    private static boolean isDisallowedNtkContentImageUrl(String src) {
        if(src == null)
            return true;
        String lower = src.trim().toLowerCase(Locale.ROOT);
        String path = lower;
        try {
            URI uri = URI.create(lower.startsWith("//") ? "https:" + lower : lower);
            if(uri.getPath() != null)
                path = uri.getPath().toLowerCase(Locale.ROOT);
        } catch(Exception ignored) {
            int query = path.indexOf('?');
            if(query >= 0)
                path = path.substring(0, query);
            int fragment = path.indexOf('#');
            if(fragment >= 0)
                path = path.substring(0, fragment);
        }
        return path.startsWith("/api/m/")
                || path.startsWith("/api/ad/")
                || path.startsWith("/cdn-cgi/")
                || path.contains("/challenge")
                || path.contains("/turnstile")
                || path.contains("/cloudflare")
                || path.contains("/verification")
                || path.contains("/captcha")
                || path.contains("/thumbs/")
                || path.contains("/banner")
                || path.contains("/advert")
                || path.contains("/sponsor")
                || path.contains("/popup")
                || path.contains("/ads/")
                || path.contains("/ad/")
                || Pattern.compile("(?i)(^|[-_/])(ad|ads|banner|advert|sponsor|popup)([-_/]|$)")
                .matcher(path)
                .find();
    }

    private static boolean hasNtkViewerContent(Document document) {
        return document != null
                && document.selectFirst(".vw-main, .vw-imgs, .viewer-content, .toon-view, main[class*=viewer]") != null;
    }

    private static boolean hasNtkViewerImageContext(String context) {
        if(context == null)
            return false;
        return context.contains("vw-main")
                || context.contains("vw-imgs")
                || context.contains("viewer-content")
                || context.contains("toon-view");
    }

    private static boolean hasNtkBlockedImageContext(String context) {
        if(context == null)
            return false;
        return context.contains("banner")
                || context.contains("advert")
                || context.contains("sponsor")
                || context.contains("popup")
                || context.contains("bn-r")
                || context.contains("bn-s")
                || context.contains("bn-ph")
                || context.contains("data-br")
                || context.contains("nofollow")
                || context.contains("ad-")
                || context.contains("-ad")
                || context.contains("thumb")
                || context.contains("cover")
                || context.contains("logo")
                || context.contains("avatar")
                || context.contains("profile")
                || context.contains("recommend")
                || context.contains("related")
                || context.contains("episode-list")
                || context.contains("list-item")
                || context.contains("card");
    }

    private static String ntkImageContext(Element img) {
        if(img == null)
            return "";
        StringBuilder context = new StringBuilder();
        context.append(img.id()).append(' ')
                .append(img.className()).append(' ')
                .append(img.attr("alt")).append(' ')
                .append(img.attr("title")).append(' ')
                .append(img.attr("aria-label"));
        for(Element parent = img.parent(); parent != null; parent = parent.parent()) {
            context.append(' ')
                    .append(parent.id()).append(' ')
                    .append(parent.className()).append(' ')
                    .append(parent.attr("href")).append(' ')
                    .append(parent.attr("rel")).append(' ')
                    .append(parent.attr("aria-label")).append(' ')
                    .append(parent.attr("data-br"));
            if("body".equals(parent.tagName()))
                break;
        }
        return context.toString().toLowerCase(Locale.ROOT);
    }

    static boolean isNtkPageImageForTest(String html) {
        Element img = Jsoup.parseBodyFragment(html == null ? "" : html).selectFirst("img");
        if(img == null)
            return false;
        String src = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
        return isNtkPageImage(img, src);
    }

    static boolean isNtkFallbackBoardPageImageForTest(String html) {
        Element img = Jsoup.parseBodyFragment(html == null ? "" : html).selectFirst("img");
        if(img == null)
            return false;
        String src = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
        return isNtkFallbackBoardPageImage(img, src);
    }

    static boolean looksLikeNtkBlockedPageForTest(String body) {
        return looksLikeNtkBlockedPage(body);
    }

    static boolean looksLikeNtkMissingPageForTest(String body) {
        return looksLikeNtkMissingPage(body);
    }

    static String ntkViewerEpisodeNameForTest(String body) {
        return extractNtkViewerEpisodeName(Jsoup.parse(body == null ? "" : body));
    }

    static List<String> ntkEmbeddedPageImagesForTest(String body) {
        Manga manga = new Manga(1, "test", "", MTitle.base_webtoon);
        manga.imgs = new ArrayList<>();
        Set<String> seenImages = new LinkedHashSet<>();
        Set<String> fallbackImages = new LinkedHashSet<>();
        manga.addNtkTextImageCandidates(null, body, seenImages, fallbackImages);
        if(manga.imgs.size() == 0) {
            for(String src : fallbackImages)
                manga.addImageIfValid(null, seenImages, src);
        }
        return manga.imgs;
    }

    static List<String> ntkViewerMetaPageImagesForTest(String body, String path) {
        Manga manga = new Manga(1, "test", "", MTitle.base_webtoon);
        manga.imgs = new ArrayList<>();
        Set<String> seenImages = new LinkedHashSet<>();
        manga.addNtkViewerMetaImageCandidates(null, body, path, seenImages);
        return manga.imgs;
    }

    static List<String> ntkDocumentPageImagesForTest(String body) {
        Manga manga = new Manga(1, "test", "", MTitle.base_webtoon);
        manga.imgs = new ArrayList<>();
        Set<String> seenImages = new LinkedHashSet<>();
        Set<String> fallbackImages = new LinkedHashSet<>();
        Document d = Jsoup.parse(body == null ? "" : body);
        manga.addNtkDocumentImageCandidates(null, d, seenImages, fallbackImages);
        if(manga.imgs.size() == 0) {
            for(String src : fallbackImages)
                manga.addImageIfValid(null, seenImages, src);
        }
        return manga.imgs;
    }

    static boolean shouldTrimNtkGeneratedPagesBeforeFirstFrameForTest() {
        return NTK_GENERATED_TRIM_BEFORE_FIRST_FRAME;
    }

    static int ntkGeneratedInitialValidationPageCountForTest(int pageCount) {
        return ntkGeneratedInitialValidationPageCount(pageCount);
    }

    static boolean shouldUseImmediateNtkGeneratedFastPathForTest(int baseMode, String path, int imageCount) {
        return shouldUseImmediateNtkGeneratedFastPath(baseMode, path, imageCount);
    }

    static String ntkCanonicalWebtoonSlugCandidateForTest(String titlePath, String titleName) {
        return ntkCanonicalWebtoonSlugCandidate(titlePath, titleName);
    }

    static String ntkSlugWebtoonImageUrlForTest(String slug, String episodeId, int page, String extension) {
        return ntkSlugWebtoonImageUrl(slug, episodeId, page, extension);
    }

    static String ntkGeneratedEpisodeIdForTest(String path) {
        return ntkGeneratedEpisodeIdForPath(path);
    }

    static String ntkApiEpisodeIdForTest(String pathEpisodeId) {
        return ntkApiEpisodeIdForPath(pathEpisodeId);
    }

    static String ntkViewerEmbeddedImageEpisodeIdForTest(String body, String pathEpisodeId) {
        return ntkViewerEmbeddedImageEpisodeId(body, pathEpisodeId);
    }

    static String ntkViewerApiImageEpisodeIdForTest(String tokenEpisodeId, String knownImageEpisodeId,
                                                    String pathEpisodeId, String embeddedEpisodeId) {
        return ntkViewerApiImageEpisodeId(tokenEpisodeId, knownImageEpisodeId, pathEpisodeId, embeddedEpisodeId);
    }

    static boolean shouldRetryNtkKnownImageEpisodeIdForTest(String tokenEpisodeId, String pathEpisodeId,
                                                            String apiEpisodeId, String knownImageEpisodeId) {
        return shouldRetryNtkKnownImageEpisodeId(tokenEpisodeId, pathEpisodeId,
                apiEpisodeId, knownImageEpisodeId, 0);
    }

    static boolean shouldRetryNtkKnownImageEpisodeIdForTest(String tokenEpisodeId, String pathEpisodeId,
                                                            String apiEpisodeId, String knownImageEpisodeId,
                                                            int knownImageCount) {
        return shouldRetryNtkKnownImageEpisodeId(tokenEpisodeId, pathEpisodeId,
                apiEpisodeId, knownImageEpisodeId, knownImageCount);
    }

    static boolean isNtkViewerImageMetasExplicitlyEmptyForTest(String body) {
        return isNtkViewerImageMetasExplicitlyEmpty(body);
    }

    static boolean hasNtkViewerImageApiPayloadForTest(String body) {
        return hasNtkViewerImageApiPayload(body);
    }

    static String ntkViewerImagesTokenForTest(String body) {
        return ntkViewerImagesToken(normalizeNtkViewerPayloadText(body));
    }

    static boolean isNtkViewerConfirmedEmptyPayloadForTest(String body, String path) {
        return isNtkViewerConfirmedEmptyPayload(body, path);
    }

    static boolean shouldPreferNtkHtmlImagePageForTest(String directBody, String fallbackBody, String path) {
        return shouldPreferNtkHtmlImagePage(
                new CustomHttpClient.PageResponse(200, directBody, false),
                new CustomHttpClient.PageResponse(200, fallbackBody, false),
                path);
    }

    static boolean shouldProbeKnownManhwaGeneratedBeforeApiFallbackForTest(String path, int imageCount) {
        return shouldProbeKnownGeneratedBeforeApiFallback(path, imageCount);
    }

    static boolean shouldProbeKnownGeneratedBeforeApiFallbackForTest(String path, int imageCount) {
        return shouldProbeKnownGeneratedBeforeApiFallback(path, imageCount);
    }

    static boolean shouldProbeGeneratedModeBeforeApiForTest(String path, int imageCount) {
        return shouldProbeGeneratedModeBeforeApi(path, imageCount);
    }

    static boolean shouldSkipNtkGeneratedForEpisodePathForTest(String path) {
        return shouldSkipNtkGeneratedForEpisodePath(path);
    }

    static boolean shouldStartDirectOnlyNtkImageApiPrefetchForTest(String path) {
        return shouldStartDirectOnlyNtkImageApiPrefetch(path);
    }

    private boolean hasWolfBlockedAncestor(Element img) {
        if(img == null)
            return false;
        for(Element parent = img.parent(); parent != null; parent = parent.parent()) {
            String context = (parent.id() + " " + parent.className()).toLowerCase(Locale.ROOT);
            if(context.contains("banner")
                    || context.contains("advert")
                    || context.contains("sponsor")
                    || context.contains("popup")
                    || context.contains("광고")
                    || hasWolfAdToken(context))
                return true;
            if("body".equals(parent.tagName()))
                break;
        }
        return false;
    }

    private boolean hasWolfAdToken(String value) {
        if(value == null)
            return false;
        return Pattern.compile("(^|[^a-z0-9가-힣])(ad|ads|advert|sponsor|popup|광고)([^a-z0-9가-힣]|$)")
                .matcher(value.toLowerCase(Locale.ROOT))
                .find();
    }

    private void restoreBetterEpisodeList(List<Manga> previousEpisodes) {
        if(previousEpisodes == null || previousEpisodes.size() == 0)
            return;
        if(!containsEpisodeId(previousEpisodes, id))
            return;
        int currentSize = eps == null ? 0 : eps.size();
        if(previousEpisodes.size() > currentSize)
            eps = new ArrayList<>(previousEpisodes);
    }

    private boolean containsEpisodeId(List<Manga> episodes, int episodeId) {
        if(episodes == null)
            return false;
        for(Manga episode : episodes)
            if(episode != null && episode.getId() == episodeId && episode.getBaseMode() == getBaseMode())
                return true;
        return false;
    }

    private void compactNtkImageCandidates(String body, Set<String> seenImages) {
        if(imgs == null || imgs.size() <= 1)
            return;
        int before = imgs.size();
        ArrayList<String> compacted = new ArrayList<>(before);
        LinkedHashSet<String> compactKeys = new LinkedHashSet<>();
        for(String image : imgs) {
            String key = ntkImageDedupKey(image);
            if(key.length() == 0)
                key = image == null ? "" : image.trim().toLowerCase(Locale.ROOT);
            if(key.length() == 0 || !compactKeys.add(key))
                continue;
            compacted.add(image);
        }
        int metaCount = ntkViewerMetaPageCount(normalizeNtkViewerPayloadText(body));
        if(metaCount > 0 && compacted.size() > metaCount) {
            compacted = new ArrayList<>(compacted.subList(0, metaCount));
            compactKeys.clear();
            for(String image : compacted) {
                String key = ntkImageDedupKey(image);
                if(key.length() > 0)
                    compactKeys.add(key);
            }
        }
        if(compacted.size() == before)
            return;
        imgs.clear();
        imgs.addAll(compacted);
        if(seenImages != null) {
            seenImages.clear();
            seenImages.addAll(compactKeys);
        }
    }

    private static String ntkImageDedupKey(String img) {
        if(img == null)
            return "";
        String normalized = normalizeNtkEmbeddedImageText(img.trim());
        String proxied = ntkProxiedImageUrl(normalized);
        if(proxied.length() > 0)
            normalized = normalizeNtkEmbeddedImageText(proxied.trim());
        if(normalized.startsWith("//"))
            normalized = "https:" + normalized;
        int fragment = normalized.indexOf('#');
        if(fragment >= 0)
            normalized = normalized.substring(0, fragment);
        NtkGeneratedUrlIdentity generated = ntkGeneratedUrlIdentity(normalized);
        if(generated != null)
            return generated.dedupKey();
        int query = normalized.indexOf('?');
        if(query >= 0) {
            String withoutQuery = normalized.substring(0, query);
            if(isNtkPageImage(null, withoutQuery) || isNtkFallbackBoardPageImage(null, withoutQuery))
                normalized = withoutQuery;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean addImageIfValid(CustomHttpClient client, Set<String> seenImages, String img) {
        if(img == null)
            return false;
        img = img.trim();
        if(!isImageSourceCandidate(img))
            return false;
        if(img.startsWith("//"))
            img = "https:" + img;
        else if(img.startsWith("/")) {
            if(client == null)
                return false;
            String baseUrl = client.isNtk() ? client.getUrl(img) : client.getUrl(baseMode);
            img = baseUrl + img;
        } else if(client != null && client.isNtk()
                && !img.toLowerCase(Locale.ROOT).startsWith("http://")
                && !img.toLowerCase(Locale.ROOT).startsWith("https://")) {
            String path = "/" + img;
            img = client.getUrl(path) + path;
        }
        if(isNtkImageCandidateContext(client) && !isNtkPageImage(null, img)
                && !isNaverWebtoonPageImage(img)
                && !isNtkKpCvDescriptorImageUrl(img)) {
            Log.d(TAG, "ntk_non_page_image_rejected path=" + getNtkEpisodePath()
                    + ",src=" + logSafeUrl(img));
            return false;
        }
        String dedupKey = ntkImageDedupKey(img);
        if(dedupKey.length() == 0)
            dedupKey = img.toLowerCase(Locale.ROOT);
        if(!seenImages.add(dedupKey))
            return false;
        imgs.add(img);
        return true;
    }

    private boolean isNtkImageCandidateContext(CustomHttpClient client) {
        if(client != null && client.isNtk())
            return true;
        String path = getNtkEpisodePath();
        return path != null && path.matches("(?i)^/(manhwa|webtoon|comic)/[^/?#]+/[^/?#]+.*");
    }

    private static String logSafeUrl(String url) {
        if(url == null)
            return "";
        String value = url.replace('\n', ' ').replace('\r', ' ');
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    private boolean shouldSkipCanonicalWebtoonNonWtImageProbe(String src) {
        String path = getNtkEpisodePath();
        if(!shouldPreferNtkApiForCanonicalWebtoonPath(path))
            return false;
        if(src == null || src.length() == 0)
            return true;
        if(src.contains("/wt/episodes/"))
            return false;
        if(isTrustedKnownNtkGeneratedImageUrl(src)) {
            Log.d(TAG, "ntk_canonical_webtoon_allow_known_generated_probe path=" + path
                    + ",url=" + safeLogImageUrl(src));
            return false;
        }
        Log.d(TAG, "ntk_canonical_webtoon_skip_non_wt_probe path=" + path
                + ",url=" + safeLogImageUrl(src));
        return true;
    }

    private boolean isTrustedKnownNtkGeneratedImageUrl(String src) {
        if(src == null || src.length() == 0)
            return false;
        String lower = src.toLowerCase(Locale.ROOT);
        String[][] patterns = new String[][]{
                {"(?i)/(?:black(?:toon)?/)?episodes/(\\d{1,12})/(\\d{1,12})/(?:p)?\\d{1,4}\\.(?:jpg|jpeg|webp|png)(?:[?#].*)?$"},
                {"(?i)/(?:manhwa|webtoon)/(\\d{1,12})/(\\d{1,12})/(?:p)?\\d{1,4}\\.(?:jpg|jpeg|webp|png)(?:[?#].*)?$"}
        };
        for(String[] holder : patterns) {
            Matcher matcher = Pattern.compile(holder[0]).matcher(lower);
            if(matcher.find() && isTrustedKnownNtkGeneratedCandidate(matcher.group(1), matcher.group(2)))
                return true;
        }
        return false;
    }

    private static boolean isNaverWebtoonPageImage(String src) {
        if(src == null)
            return false;
        String lower = src.trim().toLowerCase(Locale.ROOT);
        if(lower.length() == 0
                || lower.contains("thumbnail")
                || lower.contains("banner")
                || lower.contains("advert")
                || lower.contains("sponsor")
                || lower.contains("popup")
                || lower.contains("/ad/")
                || lower.contains("/ads/"))
            return false;
        try {
            URI uri = URI.create(lower.startsWith("//") ? "https:" + lower : lower);
            String host = uri.getHost();
            String path = uri.getPath();
            if(host == null || path == null)
                return false;
            host = host.toLowerCase(Locale.ROOT);
            path = path.toLowerCase(Locale.ROOT);
            return "image-comic.pstatic.net".equals(host)
                    && path.matches("^/webtoon/\\d{5,}/\\d+/[^/?#]+\\.(?:jpg|jpeg|png|webp)$");
        } catch(Exception ignored) {
            return false;
        }
    }

    private static boolean isImageSourceCandidate(String img) {
        if(img == null)
            return false;
        String lower = img.toLowerCase(Locale.ROOT);
        if(lower.length() == 0
                || lower.contains("blank")
                || lower.contains("loading")
                || lower.startsWith("data:")
                || lower.startsWith("javascript:")
                || lower.startsWith("about:"))
            return false;
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("//")
                || lower.startsWith("/")
                || lower.contains("/")
                || lower.contains(".jpg")
                || lower.contains(".jpeg")
                || lower.contains(".png")
                || lower.contains(".webp")
                || lower.contains(".gif");
    }

    private Manga wolfEpisode(Element link, int titleId) {
        if(link == null) return null;
        int epId = MainPageWebtoon.getQueryInt(link.attr("href"), "num");
        if(epId <= 0) return null;
        String epTitle = link.ownText().replace("\u00a0", " ").trim();
        Manga manga = new Manga(epId, epTitle, "", baseMode);
        manga.setMode(0);
        manga.setTitle(title);
        manga.setTitleId(titleId);
        return manga;
    }

    public List<Manga> getEps() {
        return eps;
    }

    public void setEps(List<Manga> eps) {
        this.eps = Title.orderedEpisodeSnapshot(eps);
        attachEpisodeSeriesMetadata();
    }

    private void attachEpisodeSeriesMetadata() {
        if(eps == null)
            return;
        int currentTitleId = resolvedTitleId(this);
        Title currentTitle = getTitle();
        if(currentTitleId <= 0 && currentTitle == null)
            return;
        for(Manga episode : eps) {
            if(episode == null)
                continue;
            if(episode.getTitleId() <= 0 && currentTitleId > 0)
                episode.setTitleId(currentTitleId);
            if(episode.getTitle() == null && currentTitle != null)
                episode.setTitle(currentTitle);
        }
    }

    public Title getTitle() {
        return title;
    }

    public int getTitleId() {
        if(titleId > 0)
            return titleId;
        return title == null ? -1 : title.getId();
    }

    public List<String> getImgs(Context context) {
        if(mode == 0 && isFetchInProgress())
            return imageSnapshot();
        synchronized (this) {
            if (mode != 0) {
                if (imgs == null) {
                    imgs = new ArrayList<>();
                    if(offlinePath == null || offlinePath.length() == 0)
                        return imgs;
                    //is offline : read image list
                    if (useScopedStorageHome(offlinePath)) {
                        DocumentFile root = documentFileFromUri(context, offlinePath);
                        DocumentFile[] offimgs = root == null ? null : root.listFiles();
                        if(offimgs == null)
                            return imgs;
                        Arrays.sort(offimgs, (documentFile, t1) -> String.valueOf(documentFile.getName()).compareTo(String.valueOf(t1.getName())));
                        for (DocumentFile f : offimgs) {
                            if(isOfflineImageFile(f == null ? null : f.getName(), f != null && f.isFile()))
                                imgs.add(f.getUri().toString());
                        }
                    } else {
                        File[] offimgs = new File(offlinePath).listFiles();
                        if(offimgs == null)
                            return imgs;
                        Arrays.sort(offimgs);
                        for (File img : offimgs) {
                            if(isOfflineImageFile(img == null ? null : img.getName(), img != null && img.isFile()))
                                imgs.add(img.getAbsolutePath());
                        }
                    }
                }
            }
            return imgs;
        }
    }

    private List<String> imageSnapshot() {
        List<String> current = imgs;
        if(current == null)
            return null;
        try {
            return new ArrayList<>(current);
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private static boolean isOfflineImageFile(String name, boolean file) {
        if(!file || name == null)
            return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".part") || "downloading".equals(lower))
            return false;
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif");
    }

    public int getSeed() {
        return seed;
    }

    public void setSeed(int seed) {
        this.seed = seed;
    }

    public String toString() {
        JSONObject tmp = new JSONObject();
        try {
            tmp.put("id", id);
            tmp.put("name", name);
            tmp.put("date", date);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return tmp.toString();
    }

    public void setTitle(Title title) {
        this.title = title;
        if(title != null) {
            titleId = title.getId();
            String episodePath = ntkEpisodePath == null ? "" : ntkEpisodePath.trim();
            if(episodePath.length() > 0)
                title.applyNtkTitlePathFromEpisodePath(episodePath);
        }
        attachEpisodeSeriesMetadata();
    }

    public void setTitleId(int titleId) {
        this.titleId = titleId;
        attachEpisodeSeriesMetadata();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Manga && this.id == ((Manga) obj).getId();
    }

    @Override
    public int hashCode() {
        return id;
    }

    public void setOfflinePath(String offlinePath) {
        this.offlinePath = offlinePath;
    }

    public String getOfflinePath() {
        return this.offlinePath;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public String getUrl() {
        String ntkPath = getNtkEpisodePath();
        if(ntkPath.length() > 0)
            return ntkPath;
        if(titleId > 0 && shouldUseNtkUrl()) {
            String segment = baseMode == MTitle.base_webtoon ? "webtoon" : "manhwa";
            return "/" + segment + "/" + titleId + "/" + id;
        }
        if(isComicWolfSource()) {
            int tid = titleId;
            if(tid <= 0 && title != null)
                tid = title.getId();
            if(tid > 0)
                return "/cv?toon=" + tid + "&num=" + resolvedWolfEpisodeId();
        }
        if(isWebtoonWolfSource()) {
            int tid = titleId;
            if(tid <= 0 && title != null)
                tid = title.getId();
            if(tid > 0)
                return "/view?toon=" + tid + "&num=" + resolvedWolfEpisodeId();
        }
        return '/' + baseModeStr(baseMode) + '/' + id;
    }

    private int resolvedWolfEpisodeId() {
        int resolved = matchingWolfEpisodeId(title == null ? null : title.getEps());
        if(resolved > 0)
            return resolved;
        resolved = matchingWolfEpisodeId(eps);
        return resolved > 0 ? resolved : id;
    }

    private int matchingWolfEpisodeId(List<Manga> episodes) {
        if(episodes == null || episodes.size() == 0 || !hasExplicitWolfSource())
            return 0;
        String currentEpisodeNumber = episodeNumberKey(name);
        if(currentEpisodeNumber.length() == 0)
            return 0;
        List<Manga> snapshot;
        try {
            snapshot = new ArrayList<>(episodes);
        } catch (RuntimeException e) {
            return 0;
        }
        for(Manga episode : snapshot) {
            if(episode == null || episode.getId() != id || !sameSeriesEpisode(episode))
                continue;
            String episodeNumber = episodeNumberKey(episode.getName());
            if(currentEpisodeNumber.equals(episodeNumber))
                return id;
        }
        for(Manga episode : snapshot) {
            if(episode == null || episode == this || !sameSeriesEpisode(episode))
                continue;
            String episodeNumber = episodeNumberKey(episode.getName());
            if(currentEpisodeNumber.equals(episodeNumber))
                return episode.getId();
        }
        return 0;
    }

    public static String safeUrl(Manga manga) {
        if(manga == null)
            return null;
        try {
            return manga.getUrl();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isWebtoonWolfSource() {
        return baseMode == MTitle.base_webtoon;
    }

    private boolean isComicWolfSource() {
        return baseMode == base_comic;
    }

    private boolean shouldFetchNtk(CustomHttpClient client) {
        if(hasExplicitWolfSource())
            return false;
        if(hasExplicitNtkSource())
            return true;
        return client != null && client.isNtk();
    }

    private boolean shouldUseNtkUrl() {
        if(hasExplicitWolfSource())
            return false;
        if(hasExplicitNtkSource())
            return true;
        return p != null && p.isNtkSite();
    }

    private boolean hasExplicitNtkSource() {
        return "ntk".equals(sourceSite());
    }

    private boolean hasExplicitWolfSource() {
        return "wfwf".equals(sourceSite());
    }

    private String sourceSite() {
        return title == null ? "" : title.getSourceSite();
    }

    public boolean useBookmark() {
        return id > 0 && (mode == 0 || mode == 3);
    }

    public boolean isOnline() {
        return id > 0 && mode == 0;
    }

    public Manga nextEp() {
        if (isOnline()) {
            List<Manga> episodes = effectiveEpisodes();
            if (episodes == null || episodes.size() == 0) {
                return null;
            } else {
                int index = findEpisodeIndex(episodes);
                if (index < 0) return null;
                Manga adjacent = null;
                for (int i = index - 1; i >= 0; i--) {
                    Manga episode = episodes.get(i);
                    if (sameSeriesEpisode(episode) && !sameEpisodeIdentity(this, episode)) {
                        adjacent = episode;
                        break;
                    }
                }
                return preferCloserVisibleEpisode(episodes, this, adjacent, true);
            }
        } else {
            return sameSeriesEpisode(nextEp) ? nextEp : null;
        }
    }

    public Manga prevEp() {
        if (isOnline()) {
            List<Manga> episodes = effectiveEpisodes();
            if (episodes == null || episodes.size() == 0) {
                return null;
            } else {
                int index = findEpisodeIndex(episodes);
                if (index < 0) return null;
                Manga adjacent = null;
                for (int i = index + 1; i < episodes.size(); i++) {
                    Manga episode = episodes.get(i);
                    if (sameSeriesEpisode(episode) && !sameEpisodeIdentity(this, episode)) {
                        adjacent = episode;
                        break;
                    }
                }
                return preferCloserVisibleEpisode(episodes, this, adjacent, false);
            }
        } else {
            return sameSeriesEpisode(prevEp) ? prevEp : null;
        }
    }

    private List<Manga> effectiveEpisodes() {
        List<Manga> titleEpisodes = title == null ? null : safeEpisodeCopy(title.getEps());
        int titleIndex = findEpisodeIndex(titleEpisodes);
        int localIndex = findEpisodeIndex(eps);
        if(titleIndex >= 0 && (localIndex < 0 || titleEpisodes.size() >= eps.size()))
            return titleEpisodes;
        if(localIndex >= 0)
            return eps;
        if(titleEpisodes != null && titleEpisodes.size() > 0 && eps == null)
            return titleEpisodes;
        return eps;
    }

    private int findEpisodeIndex(List<Manga> episodes) {
        if (episodes == null) return -1;
        for (int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if (episode == this && sameSeriesEpisode(episode)) return i;
        }
        for (int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if (sameExactEpisodeIdentity(this, episode)) return i;
        }
        for (int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if (sameEpisodeIdentity(this, episode)) return i;
        }
        return -1;
    }

    private boolean sameSeriesEpisode(Manga episode) {
        if(episode == null)
            return false;
        if(episode.getBaseMode() != getBaseMode())
            return false;
        int currentTitleId = resolvedTitleId(this);
        int episodeTitleId = resolvedTitleId(episode);
        if(currentTitleId > 0 && episodeTitleId > 0)
            return currentTitleId == episodeTitleId;
        return true;
    }

    public static boolean sameEpisodeIdentity(Manga first, Manga second) {
        if(first == null || second == null)
            return false;
        if(first.getBaseMode() != second.getBaseMode())
            return false;
        int firstTitleId = resolvedTitleId(first);
        int secondTitleId = resolvedTitleId(second);
        if(firstTitleId > 0 && secondTitleId > 0 && firstTitleId != secondTitleId)
            return false;
        if(usesWfwfIdentity(first, second))
            return sameWolfEpisodeIdentity(first, second);
        if(usesNtkIdentity(first, second))
            return sameNtkEpisodeIdentity(first, second);
        if(first.getId() != second.getId())
            return false;
        if(usesEpisodeNameDisambiguation(first, second)) {
            String firstName = episodeNameKey(first.getName());
            String secondName = episodeNameKey(second.getName());
            if(firstName.length() > 0 && secondName.length() > 0 && !firstName.equals(secondName))
                return false;
        }
        return true;
    }

    private static boolean sameExactEpisodeIdentity(Manga first, Manga second) {
        if(first == null || second == null)
            return false;
        if(first.getId() != second.getId() || first.getBaseMode() != second.getBaseMode())
            return false;
        int firstTitleId = resolvedTitleId(first);
        int secondTitleId = resolvedTitleId(second);
        if(firstTitleId > 0 && secondTitleId > 0 && firstTitleId != secondTitleId)
            return false;
        if(isWfwfEpisode(first) || isWfwfEpisode(second) || isNtkEpisode(first) || isNtkEpisode(second)) {
            String firstName = episodeNameKey(first.getName());
            String secondName = episodeNameKey(second.getName());
            if(firstName.length() > 0 && secondName.length() > 0 && !firstName.equals(secondName))
                return false;
        }
        if(isNtkEpisode(first) || isNtkEpisode(second)) {
            String firstPath = ntkEpisodePathKey(first);
            String secondPath = ntkEpisodePathKey(second);
            return firstPath.length() == 0 || secondPath.length() == 0 || firstPath.equals(secondPath);
        }
        return true;
    }

    private static boolean sameWolfEpisodeIdentity(Manga first, Manga second) {
        String firstNumber = episodeNumberKey(first.getName());
        String secondNumber = episodeNumberKey(second.getName());
        if(firstNumber.length() > 0 && secondNumber.length() > 0)
            return firstNumber.equals(secondNumber);
        if(first.getId() != second.getId())
            return false;
        String firstName = episodeNameKey(first.getName());
        String secondName = episodeNameKey(second.getName());
        return firstName.length() == 0 || secondName.length() == 0 || firstName.equals(secondName);
    }

    private static boolean sameNtkEpisodeIdentity(Manga first, Manga second) {
        String firstPath = ntkEpisodePathKey(first);
        String secondPath = ntkEpisodePathKey(second);
        if(firstPath.length() > 0 && secondPath.length() > 0)
            return firstPath.equals(secondPath);
        String firstNumber = episodeNumberKey(first.getName());
        String secondNumber = episodeNumberKey(second.getName());
        if(firstNumber.length() > 0 && secondNumber.length() > 0)
            return firstNumber.equals(secondNumber);
        if(first.getId() != second.getId())
            return false;
        String firstName = episodeNameKey(first.getName());
        String secondName = episodeNameKey(second.getName());
        return firstName.length() == 0 || secondName.length() == 0 || firstName.equals(secondName);
    }

    public static String episodeIdentityKey(Manga manga) {
        if(manga == null)
            return ":0:0:0:";
        String key = mangaSourceSite(manga)
                + ":" + manga.getBaseMode()
                + ":" + resolvedTitleId(manga)
                + ":" + manga.getId();
        if(isWfwfEpisode(manga)) {
            String nameKey = episodeNameKey(manga.getName());
            if(nameKey.length() > 0)
                key += ":" + nameKey;
        } else if(isNtkEpisode(manga)) {
            String pathKey = ntkEpisodePathKey(manga);
            if(pathKey.length() > 0)
                key += ":" + pathKey;
            String nameKey = episodeNameKey(manga.getName());
            if(nameKey.length() > 0)
                key += ":" + nameKey;
        }
        return key;
    }

    private static boolean usesEpisodeNameDisambiguation(Manga first, Manga second) {
        return false;
    }

    private static boolean usesWfwfIdentity(Manga first, Manga second) {
        return isWfwfEpisode(first) || isWfwfEpisode(second);
    }

    private static boolean usesNtkIdentity(Manga first, Manga second) {
        return isNtkEpisode(first) || isNtkEpisode(second);
    }

    private static boolean isWfwfEpisode(Manga manga) {
        return "wfwf".equals(mangaSourceSite(manga));
    }

    private static boolean isNtkEpisode(Manga manga) {
        return "ntk".equals(mangaSourceSite(manga));
    }

    private static String ntkEpisodePathKey(Manga manga) {
        if(manga == null)
            return "";
        String path = manga.getNtkEpisodePath();
        return path == null ? "" : path.trim();
    }

    private static String mangaSourceSite(Manga manga) {
        if(manga == null || manga.getTitle() == null)
            return "";
        return manga.getTitle().getSourceSite();
    }

    private static String episodeNameKey(String name) {
        if(name == null)
            return "";
        String value = VIEWER_EPISODE_PREFIX_PATTERN.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceFirst("");
        String episodeNumbers = episodeNumberKey(value);
        if(episodeNumbers.length() > 0)
            return episodeNumbers;
        return EPISODE_WHITESPACE_PATTERN.matcher(value).replaceAll("");
    }

    private static String episodeNumberKey(String name) {
        if(name == null)
            return "";
        String value = VIEWER_EPISODE_PREFIX_PATTERN.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceFirst("");
        String compact = EPISODE_WHITESPACE_PATTERN.matcher(value).replaceAll("");
        if(compact.contains("번외")
                || compact.contains("외전")
                || compact.contains("특별")
                || compact.contains("부록")
                || compact.contains("기록")
                || compact.contains("후기")
                || compact.contains("프롤로그"))
            return "";
        Matcher matcher = EPISODE_NUMBER_PATTERN.matcher(value);
        String episodeNumbers = "";
        while(matcher.find())
            episodeNumbers = matcher.group(1);
        if(episodeNumbers.length() > 0)
            return normalizeEpisodeNumbers(episodeNumbers);
        return "";
    }

    public static String visibleEpisodeNumberKey(String name) {
        return episodeNumberKey(name);
    }

    public static Manga preferCloserVisibleEpisode(List<Manga> episodes, Manga current, Manga fallback, boolean next) {
        Manga visible = closestVisibleEpisode(episodes, current, next);
        if(visible == null)
            return fallback;
        if(fallback == null)
            return visible;
        EpisodeNumberRange currentRange = visibleEpisodeNumberRange(current == null ? null : current.getName());
        EpisodeNumberRange visibleRange = visibleEpisodeNumberRange(visible.getName());
        EpisodeNumberRange fallbackRange = visibleEpisodeNumberRange(fallback.getName());
        if(currentRange == null || visibleRange == null || fallbackRange == null)
            return fallback;
        if(next)
            return visibleRange.min + EPISODE_RANGE_EPSILON < fallbackRange.min ? visible : fallback;
        return visibleRange.max > fallbackRange.max + EPISODE_RANGE_EPSILON ? visible : fallback;
    }

    private static Manga closestVisibleEpisode(List<Manga> episodes, Manga current, boolean next) {
        if(episodes == null || current == null)
            return null;
        EpisodeNumberRange currentRange = visibleEpisodeNumberRange(current.getName());
        if(currentRange == null)
            return null;
        Manga best = null;
        EpisodeNumberRange bestRange = null;
        List<Manga> snapshot;
        try {
            snapshot = new ArrayList<>(episodes);
        } catch (RuntimeException e) {
            return null;
        }
        for(Manga episode : snapshot) {
            if(episode == null || episode == current || !current.sameSeriesEpisode(episode)
                    || sameEpisodeIdentity(current, episode))
                continue;
            EpisodeNumberRange range = visibleEpisodeNumberRange(episode.getName());
            if(range == null)
                continue;
            if(next) {
                if(range.min <= currentRange.max + EPISODE_RANGE_EPSILON)
                    continue;
                if(best == null || range.min < bestRange.min - EPISODE_RANGE_EPSILON
                        || (Math.abs(range.min - bestRange.min) <= EPISODE_RANGE_EPSILON && range.max < bestRange.max)) {
                    best = episode;
                    bestRange = range;
                }
            } else {
                if(range.max >= currentRange.min - EPISODE_RANGE_EPSILON)
                    continue;
                if(best == null || range.max > bestRange.max + EPISODE_RANGE_EPSILON
                        || (Math.abs(range.max - bestRange.max) <= EPISODE_RANGE_EPSILON && range.min > bestRange.min)) {
                    best = episode;
                    bestRange = range;
                }
            }
        }
        return best;
    }

    private static EpisodeNumberRange visibleEpisodeNumberRange(String name) {
        String key = episodeNumberKey(name);
        if(key.length() == 0)
            return null;
        try {
            if(key.contains("-")) {
                String[] parts = key.split("-", 2);
                double major = Double.parseDouble(parts[0]);
                double part = Double.parseDouble(parts[1]);
                double value = major + Math.min(part, 9999.0d) / 10000.0d;
                return new EpisodeNumberRange(value, value);
            }
            String[] parts = key.split(",");
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for(String part : parts) {
                if(part == null || part.length() == 0)
                    continue;
                double value = Double.parseDouble(part);
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            return min == Double.MAX_VALUE ? null : new EpisodeNumberRange(min, max);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final double EPISODE_RANGE_EPSILON = 0.0001d;

    private static class EpisodeNumberRange {
        final double min;
        final double max;

        EpisodeNumberRange(double min, double max) {
            this.min = min;
            this.max = max;
        }
    }

    private static String normalizeEpisodeNumbers(String value) {
        if(value == null)
            return "";
        ArrayList<String> numbers = new ArrayList<>();
        Matcher matcher = EPISODE_BLOCK_NUMBER_PATTERN.matcher(value);
        while(matcher.find()) {
            String number = normalizeEpisodeNumberToken(matcher.group());
            if(number.length() > 0)
                numbers.add(number);
        }
        if(numbers.size() == 0)
            return "";
        if(numbers.size() == 2 && isHyphenPartEpisode(value, numbers.get(0), numbers.get(1)))
            return numbers.get(0) + "-" + numbers.get(1);
        StringBuilder key = new StringBuilder();
        for(String number : numbers) {
            if(key.length() > 0)
                key.append(',');
            key.append(number);
        }
        return key.toString();
    }

    private static String normalizeEpisodeNumberToken(String value) {
        if(value == null)
            return "";
        String number = value.trim();
        if(number.length() == 0)
            return "";
        int dot = number.indexOf('.');
        String integer = dot >= 0 ? number.substring(0, dot) : number;
        integer = integer.replaceFirst("^0+(?=\\d)", "");
        if(integer.length() == 0)
            integer = "0";
        if(dot < 0)
            return integer;
        String decimal = number.substring(dot + 1).replaceFirst("0+$", "");
        return decimal.length() == 0 ? integer : integer + "." + decimal;
    }

    private static boolean isHyphenPartEpisode(String value, String first, String second) {
        if(value == null || !value.contains("-") || first == null || second == null)
            return false;
        if(first.contains(".") || second.contains("."))
            return false;
        try {
            int firstNumber = Integer.parseInt(first);
            int secondNumber = Integer.parseInt(second);
            return firstNumber > 0 && secondNumber > 0 && secondNumber < firstNumber;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String cleanViewerEpisodeName(String name) {
        if(name == null)
            return "";
        return VIEWER_EPISODE_PREFIX_PATTERN.matcher(name.trim()).replaceFirst("");
    }

    private static int resolvedTitleId(Manga manga) {
        if(manga == null)
            return 0;
        if(manga.getTitleId() > 0)
            return manga.getTitleId();
        Title mangaTitle = manga.getTitle();
        return mangaTitle == null ? 0 : mangaTitle.getId();
    }

    private static List<Manga> safeEpisodeCopy(List<Manga> source) {
        if(source == null)
            return null;
        try {
            return new ArrayList<>(source);
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    public void setPrevEp(Manga m) {
        this.prevEp = m;
    }

    public void setNextEp(Manga m) {
        this.nextEp = m;
    }

    private final int id;
    String name;
    List<Manga> eps;
    List<String> imgs;
    String offlinePath;
    String thumb;
    transient Title title;
    String date;
    int seed;
    int mode;
    private transient ConcurrentHashMap<String, Boolean> firstNtkApiImageStreamStarts = new ConcurrentHashMap<>();
    transient Listener listener;
    transient Manga nextEp, prevEp;

    public interface Listener {
        void setMessage(String msg);
    }
}
