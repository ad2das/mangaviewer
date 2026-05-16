package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ml.melun.mangaview.MainApplication.appContext;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

public class MainPageWebtoon {
    String baseUrl;
    int baseMode;

    private static final int MAIN_SECTION_LIMIT = 10;
    private static final long PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Pattern FAST_TITLE_LINK_PATTERN = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a>");
    private static final Pattern FAST_HEADING_PATTERN = Pattern.compile("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]>");
    private static final Pattern FAST_IMG_PATTERN = Pattern.compile("(?is)<img\\b([^>]*)>");
    private static final Pattern FAST_STYLE_PATTERN = Pattern.compile("(?is)style\\s*=\\s*(['\"])(.*?)\\1");
    private static final Pattern FAST_TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");

    private static final String[] WEBTOON_STATUS = {"ing", "end"};
    private static final String[] WEBTOON_STATUS_LABELS = {"연재웹툰", "완결웹툰"};
    private static final String[] WEBTOON_DAY_LABELS = {"최신", "신작", "월", "화", "수", "목", "금", "토", "일", "열흘"};
    private static final String[] WEBTOON_DAY_VALUES = {"recent", "new", "1", "2", "3", "4", "5", "6", "7", "10"};
    private static final String[] WEBTOON_GENRES = {"성인", "드라마", "판타지", "액션", "로맨스", "일상", "개그", "미스터리", "순정", "스포츠", "BL", "스릴러", "무협", "학원", "공포", "스토리"};
    private static final String[][] NTK_WEBTOON_ING_GENRES = {
            {"학원", "1"}, {"액션", "2"}, {"SF", "3"}, {"스토리", "4"}, {"판타지", "5"},
            {"BL", "6"}, {"개그", "7"}, {"연애", "8"}, {"드라마", "9"}, {"로맨스", "10"},
            {"시대극", "11"}, {"스포츠", "12"}, {"일상", "13"}, {"추리", "14"}, {"공포", "15"},
            {"성인", "adult"}, {"옴니버스", "17"}, {"에피소드", "18"}, {"무협", "19"}, {"소년", "20"},
            {"기타", "99"}, {"코미디", "코미디"}, {"순정", "순정"}, {"스릴러", "스릴러"},
            {"미스터리", "미스터리"}
    };
    private static final String[][] NTK_WEBTOON_END_GENRES = {
            {"학원", "1"}, {"액션", "2"}, {"SF", "3"}, {"스토리", "4"}, {"판타지", "5"},
            {"BL", "6"}, {"개그", "7"}, {"연애", "8"}, {"드라마", "9"}, {"로맨스", "10"},
            {"시대극", "11"}, {"스포츠", "12"}, {"일상", "13"}, {"추리", "14"}, {"공포", "15"},
            {"성인", "16"}, {"옴니버스", "17"}, {"에피소드", "18"}, {"무협", "19"}, {"소년", "20"},
            {"기타", "99"}, {"백합", "백합"}, {"코미디", "코미디"}, {"순정", "순정"},
            {"스릴러", "스릴러"}, {"미스터리", "미스터리"}
    };
    private static final String[] ALPHABET_LABELS = {"ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "A-Z", "0-9"};
    private static final String[] ALPHABET_VALUES = {"ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "a", "0"};

    private static final String[] COMIC_DAY_LABELS = {"최신", "주간", "격주", "월간", "단편", "완결", "단행본", "비정기", "미분류"};
    private static final String[] COMIC_DAY_VALUES = {"recent", "10", "11", "12", "14", "16", "15", "13", "20"};
    private static final String[] COMIC_GENRES = {"드라마", "액션", "SF", "TS", "개그", "게임", "공포", "도박", "호러", "라노벨", "러브코미디", "로맨스", "먹방", "미스터리", "백합", "붕탁", "성인", "순정", "스릴러", "스포츠", "시대", "애니화", "판타지", "학원", "BL", "여장", "역사", "요리", "음악", "이세계", "일상", "전생", "추리"};
    private static final String[] NTK_COMIC_GENRES = {"순정", "판타지", "러브코미디", "드라마", "17", "학원", "라노벨", "개그", "액션", "백합", "SF", "이세계", "일상", "스릴러", "애니화", "전생", "스포츠", "TS", "소년", "먹방", "붕탁", "게임", "호러", "시대", "로맨스", "추리", "무협", "음악", "BL", "하렘", "라이트노벨", "전이", "코미디", "일상+치유", "도박", "역사", "배틀", "다크 판타지", "요리", "추방", "미스터리", "보추", "서스펜스", "소년만화", "학원 배틀"};
    private static final String[] NTK_COMPLETED_COMIC_GENRES = {"순정", "판타지", "러브코미디", "드라마", "17", "학원", "라노벨", "개그", "액션", "백합", "SF", "이세계", "일상", "스릴러", "애니화", "전생", "스포츠", "TS", "소년", "먹방", "붕탁", "게임", "호러", "시대", "로맨스", "추리", "무협", "음악", "BL", "코미디", "일상+치유", "모험", "배틀", "라이트노벨", "라이트 노벨", "미스터리", "소년만화", "역사", "코믹", "능력자 배틀", "도박", "전이", "전쟁", "하렘", "다크 판타지", "요리", "청춘", "범죄", "학원 배틀"};
    private static final String INFERRED_TAG_CACHE_KEY = "webtoonInferredTagCacheV1";
    private static final int INFERRED_TAG_CACHE_LIMIT = 800;
    private static final LinkedHashMap<String, List<String>> inferredTagCache = new LinkedHashMap<>();
    private static boolean inferredTagCacheLoaded = false;
    private static int inferredTagCacheWrites = 0;
    private static final long CLASSIFICATION_DB_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final Map<Integer, List<String>> classificationDb = new LinkedHashMap<>();
    private static final Map<String, List<String>> classificationNameDb = new LinkedHashMap<>();
    private static final Map<Integer, DbTitle> classificationTitleDb = new LinkedHashMap<>();
    private static final Map<String, List<DbTitle>> classificationGenreDb = new LinkedHashMap<>();
    private static boolean classificationDbLoaded = false;
    private static long classificationDbLoadedAt = 0;
    private static final Object classificationDbLock = new Object();
    private static final Map<Integer, List<String>> comicClassificationDb = new LinkedHashMap<>();
    private static final Map<String, List<String>> comicClassificationNameDb = new LinkedHashMap<>();
    private static final Map<Integer, DbTitle> comicClassificationTitleDb = new LinkedHashMap<>();
    private static final Map<String, List<DbTitle>> comicClassificationGenreDb = new LinkedHashMap<>();
    private static boolean comicClassificationDbLoaded = false;
    private static long comicClassificationDbLoadedAt = 0;
    private static final Object comicClassificationDbLock = new Object();

    public static final String[][] WEBTOON_FILTER_GROUPS = buildWebtoonFilterGroups();
    public static final String[][] COMIC_FILTER_GROUPS = buildComicFilterGroups();
    public static final String[][] NTK_WEBTOON_FILTER_GROUPS = buildNtkWebtoonFilterGroups();
    public static final String[][] NTK_COMIC_FILTER_GROUPS = buildNtkComicFilterGroups();

    private static final String[][] SECTIONS = buildWebtoonSections();
    private static final String[][] COMIC_SECTIONS = buildComicSections();
    private static final String[][] NTK_SECTIONS = buildNtkWebtoonSections();
    private static final String[][] NTK_COMIC_SECTIONS = buildNtkComicSections();
    private static final String[][] HOME_PREVIEW_WEBTOON = {
            {"17801", "아티팩트 먹는 플레이어", "/platforms/naver.png", "스토리|판타지|액션"},
            {"13197", "귀환했는데 입대 전날이다", "/platforms/naver.png", "스토리|판타지|액션"},
            {"19225", "마수 사냥꾼이 살아가는 법", "/platforms/naver.png", "스토리|판타지|액션"},
            {"14137", "킬러 배드로", "/platforms/naver.png", "스토리|액션|드라마"},
            {"18940", "환생했더니 대공의 셋째 아들", "/platforms/naver.png", "스토리|판타지"}
    };
    private static final String[][] HOME_PREVIEW_COMIC = {
            {"10001", "마왕의 딸은 너무 착해!!", "https://i1.imgcloud18.com/10001/2266a3ee.jpg", "개그|판타지"},
            {"10002", "현자의 손자", "https://i1.imgcloud18.com/10002/a7f9cb68.jpg", "라노벨|판타지"},
            {"10003", "모험에 따라 오지 말아줘", "https://i1.imgcloud18.com/10003/692272a6.jpg", "라노벨|판타지"},
            {"10004", "지금까지 한 번도 여자취급", "https://i1.imgcloud18.com/10004/e415ca6c.jpg", "러브코미디|순정"},
            {"10007", "종말의 세라프", "https://i1.imgcloud18.com/10007/ef8eef43.jpg", "애니화|액션"}
    };

    List<Ranking<?>> dataSet;

    public static void preloadClassificationDbs() {
        loadClassificationDb();
        loadComicClassificationDb();
    }

    public static void invalidateClassificationDbs() {
        synchronized (classificationDbLock) {
            classificationDbLoaded = false;
            classificationDbLoadedAt = 0;
        }
        synchronized (comicClassificationDbLock) {
            comicClassificationDbLoaded = false;
            comicClassificationDbLoadedAt = 0;
        }
    }

    public static File classificationDbCacheDir(android.content.Context context) {
        if(context == null)
            return null;
        return new File(context.getFilesDir(), "classification-db");
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
        return getSections(baseMode, false);
    }

    public static String[][] getSections(int baseMode, boolean ntk){
        if(ntk)
            return baseMode == base_comic ? NTK_COMIC_SECTIONS : NTK_SECTIONS;
        if(baseMode == base_comic)
            return COMIC_SECTIONS;
        return SECTIONS;
    }

    public Ranking<Title> parseWolfTitle(CustomHttpClient client, String title, String path, int baseMode){
        for(int attempt = 0; attempt < 2; attempt++) {
            Ranking<Title> ranking = new Ranking<>(title);
            try{
                path = normalizePathForClient(client, path);
                if(client != null && client.isNtk()) {
                    Ranking<Title> apiRanking = parseNtkApiTitle(client, title, path, baseMode);
                    if(apiRanking.size() > 0)
                        return apiRanking;
                }
                CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
                Document d = Jsoup.parse(page.body);
                String sourceSite = client != null && client.isNtk() ? "ntk" : "";
                for(Title webtoon : parseWolfTitles(d, baseMode, MAIN_SECTION_LIMIT, sourceSite))
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

    private Ranking<Title> parseNtkApiTitle(CustomHttpClient client, String title, String path, int baseMode) throws Exception {
        Ranking<Title> ranking = new Ranking<>(title);
        String apiPath = Search.ntkCategoryApiPathForTest(path, 1, baseMode);
        if(apiPath == null || apiPath.length() == 0)
            return ranking;
        CustomHttpClient.PageResponse page = client.mgetCachedPage(apiPath, PAGE_CACHE_TTL_MS);
        for(Title webtoon : Search.parseNtkApiTitles(page.body, baseMode, MAIN_SECTION_LIMIT))
            ranking.add(webtoon);
        return ranking;
    }

    private static String normalizePathForClient(CustomHttpClient client, String path) {
        if(client != null && client.isNtk())
            return normalizeNtkPath(path);
        return path;
    }

    private static String normalizeNtkPath(String path) {
        if(path == null)
            return path;
        if(path.startsWith("/cm"))
            path = "/manhwa" + path.substring(3);
        if(path.startsWith("/ing") || path.startsWith("/end"))
            return normalizeNtkWebtoonPath(path);
        if(path.startsWith("/manhwa"))
            return normalizeNtkComicPath(path);
        return path;
    }

    static String normalizeNtkPathForTest(String path) {
        return normalizeNtkPath(path);
    }

    private static String normalizeNtkWebtoonPath(String path) {
        String type1 = rawQueryValue(path, "type1");
        String type2 = rawQueryValue(path, "type2");
        String order = rawQueryValue(path, "o");
        String sort = rawQueryValue(path, "sort");
        String day = rawQueryValue(path, "day");
        String tag = rawQueryValue(path, "tag");
        String letter = rawQueryValue(path, "letter");
        String base = path.startsWith("/end") ? "/end" : "/ing";
        ArrayList<String> params = new ArrayList<>();
        if("f".equals(order))
            addQueryParam(params, "sort=hot");
        if(sort != null && sort.length() > 0)
            addQueryParam(params, "sort=" + sort);
        if(day != null && day.length() > 0)
            addQueryParam(params, "day=" + day);
        if(tag != null && tag.length() > 0)
            addQueryParam(params, "tag=" + tag);
        if(letter != null && letter.length() > 0)
            addQueryParam(params, "letter=" + letter);
        if("day".equals(type1) && type2 != null && type2.length() > 0)
            addQueryParam(params, "day=" + type2);
        else if("genre".equals(type1) && type2 != null && type2.length() > 0)
            addQueryParam(params, "tag=" + type2);
        else if("alphabet".equals(type1) && type2 != null && type2.length() > 0)
            addQueryParam(params, "letter=" + type2);
        return params.size() == 0 ? base : base + "?" + joinQuery(params);
    }

    private static String normalizeNtkComicPath(String path) {
        String base = path.startsWith("/manhwa-end") ? "/manhwa-end" : "/manhwa";
        String type1 = rawQueryValue(path, "type1");
        String type2 = rawQueryValue(path, "type2");
        String order = rawQueryValue(path, "o");
        String sort = rawQueryValue(path, "sort");
        String genre = rawQueryValue(path, "g");
        String letter = rawQueryValue(path, "letter");
        String type = rawQueryValue(path, "type");
        ArrayList<String> params = new ArrayList<>();
        if("f".equals(order))
            addQueryParam(params, "sort=hot");
        if(sort != null && sort.length() > 0)
            addQueryParam(params, "sort=" + sort);
        if(genre != null && genre.length() > 0)
            addQueryParam(params, "g=" + genre);
        if(letter != null && letter.length() > 0)
            addQueryParam(params, "letter=" + letter);
        if(type != null && type.length() > 0)
            addQueryParam(params, "type=" + type);
        if("genre".equals(type1) && type2 != null && type2.length() > 0)
            addQueryParam(params, "g=" + type2);
        else if("alphabet".equals(type1) && type2 != null && type2.length() > 0)
            addQueryParam(params, "letter=" + type2);
        else if("complete".equals(type1) && type2 != null && type2.length() > 0 && !"recent".equals(type2))
            addQueryParam(params, "type=" + type2);
        return params.size() == 0 ? base : base + "?" + joinQuery(params);
    }

    private static void addQueryParam(ArrayList<String> params, String param) {
        if(param == null || param.length() == 0 || params.contains(param))
            return;
        params.add(param);
    }

    private static String joinQuery(ArrayList<String> params) {
        StringBuilder builder = new StringBuilder();
        for(String param : params) {
            if(builder.length() > 0)
                builder.append('&');
            builder.append(param);
        }
        return builder.toString();
    }

    private static String rawQueryValue(String value, String key) {
        int question = value.indexOf('?');
        if(question < 0)
            return null;
        String query = value.substring(question + 1);
        for(String part : query.split("&")) {
            int equals = part.indexOf('=');
            String name = equals >= 0 ? part.substring(0, equals) : part;
            if(name.equals(key))
                return equals >= 0 ? part.substring(equals + 1) : "";
        }
        return null;
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

    private static String[][] buildNtkWebtoonSections() {
        ArrayList<String[]> sections = new ArrayList<>();
        sections.add(section("연재웹툰", "인기순", "/ing?sort=hot"));
        sections.add(section("연재웹툰", "신작", "/ing"));
        sections.add(section("완결웹툰", "인기순", "/end?sort=hot"));
        sections.add(section("완결웹툰", "최신", "/end"));
        for(String[] genre : NTK_WEBTOON_ING_GENRES)
            sections.add(section("연재 장르별", genre[0], ntkWebtoonGenrePath("ing", genre[1])));
        for(String[] genre : NTK_WEBTOON_END_GENRES)
            sections.add(section("완결 장르별", genre[0], ntkWebtoonGenrePath("end", genre[1])));
        return sections.toArray(new String[0][]);
    }

    private static String[][] buildNtkComicSections() {
        ArrayList<String[]> sections = new ArrayList<>();
        sections.add(section("만화", "인기순", "/manhwa?sort=hot"));
        sections.add(section("만화", "최신", "/manhwa"));
        sections.add(section("완결만화", "인기순", "/manhwa-end?sort=hot"));
        sections.add(section("완결만화", "최신", "/manhwa-end"));
        for(String genre : NTK_COMIC_GENRES)
            sections.add(section("만화 장르별", genre, ntkComicGenrePath("/manhwa", genre)));
        for(String genre : NTK_COMPLETED_COMIC_GENRES)
            sections.add(section("완결만화 장르별", genre, ntkComicGenrePath("/manhwa-end", genre)));
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

    private static String[][] buildNtkWebtoonFilterGroups() {
        ArrayList<String[]> groups = new ArrayList<>();
        groups.add(new String[]{
                filter("정렬", "연재 인기순", "/ing?sort=hot"),
                filter("정렬", "연재 신작", "/ing"),
                filter("정렬", "완결 인기순", "/end?sort=hot"),
                filter("정렬", "완결 최신순", "/end")
        });
        LinkedHashMap<String, String> ingGenres = new LinkedHashMap<>();
        for(String[] genre : NTK_WEBTOON_ING_GENRES)
            ingGenres.put(genre[0], ntkWebtoonGenrePath("ing", genre[1]));
        LinkedHashMap<String, String> endGenres = new LinkedHashMap<>();
        for(String[] genre : NTK_WEBTOON_END_GENRES)
            endGenres.put(genre[0], ntkWebtoonGenrePath("end", genre[1]));
        groups.add(buildNtkCombinedGenreFilters("장르별", "webtoon", ingGenres, endGenres));
        return groups.toArray(new String[0][]);
    }

    private static String[][] buildNtkComicFilterGroups() {
        ArrayList<String[]> groups = new ArrayList<>();
        groups.add(new String[]{
                filter("정렬", "만화 인기순", "/manhwa?sort=hot"),
                filter("정렬", "만화 최신순", "/manhwa"),
                filter("정렬", "완결만화 인기순", "/manhwa-end?sort=hot"),
                filter("정렬", "완결만화 최신순", "/manhwa-end")
        });
        LinkedHashMap<String, String> genres = new LinkedHashMap<>();
        for(String genre : NTK_COMIC_GENRES)
            genres.put(genre, ntkComicGenrePath("/manhwa", genre));
        LinkedHashMap<String, String> completedGenres = new LinkedHashMap<>();
        for(String genre : NTK_COMPLETED_COMIC_GENRES)
            completedGenres.put(genre, ntkComicGenrePath("/manhwa-end", genre));
        groups.add(buildNtkCombinedGenreFilters("장르별", "comic", genres, completedGenres));
        return groups.toArray(new String[0][]);
    }

    private static String[] buildNtkCombinedGenreFilters(String group, String kind, LinkedHashMap<String, String> ongoing, LinkedHashMap<String, String> completed) {
        ArrayList<String> filters = new ArrayList<>();
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        for(String label : ongoing.keySet())
            labels.put(label, label);
        for(String label : completed.keySet())
            labels.put(label, label);
        for(String label : labels.keySet())
            filters.add(filter(group, label, ntkCombinedGenrePath(kind, label, ongoing.get(label), completed.get(label))));
        return filters.toArray(new String[0]);
    }

    private static String[] section(String group, String label, String path) {
        return new String[]{filter(group, label, path), path};
    }

    private static String filter(String group, String label, String path) {
        return group + "|" + label + "|" + path;
    }

    public static String resolveCurrentSiteFilterPath(String label, int baseMode, boolean ntkSite) {
        if(label == null || label.length() == 0)
            return "";
        String[][] groups;
        if(ntkSite)
            groups = baseMode == base_comic ? NTK_COMIC_FILTER_GROUPS : NTK_WEBTOON_FILTER_GROUPS;
        else
            groups = baseMode == base_comic ? COMIC_FILTER_GROUPS : WEBTOON_FILTER_GROUPS;
        for(String[] group : groups) {
            for(String item : group) {
                SectionInfo info = parseSectionInfo(item);
                if(label.equals(info.label))
                    return info.path;
            }
        }
        return "";
    }

    public static boolean isFilterPathForSite(String path, boolean ntkSite) {
        if(path == null || path.length() == 0)
            return false;
        boolean wfwfFilter = path.startsWith("/cm?")
                || path.startsWith("/cl?")
                || path.startsWith("/ing?type1=")
                || path.startsWith("/end?type1=");
        boolean ntkFilter = path.startsWith("/ntk-genre?")
                || path.equals("/ing")
                || path.equals("/end")
                || path.equals("/manhwa")
                || path.equals("/manhwa-end")
                || path.startsWith("/ing?sort=")
                || path.startsWith("/end?sort=")
                || path.startsWith("/ing?tag=")
                || path.startsWith("/end?tag=")
                || path.startsWith("/ing?cat=")
                || path.startsWith("/manhwa?")
                || path.startsWith("/manhwa-end?");
        return ntkSite ? ntkFilter && !wfwfFilter : wfwfFilter && !ntkFilter;
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

    private static String ntkWebtoonGenrePath(String status, String tag) {
        if("ing".equals(status) && "adult".equals(tag))
            return "/ing?cat=adult";
        return "/" + status + "?tag=" + percentEncode(tag, StandardCharsets.UTF_8);
    }

    private static String ntkComicGenrePath(String route, String genre) {
        return route + "?g=" + percentEncode(genre, StandardCharsets.UTF_8);
    }

    private static String ntkCombinedGenrePath(String kind, String label, String ongoingPath, String completedPath) {
        ArrayList<String> params = new ArrayList<>();
        addQueryParam(params, "kind=" + kind);
        addQueryParam(params, "label=" + percentEncode(label, StandardCharsets.UTF_8));
        if(ongoingPath != null && ongoingPath.length() > 0)
            addQueryParam(params, "ongoing=" + percentEncode(ongoingPath, StandardCharsets.UTF_8));
        if(completedPath != null && completedPath.length() > 0)
            addQueryParam(params, "completed=" + percentEncode(completedPath, StandardCharsets.UTF_8));
        return "/ntk-genre?" + joinQuery(params);
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
        return parseWolfTitles(d, baseMode, limit, "");
    }

    static ArrayList<Title> parseWolfTitles(Document d, int baseMode, int limit, String sourceSite){
        return parseWolfTitles(d, baseMode, limit, sourceSite, true);
    }

    static ArrayList<Title> parseWolfTitles(Document d, int baseMode, int limit, String sourceSite, boolean enrichClassification){
        if(d == null)
            return new ArrayList<>();
        return parseWolfTitleElements(
                d.select("div.webtoon-list li, article.searchItem, li:has(a[href*=/webtoon/]), li:has(a[href*=/manhwa/]), article:has(a[href*=/webtoon/]), article:has(a[href*=/manhwa/]), div:has(> a[href*=/webtoon/]), div:has(> a[href*=/manhwa/]), a[href*=toon=], a[href*=/webtoon/], a[href*=/manhwa/]"),
                baseMode, limit, sourceSite, enrichClassification);
    }

    static ArrayList<Title> parseWolfTitleAnchorsFast(Document d, int baseMode, int limit, String sourceSite) {
        if(d == null)
            return new ArrayList<>();
        return parseWolfTitleElements(
                d.select("a[href*=toon=], a[href*=/webtoon/], a[href*=/manhwa/]"),
                baseMode, limit, sourceSite, false);
    }

    static ArrayList<Title> parseWolfSearchHtmlFast(String body, int baseMode, int limit, String sourceSite) {
        ArrayList<Title> titles = new ArrayList<>();
        java.util.HashSet<String> seenTitleKeys = new java.util.HashSet<>();
        if(body == null || body.length() == 0)
            return titles;
        Matcher matcher = FAST_TITLE_LINK_PATTERN.matcher(body);
        while(matcher.find()) {
            try {
                String href = decodeHtml(matcher.group(2));
                int id = getQueryInt(href, "toon");
                if(id <= 0)
                    id = getPathId(href, "webtoon");
                if(id <= 0)
                    id = getPathId(href, "manhwa");
                if(id <= 0)
                    continue;
                int detectedBaseMode = detectWolfBaseMode(href);
                if(detectedBaseMode != 0 && detectedBaseMode != baseMode)
                    continue;
                String seenKey = baseMode + ":" + id;
                if(!seenTitleKeys.add(seenKey))
                    continue;

                String inner = matcher.group(3);
                String name = firstHeadingText(inner);
                if(name.length() == 0)
                    name = cleanNtkListText(decodeHtml(stripTags(inner)));
                String thumb = firstFastThumb(inner);
                thumb = resolveFastSearchThumb(name, id, thumb, baseMode, sourceSite);
                Title parsed = new Title(name, thumb, "", new ArrayList<>(), "", id, baseMode);
                if(sourceSite != null && sourceSite.trim().length() > 0)
                    parsed.setSourceSite(sourceSite.trim());
                applyInferredSearchTagsIfLoaded(parsed);
                titles.add(parsed);
                if(limit > 0 && titles.size() >= limit)
                    break;
            } catch (Exception ignored) {
            }
        }
        return titles;
    }

    private static ArrayList<Title> parseWolfTitleElements(Elements candidates, int baseMode, int limit,
                                                           String sourceSite, boolean enrichClassification) {
        ArrayList<Title> titles = new ArrayList<>();
        java.util.HashSet<String> seenTitleKeys = new java.util.HashSet<>();
        for(Element e : candidates){
            try{
                Element link = findTitleLink(e, baseMode);
                if(link == null) continue;
                String href = link.attr("href");
                int id = getQueryInt(href, "toon");
                if(id <= 0)
                    id = getPathId(href, "webtoon");
                if(id <= 0)
                    id = getPathId(href, "manhwa");
                if(id <= 0) continue;
                int detectedBaseMode = detectWolfBaseMode(href);
                if(detectedBaseMode != 0 && detectedBaseMode != baseMode)
                    continue;
                String seenKey = baseMode + ":" + id;
                if(!seenTitleKeys.add(seenKey))
                    continue;

                Element context = titleCardContext(e, link);
                String name = firstOwnText(context.selectFirst("p.subject"));
                if(name.length() == 0)
                    name = cleanText(context.selectFirst("h6.searchDetailTitle"));
                if(name.length() == 0)
                    name = cleanText(context.selectFirst(".subject, .wr-subject, .episode-title, .post-title, .title, .name, h2, h3, h4, h5, h6"));
                if(name.length() == 0)
                    name = link.attr("title");
                if(name.length() == 0)
                    name = getQueryString(href, "title");
                if(name.length() == 0)
                    name = cleanNtkListText(link.text());
                if(name.length() == 0)
                    name = cleanNtkListText(context.text());

                String thumb = "";
                Element img = context.selectFirst("img");
                if(img != null)
                    thumb = firstImageAttr(img);
                if(thumb.length() == 0) {
                    Element background = context.selectFirst("[style*=background-image], [style*=background]");
                    if(background != null)
                        thumb = extractBackgroundImage(background.attr("style"));
                }
                thumb = enrichClassification
                        ? resolveCoverThumb(name, id, thumb, baseMode)
                        : resolveCoverThumbIfLoaded(name, id, thumb, baseMode);

                Elements infos = context.select("div.txt p");
                List<String> tags = new ArrayList<>();
                if(infos.size() > 1)
                    for(String tag : cleanTextWithoutChildren(infos.get(1)).split("/"))
                        if(tag.trim().length() > 0) tags.add(tag.trim());
                if(tags.size() == 0) {
                    String genreText = cleanText(context.selectFirst("p.genre, .genre"));
                    if(genreText.length() > 0)
                        for(String tag : genreText.split("[,/]+"))
                            if(tag.trim().length() > 0) tags.add(tag.trim());
                }

                String release = "";
                if(infos.size() > 2)
                    release = cleanTextWithoutChildren(infos.get(2));
                if(release.length() == 0)
                    release = cleanText(context.selectFirst("p.ep, .ep, .episode-count, .latest-episode"));

                Title parsed = new Title(name, thumb, "", tags, release, id, baseMode);
                if(sourceSite != null && sourceSite.trim().length() > 0)
                    parsed.setSourceSite(sourceSite.trim());
                titles.add(parsed);
                if(enrichClassification)
                    applyInferredSearchTags(parsed);
                else
                    applyInferredSearchTagsIfLoaded(parsed);
                if(limit > 0 && titles.size() >= limit) break;
            }catch (Exception ignored){
            }
        }
        return titles;
    }

    private static Element findTitleLink(Element root, int baseMode) {
        if(root == null)
            return null;
        Elements links = root.tagName().equals("a")
                ? new Elements(root)
                : root.select("a[href*=toon=], a[href*=/webtoon/], a[href*=/manhwa/]");
        for(Element link : links) {
            String href = link.attr("href");
            if(isEpisodePath(href, "webtoon") || isEpisodePath(href, "manhwa"))
                continue;
            int id = getQueryInt(href, "toon");
            if(id <= 0)
                id = getPathId(href, "webtoon");
            if(id <= 0)
                id = getPathId(href, "manhwa");
            if(id <= 0)
                continue;
            int detectedBaseMode = detectWolfBaseMode(href);
            if(detectedBaseMode != 0 && detectedBaseMode != baseMode)
                continue;
            return link;
        }
        return null;
    }

    private static Element titleCardContext(Element root, Element link) {
        if(root != null && !root.tagName().equals("a"))
            return root;
        if(hasTitleCardData(link))
            return link;
        Element best = link;
        Element current = link == null ? null : link.parent();
        for(int depth = 0; current != null && depth < 5; depth++, current = current.parent()) {
            int titleLinks = current.select("a[href*=/webtoon/], a[href*=/manhwa/], a[href*=toon=]").size();
            if(hasTitleCardData(current) && titleLinks <= 1)
                best = current;
            String tag = current.tagName();
            if("li".equals(tag) || "article".equals(tag))
                break;
        }
        return best == null ? link : best;
    }

    private static boolean hasTitleCardData(Element element) {
        return element != null
                && element.selectFirst("img, [style*=background-image], [style*=background], .subject, .wr-subject, .post-title, .title, .name, h2, h3, h4, h5, h6") != null;
    }

    private static boolean isEpisodePath(String href, String segment) {
        return getSecondPathId(href, segment) > 0;
    }

    private static int detectWolfBaseMode(String href) {
        if(href == null)
            return 0;
        String normalized = href.toLowerCase(Locale.ROOT);
        if(normalized.contains("/cl?toon=")
                || normalized.contains("/cv?toon=")
                || normalized.contains("/cm?")
                || normalized.contains("/manhwa"))
            return base_comic;
        if(normalized.contains("/list?toon=")
                || normalized.contains("/view?toon=")
                || normalized.contains("/webtoon")
                || normalized.contains("/ing?")
                || normalized.contains("/end?"))
            return base_webtoon;
        return 0;
    }

    static int getPathId(String href, String segment) {
        try {
            if(href == null)
                return -1;
            String marker = "/" + segment + "/";
            int start = href.indexOf(marker);
            if(start < 0)
                return -1;
            start += marker.length();
            int end = href.indexOf('/', start);
            if(end < 0)
                end = href.indexOf('?', start);
            if(end < 0)
                end = href.indexOf('#', start);
            if(end < 0)
                end = href.length();
            return Integer.parseInt(href.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    static int getSecondPathId(String href, String segment) {
        try {
            if(href == null)
                return -1;
            String marker = "/" + segment + "/";
            int start = href.indexOf(marker);
            if(start < 0)
                return -1;
            start = href.indexOf('/', start + marker.length());
            if(start < 0)
                return -1;
            start++;
            int end = href.indexOf('/', start);
            if(end < 0)
                end = href.indexOf('?', start);
            if(end < 0)
                end = href.length();
            return Integer.parseInt(href.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String cleanNtkListText(String text) {
        if(text == null)
            return "";
        String cleaned = text.replace("UP", "")
                .replace("NEW", "")
                .replace("완결", "")
                .replace("▶ 보기", "")
                .replace("›", " ")
                .trim();
        cleaned = cleaned.replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("\\s+\\d+화.*$", "").trim();
        return cleaned;
    }

    private static String firstHeadingText(String html) {
        if(html == null)
            return "";
        Matcher matcher = FAST_HEADING_PATTERN.matcher(html);
        if(!matcher.find())
            return "";
        return cleanNtkListText(decodeHtml(stripTags(matcher.group(1))));
    }

    private static String firstFastThumb(String html) {
        if(html == null)
            return "";
        Matcher imgMatcher = FAST_IMG_PATTERN.matcher(html);
        if(imgMatcher.find()) {
            String attrs = imgMatcher.group(1);
            String[] names = {
                    "data-original",
                    "data-src",
                    "data-lazy-src",
                    "data-url",
                    "data-image",
                    "data-img",
                    "data-thumb",
                    "data-thumbnail",
                    "data-background-image"
            };
            for(String attr : names) {
                String value = attrValue(attrs, attr);
                if(isUsableImageValue(value))
                    return decodeHtml(value).trim();
            }
            String srcset = firstSrcsetImage(attrValue(attrs, "data-srcset"));
            if(srcset.length() == 0)
                srcset = firstSrcsetImage(attrValue(attrs, "srcset"));
            if(srcset.length() > 0)
                return decodeHtml(srcset).trim();
            String styleImage = extractBackgroundImage(attrValue(attrs, "style"));
            if(isUsableImageValue(styleImage))
                return decodeHtml(styleImage).trim();
            String src = attrValue(attrs, "src");
            if(isUsableImageValue(src))
                return decodeHtml(src).trim();
        }
        Matcher styleMatcher = FAST_STYLE_PATTERN.matcher(html);
        while(styleMatcher.find()) {
            String styleImage = extractBackgroundImage(styleMatcher.group(2));
            if(isUsableImageValue(styleImage))
                return decodeHtml(styleImage).trim();
        }
        return "";
    }

    private static String attrValue(String attrs, String attr) {
        if(attrs == null || attr == null)
            return "";
        Matcher quoted = Pattern.compile("(?is)\\b" + Pattern.quote(attr) + "\\s*=\\s*(['\"])(.*?)\\1").matcher(attrs);
        if(quoted.find())
            return quoted.group(2);
        Matcher unquoted = Pattern.compile("(?is)\\b" + Pattern.quote(attr) + "\\s*=\\s*([^\\s>]+)").matcher(attrs);
        return unquoted.find() ? unquoted.group(1) : "";
    }

    private static String stripTags(String html) {
        if(html == null)
            return "";
        return FAST_TAG_PATTERN.matcher(html).replaceAll(" ");
    }

    private static String decodeHtml(String value) {
        if(value == null)
            return "";
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String firstImageAttr(Element img) {
        if(img == null)
            return "";
        String[] attrs = {
                "data-original",
                "data-src",
                "data-lazy-src",
                "data-url",
                "data-image",
                "data-img",
                "data-thumb",
                "data-thumbnail",
                "data-background-image"
        };
        for(String attr : attrs) {
            String value = img.attr(attr);
            if(isUsableImageValue(value))
                return value.trim();
        }
        String srcset = firstSrcsetImage(img.attr("data-srcset"));
        if(srcset.length() == 0)
            srcset = firstSrcsetImage(img.attr("srcset"));
        if(srcset.length() > 0)
            return srcset;
        String styleImage = extractBackgroundImage(img.attr("style"));
        if(isUsableImageValue(styleImage))
            return styleImage;
        String src = img.attr("src");
        if(isUsableImageValue(src))
            return src.trim();
        return "";
    }

    private static String firstSrcsetImage(String srcset) {
        if(srcset != null && srcset.trim().length() > 0) {
            String first = srcset.split(",")[0].trim();
            int space = first.indexOf(' ');
            if(space > 0)
                first = first.substring(0, space);
            if(isUsableImageValue(first))
                return first.trim();
        }
        return "";
    }

    private static boolean isUsableImageValue(String value) {
        if(value == null)
            return false;
        String trimmed = value.trim();
        return trimmed.length() > 0
                && !trimmed.startsWith("data:")
                && !"about:blank".equalsIgnoreCase(trimmed)
                && !"#".equals(trimmed);
    }

    public static String resolveCoverThumb(String name, int id, String thumb, int baseMode) {
        if(!isPlatformLogoThumb(thumb))
            return thumb == null ? "" : thumb;
        DbTitle dbTitle = findClassificationDbTitle(name, id, baseMode);
        if(dbTitle != null && !isPlatformLogoThumb(dbTitle.thumb) && dbTitle.thumb != null && dbTitle.thumb.length() > 0)
            return dbTitle.thumb;
        return "";
    }

    public static String resolveCoverThumbIfLoaded(String name, int id, String thumb, int baseMode) {
        if(!isPlatformLogoThumb(thumb))
            return thumb == null ? "" : thumb;
        DbTitle dbTitle = findClassificationDbTitleIfLoaded(name, id, baseMode);
        if(dbTitle != null && !isPlatformLogoThumb(dbTitle.thumb) && dbTitle.thumb != null && dbTitle.thumb.length() > 0)
            return dbTitle.thumb;
        return "";
    }

    private static String resolveFastSearchThumb(String name, int id, String thumb, int baseMode, String sourceSite) {
        if(!isPlatformLogoThumb(thumb))
            return thumb == null ? "" : thumb;
        if("ntk".equalsIgnoreCase(sourceSite == null ? "" : sourceSite.trim()))
            return "";
        return resolveCoverThumbIfLoaded(name, id, thumb, baseMode);
    }

    public static boolean isPlatformLogoThumb(String thumb) {
        if(thumb == null)
            return true;
        String normalized = thumb.trim().toLowerCase(Locale.ROOT);
        return normalized.length() == 0
                || normalized.startsWith("/platforms/")
                || normalized.contains("/platforms/");
    }

    private static DbTitle findClassificationDbTitle(String name, int id, int baseMode) {
        if(baseMode == base_comic) {
            loadComicClassificationDb();
            synchronized (comicClassificationDbLock) {
                DbTitle byId = comicClassificationTitleDb.get(id);
                if(byId != null)
                    return byId;
                return findClassificationDbTitleByName(comicClassificationTitleDb, name);
            }
        }
        loadClassificationDb();
        synchronized (classificationDbLock) {
            DbTitle byId = classificationTitleDb.get(id);
            if(byId != null)
                return byId;
            return findClassificationDbTitleByName(classificationTitleDb, name);
        }
    }

    private static DbTitle findClassificationDbTitleIfLoaded(String name, int id, int baseMode) {
        if(baseMode == base_comic) {
            synchronized (comicClassificationDbLock) {
                if(!comicClassificationDbLoaded)
                    return null;
                DbTitle byId = comicClassificationTitleDb.get(id);
                if(byId != null)
                    return byId;
                return findClassificationDbTitleByName(comicClassificationTitleDb, name);
            }
        }
        return findWebtoonClassificationDbTitleIfLoaded(name, id);
    }

    private static DbTitle findWebtoonClassificationDbTitleIfLoaded(String name, int id) {
        synchronized (classificationDbLock) {
            if(!classificationDbLoaded)
                return null;
            DbTitle byId = classificationTitleDb.get(id);
            if(byId != null)
                return byId;
            return findClassificationDbTitleByName(classificationTitleDb, name);
        }
    }

    private static DbTitle findClassificationDbTitleByName(Map<Integer, DbTitle> titleDb, String name) {
        String nameKey = normalizeClassificationName(name);
        if(nameKey.length() == 0)
            return null;
        for(DbTitle title : titleDb.values()) {
            if(title != null && nameKey.equals(normalizeClassificationName(title.name)))
                return title;
        }
        return null;
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

    public static void applyInferredSearchTagsIfLoaded(Title title) {
        if(title == null)
            return;
        if(title.getBaseMode() == base_webtoon) {
            List<String> tags = new ArrayList<>(title.getTags());
            List<String> dbTags = getClassificationDbTagsIfLoaded(title.getId());
            if(dbTags == null)
                dbTags = getClassificationDbTagsIfLoaded(title.getName());
            if(hasMeaningfulClassificationTags(dbTags)) {
                for(String dbTag : dbTags)
                    addUnique(tags, dbTag);
                title.setTags(tags);
                return;
            }
            for(String inferredTag : inferWebtoonTags(title))
                addUnique(tags, inferredTag);
            title.setTags(tags);
            return;
        }
        if(title.getBaseMode() != base_comic)
            return;
        List<String> tags = new ArrayList<>(title.getTags());
        List<String> dbTags = getComicClassificationDbTagsIfLoaded(title.getId());
        if(dbTags == null)
            dbTags = getComicClassificationDbTagsIfLoaded(title.getName());
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
            if(pool != null) {
                for(Title title : pool.values()) {
                    if(section.size() >= MAIN_SECTION_LIMIT)
                        break;
                    if(!hasTag(title, info.label) || containsTitle(section, title))
                        continue;
                    ((Ranking<Object>) section).add(title);
                }
            }

            for(Title title : getClassificationDbTitlesByGenre(info.label, MAIN_SECTION_LIMIT)) {
                if(section.size() >= MAIN_SECTION_LIMIT)
                    break;
                if(containsTitle(section, title))
                    continue;
                ((Ranking<Object>) section).add(title);
            }
        }

        for(Ranking<?> section : sections) {
            if(section == null || section.size() > 0)
                continue;
            SectionInfo info = parseSectionInfo(section.getName());
            if(isWebtoonGenre(info.label))
                continue;
            for(Title title : getClassificationDbTitles(MAIN_SECTION_LIMIT)) {
                if(section.size() >= MAIN_SECTION_LIMIT)
                    break;
                if(containsTitle(section, title))
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

    public static List<Ranking<?>> getFastHomePreviewDataSet(int baseMode, boolean ntk) {
        List<Ranking<?>> dataset = getBlankDataSet(baseMode, ntk);
        for(Ranking<?> section : dataset) {
            if(section == null)
                continue;
            SectionInfo info = parseSectionInfo(section.getName());
            if(!info.label.contains("인기순"))
                continue;
            addHomePreviewTitles((Ranking<Object>) section, baseMode);
            if(section.size() > 0)
                break;
        }
        return dataset;
    }

    private static void addHomePreviewTitles(Ranking<Object> section, int baseMode) {
        if(section == null)
            return;
        String[][] source = baseMode == base_comic ? HOME_PREVIEW_COMIC : HOME_PREVIEW_WEBTOON;
        for(String[] item : source)
            section.add(previewTitle(item, baseMode));
    }

    private static Title previewTitle(String[] item, int baseMode) {
        int id = 0;
        try {
            id = Integer.parseInt(item[0]);
        } catch (Exception ignored) {
        }
        ArrayList<String> tags = new ArrayList<>();
        if(item.length > 3 && item[3] != null)
            for(String tag : item[3].split("\\|"))
                if(tag.length() > 0)
                    tags.add(tag);
        return new Title(item[1], item[2], "", tags, "", id, baseMode);
    }

    public static List<Ranking<?>> getFastNtkWebtoonDataSet() {
        return getFastHomePreviewDataSet(base_webtoon, true);
    }

    public static ArrayList<Title> getClassificationDbTitlesByGenre(String genre, int offset, int limit) {
        ArrayList<Title> result = new ArrayList<>();
        if(genre == null)
            return result;
        loadClassificationDb();
        synchronized (classificationDbLock) {
            List<DbTitle> titles = classificationGenreDb.get(normalizeClassificationTag(genre));
            if(titles == null)
                return result;
            int start = Math.max(0, offset);
            for(int i = start; i < titles.size(); i++) {
                DbTitle dbTitle = titles.get(i);
                result.add(classificationTitle(dbTitle, base_webtoon));
                if(limit > 0 && result.size() >= limit)
                    break;
            }
        }
        return result;
    }

    private static ArrayList<Title> getClassificationDbTitles(int limit) {
        ArrayList<Title> result = new ArrayList<>();
        loadClassificationDb();
        synchronized (classificationDbLock) {
            for(DbTitle dbTitle : classificationTitleDb.values()) {
                result.add(classificationTitle(dbTitle, base_webtoon));
                if(limit > 0 && result.size() >= limit)
                    break;
            }
        }
        return result;
    }

    private static ArrayList<Title> getClassificationDbTitlesIfLoaded(int limit) {
        ArrayList<Title> result = new ArrayList<>();
        synchronized (classificationDbLock) {
            if(!classificationDbLoaded)
                return result;
            for(DbTitle dbTitle : classificationTitleDb.values()) {
                result.add(classificationTitle(dbTitle, base_webtoon));
                if(limit > 0 && result.size() >= limit)
                    break;
            }
        }
        return result;
    }

    private static ArrayList<Title> getClassificationDbTitlesByGenreIfLoaded(String genre, int limit) {
        ArrayList<Title> result = new ArrayList<>();
        synchronized (classificationDbLock) {
            if(!classificationDbLoaded || genre == null)
                return result;
            List<DbTitle> titles = classificationGenreDb.get(normalizeClassificationTag(genre));
            if(titles == null)
                return result;
            for(DbTitle dbTitle : titles) {
                result.add(classificationTitle(dbTitle, base_webtoon));
                if(limit > 0 && result.size() >= limit)
                    break;
            }
        }
        return result;
    }

    public static int getClassificationDbGenreCount(String genre) {
        if(genre == null)
            return 0;
        loadClassificationDb();
        synchronized (classificationDbLock) {
            List<DbTitle> titles = classificationGenreDb.get(normalizeClassificationTag(genre));
            return titles == null ? 0 : titles.size();
        }
    }

    public static ArrayList<Title> getComicClassificationDbTitlesByGenre(String genre, int limit) {
        return getComicClassificationDbTitlesByGenre(genre, 0, limit);
    }

    public static ArrayList<Title> getComicClassificationDbTitlesByGenre(String genre, int offset, int limit) {
        ArrayList<Title> result = new ArrayList<>();
        if(genre == null)
            return result;
        loadComicClassificationDb();
        synchronized (comicClassificationDbLock) {
            List<DbTitle> titles = comicClassificationGenreDb.get(normalizeClassificationTag(genre));
            if(titles == null)
                return result;
            int start = Math.max(0, offset);
            for(int i = start; i < titles.size(); i++) {
                DbTitle dbTitle = titles.get(i);
                result.add(classificationTitle(dbTitle, base_comic));
                if(limit > 0 && result.size() >= limit)
                    break;
            }
        }
        return result;
    }

    private static Title classificationTitle(DbTitle dbTitle, int baseMode) {
        Title title = new Title(dbTitle.name, dbTitle.thumb, "", dbTitle.tags, dbTitle.release, dbTitle.id, baseMode);
        title.setSourceSite("wfwf");
        return title;
    }

    public static int getComicClassificationDbGenreCount(String genre) {
        if(genre == null)
            return 0;
        loadComicClassificationDb();
        synchronized (comicClassificationDbLock) {
            List<DbTitle> titles = comicClassificationGenreDb.get(normalizeClassificationTag(genre));
            return titles == null ? 0 : titles.size();
        }
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

    private static List<String> getClassificationDbTags(int titleId) {
        loadClassificationDb();
        synchronized (classificationDbLock) {
            List<String> tags = classificationDb.get(titleId);
            return tags == null ? null : new ArrayList<>(tags);
        }
    }

    private static List<String> getClassificationDbTagsIfLoaded(int titleId) {
        synchronized (classificationDbLock) {
            if(!classificationDbLoaded)
                return null;
            List<String> tags = classificationDb.get(titleId);
            return tags == null ? null : new ArrayList<>(tags);
        }
    }

    private static List<String> getClassificationDbTags(String titleName) {
        loadClassificationDb();
        String key = normalizeClassificationName(titleName);
        if(key.length() == 0)
            return null;
        synchronized (classificationDbLock) {
            List<String> tags = classificationNameDb.get(key);
            return tags == null ? null : new ArrayList<>(tags);
        }
    }

    private static List<String> getClassificationDbTagsIfLoaded(String titleName) {
        String key = normalizeClassificationName(titleName);
        if(key.length() == 0)
            return null;
        synchronized (classificationDbLock) {
            if(!classificationDbLoaded)
                return null;
            List<String> tags = classificationNameDb.get(key);
            return tags == null ? null : new ArrayList<>(tags);
        }
    }

    private static List<String> getComicClassificationDbTags(int titleId) {
        loadComicClassificationDb();
        synchronized (comicClassificationDbLock) {
            List<String> tags = comicClassificationDb.get(titleId);
            return tags == null ? null : new ArrayList<>(tags);
        }
    }

    private static List<String> getComicClassificationDbTagsIfLoaded(int titleId) {
        synchronized (comicClassificationDbLock) {
            if(!comicClassificationDbLoaded)
                return null;
            List<String> tags = comicClassificationDb.get(titleId);
            return tags == null ? null : new ArrayList<>(tags);
        }
    }

    private static List<String> getComicClassificationDbTags(String titleName) {
        loadComicClassificationDb();
        String key = normalizeClassificationName(titleName);
        if(key.length() == 0)
            return null;
        synchronized (comicClassificationDbLock) {
            List<String> tags = comicClassificationNameDb.get(key);
            return tags == null ? null : new ArrayList<>(tags);
        }
    }

    private static List<String> getComicClassificationDbTagsIfLoaded(String titleName) {
        String key = normalizeClassificationName(titleName);
        if(key.length() == 0)
            return null;
        synchronized (comicClassificationDbLock) {
            if(!comicClassificationDbLoaded)
                return null;
            List<String> tags = comicClassificationNameDb.get(key);
            return tags == null ? null : new ArrayList<>(tags);
        }
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

    private static void loadClassificationDb() {
        long now = System.currentTimeMillis();
        synchronized (classificationDbLock) {
            if(classificationDbLoaded && now - classificationDbLoadedAt <= CLASSIFICATION_DB_TTL_MS)
                return;
        }
        Map<Integer, List<String>> idDb = new LinkedHashMap<>();
        Map<String, List<String>> nameDb = new LinkedHashMap<>();
        Map<Integer, DbTitle> titleDb = new LinkedHashMap<>();
        Map<String, List<DbTitle>> genreDb = new LinkedHashMap<>();
        try {
            parseClassificationDb(readCachedClassificationDb("webtoon-classification.json"), idDb, nameDb, titleDb, genreDb);
            if(idDb.size() == 0)
                parseClassificationDb(readBundledClassificationDb(), idDb, nameDb, titleDb, genreDb);
        } catch (Exception e) {
            idDb.clear();
            nameDb.clear();
            titleDb.clear();
            genreDb.clear();
            parseClassificationDb(readBundledClassificationDb(), idDb, nameDb, titleDb, genreDb);
        }
        synchronized (classificationDbLock) {
            classificationDb.clear();
            classificationNameDb.clear();
            classificationTitleDb.clear();
            classificationGenreDb.clear();
            classificationDb.putAll(idDb);
            classificationNameDb.putAll(nameDb);
            classificationTitleDb.putAll(titleDb);
            classificationGenreDb.putAll(genreDb);
            classificationDbLoaded = true;
            classificationDbLoadedAt = now;
        }
    }

    private static void loadComicClassificationDb() {
        long now = System.currentTimeMillis();
        synchronized (comicClassificationDbLock) {
            if(comicClassificationDbLoaded && now - comicClassificationDbLoadedAt <= CLASSIFICATION_DB_TTL_MS)
                return;
        }
        Map<Integer, List<String>> idDb = new LinkedHashMap<>();
        Map<String, List<String>> nameDb = new LinkedHashMap<>();
        Map<Integer, DbTitle> titleDb = new LinkedHashMap<>();
        Map<String, List<DbTitle>> genreDb = new LinkedHashMap<>();
        try {
            parseClassificationDb(readCachedClassificationDb("comic-classification.json"), idDb, nameDb, titleDb, genreDb);
            if(idDb.size() == 0)
                parseClassificationDb(readBundledComicClassificationDb(), idDb, nameDb, titleDb, genreDb);
        } catch (Exception e) {
            idDb.clear();
            nameDb.clear();
            titleDb.clear();
            genreDb.clear();
            parseClassificationDb(readBundledComicClassificationDb(), idDb, nameDb, titleDb, genreDb);
        }
        synchronized (comicClassificationDbLock) {
            comicClassificationDb.clear();
            comicClassificationNameDb.clear();
            comicClassificationTitleDb.clear();
            comicClassificationGenreDb.clear();
            comicClassificationDb.putAll(idDb);
            comicClassificationNameDb.putAll(nameDb);
            comicClassificationTitleDb.putAll(titleDb);
            comicClassificationGenreDb.putAll(genreDb);
            comicClassificationDbLoaded = true;
            comicClassificationDbLoadedAt = now;
        }
    }

    private static void parseClassificationDb(String json) {
        parseClassificationDb(json, classificationDb, classificationNameDb, classificationTitleDb, classificationGenreDb);
    }

    private static void parseComicClassificationDb(String json) {
        parseClassificationDb(json, comicClassificationDb, comicClassificationNameDb, comicClassificationTitleDb, comicClassificationGenreDb);
    }

    static void putClassificationDbTitleForTest(int id, String name, boolean comic, String... tags) {
        ArrayList<String> tagList = new ArrayList<>();
        if(tags != null)
            for(String tag : tags)
                if(tag != null && tag.length() > 0)
                    tagList.add(tag);
        if(comic) {
            synchronized (comicClassificationDbLock) {
                comicClassificationDbLoaded = true;
                comicClassificationDbLoadedAt = System.currentTimeMillis();
                comicClassificationDb.put(id, tagList);
                comicClassificationNameDb.put(normalizeClassificationName(name), tagList);
                DbTitle dbTitle = new DbTitle(id, name, "", "", tagList);
                comicClassificationTitleDb.put(id, dbTitle);
                indexClassificationTitle(comicClassificationGenreDb, dbTitle);
            }
        } else {
            synchronized (classificationDbLock) {
                classificationDbLoaded = true;
                classificationDbLoadedAt = System.currentTimeMillis();
                classificationDb.put(id, tagList);
                classificationNameDb.put(normalizeClassificationName(name), tagList);
                DbTitle dbTitle = new DbTitle(id, name, "", "", tagList);
                classificationTitleDb.put(id, dbTitle);
                indexClassificationTitle(classificationGenreDb, dbTitle);
            }
        }
    }

    static void clearClassificationDbForTest() {
        synchronized (classificationDbLock) {
            classificationDb.clear();
            classificationNameDb.clear();
            classificationTitleDb.clear();
            classificationGenreDb.clear();
            classificationDbLoaded = false;
            classificationDbLoadedAt = 0;
        }
        synchronized (comicClassificationDbLock) {
            comicClassificationDb.clear();
            comicClassificationNameDb.clear();
            comicClassificationTitleDb.clear();
            comicClassificationGenreDb.clear();
            comicClassificationDbLoaded = false;
            comicClassificationDbLoadedAt = 0;
        }
    }

    static boolean isClassificationDbLoadedForTest(boolean comic) {
        Object lock = comic ? comicClassificationDbLock : classificationDbLock;
        synchronized (lock) {
            return comic ? comicClassificationDbLoaded : classificationDbLoaded;
        }
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

    private static String readCachedClassificationDb(String fileName) {
        if(appContext == null)
            return "";
        File dir = classificationDbCacheDir(appContext);
        if(dir == null)
            return "";
        File file = new File(dir, fileName);
        if(!file.exists() || !file.isFile() || file.length() <= 0)
            return "";
        try(InputStream input = new FileInputStream(file)) {
            return readUtf8(input);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readBundledClassificationDb(String assetName) {
        if(appContext == null)
            return "";
        try(InputStream input = appContext.getAssets().open(assetName)) {
            return readUtf8(input);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while(true) {
            int read = input.read(buffer);
            if(read < 0)
                break;
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
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
        for(String[] genre : NTK_WEBTOON_ING_GENRES)
            if(genre[0].equals(label))
                return true;
        for(String[] genre : NTK_WEBTOON_END_GENRES)
            if(genre[0].equals(label))
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
            if(href == null || key == null || key.length() == 0)
                return "";
            String target = key + "=";
            int queryStart = href.indexOf('?');
            int fragmentStart = href.indexOf('#');
            int start = queryStart >= 0 ? queryStart + 1 : 0;
            int endLimit = fragmentStart >= 0 ? fragmentStart : href.length();
            while(start < endLimit) {
                int end = href.indexOf('&', start);
                if(end < 0 || end > endLimit)
                    end = endLimit;
                if(href.startsWith(target, start))
                    return URLDecoder.decode(href.substring(start + target.length(), end), "UTF-8");
                start = end + 1;
            }
            return "";
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
        return getBlankDataSet(baseMode, false);
    }

    public static List<Ranking<?>> getBlankDataSet(int baseMode, boolean ntk){
        List<Ranking<?>> dataset = new ArrayList<>();
        String[][] sections = getSections(baseMode, ntk);
        for(String[] section : sections)
            dataset.add(new Ranking<>(section[0]));
        return dataset;
    }
}
