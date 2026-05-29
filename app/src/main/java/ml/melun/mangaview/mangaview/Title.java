package ml.melun.mangaview.mangaview;
import androidx.annotation.NonNull;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import okhttp3.Response;

import static ml.melun.mangaview.MainApplication.p;


public class Title extends MTitle {
    private static final String TAG = "ViewerPerf";
    private List<Manga> eps = null;
    int bookmark = 0;
    Boolean bookmarked = false;
    String bookmarkLink = "";
    int rc = 0;

    public static final int BATTERY_EMPTY = 0;
    public static final int BATTERY_ONE_QUARTER = 1;
    public static final int BATTERY_HALF = 2;
    public static final int BATTERY_THREE_QUARTER = 3;
    public static final int BATTERY_FULL = 4;
    public static final int LOAD_OK = 0;
    public static final int LOAD_CAPTCHA = 1;
    public static final int LOAD_ERROR = 2;
    private static final long PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;


    public Title(String n, String t, String a, List<String> tg, String r, int id, int baseMode) {
        super(n, id, t, a, tg, r, baseMode);
    }

    public String getUrl(){
        if(shouldUseNtkUrl())
            return ntkTitlePath(ntkSegment());
        if(isComicWolfSource())
            return "/cl?toon=" + id;
        if(isWebtoonWolfSource())
            return "/list?toon=" + id;
        return '/'+baseModeStr(baseMode)+'/'+ id;
    }


    public Title(MTitle title){
        super(title.getName(), title.getId(), title.getThumb(), title.getAuthor(), title.getTags(), title.getRelease(), title.getBaseMode());
        setPath(title.getPath());
        setSourceSite(title.getSourceSite());
        setNtkStatusLabel(title.getNtkStatusLabel());
        setResumeNtkEpisodePath(title.getResumeNtkEpisodePath());
        setReadingProgress(title.getBookmarkEpisodeId(), title.getBookmarkEpisodeIndex(), title.getEpisodeCount());
        bookmark = title.getBookmarkEpisodeId();
    }

    @NonNull
    @Override
    public String toString() {
        return super.toString()  + " . " + eps;
    }

    public List<Manga> getEps(){
        return eps;
    }

    public Boolean getBookmarked() {
        if(bookmarked==null) return false;
        return bookmarked;
    }

    public int fetchEps(CustomHttpClient client) {
        if(shouldFetchNtkEpisodes(client))
            return fetchNtkEps(client);
        if(isComicWolfSource())
            return fetchWolfEps(client, "/cl?toon=", "/cv?toon=");
        if(isWebtoonWolfSource())
            return fetchWolfEps(client);

        for(int attempt = 0; attempt <= MAX_TIMEOUT_RETRIES; attempt++) {
            try {
                Response r = client.mget('/'+baseModeStr(baseMode)+'/'+ id);
                if(r == null)
                    return LOAD_OK;
                //웹툰의 경우 캡차 있을 수 있음.
                String location = r.header("location");
                if(r.code() == 302 && location != null && location.contains("captcha.php")){
                    r.close();
                    return LOAD_CAPTCHA;
                }
                String body = CustomHttpClient.readBody(r);
                if(body.contains("Connect Error: Connection timed out"))
                    continue;
                Document d = Jsoup.parse(body);
                Element header = legacyInfoRoot(d);

                //extra info
                try{
                    Element infoTable = d.selectFirst("table.table");
                    //recommend
                    rc = legacyRecommendCount(infoTable);
                    //bookmark
                    Element bookmark = infoTable == null ? null : infoTable.selectFirst("a#webtoon_bookmark");
                    if(bookmark != null) {
                        //logged in
                        bookmarked = bookmark.hasClass("btn-orangered");
                        bookmarkLink = bookmark.attr("href");
                    }else{
                        //not logged in
                        bookmarked = false;
                        bookmarkLink = "";
                    }
                }catch (Exception e){
                    ml.melun.mangaview.report.CrashReporter.record(e);
                }

                //thumb
                try {
                    thumb = header.selectFirst("div.view-img").selectFirst("img").attr("src");
                }catch (Exception e){}

                Elements infos = header.select("div.view-content");
                //title
                try {
                    name = infos.get(1).selectFirst("b").ownText();
                }catch (Exception e){}
                tags = new ArrayList<>();

                for(int i=1; i<infos.size(); i++){
                    Element e = infos.get(i);
                    try {
                        String type = e.selectFirst("strong").ownText();
                        switch (type) {
                            case "작가":
                                author = e.selectFirst("a").ownText();
                                break;
                            case "분류":
                                for (Element t : e.select("a"))
                                    tags.add(t.ownText());
                                break;
                            case "발행구분":
                                release = e.selectFirst("a").ownText();
                                break;
                        }

                    }catch (Exception e2){continue;}
                }
                MainPageWebtoon.applyInferredSearchTags(this);

                eps = WfwfEpisodeParser.parseLegacyEpisodes(d, baseMode);
                for(Manga episode : eps)
                    episode.setTitle(this);
                break;
            }catch(Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
                break;
            }
        }
        return LOAD_OK;
    }

    private int fetchNtkEps(CustomHttpClient client) {
        return fetchNtkEps(client, true);
    }

    private int fetchNtkEps(CustomHttpClient client, boolean allowPathRefresh) {
        try {
            String segment = ntkSegment();
            String titlePath = ntkTitlePath(segment);
            if(allowPathRefresh) {
                NtkPathRefreshResult refresh = refreshNtkTitlePathFromSearch(client, segment, titlePath);
                if(refresh.blocked)
                    return LOAD_CAPTCHA;
                titlePath = ntkTitlePath(segment);
            }
            String titleKey = ntkTitleKey(segment);
            CustomHttpClient.PageResponse page;
            try {
                page = client.mgetCachedPage(titlePath, PAGE_CACHE_TTL_MS);
            } catch(Exception e) {
                if(allowPathRefresh) {
                    NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                    if(refresh.refreshed)
                        return fetchNtkEps(client, false);
                    if(refresh.blocked)
                        return LOAD_CAPTCHA;
                }
                if(isNtkLoadBlocked(e))
                    return LOAD_CAPTCHA;
                throw e;
            }
            if(client.isCloudflareChallengeResponse(page.code, page.body) || NtkEpisodeParser.looksLikeErrorPage(page.body)) {
                logNtkEpisodeParse("challenge_or_error", page, segment, 0, 0);
                if(allowPathRefresh) {
                    NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                    if(refresh.refreshed)
                        return fetchNtkEps(client, false);
                    if(refresh.blocked)
                        return LOAD_CAPTCHA;
                }
                return LOAD_CAPTCHA;
            }
            if(page.code >= 400 || NtkEpisodeParser.looksLikeMissingPage(page.body)) {
                logNtkEpisodeParse("missing", page, segment, 0, 0);
                if(allowPathRefresh) {
                    NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                    if(refresh.refreshed)
                        return fetchNtkEps(client, false);
                    if(refresh.blocked)
                        return LOAD_CAPTCHA;
                }
                return LOAD_ERROR;
            }
            Document d = Jsoup.parse(page.body);

            Element h1 = d.selectFirst("h1");
            if(h1 != null)
                name = h1.ownText().trim();
            Element authorElement = d.selectFirst("h1 + *");
            if(authorElement != null)
                author = authorElement.ownText().trim();

            tags = new ArrayList<>();
            for(Element tag : d.select("a[href*=genre], a[href*=tag], a:matchesOwn(^#)")) {
                String text = tag.text().replace("#", "").trim();
                if(text.length() > 0 && !tags.contains(text))
                    tags.add(text);
            }
            MainPageWebtoon.applyInferredSearchTags(this);

            Element img = NtkEpisodeParser.firstTitleImage(d, titleKey, name);
            if(img != null)
                thumb = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");

            Elements episodeLinks = d.select("a[href]");
            NtkEpisodeParser.ParseResult parsed = NtkEpisodeParser.parse(d, segment, titleKey, baseMode, this);
            eps = parsed.episodes;
            if(eps.size() == 0) {
                logNtkEpisodeParse("empty", page, segment, parsed.matchedEpisodeLinks, episodeLinks.size());
                if(allowPathRefresh) {
                    NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                    if(refresh.refreshed)
                        return fetchNtkEps(client, false);
                    if(refresh.blocked)
                        return LOAD_CAPTCHA;
                }
                return LOAD_ERROR;
            }
        }catch(Exception e) {
            if(isNtkLoadBlocked(e))
                return LOAD_CAPTCHA;
            Log.w(TAG, "ntk_episode_parse_error id=" + id + ",url=" + getUrl(), e);
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return LOAD_OK;
    }

    private void logNtkEpisodeParse(String reason, CustomHttpClient.PageResponse page, String segment,
                                    int episodeLinkCount, int allLinkCount) {
        if(!Log.isLoggable(TAG, Log.DEBUG) && !"challenge_or_error".equals(reason))
            return;
        String sample = page == null || page.body == null ? "" : page.body.replace('\n', ' ').replace('\r', ' ');
        if(sample.length() > 220)
            sample = sample.substring(0, 220);
        Log.d(TAG, "ntk_episode_parse reason=" + reason
                + ",id=" + id
                + ",segment=" + segment
                + ",code=" + (page == null ? 0 : page.code)
                + ",fromCache=" + (page != null && page.fromCache)
                + ",bodyLen=" + (page == null || page.body == null ? 0 : page.body.length())
                + ",episodeLinks=" + episodeLinkCount
                + ",allLinks=" + allLinkCount
                + ",sample=" + sample);
    }

    private static boolean isCloudflareChallenge(Exception e) {
        String message = e == null ? null : e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("cloudflare");
    }

    private static boolean isNtkLoadBlocked(Exception e) {
        String message = e == null ? null : e.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("cloudflare")
                || lower.contains("request failed")
                || lower.contains("connectexception")
                || lower.contains("connection refused")
                || lower.contains("connection reset")
                || lower.contains("timed out");
    }

    static boolean isNtkLoadBlockedForTest(Exception e) {
        return isNtkLoadBlocked(e);
    }

    static List<Manga> parseLegacyEpisodesForTest(String html, int baseMode) {
        return WfwfEpisodeParser.parseLegacyEpisodesForTest(html, baseMode);
    }

    static String legacyInfoRootTextForTest(String html, String selector) {
        Element root = legacyInfoRoot(Jsoup.parse(html));
        Element item = root == null ? null : root.selectFirst(selector);
        return item == null ? "" : item.text();
    }

    static int legacyRecommendCountForTest(String html) {
        Document d = Jsoup.parseBodyFragment(html);
        return legacyRecommendCount(d.selectFirst("table.table"));
    }

    private static int legacyRecommendCount(Element infoTable) {
        if(infoTable == null)
            return 0;
        Element value = infoTable.selectFirst("button.btn-red b");
        return value == null ? 0 : parsePositiveInt(value.ownText());
    }

    private static Element legacyInfoRoot(Document d) {
        if(d == null)
            return null;
        Element header = d.selectFirst("div.view-title");
        return header == null ? d : header;
    }

    static String cleanNtkEpisodeTitleForTest(String html) {
        return NtkEpisodeParser.cleanEpisodeTitleForTest(html);
    }

    static String normalizeNtkEpisodePathForTest(String href, String segment, int titleId) {
        return NtkEpisodeParser.normalizeEpisodePathForTest(href, segment, String.valueOf(titleId));
    }

    static String normalizeNtkEpisodePathForTest(String href, String segment, String titleKey) {
        return NtkEpisodeParser.normalizeEpisodePathForTest(href, segment, titleKey);
    }

    static int ntkEpisodeSortIdForTest(String html, String epPath, String segment) {
        return NtkEpisodeParser.episodeSortIdForTest(html, epPath, segment);
    }

    static boolean looksLikeNtkMissingPageForTest(String body) {
        return NtkEpisodeParser.looksLikeMissingPage(body);
    }

    private static int parsePositiveInt(String value) {
        if(value == null)
            return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(value);
        if(!matcher.find())
            return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return 0;
        }
    }

    private String ntkSegment() {
        return baseMode == MTitle.base_webtoon ? "webtoon" : "manhwa";
    }

    private String ntkTitlePath(String segment) {
        if(path != null) {
            String trimmed = path.trim();
            String prefix = "/" + segment + "/";
            if(trimmed.startsWith(prefix) && trimmed.length() > prefix.length()) {
                int query = trimmed.indexOf('?');
                if(query >= 0)
                    trimmed = trimmed.substring(0, query);
                int hash = trimmed.indexOf('#');
                if(hash >= 0)
                    trimmed = trimmed.substring(0, hash);
                while(trimmed.endsWith("/") && trimmed.length() > prefix.length())
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                return trimmed;
            }
        }
        return "/" + segment + "/" + id;
    }

    private String ntkTitleKey(String segment) {
        String titlePath = ntkTitlePath(segment);
        String prefix = "/" + segment + "/";
        if(titlePath.startsWith(prefix)) {
            String value = titlePath.substring(prefix.length());
            int slash = value.indexOf('/');
            if(slash >= 0)
                value = value.substring(0, slash);
            if(value.length() > 0)
                return value;
        }
        return String.valueOf(id);
    }

    private int fetchWolfEps(CustomHttpClient client) {
        return fetchWolfEps(client, "/list?toon=", "/view?toon=");
    }

    private int fetchWolfEps(CustomHttpClient client, String listPath, String viewPath) {
        try {
            CustomHttpClient.PageResponse page = client.mgetCachedPage(listPath + id, PAGE_CACHE_TTL_MS);
            Document d = Jsoup.parse(page.body);

            try {
                Element metaTitle = d.selectFirst("meta[property=og:title]");
                if(metaTitle != null)
                    name = metaTitle.attr("content");
            }catch (Exception e){}

            try {
                Element metaDescription = d.selectFirst("meta[name=description]");
                if(metaDescription != null)
                    release = metaDescription.attr("content");
            }catch (Exception e){}

            try {
                Element img = d.selectFirst("section.webtoon-body img[src*=/" + id + "/], section.webtoon-body img[data-original*=/" + id + "/]");
                if(img == null)
                    img = d.selectFirst("div.img-box img");
                if(img != null) {
                    thumb = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
                }
            }catch (Exception e){}

            eps = WfwfEpisodeParser.parseWolfEpisodes(d, id, viewPath, baseMode, this);
            if(eps.size() == 0 && client.resolveWfwfDomainNow())
                return fetchWolfEps(client, listPath, viewPath);
            if(eps.size() == 0)
                return LOAD_ERROR;
        }catch(Exception e) {
            if(isCloudflareChallenge(e))
                return LOAD_CAPTCHA;
            if(shouldReportFetchFailure(e))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return LOAD_ERROR;
        }
        return LOAD_OK;
    }

    private static void sortEpisodesByVisibleEpisodeNumber(ArrayList<Manga> episodes) {
        EpisodeOrderingPolicy.sortByVisibleEpisodeNumber(episodes);
    }

    private NtkPathRefreshResult refreshNtkTitlePathFromApi(CustomHttpClient client, String segment, String currentPath) {
        if(client == null || name == null || name.trim().length() == 0)
            return NtkPathRefreshResult.none();
        try {
            String apiPath = "/api/" + ("webtoon".equals(segment) ? "works" : "manhwa-list")
                    + "?keyword=" + ntkEncodeQuery(name.trim()) + "&page=1&pageSize=10&withTotal=1";
            CustomHttpClient.PageResponse page = client.mgetCachedPage(apiPath, PAGE_CACHE_TTL_MS);
            if(client.isCloudflareChallengeResponse(page.code, page.body))
                return NtkPathRefreshResult.blocked();
            if(!client.isCloudflareChallengeResponse(page.code, page.body) && page.code < 400) {
                JsonElement root = JsonParser.parseString(page.body == null || page.body.length() == 0 ? "{}" : page.body);
                JsonArray works = root != null && root.isJsonObject()
                        && root.getAsJsonObject().has("works")
                        && root.getAsJsonObject().get("works").isJsonArray()
                        ? root.getAsJsonObject().getAsJsonArray("works")
                        : null;
                if(works != null) {
                    for(int i = 0; i < works.size(); i++) {
                        JsonElement workElement = works.get(i);
                        if(workElement == null || !workElement.isJsonObject())
                            continue;
                        JsonObject work = workElement.getAsJsonObject();
                        if(!isNtkTitleNameMatch(name, jsonString(work, "title")))
                            continue;
                        String sourceWorkId = firstNonEmpty(jsonString(work, "sourceWorkId"), jsonString(work, "id"));
                        if(applyNtkTitlePathRefresh(segment, sourceWorkId, currentPath))
                            return NtkPathRefreshResult.refreshed();
                    }
                }
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_path_refresh_failed id=" + id + ",name=" + name, e);
            if(isNtkLoadBlocked(e)) {
                NtkPathRefreshResult searchRefresh = refreshNtkTitlePathFromSearch(client, segment, currentPath);
                return searchRefresh.refreshed ? searchRefresh : NtkPathRefreshResult.blocked();
            }
        }
        return refreshNtkTitlePathFromSearch(client, segment, currentPath);
    }

    private NtkPathRefreshResult refreshNtkTitlePathFromSearch(CustomHttpClient client, String segment, String currentPath) {
        try {
            String searchPath = "/search?q=" + ntkEncodeQuery(name.trim())
                    + "&kind=" + ("webtoon".equals(segment) ? "webtoon" : "manhwa");
            CustomHttpClient.PageResponse page = client.mgetCachedPage(searchPath, PAGE_CACHE_TTL_MS);
            if(client.isCloudflareChallengeResponse(page.code, page.body))
                return NtkPathRefreshResult.blocked();
            if(page.code >= 400)
                return NtkPathRefreshResult.none();
            String refreshedPath = findNtkSearchTitlePath(Jsoup.parse(page.body), segment, name);
            if(refreshedPath.length() == 0)
                return NtkPathRefreshResult.none();
            return applyNtkTitlePathRefresh(segment, refreshedPath, currentPath)
                    ? NtkPathRefreshResult.refreshed()
                    : NtkPathRefreshResult.none();
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_search_refresh_failed id=" + id + ",name=" + name, e);
            if(isNtkLoadBlocked(e))
                return NtkPathRefreshResult.blocked();
        }
        return NtkPathRefreshResult.none();
    }

    private static class NtkPathRefreshResult {
        final boolean refreshed;
        final boolean blocked;

        private NtkPathRefreshResult(boolean refreshed, boolean blocked) {
            this.refreshed = refreshed;
            this.blocked = blocked;
        }

        static NtkPathRefreshResult refreshed() {
            return new NtkPathRefreshResult(true, false);
        }

        static NtkPathRefreshResult blocked() {
            return new NtkPathRefreshResult(false, true);
        }

        static NtkPathRefreshResult none() {
            return new NtkPathRefreshResult(false, false);
        }
    }

    private boolean applyNtkTitlePathRefresh(String segment, String sourceWorkId, String currentPath) {
        String refreshedPath = ntkApiTitlePath(segment, sourceWorkId);
        if(refreshedPath.length() == 0)
            return false;
        if(refreshedPath.equals(currentPath)) {
            setSourceSite("ntk");
            Log.d(TAG, "ntk_episode_path_refresh_retry path=" + refreshedPath + ",name=" + name);
            return true;
        }
        int refreshedId = parsePositiveInt(sourceWorkId);
        if(refreshedId > 0)
            id = refreshedId;
        setPath(refreshedPath);
        setSourceSite("ntk");
        Log.d(TAG, "ntk_episode_path_refreshed old=" + currentPath + ",new=" + refreshedPath + ",name=" + name);
        return true;
    }

    private static String findNtkSearchTitlePath(Document document, String segment, String expectedTitle) {
        if(document == null)
            return "";
        String prefix = "/" + ("webtoon".equals(segment) ? "webtoon" : "manhwa") + "/";
        for(Element link : document.select("a[href]")) {
            String candidatePath = ntkApiTitlePath(segment, link.attr("href"));
            if(!candidatePath.startsWith(prefix))
                continue;
            if(isNtkTitleNameMatch(expectedTitle, ntkSearchCandidateTitle(link)))
                return candidatePath;
        }
        return "";
    }

    private static String ntkSearchCandidateTitle(Element link) {
        if(link == null)
            return "";
        Element titleElement = link.selectFirst(".title, .card-title, h1, h2, h3, strong, b");
        String text = titleElement == null ? "" : titleElement.text().trim();
        if(text.length() > 0)
            return text;
        Element image = link.selectFirst("img[alt]");
        text = image == null ? "" : image.attr("alt").trim();
        if(text.length() > 0)
            return text;
        return link.text().trim();
    }

    private static String ntkEncodeQuery(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
    }

    static String ntkApiTitlePathForTest(String segment, String sourceWorkId) {
        return ntkApiTitlePath(segment, sourceWorkId);
    }

    static String ntkSearchTitlePathForTest(String html, String segment, String expectedTitle) {
        return findNtkSearchTitlePath(Jsoup.parse(html == null ? "" : html), segment, expectedTitle);
    }

    static boolean shouldRetrySameNtkTitlePathRefreshForTest(String segment, String sourceWorkId, String currentPath) {
        String refreshedPath = ntkApiTitlePath(segment, sourceWorkId);
        return refreshedPath.length() > 0 && refreshedPath.equals(currentPath);
    }

    static List<Manga> parseNtkEpisodesForTest(String html, String segment, String titleKey, int baseMode) {
        return NtkEpisodeParser.parseForTest(html, segment, titleKey, baseMode);
    }

    static List<Manga> parseWolfEpisodesForTest(String html, int titleId, String viewPath, int baseMode) {
        return WfwfEpisodeParser.parseWolfEpisodesForTest(html, titleId, viewPath, baseMode);
    }

    private static String ntkApiTitlePath(String segment, String sourceWorkId) {
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
        String resolvedSegment = "webtoon".equals(segment) ? "webtoon" : "manhwa";
        return trimTrailingPathSlash("/" + resolvedSegment + "/" + value);
    }

    private static String trimTrailingPathSlash(String value) {
        while(value != null && value.endsWith("/") && value.length() > 1)
            value = value.substring(0, value.length() - 1);
        return value == null ? "" : value;
    }

    private static String normalizeNtkTitleName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replace("\u2026", "")
                .replaceAll("[\\s.]+", "");
    }

    private static boolean isNtkTitleNameMatch(String expectedTitle, String candidateTitle) {
        String expected = normalizeNtkTitleName(expectedTitle);
        String candidate = normalizeNtkTitleName(candidateTitle);
        if(expected.length() == 0 || candidate.length() == 0)
            return false;
        if(expected.equals(candidate))
            return true;
        return expected.length() >= 6 && candidate.contains(expected)
                || candidate.length() >= 6 && expected.contains(candidate);
    }

    private static String firstNonEmpty(String first, String second) {
        if(first != null && first.trim().length() > 0)
            return first.trim();
        return second == null ? "" : second.trim();
    }

    private static String jsonString(JsonObject json, String key) {
        if(json == null || key == null || !json.has(key))
            return "";
        try {
            JsonElement value = json.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString();
        } catch(Exception e) {
            return "";
        }
    }

    static boolean shouldReportFetchFailure(Throwable failure) {
        if(failure == null)
            return false;
        String message = failure.getMessage();
        if(message != null && message.startsWith("Request failed:"))
            return false;
        return !(failure instanceof java.io.IOException);
    }

    public int getBookmark(){
        return bookmark;
    }
    public int getEpsCount(){ return eps == null ? 0 : eps.size();}

    public Boolean isNew() throws Exception{
        if(eps!=null && eps.size() > 0 && eps.get(0) != null && eps.get(0).getName() != null){
            return eps.get(0).getName().split(" ")[0].contains("NEW");
        }else{
            throw new Exception("not loaded");
        }
    }

    public void setEps(List<Manga> list){
        eps = orderedEpisodeSnapshot(list);
    }

    public static ArrayList<Manga> orderedEpisodeSnapshot(List<Manga> list) {
        if(list == null)
            return null;
        ArrayList<Manga> ordered;
        try {
            ordered = new ArrayList<>(list);
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            ordered = new ArrayList<>();
        }
        sortEpisodesByVisibleEpisodeNumber(ordered);
        return ordered;
    }

    public boolean ensureProgressEpisodes(Manga current) {
        int count = episodeCount;
        if(count <= 0)
            count = bookmarkEpisodeIndex;
        int currentEpisodeId = current == null ? bookmark : current.getId();
        if(count <= 1 || currentEpisodeId <= 0 || currentEpisodeId > count)
            return false;
        if("ntk".equals(getSourceSite()) || "wfwf".equals(getSourceSite()))
            return false;
        if(eps != null && eps.size() >= count)
            return false;

        ArrayList<Manga> generated = new ArrayList<>();
        for(int episodeId = count; episodeId >= 1; episodeId--) {
            String episodeName = episodeId + "화";
            if(current != null && current.getId() == episodeId
                    && current.getName() != null && current.getName().length() > 0)
                episodeName = current.getName();
            Manga episode = new Manga(episodeId, episodeName, "", baseMode);
            episode.setMode(0);
            episode.setTitle(this);
            episode.setTitleId(id);
            generated.add(episode);
        }
        eps = generated;
        if(current != null) {
            current.setTitle(this);
            current.setTitleId(id);
            current.setEps(eps);
        }
        return true;
    }

    public void removeEps(){
        if(eps!=null) eps.clear();
    }

    public void setBookmark(int b){bookmark = b;}


    @Override
    public Title clone(){
        return new Title(name, thumb, author, tags, release, id, baseMode);
    }

    public int getRecommend_c() {
        return rc;
    }

    public void setRecommend_c(int recommend_c) {
        this.rc = recommend_c;
    }

    public MTitle minimize(){
        MTitle title = new MTitle(name, id, thumb, author, tags, release, baseMode);
        int progressEpisodeId = getBookmark();
        if(progressEpisodeId <= 0)
            progressEpisodeId = getBookmarkEpisodeId();
        int progressIndex = getBookmarkIndex();
        if(progressIndex <= 0)
            progressIndex = getBookmarkEpisodeIndex();
        int progressCount = getEpsCount();
        if(progressCount <= 0)
            progressCount = getEpisodeCount();
        title.setReadingProgress(progressEpisodeId, progressIndex, progressCount);
        title.setPath(getPath());
        title.setSourceSite(getSourceSite());
        return title;
    }

    public int getBookmarkIndex() {
        if(eps == null || bookmark <= 0)
            return -1;
        for(int i = 0; i < eps.size(); i++)
            if(eps.get(i) != null && eps.get(i).getId() == bookmark)
                return i + 1;
        return -1;
    }

    public boolean hasCounter(){
        return !(rc==0&&(bookmarkLink==null||bookmarkLink.length()==0));
    }

    public static boolean isInteger(String s) {
        if(s.isEmpty()) return false;
        for(int i = 0; i < s.length(); i++) {
            if(i == 0 && s.charAt(i) == '-') {
                if(s.length() == 1) return false;
                else continue;
            }
            if(Character.digit(s.charAt(i),10) < 0) return false;
        }
        return true;
    }

    public boolean useBookmark(){
        return !isInteger(release);
    }

    private boolean isWebtoonWolfSource() {
        return baseMode == base_webtoon;
    }

    private boolean isComicWolfSource() {
        return baseMode == base_comic;
    }

    private boolean shouldFetchNtkEpisodes(CustomHttpClient client) {
        if(isWolfSource())
            return false;
        if(isNtkSource())
            return true;
        return client != null && client.isNtk();
    }

    private boolean isNtkSource() {
        return "ntk".equalsIgnoreCase(getSourceSite());
    }

    private boolean isWolfSource() {
        return "wfwf".equalsIgnoreCase(getSourceSite());
    }

    private boolean shouldUseNtkUrl() {
        if(isWolfSource())
            return false;
        if(isNtkSource())
            return true;
        return p != null && p.isNtkSite();
    }

}
