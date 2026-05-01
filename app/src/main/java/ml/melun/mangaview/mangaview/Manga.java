package ml.melun.mangaview.mangaview;

import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.jsoup.*;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import okhttp3.Response;

import static ml.melun.mangaview.Utils.CODE_SCOPED_STORAGE;
import static ml.melun.mangaview.mangaview.MTitle.baseModeStr;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

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
    private static final long PAGE_CACHE_TTL_MS = 60 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;

    int baseMode = base_comic;
    int titleId = -1;

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

    public void setImgs(List<String> imgs) {
        this.imgs = imgs;
    }

    public String getThumb() {
        if (thumb == null) return "";
        return thumb;
    }

    public int fetch(CustomHttpClient client) {
        return fetch(client, true, null);
    }

    public int fetch(CustomHttpClient client, Map<String, String> cookies) {
        return fetch(client, false, cookies);
    }

    public int fetch(CustomHttpClient client, boolean doLogin, Map<String, String> cookies) {
        if(isComicWolfSource())
            return fetchWolf(client, "/cv?toon=", "/cv?toon=");
        if(isWebtoonWolfSource())
            return fetchWolf(client, "/view?toon=", "/view?toon=");

        mode = 0;
        imgs = new ArrayList<>();
        Set<String> seenImages = new LinkedHashSet<>();
        eps = new ArrayList<>();
        comments = new ArrayList<>();
        bcomments = new ArrayList<>();
        int tries = 0;
        int timeoutRetries = 0;

        while (imgs.size() == 0 && tries < 2) {
            Response r = client.mget(  baseModeStr(baseMode) + '/' + id, false, cookies);
            try {
                if(r == null)
                    break;
                String location = r.header("location");
                if (r.code() == 302 && location != null && location.contains("captcha.php")) {
                    r.close();
                    return LOAD_CAPTCHA;
                }
                String body = CustomHttpClient.readBody(r);
                r = null;
                if (body.contains("Connect Error: Connection timed out")) {
                    if(++timeoutRetries > MAX_TIMEOUT_RETRIES)
                        break;
                    continue;
                }
                timeoutRetries = 0;

                Document d = Jsoup.parse(body);

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
                            String idstr = e.attr("value");
                            if (idstr.length() > 0)
                                eps.add(new Manga(Integer.parseInt(idstr), e.ownText(), "", baseMode));
                        }
                    }
                }

                //imgs
                Element scriptElement = findImageScript(d);
                if(scriptElement != null) {
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

                //comments
                Element commentdiv = d.selectFirst("div#viewcomment");


                try {
                    for (Element e : commentdiv.selectFirst("section#bo_vc").select("div.media")) {
                        try {
                            comments.add(parseComment(e));
                        } catch (Exception e3) {
                            e3.printStackTrace();
                        }

                    }
                    for (Element e : commentdiv.selectFirst("section#bo_vcb").select("div.media")) {
                        try {
                            bcomments.add(parseComment(e));
                        } catch (Exception e3) {
                            e3.printStackTrace();
                        }

                    }
                } catch (Exception e1) {
                    e1.printStackTrace();
                }

            } catch (Exception e2) {
                e2.printStackTrace();
            }
            if (r != null) {
                r.close();
            }
            tries++;
        }
        return LOAD_OK;
    }

    private int parseEpisodeId(String href, String marker) {
        try {
            if(href == null || !href.contains(marker))
                return -1;
            return Integer.parseInt(href.split(marker)[1].split("\\?")[0]);
        } catch (Exception e) {
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

    private int fetchWolf(CustomHttpClient client, String viewPath, String epPath) {
        mode = 0;
        imgs = new ArrayList<>();
        Set<String> seenImages = new LinkedHashSet<>();
        eps = new ArrayList<>();
        comments = new ArrayList<>();
        bcomments = new ArrayList<>();

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

            for(Element img : d.select("div.image-view img.v-img")) {
                String src = img.attr("data-original");
                if(src == null || src.length() == 0)
                    src = img.attr("src");
                if(src.length() > 0 && !src.contains("sprite.png"))
                    addImageIfValid(client, seenImages, src);
            }

            if(title != null && title.getEps() != null && title.getEps().size() > 0) {
                eps = title.getEps();
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
            if(imgs.size() == 0 && attempt == 0 && client.resolveWfwfDomainNow()) {
                imgs.clear();
                seenImages.clear();
                eps.clear();
                continue;
            }
            break;
            } catch (Exception e) {
                e.printStackTrace();
                break;
            }
        }
        return LOAD_OK;
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

    private boolean isImageSourceCandidate(String img) {
        if(img == null)
            return false;
        String lower = img.toLowerCase();
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

    private Comment parseComment(Element e) {
        String user;
        String icon;
        String content;
        String timestamp;
        int likes;
        int level;
        String lvlstr;
        int indent;
        String indentstr;
        //indent
        indentstr = e.attr("style");
        if (indentstr.length() > 0)
            indent = Integer.parseInt(indentstr.substring(indentstr.lastIndexOf(':') + 1, indentstr.lastIndexOf('p'))) / 64;
        else
            indent = 0;

        //icon
        Element icone = e.selectFirst(".media-object");
        if (icone.is("img"))
            icon = icone.attr("src");
        else
            icon = "";

        Element header = e.selectFirst("div.media-heading");
        Element userSpan = header.selectFirst("span.member");
        user = userSpan.ownText();
        if (userSpan.hasClass("guest"))
            level = 0;
        else {
            lvlstr = userSpan.selectFirst("img").attr("src");
            level = Integer.parseInt(lvlstr.substring(lvlstr.lastIndexOf('/') + 1, lvlstr.lastIndexOf('.')));
        }
        timestamp = header.selectFirst("span.media-info").ownText();

        Element cbody = e.selectFirst("div.media-content");
        content = cbody.selectFirst("div:not([class])").ownText();

        Elements cspans = cbody.selectFirst("div.cmt-good-btn").select("span");
        likes = Integer.parseInt(cspans.get(cspans.size() - 1).ownText());
        return new Comment(user, timestamp, icon, content, indent, likes, level);
    }


    public List<Manga> getEps() {
        return eps;
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
        if (mode != 0) {
            if (imgs == null) {
                imgs = new ArrayList<>();
                //is offline : read image list
                if (Build.VERSION.SDK_INT >= CODE_SCOPED_STORAGE) {
                    DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(offlinePath));
                    DocumentFile[] offimgs = root == null ? null : root.listFiles();
                    if(offimgs == null)
                        return imgs;
                    Arrays.sort(offimgs, (documentFile, t1) -> String.valueOf(documentFile.getName()).compareTo(String.valueOf(t1.getName())));
                    for (DocumentFile f : offimgs) {
                        imgs.add(f.getUri().toString());
                    }
                } else {
                    File[] offimgs = new File(offlinePath).listFiles();
                    if(offimgs == null)
                        return imgs;
                    Arrays.sort(offimgs);
                    for (File img : offimgs) {
                        imgs.add(img.getAbsolutePath());
                    }
                }
            }
        }
        return imgs;
    }

    public List<Comment> getComments() {
        return comments;
    }

    public List<Comment> getBestComments() {
        return bcomments;
    }

    public int getSeed() {
        return seed;
    }

    public String toString() {
        JSONObject tmp = new JSONObject();
        try {
            tmp.put("id", id);
            tmp.put("name", name);
            tmp.put("date", date);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tmp.toString();
    }

    public void setTitle(Title title) {
        this.title = title;
        if(title != null)
            titleId = title.getId();
    }

    public void setTitleId(int titleId) {
        this.titleId = titleId;
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
            if (eps == null || eps.size() == 0) {
                return null;
            } else {
                int index = findEpisodeIndex();
                if (index < 0) return null;
                for (int i = index - 1; i >= 0; i--) {
                    Manga episode = eps.get(i);
                    if (episode != null && episode.getId() != id) return episode;
                }
                return null;
            }
        } else {
            return nextEp;
        }
    }

    public Manga prevEp() {
        if (isOnline()) {
            if (eps == null || eps.size() == 0) {
                return null;
            } else {
                int index = findEpisodeIndex();
                if (index < 0) return null;
                for (int i = index + 1; i < eps.size(); i++) {
                    Manga episode = eps.get(i);
                    if (episode != null && episode.getId() != id) return episode;
                }
                return null;
            }
        } else {
            return prevEp;
        }
    }

    private int findEpisodeIndex() {
        if (eps == null) return -1;
        for (int i = 0; i < eps.size(); i++) {
            Manga episode = eps.get(i);
            if (episode == this) return i;
        }
        for (int i = 0; i < eps.size(); i++) {
            Manga episode = eps.get(i);
            if (episode != null && episode.getId() == id) return i;
        }
        return -1;
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
    List<Comment> comments, bcomments;
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
