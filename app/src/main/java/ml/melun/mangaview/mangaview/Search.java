package ml.melun.mangaview.mangaview;

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
import java.util.Map;
import java.util.Set;

import okhttp3.Response;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_auto;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static ml.melun.mangaview.mangaview.MTitle.baseModeStr;

public class Search {
    private static final long PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;
    private static final int CLASSIFICATION_DB_PAGE_SIZE = 120;
    private static final int NTK_CATEGORY_PAGE_SIZE = 30;
    private static final int NTK_KEYWORD_PAGE_SIZE = 30;
    private static final long NTK_RESULT_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int NTK_RESULT_CACHE_MAX_ENTRIES = 80;
    private static final Map<String, CachedPageTitles> NTK_RESULT_CACHE = new HashMap<>();

    int baseMode;
    private final String query;
    Boolean last = false;
    int mode;
    int page = 1;
    int timeoutRetries = 0;
    int classificationDbOffset = 0;
    int classificationDbTotalCount = 0;
    boolean classificationSourceFetched = false;
    String ntkCategoryNextPath = null;
    String ntkSearchNextPath = null;
    private ArrayList<Title> result;
    private final Set<String> seenTitleKeys = new HashSet<>();

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

    public Boolean isLast() {
        return last;
    }

    public int getVirtualResultCount() {
        return Math.max(classificationDbTotalCount, classificationDbOffset);
    }

    public int fetch(CustomHttpClient client) {
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
        int status = 0;
        ArrayList<Title> combined = new ArrayList<>();
        try {
            if(client != null && client.isNtk() && mode == 0) {
                last = appendNextNtkSearchPage(client, combined, base_auto, 200);
                result.addAll(combined);
                return 0;
            }

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
            ml.melun.mangaview.report.CrashReporter.record(e);
            status = 1;
        }
        result.addAll(combined);
        last = true;
        return result.size() > 0 ? 0 : status;
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
                if(existing != null
                        && existing.getBaseMode() == title.getBaseMode()
                        && existing.getId() == title.getId()) {
                    exists = true;
                    break;
                }
            }
            if(!exists)
                target.add(title);
        }
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
                        last = appendNextClassificationDbGenreResults(webtoonResults, genre);
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
                last = appendNextClassificationDbGenreResults(webtoonResults, query);
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
                        last = appendNextClassificationDbGenreResults(comicResults, genre);
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
                last = appendNextClassificationDbGenreResults(comicResults, query);
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
        if(page.code >= 400)
            throw new Exception("Webtoon search failed: " + page.code);
        if(client != null && client.isNtk() && isNtkApiListPath(path))
            return parseNtkApiPage(page.body, path, baseMode, limit, currentPage);
        Document d = Jsoup.parse(page.body);
        ArrayList<Title> parsed = MainPageWebtoon.parseWolfTitles(d, baseMode, limit);
        if(parsed.size() == 0 && client.resolveWfwfDomainNow()) {
            page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
            if(page.code >= 400)
                throw new Exception("Webtoon search failed: " + page.code);
            d = Jsoup.parse(page.body);
            parsed = MainPageWebtoon.parseWolfTitles(d, baseMode, limit);
        }
        String sourceSite = client != null && client.isNtk() ? "ntk" : "wfwf";
        for(Title title : parsed)
            if(title != null)
                title.setSourceSite(sourceSite);
        String nextPath = client != null && client.isNtk() ? findNtkNextPagePath(d, path, currentPage + 1) : null;
        return new PageTitles(parsed, nextPath);
    }

    private boolean appendNextNtkCategoryPage(CustomHttpClient client, ArrayList<Title> target, String path, int limit) throws Exception {
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

    private int appendUniquePageTitles(ArrayList<Title> target, ArrayList<Title> parsed) {
        int added = 0;
        HashSet<String> pageKeys = new HashSet<>();
        for(Title title : parsed) {
            if(title == null)
                continue;
            String key = title.getBaseMode() + ":" + title.getId();
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
            api.add("status=");
            api.addAll(params);
            api.add("page=" + page);
            api.add("pageSize=" + NTK_CATEGORY_PAGE_SIZE);
            api.add("withTotal=1");
            return "/api/manhwa-list?" + String.join("&", api);
        }
        if(baseMode == base_webtoon && (route.startsWith("/ing") || route.startsWith("/end"))) {
            ArrayList<String> api = new ArrayList<>();
            api.add("status=" + (route.startsWith("/end") ? "end" : "ing"));
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
            if("tag".equals(key) && "16".equals(value)) {
                result.add("tag=" + percentEncode("성인", StandardCharsets.UTF_8));
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

    private interface PageTitleLoader {
        PageTitles load() throws Exception;
    }

    private PageTitles cachedNtkPageTitles(CustomHttpClient client, String kind, String path, int targetBaseMode,
                                           int limit, int currentPage, PageTitleLoader loader) throws Exception {
        if(client == null || !client.isNtk())
            return loader.load();
        String key = kind + ':' + targetBaseMode + ':' + limit + ':' + currentPage + ':' + path;
        long now = System.currentTimeMillis();
        synchronized (NTK_RESULT_CACHE) {
            CachedPageTitles cached = NTK_RESULT_CACHE.get(key);
            if(cached != null && now - cached.loadedAt < NTK_RESULT_CACHE_TTL_MS)
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
                int id = parsePositiveInt(jsonString(work, "sourceWorkId"));
                if(id <= 0)
                    id = parsePositiveInt(jsonString(work, "id"));
                if(id <= 0)
                    continue;
                String name = jsonString(work, "title").trim();
                if(name.length() == 0)
                    continue;
                String thumb = jsonString(work, "thumbnailUrl");
                ArrayList<String> tags = splitNtkGenre(jsonString(work, "genre"));
                String release = "";
                if(hasJsonValue(work, "latestEpisodeNumber"))
                    release = jsonString(work, "latestEpisodeNumber") + "화";
                else
                    release = jsonString(work, "ep");
                Title title = new Title(name, thumb, "", tags, release, id, baseMode);
                title.setSourceSite("ntk");
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

    private static ArrayList<String> splitNtkGenre(String value) {
        ArrayList<String> tags = new ArrayList<>();
        if(value == null)
            return tags;
        for(String tag : value.split("[,/|]")) {
            String trimmed = tag.trim();
            if(trimmed.length() > 0 && !tags.contains(trimmed))
                tags.add(trimmed);
        }
        return tags;
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
            return appendNextNtkSearchPage(client, target, targetBaseMode, limit);
        }
        appendWebtoonResults(client, target, "/search.html?q=" + percentEncode(query, Charset.forName("EUC-KR")), limit);
        return true;
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
        CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
        if(page.code >= 400)
            throw new Exception("NTK search failed: " + page.code);
        Document d = Jsoup.parse(page.body);
        ArrayList<Title> parsed = new ArrayList<>();
        if(targetBaseMode == base_auto || targetBaseMode == base_webtoon)
            appendUnique(parsed, MainPageWebtoon.parseWolfTitles(d, base_webtoon, limit));
        if(targetBaseMode == base_auto || targetBaseMode == base_comic)
            appendUnique(parsed, MainPageWebtoon.parseWolfTitles(d, base_comic, limit));
        for(Title title : parsed)
            if(title != null)
                title.setSourceSite("ntk");
        String nextPath = findNtkNextPagePath(d, path, currentPage + 1);
        return new PageTitles(parsed, nextPath);
    }

    static String ntkSearchPathForTest(String query, int targetBaseMode, int page) {
        return ntkSearchPath(query, targetBaseMode, page);
    }

    private static String ntkSearchPath(String query, int targetBaseMode, int page) {
        String encoded = percentEncode(query, Charset.forName("UTF-8"));
        ArrayList<String> params = new ArrayList<>();
        params.add("q=" + encoded);
        if(targetBaseMode == base_comic)
            params.add("kind=manhwa");
        else if(targetBaseMode == base_webtoon)
            params.add("kind=webtoon");
        if(page > 1)
            params.add("page=" + page);
        return "/search?" + String.join("&", params);
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
        String[] paths = {
                "/search?q=" + encoded + "&kind=" + kind,
                "/search?q=" + encoded,
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
            if(page.code >= 400)
                return new PageTitles(new ArrayList<>(), null);
            return parseNtkApiPage(page.body, path, targetBaseMode, 0, 1);
        });
        ArrayList<Title> filtered = filterNtkKeywordResults(parsed.titles, query, limit);
        appendUnique(target, filtered);
    }

    static ArrayList<Title> filterNtkKeywordResultsForTest(ArrayList<Title> titles, String query, int limit) {
        return filterNtkKeywordResults(titles, query, limit);
    }

    private static ArrayList<Title> filterNtkKeywordResults(ArrayList<Title> titles, String query, int limit) {
        ArrayList<Title> filtered = new ArrayList<>();
        if(titles == null)
            return filtered;
        String normalized = normalizeSearchText(query);
        for(Title title : titles) {
            if(title == null)
                continue;
            if(normalized.length() > 0 && !matchesNtkKeyword(title, normalized))
                continue;
            filtered.add(title);
            if(limit > 0 && filtered.size() >= limit)
                break;
        }
        return filtered;
    }

    private static boolean matchesNtkKeyword(Title title, String normalizedQuery) {
        if(normalizedQuery.length() == 0)
            return true;
        if(normalizeSearchText(title.getName()).contains(normalizedQuery))
            return true;
        if(normalizeSearchText(title.getRelease()).contains(normalizedQuery))
            return true;
        for(String tag : title.getTags())
            if(normalizeSearchText(tag).contains(normalizedQuery))
                return true;
        return false;
    }

    private static String normalizeSearchText(String value) {
        if(value == null)
            return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String ntkPath(CustomHttpClient client, String ntkPath, String wolfPath) {
        return client != null && client.isNtk() ? ntkPath : wolfPath;
    }

    private String comicRoot(CustomHttpClient client) {
        return client != null && client.isNtk() ? "/manhwa" : "/cm";
    }

    private boolean appendNextClassificationDbGenreResults(ArrayList<Title> target, String genre) {
        if(genre == null || genre.trim().length() == 0)
            return true;
        ArrayList<Title> dbResults;
        if(baseMode == base_comic) {
            classificationDbTotalCount = MainPageWebtoon.getComicClassificationDbGenreCount(genre.trim());
            dbResults = MainPageWebtoon.getComicClassificationDbTitlesByGenre(genre.trim(), classificationDbOffset, CLASSIFICATION_DB_PAGE_SIZE);
        } else {
            classificationDbTotalCount = MainPageWebtoon.getClassificationDbGenreCount(genre.trim());
            dbResults = MainPageWebtoon.getClassificationDbTitlesByGenre(genre.trim(), classificationDbOffset, CLASSIFICATION_DB_PAGE_SIZE);
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
            String key = title.getBaseMode() + ":" + title.getId();
            if(seenTitleKeys.add(key))
                result.add(title);
        }
    }

    static String genreFromCategoryPath(String path, int baseMode) {
        if(path == null)
            return "";
        String ntkGenre = rawQueryValue(path, baseMode == base_comic ? "g" : "tag");
        if(baseMode == base_webtoon && "16".equals(ntkGenre))
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
