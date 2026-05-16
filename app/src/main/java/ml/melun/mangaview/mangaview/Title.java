package ml.melun.mangaview.mangaview;
import androidx.annotation.NonNull;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jsoup.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import okhttp3.Response;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getNumberFromString;


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

                eps = parseLegacyEpisodes(d, baseMode);
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
        try {
            String segment = ntkSegment();
            String titlePath = ntkTitlePath(segment);
            String titleKey = ntkTitleKey(segment);
            CustomHttpClient.PageResponse page = client.mgetCachedPage(titlePath, PAGE_CACHE_TTL_MS);
            if(client.isCloudflareChallengeResponse(page.code, page.body) || looksLikeNtkErrorPage(page.body)) {
                logNtkEpisodeParse("challenge_or_error", page, segment, 0, 0);
                return LOAD_CAPTCHA;
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

            Element img = firstNtkTitleImage(d, titleKey, name);
            if(img != null)
                thumb = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");

            eps = new ArrayList<>();
            Set<String> seenEpisodePaths = new HashSet<>();
            Elements episodeLinks = d.select("a[href]");
            int matchedEpisodeLinks = 0;
            for(Element link : episodeLinks) {
                if(link.hasClass("cta"))
                    continue;
                String href = link.attr("href");
                String epPath = normalizeNtkEpisodePath(href, segment, titleKey);
                if(epPath.length() == 0)
                    continue;
                matchedEpisodeLinks++;
                int epId = ntkEpisodeSortId(link, epPath, segment);
                if(epId <= 0)
                    continue;
                String epTitle = cleanNtkEpisodeTitle(link);
                if(isNtkEpisodeActionTitle(epTitle))
                    continue;
                if(!seenEpisodePaths.add(epPath))
                    continue;
                String date = extractNtkEpisodeDate(link, epTitle);
                Manga tmp = new Manga(epId, epTitle, date, baseMode);
                tmp.setMode(0);
                tmp.setTitle(this);
                tmp.setTitleId(id);
                tmp.setNtkEpisodePath(epPath);
                eps.add(tmp);
            }
            eps.sort((left, right) -> Integer.compare(right.getId(), left.getId()));
            if(eps.size() == 0) {
                logNtkEpisodeParse("empty", page, segment, matchedEpisodeLinks, episodeLinks.size());
                return LOAD_CAPTCHA;
            }
        }catch(Exception e) {
            if(isNtkLoadBlocked(e))
                return LOAD_CAPTCHA;
            Log.w(TAG, "ntk_episode_parse_error id=" + id + ",url=" + getUrl(), e);
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return LOAD_OK;
    }

    private static boolean looksLikeNtkErrorPage(String body) {
        if(body == null || body.length() == 0)
            return true;
        if(body.length() < 160 && body.toLowerCase(java.util.Locale.ROOT).contains("<body></body>"))
            return true;
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("webpage not available")
                || lower.contains("net::err_")
                || lower.contains("err_connection_reset")
                || lower.contains("err_name_not_resolved")
                || lower.contains("err_timed_out")
                || lower.contains("just a moment")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("cf-challenge")
                || lower.contains("cf_chl")
                || lower.contains("turnstile");
    }

    private static Element firstNtkTitleImage(Document document, String titleKey, String titleName) {
        if(document == null)
            return null;
        String keyNeedle = titleKey == null || titleKey.length() == 0 ? "" : "/" + titleKey + "/";
        for(Element img : document.select("img")) {
            String src = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
            if(keyNeedle.length() > 0 && src != null && src.contains(keyNeedle))
                return img;
            if(titleName != null && titleName.length() > 0 && titleName.equals(img.attr("alt")))
                return img;
        }
        return null;
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

    static List<Manga> parseLegacyEpisodesForTest(String html, int baseMode) {
        return parseLegacyEpisodes(Jsoup.parse(html), baseMode);
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

    private static List<Manga> parseLegacyEpisodes(Document d, int baseMode) {
        ArrayList<Manga> result = new ArrayList<>();
        Set<Integer> seenEpisodeIds = new HashSet<>();
        if(d == null)
            return result;
        Element list = d.selectFirst("ul.list-body");
        if(list == null)
            return result;
        for(Element row : list.select("li.list-item")) {
            Element titleElement = row.selectFirst("a.item-subject");
            if(titleElement == null)
                continue;
            int episodeId = legacyEpisodeId(titleElement.attr("href"), baseMode);
            if(episodeId <= 0 || !seenEpisodeIds.add(episodeId))
                continue;
            String episodeTitle = titleElement.ownText();
            String date = "";
            Element detail = row.selectFirst("div.item-details");
            if(detail != null) {
                Elements spans = detail.select("span");
                if(spans.size() > 0)
                    date = spans.get(0).ownText();
            }
            Manga episode = new Manga(episodeId, episodeTitle, date, baseMode);
            episode.setMode(0);
            result.add(episode);
        }
        return result;
    }

    private static int legacyEpisodeId(String href, int baseMode) {
        if(href == null)
            return -1;
        int id = legacyEpisodeIdAfterMarker(href, baseModeStr(baseMode) + '/');
        if(id > 0)
            return id;
        id = legacyEpisodeIdAfterMarker(href, "webtoon/");
        if(id > 0)
            return id;
        return legacyEpisodeIdAfterMarker(href, "comic/");
    }

    private static int legacyEpisodeIdAfterMarker(String href, String marker) {
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
        }catch(NumberFormatException e) {
            return -1;
        }
    }

    static String cleanNtkEpisodeTitleForTest(String html) {
        return cleanNtkEpisodeTitle(Jsoup.parseBodyFragment(html).body());
    }

    static String normalizeNtkEpisodePathForTest(String href, String segment, int titleId) {
        return normalizeNtkEpisodePath(href, segment, String.valueOf(titleId));
    }

    static String normalizeNtkEpisodePathForTest(String href, String segment, String titleKey) {
        return normalizeNtkEpisodePath(href, segment, titleKey);
    }

    static int ntkEpisodeSortIdForTest(String html, String epPath, String segment) {
        return ntkEpisodeSortId(Jsoup.parseBodyFragment(html).body(), epPath, segment);
    }

    private static String cleanNtkEpisodeTitle(Element link) {
        if(link == null)
            return "";
        Element subject = link.selectFirst(".subject, .wr-subject, .episode-title, .title, strong, b");
        String text = subject == null ? link.text() : subject.text();
        text = text.replace("첫화부터 정주행", "")
                .replace("첫화부터", "")
                .replace("정주행", "")
                .replace("▶ 보기", "")
                .replace("›", " ")
                .replace("UP", "")
                .replace("NEW", "")
                .trim();
        text = text.replaceAll("\\d{2}\\.\\d{2}\\.\\d{2}", " ").trim();
        text = text.replaceAll("\\s+", " ");
        if(!hasLetterOrDigit(text))
            return "";
        return text;
    }

    private static boolean isNtkEpisodeActionTitle(String title) {
        if(title == null)
            return true;
        String normalized = title.replaceAll("\\s+", "");
        return normalized.length() == 0
                || !hasLetterOrDigit(normalized)
                || "보기".equals(normalized)
                || "첫화부터정주행".equals(normalized)
                || "첫화부터".equals(normalized)
                || "정주행".equals(normalized);
    }

    private static boolean hasLetterOrDigit(String value) {
        if(value == null)
            return false;
        for(int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if(Character.isLetterOrDigit(codePoint))
                return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private static String normalizeNtkEpisodePath(String href, String segment, String titleKey) {
        if(href == null)
            return "";
        if(titleKey == null || titleKey.length() == 0)
            return "";
        String path = href.trim();
        int schemeIndex = path.indexOf("://");
        if(schemeIndex >= 0) {
            int slash = path.indexOf('/', schemeIndex + 3);
            path = slash >= 0 ? path.substring(slash) : "";
        }
        int hash = path.indexOf('#');
        if(hash >= 0)
            path = path.substring(0, hash);
        int query = path.indexOf('?');
        if(query >= 0)
            path = path.substring(0, query);
        if(path.length() > 0 && path.charAt(0) != '/')
            path = "/" + path;
        String prefix = "/" + segment + "/" + titleKey + "/";
        if(!path.startsWith(prefix))
            return "";
        String token = path.substring(prefix.length());
        return token.length() == 0 ? "" : path;
    }

    private static int ntkEpisodeSortId(Element link, String epPath, String segment) {
        Element number = link == null ? null : link.selectFirst(".ep-row-v2-no");
        int sortId = parsePositiveInt(number == null ? "" : number.text());
        if(sortId > 0)
            return sortId;
        sortId = MainPageWebtoon.getSecondPathId(epPath, segment);
        if(sortId > 0)
            return sortId;
        return parsePositiveInt(cleanNtkEpisodeTitle(link));
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

    private static String extractNtkEpisodeDate(Element link, String epTitle) {
        if(link == null)
            return "";
        String text = link.text();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{2})").matcher(text);
        if(matcher.find())
            return matcher.group(1);
        matcher = java.util.regex.Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{2})").matcher(epTitle == null ? "" : epTitle);
        if(matcher.find())
            return matcher.group(1);
        return "";
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

            eps = new ArrayList<>();
            Set<Integer> seenEpisodeIds = new HashSet<>();
            for(Element e : d.select("a[href^=\"" + viewPath + id + "\"]")) {
                String href = e.attr("href");
                int epId = MainPageWebtoon.getQueryInt(href, "num");
                if(epId <= 0) continue;
                if(!seenEpisodeIds.add(epId)) continue;
                String epTitle = "";
                Element subject = e.selectFirst(".subject");
                if(subject != null)
                    epTitle = subject.ownText().replace("\u00a0", " ").trim();
                if(epTitle.length() == 0)
                    epTitle = e.ownText().replace("\u00a0", " ").trim();
                if(epTitle.length() == 0)
                    epTitle = MainPageWebtoon.getQueryString(href, "title");

                String date = "";
                Element dateElement = e.selectFirst("span.date, div.date, span:last-child");
                if(dateElement != null)
                    date = dateElement.ownText();

                Manga tmp = new Manga(epId, epTitle, date, baseMode);
                tmp.setMode(0);
                tmp.setTitle(this);
                eps.add(tmp);
            }
            if(eps.size() == 0 && client.resolveWfwfDomainNow())
                return fetchWolfEps(client, listPath, viewPath);
            if(eps.size() == 0)
                return LOAD_ERROR;
        }catch(Exception e) {
            if(isCloudflareChallenge(e))
                return LOAD_CAPTCHA;
            ml.melun.mangaview.report.CrashReporter.record(e);
            return LOAD_ERROR;
        }
        return LOAD_OK;
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
        eps = list;
    }

    public boolean ensureProgressEpisodes(Manga current) {
        int count = episodeCount;
        if(count <= 0)
            count = bookmarkEpisodeIndex;
        int currentEpisodeId = current == null ? bookmark : current.getId();
        if(count <= 1 || currentEpisodeId <= 0 || currentEpisodeId > count)
            return false;
        if("ntk".equals(getSourceSite()) && eps != null && eps.size() > 0)
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
