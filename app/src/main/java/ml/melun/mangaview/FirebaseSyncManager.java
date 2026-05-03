package ml.melun.mangaview;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ml.melun.mangaview.mangaview.MTitle;

public class FirebaseSyncManager {
    private static final String META_PREF = "firebaseSyncMeta";
    private static final String STATE_DOC = "state";
    private static final long SYNC_DEBOUNCE_MS = 1200L;

    private final Context appContext;
    private final Preference preference;
    private final SharedPreferences metaPref;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private FirebaseFirestore firestore;
    private FirebaseAuth auth;
    private boolean syncing;

    private final Runnable uploadRunnable = this::uploadCurrentState;

    public FirebaseSyncManager(Context context, Preference preference) {
        appContext = context.getApplicationContext();
        this.preference = preference;
        metaPref = appContext.getSharedPreferences(META_PREF, Context.MODE_PRIVATE);
        try {
            auth = FirebaseAuth.getInstance();
            firestore = FirebaseFirestore.getInstance();
        } catch (Exception e) {
            auth = null;
            firestore = null;
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
        if(syncing || !isSignedIn())
            return;
        markLocalUpdated(scope);
        handler.removeCallbacks(uploadRunnable);
        handler.postDelayed(uploadRunnable, SYNC_DEBOUNCE_MS);
    }

    public void syncAfterSignIn(Runnable afterSync) {
        if(!isSignedIn()) {
            if(afterSync != null)
                afterSync.run();
            return;
        }
        downloadAndMerge(afterSync);
    }

    public void uploadCurrentState() {
        FirebaseUser user = currentUser();
        if(user == null)
            return;
        DocumentReference doc = stateDoc(user.getUid());
        Map<String, Object> data = exportState();
        doc.set(data);
    }

    private void downloadAndMerge(Runnable afterSync) {
        FirebaseUser user = currentUser();
        if(user == null) {
            if(afterSync != null)
                afterSync.run();
            return;
        }
        stateDoc(user.getUid()).get()
                .addOnSuccessListener(snapshot -> {
                    syncing = true;
                    try {
                        if(snapshot != null && snapshot.exists())
                            mergeRemote(snapshot.getData());
                        uploadCurrentState();
                    } finally {
                        syncing = false;
                        if(afterSync != null)
                            afterSync.run();
                    }
                })
                .addOnFailureListener(e -> {
                    uploadCurrentState();
                    if(afterSync != null)
                        afterSync.run();
                });
    }

    private Map<String, Object> exportState() {
        Map<String, Object> data = new HashMap<>();
        data.put("recentJson", gson.toJson(preference.getRecent()));
        data.put("favoriteJson", gson.toJson(preference.getFavorite()));
        data.put("bookmarkJson", preference.getBookmarkObject().toString());
        data.put("pageBookmarkJson", preference.getViewerBookmarkObject().toString());
        data.put("settings", preference.exportSyncSettings());
        data.put("recentUpdatedAt", getLocalUpdatedAt("recent"));
        data.put("favoriteUpdatedAt", getLocalUpdatedAt("favorite"));
        data.put("bookmarkUpdatedAt", getLocalUpdatedAt("bookmark"));
        data.put("pageBookmarkUpdatedAt", getLocalUpdatedAt("pageBookmark"));
        data.put("settingsUpdatedAt", getLocalUpdatedAt("settings"));
        data.put("updatedAt", System.currentTimeMillis());
        return data;
    }

    private void mergeRemote(Map<String, Object> remote) {
        if(remote == null)
            return;
        preference.runWithoutSync(() -> {
            if(remoteTime(remote, "recentUpdatedAt") > getLocalUpdatedAt("recent")) {
                List<MTitle> recents = gson.fromJson(readString(remote, "recentJson", "[]"), new TypeToken<List<MTitle>>(){}.getType());
                preference.setRecents(recents);
                setLocalUpdatedAt("recent", remoteTime(remote, "recentUpdatedAt"));
            }
            if(remoteTime(remote, "favoriteUpdatedAt") > getLocalUpdatedAt("favorite")) {
                List<MTitle> favorites = gson.fromJson(readString(remote, "favoriteJson", "[]"), new TypeToken<List<MTitle>>(){}.getType());
                preference.setFavorites(favorites);
                setLocalUpdatedAt("favorite", remoteTime(remote, "favoriteUpdatedAt"));
            }
            if(remoteTime(remote, "bookmarkUpdatedAt") > getLocalUpdatedAt("bookmark")) {
                preference.setBookmarks(jsonObject(readString(remote, "bookmarkJson", "{}")));
                setLocalUpdatedAt("bookmark", remoteTime(remote, "bookmarkUpdatedAt"));
            }
            if(remoteTime(remote, "pageBookmarkUpdatedAt") > getLocalUpdatedAt("pageBookmark")) {
                preference.setViewerBookmarks(jsonObject(readString(remote, "pageBookmarkJson", "{}")));
                setLocalUpdatedAt("pageBookmark", remoteTime(remote, "pageBookmarkUpdatedAt"));
            }
            if(remoteTime(remote, "settingsUpdatedAt") > getLocalUpdatedAt("settings")) {
                Object settings = remote.get("settings");
                if(settings instanceof Map)
                    preference.importSyncSettings((Map<String, Object>)settings);
                setLocalUpdatedAt("settings", remoteTime(remote, "settingsUpdatedAt"));
            }
        });
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
        long value = metaPref.getLong(scope + "UpdatedAt", 0L);
        if(value > 0)
            return value;
        long seeded = System.currentTimeMillis();
        setLocalUpdatedAt(scope, seeded);
        return seeded;
    }

    private void setLocalUpdatedAt(String scope, long timestamp) {
        metaPref.edit().putLong(scope + "UpdatedAt", timestamp).apply();
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
