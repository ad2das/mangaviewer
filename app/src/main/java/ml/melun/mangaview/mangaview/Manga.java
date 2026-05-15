package ml.melun.mangaview.mangaview;

import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

import android.content.Context;

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

    int baseMode = base_comic;
    int titleId = -1;
    private String ntkEpisodePath = "";

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
        return ntkEpisodePath == null ? "" : ntkEpisodePath;
    }

    public void setNtkEpisodePath(String ntkEpisodePath) {
        this.ntkEpisodePath = ntkEpisodePath == null ? "" : ntkEpisodePath.trim();
    }

    public void setImgs(List<String> imgs) {
        this.imgs = imgs;
    }

    public synchronized boolean copyViewerStateFrom(Manga source) {
        if(source == null
                || source.getId() != getId()
                || source.getBaseMode() != getBaseMode()
                || source.getTitleId() != getTitleId())
            return false;
        List<String> sourceImages = source.getImgs(null);
        if(sourceImages != null)
            imgs = new ArrayList<>(sourceImages);
        List<Manga> sourceEpisodes = safeEpisodeCopy(source.getEps());
        if(sourceEpisodes != null)
            eps = sourceEpisodes;
        seed = source.getSeed();
        if(source.getName() != null && source.getName().length() > 0)
            name = source.getName();
        if(source.getTitle() != null)
            setTitle(source.getTitle());
        else
            setTitleId(source.getTitleId());
        setNtkEpisodePath(source.getNtkEpisodePath());
        return true;
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
        if(client.isNtk())
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
                ml.melun.mangaview.report.CrashReporter.record(e2);
            }
            if (r != null) {
                r.close();
            }
            tries++;
        }
        restoreBetterEpisodeList(previousEpisodes);
        attachEpisodeSeriesMetadata();
        return LOAD_OK;
    }

    private int fetchNtk(CustomHttpClient client) {
        mode = 0;
        List<Manga> previousEpisodes = safeEpisodeCopy(eps);
        imgs = new ArrayList<>();
        Set<String> seenImages = new LinkedHashSet<>();
        eps = new ArrayList<>();
        try {
            int tid = titleId;
            if(tid <= 0 && title != null)
                tid = title.getId();
            if(tid <= 0)
                return LOAD_OK;
            String segment = baseMode == MTitle.base_webtoon ? "webtoon" : "manhwa";
            String path = getNtkEpisodePath();
            if(path.length() == 0)
                path = "/" + segment + "/" + tid + "/" + id;
            CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
            Document d = Jsoup.parse(page.body);

            Element h1 = d.selectFirst("h1");
            if(h1 != null)
                name = h1.text().trim();

            for(Element img : d.select("img")) {
                for(String attr : new String[]{"data-original", "data-src", "data-lazy-src", "src"}) {
                    String src = img.attr(attr);
                    if(isNtkPageImage(img, src))
                        addImageIfValid(client, seenImages, src);
                }
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
            if(isCloudflareChallenge(e))
                return LOAD_CAPTCHA;
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        restoreBetterEpisodeList(previousEpisodes);
        attachEpisodeSeriesMetadata();
        return LOAD_OK;
    }

    private static boolean isCloudflareChallenge(Exception e) {
        String message = e == null ? null : e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("cloudflare");
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

        for(int attempt = 0; attempt < 2; attempt++) {
            try {
            int titleId = this.titleId;
            if(titleId <= 0 && title != null)
                titleId = title.getId();
            if(titleId <= 0)
                return LOAD_OK;

            CustomHttpClient.PageResponse page = client.mgetCachedPage(viewPath + titleId + "&num=" + id, PAGE_CACHE_TTL_MS);
            Document d = Jsoup.parse(page.body);

            try {
                Element header = d.selectFirst("div.image-view h2 span");
                if(header != null)
                    name = header.ownText();
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
                ml.melun.mangaview.report.CrashReporter.record(e);
                break;
            }
        }
        restoreBetterEpisodeList(previousEpisodes);
        attachEpisodeSeriesMetadata();
        return LOAD_OK;
    }

    private boolean hasReachableWolfPageImage(CustomHttpClient client) {
        if(client == null || imgs == null || imgs.size() == 0)
            return false;
        int checked = 0;
        for(String img : imgs) {
            if(img == null || img.length() == 0)
                continue;
            Integer code = probeWolfImage(client, img);
            if(code == null)
                return true;
            if(code >= 200 && code < 400)
                return true;
            if(code == 403 || code == 429)
                return true;
            checked++;
            if(checked >= 2)
                break;
        }
        return false;
    }

    public synchronized boolean ensureReachablePageImages(CustomHttpClient client) {
        if(client == null || !isOnline())
            return true;
        if(!(isComicWolfSource() || isWebtoonWolfSource()))
            return true;
        if(imgs == null || imgs.size() == 0)
            return false;
        if(hasReachableWolfPageImage(client))
            return true;
        imgs.clear();
        client.clearPageCache();
        return false;
    }

    private Integer probeWolfImage(CustomHttpClient client, String url) {
        Response response = null;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", client.agent);
            headers.put("Referer", client.getUrl(baseMode));
            headers.put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            headers.put("Range", "bytes=0-0");
            response = client.get(url, headers);
            return response == null ? null : response.code();
        } catch (Exception e) {
            return null;
        } finally {
            if(response != null)
                response.close();
        }
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
        if(context.contains("banner")
                || context.contains("advert")
                || context.contains("sponsor")
                || context.contains("popup"))
            return false;
        return lower.contains("/webtoon_uploads/")
                || lower.contains("/manhwa_uploads/")
                || lower.contains("/comic_uploads/")
                || lower.contains("/blacktoon/episodes/");
    }

    private static String ntkImageContext(Element img) {
        if(img == null)
            return "";
        StringBuilder context = new StringBuilder();
        context.append(img.id()).append(' ')
                .append(img.className()).append(' ')
                .append(img.attr("alt"));
        for(Element parent = img.parent(); parent != null; parent = parent.parent()) {
            context.append(' ')
                    .append(parent.id()).append(' ')
                    .append(parent.className());
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
        else if(img.startsWith("/"))
            img = client.getUrl(baseMode) + img;
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
        this.eps = eps;
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

    public synchronized List<String> getImgs(Context context) {
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
        if(titleId > 0 && p != null && p.isNtkSite()) {
            String segment = baseMode == MTitle.base_webtoon ? "webtoon" : "manhwa";
            return "/" + segment + "/" + titleId + "/" + id;
        }
        if(isComicWolfSource()) {
            int tid = titleId;
            if(tid <= 0 && title != null)
                tid = title.getId();
            if(tid > 0)
                return "/cv?toon=" + tid + "&num=" + id;
        }
        if(isWebtoonWolfSource()) {
            int tid = titleId;
            if(tid <= 0 && title != null)
                tid = title.getId();
            if(tid > 0)
                return "/view?toon=" + tid + "&num=" + id;
        }
        return '/' + baseModeStr(baseMode) + '/' + id;
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
                for (int i = index - 1; i >= 0; i--) {
                    Manga episode = episodes.get(i);
                    if (sameSeriesEpisode(episode) && episode.getId() != id) return episode;
                }
                return null;
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
                for (int i = index + 1; i < episodes.size(); i++) {
                    Manga episode = episodes.get(i);
                    if (sameSeriesEpisode(episode) && episode.getId() != id) return episode;
                }
                return null;
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
            if (sameSeriesEpisode(episode) && episode.getId() == id) return i;
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
    Listener listener;
    Manga nextEp, prevEp;

    public interface Listener {
        void setMessage(String msg);
    }
}
