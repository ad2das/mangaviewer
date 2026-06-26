package ml.melun.mangaview.mangaview;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Locale;

import static ml.melun.mangaview.mangaview.MTitle.base_auto;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

final class NtkKeywordSearchPolicy {
    static final int KEYWORD_PAGE_SIZE = 120;

    private NtkKeywordSearchPolicy() {
    }

    static boolean shouldFetchPathsInParallel(ArrayList<String> paths) {
        return paths != null && paths.size() > 1;
    }

    static ArrayList<String> keywordApiPaths(String query, int targetBaseMode, int page, int limit) {
        ArrayList<String> paths = new ArrayList<>();
        if(page < 1)
            page = 1;
        int pageSize = keywordPageSize(limit);
        String encoded = percentEncode(query, Charset.forName("UTF-8"));
        if(targetBaseMode == base_auto || targetBaseMode == base_webtoon)
            paths.add("/api/works?keyword=" + encoded + "&page=" + page + "&pageSize=" + pageSize + "&withTotal=1");
        if(targetBaseMode == base_auto || targetBaseMode == base_comic)
            paths.add("/api/manhwa-list?keyword=" + encoded + "&page=" + page + "&pageSize=" + pageSize + "&withTotal=1");
        return paths;
    }

    static int keywordPageSize(int limit) {
        return KEYWORD_PAGE_SIZE;
    }

    static int perKindLimit(int targetBaseMode, int limit) {
        if(limit <= 0 || targetBaseMode != base_auto)
            return limit;
        return Math.max(10, limit / 2);
    }

    static boolean apiHasMore(int pathCount, boolean anyPathHasMore) {
        return pathCount > 0 && anyPathHasMore;
    }

    static ArrayList<Title> filterResults(ArrayList<Title> titles, String query, int limit) {
        ArrayList<Title> filtered = new ArrayList<>();
        if(titles == null)
            return filtered;
        String normalized = normalizeSearchText(query);
        for(Title title : titles) {
            if(title == null)
                continue;
            if(normalized.length() > 0 && !matchesKeyword(title, normalized))
                continue;
            filtered.add(title);
            if(limit > 0 && filtered.size() >= limit)
                break;
        }
        return filtered;
    }

    static boolean matchesKeyword(Title title, String normalizedQuery) {
        if(normalizedQuery.length() == 0)
            return true;
        return normalizeSearchText(title.getName()).contains(normalizedQuery);
    }

    static String normalizeSearchText(String value) {
        if(value == null)
            return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String percentEncode(String value, Charset charset) {
        try {
            return URLEncoder.encode(value == null ? "" : value, charset.name());
        } catch (Exception ignored) {
            return "";
        }
    }
}
