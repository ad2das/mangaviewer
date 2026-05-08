package ml.melun.mangaview.repository;

import android.content.SharedPreferences;

import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.repository.room.MangaRoomStore;

import static ml.melun.mangaview.MainApplication.appContext;
import static ml.melun.mangaview.MainApplication.p;

public final class PreferenceStore {
    private PreferenceStore() {
    }

    public static SharedPreferences raw() {
        return p.getSharedPref();
    }

    public static List<MTitle> favorites() {
        return p.getFavorite();
    }

    public static List<MTitle> recents() {
        return p.getRecent();
    }

    public static void setFavorites(List<MTitle> titles) {
        p.setFavorites(titles);
        mirror(MangaRoomStore.SCOPE_FAVORITE, titles);
    }

    public static void setRecents(List<MTitle> titles) {
        p.setRecents(titles);
        mirror(MangaRoomStore.SCOPE_RECENT, titles);
    }

    public static String homeDir() {
        return p.getHomeDir();
    }

    private static void mirror(String scope, List<MTitle> titles) {
        if(appContext == null)
            return;
        ml.melun.mangaview.runtime.AppDispatchers.submitIo(
                () -> MangaRoomStore.mirrorLibrary(appContext, scope, titles));
    }
}
