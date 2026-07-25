package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NtkEpisodeParser {
    private static final Pattern FAST_EPISODE_ANCHOR_PATTERN =
            Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
    private static final Pattern FAST_HREF_PATTERN =
            Pattern.compile("(?is)\\bhref\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern FAST_STRONG_PATTERN =
            Pattern.compile("(?is)<strong\\b[^>]*>(.*?)</strong>");
    private static final Pattern FAST_EPISODE_NUMBER_PATTERN =
            Pattern.compile("(?is)<span\\b[^>]*class\\s*=\\s*[\"'][^\"']*ep-row-v2-no[^\"']*[\"'][^>]*>(.*?)</span>");
    private static final Pattern FAST_DATE_PATTERN =
            Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{2})");

    private NtkEpisodeParser() {
    }

    static ParseResult parse(Document document, String segment, String titleKey, int baseMode, Title title) {
        ParseResult result = new ParseResult();
        if(document == null)
            return result;
        int titleId = title == null ? parsePositiveInt(titleKey) : title.getId();
        String imageCountMetadata = normalizeEmbeddedText(document.html());
        String titleImageWorkId = preferredTitleImageWorkId(
                extractTitleImageWorkId(imageCountMetadata, titleKey), titleKey, titleId);
        result.definitiveEmptyEpisodeList = looksLikeDefinitiveEmptyEpisodeList(imageCountMetadata);
        boolean preserveViewerPayloadHint = shouldPreserveViewerPayloadHint(imageCountMetadata);
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
            String imageEpisodeId = extractImageEpisodeId(imageCountMetadata, epPath);
            int imageCount = extractImageCount(imageCountMetadata, epPath);
            manga.setNtkImageEpisodeId(imageEpisodeId);
            if(titleImageWorkId.length() > 0)
                manga.setNtkImageWorkId(titleImageWorkId);
            manga.setNtkImageCount(imageCount);
            if(preserveViewerPayloadHint)
                manga.setNtkViewerPayloadHint(compactViewerPayloadHint(
                        imageCountMetadata, epPath, titleId, imageEpisodeId, imageCount));
            else if(imageCount > 0)
                manga.setNtkViewerPayloadHint(compactEpisodeCountHint(
                        epPath, titleId, imageEpisodeId, imageCount));
            result.episodes.add(manga);
        }
        appendEmbeddedEpisodes(result, imageCountMetadata, seenEpisodePaths, segment, titleKey, baseMode, title, titleId);
        Collections.sort(result.episodes, (left, right) -> Integer.compare(right.getId(), left.getId()));
        EpisodeOrderingPolicy.sortByVisibleEpisodeNumber(result.episodes);
        return result;
    }

    static ParseResult parseEpisodeRowsFast(
            String payload,
            String segment,
            String titleKey,
            int baseMode,
            Title title
    ) {
        ParseResult result = new ParseResult();
        if(payload == null || payload.length() == 0
                || segment == null || segment.length() == 0
                || titleKey == null || titleKey.length() == 0)
            return result;
        String normalized = normalizeFastEpisodePayload(payload);
        result.definitiveEmptyEpisodeList = looksLikeDefinitiveEmptyEpisodeList(normalized);
        int titleId = title == null ? parsePositiveInt(titleKey) : title.getId();
        String imageWorkId = preferredTitleImageWorkId(
                extractTitleImageWorkId(normalized, titleKey), titleKey, titleId);
        Set<String> seenEpisodePaths = new HashSet<>();
        Matcher anchorMatcher = FAST_EPISODE_ANCHOR_PATTERN.matcher(normalized);
        while(anchorMatcher.find()) {
            String attributes = anchorMatcher.group(1);
            if(attributes == null || !attributes.toLowerCase(java.util.Locale.ROOT)
                    .contains("ep-row-v2-link"))
                continue;
            Matcher hrefMatcher = FAST_HREF_PATTERN.matcher(attributes);
            if(!hrefMatcher.find())
                continue;
            String epPath = normalizeEpisodePath(hrefMatcher.group(1), segment, titleKey);
            if(epPath.length() == 0 || !seenEpisodePaths.add(epPath))
                continue;
            result.matchedEpisodeLinks++;
            String row = anchorMatcher.group(2);
            String epTitle = fastEpisodeTitle(row);
            if(isActionTitle(epTitle))
                continue;
            Matcher numberMatcher = FAST_EPISODE_NUMBER_PATTERN.matcher(row == null ? "" : row);
            int epId = numberMatcher.find() ? parsePositiveInt(cleanFastEpisodeText(numberMatcher.group(1))) : 0;
            if(epId <= 0)
                epId = MainPageWebtoon.getSecondPathId(epPath, segment);
            if(epId <= 0)
                epId = parsePositiveInt(Manga.visibleEpisodeNumberKey(epTitle));
            if(epId <= 0)
                epId = parsePositiveInt(epTitle);
            if(epId <= 0)
                continue;
            String date = "";
            Matcher dateMatcher = FAST_DATE_PATTERN.matcher(row == null ? "" : row);
            if(dateMatcher.find())
                date = dateMatcher.group(1);
            Manga manga = new Manga(epId, epTitle, date, baseMode);
            manga.setMode(0);
            manga.setTitle(title);
            manga.setTitleId(titleId);
            manga.setNtkEpisodePath(epPath);
            String sourceEpisodeId = epPath.substring(epPath.lastIndexOf('/') + 1);
            manga.setNtkImageEpisodeId(sourceEpisodeId);
            if(imageWorkId.length() > 0)
                manga.setNtkImageWorkId(imageWorkId);
            result.episodes.add(manga);
        }
        mergeEmbeddedEpisodeMetadata(
                result, normalized, seenEpisodePaths, segment, titleKey, baseMode, title, titleId);
        EpisodeOrderingPolicy.sortByVisibleEpisodeNumber(result.episodes);
        return result;
    }

    private static void mergeEmbeddedEpisodeMetadata(
            ParseResult result,
            String normalized,
            Set<String> seenEpisodePaths,
            String segment,
            String titleKey,
            int baseMode,
            Title title,
            int titleId
    ) {
        ParseResult embedded = new ParseResult();
        appendEmbeddedEpisodes(
                embedded,
                normalized,
                new HashSet<>(),
                segment,
                titleKey,
                baseMode,
                title,
                titleId);
        if(embedded.episodes.size() == 0)
            return;
        Map<String, Manga> parsedByPath = new HashMap<>();
        for(Manga episode : result.episodes) {
            if(episode != null && episode.getNtkEpisodePath().length() > 0)
                parsedByPath.put(episode.getNtkEpisodePath(), episode);
        }
        for(Manga metadata : embedded.episodes) {
            if(metadata == null || metadata.getNtkEpisodePath().length() == 0)
                continue;
            Manga episode = parsedByPath.get(metadata.getNtkEpisodePath());
            if(episode == null) {
                if(seenEpisodePaths.add(metadata.getNtkEpisodePath())) {
                    result.episodes.add(metadata);
                    parsedByPath.put(metadata.getNtkEpisodePath(), metadata);
                }
                continue;
            }
            if(metadata.getNtkImageEpisodeId().length() > 0)
                episode.setNtkImageEpisodeId(metadata.getNtkImageEpisodeId());
            if(metadata.getNtkImageWorkId().length() > 0)
                episode.setNtkImageWorkId(metadata.getNtkImageWorkId());
            if(metadata.getNtkImageCount() > 0)
                episode.setNtkImageCount(metadata.getNtkImageCount());
            if(metadata.getNtkViewerPayloadHint().length() > 0)
                episode.setNtkViewerPayloadHint(metadata.getNtkViewerPayloadHint());
        }
    }

    private static String normalizeFastEpisodePayload(String payload) {
        return payload.replace("\\u003c", "<")
                .replace("\\u003C", "<")
                .replace("\\u003e", ">")
                .replace("\\u003E", ">")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'");
    }

    private static String fastEpisodeTitle(String row) {
        String value = row == null ? "" : row;
        Matcher titleMatcher = FAST_STRONG_PATTERN.matcher(value);
        if(titleMatcher.find())
            value = titleMatcher.group(1);
        else {
            Matcher numberMatcher = FAST_EPISODE_NUMBER_PATTERN.matcher(value);
            value = numberMatcher.find() ? numberMatcher.group(1) : "";
        }
        return cleanFastEpisodeText(value);
    }

    private static String cleanFastEpisodeText(String value) {
        if(value == null || value.length() == 0)
            return "";
        return value.replaceAll("(?is)<[^>]+>", " ")
                .replace("\\n", " ")
                .replace("\\t", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
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

    private static boolean looksLikeDefinitiveEmptyEpisodeList(String html) {
        if(html == null || html.length() == 0)
            return false;
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ep-empty")
                && (html.contains("\uD68C\uCC28 \uC815\uBCF4\uAC00 \uC544\uC9C1 \uC218\uC9D1\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4")
                || html.contains("\uACE7 \uD68C\uCC28 \uBAA9\uB85D\uC774 \uC5C5\uB370\uC774\uD2B8\uB429\uB2C8\uB2E4")
                || lower.contains("episode list is not available"));
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

    private static void appendEmbeddedEpisodes(ParseResult result, String html, Set<String> seenEpisodePaths,
                                               String segment, String titleKey, int baseMode, Title title,
                                               int titleId) {
        if(result == null || html == null || html.length() == 0
                || segment == null || segment.length() == 0
                || titleKey == null || titleKey.length() == 0)
            return;
        int searchFrom = 0;
        while(searchFrom < html.length()) {
            Matcher listMatcher = Pattern.compile("\"(?:allEpisodes|episodes)\"\\s*:\\s*\\[").matcher(html);
            if(!listMatcher.find(searchFrom))
                return;
            int arrayStart = html.indexOf('[', listMatcher.end() - 1);
            int arrayEnd = findMatchingBracket(html, arrayStart, '[', ']');
            if(arrayStart < 0 || arrayEnd <= arrayStart)
                return;
            appendEmbeddedEpisodeArray(result, html.substring(arrayStart + 1, arrayEnd),
                    seenEpisodePaths, segment, titleKey, baseMode, title, titleId, html);
            searchFrom = arrayEnd + 1;
        }
    }

    private static void appendEmbeddedEpisodeArray(ParseResult result, String arrayBody,
                                                   Set<String> seenEpisodePaths, String segment,
                                                   String titleKey, int baseMode, Title title,
                                                   int titleId, String imageCountMetadata) {
        int searchFrom = 0;
        while(searchFrom < arrayBody.length()) {
            int objectStart = arrayBody.indexOf('{', searchFrom);
            if(objectStart < 0)
                return;
            int objectEnd = findMatchingBracket(arrayBody, objectStart, '{', '}');
            if(objectEnd <= objectStart)
                return;
            addEmbeddedEpisode(result, arrayBody.substring(objectStart, objectEnd + 1),
                    seenEpisodePaths, segment, titleKey, baseMode, title, titleId, imageCountMetadata);
            searchFrom = objectEnd + 1;
        }
    }

    private static void addEmbeddedEpisode(ParseResult result, String objectJson,
                                           Set<String> seenEpisodePaths, String segment,
                                           String titleKey, int baseMode, Title title,
                                           int titleId, String imageCountMetadata) {
        String sourceEpisodeId = embeddedStringField(objectJson, "sourceEpisodeId");
        if(sourceEpisodeId.length() == 0)
            return;
        int imageCount = embeddedIntField(objectJson, "imageCount");
        int imagesStatus = embeddedIntField(objectJson, "imagesStatus");
        if(imagesStatus > 0 && imagesStatus != 2 && imageCount <= 0)
            return;
        String epPath = "/" + segment + "/" + titleKey + "/" + sourceEpisodeId;
        if(!seenEpisodePaths.add(epPath))
            return;
        int epId = embeddedIntField(objectJson, "epNo");
        if(epId <= 0)
            epId = embeddedIntField(objectJson, "number");
        if(epId <= 0)
            epId = embeddedIntField(objectJson, "displayNumber");
        if(epId <= 0)
            epId = parsePositiveInt(sourceEpisodeId);
        if(epId <= 0)
            epId = result.episodes.size() + 1;
        String epTitle = embeddedStringField(objectJson, "title");
        if(epTitle.length() == 0)
            epTitle = String.valueOf(epId);
        Manga manga = new Manga(epId, epTitle, "", baseMode);
        manga.setMode(0);
        manga.setTitle(title);
        manga.setTitleId(titleId);
        manga.setNtkEpisodePath(epPath);
        String titleImageWorkId = preferredTitleImageWorkId(
                extractTitleImageWorkId(imageCountMetadata, titleKey), titleKey, titleId);
        if(titleImageWorkId.length() > 0)
            manga.setNtkImageWorkId(titleImageWorkId);
        String imageEpisodeId = "";
        String embeddedId = embeddedStringField(objectJson, "id");
        if(!sourceEpisodeId.matches("\\d+") && embeddedId.matches("\\d{1,12}"))
            imageEpisodeId = embeddedId;
        if(imageEpisodeId.length() == 0)
            imageEpisodeId = extractImageEpisodeId(imageCountMetadata, epPath);
        manga.setNtkImageEpisodeId(imageEpisodeId.length() == 0 ? sourceEpisodeId : imageEpisodeId);
        if(imageCount <= 0)
            imageCount = extractImageCount(imageCountMetadata, epPath);
        manga.setNtkImageCount(imageCount);
        if(shouldPreserveViewerPayloadHint(imageCountMetadata))
            manga.setNtkViewerPayloadHint(compactViewerPayloadHint(
                    imageCountMetadata, epPath, titleId, manga.getNtkImageEpisodeId(), imageCount));
        else if(imageCount > 0)
            manga.setNtkViewerPayloadHint(compactEpisodeCountHint(
                    epPath, titleId, manga.getNtkImageEpisodeId(), imageCount));
        result.episodes.add(manga);
    }

    private static String compactEpisodeCountHint(String epPath, int titleId,
                                                  String imageEpisodeId, int imageCount) {
        if(epPath == null || epPath.length() == 0 || imageCount <= 0)
            return "";
        String episodeId = epPath.substring(epPath.lastIndexOf('/') + 1);
        StringBuilder builder = new StringBuilder(192);
        builder.append("{\"sourceWorkId\":\"").append(titleId).append("\"");
        if(episodeId.length() > 0)
            builder.append(",\"episodeId\":\"").append(jsonEscape(episodeId)).append("\"");
        if(imageEpisodeId != null && imageEpisodeId.length() > 0)
            builder.append(",\"sourceEpisodeId\":\"")
                    .append(jsonEscape(imageEpisodeId)).append("\"");
        builder.append(",\"episodePath\":\"").append(jsonEscape(epPath)).append("\"");
        builder.append(",\"imageCount\":").append(imageCount);
        builder.append(",\"episodeCountAuthority\":\"title-document-v1\"}");
        return builder.toString();
    }

    private static String extractTitleImageWorkId(String html, String titleKey) {
        if(html == null || html.length() == 0)
            return "";
        String normalized = html.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/");
        String pathWorkId = titleKey == null ? "" : titleKey.trim();
        String[] patterns = new String[]{
                "(?i)/(?:blacktoon/)?thumbs/(\\d{1,12})\\.(?:png|jpg|jpeg|webp)",
                "(?i)https?://[^\"'<>\\s]+/(\\d{1,12})/[^\"'<>\\s]+\\.(?:png|jpg|jpeg|webp)"
        };
        for(String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(normalized);
            while(matcher.find()) {
                String candidate = matcher.group(1);
                if(candidate != null && candidate.matches("\\d{1,12}")
                        && !candidate.equals(pathWorkId))
                    return candidate;
            }
        }
        return "";
    }

    private static String preferredTitleImageWorkId(String extractedWorkId, String titleKey, int titleId) {
        String extracted = extractedWorkId == null ? "" : extractedWorkId.trim();
        if(extracted.matches("\\d{1,12}"))
            return extracted;
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

    private static String compactViewerPayloadHint(String html, String epPath, int titleId,
                                                   String imageEpisodeId, int imageCount) {
        if(html == null || html.length() == 0)
            return "";
        String imageApiHint = compactViewerImageApiPayloadHint(html, epPath, imageCount);
        if(imageApiHint.length() > 0)
            return imageApiHint;
        if(html.length() <= 48_000 && !hasEpisodeMetadataPayload(html))
            return html;
        String episodeId = "";
        if(epPath != null) {
            int slash = epPath.lastIndexOf('/');
            if(slash >= 0 && slash + 1 < epPath.length())
                episodeId = epPath.substring(slash + 1);
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append("{\"sourceWorkId\":\"").append(titleId).append("\"");
        if(episodeId.length() > 0)
            builder.append(",\"episodeId\":\"").append(jsonEscape(episodeId)).append("\"");
        if(imageEpisodeId != null && imageEpisodeId.length() > 0)
            builder.append(",\"sourceEpisodeId\":\"").append(jsonEscape(imageEpisodeId)).append("\"");
        if(epPath != null && epPath.length() > 0)
            builder.append(",\"episodePath\":\"").append(jsonEscape(epPath)).append("\"");
        if(imageCount > 0)
            builder.append(",\"imageCount\":").append(imageCount);
        builder.append(",\"episodes\":true}");
        return builder.toString();
    }

    private static String compactViewerImageApiPayloadHint(String html, String epPath, int imageCount) {
        if(html == null || html.length() == 0)
            return "";
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        boolean hasProtectedImageApi = lower.contains("imageapipath")
                && (lower.contains("/api/webtoon-images") || lower.contains("/api/manhwa-images"));
        List<String> directImages = new ArrayList<>(directViewerPayloadImages(html));
        if(hasProtectedImageApi)
            directImages.removeIf(url -> url != null
                    && url.toLowerCase(java.util.Locale.ROOT).contains("/board_uploads/"));
        if(!hasProtectedImageApi)
            return directImages.isEmpty() ? "" : compactDirectViewerPayloadHint(
                    html, epPath, imageCount, directImages);
        String sourceWorkId = firstJsonStringField(html, "sourceWorkId");
        String episodeId = firstJsonStringField(html, "episodeId");
        String token = firstJsonStringField(html, "token");
        if(token.length() == 0)
            token = firstJsonStringField(html, "imagesToken");
        String imageApiPath = firstJsonStringField(html, "imageApiPath");
        if(sourceWorkId.length() == 0 || episodeId.length() == 0
                || token.length() == 0 || imageApiPath.length() == 0)
            return directImages.isEmpty() ? "" : compactDirectViewerPayloadHint(
                    html, epPath, imageCount, directImages);
        String scopePath = firstJsonStringField(html, "scopePath");
        if(scopePath.length() == 0 && epPath != null)
            scopePath = epPath;
        int pageCount = imageCount > 0 ? imageCount : Math.max(countViewerImagePages(html), directImages.size());
        StringBuilder builder = new StringBuilder(Math.max(256, pageCount * 96));
        builder.append("{\"sourceWorkId\":\"").append(jsonEscape(sourceWorkId)).append("\"");
        builder.append(",\"episodeId\":\"").append(jsonEscape(episodeId)).append("\"");
        builder.append(",\"token\":\"").append(jsonEscape(token)).append("\"");
        builder.append(",\"imageApiPath\":\"").append(jsonEscape(imageApiPath)).append("\"");
        if(scopePath.length() > 0)
            builder.append(",\"scopePath\":\"").append(jsonEscape(scopePath)).append("\"");
        builder.append(",\"images\":[");
        int safePageCount = Math.max(1, Math.min(pageCount <= 0 ? 1 : pageCount, 400));
        for(int page = 1; page <= safePageCount; page++) {
            if(page > 1)
                builder.append(',');
            builder.append("{\"page\":").append(page).append('}');
            if(page <= directImages.size())
                builder.insert(builder.length() - 1,
                        ",\"src\":\"" + jsonEscape(directImages.get(page - 1)) + "\"");
        }
        builder.append("]}");
        return builder.toString();
    }

    private static String compactDirectViewerPayloadHint(String html, String epPath, int imageCount,
                                                         List<String> directImages) {
        if(directImages == null || directImages.isEmpty())
            return "";
        int count = imageCount > 0 ? Math.min(imageCount, directImages.size()) : directImages.size();
        StringBuilder builder = new StringBuilder(Math.max(256, count * 96));
        builder.append("{");
        if(epPath != null && epPath.length() > 0)
            builder.append("\"scopePath\":\"").append(jsonEscape(epPath)).append("\",");
        String imagesToken = firstJsonStringField(html, "imagesToken");
        if(imagesToken.length() == 0)
            imagesToken = firstJsonStringField(html, "token");
        if(imagesToken.length() > 0)
            builder.append("\"imagesToken\":\"").append(jsonEscape(imagesToken)).append("\",");
        builder.append("\"images\":[");
        for(int index = 0; index < count; index++) {
            if(index > 0)
                builder.append(',');
            builder.append("{\"page\":").append(index + 1)
                    .append(",\"src\":\"").append(jsonEscape(directImages.get(index))).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private static List<String> directViewerPayloadImages(String html) {
        if(html == null || html.length() == 0)
            return Collections.emptyList();
        String normalized = html.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/");
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "(?i)https?://[^\"'<>\\\\\\s]+/(?:webtoon_uploads|manhwa_uploads|comic_uploads|board_uploads)/[^\"'<>\\\\\\s]+\\.(?:jpg|jpeg|png|webp|gif)(?:[?#][^\"'<>\\\\\\s]*)?")
                .matcher(normalized);
        while(matcher.find() && urls.size() < 512) {
            String url = matcher.group();
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            if(lower.contains("banner") || lower.contains("advert")
                    || lower.contains("sponsor") || lower.contains("popup"))
                continue;
            if(lower.contains("/board_uploads/")
                    && isPageChromeBannerContext(normalized, matcher.start(), matcher.end()))
                continue;
            urls.add(url);
        }
        return urls.isEmpty() ? Collections.emptyList() : new ArrayList<>(urls);
    }

    private static boolean isPageChromeBannerContext(String html, int matchStart, int matchEnd) {
        if(html == null || html.length() == 0)
            return false;
        int start = Math.max(0, matchStart - 420);
        int end = Math.min(html.length(), matchEnd + 220);
        String context = html.substring(start, end).toLowerCase(java.util.Locale.ROOT);
        return context.contains("data-banner-id")
                || context.contains("data-banner-href")
                || context.contains("thema-home-banner-button");
    }

    private static String firstJsonStringField(String html, String field) {
        if(html == null || html.length() == 0 || field == null || field.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(html);
        if(matcher.find())
            return matcher.group(1);
        matcher = Pattern.compile("\\\\\"" + Pattern.quote(field) + "\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]*)\\\\\"")
                .matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int countViewerImagePages(String html) {
        if(html == null || html.length() == 0)
            return 0;
        int maxPage = 0;
        Matcher matcher = Pattern.compile("\"page\"\\s*:\\s*(\\d{1,4})").matcher(html);
        while(matcher.find()) {
            try {
                maxPage = Math.max(maxPage, Integer.parseInt(matcher.group(1)));
            } catch(Exception ignored) {
            }
        }
        matcher = Pattern.compile("data-theme-page=\"(\\d{1,4})\"").matcher(html);
        while(matcher.find()) {
            try {
                maxPage = Math.max(maxPage, Integer.parseInt(matcher.group(1)));
            } catch(Exception ignored) {
            }
        }
        return maxPage;
    }

    private static boolean hasEpisodeMetadataPayload(String html) {
        if(html == null || html.length() == 0)
            return false;
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("sourceworkid")
                && (lower.contains("episodeid")
                || lower.contains("sourceepisodeid")
                || lower.contains("episodes")
                || lower.contains("latestepisodenumber")
                || lower.contains("\"slug\"")
                || lower.contains("\\\"slug\\\""));
    }

    private static String jsonEscape(String value) {
        if(value == null || value.length() == 0)
            return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean shouldPreserveViewerPayloadHint(String html) {
        if(html == null || html.length() == 0)
            return false;
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        boolean hasImagesTokenPayload = lower.contains("imagestoken") && lower.contains("imagemetas");
        boolean hasImageApiPayload = lower.contains("imageapipath")
                && (lower.contains("/api/webtoon-images") || lower.contains("/api/manhwa-images"))
                && lower.contains("sourceworkid")
                && lower.contains("episodeid")
                && (lower.contains("imagestoken") || lower.contains("\"token\"") || lower.contains("\\\"token\\\""));
        boolean hasEpisodeMetadataPayload = hasEpisodeMetadataPayload(html);
        if(!hasImagesTokenPayload && !hasImageApiPayload && !hasEpisodeMetadataPayload)
            return false;
        if(lower.contains("sourceepisodeid") || lower.contains("episodeid"))
            return true;
        if(lower.contains("/webtoon_uploads/")
                || lower.contains("/manhwa_uploads/")
                || lower.contains("/comic_uploads/")
                || lower.contains("/board_uploads/"))
            return true;
        return Pattern.compile("(?i)https?://(?:flysky\\d*m\\.com|fvcdn\\d*\\.com|aws-cdn\\d*\\.site)/[a-z0-9_-]{16,}\\.(?:jpg|jpeg|png|webp)(?:[?#][^\"'<>\\\\\\s]*)?")
                .matcher(html)
                .find();
    }

    private static int findMatchingBracket(String value, int start, char open, char close) {
        if(value == null || start < 0 || start >= value.length() || value.charAt(start) != open)
            return -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for(int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if(inString) {
                if(escaped) {
                    escaped = false;
                } else if(c == '\\') {
                    escaped = true;
                } else if(c == '"') {
                    inString = false;
                }
                continue;
            }
            if(c == '"') {
                inString = true;
            } else if(c == open) {
                depth++;
            } else if(c == close) {
                depth--;
                if(depth == 0)
                    return i;
            }
        }
        return -1;
    }

    private static String embeddedStringField(String objectJson, String field) {
        JSONObject object = embeddedJsonObject(objectJson);
        if(object != null)
            return object.optString(field, "").trim();
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(objectJson == null ? "" : objectJson);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static int embeddedIntField(String objectJson, String field) {
        JSONObject object = embeddedJsonObject(objectJson);
        if(object != null)
            return object.optInt(field, 0);
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d{1,12})")
                .matcher(objectJson == null ? "" : objectJson);
        return matcher.find() ? parsePositiveInt(matcher.group(1)) : 0;
    }

    private static JSONObject embeddedJsonObject(String objectJson) {
        if(objectJson == null || objectJson.length() == 0)
            return null;
        try {
            return new JSONObject(objectJson);
        } catch (Exception ignored) {
            return null;
        }
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
            int start = Math.max(0, idMatcher.start() - 900);
            int end = Math.min(html.length(), idMatcher.end() + 900);
            Matcher countMatcher = Pattern.compile("\"imageCount\"\\s*:\\s*(\\d{1,4})")
                    .matcher(html.substring(start, end));
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
        boolean definitiveEmptyEpisodeList;
    }
}
