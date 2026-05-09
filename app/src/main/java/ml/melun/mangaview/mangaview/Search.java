package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import okhttp3.Response;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_auto;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static ml.melun.mangaview.mangaview.MTitle.baseModeStr;

public class Search {
    private static final long PAGE_CACHE_TTL_MS = 2 * 60 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;
    private static final int CLASSIFICATION_DB_PAGE_SIZE = 120;

    int baseMode;
    private final String query;
    Boolean last = false;
    int mode;
    int page = 1;
    int timeoutRetries = 0;
    int classificationDbOffset = 0;
    int classificationDbTotalCount = 0;
    boolean classificationSourceFetched = false;
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
                appendNtkCommonSearchResults(client, combined, 200);
                result.addAll(combined);
                last = true;
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
        target.addAll(fetchWebtoonResults(client, path, limit));
    }

    private ArrayList<Title> fetchWebtoonResults(CustomHttpClient client, String path, int limit) throws Exception {
        CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
        if(page.code >= 400)
            throw new Exception("Webtoon search failed: " + page.code);
        Document d = Jsoup.parse(page.body);
        ArrayList<Title> parsed = MainPageWebtoon.parseWolfTitles(d, baseMode, limit);
        if(parsed.size() == 0 && client.resolveWfwfDomainNow()) {
            page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
            if(page.code >= 400)
                throw new Exception("Webtoon search failed: " + page.code);
            parsed = MainPageWebtoon.parseWolfTitles(Jsoup.parse(page.body), baseMode, limit);
        }
        String sourceSite = client != null && client.isNtk() ? "ntk" : "wfwf";
        for(Title title : parsed)
            if(title != null)
                title.setSourceSite(sourceSite);
        return parsed;
    }

    private boolean appendNextNtkCategoryPage(CustomHttpClient client, ArrayList<Title> target, String path, int limit) throws Exception {
        String pagePath = ntkPagePath(path, page);
        ArrayList<Title> parsed = fetchWebtoonResults(client, pagePath, limit);
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
        page++;
        classificationSourceFetched = true;
        return parsed.size() == 0 || added == 0;
    }

    static String ntkPagePathForTest(String path, int page) {
        return ntkPagePath(path, page);
    }

    private static String ntkPagePath(String path, int page) {
        if(path == null || page <= 1)
            return path;
        int hash = path.indexOf('#');
        String fragment = hash >= 0 ? path.substring(hash) : "";
        String base = hash >= 0 ? path.substring(0, hash) : path;
        String[] split = base.split("\\?", 2);
        String route = split[0];
        String query = split.length > 1 ? split[1] : "";
        ArrayList<String> params = new ArrayList<>();
        if(query.length() > 0) {
            for(String param : query.split("&")) {
                if(param.length() == 0 || param.startsWith("page="))
                    continue;
                params.add(param);
            }
        }
        params.add("page=" + page);
        return route + "?" + String.join("&", params) + fragment;
    }

    private boolean appendSearchResults(CustomHttpClient client, ArrayList<Title> target, int targetBaseMode, int limit) throws Exception {
        if(client != null && client.isNtk()) {
            appendNtkSiteSearchResults(client, target, targetBaseMode, limit);
            return true;
        }
        appendWebtoonResults(client, target, "/search.html?q=" + percentEncode(query, Charset.forName("EUC-KR")), limit);
        return true;
    }

    private void appendNtkSiteSearchResults(CustomHttpClient client, ArrayList<Title> target, int targetBaseMode, int limit) throws Exception {
        String encoded = percentEncode(query, Charset.forName("UTF-8"));
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

    private void appendNtkCommonSearchResults(CustomHttpClient client, ArrayList<Title> target, int limit) throws Exception {
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
