package ml.melun.mangaview;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
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

    public SharedPreferences getSharedPref(){
        return this.sharedPref;
    }

    public void reset(){
        setUrl(defUrl);
        resetFavorites();
        resetRecent();
        resetBookmark();
        resetViewerBookmark();
    }

    //Offline manga has id of -1
    public Preference(Context context){
        init(context);
    }
    public void init(Context mcontext){
        sharedPref = mcontext.getSharedPreferences("mangaView",Context.MODE_PRIVATE);
        prefsEditor = sharedPref.edit();
        try {
            Gson gson = new Gson();
            recent = gson.fromJson(sharedPref.getString("recent", ""),new TypeToken<ArrayList<MTitle>>(){}.getType());
            if(recent==null) recent = new ArrayList<>();
            favorite = gson.fromJson(sharedPref.getString("favorite", ""),new TypeToken<ArrayList<MTitle>>(){}.getType());
            if(favorite==null) favorite = new ArrayList<>();
            homeDir = sharedPref.getString("homeDir", "");
            prevPageKey = sharedPref.getInt("prevPageKey", -1);
            nextPageKey = sharedPref.getInt("nextPageKey", -1);
            pagebookmark = new JSONObject(sharedPref.getString("bookmark", "{}"));
            bookmark = new JSONObject(sharedPref.getString("bookmark2", "{}"));
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


    public String getBaseModeStr(){
        return baseModeStr(this.baseMode);
    }

    private String normalizeComicUrl(String sourceUrl) {
        if(sourceUrl == null || sourceUrl.trim().length() == 0)
            return DEFAULT_COMIC_URL;
        String normalized = sourceUrl.trim();
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
        String normalized = sourceUrl.trim();
        while(normalized.endsWith("/"))
            normalized = normalized.substring(0, normalized.length() - 1);
        if(normalized.contains("manatoki"))
            return WEBTOON_URL;
        if(normalized.endsWith("/cm"))
            return normalized.substring(0, normalized.length() - 3);
        return normalized;
    }

    public int getBaseMode(){
        return this.baseMode;
    }

    public void setBaseMode(int baseMode){
        this.baseMode = baseMode;
        prefsEditor.putInt("baseMode", baseMode);
        prefsEditor.apply();
    }

    public void setDefUrl(String defUrl){
        this.defUrl = normalizeComicUrl(defUrl);
        prefsEditor.putString("defUrl", this.defUrl);
        prefsEditor.apply();
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
    }

    public int getViewerType() {
        return viewerType;
    }

    public void setViewerType(int viewerType) {
        this.viewerType = viewerType;
        prefsEditor.putInt("viewerType", viewerType);
        prefsEditor.apply();
    }

    public boolean getStretch() {
        return stretch;
    }

    public void setStretch(boolean stretch) {
        this.stretch = stretch;
        prefsEditor.putBoolean("stretch", stretch);
        prefsEditor.apply();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = normalizeComicUrl(url);
        prefsEditor.putString("url", this.url);
        prefsEditor.apply();
    }

    public String getWebtoonUrl() {
        return webtoonUrl;
    }

    public void setWebtoonUrl(String webtoonUrl) {
        this.webtoonUrl = normalizeWebtoonUrl(webtoonUrl);
        prefsEditor.putString("webtoonUrl", this.webtoonUrl);
        prefsEditor.apply();
    }

    public int getStartTab() {
        return startTab;
    }

    public void setStartTab(int startTab) {
        this.startTab = startTab;
        prefsEditor.putInt("startTab", startTab);
        prefsEditor.apply();
    }

    public boolean getDataSave() {
        return dataSave;
    }

    public void setDataSave(boolean dataSave) {
        this.dataSave = dataSave;
        prefsEditor.putBoolean("dataSave", dataSave);
        prefsEditor.apply();
    }

    public boolean getReverse() {
        return reverse;
    }

    public void setReverse(boolean reverse) {
        this.reverse = reverse;
        prefsEditor.putBoolean("pageReverse", reverse);
        prefsEditor.apply();
    }

    public boolean getPageRtl() {
        return pageRtl;
    }

    public void setPageRtl(boolean pageRtl) {
        this.pageRtl = pageRtl;
        prefsEditor.putBoolean("pageRtl", pageRtl);
        prefsEditor.apply();
    }


    public boolean getDarkTheme() {
        return darkTheme;
    }

    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
        prefsEditor.putBoolean("darkTheme", darkTheme);
        prefsEditor.apply();
    }



    public String getHomeDir() {
        return homeDir;
    }

    public void setHomeDir(String homeDir) {
        this.homeDir = homeDir;
        prefsEditor.putString("homeDir", homeDir);
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

    public void addRecent(MTitle tmp){
        if(tmp != null && tmp.getId()>0) {
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
        if(recent.size() == 0)
            recent.add(tmp);
        else
            recent.set(0, tmp);
        writeRecent();
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
        if(recent.size() == 0)
            recent.add(tmp);
        else
            recent.set(0, tmp);
        writeRecent();
        int index = findFavorite(tmp);
        if(index>-1){
            favorite.set(index, tmp);
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
        }
    }

    private int getIndexOf(MTitle title){
        if(title.getId()>0) {
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
                if(bookmark.has(key) && bookmark.getInt(key) == id)
                    return;
                bookmark.put(key, id);
            } catch (Exception e) {
                //
            }
            writeBookmark();
        }
    }
    public int getBookmark(MTitle title){
        //return recent.mget(0).getBookmark();
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

    private void removeBookmark(MTitle title){
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

    }

    private void writeRecent(){
        Gson gson = new Gson();
        prefsEditor.putString("recent", gson.toJson(recent));
        prefsEditor.apply();
    }


    public void setViewerBookmark(Manga m,int index){
        if(m.getId()>-1) {
            if (index > 0) {
                String key = viewerBookmarkKey(m);
                try {
                    if(pagebookmark.has(key) && pagebookmark.getInt(key) == index)
                        return;
                    pagebookmark.put(key, index);
                } catch (Exception e) {
                    //
                }
                writeViewerBookmark();
            }
        }
    }
    public int getViewerBookmark(Manga m){
        if(m.getId()>-1) {
            try {
                return pagebookmark.getInt(viewerBookmarkKey(m));
            } catch (Exception e) {
                //
            }
        }
        return 0;
    }
    public void removeViewerBookmark(Manga m){
        String key = viewerBookmarkKey(m);
        if(!pagebookmark.has(key))
            return;
        pagebookmark.remove(key);
        writeViewerBookmark();
    }

    private String viewerBookmarkKey(Manga m) {
        int titleId = m.getTitleId();
        if(titleId > 0)
            return m.getBaseMode() + "." + titleId + "." + m.getId();
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
            return true;
        }else{
            favorite.remove(index);
            Gson gson = new Gson();
            prefsEditor.putString("favorite", gson.toJson(favorite));
            prefsEditor.apply();
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
        this.favorite = (List<MTitle>)(List<?>)fav;
        Gson gson = new Gson();
        prefsEditor.putString("favorite", gson.toJson(favorite));
        prefsEditor.apply();
    }

    public void setRecents(List<MTitle> rec){
        this.recent = (List<MTitle>)(List<?>)rec;
        writeRecent();
    }

    public void setBookmarks(JSONObject book){
        this.bookmark = book;
        writeBookmark();
    }

    public List<MTitle> getRecent(){
        return recent;
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
            if(isInteger(t.getRelease())) return false;
        }
        for(MTitle t: favorite){
            if(isInteger(t.getRelease())) return false;
        }
        return true;
    }


    public void check2(){
        //returns false if needs update
        Iterator<String> keys = bookmark.keys();
        List<String> fix = new ArrayList<>();
        while(keys.hasNext()){
            String key = keys.next();
            if(key.toCharArray()[1] != '.'){
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
            if(key.toCharArray()[1] != '.'){
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
    }

    public int getNextPageKey() {
        return nextPageKey;
    }

    public void setNextPageKey(int nextPageKey) {
        this.nextPageKey = nextPageKey;
        prefsEditor.putInt("nextPageKey", nextPageKey);
        prefsEditor.apply();
    }

    public float getPageControlButtonOffset() {
        return pageControlButtonOffset;
    }

    public void setPageControlButtonOffset(float pageControlButtonOffset) {
        this.pageControlButtonOffset = pageControlButtonOffset;
        prefsEditor.putFloat("pageControlButtonOffset", pageControlButtonOffset);
        prefsEditor.apply();
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
    }

    public void setDoublepReverse(boolean doublepReverse){
        this.doublepReverse = doublepReverse;
        prefsEditor.putBoolean("doublepReverse", doublepReverse);
        prefsEditor.apply();
    }
}
