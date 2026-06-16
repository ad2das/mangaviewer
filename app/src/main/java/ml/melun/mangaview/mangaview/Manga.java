package ml.melun.mangaview.mangaview;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLDecoder;
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
    private static final int NTK_EARLY_INITIAL_STREAM_PAGES = 1;
    private static final int NTK_EARLY_INITIAL_STREAM_START_COUNT = 3;
    private static final long NTK_EARLY_INITIAL_STREAM_STAGGER_MS = 180L;
    private static final long NTK_EARLY_INITIAL_STREAM_RETRY_MS = 800L;
    private static final int NTK_EARLY_SPECULATIVE_PAGE_PUBLISH_COUNT = 6;
    private static final int NTK_EARLY_INITIAL_PUBLISH_PAGES = 5;
    private static final int NTK_SPECULATIVE_INITIAL_STREAM_PAGES = 1;
    private static final long NTK_PAGE_FETCH_LAUNCH_HOLD_POLL_MS = 40L;
    private static final long NTK_PAGE_FETCH_LAUNCH_HOLD_MAX_MS = 4_200L;
    private static final String[] NTK_GENERATED_IMAGE_EXTENSIONS = new String[]{"jpg", "jpeg", "webp", "png"};
    private static final long NTK_EARLY_GENERATED_HEADER_PROBE_MS = 350L;
    private static final String TAG = "ViewerPerf";
    private static volatile String ntkViewerFetchModeOverride = "";
    private static final ThreadLocal<String> ntkThreadFetchModeOverride = new ThreadLocal<>();
    private static final String NTK_IMAGE_HOST_PATTERN =
            "(?:(?:[a-z0-9.-]+\\.)?toonflix\\.app|fvcdn\\d*\\.com|\\d{5,10}\\.com|img\\.[a-z0-9.-]+|(?:www\\.)?pl\\d+\\.com)";
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

    public static void setNtkViewerFetchModeOverrideForTest(String mode) {
        ntkViewerFetchModeOverride = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    public static void clearNtkViewerFetchModeOverrideForTest() {
        ntkViewerFetchModeOverride = "";
    }

    public static void clearNtkGeneratedExtensionCacheForTest() {
        NTK_GENERATED_EXTENSION_CACHE.clear();
        NTK_GENERATED_EXTENSION_CACHE_TIME.clear();
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
        if(id.length() > 0)
            return id;
        String path = getNtkEpisodePath();
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
        if(id.length() > 0)
            return id;
        Title currentTitle = getTitle();
        if(currentTitle != null) {
            id = ntkViewerThumbWorkId(currentTitle.getThumb());
            if(id.length() > 0)
                return id;
        }
        return "";
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

    public int getNtkImageCount() {
        if(ntkImageCount > 0)
            return ntkImageCount;
        String path = ntkEpisodePath == null ? "" : ntkEpisodePath.trim();
        int count = matchingNtkImageCount(eps, path);
        if(count > 0)
            return count;
        return title == null ? 0 : matchingNtkImageCount(title.getEps(), path);
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
        final AsyncNtkNativeAck[] webViewAckRef = new AsyncNtkNativeAck[1];
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
            boolean nativeAckMode = isNtkNativeAckModeOverride();
            boolean apiFallbackMode = isNtkApiFallbackModeOverride();
            boolean strictApiFallbackMode = isNtkStrictApiFallbackModeOverride();
            final boolean kpApiDirectOnlyEpisode = isNtkKpWebtoonEpisodePath(path)
                    && !nativeAckMode
                    && !apiFallbackMode;
            if(!nativeAckMode
                    && !apiFallbackMode
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
            Runnable startWebViewAckIfNeeded = () -> {
                if(!modernNtkGuardRoot)
                    return;
                if(webViewAckRef[0] == null)
                    webViewAckRef[0] = startAsyncNtkWebViewAck(client, viewerPath);
            };
            if(nativeAckMode
                    && modernNtkGuardRoot
                    && isNtkViewerEpisodePath(path)
                    && isNumericNtkGeneratedEpisodePath(path)
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
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
                if(!kpApiDirectOnlyEpisode) {
                    startNativeAckIfNeeded.run();
                    awaitAsyncNtkNativeAckStarted(nativeAckRef[0], 80L);
                }
                startDirectPageFetchIfNeeded.run();
                if(apiFirstNtkEpisode || (skipGeneratedForSlugEpisode && !apiFirstNtkEpisode))
                    if(!kpApiDirectOnlyEpisode && !nativeAckMode && !strictApiFallbackMode)
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
                if(isNtkManhwaEpisodePath(path) && isNumericNtkGeneratedEpisodePath(path))
                    startEarlyNtkGeneratedPublishProbeIfNeeded(client, path);
                if(isNtkViewerEpisodePath(path)
                        && isNumericNtkGeneratedEpisodePath(path)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), shouldValidateNtkGeneratedInitialCandidates(path))) {
                    logNtkViewerParse("generated-modern-native-ack-probed-early", null, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                startDirectPageFetchIfNeeded.run();
                if(isNtkWebtoonEpisodePath(path))
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
                if(isNtkManhwaEpisodePath(path)
                        && isNumericNtkGeneratedEpisodePath(path)
                        && isBlockedNtkDirectPage(client, directApiPage)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        Math.max(ntkGeneratedImageCandidateCount(), NTK_DEFAULT_GENERATED_PAGE_COUNT), true)) {
                    logNtkViewerParse("generated-modern-native-ack-after-direct-block", directApiPage, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
                if(!apiFirstCanonicalWebtoonEpisode
                        && isNumericNtkGeneratedEpisodePath(path)
                        && hasCachedReachableNtkGeneratedImageExtension(path)
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
                    logNtkViewerParse("api-fallback-blocked-fast", directApiPage, path, 0, 0);
                    return LOAD_CAPTCHA;
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
            if(!nativeAckCompleted && nativeAckMode && modernNtkGuardRoot
                    && webViewAckRef[0] != null
                    && (client.isCloudflareChallengeResponse(page.code, page.body)
                    || looksLikeNtkBlockedPage(page.body))) {
                nativeAckCompleted = awaitAsyncNtkNativeAck(webViewAckRef[0], 16_000L, false);
                if(nativeAckCompleted) {
                    page = fetchFreshNtkPage(client, path);
                    if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)
                            || addFastNtkApiPageImageCandidates(client, page, path, seenImages, false, true)
                            || addNtkApiViewerImageCandidates(client, page.body, path, seenImages, false)) {
                        logNtkViewerParse("api-after-webview-ack", page, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                }
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
                        && hasCachedReachableNtkGeneratedImageExtension(path)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), true)) {
                    logNtkViewerParse("generated-empty-page", page, path, pageImages.size(), fallbackBoardImages.size());
                }
                if(imgs.size() == 0
                        && !confirmedEmptyViewerPayload
                        && addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, true)) {
                    logNtkViewerParse("generated-shell-empty-page", page, path, pageImages.size(), fallbackBoardImages.size());
                }
                if(imgs.size() == 0
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
            if(isCloudflareChallenge(e) || isNtkViewerChallengeFailure(client, e))
                return LOAD_CAPTCHA;
            recordFetchException(e);
        } finally {
            cancelAsyncNtkPageFetch(pageFetchRef[0]);
            cancelAsyncNtkPageFetch(directPageFetchRef[0]);
            cancelAsyncNtkNativeAck(nativeAckRef[0]);
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
        fetch.requestGroup = requestGroup;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                if(ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path)) {
                    if(!waitForNtkPageFetchLaunchHold(path, fetchMode)) {
                        Log.d(TAG, "ntk_page_fetch_launch_hold_cancelled mode="
                                + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                                + ",ms=" + (System.currentTimeMillis() - startedAt)
                                + ",path=" + path);
                        return;
                    }
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
                            boolean hasDirectImageMarker = partialText.contains("toonflix.app/")
                                    || partialText.contains("fvcdn")
                                    || partialText.contains("%2Fwebtoon%2F")
                                    || partialText.contains("%2Fmanhwa%2F")
                                    || partialText.contains("%2Fwt%2Fepisodes%2F")
                                    || partialText.contains("%2Fblacktoon%2Fepisodes%2F");
                            if(hasDirectImageMarker && !directImageHandoffStarted.get()) {
                                List<String> directUrls = ntkDirectPageImageUrlsFromText(partialText, 4);
                                if(!directUrls.isEmpty() && directImageHandoffStarted.compareAndSet(false, true)) {
                                    Log.d(TAG, "ntk_rsc_direct_image_urls_early path=" + path
                                            + ",count=" + directUrls.size()
                                            + ",partialLen=" + partialText.length()
                                            + ",first=" + ntkLogImageName(directUrls.get(0)));
                                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, directUrls);
                                    startFirstNtkApiImageStream(client, path, directUrls);
                                }
                            }
                            if(!partialText.contains("imagesToken") && !partialText.contains("\\\"imagesToken\\\""))
                                return;
                            String token = ntkViewerImagesToken(partialText);
                            if(token.length() == 0 || !tokenPrefetchStarted.compareAndSet(false, true))
                                return;
                            Log.d(TAG, "ntk_viewer_api_prefetch_token_early path=" + path
                                    + ",partialLen=" + partialText.length()
                                    + "," + ntkViewerPayloadMarkerSummary(partialText, token));
                            startEarlyGeneratedNtkImageStreamFromPartial(client, path, partialText,
                                    generatedImageHandoffStarted);
                            if(shouldStartDirectOnlyNtkImageApiPrefetch(path))
                                startAsyncNtkViewerImageApiFetchFromToken(client, path, partialText);
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
                    startEarlyGeneratedNtkImageStreamFromPartial(client, path, fetch.page.body,
                            generatedImageHandoffStarted);
                }
                if(fetch.page != null && fetch.page.code >= 200 && fetch.page.code < 400
                        && shouldStartNtkImageApiPrefetchForPageFetch(path))
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

    private boolean waitForNtkPageFetchLaunchHold(String path, CustomHttpClient.FetchMode fetchMode) {
        long startedAt = System.currentTimeMillis();
        boolean waited = false;
        boolean interrupted = false;
        while(ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path)
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
        boolean held = ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path);
        Log.d(TAG, "ntk_page_fetch_launch_hold_wait mode="
                + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                + ",path=" + path
                + ",waited=" + waited
                + ",held=" + held
                + ",interrupted=" + interrupted
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        return !interrupted && !held;
    }

    private static boolean shouldStartDirectOnlyNtkImageApiPrefetch(String path) {
        if(path == null || path.length() == 0)
            return false;
        if(isNtkKpWebtoonEpisodePath(path))
            return false;
        return shouldPreferNtkApiForCanonicalWebtoonPath(path)
                || shouldSkipNtkGeneratedForEpisodePath(path);
    }

    private static boolean shouldStartNtkImageApiPrefetchForPageFetch(String path) {
        return shouldStartDirectOnlyNtkImageApiPrefetch(path);
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
        if(client == null || path == null || partialBody == null || ntkViewerImagesToken(partialBody).length() == 0)
            return;
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                prefetchNtkViewerImageApiTokenCandidate(client, partialBody, path);
                Log.d(TAG, "ntk_viewer_api_prefetch_token_done path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_api_prefetch_token_error path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-viewer-image-api-token-prefetch");
        thread.setDaemon(true);
        thread.start();
    }

    private void prefetchNtkViewerImageApiTokenCandidate(CustomHttpClient client, String body, String path) {
        String normalized = normalizeNtkViewerPayloadText(body);
        String token = ntkViewerImagesToken(normalized);
        if(token == null || token.length() == 0)
            return;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return;
        String segment = pathMatcher.group(1);
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathMatcher.group(3));
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String workId = ntkApiEpisodeIdForPath(tokenWorkId);
        if(workId.length() == 0)
            workId = pathMatcher.group(2);
        String episodeId = ntkPreferredViewerImagesApiEpisodeId(tokenEpisodeId, embeddedEpisodeId,
                pathMatcher.group(3));
        if(workId.length() == 0 || episodeId.length() == 0)
            return;
        List<String> urls = client.fetchNtkViewerImageUrls(segment, workId, episodeId,
                token, normalized, path, path, trustedUrls ->
                        startFirstNtkApiImageStream(client, path, trustedUrls));
        startFirstNtkApiImageStream(client, path, urls);
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
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        String imageEpisodeId = ntkViewerApiImageEpisodeId(tokenEpisodeId, getNtkImageEpisodeId(),
                pathEpisodeId, embeddedEpisodeId);
        String apiEpisodeId = ntkPreferredViewerImagesApiEpisodeId(tokenEpisodeId, imageEpisodeId,
                pathEpisodeId);
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String workId = ntkApiEpisodeIdForPath(tokenWorkId);
        if(workId.length() == 0)
            workId = pathMatcher.group(2);
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
                fetch.done.await(14, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
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
        long deadline = System.currentTimeMillis() + ntkBestPageFetchWaitMs(client, path, fallbackFetch);
        CustomHttpClient.PageResponse direct = null;
        CustomHttpClient.PageResponse fallback = null;
        boolean kpPath = isNtkKpWebtoonEpisodePath(path);
        while(System.currentTimeMillis() < deadline) {
            if(direct == null)
                direct = completedNtkPageFetch(directFetch, false);
            if(isNtkViewerUnavailableEpisode(direct == null ? null : direct.body)) {
                cancelAsyncNtkPageFetch(fallbackFetch);
                return direct;
            }
            if(isUsableNtkFastPage(direct, path)) {
                if(kpPath && !isUsableNtkKpDirectPage(direct, path)
                        && fallbackFetch != null && fallbackFetch.done.getCount() > 0) {
                    // KP RSC often exposes only the API token; the shared WebView page can carry
                    // direct image text a few hundred milliseconds later.
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
            if(kpPath && isUsableNtkKpDirectPage(fallback, path)) {
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
        if(kpPath && isUsableNtkKpDirectPage(fallback, path)) {
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
        return isUsableNtkApiPage(page) || isUsableNtkKpDirectPage(page, path);
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
                && !isUsableNtkKpDirectPage(page, path)
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
        if(!isNtkViewerEpisodePath(path) || isNtkKpWebtoonEpisodePath(path))
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

    private AsyncNtkNativeAck startAsyncNtkWebViewAck(CustomHttpClient client, String path) {
        AsyncNtkNativeAck fetch = new AsyncNtkNativeAck();
        Thread thread = new Thread(() -> {
            fetch.started.countDown();
            try {
                fetch.completed = client != null && client.performNtkWebViewAckPreflight(path);
            } catch (Exception e) {
                fetch.error = e;
            } finally {
                fetch.done.countDown();
            }
        }, "ntk-webview-ack-prefetch");
        thread.setDaemon(true);
        thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
        fetch.thread = thread;
        thread.start();
        return fetch;
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
        if(isNtkKpWebtoonEpisodePath(path)) {
            Matcher kpMatcher = Pattern.compile("^/webtoon/(\\d+)/[^/?#]+").matcher(path);
            String imageEpisodeId = getNtkImageEpisodeId();
            if(kpMatcher.find() && isNumericNtkId(imageEpisodeId)) {
                int pageCount = ntkViewerMetaPageCount(normalized);
                if(pageCount <= 0)
                    pageCount = getNtkImageCount();
                if(pageCount <= 0)
                    pageCount = ntkGeneratedImageCandidateCount();
                return addValidatedNtkGeneratedBaseImages(client, seenImages,
                        "webtoon", kpMatcher.group(1), imageEpisodeId, pageCount);
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
            if(!isUsableNtkKpDirectPage(page, path))
                return false;
        }
        boolean apiFirstNtkEpisode = isNtkViewerEpisodePath(path);
        boolean webtoonApiFirst = isNtkWebtoonEpisodePath(path);
        boolean canonicalWebtoonApiFirst = shouldPreferNtkApiForCanonicalWebtoonPath(path);
        if(isNtkKpWebtoonEpisodePath(path) && addNtkDirectTextImageCandidates(client, page.body, path, seenImages))
            return true;
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

    private static boolean isUsableNtkKpDirectPage(CustomHttpClient.PageResponse page, String path) {
        return isNtkKpWebtoonEpisodePath(path)
                && page != null
                && page.code >= 200
                && page.code < 400
                && page.body != null
                && page.body.length() > 0
                && hasNtkPageImageInText(page.body);
    }

    static boolean isUsableNtkKpDirectPageForTest(String path, int code, String body) {
        return isUsableNtkKpDirectPage(new CustomHttpClient.PageResponse(code, body, false), path);
    }

    private boolean addNtkViewerShellGeneratedImageCandidates(CustomHttpClient client, String body,
                                                             String path, Set<String> seenImages,
                                                             boolean validateInitialPages) {
        if(client == null || body == null || path == null || seenImages == null)
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        if(isNtkKpWebtoonEpisodePath(path))
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
        if(canonicalWebtoonPath && isNumericNtkId(episodeId)) {
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
        String token = ntkViewerImagesToken(normalized);
        if(token.length() == 0) {
            Log.d(TAG, "ntk_viewer_api_token_missing path=" + path
                    + ",snippet=" + ntkViewerPayloadSnippet(normalized));
            return false;
        }
        boolean canonicalWebtoonApiFirst = shouldPreferNtkApiForCanonicalWebtoonPath(path);
        int before = imgs == null ? 0 : imgs.size();
        if(!canonicalWebtoonApiFirst)
            addNtkTextImageCandidates(client, normalized, seenImages, new LinkedHashSet<>());
        if(imgs != null && imgs.size() > before)
            return true;
        if(!isNtkViewerEpisodePath(path)
                && !shouldPreferNtkApiForCanonicalWebtoonPath(path)
                && (tryGeneratedMetaFirst || shouldSkipNtkGeneratedForEpisodePath(path))) {
            addNtkViewerMetaImageCandidates(client, normalized, path, seenImages);
            if(imgs != null && imgs.size() > before)
                return true;
        }
        if(!canonicalWebtoonApiFirst && addNtkBoardUploadTextImageCandidates(client, normalized, seenImages))
            return true;
        if(addCachedNtkViewerImageApiCandidates(client, path, seenImages))
            return true;
        boolean preferNativeApiImageFetch = shouldPreAckBeforeNtkViewerImageApi(path);
        if(!preferNativeApiImageFetch && looksLikeNtkWebViewViewerPayload(normalized)
                && awaitCachedNtkViewerImageApiCandidates(client, path, seenImages, NTK_WEBVIEW_VIEWER_IMAGES_CACHE_WAIT_MS))
            return true;
        if(!isNtkNativeAckModeOverride()
                && !isNtkApiFallbackModeOverride()
                && !isNtkStrictApiFallbackModeOverride()
                && !shouldPreferNtkApiForCanonicalWebtoonPath(path)
                && shouldPreAckBeforeNtkViewerImageApi(path)
                && awaitCachedNtkViewerImageApiCandidates(client, path, seenImages, 650L))
            return true;
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        String pathEpisodeId = pathMatcher.group(3);
        String embeddedEpisodeId = ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId);
        String imageEpisodeId = ntkViewerApiImageEpisodeId(tokenEpisodeId, getNtkImageEpisodeId(),
                pathEpisodeId, embeddedEpisodeId);
        String apiEpisodeId = ntkPreferredViewerImagesApiEpisodeId(tokenEpisodeId, imageEpisodeId,
                pathEpisodeId);
        if(embeddedEpisodeId.length() > 0)
            Log.d(TAG, "ntk_viewer_api_embedded_episode_id path=" + path
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",tokenEpisodeId=" + tokenEpisodeId
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",apiEpisodeId=" + apiEpisodeId);
        String viewerBodyForImageFetch = normalized;
        String segment = pathMatcher.group(1);
        String workId = ntkApiEpisodeIdForPath(tokenWorkId);
        if(workId.length() == 0)
            workId = pathMatcher.group(2);
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
        if(urls.size() >= 3 && imgs != null && imgs.size() > 0 && imgs.size() <= 2) {
            if(seenImages != null)
                seenImages.clear();
            imgs.clear();
        }
        int fetchedUrlCount = urls.size();
        urls = normalizeNtkApiViewerImageUrls(urls);
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
                && isNumericNtkId(workId)
                && isNumericNtkId(imageEpisodeId)) {
            int pageCount = ntkViewerMetaPageCount(normalized);
            if(pageCount <= 0)
                pageCount = ntkGeneratedImageCandidateCount();
            Log.d(TAG, "ntk_viewer_api_generated_after_api_miss path=" + path
                    + ",workId=" + workId
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",pageCount=" + pageCount);
            if(awaitCachedNtkViewerImageApiCandidates(client, path, seenImages, 2200L))
                return true;
            if(addValidatedNtkGeneratedBaseImages(client, seenImages,
                    segment, workId, imageEpisodeId, pageCount))
                return true;
            Log.d(TAG, "ntk_viewer_api_generated_after_api_miss_failed path=" + path
                    + ",workId=" + workId
                    + ",imageEpisodeId=" + imageEpisodeId);
        }
        compactNtkImageCandidates(normalized, seenImages);
        return imgs != null && imgs.size() > before;
    }

    private void startFirstNtkApiImageStream(CustomHttpClient client, String path, List<String> urls) {
        startFirstNtkApiImageStream(client, path, urls, true);
    }

    private void startFirstNtkApiImageStream(CustomHttpClient client, String path, List<String> urls,
                                             boolean publishEarlyUrls) {
        if(client == null || urls == null || urls.isEmpty())
            return;
        if(!isNtkViewerEpisodePath(path) && !shouldPreferNtkApiForCanonicalWebtoonPath(path)
                && !shouldSkipNtkGeneratedForEpisodePath(path))
            return;
        int streamCount = Math.min(NTK_EARLY_INITIAL_STREAM_START_COUNT, urls.size());
        int[] streamOrder = new int[]{0, 1, 2, 3};
        for(int orderIndex = 0; orderIndex < streamCount; orderIndex++) {
            int index = streamOrder[orderIndex];
            String image = urls.get(index);
            if(image == null || image.length() == 0)
                continue;
            String key = (path == null ? "" : path) + "|" + image;
            if(firstNtkApiImageStreamStarts().putIfAbsent(key, Boolean.TRUE) != null)
                continue;
            startNtkInitialForegroundStream(client, path, image, index, key, true);
        }
        if(urls.size() > streamCount) {
            Log.d(TAG, "ntk_first_api_image_stream_defer_adjacent path=" + path
                    + ",started=" + streamCount
                    + ",deferred=" + (urls.size() - streamCount));
        }
        if(!publishEarlyUrls)
            return;
        try {
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
        } catch(Exception e) {
            Log.d(TAG, "ntk_early_api_image_urls_error path=" + path + "," + e);
        }
    }

    private void startNtkInitialForegroundStream(CustomHttpClient client, String path,
                                                 String image, int index, String startKey,
                                                 boolean allowConditionRetry) {
        long delayMs = Math.max(0, index) * NTK_EARLY_INITIAL_STREAM_STAGGER_MS;
        Runnable start = () -> {
            try {
                Context context = client.getContext();
                if(context != null) {
                    boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            context, this, image, null, false);
                    Log.d(TAG, "ntk_first_api_image_stream_start path=" + path
                            + ",started=" + started
                            + ",index=" + index
                            + ",delayMs=" + delayMs
                            + ",image=" + safeLogImage(image));
                    if(!started && index > 0 && startKey != null && startKey.length() > 0) {
                        firstNtkApiImageStreamStarts().remove(startKey);
                        if(allowConditionRetry)
                            retryNtkInitialForegroundStream(client, path, image, index, startKey);
                    }
                }
            } catch(Exception e) {
                Log.d(TAG, "ntk_first_api_image_stream_error path=" + path + "," + e);
                if(index > 0 && startKey != null && startKey.length() > 0)
                    firstNtkApiImageStreamStarts().remove(startKey);
            }
        };
        if(delayMs <= 0L) {
            start.run();
            return;
        }
        Thread thread = new Thread(start, "ntk-initial-image-stagger");
        thread.setDaemon(true);
        thread.start();
    }

    private void retryNtkInitialForegroundStream(CustomHttpClient client, String path,
                                                 String image, int index, String startKey) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(NTK_EARLY_INITIAL_STREAM_RETRY_MS);
                if(firstNtkApiImageStreamStarts().putIfAbsent(startKey, Boolean.TRUE) != null)
                    return;
                startNtkInitialForegroundStream(client, path, image, index, startKey, false);
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
        return Pattern.compile("(?i)^https?://(?:[^/]+\\.)?toonflix\\.app/(?:blacktoon/episodes|manhwa|webtoon|wt/episodes)/.*/p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$")
                .matcher(image)
                .find();
    }

    private void startEarlyGeneratedNtkImageStreamFromPartial(CustomHttpClient client, String path,
                                                              String partialText,
                                                              AtomicBoolean started) {
        if(client == null || path == null || partialText == null || started == null)
            return;
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

    public void startNtkVerifiedInitialImageProbe(CustomHttpClient client) {
        String path = getNtkEpisodePath();
        if(client == null || path == null || path.length() == 0)
            return;
        if(!isNumericNtkGeneratedEpisodePath(path))
            return;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/(\\d+)(?:[/?#].*)?$").matcher(path);
        if(!pathMatcher.find())
            return;
        String segment = pathMatcher.group(1);
        String pathWorkId = pathMatcher.group(2);
        String resolvedEpisodeId = ntkGeneratedEpisodeIdForPath(path);
        if(resolvedEpisodeId.length() == 0)
            resolvedEpisodeId = pathMatcher.group(3);
        final String episodeId = resolvedEpisodeId;
        String titleThumbWorkIdCandidate = getNtkImageWorkId();
        final String titleThumbWorkId = titleThumbWorkIdCandidate;
        boolean cachedReachable = hasCachedReachableNtkGeneratedImageExtension(path);
        boolean canonicalWebtoon = shouldPreferNtkApiForCanonicalWebtoonPath(path);
        boolean hasTrustedTitleThumbWork = canonicalWebtoon && isNumericNtkId(titleThumbWorkId);
        if(!cachedReachable && !hasTrustedTitleThumbWork)
            return;
        LinkedHashSet<String> workIds = new LinkedHashSet<>();
        if(canonicalWebtoon)
            addNtkCandidateIfNumeric(workIds, titleThumbWorkId);
        if(cachedReachable && !shouldSkipUnverifiedCanonicalWebtoonPathWorkSpeculation(path, segment, pathWorkId, episodeId))
            addNtkCandidateIfNumeric(workIds, pathWorkId);
        if(workIds.isEmpty())
            return;
        String streamKey = path + "|verified-path-probe";
        if(firstNtkApiImageStreamStarts().putIfAbsent(streamKey, Boolean.TRUE) != null)
            return;
        Thread thread = new Thread(() -> {
            try {
                for(String workId : workIds) {
                    String extension = reachableEarlyNtkGeneratedImageExtension(client, segment, workId,
                            episodeId, 1);
                    if(extension.length() == 0)
                        continue;
                    ArrayList<String> urls = ntkInitialGeneratedImageUrls(segment, workId,
                            episodeId, extension);
                    if(urls.isEmpty())
                        return;
                    Log.d(TAG, "ntk_generated_direct_image_url_path_probe path=" + path
                            + ",workId=" + workId
                            + ",pathWorkId=" + pathWorkId
                            + ",titleThumbWorkId=" + titleThumbWorkId
                            + ",cachedReachable=" + cachedReachable
                            + ",count=" + urls.size()
                            + ",extension=" + extension
                            + ",first=" + safeLogImage(urls.get(0)));
                    startFirstNtkApiImageStream(client, path, urls);
                    return;
                }
            } catch(Exception e) {
                Log.d(TAG, "ntk_generated_direct_image_url_path_probe_error path=" + path + "," + e);
            }
        }, "ntk-generated-path-initial");
        thread.setDaemon(true);
        thread.start();
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
        String normalized = normalizeNtkViewerPayloadText(partialText);
        int pageCount = ntkViewerMetaPageCount(normalized);
        if(pageCount <= 0)
            return urls;
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
        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        if(shouldPreferNtkApiForCanonicalWebtoonPath(path)) {
            addNtkCandidateIfNumeric(episodeIds, tokenEpisodeId);
            addNtkCandidateIfNumeric(episodeIds, getNtkImageEpisodeId());
            addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
            addNtkCandidateIfNumeric(episodeIds, embeddedEpisodeId);
        } else {
            addNtkCandidateIfNumeric(episodeIds, tokenEpisodeId);
            addNtkCandidateIfNumeric(episodeIds, embeddedEpisodeId);
            addNtkCandidateIfNumeric(episodeIds, getNtkImageEpisodeId());
            addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
        }
        if(workIds.isEmpty() || episodeIds.isEmpty())
            return urls;
        int safeLimit = Math.min(Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT), limit);
        boolean canonicalWebtoon = shouldPreferNtkApiForCanonicalWebtoonPath(path);
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
        if(client == null || segment == null || workId == null || episodeId == null)
            return "";
        String cacheKey = ntkGeneratedExtensionCacheKey(segment, workId, episodeId, probePage);
        String cached = cachedFreshNtkGeneratedImageExtension(cacheKey);
        if(cached != null)
            return cached;
        final FutureTask<String>[] taskHolder = new FutureTask[1];
        FutureTask<String> task = new FutureTask<String>(() ->
                reachableEarlyNtkGeneratedImageExtensionForPageUnshared(
                        client, segment, workId, episodeId, probePage, cacheKey)) {
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
                                                                           int probePage, String cacheKey) {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch remaining = new CountDownLatch(NTK_GENERATED_IMAGE_EXTENSIONS.length);
        AtomicBoolean winner = new AtomicBoolean(false);
        String[] hit = new String[]{""};
        long startedAt = System.currentTimeMillis();
        startSpeculativeNtkGeneratedInitialStreams(client, segment, workId, episodeId);
        String primaryExtension = "jpg";
        try {
            String primaryProbe = ntkGeneratedImageUrl(segment, workId, episodeId, probePage, primaryExtension);
            if(awaitCachedNtkGeneratedImageAvailable(client, primaryProbe, 80L)
                    || isNtkGeneratedImageReachableFast(client, primaryProbe, winner)) {
                hit[0] = primaryExtension;
                cacheNtkGeneratedImageExtension(cacheKey, primaryExtension);
                publishVerifiedEarlyNtkGeneratedImages(client, segment, workId, episodeId,
                        primaryExtension, probePage, 1);
                Log.d(TAG, "ntk_generated_direct_extension_probe path=" + segment + "/" + workId + "/" + episodeId
                        + ",extension=" + primaryExtension
                        + ",probePage=" + probePage
                        + ",primary=true"
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                return primaryExtension;
            }
        } catch(Exception ignored) {
        } finally {
            winner.set(false);
        }
        for(String extension : NTK_GENERATED_IMAGE_EXTENSIONS) {
            if(primaryExtension.equals(extension))
                continue;
            Thread thread = new Thread(() -> {
                try {
                    if(winner.get())
                        return;
                    String probe = ntkGeneratedImageUrl(segment, workId, episodeId, probePage, extension);
                    if(awaitCachedNtkGeneratedImageAvailable(client, probe, 80L)
                            && winner.compareAndSet(false, true)) {
                        hit[0] = extension;
                        cacheNtkGeneratedImageExtension(cacheKey, extension);
                        publishVerifiedEarlyNtkGeneratedImages(client, segment, workId, episodeId, extension, probePage, 1);
                        done.countDown();
                        return;
                    }
                    if(isNtkGeneratedImageReachableFast(client, probe, winner)
                            && winner.compareAndSet(false, true)) {
                        hit[0] = extension;
                        cacheNtkGeneratedImageExtension(cacheKey, extension);
                        publishVerifiedEarlyNtkGeneratedImages(client, segment, workId, episodeId, extension, probePage, 1);
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
            publishVerifiedEarlyNtkGeneratedImages(client, segment, workId, episodeId, result, probePage, 1);
        return result;
    }

    private void publishVerifiedEarlyNtkGeneratedImages(CustomHttpClient client, String segment,
                                                        String workId, String episodeId,
                                                        String extension, int startPage, int verifiedCount) {
        String safeExtension = normalizeNtkGeneratedImageExtension(extension);
        if(client == null || segment == null || workId == null || episodeId == null
                || safeExtension.length() == 0 || verifiedCount <= 0)
            return;
        String path = getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return;
        int knownCount = getNtkImageCount() > 0 ? getNtkImageCount() : ntkGeneratedImageCandidateCount();
        int publishCount = Math.max(verifiedCount, NTK_EARLY_INITIAL_STREAM_PAGES);
        int limit = Math.min(Math.min(publishCount, NTK_EARLY_INITIAL_STREAM_PAGES),
                Math.max(1, knownCount));
        int safeStartPage = Math.max(1, startPage);
        ArrayList<String> urls = ntkInitialGeneratedImageUrlsWithKnownExtensions(segment, workId,
                episodeId, safeExtension, safeStartPage, safeStartPage + limit - 1);
        if(urls.isEmpty())
            urls.add(ntkGeneratedImageUrl(segment, workId, episodeId, safeStartPage, safeExtension));
        Log.d(TAG, "ntk_generated_verified_urls_early path=" + path
                + ",count=" + urls.size()
                + ",extension=" + safeExtension
                + ",startPage=" + safeStartPage
                + ",first=" + safeLogImage(urls.get(0)));
        try {
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
        } catch(Exception e) {
            Log.d(TAG, "ntk_generated_verified_urls_remember_error path=" + path + "," + e);
        }
        Context context = client.getContext();
        if(context != null) {
            int streamCount = Math.min(1, urls.size());
            for(int i = 0; i < streamCount; i++) {
                String url = urls.get(i);
                try {
                    boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            context, this, url, null, false);
                    Log.d(TAG, "ntk_generated_verified_stream_start path=" + path
                            + ",started=" + started
                            + ",image=" + safeLogImage(url));
                } catch(Exception e) {
                    Log.d(TAG, "ntk_generated_verified_stream_error path=" + path
                            + ",image=" + safeLogImage(url)
                            + "," + e);
                }
            }
            if(urls.size() > streamCount) {
                Log.d(TAG, "ntk_generated_verified_stream_defer_adjacent path=" + path
                        + ",started=" + streamCount
                        + ",deferred=" + (urls.size() - streamCount));
            }
        }
        startFirstNtkApiImageStream(client, path, urls, false);
    }

    private void startAsyncNtkGeneratedInitialExtensionValidation(CustomHttpClient client,
                                                                  String segment, String workId,
                                                                  String episodeId, String defaultExtension,
                                                                  int pageCount) {
        if(client == null || segment == null || workId == null || episodeId == null
                || normalizeNtkGeneratedImageExtension(defaultExtension).length() == 0)
            return;
        int validationPageCount = Math.min(Math.min(pageCount, NTK_EARLY_INITIAL_PUBLISH_PAGES),
                NTK_EARLY_INITIAL_STREAM_PAGES);
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
        if(shouldSkipUnverifiedCanonicalWebtoonPathWorkSpeculation(path, segment, workId, episodeId))
            return;
        int speculativeLimit = Math.min(1, limit);
        if (getNtkImageCount() > 0 && isNumericNtkId(workId) && isNumericNtkId(episodeId)) {
            ArrayList<String> speculativeUrls = ntkInitialGeneratedImageUrlsWithKnownExtensions(
                    segment, workId, episodeId, "jpg", 1,
                    Math.min(1, getNtkImageCount()), false);
            if(!speculativeUrls.isEmpty()) {
                try {
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, speculativeUrls);
                    Log.d(TAG, "ntk_generated_speculative_urls_unverified_count_hint path=" + path
                            + ",count=" + speculativeUrls.size()
                            + ",workId=" + workId
                            + ",episodeId=" + episodeId
                            + ",extension=jpg");
                } catch(Exception e) {
                    Log.d(TAG, "ntk_generated_speculative_urls_unverified_count_hint_error path="
                            + path + "," + e);
                }
                startFirstNtkApiImageStream(client, path, speculativeUrls, false);
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
                if(firstNtkApiImageStreamStarts().putIfAbsent(key, Boolean.TRUE) != null)
                    continue;
                try {
                    boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            context, this, image, null, false);
                    Log.d(TAG, "ntk_generated_speculative_stream_start path=" + getNtkEpisodePath()
                            + ",started=" + started
                            + ",page=" + page
                            + ",extension=" + safeExtension
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
        if(pathWorkId.length() <= 5)
            return false;
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
        Matcher matcher = Pattern.compile("^/(manhwa)/(\\d+)/([^/?#]+)").matcher(path);
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
        Thread thread = new Thread(() -> {
            try {
                long startedAt = System.currentTimeMillis();
                for(String workId : workIds) {
                    for(String episodeId : episodeIds) {
                        String extension = reachableNtkGeneratedImageExtension(client, segment, workId,
                                episodeId, 1, null);
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
                            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
                            startFirstNtkApiImageStream(client, path, urls);
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

    private static String ntkViewerCanonicalWorkIdForImageApi(String normalized, String path,
                                                              int titleId, String apiWorkId) {
        if(path == null || imageApiWorkIdIsNumeric(apiWorkId))
            return "";
        String sourceWorkId = ntkViewerSourceWorkId(normalized);
        if(sourceWorkId.length() > 0)
            return sourceWorkId;
        return "";
    }

    static String ntkViewerCanonicalWorkIdForImageApiForTest(String normalized, String path,
                                                             int titleId, String apiWorkId) {
        return ntkViewerCanonicalWorkIdForImageApi(normalized, path, titleId, apiWorkId);
    }

    private static boolean imageApiWorkIdIsNumeric(String apiWorkId) {
        return apiWorkId != null && apiWorkId.matches("\\d+");
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
        if(isNtkKpWebtoonEpisodePath(path))
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
        return lower.contains("\"imagestoken\"") && lower.contains("\"imagemetas\"")
                && ntkViewerImagesToken(normalized).length() > 0;
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
        if(!isNtkWebtoonEpisodePath(path) || isNtkKpWebtoonEpisodePath(path))
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
        if(shouldSkipNtkGeneratedForEpisodePath(path) && !isNumericNtkId(getNtkImageEpisodeId())) {
            Log.d(TAG, "ntk_generated_skip_slug_api path=" + path
                    + ",imageEpisodeId=" + getNtkImageEpisodeId());
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

    private String trustedPrimaryNtkGeneratedExtension(String path, String segment, String pathWorkId,
                                                       String pathEpisodeId, String candidateWorkId,
                                                       String candidateEpisodeId) {
        if(!"webtoon".equalsIgnoreCase(segment))
            return "";
        if(!isNumericNtkGeneratedEpisodePath(path) || getNtkImageCount() <= 0)
            return "";
        if(!isNumericNtkId(pathWorkId) || !isNumericNtkId(pathEpisodeId))
            return "";
        if(!pathWorkId.equals(candidateWorkId) || !pathEpisodeId.equals(candidateEpisodeId))
            return "";
        if(isNtkProtectedWebtoonSourceEpisodePath(path))
            return "";
        return "jpg";
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
            return ReaderImageCache.INSTANCE.cachedFile(client.getContext(), this, src) != null;
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
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
        headers.put("Referer", client.getUrl(src.contains("/webtoon/")
                || src.contains("/blacktoon/episodes/")
                || src.contains("/wt/episodes/")
                ? MTitle.base_webtoon : MTitle.base_comic));
        headers.put("User-Agent", client.agent);
        String cookie = client.getCookieHeader();
        if(cookie != null && cookie.length() > 0)
            headers.put("Cookie", cookie);
        headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Sec-Fetch-Dest", "image");
        headers.put("Sec-Fetch-Mode", "no-cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("Accept-Encoding", "identity");
        return client.ntkImageHeaderReachability(src, headers, NTK_EARLY_GENERATED_HEADER_PROBE_MS);
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
        Matcher pathMatcher = Pattern.compile("^/webtoon/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$").matcher(path);
        if(!pathMatcher.find())
            return false;
        String pathWorkId = pathMatcher.group(1);
        String episodeId = pathMatcher.group(2);
        if(isNtkKpEpisodeId(episodeId))
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
        addNtkCandidateIfNumeric(numericWorkIds, pathWorkId);
        addNtkCandidateIfNumeric(numericWorkIds, String.valueOf(titleId));
        if(title != null)
            addNtkCandidateIfNumeric(numericWorkIds, String.valueOf(title.getId()));
        addNtkCandidateIfNumeric(numericWorkIds, thumbWorkId);

        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        addNtkCandidateIfNumeric(episodeIds, ntkViewerImagesTokenField(token, "e"));
        addNtkCandidateIfNumeric(episodeIds, getNtkImageEpisodeId());
        addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
        addNtkCandidateIfNumeric(episodeIds, ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId));
        LinkedHashSet<String> slugEpisodeIds = new LinkedHashSet<>(episodeIds);
        addNtkEpisodeCandidate(slugEpisodeIds, ntkViewerImagesTokenField(token, "e"));
        addNtkEpisodeCandidate(slugEpisodeIds, getNtkImageEpisodeId());
        addNtkEpisodeCandidate(slugEpisodeIds, pathEpisodeId);
        addNtkEpisodeCandidate(slugEpisodeIds, ntkViewerEmbeddedImageEpisodeId(normalized, pathEpisodeId));

        LinkedHashSet<String> slugs = new LinkedHashSet<>();
        addNtkSlugCandidate(slugs, pathWorkId);
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
        String extension = reachableNtkSlugWebtoonImageExtension(client, slug, episodeId, 1);
        if(extension.length() == 0)
            return false;
        int before = imgs == null ? 0 : imgs.size();
        for(int page = 1; page <= pageCount; page++)
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

    private static String ntkGeneratedImageUrl(String segment, String workId, String episodeId, int page, String extension) {
        String safeExtension = normalizeNtkGeneratedImageExtension(extension);
        if(safeExtension.length() == 0)
            throw new IllegalArgumentException("Missing NTK generated image extension");
        if("webtoon".equals(segment)) {
            return String.format(Locale.ROOT,
                    "https://i.toonflix.app/blacktoon/episodes/%s/%s/p%03d.%s",
                    workId, episodeId, page, safeExtension);
        }
        return String.format(Locale.ROOT,
                "https://i.toonflix.app/%s/%s/%s/p%03d.%s",
                segment, workId, episodeId, page, safeExtension);
    }

    private static String ntkSlugWebtoonImageUrl(String slug, String episodeId, int page, String extension) {
        String safeExtension = normalizeNtkGeneratedImageExtension(extension);
        if(safeExtension.length() == 0)
            throw new IllegalArgumentException("Missing NTK slug image extension");
        return String.format(Locale.ROOT,
                "https://i.toonflix.app/wt/episodes/%s/%s/p%03d.%s",
                slug, episodeId, page, safeExtension);
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
        matcher = Pattern.compile("\\\\\"sourceWorkId\\\\\"\\s*:\\s*\\\\\"(\\d{1,12})\\\\\"",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        return matcher.find() ? matcher.group(1) : "";
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
        String tokenImageEpisodeId = ntkApiEpisodeIdForPath(tokenEpisodeId);
        String knownImageEpisode = ntkApiEpisodeIdForPath(knownImageEpisodeId);
        String embeddedImageEpisode = ntkApiEpisodeIdForPath(embeddedEpisodeId);
        String pathImageEpisode = ntkApiEpisodeIdForPath(pathEpisodeId);
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
        String tokenImageEpisode = ntkApiEpisodeIdForPath(tokenEpisodeId);
        String resolvedImageEpisode = ntkApiEpisodeIdForPath(imageEpisodeId);
        if(isNumericNtkId(tokenImageEpisode))
            return tokenImageEpisode;
        if(!path.matches("\\d+") && tokenEpisodeId != null && tokenEpisodeId.length() > 0)
            return tokenEpisodeId;
        if(!path.matches("\\d+") && isNumericNtkId(resolvedImageEpisode))
            return resolvedImageEpisode;
        if(tokenEpisodeId != null && tokenEpisodeId.length() > 0)
            return tokenEpisodeId;
        if(resolvedImageEpisode.length() > 0)
            return resolvedImageEpisode;
        return path;
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
        Matcher matcher = Pattern.compile("^/(?:manhwa|webtoon)/[^/?#]+/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return "";
        String pathEpisodeId = matcher.group(1);
        return pathEpisodeId == null ? "" : pathEpisodeId.trim();
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
        addNtkCandidateIfNumeric(episodeIds, getNtkImageEpisodeId());
        addNtkCandidateIfNumeric(episodeIds, ntkGeneratedEpisodeIdForPath(path));
        addNtkCandidateIfNumeric(episodeIds, pathEpisodeId);
        return episodeIds;
    }

    private String reachableNtkGeneratedImageExtension(CustomHttpClient client, String segment, String workId,
                                                       String episodeId, int page) {
        return reachableNtkGeneratedImageExtension(client, segment, workId, episodeId, page, null);
    }

    private String reachableNtkGeneratedImageExtension(CustomHttpClient client, String segment, String workId,
                                                       String episodeId, int page, Runnable onPrimaryValidationMiss) {
        String cacheKey = ntkGeneratedExtensionCacheKey(segment, workId, episodeId, page);
        String cached = cachedFreshNtkGeneratedImageExtension(cacheKey);
        if(cached != null) {
            if(cached.length() == 0 && onPrimaryValidationMiss != null)
                onPrimaryValidationMiss.run();
            return cached;
        }
        String extension = reachableEarlyNtkGeneratedImageExtensionForPage(client, segment, workId, episodeId, page);
        if(extension.length() > 0)
            return extension;
        if(onPrimaryValidationMiss != null)
            onPrimaryValidationMiss.run();
        return "";
    }

    private String reachableNtkSlugWebtoonImageExtension(CustomHttpClient client, String cdnWorkId,
                                                         String episodeId, int page) {
        String cacheKey = ntkGeneratedExtensionCacheKey("wt", cdnWorkId, episodeId, page);
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
                    String probe = ntkSlugWebtoonImageUrl(cdnWorkId, episodeId, page, extension);
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
        Response response = null;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            headers.put("Referer", client.getUrl(src.contains("/webtoon/")
                    || src.contains("/blacktoon/episodes/")
                    || src.contains("/wt/episodes/")
                    ? MTitle.base_webtoon : MTitle.base_comic));
            headers.put("User-Agent", client.agent);
            String cookie = client.getCookieHeader();
            if(cookie != null && cookie.length() > 0)
                headers.put("Cookie", cookie);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Sec-Fetch-Dest", "image");
            headers.put("Sec-Fetch-Mode", "no-cors");
            headers.put("Sec-Fetch-Site", "same-origin");
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
            headers.put("Referer", client.getUrl(src.contains("/webtoon/")
                    || src.contains("/blacktoon/episodes/")
                    || src.contains("/wt/episodes/")
                    ? MTitle.base_webtoon : MTitle.base_comic));
            headers.put("User-Agent", client.agent);
            String cookie = client.getCookieHeader();
            if(cookie != null && cookie.length() > 0)
                headers.put("Cookie", cookie);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Sec-Fetch-Dest", "image");
            headers.put("Sec-Fetch-Mode", "no-cors");
            headers.put("Sec-Fetch-Site", "same-origin");
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
        if(stopSignal != null && stopSignal.get())
            return false;
        Response response = null;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            headers.put("Referer", client.getUrl(src.contains("/webtoon/")
                    || src.contains("/blacktoon/episodes/")
                    || src.contains("/wt/episodes/")
                    ? MTitle.base_webtoon : MTitle.base_comic));
            headers.put("User-Agent", client.agent);
            String cookie = client.getCookieHeader();
            if(cookie != null && cookie.length() > 0)
                headers.put("Cookie", cookie);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Sec-Fetch-Dest", "image");
            headers.put("Sec-Fetch-Mode", "no-cors");
            headers.put("Sec-Fetch-Site", "same-origin");
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
        return shouldPreferNtkApiForCanonicalWebtoonPath(path, 0);
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
        Matcher matcher = Pattern.compile("^/(?:manhwa|webtoon)/[^/?#]+/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return false;
        String episodePathId = matcher.group(1);
        return !episodePathId.matches("\\d+");
    }

    private static boolean isNtkKpWebtoonEpisodePath(String path) {
        if(path == null || path.length() == 0)
            return false;
        Matcher matcher = Pattern.compile("^/webtoon/[^/?#]+/([^/?#]+)").matcher(path);
        return matcher.find() && isNtkKpEpisodeId(matcher.group(1));
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
                "\"(page|pageNo|pageNumber|pageIndex|sort|order|index|no|count|total|imageCount)\"\\s*:\\s*(\\d{1,4})",
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
        int imagesToken = firstIndexOfAny(normalized, "\"imagesToken\"", "\\\"imagesToken\\\"");
        String tokenWorkId = ntkViewerImagesTokenField(token, "w");
        String tokenEpisodeId = ntkViewerImagesTokenField(token, "e");
        return "srcIndex=" + src
                + ",imageMetasIndex=" + imageMetas
                + ",imagesTokenIndex=" + imagesToken
                + ",tokenWorkId=" + tokenWorkId
                + ",tokenEpisodeId=" + tokenEpisodeId;
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
                || lower.contains("/wt/episodes/")
                || isNtkNumberedPageImage(lower)
                || isNtkCurrentCdnPageImage(lower);
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
            return host.matches(NTK_IMAGE_HOST_PATTERN);
        }
        return true;
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

    static boolean isNtkViewerImageMetasExplicitlyEmptyForTest(String body) {
        return isNtkViewerImageMetasExplicitlyEmpty(body);
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
        if(isNtkImageCandidateContext(client) && !isNtkPageImage(null, img)) {
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
