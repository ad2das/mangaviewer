package ml.melun.mangaview.mangaview;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jsoup.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getNumberFromString;


public class Title extends MTitle {
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
    private static final long PAGE_CACHE_TTL_MS = 2 * 60 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;


    public Title(String n, String t, String a, List<String> tg, String r, int id, int baseMode) {
        super(n, id, t, a, tg, r, baseMode);
    }

    public String getUrl(){
        if(isComicWolfSource())
            return "/cl?toon=" + id;
        if(isWebtoonWolfSource())
            return "/list?toon=" + id;
        return '/'+baseModeStr(baseMode)+'/'+ id;
    }


    public Title(MTitle title){
        super(title.getName(), title.getId(), title.getThumb(), title.getAuthor(), title.getTags(), title.getRelease(), title.getBaseMode());
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
        if(isComicWolfSource())
            return fetchWolfEps(client, "/cl?toon=", "/cv?toon=");
        if(isWebtoonWolfSource())
            return fetchWolfEps(client);

        for(int attempt = 0; attempt <= MAX_TIMEOUT_RETRIES; attempt++) {
            try {
                CustomHttpClient.PageResponse page = client.mgetCachedPage('/'+baseModeStr(baseMode)+'/'+ id, PAGE_CACHE_TTL_MS);
                String body = page.body;
                if(body.contains("Connect Error: Connection timed out"))
                    continue;
                Document d = Jsoup.parse(body);
                Element header = d.selectFirst("div.view-title");

                //extra info
                try{
                    Element infoTable = d.selectFirst("table.table");
                    //recommend
                    rc = Integer.parseInt(infoTable.selectFirst("button.btn-red").selectFirst("b").ownText());
                    //bookmark
                    Element bookmark = infoTable.selectFirst("a#webtoon_bookmark");
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
                    e.printStackTrace();
                }

                //thumb
                try {
                    Element thumbContainer = header == null ? null : header.selectFirst("div.view-img");
                    Element thumbImage = thumbContainer == null ? null : thumbContainer.selectFirst("img");
                    if(thumbImage != null)
                        thumb = thumbImage.attr("src");
                }catch (Exception e){}

                Elements infos = header == null ? new Elements() : header.select("div.view-content");
                //title
                try {
                    Element titleElement = infos.size() > 1 ? infos.get(1).selectFirst("b") : null;
                    if(titleElement != null)
                        name = titleElement.ownText();
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

                //eps
                String title, date;
                Manga tmp;
                int id;
                eps = new ArrayList<>();
                Set<Integer> seenEpisodeIds = new HashSet<>();
                try{
                    Element listBody = d.selectFirst("ul.list-body");
                    if(listBody == null)
                        break;
                    for(Element e : listBody.select("li.list-item")) {
                        Element titlee = e.selectFirst("a.item-subject");
                        if(titlee == null)
                            continue;
                        id = parseEpisodeId(titlee.attr("href"));
                        if(id <= 0)
                            continue;
                        if(!seenEpisodeIds.add(id)) continue;

                        title = titlee.ownText();

                        Element details = e.selectFirst("div.item-details");
                        Elements infoe = details == null ? new Elements() : details.select("span");
                        date = infoe.size() > 0 ? infoe.get(0).ownText() : "";
                        //has view-count, thumb-count and other extra info, implement later
                        tmp = new Manga(id, title, date, baseMode);
                        tmp.setMode(0);
                        eps.add(tmp);
                    }
                }catch (Exception e){e.printStackTrace();}
                break;
            }catch(Exception e) {
                e.printStackTrace();
                break;
            }
        }
        return LOAD_OK;
    }

    private int parseEpisodeId(String href) {
        try {
            String marker = baseModeStr(baseMode) + '/';
            if(href == null || !href.contains(marker))
                return -1;
            return getNumberFromString(href.split(marker)[1]);
        } catch (Exception e) {
            return -1;
        }
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
        }catch(Exception e) {
            e.printStackTrace();
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

    public void removeEps(){
        if(eps!=null) eps.clear();
    }

    public void setBookmark(int b){bookmark = b;}


    @Override
    public Title clone(){
        Title copy = new Title(name, thumb, author, tags, release, id, baseMode);
        copy.bookmark = bookmark;
        copy.bookmarked = bookmarked;
        copy.bookmarkLink = bookmarkLink;
        copy.rc = rc;
        return copy;
    }

    public int getRecommend_c() {
        return rc;
    }

    public void setRecommend_c(int recommend_c) {
        this.rc = recommend_c;
    }

    public MTitle minimize(){
        return new MTitle(name, id, thumb, author, tags, release, baseMode);
    }

    public boolean hasCounter(){
        return !(rc==0&&(bookmarkLink==null||bookmarkLink.length()==0));
    }

    public static boolean isInteger(String s) {
        if(s == null)
            return false;
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
        return !isInteger(getRelease());
    }

    private boolean isWebtoonWolfSource() {
        return baseMode == base_webtoon;
    }

    private boolean isComicWolfSource() {
        return baseMode == base_comic;
    }

}
