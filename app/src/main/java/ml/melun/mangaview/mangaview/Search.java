package ml.melun.mangaview.mangaview;

import android.util.Log;
import android.os.SystemClock;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

import ml.melun.mangaview.ClassificationDbStore;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerfTrace;

import static ml.melun.mangaview.MainApplication.appContext;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_auto;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static ml.melun.mangaview.mangaview.MTitle.baseModeStr;

public class Search {
    private static final String TAG = "Search";
    private static final long PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;
    private static final int CLASSIFICATION_DB_PAGE_SIZE = 120;
    private static final int NTK_CATEGORY_PAGE_SIZE = 30;
    private static final int NTK_KEYWORD_PAGE_SIZE = 120;
    private static final long WFWF_RESULT_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int WFWF_RESULT_CACHE_MAX_ENTRIES = 80;
    private static final long NTK_RESULT_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int NTK_RESULT_CACHE_MAX_ENTRIES = 80;
    private static final int NTK_KEYWORD_API_CACHE_MAX_ENTRIES = 80;
    private static final int NTK_KEYWORD_SCAN_PARALLELISM = 8;
    private static final Pattern NTK_EXACT_WORK_ID_QUERY = Pattern.compile(
            "^\\s*#([\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,127})\\s*$");
    private static final String NTK_ALIAS_WEBTOON_URL = "https://newtoki1.org";
    private static final String NTK_ALIAS_COMIC_URL = NTK_ALIAS_WEBTOON_URL + "/manhwa";
    private static final Pattern WFWF_FAST_LINK_PATTERN = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a>");
    private static final Pattern WFWF_FAST_HEADING_PATTERN = Pattern.compile("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]>");
    private static final Pattern WFWF_FAST_IMG_PATTERN = Pattern.compile("(?is)<img\\b([^>]*)>");
    private static final Pattern WFWF_FAST_STYLE_PATTERN = Pattern.compile("(?is)style\\s*=\\s*(['\"])(.*?)\\1");
    private static final Pattern WFWF_FAST_TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Map<String, CachedPageTitles> WFWF_RESULT_CACHE = new HashMap<>();
    private static final Map<String, CachedPageTitles> NTK_RESULT_CACHE = new HashMap<>();
    private static final Map<String, CachedNtkApiPathResult> NTK_KEYWORD_API_RESULT_CACHE = new HashMap<>();

    int baseMode;
    private final String query;
    Boolean last = false;
    int mode;
    int page = 1;
    int timeoutRetries = 0;
    int classificationDbOffset = 0;
    int classificationDbTotalCount = 0;
    int ntkOngoingTotalCount = 0;
    int ntkCompletedTotalCount = 0;
    boolean classificationSourceFetched = false;
    String ntkCategoryNextPath = null;
    String ntkSearchNextPath = null;
    private ArrayList<Title> result;
    private final Set<String> seenTitleKeys = new HashSet<>();
    private transient PartialResultListener partialResultListener;
    private transient String activeFetchSourceSite = "";

    public interface PartialResultListener {
        void onPartialResults(ArrayList<Title> titles);
    }

    public Search(String q, int mode, int baseMode) {
        query = q;
        this.mode = mode;
        this.baseMode = baseMode;
    }

    public int getBaseMode() {
        return baseMode;
    }

    public String getQuery() {
        return query;
    }

    public void setPartialResultListener(PartialResultListener partialResultListener) {
        this.partialResultListener = partialResultListener;
    }

    public Boolean isLast() {
        return last;
    }

    public int getVirtualResultCount() {
        return Math.max(classificationDbTotalCount, classificationDbOffset);
    }

    public int getNtkStatusTotalCount(String status) {
        if("연재".equals(status))
            return ntkOngoingTotalCount;
        if("완결".equals(status))
            return ntkCompletedTotalCount;
        int total = ntkOngoingTotalCount + ntkCompletedTotalCount;
        return total > 0 ? total : getVirtualResultCount();
    }

    public int fetch(CustomHttpClient client) {
        // A Search can outlive a site-toggle because it is owned by a Fragment and its network
        // work runs off the main thread.  Bind every result to the source that owned the request
        // at admission time; consulting the mutable global preset later can otherwise turn an NTK
        // title into a WFWF title (or vice versa) and launch the reader with the wrong trust policy.
        activeFetchSourceSite = sourceSiteForClient(client);
        try {
            return fetchBoundToSource(client);
        } finally {
            bindTitlesToSourceSite(result, activeFetchSourceSite);
        }
    }

    private int fetchBoundToSource(CustomHttpClient client) {
        result = new ArrayList<>();
        if(!last) {
            if(baseMode == base_auto)
                return fetchAll(client);
            if(baseMode == base_webtoon)
                return fetchWebtoon(client);
            if(baseMode == base_comic)
                return fetchComic(client);
            for(int attempt = 0; attempt <= MAX_TIMEOUT_RETRIES; attempt++) {
                try {
                String searchUrl = "";
                switch(mode){
                    case 0:
                        searchUrl = "?bo_table="+baseModeStr(baseMode)+"&stx=";
                        break;
                    case 1:
                        searchUrl = "?bo_table="+baseModeStr(baseMode)+"&artist=";
                        break;
                    case 2:
                        searchUrl = "?bo_table="+baseModeStr(baseMode)+"&tag=";
                        break;
                    case 3:
                        searchUrl = "?bo_table="+baseModeStr(baseMode)+"&jaum=";
                        break;
                    case 4:
                        searchUrl = "?bo_table="+baseModeStr(baseMode)+"&publish=";
                        break;
                }

                Response response = client.mget('/'+baseModeStr(baseMode)+"/p" + page++ + searchUrl + URLEncoder.encode(query,"UTF-8"), true, null);
                int code = response == null ? 500 : response.code();
                String body = CustomHttpClient.readBody(response);
                if(body.contains("Connect Error: Connection timed out")){
                    page--;
                    timeoutRetries = attempt + 1;
                    continue;
                }
                Document d = Jsoup.parse(body);
                d.outputSettings().charset(StandardCharsets.UTF_8);

                Elements titles = d.select("div.list-item");

                throwIfCloudflareChallenge(client, code, body, '/' + baseModeStr(baseMode) + "/p" + (page - 1) + searchUrl);
                if(code>=400){
                    return 1;
                } else if (titles.size() < 1)
                    last = true;

                String title;
                String thumb;
                String author;
                String release;
                int id;

                for(Element e : titles) {
                    try {
                        Element infos = e.selectFirst("div.img-item");
                        if(infos == null)
                            continue;
                        Element infos2 = infos.selectFirst("div.in-lable");
                        Element label = infos2 != null ? infos2.selectFirst("span") : null;
                        Element img = infos.selectFirst("img");
                        if(infos2 == null || label == null || img == null)
                            continue;

                        id = Integer.parseInt(infos2.attr("rel"));
                        title = label.ownText();
                        thumb = img.attr("src");

                        Element ae = e.selectFirst("div.list-artist");
                        Element authorLink = ae != null ? ae.selectFirst("a") : null;
                        if (authorLink != null) author = authorLink.ownText();
                        else author = "";

                        Element re = e.selectFirst("div.list-publish");
                        Element releaseLink = re != null ? re.selectFirst("a") : null;
                        if (releaseLink != null) release = releaseLink.ownText();
                        else release = "";

                        result.add(new Title(title, thumb, author, null, release, id, baseMode));
                    }catch (Exception e2){
                        ml.melun.mangaview.report.CrashReporter.record(e2);
                    }
                }
                if (result.size() < 35)
                    last = true;

                if(result.size()==0)
                    page--;
                timeoutRetries = 0;
                return 0;

                } catch (Exception e) {
                    page--;
                    timeoutRetries = 0;
                    if(shouldReportSearchFailure(e))
                        ml.melun.mangaview.report.CrashReporter.record(e);
                    return 1;
                }
            }
            timeoutRetries = 0;
            return 1;
        }
        return 0;
    }

    private int fetchAll(CustomHttpClient client) {
        if(mode == 0)
            return fetchAllKeyword(client);
        int status = 0;
        ArrayList<Title> combined = new ArrayList<>();
        try {
            Search webtoonSearch = new Search(query, mode, base_webtoon);
            Search comicSearch = new Search(query, mode, base_comic);
            CustomHttpClient.RequestGroup requestGroup = client.currentRequestGroup();

            int webtoonStatus = requestGroup == null
                    ? webtoonSearch.fetch(client)
                    : client.runWithRequestGroup(requestGroup, () -> webtoonSearch.fetch(client));
            SearchResult webtoonResult = new SearchResult(webtoonStatus, webtoonSearch.getResult());
            if(webtoonResult.status == 0)
                appendUnique(combined, webtoonResult.titles);
            else
                status = webtoonResult.status;

            int comicStatus = requestGroup == null
                    ? comicSearch.fetch(client)
                    : client.runWithRequestGroup(requestGroup, () -> comicSearch.fetch(client));
            SearchResult comicResult = new SearchResult(comicStatus, comicSearch.getResult());
            if(comicResult.status == 0)
                appendUnique(combined, comicResult.titles);
            else if(status == 0)
                status = comicResult.status;
        } catch (Exception e) {
            if(shouldReportSearchFailure(e))
                ml.melun.mangaview.report.CrashReporter.record(e);
            status = 1;
        }
        result.addAll(combined);
        last = true;
        return result.size() > 0 ? 0 : status;
    }

    private int fetchAllKeyword(CustomHttpClient client) {
        long startedAt = PerfTrace.start("keyword_search_total_ms");
        int status = 0;
        ArrayList<Title> combined = new ArrayList<>();
        try {
            if(client != null && client.isNtk()) {
                int currentPage = page;
                PageTitles hybridResults = fetchNtkHybridKeywordResults(client, base_auto, 240, currentPage);
                appendUnique(combined, hybridResults.titles);
                page++;
                last = hybridResults.hasMoreKnown ? !hybridResults.hasMore : true;
            } else {
                appendUnique(combined, fetchWfwfCombinedKeywordSearchResults(client).titles);
                last = true;
            }
        } catch (Exception e) {
            if(shouldReportSearchFailure(e))
                ml.melun.mangaview.report.CrashReporter.record(e);
            status = 1;
        }
        result.addAll(combined);
        int finalStatus = result.size() > 0 ? 0 : status;
        traceSearchMetric("keyword_search_total_ms", startedAt,
                ",site=" + (client != null && client.isNtk() ? "ntk" : "wfwf")
                        + ",mode=" + mode
                        + ",base=" + baseMode
                        + ",count=" + result.size()
                        + ",status=" + finalStatus);
        return finalStatus;
    }

    private static boolean shouldReportSearchFailure(Exception e) {
        if(e == null)
            return false;
        if(isCaptchaRequiredException(e))
            return false;
        String message = e.getMessage();
        if(message != null && message.startsWith("Request failed:"))
            return false;
        return !(e instanceof java.io.IOException);
    }

    static boolean shouldReportSearchFailureForTest(Exception e) {
        return shouldReportSearchFailure(e);
    }

    private static class SearchResult {
        final int status;
        final ArrayList<Title> titles;

        SearchResult(int status, ArrayList<Title> titles) {
            this.status = status;
            this.titles = titles;
        }
    }

    private static void appendUnique(ArrayList<Title> target, ArrayList<Title> source) {
        if(target == null || source == null)
            return;
        for(Title title : source) {
            if(title == null)
                continue;
            boolean exists = false;
            for(Title existing : target) {
                if(isSameTitleIdentity(existing, title)) {
                    mergeTitleMetadata(existing, title);
                    exists = true;
                    break;
                }
            }
            if(!exists)
                target.add(title);
        }
    }

    private static void mergeTitleMetadata(Title target, Title source) {
        if(target == null || source == null)
            return;
        if((target.getThumb() == null || target.getThumb().trim().length() == 0) && source.getThumb() != null && source.getThumb().trim().length() > 0)
            target.setThumb(source.getThumb());
        if((target.getRelease() == null || target.getRelease().trim().length() == 0) && source.getRelease() != null && source.getRelease().trim().length() > 0)
            target.setRelease(source.getRelease());
        if((target.getPath() == null || target.getPath().trim().length() == 0) && source.getPath() != null && source.getPath().trim().length() > 0)
            target.setPath(source.getPath());
        if((target.getSourceSite() == null || target.getSourceSite().trim().length() == 0) && source.getSourceSite() != null && source.getSourceSite().trim().length() > 0)
            target.setSourceSite(source.getSourceSite());
        ArrayList<String> tags = new ArrayList<>(target.getTags());
        for(String tag : source.getTags()) {
            String normalized = normalizeNtkGenreTag(tag);
            if(normalized.length() > 0 && !tags.contains(normalized))
                tags.add(normalized);
        }
        target.setTags(tags);
    }

    private static boolean isSameTitleIdentity(Title first, Title second) {
        if(first == null || second == null || first.getBaseMode() != second.getBaseMode())
            return false;
        String firstPath = normalizedTitleIdentityPath(first);
        String secondPath = normalizedTitleIdentityPath(second);
        if(firstPath.length() > 0 && secondPath.length() > 0)
            return firstPath.equals(secondPath);
        return first.getId() > 0 && first.getId() == second.getId();
    }

    private static String titleIdentityKey(Title title) {
        if(title == null)
            return "";
        String path = normalizedTitleIdentityPath(title);
        if(path.length() > 0)
            return title.getBaseMode() + ":" + path;
        return title.getBaseMode() + ":" + title.getId();
    }

    private static String normalizedTitleIdentityPath(Title title) {
        if(title == null)
            return "";
        String path = title.getPath();
        if((path == null || path.trim().length() == 0) && "ntk".equals(title.getSourceSite()))
            path = title.getUrl();
        if(path == null)
            return "";
        String value = path.trim();
        int scheme = value.indexOf("://");
        if(scheme >= 0) {
            int slash = value.indexOf('/', scheme + 3);
            value = slash >= 0 ? value.substring(slash) : "";
        }
        int query = value.indexOf('?');
        if(query >= 0)
            value = value.substring(0, query);
        int hash = value.indexOf('#');
        if(hash >= 0)
            value = value.substring(0, hash);
        while(value.endsWith("/") && value.length() > 1)
            value = value.substring(0, value.length() - 1);
        return value;
    }

    private PageTitles fetchWfwfCombinedKeywordSearchResults(CustomHttpClient client) throws Exception {
        String path = wfwfKeywordSearchPath(query);
        PageTitles pageTitles = cachedWfwfPageTitles(client, "keyword", path,
                () -> fetchWfwfCombinedKeywordSearchResultsUncached(client, path));
        return pageTitles;
    }

    private PageTitles fetchWfwfCombinedKeywordSearchResultsUncached(CustomHttpClient client, String path) throws Exception {
        long fetchStartedAt = PerfTrace.start("wfwf_search_page_fetch_ms");
        CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
        traceSearchMetric("wfwf_search_page_fetch_ms", fetchStartedAt,
                ",fromCache=" + page.fromCache
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
        throwIfCloudflareChallenge(client, page.code, page.body, path);
        if(page.code >= 400)
            throw new Exception("WFWF search failed: " + page.code);
        long parseStartedAt = PerfTrace.start("wfwf_search_parse_ms");
        ArrayList<Title> parsed = new ArrayList<>();
        appendUnique(parsed, parseWfwfSearchHtmlFast(page.body, base_webtoon, 80));
        appendUnique(parsed, parseWfwfSearchHtmlFast(page.body, base_comic, 120));
        traceSearchMetric("wfwf_search_parse_ms", parseStartedAt,
                ",count=" + parsed.size()
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
        return new PageTitles(parsed, null);
    }

    private static ArrayList<Title> parseWfwfSearchHtmlFast(String body, int targetBaseMode, int limit) {
        ArrayList<Title> titles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if(body == null || body.length() == 0)
            return titles;
        Matcher matcher = WFWF_FAST_LINK_PATTERN.matcher(body);
        while(matcher.find()) {
            try {
                String href = decodeFastHtml(matcher.group(2));
                int id = fastQueryInt(href, "toon");
                if(id <= 0)
                    id = fastPathId(href, "webtoon");
                if(id <= 0)
                    id = fastPathId(href, "manhwa");
                if(id <= 0)
                    continue;
                int detectedBaseMode = fastDetectBaseMode(href);
                if(detectedBaseMode != 0 && detectedBaseMode != targetBaseMode)
                    continue;
                String seenKey = targetBaseMode + ":" + id;
                if(!seen.add(seenKey))
                    continue;
                String inner = matcher.group(3);
                String name = fastHeadingText(inner);
                if(name.length() == 0)
                    name = fastCleanText(stripFastTags(inner));
                String thumb = fastThumb(inner);
                Title title = new Title(name, thumb, "", new ArrayList<>(), "", id, targetBaseMode);
                title.setSourceSite("wfwf");
                MainPageWebtoon.applyInferredSearchTags(title);
                titles.add(title);
                if(limit > 0 && titles.size() >= limit)
                    break;
            } catch (Exception ignored) {
            }
        }
        return titles;
    }

    static ArrayList<Title> parseWfwfSearchHtmlFastForTest(String body, int targetBaseMode, int limit) {
        return parseWfwfSearchHtmlFast(body, targetBaseMode, limit);
    }

    private static int fastQueryInt(String href, String key) {
        try {
            if(href == null || key == null)
                return -1;
            String marker = key + "=";
            int start = href.indexOf(marker);
            if(start < 0)
                return -1;
            start += marker.length();
            int end = href.indexOf('&', start);
            if(end < 0)
                end = href.indexOf('#', start);
            if(end < 0)
                end = href.length();
            return Integer.parseInt(href.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private static int fastPathId(String href, String segment) {
        try {
            if(href == null)
                return -1;
            String marker = "/" + segment + "/";
            int start = href.indexOf(marker);
            if(start < 0)
                return -1;
            start += marker.length();
            int end = href.indexOf('/', start);
            if(end < 0)
                end = href.indexOf('?', start);
            if(end < 0)
                end = href.indexOf('#', start);
            if(end < 0)
                end = href.length();
            return Integer.parseInt(href.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private static int fastDetectBaseMode(String href) {
        if(href == null)
            return 0;
        String normalized = href.toLowerCase(Locale.ROOT);
        if(normalized.contains("/cl?toon=")
                || normalized.contains("/cv?toon=")
                || normalized.contains("/cm?")
                || normalized.contains("/manhwa"))
            return base_comic;
        if(normalized.contains("/list?toon=")
                || normalized.contains("/view?toon=")
                || normalized.contains("/webtoon")
                || normalized.contains("/ing?")
                || normalized.contains("/end?"))
            return base_webtoon;
        return 0;
    }

    private static String fastHeadingText(String html) {
        if(html == null)
            return "";
        Matcher matcher = WFWF_FAST_HEADING_PATTERN.matcher(html);
        if(!matcher.find())
            return "";
        return fastCleanText(stripFastTags(matcher.group(1)));
    }

    private static String fastThumb(String html) {
        if(html == null)
            return "";
        Matcher imgMatcher = WFWF_FAST_IMG_PATTERN.matcher(html);
        if(imgMatcher.find()) {
            String attrs = imgMatcher.group(1);
            String[] names = {"data-original", "data-src", "data-lazy-src", "data-url", "data-image",
                    "data-img", "data-thumb", "data-thumbnail", "data-background-image", "src"};
            for(String attr : names) {
                String value = fastAttr(attrs, attr);
                if(isFastImageValue(value))
                    return decodeFastHtml(value).trim();
            }
        }
        Matcher styleMatcher = WFWF_FAST_STYLE_PATTERN.matcher(html);
        while(styleMatcher.find()) {
            String value = fastBackgroundImage(styleMatcher.group(2));
            if(isFastImageValue(value))
                return decodeFastHtml(value).trim();
        }
        return "";
    }

    private static String fastAttr(String attrs, String attr) {
        if(attrs == null || attr == null)
            return "";
        Matcher quoted = Pattern.compile("(?is)\\b" + Pattern.quote(attr) + "\\s*=\\s*(['\"])(.*?)\\1").matcher(attrs);
        if(quoted.find())
            return quoted.group(2);
        Matcher unquoted = Pattern.compile("(?is)\\b" + Pattern.quote(attr) + "\\s*=\\s*([^\\s>]+)").matcher(attrs);
        return unquoted.find() ? unquoted.group(1) : "";
    }

    private static String fastBackgroundImage(String style) {
        if(style == null)
            return "";
        int start = style.indexOf("url(");
        if(start < 0)
            return "";
        start += 4;
        int end = style.indexOf(')', start);
        if(end < 0)
            return "";
        return style.substring(start, end).replace("'", "").replace("\"", "").trim();
    }

    private static boolean isFastImageValue(String value) {
        if(value == null)
            return false;
        String trimmed = value.trim();
        return trimmed.length() > 0
                && !trimmed.startsWith("data:")
                && !"about:blank".equalsIgnoreCase(trimmed)
                && !"#".equals(trimmed)
                && !trimmed.contains("/platforms/");
    }

    private static String stripFastTags(String html) {
        if(html == null)
            return "";
        return WFWF_FAST_TAG_PATTERN.matcher(html).replaceAll(" ");
    }

    private static String fastCleanText(String text) {
        if(text == null)
            return "";
        return decodeFastHtml(text)
                .replace("UP", "")
                .replace("NEW", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String decodeFastHtml(String value) {
        if(value == null)
            return "";
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static void traceSearchMetric(String name, long startedAtMs, String metadata) {
        if(!PerfTrace.shouldLog())
            return;
        PerfTrace.mark(name, (SystemClock.elapsedRealtime() - startedAtMs)
                + (metadata == null || metadata.length() == 0 ? "" : metadata));
    }

    private static CustomHttpClient.PageResponse fetchSearchPage(CustomHttpClient client, String path,
                                                                 boolean suppressWebViewFallback) throws Exception {
        if(client == null)
            throw new Exception("Missing HTTP client");
        if(client.isNtk() && shouldTryNtkAliasSearchFallback(path)) {
            CustomHttpClient.PageResponse directPage = null;
            Exception directError = null;
            try {
                directPage = fetchSearchPageNoWebView(client, path);
                if(!shouldRetryNtkSearchWithAlias(client, directPage, null, path))
                    return directPage;
            } catch (Exception e) {
                if(!shouldRetryNtkSearchWithAlias(client, null, e, path))
                    throw e;
                directError = e;
            }
            CustomHttpClient.PageResponse aliasPage = null;
            try {
                aliasPage = fetchNtkAliasSearchPage(client, path);
                if(!shouldRetryNtkSearchWithAlias(client, aliasPage, null, path))
                    return aliasPage;
            } catch (Exception aliasError) {
                Log.d(TAG, "ntk_search_alias_error path=" + ntkMetricPath(path)
                        + ",type=" + aliasError.getClass().getSimpleName()
                        + ",message=" + aliasError.getMessage());
                if(suppressWebViewFallback) {
                    if(directError != null)
                        throw directError;
                    throw aliasError;
                }
            }
            if(suppressWebViewFallback) {
                if(directPage != null)
                    return directPage;
                if(directError != null)
                    throw directError;
                return aliasPage;
            }
            if(directError == null)
                Log.d(TAG, "ntk_search_alias_fallback_to_webview path=" + ntkMetricPath(path)
                        + ",directCode=" + (directPage == null ? 0 : directPage.code)
                        + ",aliasCode=" + (aliasPage == null ? 0 : aliasPage.code));
        }
        if(!suppressWebViewFallback)
            return client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
        return fetchSearchPageNoWebView(client, path);
    }

    private static CustomHttpClient.PageResponse fetchSearchPageNoWebView(CustomHttpClient client,
                                                                          String path) throws Exception {
        return client.runWithFetchMode(CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW,
                () -> client.mgetCachedPage(path, PAGE_CACHE_TTL_MS));
    }

    private static CustomHttpClient.PageResponse fetchNtkAliasSearchPage(CustomHttpClient client,
                                                                         String path) throws Exception {
        long startedAt = PerfTrace.start("ntk_search_alias_fetch_ms");
        try {
            CustomHttpClient.PageResponse page = client.runWithSitePreset(
                    NTK_ALIAS_COMIC_URL, NTK_ALIAS_WEBTOON_URL,
                    () -> client.mgetNtkDesktopSearchPage(path, PAGE_CACHE_TTL_MS));
            traceSearchMetric("ntk_search_alias_fetch_ms", startedAt,
                    ",path=" + ntkMetricPath(path)
                            + ",code=" + (page == null ? 0 : page.code)
                            + ",bodyLen=" + (page == null || page.body == null ? 0 : page.body.length()));
            if(page != null && page.code >= 200 && page.code < 400
                    && !client.isCloudflareChallengeResponse(page.code, page.body))
                client.applyResolvedNtkRootFromSearch(NTK_ALIAS_WEBTOON_URL);
            return page;
        } catch (Exception e) {
            traceSearchMetric("ntk_search_alias_error_ms", startedAt,
                    ",path=" + ntkMetricPath(path)
                            + ",type=" + e.getClass().getSimpleName());
            throw e;
        }
    }

    private static boolean shouldTryNtkAliasSearchFallback(String path) {
        if(path == null)
            return false;
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.startsWith("/api/works")
                || normalized.startsWith("/api/manhwa-list")
                || normalized.startsWith("/search?");
    }

    private static boolean shouldRetryNtkSearchWithAlias(CustomHttpClient client,
                                                         CustomHttpClient.PageResponse page,
                                                         Exception error,
                                                         String path) {
        if(client == null || !client.isNtk() || !shouldTryNtkAliasSearchFallback(path))
            return false;
        if(error != null)
            return isCaptchaRequiredException(error)
                    || String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT).contains("cloudflare");
        if(page == null)
            return true;
        return page.code == 0
                || page.code == 403
                || client.isCloudflareChallengeResponse(page.code, page.body);
    }

    static boolean shouldTryNtkAliasSearchFallbackForTest(String path) {
        return shouldTryNtkAliasSearchFallback(path);
    }

    static boolean isNtkSearchNoWebViewPathForTest(String path) {
        return isNtkSearchNoWebViewPath(path);
    }

    private static boolean isNtkSearchNoWebViewPath(String path) {
        return HttpDocumentPolicy.isNtkSearchNoWebViewPath(path);
    }

    private int fetchWebtoon(CustomHttpClient client) {
        try {
            ArrayList<Title> webtoonResults = new ArrayList<>();
            if(mode == 8) {
                if(client != null && client.isNtk()) {
                    last = appendNextNtkCategoryPage(client, webtoonResults, query, 0);
                } else {
                    String genre = genreFromCategoryPath(query, base_webtoon);
                    if(genre.length() > 0) {
                        last = appendNextClassificationDbGenreResults(client, webtoonResults, genre);
                        if(webtoonResults.size() == 0 && !classificationSourceFetched) {
                            appendWebtoonResults(client, webtoonResults, query, 0);
                            classificationSourceFetched = true;
                            last = true;
                        }
                    } else {
                        if(!classificationSourceFetched) {
                            appendWebtoonResults(client, webtoonResults, query, 0);
                            classificationSourceFetched = true;
                        }
                        last = true;
                    }
                }
            } else if(mode == 2) {
                if(!classificationSourceFetched) {
                    appendWebtoonResults(client, webtoonResults, webtoonGenrePath("ing", query), 80);
                    appendWebtoonResults(client, webtoonResults, webtoonGenrePath("end", query), 80);
                    classificationSourceFetched = true;
                }
                last = appendNextClassificationDbGenreResults(client, webtoonResults, query);
            } else if(mode == 3) {
                String alphabet = percentEncode(alphabetValue(query), Charset.forName("EUC-KR"));
                appendWebtoonResults(client, webtoonResults, ntkPath(client, "/ing?letter=" + alphabet, "/ing?type1=alphabet&type2=" + alphabet + "&o=n"), 80);
                appendWebtoonResults(client, webtoonResults, ntkPath(client, "/end?letter=" + alphabet, "/end?type1=alphabet&type2=" + alphabet + "&o=n"), 80);
                last = true;
            } else if(mode == 4) {
                String status = webtoonStatus(query);
                if(status.length() > 0) {
                    appendWebtoonResults(client, webtoonResults, ntkPath(client, status, status + "?type1=day&type2=recent&o=n"), 80);
                    last = true;
                } else {
                    String day = webtoonDay(query);
                    if(day.length() > 0) {
                        appendWebtoonResults(client, webtoonResults, ntkPath(client, "/ing?day=" + percentEncode(query, Charset.forName("UTF-8")), "/ing?type1=day&type2=" + day + "&o=n"), 80);
                        appendWebtoonResults(client, webtoonResults, ntkPath(client, "/end?day=" + percentEncode(query, Charset.forName("UTF-8")), "/end?type1=day&type2=" + day + "&o=n"), 80);
                        last = true;
                    } else {
                        last = appendSearchResults(client, webtoonResults, base_webtoon, 80);
                    }
                }
            } else {
                last = appendSearchResults(client, webtoonResults, base_webtoon, 80);
            }

            appendNewResults(webtoonResults);
            return 0;
        } catch (Exception e) {
            if(shouldReportSearchFailure(e))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return 1;
        }
    }

    private int fetchComic(CustomHttpClient client) {
        try {
            ArrayList<Title> comicResults = new ArrayList<>();
            if(mode == 8) {
                if(client != null && client.isNtk()) {
                    last = appendNextNtkCategoryPage(client, comicResults, query, 0);
                } else {
                    String genre = genreFromCategoryPath(query, base_comic);
                    if(genre.length() > 0) {
                        last = appendNextClassificationDbGenreResults(client, comicResults, genre);
                        if(comicResults.size() == 0 && !classificationSourceFetched) {
                            appendWebtoonResults(client, comicResults, query, 0);
                            classificationSourceFetched = true;
                            last = true;
                        }
                    } else {
                        if(!classificationSourceFetched) {
                            appendWebtoonResults(client, comicResults, query, 0);
                            classificationSourceFetched = true;
                        }
                        last = true;
                    }
                }
            } else if(mode == 2) {
                if(!classificationSourceFetched) {
                    appendWebtoonResults(client, comicResults, comicRoot(client) + "?type1=genre&type2=" + percentEncode(query, Charset.forName("EUC-KR")) + "&o=n", 120);
                    classificationSourceFetched = true;
                }
                last = appendNextClassificationDbGenreResults(client, comicResults, query);
            } else if(mode == 3) {
                String alphabet = percentEncode(alphabetValue(query), Charset.forName("EUC-KR"));
                appendWebtoonResults(client, comicResults, ntkPath(client, "/manhwa?letter=" + alphabet, comicRoot(client) + "?type1=alphabet&type2=" + alphabet + "&o=n"), 120);
                last = true;
            } else if(mode == 4) {
                String type = comicType(query);
                if(type.length() > 0) {
                    appendWebtoonResults(client, comicResults, ntkPath(client, "/manhwa?sort=recent", comicRoot(client) + "?type1=complete&type2=" + type + "&o=n"), 120);
                    last = true;
                } else {
                    last = appendSearchResults(client, comicResults, base_comic, 120);
                }
            } else {
                last = appendSearchResults(client, comicResults, base_comic, 120);
            }

            appendNewResults(comicResults);
            return 0;
        } catch (Exception e) {
            if(shouldReportSearchFailure(e))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return 1;
        }
    }

    private void appendWebtoonResults(CustomHttpClient client, ArrayList<Title> target, String path, int limit) throws Exception {
        target.addAll(fetchWebtoonResults(client, path, limit, 1).titles);
    }

    private PageTitles fetchWebtoonResults(CustomHttpClient client, String path, int limit, int currentPage) throws Exception {
        return cachedNtkPageTitles(client, "webtoon", path, baseMode, limit, currentPage,
                () -> fetchWebtoonResultsUncached(client, path, limit, currentPage));
    }

    private PageTitles fetchWebtoonResultsUncached(CustomHttpClient client, String path, int limit, int currentPage) throws Exception {
        CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
        throwIfCloudflareChallenge(client, page.code, page.body, path);
        if(page.code >= 400)
            throw new Exception("Webtoon search failed: " + page.code);
        if(client != null && client.isNtk() && isNtkApiListPath(path))
            return parseNtkApiPage(page.body, path, baseMode, limit, currentPage);
        boolean fastWfwfKeyword = client != null && !client.isNtk() && path != null && path.startsWith("/search.html");
        Document d = fastWfwfKeyword ? null : Jsoup.parse(page.body);
        ArrayList<Title> parsed = fastWfwfKeyword
                ? parseWfwfSearchHtmlFast(page.body, baseMode, limit)
                : MainPageWebtoon.parseWolfTitles(d, baseMode, limit);
        if(parsed.size() == 0 && client.resolveWfwfDomainNow()) {
            page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
            throwIfCloudflareChallenge(client, page.code, page.body, path);
            if(page.code >= 400)
                throw new Exception("Webtoon search failed: " + page.code);
            d = fastWfwfKeyword ? null : Jsoup.parse(page.body);
            parsed = fastWfwfKeyword
                    ? parseWfwfSearchHtmlFast(page.body, baseMode, limit)
                    : MainPageWebtoon.parseWolfTitles(d, baseMode, limit);
        }
        String sourceSite = client != null && client.isNtk() ? "ntk" : "wfwf";
        for(Title title : parsed)
            if(title != null)
                title.setSourceSite(sourceSite);
        String nextPath = client != null && client.isNtk() ? findNtkNextPagePath(d, path, currentPage + 1) : null;
        return new PageTitles(parsed, nextPath);
    }

    private boolean appendNextNtkCategoryPage(CustomHttpClient client, ArrayList<Title> target, String path, int limit) throws Exception {
        if(isNtkCombinedGenrePath(path))
            return appendNextNtkCombinedGenrePage(client, target, path, limit);
        ArrayList<String> candidates = new ArrayList<>();
        if(ntkCategoryNextPath != null && ntkCategoryNextPath.length() > 0)
            addCandidate(candidates, ntkCategoryNextPath);
        addCandidate(candidates, ntkCategoryApiPath(path, page));
        for(String candidate : ntkPageCandidates(path, page))
            addCandidate(candidates, candidate);
        if(candidates.size() == 0)
            return true;

        Exception lastError = null;
        for(String pagePath : candidates) {
            try {
                PageTitles pageTitles = fetchWebtoonResults(client, pagePath, limit, page);
                ArrayList<Title> parsed = pageTitles.titles;
                int added = appendUniquePageTitles(target, parsed);
                if(added == 0)
                    continue;
                page++;
                ntkCategoryNextPath = pageTitles.nextPath;
                if(pageTitles.totalCount > 0)
                    classificationDbTotalCount = pageTitles.totalCount;
                classificationSourceFetched = true;
                return pageTitles.hasMoreKnown && !pageTitles.hasMore;
            } catch (Exception e) {
                lastError = e;
            }
        }
        if(page <= 1 && lastError != null)
            throw lastError;
        classificationSourceFetched = true;
        return true;
    }

    private boolean appendNextNtkCombinedGenrePage(CustomHttpClient client, ArrayList<Title> target, String path, int limit) throws Exception {
        String ongoing = decodedRawQueryValue(path, "ongoing");
        String completed = decodedRawQueryValue(path, "completed");
        int targetBaseMode = "webtoon".equals(rawQueryValue(path, "kind")) ? base_webtoon : base_comic;
        ArrayList<PageTitles> pages = new ArrayList<>();
        Exception lastError = null;
        if(ongoing != null && ongoing.length() > 0) {
            try {
                PageTitles pageTitles = fetchCombinedGenreStatusPage(client, ongoing, targetBaseMode, "연재", limit);
                if(pageTitles.totalCount > 0)
                    ntkOngoingTotalCount = pageTitles.totalCount;
                pages.add(pageTitles);
            } catch (Exception e) {
                lastError = e;
            }
        }
        if(completed != null && completed.length() > 0) {
            try {
                PageTitles pageTitles = fetchCombinedGenreStatusPage(client, completed, targetBaseMode, "완결", limit);
                if(pageTitles.totalCount > 0)
                    ntkCompletedTotalCount = pageTitles.totalCount;
                pages.add(pageTitles);
            } catch (Exception e) {
                lastError = e;
            }
        }
        if(pages.size() == 0) {
            if(lastError != null)
                throw lastError;
            classificationSourceFetched = true;
            return true;
        }
        boolean hasMore = false;
        int total = 0;
        for(PageTitles pageTitles : pages) {
            appendUniquePageTitles(target, pageTitles.titles);
            hasMore = hasMore || pageTitles.hasMore;
            if(pageTitles.totalCount > 0)
                total += pageTitles.totalCount;
        }
        page++;
        if(total > 0)
            classificationDbTotalCount = total;
        classificationSourceFetched = true;
        return !hasMore;
    }

    private PageTitles fetchCombinedGenreStatusPage(CustomHttpClient client, String sourcePath, int targetBaseMode, String statusLabel, int limit) throws Exception {
        String apiPath = ntkCategoryApiPath(sourcePath, page, targetBaseMode);
        if(apiPath == null || apiPath.length() == 0)
            apiPath = sourcePath;
        PageTitles pageTitles = fetchWebtoonResults(client, apiPath, limit, page);
        for(Title title : pageTitles.titles)
            if(title != null)
                title.setNtkStatusLabel(statusLabel);
        return pageTitles;
    }

    private static boolean isNtkCombinedGenrePath(String path) {
        return path != null && path.startsWith("/ntk-genre?");
    }

    private static String decodedRawQueryValue(String path, String key) {
        String value = rawQueryValue(path, key);
        return value == null ? "" : percentDecode(value, Charset.forName("UTF-8"));
    }

    private int appendUniquePageTitles(ArrayList<Title> target, ArrayList<Title> parsed) {
        int added = 0;
        HashSet<String> pageKeys = new HashSet<>();
        for(Title title : parsed) {
            if(title == null)
                continue;
            String key = titleIdentityKey(title);
            if(seenTitleKeys.contains(key) || !pageKeys.add(key))
                continue;
            target.add(title);
            added++;
        }
        return added;
    }

    private String ntkCategoryApiPath(String path, int page) {
        return ntkCategoryApiPath(path, page, baseMode);
    }

    static String ntkCategoryApiPathForTest(String path, int page, int baseMode) {
        return ntkCategoryApiPath(path, page, baseMode);
    }

    private static String ntkCategoryApiPath(String path, int page, int baseMode) {
        if(path == null || path.length() == 0 || page < 1)
            return null;
        int hash = path.indexOf('#');
        String base = hash >= 0 ? path.substring(0, hash) : path;
        String[] split = base.split("\\?", 2);
        String route = normalizedNtkRoute(split[0]);
        String query = split.length > 1 ? split[1] : "";
        ArrayList<String> params = normalizeNtkApiParams(queryParamsWithoutPage(query));
        if(baseMode == base_comic && route.startsWith("/manhwa")) {
            ArrayList<String> api = new ArrayList<>();
            api.add("status=" + (route.startsWith("/manhwa-end") ? "completed" : ""));
            api.addAll(params);
            api.add("page=" + page);
            api.add("pageSize=" + NTK_CATEGORY_PAGE_SIZE);
            api.add("withTotal=1");
            return "/api/manhwa-list?" + String.join("&", api);
        }
        if(baseMode == base_webtoon && (route.startsWith("/ing") || route.startsWith("/end"))) {
            ArrayList<String> api = new ArrayList<>();
            api.add("status=" + (route.startsWith("/end") ? "completed" : "ing"));
            api.addAll(params);
            api.add("page=" + page);
            api.add("pageSize=" + NTK_CATEGORY_PAGE_SIZE);
            api.add("withTotal=1");
            return "/api/works?" + String.join("&", api);
        }
        return null;
    }

    private static ArrayList<String> normalizeNtkApiParams(ArrayList<String> params) {
        ArrayList<String> result = new ArrayList<>();
        String legacyType1 = "";
        String legacyType2 = "";
        if(params == null)
            return result;
        for(String param : params) {
            if(param == null || param.length() == 0)
                continue;
            String[] split = param.split("=", 2);
            String key = split[0];
            String value = split.length > 1 ? split[1] : "";
            if("type1".equals(key)) {
                legacyType1 = value;
                continue;
            }
            if("type2".equals(key)) {
                legacyType2 = value;
                continue;
            }
            if("day".equals(key)) {
                String day = webtoonDay(percentDecode(value, Charset.forName("UTF-8")));
                result.add("day=" + (day.length() > 0 ? day : value));
                continue;
            }
            result.add(param);
        }
        if("day".equals(legacyType1) && legacyType2.length() > 0 && !hasQueryParam(result, "day")) {
            String day = webtoonDay(percentDecode(legacyType2, Charset.forName("UTF-8")));
            result.add("day=" + (day.length() > 0 ? day : legacyType2));
        } else if("genre".equals(legacyType1) && legacyType2.length() > 0 && !hasQueryParam(result, "tag")) {
            result.add("tag=" + legacyType2);
        } else if("alphabet".equals(legacyType1) && legacyType2.length() > 0 && !hasQueryParam(result, "letter")) {
            result.add("letter=" + legacyType2);
        }
        return result;
    }

    private static boolean hasQueryParam(ArrayList<String> params, String key) {
        if(params == null || key == null)
            return false;
        for(String param : params) {
            if(param != null && param.split("=", 2)[0].equals(key))
                return true;
        }
        return false;
    }

    private static class PageTitles {
        final ArrayList<Title> titles;
        final String nextPath;
        final boolean hasMoreKnown;
        final boolean hasMore;
        final int totalCount;

        PageTitles(ArrayList<Title> titles, String nextPath) {
            this(titles, nextPath, false, nextPath != null && nextPath.length() > 0, 0);
        }

        PageTitles(ArrayList<Title> titles, String nextPath, boolean hasMoreKnown, boolean hasMore, int totalCount) {
            this.titles = titles == null ? new ArrayList<>() : titles;
            this.nextPath = nextPath;
            this.hasMoreKnown = hasMoreKnown;
            this.hasMore = hasMore;
            this.totalCount = totalCount;
        }
    }

    private static class CachedPageTitles {
        final PageTitles pageTitles;
        final long loadedAt;

        CachedPageTitles(PageTitles pageTitles, long loadedAt) {
            this.pageTitles = pageTitles;
            this.loadedAt = loadedAt;
        }
    }

    private static class CachedNtkApiPathResult {
        final NtkApiPathResult result;
        final long loadedAt;

        CachedNtkApiPathResult(NtkApiPathResult result, long loadedAt) {
            this.result = result;
            this.loadedAt = loadedAt;
        }
    }

    private interface PageTitleLoader {
        PageTitles load() throws Exception;
    }

    private interface NtkApiPathLoader {
        NtkApiPathResult load();
    }

    private interface NtkHybridLoader {
        PageTitles load() throws Exception;
    }

    public static void clearNtkResultCaches() {
        synchronized (NTK_RESULT_CACHE) {
            NTK_RESULT_CACHE.clear();
        }
        synchronized (NTK_KEYWORD_API_RESULT_CACHE) {
            NTK_KEYWORD_API_RESULT_CACHE.clear();
        }
    }

    private PageTitles cachedNtkPageTitles(CustomHttpClient client, String kind, String path, int targetBaseMode,
                                           int limit, int currentPage, PageTitleLoader loader) throws Exception {
        if(client == null || !client.isNtk())
            return loader.load();
        String key = kind + ':' + targetBaseMode + ':' + limit + ':' + currentPage + ':' + path;
        long now = System.currentTimeMillis();
        synchronized (NTK_RESULT_CACHE) {
            CachedPageTitles cached = NTK_RESULT_CACHE.get(key);
            if(cached != null && isNtkResultCacheFresh(cached.loadedAt, now, NTK_RESULT_CACHE_TTL_MS))
                return copyPageTitles(cached.pageTitles);
        }
        PageTitles loaded = loader.load();
        synchronized (NTK_RESULT_CACHE) {
            if(NTK_RESULT_CACHE.size() >= NTK_RESULT_CACHE_MAX_ENTRIES)
                NTK_RESULT_CACHE.clear();
            NTK_RESULT_CACHE.put(key, new CachedPageTitles(copyPageTitles(loaded), System.currentTimeMillis()));
        }
        return loaded;
    }

    private static PageTitles copyPageTitles(PageTitles source) {
        if(source == null)
            return new PageTitles(new ArrayList<>(), null);
        return new PageTitles(new ArrayList<>(source.titles), source.nextPath, source.hasMoreKnown, source.hasMore, source.totalCount);
    }

    static boolean isNtkResultCacheFreshForTest(long loadedAt, long now, long ttlMs) {
        return isNtkResultCacheFresh(loadedAt, now, ttlMs);
    }

    private static boolean isNtkResultCacheFresh(long loadedAt, long now, long ttlMs) {
        return loadedAt <= now && now - loadedAt < ttlMs;
    }

    private static boolean isNtkApiListPath(String path) {
        return path != null && (path.startsWith("/api/manhwa-list") || path.startsWith("/api/works"));
    }

    private static PageTitles parseNtkApiPage(String body, String path, int baseMode, int limit, int currentPage) throws Exception {
        JsonElement root = JsonParser.parseString(body == null || body.length() == 0 ? "{}" : body);
        JsonObject json = root != null && root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
        JsonArray works = json.has("works") && json.get("works").isJsonArray()
                ? json.getAsJsonArray("works")
                : null;
        ArrayList<Title> titles = new ArrayList<>();
        if(works != null) {
            for(int i = 0; i < works.size(); i++) {
                JsonElement workElement = works.get(i);
                if(workElement == null || !workElement.isJsonObject())
                    continue;
                JsonObject work = workElement.getAsJsonObject();
                String sourceWorkId = ntkCanonicalWorkId(work);
                int id = parsePositiveInt(sourceWorkId);
                if(id <= 0)
                    id = stableNtkSourceId(sourceWorkId);
                if(id <= 0)
                    continue;
                String name = jsonString(work, "title").trim();
                if(name.length() == 0)
                    continue;
                String thumb = jsonString(work, "thumbnailUrl");
                ArrayList<String> tags = ntkGenreTags(work);
                String release = ntkReleaseLabel(work);
                Title title = new Title(name, thumb, "", tags, release, id, baseMode);
                title.setSourceSite("ntk");
                String titlePath = ntkApiTitlePath(baseMode, sourceWorkId);
                if(titlePath.length() > 0)
                    title.setPath(titlePath);
                title.setNtkStatusLabel(ntkStatusLabelFromApiPath(path));
                titles.add(title);
                if(limit > 0 && titles.size() >= limit)
                    break;
            }
        }
        int apiPage = jsonInt(json, "page", currentPage);
        int pageSize = jsonInt(json, "pageSize", NTK_CATEGORY_PAGE_SIZE);
        boolean hasMore = jsonBoolean(json, "hasMore", false);
        int total = json.has("total") ? jsonInt(json, "total", 0) : 0;
        String nextPath = hasMore ? replaceNtkQueryParam(replaceNtkQueryParam(path, "page", String.valueOf(apiPage + 1)), "pageSize", String.valueOf(pageSize)) : null;
        return new PageTitles(titles, nextPath, true, hasMore, total);
    }

    private static String ntkStatusLabelFromApiPath(String path) {
        String status = rawQueryValue(path, "status");
        if("completed".equals(status))
            return "완결";
        if("ing".equals(status))
            return "연재";
        if(path != null && path.startsWith("/api/manhwa-list"))
            return "연재";
        return "";
    }

    static ArrayList<Title> parseNtkApiTitles(String body, int baseMode, int limit) throws Exception {
        return parseNtkApiPage(body, "/api/manhwa-list?page=1&pageSize=30", baseMode, limit, 1).titles;
    }

    static ArrayList<Title> parseNtkApiTitlesForTest(String body, int baseMode) throws Exception {
        return parseNtkApiTitles(body, baseMode, 0);
    }

    static int parseNtkApiTotalForTest(String body, int baseMode) throws Exception {
        return parseNtkApiPage(body, "/api/manhwa-list?page=1&pageSize=30&withTotal=1", baseMode, 0, 1).totalCount;
    }

    private static boolean hasJsonValue(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key) != null && !json.get(key).isJsonNull();
    }

    private static String ntkReleaseLabel(JsonObject work) {
        String ep = jsonString(work, "ep").trim();
        if(ep.length() > 0)
            return ep;
        if(hasJsonValue(work, "latestEpisodeNumber"))
            return jsonString(work, "latestEpisodeNumber") + "화";
        return "";
    }

    private static String jsonString(JsonObject json, String key) {
        if(!hasJsonValue(json, key))
            return "";
        try {
            JsonElement value = json.get(key);
            return value.isJsonPrimitive() ? value.getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int jsonInt(JsonObject json, String key, int fallback) {
        if(!hasJsonValue(json, key))
            return fallback;
        try {
            return json.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean jsonBoolean(JsonObject json, String key, boolean fallback) {
        if(!hasJsonValue(json, key))
            return fallback;
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int parsePositiveInt(String value) {
        if(value == null)
            return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String firstNonEmpty(String first, String second) {
        if(first != null && first.trim().length() > 0)
            return first.trim();
        return second == null ? "" : second.trim();
    }

    private static String ntkCanonicalWorkId(JsonObject work) {
        String sourceWorkId = jsonString(work, "sourceWorkId");
        if(parsePositiveInt(sourceWorkId) > 0)
            return sourceWorkId.trim();
        String id = jsonString(work, "id");
        if(parsePositiveInt(id) > 0)
            return id.trim();
        return firstNonEmpty(sourceWorkId, id);
    }

    private static int stableNtkSourceId(String value) {
        if(value == null)
            return 0;
        String trimmed = value.trim();
        if(trimmed.length() == 0)
            return 0;
        int hash = 0x811c9dc5;
        for(int i = 0; i < trimmed.length(); i++)
            hash = (hash ^ trimmed.charAt(i)) * 0x01000193;
        hash &= 0x7fffffff;
        return hash == 0 ? 1 : hash;
    }

    private static String ntkApiTitlePath(int baseMode, String sourceWorkId) {
        if(sourceWorkId == null)
            return "";
        String value = sourceWorkId.trim();
        if(value.length() == 0)
            return "";
        int scheme = value.indexOf("://");
        if(scheme >= 0) {
            int slash = value.indexOf('/', scheme + 3);
            value = slash >= 0 ? value.substring(slash) : "";
        }
        int query = value.indexOf('?');
        if(query >= 0)
            value = value.substring(0, query);
        int hash = value.indexOf('#');
        if(hash >= 0)
            value = value.substring(0, hash);
        if(value.startsWith("/manhwa/") || value.startsWith("/webtoon/"))
            return trimTrailingPathSlash(value);
        while(value.startsWith("/"))
            value = value.substring(1);
        if(value.length() == 0)
            return "";
        String segment = baseMode == base_webtoon ? "webtoon" : "manhwa";
        return "/" + segment + "/" + value;
    }

    private static String trimTrailingPathSlash(String value) {
        while(value != null && value.endsWith("/") && value.length() > 1)
            value = value.substring(0, value.length() - 1);
        return value == null ? "" : value;
    }

    private static ArrayList<String> ntkGenreTags(JsonObject work) {
        ArrayList<String> tags = new ArrayList<>();
        appendNtkGenreValue(tags, work, "genre");
        appendNtkGenreValue(tags, work, "genres");
        appendNtkGenreValue(tags, work, "tags");
        return tags;
    }

    private static void appendNtkGenreValue(ArrayList<String> tags, JsonObject work, String key) {
        if(tags == null || work == null || !hasJsonValue(work, key))
            return;
        appendNtkGenreElement(tags, work.get(key));
    }

    private static void appendNtkGenreElement(ArrayList<String> tags, JsonElement value) {
        if(value == null || value.isJsonNull())
            return;
        if(value.isJsonArray()) {
            JsonArray values = value.getAsJsonArray();
            for(int i = 0; i < values.size(); i++)
                appendNtkGenreElement(tags, values.get(i));
            return;
        }
        if(value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            String label = firstNonEmpty(jsonString(object, "name"), jsonString(object, "label"));
            label = firstNonEmpty(label, jsonString(object, "title"));
            label = firstNonEmpty(label, jsonString(object, "value"));
            splitNtkGenreInto(tags, label);
            return;
        }
        if(value.isJsonPrimitive())
            splitNtkGenreInto(tags, value.getAsString());
    }

    private static void splitNtkGenreInto(ArrayList<String> tags, String value) {
        if(tags == null || value == null)
            return;
        for(String tag : value.split("[,/|]")) {
            String normalized = normalizeNtkGenreTag(tag);
            if(normalized.length() > 0 && !tags.contains(normalized))
                tags.add(normalized);
        }
    }

    private static String normalizeNtkGenreTag(String value) {
        if(value == null)
            return "";
        String trimmed = value.trim();
        if(trimmed.startsWith("#"))
            trimmed = trimmed.substring(1).trim();
        if("17".equals(trimmed) || "adult".equalsIgnoreCase(trimmed))
            return "성인";
        return trimmed;
    }

    private static String replaceNtkQueryParam(String path, String key, String value) {
        if(path == null || path.length() == 0 || key == null || key.length() == 0)
            return path;
        int hash = path.indexOf('#');
        String fragment = hash >= 0 ? path.substring(hash) : "";
        String base = hash >= 0 ? path.substring(0, hash) : path;
        String[] split = base.split("\\?", 2);
        String route = split[0];
        String query = split.length > 1 ? split[1] : "";
        ArrayList<String> params = new ArrayList<>();
        boolean replaced = false;
        if(query.length() > 0) {
            for(String param : query.split("&")) {
                if(param.length() == 0)
                    continue;
                String paramKey = param.split("=", 2)[0];
                if(paramKey.equals(key)) {
                    if(!replaced) {
                        params.add(key + "=" + value);
                        replaced = true;
                    }
                } else {
                    params.add(param);
                }
            }
        }
        if(!replaced)
            params.add(key + "=" + value);
        return route + "?" + String.join("&", params) + fragment;
    }

    private static String findNtkNextPagePath(Document document, String currentPath, int nextPage) {
        if(document == null || nextPage <= 1)
            return null;
        String nextPageText = String.valueOf(nextPage);
        for(Element link : document.select("a[href]")) {
            String href = link.attr("href");
            if(href == null || href.length() == 0)
                continue;
            String resolved = resolveNtkHref(currentPath, href);
            if(resolved == null || resolved.length() == 0)
                continue;
            if(resolved.equals(currentPath))
                continue;
            String text = link.text() == null ? "" : link.text().trim();
            String label = (link.attr("aria-label") + " " + link.attr("title")).trim().toLowerCase(Locale.ROOT);
            String rel = link.attr("rel");
            String className = link.className();
            boolean hrefLooksNext = isLikelyNtkPagePath(resolved, currentPath, nextPage);
            boolean looksNext = hrefLooksNext
                    || "next".equalsIgnoreCase(rel)
                    || className.toLowerCase(Locale.ROOT).contains("next")
                    || label.contains("next")
                    || label.contains("다음")
                    || text.equals(nextPageText)
                    || text.equals("다음")
                    || text.equals("›")
                    || text.equals("»")
                    || text.equals(">");
            if(!looksNext)
                continue;
            if(isLikelyNtkPagePath(resolved, currentPath, nextPage))
                return resolved;
        }
        return null;
    }

    static String findNtkNextPagePathForTest(String html, String currentPath, int nextPage) {
        return findNtkNextPagePath(Jsoup.parse(html == null ? "" : html), currentPath, nextPage);
    }

    private static String resolveNtkHref(String currentPath, String href) {
        if(href == null)
            return null;
        String value = href.trim();
        if(value.length() == 0 || value.startsWith("#") || value.toLowerCase(Locale.ROOT).startsWith("javascript:"))
            return null;
        if(value.startsWith("http://") || value.startsWith("https://")) {
            try {
                java.net.URI uri = java.net.URI.create(value);
                String path = uri.getRawPath();
                String query = uri.getRawQuery();
                if(path == null || path.length() == 0)
                    path = "/";
                return query == null || query.length() == 0 ? path : path + "?" + query;
            } catch (Exception e) {
                return null;
            }
        }
        if(value.startsWith("/"))
            return value;
        if(value.startsWith("?")) {
            String route = normalizedNtkRoute(currentPath == null ? "" : currentPath.split("\\?", 2)[0]);
            return route + "?" + mergeNtkQuery(currentPath, value.substring(1));
        }
        String route = currentPath == null ? "" : currentPath.split("\\?", 2)[0];
        int slash = route.lastIndexOf('/');
        String parent = slash >= 0 ? route.substring(0, slash + 1) : "/";
        return parent + value;
    }

    static String ntkPagePathForTest(String path, int page) {
        return ntkPagePath(path, page);
    }

    static ArrayList<String> ntkPageCandidatesForTest(String path, int page) {
        return ntkPageCandidates(path, page);
    }

    private static String ntkPagePath(String path, int page) {
        ArrayList<String> candidates = ntkPageCandidates(path, page);
        return candidates.size() == 0 ? path : candidates.get(0);
    }

    private static ArrayList<String> ntkPageCandidates(String path, int page) {
        ArrayList<String> candidates = new ArrayList<>();
        if(path == null || path.length() == 0)
            return candidates;
        if(page <= 1) {
            candidates.add(path);
            return candidates;
        }
        int hash = path.indexOf('#');
        String fragment = hash >= 0 ? path.substring(hash) : "";
        String base = hash >= 0 ? path.substring(0, hash) : path;
        String[] split = base.split("\\?", 2);
        String route = split[0];
        String query = split.length > 1 ? split[1] : "";
        ArrayList<String> params = queryParamsWithoutPage(query);
        ArrayList<String> pageFirst = new ArrayList<>();
        pageFirst.add("page=" + page);
        pageFirst.addAll(params);
        addCandidate(candidates, route + "?" + String.join("&", pageFirst) + fragment);
        ArrayList<String> pFirst = new ArrayList<>();
        pFirst.add("p=" + page);
        pFirst.addAll(params);
        addCandidate(candidates, route + "?" + String.join("&", pFirst) + fragment);
        addCandidate(candidates, route + "?" + joinWithPage(params, "page", page) + fragment);
        addCandidate(candidates, route + "?" + joinWithPage(params, "p", page) + fragment);
        addCandidate(candidates, route + "/page/" + page + (params.size() == 0 ? "" : "?" + String.join("&", params)) + fragment);
        addCandidate(candidates, route + "/p/" + page + (params.size() == 0 ? "" : "?" + String.join("&", params)) + fragment);
        return candidates;
    }

    private static ArrayList<String> queryParamsWithoutPage(String query) {
        ArrayList<String> params = new ArrayList<>();
        if(query.length() > 0) {
            for(String param : query.split("&")) {
                if(param.length() == 0)
                    continue;
                String key = param.split("=", 2)[0];
                if("page".equals(key) || "p".equals(key) || "paged".equals(key))
                    continue;
                params.add(param);
            }
        }
        return params;
    }

    private static String joinWithPage(ArrayList<String> params, String key, int page) {
        ArrayList<String> next = new ArrayList<>(params);
        next.add(key + "=" + page);
        return String.join("&", next);
    }

    private static void addCandidate(ArrayList<String> candidates, String candidate) {
        if(candidate == null || candidate.length() == 0 || candidates.contains(candidate))
            return;
        candidates.add(candidate);
    }

    private static boolean isLikelyNtkPagePath(String candidate, String currentPath, int page) {
        if(candidate == null || candidate.length() == 0)
            return false;
        String route = normalizedNtkRoute(currentPath == null ? "" : currentPath.split("\\?", 2)[0]);
        if(route.length() > 0 && !candidate.startsWith(route))
            return false;
        if(candidate.contains("page=" + page) || candidate.contains("p=" + page) || candidate.contains("paged=" + page))
            return true;
        return candidate.contains("/page/" + page) || candidate.contains("/p/" + page);
    }

    private static String normalizedNtkRoute(String route) {
        if(route == null || route.length() == 0)
            return "";
        return route.replaceFirst("/(?:page|p)/\\d+/?$", "");
    }

    private static String mergeNtkQuery(String currentPath, String nextQuery) {
        ArrayList<String> merged = new ArrayList<>();
        HashSet<String> nextKeys = new HashSet<>();
        if(nextQuery != null && nextQuery.length() > 0) {
            for(String param : nextQuery.split("&")) {
                if(param.length() == 0)
                    continue;
                nextKeys.add(param.split("=", 2)[0]);
            }
        }
        int question = currentPath == null ? -1 : currentPath.indexOf('?');
        if(nextQuery != null && nextQuery.length() > 0)
            for(String param : nextQuery.split("&"))
                if(param.length() > 0)
                    merged.add(param);
        if(question >= 0 && question + 1 < currentPath.length()) {
            for(String param : currentPath.substring(question + 1).split("&")) {
                if(param.length() == 0)
                    continue;
                String key = param.split("=", 2)[0];
                if("page".equals(key) || "p".equals(key) || "paged".equals(key) || nextKeys.contains(key))
                    continue;
                merged.add(param);
            }
        }
        return String.join("&", merged);
    }

    private boolean appendSearchResults(CustomHttpClient client, ArrayList<Title> target, int targetBaseMode, int limit) throws Exception {
        if(client != null && client.isNtk()) {
            int before = target.size();
            if(page <= 1 && appendExactNtkWorkIdResult(client, target, targetBaseMode)) {
                page++;
                return true;
            }
            int beforePage = page;
            Exception captchaError = null;
            try {
                boolean useKeywordApi = shouldUseNtkKeywordApi(client.isNtk(), mode);
                boolean done = useKeywordApi
                        ? appendNextNtkKeywordApiPage(client, target, targetBaseMode, limit)
                        : appendNextNtkSearchPage(client, target, targetBaseMode, limit);
                if(target.size() > before || mode != 0)
                    return done;
                if(useKeywordApi) {
                    page = beforePage;
                    ntkSearchNextPath = null;
                    try {
                        boolean htmlDone = appendNextNtkSearchPage(client, target, targetBaseMode, limit);
                        if(target.size() > before)
                            return htmlDone;
                    } catch (Exception htmlError) {
                        if(isCaptchaRequiredException(htmlError))
                            captchaError = htmlError;
                        if(shouldReportSearchFailure(htmlError))
                            ml.melun.mangaview.report.CrashReporter.record(htmlError);
                    }
                }
            } catch (Exception e) {
                if(mode != 0)
                    throw e;
                if(isCaptchaRequiredException(e))
                    captchaError = e;
                if(shouldReportSearchFailure(e))
                    ml.melun.mangaview.report.CrashReporter.record(e);
            }
            int added = appendNtkClassificationKeywordResults(target, targetBaseMode, limit);
            if(added > 0)
                return true;
            if(target.size() == before &&
                    (captchaError != null || client.hasRecentCloudflareChallenge()))
                throw new NtkCaptchaRequiredException();
            if(target.size() == before)
                return true;
            return true;
        }
        appendWebtoonResults(client, target, wfwfKeywordSearchPath(query), limit);
        return true;
    }

    /**
     * Production exact-ID search. This is useful for old works whose catalog keyword endpoint
     * does not implement filtering reliably, while still using the ordinary visible search UI
     * and the ordinary title/detail navigation path. It fetches title metadata only; viewer
     * image discovery remains owned by the later physical episode click.
     */
    private boolean appendExactNtkWorkIdResult(CustomHttpClient client, ArrayList<Title> target,
                                               int targetBaseMode) throws Exception {
        String workId = ntkExactWorkIdQuery(query);
        if(workId.length() == 0 || targetBaseMode != base_webtoon && targetBaseMode != base_comic)
            return false;
        String segment = targetBaseMode == base_comic ? "manhwa" : "webtoon";
        String path = "/" + segment + "/" + workId;
        CustomHttpClient.PageResponse pageResponse = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
        throwIfCloudflareChallenge(client, pageResponse.code, pageResponse.body, path);
        throwIfNtkRequestUnavailable(client, pageResponse.code, path);
        if(pageResponse.code < 200 || pageResponse.code >= 400)
            return false;

        Document document = Jsoup.parse(pageResponse.body == null ? "" : pageResponse.body);
        Element titleMeta = document.selectFirst("meta[property=og:title], meta[name=twitter:title]");
        String name = ntkExactDocumentTitle(titleMeta == null ? "" : titleMeta.attr("content"));
        if(name.length() == 0)
            return false;
        Element imageMeta = document.selectFirst("meta[property=og:image], meta[name=twitter:image]");
        String thumb = imageMeta == null ? "" : imageMeta.attr("content").trim();
        int numericId;
        try {
            numericId = Integer.parseInt(workId);
        } catch (NumberFormatException ignored) {
            numericId = stableNtkSourceId(workId);
        }
        if(numericId <= 0)
            return false;
        Title exact = new Title(name, thumb, "", new ArrayList<>(), "", numericId, targetBaseMode);
        exact.setPath(path);
        exact.setSourceSite("ntk");
        target.add(exact);
        Log.d(TAG, "ntk_exact_work_id_search path=" + path + ",name=" + name);
        return true;
    }

    private static String ntkExactWorkIdQuery(String value) {
        Matcher matcher = NTK_EXACT_WORK_ID_QUERY.matcher(value == null ? "" : value);
        if(!matcher.matches())
            return "";
        String workId = matcher.group(1);
        if(workId.matches("[0-9]+") && parsePositiveInt(workId) <= 0)
            return "";
        return workId;
    }

    private static String ntkExactDocumentTitle(String value) {
        String title = value == null ? "" : value.trim();
        int siteSeparator = title.lastIndexOf('|');
        if(siteSeparator > 0)
            title = title.substring(0, siteSeparator).trim();
        int authorSeparator = title.lastIndexOf(" - ");
        if(authorSeparator > 0)
            title = title.substring(0, authorSeparator).trim();
        return title;
    }

    static String ntkExactWorkIdQueryForTest(String value) {
        return ntkExactWorkIdQuery(value);
    }

    static String ntkExactDocumentTitleForTest(String value) {
        return ntkExactDocumentTitle(value);
    }

    private int appendNtkClassificationKeywordResults(ArrayList<Title> target, int targetBaseMode, int limit) {
        if(mode != 0 || appContext == null || target == null)
            return 0;
        int before = target.size();
        int pageSize = limit > 0 ? limit : NTK_KEYWORD_PAGE_SIZE;
        int offset = Math.max(0, page - 1) * pageSize;
        if(targetBaseMode == base_auto || targetBaseMode == base_webtoon) {
            appendUnique(target, ClassificationDbStore.searchTitles(
                    appContext, false, "ntk", query, offset, pageSize));
        }
        if((targetBaseMode == base_auto || targetBaseMode == base_comic) &&
                (limit <= 0 || target.size() - before < limit)) {
            int remaining = limit > 0 ? Math.max(0, limit - (target.size() - before)) : pageSize;
            appendUnique(target, ClassificationDbStore.searchTitles(
                    appContext, true, "ntk", query, offset, remaining));
        }
        int added = target.size() - before;
        if(added > 0) {
            long startedAt = PerfTrace.start("ntk_search_classification_fallback_ms");
            traceSearchMetric("ntk_search_classification_fallback_ms", startedAt,
                    ",baseMode=" + targetBaseMode + ",count=" + added);
        }
        return added;
    }

    private boolean appendNextNtkKeywordApiPage(CustomHttpClient client, ArrayList<Title> target, int targetBaseMode, int limit) throws Exception {
        int currentPage = page;
        PageTitles pageTitles = fetchNtkHybridKeywordResults(client, targetBaseMode, limit, currentPage);
        int added = appendUniquePageTitles(target, pageTitles.titles);
        page++;
        if(added > 0)
            return pageTitles.hasMoreKnown ? !pageTitles.hasMore : true;
        if(pageTitles.hasMoreKnown)
            return !pageTitles.hasMore;
        page = currentPage;
        return true;
    }

    private static boolean shouldUseNtkKeywordApi(boolean ntkClient, int mode) {
        return ntkClient && mode == 0;
    }

    private static boolean isNtkKeywordApiEmptyAuthoritative(int successfulPaths, int pathCount, int total, int parsedCandidates) {
        return pathCount > 0 && successfulPaths == pathCount && total == 0 && parsedCandidates == 0;
    }

    static boolean isNtkKeywordApiEmptyAuthoritativeForTest(int successfulPaths, int pathCount, int total, int parsedCandidates) {
        return isNtkKeywordApiEmptyAuthoritative(successfulPaths, pathCount, total, parsedCandidates);
    }

    static boolean shouldUseNtkKeywordApiForTest(boolean ntkClient, int mode) {
        return shouldUseNtkKeywordApi(ntkClient, mode);
    }

    private boolean appendNextNtkSearchPage(CustomHttpClient client, ArrayList<Title> target, int targetBaseMode, int limit) throws Exception {
        String path = ntkSearchNextPath;
        if(path == null || path.length() == 0)
            path = ntkSearchPath(query, targetBaseMode, page);
        PageTitles pageTitles = fetchNtkSearchResults(client, path, targetBaseMode, limit, page);
        int added = appendUniquePageTitles(target, pageTitles.titles);
        ntkSearchNextPath = pageTitles.nextPath;
        page++;
        return pageTitles.nextPath == null || pageTitles.nextPath.length() == 0 || added == 0;
    }

    private PageTitles fetchNtkSearchResults(CustomHttpClient client, String path, int targetBaseMode, int limit, int currentPage) throws Exception {
        return cachedNtkPageTitles(client, "search", path, targetBaseMode, limit, currentPage,
                () -> fetchNtkSearchResultsUncached(client, path, targetBaseMode, limit, currentPage));
    }

    private PageTitles fetchNtkSearchResultsUncached(CustomHttpClient client, String path, int targetBaseMode, int limit, int currentPage) throws Exception {
        return fetchNtkHtmlSearchResultsPage(client, path, targetBaseMode, limit, currentPage);
    }

    private PageTitles fetchNtkHtmlSearchResultsPage(CustomHttpClient client, String path, int targetBaseMode, int limit, int currentPage) throws Exception {
        long fetchStartedAt = PerfTrace.start("ntk_search_html_fetch_ms");
        boolean suppressWebViewFallback = shouldSuppressNtkHtmlSearchWebViewFallback(client, path);
        CustomHttpClient.PageResponse page;
        try {
            page = fetchSearchPage(client, path, suppressWebViewFallback);
        } catch (Exception e) {
            traceSearchMetric("ntk_search_html_error_ms", fetchStartedAt,
                    ",path=" + ntkMetricPath(path)
                            + ",searchNoWebView=" + suppressWebViewFallback
                            + ",type=" + e.getClass().getSimpleName());
            throw e;
        }
        traceSearchMetric("ntk_search_html_fetch_ms", fetchStartedAt,
                ",path=" + ntkMetricPath(path)
                        + ",searchNoWebView=" + suppressWebViewFallback
                        + ",fromCache=" + page.fromCache
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
        throwIfCloudflareChallenge(client, page.code, page.body, path);
        throwIfNtkRequestUnavailable(client, page.code, path);
        if(page.code >= 400)
            throw new Exception("NTK search failed: " + page.code);
        long parseStartedAt = PerfTrace.start("ntk_search_html_parse_ms");
        ArrayList<Title> parsed = new ArrayList<>();
        Document d = null;
        boolean fastKeywordSearch = client != null && client.isNtk() && mode == 0 && path != null && path.startsWith("/search?");
        if(fastKeywordSearch) {
            if(targetBaseMode == base_auto || targetBaseMode == base_webtoon)
                appendUnique(parsed, MainPageWebtoon.parseWolfSearchHtmlFast(page.body, base_webtoon, limit, "ntk"));
            if(targetBaseMode == base_auto || targetBaseMode == base_comic)
                appendUnique(parsed, MainPageWebtoon.parseWolfSearchHtmlFast(page.body, base_comic, limit, "ntk"));
        }
        if(parsed.size() == 0) {
            d = Jsoup.parse(page.body);
            if(targetBaseMode == base_auto || targetBaseMode == base_webtoon)
                appendUnique(parsed, MainPageWebtoon.parseWolfTitles(d, base_webtoon, limit));
            if(targetBaseMode == base_auto || targetBaseMode == base_comic)
                appendUnique(parsed, MainPageWebtoon.parseWolfTitles(d, base_comic, limit));
            for(Title title : parsed)
                if(title != null)
                    title.setSourceSite("ntk");
        }
        int rawCount = parsed.size();
        if(client != null && client.isNtk() && mode == 0)
            parsed = filterNtkKeywordResults(parsed, query, limit);
        String nextPath = null;
        if(!fastKeywordSearch) {
            if(d == null)
                d = Jsoup.parse(page.body);
            nextPath = findNtkNextPagePath(d, path, currentPage + 1);
        }
        traceSearchMetric("ntk_search_html_parse_ms", parseStartedAt,
                ",path=" + ntkMetricPath(path)
                        + ",fast=" + fastKeywordSearch
                        + ",raw=" + rawCount
                        + ",count=" + parsed.size());
        return new PageTitles(parsed, nextPath);
    }

    static String ntkSearchPathForTest(String query, int targetBaseMode, int page) {
        return ntkSearchPath(query, targetBaseMode, page);
    }

    private static String ntkSearchPath(String query, int targetBaseMode, int page) {
        String encoded = percentEncode(query, Charset.forName("UTF-8"));
        ArrayList<String> params = new ArrayList<>();
        params.add("q=" + encoded);
        params.add("field=title");
        params.add("match=contains");
        if(targetBaseMode == base_comic)
            params.add("kind=manhwa");
        else if(targetBaseMode == base_webtoon)
            params.add("kind=webtoon");
        if(page > 1)
            params.add("page=" + page);
        return "/search?" + String.join("&", params);
    }

    static String wfwfKeywordSearchPathForTest(String query) {
        return wfwfKeywordSearchPath(query);
    }

    private static String wfwfKeywordSearchPath(String query) {
        return "/search.html?q=" + percentEncode(query, Charset.forName("EUC-KR"));
    }

    private void appendNtkSiteSearchResults(CustomHttpClient client, ArrayList<Title> target, int targetBaseMode, int limit) throws Exception {
        String encoded = percentEncode(query, Charset.forName("UTF-8"));
        if(targetBaseMode == base_webtoon) {
            int before = target.size();
            appendNtkApiKeywordSearchResults(client, target, targetBaseMode, limit);
            if(target.size() > before)
                return;
        }
        appendNtkHtmlSearchResults(client, target, targetBaseMode, limit, encoded);
    }

    private void appendNtkCommonSearchResults(CustomHttpClient client, ArrayList<Title> target, int limit) throws Exception {
        appendNtkApiKeywordSearchResults(client, target, base_webtoon, limit);
        if(target.size() > 0)
            return;
        String encoded = percentEncode(query, Charset.forName("UTF-8"));
        String[] paths = {
                "/search?q=" + encoded,
                "/bbs/search.php?stx=" + encoded,
                "/bbs/search.php?sfl=wr_subject&stx=" + encoded
        };
        Exception lastError = null;
        for(String path : paths) {
            try {
                CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
                throwIfCloudflareChallenge(client, page.code, page.body, path);
                if(page.code >= 400)
                    throw new Exception("NTK search failed: " + page.code);
                Document d = Jsoup.parse(page.body);
                appendUnique(target, MainPageWebtoon.parseWolfTitles(d, base_webtoon, limit));
                appendUnique(target, MainPageWebtoon.parseWolfTitles(d, base_comic, limit));
                return;
            } catch (Exception e) {
                lastError = e;
            }
        }
        if(lastError != null)
            throw lastError;
    }

    private void appendNtkHtmlSearchResults(CustomHttpClient client, ArrayList<Title> target, int targetBaseMode, int limit, String encoded) throws Exception {
        String kind = targetBaseMode == base_comic ? "manhwa" : "webtoon";
        String searchParams = "q=" + encoded + "&field=title&match=contains";
        String[] paths = {
                "/search?" + searchParams + "&kind=" + kind,
                "/search?" + searchParams,
                "/bbs/search.php?stx=" + encoded,
                "/bbs/search.php?sfl=wr_subject&stx=" + encoded
        };
        Exception lastError = null;
        for(String path : paths) {
            try {
                appendWebtoonResults(client, target, path, limit);
                return;
            } catch (Exception e) {
                lastError = e;
            }
        }
        if(lastError != null)
            throw lastError;
    }

    private void appendNtkApiKeywordSearchResults(CustomHttpClient client, ArrayList<Title> target, int targetBaseMode, int limit) throws Exception {
        if(client == null || targetBaseMode != base_webtoon)
            return;
        int pageSize = limit > 0 ? Math.min(NTK_KEYWORD_PAGE_SIZE, Math.max(10, limit)) : NTK_KEYWORD_PAGE_SIZE;
        String path = "/api/works?keyword=" + percentEncode(query, Charset.forName("UTF-8"))
                + "&page=1&pageSize=" + pageSize;
        PageTitles parsed = cachedNtkPageTitles(client, "keyword", path, targetBaseMode, limit, 1, () -> {
            CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
            throwIfCloudflareChallenge(client, page.code, page.body, path);
            if(page.code >= 400)
                return new PageTitles(new ArrayList<>(), null);
            return parseNtkApiPage(page.body, path, targetBaseMode, 0, 1);
        });
        ArrayList<Title> filtered = filterNtkKeywordResults(parsed.titles, query, limit);
        appendUnique(target, filtered);
    }

    private PageTitles fetchNtkHybridKeywordResults(CustomHttpClient client, int targetBaseMode, int limit, int currentPage) throws Exception {
        if(client == null)
            return new PageTitles(new ArrayList<>(), null, true, false, 0);
        String htmlPath = ntkSearchPath(query, targetBaseMode, currentPage);
        CustomHttpClient.RequestGroup requestGroup = client.currentRequestGroup();
        NtkHybridPart apiPart = null;
        NtkHybridPart htmlPart = null;
        CompletionService<NtkHybridPart> completion = AppDispatchers.ioCompletionService();
        ArrayList<Future<NtkHybridPart>> running = new ArrayList<>();
        try {
            running.add(completion.submit(AppDispatchers.safeCallable(() ->
                    fetchNtkHybridPart(client, requestGroup, "api",
                            () -> fetchNtkKeywordApiResults(client, targetBaseMode, limit, currentPage)))));
            running.add(completion.submit(AppDispatchers.safeCallable(() ->
                    fetchNtkHybridPart(client, requestGroup, "html",
                            () -> fetchNtkSearchResults(client, htmlPath, targetBaseMode, limit, currentPage)))));
            for(int i = 0; i < running.size(); i++) {
                NtkHybridPart part = completion.take().get();
                if(part == null)
                    continue;
                if("api".equals(part.kind))
                    apiPart = part;
                else if("html".equals(part.kind))
                    htmlPart = part;
                if(part.pageTitles.titles.size() > 0)
                    publishPartialResults(part.pageTitles.titles);
            }
        } finally {
            for(Future<NtkHybridPart> future : running)
                if(future != null && !future.isDone())
                    future.cancel(true);
        }
        if(apiPart == null)
            apiPart = new NtkHybridPart("api", new PageTitles(new ArrayList<>(), null, true, false, 0), false);
        if(htmlPart == null)
            htmlPart = new NtkHybridPart("html", new PageTitles(new ArrayList<>(), null, true, false, 0), false);
        PageTitles merged = mergeNtkHybridKeywordResults(htmlPart.pageTitles, apiPart.pageTitles);
        Log.d(TAG, "ntk_search_hybrid_keyword apiSuccess=" + apiPart.success
                + ",apiCaptcha=" + apiPart.captchaRequired
                + ",apiCount=" + apiPart.pageTitles.titles.size()
                + ",htmlSuccess=" + htmlPart.success
                + ",htmlCaptcha=" + htmlPart.captchaRequired
                + ",htmlCount=" + htmlPart.pageTitles.titles.size()
                + ",merged=" + merged.titles.size()
                + ",htmlPath=" + ntkMetricPath(htmlPath));
        if(merged.titles.size() == 0
                && apiPart.captchaRequired
                && htmlPart.captchaRequired)
            throw new NtkCaptchaRequiredException();
        return merged;
    }

    private void publishPartialResults(ArrayList<Title> titles) {
        PartialResultListener listener = partialResultListener;
        if(listener == null || titles == null || titles.size() == 0)
            return;
        bindTitlesToSourceSite(titles, activeFetchSourceSite);
        listener.onPartialResults(new ArrayList<>(titles));
    }

    private static String sourceSiteForClient(CustomHttpClient client) {
        return client != null && client.isNtk() ? "ntk" : "wfwf";
    }

    private static void bindTitlesToSourceSite(List<Title> titles, String sourceSite) {
        if(titles == null)
            return;
        String normalized = "ntk".equalsIgnoreCase(sourceSite) ? "ntk" : "wfwf";
        for(Title title : titles)
            if(title != null)
                title.setSourceSite(normalized);
    }

    static void bindTitlesToSourceSiteForTest(List<Title> titles, String sourceSite) {
        bindTitlesToSourceSite(titles, sourceSite);
    }

    private NtkHybridPart fetchNtkHybridPart(CustomHttpClient client, CustomHttpClient.RequestGroup requestGroup,
                                             String kind, NtkHybridLoader loader) {
        try {
            PageTitles pageTitles = requestGroup == null
                    ? loader.load()
                    : client.runWithRequestGroup(requestGroup, () -> loader.load());
            return new NtkHybridPart(kind, pageTitles, true);
        } catch (Exception e) {
            if(isCaptchaRequiredException(e))
                return new NtkHybridPart(kind, new PageTitles(new ArrayList<>(), null, true, false, 0), false, true);
            if((requestGroup == null || !requestGroup.isCancelled()) && shouldReportSearchFailure(e))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return new NtkHybridPart(kind, new PageTitles(new ArrayList<>(), null, true, false, 0), false);
        }
    }

    private static PageTitles mergeNtkHybridKeywordResults(PageTitles htmlResults, PageTitles apiResults) {
        if(htmlResults == null)
            htmlResults = new PageTitles(new ArrayList<>(), null, true, false, 0);
        if(apiResults == null)
            apiResults = new PageTitles(new ArrayList<>(), null, true, false, 0);
        ArrayList<Title> merged = new ArrayList<>();
        appendUnique(merged, htmlResults.titles);
        appendUnique(merged, apiResults.titles);
        boolean hasMore = htmlResults.hasMore || (apiResults.hasMore && apiResults.titles.size() > 0);
        boolean hasMoreKnown = htmlResults.hasMoreKnown || apiResults.hasMoreKnown;
        String nextPath = htmlResults.nextPath != null && htmlResults.nextPath.length() > 0
                ? htmlResults.nextPath : (apiResults.titles.size() > 0 ? apiResults.nextPath : null);
        int total = Math.max(0, htmlResults.totalCount) + Math.max(0, apiResults.totalCount);
        return new PageTitles(merged, nextPath, hasMoreKnown, hasMore, total);
    }

    static ArrayList<Title> mergeNtkHybridKeywordTitlesForTest(ArrayList<Title> htmlTitles, ArrayList<Title> apiTitles) {
        return mergeNtkHybridKeywordResults(new PageTitles(htmlTitles, null, true, false, 0),
                new PageTitles(apiTitles, null, true, false, 0)).titles;
    }

    private PageTitles fetchNtkKeywordApiResults(CustomHttpClient client, int targetBaseMode, int limit, int currentPage) throws Exception {
        ArrayList<String> paths = ntkKeywordApiPaths(query, targetBaseMode, currentPage, limit);
        if(client == null || paths.size() == 0)
            return new PageTitles(new ArrayList<>(), null);
        long totalStartedAt = PerfTrace.start("ntk_search_api_total_ms");
        ArrayList<Title> titles = new ArrayList<>();
        boolean hasMore = false;
        int total = 0;
        int successfulPaths = 0;
        int parsedCandidates = 0;
        String singleNextPath = null;
        boolean captchaRequired = false;
        if(shouldFetchNtkKeywordApiPathsInParallel(paths)) {
            CompletionService<NtkApiPathResult> completion = AppDispatchers.ioCompletionService();
            ArrayList<Future<NtkApiPathResult>> running = new ArrayList<>();
            CustomHttpClient.RequestGroup requestGroup = client.currentRequestGroup();
            try {
                for(String path : paths)
                    running.add(completion.submit(AppDispatchers.safeCallable(() -> requestGroup == null
                            ? fetchNtkKeywordApiPathResult(client, path, targetBaseMode, limit, currentPage, totalStartedAt)
                            : client.runWithRequestGroup(requestGroup, () -> fetchNtkKeywordApiPathResult(
                                    client, path, targetBaseMode, limit, currentPage, totalStartedAt)))));
                for(int i = 0; i < running.size(); i++) {
                    NtkApiPathResult result = completion.take().get();
                    if(result == null)
                        continue;
                    if(result.success)
                        successfulPaths++;
                    captchaRequired = captchaRequired || result.captchaRequired;
                    parsedCandidates += Math.max(0, result.parsedCount);
                    appendUnique(titles, result.pageTitles.titles);
                    hasMore = hasMore || result.pageTitles.hasMore;
                    total += Math.max(0, result.pageTitles.totalCount);
                }
            } finally {
                for(Future<NtkApiPathResult> future : running)
                    if(future != null && !future.isDone())
                        future.cancel(true);
            }
        } else {
            NtkApiPathResult result = fetchNtkKeywordApiPathResult(client, paths.get(0), targetBaseMode, limit, currentPage, totalStartedAt);
            if(result.success)
                successfulPaths++;
            captchaRequired = result.captchaRequired;
            parsedCandidates += Math.max(0, result.parsedCount);
            appendUnique(titles, result.pageTitles.titles);
            hasMore = result.pageTitles.hasMore;
            total = Math.max(0, result.pageTitles.totalCount);
            singleNextPath = result.pageTitles.nextPath;
        }
        traceSearchMetric("ntk_search_api_total_ms", totalStartedAt,
                ",paths=" + paths.size()
                        + ",success=" + successfulPaths
                        + ",parsedCandidates=" + parsedCandidates
                        + ",count=" + titles.size()
                        + ",total=" + total);
        if(titles.size() == 0 && (captchaRequired || successfulPaths == 0 && client.hasRecentCloudflareChallenge()))
            throw new NtkCaptchaRequiredException();
        if(titles.size() == 0)
            return new PageTitles(new ArrayList<>(), null,
                    isNtkKeywordApiEmptyAuthoritative(successfulPaths, paths.size(), total, parsedCandidates),
                    false, total);
        return new PageTitles(titles, singleNextPath, true, NtkKeywordSearchPolicy.apiHasMore(paths.size(), hasMore), total);
    }

    private NtkApiPathResult fetchNtkKeywordApiPathResult(CustomHttpClient client, String path, int targetBaseMode,
                                                          int limit, int currentPage, long totalStartedAt) {
        return cachedNtkKeywordApiPathResult(client, path, targetBaseMode, limit, currentPage,
                () -> fetchNtkKeywordApiPathResultUncached(client, path, targetBaseMode, limit, currentPage, totalStartedAt));
    }

    private NtkApiPathResult fetchNtkKeywordApiPathResultUncached(CustomHttpClient client, String path, int targetBaseMode,
                                                                  int limit, int currentPage, long totalStartedAt) {
        try {
            long fetchStartedAt = PerfTrace.start("ntk_search_api_fetch_ms");
            boolean suppressWebViewFallback = shouldSuppressNtkKeywordApiWebViewFallback(client, path);
            CustomHttpClient.PageResponse page = fetchSearchPage(client, path, suppressWebViewFallback);
            Log.d(TAG, "ntk_search_api_fetch path=" + ntkMetricPath(path)
                    + ",searchNoWebView=" + suppressWebViewFallback
                    + ",fromCache=" + page.fromCache
                    + ",code=" + page.code
                    + ",bodyLen=" + (page.body == null ? 0 : page.body.length())
                    + ",snippet=" + ntkLogSnippet(page.body));
            traceSearchMetric("ntk_search_api_fetch_ms", fetchStartedAt,
                    ",path=" + ntkMetricPath(path)
                            + ",searchNoWebView=" + suppressWebViewFallback
                            + ",fromCache=" + page.fromCache
                            + ",code=" + page.code
                            + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
            throwIfCloudflareChallenge(client, page.code, page.body, path);
            throwIfNtkRequestUnavailable(client, page.code, path);
            if(page.code >= 400)
                return new NtkApiPathResult(path, new PageTitles(new ArrayList<>(), null), false, 0);
            int parsedBaseMode = path.startsWith("/api/manhwa-list") ? base_comic : base_webtoon;
            long parseStartedAt = PerfTrace.start("ntk_search_api_parse_ms");
            PageTitles parsed = parseNtkApiPage(page.body, path, parsedBaseMode, 0, currentPage);
            int perKindLimit = perNtkKeywordKindLimit(targetBaseMode, limit);
            ArrayList<Title> filtered = filterLaunchableNtkKeywordResults(
                    filterNtkKeywordResults(parsed.titles, query, perKindLimit), perKindLimit);
            if(filtered.size() == 0 && parsed.titles.size() > 0 && parsed.totalCount > parsed.titles.size()) {
                ArrayList<Title> scanned = scanNtkKeywordApiPagesForMatches(
                        client, path, parsedBaseMode, targetBaseMode, limit, parsed.totalCount, parsed.titles.size());
                if(scanned.size() > 0)
                    filtered = scanned;
            }
            Log.d(TAG, "ntk_search_api_parse path=" + ntkMetricPath(path)
                    + ",parsed=" + parsed.titles.size()
                    + ",filtered=" + filtered.size()
                    + ",total=" + parsed.totalCount);
            traceSearchMetric("ntk_search_api_parse_ms", parseStartedAt,
                    ",path=" + ntkMetricPath(path)
                            + ",parsed=" + parsed.titles.size()
                            + ",filtered=" + filtered.size()
                            + ",total=" + parsed.totalCount);
            return new NtkApiPathResult(path, new PageTitles(filtered, parsed.nextPath,
                    parsed.hasMoreKnown, parsed.hasMore, parsed.totalCount), true, parsed.titles.size());
        } catch (Exception e) {
            Log.d(TAG, "ntk_search_api_error path=" + ntkMetricPath(path)
                    + ",type=" + e.getClass().getSimpleName()
                    + ",message=" + e.getMessage(), e);
            traceSearchMetric("ntk_search_api_error_ms", totalStartedAt,
                    ",path=" + ntkMetricPath(path)
                            + ",type=" + e.getClass().getSimpleName());
            if(isCaptchaRequiredException(e))
                return new NtkApiPathResult(path, new PageTitles(new ArrayList<>(), null), false, 0, true);
            if(shouldReportSearchFailure(e))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return new NtkApiPathResult(path, new PageTitles(new ArrayList<>(), null), false, 0);
        }
    }

    private ArrayList<Title> scanNtkKeywordApiPagesForMatches(CustomHttpClient client, String firstPath,
                                                              int parsedBaseMode, int targetBaseMode, int limit,
                                                              int totalCount, int firstPageCount) {
        ArrayList<Title> matches = new ArrayList<>();
        if(client == null || firstPath == null || firstPageCount <= 0 || totalCount <= firstPageCount)
            return matches;
        int perKindLimit = perNtkKeywordKindLimit(targetBaseMode, limit);
        int pageSize = parsePositiveInt(rawQueryValue(firstPath, "pageSize"));
        if(pageSize <= 0)
            pageSize = Math.max(firstPageCount, NTK_KEYWORD_PAGE_SIZE);
        int maxPages = Math.min(100, Math.max(1, (totalCount + pageSize - 1) / pageSize));
        int scanPage = 2;
        while(scanPage <= maxPages) {
            if(perKindLimit > 0 && matches.size() >= perKindLimit)
                break;
            CompletionService<NtkApiPathResult> completion = AppDispatchers.ioCompletionService();
            ArrayList<Future<NtkApiPathResult>> running = new ArrayList<>();
            CustomHttpClient.RequestGroup requestGroup = client.currentRequestGroup();
            int submitted = 0;
            try {
                while(scanPage <= maxPages && submitted < NTK_KEYWORD_SCAN_PARALLELISM) {
                    final int pageNumber = scanPage++;
                    final String scanPath = replaceNtkQueryParam(firstPath, "page", String.valueOf(pageNumber));
                    running.add(completion.submit(AppDispatchers.safeCallable(() -> requestGroup == null
                            ? fetchNtkKeywordScanPageResult(client, scanPath, parsedBaseMode, pageNumber)
                            : client.runWithRequestGroup(requestGroup, () -> fetchNtkKeywordScanPageResult(
                                    client, scanPath, parsedBaseMode, pageNumber)))));
                    submitted++;
                }
                boolean batchHasMore = true;
                for(int i = 0; i < submitted; i++) {
                    NtkApiPathResult result = completion.take().get();
                    if(result == null || !result.success) {
                        batchHasMore = false;
                        continue;
                    }
                    PageTitles parsed = result.pageTitles;
                    ArrayList<Title> filtered = filterLaunchableNtkKeywordResults(
                            filterNtkKeywordResults(parsed.titles, query, perKindLimit), perKindLimit);
                    appendUnique(matches, filtered);
                    Log.d(TAG, "ntk_search_api_scan path=" + ntkMetricPath(result.path)
                            + ",parsed=" + parsed.titles.size()
                            + ",filtered=" + filtered.size()
                            + ",matches=" + matches.size());
                    batchHasMore = batchHasMore && parsed.hasMore;
                    if(perKindLimit > 0 && matches.size() >= perKindLimit)
                        break;
                }
                if(!batchHasMore || matches.size() > 0)
                    break;
            } catch (Exception e) {
                Log.d(TAG, "ntk_search_api_scan_error path=" + ntkMetricPath(firstPath)
                        + ",type=" + e.getClass().getSimpleName()
                        + ",message=" + e.getMessage());
                break;
            } finally {
                for(Future<NtkApiPathResult> future : running)
                    if(future != null && !future.isDone())
                        future.cancel(true);
            }
        }
        return matches;
    }

    private NtkApiPathResult fetchNtkKeywordScanPageResult(CustomHttpClient client, String scanPath,
                                                           int parsedBaseMode, int scanPage) {
        try {
            CustomHttpClient.PageResponse page = fetchSearchPage(client, scanPath, true);
            throwIfCloudflareChallenge(client, page.code, page.body, scanPath);
            throwIfNtkRequestUnavailable(client, page.code, scanPath);
            if(page.code >= 400)
                return new NtkApiPathResult(scanPath, new PageTitles(new ArrayList<>(), null), false, 0);
            PageTitles parsed = parseNtkApiPage(page.body, scanPath, parsedBaseMode, 0, scanPage);
            return new NtkApiPathResult(scanPath, parsed, true, parsed.titles.size());
        } catch (Exception e) {
            Log.d(TAG, "ntk_search_api_scan_error path=" + ntkMetricPath(scanPath)
                    + ",type=" + e.getClass().getSimpleName()
                    + ",message=" + e.getMessage());
            return new NtkApiPathResult(scanPath, new PageTitles(new ArrayList<>(), null), false, 0,
                    isCaptchaRequiredException(e));
        }
    }

    private static ArrayList<Title> filterLaunchableNtkKeywordResults(ArrayList<Title> titles, int limit) {
        ArrayList<Title> launchable = new ArrayList<>();
        if(titles == null)
            return launchable;
        for(Title title : titles) {
            if(!hasNumericNtkTitlePath(title))
                continue;
            launchable.add(title);
            if(limit > 0 && launchable.size() >= limit)
                break;
        }
        return launchable;
    }

    private static boolean hasNumericNtkTitlePath(Title title) {
        if(title == null)
            return false;
        String path = title.getPath();
        if(path == null)
            return false;
        Matcher matcher = Pattern.compile("^/(?:webtoon|manhwa)/(\\d+)(?:[/?#].*)?$").matcher(path.trim());
        return matcher.matches();
    }

    private NtkApiPathResult cachedNtkKeywordApiPathResult(CustomHttpClient client, String path, int targetBaseMode,
                                                           int limit, int currentPage, NtkApiPathLoader loader) {
        if(client == null || !client.isNtk())
            return loader.load();
        String key = targetBaseMode + ":" + limit + ":" + currentPage + ":" + path;
        long now = System.currentTimeMillis();
        synchronized (NTK_KEYWORD_API_RESULT_CACHE) {
            CachedNtkApiPathResult cached = NTK_KEYWORD_API_RESULT_CACHE.get(key);
            if(cached != null && isNtkResultCacheFresh(cached.loadedAt, now, NTK_RESULT_CACHE_TTL_MS)) {
                long cacheStartedAt = PerfTrace.start("ntk_search_api_cache_ms");
                traceSearchMetric("ntk_search_api_cache_ms", cacheStartedAt,
                        ",path=" + ntkMetricPath(path)
                                + ",parsedCandidates=" + cached.result.parsedCount
                                + ",count=" + cached.result.pageTitles.titles.size()
                                + ",total=" + cached.result.pageTitles.totalCount);
                return copyNtkApiPathResult(cached.result);
            }
        }
        NtkApiPathResult loaded = loader.load();
        if(loaded != null && loaded.success) {
            synchronized (NTK_KEYWORD_API_RESULT_CACHE) {
                if(NTK_KEYWORD_API_RESULT_CACHE.size() >= NTK_KEYWORD_API_CACHE_MAX_ENTRIES)
                    NTK_KEYWORD_API_RESULT_CACHE.clear();
                NTK_KEYWORD_API_RESULT_CACHE.put(key,
                        new CachedNtkApiPathResult(copyNtkApiPathResult(loaded), System.currentTimeMillis()));
            }
        }
        return loaded;
    }

    private PageTitles cachedWfwfPageTitles(CustomHttpClient client, String kind, String path,
                                            PageTitleLoader loader) throws Exception {
        if(client == null || client.isNtk())
            return loader.load();
        String key = kind + ':' + client.getUrl(path) + ':' + path;
        long now = System.currentTimeMillis();
        synchronized (WFWF_RESULT_CACHE) {
            CachedPageTitles cached = WFWF_RESULT_CACHE.get(key);
            if(cached != null && isNtkResultCacheFresh(cached.loadedAt, now, WFWF_RESULT_CACHE_TTL_MS)) {
                long cacheStartedAt = PerfTrace.start("wfwf_search_result_cache_ms");
                traceSearchMetric("wfwf_search_result_cache_ms", cacheStartedAt,
                        ",path=" + ntkMetricPath(path)
                                + ",count=" + cached.pageTitles.titles.size());
                return copyPageTitles(cached.pageTitles);
            }
        }
        PageTitles loaded = loader.load();
        synchronized (WFWF_RESULT_CACHE) {
            if(WFWF_RESULT_CACHE.size() >= WFWF_RESULT_CACHE_MAX_ENTRIES)
                WFWF_RESULT_CACHE.clear();
            WFWF_RESULT_CACHE.put(key, new CachedPageTitles(copyPageTitles(loaded), System.currentTimeMillis()));
        }
        return loaded;
    }

    private static NtkApiPathResult copyNtkApiPathResult(NtkApiPathResult source) {
        if(source == null)
            return null;
        return new NtkApiPathResult(source.path, copyPageTitles(source.pageTitles), source.success,
                source.parsedCount, source.captchaRequired);
    }

    private static class NtkApiPathResult {
        final String path;
        final PageTitles pageTitles;
        final boolean success;
        final int parsedCount;
        final boolean captchaRequired;

        NtkApiPathResult(String path, PageTitles pageTitles, boolean success, int parsedCount) {
            this(path, pageTitles, success, parsedCount, false);
        }

        NtkApiPathResult(String path, PageTitles pageTitles, boolean success, int parsedCount, boolean captchaRequired) {
            this.path = path;
            this.pageTitles = pageTitles == null ? new PageTitles(new ArrayList<>(), null) : pageTitles;
            this.success = success;
            this.parsedCount = parsedCount;
            this.captchaRequired = captchaRequired;
        }
    }

    private static class NtkHybridPart {
        final String kind;
        final PageTitles pageTitles;
        final boolean success;
        final boolean captchaRequired;

        NtkHybridPart(String kind, PageTitles pageTitles, boolean success) {
            this(kind, pageTitles, success, false);
        }

        NtkHybridPart(String kind, PageTitles pageTitles, boolean success, boolean captchaRequired) {
            this.kind = kind == null ? "" : kind;
            this.pageTitles = pageTitles == null ? new PageTitles(new ArrayList<>(), null) : pageTitles;
            this.success = success;
            this.captchaRequired = captchaRequired;
        }
    }

    private static String ntkMetricPath(String path) {
        if(path == null)
            return "";
        String value = path.replace(',', ';');
        if(value.length() > 96)
            return value.substring(0, 96);
        return value;
    }

    private static String ntkLogSnippet(String body) {
        if(body == null)
            return "";
        String value = body.replace('\n', ' ').replace('\r', ' ').replace(',', ';').trim();
        if(value.length() > 160)
            return value.substring(0, 160);
        return value;
    }

    static ArrayList<String> ntkKeywordApiPathsForTest(String query, int targetBaseMode, int page, int limit) {
        return ntkKeywordApiPaths(query, targetBaseMode, page, limit);
    }

    static boolean shouldFetchNtkKeywordApiPathsInParallelForTest(ArrayList<String> paths) {
        return shouldFetchNtkKeywordApiPathsInParallel(paths);
    }

    private static boolean shouldFetchNtkKeywordApiPathsInParallel(ArrayList<String> paths) {
        return NtkKeywordSearchPolicy.shouldFetchPathsInParallel(paths);
    }

    static boolean shouldSuppressNtkHtmlSearchWebViewFallbackForTest(boolean ntkClient, String path) {
        return shouldSuppressNtkHtmlSearchWebViewFallback(ntkClient, path);
    }

    private static boolean shouldSuppressNtkHtmlSearchWebViewFallback(CustomHttpClient client, String path) {
        return shouldSuppressNtkHtmlSearchWebViewFallback(client != null && client.isNtk(), path);
    }

    private static boolean shouldSuppressNtkHtmlSearchWebViewFallback(boolean ntkClient, String path) {
        return ntkClient && HttpDocumentPolicy.isNtkApiPath(path);
    }

    static boolean shouldSuppressNtkKeywordApiWebViewFallbackForTest(boolean ntkClient, String path) {
        return shouldSuppressNtkKeywordApiWebViewFallback(ntkClient, path);
    }

    private static boolean shouldSuppressNtkKeywordApiWebViewFallback(CustomHttpClient client, String path) {
        return shouldSuppressNtkKeywordApiWebViewFallback(client != null && client.isNtk(), path);
    }

    private static boolean shouldSuppressNtkKeywordApiWebViewFallback(boolean ntkClient, String path) {
        return ntkClient && HttpDocumentPolicy.isNtkApiPath(path);
    }

    private static ArrayList<String> ntkKeywordApiPaths(String query, int targetBaseMode, int page, int limit) {
        return NtkKeywordSearchPolicy.keywordApiPaths(query, targetBaseMode, page, limit);
    }

    private static int ntkKeywordPageSize(int limit) {
        return NtkKeywordSearchPolicy.keywordPageSize(limit);
    }

    private static int perNtkKeywordKindLimit(int targetBaseMode, int limit) {
        return NtkKeywordSearchPolicy.perKindLimit(targetBaseMode, limit);
    }

    static ArrayList<Title> filterNtkKeywordResultsForTest(ArrayList<Title> titles, String query, int limit) {
        return filterNtkKeywordResults(titles, query, limit);
    }

    private static ArrayList<Title> filterNtkKeywordResults(ArrayList<Title> titles, String query, int limit) {
        return NtkKeywordSearchPolicy.filterResults(titles, query, limit);
    }

    private static boolean matchesNtkKeyword(Title title, String normalizedQuery) {
        return NtkKeywordSearchPolicy.matchesKeyword(title, normalizedQuery);
    }

    private static String normalizeSearchText(String value) {
        return NtkKeywordSearchPolicy.normalizeSearchText(value);
    }

    private static String ntkPath(CustomHttpClient client, String ntkPath, String wolfPath) {
        return client != null && client.isNtk() ? ntkPath : wolfPath;
    }

    private String comicRoot(CustomHttpClient client) {
        return client != null && client.isNtk() ? "/manhwa" : "/cm";
    }

    private boolean appendNextClassificationDbGenreResults(CustomHttpClient client, ArrayList<Title> target, String genre) {
        if(genre == null || genre.trim().length() == 0)
            return true;
        ArrayList<Title> dbResults;
        String sourceSite = client != null && client.isNtk() ? "ntk" : "wfwf";
        if(baseMode == base_comic) {
            classificationDbTotalCount = MainPageWebtoon.getComicClassificationDbGenreCount(genre.trim(), sourceSite);
            dbResults = MainPageWebtoon.getComicClassificationDbTitlesByGenre(genre.trim(), classificationDbOffset, CLASSIFICATION_DB_PAGE_SIZE, sourceSite);
        } else {
            classificationDbTotalCount = MainPageWebtoon.getClassificationDbGenreCount(genre.trim(), sourceSite);
            dbResults = MainPageWebtoon.getClassificationDbTitlesByGenre(genre.trim(), classificationDbOffset, CLASSIFICATION_DB_PAGE_SIZE, sourceSite);
        }
        classificationDbOffset += dbResults.size();
        target.addAll(dbResults);
        return dbResults.size() < CLASSIFICATION_DB_PAGE_SIZE;
    }

    private void appendNewResults(ArrayList<Title> source) {
        if(source == null)
            return;
        for(Title title : source) {
            if(title == null)
                continue;
            String key = titleIdentityKey(title);
            if(seenTitleKeys.add(key))
                result.add(title);
        }
    }

    static String genreFromCategoryPath(String path, int baseMode) {
        if(path == null)
            return "";
        String ntkGenre = rawQueryValue(path, baseMode == base_comic ? "g" : "tag");
        String ntkCategory = rawQueryValue(path, "cat");
        if(baseMode == base_webtoon && "adult".equals(ntkCategory))
            return "성인";
        if(ntkGenre != null && ntkGenre.length() > 0)
            return percentDecode(ntkGenre, Charset.forName("UTF-8")).trim();
        String type1 = rawQueryValue(path, "type1");
        if(!"genre".equalsIgnoreCase(type1))
            return "";
        String type2 = rawQueryValue(path, "type2");
        if(type2 == null)
            return baseMode == base_webtoon ? "성인" : "";
        if(type2.length() == 0)
            return "";
        return percentDecode(type2, Charset.forName("EUC-KR")).trim();
    }

    private static String rawQueryValue(String value, String key) {
        int question = value.indexOf('?');
        String query = question >= 0 ? value.substring(question + 1) : value;
        for(String part : query.split("&")) {
            int equals = part.indexOf('=');
            String name = equals >= 0 ? part.substring(0, equals) : part;
            if(name.equals(key))
                return equals >= 0 ? part.substring(equals + 1) : "";
        }
        return null;
    }

    private static String percentDecode(String value, Charset charset) {
        try {
            return URLDecoder.decode(value, charset.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static String percentEncode(String value, Charset charset) {
        byte[] bytes = value.getBytes(charset);
        StringBuilder encoded = new StringBuilder();
        for(byte b : bytes) {
            int c = b & 0xff;
            if((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~')
                encoded.append((char)c);
            else
                encoded.append('%').append(String.format("%02X", c));
        }
        return encoded.toString();
    }

    private static void throwIfCloudflareChallenge(CustomHttpClient client, int code, String body, String path) throws Exception {
        if(client == null || !client.isCloudflareChallengeResponse(code, body))
            return;
        client.markCloudflareChallenge(resolveChallengeUrl(client, path));
        throw new NtkCaptchaRequiredException();
    }

    private static void throwIfNtkRequestUnavailable(CustomHttpClient client, int code, String path) throws Exception {
        if(code > 0)
            return;
        if(client != null && client.isNtk() && client.hasRecentCloudflareChallenge())
            throw new NtkCaptchaRequiredException();
        throw new Exception("NTK request failed: " + path);
    }

    private static boolean isCaptchaRequiredException(Exception e) {
        if(e instanceof NtkCaptchaRequiredException)
            return true;
        String message = e == null ? null : e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("cloudflare challenge");
    }

    static boolean isCaptchaRequiredExceptionForTest(Exception e) {
        return isCaptchaRequiredException(e);
    }

    private static class NtkCaptchaRequiredException extends Exception {
        NtkCaptchaRequiredException() {
            super("Cloudflare challenge");
        }
    }

    private static String resolveChallengeUrl(CustomHttpClient client, String path) {
        if(path == null || path.length() == 0)
            return null;
        String lower = path.toLowerCase(Locale.ROOT);
        if(lower.startsWith("http://") || lower.startsWith("https://"))
            return path;
        return client.getUrl(path) + path;
    }

    static boolean shouldTreatSearchResponseAsCaptchaForTest(CustomHttpClient client, int code, String body) {
        return client != null && client.isCloudflareChallengeResponse(code, body);
    }

    private static String webtoonStatus(String value) {
        if(value == null) return "";
        String q = value.trim().toLowerCase(Locale.ROOT);
        if(q.equals("연재") || q.equals("연재중") || q.equals("연재웹툰") || q.equals("ing") || q.equals("ongoing")) return "/ing";
        if(q.equals("완결") || q.equals("완결웹툰") || q.equals("end") || q.equals("completed") || q.equals("complete")) return "/end";
        return "";
    }

    private static String webtoonGenrePath(String status, String genre) {
        if(genre != null && genre.trim().equals("성인"))
            return "/" + status + "?type1=genre&o=n";
        return "/" + status + "?type1=genre&type2=" + percentEncode(genre, Charset.forName("EUC-KR")) + "&o=n";
    }

    private static String webtoonDay(String value) {
        if(value == null) return "";
        String q = value.trim().toLowerCase(Locale.ROOT);
        if(q.equals("월") || q.equals("월요") || q.equals("월요일") || q.equals("mon") || q.equals("monday")) return "1";
        if(q.equals("화") || q.equals("화요") || q.equals("화요일") || q.equals("tue") || q.equals("tuesday")) return "2";
        if(q.equals("수") || q.equals("수요") || q.equals("수요일") || q.equals("wed") || q.equals("wednesday")) return "3";
        if(q.equals("목") || q.equals("목요") || q.equals("목요일") || q.equals("thu") || q.equals("thursday")) return "4";
        if(q.equals("금") || q.equals("금요") || q.equals("금요일") || q.equals("fri") || q.equals("friday")) return "5";
        if(q.equals("토") || q.equals("토요") || q.equals("토요일") || q.equals("sat") || q.equals("saturday")) return "6";
        if(q.equals("일") || q.equals("일요") || q.equals("일요일") || q.equals("sun") || q.equals("sunday")) return "7";
        if(q.equals("열흘") || q.equals("10")) return "10";
        if(q.equals("신작") || q.equals("new")) return "new";
        if(q.equals("최신") || q.equals("recent")) return "recent";
        return "";
    }

    private static String alphabetValue(String value) {
        if(value == null) return "";
        String q = value.trim().toLowerCase(Locale.ROOT);
        if(q.equals("a-z") || q.equals("az")) return "a";
        if(q.equals("0-9") || q.equals("09")) return "0";
        return value.trim();
    }

    private static String comicType(String value) {
        if(value == null) return "";
        String q = value.trim().toLowerCase(Locale.ROOT);
        if(q.equals("recent") || q.equals("최신")) return "recent";
        if(q.equals("weekly") || q.equals("주간")) return "10";
        if(q.equals("biweekly") || q.equals("격주")) return "11";
        if(q.equals("monthly") || q.equals("월간")) return "12";
        if(q.equals("irregular") || q.equals("비정기") || q.equals("격월/비정기")) return "13";
        if(q.equals("oneshot") || q.equals("단편")) return "14";
        if(q.equals("uncategorized") || q.equals("미분류")) return "20";
        if(q.equals("completed") || q.equals("complete") || q.equals("완결")) return "16";
        if(q.equals("book") || q.equals("단행본")) return "15";
        return "";
    }

    public ArrayList<Title> getResult(){
        return result;
    }
}
