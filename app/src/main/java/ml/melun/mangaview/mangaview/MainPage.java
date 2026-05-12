package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Response;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;


public class MainPage {
    private static final long PAGE_CACHE_TTL_MS = 3 * 60 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;
    private static final Pattern COMIC_ID_PATH = Pattern.compile("(?:^|/)comic/(\\d+)(?:[-/?#].*)?$");
    List<Manga> recent, favUpdate, onlineRecent;
    List<RankingTitle> ranking;

    public List<RankingManga> getWeeklyRanking() {
        return weeklyRanking;
    }

    List<RankingManga> weeklyRanking;

    void fetch(CustomHttpClient client) {

        recent = new ArrayList<>();
        ranking = new ArrayList<>();
        weeklyRanking = new ArrayList<>();

        favUpdate = new ArrayList<>();
        onlineRecent = new ArrayList<>();

        for(int attempt = 0; attempt <= MAX_TIMEOUT_RETRIES; attempt++) {
            try{
                CustomHttpClient.PageResponse page = client.mgetCachedPage("/", PAGE_CACHE_TTL_MS);
                String body = page.body;
                if(body.contains("Connect Error: Connection timed out")){
                    //adblock : try again
                    continue;
                }
                Document d = Jsoup.parse(body);

            //recent
            int id;
            String name;
            String thumb;
            Manga mtmp;
            Element infos;
            Title ttmp;

            Element recentGallery = d.selectFirst("div.miso-post-gallery");
            if(recentGallery != null) {
                for(Element e : recentGallery.select("div.post-row")){
                    Element link = e.selectFirst("a[href*=comic/]");
                    infos = e.selectFirst("div.img-item");
                    if(link == null || infos == null)
                        continue;
                    id = parseComicId(link.attr("href"));
                    if(id <= 0)
                        continue;
                    Element img = infos.selectFirst("img");
                    Element subject = infos.selectFirst("b");
                    thumb = img != null ? img.attr("src") : "";
                    name = subject != null ? subject.ownText() : link.attr("title");

                    mtmp = new Manga(id, name, "", base_comic);
                    mtmp.addThumb(thumb);
                    recent.add(mtmp);
                }
            }

            int i=1;
            Element rankingGallery = d.select("div.miso-post-gallery").last();
            if(rankingGallery != null) {
                for(Element e : rankingGallery.select("div.post-row")){
                    Element link = e.selectFirst("a[href*=comic/]");
                    infos = e.selectFirst("div.img-item");
                    if(link == null || infos == null)
                        continue;
                    id = parseComicId(link.attr("href"));
                    if(id <= 0)
                        continue;
                    Element img = infos.selectFirst("img");
                    Element subject = infos.selectFirst("div.in-subject");
                    thumb = img != null ? img.attr("src") : "";
                    name = subject != null ? subject.ownText() : link.attr("title");

                    ranking.add(new RankingTitle(name, thumb, "", null, "", id, base_comic, i++));
                }
            }

            i=1;
            Element weeklyList = d.select("div.miso-post-list").last();
            if(weeklyList != null) {
                for(Element e : weeklyList.select("li.post-row")){
                    infos = e.selectFirst("a[href*=comic/]");
                    if(infos == null)
                        continue;
                    id = parseComicId(infos.attr("href"));
                    if(id <= 0)
                        continue;
                    name = infos.ownText();

                    weeklyRanking.add(new RankingManga(id, name, "", base_comic, i++));
                }
            }
                return;

            }catch(Exception e){
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        }

/*
        try{
            Response response = client.mget("");
            Document doc = Jsoup.parse(response.body().string());

            Elements list = doc.selectFirst("div.msm-post-gallery").select("div.post-row");
            for(Element e:list){
                String[] tmp_idStr = e.selectFirst("a").attr("href").toString().split("=");
                int tmp_id = Integer.parseInt(tmp_idStr[tmp_idStr.length-1]);
                String tmp_thumb = e.selectFirst("img").attr("src").toString();
                String tmp_title = e.selectFirst("img").attr("alt").toString();
                Manga tmp = new Manga(tmp_id,tmp_title,"");
                tmp.addThumb(tmp_thumb);
                recent.add(tmp);
            }
            Elements rankingWidgets = doc.select("div.rank-manga-widget");

            // online data
            Elements fav= rankingWidgets.get(0).select("li");
            rankingWidgetLiParser(fav, favUpdate);

            Elements rec = rankingWidgets.get(1).select("li");
            rankingWidgetLiParser(rec, onlineRecent);

            // ranking
            Elements rank = rankingWidgets.get(2).select("li");
            rankingWidgetLiParser(rank, ranking);

            //close response
            response.close();


        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
*/
    }

    static int parseComicId(String href) {
        if(href == null)
            return -1;
        Matcher matcher = COMIC_ID_PATH.matcher(href);
        if(!matcher.find())
            return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static class RankingTitle extends Title{
        int ranking;
        public RankingTitle(String n, String t, String a, List<String> tg, String r, int id, int baseMode, int ranking) {
            super(n, t, a, tg, r, id, baseMode);
            this.ranking = ranking;
        }

        public int getRanking() {
            return ranking;
        }
    }
    public static class RankingManga extends Manga{
        int ranking;
        public RankingManga(int i, String n, String d, int baseMode, int ranking) {
            super(i, n, d, baseMode);
            this.ranking = ranking;
        }

        public int getRanking() {
            return ranking;
        }
    }

    void rankingWidgetLiParser(Elements input, List output){
        for(Element e: input){
            String[] tmp_link = e.selectFirst("a").attr("href").split("=");
            int tmp_id = Integer.parseInt(tmp_link[tmp_link.length-1]);
            String tmp_title = e.selectFirst("div.subject").ownText();
            output.add(new Manga(tmp_id, tmp_title,"", base_comic));
        }
    }
    public MainPage(CustomHttpClient client) {
        fetch(client);
    }

    public List<Manga> getRecent() {
        return recent;
    }

    public List<Manga> getFavUpdate() {
        return favUpdate;
    }

    public List<Manga> getOnlineRecent() {
        return onlineRecent;
    }

    public List<RankingTitle> getRanking() { return ranking; }
}
