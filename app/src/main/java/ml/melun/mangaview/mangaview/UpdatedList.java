package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.util.ArrayList;
import java.util.List;


public class UpdatedList {
    private static final long PAGE_CACHE_TTL_MS = 30 * 1000L;
    private static final int MAX_TIMEOUT_RETRIES = 2;
    private static final int UPDATE_LIMIT = 60;

    Boolean last = false;
    ArrayList<UpdatedManga> result;
    int page = 1;
    int baseMode;
    int timeoutRetries = 0;

    public UpdatedList(int baseMode){
        this.baseMode = baseMode;
    }

    public int getPage(){
        return this.page;
    }

    public void fetch(CustomHttpClient client){
        result = new ArrayList<>();
        if(last)
            return;

        int requestedPage = page;
        for(int attempt = 0; attempt <= MAX_TIMEOUT_RETRIES; attempt++) {
            try {
                CustomHttpClient.PageResponse pageResponse = client.mgetCachedPage(getLatestPath(), PAGE_CACHE_TTL_MS);
                int code = pageResponse.code;
                String body = pageResponse.body;
                if(code >= 400)
                    return;
                if(body.contains("Connect Error: Connection timed out")){
                    timeoutRetries = attempt + 1;
                    continue;
                }
                Document document = Jsoup.parse(body);
                ArrayList<Title> titles = MainPageWebtoon.parseWolfTitles(document, baseMode, UPDATE_LIMIT);
                for(Title title : titles){
                    List<String> tags = title.getTags() == null ? new ArrayList<>() : title.getTags();
                    String date = title.getRelease() == null ? "" : title.getRelease();
                    String author = title.getAuthor() == null ? "" : title.getAuthor();
                    UpdatedManga tmp = new UpdatedManga(title.getId(), title.getName(), date, baseMode, author, tags);
                    tmp.setMode(0);
                    tmp.setTitle(title);
                    if(title.getThumb() != null)
                        tmp.addThumb(title.getThumb());
                    result.add(tmp);
                }
                page = requestedPage + 1;
                last = result.size() > 0;
                timeoutRetries = 0;
                return;
            } catch (Exception e) {
                e.printStackTrace();
                timeoutRetries = 0;
                return;
            }
        }
        timeoutRetries = 0;
    }

    private String getLatestPath() {
        if(baseMode == MTitle.base_comic)
            return "/cm?type1=complete&type2=recent&o=n";
        return "/ing?type1=day&type2=recent&o=n";
    }

    public ArrayList<UpdatedManga> getResult() {
        return result;
    }
    public boolean isLast(){return last;}


}
