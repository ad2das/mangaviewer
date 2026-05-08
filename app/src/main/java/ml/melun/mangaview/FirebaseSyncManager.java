package ml.melun.mangaview;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import ml.melun.mangaview.mangaview.MTitle;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FirebaseSyncManager {
    private static final String META_PREF = "firebaseSyncMeta";
    private static final String STATE_DOC = "state";
    private static final long SYNC_DEBOUNCE_MS = 1200L;

    private final Context appContext;
    private final Preference preference;
    private final SharedPreferences metaPref;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final OkHttpClient httpClient = new OkHttpClient();
    private FirebaseFirestore firestore;
    private FirebaseAuth auth;
    private boolean syncing;

    private final Runnable uploadRunnable = this::uploadCurrentState;

    public interface SyncCallback {
        void onComplete(boolean success, String message);
    }

    private interface TokenCallback {
        void onToken(boolean success, String token, String message);
    }

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
        return auth != null;
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
        syncAfterSignIn((success, message) -> {
            if(afterSync != null)
                afterSync.run();
        });
    }

    public void syncAfterSignIn(SyncCallback afterSync) {
        if(!isSignedIn()) {
            if(afterSync != null)
                afterSync.onComplete(false, "로그인이 필요합니다");
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
            if(afterUpload != null)
                afterUpload.onComplete(false, "로그인이 필요합니다");
            return;
        }
        Map<String, Object> data = exportState();
        requestIdToken(user, (tokenSuccess, token, tokenMessage) -> {
            if(!tokenSuccess) {
                if(afterUpload != null)
                    afterUpload.onComplete(false, tokenMessage);
                return;
            }
            Request request = new Request.Builder()
                    .url(restDocumentUrl(user.getUid()))
                    .patch(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), firestoreDocumentJson(data).toString()))
                    .addHeader("Authorization", "Bearer " + token)
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliver(afterUpload, false, errorMessage("업로드 실패", e));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        boolean success = response.isSuccessful();
                        int code = response.code();
                        String body = response.body() == null ? "" : response.body().string();
                        deliver(afterUpload, success, success ? null : restErrorMessage("업로드 실패", code, body));
                    } catch (IOException e) {
                        deliver(afterUpload, false, errorMessage("업로드 실패", e));
                    } finally {
                        response.close();
                    }
                }
            });
        });
    }

    private void downloadAndMerge(SyncCallback afterSync) {
        FirebaseUser user = currentUser();
        if(user == null) {
            if(afterSync != null)
                afterSync.onComplete(false, "로그인이 필요합니다");
            return;
        }
        if(!isDeviceOnline()) {
            if(afterSync != null)
                afterSync.onComplete(false, "인터넷 연결이 필요합니다");
            return;
        }
        requestIdToken(user, (tokenSuccess, token, tokenMessage) -> {
            if(!tokenSuccess) {
                if(afterSync != null)
                    afterSync.onComplete(false, tokenMessage);
                return;
            }
            Request request = new Request.Builder()
                    .url(restDocumentUrl(user.getUid()))
                    .get()
                    .addHeader("Authorization", "Bearer " + token)
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliver(afterSync, false, errorMessage("다운로드 실패", e));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String body = response.body() == null ? "" : response.body().string();
                        int code = response.code();
                        boolean success = response.isSuccessful();
                        if(!success && code != 404) {
                            deliver(afterSync, false, restErrorMessage("다운로드 실패", code, body));
                            return;
                        }
                        syncing = true;
                        try {
                            if(success)
                                mergeRemote(readFirestoreDocument(body));
                        } finally {
                            syncing = false;
                        }
                        uploadCurrentState(afterSync);
                    } catch (IOException e) {
                        deliver(afterSync, false, errorMessage("다운로드 실패", e));
                    } finally {
                        response.close();
                    }
                }
            });
        });
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

    private void mergeRemote(Map<String, Object> remote) {
        if(remote == null)
            return;
        preference.runWithoutSync(() -> {
            if(shouldMerge(remote, "recent", "recentJson", "[]")) {
                List<MTitle> recents = gson.fromJson(readString(remote, "recentJson", "[]"), new TypeToken<List<MTitle>>(){}.getType());
                preference.setRecents(recents);
                setLocalUpdatedAt("recent", remoteTime(remote, "recentUpdatedAt"));
            }
            if(shouldMerge(remote, "favorite", "favoriteJson", "[]")) {
                List<MTitle> favorites = gson.fromJson(readString(remote, "favoriteJson", "[]"), new TypeToken<List<MTitle>>(){}.getType());
                preference.setFavorites(favorites);
                setLocalUpdatedAt("favorite", remoteTime(remote, "favoriteUpdatedAt"));
            }
            if(shouldMerge(remote, "bookmark", "bookmarkJson", "{}")) {
                preference.setBookmarks(jsonObject(readString(remote, "bookmarkJson", "{}")));
                setLocalUpdatedAt("bookmark", remoteTime(remote, "bookmarkUpdatedAt"));
            }
            if(shouldMerge(remote, "pageBookmark", "pageBookmarkJson", "{}")) {
                preference.setViewerBookmarks(jsonObject(readString(remote, "pageBookmarkJson", "{}")));
                setLocalUpdatedAt("pageBookmark", remoteTime(remote, "pageBookmarkUpdatedAt"));
            }
            if(remoteTime(remote, "settingsUpdatedAt") > getLocalUpdatedAt("settings")) {
                Object settings = remote.get("settings");
                if(settings instanceof Map)
                    preference.importSyncSettings((Map<String, Object>)settings);
                setLocalUpdatedAt("settings", remoteTime(remote, "settingsUpdatedAt"));
            }
            preference.backfillRecentProgress(MainApplication.getHttpClient(), 30);
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

    private void requestIdToken(FirebaseUser user, TokenCallback callback) {
        user.getIdToken(false)
                .addOnSuccessListener(result -> callback.onToken(true, result.getToken(), null))
                .addOnFailureListener(e -> callback.onToken(false, null, errorMessage("인증 토큰 가져오기 실패", e)));
    }

    private void deliver(SyncCallback callback, boolean success, String message) {
        if(callback == null)
            return;
        handler.post(() -> callback.onComplete(success, message));
    }

    private String restDocumentUrl(String uid) {
        return "https://firestore.googleapis.com/v1/projects/"
                + appContext.getString(R.string.project_id)
                + "/databases/(default)/documents/users/"
                + uid
                + "/mangaView/"
                + STATE_DOC;
    }

    private JSONObject firestoreDocumentJson(Map<String, Object> data) {
        JSONObject root = new JSONObject();
        JSONObject fields = new JSONObject();
        try {
            for(String key : data.keySet())
                fields.put(key, firestoreValue(data.get(key)));
            root.put("fields", fields);
        } catch (Exception e) {
            //
        }
        return root;
    }

    private JSONObject firestoreValue(Object value) {
        JSONObject json = new JSONObject();
        try {
            if(value instanceof Boolean) {
                json.put("booleanValue", value);
            } else if(value instanceof Integer || value instanceof Long) {
                json.put("integerValue", String.valueOf(value));
            } else if(value instanceof Number) {
                json.put("doubleValue", ((Number)value).doubleValue());
            } else if(value instanceof Map) {
                JSONObject fields = new JSONObject();
                Map<String, Object> map = (Map<String, Object>)value;
                for(String key : map.keySet())
                    fields.put(key, firestoreValue(map.get(key)));
                json.put("mapValue", new JSONObject().put("fields", fields));
            } else {
                json.put("stringValue", value == null ? "" : String.valueOf(value));
            }
        } catch (Exception e) {
            //
        }
        return json;
    }

    private Map<String, Object> readFirestoreDocument(String body) {
        Map<String, Object> data = new HashMap<>();
        try {
            JSONObject fields = new JSONObject(body).optJSONObject("fields");
            if(fields == null)
                return data;
            Iterator<String> keys = fields.keys();
            while(keys.hasNext()) {
                String key = keys.next();
                data.put(key, readFirestoreValue(fields.optJSONObject(key)));
            }
        } catch (Exception e) {
            //
        }
        return data;
    }

    private Object readFirestoreValue(JSONObject value) {
        if(value == null)
            return null;
        if(value.has("stringValue"))
            return value.optString("stringValue", "");
        if(value.has("integerValue")) {
            try {
                return Long.parseLong(value.optString("integerValue", "0"));
            } catch (Exception e) {
                return 0L;
            }
        }
        if(value.has("doubleValue"))
            return value.optDouble("doubleValue", 0);
        if(value.has("booleanValue"))
            return value.optBoolean("booleanValue", false);
        if(value.has("mapValue")) {
            Map<String, Object> map = new HashMap<>();
            JSONObject fields = value.optJSONObject("mapValue").optJSONObject("fields");
            if(fields == null)
                return map;
            Iterator<String> keys = fields.keys();
            while(keys.hasNext()) {
                String key = keys.next();
                map.put(key, readFirestoreValue(fields.optJSONObject(key)));
            }
            return map;
        }
        return null;
    }

    private String restErrorMessage(String prefix, int code, String body) {
        String message = "";
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if(error != null)
                message = error.optString("message", "");
        } catch (Exception e) {
            //
        }
        if(message.contains("Missing or insufficient permissions"))
            return prefix + ": Firestore 권한을 확인해 주세요";
        if(message.length() == 0)
            return prefix + " (" + code + ")";
        return prefix + ": " + message;
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
