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
import static ml.melun.mangaview.mangaview.CustomHttpClient.WEBTOON_URL;

public class Preference {
    SharedPreferences sharedPref;
    //ArrayList<Title> recent;
    List<MTitle> recent;
    List<MTitle> favorite;
    SharedPreferences.Editor prefsEditor;
    JSONObject pagebookmark;
    JSONObject bookmark;
    JSONObject offlineProgress;
    String homeDir;
    boolean darkTheme;
    int viewerType;
    boolean reverse;
    boolean pageRtl;
    boolean dataSave;
    int startTab;
    String url;
    String webtoonUrl;
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
        for(LocalChangeListener listener : localChangeListeners)
            listener.onLocalPreferenceChanged(scope);
    }

    private void notifySync(String scope) {
        if(!syncSuppressed && syncManager != null)
            syncManager.onLocalPreferencesChanged(scope);
    }

    public void reset(){
        setUrl(defUrl);
        resetFavorites();
        resetRecent();
        resetBookmark();
        resetViewerBookmark();
        resetOfflineProgress();
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
        offlineProgress = new JSONObject();
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
            Gson gson = new Gson();
            recent = safeTitleList(gson.fromJson(sharedPref.getString("recent", ""),new TypeToken<ArrayList<MTitle>>(){}.getType()));
            favorite = safeTitleList(gson.fromJson(sharedPref.getString("favorite", ""),new TypeToken<ArrayList<MTitle>>(){}.getType()));
            homeDir = sharedPref.getString("homeDir", "");
            prevPageKey = sharedPref.getInt("prevPageKey", -1);
            nextPageKey = sharedPref.getInt("nextPageKey", -1);
            pagebookmark = new JSONObject(sharedPref.getString("bookmark", "{}"));
            bookmark = new JSONObject(sharedPref.getString("bookmark2", "{}"));
            offlineProgress = new JSONObject(sharedPref.getString("offlineProgress", "{}"));
            darkTheme = sharedPref.getBoolean("darkTheme", false);
            viewerType = sharedPref.getInt("viewerType",0);
            reverse = sharedPref.getBoolean("pageReverse",false);
            pageRtl = sharedPref.getBoolean("pageRtl",false);
            dataSave = sharedPref.getBoolean("dataSave", false);
            startTab = sharedPref.getInt("startTab", 0);
            defUrl = normalizeComicUrl(sharedPref.getString("defUrl", DEFAULT_COMIC_URL));
            url = normalizeComicUrl(sharedPref.getString("url", DEFAULT_COMIC_URL));
            webtoonUrl = normalizeWebtoonUrl(sharedPref.getString("webtoonUrl", WEBTOON_URL));
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
                    .putBoolean("autoUrl", false)
                    .remove("login")
                    .remove("notice")
                    .remove("lastNoticeTime")
                    .remove("lastUpdateTime")
                    .apply();
        }catch(Exception e){
            e.printStackTrace();
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

    private String normalizeComicUrl(String sourceUrl) {
        if(sourceUrl == null || sourceUrl.trim().length() == 0)
            return DEFAULT_COMIC_URL;
        String normalized = normalizeHttpUrl(sourceUrl.trim(), DEFAULT_COMIC_URL);
        while(normalized.endsWith("/"))
            normalized = normalized.substring(0, normalized.length() - 1);
        if(normalized.contains("manatoki"))
            return DEFAULT_COMIC_URL;
        if(normalized.equals(WEBTOON_URL))
            return DEFAULT_COMIC_URL;
        return normalized;
    }

    private String normalizeWebtoonUrl(String sourceUrl) {
        if(sourceUrl == null || sourceUrl.trim().length() == 0)
            return WEBTOON_URL;
        String normalized = normalizeHttpUrl(sourceUrl.trim(), WEBTOON_URL);
        while(normalized.endsWith("/"))
            normalized = normalized.substring(0, normalized.length() - 1);
        if(normalized.contains("manatoki"))
            return WEBTOON_URL;
        if(normalized.endsWith("/cm"))
            return normalized.substring(0, normalized.length() - 3);
        return normalized;
    }

    private String normalizeHttpUrl(String sourceUrl, String fallback) {
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
        this.url = normalizeComicUrl(url);
        prefsEditor.putString("url", this.url);
        prefsEditor.apply();
        notifySync("settings");
    }

    public String getWebtoonUrl() {
        return webtoonUrl;
    }

    public void setWebtoonUrl(String webtoonUrl) {
        this.webtoonUrl = normalizeWebtoonUrl(webtoonUrl);
        prefsEditor.putString("webtoonUrl", this.webtoonUrl);
        prefsEditor.apply();
        notifySync("settings");
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
        if(position < 0 || position >= recent.size())
            return;
        MTitle title = recent.remove(position);
        writeRecent();
        removeBookmark(title);
        writeBookmark();
    }

    public void removeRecent(MTitle title){
        int position = getIndexOf(title);
        if(position < 0)
            return;
        MTitle removed = recent.remove(position);
        writeRecent();
        removeBookmark(removed);
        writeBookmark();
    }

    public void addRecent(MTitle tmp){
        if(tmp != null && tmp.getId()>0) {
            tmp.setPath(null);
            int position = getIndexOf(tmp);
            if (position > -1) {
                recent.remove(position);
                recent.add(0, tmp);
            } else recent.add(0, tmp);
            writeRecent();
        }
    }
    public void addRecent(Title tmp){
        if(tmp != null && tmp.getId()>0) {
            MTitle title = tmp.minimize();
            title.setPath(null);
            int position = getIndexOf(title);
            if (position > -1) {
                recent.remove(position);
                recent.add(0, title);
            } else recent.add(0, title);
            writeRecent();
        }
    }


    public void updateRecentData(MTitle title){
        if(title == null)
            return;
        MTitle tmp = title.clone();
        tmp.setPath(null);
        int recentIndex = getIndexOf(tmp);
        if(recentIndex > -1) {
            recent.set(recentIndex, tmp);
            writeRecent();
        }
        int index = findFavorite(tmp);
        if(index>-1){
            favorite.set(index,tmp);
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
        }
    }

    public void updateRecentData(Title title){
        if(title == null)
            return;
        MTitle tmp = title.minimize();
        tmp.setPath(null);
        int recentIndex = getIndexOf(tmp);
        if(recentIndex > -1) {
            recent.set(recentIndex, tmp);
            writeRecent();
        }
        int index = findFavorite(tmp);
        if(index>-1){
            favorite.set(index, tmp);
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
        }
    }

    private int getIndexOf(MTitle title){
        if(title != null && title.getId()>0) {
            return recent.indexOf(title);
        }
        return -1;
    }

    public void setBookmark(Title title, int id){
        if(title == null)
            return;
        int titleId = title.getId();
        if(titleId>0) {
            String key = title.getBaseMode() + "." + title.getId();
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
        if(title == null || episodeId <= 0)
            return;
        int index = getIndexOf(title);
        if(index < 0)
            return;
        int episodeIndex = -1;
        int episodeCount = title.getEpsCount();
        if(title.getEps() != null) {
            for(int i = 0; i < title.getEps().size(); i++) {
                if(title.getEps().get(i) != null && title.getEps().get(i).getId() == episodeId) {
                    episodeIndex = i + 1;
                    break;
                }
            }
        }
        MTitle recentTitle = recent.get(index);
        recentTitle.setReadingProgress(episodeId, episodeIndex, episodeCount);
        writeRecent();
    }
    public int getBookmark(MTitle title){
        //return recent.mget(0).getBookmark();
        if(title == null)
            return -1;
        int titleId = title.getId();
        if(titleId>0) {
            try {
                return bookmark.getInt(title.getBaseMode()+"."+titleId);
            } catch (Exception e) {
                //
            }
        }
        return -1;
    }

    public void setOfflineProgress(Title title, Manga manga) {
        if(title == null || manga == null || manga.isOnline() || manga.getId() <= 0)
            return;
        String key = offlineProgressKey(title);
        if(key == null)
            return;
        int episodeIndex = -1;
        int episodeCount = title.getEpsCount();
        if(title.getEps() != null) {
            for(int i = 0; i < title.getEps().size(); i++) {
                Manga episode = title.getEps().get(i);
                if(episode != null && episode.getId() == manga.getId()) {
                    episodeIndex = i + 1;
                    break;
                }
            }
        }
        try {
            JSONObject value = new JSONObject();
            value.put("episodeId", manga.getId());
            value.put("episodeIndex", episodeIndex);
            value.put("episodeCount", episodeCount);
            offlineProgress.put(key, value);
            writeOfflineProgress();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getOfflineBookmark(MTitle title) {
        JSONObject value = getOfflineProgress(title);
        if(value == null)
            return -1;
        return value.optInt("episodeId", -1);
    }

    public boolean applyOfflineProgress(Title title) {
        if(title == null)
            return false;
        JSONObject value = getOfflineProgress(title);
        if(value == null)
            return false;
        int episodeId = value.optInt("episodeId", -1);
        if(episodeId <= 0)
            return false;
        int episodeIndex = value.optInt("episodeIndex", -1);
        int episodeCount = value.optInt("episodeCount", title.getEpsCount());
        title.setBookmark(episodeId);
        title.setReadingProgress(episodeId, episodeIndex, episodeCount);
        return true;
    }

    private JSONObject getOfflineProgress(MTitle title) {
        String key = offlineProgressKey(title);
        if(key == null)
            return null;
        return offlineProgress.optJSONObject(key);
    }

    private String offlineProgressKey(MTitle title) {
        if(title == null)
            return null;
        if(title.getId() > 0)
            return title.getBaseMode() + "." + title.getId();
        String path = title.getPath();
        if(path != null && path.length() > 0)
            return "path." + path.hashCode();
        return null;
    }

    private void writeOfflineProgress() {
        prefsEditor.putString("offlineProgress", offlineProgress.toString());
        prefsEditor.apply();
        notifyLocalChange("offlineProgress");
    }

    private void resetOfflineProgress(){
        try {
            offlineProgress = new JSONObject("{}");
        }catch (Exception e){}
        writeOfflineProgress();
    }

    private void removeBookmark(MTitle title){
        if(title == null)
            return;
        int titleId = title.getId();
        if(titleId>0) {
            try {
                String key = title.getBaseMode()+"."+titleId;
                if(!bookmark.has(key))
                    return;
                bookmark.remove(key);
            } catch (Exception e) {
                //
            }
            writeBookmark();
        }
    }

    public void writeBookmark(){
        prefsEditor.putString("bookmark2", bookmark.toString());
        prefsEditor.apply();
        notifyLocalChange("bookmark");
        notifySync("bookmark");
    }

    public void resetBookmark(){
        try {
            bookmark = new JSONObject("{}");
        }catch (Exception e){}
        writeBookmark();
    }
    public void resetRecent(){
        recent = new ArrayList<>();
        writeRecent();
    }

    public void resetFavorites(){
        favorite = new ArrayList<>();
        prefsEditor.putString("favorite", new Gson().toJson(favorite));
        prefsEditor.apply();
        notifySync("favorite");

    }

    private void writeRecent(){
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
        if(m == null)
            return;
        if(m.getId()>-1) {
            if(index <= 0 && offset == 0) {
                removeViewerBookmark(m);
                return;
            }
            if (index > 0 || offset != 0) {
                String key = viewerBookmarkKey(m);
                String offsetKey = viewerBookmarkOffsetKey(m);
                try {
                    int existingIndex = pagebookmark.has(key) ? pagebookmark.getInt(key) : 0;
                    int existingOffset = pagebookmark.has(offsetKey) ? pagebookmark.getInt(offsetKey) : 0;
                    if(existingIndex == index && existingOffset == offset)
                        return;
                    pagebookmark.put(key, index);
                    pagebookmark.put(offsetKey, offset);
                } catch (Exception e) {
                    //
                }
                writeViewerBookmark();
            }
        }
    }
    public int getViewerBookmark(Manga m){
        if(m == null)
            return 0;
        if(m.getId()>-1) {
            try {
                return pagebookmark.getInt(viewerBookmarkKey(m));
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
        if(m == null)
            return 0;
        if(m.getId()>-1) {
            try {
                return pagebookmark.getInt(viewerBookmarkOffsetKey(m));
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
    public void removeViewerBookmark(Manga m){
        if(m == null)
            return;
        String key = viewerBookmarkKey(m);
        String offsetKey = viewerBookmarkOffsetKey(m);
        if(!pagebookmark.has(key) && !pagebookmark.has(offsetKey))
            return;
        pagebookmark.remove(key);
        pagebookmark.remove(offsetKey);
        writeViewerBookmark();
    }

    private String viewerBookmarkKey(Manga m) {
        int titleId = m.getTitleId();
        if(titleId > 0)
            return m.getBaseMode() + "." + titleId + "." + m.getId();
        return m.getBaseMode() + "." + m.getId();
    }

    private String viewerBookmarkOffsetKey(Manga m) {
        return viewerBookmarkKey(m) + ".offset";
    }

    private String legacyViewerBookmarkKey(Manga m) {
        if(m == null || m.getTitleId() <= 0)
            return null;
        return m.getBaseMode() + "." + m.getId();
    }
    public void resetViewerBookmark(){
        try {
            pagebookmark = new JSONObject("{}");
        }catch (Exception e){}
        writeViewerBookmark();
    }
    private void writeViewerBookmark(){
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
        if(title == null)
            return false;
        int index = findFavorite(title);
        if(index==-1){
            if(position < 0 || position > favorite.size())
                position = favorite.size();
            favorite.add(position,title);
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
            notifySync("favorite");
            return true;
        }else{
            favorite.remove(index);
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
            notifySync("favorite");
            return false;
        }
    }

    public int findFavorite(MTitle title){
        if(title != null && title.getId()>0){
            return favorite.indexOf(title);
        }
        return -1;
    }

    public List<MTitle> getFavorite(){
        return favorite;
    }

    public void setFavorites(List<MTitle> fav){
        this.favorite = safeTitleList(fav);
        Gson gson = new Gson();
        prefsEditor.putString("favorite", gson.toJson(favorite));
        prefsEditor.apply();
        notifyLocalChange("favorite");
        notifySync("favorite");
    }

    public void setRecents(List<MTitle> rec){
        this.recent = safeTitleList(rec);
        writeRecent();
    }

    public void backfillRecentProgress(CustomHttpClient client, int limit) {
        if(client == null || recent == null || recent.size() == 0)
            return;
        boolean changed = false;
        int processed = 0;
        for(MTitle item : recent) {
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
                if(code == Title.LOAD_CAPTCHA || title.getEps() == null || title.getEps().size() == 0)
                    continue;
                int episodeIndex = -1;
                for(int i = 0; i < title.getEps().size(); i++) {
                    Manga episode = title.getEps().get(i);
                    if(episode != null && episode.getId() == bookmarkId) {
                        episodeIndex = i + 1;
                        break;
                    }
                }
                if(episodeIndex > 0) {
                    item.setReadingProgress(bookmarkId, episodeIndex, title.getEps().size());
                    changed = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if(changed)
            writeRecent();
    }

    public List<MTitle> getRecentForSync(){
        return recent == null ? new ArrayList<>() : recent;
    }

    public void setBookmarks(JSONObject book){
        this.bookmark = book == null ? new JSONObject() : book;
        writeBookmark();
    }

    public void setViewerBookmarks(JSONObject book){
        this.pagebookmark = book == null ? new JSONObject() : book;
        writeViewerBookmark();
    }

    public JSONObject getViewerBookmarkObject() {
        return pagebookmark;
    }

    public List<MTitle> getRecent(){
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
        writeViewerBookmark();
    }
    public JSONObject getBookmarkObject() {
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
        settings.put("url", url);
        settings.put("webtoonUrl", webtoonUrl);
        settings.put("defUrl", defUrl);
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
        url = normalizeComicUrl(readString(settings, "url", url));
        webtoonUrl = normalizeWebtoonUrl(readString(settings, "webtoonUrl", webtoonUrl));
        defUrl = normalizeComicUrl(readString(settings, "defUrl", defUrl));
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
                .putString("url", url)
                .putString("webtoonUrl", webtoonUrl)
                .putString("defUrl", defUrl)
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

    private String readString(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        return value instanceof String ? (String)value : fallback;
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
