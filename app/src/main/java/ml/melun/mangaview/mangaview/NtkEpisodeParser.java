package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NtkEpisodeParser {
    private NtkEpisodeParser() {
    }

    static ParseResult parse(Document document, String segment, String titleKey, int baseMode, Title title) {
        ParseResult result = new ParseResult();
        if(document == null)
            return result;
        int titleId = title == null ? parsePositiveInt(titleKey) : title.getId();
        String imageCountMetadata = normalizeEmbeddedText(document.html());
        Set<String> seenEpisodePaths = new HashSet<>();
        for(Element link : document.select("a[href]")) {
            if(link.hasClass("cta"))
                continue;
            String href = link.attr("href");
            String epPath = normalizeEpisodePath(href, segment, titleKey);
            if(epPath.length() == 0)
                continue;
            result.matchedEpisodeLinks++;
            int epId = episodeSortId(link, epPath, segment);
            if(epId <= 0)
                continue;
            String epTitle = cleanEpisodeTitle(link);
            if(isActionTitle(epTitle))
                continue;
            if(!seenEpisodePaths.add(epPath))
                continue;
            Manga manga = new Manga(epId, epTitle, extractEpisodeDate(link, epTitle), baseMode);
            manga.setMode(0);
            manga.setTitle(title);
            manga.setTitleId(titleId);
            manga.setNtkEpisodePath(epPath);
            manga.setNtkImageEpisodeId(extractImageEpisodeId(imageCountMetadata, epPath));
            manga.setNtkImageCount(extractImageCount(imageCountMetadata, epPath));
            result.episodes.add(manga);
        }
        Collections.sort(result.episodes, (left, right) -> Integer.compare(right.getId(), left.getId()));
        EpisodeOrderingPolicy.sortByVisibleEpisodeNumber(result.episodes);
        return result;
    }

    static List<Manga> parseForTest(String html, String segment, String titleKey, int baseMode) {
        int titleId = parsePositiveInt(titleKey);
        if(titleId <= 0)
            titleId = 1;
        Title title = new Title("title", "", "", null, "", titleId, baseMode);
        title.setSourceSite("ntk");
        title.setPath("/" + segment + "/" + titleKey);
        ParseResult parsed = parse(Jsoup.parse(html == null ? "" : html), segment, titleKey, baseMode, title);
        title.setEps(parsed.episodes);
        return parsed.episodes;
    }

    static String cleanEpisodeTitleForTest(String html) {
        return cleanEpisodeTitle(Jsoup.parseBodyFragment(html).body());
    }

    static String normalizeEpisodePathForTest(String href, String segment, String titleKey) {
        return normalizeEpisodePath(href, segment, titleKey);
    }

    static int episodeSortIdForTest(String html, String epPath, String segment) {
        return episodeSortId(Jsoup.parseBodyFragment(html).body(), epPath, segment);
    }

    static boolean looksLikeErrorPage(String body) {
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

    static boolean looksLikeMissingPage(String body) {
        if(body == null || body.length() == 0)
            return true;
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if(hasEpisodeListContent(lower))
            return false;
        if(lower.contains("\"imagestoken\"") && lower.contains("\"imagemetas\""))
            return false;
        return lower.matches("(?s).*next_http_error_fallback[^\\]]*(?:404|410).*")
                || lower.matches("(?s).*<html[^>]+id=[\"']__next_error__[\"'].*")
                || lower.contains("404: this page could not be found")
                || body.contains("\uC791\uD488\uC744 \uCC3E\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4")
                || body.contains("\uD68C\uCC28 \uC5C6\uC74C");
    }

    static Element firstTitleImage(Document document, String titleKey, String titleName) {
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

    private static boolean hasEpisodeListContent(String lowerBody) {
        if(lowerBody == null || lowerBody.length() == 0)
            return false;
        return lowerBody.contains("ep-row-v2-link")
                || lowerBody.matches("(?s).*/(?:manhwa|webtoon)/[^\"'<>\\s]+/[^\"'<>\\s]+.*");
    }

    private static String cleanEpisodeTitle(Element link) {
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

    private static boolean isActionTitle(String title) {
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

    private static String normalizeEpisodePath(String href, String segment, String titleKey) {
        if(href == null || titleKey == null || titleKey.length() == 0)
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

    private static int episodeSortId(Element link, String epPath, String segment) {
        Element number = link == null ? null : link.selectFirst(".ep-row-v2-no");
        int sortId = parsePositiveInt(number == null ? "" : number.text());
        if(sortId > 0)
            return sortId;
        sortId = MainPageWebtoon.getSecondPathId(epPath, segment);
        if(sortId > 0)
            return sortId;
        return parsePositiveInt(cleanEpisodeTitle(link));
    }

    private static int extractImageCount(String html, String epPath) {
        if(html == null || html.length() == 0 || epPath == null || epPath.length() == 0)
            return 0;
        String episodeId = epPath.substring(epPath.lastIndexOf('/') + 1);
        if(episodeId.length() == 0)
            return 0;
        int count = imageCountNearEpisodeId(html, "\"sourceEpisodeId\"\\s*:\\s*\"" + Pattern.quote(episodeId) + "\"");
        if(count > 0)
            return count;
        count = imageCountNearEpisodeId(html, "\"id\"\\s*:\\s*\"" + Pattern.quote(episodeId) + "\"");
        if(count > 0)
            return count;
        return imageCountNearEpisodeId(html, "\"id\"\\s*:\\s*" + Pattern.quote(episodeId));
    }

    private static String extractImageEpisodeId(String html, String epPath) {
        if(html == null || html.length() == 0 || epPath == null || epPath.length() == 0)
            return "";
        String episodeId = epPath.substring(epPath.lastIndexOf('/') + 1);
        if(episodeId.length() == 0)
            return "";
        String internalId = episodeIdNearSourceEpisodeId(html, episodeId);
        if(internalId.length() > 0)
            return internalId;
        String sourceId = sourceEpisodeIdNearEpisodeId(html, "\"id\"\\s*:\\s*\"" + Pattern.quote(episodeId) + "\"");
        if(sourceId.length() > 0)
            return sourceId;
        sourceId = sourceEpisodeIdNearEpisodeId(html, "\"slug\"\\s*:\\s*\"" + Pattern.quote(episodeId) + "\"");
        if(sourceId.length() > 0)
            return sourceId;
        if(episodeId.matches("\\d+"))
            return episodeId;
        return "";
    }

    private static String episodeIdNearSourceEpisodeId(String html, String sourceEpisodeId) {
        Matcher sourceMatcher = Pattern.compile("\"sourceEpisodeId\"\\s*:\\s*\"" + Pattern.quote(sourceEpisodeId) + "\"")
                .matcher(html);
        while(sourceMatcher.find()) {
            int start = Math.max(0, sourceMatcher.start() - 900);
            int end = sourceMatcher.start();
            Matcher idMatcher = Pattern.compile("\"id\"\\s*:\\s*\"?(\\d{1,12})\"?")
                    .matcher(html.substring(start, end));
            String id = "";
            while(idMatcher.find())
                id = idMatcher.group(1);
            if(id.length() > 0)
                return id;
        }
        return "";
    }

    private static String sourceEpisodeIdNearEpisodeId(String html, String idPattern) {
        Matcher idMatcher = Pattern.compile(idPattern).matcher(html);
        while(idMatcher.find()) {
            int start = Math.max(0, idMatcher.start() - 900);
            int end = Math.min(html.length(), idMatcher.end() + 900);
            Matcher sourceMatcher = Pattern.compile("\"sourceEpisodeId\"\\s*:\\s*\"?(\\d{1,12})\"?")
                    .matcher(html.substring(start, end));
            if(sourceMatcher.find())
                return sourceMatcher.group(1);
        }
        return "";
    }

    private static int imageCountNearEpisodeId(String html, String idPattern) {
        Matcher idMatcher = Pattern.compile(idPattern).matcher(html);
        while(idMatcher.find()) {
            int end = Math.min(html.length(), idMatcher.end() + 900);
            Matcher countMatcher = Pattern.compile("\"imageCount\"\\s*:\\s*(\\d{1,4})")
                    .matcher(html.substring(idMatcher.start(), end));
            if(countMatcher.find()) {
                int count = parsePositiveInt(countMatcher.group(1));
                if(count > 0)
                    return count;
            }
        }
        return 0;
    }

    private static String normalizeEmbeddedText(String source) {
        if(source == null)
            return "";
        return source.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\\"", "\"")
                .replace("&quot;", "\"");
    }

    static int parsePositiveInt(String value) {
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

    private static String extractEpisodeDate(Element link, String epTitle) {
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

    static final class ParseResult {
        final ArrayList<Manga> episodes = new ArrayList<>();
        int matchedEpisodeLinks;
    }
}
