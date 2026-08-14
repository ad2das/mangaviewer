package ml.melun.mangaview;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.repository.PreferenceStore;

public class FirebaseSyncManager {
    private static final String TAG = "FirebaseSync";
    private static final String META_PREF = "firebaseSyncMeta";
    private static final String STATE_DOC = "state";
    private static final long SYNC_DEBOUNCE_MS = 1200L;

    private final Context appContext;
    private final Preference preference;
    private final SharedPreferences metaPref;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final ExecutorService syncDataExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "firebase-sync-data");
        thread.setDaemon(true);
        return thread;
    });
    private final Object syncWorkLock = new Object();
    private final SyncGenerationGate syncGenerationGate = new SyncGenerationGate();
    private FirebaseFirestore firestore;
    private FirebaseAuth auth;
    private FirebaseAuth.AuthStateListener authStateListener;
    private volatile Future<?> activeSyncWork;
    private volatile long activeSyncWorkGeneration;
    private volatile boolean syncing;
    private volatile boolean pendingLocalUpload;
    private volatile String activeSyncUid = "";

    private final Runnable uploadRunnable = this::uploadCurrentState;

    public interface SyncCallback {
        void onComplete(boolean success, String message);
    }

    public FirebaseSyncManager(Context context, Preference preference) {
        appContext = context.getApplicationContext();
        this.preference = preference;
        metaPref = appContext.getSharedPreferences(META_PREF, Context.MODE_PRIVATE);
        try {
            FirebaseApp app;
            if(FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseOptions options = FirebaseOptions.fromResource(appContext);
                app = options == null ? null : FirebaseApp.initializeApp(appContext, options);
            } else {
                app = FirebaseApp.getInstance();
            }
            if(app != null) {
                auth = FirebaseAuth.getInstance(app);
                firestore = FirebaseFirestore.getInstance(app);
                authStateListener = firebaseAuth -> invalidateSyncForChangedIdentity(
                        uidOf(firebaseAuth == null ? null : firebaseAuth.getCurrentUser()));
                auth.addAuthStateListener(authStateListener);
            }
        } catch (Exception e) {
            auth = null;
            firestore = null;
            authStateListener = null;
        }
        preference.setFirebaseSyncManager(this);
    }

    public boolean isAvailable() {
        return auth != null && firestore != null;
    }

    public boolean isSignedIn() {
        return currentUser() != null;
    }

    public void onLocalPreferencesChanged(String scope) {
        if(!isSignedIn())
            return;
        markLocalUpdated(scope);
        if(syncing) {
            pendingLocalUpload = true;
            return;
        }
        handler.removeCallbacks(uploadRunnable);
        handler.postDelayed(uploadRunnable, SYNC_DEBOUNCE_MS);
    }

    public void syncAfterSignIn(Runnable afterSync) {
        syncAfterSignIn((success, message) -> {
            if(afterSync != null)
                afterSync.run();
        });
    }

    public void syncAfterSignIn(SyncCallback afterSync) {
        if(!isSignedIn()) {
            deliver(afterSync, false, "로그인이 필요합니다");
            return;
        }
        downloadAndMerge(afterSync);
    }

    public void uploadCurrentState() {
        uploadCurrentState((SyncCallback)null);
    }

    private void uploadCurrentState(Runnable afterUpload) {
        uploadCurrentState((success, message) -> {
            if(afterUpload != null)
                afterUpload.run();
        });
    }

    private void uploadCurrentState(SyncCallback afterUpload) {
        FirebaseUser user = currentUser();
        if(user == null) {
            deliver(afterUpload, false, "로그인이 필요합니다");
            return;
        }
        if(firestore == null) {
            deliver(afterUpload, false, "Firestore 설정이 필요합니다");
            return;
        }
        Map<String, Object> data = exportState();
        Log.i(TAG, "upload_start uid=" + user.getUid());
        stateDoc(user.getUid()).set(data, SetOptions.merge())
                .addOnSuccessListener(unused -> {
                    Log.i(TAG, "upload_success uid=" + user.getUid());
                    deliver(afterUpload, true, null);
                })
                .addOnFailureListener(e -> {
                    String message = errorMessage("업로드 실패", e);
                    Log.w(TAG, "upload_failed " + message, e);
                    deliver(afterUpload, false, message);
                });
    }

    private void downloadAndMerge(SyncCallback afterSync) {
        FirebaseUser user = currentUser();
        if(user == null) {
            deliver(afterSync, false, "로그인이 필요합니다");
            return;
        }
        if(!isDeviceOnline()) {
            deliver(afterSync, false, "인터넷 연결이 필요합니다");
            return;
        }
        if(firestore == null) {
            deliver(afterSync, false, "Firestore 설정이 필요합니다");
            return;
        }
        String uid = user.getUid();
        long generation = beginSync(uid);
        Log.i(TAG, "download_start uid=" + uid + " generation=" + generation);
        stateDoc(uid).get(Source.SERVER)
                .addOnSuccessListener(snapshot -> handleDownloadedState(
                        generation,
                        uid,
                        snapshot != null && snapshot.exists(),
                        snapshot == null ? null : snapshot.getData(),
                        afterSync))
                .addOnFailureListener(e -> completeSyncFailure(
                        generation, uid, afterSync, "다운로드 실패", e));
    }

    private Map<String, Object> exportState() {
        Map<String, Object> data = new HashMap<>();
        data.put("recentJson", gson.toJson(preference.getRecentForSync()));
        data.put("favoriteJson", gson.toJson(preference.getFavorite()));
        data.put("bookmarkJson", preference.getBookmarkObject().toString());
        data.put("pageBookmarkJson", preference.getViewerBookmarkObject().toString());
        data.put("settings", preference.exportSyncSettings());
        data.put("recentUpdatedAt", exportUpdatedAt("recent", !preference.getRecentForSync().isEmpty()));
        data.put("favoriteUpdatedAt", exportUpdatedAt("favorite", !preference.getFavorite().isEmpty()));
        data.put("bookmarkUpdatedAt", exportUpdatedAt("bookmark", preference.getBookmarkObject().length() > 0));
        data.put("pageBookmarkUpdatedAt", exportUpdatedAt("pageBookmark", preference.getViewerBookmarkObject().length() > 0));
        data.put("settingsUpdatedAt", exportUpdatedAt("settings", true));
        data.put("updatedAt", System.currentTimeMillis());
        return data;
    }

    private void handleDownloadedState(long generation,
                                       String uid,
                                       boolean exists,
                                       Map<String, Object> remote,
                                       SyncCallback afterSync) {
        if(!isSyncWorkCurrent(generation, uid)) {
            finishSilentlyIfIdentityChanged(generation, uid);
            return;
        }
        if(!exists) {
            Log.i(TAG, "download_empty uid=" + uid + " generation=" + generation);
            postGenerationUpload(generation, uid, afterSync);
            return;
        }
        Map<String, Object> detachedRemote = remote == null ? null : new HashMap<>(remote);
        Log.i(TAG, "download_success uid=" + uid
                + " generation=" + generation
                + " hasPayload=" + hasAnyRemotePayload(detachedRemote));
        if(detachedRemote == null) {
            postGenerationUpload(generation, uid, afterSync);
            return;
        }
        submitSyncWork(generation, uid, afterSync, () -> {
            ParsedRemoteState parsed = parseRemoteState(detachedRemote);
            if(!isSyncWorkCurrent(generation, uid))
                return;
            handler.post(() -> prepareBackfillOnMain(generation, uid, parsed, afterSync));
        });
    }

    private ParsedRemoteState parseRemoteState(Map<String, Object> remote) {
        Object settingsValue = remote.get("settings");
        Map<String, Object> settings = settingsValue instanceof Map
                ? new HashMap<>((Map<String, Object>)settingsValue)
                : null;
        return new ParsedRemoteState(
                remote,
                readTitleList(remote, "recentJson"),
                readTitleList(remote, "favoriteJson"),
                jsonObject(readString(remote, "bookmarkJson", "{}")),
                jsonObject(readString(remote, "pageBookmarkJson", "{}")),
                settings);
    }

    private void prepareBackfillOnMain(long generation,
                                       String uid,
                                       ParsedRemoteState parsed,
                                       SyncCallback afterSync) {
        if(!isSyncWorkCurrent(generation, uid)) {
            finishSilentlyIfIdentityChanged(generation, uid);
            return;
        }
        preference.runWithoutSync(() -> applyRemoteStateOnMain(parsed));
        long capturedRecentUpdatedAt = getLocalUpdatedAt("recent");
        long capturedLocalDataVersion = preference.getLocalDataVersion();
        List<MTitle> detachedRecents = preference.prepareRecentProgressBackfillSnapshot(
                preference.getRecentForSync());
        PreparedRemoteMerge prepared = new PreparedRemoteMerge(
                capturedRecentUpdatedAt,
                capturedLocalDataVersion,
                detachedRecents);
        submitSyncWork(generation, uid, afterSync, () -> runPreparedBackfill(
                generation, uid, prepared, afterSync));
    }

    private void runPreparedBackfill(long generation,
                                     String uid,
                                     PreparedRemoteMerge prepared,
                                     SyncCallback afterSync) {
        Preference.RecentProgressBackfillResult backfill =
                Preference.backfillRecentProgressSnapshot(
                        MainApplication.getHttpClient(),
                        prepared.detachedRecents,
                        30,
                        () -> Thread.currentThread().isInterrupted()
                                || !isSyncWorkCurrent(generation, uid));
        if(backfill.cancelled || !isSyncWorkCurrent(generation, uid))
            return;
        handler.post(() -> publishMergeOnMain(
                generation, uid, prepared, backfill, afterSync));
    }

    private void applyRemoteStateOnMain(ParsedRemoteState parsed) {
        if(shouldMerge(parsed.remote, "recent", "recentJson", "[]")) {
            PreferenceStore.setRecents(parsed.recents);
            setLocalUpdatedAt("recent", remoteTime(parsed.remote, "recentUpdatedAt"));
        }
        if(shouldMerge(parsed.remote, "favorite", "favoriteJson", "[]")) {
            PreferenceStore.setFavorites(parsed.favorites);
            setLocalUpdatedAt("favorite", remoteTime(parsed.remote, "favoriteUpdatedAt"));
        }
        if(shouldMerge(parsed.remote, "bookmark", "bookmarkJson", "{}")) {
            preference.setBookmarks(parsed.bookmarks);
            setLocalUpdatedAt("bookmark", remoteTime(parsed.remote, "bookmarkUpdatedAt"));
        }
        if(shouldMerge(parsed.remote, "pageBookmark", "pageBookmarkJson", "{}")) {
            preference.setViewerBookmarks(parsed.pageBookmarks);
            setLocalUpdatedAt("pageBookmark", remoteTime(parsed.remote, "pageBookmarkUpdatedAt"));
        }
        if(remoteTime(parsed.remote, "settingsUpdatedAt") > getLocalUpdatedAt("settings")) {
            if(parsed.settings != null)
                preference.importSyncSettings(parsed.settings);
            setLocalUpdatedAt("settings", remoteTime(parsed.remote, "settingsUpdatedAt"));
        }
    }

    private void publishMergeOnMain(long generation,
                                    String uid,
                                    PreparedRemoteMerge prepared,
                                    Preference.RecentProgressBackfillResult backfill,
                                    SyncCallback afterSync) {
        if(!isSyncWorkCurrent(generation, uid)) {
            finishSilentlyIfIdentityChanged(generation, uid);
            return;
        }
        if(backfill.changed
                && prepared.capturedRecentUpdatedAt == getLocalUpdatedAt("recent")
                && prepared.capturedLocalDataVersion == preference.getLocalDataVersion()) {
            preference.runWithoutSync(() -> {
                PreferenceStore.setRecents(backfill.titles);
                markLocalUpdated("recent");
            });
        }
        startGenerationUpload(generation, uid, afterSync);
    }

    private void postGenerationUpload(long generation, String uid, SyncCallback afterSync) {
        handler.post(() -> startGenerationUpload(generation, uid, afterSync));
    }

    private void startGenerationUpload(long generation, String uid, SyncCallback afterSync) {
        if(!isSyncWorkCurrent(generation, uid)) {
            finishSilentlyIfIdentityChanged(generation, uid);
            return;
        }
        uploadCurrentState((success, message) -> completeGenerationUpload(
                generation, uid, afterSync, success, message));
    }

    private void completeGenerationUpload(long generation,
                                          String uid,
                                          SyncCallback afterSync,
                                          boolean success,
                                          String message) {
        if(!syncGenerationGate.isCurrent(generation))
            return;
        if(!uidStillCurrent(uid)) {
            finishSyncSilently(generation);
            return;
        }
        finishSync(generation, afterSync, success, message);
    }

    private void completeSyncFailure(long generation,
                                     String uid,
                                     SyncCallback afterSync,
                                     String prefix,
                                     Exception error) {
        String message = errorMessage(prefix, error);
        Log.w(TAG, "sync_failed generation=" + generation + " " + message, error);
        handler.post(() -> {
            if(!syncGenerationGate.isCurrent(generation))
                return;
            if(!uidStillCurrent(uid)) {
                finishSyncSilently(generation);
                return;
            }
            finishSync(generation, afterSync, false, message);
        });
    }

    private void submitSyncWork(long generation,
                                String uid,
                                SyncCallback afterSync,
                                Runnable work) {
        if(!isSyncWorkCurrent(generation, uid))
            return;
        synchronized (syncWorkLock) {
            if(!isSyncWorkCurrent(generation, uid))
                return;
            Future<?> previous = activeSyncWork;
            if(previous != null && !previous.isDone()
                    && activeSyncWorkGeneration != generation)
                previous.cancel(true);
            activeSyncWork = syncDataExecutor.submit(() -> {
                if(!isSyncWorkCurrent(generation, uid))
                    return;
                try {
                    work.run();
                } catch (Exception e) {
                    completeSyncFailure(generation, uid, afterSync, "동기화 실패", e);
                }
            });
            activeSyncWorkGeneration = generation;
        }
    }

    private long beginSync(String uid) {
        long generation = syncGenerationGate.begin();
        cancelActiveSyncWork();
        activeSyncUid = uid == null ? "" : uid;
        pendingLocalUpload = false;
        syncing = true;
        handler.removeCallbacks(uploadRunnable);
        return generation;
    }

    private void finishSync(long generation,
                            SyncCallback callback,
                            boolean success,
                            String message) {
        if(!syncGenerationGate.isCurrent(generation))
            return;
        boolean uploadAgain = pendingLocalUpload;
        pendingLocalUpload = false;
        syncing = false;
        activeSyncUid = "";
        syncGenerationGate.invalidate();
        clearFinishedSyncWork();
        if(callback != null)
            callback.onComplete(success, message);
        if(uploadAgain && isSignedIn()) {
            handler.removeCallbacks(uploadRunnable);
            handler.postDelayed(uploadRunnable, SYNC_DEBOUNCE_MS);
        }
    }

    private void finishSyncSilently(long generation) {
        if(!syncGenerationGate.isCurrent(generation))
            return;
        pendingLocalUpload = false;
        syncing = false;
        activeSyncUid = "";
        syncGenerationGate.invalidate();
        cancelActiveSyncWork();
    }

    private void finishSilentlyIfIdentityChanged(long generation, String uid) {
        if(syncGenerationGate.isCurrent(generation) && !uidStillCurrent(uid))
            finishSyncSilently(generation);
    }

    private void invalidateSyncForChangedIdentity(String observedUid) {
        if(!syncing || Objects.equals(activeSyncUid, observedUid == null ? "" : observedUid))
            return;
        finishSyncSilently(syncGenerationGate.current());
    }

    private boolean isSyncWorkCurrent(long generation, String uid) {
        String expectedUid = uid == null ? "" : uid;
        return syncGenerationGate.canPublish(generation, expectedUid, uidOf(currentUser()))
                && Objects.equals(activeSyncUid, expectedUid);
    }

    private boolean uidStillCurrent(String expectedUid) {
        return Objects.equals(expectedUid == null ? "" : expectedUid,
                uidOf(currentUser()));
    }

    private String uidOf(FirebaseUser user) {
        return user == null || user.getUid() == null ? "" : user.getUid();
    }

    private void cancelActiveSyncWork() {
        synchronized (syncWorkLock) {
            Future<?> active = activeSyncWork;
            activeSyncWork = null;
            activeSyncWorkGeneration = 0L;
            if(active != null && !active.isDone())
                active.cancel(true);
        }
    }

    private void clearFinishedSyncWork() {
        synchronized (syncWorkLock) {
            if(activeSyncWork != null && activeSyncWork.isDone())
                activeSyncWork = null;
            if(activeSyncWork == null)
                activeSyncWorkGeneration = 0L;
        }
    }

    private static final class ParsedRemoteState {
        final Map<String, Object> remote;
        final List<MTitle> recents;
        final List<MTitle> favorites;
        final JSONObject bookmarks;
        final JSONObject pageBookmarks;
        final Map<String, Object> settings;

        ParsedRemoteState(Map<String, Object> remote,
                          List<MTitle> recents,
                          List<MTitle> favorites,
                          JSONObject bookmarks,
                          JSONObject pageBookmarks,
                          Map<String, Object> settings) {
            this.remote = remote;
            this.recents = recents;
            this.favorites = favorites;
            this.bookmarks = bookmarks;
            this.pageBookmarks = pageBookmarks;
            this.settings = settings;
        }
    }

    private static final class PreparedRemoteMerge {
        final long capturedRecentUpdatedAt;
        final long capturedLocalDataVersion;
        final List<MTitle> detachedRecents;

        PreparedRemoteMerge(long capturedRecentUpdatedAt,
                            long capturedLocalDataVersion,
                            List<MTitle> detachedRecents) {
            this.capturedRecentUpdatedAt = capturedRecentUpdatedAt;
            this.capturedLocalDataVersion = capturedLocalDataVersion;
            this.detachedRecents = detachedRecents;
        }
    }

    static final class SyncGenerationGate {
        private final AtomicLong generation = new AtomicLong();

        long begin() {
            return generation.incrementAndGet();
        }

        long invalidate() {
            return generation.incrementAndGet();
        }

        long current() {
            return generation.get();
        }

        boolean isCurrent(long candidate) {
            return candidate > 0L && generation.get() == candidate;
        }

        boolean canPublish(long candidate, String expectedUid, String currentUid) {
            String expected = expectedUid == null ? "" : expectedUid;
            String current = currentUid == null ? "" : currentUid;
            return isCurrent(candidate) && expected.equals(current);
        }
    }

    private List<MTitle> readTitleList(Map<String, Object> remote, String key) {
        try {
            List<MTitle> parsed = gson.fromJson(readString(remote, key, "[]"), new TypeToken<List<MTitle>>(){}.getType());
            if(parsed == null)
                return new ArrayList<>();
            ArrayList<MTitle> sanitized = new ArrayList<>();
            for(MTitle title : parsed)
                if(title != null && title.getName() != null)
                    sanitized.add(title);
            return sanitized;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return new ArrayList<>();
        }
    }

    private JSONObject jsonObject(String source) {
        try {
            return new JSONObject(source == null ? "{}" : source);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String readString(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        return value instanceof String ? (String)value : fallback;
    }

    private long remoteTime(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if(value instanceof Number)
            return ((Number)value).longValue();
        return 0L;
    }

    private void markLocalUpdated(String scope) {
        setLocalUpdatedAt(scope, System.currentTimeMillis());
    }

    private long getLocalUpdatedAt(String scope) {
        return metaPref.getLong(scope + "UpdatedAt", 0L);
    }

    private long exportUpdatedAt(String scope, boolean hasLocalData) {
        long value = getLocalUpdatedAt(scope);
        if(value > 0 || !hasLocalData)
            return value;
        long seeded = System.currentTimeMillis();
        setLocalUpdatedAt(scope, seeded);
        return seeded;
    }

    private boolean shouldMerge(Map<String, Object> remote, String scope, String payloadKey, String emptyPayload) {
        long remoteUpdatedAt = remoteTime(remote, scope + "UpdatedAt");
        long localUpdatedAt = getLocalUpdatedAt(scope);
        if(remoteUpdatedAt > localUpdatedAt)
            return true;
        return localUpdatedAt == 0L && hasRemotePayload(remote, payloadKey, emptyPayload);
    }

    private boolean hasRemotePayload(Map<String, Object> remote, String payloadKey, String emptyPayload) {
        String payload = readString(remote, payloadKey, emptyPayload);
        return payload != null && payload.trim().length() > 0 && !payload.trim().equals(emptyPayload);
    }

    private boolean hasAnyRemotePayload(Map<String, Object> remote) {
        if(remote == null)
            return false;
        return hasRemotePayload(remote, "recentJson", "[]")
                || hasRemotePayload(remote, "favoriteJson", "[]")
                || hasRemotePayload(remote, "bookmarkJson", "{}")
                || hasRemotePayload(remote, "pageBookmarkJson", "{}");
    }

    private void setLocalUpdatedAt(String scope, long timestamp) {
        metaPref.edit().putLong(scope + "UpdatedAt", timestamp).apply();
    }

    private String errorMessage(String prefix, Exception e) {
        String detail = e == null ? "" : e.getMessage();
        if(detail != null && detail.contains("client is offline"))
            return prefix + ": 인터넷 연결을 확인해 주세요";
        return detail == null || detail.length() == 0 ? prefix : prefix + ": " + detail;
    }

    private boolean isDeviceOnline() {
        ConnectivityManager manager = (ConnectivityManager)appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(manager == null)
            return true;
        NetworkInfo info = manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void deliver(SyncCallback callback, boolean success, String message) {
        if(callback == null)
            return;
        handler.post(() -> callback.onComplete(success, message));
    }

    private FirebaseUser currentUser() {
        return auth == null ? null : auth.getCurrentUser();
    }

    private DocumentReference stateDoc(String uid) {
        return firestore.collection("users")
                .document(uid)
                .collection("mangaView")
                .document(STATE_DOC);
    }
}
