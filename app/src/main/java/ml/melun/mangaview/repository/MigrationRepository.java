package ml.melun.mangaview.repository;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.MainPage;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;

public final class MigrationRepository {
    private MigrationRepository() {
    }

    public static MigrationResult migrate(Progress progress) {
        MainPage mp = new MainPage(getHttpClient());
        if(mp.getRecent().size() < 1)
            return MigrationResult.connectionError();

        List<MTitle> recents = new ArrayList<>(PreferenceStore.recents());
        List<MTitle> favorites = new ArrayList<>(PreferenceStore.favorites());
        removeDups(favorites);
        removeDups(recents);

        int total = recents.size() + favorites.size();
        int current = 0;
        List<MTitle> newRecents = new ArrayList<>();
        List<MTitle> newFavorites = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for(MTitle recent : recents) {
            current++;
            MTitle found = findTitle(recent);
            progress.onProgress(current, total, found == null ? recent.getName() : found.getName());
            if(found != null)
                newRecents.add(found);
            else
                failed.add(recent.getName());
        }
        for(MTitle favorite : favorites) {
            current++;
            MTitle found = findTitle(favorite);
            progress.onProgress(current, total, found == null ? favorite.getName() : found.getName());
            if(found != null)
                newFavorites.add(found);
            else
                failed.add(favorite.getName());
        }

        PreferenceStore.setFavorites(newFavorites);
        PreferenceStore.setRecents(newRecents);
        p.resetViewerBookmark();
        p.resetBookmark();
        return MigrationResult.success(failed);
    }

    private static void removeDups(List<MTitle> titles) {
        for(int i = 0; i < titles.size(); i++) {
            MTitle target = titles.get(i);
            for(int j = 0; j < titles.size(); j++) {
                if(j != i && titles.get(j).getId() == target.getId()) {
                    titles.remove(i);
                    i--;
                    break;
                }
            }
        }
    }

    private static MTitle findTitle(MTitle title) {
        String name = title.getName();
        Search search = new Search(name, 0, base_comic);
        while(!search.isLast()) {
            search.fetch(getHttpClient());
            for(Title result : search.getResult()) {
                if(result.getName().equals(name))
                    return result.minimize();
            }
        }
        return null;
    }

    public interface Progress {
        void onProgress(int current, int total, String name);
    }

    public static final class MigrationResult {
        public final boolean success;
        public final boolean connectionError;
        public final List<String> failed;

        private MigrationResult(boolean success, boolean connectionError, List<String> failed) {
            this.success = success;
            this.connectionError = connectionError;
            this.failed = failed;
        }

        static MigrationResult success(List<String> failed) {
            return new MigrationResult(true, false, failed);
        }

        static MigrationResult connectionError() {
            return new MigrationResult(false, true, new ArrayList<>());
        }
    }
}
