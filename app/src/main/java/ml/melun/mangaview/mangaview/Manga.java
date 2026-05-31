package ml.melun.mangaview.mangaview;

import java.io.File;
import java.io.IOException;
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
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

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
    private static final String NTK_IMAGE_HOST_PATTERN =
            "(?:(?:[a-z0-9.-]+\\.)?toonflix\\.app|img\\.[a-z0-9.-]+|(?:www\\.)?pl\\d+\\.com)";
    private static final Pattern NTK_TEXT_IMAGE_PATTERN = Pattern.compile(
            "(?i)(?:https?:)?//" + NTK_IMAGE_HOST_PATTERN + "/[^\\s\"'<>\\\\]+?\\.(?:jpg|jpeg|png|webp|gif)(?:\\?[^\\s\"'<>\\\\]*)?");
    private static final Pattern NTK_ENCODED_TEXT_IMAGE_PATTERN = Pattern.compile(
            "(?i)https%3A%2F%2F" + NTK_IMAGE_HOST_PATTERN + "%2F[^\\s\"'<>\\\\]+?\\.(?:jpg|jpeg|png|webp|gif)(?:%3F[^\\s\"'<>\\\\]*)?");
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

    public static void setNtkViewerFetchModeOverrideForTest(String mode) {
        ntkViewerFetchModeOverride = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    public static void clearNtkViewerFetchModeOverrideForTest() {
        ntkViewerFetchModeOverride = "";
    }

    private static boolean isNtkNativeAckModeOverride() {
        return "native".equals(ntkViewerFetchModeOverride)
                || "native-ack".equals(ntkViewerFetchModeOverride)
                || "native_ack".equals(ntkViewerFetchModeOverride);
    }

    private static boolean isNtkGeneratedModeOverride() {
        return "generated".equals(ntkViewerFetchModeOverride)
                || "fast".equals(ntkViewerFetchModeOverride)
                || "generated-fast".equals(ntkViewerFetchModeOverride);
    }

    int baseMode = base_comic;
    int titleId = -1;
    private String ntkEpisodePath = "";
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
        try {
            int tid = titleId;
            if(tid <= 0 && title != null)
                tid = title.getId();
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
            boolean allowGeneratedImages = !nativeAckMode;
            if(nativeAckMode)
                client.performNtkNativeAckBypass(client.getUrl(path), path);
            boolean validateGeneratedFirstImage = true;
            boolean generatedCandidatesChecked = false;
            if(allowGeneratedImages) {
                generatedCandidatesChecked = true;
                if(addNtkGeneratedPathImageCandidates(client, path, seenImages,
                        ntkGeneratedImageCandidateCount(), validateGeneratedFirstImage)) {
                    logNtkViewerParse("generated-fast", null, path, 0, 0);
                    restoreBetterEpisodeList(previousEpisodes);
                    attachEpisodeSeriesMetadata();
                    return LOAD_OK;
                }
            }
            AsyncNtkPageFetch pageFetch = null;
            CustomHttpClient.PageResponse page = awaitAsyncNtkPageFetch(pageFetch, client, path);
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
                    if(addNtkApiViewerImageCandidates(client, page.body, path, seenImages)) {
                        logNtkViewerParse("api-missing", page, path, 0, 0);
                    } else {
                        logNtkViewerParse("api-missing-failed", page, path, 0, 0);
                        return LOAD_CAPTCHA;
                    }
                } else if(allowGeneratedImages && page.code >= 200 && page.code < 400
                        && !generatedCandidatesChecked
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
                if(parsedName.length() > 0)
                    name = parsedName;

                Elements pageImages = d.select("img");
                addNtkDocumentImageCandidates(client, d, seenImages, fallbackBoardImages);
                addNtkTextImageCandidates(client, page.body, seenImages, fallbackBoardImages);
                addNtkViewerMetaImageCandidates(client, page.body, path, seenImages);
                if(imgs.size() == 0)
                    addNtkApiViewerImageCandidates(client, page.body, path, seenImages);
                if(imgs.size() == 0) {
                    for(String src : fallbackBoardImages)
                        addImageIfValid(client, seenImages, src);
                }
                if(imgs.size() == 0)
                    logNtkViewerParse("empty", page, path, pageImages.size(), fallbackBoardImages.size());
                else
                    logNtkViewerParse("ok", page, path, pageImages.size(), fallbackBoardImages.size());
            }

            List<Manga> titleEpisodes = title == null ? null : safeEpisodeCopy(title.getEps());
            if(titleEpisodes != null && titleEpisodes.size() > 0) {
                eps = titleEpisodes;
                for(Manga ep : eps) {
                    ep.setMode(0);
                    ep.setTitle(title);
                    ep.setTitleId(tid);
                }
            }
        } catch (Exception e) {
            if(isCloudflareChallenge(e) || isRecentNtkCloudflareChallenge(client)
                    || isNtkViewerChallengeFailure(client, e))
                return LOAD_CAPTCHA;
            recordFetchException(e);
        }
        restoreBetterEpisodeList(previousEpisodes);
        attachEpisodeSeriesMetadata();
        return LOAD_OK;
    }

    private AsyncNtkPageFetch startAsyncNtkPageFetch(CustomHttpClient client, String path) {
        AsyncNtkPageFetch fetch = new AsyncNtkPageFetch();
        Thread thread = new Thread(() -> {
            try {
                fetch.page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
            } catch (Exception e) {
                fetch.error = e;
            } finally {
                fetch.done.countDown();
            }
        }, "ntk-page-prefetch");
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

    private static final class AsyncNtkPageFetch {
        final CountDownLatch done = new CountDownLatch(1);
        volatile CustomHttpClient.PageResponse page;
        volatile Exception error;
    }

    private void addNtkDocumentImageCandidates(CustomHttpClient client, Document d, Set<String> seenImages,
                                               Set<String> fallbackBoardImages) {
        if(d == null)
            return;
        Elements pageImages = d.select("img");
        boolean hasViewerContent = hasNtkViewerContent(d);
        for(Element img : pageImages) {
            for(String attr : new String[]{"data-original", "data-src", "data-lazy-src", "src"}) {
                String src = img.attr(attr);
                if(isNtkPageImage(img, src))
                    addImageIfValid(client, seenImages, src);
                else if(isNtkFallbackBoardPageImage(img, src))
                    fallbackBoardImages.add(src);
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
    }

    private void addNtkViewerMetaImageCandidates(CustomHttpClient client, String body, String path, Set<String> seenImages) {
        if(body == null || path == null || seenImages == null)
            return;
        String normalized = normalizeNtkViewerPayloadText(body);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if(!lower.contains("\"imagestoken\"") || !lower.contains("\"imagemetas\""))
            return;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return;
        String segment = pathMatcher.group(1);
        String workId = pathMatcher.group(2);
        String episodeId = pathMatcher.group(3);
        int pageCount = ntkViewerMetaPageCount(normalized);
        if(pageCount <= 0)
            return;
        pageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        for(int page = 1; page <= pageCount; page++) {
            String src = String.format(Locale.ROOT,
                    "https://i.toonflix.app/%s/%s/%s/p%03d.jpg",
                    segment, workId, episodeId, page);
            if(page == 1 && !isNtkGeneratedImageReachable(client, src))
                return;
            addImageIfValid(client, seenImages, src);
        }
    }

    private boolean addNtkApiViewerImageCandidates(CustomHttpClient client, String body, String path, Set<String> seenImages) {
        if(client == null || body == null || path == null || seenImages == null)
            return false;
        String normalized = normalizeNtkViewerPayloadText(body);
        if(!hasNtkViewerImageApiPayloadNormalized(normalized))
            return false;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String token = ntkViewerImagesToken(normalized);
        if(token.length() == 0)
            return false;
        int before = imgs == null ? 0 : imgs.size();
        if(!isNtkNativeAckModeOverride()) {
            addNtkViewerMetaImageCandidates(client, normalized, path, seenImages);
            if(imgs != null && imgs.size() > before)
                return true;
        }
        List<String> urls = client.fetchNtkViewerImageUrls(pathMatcher.group(1), pathMatcher.group(2), pathMatcher.group(3), token, normalized);
        for(String url : urls)
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
        return lower.contains("\"imagestoken\"") && lower.contains("\"imagemetas\"");
    }

    private boolean addNtkGeneratedPathImageCandidates(CustomHttpClient client, String path, Set<String> seenImages, int pageCount) {
        return addNtkGeneratedPathImageCandidates(client, path, seenImages, pageCount, false);
    }

    private boolean addNtkGeneratedPathImageCandidates(CustomHttpClient client, String path, Set<String> seenImages, int pageCount,
                                                      boolean validateFirstImage) {
        if(path == null || seenImages == null || pageCount <= 0)
            return false;
        Matcher pathMatcher = Pattern.compile("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)").matcher(path);
        if(!pathMatcher.find())
            return false;
        String segment = pathMatcher.group(1);
        String workId = pathMatcher.group(2);
        String episodeId = pathMatcher.group(3);
        int before = imgs == null ? 0 : imgs.size();
        int safePageCount = Math.min(pageCount, NTK_MAX_GENERATED_PAGE_COUNT);
        String imageEpisodeId = episodeId;
        String imageExtension = "jpg";
        if(validateFirstImage) {
            imageExtension = reachableNtkGeneratedImageExtension(client, segment, workId, imageEpisodeId, 1);
            if(imageExtension.length() == 0)
                return false;
            safePageCount = reachableNtkGeneratedPageCount(client, segment, workId, imageEpisodeId, imageExtension, safePageCount);
        }
        for(int page = 1; page <= safePageCount; page++) {
            String src = ntkGeneratedImageUrl(segment, workId, imageEpisodeId, page, imageExtension);
            addImageIfValid(client, seenImages, src);
        }
        return imgs != null && imgs.size() > before;
    }

    private static String ntkGeneratedImageUrl(String segment, String workId, String episodeId, int page) {
        return ntkGeneratedImageUrl(segment, workId, episodeId, page, "jpg");
    }

    private static String ntkGeneratedImageUrl(String segment, String workId, String episodeId, int page, String extension) {
        String safeExtension = extension == null || extension.length() == 0 ? "jpg" : extension;
        return String.format(Locale.ROOT,
                "https://i.toonflix.app/%s/%s/%s/p%03d.%s",
                segment, workId, episodeId, page, safeExtension);
    }

    private String reachableNtkGeneratedImageExtension(CustomHttpClient client, String segment, String workId,
                                                       String episodeId, int page) {
        String[] extensions = {"jpeg", "jpg", "png", "webp"};
        for(String extension : extensions) {
            if(isNtkGeneratedImageReachable(client, ntkGeneratedImageUrl(segment, workId, episodeId, page, extension)))
                return extension;
        }
        return "";
    }

    private int reachableNtkGeneratedPageCount(CustomHttpClient client, String segment, String workId,
                                               String episodeId, String extension, int pageCount) {
        if(pageCount <= 1)
            return pageCount;
        if(isNtkGeneratedImageReachable(client, ntkGeneratedImageUrl(segment, workId, episodeId, pageCount, extension)))
            return pageCount;
        int low = 1;
        int high = pageCount - 1;
        int best = 1;
        while(low <= high) {
            int mid = low + (high - low) / 2;
            if(isNtkGeneratedImageReachable(client, ntkGeneratedImageUrl(segment, workId, episodeId, mid, extension))) {
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

    private boolean isNtkGeneratedImageReachable(CustomHttpClient client, String src) {
        if(client == null || src == null || src.length() == 0)
            return false;
        Response response = null;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            headers.put("range", "bytes=0-0");
            response = client.get(src, headers);
            int code = response == null ? 0 : response.code();
            String contentType = response == null || response.body() == null
                    ? "" : String.valueOf(response.body().contentType()).toLowerCase(Locale.ROOT);
            boolean ok = (code >= 200 && code < 300 || code == 206) && contentType.startsWith("image/");
            if(!ok)
                logNtkViewerParse("generated-unreachable-" + code, null, getNtkEpisodePath(), 0, 0);
            return ok;
        } catch(Exception e) {
            logNtkViewerParse("generated-unreachable-error", null, getNtkEpisodePath(), 0, 0);
            return false;
        } finally {
            if(response != null)
                response.close();
        }
    }

    private int ntkGeneratedImageCandidateCount() {
        int count = getNtkImageCount();
        return count > 0 ? count : NTK_DEFAULT_GENERATED_PAGE_COUNT;
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
        return matcher.find() ? matcher.group(1) : "";
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
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("webpage not available")
                || lower.contains("net::err_")
                || lower.contains("err_connection_reset")
                || lower.contains("err_name_not_resolved")
                || lower.contains("err_timed_out")
                || lower.contains("just a moment")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("cf-challenge")
                || lower.contains("cf_chl")
                || lower.contains("cf-mitigated")
                || lower.contains("turnstile");
    }

    private static boolean looksLikeNtkMissingPage(String body) {
        if(body == null || body.length() == 0)
            return true;
        if(hasNtkPageImageInText(body))
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
                || context.contains("http://")
                || context.contains("https://")
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
        if(!seenImages.add(img))
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
        if(title != null)
            titleId = title.getId();
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
