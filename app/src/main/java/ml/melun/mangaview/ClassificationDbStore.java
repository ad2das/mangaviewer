package ml.melun.mangaview;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

public final class ClassificationDbStore {
    public static final String DB_FILE_NAME = "classification.sqlite";
    private static final String SEED_DB_ASSET_NAME = "classification.sqlite";
    private static final int CACHE_LIMIT = 256;
    private static final Object lock = new Object();
    private static SQLiteDatabase database;
    private static String openedPath = "";
    private static final LinkedHashMap<String, List<String>> tagCache = lruMap();
    private static final LinkedHashMap<String, Entry> titleCache = lruMap();
    private static final LinkedHashMap<String, Integer> countCache = lruMap();
    private static final LinkedHashMap<String, ArrayList<Title>> pageCache = lruMap();

    private ClassificationDbStore() {
    }

    public static File dbDir(Context context) {
        if(context == null)
            return null;
        return new File(context.getFilesDir(), "classification-db");
    }

    public static File dbFile(Context context) {
        File dir = dbDir(context);
        return dir == null ? null : new File(dir, DB_FILE_NAME);
    }

    public static void invalidate() {
        synchronized (lock) {
            closeLocked();
            tagCache.clear();
            titleCache.clear();
            countCache.clear();
            pageCache.clear();
        }
    }

    public static Entry findTitle(Context context, boolean comic, String name, int id) {
        return findTitle(context, comic, "wfwf", name, id);
    }

    public static Entry findTitle(Context context, boolean comic, String sourceSite, String name, int id) {
        String kind = kind(comic);
        String site = normalizeSourceSite(sourceSite);
        String nameKey = normalizeName(name);
        if(id <= 0 && nameKey.length() == 0)
            return null;
        String cacheKey = site + ":" + kind + ":title:" + id + ":" + nameKey;
        synchronized (lock) {
            Entry cached = titleCache.get(cacheKey);
            if(cached != null)
                return cached.copy();
        }
        SQLiteDatabase db = openReadOnly(context);
        if(db == null)
            return null;
        Entry result = null;
        Cursor cursor = null;
        try {
            if(id > 0) {
                cursor = db.rawQuery(
                        "SELECT id,name,thumb,release,source_site,path FROM classification_titles WHERE kind=? AND source_site=? AND id=? LIMIT 1",
                        new String[]{kind, site, String.valueOf(id)});
                result = readEntry(db, kind, site, cursor);
                cursor.close();
                cursor = null;
            }
            if(result == null && nameKey.length() > 0) {
                cursor = db.rawQuery(
                        "SELECT id,name,thumb,release,source_site,path FROM classification_titles WHERE kind=? AND source_site=? AND normalized_name=? LIMIT 1",
                        new String[]{kind, site, nameKey});
                result = readEntry(db, kind, site, cursor);
            }
        } catch (Exception e) {
            result = null;
        } finally {
            if(cursor != null)
                cursor.close();
        }
        if(result != null) {
            synchronized (lock) {
                titleCache.put(cacheKey, result.copy());
            }
            return result;
        }
        return null;
    }

    public static List<String> getTagsById(Context context, boolean comic, int id) {
        return getTagsById(context, comic, "wfwf", id);
    }

    public static List<String> getTagsById(Context context, boolean comic, String sourceSite, int id) {
        if(id <= 0)
            return null;
        String kind = kind(comic);
        String site = normalizeSourceSite(sourceSite);
        String cacheKey = site + ":" + kind + ":id:" + id;
        synchronized (lock) {
            List<String> cached = tagCache.get(cacheKey);
            if(cached != null)
                return new ArrayList<>(cached);
        }
        SQLiteDatabase db = openReadOnly(context);
        if(db == null)
            return null;
        ArrayList<String> tags = readTags(db, kind, site, id);
        if(tags.size() == 0)
            return null;
        synchronized (lock) {
            tagCache.put(cacheKey, new ArrayList<>(tags));
        }
        return tags;
    }

    public static List<String> getTagsByName(Context context, boolean comic, String name) {
        return getTagsByName(context, comic, "wfwf", name);
    }

    public static List<String> getTagsByName(Context context, boolean comic, String sourceSite, String name) {
        Entry entry = findTitle(context, comic, sourceSite, name, 0);
        return entry == null || entry.tags.size() == 0 ? null : new ArrayList<>(entry.tags);
    }

    public static ArrayList<Title> getTitlesByGenre(Context context, boolean comic, String genre, int offset, int limit) {
        return getTitlesByGenre(context, comic, "wfwf", genre, offset, limit);
    }

    public static ArrayList<Title> getTitlesByGenre(Context context, boolean comic, String sourceSite, String genre, int offset, int limit) {
        String tag = normalizeTag(genre);
        if(tag.length() == 0)
            return new ArrayList<>();
        String kind = kind(comic);
        String site = normalizeSourceSite(sourceSite);
        int start = Math.max(0, offset);
        String cacheKey = site + ":" + kind + ":genre:" + tag + ":" + start + ":" + limit;
        synchronized (lock) {
            ArrayList<Title> cached = pageCache.get(cacheKey);
            if(cached != null)
                return cloneTitles(cached);
        }
        SQLiteDatabase db = openReadOnly(context);
        if(db == null)
            return new ArrayList<>();
        ArrayList<Title> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            String sql = "SELECT t.id,t.path,t.name,t.thumb,t.release "
                    + "FROM classification_title_tags g "
                    + "JOIN classification_titles t ON t.kind=g.kind AND t.source_site=g.source_site AND t.id=g.id "
                    + "WHERE g.kind=? AND g.source_site=? AND g.normalized_tag=? "
                    + "ORDER BY t.id LIMIT ? OFFSET ?";
            String pageLimit = String.valueOf(limit > 0 ? limit : 1000000);
            cursor = db.rawQuery(sql, new String[]{kind, site, tag, pageLimit, String.valueOf(start)});
            int baseMode = comic ? base_comic : base_webtoon;
            while(cursor.moveToNext()) {
                int id = cursor.getInt(0);
                ArrayList<String> tags = readTags(db, kind, site, id);
                result.add(titleFromClassificationRow(baseMode, site, id, cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4), tags));
            }
        } catch (Exception e) {
            result.clear();
        } finally {
            if(cursor != null)
                cursor.close();
        }
        synchronized (lock) {
            pageCache.put(cacheKey, cloneTitles(result));
        }
        return result;
    }

    public static ArrayList<Title> getTitles(Context context, boolean comic, int limit) {
        return getTitles(context, comic, "wfwf", limit);
    }

    public static ArrayList<Title> getTitles(Context context, boolean comic, String sourceSite, int limit) {
        return getTitles(context, comic, sourceSite, 0, limit);
    }

    public static ArrayList<Title> getTitles(Context context, boolean comic, String sourceSite, int offset, int limit) {
        SQLiteDatabase db = openReadOnly(context);
        if(db == null)
            return new ArrayList<>();
        ArrayList<Title> result = new ArrayList<>();
        Cursor cursor = null;
        String kind = kind(comic);
        String site = normalizeSourceSite(sourceSite);
        try {
            cursor = db.rawQuery(
                "SELECT id,path,name,thumb,release FROM classification_titles WHERE kind=? AND source_site=? ORDER BY id LIMIT ? OFFSET ?",
                    new String[]{kind, site, String.valueOf(limit > 0 ? limit : 1000000),
                            String.valueOf(Math.max(0, offset))});
            int baseMode = comic ? base_comic : base_webtoon;
            while(cursor.moveToNext()) {
                int id = cursor.getInt(0);
                result.add(titleFromClassificationRow(baseMode, site, id, cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4),
                        readTags(db, kind, site, id)));
            }
        } catch (Exception e) {
            result.clear();
        } finally {
            if(cursor != null)
                cursor.close();
        }
        return result;
    }

    public static ArrayList<Title> searchTitles(Context context, boolean comic, String sourceSite,
                                                 String query, int offset, int limit) {
        String name = normalizeName(query);
        if(name.length() == 0)
            return new ArrayList<>();
        SQLiteDatabase db = openReadOnly(context);
        if(db == null)
            return new ArrayList<>();
        ArrayList<Title> result = new ArrayList<>();
        Cursor cursor = null;
        String kind = kind(comic);
        String site = normalizeSourceSite(sourceSite);
        try {
            cursor = db.rawQuery(
                    "SELECT id,path,name,thumb,release FROM classification_titles " +
                            "WHERE kind=? AND source_site=? AND normalized_name LIKE ? " +
                            "ORDER BY CASE WHEN normalized_name=? THEN 0 " +
                            "WHEN normalized_name LIKE ? THEN 1 ELSE 2 END,id LIMIT ? OFFSET ?",
                    new String[]{
                            kind,
                            site,
                            "%" + name + "%",
                            name,
                            name + "%",
                            String.valueOf(limit > 0 ? limit : 1000000),
                            String.valueOf(Math.max(0, offset))
                    });
            int baseMode = comic ? base_comic : base_webtoon;
            while(cursor.moveToNext()) {
                int id = cursor.getInt(0);
                result.add(titleFromClassificationRow(baseMode, site, id, cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4),
                        readTags(db, kind, site, id)));
            }
        } catch (Exception e) {
            result.clear();
        } finally {
            if(cursor != null)
                cursor.close();
        }
        return result;
    }

    public static int getTitleCount(Context context, boolean comic, String sourceSite) {
        SQLiteDatabase db = openReadOnly(context);
        if(db == null)
            return 0;
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT COUNT(*) FROM classification_titles WHERE kind=? AND source_site=?",
                    new String[]{kind(comic), normalizeSourceSite(sourceSite)});
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if(cursor != null)
                cursor.close();
        }
    }

    public static int getGenreCount(Context context, boolean comic, String genre) {
        return getGenreCount(context, comic, "wfwf", genre);
    }

    public static int getGenreCount(Context context, boolean comic, String sourceSite, String genre) {
        String tag = normalizeTag(genre);
        if(tag.length() == 0)
            return 0;
        String kind = kind(comic);
        String site = normalizeSourceSite(sourceSite);
        String cacheKey = site + ":" + kind + ":count:" + tag;
        synchronized (lock) {
            Integer cached = countCache.get(cacheKey);
            if(cached != null)
                return cached;
        }
        SQLiteDatabase db = openReadOnly(context);
        if(db == null)
            return 0;
        int count = 0;
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT COUNT(*) FROM classification_title_tags WHERE kind=? AND source_site=? AND normalized_tag=?",
                    new String[]{kind, site, tag});
            if(cursor.moveToFirst())
                count = cursor.getInt(0);
        } catch (Exception e) {
            count = 0;
        } finally {
            if(cursor != null)
                cursor.close();
        }
        synchronized (lock) {
            countCache.put(cacheKey, count);
        }
        return count;
    }

    public static String installedVersion(Context context) {
        SQLiteDatabase db = openReadOnly(context);
        if(db == null)
            return "";
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT value FROM classification_meta WHERE key='version' LIMIT 1", null);
            if(cursor.moveToFirst())
                return cursor.getString(0);
        } catch (Exception ignored) {
        } finally {
            if(cursor != null)
                cursor.close();
        }
        return "";
    }

    public static void requestUpdateIfMissing(Context context) {
        cleanupLegacyFiles(context);
        if(openReadOnly(context) == null)
            ClassificationDbUpdater.start(context);
    }

    public static void cleanupLegacyFiles(Context context) {
        File dir = dbDir(context);
        if(dir == null || !dir.exists())
            return;
        String[] legacyFiles = {
                "webtoon-classification.json",
                "comic-classification.json",
                "webtoon-classification.index.jsonl",
                "comic-classification.index.jsonl"
        };
        for(String name : legacyFiles) {
            deleteIfExists(new File(dir, name));
            deleteIfExists(new File(dir, name + ".tmp"));
            deleteIfExists(new File(dir, name + ".bak"));
        }
    }

    static void ensureSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS classification_titles ("
                + "kind TEXT NOT NULL,"
                + "source_site TEXT NOT NULL DEFAULT 'wfwf',"
                + "id INTEGER NOT NULL,"
                + "path TEXT,"
                + "name TEXT NOT NULL,"
                + "normalized_name TEXT NOT NULL,"
                + "thumb TEXT,"
                + "release TEXT,"
                + "updated_at INTEGER DEFAULT 0,"
                + "PRIMARY KEY(kind,source_site,id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS classification_title_tags ("
                + "kind TEXT NOT NULL,"
                + "source_site TEXT NOT NULL DEFAULT 'wfwf',"
                + "id INTEGER NOT NULL,"
                + "tag TEXT NOT NULL,"
                + "normalized_tag TEXT NOT NULL,"
                + "PRIMARY KEY(kind,source_site,id,normalized_tag))");
        db.execSQL("CREATE TABLE IF NOT EXISTS classification_meta (key TEXT PRIMARY KEY, value TEXT)");
        ensureColumn(db, "classification_titles", "source_site", "TEXT NOT NULL DEFAULT 'wfwf'");
        ensureColumn(db, "classification_titles", "path", "TEXT");
        ensureColumn(db, "classification_title_tags", "source_site", "TEXT NOT NULL DEFAULT 'wfwf'");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_classification_tag ON classification_title_tags(kind,source_site,normalized_tag,id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_classification_title_id ON classification_titles(kind,source_site,id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_classification_title_name ON classification_titles(kind,source_site,normalized_name)");
    }

    static String normalizeName(String value) {
        if(value == null)
            return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }

    static String normalizeTag(String value) {
        if(value == null)
            return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static SQLiteDatabase openReadOnly(Context context) {
        if(context == null)
            return null;
        File file = dbFile(context.getApplicationContext());
        if(file == null || !file.exists() || file.length() <= 0) {
            installSeedDatabaseIfAvailable(context.getApplicationContext(), file);
            if(file == null || !file.exists() || file.length() <= 0) {
                ClassificationDbUpdater.start(context);
                return null;
            }
        }
        String path = file.getAbsolutePath();
        synchronized (lock) {
            if(database != null && path.equals(openedPath) && database.isOpen())
                return database;
            closeLocked();
            try {
                database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
                openedPath = path;
                return database;
            } catch (SQLiteException e) {
                closeLocked();
                ClassificationDbUpdater.start(context);
                return null;
            }
        }
    }

    private static void installSeedDatabaseIfAvailable(Context context, File target) {
        if(context == null || target == null)
            return;
        synchronized (lock) {
            if(target.exists() && target.length() > 0)
                return;
            File dir = target.getParentFile();
            if(dir == null || (!dir.exists() && !dir.mkdirs()))
                return;
            File tmp = new File(dir, DB_FILE_NAME + ".seed");
            deleteIfExists(tmp);
            try(InputStream input = context.getAssets().open(SEED_DB_ASSET_NAME);
                FileOutputStream output = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                while(true) {
                    int read = input.read(buffer);
                    if(read < 0)
                        break;
                    output.write(buffer, 0, read);
                }
                output.flush();
                if(!isReadableSeedDatabase(tmp)) {
                    deleteIfExists(tmp);
                    return;
                }
                deleteIfExists(target);
                if(!tmp.renameTo(target)) {
                    copySeedFile(tmp, target);
                    deleteIfExists(tmp);
                }
            } catch (Exception ignored) {
                deleteIfExists(tmp);
            }
        }
    }

    private static boolean isReadableSeedDatabase(File file) {
        if(file == null || !file.exists() || file.length() <= 0)
            return false;
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = db.rawQuery("SELECT COUNT(*) FROM classification_titles", null);
            return cursor.moveToFirst() && cursor.getInt(0) > 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if(cursor != null)
                cursor.close();
            if(db != null)
                db.close();
        }
    }

    private static void copySeedFile(File source, File target) throws Exception {
        try(InputStream input = new java.io.FileInputStream(source);
            FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            while(true) {
                int read = input.read(buffer);
                if(read < 0)
                    break;
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static void closeLocked() {
        try {
            if(database != null)
                database.close();
        } catch (Exception ignored) {
        }
        database = null;
        openedPath = "";
    }

    private static void deleteIfExists(File file) {
        try {
            if(file != null && file.exists())
                file.delete();
        } catch (Exception ignored) {
        }
    }

    private static Entry readEntry(SQLiteDatabase db, String kind, String site, Cursor cursor) {
        if(cursor == null || !cursor.moveToFirst())
            return null;
        int id = cursor.getInt(0);
        String sourceSite = cursor.getColumnCount() > 4 ? cursor.getString(4) : site;
        String path = cursor.getColumnCount() > 5 ? cursor.getString(5) : "";
        return new Entry(id, sourceSite, path, cursor.getString(1), cursor.getString(2), cursor.getString(3), readTags(db, kind, sourceSite, id));
    }

    private static Title titleFromClassificationRow(int baseMode, String sourceSite, int id, String path,
                                                    String name, String thumb, String release,
                                                    List<String> tags) {
        Title title = new Title(name, thumb, "", tags, release, id, baseMode);
        title.setSourceSite(sourceSite);
        title.setPath(path);
        return title;
    }

    private static ArrayList<String> readTags(SQLiteDatabase db, String kind, String sourceSite, int id) {
        ArrayList<String> tags = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT tag FROM classification_title_tags WHERE kind=? AND source_site=? AND id=? ORDER BY rowid",
                    new String[]{kind, normalizeSourceSite(sourceSite), String.valueOf(id)});
            while(cursor.moveToNext())
                tags.add(cursor.getString(0));
        } catch (Exception ignored) {
            tags.clear();
        } finally {
            if(cursor != null)
                cursor.close();
        }
        return tags;
    }

    private static String kind(boolean comic) {
        return comic ? "comic" : "webtoon";
    }

    private static String normalizeSourceSite(String sourceSite) {
        String normalized = sourceSite == null ? "" : sourceSite.trim().toLowerCase(Locale.ROOT);
        return "ntk".equals(normalized) ? "ntk" : "wfwf";
    }

    private static void ensureColumn(SQLiteDatabase db, String table, String column, String definition) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while(cursor.moveToNext())
                if(column.equalsIgnoreCase(cursor.getString(1)))
                    return;
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (Exception ignored) {
        } finally {
            if(cursor != null)
                cursor.close();
        }
    }

    private static ArrayList<Title> cloneTitles(ArrayList<Title> source) {
        ArrayList<Title> result = new ArrayList<>();
        if(source == null)
            return result;
        for(Title title : source)
            result.add(title == null ? null : new Title(title));
        return result;
    }

    private static <K, V> LinkedHashMap<K, V> lruMap() {
        return new LinkedHashMap<K, V>(CACHE_LIMIT, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > CACHE_LIMIT;
            }
        };
    }

    public static final class Entry {
        public final int id;
        public final String sourceSite;
        public final String path;
        public final String name;
        public final String thumb;
        public final String release;
        public final ArrayList<String> tags;

        Entry(int id, String sourceSite, String path, String name, String thumb, String release, List<String> tags) {
            this.id = id;
            this.sourceSite = normalizeSourceSite(sourceSite);
            this.path = path == null ? "" : path;
            this.name = name == null ? "" : name;
            this.thumb = thumb == null ? "" : thumb;
            this.release = release == null ? "" : release;
            this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        }

        Entry copy() {
            return new Entry(id, sourceSite, path, name, thumb, release, tags);
        }
    }
}
