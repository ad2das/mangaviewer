package ml.melun.mangaview.repository;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.room.MangaRoomStore;
import ml.melun.mangaview.runtime.AppDispatchers;

import static ml.melun.mangaview.Utils.deleteRecursive;
import static ml.melun.mangaview.Utils.documentFileFromUri;
import static ml.melun.mangaview.Utils.filterFolder;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getOfflineEpisodes;
import static ml.melun.mangaview.Utils.readFileToString;
import static ml.melun.mangaview.Utils.readUriToString;
import static ml.melun.mangaview.Utils.useScopedStorageHome;

public final class OfflineStore {
    private static volatile String cachedHomeDir = "";
    private static volatile long cachedAt = 0L;
    private static volatile List<Object> cachedSnapshot = new ArrayList<>();
    private static final long SNAPSHOT_TTL_MS = 15_000L;

    private OfflineStore() {
    }

    public static synchronized void invalidate() {
        cachedAt = 0L;
        cachedSnapshot = new ArrayList<>();
    }

    public static synchronized List<Object> snapshot(Context context) {
        String homeDir = p.getHomeDir();
        long now = System.currentTimeMillis();
        if(homeDir.equals(cachedHomeDir) && now - cachedAt < SNAPSHOT_TTL_MS)
            return new ArrayList<>(cachedSnapshot);
        ArrayList<Object> result = new ArrayList<>();
        if(homeDir == null || homeDir.length() == 0)
            return result;
        if(useScopedStorageHome(homeDir)) {
            DocumentFile home = DocumentFile.fromTreeUri(context, Uri.parse(homeDir));
            if(home != null)
                result.addAll(getOfflineEpisodes(home));
        } else {
            File home = new File(homeDir);
            File[] children = home.listFiles();
            if(children != null)
                for(File child : children)
                    result.add(child);
        }
        cachedHomeDir = homeDir;
        cachedAt = now;
        cachedSnapshot = new ArrayList<>(result);
        return result;
    }

    public static synchronized ArrayList<Title> loadTitles(Context context) {
        ArrayList<Title> titles = new ArrayList<>();
        String homeDir = p.getHomeDir();
        if(homeDir == null || homeDir.length() == 0 || context == null)
            return titles;
        if(useScopedStorageHome(homeDir)) {
            DocumentFile home;
            try {
                home = DocumentFile.fromTreeUri(context, Uri.parse(homeDir));
            } catch (IllegalArgumentException e) {
                return titles;
            }
            if(home == null || !home.canRead())
                return titles;
            for(DocumentFile child : home.listFiles()) {
                if(child.isDirectory())
                    titles.add(readOfflineTitle(context, child));
            }
            mirrorOfflineIndex(context, titles);
            return titles;
        }
        File home = new File(homeDir);
        File[] files = home.exists() ? home.listFiles() : null;
        if(files == null)
            return titles;
        for(File child : files) {
            if(child.isDirectory())
                titles.add(readOfflineTitle(child));
        }
        mirrorOfflineIndex(context, titles);
        return titles;
    }

    private static void mirrorOfflineIndex(Context context, ArrayList<Title> titles) {
        Context appContext = context == null ? null : context.getApplicationContext();
        if(appContext == null)
            return;
        AppDispatchers.submitIo(() -> MangaRoomStore.mirrorOfflineTitles(appContext, titles));
    }

    public static Manga resolveResumeManga(Context context, Title title, int bookmark) {
        if(context == null || title == null || title.getPath() == null)
            return null;
        List<Manga> episodes = title.getEps();
        if(episodes == null)
            episodes = new ArrayList<>();
        title.setEps(episodes);
        int mode = title.useBookmark() ? 3 : 4;
        if(useScopedStorageHome(title.getPath())) {
            DocumentFile titleDir = documentFileFromUri(context, title.getPath());
            for(DocumentFile folder : getOfflineEpisodes(titleDir)) {
                Manga found = applyOfflineFolder(title, episodes, folder.getName(), folder.getUri().toString(), mode);
                if(found != null && found.getId() == bookmark)
                    return found;
            }
            return null;
        }
        for(File folder : getOfflineEpisodes(title.getPath())) {
            Manga found = applyOfflineFolder(title, episodes, folder.getName(), folder.getAbsolutePath(), mode);
            if(found != null && found.getId() == bookmark)
                return found;
        }
        return null;
    }

    public static OfflineEpisodes loadEpisodes(Context context, Title title) {
        ArrayList<Manga> episodes = new ArrayList<>();
        if(context == null || title == null || title.getPath() == null)
            return new OfflineEpisodes(episodes, 1);
        int mode = 1;
        if(useScopedStorageHome(title.getPath())) {
            DocumentFile titleDir = documentFileFromUri(context, title.getPath());
            DocumentFile data = titleDir == null ? null : titleDir.findFile("title.gson");
            if(data != null) {
                mode = title.useBookmark() ? 3 : 4;
                episodes = existingEpisodes(title);
                for(DocumentFile folder : getOfflineEpisodes(titleDir))
                    applyExistingOfflineFolder(title, episodes, folder.getName(), folder.getUri().toString(), mode);
                removeMissingOfflineEpisodes(episodes);
            } else if(titleDir != null) {
                for(DocumentFile folder : getOfflineEpisodes(titleDir)) {
                    Manga manga = new Manga(-1, folder.getName(), "", title.getBaseMode());
                    manga.setMode(mode);
                    manga.setOfflinePath(folder.getUri().toString());
                    episodes.add(manga);
                }
            }
            title.setEps(episodes);
            return new OfflineEpisodes(episodes, mode);
        }

        File titleDir = new File(title.getPath());
        File data = new File(titleDir, "title.gson");
        if(data.exists()) {
            mode = title.useBookmark() ? 3 : 4;
            episodes = existingEpisodes(title);
            for(File folder : getOfflineEpisodes(title.getPath()))
                applyExistingOfflineFolder(title, episodes, folder.getName(), folder.getAbsolutePath(), mode);
            removeMissingOfflineEpisodes(episodes);
        } else {
            for(File folder : getOfflineEpisodes(title.getPath())) {
                Manga manga = new Manga(-1, folder.getName(), "", title.getBaseMode());
                manga.setMode(mode);
                manga.setOfflinePath(folder.getAbsolutePath());
                episodes.add(manga);
            }
        }
        title.setEps(episodes);
        return new OfflineEpisodes(episodes, mode);
    }

    public static boolean deleteTitle(Context context, Title title) {
        if(context == null || title == null)
            return false;
        boolean deleted = false;
        String path = title.getPath();
        if(path != null && path.length() > 0) {
            if(useScopedStorageHome(path)) {
                try {
                    DocumentFile target = DocumentFile.fromTreeUri(context, Uri.parse(path));
                    deleted = target != null && target.delete();
                } catch (Exception e) {
                    ml.melun.mangaview.report.CrashReporter.record(e);
                }
            } else {
                deleted = deleteRecursive(new File(path));
            }
        }
        if(deleted)
            return true;
        if(useScopedStorageHome(p.getHomeDir())) {
            try {
                DocumentFile home = DocumentFile.fromTreeUri(context, Uri.parse(p.getHomeDir()));
                DocumentFile target = home == null ? null : home.findFile(filterFolder(title.getName()));
                return target != null && target.delete();
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
                return false;
            }
        }
        return deleteRecursive(new File(p.getHomeDir(), filterFolder(title.getName())));
    }

    public static boolean deleteEpisode(Context context, Manga manga) {
        if(context == null || manga == null || manga.getOfflinePath() == null || manga.getOfflinePath().length() == 0)
            return false;
        String path = manga.getOfflinePath();
        if(useScopedStorageHome(path)) {
            try {
                DocumentFile target = documentFileFromUri(context, path);
                return target != null && target.delete();
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
                return false;
            }
        }
        return deleteRecursive(new File(path));
    }

    private static ArrayList<Manga> existingEpisodes(Title title) {
        List<Manga> source = title.getEps();
        if(source == null)
            return new ArrayList<>();
        return new ArrayList<>(source);
    }

    private static void applyExistingOfflineFolder(Title title, List<Manga> episodes, String folderName, String path, int mode) {
        int id = parseOfflineEpisodeId(folderName);
        if(id <= 0)
            return;
        int index = episodes.indexOf(new Manga(id, "", "", title.getBaseMode()));
        if(index > -1) {
            episodes.get(index).setOfflinePath(path);
            episodes.get(index).setMode(mode);
        }
    }

    private static void removeMissingOfflineEpisodes(List<Manga> episodes) {
        for(int i = episodes.size() - 1; i >= 0; i--)
            if(episodes.get(i).getOfflinePath() == null)
                episodes.remove(i);
    }

    private static Manga applyOfflineFolder(Title title, List<Manga> episodes, String folderName, String path, int mode) {
        if(folderName == null || path == null)
            return null;
        int id = parseOfflineEpisodeId(folderName);
        Manga manga = null;
        if(id > 0) {
            for(Manga episode : episodes) {
                if(episode != null && episode.getId() == id && episode.getBaseMode() == title.getBaseMode()) {
                    manga = episode;
                    break;
                }
            }
            if(manga == null) {
                manga = new Manga(id, folderName, "", title.getBaseMode());
                episodes.add(manga);
            }
        } else {
            manga = new Manga(-1, folderName, "", title.getBaseMode());
            episodes.add(manga);
        }
        manga.setOfflinePath(path);
        manga.setMode(id > 0 ? mode : 1);
        return manga;
    }

    private static int parseOfflineEpisodeId(String folderName) {
        try {
            int dot = folderName.lastIndexOf('.');
            if(dot < 0 || dot >= folderName.length() - 1)
                return -1;
            return Integer.parseInt(folderName.substring(dot + 1));
        } catch (Exception e) {
            return -1;
        }
    }

    private static Title readOfflineTitle(Context context, DocumentFile folder) {
        DocumentFile data = folder.findFile("title.gson");
        if(data != null) {
            try {
                Title title = new Gson().fromJson(readUriToString(context, data.getUri()), new TypeToken<Title>() {
                }.getType());
                title.setPath(folder.getUri().toString());
                String thumb = title.getThumb();
                if(thumb != null && thumb.length() > 0) {
                    DocumentFile target = folder.findFile(thumb);
                    if(target != null)
                        title.setThumb(target.getUri().toString());
                }
                return title;
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        }
        Title title = new Title(folder.getName(), "", "", new ArrayList<>(), "", 0, MTitle.base_auto);
        title.setPath(folder.getUri().toString());
        return title;
    }

    private static Title readOfflineTitle(File folder) {
        File data = new File(folder, "title.gson");
        if(data.exists()) {
            try {
                Title title = new Gson().fromJson(readFileToString(data), new TypeToken<Title>() {
                }.getType());
                title.setPath(folder.getAbsolutePath());
                String thumb = title.getThumb();
                if(thumb != null && thumb.length() > 0)
                    title.setThumb(folder.getAbsolutePath() + '/' + thumb);
                return title;
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        }
        Title title = new Title(folder.getName(), "", "", new ArrayList<>(), "", 0, MTitle.base_auto);
        title.setPath(folder.getAbsolutePath());
        return title;
    }

    public static final class OfflineEpisodes {
        public final ArrayList<Manga> episodes;
        public final int mode;

        public OfflineEpisodes(ArrayList<Manga> episodes, int mode) {
            this.episodes = episodes;
            this.mode = mode;
        }
    }
}
