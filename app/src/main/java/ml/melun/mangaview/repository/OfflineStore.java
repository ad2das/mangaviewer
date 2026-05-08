package ml.melun.mangaview.repository;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getOfflineEpisodes;
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
}
