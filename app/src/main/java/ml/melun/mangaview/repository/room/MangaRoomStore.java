package ml.melun.mangaview.repository.room;

import android.content.Context;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ml.melun.mangaview.Preference;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.runtime.AppDispatchers;

import static ml.melun.mangaview.MainApplication.p;

public final class MangaRoomStore {
    public static final String SCOPE_RECENT = "recent";
    public static final String SCOPE_FAVORITE = "favorite";
    private static final Gson GSON = new Gson();
    private static final AtomicBoolean migrationStarted = new AtomicBoolean(false);

    private MangaRoomStore() {
    }

    public static void prime(Context context) {
        if(context == null || !migrationStarted.compareAndSet(false, true))
            return;
        Context appContext = context.getApplicationContext();
        AppDispatchers.submitIo(() -> migrateLegacy(appContext));
    }

    public static MangaStoreDao dao(Context context) {
        return MangaStoreDatabase.get(context).dao();
    }

    public static void mirrorLibrary(Context context, String scope, List<MTitle> titles) {
        if(context == null || scope == null)
            return;
        dao(context).replaceLibraryScope(scope, libraryEntities(scope, titles));
    }

    public static void mirrorOfflineTitles(Context context, List<Title> titles) {
        if(context == null || titles == null)
            return;
        ArrayList<OfflineIndexEntity> entities = new ArrayList<>();
        long now = System.currentTimeMillis();
        for(Title title : titles) {
            if(title == null || title.getPath() == null || title.getPath().length() == 0)
                continue;
            OfflineIndexEntity entity = new OfflineIndexEntity();
            entity.path = title.getPath();
            entity.titleName = title.getName();
            entity.titleId = title.getId();
            entity.baseMode = title.getBaseMode();
            entity.payloadJson = GSON.toJson(title);
            entity.indexedAt = now;
            entities.add(entity);
        }
        MangaStoreDao dao = dao(context);
        dao.clearOfflineIndex();
        if(entities.size() > 0)
            dao.upsertOfflineIndex(entities);
    }

    private static void migrateLegacy(Context context) {
        try {
            Preference preference = p;
            if(preference == null)
                return;
            MangaStoreDao dao = dao(context);
            dao.replaceLibraryScope(SCOPE_RECENT, libraryEntities(SCOPE_RECENT, preference.getRecent()));
            dao.replaceLibraryScope(SCOPE_FAVORITE, libraryEntities(SCOPE_FAVORITE, preference.getFavorite()));
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static List<LibraryTitleEntity> libraryEntities(String scope, List<MTitle> titles) {
        ArrayList<LibraryTitleEntity> entities = new ArrayList<>();
        if(titles == null)
            return entities;
        long now = System.currentTimeMillis();
        for(int i = 0; i < titles.size(); i++) {
            MTitle title = titles.get(i);
            if(title == null)
                continue;
            LibraryTitleEntity entity = new LibraryTitleEntity();
            entity.scope = scope;
            entity.baseMode = title.getBaseMode();
            entity.titleId = title.getId();
            entity.sortOrder = i;
            entity.updatedAt = now;
            entity.payloadJson = GSON.toJson(title);
            entities.add(entity);
        }
        return entities;
    }
}
