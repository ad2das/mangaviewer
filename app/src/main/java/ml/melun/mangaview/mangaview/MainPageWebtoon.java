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
    private static final String[] WEBTOON_GENRES = {"성인", "드라마", "판타지", "액션", "로맨스", "일상", "개그", "미스터리", "순정", "스포츠", "BL", "스릴러", "무협", "학원", "공포", "스토리"};
    private static final String[][] NTK_WEBTOON_ING_GENRES = {
            {"학원", "1"}, {"액션", "2"}, {"SF", "3"}, {"스토리", "4"}, {"판타지", "5"},
            {"BL", "6"}, {"개그", "7"}, {"연애", "8"}, {"드라마", "9"}, {"로맨스", "10"},
            {"시대극", "11"}, {"스포츠", "12"}, {"일상", "13"}, {"추리", "14"}, {"공포", "15"},
            {"성인", "16"}, {"옴니버스", "17"}, {"에피소드", "18"}, {"무협", "19"}, {"소년", "20"},
            {"기타", "99"}
    };
    private static final String[][] NTK_WEBTOON_END_GENRES = {
            {"학원", "1"}, {"액션", "2"}, {"SF", "3"}, {"스토리", "4"}, {"판타지", "5"},
            {"BL", "6"}, {"개그", "7"}, {"연애", "8"}, {"드라마", "9"}, {"로맨스", "10"},
            {"시대극", "11"}, {"스포츠", "12"}, {"일상", "13"}, {"추리", "14"}, {"공포", "15"},
            {"성인", "16"}, {"옴니버스", "17"}, {"에피소드", "18"}, {"무협", "19"}, {"소년", "20"},
            {"기타", "99"}, {"노벨피아", "100"}, {"마법소녀", "101"}, {"유부녀", "102"},
            {"하드코어", "103"}, {"비밀", "104"}, {"현대판타", "105"}, {"인외", "106"},
            {"미시", "107"}, {"감금", "108"}, {"조교", "109"}, {"자매", "110"},
            {"갑을 관", "111"}, {"아포칼립", "112"}, {"사육", "113"}, {"고수위", "114"},
            {"기사", "115"}, {"여동생", "116"}, {"남의여자", "117"}, {"미망인", "118"},
            {"젊줌마", "119"}, {"고향", "120"}, {"오피스", "121"}, {"각성", "122"},
            {"시골", "123"}, {"어린 여", "124"}, {"여사친", "125"}, {"다방레지", "126"},
            {"능욕", "127"}, {"관계역전", "128"}
    };
    private static final String[] ALPHABET_LABELS = {"ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "A-Z", "0-9"};
    private static final String[] ALPHABET_VALUES = {"ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "a", "0"};

    private static final String[] COMIC_DAY_LABELS = {"최신", "주간", "격주", "월간", "단편", "완결", "단행본", "비정기", "미분류"};
    private static final String[] COMIC_DAY_VALUES = {"recent", "10", "11", "12", "14", "16", "15", "13", "20"};
    private static final String[] COMIC_GENRES = {"17", "드라마", "액션", "SF", "TS", "개그", "게임", "공포", "도박", "호러", "라노벨", "러브코미디", "로맨스", "먹방", "미스터리", "백합", "붕탁", "성인", "순정", "스릴러", "스포츠", "시대", "학원", "BL", "여장", "역사", "요리", "음악", "이세계", "일상", "전생", "추리"};
    private static final String[] NTK_COMIC_GENRES = {"순정", "판타지", "러브코미디", "드라마", "17", "학원", "라노벨", "개그", "액션", "백합", "SF", "이세계", "일상", "스릴러", "애니화", "전생", "스포츠", "TS", "소년", "먹방", "붕탁", "게임", "호러", "시대", "로맨스", "추리", "무협", "음악", "BL"};
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
    private static final Map<Integer, List<String>> comicClassificationDb = new LinkedHashMap<>();
    private static final Map<String, List<String>> comicClassificationNameDb = new LinkedHashMap<>();
    private static final Map<Integer, DbTitle> comicClassificationTitleDb = new LinkedHashMap<>();
    private static final Map<String, List<DbTitle>> comicClassificationGenreDb = new LinkedHashMap<>();
    private static boolean comicClassificationDbLoaded = false;
    private static long comicClassificationDbLoadedAt = 0;

    public static final String[][] WEBTOON_FILTER_GROUPS = buildWebtoonFilterGroups();
    public static final String[][] COMIC_FILTER_GROUPS = buildComicFilterGroups();
    public static final String[][] NTK_WEBTOON_FILTER_GROUPS = buildNtkWebtoonFilterGroups();
    public static final String[][] NTK_COMIC_FILTER_GROUPS = buildNtkComicFilterGroups();

    private static final String[][] SECTIONS = buildWebtoonSections();
    private static final String[][] COMIC_SECTIONS = buildComicSections();
    private static final String[][] NTK_SECTIONS = buildNtkWebtoonSections();
    private static final String[][] NTK_COMIC_SECTIONS = buildNtkComicSections();

    List<Ranking<?>> dataSet;

    public static void preloadClassificationDbs() {
        loadClassificationDb();
        loadComicClassificationDb();
    }

    public static synchronized void invalidateClassificationDbs() {
        classificationDbLoaded = false;
        classificationDbLoadedAt = 0;
        comicClassificationDbLoaded = false;
        comicClassificationDbLoadedAt = 0;
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
        return params.size() == 0 ? "/manhwa" : "/manhwa?" + joinQuery(params);
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
        sections.add(section("연재웹툰", "월", "/ing?day=%EC%9B%94"));
        sections.add(section("연재웹툰", "화", "/ing?day=%ED%99%94"));
        sections.add(section("연재웹툰", "수", "/ing?day=%EC%88%98"));
        sections.add(section("연재웹툰", "목", "/ing?day=%EB%AA%A9"));
        sections.add(section("연재웹툰", "금", "/ing?day=%EA%B8%88"));
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
        sections.add(section("정렬", "인기순", "/manhwa?sort=hot"));
        sections.add(section("정렬", "최신", "/manhwa"));
        for(String genre : NTK_COMIC_GENRES)
            sections.add(section("장르별", genre, ntkComicGenrePath(genre)));
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
        groups.add(new String[]{
                filter("연재 요일별", "월", "/ing?day=%EC%9B%94"),
                filter("연재 요일별", "화", "/ing?day=%ED%99%94"),
                filter("연재 요일별", "수", "/ing?day=%EC%88%98"),
                filter("연재 요일별", "목", "/ing?day=%EB%AA%A9"),
                filter("연재 요일별", "금", "/ing?day=%EA%B8%88"),
                filter("연재 요일별", "토", "/ing?day=%ED%86%A0"),
                filter("연재 요일별", "일", "/ing?day=%EC%9D%BC")
        });
        ArrayList<String> ingGenres = new ArrayList<>();
        for(String[] genre : NTK_WEBTOON_ING_GENRES)
            ingGenres.add(filter("연재 장르별", genre[0], ntkWebtoonGenrePath("ing", genre[1])));
        groups.add(ingGenres.toArray(new String[0]));
        ArrayList<String> endGenres = new ArrayList<>();
        for(String[] genre : NTK_WEBTOON_END_GENRES)
            endGenres.add(filter("완결 장르별", genre[0], ntkWebtoonGenrePath("end", genre[1])));
        groups.add(endGenres.toArray(new String[0]));
        return groups.toArray(new String[0][]);
    }

    private static String[][] buildNtkComicFilterGroups() {
        ArrayList<String[]> groups = new ArrayList<>();
        groups.add(new String[]{
                filter("정렬", "인기순", "/manhwa?sort=hot"),
                filter("정렬", "최신순", "/manhwa")
        });
        ArrayList<String> genres = new ArrayList<>();
        for(String genre : NTK_COMIC_GENRES)
            genres.add(filter("장르별", genre, ntkComicGenrePath(genre)));
        groups.add(genres.toArray(new String[0]));
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

    private static String ntkWebtoonGenrePath(String status, String tag) {
        return "/" + status + "?tag=" + percentEncode(tag, StandardCharsets.UTF_8);
    }

    private static String ntkComicGenrePath(String genre) {
        return "/manhwa?g=" + percentEncode(genre, StandardCharsets.UTF_8);
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
        java.util.HashSet<String> seenTitleKeys = new java.util.HashSet<>();
        for(Element e : d.select("div.webtoon-list li, article.searchItem, li:has(a[href*=/webtoon/]), li:has(a[href*=/manhwa/]), article:has(a[href*=/webtoon/]), article:has(a[href*=/manhwa/]), div:has(> a[href*=/webtoon/]), div:has(> a[href*=/manhwa/]), a[href*=toon=], a[href*=/webtoon/], a[href*=/manhwa/]")){
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
                thumb = resolveCoverThumb(name, id, thumb, baseMode);

                Elements infos = context.select("div.txt p");
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
        Element best = link;
        Element current = link == null ? null : link.parent();
        for(int depth = 0; current != null && depth < 5; depth++, current = current.parent()) {
            int titleLinks = current.select("a[href*=/webtoon/], a[href*=/manhwa/], a[href*=toon=]").size();
            boolean hasCardData = current.selectFirst("img, [style*=background-image], [style*=background], .subject, .wr-subject, .post-title, .title, .name, h2, h3, h4, h5, h6") != null;
            if(hasCardData && titleLinks <= 3)
                best = current;
            String tag = current.tagName();
            if("li".equals(tag) || "article".equals(tag))
                break;
        }
        return best == null ? link : best;
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
            DbTitle byId = comicClassificationTitleDb.get(id);
            if(byId != null)
                return byId;
            return findClassificationDbTitleByName(comicClassificationTitleDb, name);
        }
        loadClassificationDb();
        DbTitle byId = classificationTitleDb.get(id);
        if(byId != null)
            return byId;
        return findClassificationDbTitleByName(classificationTitleDb, name);
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

    public static List<Ranking<?>> getFastNtkWebtoonDataSet() {
        if(!classificationDbLoaded)
            return getBlankDataSet(base_webtoon, true);
        List<Ranking<?>> dataset = getBlankDataSet(base_webtoon, true);
        for(Ranking<?> section : dataset) {
            if(section == null)
                continue;
            SectionInfo info = parseSectionInfo(section.getName());
            if(!isWebtoonGenre(info.label))
                continue;
            ArrayList<Title> titles = getClassificationDbTitlesByGenreIfLoaded(info.label, MAIN_SECTION_LIMIT);
            for(Title title : titles)
                ((Ranking<Object>) section).add(title);
        }
        return dataset;
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

    private static ArrayList<Title> getClassificationDbTitles(int limit) {
        ArrayList<Title> result = new ArrayList<>();
        loadClassificationDb();
        for(DbTitle dbTitle : classificationTitleDb.values()) {
            result.add(new Title(dbTitle.name, dbTitle.thumb, "", dbTitle.tags, dbTitle.release, dbTitle.id, base_webtoon));
            if(limit > 0 && result.size() >= limit)
                break;
        }
        return result;
    }

    private static ArrayList<Title> getClassificationDbTitlesIfLoaded(int limit) {
        ArrayList<Title> result = new ArrayList<>();
        if(!classificationDbLoaded)
            return result;
        for(DbTitle dbTitle : classificationTitleDb.values()) {
            result.add(new Title(dbTitle.name, dbTitle.thumb, "", dbTitle.tags, dbTitle.release, dbTitle.id, base_webtoon));
            if(limit > 0 && result.size() >= limit)
                break;
        }
        return result;
    }

    private static ArrayList<Title> getClassificationDbTitlesByGenreIfLoaded(String genre, int limit) {
        ArrayList<Title> result = new ArrayList<>();
        if(!classificationDbLoaded || genre == null)
            return result;
        List<DbTitle> titles = classificationGenreDb.get(normalizeClassificationTag(genre));
        if(titles == null)
            return result;
        for(DbTitle dbTitle : titles) {
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
        try {
            parseClassificationDb(readCachedClassificationDb("webtoon-classification.json"));
            if(classificationDb.size() == 0)
                parseClassificationDb(readBundledClassificationDb());
        } catch (Exception e) {
            classificationDb.clear();
            classificationNameDb.clear();
            classificationTitleDb.clear();
            classificationGenreDb.clear();
            parseClassificationDb(readBundledClassificationDb());
        }
    }

    private static synchronized void loadComicClassificationDb() {
        long now = System.currentTimeMillis();
        if(comicClassificationDbLoaded && now - comicClassificationDbLoadedAt <= CLASSIFICATION_DB_TTL_MS)
            return;
        comicClassificationDbLoaded = true;
        comicClassificationDbLoadedAt = now;
        try {
            parseComicClassificationDb(readCachedClassificationDb("comic-classification.json"));
            if(comicClassificationDb.size() == 0)
                parseComicClassificationDb(readBundledComicClassificationDb());
        } catch (Exception e) {
            comicClassificationDb.clear();
            comicClassificationNameDb.clear();
            comicClassificationTitleDb.clear();
            comicClassificationGenreDb.clear();
            parseComicClassificationDb(readBundledComicClassificationDb());
        }
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
