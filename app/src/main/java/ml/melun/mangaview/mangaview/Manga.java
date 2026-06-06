package ml.melun.mangaview.mangaview;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.jsoup.*;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import okhttp3.Response;

import static ml.melun.mangaview.Utils.documentFileFromUri;
import static ml.melun.mangaview.Utils.useScopedStorageHome;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.MTitle.baseModeStr;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_ERROR;
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import ml.melun.mangaview.MainApplication;

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
    private static final String TAG = "ViewerPerf";
    private static volatile String ntkViewerFetchModeOverride = "";
    private static final ThreadLocal<String> ntkThreadFetchModeOverride = new ThreadLocal<>();
    private static final String NTK_IMAGE_HOST_PATTERN =
            "(?:(?:[a-z0-9.-]+\\.)?toonflix\\.app|img\\.[a-z0-9.-]+|(?:www\\.)?pl\\d+\\.com)";
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
    private static final int NTK_GENERATED_INITIAL_VALIDATION_PAGE_COUNT = 2;
    private static final boolean NTK_GENERATED_TRIM_BEFORE_FIRST_FRAME = false;
    private static final long NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS = 2400L;
    private static final long NTK_GENERATED_MISS_ACK_GRACE_MS = 650L;
    private static final long NTK_GENERATED_MISS_PAGE_FAST_PATH_MS = 2600L;
    private static final long NTK_STRICT_ACK_FAILED_PAGE_FAST_PATH_MS = 5200L;
    private static final long NTK_WEBVIEW_VIEWER_IMAGES_CACHE_WAIT_MS = 1700L;
    private static final long NTK_GENERATED_EXTENSION_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Map<String, String> NTK_GENERATED_EXTENSION_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> NTK_GENERATED_EXTENSION_CACHE_TIME = new ConcurrentHashMap<>();

    public static void setNtkViewerFetchModeOverrideForTest(String mode) {
        ntkViewerFetchModeOverride = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    public static void clearNtkViewerFetchModeOverrideForTest() {
        ntkViewerFetchModeOverride = "";
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
            boolean nativeAckMode = isNtkNativeAckModeOverride();
            boolean apiFallbackMode = isNtkApiFallbackModeOverride();
            boolean strictApiFallbackMode = isNtkStrictApiFallbackModeOverride();
            final boolean kpApiDirectOnlyEpisode = isNtkKpWebtoonEpisodePath(path)
                    && !nativeAckMode
                    && !apiFallbackMode;
            if(isNtkGeneratedModeOverride()
                    && !nativeAckMode
                    && !apiFallbackMode
                    && shouldProbeKnownGeneratedBeforeApiFallback(path, getNtkImageCount())
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), !shouldOpenKnownNtkGeneratedPathWithoutValidation(path))) {
                logNtkViewerParse("generated-known-fast", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            final boolean apiFirstNtkEpisode = isNtkViewerEpisodePath(path);
            final boolean apiFirstWebtoonEpisode = isNtkWebtoonEpisodePath(path);
            boolean allowGeneratedImages = !apiFirstNtkEpisode && !nativeAckMode && !apiFallbackMode;
            final boolean skipGeneratedForSlugEpisode = shouldSkipNtkGeneratedForEpisodePath(path);
            final boolean apiFirstCanonicalWebtoonEpisode = shouldPreferNtkApiForCanonicalWebtoonPath(path);
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
                if(nativeAckRef[0] == null)
                    nativeAckRef[0] = startAsyncNtkNativeAck(client, viewerPath);
            };
            final boolean[] generatedPrimaryValidationMiss = new boolean[]{false};
            Runnable startFallbackFetchIfGeneratedBlocked = () -> {
                generatedPrimaryValidationMiss[0] = true;
                Log.d(TAG, "ntk_generated_miss_start_fallback path=" + viewerPath);
                startNativeAckIfNeeded.run();
                startDirectPageFetchIfNeeded.run();
            };
            if(apiFirstNtkEpisode || skipGeneratedForSlugEpisode || apiFirstCanonicalWebtoonEpisode) {
                if(!kpApiDirectOnlyEpisode)
                    startNativeAckIfNeeded.run();
                startDirectPageFetchIfNeeded.run();
                if(apiFirstNtkEpisode || (skipGeneratedForSlugEpisode && !apiFirstNtkEpisode))
                    if(!kpApiDirectOnlyEpisode)
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
            if(apiOptimisticGeneratedFastPath
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), !shouldOpenKnownNtkGeneratedPathWithoutValidation(path))) {
                logNtkViewerParse("api-optimistic-generated-fast", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(apiOptimisticGeneratedCandidate
                    && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                    ntkGeneratedImageCandidateCount(), !shouldOpenKnownNtkGeneratedPathWithoutValidation(path))) {
                logNtkViewerParse("api-optimistic-generated", null, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
            }
            if(nativeAckMode) {
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
                            ntkGeneratedImageCandidateCount(), !shouldOpenKnownNtkGeneratedPathWithoutValidation(path))) {
                        logNtkViewerParse("native-ack-optimistic-generated-fast", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    if(!skipGeneratedForSlugEpisode
                            && !apiFirstCanonicalWebtoonEpisode
                            && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                            ntkGeneratedImageCandidateCount(), !shouldOpenKnownNtkGeneratedPathWithoutValidation(path))) {
                        logNtkViewerParse("native-ack-optimistic-generated", null, path, 0, 0);
                        restoreBetterEpisodeList(previousEpisodes);
                        attachEpisodeSeriesMetadata();
                        return LOAD_OK;
                    }
                    startDirectPageFetchIfNeeded.run();
                    if(apiFirstCanonicalWebtoonEpisode) {
                        CustomHttpClient.PageResponse earlyPage =
                                awaitFastNtkApiPageFetch(directPageFetchRef[0], null,
                                        path, NTK_API_FALLBACK_ACK_FAST_PATH_WAIT_MS);
                        if(addFastNtkApiPageImageCandidates(client, earlyPage, path, seenImages, false)) {
                            logNtkViewerParse("api-first-canonical-webtoon", earlyPage, path, 0, 0);
                            restoreBetterEpisodeList(previousEpisodes);
                            attachEpisodeSeriesMetadata();
                            return LOAD_OK;
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
            }
            boolean validateGeneratedFirstImage = true;
            boolean generatedCandidatesChecked = false;
            if(allowGeneratedImages) {
                generatedCandidatesChecked = true;
                boolean optimisticGeneratedFastPath = shouldUseOptimisticNtkGeneratedFastPath(path);
                if((isNtkGeneratedImmediateModeOverride() || optimisticGeneratedFastPath)
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), !shouldOpenKnownNtkGeneratedPathWithoutValidation(path))) {
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
            if(pageFetchRef[0] == null
                    && !isUsableNtkApiPage(page)
                    && (apiFirstNtkEpisode || skipGeneratedForSlugEpisode || nativeAckMode || apiFallbackMode)) {
                startPageFetchIfNeeded.run();
                page = awaitBestNtkApiPageFetch(firstFrameDirectFetch, pageFetchRef[0], client, path);
            }
            if(addCachedNtkViewerImageApiCandidates(client, path, seenImages)) {
                logNtkViewerParse("api-cached-webview", page, path, 0, 0);
                restoreBetterEpisodeList(previousEpisodes);
                attachEpisodeSeriesMetadata();
                return LOAD_OK;
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
                } else {
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
                if(allowGeneratedImages && !generatedCandidatesChecked)
                    addNtkViewerMetaImageCandidates(client, page.body, path, seenImages);
                if(apiFirstNtkEpisode)
                    addNtkApiViewerImageCandidates(client, page.body, path, seenImages, false);
                if(!apiFirstNtkEpisode)
                    addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, true);
                compactNtkImageCandidates(page.body, seenImages);
                if(shouldFetchNtkApiViewerImagesForSparseParse(page.body, path, pageImages.size()))
                    addNtkApiViewerImageCandidates(client, page.body, path, seenImages);
                if(imgs.size() == 0) {
                    for(String src : fallbackBoardImages)
                        addImageIfValid(client, seenImages, src);
                }
                compactNtkImageCandidates(page.body, seenImages);
                if(imgs.size() == 0
                        && addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), true)) {
                    logNtkViewerParse("generated-empty-page", page, path, pageImages.size(), fallbackBoardImages.size());
                }
                if(imgs.size() == 0) {
                    logNtkViewerParse("empty", page, path, pageImages.size(), fallbackBoardImages.size());
                    return LOAD_CAPTCHA;
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
            if(isCloudflareChallenge(e) || isRecentNtkCloudflareChallenge(client)
                    || isNtkViewerChallengeFailure(client, e))
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
                Log.d(TAG, "ntk_page_fetch_start mode="
                        + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                        + ",path=" + path);
                CustomHttpClient.RequestWork<CustomHttpClient.PageResponse> work = () -> {
                    if(fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY)
                        return client.mgetNtkViewerPayloadPage(path, PAGE_CACHE_TTL_MS);
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
            } catch (Exception e) {
                fetch.error = e;
                Log.d(TAG, "ntk_page_fetch_error mode="
                        + (fetchMode == CustomHttpClient.FetchMode.DIRECT_ONLY ? "direct" : "allow")
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + ",path=" + path
                        + "," + e);
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
        long deadline = System.currentTimeMillis() + 14_000L;
        CustomHttpClient.PageResponse direct = null;
        CustomHttpClient.PageResponse fallback = null;
        while(System.currentTimeMillis() < deadline) {
            if(direct == null)
                direct = completedNtkPageFetch(directFetch, false);
            if(isUsableNtkFastPage(direct, path)) {
                cancelAsyncNtkPageFetch(fallbackFetch);
                logNtkViewerParse("api-direct-page", direct, path, 0, 0);
                return direct;
            }
            if(fallback == null)
                fallback = completedNtkPageFetch(fallbackFetch, true);
            if(isUsableNtkFastPage(fallback, path)) {
                cancelAsyncNtkPageFetch(directFetch);
                return fallback;
            }
            if((directFetch == null || directFetch.done.getCount() == 0)
                    && (fallbackFetch == null || fallbackFetch.done.getCount() == 0))
                break;
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        if(direct == null)
            direct = completedNtkPageFetch(directFetch, false);
        if(isUsableNtkFastPage(direct, path)) {
            cancelAsyncNtkPageFetch(fallbackFetch);
            logNtkViewerParse("api-direct-page", direct, path, 0, 0);
            return direct;
        }
        if(fallback == null)
            fallback = completedNtkPageFetch(fallbackFetch, true);
        if(isUsableNtkFastPage(fallback, path)) {
            cancelAsyncNtkPageFetch(directFetch);
            return fallback;
        }
        return fallback != null ? fallback
                : direct != null ? direct
                : client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
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
                cancelAsyncNtkPageFetch(fallbackFetch);
                return direct;
            }
            if(fallback == null)
                fallback = completedNtkPageFetch(fallbackFetch, false);
            if(isUsableNtkFastPage(fallback, path)) {
                cancelAsyncNtkPageFetch(directFetch);
                return fallback;
            }
            if((directFetch == null || directFetch.done.getCount() == 0)
                    && (fallbackFetch == null || fallbackFetch.done.getCount() == 0))
                break;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        if(direct == null)
            direct = completedNtkPageFetch(directFetch, false);
        if(isUsableNtkFastPage(direct, path)) {
            cancelAsyncNtkPageFetch(fallbackFetch);
            return direct;
        }
        if(fallback == null)
            fallback = completedNtkPageFetch(fallbackFetch, false);
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

    private AsyncNtkNativeAck startAsyncNtkNativeAck(CustomHttpClient client, String path) {
        AsyncNtkNativeAck fetch = new AsyncNtkNativeAck();
        CustomHttpClient.RequestGroup requestGroup = client == null ? null : client.currentRequestGroup();
        fetch.requestGroup = requestGroup;
        Thread thread = new Thread(() -> {
            try {
                if(requestGroup != null) {
                    fetch.completed = client.runWithRequestGroup(requestGroup,
                            () -> client.performNtkNativeAckBypass(client.getUrl(path), path));
                } else {
                    fetch.completed = client.performNtkNativeAckBypass(client.getUrl(path), path);
                }
            } catch (Exception e) {
                fetch.error = e;
            } finally {
                fetch.done.countDown();
            }
        }, "ntk-native-ack-prefetch");
        thread.setDaemon(true);
        fetch.thread = thread;
        thread.start();
        return fetch;
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
        String normalized = normalizeNtkEmbeddedImageText(body);
        addNtkTextImageMatches(client, normalized, NTK_TEXT_IMAGE_PATTERN, false, seenImages, fallbackBoardImages);
        addNtkTextImageMatches(client, body, NTK_ENCODED_TEXT_IMAGE_PATTERN, true, seenImages, fallbackBoardImages);
        compactNtkImageCandidates(normalized, seenImages);
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
        if(shouldPreferNtkApiForCanonicalWebtoonPath(path))
            return false;
        if(isNtkKpWebtoonEpisodePath(path))
            return false;
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
        int before = imgs == null ? 0 : imgs.size();
        for(int page = 1; page <= pageCount; page++) {
            String src = String.format(Locale.ROOT,
                    "https://i.toonflix.app/%s/%s/%s/p%03d.jpg",
                    segment, workId, episodeId, page);
            if(page == 1 && client != null && !isNtkGeneratedImageReachable(client, src))
                return false;
            addImageIfValid(client, seenImages, src);
        }
        return imgs != null && imgs.size() > before;
    }

    private boolean addNtkSlugViewerMetaImageCandidates(CustomHttpClient client, String normalized,
                                                        String path, Set<String> seenImages) {
        Matcher pathMatcher = Pattern.compile("^/webtoon/\\d+/([^/?#]+)").matcher(path == null ? "" : path);
        if(!pathMatcher.find())
            return false;
        String episodeId = pathMatcher.group(1);
        String cdnWorkId = ntkViewerThumbWorkId(normalized);
        if(cdnWorkId.length() == 0)
            return false;
        int pageCount = ntkViewerMetaPageCount(normalized);
        if(pageCount <= 0)
            return false;
        pageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        String extension = client == null
                ? "jpeg"
                : reachableNtkSlugWebtoonImageExtension(client, cdnWorkId, episodeId, 1);
        if(extension.length() == 0)
            return false;
        int validationPageCount = ntkGeneratedInitialValidationPageCount(pageCount);
        for(int page = 2; client != null && page <= validationPageCount; page++) {
            String cacheKey = ntkGeneratedExtensionCacheKey("wt", cdnWorkId, episodeId, page);
            String cachedExtension = cachedFreshNtkGeneratedImageExtension(cacheKey);
            if(cachedExtension != null) {
                if(cachedExtension.length() == 0)
                    return false;
                if(cachedExtension.equals(extension))
                    continue;
            }
            if(!isNtkGeneratedImageReachable(client,
                    ntkSlugWebtoonImageUrl(cdnWorkId, episodeId, page, extension))) {
                cacheNtkGeneratedImageExtension(cacheKey, "");
                return false;
            }
            cacheNtkGeneratedImageExtension(cacheKey, extension);
        }
        int before = imgs == null ? 0 : imgs.size();
        for(int page = 1; page <= pageCount; page++)
            addImageIfValid(client, seenImages, ntkSlugWebtoonImageUrl(cdnWorkId, episodeId, page, extension));
        return imgs != null && imgs.size() > before;
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
        if(isNtkKpWebtoonEpisodePath(path) && addNtkDirectTextImageCandidates(client, page.body, path, seenImages))
            return true;
        if((preferApiPayload || apiFirstNtkEpisode)
                && hasNtkViewerImageApiPayload(page.body)
                && addNtkApiViewerImageCandidates(client, page.body, path, seenImages,
                !apiFirstNtkEpisode && tryGeneratedMetaFirst))
            return true;
        if(!apiFirstNtkEpisode && addNtkViewerShellGeneratedImageCandidates(client, page.body, path, seenImages, false))
            return true;
        return addNtkApiViewerImageCandidates(client, page.body, path, seenImages,
                !apiFirstNtkEpisode && tryGeneratedMetaFirst);
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
        if(isNtkKpWebtoonEpisodePath(path))
            return false;
        if(shouldPreferNtkApiForCanonicalWebtoonPath(path))
            return false;
        Matcher pathMatcher = Pattern.compile("^/webtoon/(\\d+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        String workId = ntkViewerSourceWorkId(normalized);
        if(workId.length() == 0)
            workId = ntkViewerThumbWorkId(normalized);
        if(workId.length() == 0)
            return false;
        String episodeId = getNtkImageEpisodeId();
        if(episodeId.length() == 0)
            episodeId = pathMatcher.group(2);
        int pageCount = ntkViewerMetaPageCount(normalized);
        if(pageCount <= 0)
            pageCount = ntkGeneratedImageCandidateCount();
        String extension = reachableNtkGeneratedImageExtension(client, "webtoon", workId, episodeId, 1);
        if(extension.length() == 0)
            return false;
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        int validationPageCount = validateInitialPages ? ntkGeneratedInitialValidationPageCount(safePageCount) : 1;
        for(int page = 2; client != null && page <= validationPageCount; page++) {
            String cacheKey = ntkGeneratedExtensionCacheKey("webtoon", workId, episodeId, page);
            String cachedExtension = cachedFreshNtkGeneratedImageExtension(cacheKey);
            if(cachedExtension != null) {
                if(cachedExtension.length() == 0)
                    return false;
                if(cachedExtension.equals(extension))
                    continue;
            }
            if(!isNtkGeneratedImageReachable(client,
                    ntkGeneratedImageUrl("webtoon", workId, episodeId, page, extension))) {
                cacheNtkGeneratedImageExtension(cacheKey, "");
                return false;
            }
            cacheNtkGeneratedImageExtension(cacheKey, extension);
        }
        int before = imgs == null ? 0 : imgs.size();
        for(int page = 1; page <= safePageCount; page++)
            addImageIfValid(client, seenImages,
                    ntkGeneratedImageUrl("webtoon", workId, episodeId, page, extension));
        return imgs != null && imgs.size() > before;
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
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String token = ntkViewerImagesToken(normalized);
        if(token.length() == 0) {
            Log.d(TAG, "ntk_viewer_api_token_missing path=" + path
                    + ",snippet=" + ntkViewerPayloadSnippet(normalized));
            return false;
        }
        int before = imgs == null ? 0 : imgs.size();
        if(!isNtkViewerEpisodePath(path)
                && !shouldPreferNtkApiForCanonicalWebtoonPath(path)
                && (tryGeneratedMetaFirst || shouldSkipNtkGeneratedForEpisodePath(path))) {
            addNtkViewerMetaImageCandidates(client, normalized, path, seenImages);
            if(imgs != null && imgs.size() > before)
                return true;
        }
        if(addNtkBoardUploadTextImageCandidates(client, normalized, seenImages))
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
        String imageEpisodeId = ntkApiEpisodeIdForPath(tokenEpisodeId);
        if(imageEpisodeId.length() == 0)
            imageEpisodeId = ntkApiEpisodeIdForPath(getNtkImageEpisodeId());
        if(imageEpisodeId.length() == 0)
            imageEpisodeId = ntkApiEpisodeIdForPath(pathEpisodeId);
        String viewerBodyForImageFetch = preferNativeApiImageFetch ? null : normalized;
        String segment = pathMatcher.group(1);
        String workId = ntkApiEpisodeIdForPath(tokenWorkId);
        if(workId.length() == 0)
            workId = pathMatcher.group(2);
        List<String> urls = client.fetchNtkViewerImageUrls(segment, workId, imageEpisodeId,
                token, viewerBodyForImageFetch, path, path);
        if(urls.isEmpty() && titleId > 0 && !isNumericNtkId(workId)) {
            String canonicalAckPath = "/" + segment + "/" + titleId + "/" + imageEpisodeId;
            Log.d(TAG, "ntk_viewer_api_canonical_ack_retry path=" + path
                    + ",ackPath=" + canonicalAckPath);
            urls = client.fetchNtkViewerImageUrls(segment, workId, imageEpisodeId,
                    token, null, path, canonicalAckPath);
        }
        if(urls.size() >= 3 && imgs != null && imgs.size() > 0 && imgs.size() <= 2) {
            if(seenImages != null)
                seenImages.clear();
            imgs.clear();
        }
        urls = normalizeNtkApiViewerImageUrls(urls);
        for(String url : urls)
            addImageIfValid(client, seenImages, url);
        if(imgs == null || imgs.size() == before) {
            addNtkBoardUploadTextImageCandidates(client, normalized, seenImages);
        }
        compactNtkImageCandidates(normalized, seenImages);
        return imgs != null && imgs.size() > before;
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
                "\"page\"\\s*:\\s*\\d{1,4}[^\\{\\}\\[\\]]{0,600}?\"src\"\\s*:\\s*\"(https?://[^\"<>]+/board_uploads/[^\"<>]+\\.(?:jpg|jpeg|png|webp|gif)(?:\\?[^\"<>]*)?)\"",
                Pattern.CASE_INSENSITIVE);
        while(arrayMatcher.find()) {
            Matcher pageSrcMatcher = pageSrcPattern.matcher(arrayMatcher.group(1));
            while(pageSrcMatcher.find())
                ordered.add(normalizeNtkEmbeddedImageText(pageSrcMatcher.group(1)));
            if(!ordered.isEmpty())
                break;
        }
        if(ordered.size() < 3)
            return false;
        int before = imgs == null ? 0 : imgs.size();
        for(String url : ordered)
            addImageIfValid(client, seenImages, url);
        return imgs != null && imgs.size() > before;
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
        if(shouldSkipNtkGeneratedForEpisodePath(path)) {
            Log.d(TAG, "ntk_generated_skip_slug_api path=" + path
                    + ",imageEpisodeId=" + getNtkImageEpisodeId());
            return false;
        }
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String segment = pathMatcher.group(1);
        String workId = pathMatcher.group(2);
        String episodeId = pathMatcher.group(3);
        int before = imgs == null ? 0 : imgs.size();
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        String imageEpisodeId = ntkGeneratedEpisodeIdForPath(path);
        if(imageEpisodeId.length() == 0)
            imageEpisodeId = episodeId;
        String imageExtension = "jpg";
        if(validateFirstImage) {
            imageExtension = reachableNtkGeneratedImageExtension(client, segment, workId, imageEpisodeId, 1,
                    onPrimaryValidationMiss);
            if(imageExtension.length() == 0)
                return addNtkSlugWebtoonGeneratedImageCandidates(
                        client, path, seenImages, pageCount, true);
            int validationPageCount = shouldValidateNtkGeneratedInitialPages()
                    ? ntkGeneratedInitialValidationPageCount(safePageCount)
                    : 1;
            for(int page = 2; page <= validationPageCount; page++) {
                String cacheKey = ntkGeneratedExtensionCacheKey(segment, workId, imageEpisodeId, page);
                String cachedExtension = cachedFreshNtkGeneratedImageExtension(cacheKey);
                if(cachedExtension != null) {
                    if(cachedExtension.length() == 0)
                        return addNtkSlugWebtoonGeneratedImageCandidates(
                                client, path, seenImages, pageCount, true);
                    if(cachedExtension.equals(imageExtension))
                        continue;
                }
                if(!isNtkGeneratedImageReachable(client,
                        ntkGeneratedImageUrl(segment, workId, imageEpisodeId, page, imageExtension))) {
                    cacheNtkGeneratedImageExtension(cacheKey, "");
                    return addNtkSlugWebtoonGeneratedImageCandidates(
                            client, path, seenImages, pageCount, true);
                }
                cacheNtkGeneratedImageExtension(cacheKey, imageExtension);
            }
            if(NTK_GENERATED_TRIM_BEFORE_FIRST_FRAME || getNtkImageCount() <= 0)
                safePageCount = reachableNtkGeneratedPageCount(client, segment, workId, imageEpisodeId, imageExtension, safePageCount);
            else
                logNtkViewerParse("generated-page-count-" + generatedPageCountSource(), null, path, 0, 0);
        }
        for(int page = 1; page <= safePageCount; page++) {
            String src = ntkGeneratedImageUrl(segment, workId, imageEpisodeId, page, imageExtension);
            addImageIfValid(client, seenImages, src);
        }
        return imgs != null && imgs.size() > before;
    }

    private boolean addNtkSlugWebtoonGeneratedImageCandidates(CustomHttpClient client, String path,
                                                              Set<String> seenImages, int pageCount,
                                                              boolean validateFirstImage) {
        if(client == null || path == null || seenImages == null || pageCount <= 0)
            return false;
        Matcher pathMatcher = Pattern.compile("^/webtoon/(\\d+)/([^/?#]+)(?:[/?#].*)?$").matcher(path);
        if(!pathMatcher.find())
            return false;
        String episodeId = pathMatcher.group(2);
        if(isNtkKpEpisodeId(episodeId))
            return false;
        String slug = ntkCanonicalWebtoonSlugCandidate(title == null ? "" : title.getPath(),
                title == null ? "" : title.getName());
        if(slug.length() == 0)
            slug = ntkCanonicalWebtoonSlugCandidate("", name);
        if(slug.length() == 0)
            return false;
        String extension = "jpeg";
        String first = ntkSlugWebtoonImageUrl(slug, episodeId, 1, extension);
        if(validateFirstImage && !isNtkGeneratedImageReachable(client, first))
            return false;
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        if(validateFirstImage) {
            int validationPageCount = shouldValidateNtkGeneratedInitialPages()
                    ? ntkGeneratedInitialValidationPageCount(safePageCount)
                    : 1;
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

    private static String ntkGeneratedImageUrl(String segment, String workId, String episodeId, int page) {
        return ntkGeneratedImageUrl(segment, workId, episodeId, page, "jpg");
    }

    private static String ntkGeneratedImageUrl(String segment, String workId, String episodeId, int page, String extension) {
        String safeExtension = extension == null || extension.length() == 0 ? "jpg" : extension;
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
        String safeExtension = extension == null || extension.length() == 0 ? "jpeg" : extension;
        return String.format(Locale.ROOT,
                "https://i.toonflix.app/wt/episodes/%s/%s/p%03d.%s",
                slug, episodeId, page, safeExtension);
    }

    private static String ntkViewerThumbWorkId(String normalized) {
        if(normalized == null || normalized.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("/(?:blacktoon/)?thumbs/(\\d{1,12})\\.(?:png|jpg|jpeg|webp)",
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

    private static String ntkGeneratedEpisodeIdForPath(String path) {
        if(path == null || path.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("^/(?:manhwa|webtoon)/[^/?#]+/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return "";
        String pathEpisodeId = matcher.group(1);
        return pathEpisodeId == null ? "" : pathEpisodeId.trim();
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
        String[] extensions = onPrimaryValidationMiss == null
                ? new String[]{"jpg", "jpeg", "webp", "png"}
                : new String[]{"jpg", "jpeg"};
        boolean primaryMissReported = false;
        for(int i = 0; i < extensions.length; i++) {
            String extension = extensions[i];
            if(isNtkGeneratedImageReachable(client, ntkGeneratedImageUrl(segment, workId, episodeId, page, extension))) {
                cacheNtkGeneratedImageExtension(cacheKey, extension);
                return extension;
            }
            if(i == 0 && onPrimaryValidationMiss != null && !primaryMissReported) {
                primaryMissReported = true;
                onPrimaryValidationMiss.run();
            }
        }
        cacheNtkGeneratedImageExtension(cacheKey, "");
        return "";
    }

    private String reachableNtkSlugWebtoonImageExtension(CustomHttpClient client, String cdnWorkId,
                                                         String episodeId, int page) {
        String cacheKey = ntkGeneratedExtensionCacheKey("wt", cdnWorkId, episodeId, page);
        String cached = cachedFreshNtkGeneratedImageExtension(cacheKey);
        if(cached != null)
            return cached;
        for(String extension : new String[]{"jpeg", "jpg", "webp", "png"}) {
            if(isNtkGeneratedImageReachable(client, ntkSlugWebtoonImageUrl(cdnWorkId, episodeId, page, extension))) {
                cacheNtkGeneratedImageExtension(cacheKey, extension);
                return extension;
            }
        }
        cacheNtkGeneratedImageExtension(cacheKey, "");
        return "";
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
        return shouldProbeKnownGeneratedBeforeApiFallback(path, getNtkImageCount())
                || isNumericNtkGeneratedEpisodePath(path);
    }

    private boolean shouldUseOptimisticNtkGeneratedFastPath(String path) {
        return shouldProbeKnownGeneratedBeforeApiFallback(path, getNtkImageCount())
                && !hasCachedUnreachableNtkGeneratedImageExtension(path);
    }

    private boolean shouldOpenKnownNtkGeneratedPathWithoutValidation(String path) {
        return shouldProbeKnownGeneratedBeforeApiFallback(path, getNtkImageCount())
                && !hasCachedUnreachableNtkGeneratedImageExtension(path);
    }

    private static boolean shouldProbeKnownGeneratedBeforeApiFallback(String path, int imageCount) {
        if(imageCount <= 0)
            return false;
        return isNumericNtkGeneratedEpisodePath(path);
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
                        reachable[index] = isNtkGeneratedImageReachable(client,
                                ntkGeneratedImageUrl(segment, workId, episodeId, probes[index], extension), false);
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
            if(isNtkGeneratedImageReachable(client, ntkGeneratedImageUrl(segment, workId, episodeId, mid, extension), false)) {
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
            headers.put("range", "bytes=0-0");
            response = client.get(src, headers);
            int code = response == null ? 0 : response.code();
            String contentType = response == null || response.body() == null
                    ? "" : String.valueOf(response.body().contentType()).toLowerCase(Locale.ROOT);
            boolean ok = (code >= 200 && code < 300 || code == 206) && contentType.startsWith("image/");
            if(!ok && code == 403) {
                response.close();
                response = null;
                headers.remove("range");
                response = client.get(src, headers);
                code = response == null ? 0 : response.code();
                contentType = response == null || response.body() == null
                        ? "" : String.valueOf(response.body().contentType()).toLowerCase(Locale.ROOT);
                ok = (code >= 200 && code < 300 || code == 206) && contentType.startsWith("image/");
            }
            if(!ok && logMiss)
                logNtkViewerParse("generated-unreachable-" + code + "-" + generatedImageDebugSuffix(src),
                        null, getNtkEpisodePath(), 0, 0);
            return ok;
        } catch(Exception e) {
            if(logMiss)
                logNtkViewerParse("generated-unreachable-error", null, getNtkEpisodePath(), 0, 0);
            return false;
        } finally {
            if(response != null)
                response.close();
        }
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
        return baseMode == MTitle.base_webtoon
                && imageCount > 0
                && isNumericNtkGeneratedEpisodePath(path)
                && !shouldPreferNtkApiForCanonicalWebtoonPath(path);
    }

    private static boolean isNtkWebtoonEpisodePath(String path) {
        return path != null && path.matches("^/webtoon/[^/?#]+/[^/?#]+.*");
    }

    private static boolean isNtkViewerEpisodePath(String path) {
        return path != null && path.matches("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+.*");
    }

    private static boolean shouldPreferNtkApiForCanonicalWebtoonPath(String path) {
        return shouldPreferNtkApiForCanonicalWebtoonPath(path, 100_000);
    }

    static boolean shouldPreferNtkApiForCanonicalWebtoonPathForTest(String path) {
        return shouldPreferNtkApiForCanonicalWebtoonPath(path);
    }

    private static boolean shouldPreferNtkApiForCanonicalWebtoonPath(String path, int minimumCanonicalId) {
        if(path == null)
            return false;
        Matcher matcher = Pattern.compile("^/webtoon/(\\d{6,})/\\d+(?:[/?#].*)?$").matcher(path);
        if(!matcher.find())
            return false;
        try {
            return Integer.parseInt(matcher.group(1)) >= minimumCanonicalId;
        } catch(Exception ignored) {
            return true;
        }
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
        int maxPage = 0;
        Matcher matcher = Pattern.compile("\"page\"\\s*:\\s*(\\d{1,4})").matcher(body);
        while(matcher.find()) {
            try {
                maxPage = Math.max(maxPage, Integer.parseInt(matcher.group(1)));
            } catch (Exception ignored) {
            }
        }
        return maxPage;
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
        String normalized = normalizeNtkEmbeddedImageText(body);
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

    private static boolean isRecentNtkCloudflareChallenge(CustomHttpClient client) {
        return client != null && client.isNtk() && client.hasRecentCloudflareChallenge();
    }

    private static boolean isNtkViewerChallengeFailure(CustomHttpClient client, Exception e) {
        return client != null && client.isNtk() && isRecoverableNetworkFetchFailure(e);
    }

    private static void recordFetchException(Exception e) {
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
        String lower = src.trim().toLowerCase(Locale.ROOT);
        if(lower.contains("/board_uploads/")
                || lower.contains("/thumbs/")
                || lower.contains("banner")
                || lower.contains("advert")
                || lower.contains("sponsor")
                || lower.contains("popup")
                || lower.contains("/ad/")
                || lower.contains("/ads/"))
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
        String lower = src.trim().toLowerCase(Locale.ROOT);
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

    static String ntkSlugWebtoonImageUrlForTest(String slug, String episodeId, int page) {
        return ntkSlugWebtoonImageUrl(slug, episodeId, page, "jpeg");
    }

    static String ntkGeneratedEpisodeIdForTest(String path) {
        return ntkGeneratedEpisodeIdForPath(path);
    }

    static String ntkApiEpisodeIdForTest(String pathEpisodeId) {
        return ntkApiEpisodeIdForPath(pathEpisodeId);
    }

    static boolean shouldProbeKnownManhwaGeneratedBeforeApiFallbackForTest(String path, int imageCount) {
        return shouldProbeKnownGeneratedBeforeApiFallback(path, imageCount);
    }

    static boolean shouldProbeKnownGeneratedBeforeApiFallbackForTest(String path, int imageCount) {
        return shouldProbeKnownGeneratedBeforeApiFallback(path, imageCount);
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
        String dedupKey = ntkImageDedupKey(img);
        if(dedupKey.length() == 0)
            dedupKey = img.toLowerCase(Locale.ROOT);
        if(!seenImages.add(dedupKey))
            return false;
        imgs.add(img);
        return true;
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
    transient Listener listener;
    transient Manga nextEp, prevEp;

    public interface Listener {
        void setMessage(String msg);
    }
}
