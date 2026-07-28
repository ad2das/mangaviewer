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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import okhttp3.Response;
import ml.melun.mangaview.runtime.AppDispatchers;

import static ml.melun.mangaview.MainApplication.p;


public class Title extends MTitle {
    private static final String TAG = "ViewerPerf";
    private List<Manga> eps = null;
    private boolean ntkEpisodeListConfirmedEmpty = false;
    private volatile boolean ntkEpisodeLoadDefinitivelyMissing = false;
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
    private static final int MAX_NTK_EPISODE_PAGES = 50;
    private static final String NTK_ALIAS_WEBTOON_URL = "https://newtoki1.org";
    private static final String NTK_ALIAS_COMIC_URL = NTK_ALIAS_WEBTOON_URL + "/manhwa";


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
        setResumeNtkImageIdentity(
                title.getResumeNtkImageWorkId(),
                title.getResumeNtkImageEpisodeId(),
                title.getResumeNtkImageCount());
        setReadingProgress(title.getBookmarkEpisodeId(), title.getBookmarkEpisodeIndex(), title.getEpisodeCount());
        if(title instanceof Title) {
            ntkEpisodeListConfirmedEmpty = ((Title) title).isNtkEpisodeListConfirmedEmpty();
            if(ntkEpisodeListConfirmedEmpty)
                eps = new ArrayList<>();
        }
        bookmark = title.getBookmarkEpisodeId();
    }

    @Override
    public void setResumeNtkEpisodePath(String resumeNtkEpisodePath) {
        super.setResumeNtkEpisodePath(resumeNtkEpisodePath);
        // A tokenized NTK episode path carries a more authoritative work identity than an old
        // locally generated numeric title id. Keep the title route in lockstep so episode-list
        // refresh and continuous-reader adjacency never fall back to /manhwa/<local-id>.
        applyNtkTitlePathFromEpisodePath(getResumeNtkEpisodePath());
    }

    @NonNull
    @Override
    public String toString() {
        return super.toString()  + " . " + eps;
    }

    public List<Manga> getEps(){
        return eps;
    }

    public boolean isNtkEpisodeListConfirmedEmpty() {
        return ntkEpisodeListConfirmedEmpty;
    }

    public boolean isNtkEpisodeLoadDefinitivelyMissing() {
        return ntkEpisodeLoadDefinitivelyMissing;
    }

    public void setNtkEpisodeListConfirmedEmpty(boolean confirmedEmpty) {
        ntkEpisodeListConfirmedEmpty = confirmedEmpty;
        if(confirmedEmpty && eps == null)
            eps = new ArrayList<>();
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
            if(isNtkEpisodeRequestCancelled(client))
                return LOAD_ERROR;
            ntkEpisodeListConfirmedEmpty = false;
            ntkEpisodeLoadDefinitivelyMissing = false;
            String segment = ntkSegment();
            String titlePath = ntkTitlePath(segment);
            if(allowPathRefresh && shouldRefreshNtkTitlePath(client, titlePath)) {
                NtkPathRefreshResult refresh = refreshNtkTitlePathFromSearch(client, segment, titlePath);
                if(refresh.blocked && (titlePath == null || titlePath.length() == 0))
                    return shouldOpenNtkCaptchaForLoadFailure(client) ? LOAD_CAPTCHA : LOAD_ERROR;
                titlePath = ntkTitlePath(segment);
            }
            String titleKey = ntkTitleKey(segment);
            boolean preferDocumentMetadata = shouldPreferNtkDocumentMetadata(
                    segment, titleKey, client.isModernNtkGuardRootForPath(titlePath));
            boolean preferSlugRscMetadata = shouldPreferNtkSlugRscMetadata(titlePath, titleKey);
            NtkEpisodeParser.ParseResult apiEpisodes = preferDocumentMetadata || preferSlugRscMetadata
                    ? new NtkEpisodeParser.ParseResult()
                    : parseNtkEpisodesFromApi(client, segment, titleKey, baseMode);
            if(isNtkEpisodeRequestCancelled(client))
                return LOAD_ERROR;
            if(!preferDocumentMetadata) {
                if(apiEpisodes.episodes.size() > 0) {
                    eps = apiEpisodes.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=episode_api,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",episodes=" + eps.size());
                    return LOAD_OK;
                }
                if(apiEpisodes.definitiveEmptyEpisodeList) {
                    ntkEpisodeListConfirmedEmpty = true;
                    eps = apiEpisodes.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=episode_api_empty_confirmed,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath);
                    return LOAD_OK;
                }
            }
            if(!preferDocumentMetadata && shouldPreferNtkRscTitlePayload(titlePath)) {
                NtkEpisodeParser.ParseResult payloadOnly = parseNtkEpisodesFromNextPayloads(client, titlePath, "",
                        segment, titleKey, baseMode);
                if(payloadOnly.episodes.size() > 0) {
                    eps = payloadOnly.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=rsc_first,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",episodes=" + eps.size());
                    return LOAD_OK;
                }
                if(payloadOnly.definitiveEmptyEpisodeList) {
                    ntkEpisodeListConfirmedEmpty = true;
                    eps = payloadOnly.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=rsc_empty_confirmed,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath);
                    return LOAD_OK;
                }
                if(isNonNumericNtkTitlePath(titlePath)) {
                    boolean shouldRefresh = shouldRefreshNtkTitlePathAfterMissing(client, titlePath);
                    Log.d(TAG, "ntk_episode_slug_refresh_check id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",allow=" + allowPathRefresh
                            + ",should=" + shouldRefresh);
                    if(allowPathRefresh && shouldRefresh) {
                        NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                        if(refresh.refreshed)
                            return fetchNtkEps(client, false);
                        if(refresh.blocked)
                            return shouldOpenNtkCaptchaForLoadFailure(client) ? LOAD_CAPTCHA : LOAD_ERROR;
                    }
                    Log.d(TAG, "ntk_episode_parse reason=rsc_empty_slug,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath);
                    return LOAD_ERROR;
                }
            }
            CustomHttpClient.PageResponse page;
            try {
                page = client.mgetCachedPage(titlePath, PAGE_CACHE_TTL_MS);
            } catch(Exception e) {
                NtkEpisodeParser.ParseResult payloadOnly = parseNtkEpisodesFromNextPayloads(client, titlePath, "",
                        segment, titleKey, baseMode);
                if(payloadOnly.episodes.size() > 0) {
                    eps = payloadOnly.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=rsc_only_after_page_failure,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",episodes=" + eps.size());
                    return LOAD_OK;
                }
                if(payloadOnly.definitiveEmptyEpisodeList) {
                    ntkEpisodeListConfirmedEmpty = true;
                    eps = payloadOnly.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=rsc_empty_confirmed_after_page_failure,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath);
                    return LOAD_OK;
                }
                NtkEpisodeParser.ParseResult desktopParsed =
                        parseNtkEpisodesFromDesktopDocument(client, titlePath, segment, titleKey, baseMode);
                if(desktopParsed.episodes.size() > 0) {
                    eps = desktopParsed.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=desktop_document_after_page_failure,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",episodes=" + eps.size());
                    return LOAD_OK;
                }
                if(preferDocumentMetadata) {
                    NtkEpisodeParser.ParseResult apiFallback =
                            parseNtkEpisodesFromApi(client, segment, titleKey, baseMode);
                    if(apiFallback.episodes.size() > 0) {
                        eps = apiFallback.episodes;
                        Log.d(TAG, "ntk_episode_parse reason=episode_api_fallback_after_document_failure,id=" + id
                                + ",segment=" + segment
                                + ",path=" + titlePath
                                + ",episodes=" + eps.size());
                        return LOAD_OK;
                    }
                    if(apiFallback.definitiveEmptyEpisodeList) {
                        ntkEpisodeListConfirmedEmpty = true;
                        eps = apiFallback.episodes;
                        Log.d(TAG, "ntk_episode_parse reason=episode_api_empty_confirmed_after_document_failure,id=" + id
                                + ",segment=" + segment
                                + ",path=" + titlePath);
                        return LOAD_OK;
                    }
                }
                if(allowPathRefresh && shouldRefreshNtkTitlePathAfterMissing(client, titlePath)) {
                    NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                    if(refresh.refreshed)
                        return fetchNtkEps(client, false);
                    if(refresh.blocked)
                        return shouldOpenNtkCaptchaForLoadFailure(client) ? LOAD_CAPTCHA : LOAD_ERROR;
                }
                if(isNtkLoadBlocked(e))
                    return shouldOpenNtkCaptchaForLoadFailure(client) ? LOAD_CAPTCHA : LOAD_ERROR;
                throw e;
            }
            if(isNtkEpisodeRequestCancelled(client))
                return LOAD_ERROR;
            Log.d(TAG, "ntk_episode_page_loaded id=" + id
                    + ",path=" + titlePath
                    + ",code=" + page.code
                    + ",fromCache=" + page.fromCache
                    + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
            boolean definitivelyMissingTitlePage =
                    isDefinitivelyMissingNtkTitlePage(page);
            if(!definitivelyMissingTitlePage
                    && (client.isCloudflareChallengeResponse(page.code, page.body)
                    || NtkEpisodeParser.looksLikeErrorPage(page.body))) {
                logNtkEpisodeParse("challenge_or_error", page, segment, 0, 0);
                NtkEpisodeParser.ParseResult payloadOnly = parseNtkEpisodesFromNextPayloads(client, titlePath, page.body,
                        segment, titleKey, baseMode);
                if(payloadOnly.episodes.size() > 0) {
                    eps = payloadOnly.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=rsc_only_after_error,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",episodes=" + eps.size());
                    return LOAD_OK;
                }
                if(payloadOnly.definitiveEmptyEpisodeList) {
                    ntkEpisodeListConfirmedEmpty = true;
                    eps = payloadOnly.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=rsc_empty_confirmed_after_error,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath);
                    return LOAD_OK;
                }
                if(allowPathRefresh && shouldRefreshNtkTitlePath(client, titlePath)) {
                    NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                    if(refresh.refreshed)
                        return fetchNtkEps(client, false);
                    if(refresh.blocked)
                        return shouldOpenNtkCaptchaForLoadFailure(client) ? LOAD_CAPTCHA : LOAD_ERROR;
                }
                return shouldOpenNtkCaptchaForLoadFailure(client) ? LOAD_CAPTCHA : LOAD_ERROR;
            }
            if(definitivelyMissingTitlePage || page.code >= 400
                    || NtkEpisodeParser.looksLikeMissingPage(page.body)) {
                logNtkEpisodeParse("missing", page, segment, 0, 0);
                NtkEpisodeParser.ParseResult payloadOnly = parseNtkEpisodesFromNextPayloads(client, titlePath, page.body,
                        segment, titleKey, baseMode);
                if(payloadOnly.episodes.size() > 0) {
                    eps = payloadOnly.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=rsc_only_after_missing,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",episodes=" + eps.size());
                    return LOAD_OK;
                }
                if(payloadOnly.definitiveEmptyEpisodeList) {
                    ntkEpisodeListConfirmedEmpty = true;
                    eps = payloadOnly.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=rsc_empty_confirmed_after_missing,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath);
                    return LOAD_OK;
                }
                if(allowPathRefresh && shouldRefreshNtkTitlePathAfterMissing(client, titlePath)) {
                    NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                    if(refresh.refreshed)
                        return fetchNtkEps(client, false);
                    if(refresh.blocked)
                        return shouldOpenNtkCaptchaForLoadFailure(client) ? LOAD_CAPTCHA : LOAD_ERROR;
                }
                ntkEpisodeLoadDefinitivelyMissing = definitivelyMissingTitlePage;
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
            NtkEpisodeParser.ParseResult parsed = parseNtkEpisodeRowsWithFallback(
                    page.body, d, segment, titleKey, baseMode);
            parsed = appendNtkEpisodePages(
                    client, titlePath, page.body, segment, titleKey, baseMode, parsed, false);
            eps = parsed.episodes;
            if(eps.size() == 0) {
                if(preferDocumentMetadata) {
                    NtkEpisodeParser.ParseResult apiFallback =
                            parseNtkEpisodesFromApi(client, segment, titleKey, baseMode);
                    if(apiFallback.episodes.size() > 0) {
                        eps = apiFallback.episodes;
                        Log.d(TAG, "ntk_episode_parse reason=episode_api_fallback_after_document_empty,id=" + id
                                + ",segment=" + segment
                                + ",path=" + titlePath
                                + ",episodes=" + eps.size());
                        return LOAD_OK;
                    }
                    if(apiFallback.definitiveEmptyEpisodeList) {
                        ntkEpisodeListConfirmedEmpty = true;
                        eps = apiFallback.episodes;
                        Log.d(TAG, "ntk_episode_parse reason=episode_api_empty_confirmed_after_document_empty,id=" + id
                                + ",segment=" + segment
                                + ",path=" + titlePath);
                        return LOAD_OK;
                    }
                }
                NtkEpisodeParser.ParseResult chunkParsed = parseNtkEpisodesFromNextPayloads(client, titlePath, page.body,
                        segment, titleKey, baseMode);
                if(chunkParsed.episodes.size() > 0) {
                    eps = chunkParsed.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=next_chunk,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",episodes=" + eps.size());
                    return LOAD_OK;
                }
                if(chunkParsed.definitiveEmptyEpisodeList) {
                    ntkEpisodeListConfirmedEmpty = true;
                    eps = chunkParsed.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=next_chunk_empty_confirmed,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath);
                    return LOAD_OK;
                }
                logNtkEpisodeParse("empty", page, segment, parsed.matchedEpisodeLinks, episodeLinks.size());
                if(allowPathRefresh && shouldRefreshNtkTitlePath(client, titlePath)) {
                    NtkPathRefreshResult refresh = refreshNtkTitlePathFromApi(client, segment, titlePath);
                    if(refresh.refreshed)
                        return fetchNtkEps(client, false);
                    if(refresh.blocked)
                        return LOAD_CAPTCHA;
                }
                return LOAD_ERROR;
            }
            if(ntkEpisodePageCount(page.body) <= 1 && shouldEnrichNtkEpisodeImageCounts(eps)) {
                NtkEpisodeParser.ParseResult payloadParsed = parseNtkEpisodesFromNextPayloads(client, titlePath, page.body,
                        segment, titleKey, baseMode);
                if(payloadParsed.episodes.size() > 0 && hasNtkEpisodeImageCount(payloadParsed.episodes)) {
                    eps = payloadParsed.episodes;
                    Log.d(TAG, "ntk_episode_parse reason=next_payload_enrich,id=" + id
                            + ",segment=" + segment
                            + ",path=" + titlePath
                            + ",episodes=" + eps.size());
                }
            }
        }catch(Exception e) {
            if(isNtkLoadBlocked(e))
                return shouldOpenNtkCaptchaForLoadFailure(client) ? LOAD_CAPTCHA : LOAD_ERROR;
            Log.w(TAG, "ntk_episode_parse_error id=" + id + ",url=" + getUrl(), e);
            ml.melun.mangaview.report.CrashReporter.record(e);
            return LOAD_ERROR;
        }
        return LOAD_OK;
    }

    private NtkEpisodeParser.ParseResult parseNtkEpisodesFromApi(CustomHttpClient client, String segment,
                                                                 String titleKey, int baseMode) {
        NtkEpisodeParser.ParseResult empty = new NtkEpisodeParser.ParseResult();
        if(client == null || segment == null || titleKey == null
                || segment.length() == 0 || titleKey.length() == 0
                || isNtkEpisodeRequestCancelled(client))
            return empty;
        String apiPath = "/api/" + segment + "/" + titleKey + "/episodes";
        try {
            CustomHttpClient.PageResponse page = fetchNtkEpisodeApiPage(client, apiPath);
            if(isNtkEpisodeRequestCancelled(client))
                return empty;
            Log.d(TAG, "ntk_episode_api path=" + apiPath
                    + ",code=" + (page == null ? 0 : page.code)
                    + ",bodyLen=" + (page == null || page.body == null ? 0 : page.body.length()));
            if(page == null || page.code >= 400 || page.body == null || page.body.length() == 0
                    || client.isCloudflareChallengeResponse(page.code, page.body))
                return empty;
            JsonElement rootElement = JsonParser.parseString(page.body);
            if(rootElement == null || !rootElement.isJsonObject())
                return empty;
            JsonObject root = rootElement.getAsJsonObject();
            JsonArray items = root.has("episodes") && root.get("episodes").isJsonArray()
                    ? root.getAsJsonArray("episodes") : null;
            if(items == null)
                return empty;
            int titleId = parsePositiveInt(titleKey);
            if(titleId <= 0)
                titleId = id;
            for(JsonElement element : items) {
                if(element == null || !element.isJsonObject())
                    continue;
                JsonObject item = element.getAsJsonObject();
                String sourceEpisodeId = firstNonEmpty(jsonString(item, "sourceEpisodeId"),
                        jsonString(item, "episodeId"));
                if(sourceEpisodeId.length() == 0)
                    continue;
                int epId = parsePositiveInt(jsonString(item, "epNo"));
                if(epId <= 0)
                    epId = parsePositiveInt(jsonString(item, "number"));
                if(epId <= 0)
                    epId = parsePositiveInt(sourceEpisodeId);
                if(epId <= 0)
                    epId = empty.episodes.size() + 1;
                String epTitle = firstNonEmpty(jsonString(item, "title"), epId + "\uD654");
                Manga manga = new Manga(epId, epTitle, jsonString(item, "date"), baseMode);
                manga.setMode(0);
                manga.setTitle(this);
                manga.setTitleId(titleId);
                manga.setNtkEpisodePath("/" + segment + "/" + titleKey + "/" + sourceEpisodeId);
                String imageWorkId = preferredNtkImageWorkId(titleKey, titleId);
                if(imageWorkId.length() > 0)
                    manga.setNtkImageWorkId(imageWorkId);
                manga.setNtkImageEpisodeId(sourceEpisodeId);
                int imageCount = parsePositiveInt(jsonString(item, "imageCount"));
                if(imageCount > 0)
                    manga.setNtkImageCount(imageCount);
                empty.episodes.add(manga);
            }
            EpisodeOrderingPolicy.sortByVisibleEpisodeNumber(empty.episodes);
            int authoritativeTotal = 0;
            if(root.has("total")) {
                try {
                    authoritativeTotal = Math.max(0, root.get("total").getAsInt());
                } catch(Exception ignored) {
                }
            }
            // The API is the fastest complete source for numeric webtoon episode lists, but a
            // partially replicated response must never replace the paginated title document.
            // Accept it only when its explicit total agrees with the unique parsed rows; otherwise
            // return an unproven result and let the existing document/RSC path recover the list.
            if(authoritativeTotal > 0 && empty.episodes.size() != authoritativeTotal) {
                Log.w(TAG, "ntk_episode_api_incomplete path=" + apiPath
                        + ",expected=" + authoritativeTotal
                        + ",parsed=" + empty.episodes.size());
                return new NtkEpisodeParser.ParseResult();
            }
            if(empty.episodes.size() == 0 && root.has("ok") && root.has("total")) {
                try {
                    empty.definitiveEmptyEpisodeList = root.get("ok").getAsBoolean()
                            && root.get("total").getAsInt() == 0;
                } catch (Exception ignored) {
                }
            }
            return empty;
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_api_failed path=" + apiPath + "," + e);
            return empty;
        }
    }

    private CustomHttpClient.PageResponse fetchNtkEpisodeApiPage(CustomHttpClient client, String apiPath)
            throws Exception {
        try {
            CustomHttpClient.PageResponse page = client.mgetNtkDesktopSearchPage(apiPath, PAGE_CACHE_TTL_MS);
            if(page != null && page.code >= 200 && page.code < 400
                    && !client.isCloudflareChallengeResponse(page.code, page.body))
                return page;
        } catch(Exception ignored) {
        }
        CustomHttpClient.PageResponse aliasPage = client.runWithSitePreset(
                NTK_ALIAS_COMIC_URL, NTK_ALIAS_WEBTOON_URL,
                () -> client.mgetNtkDesktopSearchPage(apiPath, PAGE_CACHE_TTL_MS));
        if(aliasPage != null && aliasPage.code >= 200 && aliasPage.code < 400
                && !client.isCloudflareChallengeResponse(aliasPage.code, aliasPage.body))
            client.applyResolvedNtkRootFromSearch(NTK_ALIAS_WEBTOON_URL);
        return aliasPage;
    }

    private static boolean shouldEnrichNtkEpisodeImageCounts(List<Manga> episodes) {
        return episodes != null && episodes.size() > 0 && !hasNtkEpisodeImageCount(episodes);
    }

    private static boolean hasNtkEpisodeImageCount(List<Manga> episodes) {
        if(episodes == null)
            return false;
        for(Manga episode : episodes) {
            if(episode != null && episode.getNtkImageCount() > 0)
                return true;
        }
        return false;
    }

    private NtkEpisodeParser.ParseResult parseNtkEpisodesFromDesktopDocument(CustomHttpClient client, String titlePath,
                                                                             String segment, String titleKey,
                                                                             int baseMode) {
        NtkEpisodeParser.ParseResult empty = new NtkEpisodeParser.ParseResult();
        if(client == null || titlePath == null || titlePath.length() == 0)
            return empty;
        try {
            CustomHttpClient.PageResponse page = client.mgetNtkDesktopDocumentPage(titlePath, PAGE_CACHE_TTL_MS);
            Log.d(TAG, "ntk_episode_desktop_document_loaded id=" + id
                    + ",path=" + titlePath
                    + ",code=" + page.code
                    + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
            if(page.code >= 400 || page.body == null || page.body.length() == 0
                    || client.isCloudflareChallengeResponse(page.code, page.body))
                return empty;
            Document document = Jsoup.parse(page.body);
            NtkEpisodeParser.ParseResult parsed = parseNtkEpisodeRowsWithFallback(
                    page.body, document, segment, titleKey, baseMode);
            if(parsed.episodes.size() > 0) {
                return appendNtkEpisodePages(
                        client, titlePath, page.body, segment, titleKey, baseMode, parsed, false);
            }
            if(parsed.definitiveEmptyEpisodeList)
                return parsed;
            return parseNtkEpisodesFromNextPayloads(client, titlePath, page.body, segment, titleKey, baseMode);
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_desktop_document_failed id=" + id
                    + ",path=" + titlePath, e);
        }
        return empty;
    }

    private NtkEpisodeParser.ParseResult parseNtkEpisodesFromNextPayloads(CustomHttpClient client, String titlePath,
                                                                          String html, String segment, String titleKey,
                                                                          int baseMode) {
        NtkEpisodeParser.ParseResult empty = new NtkEpisodeParser.ParseResult();
        if(client == null || segment == null || titleKey == null
                || isNtkEpisodeRequestCancelled(client))
            return empty;
        String safeHtml = html == null ? "" : html;
        try {
            CustomHttpClient.PageResponse rsc = client.mgetNtkRscPage(titlePath, PAGE_CACHE_TTL_MS);
            if(isNtkEpisodeRequestCancelled(client))
                return empty;
            String body = rsc == null ? "" : rsc.body;
            Log.d(TAG, "ntk_episode_rsc path=" + titlePath
                    + ",code=" + (rsc == null ? 0 : rsc.code)
                    + ",fromCache=" + (rsc != null && rsc.fromCache)
                    + ",bodyLen=" + (body == null ? 0 : body.length()));
            if(body != null && body.length() > 0) {
                Document fallbackDocument = null;
                NtkEpisodeParser.ParseResult parsed = NtkEpisodeParser.parseEpisodeRowsFast(
                        body, segment, titleKey, baseMode, this);
                if(parsed.episodes.size() == 0 && !parsed.definitiveEmptyEpisodeList) {
                    fallbackDocument = Jsoup.parse(safeHtml + "<script>" + body + "</script>");
                    parsed = NtkEpisodeParser.parse(
                            fallbackDocument, segment, titleKey, baseMode, this);
                }
                if(parsed.episodes.size() > 0 || parsed.definitiveEmptyEpisodeList) {
                    parsed = appendNtkEpisodePages(
                            client, titlePath, body, segment, titleKey, baseMode, parsed, true);
                    attachResumeNtkKpMetadataFromParsed(parsed.episodes, "title-rsc");
                    return parsed;
                }
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_rsc_failed path=" + titlePath + "," + e);
        }
        List<String> chunks = ntkTitleNextChunkPaths(safeHtml, segment);
        if(chunks.size() == 0)
            return empty;
        StringBuilder merged = new StringBuilder(safeHtml.length() + 8192);
        merged.append(safeHtml);
        int fetched = 0;
        for(String chunkPath : chunks) {
            if(isNtkEpisodeRequestCancelled(client))
                return empty;
            try {
                CustomHttpClient.PageResponse chunk = client.mgetNtkStaticTextPage(chunkPath, PAGE_CACHE_TTL_MS);
                if(isNtkEpisodeRequestCancelled(client))
                    return empty;
                String body = chunk == null ? "" : chunk.body;
                Log.d(TAG, "ntk_episode_next_chunk path=" + chunkPath
                        + ",code=" + (chunk == null ? 0 : chunk.code)
                        + ",fromCache=" + (chunk != null && chunk.fromCache)
                        + ",bodyLen=" + (body == null ? 0 : body.length()));
                if(body != null && body.length() > 0) {
                    merged.append("<script>").append(body).append("</script>");
                    fetched++;
                }
            } catch(Exception e) {
                Log.d(TAG, "ntk_episode_next_chunk_failed path=" + chunkPath + "," + e);
            }
        }
        if(fetched == 0)
            return empty;
        NtkEpisodeParser.ParseResult parsed = NtkEpisodeParser.parse(Jsoup.parse(merged.toString()),
                segment, titleKey, baseMode, this);
        attachResumeNtkKpMetadataFromParsed(parsed.episodes, "title-next-chunks");
        return parsed;
    }

    private NtkEpisodeParser.ParseResult appendNtkEpisodePages(
            CustomHttpClient client,
            String titlePath,
            String firstPageBody,
            String segment,
            String titleKey,
            int baseMode,
            NtkEpisodeParser.ParseResult firstPage,
            boolean preferRsc
    ) {
        int pageCount = ntkEpisodePageCount(firstPageBody);
        if(pageCount <= 1 || firstPage == null || firstPage.episodes.size() == 0)
            return firstPage;
        NtkEpisodeParser.ParseResult merged = new NtkEpisodeParser.ParseResult();
        Set<String> seen = new HashSet<>();
        appendUniqueNtkEpisodes(merged.episodes, seen, firstPage.episodes);
        merged.matchedEpisodeLinks += firstPage.matchedEpisodeLinks;
        merged.definitiveEmptyEpisodeList = firstPage.definitiveEmptyEpisodeList;
        int fetchedPages = 1;
        Set<Integer> completedPages = new HashSet<>();
        int submittedPages = 0;
        CompletionService<NtkEpisodePageResult> completionService =
                AppDispatchers.ioCompletionService();
        CustomHttpClient.RequestGroup requestGroup = client.currentRequestGroup();
        for(int pageNumber = 2; pageNumber <= pageCount; pageNumber++) {
            if(isNtkEpisodeRequestCancelled(client))
                break;
            final int requestedPage = pageNumber;
            final String pagePath = ntkEpisodePagePath(titlePath, requestedPage);
            completionService.submit(() -> {
                try {
                    CustomHttpClient.RequestWork<CustomHttpClient.PageResponse> fetch =
                            () -> fetchNtkEpisodePage(client, pagePath, preferRsc);
                    CustomHttpClient.PageResponse response = requestGroup == null
                            ? fetch.run()
                            : client.runWithRequestGroup(requestGroup, fetch);
                    String body = response == null || response.body == null ? "" : response.body;
                    NtkEpisodeParser.ParseResult parsed = null;
                    if(response != null && response.code < 400 && body.length() > 0
                            && !client.isCloudflareChallengeResponse(response.code, body)) {
                        parsed = parseNtkEpisodeRowsWithFallback(
                                body, null, segment, titleKey, baseMode);
                    }
                    return new NtkEpisodePageResult(
                            requestedPage, pagePath, response, parsed, null);
                } catch(Exception e) {
                    return new NtkEpisodePageResult(
                            requestedPage, pagePath, null, null, e);
                }
            });
            submittedPages++;
        }
        for(int completedPage = 0; completedPage < submittedPages; completedPage++) {
            if(isNtkEpisodeRequestCancelled(client))
                break;
            try {
                NtkEpisodePageResult result = completionService.take().get();
                if(result.error != null) {
                    Log.d(TAG, "ntk_episode_rsc_page_failed path="
                            + result.pagePath + "," + result.error);
                    continue;
                }
                CustomHttpClient.PageResponse response = result.response;
                String body = response == null || response.body == null ? "" : response.body;
                Log.d(TAG, "ntk_episode_rsc_page path=" + result.pagePath
                        + ",page=" + result.pageNumber
                        + ",pageCount=" + pageCount
                        + ",code=" + (response == null ? 0 : response.code)
                        + ",mode=" + (preferRsc ? "rsc" : "document")
                        + ",fromCache=" + (response != null && response.fromCache)
                        + ",bodyLen=" + body.length());
                if(result.parsed == null || result.parsed.episodes.size() == 0)
                    continue;
                appendUniqueNtkEpisodes(merged.episodes, seen, result.parsed.episodes);
                merged.matchedEpisodeLinks += result.parsed.matchedEpisodeLinks;
                merged.definitiveEmptyEpisodeList =
                        merged.definitiveEmptyEpisodeList
                                && result.parsed.definitiveEmptyEpisodeList;
                fetchedPages++;
                completedPages.add(result.pageNumber);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch(Exception e) {
                Log.d(TAG, "ntk_episode_rsc_page_completion_failed path="
                        + titlePath + "," + e);
            }
        }
        EpisodeOrderingPolicy.sortByVisibleEpisodeNumber(merged.episodes);
        Log.d(TAG, "ntk_episode_rsc_pages_merged path=" + titlePath
                + ",expectedPages=" + pageCount
                + ",fetchedPages=" + fetchedPages
                + ",episodes=" + merged.episodes.size());
        if(!isNtkEpisodeRequestCancelled(client)
                && completedPages.size() != pageCount - 1) {
            Log.w(TAG, "ntk_episode_rsc_pages_incomplete path=" + titlePath
                    + ",expectedPages=" + pageCount
                    + ",fetchedPages=" + fetchedPages
                    + ",episodes=" + merged.episodes.size());
            return new NtkEpisodeParser.ParseResult();
        }
        return merged;
    }

    private NtkEpisodeParser.ParseResult parseNtkEpisodeRowsWithFallback(
            String body,
            Document parsedDocument,
            String segment,
            String titleKey,
            int baseMode
    ) {
        NtkEpisodeParser.ParseResult parsed = NtkEpisodeParser.parseEpisodeRowsFast(
                body, segment, titleKey, baseMode, this);
        if(parsed.episodes.size() > 0 || parsed.definitiveEmptyEpisodeList)
            return parsed;
        Document document = parsedDocument == null
                ? Jsoup.parse("<script>" + (body == null ? "" : body) + "</script>")
                : parsedDocument;
        return NtkEpisodeParser.parse(document, segment, titleKey, baseMode, this);
    }

    private static final class NtkEpisodePageResult {
        final int pageNumber;
        final String pagePath;
        final CustomHttpClient.PageResponse response;
        final NtkEpisodeParser.ParseResult parsed;
        final Exception error;

        NtkEpisodePageResult(
                int pageNumber,
                String pagePath,
                CustomHttpClient.PageResponse response,
                NtkEpisodeParser.ParseResult parsed,
                Exception error
        ) {
            this.pageNumber = pageNumber;
            this.pagePath = pagePath;
            this.response = response;
            this.parsed = parsed;
            this.error = error;
        }
    }

    private CustomHttpClient.PageResponse fetchNtkEpisodePage(
            CustomHttpClient client,
            String pagePath,
            boolean preferRsc
    ) throws Exception {
        CustomHttpClient.PageResponse first = preferRsc
                ? client.mgetNtkRscPage(pagePath, PAGE_CACHE_TTL_MS)
                : client.mgetNtkDesktopDocumentPage(pagePath, PAGE_CACHE_TTL_MS);
        if(isUsableNtkEpisodePage(client, first))
            return first;
        return preferRsc
                ? client.mgetNtkDesktopDocumentPage(pagePath, PAGE_CACHE_TTL_MS)
                : client.mgetNtkRscPage(pagePath, PAGE_CACHE_TTL_MS);
    }

    private static boolean isUsableNtkEpisodePage(
            CustomHttpClient client,
            CustomHttpClient.PageResponse page
    ) {
        return page != null
                && page.code >= 200
                && page.code < 400
                && page.body != null
                && page.body.length() > 0
                && !client.isCloudflareChallengeResponse(page.code, page.body);
    }

    private static void appendUniqueNtkEpisodes(
            List<Manga> destination,
            Set<String> seen,
            List<Manga> candidates
    ) {
        if(destination == null || seen == null || candidates == null)
            return;
        for(Manga episode : candidates) {
            if(episode == null)
                continue;
            String path = episode.getNtkEpisodePath();
            String key = path == null || path.length() == 0
                    ? episode.getId() + "|" + episode.getName()
                    : path;
            if(seen.add(key))
                destination.add(episode);
        }
    }

    private static int ntkEpisodePageCount(String body) {
        if(body == null || body.length() == 0)
            return 1;
        String normalized = body.replace("\\u0026", "&")
                .replace("\\u0026amp;", "&")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("&amp;", "&");
        Matcher matcher = Pattern.compile("(?:[?&])epage=(\\d{1,4})",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        int maxPage = 1;
        while(matcher.find()) {
            try {
                maxPage = Math.max(maxPage, Integer.parseInt(matcher.group(1)));
            } catch(Exception ignored) {
            }
        }
        return Math.min(maxPage, MAX_NTK_EPISODE_PAGES);
    }

    private static String ntkEpisodePagePath(String titlePath, int pageNumber) {
        String value = titlePath == null ? "" : titlePath.trim();
        int hash = value.indexOf('#');
        if(hash >= 0)
            value = value.substring(0, hash);
        int query = value.indexOf('?');
        String base = query < 0 ? value : value.substring(0, query);
        String queryString = query < 0 ? "" : value.substring(query + 1);
        ArrayList<String> parameters = new ArrayList<>();
        if(queryString.length() > 0) {
            for(String parameter : queryString.split("&")) {
                if(parameter.length() > 0 && !parameter.toLowerCase(java.util.Locale.ROOT)
                        .startsWith("epage="))
                    parameters.add(parameter);
            }
        }
        parameters.add("epage=" + Math.max(1, pageNumber));
        return base + "?" + String.join("&", parameters);
    }

    private static boolean isNtkEpisodeRequestCancelled(CustomHttpClient client) {
        if(client == null)
            return true;
        CustomHttpClient.RequestGroup requestGroup = client.currentRequestGroup();
        return requestGroup != null && requestGroup.isCancelled();
    }

    private void attachResumeNtkKpMetadataFromParsed(List<Manga> episodes, String reason) {
        if(episodes == null || episodes.size() == 0)
            return;
        String resumePath = getResumeNtkEpisodePath();
        if(resumePath == null || resumePath.length() == 0)
            return;
        if(!Pattern.compile("^/webtoon/\\d{1,12}/kp-[^/?#]+(?:[/?#].*)?$",
                Pattern.CASE_INSENSITIVE).matcher(resumePath).matches())
            return;
        for(Manga episode : episodes) {
            if(episode == null)
                continue;
            episode.setTitle(this);
            episode.setTitleId(getId());
            episode.setMode(baseMode);
            episode.ensureNtkEpisodePathFromIdentity();
            if(!resumePath.equals(episode.getNtkEpisodePath()))
                continue;
            Log.d(TAG, "ntk_resume_kp_metadata_from_parse path="
                    + resumePath + ",reason=" + reason);
            return;
        }
    }

    private static List<String> ntkTitleNextChunkPaths(String html, String segment) {
        ArrayList<String> chunks = new ArrayList<>();
        if(html == null || html.length() == 0 || segment == null || segment.length() == 0)
            return chunks;
        Set<String> seen = new HashSet<>();
        String quotedSegment = Pattern.quote(segment);
        Pattern pattern = Pattern.compile("([\"'])((?:/[^\"']*)?/_next/static/chunks/app/"
                + quotedSegment
                + "/%5BsourceWorkId%5D/page-[^\"'<>\\s]+\\.js)\\1", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        while(matcher.find()) {
            String path = matcher.group(2);
            if(path == null || path.length() == 0)
                continue;
            if(path.startsWith("http://") || path.startsWith("https://")) {
                int scheme = path.indexOf("://");
                int slash = scheme < 0 ? -1 : path.indexOf('/', scheme + 3);
                path = slash < 0 ? "" : path.substring(slash);
            }
            if(path.length() > 0 && path.charAt(0) != '/')
                path = "/" + path;
            if(path.length() > 0 && seen.add(path))
                chunks.add(path);
        }
        return chunks;
    }

    private static boolean shouldRefreshNtkTitlePath(CustomHttpClient client, String titlePath) {
        if(titlePath == null || titlePath.length() == 0)
            return true;
        // A canonical title route is already sufficient to request its RSC metadata. Re-resolving
        // a tokenized route through /search before that request can sit on a guarded browser
        // timeout for several seconds even though the title RSC is immediately available. Keep
        // refresh as a recovery path after the known route actually fails.
        if(isNumericNtkTitleFallbackPath(titlePath) || isNonNumericNtkTitlePath(titlePath))
            return false;
        return client == null || (!client.hasNtkAccessProof() && !client.hasRecentNtkAccessVerification());
    }

    private static boolean shouldRefreshNtkTitlePathAfterMissing(CustomHttpClient client, String titlePath) {
        return titlePath == null
                || titlePath.length() == 0
                || titlePath.matches("^/(?:manhwa|webtoon)/[^/]+/?$")
                        && !isNumericNtkTitleFallbackPath(titlePath)
                || shouldRefreshNtkTitlePath(client, titlePath)
                || isNumericNtkTitleFallbackPath(titlePath)
                        && client != null
                        && (client.hasNtkAccessProof() || client.hasRecentNtkAccessVerification());
    }

    private static boolean isNumericNtkTitleFallbackPath(String titlePath) {
        return titlePath != null && titlePath.matches("^/(?:manhwa|webtoon)/\\d+$");
    }

    private static boolean isNonNumericNtkTitlePath(String titlePath) {
        return titlePath != null && titlePath.matches("^/(?:manhwa|webtoon)/(?!\\d+$)[^/]+/?$");
    }

    private static boolean shouldPreferNtkRscTitlePayload(String titlePath) {
        if(titlePath == null)
            return false;
        return titlePath.matches("^/(?:manhwa|webtoon)/[^/]+/?$");
    }

    static boolean shouldPreferNtkDocumentMetadataForTest(String segment, String titleKey,
                                                          boolean modernGuardRoot) {
        return shouldPreferNtkDocumentMetadata(segment, titleKey, modernGuardRoot);
    }

    private static boolean shouldPreferNtkDocumentMetadata(String segment, String titleKey,
                                                           boolean modernGuardRoot) {
        if(titleKey == null || !titleKey.matches("\\d{1,12}"))
            return false;
        // Numeric webtoon APIs expose an authoritative `total` and include special/latest rows
        // that can be absent from the paginated title document. parseNtkEpisodesFromApi verifies
        // that total before the result is accepted, so prefer it and retain the document as the
        // fallback. Modern guarded manhwa roots still require their document metadata.
        return modernGuardRoot && "manhwa".equals(segment);
    }

    private static boolean shouldOpenNtkCaptchaForLoadFailure(CustomHttpClient client) {
        return client == null || (!client.hasRecentNtkHardBlock()
                && !client.hasNtkAccessProof()
                && !client.hasRecentNtkAccessVerification());
    }

    private void logNtkEpisodeParse(String reason, CustomHttpClient.PageResponse page, String segment,
                                    int episodeLinkCount, int allLinkCount) {
        if(!Log.isLoggable(TAG, Log.DEBUG) && "ok".equals(reason))
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

    private static boolean isNtkHardBlockFailure(Exception e) {
        String message = e == null ? null : e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("ntk hard block");
    }

    private static boolean isNtkLoadBlocked(Exception e) {
        String message = e == null ? null : e.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ntk hard block")
                || lower.contains("cloudflare")
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

    static int ntkEpisodePageCountForTest(String body) {
        return ntkEpisodePageCount(body);
    }

    static String ntkEpisodePagePathForTest(String titlePath, int pageNumber) {
        return ntkEpisodePagePath(titlePath, pageNumber);
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

    private static boolean isDefinitivelyMissingNtkTitlePage(
            CustomHttpClient.PageResponse page) {
        if(page == null)
            return false;
        if(page.code == 404 || page.code == 410)
            return true;
        String body = page.body;
        return body != null && body.contains("작품을 찾을 수 없습니다");
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

    private static String preferredNtkImageWorkId(String titleKey, int titleId) {
        String key = titleKey == null ? "" : titleKey.trim();
        if(key.matches("\\d{1,12}"))
            return key;
        if(titleId > 0 && stableNtkSourceId(key) != titleId)
            return String.valueOf(titleId);
        return "";
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

    public boolean applyNtkTitlePathFromEpisodePath(String episodePath) {
        String titlePath = ntkTitlePathFromEpisodePath(episodePath, ntkSegment());
        if(titlePath.length() == 0)
            return false;
        String currentPath = path == null ? "" : path.trim();
        setSourceSite("ntk");
        if(titlePath.equals(currentPath))
            return false;
        setPath(titlePath);
        logNtkTitlePathFromEpisode("ntk_title_path_from_episode old=" + currentPath + ",new=" + titlePath
                + ",episodePath=" + episodePath + ",name=" + name);
        return true;
    }

    private static void logNtkTitlePathFromEpisode(String message) {
        try {
            Log.d(TAG, message);
        } catch(RuntimeException ignored) {
        }
    }

    static String ntkTitlePathFromEpisodePathForTest(String episodePath, String segment) {
        return ntkTitlePathFromEpisodePath(episodePath, segment);
    }

    private static String ntkTitlePathFromEpisodePath(String episodePath, String expectedSegment) {
        if(episodePath == null)
            return "";
        String path = episodePath.trim();
        int scheme = path.indexOf("://");
        if(scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "";
        }
        int query = path.indexOf('?');
        if(query >= 0)
            path = path.substring(0, query);
        int hash = path.indexOf('#');
        if(hash >= 0)
            path = path.substring(0, hash);
        while(path.endsWith("/") && path.length() > 1)
            path = path.substring(0, path.length() - 1);
        if(path.length() > 0 && path.charAt(0) != '/')
            path = "/" + path;
        String[] parts = path.split("/");
        if(parts.length < 4)
            return "";
        String actualSegment = parts[1];
        if(!"manhwa".equals(actualSegment) && !"webtoon".equals(actualSegment))
            return "";
        if(expectedSegment != null && expectedSegment.trim().length() > 0
                && !actualSegment.equals(expectedSegment.trim()))
            return "";
        String workKey = parts[2];
        if(workKey.length() == 0 || parts[3].length() == 0)
            return "";
        return "/" + actualSegment + "/" + workKey;
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
                        String sourceWorkId = ntkCanonicalWorkId(work);
                        String candidatePath = ntkApiTitlePath(segment, sourceWorkId);
                        Log.d(TAG, "ntk_episode_path_refresh_api_candidate old=" + currentPath
                                + ",candidate=" + candidatePath
                                + ",name=" + name);
                        if(applyNtkTitlePathRefresh(segment, sourceWorkId, currentPath))
                            return NtkPathRefreshResult.refreshed();
                    }
                }
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_path_refresh_failed id=" + id + ",name=" + name, e);
            if(isNtkHardBlockFailure(e))
                return NtkPathRefreshResult.blocked();
            if(isNtkLoadBlocked(e)) {
                NtkPathRefreshResult searchRefresh = refreshNtkTitlePathFromSearch(client, segment, currentPath);
                return searchRefresh.refreshed ? searchRefresh : NtkPathRefreshResult.none();
            }
        }
        return refreshNtkTitlePathFromSearch(client, segment, currentPath);
    }

    private NtkPathRefreshResult refreshNtkTitlePathFromSearch(CustomHttpClient client, String segment, String currentPath) {
        try {
            String searchPath = "/search?q=" + ntkEncodeQuery(name.trim())
                    + "&kind=" + ("webtoon".equals(segment) ? "webtoon" : "manhwa");
            CustomHttpClient.PageResponse page = client.mgetCachedPage(searchPath, PAGE_CACHE_TTL_MS);
            if(client.isCloudflareChallengeResponse(page.code, page.body)) {
                Log.d(TAG, "ntk_episode_search_refresh_blocked id=" + id
                        + ",name=" + name
                        + ",path=" + searchPath
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
                NtkPathRefreshResult categoryRefresh =
                        refreshNtkTitlePathFromCategory(client, segment, currentPath, "search_blocked");
                if(categoryRefresh.refreshed || categoryRefresh.blocked)
                    return categoryRefresh;
                return NtkPathRefreshResult.blocked();
            }
            if(page.code >= 400 || page.code == 301 || page.code == 302) {
                Log.d(TAG, "ntk_episode_search_refresh_unusable id=" + id
                        + ",name=" + name
                        + ",path=" + searchPath
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length())
                        + ",snippet=" + ntkLogSnippet(page.body));
                return refreshNtkTitlePathFromCategory(client, segment, currentPath, "search_unusable");
            }
            String refreshedPath = findNtkSearchTitlePath(Jsoup.parse(page.body), segment, name);
            if(refreshedPath.length() == 0) {
                Log.d(TAG, "ntk_episode_search_refresh_miss id=" + id
                        + ",name=" + name
                        + ",path=" + searchPath
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length())
                        + ",snippet=" + ntkLogSnippet(page.body));
                return refreshNtkTitlePathFromCategory(client, segment, currentPath, "search_miss");
            }
            Log.d(TAG, "ntk_episode_search_refresh_candidate old=" + currentPath
                    + ",candidate=" + refreshedPath
                    + ",name=" + name
                    + ",bodyLen=" + (page.body == null ? 0 : page.body.length())
                    + ",links=" + ntkSearchDebugLinks(page.body, segment));
            if(applyNtkTitlePathRefresh(segment, refreshedPath, currentPath))
                return NtkPathRefreshResult.refreshed();
            if(isNonNumericNtkTitlePath(refreshedPath))
                return refreshNtkTitlePathFromDesktopSearch(client, segment, currentPath, searchPath);
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_search_refresh_failed id=" + id + ",name=" + name, e);
            if(isNtkHardBlockFailure(e))
                return NtkPathRefreshResult.blocked();
            if(isNtkCloudflareChallengeFailure(e))
                return NtkPathRefreshResult.blocked();
            NtkPathRefreshResult categoryRefresh =
                    refreshNtkTitlePathFromCategory(client, segment, currentPath, "search_exception");
            if(categoryRefresh.refreshed || categoryRefresh.blocked)
                return categoryRefresh;
        }
        return NtkPathRefreshResult.none();
    }

    private NtkPathRefreshResult refreshNtkTitlePathFromCategory(CustomHttpClient client, String segment,
                                                                 String currentPath, String reason) {
        if(client == null || name == null || name.trim().length() == 0)
            return NtkPathRefreshResult.none();
        String categoryPath = "/" + ("webtoon".equals(segment) ? "webtoon" : "manhwa");
        try {
            CustomHttpClient.PageResponse page = client.mgetNtkDesktopDocumentPage(categoryPath, PAGE_CACHE_TTL_MS);
            if(client.isCloudflareChallengeResponse(page.code, page.body)) {
                Log.d(TAG, "ntk_episode_category_refresh_blocked id=" + id
                        + ",name=" + name
                        + ",reason=" + reason
                        + ",path=" + categoryPath
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
                return NtkPathRefreshResult.blocked();
            }
            if(page.code >= 400 || page.code == 301 || page.code == 302) {
                Log.d(TAG, "ntk_episode_category_refresh_unusable id=" + id
                        + ",name=" + name
                        + ",reason=" + reason
                        + ",path=" + categoryPath
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length())
                        + ",snippet=" + ntkLogSnippet(page.body));
                return NtkPathRefreshResult.none();
            }
            String refreshedPath = findNtkSearchTitlePath(Jsoup.parse(page.body), segment, name);
            if(refreshedPath.length() == 0) {
                Log.d(TAG, "ntk_episode_category_refresh_miss id=" + id
                        + ",name=" + name
                        + ",reason=" + reason
                        + ",path=" + categoryPath
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length())
                        + ",links=" + ntkSearchDebugLinks(page.body, segment));
                return NtkPathRefreshResult.none();
            }
            Log.d(TAG, "ntk_episode_category_refresh_candidate old=" + currentPath
                    + ",candidate=" + refreshedPath
                    + ",name=" + name
                    + ",reason=" + reason
                    + ",bodyLen=" + (page.body == null ? 0 : page.body.length())
                    + ",links=" + ntkSearchDebugLinks(page.body, segment));
            return applyNtkTitlePathRefresh(segment, refreshedPath, currentPath)
                    ? NtkPathRefreshResult.refreshed()
                    : NtkPathRefreshResult.none();
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_category_refresh_failed id=" + id
                    + ",name=" + name
                    + ",reason=" + reason, e);
            if(isNtkHardBlockFailure(e))
                return NtkPathRefreshResult.blocked();
            if(isNtkCloudflareChallengeFailure(e))
                return NtkPathRefreshResult.blocked();
        }
        return NtkPathRefreshResult.none();
    }

    private NtkPathRefreshResult refreshNtkTitlePathFromDesktopSearch(CustomHttpClient client, String segment,
                                                                      String currentPath, String searchPath) {
        try {
            CustomHttpClient.PageResponse page = client.mgetNtkDesktopDocumentPage(searchPath, PAGE_CACHE_TTL_MS);
            if(client.isCloudflareChallengeResponse(page.code, page.body))
                return NtkPathRefreshResult.blocked();
            if(page.code >= 400 || page.code == 301 || page.code == 302)
                return NtkPathRefreshResult.none();
            String refreshedPath = findNtkSearchTitlePath(Jsoup.parse(page.body), segment, name);
            if(refreshedPath.length() == 0)
                return NtkPathRefreshResult.none();
            Log.d(TAG, "ntk_episode_desktop_search_refresh_candidate old=" + currentPath
                    + ",candidate=" + refreshedPath
                    + ",name=" + name
                    + ",bodyLen=" + (page.body == null ? 0 : page.body.length()));
            return applyNtkTitlePathRefresh(segment, refreshedPath, currentPath)
                    ? NtkPathRefreshResult.refreshed()
                    : NtkPathRefreshResult.none();
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_desktop_search_refresh_failed id=" + id + ",name=" + name, e);
            if(isNtkHardBlockFailure(e))
                return NtkPathRefreshResult.blocked();
            if(isNtkCloudflareChallengeFailure(e))
                return NtkPathRefreshResult.blocked();
        }
        return NtkPathRefreshResult.none();
    }

    private static String ntkLogSnippet(String body) {
        if(body == null)
            return "";
        String compact = body.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240);
    }

    private static String ntkSearchDebugLinks(String body, String segment) {
        if(body == null || body.length() == 0)
            return "";
        try {
            String prefix = "/" + ("webtoon".equals(segment) ? "webtoon" : "manhwa") + "/";
            StringBuilder builder = new StringBuilder();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("href=[\"']([^\"']+)").matcher(body);
            int count = 0;
            while(matcher.find() && count < 8) {
                String href = matcher.group(1);
                if(href == null || !href.contains(prefix))
                    continue;
                if(builder.length() > 0)
                    builder.append('|');
                builder.append(href.length() > 80 ? href.substring(0, 80) : href);
                count++;
            }
            return builder.toString();
        } catch(Exception e) {
            return "";
        }
    }

    private static boolean isNtkCloudflareChallengeFailure(Exception e) {
        String message = e == null ? null : e.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("cloudflare") || lower.contains("challenge");
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
            if(!shouldRetrySameNtkTitlePathRefresh(refreshedPath, currentPath))
                return false;
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
        String fallback = "";
        for(Element link : document.select("a[href]")) {
            String candidatePath = ntkApiTitlePath(segment, link.attr("href"));
            if(!candidatePath.startsWith(prefix))
                continue;
            if(!isNtkTitleNameMatch(expectedTitle, ntkSearchCandidateTitle(link)))
                continue;
            if(isNumericNtkTitleFallbackPath(candidatePath))
                return candidatePath;
            if(fallback.length() == 0)
                fallback = candidatePath;
        }
        return fallback;
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
        text = link.wholeText().trim();
        if(text.length() == 0)
            text = link.text().trim();
        String compact = text.replace('\r', '\n');
        String[] lines = compact.split("\\n+");
        for(String line : lines) {
            String candidate = line == null ? "" : line.trim();
            if(candidate.length() == 0)
                continue;
            if("웹툰".equals(candidate) || "만화".equals(candidate) || "소설".equals(candidate)
                    || "애니".equals(candidate))
                continue;
            if(candidate.matches("\\d+\\s*화?"))
                continue;
            if(candidate.contains("/"))
                continue;
            return candidate;
        }
        return text;
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
        return shouldRetrySameNtkTitlePathRefresh(refreshedPath, currentPath);
    }

    static boolean shouldPreferNtkRscTitlePayloadForTest(String titlePath) {
        return shouldPreferNtkRscTitlePayload(titlePath);
    }

    static boolean shouldPreferNtkSlugRscMetadataForTest(String titlePath, String titleKey) {
        return shouldPreferNtkSlugRscMetadata(titlePath, titleKey);
    }

    private static boolean shouldPreferNtkSlugRscMetadata(String titlePath, String titleKey) {
        // Tokenized works are served by the title RSC document. Their similarly-shaped
        // /api/{segment}/{slug}/episodes endpoint is not an episode-list route and returns 404,
        // so waiting for it before the RSC request only adds a cold network round trip. Numeric
        // works keep the authoritative API-first path and its explicit total validation.
        return isNonNumericNtkTitlePath(titlePath)
                && titleKey != null
                && !titleKey.matches("\\d{1,12}");
    }

    static boolean shouldRefreshNtkTitlePathForTest(String titlePath) {
        return shouldRefreshNtkTitlePath(null, titlePath);
    }

    static boolean shouldRefreshNtkTitlePathAfterMissingForTest(String titlePath) {
        return shouldRefreshNtkTitlePathAfterMissing(null, titlePath);
    }

    private static boolean shouldRetrySameNtkTitlePathRefresh(String refreshedPath, String currentPath) {
        return refreshedPath != null
                && refreshedPath.length() > 0
                && refreshedPath.equals(currentPath)
                && !isNonNumericNtkTitlePath(refreshedPath);
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

    private static String ntkCanonicalWorkId(JsonObject work) {
        String sourceWorkId = jsonString(work, "sourceWorkId");
        if(parsePositiveInt(sourceWorkId) > 0)
            return sourceWorkId.trim();
        String id = jsonString(work, "id");
        if(parsePositiveInt(id) > 0)
            return id.trim();
        return firstNonEmpty(sourceWorkId, id);
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
        if(eps != null && eps.size() > 0)
            ntkEpisodeListConfirmedEmpty = false;
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
        Title copy = new Title(name, thumb, author, tags, release, id, baseMode);
        copy.setPath(getPath());
        copy.setSourceSite(getSourceSite());
        copy.setNtkStatusLabel(getNtkStatusLabel());
        copy.setResumeNtkEpisodePath(getResumeNtkEpisodePath());
        copyResumeNtkImageIdentityTo(copy);
        copy.setReadingProgress(getBookmarkEpisodeId(), getBookmarkEpisodeIndex(), getEpisodeCount());
        copy.bookmark = bookmark;
        copy.bookmarked = bookmarked;
        copy.bookmarkLink = bookmarkLink;
        copy.rc = rc;
        copy.ntkEpisodeListConfirmedEmpty = ntkEpisodeListConfirmedEmpty;
        return copy;
    }

    public int getRecommend_c() {
        return rc;
    }

    public void setRecommend_c(int recommend_c) {
        this.rc = recommend_c;
    }

    public MTitle minimize(){
        Title title = new Title(name, thumb, author, tags, release, id, baseMode);
        int progressEpisodeId = getBookmark();
        if(progressEpisodeId <= 0)
            progressEpisodeId = getBookmarkEpisodeId();
        int progressIndex = getBookmarkIndex();
        if(progressIndex <= 0)
            progressIndex = getBookmarkEpisodeIndex();
        int progressCount = Math.max(getEpsCount(), getEpisodeCount());
        int releaseCount = getNtkReleaseEpisodeCount();
        if(releaseCount > progressCount)
            progressCount = releaseCount;
        title.setReadingProgress(progressEpisodeId, progressIndex, progressCount);
        title.setPath(getPath());
        title.setSourceSite(getSourceSite());
        title.setNtkStatusLabel(getNtkStatusLabel());
        title.setResumeNtkEpisodePath(getResumeNtkEpisodePath());
        copyResumeNtkImageIdentityTo(title);
        title.ntkEpisodeListConfirmedEmpty = ntkEpisodeListConfirmedEmpty;
        return title;
    }

    private void copyResumeNtkImageIdentityTo(MTitle target) {
        if(target == null)
            return;
        Manga resumeEpisode = findResumeNtkEpisodeMetadata();
        if(resumeEpisode != null) {
            target.setResumeNtkImageIdentity(
                    resumeEpisode.getNtkImageWorkId(),
                    resumeEpisode.getNtkImageEpisodeId(),
                    resumeEpisode.getNtkImageCount());
            return;
        }
        target.setResumeNtkImageIdentity(
                getResumeNtkImageWorkId(),
                getResumeNtkImageEpisodeId(),
                getResumeNtkImageCount());
    }

    private Manga findResumeNtkEpisodeMetadata() {
        if(eps == null || eps.isEmpty())
            return null;
        String resumePath = getResumeNtkEpisodePath();
        int resumeId = getBookmark() > 0 ? getBookmark() : getBookmarkEpisodeId();
        Manga idMatch = null;
        for(Manga episode : eps) {
            if(episode == null)
                continue;
            String episodePath = episode.getNtkEpisodePath();
            if(resumePath.length() > 0 && resumePath.equals(episodePath))
                return episode;
            if(idMatch == null && resumeId > 0 && episode.getId() == resumeId)
                idMatch = episode;
        }
        return idMatch;
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
