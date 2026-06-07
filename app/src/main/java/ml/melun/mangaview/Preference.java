package ml.melun.mangaview;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.mangaview.MTitle.baseModeStr;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.Title.isInteger;
import static ml.melun.mangaview.mangaview.CustomHttpClient.DEFAULT_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_WEBTOON_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.WEBTOON_URL;

public class Preference {
    SharedPreferences sharedPref;
    //ArrayList<Title> recent;
    List<MTitle> recent;
    List<MTitle> favorite;
    SharedPreferences.Editor prefsEditor;
    JSONObject pagebookmark;
    JSONObject bookmark;
    String homeDir;
    boolean darkTheme;
    int viewerType;
    boolean reverse;
    boolean pageRtl;
    boolean dataSave;
    int startTab;
    String url;
    String webtoonUrl;
    String wfwfResolvedRoot;
    String ntkResolvedRoot;
    boolean stretch;
    boolean leftRight;
    String defUrl;
    boolean autoUrl;
    float pageControlButtonOffset;
    int prevPageKey, nextPageKey;
    int baseMode;
    boolean doublep;
    boolean doublepReverse;
    FirebaseSyncManager syncManager;
    boolean syncSuppressed;
    boolean historyLoaded;
    boolean bookmarkLoaded;
    boolean viewerBookmarkLoaded;
    private boolean historyIndexDirty = true;
    private final Map<String, Integer> recentIndexByKey = new HashMap<>();
    private final Map<String, Integer> favoriteIndexByKey = new HashMap<>();
    private final Map<String, MTitle> recentByKey = new HashMap<>();
    private final Map<String, MTitle> favoriteByKey = new HashMap<>();
    private final Map<String, Integer> bookmarkValueByKey = new HashMap<>();
    private final Map<String, String> knownSourceByKey = new HashMap<>();
    private volatile long localDataVersion = 0L;
    private final CopyOnWriteArrayList<LocalChangeListener> localChangeListeners = new CopyOnWriteArrayList<>();

    public interface LocalChangeListener {
        void onLocalPreferenceChanged(String scope);
    }

    public SharedPreferences getSharedPref(){
        return this.sharedPref;
    }

    public void setFirebaseSyncManager(FirebaseSyncManager syncManager) {
        this.syncManager = syncManager;
    }

    public void runWithoutSync(Runnable runnable) {
        boolean previous = syncSuppressed;
        syncSuppressed = true;
        try {
            runnable.run();
        } finally {
            syncSuppressed = previous;
        }
    }

    public void addLocalChangeListener(LocalChangeListener listener) {
        if(listener != null && !localChangeListeners.contains(listener))
            localChangeListeners.add(listener);
    }

    public void removeLocalChangeListener(LocalChangeListener listener) {
        if(listener != null)
            localChangeListeners.remove(listener);
    }

    private void notifyLocalChange(String scope) {
        localDataVersion++;
        for(LocalChangeListener listener : localChangeListeners)
            listener.onLocalPreferenceChanged(scope);
    }

    public long getLocalDataVersion() {
        return localDataVersion;
    }

    private void notifySync(String scope) {
        if(syncSuppressed)
            return;
        FirebaseSyncManager manager = syncManager;
        if(manager == null)
            manager = ensureFirebaseSyncManager();
        if(manager != null)
            manager.onLocalPreferencesChanged(scope);
    }

    private FirebaseSyncManager ensureFirebaseSyncManager() {
        if(MainApplication.appContext == null || MainApplication.p != this)
            return null;
        try {
            return MainApplication.getFirebaseSyncManager();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    public void reset(){
        setUrl(defUrl);
        resetFavorites();
        resetRecent();
        resetBookmark();
        resetViewerBookmark();
    }

    public boolean forceWfwfSitePresetIfNeeded() {
        boolean changed = normalizeToWfwfSitePresetIfNeeded();
        if(!changed)
            return false;
        writeSiteSettings();
        notifySync("settings");
        return true;
    }

    //Offline manga has id of -1
    public Preference(Context context){
        init(context);
    }
    public void init(Context mcontext){
        sharedPref = mcontext.getSharedPreferences("mangaView",Context.MODE_PRIVATE);
        prefsEditor = sharedPref.edit();
        recent = new ArrayList<>();
        favorite = new ArrayList<>();
        pagebookmark = new JSONObject();
        bookmark = new JSONObject();
        homeDir = "";
        darkTheme = false;
        viewerType = 0;
        reverse = false;
        pageRtl = false;
        dataSave = false;
        startTab = 0;
        defUrl = DEFAULT_COMIC_URL;
        url = DEFAULT_COMIC_URL;
        webtoonUrl = WEBTOON_URL;
        wfwfResolvedRoot = WEBTOON_URL;
        stretch = false;
        leftRight = false;
        autoUrl = false;
        doublep = false;
        doublepReverse = false;
        pageControlButtonOffset = -1;
        prevPageKey = -1;
        nextPageKey = -1;
        baseMode = base_comic;
        try {
            homeDir = sharedPref.getString("homeDir", "");
            prevPageKey = sharedPref.getInt("prevPageKey", -1);
            nextPageKey = sharedPref.getInt("nextPageKey", -1);
            darkTheme = sharedPref.getBoolean("darkTheme", false);
            viewerType = sharedPref.getInt("viewerType",0);
            reverse = sharedPref.getBoolean("pageReverse",false);
            pageRtl = sharedPref.getBoolean("pageRtl",false);
            dataSave = sharedPref.getBoolean("dataSave", false);
            startTab = sharedPref.getInt("startTab", 0);
            ntkResolvedRoot = normalizeNtkRoot(sharedPref.getString("ntkResolvedRoot", NTK_WEBTOON_URL));
            defUrl = normalizeComicUrl(sharedPref.getString("defUrl", DEFAULT_COMIC_URL), ntkResolvedRoot);
            url = normalizeComicUrl(sharedPref.getString("url", DEFAULT_COMIC_URL), ntkResolvedRoot);
            webtoonUrl = normalizeWebtoonUrl(sharedPref.getString("webtoonUrl", WEBTOON_URL), ntkResolvedRoot);
            migrateStaleNtkPresetIfNeeded();
            wfwfResolvedRoot = normalizeWfwfRoot(sharedPref.getString("wfwfResolvedRoot", webtoonUrl));
            if(wfwfResolvedRoot.length() == 0)
                wfwfResolvedRoot = WEBTOON_URL;
            rememberWfwfRoot(webtoonUrl);
            rememberWfwfRoot(defUrl);
            upgradeLegacyWfwfDefaultUrl();
            stretch = sharedPref.getBoolean("stretch", false);
            leftRight = sharedPref.getBoolean("leftRight", false);
            autoUrl = false;
            doublep = sharedPref.getBoolean("doublep", false);
            doublepReverse = sharedPref.getBoolean("doublepReverse", false);
            pageControlButtonOffset = sharedPref.getFloat("pageControlButtonOffset", -1);
            baseMode = sharedPref.getInt("baseMode", base_comic);
            prefsEditor.putString("defUrl", defUrl)
                    .putString("url", url)
                    .putString("webtoonUrl", webtoonUrl)
                    .putString("wfwfResolvedRoot", wfwfResolvedRoot)
                    .putString("ntkResolvedRoot", ntkResolvedRoot)
                    .putBoolean("autoUrl", false)
                    .remove("login")
                    .remove("notice")
                    .remove("lastNoticeTime")
                    .remove("lastUpdateTime")
                    .apply();
        }catch(Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        migrateSourcePrefKeysIfNeeded();
    }

    private void upgradeLegacyWfwfDefaultUrl() {
        if(isNtkLikeUrl(url) || isNtkLikeUrl(webtoonUrl) || isNtkLikeUrl(defUrl))
            return;
        String oldRoot = "https://wfwf449.com";
        if(oldRoot.equals(trimTrailingSlashLocal(webtoonUrl))) {
            webtoonUrl = WEBTOON_URL;
            url = DEFAULT_COMIC_URL;
            defUrl = DEFAULT_COMIC_URL;
        }
        rememberWfwfRoot(webtoonUrl);
    }

    private static String trimTrailingSlashLocal(String value) {
        if(value == null)
            return "";
        String trimmed = value.trim();
        while(trimmed.endsWith("/"))
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private void migrateSourcePrefKeysIfNeeded() {
        migrateStoredSourcePrefKeysIfNeeded();
        ensureBookmarkLoaded();
        ensureViewerBookmarkLoaded();
    }

    private void migrateStoredSourcePrefKeysIfNeeded() {
        boolean changed = false;
        String storedBookmark = sharedPref.getString("bookmark2", "{}");
        String normalizedBookmark = normalizeSourcePrefJsonString(storedBookmark);
        if(!normalizedBookmark.equals(storedBookmark)) {
            prefsEditor.putString("bookmark2", normalizedBookmark);
            changed = true;
        }
        String storedViewerBookmark = sharedPref.getString("bookmark", "{}");
        String normalizedViewerBookmark = normalizeSourcePrefJsonString(storedViewerBookmark);
        if(!normalizedViewerBookmark.equals(storedViewerBookmark)) {
            prefsEditor.putString("bookmark", normalizedViewerBookmark);
            changed = true;
        }
        if(changed)
            prefsEditor.apply();
    }

    private String normalizeSourcePrefJsonString(String source) {
        if(source == null || source.length() == 0)
            return "{}";
        return source.replaceAll("\"(?:[0-9]+\\.)+(ntk|wfwf)\\.", "\"$1.");
    }

    private void ensureHistoryLoaded() {
        if(historyLoaded)
            return;
        historyLoaded = true;
        try {
            Gson gson = new Gson();
            recent = safeTitleList(gson.fromJson(sharedPref.getString("recent", ""), new TypeToken<ArrayList<MTitle>>(){}.getType()));
            favorite = safeTitleList(gson.fromJson(sharedPref.getString("favorite", ""), new TypeToken<ArrayList<MTitle>>(){}.getType()));
            historyIndexDirty = true;
        } catch(Exception e) {
            recent = new ArrayList<>();
            favorite = new ArrayList<>();
            historyIndexDirty = true;
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private void markHistoryIndexDirty() {
        historyIndexDirty = true;
        knownSourceByKey.clear();
    }

    private void ensureHistoryIndex() {
        ensureHistoryLoaded();
        if(!historyIndexDirty)
            return;
        recentIndexByKey.clear();
        favoriteIndexByKey.clear();
        recentByKey.clear();
        favoriteByKey.clear();
        indexTitles(recent, recentIndexByKey, recentByKey);
        indexTitles(favorite, favoriteIndexByKey, favoriteByKey);
        historyIndexDirty = false;
    }

    private void indexTitles(List<MTitle> source, Map<String, Integer> indexMap, Map<String, MTitle> titleMap) {
        if(source == null)
            return;
        for(int i = 0; i < source.size(); i++) {
            MTitle title = source.get(i);
            if(title == null || title.getId() <= 0)
                continue;
            String legacy = legacyTitleLookupKey(title);
            if(!indexMap.containsKey(legacy)) {
                indexMap.put(legacy, i);
                titleMap.put(legacy, title);
            }
            String sourceKey = sourceTitleLookupKey(title);
            if(sourceKey.length() > 0 && !indexMap.containsKey(sourceKey)) {
                indexMap.put(sourceKey, i);
                titleMap.put(sourceKey, title);
            }
        }
    }

    private String legacyTitleLookupKey(MTitle title) {
        if(title == null)
            return "";
        return title.getBaseMode() + "." + title.getId();
    }

    private String sourceTitleLookupKey(MTitle title) {
        if(title == null)
            return "";
        String source = title.getSourceSite();
        if(source == null || source.length() == 0)
            source = resolveKnownSourceSite(title);
        if(source == null || source.length() == 0)
            return "";
        return source + "." + legacyTitleLookupKey(title);
    }

    private Integer findIndexedPosition(MTitle title, Map<String, Integer> indexMap) {
        if(title == null || title.getId() <= 0)
            return null;
        String sourceKey = sourceTitleLookupKey(title);
        Integer indexed = sourceKey.length() == 0 ? null : indexMap.get(sourceKey);
        if(indexed != null)
            return indexed;
        if(hasExplicitSourceSite(title))
            return null;
        return indexMap.get(legacyTitleLookupKey(title));
    }

    private MTitle findIndexedTitle(MTitle title, Map<String, MTitle> titleMap) {
        if(title == null || title.getId() <= 0)
            return null;
        String sourceKey = sourceTitleLookupKey(title);
        MTitle indexed = sourceKey.length() == 0 ? null : titleMap.get(sourceKey);
        if(indexed != null)
            return indexed;
        if(hasExplicitSourceSite(title))
            return null;
        return titleMap.get(legacyTitleLookupKey(title));
    }

    private boolean hasExplicitSourceSite(MTitle title) {
        if(title == null)
            return false;
        String source = title.getSourceSite();
        return source != null && source.length() > 0;
    }

    private void ensureBookmarkLoaded() {
        if(bookmarkLoaded)
            return;
        bookmarkLoaded = true;
        try {
            bookmark = new JSONObject(sharedPref.getString("bookmark2", "{}"));
            if(normalizeSourcePrefKeys(bookmark))
                writeBookmark();
        } catch(Exception e) {
            bookmark = new JSONObject();
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private void ensureViewerBookmarkLoaded() {
        if(viewerBookmarkLoaded)
            return;
        viewerBookmarkLoaded = true;
        try {
            pagebookmark = new JSONObject(sharedPref.getString("bookmark", "{}"));
            if(normalizeSourcePrefKeys(pagebookmark))
                writeViewerBookmark();
        } catch(Exception e) {
            pagebookmark = new JSONObject();
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private List<MTitle> safeTitleList(List<MTitle> source) {
        List<MTitle> result = new ArrayList<>();
        if(source == null)
            return result;
        for(MTitle title : source) {
            if(title != null && title.getId() > 0) {
                title.setPath(null);
                result.add(title);
            }
        }
        return result;
    }


    public String getBaseModeStr(){
        return baseModeStr(this.baseMode);
    }

    static String normalizeComicUrlForTest(String sourceUrl) {
        return normalizeComicUrl(sourceUrl);
    }

    static String normalizeWebtoonUrlForTest(String sourceUrl) {
        return normalizeWebtoonUrl(sourceUrl);
    }

    static String normalizeComicUrlForTest(String sourceUrl, String ntkRootFallback) {
        return normalizeComicUrl(sourceUrl, ntkRootFallback);
    }

    static String normalizeWebtoonUrlForTest(String sourceUrl, String ntkRootFallback) {
        return normalizeWebtoonUrl(sourceUrl, ntkRootFallback);
    }

    static boolean needsWfwfSitePresetForTest(String defUrl, String url, String webtoonUrl) {
        return needsWfwfSitePreset(defUrl, url, webtoonUrl);
    }

    static String resolvedWfwfRootForTest(String rememberedRoot, String webtoonUrl, String defUrl, String url) {
        return resolvedWfwfRoot(rememberedRoot, webtoonUrl, defUrl, url);
    }

    private static String normalizeComicUrl(String sourceUrl) {
        return normalizeComicUrl(sourceUrl, NTK_WEBTOON_URL);
    }

    private static String normalizeComicUrl(String sourceUrl, String ntkRootFallback) {
        if(sourceUrl == null || sourceUrl.trim().length() == 0)
            return DEFAULT_COMIC_URL;
        String normalized = normalizeHttpUrl(sourceUrl.trim(), DEFAULT_COMIC_URL);
        while(normalized.endsWith("/"))
            normalized = normalized.substring(0, normalized.length() - 1);
        if(isNtkLikeUrl(normalized, ntkRootFallback)) {
            String root = ntkRoot(normalized);
            if(root.length() == 0)
                root = normalizeNtkRoot(ntkRootFallback);
            boolean legacyRoot = isLegacyNtkRedirectRoot(root);
            if(legacyRoot)
                root = normalizeNtkRoot(ntkRootFallback);
            if(legacyRoot)
                return root + "/manhwa";
            if(normalized.endsWith("/cm") || normalized.endsWith("/manhwa") || normalized.equals(root))
                return root + "/manhwa";
        }
        if(normalized.contains("manatoki"))
            return DEFAULT_COMIC_URL;
        if(normalized.equals(WEBTOON_URL))
            return DEFAULT_COMIC_URL;
        if(isWfwfLikeUrl(normalized) && normalized.endsWith("/manhwa"))
            return normalized.substring(0, normalized.length() - 7) + "/cm";
        return normalized;
    }

    private static String normalizeWebtoonUrl(String sourceUrl) {
        return normalizeWebtoonUrl(sourceUrl, NTK_WEBTOON_URL);
    }

    private static String normalizeWebtoonUrl(String sourceUrl, String ntkRootFallback) {
        if(sourceUrl == null || sourceUrl.trim().length() == 0)
            return WEBTOON_URL;
        String normalized = normalizeHttpUrl(sourceUrl.trim(), WEBTOON_URL);
        while(normalized.endsWith("/"))
            normalized = normalized.substring(0, normalized.length() - 1);
        if(isNtkLikeUrl(normalized, ntkRootFallback)) {
            String root = ntkRoot(normalized);
            if(root.length() == 0)
                root = normalizeNtkRoot(ntkRootFallback);
            boolean legacyRoot = isLegacyNtkRedirectRoot(root);
            if(legacyRoot)
                root = normalizeNtkRoot(ntkRootFallback);
            if(legacyRoot)
                return root;
            if(normalized.endsWith("/cm") || normalized.endsWith("/manhwa"))
                return root;
            if(normalized.equals(root))
                return root;
        }
        if(normalized.contains("manatoki"))
            return WEBTOON_URL;
        if(normalized.endsWith("/cm"))
            return normalized.substring(0, normalized.length() - 3);
        if(isWfwfLikeUrl(normalized) && normalized.endsWith("/manhwa"))
            return normalized.substring(0, normalized.length() - 7);
        return normalized;
    }

    private static String normalizeHttpUrl(String sourceUrl, String fallback) {
        try {
            String normalized = sourceUrl;
            if(!normalized.startsWith("http://") && !normalized.startsWith("https://"))
                normalized = "https://" + normalized;
            URI uri = URI.create(normalized);
            if(uri.getHost() == null || uri.getHost().length() == 0)
                return fallback;
            return normalized;
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean normalizeToWfwfSitePresetIfNeeded() {
        if(!needsWfwfSitePreset(defUrl, url, webtoonUrl))
            return false;
        String root = resolvedWfwfRoot(wfwfResolvedRoot, webtoonUrl, defUrl, url);
        wfwfResolvedRoot = root;
        defUrl = root + "/cm";
        url = defUrl;
        webtoonUrl = root;
        ntkResolvedRoot = NTK_WEBTOON_URL;
        autoUrl = false;
        return true;
    }

    private void migrateStaleNtkPresetIfNeeded() {
        if(!isNtkLikeUrl(defUrl) && !isNtkLikeUrl(url) && !isNtkLikeUrl(webtoonUrl))
            return;
        boolean staleActiveUrl = isLegacyNtkRedirectRoot(defUrl)
                || isLegacyNtkRedirectRoot(url)
                || isLegacyNtkRedirectRoot(webtoonUrl);
        if(!staleActiveUrl) {
            if(isLegacyNtkRedirectRoot(ntkResolvedRoot))
                ntkResolvedRoot = normalizeNtkRoot(webtoonUrl);
            return;
        }
        String root = normalizeNtkRoot(NTK_WEBTOON_URL);
        ntkResolvedRoot = root;
        defUrl = root + "/manhwa";
        url = defUrl;
        webtoonUrl = root;
    }

    private void writeSiteSettings() {
        prefsEditor.putString("defUrl", defUrl)
                .putString("url", url)
                .putString("webtoonUrl", webtoonUrl)
                .putString("wfwfResolvedRoot", wfwfResolvedRoot)
                .putString("ntkResolvedRoot", ntkResolvedRoot)
                .putBoolean("autoUrl", autoUrl)
                .apply();
    }

    private static boolean needsWfwfSitePreset(String defUrl, String url, String webtoonUrl) {
        boolean allWfwf = isWfwfLikeUrl(defUrl) && isWfwfLikeUrl(url) && isWfwfLikeUrl(webtoonUrl);
        boolean allNtk = isNtkLikeUrl(defUrl) && isNtkLikeUrl(url) && isNtkLikeUrl(webtoonUrl);
        return !allWfwf && !allNtk;
    }

    private static boolean isWfwfLikeUrl(String sourceUrl) {
        return hostStartsWith(sourceUrl, "wfwf");
    }

    private static boolean isNtkLikeUrl(String sourceUrl) {
        return isNtkLikeUrl(sourceUrl, "");
    }

    private static boolean isNtkLikeUrl(String sourceUrl, String ntkRootFallback) {
        try {
            if(sourceUrl == null || sourceUrl.trim().length() == 0)
                return false;
            String normalized = normalizeHttpUrl(sourceUrl.trim(), "");
            if(normalized.length() == 0)
                return false;
            String host = URI.create(normalized).getHost();
            if(host == null)
                return false;
            host = host.toLowerCase(Locale.ROOT);
            if(host.startsWith("www."))
                host = host.substring(4);
            if(host.startsWith("ntk")
                    || host.startsWith("newto")
                    || host.startsWith("sbxh")
                    || host.contains("newtoki")
                    || host.startsWith("toonflix")
                    || host.endsWith(".toonflix.app")
                    || "sbxh1.com".equals(host)
                    || "www.sbxh1.com".equals(host))
                return true;
            String fallbackHost = ntkHost(ntkRootFallback);
            return fallbackHost.length() > 0
                    && (host.equals(fallbackHost) || host.endsWith("." + fallbackHost));
        } catch (Exception e) {
            return false;
        }
    }

    private static String ntkHost(String sourceUrl) {
        try {
            String normalized = normalizeHttpUrl(sourceUrl == null ? "" : sourceUrl.trim(), "");
            if(normalized.length() == 0)
                return "";
            String host = URI.create(normalized).getHost();
            if(host == null)
                return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalizeNtkRoot(String root) {
        String normalized = ntkRoot(root == null ? "" : root);
        return normalized.length() == 0 ? NTK_WEBTOON_URL : normalized;
    }

    private static String ntkRoot(String sourceUrl) {
        try {
            String normalized = normalizeHttpUrl(sourceUrl.trim(), "");
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String host = uri.getHost();
            if(host == null || host.length() == 0)
                return "";
            return scheme + "://" + host;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean hostStartsWith(String sourceUrl, String prefix) {
        try {
            if(sourceUrl == null || sourceUrl.trim().length() == 0)
                return false;
            String normalized = normalizeHttpUrl(sourceUrl.trim(), "");
            if(normalized.length() == 0)
                return false;
            String host = URI.create(normalized).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).startsWith(prefix);
        } catch (Exception e) {
            return false;
        }
    }

    public int getBaseMode(){
        return this.baseMode;
    }

    public void setBaseMode(int baseMode){
        this.baseMode = baseMode;
        prefsEditor.putInt("baseMode", baseMode);
        prefsEditor.apply();
        notifySync("settings");
    }

    public void setDefUrl(String defUrl){
        this.defUrl = normalizeComicUrl(defUrl);
        prefsEditor.putString("defUrl", this.defUrl);
        prefsEditor.apply();
        notifySync("settings");
    }

    public String getDefUrl() {
        return defUrl;
    }

    public boolean getLeftRight() {
        return leftRight;
    }

    public void setLeftRight(boolean leftRight) {
        this.leftRight = leftRight;
        prefsEditor.putBoolean("leftRight", leftRight);
        prefsEditor.apply();
        notifySync("settings");
    }

    public int getViewerType() {
        return viewerType;
    }

    public void setViewerType(int viewerType) {
        this.viewerType = viewerType;
        prefsEditor.putInt("viewerType", viewerType);
        prefsEditor.apply();
        notifySync("settings");
    }

    public boolean getStretch() {
        return stretch;
    }

    public void setStretch(boolean stretch) {
        this.stretch = stretch;
        prefsEditor.putBoolean("stretch", stretch);
        prefsEditor.apply();
        notifySync("settings");
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = normalizeComicUrl(url, ntkResolvedRoot);
        rememberWfwfRoot(this.url);
        prefsEditor.putString("url", this.url);
        prefsEditor.putString("wfwfResolvedRoot", wfwfResolvedRoot);
        prefsEditor.apply();
        MainApplication.refreshWebViewDebuggingPolicy(this);
        notifySync("settings");
    }

    public String getWebtoonUrl() {
        return webtoonUrl;
    }

    public String getWfwfResolvedRoot() {
        return resolvedWfwfRoot(wfwfResolvedRoot, webtoonUrl, defUrl, url);
    }

    public String getNtkResolvedRoot() {
        String root = normalizeNtkRoot(ntkResolvedRoot);
        return root.length() == 0 ? NTK_WEBTOON_URL : root;
    }

    public void setWebtoonUrl(String webtoonUrl) {
        this.webtoonUrl = normalizeWebtoonUrl(webtoonUrl, ntkResolvedRoot);
        rememberWfwfRoot(this.webtoonUrl);
        prefsEditor.putString("webtoonUrl", this.webtoonUrl);
        prefsEditor.putString("wfwfResolvedRoot", wfwfResolvedRoot);
        prefsEditor.apply();
        MainApplication.refreshWebViewDebuggingPolicy(this);
        notifySync("settings");
    }

    public void setSitePreset(String comicUrl, String webtoonUrl) {
        if(isNtkLikeUrl(comicUrl) || isNtkLikeUrl(webtoonUrl)) {
            String root = ntkRoot(webtoonUrl);
            if(root.length() == 0)
                root = ntkRoot(comicUrl);
            String defaultRoot = ntkRoot(NTK_WEBTOON_URL);
            setNtkSitePreset(root);
            return;
        }
        if(isDefaultWfwfPreset(comicUrl, webtoonUrl)) {
            String root = wfwfResolvedRoot == null || wfwfResolvedRoot.length() == 0 ? WEBTOON_URL : wfwfResolvedRoot;
            comicUrl = root + "/cm";
            webtoonUrl = root;
        }
        this.defUrl = normalizeComicUrl(comicUrl, ntkResolvedRoot);
        this.url = this.defUrl;
        this.webtoonUrl = normalizeWebtoonUrl(webtoonUrl, ntkResolvedRoot);
        rememberWfwfRoot(this.webtoonUrl);
        prefsEditor.putString("defUrl", this.defUrl)
                .putString("url", this.url)
                .putString("webtoonUrl", this.webtoonUrl)
                .putString("wfwfResolvedRoot", wfwfResolvedRoot)
                .putBoolean("autoUrl", false)
                .apply();
        autoUrl = false;
        MainApplication.refreshWebViewDebuggingPolicy(this);
        notifySync("settings");
    }

    public void setNtkSitePreset(String rootUrl) {
        String root = normalizeNtkRoot(rootUrl);
        String defaultRoot = ntkRoot(NTK_WEBTOON_URL);
        if(isLegacyNtkRedirectRoot(root))
            root = defaultRoot;
        ntkResolvedRoot = root;
        defUrl = root + "/manhwa";
        url = defUrl;
        webtoonUrl = root;
        autoUrl = false;
        prefsEditor.putString("ntkResolvedRoot", ntkResolvedRoot)
                .putString("defUrl", defUrl)
                .putString("url", url)
                .putString("webtoonUrl", webtoonUrl)
                .putBoolean("autoUrl", false)
                .apply();
        MainApplication.refreshWebViewDebuggingPolicy(this);
        notifySync("settings");
    }

    private static boolean isLegacyNtkRedirectRoot(String root) {
        try {
            String host = URI.create(normalizeHttpUrl(root, "")).getHost();
            if(host == null)
                return false;
            host = host.toLowerCase(Locale.ROOT);
            if(host.startsWith("www."))
                host = host.substring(4);
            return "ntk01.com".equals(host) || "sbxh1.com".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isNtkSite() {
        return isNtkLikeUrl(url, ntkResolvedRoot)
                || isNtkLikeUrl(webtoonUrl, ntkResolvedRoot)
                || isNtkLikeUrl(defUrl, ntkResolvedRoot);
    }

    private boolean isDefaultWfwfPreset(String comicUrl, String webtoonUrl) {
        return DEFAULT_COMIC_URL.equals(trimTrailingSlashLocal(comicUrl))
                && WEBTOON_URL.equals(trimTrailingSlashLocal(webtoonUrl));
    }

    private void rememberWfwfRoot(String candidateUrl) {
        String root = normalizeWfwfRoot(candidateUrl);
        if(root.length() == 0)
            return;
        wfwfResolvedRoot = root;
    }

    private static String normalizeWfwfRoot(String candidateUrl) {
        String root = trimTrailingSlashLocal(candidateUrl);
        if(root.length() == 0 || isNtkLikeUrl(root) || !isWfwfLikeUrl(root))
            return "";
        if(root.endsWith("/cm"))
            root = root.substring(0, root.length() - 3);
        if(root.endsWith("/manhwa"))
            root = root.substring(0, root.length() - 7);
        return root;
    }

    private static String resolvedWfwfRoot(String rememberedRoot, String webtoonUrl, String defUrl, String url) {
        String root = normalizeWfwfRoot(rememberedRoot);
        if(root.length() > 0)
            return root;
        root = normalizeWfwfRoot(webtoonUrl);
        if(root.length() > 0)
            return root;
        root = normalizeWfwfRoot(defUrl);
        if(root.length() > 0)
            return root;
        root = normalizeWfwfRoot(url);
        return root.length() == 0 ? WEBTOON_URL : root;
    }

    public int getStartTab() {
        return startTab;
    }

    public void setStartTab(int startTab) {
        this.startTab = startTab;
        prefsEditor.putInt("startTab", startTab);
        prefsEditor.apply();
        notifySync("settings");
    }

    public boolean getDataSave() {
        return dataSave;
    }

    public void setDataSave(boolean dataSave) {
        this.dataSave = dataSave;
        prefsEditor.putBoolean("dataSave", dataSave);
        prefsEditor.apply();
        notifySync("settings");
    }

    public boolean getReverse() {
        return reverse;
    }

    public void setReverse(boolean reverse) {
        this.reverse = reverse;
        prefsEditor.putBoolean("pageReverse", reverse);
        prefsEditor.apply();
        notifySync("settings");
    }

    public boolean getPageRtl() {
        return pageRtl;
    }

    public void setPageRtl(boolean pageRtl) {
        this.pageRtl = pageRtl;
        prefsEditor.putBoolean("pageRtl", pageRtl);
        prefsEditor.apply();
        notifySync("settings");
    }


    public boolean getDarkTheme() {
        return darkTheme;
    }

    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
        prefsEditor.putBoolean("darkTheme", darkTheme);
        prefsEditor.apply();
        notifySync("settings");
    }



    public String getHomeDir() {
        return homeDir;
    }

    public void setHomeDir(String homeDir) {
        this.homeDir = homeDir == null ? "" : homeDir;
        prefsEditor.putString("homeDir", this.homeDir);
        prefsEditor.apply();
    }
    public void removeRecent(int position){
        ensureHistoryLoaded();
        if(position < 0 || position >= recent.size())
            return;
        MTitle title = recent.remove(position);
        writeRecent();
        removeBookmark(title);
        writeBookmark();
    }

    public void removeRecent(MTitle title){
        ensureHistoryLoaded();
        ensureBookmarkLoaded();
        int position = getIndexOf(title);
        if(position < 0)
            return;
        MTitle removed = recent.remove(position);
        writeRecent();
        removeBookmark(removed);
        writeBookmark();
    }

    public void addRecent(MTitle tmp){
        ensureHistoryLoaded();
        if(tmp != null && tmp.getId()>0) {
            ensureSourceSite(tmp);
            tmp.setPath(null);
            int position = getIndexOf(tmp);
            if (position > -1) {
                MTitle existing = recent.remove(position);
                preserveMoreCompleteProgress(tmp, existing);
                recent.add(0, tmp);
            } else recent.add(0, tmp);
            writeRecent();
        }
    }
    public void addRecent(Title tmp){
        ensureHistoryLoaded();
        if(tmp != null && tmp.getId()>0) {
            MTitle title = tmp.minimize();
            ensureSourceSite(title);
            title.setPath(null);
            normalizeNtkProgressFromRelease(title);
            int position = getIndexOf(title);
            if (position > -1) {
                MTitle existing = recent.remove(position);
                preserveMoreCompleteProgress(title, existing);
                normalizeNtkProgressFromRelease(title);
                recent.add(0, title);
            } else recent.add(0, title);
            writeRecent();
        }
    }

    private static void preserveMoreCompleteProgress(MTitle target, MTitle existing) {
        if(target == null || existing == null)
            return;
        int existingCount = existing.getEpisodeCount();
        int targetCount = target.getEpisodeCount();
        boolean targetHasCompleteProgress = target.getBookmarkEpisodeIndex() > 0
                && targetCount > 0
                && (existingCount <= 0 || targetCount >= existingCount);
        if(targetHasCompleteProgress)
            return;
        int episodeId = target.getBookmarkEpisodeId() > 0
                ? target.getBookmarkEpisodeId()
                : existing.getBookmarkEpisodeId();
        int episodeIndex = existing.getBookmarkEpisodeId() == episodeId
                ? existing.getBookmarkEpisodeIndex()
                : -1;
        target.setReadingProgress(episodeId, episodeIndex, Math.max(existingCount, targetCount));
    }

    static MTitle preserveMoreCompleteProgressForTest(MTitle target, MTitle existing) {
        preserveMoreCompleteProgress(target, existing);
        return target;
    }


    public void updateRecentData(MTitle title){
        ensureHistoryLoaded();
        if(title == null)
            return;
        MTitle tmp = title.clone();
        ensureSourceSite(tmp);
        tmp.setPath(null);
        normalizeNtkProgressFromRelease(tmp);
        int recentIndex = getIndexOf(tmp);
        if(recentIndex > -1) {
            preserveMoreCompleteProgress(tmp, recent.get(recentIndex));
            normalizeNtkProgressFromRelease(tmp);
            recent.set(recentIndex, tmp);
            writeRecent();
        }
        int index = findFavorite(tmp);
        if(index>-1){
            preserveMoreCompleteProgress(tmp, favorite.get(index));
            normalizeNtkProgressFromRelease(tmp);
            favorite.set(index,tmp);
            markHistoryIndexDirty();
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
        }
    }

    public void updateRecentData(Title title){
        ensureHistoryLoaded();
        if(title == null)
            return;
        MTitle tmp = title.minimize();
        ensureSourceSite(tmp);
        tmp.setPath(null);
        normalizeNtkProgressFromRelease(tmp);
        int recentIndex = getIndexOf(tmp);
        if(recentIndex > -1) {
            preserveMoreCompleteProgress(tmp, recent.get(recentIndex));
            normalizeNtkProgressFromRelease(tmp);
            recent.set(recentIndex, tmp);
            writeRecent();
        }
        int index = findFavorite(tmp);
        if(index>-1){
            preserveMoreCompleteProgress(tmp, favorite.get(index));
            normalizeNtkProgressFromRelease(tmp);
            favorite.set(index, tmp);
            markHistoryIndexDirty();
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
        }
    }

    private int getIndexOf(MTitle title){
        ensureHistoryIndex();
        Integer index = findIndexedPosition(title, recentIndexByKey);
        return index == null ? -1 : index;
    }

    private boolean sameTitleRecord(MTitle left, MTitle right) {
        if(left == null || right == null)
            return false;
        if(left.getBaseMode() != right.getBaseMode() || left.getId() != right.getId())
            return false;
        String leftSource = left.getSourceSite();
        String rightSource = right.getSourceSite();
        return leftSource.length() == 0 || rightSource.length() == 0 || leftSource.equals(rightSource);
    }

    public void setBookmark(Title title, int id){
        ensureBookmarkLoaded();
        if(title == null)
            return;
        int titleId = title.getId();
        if(titleId>0) {
            ensureSourceSite(title);
            String key = bookmarkKey(title);
            try {
                if(bookmark.has(key) && bookmark.getInt(key) == id) {
                    updateRecentProgress(title, id);
                    return;
                }
                bookmark.put(key, id);
            } catch (Exception e) {
                //
            }
            updateRecentProgress(title, id);
            writeBookmark();
        }
    }

    private void updateRecentProgress(Title title, int episodeId) {
        ensureHistoryLoaded();
        if(title == null || episodeId <= 0)
            return;
        int index = getIndexOf(title);
        if(index < 0)
            return;
        int episodeIndex = -1;
        MTitle recentTitle = recent.get(index);
        int existingCount = recentTitle.getEpisodeCount();
        int incomingCount = title.getEpsCount();
        boolean incomingHasCompleteList = incomingCount > 0
                && (existingCount <= 0 || incomingCount >= existingCount);
        List<Manga> episodes = Utils.snapshotEpisodes(title);
        if(incomingHasCompleteList && episodes.size() > 0) {
            for(int i = 0; i < episodes.size(); i++) {
                if(episodes.get(i) != null && episodes.get(i).getId() == episodeId) {
                    episodeIndex = i + 1;
                    break;
                }
            }
        }
        int episodeCount = incomingHasCompleteList ? incomingCount : existingCount;
        if(episodeCount <= 0)
            episodeCount = title.getEpisodeCount();
        if(episodeIndex <= 0 && recentTitle.getBookmarkEpisodeId() == episodeId)
            episodeIndex = recentTitle.getBookmarkEpisodeIndex();
        if(episodeIndex <= 0)
            episodeIndex = inferEpisodeIndexFromEpisodeId(title, episodeId, episodeCount);
        recentTitle.setReadingProgress(episodeId, episodeIndex, episodeCount);
        String resumePath = title.getResumeNtkEpisodePath();
        if(resumePath.length() > 0)
            recentTitle.setResumeNtkEpisodePath(resumePath);
        normalizeNtkProgressFromRelease(recentTitle);
        writeRecent();
    }

    private static int inferEpisodeIndexFromEpisodeId(MTitle title, int episodeId, int episodeCount) {
        if(title == null || episodeId <= 0 || episodeCount <= 0)
            return -1;
        String source = canonicalSourceSiteForProgress(title.getSourceSite());
        if("ntk".equals(source))
            return -1;
        if(episodeId > episodeCount)
            return -1;
        return episodeCount - episodeId + 1;
    }

    private static String canonicalSourceSiteForProgress(String source) {
        if(source == null)
            return "";
        String lower = source.trim().toLowerCase(Locale.ROOT);
        if(lower.length() == 0)
            return "";
        if(lower.contains("ntk") || lower.contains("sbxh") || lower.contains("toonflix"))
            return "ntk";
        if(lower.contains("wfwf") || lower.contains("wolf") || lower.contains("vcloud")
                || lower.contains("v12st") || lower.contains("ao9cloud"))
            return "wfwf";
        return "";
    }

    static int inferEpisodeIndexFromEpisodeIdForTest(MTitle title, int episodeId, int episodeCount) {
        return inferEpisodeIndexFromEpisodeId(title, episodeId, episodeCount);
    }

    private static void normalizeNtkProgressFromRelease(MTitle title) {
        if(title == null)
            return;
        if(title.getBaseMode() == MTitle.base_webtoon)
            return;
        int releaseCount = title.getNtkReleaseEpisodeCount();
        if(releaseCount <= 0 || title.getEpisodeCount() <= releaseCount)
            return;
        int episodeId = title.getBookmarkEpisodeId();
        int episodeIndex = title.getBookmarkEpisodeIndex();
        if(episodeId > 0 && episodeId <= releaseCount)
            episodeIndex = releaseCount - episodeId + 1;
        else if(episodeIndex > releaseCount)
            episodeIndex = releaseCount;
        title.setReadingProgress(episodeId, episodeIndex, releaseCount);
    }

    static int normalizedNtkEpisodeCountForTest(MTitle title) {
        normalizeNtkProgressFromRelease(title);
        return title == null ? 0 : title.getEpisodeCount();
    }
    public int getBookmark(MTitle title){
        ensureBookmarkLoaded();
        //return recent.mget(0).getBookmark();
        if(title == null)
            return -1;
        int titleId = title.getId();
        if(titleId>0) {
            try {
                String sourceKey = bookmarkKey(title);
                Integer cached = bookmarkValueByKey.get(sourceKey);
                if(cached != null)
                    return cached;
                if(bookmark.has(sourceKey))
                    return cacheBookmarkValue(sourceKey, bookmark.getInt(sourceKey));
                Integer normalized = intFromNormalizedSourcePref(bookmark, sourceKey);
                if(normalized != null)
                    return cacheBookmarkValue(sourceKey, normalized);
                String legacyKey = legacyBookmarkKey(title);
                cached = bookmarkValueByKey.get(legacyKey);
                if(cached != null)
                    return cached;
                return cacheBookmarkValue(legacyKey, bookmark.getInt(legacyKey));
            } catch (Exception e) {
                //
            }
        }
        return -1;
    }

    private int cacheBookmarkValue(String key, int value) {
        if(key != null && key.length() > 0)
            bookmarkValueByKey.put(key, value);
        return value;
    }

    private void ensureSourceSite(MTitle title) {
        if(title == null)
            return;
        String canonical = canonicalSourceSite(title.getSourceSite());
        if(canonical.length() > 0) {
            if(!canonical.equals(title.getSourceSite()))
                title.setSourceSite(canonical);
            return;
        }
        title.setSourceSite(resolveSourceSite(title));
    }

    public void ensureSourceSiteForTitle(MTitle title) {
        ensureSourceSite(title);
    }

    public String resolveSourceSite(MTitle title) {
        String knownSource = resolveKnownSourceSite(title);
        if(knownSource.length() > 0)
            return knownSource;
        return isNtkSite() ? "ntk" : "wfwf";
    }

    public String resolveKnownSourceSite(MTitle title) {
        String source = canonicalSourceSite(title == null ? "" : title.getSourceSite());
        if(source.length() > 0)
            return source;
        if(title != null && title.getId() > 0) {
            String sourceCacheKey = legacyTitleLookupKey(title);
            String cachedSource = knownSourceByKey.get(sourceCacheKey);
            if(cachedSource != null)
                return cachedSource;
            ensureBookmarkLoaded();
            String legacy = legacyBookmarkKey(title);
            if(hasSourceBookmark("wfwf", legacy))
                return cacheKnownSource(sourceCacheKey, "wfwf");
            if(hasSourceBookmark("ntk", legacy))
                return cacheKnownSource(sourceCacheKey, "ntk");
        }
        source = sourceSiteFromUrl(title == null ? "" : title.getThumb());
        if(source.length() > 0)
            return title != null && title.getId() > 0 ? cacheKnownSource(legacyTitleLookupKey(title), source) : source;
        source = sourceSiteFromUrl(title == null ? "" : title.getPath());
        return title != null && title.getId() > 0 ? cacheKnownSource(legacyTitleLookupKey(title), source) : source;
    }

    private boolean hasSourceBookmark(String source, String legacy) {
        if(source == null || source.length() == 0 || legacy == null || legacy.length() == 0)
            return false;
        String exact = source + "." + legacy;
        if(bookmark.has(exact))
            return true;
        String nested = "." + exact;
        Iterator<String> keys = bookmark.keys();
        while(keys.hasNext()) {
            String key = keys.next();
            if(key != null && key.endsWith(nested))
                return true;
        }
        return false;
    }

    private String cacheKnownSource(String key, String source) {
        source = canonicalSourceSite(source);
        if(key != null && key.length() > 0)
            knownSourceByKey.put(key, source == null ? "" : source);
        return source == null ? "" : source;
    }

    private String canonicalSourceSite(String source) {
        if(source == null)
            return "";
        String lower = source.trim().toLowerCase(Locale.ROOT);
        if(lower.length() == 0)
            return "";
        if(lower.contains("ntk") || lower.contains("sbxh") || lower.contains("toonflix"))
            return "ntk";
        if(lower.contains("wfwf") || lower.contains("wolf") || lower.contains("vcloud")
                || lower.contains("v12st") || lower.contains("ao9cloud"))
            return "wfwf";
        return "";
    }

    private String sourceSiteFromUrl(String sourceUrl) {
        if(isNtkLikeUrl(sourceUrl, ntkResolvedRoot))
            return "ntk";
        if(isWfwfLikeUrl(sourceUrl))
            return "wfwf";
        return "";
    }

    private String bookmarkKey(MTitle title) {
        String legacy = legacyBookmarkKey(title);
        String source = canonicalSourceSite(title == null ? "" : title.getSourceSite());
        if(source == null || source.length() == 0)
            return legacy;
        return source + "." + legacy;
    }

    private String legacyBookmarkKey(MTitle title) {
        return title.getBaseMode() + "." + title.getId();
    }

    private void removeBookmark(MTitle title){
        ensureBookmarkLoaded();
        if(title == null)
            return;
        int titleId = title.getId();
        if(titleId>0) {
            try {
                String key = bookmarkKey(title);
                String legacyKey = legacyBookmarkKey(title);
                if(!bookmark.has(key) && !bookmark.has(legacyKey))
                    return;
                bookmark.remove(key);
                bookmark.remove(legacyKey);
            } catch (Exception e) {
                //
            }
            writeBookmark();
        }
    }

    public void writeBookmark(){
        ensureBookmarkLoaded();
        normalizeSourcePrefKeys(bookmark);
        bookmarkValueByKey.clear();
        knownSourceByKey.clear();
        prefsEditor.putString("bookmark2", bookmark.toString());
        prefsEditor.apply();
        notifyLocalChange("bookmark");
        notifySync("bookmark");
    }

    public void resetBookmark(){
        bookmarkLoaded = true;
        try {
            bookmark = new JSONObject("{}");
        }catch (Exception e){}
        writeBookmark();
    }
    public void resetRecent(){
        historyLoaded = true;
        recent = new ArrayList<>();
        markHistoryIndexDirty();
        writeRecent();
    }

    public void resetFavorites(){
        ensureHistoryLoaded();
        favorite = new ArrayList<>();
        markHistoryIndexDirty();
        prefsEditor.putString("favorite", new Gson().toJson(favorite));
        prefsEditor.apply();
        notifySync("favorite");

    }

    private void writeRecent(){
        ensureHistoryLoaded();
        markHistoryIndexDirty();
        Gson gson = new Gson();
        prefsEditor.putString("recent", gson.toJson(recent));
        prefsEditor.apply();
        notifyLocalChange("recent");
        notifySync("recent");
    }


    public void setViewerBookmark(Manga m,int index){
        setViewerBookmark(m, index, 0);
    }

    public void setViewerBookmark(Manga m, int index, int offset){
        setViewerBookmark(m, index, offset, 0);
    }

    public void setViewerBookmark(Manga m, int index, int offset, int side){
        ensureViewerBookmarkLoaded();
        if(m == null)
            return;
        if(m.getId()>-1) {
            if(index <= 0 && offset == 0 && side == 0) {
                removeViewerBookmark(m);
                return;
            }
            if (index > 0 || offset != 0 || side != 0) {
                String key = viewerBookmarkKey(m);
                String offsetKey = viewerBookmarkOffsetKey(m);
                String sideKey = viewerBookmarkSideKey(m);
                try {
                    int existingIndex = pagebookmark.has(key) ? pagebookmark.getInt(key) : 0;
                    int existingOffset = pagebookmark.has(offsetKey) ? pagebookmark.getInt(offsetKey) : 0;
                    int existingSide = pagebookmark.has(sideKey) ? pagebookmark.getInt(sideKey) : 0;
                    if(existingIndex == index && existingOffset == offset && existingSide == side)
                        return;
                    pagebookmark.put(key, index);
                    pagebookmark.put(offsetKey, offset);
                    if(side == 0)
                        pagebookmark.remove(sideKey);
                    else
                        pagebookmark.put(sideKey, side);
                } catch (Exception e) {
                    //
                }
                writeViewerBookmark();
            }
        }
    }
    public int getViewerBookmark(Manga m){
        ensureViewerBookmarkLoaded();
        if(m == null)
            return 0;
        if(m.getId()>-1) {
            try {
                String key = viewerBookmarkKey(m);
                Integer normalized = intFromNormalizedSourcePref(pagebookmark, key);
                return normalized != null ? normalized : pagebookmark.getInt(key);
            } catch (Exception e) {
                //
            }
            try {
                return pagebookmark.getInt(legacyViewerBookmarkKeyWithTitle(m));
            } catch (Exception e) {
                //
            }
            try {
                String legacyKey = legacyViewerBookmarkKey(m);
                if(legacyKey != null)
                    return pagebookmark.getInt(legacyKey);
            } catch (Exception e) {
                //
            }
        }
        return 0;
    }
    public int getViewerBookmarkOffset(Manga m){
        ensureViewerBookmarkLoaded();
        if(m == null)
            return 0;
        if(m.getId()>-1) {
            try {
                String key = viewerBookmarkOffsetKey(m);
                Integer normalized = intFromNormalizedSourcePref(pagebookmark, key);
                return normalized != null ? normalized : pagebookmark.getInt(key);
            } catch (Exception e) {
                //
            }
            try {
                return pagebookmark.getInt(legacyViewerBookmarkKeyWithTitle(m) + ".offset");
            } catch (Exception e) {
                //
            }
            try {
                String legacyKey = legacyViewerBookmarkKey(m);
                if(legacyKey != null)
                    return pagebookmark.getInt(legacyKey + ".offset");
            } catch (Exception e) {
                //
            }
        }
        return 0;
    }
    public int getViewerBookmarkSide(Manga m){
        ensureViewerBookmarkLoaded();
        if(m == null)
            return 0;
        if(m.getId()>-1) {
            try {
                String key = viewerBookmarkSideKey(m);
                Integer normalized = intFromNormalizedSourcePref(pagebookmark, key);
                return normalized != null ? normalized : pagebookmark.getInt(key);
            } catch (Exception e) {
                //
            }
            try {
                return pagebookmark.getInt(legacyViewerBookmarkKeyWithTitle(m) + ".side");
            } catch (Exception e) {
                //
            }
            try {
                String legacyKey = legacyViewerBookmarkKey(m);
                if(legacyKey != null)
                    return pagebookmark.getInt(legacyKey + ".side");
            } catch (Exception e) {
                //
            }
        }
        return 0;
    }
    public void removeViewerBookmark(Manga m){
        ensureViewerBookmarkLoaded();
        if(m == null)
            return;
        String key = viewerBookmarkKey(m);
        String offsetKey = viewerBookmarkOffsetKey(m);
        String sideKey = viewerBookmarkSideKey(m);
        String legacyWithTitle = legacyViewerBookmarkKeyWithTitle(m);
        String legacyWithTitleOffset = legacyWithTitle + ".offset";
        String legacyWithTitleSide = legacyWithTitle + ".side";
        String legacy = legacyViewerBookmarkKey(m);
        String legacyOffset = legacy == null ? null : legacy + ".offset";
        String legacySide = legacy == null ? null : legacy + ".side";
        if(!pagebookmark.has(key) && !pagebookmark.has(offsetKey)
                && !pagebookmark.has(sideKey)
                && !pagebookmark.has(legacyWithTitle) && !pagebookmark.has(legacyWithTitleOffset)
                && !pagebookmark.has(legacyWithTitleSide)
                && (legacy == null || (!pagebookmark.has(legacy) && !pagebookmark.has(legacyOffset)
                && !pagebookmark.has(legacySide))))
            return;
        pagebookmark.remove(key);
        pagebookmark.remove(offsetKey);
        pagebookmark.remove(sideKey);
        pagebookmark.remove(legacyWithTitle);
        pagebookmark.remove(legacyWithTitleOffset);
        pagebookmark.remove(legacyWithTitleSide);
        if(legacy != null) {
            pagebookmark.remove(legacy);
            pagebookmark.remove(legacyOffset);
            pagebookmark.remove(legacySide);
        }
        writeViewerBookmark();
    }

    private String viewerBookmarkKey(Manga m) {
        String legacy = legacyViewerBookmarkKeyWithTitle(m);
        String source = mangaSourceSite(m);
        if(source == null || source.length() == 0)
            return legacy;
        return source + "." + legacy;
    }

    private String viewerBookmarkOffsetKey(Manga m) {
        return viewerBookmarkKey(m) + ".offset";
    }

    private String viewerBookmarkSideKey(Manga m) {
        return viewerBookmarkKey(m) + ".side";
    }

    private String legacyViewerBookmarkKeyWithTitle(Manga m) {
        int titleId = m.getTitleId();
        if(titleId > 0)
            return m.getBaseMode() + "." + titleId + "." + m.getId();
        return m.getBaseMode() + "." + m.getId();
    }

    private String mangaSourceSite(Manga m) {
        if(m == null || m.getTitle() == null)
            return "";
        String source = canonicalSourceSite(m.getTitle().getSourceSite());
        if(source != null && source.length() > 0)
            return source;
        return isNtkSite() ? "ntk" : "wfwf";
    }

    private boolean normalizeSourcePrefKeys(JSONObject object) {
        if(object == null)
            return false;
        boolean changed = false;
        JSONObject normalized = new JSONObject();
        try {
            Iterator<String> keys = object.keys();
            while(keys.hasNext()) {
                String key = keys.next();
                String normalizedKey = normalizeSourcePrefKey(key);
                if(!normalizedKey.equals(key))
                    changed = true;
                normalized.put(normalizedKey, object.get(key));
            }
            if(changed) {
                Iterator<String> existing = object.keys();
                ArrayList<String> removeKeys = new ArrayList<>();
                while(existing.hasNext())
                    removeKeys.add(existing.next());
                for(String key : removeKeys)
                    object.remove(key);
                Iterator<String> normalizedKeys = normalized.keys();
                while(normalizedKeys.hasNext()) {
                    String key = normalizedKeys.next();
                    object.put(key, normalized.get(key));
                }
            }
        } catch(Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
        return changed;
    }

    private String normalizeSourcePrefKey(String key) {
        if(key == null)
            return "";
        int ntk = key.indexOf("ntk.");
        int wfwf = key.indexOf("wfwf.");
        int index;
        if(ntk >= 0 && (wfwf < 0 || ntk < wfwf))
            index = ntk;
        else
            index = wfwf;
        return index > 0 ? key.substring(index) : key;
    }

    private Integer intFromNormalizedSourcePref(JSONObject object, String normalizedKey) {
        if(object == null || normalizedKey == null || normalizedKey.length() == 0)
            return null;
        Integer found = null;
        try {
            if(object.has(normalizedKey))
                found = object.getInt(normalizedKey);
            Iterator<String> keys = object.keys();
            while(keys.hasNext()) {
                String key = keys.next();
                if(key != null && !normalizedKey.equals(key)
                        && sourceAwarePrefKeyMatches(key, normalizedKey))
                    found = object.getInt(key);
            }
        } catch(Exception e) {
            return found;
        }
        return found;
    }

    private boolean sourceAwarePrefKeyMatches(String storedKey, String requestedKey) {
        String stored = normalizeSourcePrefKey(storedKey);
        String requested = normalizeSourcePrefKey(requestedKey);
        if(stored.equals(requested))
            return true;
        String storedSource = leadingSourcePrefix(stored);
        String requestedSource = leadingSourcePrefix(requested);
        if(storedSource.length() > 0 && requestedSource.length() > 0 && !storedSource.equals(requestedSource))
            return false;
        return stripLeadingSourcePrefix(stored).equals(stripLeadingSourcePrefix(requested));
    }

    private String leadingSourcePrefix(String key) {
        if(key == null)
            return "";
        if(key.startsWith("ntk."))
            return "ntk";
        if(key.startsWith("wfwf."))
            return "wfwf";
        return "";
    }

    private String stripLeadingSourcePrefix(String key) {
        if(key == null)
            return "";
        if(key.startsWith("ntk."))
            return key.substring(4);
        if(key.startsWith("wfwf."))
            return key.substring(5);
        return key;
    }

    private String legacyViewerBookmarkKey(Manga m) {
        if(m == null || m.getTitleId() <= 0)
            return null;
        return m.getBaseMode() + "." + m.getId();
    }
    public void resetViewerBookmark(){
        viewerBookmarkLoaded = true;
        try {
            pagebookmark = new JSONObject("{}");
        }catch (Exception e){}
        writeViewerBookmark();
    }
    private void writeViewerBookmark(){
        ensureViewerBookmarkLoaded();
        normalizeSourcePrefKeys(pagebookmark);
        prefsEditor.putString("bookmark", pagebookmark.toString());
        prefsEditor.apply();
        notifyLocalChange("pageBookmark");
        notifySync("pageBookmark");
    }

    public boolean toggleFavorite(Title tmp, int position){
            if(tmp == null)
                return false;
            return toggleFavorite(tmp.minimize(), position);
    }

    public boolean toggleFavorite(MTitle title, int position){
        ensureHistoryLoaded();
        if(title == null)
            return false;
        ensureSourceSite(title);
        int index = findFavorite(title);
        if(index==-1){
            if(position < 0 || position > favorite.size())
                position = favorite.size();
            favorite.add(position,title);
            markHistoryIndexDirty();
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
            notifyLocalChange("favorite");
            notifySync("favorite");
            return true;
        }else{
            favorite.remove(index);
            markHistoryIndexDirty();
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
            notifyLocalChange("favorite");
            notifySync("favorite");
            return false;
        }
    }

    public int findFavorite(MTitle title){
        ensureHistoryIndex();
        Integer index = findIndexedPosition(title, favoriteIndexByKey);
        return index == null ? -1 : index;
    }

    public MTitle findRecentTitle(MTitle title) {
        ensureHistoryIndex();
        return findIndexedTitle(title, recentByKey);
    }

    public MTitle findFavoriteTitle(MTitle title) {
        ensureHistoryIndex();
        return findIndexedTitle(title, favoriteByKey);
    }

    public int getStoredProgressBookmark(MTitle title) {
        ensureHistoryIndex();
        MTitle stored = findIndexedTitle(title, recentByKey);
        if(stored == null)
            stored = findIndexedTitle(title, favoriteByKey);
        return stored == null ? -1 : stored.getBookmarkEpisodeId();
    }

    public List<MTitle> getFavorite(){
        ensureHistoryLoaded();
        return favorite;
    }

    public void setFavorites(List<MTitle> fav){
        historyLoaded = true;
        this.favorite = safeTitleList(fav);
        markHistoryIndexDirty();
        Gson gson = new Gson();
        prefsEditor.putString("favorite", gson.toJson(favorite));
        prefsEditor.apply();
        notifyLocalChange("favorite");
        notifySync("favorite");
    }

    public void setRecents(List<MTitle> rec){
        historyLoaded = true;
        this.recent = safeTitleList(rec);
        markHistoryIndexDirty();
        writeRecent();
    }

    public void backfillRecentProgress(CustomHttpClient client, int limit) {
        ensureHistoryLoaded();
        ensureBookmarkLoaded();
        if(client == null || recent == null || recent.size() == 0)
            return;
        boolean changed = false;
        int processed = 0;
        for(MTitle item : Utils.snapshotList(recent)) {
            if(item == null || item.getId() <= 0)
                continue;
            if(item.getBookmarkEpisodeIndex() > 0 && item.getEpisodeCount() > 0)
                continue;
            Title title = new Title(item);
            int bookmarkId = getBookmark(title);
            if(bookmarkId <= 0)
                bookmarkId = item.getBookmarkEpisodeId();
            if(bookmarkId <= 0)
                continue;
            if(limit > 0 && processed >= limit)
                break;
            processed++;
            try {
                int code = title.fetchEps(client);
                List<Manga> episodes = Utils.snapshotEpisodes(title);
                if(code == Title.LOAD_CAPTCHA || episodes.size() == 0)
                    continue;
                int episodeIndex = -1;
                for(int i = 0; i < episodes.size(); i++) {
                    Manga episode = episodes.get(i);
                    if(episode != null && episode.getId() == bookmarkId) {
                        episodeIndex = i + 1;
                        break;
                    }
                }
                if(episodeIndex <= 0 && item.getResumeNtkEpisodePath().length() > 0) {
                    String resumePath = item.getResumeNtkEpisodePath();
                    for(int i = 0; i < episodes.size(); i++) {
                        Manga episode = episodes.get(i);
                        if(episode != null && resumePath.equals(episode.getNtkEpisodePath())) {
                            bookmarkId = episode.getId();
                            episodeIndex = i + 1;
                            break;
                        }
                    }
                }
                if(episodeIndex > 0) {
                    item.setReadingProgress(bookmarkId, episodeIndex, episodes.size());
                    changed = true;
                }
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        }
        if(changed)
            writeRecent();
    }

    public List<MTitle> getRecentForSync(){
        ensureHistoryLoaded();
        return recent == null ? new ArrayList<>() : recent;
    }

    public void setBookmarks(JSONObject book){
        bookmarkLoaded = true;
        this.bookmark = book == null ? new JSONObject() : book;
        normalizeSourcePrefKeys(this.bookmark);
        bookmarkValueByKey.clear();
        knownSourceByKey.clear();
        writeBookmark();
    }

    public void setViewerBookmarks(JSONObject book){
        viewerBookmarkLoaded = true;
        this.pagebookmark = book == null ? new JSONObject() : book;
        normalizeSourcePrefKeys(this.pagebookmark);
        writeViewerBookmark();
    }

    public JSONObject getViewerBookmarkObject() {
        ensureViewerBookmarkLoaded();
        normalizeSourcePrefKeys(pagebookmark);
        return pagebookmark;
    }

    public List<MTitle> getRecent(){
        ensureHistoryLoaded();
        pruneInvalidRecents();
        return recent;
    }

    private void pruneInvalidRecents() {
        if(recent == null)
            return;
        boolean changed = false;
        for(int i = recent.size() - 1; i >= 0; i--) {
            MTitle title = recent.get(i);
            if(title == null || title.getId() <= 0) {
                recent.remove(i);
                changed = true;
            }
        }
        if(changed)
            writeRecent();
    }


//    public boolean match(String s1, String s2){
//        return filterString(s1).matches(filterString(s2));
//    }
//    private String filterString(String input){
//        int i=0, j=0, m=0, k=0;
//        while(i>-1||j>-1||m>-1||k>-1){
//            i = input.indexOf('(');
//            j = input.indexOf(')');
//            m = input.indexOf('/');
//            k = input.indexOf('?');
//            char[] tmp = input.toCharArray();
//            if(i>-1) tmp[i] = ' ';
//            if(j>-1) tmp[j] = ' ';
//            if(m>-1) tmp[m] = ' ';
//            if(k>-1) tmp[k] = ' ';
//            input = String.valueOf(tmp);
//        }
//        return input;
//    }

    //for debug
//    public void removeEpsFromData(){
//        for(Title t:recent){t.removeEps();}
//        for(Title t:favorite){t.removeEps();}
//        writeRecent();
//        Gson gson = new Gson();
//        prefsEditor.putString("favorite", gson.toJson(favorite));
//        prefsEditor.commit();
//    }

    public boolean check(){
        ensureHistoryLoaded();
        //returns false if needs update
        for(MTitle t: recent){
            if(t != null && isInteger(t.getRelease())) return false;
        }
        for(MTitle t: favorite){
            if(t != null && isInteger(t.getRelease())) return false;
        }
        return true;
    }


    public void check2(){
        ensureBookmarkLoaded();
        ensureViewerBookmarkLoaded();
        //returns false if needs update
        Iterator<String> keys = bookmark.keys();
        List<String> fix = new ArrayList<>();
        while(keys.hasNext()){
            String key = keys.next();
            if(key.length() < 2 || key.toCharArray()[1] != '.'){
                fix.add(key);
            }
        }
        String jsonStr = bookmark.toString();
        for(String f : fix){
            jsonStr = jsonStr.replace(f, base_comic +"."+f);
        }
        try {
            this.bookmark = new JSONObject(jsonStr);
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        writeBookmark();

        fix.clear();
        keys = pagebookmark.keys();
        while(keys.hasNext()){
            String key = keys.next();
            if(key.length() < 2 || key.toCharArray()[1] != '.'){
                fix.add(key);
            }
        }
        jsonStr = pagebookmark.toString();
        for(String f : fix){
            jsonStr = jsonStr.replace(f, base_comic +"."+f);
        }
        try {
            this.pagebookmark = new JSONObject(jsonStr);
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        writeViewerBookmark();
    }
    public JSONObject getBookmarkObject() {
        ensureBookmarkLoaded();
        normalizeSourcePrefKeys(bookmark);
        return bookmark;
    }

    public Map<String, Object> exportSyncSettings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("darkTheme", darkTheme);
        settings.put("viewerType", viewerType);
        settings.put("pageReverse", reverse);
        settings.put("pageRtl", pageRtl);
        settings.put("dataSave", dataSave);
        settings.put("startTab", startTab);
        settings.put("stretch", stretch);
        settings.put("leftRight", leftRight);
        settings.put("pageControlButtonOffset", pageControlButtonOffset);
        settings.put("prevPageKey", prevPageKey);
        settings.put("nextPageKey", nextPageKey);
        settings.put("baseMode", baseMode);
        settings.put("doublep", doublep);
        settings.put("doublepReverse", doublepReverse);
        return settings;
    }

    public void importSyncSettings(Map<String, Object> settings) {
        if(settings == null)
            return;
        darkTheme = readBoolean(settings, "darkTheme", darkTheme);
        viewerType = readInt(settings, "viewerType", viewerType);
        reverse = readBoolean(settings, "pageReverse", reverse);
        pageRtl = readBoolean(settings, "pageRtl", pageRtl);
        dataSave = readBoolean(settings, "dataSave", dataSave);
        startTab = readInt(settings, "startTab", startTab);
        stretch = readBoolean(settings, "stretch", stretch);
        leftRight = readBoolean(settings, "leftRight", leftRight);
        pageControlButtonOffset = readFloat(settings, "pageControlButtonOffset", pageControlButtonOffset);
        prevPageKey = readInt(settings, "prevPageKey", prevPageKey);
        nextPageKey = readInt(settings, "nextPageKey", nextPageKey);
        baseMode = readInt(settings, "baseMode", baseMode);
        doublep = readBoolean(settings, "doublep", doublep);
        doublepReverse = readBoolean(settings, "doublepReverse", doublepReverse);
        prefsEditor.putBoolean("darkTheme", darkTheme)
                .putInt("viewerType", viewerType)
                .putBoolean("pageReverse", reverse)
                .putBoolean("pageRtl", pageRtl)
                .putBoolean("dataSave", dataSave)
                .putInt("startTab", startTab)
                .putBoolean("autoUrl", autoUrl)
                .putBoolean("stretch", stretch)
                .putBoolean("leftRight", leftRight)
                .putFloat("pageControlButtonOffset", pageControlButtonOffset)
                .putInt("prevPageKey", prevPageKey)
                .putInt("nextPageKey", nextPageKey)
                .putInt("baseMode", baseMode)
                .putBoolean("doublep", doublep)
                .putBoolean("doublepReverse", doublepReverse)
                .apply();
        notifySync("settings");
    }

    private boolean readBoolean(Map<String, Object> data, String key, boolean fallback) {
        Object value = data.get(key);
        return value instanceof Boolean ? (Boolean)value : fallback;
    }

    private int readInt(Map<String, Object> data, String key, int fallback) {
        Object value = data.get(key);
        if(value instanceof Number)
            return ((Number)value).intValue();
        return fallback;
    }

    private float readFloat(Map<String, Object> data, String key, float fallback) {
        Object value = data.get(key);
        if(value instanceof Number)
            return ((Number)value).floatValue();
        return fallback;
    }

//    public String getSession() {
//        return session;
//    }

//    public void setSession(String session) {
//        this.session = session;
//        prefsEditor.putString("session", session);
//        prefsEditor.commit();
//    }

    public boolean getAutoUrl() {
        return autoUrl;
    }

    public void setAutoUrl(boolean autoUrl) {
        this.autoUrl = autoUrl;
        prefsEditor.putBoolean("autoUrl", autoUrl);
        prefsEditor.apply();
    }


    public int getPrevPageKey() {
        return prevPageKey;
    }

    public void setPrevPageKey(int prevPageKey) {
        this.prevPageKey = prevPageKey;
        prefsEditor.putInt("prevPageKey", prevPageKey);
        prefsEditor.apply();
        notifySync("settings");
    }

    public int getNextPageKey() {
        return nextPageKey;
    }

    public void setNextPageKey(int nextPageKey) {
        this.nextPageKey = nextPageKey;
        prefsEditor.putInt("nextPageKey", nextPageKey);
        prefsEditor.apply();
        notifySync("settings");
    }

    public float getPageControlButtonOffset() {
        return pageControlButtonOffset;
    }

    public void setPageControlButtonOffset(float pageControlButtonOffset) {
        this.pageControlButtonOffset = pageControlButtonOffset;
        prefsEditor.putFloat("pageControlButtonOffset", pageControlButtonOffset);
        prefsEditor.apply();
        notifySync("settings");
    }

    public boolean getDoublep(){
        return doublep;
    }

    public boolean getDoublepReverse(){
        return doublepReverse;
    }

    public void setDoublep(boolean doublep){
        this.doublep = doublep;
        prefsEditor.putBoolean("doublep", doublep);
        prefsEditor.apply();
        notifySync("settings");
    }

    public void setDoublepReverse(boolean doublepReverse){
        this.doublepReverse = doublepReverse;
        prefsEditor.putBoolean("doublepReverse", doublepReverse);
        prefsEditor.apply();
        notifySync("settings");
    }
}
