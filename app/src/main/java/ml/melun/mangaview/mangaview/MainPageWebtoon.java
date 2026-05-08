package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ml.melun.mangaview.MainApplication.appContext;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

public class MainPageWebtoon {
    String baseUrl;
    int baseMode;

    private static final int MAIN_SECTION_LIMIT = 10;
    private static final long PAGE_CACHE_TTL_MS = 2 * 60 * 1000L;

    private static final String[] WEBTOON_STATUS = {"ing", "end"};
    private static final String[] WEBTOON_STATUS_LABELS = {"연재웹툰", "완결웹툰"};
    private static final String[] WEBTOON_DAY_LABELS = {"최신", "신작", "월", "화", "수", "목", "금", "토", "일", "열흘"};
    private static final String[] WEBTOON_DAY_VALUES = {"recent", "new", "1", "2", "3", "4", "5", "6", "7", "10"};
    private static final String[] WEBTOON_GENRES = {"성인", "드라마", "판타지", "액션", "로맨스", "일상", "개그", "미스터리", "순정", "스포츠", "BL", "스릴러", "무협", "학원", "공포", "스토리", "미분류"};
    private static final String[] ALPHABET_LABELS = {"ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "A-Z", "0-9"};
    private static final String[] ALPHABET_VALUES = {"ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "a", "0"};

    private static final String[] COMIC_DAY_LABELS = {"최신", "주간", "격주", "월간", "단편", "완결", "단행본", "비정기", "미분류"};
    private static final String[] COMIC_DAY_VALUES = {"recent", "10", "11", "12", "14", "16", "15", "13", "20"};
    private static final String[] COMIC_GENRES = {"17", "드라마", "액션", "SF", "TS", "개그", "게임", "공포", "도박", "호러", "라노벨", "러브코미디", "로맨스", "먹방", "미스터리", "백합", "붕탁", "성인", "순정", "스릴러", "스포츠", "시대", "판타지", "학원", "BL", "여장", "역사", "요리", "음악", "이세계", "일상", "전생", "추리", "애니화"};
    private static final String INFERRED_TAG_CACHE_KEY = "webtoonInferredTagCacheV1";
    private static final int INFERRED_TAG_CACHE_LIMIT = 800;
    private static final LinkedHashMap<String, List<String>> inferredTagCache = new LinkedHashMap<>();
    private static boolean inferredTagCacheLoaded = false;
    private static int inferredTagCacheWrites = 0;
    private static final String CLASSIFICATION_DB_CACHE_KEY = "webtoonClassificationDbV2";
    private static final String CLASSIFICATION_DB_FETCHED_AT_KEY = "webtoonClassificationDbFetchedAt";
    private static final String COMIC_CLASSIFICATION_DB_CACHE_KEY = "comicClassificationDbV1";
    private static final String COMIC_CLASSIFICATION_DB_FETCHED_AT_KEY = "comicClassificationDbFetchedAt";
    private static final long CLASSIFICATION_DB_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final Map<Integer, List<String>> classificationDb = new LinkedHashMap<>();
    private static final Map<String, List<String>> classificationNameDb = new LinkedHashMap<>();
    private static final Map<Integer, DbTitle> classificationTitleDb = new LinkedHashMap<>();
    private static final Map<String, List<DbTitle>> classificationGenreDb = new LinkedHashMap<>();
    private static boolean classificationDbLoaded = false;
    private static long classificationDbLoadedAt = 0;
    private static final Map<Integer, List<String>> comicClassificationDb = new LinkedHashMap<>();
    private static final Map<String, List<String>> comicClassificationNameDb = new LinkedHashMap<>();
    private static final Map<Integer, DbTitle> comicClassificationTitleDb = new LinkedHashMap<>();
    private static final Map<String, List<DbTitle>> comicClassificationGenreDb = new LinkedHashMap<>();
    private static boolean comicClassificationDbLoaded = false;
    private static long comicClassificationDbLoadedAt = 0;

    public static final String[][] WEBTOON_FILTER_GROUPS = buildWebtoonFilterGroups();
    public static final String[][] COMIC_FILTER_GROUPS = buildComicFilterGroups();

    private static final String[][] SECTIONS = buildWebtoonSections();
    private static final String[][] COMIC_SECTIONS = buildComicSections();

    List<Ranking<?>> dataSet;

    public static void preloadClassificationDbs() {
        loadClassificationDb();
        loadComicClassificationDb();
    }

    public MainPageWebtoon(CustomHttpClient client){
        this(client, base_webtoon);
    }

    public MainPageWebtoon(CustomHttpClient client, int baseMode){
        this.baseMode = baseMode;
        fetch(client);
    }

    public MainPageWebtoon(int baseMode){
        this.baseMode = baseMode;
    }

    public String getUrl(CustomHttpClient client){
        this.baseUrl = client.getUrl(baseMode);
        return this.baseUrl;
    }

    public void fetch(CustomHttpClient client){
        if(baseUrl == null || baseUrl.length()==0)
            if(getUrl(client)==null)
                return;
        try {
            dataSet = new ArrayList<>();
            String[][] sections = getSections();
            for(String[] section : sections)
                dataSet.add(parseWolfTitle(client, section[0], section[1], baseMode));
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private String[][] getSections(){
        return getSections(baseMode);
    }

    public static String[][] getSections(int baseMode){
        if(baseMode == base_comic)
            return COMIC_SECTIONS;
        return SECTIONS;
    }

    public Ranking<Title> parseWolfTitle(CustomHttpClient client, String title, String path, int baseMode){
        for(int attempt = 0; attempt < 2; attempt++) {
            Ranking<Title> ranking = new Ranking<>(title);
            try{
                CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
                Document d = Jsoup.parse(page.body);
                for(Title webtoon : parseWolfTitles(d, baseMode, MAIN_SECTION_LIMIT))
                    ranking.add(webtoon);
                if(ranking.size() == 0 && attempt == 0 && client.resolveWfwfDomainNow())
                    continue;
            }catch (Exception e){
                if(e instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return ranking;
                }
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            return ranking;
        }
        return new Ranking<>(title);
    }

    private static String[][] buildWebtoonSections() {
        ArrayList<String[]> sections = new ArrayList<>();
        for(int i = 0; i < WEBTOON_STATUS.length; i++) {
            String status = WEBTOON_STATUS[i];
            String statusLabel = WEBTOON_STATUS_LABELS[i];
            sections.add(section(statusLabel, "최신", webtoonDayPath(status, "recent", "n")));
            sections.add(section(statusLabel, "신작", webtoonDayPath(status, "new", "n")));
            sections.add(section(statusLabel, "인기순", webtoonOrderPath(status, "f")));
            sections.add(section(statusLabel, "드라마", webtoonGenrePath(status, "드라마", "n")));
            sections.add(section(statusLabel, "판타지", webtoonGenrePath(status, "판타지", "n")));
            sections.add(section(statusLabel, "액션", webtoonGenrePath(status, "액션", "n")));
            sections.add(section(statusLabel, "로맨스", webtoonGenrePath(status, "로맨스", "n")));
            sections.add(section(statusLabel, "무협", webtoonGenrePath(status, "무협", "n")));
        }
        return sections.toArray(new String[0][]);
    }

    private static String[][] buildComicSections() {
        ArrayList<String[]> sections = new ArrayList<>();
        sections.add(section("정렬", "인기순", "/cm?type1=complete&type2=recent&o=f"));
        for(int i = 0; i < COMIC_DAY_LABELS.length; i++)
            sections.add(section("연재일", COMIC_DAY_LABELS[i], comicDayPath(COMIC_DAY_VALUES[i], "n")));
        for(String genre : COMIC_GENRES)
            sections.add(section("장르별", genre, comicGenrePath(genre, "n")));
        for(int i = 0; i < ALPHABET_LABELS.length; i++)
            sections.add(section("작품별", ALPHABET_LABELS[i], comicAlphabetPath(ALPHABET_VALUES[i], "n")));
        return sections.toArray(new String[0][]);
    }

    private static String[][] buildWebtoonFilterGroups() {
        ArrayList<String[]> groups = new ArrayList<>();
        groups.add(new String[]{
                filter("정렬", "연재 인기순", webtoonOrderPath("ing", "f")),
                filter("정렬", "연재 최신순", webtoonOrderPath("ing", "n")),
                filter("정렬", "완결 인기순", webtoonOrderPath("end", "f")),
                filter("정렬", "완결 최신순", webtoonOrderPath("end", "n"))
        });
        groups.add(buildWebtoonStatusFilters("연재 요일별", "ing", "day"));
        groups.add(buildWebtoonStatusFilters("완결 요일별", "end", "day"));
        groups.add(buildWebtoonStatusFilters("연재 장르별", "ing", "genre"));
        groups.add(buildWebtoonStatusFilters("완결 장르별", "end", "genre"));
        groups.add(buildWebtoonStatusFilters("연재 작품별", "ing", "alphabet"));
        groups.add(buildWebtoonStatusFilters("완결 작품별", "end", "alphabet"));
        return groups.toArray(new String[0][]);
    }

    private static String[] buildWebtoonStatusFilters(String group, String status, String type) {
        ArrayList<String> filters = new ArrayList<>();
        if("day".equals(type)) {
            for(int i = 0; i < WEBTOON_DAY_LABELS.length; i++)
                filters.add(filter(group, WEBTOON_DAY_LABELS[i], webtoonDayPath(status, WEBTOON_DAY_VALUES[i], "n")));
        } else if("genre".equals(type)) {
            for(String genre : WEBTOON_GENRES)
                filters.add(filter(group, genre, webtoonGenrePath(status, genre, "n")));
        } else {
            for(int i = 0; i < ALPHABET_LABELS.length; i++)
                filters.add(filter(group, ALPHABET_LABELS[i], webtoonAlphabetPath(status, ALPHABET_VALUES[i], "n")));
        }
        return filters.toArray(new String[0]);
    }

    private static String[][] buildComicFilterGroups() {
        ArrayList<String[]> groups = new ArrayList<>();
        groups.add(new String[]{
                filter("정렬", "인기순", "/cm?type1=complete&type2=recent&o=f"),
                filter("정렬", "최신순", "/cm?type1=complete&type2=recent&o=n")
        });
        ArrayList<String> days = new ArrayList<>();
        for(int i = 0; i < COMIC_DAY_LABELS.length; i++)
            days.add(filter("연재일", COMIC_DAY_LABELS[i], comicDayPath(COMIC_DAY_VALUES[i], "n")));
        groups.add(days.toArray(new String[0]));
        ArrayList<String> genres = new ArrayList<>();
        for(String genre : COMIC_GENRES)
            genres.add(filter("장르별", genre, comicGenrePath(genre, "n")));
        groups.add(genres.toArray(new String[0]));
        ArrayList<String> alphabets = new ArrayList<>();
        for(int i = 0; i < ALPHABET_LABELS.length; i++)
            alphabets.add(filter("작품별", ALPHABET_LABELS[i], comicAlphabetPath(ALPHABET_VALUES[i], "n")));
        groups.add(alphabets.toArray(new String[0]));
        return groups.toArray(new String[0][]);
    }

    private static String[] section(String group, String label, String path) {
        return new String[]{filter(group, label, path), path};
    }

    private static String filter(String group, String label, String path) {
        return group + "|" + label + "|" + path;
    }

    private static String webtoonOrderPath(String status, String order) {
        if("end".equals(status))
            return "/end?type1=genre&type2=&o=" + order;
        return "/ing?type1=day&type2=recent&o=" + order;
    }

    private static String webtoonDayPath(String status, String value, String order) {
        return "/" + status + "?type1=day&type2=" + percentEncode(value, Charset.forName("EUC-KR")) + "&o=" + order;
    }

    private static String webtoonGenrePath(String status, String genre, String order) {
        if("성인".equals(genre))
            return "/" + status + "?type1=genre&o=" + order;
        return "/" + status + "?type1=genre&type2=" + percentEncode(genre, Charset.forName("EUC-KR")) + "&o=" + order;
    }

    private static String webtoonAlphabetPath(String status, String value, String order) {
        return "/" + status + "?type1=alphabet&type2=" + percentEncode(value, Charset.forName("EUC-KR")) + "&o=" + order;
    }

    private static String comicDayPath(String value, String order) {
        return "/cm?type1=complete&type2=" + value + "&o=" + order;
    }

    private static String comicGenrePath(String genre, String order) {
        return "/cm?type1=genre&type2=" + percentEncode(genre, Charset.forName("EUC-KR")) + "&o=" + order;
    }

    private static String comicAlphabetPath(String value, String order) {
        return "/cm?type1=alphabet&type2=" + percentEncode(value, Charset.forName("EUC-KR")) + "&o=" + order;
    }

    private static String percentEncode(String value, Charset charset) {
        byte[] bytes = value.getBytes(charset);
        StringBuilder encoded = new StringBuilder();
        for(byte b : bytes) {
            int c = b & 0xff;
            if((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~')
                encoded.append((char)c);
            else
                encoded.append('%').append(String.format("%02X", c));
        }
        return encoded.toString();
    }

    public static ArrayList<Title> parseWolfTitles(Document d, int baseMode, int limit){
        ArrayList<Title> titles = new ArrayList<>();
        for(Element e : d.select("div.webtoon-list li, article.searchItem")){
            try{
                Element link = e.selectFirst("a[href*=toon=]");
                if(link == null) continue;
                String href = link.attr("href");
                int id = getQueryInt(href, "toon");
                if(id <= 0) continue;
                int detectedBaseMode = detectWolfBaseMode(href);
                if(detectedBaseMode != 0 && detectedBaseMode != baseMode)
                    continue;

                String name = firstOwnText(e.selectFirst("p.subject"));
                if(name.length() == 0)
                    name = cleanText(e.selectFirst("h6.searchDetailTitle"));
                if(name.length() == 0)
                    name = link.attr("title");
                if(name.length() == 0)
                    name = getQueryString(href, "title");

                String thumb = "";
                Element img = e.selectFirst("img[data-original]");
                if(img != null)
                    thumb = img.attr("data-original");
                if(thumb.length() == 0 && img != null)
                    thumb = img.attr("src");
                if(thumb.length() == 0) {
                    Element searchPng = e.selectFirst(".searchPng[style*=background-image]");
                    if(searchPng != null)
                        thumb = extractBackgroundImage(searchPng.attr("style"));
                }

                Elements infos = e.select("div.txt p");
                List<String> tags = new ArrayList<>();
                if(infos.size() > 1)
                    for(String tag : cleanTextWithoutChildren(infos.get(1)).split("/"))
                        if(tag.trim().length() > 0) tags.add(tag.trim());

                String release = "";
                if(infos.size() > 2)
                    release = cleanTextWithoutChildren(infos.get(2));

                titles.add(new Title(name, thumb, "", tags, release, id, baseMode));
                applyInferredSearchTags(titles.get(titles.size() - 1));
                if(limit > 0 && titles.size() >= limit) break;
            }catch (Exception ignored){
            }
        }
        return titles;
    }

    private static int detectWolfBaseMode(String href) {
        if(href == null)
            return 0;
        String normalized = href.toLowerCase(Locale.ROOT);
        if(normalized.contains("/cl?toon=")
                || normalized.contains("/cv?toon=")
                || normalized.contains("/cm?"))
            return base_comic;
        if(normalized.contains("/list?toon=")
                || normalized.contains("/view?toon=")
                || normalized.contains("/webtoon")
                || normalized.contains("/ing?")
                || normalized.contains("/end?"))
            return base_webtoon;
        return 0;
    }

    public static void applyInferredWebtoonTags(Title title) {
        if(title == null || title.getBaseMode() != base_webtoon)
            return;
        List<String> tags = new ArrayList<>(title.getTags());
        List<String> dbTags = getClassificationDbTags(title.getId());
        if(dbTags == null)
            dbTags = getClassificationDbTags(title.getName());
        if(hasMeaningfulClassificationTags(dbTags)) {
            for(String dbTag : dbTags)
                addUnique(tags, dbTag);
            title.setTags(tags);
            return;
        }
        for(String inferredTag : inferWebtoonTags(title))
            addUnique(tags, inferredTag);
        title.setTags(tags);
    }

    public static void applyInferredSearchTags(Title title) {
        if(title == null)
            return;
        if(title.getBaseMode() == base_webtoon) {
            applyInferredWebtoonTags(title);
            return;
        }
        if(title.getBaseMode() != base_comic)
            return;
        List<String> tags = new ArrayList<>(title.getTags());
        List<String> dbTags = getComicClassificationDbTags(title.getId());
        if(dbTags == null)
            dbTags = getComicClassificationDbTags(title.getName());
        if(dbTags != null) {
            for(String dbTag : dbTags)
                addUnique(tags, dbTag);
            title.setTags(tags);
            return;
        }
        for(String inferredTag : inferComicTags(title))
            addUnique(tags, inferredTag);
        title.setTags(tags);
    }

    public static void enhanceComicClassification(List<Ranking<?>> sections) {
        if(sections == null)
            return;

        LinkedHashMap<Integer, Title> pool = new LinkedHashMap<>();
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            for(Object item : section) {
                if(!(item instanceof Title))
                    continue;
                Title title = (Title) item;
                applyInferredSearchTags(title);
                if(!pool.containsKey(title.getId()))
                    pool.put(title.getId(), title);
            }
        }

        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            SectionInfo info = parseSectionInfo(section.getName());
            if(!isComicGenre(info.label))
                continue;
            String genreTag = comicGenreTag(info.label);
            for(Title title : pool.values()) {
                if(section.size() >= MAIN_SECTION_LIMIT)
                    break;
                if(!hasTag(title, genreTag) || containsTitle(section, title))
                    continue;
                ((Ranking<Object>) section).add(title);
            }
            for(Title title : getComicClassificationDbTitlesByGenre(genreTag, MAIN_SECTION_LIMIT)) {
                if(section.size() >= MAIN_SECTION_LIMIT)
                    break;
                if(containsTitle(section, title))
                    continue;
                ((Ranking<Object>) section).add(title);
            }
        }
    }

    public static void enhanceWebtoonClassification(List<Ranking<?>> sections) {
        if(sections == null)
            return;

        Map<String, LinkedHashMap<Integer, Title>> pools = new LinkedHashMap<>();
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            SectionInfo info = parseSectionInfo(section.getName());
            if(info.path.length() == 0)
                continue;
            String status = webtoonStatusFromPath(info.path);
            if(status.length() == 0)
                continue;

            LinkedHashMap<Integer, Title> pool = pools.get(status);
            if(pool == null) {
                pool = new LinkedHashMap<>();
                pools.put(status, pool);
            }

            for(Object item : section) {
                if(!(item instanceof Title))
                    continue;
                Title title = (Title) item;
                applyInferredWebtoonTags(title);
                if(!pool.containsKey(title.getId()))
                    pool.put(title.getId(), title);
            }
        }

        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            SectionInfo info = parseSectionInfo(section.getName());
            if(!isWebtoonGenre(info.label))
                continue;
            String status = webtoonStatusFromPath(info.path);
            LinkedHashMap<Integer, Title> pool = pools.get(status);
            if(pool == null)
                continue;

            for(Title title : pool.values()) {
                if(section.size() >= MAIN_SECTION_LIMIT)
                    break;
                if(!hasTag(title, info.label) || containsTitle(section, title))
                    continue;
                ((Ranking<Object>) section).add(title);
            }
        }
    }

    public static ArrayList<Title> filterInferredWebtoonGenre(List<Title> source, String genre, int limit) {
        ArrayList<Title> result = new ArrayList<>();
        if(source == null || genre == null)
            return result;
        for(Title title : source) {
            applyInferredWebtoonTags(title);
            if(!hasTag(title, genre))
                continue;
            result.add(title);
            if(limit > 0 && result.size() >= limit)
                break;
        }
        return result;
    }

    public static ArrayList<Title> getClassificationDbTitlesByGenre(String genre, int limit) {
        return getClassificationDbTitlesByGenre(genre, 0, limit);
    }

    public static ArrayList<Title> getClassificationDbTitlesByGenre(String genre, int offset, int limit) {
        ArrayList<Title> result = new ArrayList<>();
        if(genre == null)
            return result;
        loadClassificationDb();
        List<DbTitle> titles = classificationGenreDb.get(normalizeClassificationTag(genre));
        if(titles == null)
            return result;
        int start = Math.max(0, offset);
        for(int i = start; i < titles.size(); i++) {
            DbTitle dbTitle = titles.get(i);
            result.add(new Title(dbTitle.name, dbTitle.thumb, "", dbTitle.tags, dbTitle.release, dbTitle.id, base_webtoon));
            if(limit > 0 && result.size() >= limit)
                break;
        }
        return result;
    }

    public static int getClassificationDbGenreCount(String genre) {
        if(genre == null)
            return 0;
        loadClassificationDb();
        List<DbTitle> titles = classificationGenreDb.get(normalizeClassificationTag(genre));
        return titles == null ? 0 : titles.size();
    }

    public static ArrayList<Title> getComicClassificationDbTitlesByGenre(String genre, int limit) {
        return getComicClassificationDbTitlesByGenre(genre, 0, limit);
    }

    public static ArrayList<Title> getComicClassificationDbTitlesByGenre(String genre, int offset, int limit) {
        ArrayList<Title> result = new ArrayList<>();
        if(genre == null)
            return result;
        loadComicClassificationDb();
        List<DbTitle> titles = comicClassificationGenreDb.get(normalizeClassificationTag(genre));
        if(titles == null)
            return result;
        int start = Math.max(0, offset);
        for(int i = start; i < titles.size(); i++) {
            DbTitle dbTitle = titles.get(i);
            result.add(new Title(dbTitle.name, dbTitle.thumb, "", dbTitle.tags, dbTitle.release, dbTitle.id, base_comic));
            if(limit > 0 && result.size() >= limit)
                break;
        }
        return result;
    }

    public static int getComicClassificationDbGenreCount(String genre) {
        if(genre == null)
            return 0;
        loadComicClassificationDb();
        List<DbTitle> titles = comicClassificationGenreDb.get(normalizeClassificationTag(genre));
        return titles == null ? 0 : titles.size();
    }

    private static List<String> inferWebtoonTags(Title title) {
        ArrayList<String> result = new ArrayList<>();
        String text = ((title.getName() == null ? "" : title.getName()) + " " +
                (title.getRelease() == null ? "" : title.getRelease()) + " " +
                join(title.getTags())).toLowerCase(Locale.ROOT);

        if(hasAny(text, "bl", "비엘", "보이즈러브")) result.add("BL");
        if(hasAny(text, "무협", "무림", "강호", "천마", "마교", "화산", "소림", "검신", "검왕")) result.add("무협");
        if(hasAny(text, "판타지", "마법", "마왕", "용사", "던전", "이세계", "회귀", "전생", "환생", "빙의", "헌터", "몬스터", "드래곤", "레벨업", "시스템")) result.add("판타지");
        if(hasAny(text, "액션", "격투", "전투", "킬러", "암살", "전쟁", "히어로", "느와르", "조폭")) result.add("액션");
        if(hasAny(text, "로맨스", "연애", "첫사랑", "결혼", "신부", "남편", "아내")) result.add("로맨스");
        if(hasAny(text, "학원", "학교", "고교", "고등학교", "학생", "교실", "캠퍼스")) result.add("학원");
        if(hasAny(text, "스포츠", "축구", "야구", "농구", "배구", "골프", "테니스", "복싱")) result.add("스포츠");
        if(hasAny(text, "공포", "귀신", "괴담", "좀비", "악령", "유령", "저주")) result.add("공포");
        if(hasAny(text, "미스터리", "추리")) result.add("미스터리");
        if(hasAny(text, "스릴러", "범죄", "살인", "사이코", "추적", "납치", "미스터리")) result.add("스릴러");
        if(hasAny(text, "개그", "코미디", "병맛")) result.add("개그");
        if(hasAny(text, "순정")) result.add("순정");
        if(hasAny(text, "일상", "힐링", "직장", "회사", "육아", "가족")) result.add("일상");
        if(hasAny(text, "드라마", "휴먼", "성장")) result.add("드라마");
        if(hasAny(text, "스토리")) result.add("스토리");

        return result;
    }

    private static List<String> inferComicTags(Title title) {
        ArrayList<String> result = new ArrayList<>();
        String text = ((title.getName() == null ? "" : title.getName()) + " " +
                (title.getRelease() == null ? "" : title.getRelease()) + " " +
                join(title.getTags())).toLowerCase(Locale.ROOT);

        if(hasAny(text, "bl", "비엘", "보이즈러브")) result.add("BL");
        if(hasAny(text, "sf", "우주", "로봇", "미래", "사이버")) result.add("SF");
        if(hasAny(text, "ts", "성전환", "여체화", "남체화")) result.add("TS");
        if(hasAny(text, "fate", "액션", "격투", "전투", "전쟁", "검", "킬러", "암살")) result.add("액션");
        if(hasAny(text, "개그", "코미디", "러브코미디", "럽코")) result.add("개그");
        if(hasAny(text, "게임", "플레이어", "게이머")) result.add("게임");
        if(hasAny(text, "공포", "호러", "괴담", "귀신", "좀비")) result.add("공포");
        if(hasAny(text, "도박", "카지노", "마작", "포커")) result.add("도박");
        if(hasAny(text, "라노벨", "라이트노벨")) result.add("라노벨");
        if(hasAny(text, "러브코미디", "러브 코미디", "럽코")) result.add("러브코미디");
        if(hasAny(text, "로맨스", "연애", "첫사랑", "사랑", "고백", "결혼")) result.add("로맨스");
        if(hasAny(text, "먹방", "요리", "식당", "셰프", "요리사")) result.add("요리");
        if(hasAny(text, "미스터리", "추리", "탐정", "사건")) result.add("미스터리");
        if(hasAny(text, "백합", "gl")) result.add("백합");
        if(hasAny(text, "순정")) result.add("순정");
        if(hasAny(text, "스릴러", "범죄", "살인", "납치", "추적")) result.add("스릴러");
        if(hasAny(text, "스포츠", "축구", "야구", "농구", "배구", "복싱")) result.add("스포츠");
        if(hasAny(text, "시대", "역사", "전국", "에도", "왕국", "제국")) result.add("역사");
        if(hasAny(text, "학교", "학원", "학생", "고교", "고등학교", "동아리")) result.add("학원");
        if(hasAny(text, "여장", "남장")) result.add("여장");
        if(hasAny(text, "음악", "밴드", "아이돌", "가수")) result.add("음악");
        if(hasAny(text, "fate", "apocrypha", "stay night", "heaven", "strange fake", "이세계", "전생", "환생", "용사", "마왕", "던전", "마법")) result.add("이세계");
        if(hasAny(text, "일상", "힐링", "가족", "직장", "회사")) result.add("일상");
        if(hasAny(text, "드라마", "휴먼", "성장")) result.add("드라마");

        return result;
    }

    private static synchronized List<String> getClassificationDbTags(int titleId) {
        loadClassificationDb();
        List<String> tags = classificationDb.get(titleId);
        return tags == null ? null : new ArrayList<>(tags);
    }

    private static synchronized List<String> getClassificationDbTags(String titleName) {
        loadClassificationDb();
        String key = normalizeClassificationName(titleName);
        if(key.length() == 0)
            return null;
        List<String> tags = classificationNameDb.get(key);
        return tags == null ? null : new ArrayList<>(tags);
    }

    private static synchronized List<String> getComicClassificationDbTags(int titleId) {
        loadComicClassificationDb();
        List<String> tags = comicClassificationDb.get(titleId);
        return tags == null ? null : new ArrayList<>(tags);
    }

    private static synchronized List<String> getComicClassificationDbTags(String titleName) {
        loadComicClassificationDb();
        String key = normalizeClassificationName(titleName);
        if(key.length() == 0)
            return null;
        List<String> tags = comicClassificationNameDb.get(key);
        return tags == null ? null : new ArrayList<>(tags);
    }

    private static String normalizeClassificationName(String value) {
        if(value == null)
            return "";
        return value.replaceAll("[\\s\\p{Punct}·・…]+", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeClassificationTag(String value) {
        if(value == null)
            return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static synchronized void loadClassificationDb() {
        long now = System.currentTimeMillis();
        if(classificationDbLoaded && now - classificationDbLoadedAt <= CLASSIFICATION_DB_TTL_MS)
            return;
        classificationDbLoaded = true;
        classificationDbLoadedAt = now;
        String cached = "";
        try {
            if(p != null) {
                cached = p.getSharedPref().getString(CLASSIFICATION_DB_CACHE_KEY, "");
                long fetchedAt = p.getSharedPref().getLong(CLASSIFICATION_DB_FETCHED_AT_KEY, 0);
                if(cached.length() == 0 || now - fetchedAt > CLASSIFICATION_DB_TTL_MS) {
                    String fetched = fetchClassificationDb();
                    if(fetched.length() > 0) {
                        cached = fetched;
                        p.getSharedPref().edit()
                                .putString(CLASSIFICATION_DB_CACHE_KEY, cached)
                                .putLong(CLASSIFICATION_DB_FETCHED_AT_KEY, now)
                                .apply();
                    } else if(cached.length() == 0) {
                        p.getSharedPref().edit()
                                .putLong(CLASSIFICATION_DB_FETCHED_AT_KEY, now)
                                .apply();
                    }
                }
            }
            parseClassificationDb(cached);
            if(classificationDb.size() == 0)
                parseClassificationDb(readBundledClassificationDb());
        } catch (Exception e) {
            classificationDb.clear();
            classificationNameDb.clear();
            classificationTitleDb.clear();
            classificationGenreDb.clear();
            parseClassificationDb(cached);
            if(classificationDb.size() == 0)
                parseClassificationDb(readBundledClassificationDb());
        }
    }

    private static String fetchClassificationDb() {
        return "";
    }

    private static synchronized void loadComicClassificationDb() {
        long now = System.currentTimeMillis();
        if(comicClassificationDbLoaded && now - comicClassificationDbLoadedAt <= CLASSIFICATION_DB_TTL_MS)
            return;
        comicClassificationDbLoaded = true;
        comicClassificationDbLoadedAt = now;
        String cached = "";
        try {
            if(p != null) {
                cached = p.getSharedPref().getString(COMIC_CLASSIFICATION_DB_CACHE_KEY, "");
                long fetchedAt = p.getSharedPref().getLong(COMIC_CLASSIFICATION_DB_FETCHED_AT_KEY, 0);
                if(cached.length() == 0 || now - fetchedAt > CLASSIFICATION_DB_TTL_MS) {
                    String fetched = fetchComicClassificationDb();
                    if(fetched.length() > 0) {
                        cached = fetched;
                        p.getSharedPref().edit()
                                .putString(COMIC_CLASSIFICATION_DB_CACHE_KEY, cached)
                                .putLong(COMIC_CLASSIFICATION_DB_FETCHED_AT_KEY, now)
                                .apply();
                    } else if(cached.length() == 0) {
                        p.getSharedPref().edit()
                                .putLong(COMIC_CLASSIFICATION_DB_FETCHED_AT_KEY, now)
                                .apply();
                    }
                }
            }
            parseComicClassificationDb(cached);
            if(comicClassificationDb.size() == 0)
                parseComicClassificationDb(readBundledComicClassificationDb());
        } catch (Exception e) {
            comicClassificationDb.clear();
            comicClassificationNameDb.clear();
            comicClassificationTitleDb.clear();
            comicClassificationGenreDb.clear();
            parseComicClassificationDb(cached);
            if(comicClassificationDb.size() == 0)
                parseComicClassificationDb(readBundledComicClassificationDb());
        }
    }

    private static String fetchComicClassificationDb() {
        return "";
    }

    private static void parseClassificationDb(String json) {
        parseClassificationDb(json, classificationDb, classificationNameDb, classificationTitleDb, classificationGenreDb);
    }

    private static void parseComicClassificationDb(String json) {
        parseClassificationDb(json, comicClassificationDb, comicClassificationNameDb, comicClassificationTitleDb, comicClassificationGenreDb);
    }

    static synchronized void putClassificationDbTitleForTest(int id, String name, boolean comic, String... tags) {
        ArrayList<String> tagList = new ArrayList<>();
        if(tags != null)
            for(String tag : tags)
                if(tag != null && tag.length() > 0)
                    tagList.add(tag);
        if(comic) {
            comicClassificationDbLoaded = true;
            comicClassificationDbLoadedAt = System.currentTimeMillis();
            comicClassificationDb.put(id, tagList);
            comicClassificationNameDb.put(normalizeClassificationName(name), tagList);
            DbTitle dbTitle = new DbTitle(id, name, "", "", tagList);
            comicClassificationTitleDb.put(id, dbTitle);
            indexClassificationTitle(comicClassificationGenreDb, dbTitle);
        } else {
            classificationDbLoaded = true;
            classificationDbLoadedAt = System.currentTimeMillis();
            classificationDb.put(id, tagList);
            classificationNameDb.put(normalizeClassificationName(name), tagList);
            DbTitle dbTitle = new DbTitle(id, name, "", "", tagList);
            classificationTitleDb.put(id, dbTitle);
            indexClassificationTitle(classificationGenreDb, dbTitle);
        }
    }

    static synchronized void clearClassificationDbForTest() {
        classificationDb.clear();
        classificationNameDb.clear();
        classificationTitleDb.clear();
        classificationGenreDb.clear();
        classificationDbLoaded = false;
        classificationDbLoadedAt = 0;
        comicClassificationDb.clear();
        comicClassificationNameDb.clear();
        comicClassificationTitleDb.clear();
        comicClassificationGenreDb.clear();
        comicClassificationDbLoaded = false;
        comicClassificationDbLoadedAt = 0;
    }

    private static void parseClassificationDb(String json, Map<Integer, List<String>> idDb,
                                              Map<String, List<String>> nameDb,
                                              Map<Integer, DbTitle> titleDb,
                                              Map<String, List<DbTitle>> genreDb) {
        try {
            if(json == null || json.length() == 0)
                return;
            idDb.clear();
            nameDb.clear();
            titleDb.clear();
            genreDb.clear();
            JSONObject root = new JSONObject(json);
            JSONObject titles = root.optJSONObject("titles");
            if(titles == null)
                return;
            Iterator<String> keys = titles.keys();
            while(keys.hasNext()) {
                String key = keys.next();
                int titleId = Integer.parseInt(key);
                JSONObject item = titles.optJSONObject(key);
                if(item == null)
                    continue;
                ArrayList<String> tags = readClassificationTags(item);
                if(tags.size() > 0)
                    idDb.put(titleId, tags);
                String name = item.optString("name", "");
                String nameKey = normalizeClassificationName(name);
                if(tags.size() > 0 && nameKey.length() > 0)
                    nameDb.put(nameKey, tags);
                String thumb = item.optString("thumb", "");
                String release = item.optString("release", "");
                if(tags.size() > 0 && name.length() > 0) {
                    DbTitle dbTitle = new DbTitle(titleId, name, thumb, release, tags);
                    titleDb.put(titleId, dbTitle);
                    indexClassificationTitle(genreDb, dbTitle);
                }
            }
        } catch (Exception e) {
            idDb.clear();
            nameDb.clear();
            titleDb.clear();
            genreDb.clear();
        }
    }

    private static void indexClassificationTitle(Map<String, List<DbTitle>> genreDb, DbTitle dbTitle) {
        if(genreDb == null || dbTitle == null || dbTitle.tags == null)
            return;
        for(String tag : dbTitle.tags) {
            String key = normalizeClassificationTag(tag);
            if(key.length() == 0)
                continue;
            List<DbTitle> titles = genreDb.get(key);
            if(titles == null) {
                titles = new ArrayList<>();
                genreDb.put(key, titles);
            }
            titles.add(dbTitle);
        }
    }

    private static String readBundledClassificationDb() {
        return readBundledClassificationDb("webtoon-classification.json");
    }

    private static String readBundledComicClassificationDb() {
        return readBundledClassificationDb("comic-classification.json");
    }

    private static String readBundledClassificationDb(String assetName) {
        if(appContext == null)
            return "";
        try(InputStream input = appContext.getAssets().open(assetName)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while(true) {
                int read = input.read(buffer);
                if(read < 0)
                    break;
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private static ArrayList<String> readClassificationTags(JSONObject item) {
        ArrayList<String> tags = new ArrayList<>();
        appendJsonTags(tags, item.optJSONArray("manualTags"));
        appendJsonTags(tags, item.optJSONArray("externalTags"));
        appendJsonTags(tags, item.optJSONArray("sourceTags"));
        appendJsonTags(tags, item.optJSONArray("inferredTags"));
        appendJsonTags(tags, item.optJSONArray("tags"));
        return tags;
    }

    private static void appendJsonTags(List<String> target, JSONArray array) {
        if(array == null)
            return;
        for(int i = 0; i < array.length(); i++) {
            String tag = array.optString(i).trim();
            if(tag.length() == 0 || "미분류".equals(tag))
                continue;
            boolean exists = false;
            for(String current : target)
                if(current.equalsIgnoreCase(tag)) {
                    exists = true;
                    break;
                }
            if(!exists)
                target.add(tag);
        }
    }

    private static class DbTitle {
        int id;
        String name;
        String thumb;
        String release;
        List<String> tags;

        DbTitle(int id, String name, String thumb, String release, List<String> tags) {
            this.id = id;
            this.name = name;
            this.thumb = thumb;
            this.release = release;
            this.tags = new ArrayList<>(tags);
        }

        boolean hasTag(String tag) {
            for(String existing : tags)
                if(existing.equalsIgnoreCase(tag))
                    return true;
            return false;
        }
    }

    private static synchronized List<String> getCachedInferredTags(String key) {
        loadInferredTagCache();
        List<String> cached = inferredTagCache.get(key);
        return cached == null ? null : new ArrayList<>(cached);
    }

    private static synchronized void putCachedInferredTags(String key, List<String> tags) {
        loadInferredTagCache();
        inferredTagCache.put(key, tags == null ? new ArrayList<>() : new ArrayList<>(tags));
        while(inferredTagCache.size() > INFERRED_TAG_CACHE_LIMIT) {
            String firstKey = inferredTagCache.keySet().iterator().next();
            inferredTagCache.remove(firstKey);
        }
        if(++inferredTagCacheWrites >= 12 || tags == null || tags.size() > 0) {
            inferredTagCacheWrites = 0;
            saveInferredTagCache();
        }
    }

    private static synchronized void loadInferredTagCache() {
        if(inferredTagCacheLoaded)
            return;
        inferredTagCacheLoaded = true;
        try {
            if(p == null)
                return;
            JSONObject object = new JSONObject(p.getSharedPref().getString(INFERRED_TAG_CACHE_KEY, "{}"));
            Iterator<String> keys = object.keys();
            while(keys.hasNext()) {
                String key = keys.next();
                JSONArray array = object.optJSONArray(key);
                ArrayList<String> tags = new ArrayList<>();
                if(array != null)
                    for(int i = 0; i < array.length(); i++)
                        tags.add(array.optString(i));
                inferredTagCache.put(key, tags);
            }
        } catch (Exception e) {
            inferredTagCache.clear();
        }
    }

    private static synchronized void saveInferredTagCache() {
        try {
            if(p == null)
                return;
            JSONObject object = new JSONObject();
            for(Map.Entry<String, List<String>> entry : inferredTagCache.entrySet()) {
                JSONArray array = new JSONArray();
                for(String tag : entry.getValue())
                    array.put(tag);
                object.put(entry.getKey(), array);
            }
            p.getSharedPref().edit().putString(INFERRED_TAG_CACHE_KEY, object.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static String inferredTagCacheKey(Title title) {
        if(title.getId() > 0)
            return String.valueOf(title.getId());
        String source = (title.getName() == null ? "" : title.getName()) + "|" +
                (title.getRelease() == null ? "" : title.getRelease());
        return "local:" + Integer.toHexString(source.hashCode());
    }

    private static boolean hasAny(String text, String... needles) {
        if(text == null || text.length() == 0)
            return false;
        for(String needle : needles)
            if(text.contains(needle.toLowerCase(Locale.ROOT)))
                return true;
        return false;
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        if(values != null)
            for(String value : values)
                builder.append(value).append(' ');
        return builder.toString();
    }

    private static void addUnique(List<String> tags, String tag) {
        if(tag == null || tag.length() == 0)
            return;
        for(String existing : tags)
            if(existing != null && existing.equalsIgnoreCase(tag))
                return;
        tags.add(tag);
    }

    private static boolean hasTag(Title title, String tag) {
        if(title == null || tag == null)
            return false;
        for(String existing : title.getTags())
            if(existing != null && existing.equalsIgnoreCase(tag))
                return true;
        return false;
    }

    private static boolean hasMeaningfulClassificationTags(List<String> tags) {
        if(tags == null)
            return false;
        for(String tag : tags)
            if(tag != null && tag.length() > 0 && !"미분류".equals(tag))
                return true;
        return false;
    }

    private static boolean containsTitle(Ranking<?> section, Title title) {
        if(section == null || title == null)
            return false;
        for(Object item : section)
            if(item instanceof Title && ((Title) item).getId() == title.getId())
                return true;
        return false;
    }

    private static boolean isWebtoonGenre(String label) {
        if(label == null)
            return false;
        for(String genre : WEBTOON_GENRES)
            if(genre.equals(label))
                return true;
        return false;
    }

    private static boolean isComicGenre(String label) {
        if(label == null)
            return false;
        for(String genre : COMIC_GENRES)
            if(genre.equals(label))
                return true;
        return false;
    }

    private static String comicGenreTag(String label) {
        if("17".equals(label))
            return "성인";
        if("호러".equals(label))
            return "공포";
        return label == null ? "" : label;
    }

    private static String webtoonStatusFromPath(String path) {
        if(path == null)
            return "";
        if(path.startsWith("/ing"))
            return "ing";
        if(path.startsWith("/end"))
            return "end";
        return "";
    }

    private static SectionInfo parseSectionInfo(String raw) {
        if(raw == null)
            return new SectionInfo("", "", "");
        String[] parts = raw.split("\\|", 3);
        return new SectionInfo(parts.length > 0 ? parts[0] : "", parts.length > 1 ? parts[1] : raw, parts.length > 2 ? parts[2] : "");
    }

    private static class SectionInfo {
        String group;
        String label;
        String path;

        SectionInfo(String group, String label, String path) {
            this.group = group;
            this.label = label;
            this.path = path;
        }
    }

    static int getQueryInt(String href, String key){
        try{
            String value = getQueryString(href, key);
            if(value.length() == 0) return -1;
            return Integer.parseInt(value);
        }catch (Exception e){
            return -1;
        }
    }

    static String getQueryString(String href, String key){
        try{
            String target = key + "=";
            int start = href.indexOf(target);
            if(start < 0) return "";
            start += target.length();
            int end = href.indexOf('&', start);
            if(end < 0) end = href.length();
            return URLDecoder.decode(href.substring(start, end), "UTF-8");
        }catch (Exception e){
            return "";
        }
    }

    private static String firstOwnText(Element element){
        if(element == null) return "";
        for(TextNode node : element.textNodes()){
            String text = node.text().trim();
            if(text.length() > 0) return text;
        }
        return element.ownText().trim();
    }

    private static String cleanText(Element element){
        if(element == null) return "";
        return element.text().trim();
    }

    private static String cleanTextWithoutChildren(Element element){
        if(element == null) return "";
        Element copy = element.clone();
        copy.children().remove();
        return copy.text().trim();
    }

    private static String extractBackgroundImage(String style){
        int start = style.indexOf("url(");
        if(start < 0) return "";
        start += 4;
        int end = style.indexOf(')', start);
        if(end < 0) return "";
        return style.substring(start, end).replace("'", "").replace("\"", "").trim();
    }

    public List<Ranking<?>> getDataSet(){
        return this.dataSet;
    }

    public static List<Ranking<?>> getBlankDataSet(){
        return getBlankDataSet(base_webtoon);
    }

    public static List<Ranking<?>> getBlankDataSet(int baseMode){
        List<Ranking<?>> dataset = new ArrayList<>();
        String[][] sections = getSections(baseMode);
        for(String[] section : sections)
            dataset.add(new Ranking<>(section[0]));
        return dataset;
    }
}
