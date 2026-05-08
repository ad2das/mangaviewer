package ml.melun.mangaview.repository;

import android.content.SharedPreferences;

import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;

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
    }

    public static void setRecents(List<MTitle> titles) {
        p.setRecents(titles);
    }

    public static String homeDir() {
        return p.getHomeDir();
    }
}
