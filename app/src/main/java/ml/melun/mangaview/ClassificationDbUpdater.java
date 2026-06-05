package ml.melun.mangaview;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import ml.melun.mangaview.report.CrashReporter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class ClassificationDbUpdater extends Worker {
    private static final String TAG = "ViewerPerf";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final long FAILURE_RETRY_MS = 60 * 60 * 1000L;
    private static final int MAX_PATCH_CHAIN = 8;
    private static final String PREF = "classificationDbUpdater";
    private static final String WORK_NAME = "classification-db-update";
    private static final String MANIFEST_URL = "https://github.com/ad2das/mangaviewer/releases/latest/download/classification-manifest.json";
    private static final AtomicBoolean updateQueued = new AtomicBoolean(false);
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build();

    public ClassificationDbUpdater(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void start(Context context) {
        if(context == null || !updateQueued.compareAndSet(false, true))
            return;
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ClassificationDbUpdater.class).build();
            MainApplication.getWorkManager(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request);
        } catch (Exception e) {
            updateQueued.set(false);
            ml.melun.mangaview.runtime.AppDispatchers.runIo(() -> updateInBackground(context));
        }
    }

    public static void updateInBackground(Context context) {
        if(context == null)
            return;
        try {
            new UpdateSession(context.getApplicationContext()).run();
        } finally {
            updateQueued.set(false);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            new UpdateSession(getApplicationContext()).run();
            return Result.success();
        } catch (Exception e) {
            CrashReporter.record(e);
            return Result.retry();
        } finally {
            updateQueued.set(false);
        }
    }

    private static final class UpdateSession {
        private final Context context;
        private final SharedPreferences pref;

        UpdateSession(Context context) {
            this.context = context;
            this.pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }

        void run() {
            ClassificationDbStore.cleanupLegacyFiles(context);
            long now = System.currentTimeMillis();
            long lastChecked = pref.getLong("checkedAt", 0);
            File currentDb = ClassificationDbStore.dbFile(context);
            boolean missingDatabase = currentDb == null || !currentDb.exists() || currentDb.length() <= 0;
            if(!missingDatabase && now - lastChecked < CHECK_INTERVAL_MS) {
                Log.d(TAG, "classification_db_skip_recent version=" + ClassificationDbStore.installedVersion(context));
                return;
            }
            SharedPreferences.Editor editor = pref.edit().putLong("checkedAt", now);
            try {
                Manifest manifest = fetchManifest();
                if(manifest == null || manifest.version.length() == 0) {
                    Log.d(TAG, "classification_db_manifest_empty missing=" + missingDatabase);
                    editor.apply();
                    return;
                }
                String currentVersion = ClassificationDbStore.installedVersion(context);
                Log.d(TAG, "classification_db_manifest version=" + manifest.version
                        + ",current=" + currentVersion
                        + ",missing=" + missingDatabase
                        + ",base=" + manifest.baseUrl);
                if(manifest.version.equals(currentVersion)) {
                    editor.putString("version", currentVersion).apply();
                    return;
                }
                boolean updated = false;
                List<Patch> chain = patchChain(currentVersion, manifest);
                if(currentVersion.length() > 0 && chain.size() > 0 && chain.size() <= MAX_PATCH_CHAIN)
                    updated = applyPatchChain(chain, manifest.version);
                if(!updated)
                    updated = installBase(manifest);
                if(updated) {
                    Log.d(TAG, "classification_db_updated version=" + manifest.version);
                    editor.putString("version", manifest.version);
                    ClassificationDbStore.invalidate();
                } else {
                    Log.d(TAG, "classification_db_update_failed version=" + manifest.version);
                    long retryCheckedAt = System.currentTimeMillis() - CHECK_INTERVAL_MS + FAILURE_RETRY_MS;
                    editor.putLong("checkedAt", retryCheckedAt);
                }
                editor.apply();
            } catch (IOException e) {
                Log.d(TAG, "classification_db_io_error " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
                long retryCheckedAt = System.currentTimeMillis() - CHECK_INTERVAL_MS + FAILURE_RETRY_MS;
                editor.putLong("checkedAt", retryCheckedAt).apply();
            } catch (Exception e) {
                Log.d(TAG, "classification_db_error " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
                CrashReporter.record(e);
                editor.apply();
            }
        }

        private Manifest fetchManifest() throws Exception {
            Request request = new Request.Builder()
                    .url(MANIFEST_URL)
                    .header("User-Agent", "MangaView")
                    .header("Cache-Control", "no-cache")
                    .build();
            try(Response response = CLIENT.newCall(request).execute()) {
                if(!response.isSuccessful() || response.body() == null) {
                    Log.d(TAG, "classification_db_manifest_http code=" + response.code());
                    return null;
                }
                JSONObject json = new JSONObject(response.body().string());
                Manifest manifest = new Manifest();
                manifest.version = json.optString("version", "");
                manifest.baseUrl = resolveUrl(MANIFEST_URL, json.optString("baseUrl", ""));
                manifest.baseSha256 = json.optString("baseSha256", "").toLowerCase(Locale.ROOT);
                manifest.minSupportedVersion = json.optString("minSupportedVersion", "");
                JSONArray patches = json.optJSONArray("patches");
                if(patches != null)
                    for(int i = 0; i < patches.length(); i++) {
                        JSONObject item = patches.optJSONObject(i);
                        if(item == null)
                            continue;
                        Patch patch = new Patch();
                        patch.from = item.optString("from", "");
                        patch.to = item.optString("to", "");
                        patch.url = resolveUrl(MANIFEST_URL, item.optString("url", ""));
                        patch.sha256 = item.optString("sha256", "").toLowerCase(Locale.ROOT);
                        if(patch.from.length() > 0 && patch.to.length() > 0 && patch.url.length() > 0)
                            manifest.patches.add(patch);
                    }
                return manifest;
            }
        }

        private boolean installBase(Manifest manifest) {
            if(manifest.baseUrl.length() == 0 || manifest.baseSha256.length() == 0) {
                Log.d(TAG, "classification_db_base_missing_url_or_sha");
                return false;
            }
            File dir = ClassificationDbStore.dbDir(context);
            if(dir == null || (!dir.exists() && !dir.mkdirs())) {
                Log.d(TAG, "classification_db_base_dir_failed");
                return false;
            }
            File target = ClassificationDbStore.dbFile(context);
            File tmp = new File(dir, ClassificationDbStore.DB_FILE_NAME + ".download");
            deleteIfExists(tmp);
            try {
                String sha = downloadGzipToFile(manifest.baseUrl, tmp);
                if(!manifest.baseSha256.equalsIgnoreCase(sha)) {
                    Log.d(TAG, "classification_db_base_sha_mismatch expected=" + manifest.baseSha256
                            + ",actual=" + sha);
                    deleteIfExists(tmp);
                    return false;
                }
                if(!validateDatabase(tmp, manifest.version)) {
                    Log.d(TAG, "classification_db_base_validate_failed version=" + manifest.version);
                    deleteIfExists(tmp);
                    return false;
                }
                boolean replaced = replaceAtomically(target, tmp);
                Log.d(TAG, "classification_db_base_replace result=" + replaced
                        + ",bytes=" + (target == null || !target.exists() ? 0 : target.length()));
                return replaced;
            } catch (Exception e) {
                Log.d(TAG, "classification_db_base_error " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
                CrashReporter.record(e);
                deleteIfExists(tmp);
                return false;
            }
        }

        private boolean applyPatchChain(List<Patch> patches, String targetVersion) {
            File current = ClassificationDbStore.dbFile(context);
            File dir = ClassificationDbStore.dbDir(context);
            if(current == null || dir == null || !current.exists())
                return false;
            File work = new File(dir, ClassificationDbStore.DB_FILE_NAME + ".patching");
            File patchFile = new File(dir, "classification-patch.sqlite");
            deleteIfExists(work);
            deleteIfExists(patchFile);
            try {
                copyFile(current, work);
                for(Patch patch : patches) {
                    String sha = downloadGzipToFile(patch.url, patchFile);
                    if(patch.sha256.length() > 0 && !patch.sha256.equalsIgnoreCase(sha))
                        return false;
                    applyPatch(work, patchFile, patch.to);
                    deleteIfExists(patchFile);
                }
                if(!validateDatabase(work, targetVersion))
                    return false;
                return replaceAtomically(current, work);
            } catch (Exception e) {
                CrashReporter.record(e);
                return false;
            } finally {
                deleteIfExists(patchFile);
                deleteIfExists(work);
            }
        }

        private void applyPatch(File target, File patchFile, String version) {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(target.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            String patchPath = patchFile.getAbsolutePath().replace("'", "''");
            try {
                ClassificationDbStore.ensureSchema(db);
                db.execSQL("ATTACH DATABASE '" + patchPath + "' AS patch");
                db.beginTransaction();
                try {
                    db.execSQL("DELETE FROM classification_title_tags WHERE EXISTS ("
                            + "SELECT 1 FROM patch.classification_titles p "
                            + "WHERE p.kind=classification_title_tags.kind "
                            + "AND p.source_site=classification_title_tags.source_site "
                            + "AND p.id=classification_title_tags.id)");
                    db.execSQL("INSERT OR REPLACE INTO classification_titles(kind,source_site,id,path,name,normalized_name,thumb,release,updated_at) "
                            + "SELECT kind,source_site,id,path,name,normalized_name,thumb,release,updated_at FROM patch.classification_titles");
                    db.execSQL("INSERT OR REPLACE INTO classification_title_tags(kind,source_site,id,tag,normalized_tag) "
                            + "SELECT kind,source_site,id,tag,normalized_tag FROM patch.classification_title_tags");
                    if(tableExists(db, "patch", "classification_deleted_titles")) {
                        db.execSQL("DELETE FROM classification_title_tags WHERE EXISTS ("
                                + "SELECT 1 FROM patch.classification_deleted_titles d "
                                + "WHERE d.kind=classification_title_tags.kind "
                                + "AND d.source_site=classification_title_tags.source_site "
                                + "AND d.id=classification_title_tags.id)");
                        db.execSQL("DELETE FROM classification_titles WHERE EXISTS ("
                                + "SELECT 1 FROM patch.classification_deleted_titles d "
                                + "WHERE d.kind=classification_titles.kind "
                                + "AND d.source_site=classification_titles.source_site "
                                + "AND d.id=classification_titles.id)");
                    }
                    db.execSQL("INSERT OR REPLACE INTO classification_meta(key,value) VALUES('version',?)", new Object[]{version});
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    db.execSQL("DETACH DATABASE patch");
                }
            } finally {
                db.close();
            }
        }

        private boolean validateDatabase(File file, String version) {
            SQLiteDatabase db = null;
            try {
                db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
                ClassificationDbStore.ensureSchema(db);
                db.execSQL("INSERT OR REPLACE INTO classification_meta(key,value) VALUES('version',?)", new Object[]{version});
                CursorCount titles = count(db, "classification_titles");
                CursorCount tags = count(db, "classification_title_tags");
                return titles.count > 0 && tags.count > 0;
            } catch (Exception e) {
                return false;
            } finally {
                if(db != null)
                    db.close();
            }
        }

        private CursorCount count(SQLiteDatabase db, String table) {
            android.database.Cursor cursor = null;
            try {
                cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null);
                return new CursorCount(cursor.moveToFirst() ? cursor.getInt(0) : 0);
            } finally {
                if(cursor != null)
                    cursor.close();
            }
        }

        private List<Patch> patchChain(String currentVersion, Manifest manifest) {
            ArrayList<Patch> chain = new ArrayList<>();
            String version = currentVersion == null ? "" : currentVersion;
            while(!version.equals(manifest.version) && chain.size() <= MAX_PATCH_CHAIN) {
                Patch next = null;
                for(Patch patch : manifest.patches)
                    if(version.equals(patch.from)) {
                        next = patch;
                        break;
                    }
                if(next == null)
                    break;
                chain.add(next);
                version = next.to;
            }
            return version.equals(manifest.version) ? chain : new ArrayList<>();
        }

        private String downloadGzipToFile(String url, File target) throws Exception {
            Request request = new Request.Builder().url(url).header("User-Agent", "MangaView").build();
            try(Response response = CLIENT.newCall(request).execute()) {
                ResponseBody body = response.body();
                if(!response.isSuccessful() || body == null)
                    throw new IOException("Classification DB download failed: " + response.code());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try(InputStream raw = body.byteStream();
                    GZIPInputStream gzip = new GZIPInputStream(raw);
                    DigestInputStream input = new DigestInputStream(gzip, digest);
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
                return hex(digest.digest());
            }
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String schema, String table) {
        android.database.Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT name FROM " + schema + ".sqlite_master WHERE type='table' AND name=?",
                    new String[]{table});
            return cursor.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if(cursor != null)
                cursor.close();
        }
    }

    private static boolean replaceAtomically(File target, File tmp) {
        if(target == null || tmp == null || !tmp.exists())
            return false;
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        deleteIfExists(backup);
        if(target.exists() && !target.renameTo(backup))
            return false;
        if(tmp.renameTo(target)) {
            deleteIfExists(backup);
            return true;
        }
        if(backup.exists())
            backup.renameTo(target);
        return false;
    }

    private static void copyFile(File source, File target) throws IOException {
        try(FileInputStream input = new FileInputStream(source);
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

    private static void deleteIfExists(File file) {
        try {
            if(file != null && file.exists())
                file.delete();
        } catch (Exception ignored) {
        }
    }

    private static String resolveUrl(String base, String value) {
        try {
            if(value == null || value.trim().length() == 0)
                return "";
            URI uri = new URI(value.trim());
            if(uri.isAbsolute())
                return uri.toString();
            return new URI(base).resolve(uri).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for(byte b : bytes)
            builder.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return builder.toString();
    }

    private static final class Manifest {
        String version = "";
        String baseUrl = "";
        String baseSha256 = "";
        String minSupportedVersion = "";
        final ArrayList<Patch> patches = new ArrayList<>();
    }

    private static final class Patch {
        String from = "";
        String to = "";
        String url = "";
        String sha256 = "";
    }

    private static final class CursorCount {
        final int count;

        CursorCount(int count) {
            this.count = count;
        }
    }
}
