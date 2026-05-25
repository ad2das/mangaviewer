package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ml.melun.mangaview.mangaview.MTitle.baseModeStr;

final class WfwfEpisodeParser {
    private WfwfEpisodeParser() {
    }

    static List<Manga> parseLegacyEpisodes(Document document, int baseMode) {
        ArrayList<Manga> result = new ArrayList<>();
        Set<Integer> seenEpisodeIds = new HashSet<>();
        if(document == null)
            return result;
        Element list = document.selectFirst("ul.list-body");
        if(list == null)
            return result;
        for(Element row : list.select("li.list-item")) {
            Element titleElement = row.selectFirst("a.item-subject");
            if(titleElement == null)
                continue;
            int episodeId = legacyEpisodeId(titleElement.attr("href"), baseMode);
            if(episodeId <= 0 || !seenEpisodeIds.add(episodeId))
                continue;
            String date = "";
            Element detail = row.selectFirst("div.item-details");
            if(detail != null) {
                Elements spans = detail.select("span");
                if(spans.size() > 0)
                    date = spans.get(0).ownText();
            }
            Manga episode = new Manga(episodeId, titleElement.ownText(), date, baseMode);
            episode.setMode(0);
            result.add(episode);
        }
        return result;
    }

    static ArrayList<Manga> parseWolfEpisodes(Document document, int titleId, String viewPath, int baseMode, Title title) {
        ArrayList<Manga> episodes = new ArrayList<>();
        if(document == null)
            return episodes;

        String episodeHrefSelector = "a[href^=\"" + viewPath + titleId + "\"]";
        Elements links = document.select(".webtoon-bbs-list " + episodeHrefSelector + ":has(.list-box), "
                + ".bbs-list " + episodeHrefSelector + ":has(.list-box), "
                + episodeHrefSelector + ":has(.list-box)");
        if(links.size() == 0)
            links = document.select(episodeHrefSelector);

        Set<Integer> seenEpisodeIds = new HashSet<>();
        for(Element link : links) {
            String href = link.attr("href");
            int epId = MainPageWebtoon.getQueryInt(href, "num");
            if(epId <= 0 || !seenEpisodeIds.add(epId))
                continue;
            String date = "";
            Element dateElement = link.selectFirst("span.date, div.date, span:last-child");
            if(dateElement != null)
                date = dateElement.ownText();

            Manga manga = new Manga(epId, wolfEpisodeTitle(link, href), date, baseMode);
            manga.setMode(0);
            manga.setTitle(title);
            manga.setTitleId(titleId);
            episodes.add(manga);
        }
        EpisodeOrderingPolicy.sortByVisibleEpisodeNumber(episodes);
        return episodes;
    }

    static List<Manga> parseLegacyEpisodesForTest(String html, int baseMode) {
        return parseLegacyEpisodes(Jsoup.parse(html == null ? "" : html), baseMode);
    }

    static List<Manga> parseWolfEpisodesForTest(String html, int titleId, String viewPath, int baseMode) {
        Title title = new Title("title", "", "", null, "", titleId, baseMode);
        title.setSourceSite("wfwf");
        ArrayList<Manga> episodes = parseWolfEpisodes(Jsoup.parse(html == null ? "" : html), titleId, viewPath, baseMode, title);
        title.setEps(episodes);
        return episodes;
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
        } catch(NumberFormatException e) {
            return -1;
        }
    }

    private static String wolfEpisodeTitle(Element episodeLink, String href) {
        if(episodeLink == null)
            return MainPageWebtoon.getQueryString(href, "title");
        String epTitle = "";
        Element subject = episodeLink.selectFirst(".subject");
        if(subject != null)
            epTitle = subject.ownText().replace("\u00a0", " ").trim();
        if(epTitle.length() == 0)
            epTitle = episodeLink.ownText().replace("\u00a0", " ").trim();
        if(epTitle.length() == 0)
            epTitle = MainPageWebtoon.getQueryString(href, "title");
        return epTitle;
    }
}
