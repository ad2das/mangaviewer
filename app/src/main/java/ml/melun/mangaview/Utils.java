package ml.melun.mangaview;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import ml.melun.mangaview.activity.CaptchaActivity;
import ml.melun.mangaview.activity.EpisodeActivity;
import ml.melun.mangaview.activity.ViewerActivity;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.download.DownloadQueueWorker;
import ml.melun.mangaview.interfaces.IntegerCallback;
import ml.melun.mangaview.interfaces.StringCallback;
import ml.melun.mangaview.Downloader;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.DownloadTitle;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import okhttp3.FormBody;
import okhttp3.RequestBody;
import okhttp3.Response;

import static java.lang.System.currentTimeMillis;
import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.activity.CaptchaActivity.REQUEST_CAPTCHA;
import static ml.melun.mangaview.activity.SettingsActivity.urlSettingPopup;

public class Utils {
    private static final String MANGA_STATE_V2 = "manga_state_v2";
    private static final String MANGA_ID = "manga_id";
    private static final String MANGA_NAME = "manga_name";
    private static final String MANGA_DATE = "manga_date";
    private static final String MANGA_BASE_MODE = "manga_base_mode";
    private static final String MANGA_MODE = "manga_mode";
    private static final String MANGA_OFFLINE_PATH = "manga_offline_path";

    private static int captchaCount = 1;

    public static final String ReservedChars = "|\\?*<\":>+[]/'";

    public static boolean deleteRecursive(File fileOrDirectory) {
        if(!checkWriteable(fileOrDirectory)) return false;
        try {
            if (fileOrDirectory.isDirectory())
                for (File child : fileOrDirectory.listFiles())
                    if(!deleteRecursive(child)) return false;
            fileOrDirectory.delete();
        }catch (Exception e){
            return false;
        }
        return true;
    }

    public static boolean checkWriteable(File targetDir) {
        if(targetDir.isDirectory()) {
            File tmp = new File(targetDir, "mangaViewTestFile");
            try {
                if (tmp.createNewFile()) tmp.delete();
                else return false;
            } catch (Exception e) {
                return false;
            }
            return true;
        }else{
            File tmp = new File(targetDir.getParent(), "mangaViewTestFile");
            try {
                if (tmp.createNewFile()) tmp.delete();
                else return false;
            } catch (Exception e) {
                return false;
            }
            return true;
        }
    }

//    public static String httpsGet(String urlin, String cookie){
//        BufferedReader reader = null;
//        try {
//            InputStream stream = null;
//            URL url = new URL(urlin);
//            if(url.getProtocol().equals("http")){
//                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
//                connection.setRequestMethod("GET");
//                connection.setRequestProperty("Accept-Encoding", "*");
//                connection.setRequestProperty("Accept", "*");
//                connection.setRequestProperty("Cookie",cookie);
//                connection.connect();
//                stream = connection.getInputStream();
//            }else if(url.getProtocol().equals("https")){
//                HttpsURLConnection connections = (HttpsURLConnection) url.openConnection();
//                connections.setInstanceFollowRedirects(false);
//                connections.setRequestMethod("GET");
//                connections.setRequestProperty("Accept-Encoding", "*");
//                connections.setRequestProperty("Accept", "*");
//                connections.setRequestProperty("Cookie",cookie);
//                connections.connect();
//                stream = connections.getInputStream();
//            }
//            reader = new BufferedReader(new InputStreamReader(stream));
//            StringBuffer buffer = new StringBuffer();
//            String line = "";
//            while ((line = reader.readLine()) != null) {
//                buffer.append(line);
//            }
//            return buffer.toString();
//        } catch (Exception e) {
//            e.printStackTrace();
//        } finally {
//            try {
//                if (reader != null) {
//                    reader.close();
//                }
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }
//        return null;
//    }
//
//    public static String httpsGet(String urlin){
//        return httpsGet(urlin, "");
//    }
    public static Intent episodeIntent(Context context,Title title){
        Intent episodeView = new Intent(context, EpisodeActivity.class);
        episodeView.putExtra("title", new Gson().toJson(title));
        return episodeView;
    }

    public static Intent viewerIntent(Context context, Manga manga){
        int viewerMode = new Preference(context).getViewerType();
        Intent viewer = new Intent(context, ViewerActivity.class);
        viewer.putExtra("viewerMode", viewerMode);
        ViewerWarmupManager.warmup(context, manga, manga == null ? null : manga.getTitle());
        viewer.putExtra("manga", toViewerMangaJson(manga, manga == null ? null : manga.getTitle()));
        return viewer;
    }

    public static String toViewerMangaJson(Manga manga, Title title) {
        return new Gson().toJson(viewerMangaCopy(manga, title));
    }

    public static String toViewerTitleJson(Title title) {
        return toViewerTitleJson(title, true);
    }

    public static String toViewerTitleJson(Title title, boolean includeEpisodes) {
        if(title == null)
            return null;
        Title copy = new Title(title.minimize());
        if(includeEpisodes)
            copy.setEps(viewerEpisodeCopies(title.getEps()));
        return new Gson().toJson(copy);
    }

    private static Manga viewerMangaCopy(Manga source, Title title) {
        if(source == null)
            return null;
        Manga copy = viewerEpisodeCopy(source, true);
        List<Manga> episodes = null;
        if(title != null && title.getEps() != null)
            episodes = title.getEps();
        else if(source.getEps() != null)
            episodes = source.getEps();
        copy.setEps(viewerEpisodeCopies(episodes));
        if(title != null)
            copy.setTitle(new Title(title.minimize()));
        return copy;
    }

    private static ArrayList<Manga> viewerEpisodeCopies(List<Manga> episodes) {
        ArrayList<Manga> copies = new ArrayList<>();
        if(episodes == null)
            return copies;
        for(Manga episode : episodes) {
            if(episode != null)
                copies.add(viewerEpisodeCopy(episode, false));
        }
        return copies;
    }

    private static Manga viewerEpisodeCopy(Manga source, boolean includeImages) {
        Manga copy = new Manga(source.getId(), source.getName(), source.getDate(), source.getBaseMode());
        copy.addThumb(source.getThumb());
        copy.setMode(source.getMode());
        copy.setTitleId(source.getTitleId());
        copy.setOfflinePath(source.getOfflinePath());
        if(includeImages) {
            try {
                List<String> images = source.getImgs(null);
                if(images != null)
                    copy.setImgs(new ArrayList<>(images));
            } catch (Exception ignored) {
                // Offline image lists need a Context to resolve storage; the viewer can resolve them after launch.
            }
        }
        return copy;
    }

    public static boolean queueOfflineDownload(Context context, Title title, Manga manga) {
        if(context == null)
            return false;
        if(manga == null) {
            Toast.makeText(context, "저장할 회차 정보가 없습니다", Toast.LENGTH_SHORT).show();
            return false;
        }
        if(!manga.isOnline()) {
            Toast.makeText(context, "이미 오프라인 회차입니다", Toast.LENGTH_SHORT).show();
            return false;
        }
        if(title == null)
            title = manga.getTitle();
        if(title == null) {
            Toast.makeText(context, "작품 정보를 불러오지 못했습니다", Toast.LENGTH_SHORT).show();
            return false;
        }
        if(title.getEps() == null)
            title.setEps(new ArrayList<>());
        int index = findEpisodeIndex(title.getEps(), manga);
        if(index < 0) {
            manga.setTitle(title);
            title.getEps().add(manga);
            index = title.getEps().size() - 1;
        }
        JSONArray selected = new JSONArray();
        selected.put(index);
        return queueOfflineDownload(context, title, selected);
    }

    public static boolean queueOfflineDownload(Context context, Title title, JSONArray selected) {
        if(context == null || title == null || selected == null || selected.length() == 0) {
            Toast.makeText(context, "다운로드할 회차를 선택해 주세요", Toast.LENGTH_SHORT).show();
            return false;
        }
        if(title.getEps() == null || title.getEps().size() == 0) {
            Toast.makeText(context, "다운로드할 회차 정보를 불러오지 못했습니다", Toast.LENGTH_SHORT).show();
            return false;
        }
        if(!ensureOfflineHomeWritable(context))
            return false;
        String queueId = UUID.randomUUID().toString();
        File queueDir = new File(context.getFilesDir(), DownloadQueueWorker.QUEUE_DIR);
        if(!queueDir.exists() && !queueDir.mkdirs()) {
            Toast.makeText(context, "다운로드 대기열을 만들지 못했습니다", Toast.LENGTH_SHORT).show();
            return false;
        }
        File queueFile = new File(queueDir, queueId + ".json");
        try (FileOutputStream stream = new FileOutputStream(queueFile)) {
            String payload = new Gson().toJson(new DownloadTitle(title)) + "\n" + selected.toString();
            stream.write(payload.getBytes());
            stream.flush();
        } catch (Exception e) {
            Toast.makeText(context, "다운로드 대기열 저장에 실패했습니다", Toast.LENGTH_SHORT).show();
            return false;
        }
        Data input = new Data.Builder()
                .putString(DownloadQueueWorker.KEY_QUEUE_ID, queueId)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DownloadQueueWorker.class)
                .setInputData(input)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork("download-" + queueId, ExistingWorkPolicy.REPLACE, request);
        Toast.makeText(context,"오프라인 저장을 시작합니다.", Toast.LENGTH_LONG).show();
        return true;
    }

    private static int findEpisodeIndex(List<Manga> episodes, Manga target) {
        if(episodes == null || target == null)
            return -1;
        for(int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if(episode == null)
                continue;
            if(episode.getId() == target.getId() && episode.getBaseMode() == target.getBaseMode())
                return i;
        }
        return episodes.indexOf(target);
    }

    private static boolean ensureOfflineHomeWritable(Context context) {
        String homeDir = p.getHomeDir();
        if(homeDir == null || homeDir.length() == 0) {
            File defHome = getDefHomeDir(context);
            if(defHome != null) {
                p.setHomeDir(defHome.getAbsolutePath());
                homeDir = p.getHomeDir();
            }
        }
        if(homeDir == null || homeDir.length() == 0) {
            Toast.makeText(context,"오프라인 저장 폴더를 먼저 설정해 주세요", Toast.LENGTH_LONG).show();
            return false;
        }
        if(useScopedStorageHome(homeDir)) {
            DocumentFile home = DocumentFile.fromTreeUri(context, Uri.parse(homeDir));
            if(home == null || !home.canWrite()) {
                Toast.makeText(context,"오프라인 저장 폴더 권한을 다시 설정해 주세요", Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        }
        File home = new File(homeDir);
        if(!home.exists() && !home.mkdirs()) {
            Toast.makeText(context,"오프라인 저장 폴더를 만들 수 없습니다", Toast.LENGTH_LONG).show();
            return false;
        }
        if(!home.canWrite()) {
            Toast.makeText(context,"오프라인 저장 폴더에 쓸 수 없습니다", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    public static void saveMangaState(Bundle outState, Manga manga) {
        if(outState == null || manga == null)
            return;
        outState.putBoolean(MANGA_STATE_V2, true);
        outState.putInt(MANGA_ID, manga.getId());
        outState.putString(MANGA_NAME, manga.getName());
        outState.putString(MANGA_DATE, manga.getDate());
        outState.putInt(MANGA_BASE_MODE, manga.getBaseMode());
        outState.putInt(MANGA_MODE, manga.getMode());
        outState.putString(MANGA_OFFLINE_PATH, manga.getOfflinePath());
    }

    public static Manga restoreMangaState(Bundle savedInstanceState, Title title) {
        if(savedInstanceState == null || !savedInstanceState.getBoolean(MANGA_STATE_V2, false))
            return null;
        int id = savedInstanceState.getInt(MANGA_ID, -1);
        int baseMode = savedInstanceState.getInt(MANGA_BASE_MODE,
                title == null ? MTitle.base_comic : title.getBaseMode());
        Manga restored = findSavedEpisode(title, id, baseMode);
        if(restored == null) {
            String name = savedInstanceState.getString(MANGA_NAME, "");
            String date = savedInstanceState.getString(MANGA_DATE, "");
            restored = new Manga(id, name == null ? "" : name, date == null ? "" : date, baseMode);
        }
        restored.setMode(savedInstanceState.getInt(MANGA_MODE, restored.getMode()));
        String offlinePath = savedInstanceState.getString(MANGA_OFFLINE_PATH);
        if(offlinePath != null)
            restored.setOfflinePath(offlinePath);
        if(title != null)
            restored.setTitle(title);
        return restored;
    }

    private static Manga findSavedEpisode(Title title, int id, int baseMode) {
        if(title == null || title.getEps() == null)
            return null;
        for(Manga episode : title.getEps()) {
            if(episode != null && episode.getId() == id && episode.getBaseMode() == baseMode)
                return episode;
        }
        return null;
    }
    public static void showPopup(Context context, String title, String content, DialogInterface.OnClickListener clickListener, DialogInterface.OnCancelListener cancelListener){
        AlertDialog.Builder builder;
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("확인", clickListener)
                .setOnCancelListener(cancelListener)
                .show();
    }

    public static void showYesNoPopup(Context context, String title, String content,
                                      DialogInterface.OnClickListener posClickListener,
                                      DialogInterface.OnClickListener negClickListener,
                                      DialogInterface.OnCancelListener cancelListener){

        AlertDialog.Builder builder;
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("예", posClickListener)
                .setNegativeButton("아니오", negClickListener)
                .setOnCancelListener(cancelListener)
                .show();
    }

    public static void showYesNoPopup(boolean dark, Context context, String title, String content,
                                      DialogInterface.OnClickListener posClickListener,
                                      DialogInterface.OnClickListener negClickListener,
                                      DialogInterface.OnCancelListener cancelListener){

        AlertDialog.Builder builder;
        if (dark) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("예", posClickListener)
                .setNegativeButton("아니오", negClickListener)
                .setOnCancelListener(cancelListener)
                .show();
    }

    public static void showYesNoNeutralPopup(Context context, String title, String content, String neutral,
                                             DialogInterface.OnClickListener posClickListener,
                                             DialogInterface.OnClickListener negClickListener,
                                             DialogInterface.OnClickListener neuClickListener,
                                             DialogInterface.OnCancelListener cancelListener){

        AlertDialog.Builder builder;
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("예", posClickListener)
                .setNegativeButton("아니오", negClickListener)
                .setNeutralButton(neutral, neuClickListener)
                .setOnCancelListener(cancelListener)
                .show();
    }

    public static void showErrorPopup(Context context, String message, Exception e, boolean force_close){
        AlertDialog.Builder builder;
        String title = "오류";
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(message)
                .setPositiveButton("확인", (dialog, which) -> {
                    if(force_close) ((Activity)context).finish();
                })
                .setOnCancelListener(dialogInterface -> {
                    if(force_close) ((Activity)context).finish();
                });
        if(e != null) {
            builder.setNeutralButton("자세히", (dialog, which) -> showStackTrace(context, e));
        }
        builder.show();
    }

    public static boolean checkConnection(Context context){
        if(context != null) {
            ConnectivityManager connectivityManager
                    = (ConnectivityManager) ((Activity) context).getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
        }else return false;
    }



    public static void showCaptchaPopup(String url, Context context, int code, Exception e, boolean force_close, Fragment fragment, Preference p){
        if(context != null) {
            if (!checkConnection(context)) {
                //no internet
                //showErrorPopup(context, "네트워크 연결이 없습니다.", e, force_close);
                Toast.makeText(context, "네트워크 연결이 없습니다.", Toast.LENGTH_LONG).show();
                if (force_close) ((Activity) context).finish();
            } else if (captchaCount == 0) {
                startCaptchaActivity(context, code, fragment, url);
            } else {
                AlertDialog.Builder builder;
                String title = "오류";
                String content = "정보를 불러오는데 실패하였습니다.";
                if (new Preference(context).getDarkTheme())
                    builder = new AlertDialog.Builder(context, R.style.darkDialog);
                else builder = new AlertDialog.Builder(context);
                builder.setTitle(title)
                        .setMessage(content)
                        .setNeutralButton("확인", (dialogInterface, i) -> {
                            if (force_close) ((Activity) context).finish();
                        })
                        .setPositiveButton("CAPTCHA 인증", (dialog, which) -> startCaptchaActivity(context, code, fragment, url))
                        .setNegativeButton("URL 설정", (dialogInterface, i) -> urlSettingPopup(context, p))
                        .setOnCancelListener(dialogInterface -> {
                            if (force_close) ((Activity) context).finish();
                        });
                if (e != null) {
                    builder.setNeutralButton("자세히", (dialog, which) -> showStackTrace(context, e));
                }
                try {
                    builder.show();
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
            }
            captchaCount++;
        }
    }

    static void startCaptchaActivity(Context context, int code, Fragment fragment, String url){
        Intent captchaIntent = new Intent(context, CaptchaActivity.class);
        if(url != null && url.startsWith("/"))
            url = getHttpClient().getUrl(url) + url;
        captchaIntent.putExtra("url", url);
        if(fragment == null)
            ((Activity)context).startActivityForResult(captchaIntent, code);
        else
            fragment.startActivityForResult(captchaIntent, code);
    }

    static void startCaptchaActivity(Context context, int code, Fragment fragment){
        Intent captchaIntent = new Intent(context, CaptchaActivity.class);
        if(fragment == null)
            ((Activity)context).startActivityForResult(captchaIntent, code);
        else
            fragment.startActivityForResult(captchaIntent, code);
    }

    public static void showCaptchaPopup(String url, Context context, int code, Exception e, boolean force_close, Preference p) {
        showCaptchaPopup(url, context,code,e,force_close,null, p);
    }

    public static void showCaptchaPopup(String url, Context context, Exception e, Preference p) {
        // viewer call
        showCaptchaPopup(url, context, REQUEST_CAPTCHA, e, true, p);
    }

    public static void showCaptchaPopup(String url, Context context, int code, Preference p){
        // menu call
        showCaptchaPopup(url, context, code, null, false, p);
    }

    public static void showCaptchaPopup(String url, Context context, int code, Fragment fragment, Preference p){
        // menu call
        showCaptchaPopup(url, context, code, null, false, fragment, p);
    }

    public static void showCaptchaPopup(Context context, int code, Fragment fragment, Preference p){
        // menu call
        showCaptchaPopup(null, context, code, null, false, fragment, p);
    }

    public static void showCaptchaPopup(String url, Context context, Preference p){
        // viewer call
        showCaptchaPopup(url, context, 0, null, true, p);
    }
    public static void showCaptchaPopup(Context context, Preference p){
        // viewer call
        showCaptchaPopup(null, context, 0, null, true, p);
    }


    public static void showTokiCaptchaPopup(Context context, Preference p){
        AlertDialog.Builder builder;
        String title = "캡차 인증";
        if (new Preference(context).getDarkTheme())
            builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        View v = ((Activity)context).getLayoutInflater().inflate(R.layout.content_toki_captcha_popup, null);

        ImageView img = v.findViewById(R.id.toki_captcha_image);
        EditText answer = v.findViewById(R.id.toki_captcha_answer);

        new Thread(() -> {
            int tries = 3;
            while(tries > 0) {
                Response r = null;
                try {
                    r = getHttpClient().post(p.getUrl() + "/plugin/kcaptcha/kcaptcha_session.php", new FormBody.Builder().build(), new HashMap<>(), true);
                    if(r != null && r.code() == 200) {
                        List<String> setcookie = r.headers("Set-Cookie");
                        for (String c : setcookie) {
                            if (c.contains("PHPSESSID=")) {
                                String cookie = c.substring(c.indexOf("=") + 1, c.indexOf(";"));
                                getHttpClient().setCookie("PHPSESSID", cookie);
                            }
                        }
                        break;
                    }
                } finally {
                    if(r != null)
                        r.close();
                }
                tries--;
            }
            try {
                Response r = getHttpClient().mget("/plugin/kcaptcha/kcaptcha_image.php?t=" + currentTimeMillis(), false);
                final byte[] b = CustomHttpClient.readBytes(r);
                ((Activity) context).runOnUiThread(() -> Glide.with(img)
                        .load(b)
                        .into(img));
            }catch (Exception e){
                e.printStackTrace();
            }
        }).start();

        builder.setTitle(title)
                .setView(v)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> new Thread(() -> {
                    RequestBody requestBody = new FormBody.Builder()
                            .addEncoded("url", p.getUrl())
                            .addEncoded("captcha_key", answer.getText().toString())
                            .build();
                    Map<String, String> headers = new HashMap<>();
                    headers.put("cookie", "PHPSESSID=" + getHttpClient().getCookie("PHPSESSID") + ";");
                    Response response = null;
                    try {
                        response = getHttpClient().post(p.getUrl() + "/bbs/captcha_check.php", requestBody, headers, true);
                    } finally {
                        if(response != null)
                            response.close();
                    }
                    ((Activity) context).runOnUiThread(() -> {
                        ((Activity) context).finish();
                        ((Activity) context).startActivity(((Activity) context).getIntent());
                    });
                }).start())
                .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> ((Activity) context).finish())
                .setOnCancelListener(dialogInterface -> ((Activity) context).finish());

        builder.show();
    }
    public static GlideUrl getGlideUrl(String image){
        return getGlideUrl(image, guessImageBaseMode(image));
    }

    public static GlideUrl getGlideUrl(String image, int baseMode){
        String referer = getHttpClient().getUrl(baseMode);
        String url = normalizeImageUrl(image, baseMode);
        return new GlideUrl(url, new LazyHeaders.Builder()
                .addHeader("Referer", referer)
                .build());
    }

    private static int guessImageBaseMode(String image) {
        if(image == null)
            return MTitle.base_comic;
        String lower = image.toLowerCase(Locale.ROOT);
        if(lower.contains("/webtoon") || lower.contains("webtoon"))
            return MTitle.base_webtoon;
        return MTitle.base_comic;
    }

    private static String normalizeImageUrl(String image, int baseMode) {
        if(image == null)
            return "";
        String url = image.trim();
        if(url.startsWith("//"))
            return "https:" + url;
        if(url.startsWith("/"))
            return getSiteRoot(baseMode) + url;
        if(!url.startsWith("http") && !url.contains("://"))
            return getSiteRoot(baseMode) + "/" + url;
        return url;
    }

    private static String getSiteRoot(int baseMode) {
        String url = getHttpClient().getUrl(baseMode);
        while(url.endsWith("/"))
            url = url.substring(0, url.length() - 1);
        if(url.endsWith("/cm"))
            return url.substring(0, url.length() - 3);
        return url;
    }

    private static void showStackTrace(Context context, Exception e){
        StringBuilder sbuilder = new StringBuilder();
        if(e.getMessage() != null)
            sbuilder.append(e.getMessage()).append("\n");
        for(StackTraceElement s : e.getStackTrace()){
            sbuilder.append(s).append("\n");
        }
        final String error = sbuilder.toString();
        AlertDialog.Builder builder;
        String title = "STACK TRACE";
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(error)
                .setNeutralButton("복사", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("stack_trace", error);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context,"클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show();
                    ((Activity)context).finish();
                })
                .setPositiveButton("확인", (dialog, which) -> ((Activity)context).finish())
                .setOnCancelListener(dialog -> ((Activity)context).finish())
                .show();
    }

    public static File getDefHomeDir(Context context){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
            return context.getExternalFilesDir("");
        } else {
            return new File(Environment.getExternalStorageDirectory(), "MangaView/saved/");
        }
    }


    public static void showPopup(Context context, String title, String content){
        AlertDialog.Builder builder;
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("확인", null)
                .show();
    }

    static char[] filter = {'/','?','*',':','|','<','>','\\'};
    static public String filterFolder(String input){
        for (char c : filter) {
            int index = input.indexOf(c);
            while (index >= 0) {
                char[] tmp = input.toCharArray();
                tmp[index] = ' ';
                input = String.valueOf(tmp);
                index = input.indexOf(c);
            }
        }
        return input;
    }

    static public String readFileToString(File data){
        StringBuilder raw = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(data));
            String line;
            while ((line = br.readLine()) != null) {
                raw.append(line);
            }
            br.close();
        }catch (Exception e){
            e.printStackTrace();
        }
        return raw.toString();
    }

    public static Bitmap getSample(Bitmap input, int width){
        //scale down bitmap to avoid outofmem exception
        if(input.getWidth()<=width) return input;
        else{
            //ratio
            float ratio = (float)input.getHeight()/(float)input.getWidth();
            int height = Math.round(ratio*width);
            return Bitmap.createScaledBitmap(input, width, height,false);
        }
    }

    public static int getScreenSize(Display display){
        Point size = new Point();
        display.getSize(size);
        int width = size.x>size.y ? size.x : size.y;
        //max pixels : 3000 ?
        return width>3000 ? 3000 : width ;
    }

    public static int getScreenWidth(Display display){
        Point size = new Point();
        display.getSize(size);
        return size.x;
    }

    public static void hideSpinnerDropDown(Spinner spinner) {
        try {
            Method method = Spinner.class.getDeclaredMethod("onDetachedFromWindow");
            method.setAccessible(true);
            method.invoke(spinner);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean writePreferenceToFile(Context c, File f){
        try {
            FileOutputStream stream = new FileOutputStream(f);
            stream.write(readPref(c).getBytes());
            stream.flush();
            stream.close();
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean writePreferenceToFile(Context c, Uri uri){
        try {
            OutputStream stream = c.getContentResolver().openOutputStream(uri);
            stream.write(readPref(c).getBytes());
            stream.flush();
            stream.close();
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static void jsonToPref(Context c, CustomJSONObject data){
        SharedPreferences.Editor editor = c.getSharedPreferences("mangaView", Context.MODE_PRIVATE).edit();
        editor.putString("recent",data.getJSONArray("recent", new JSONArray()).toString());
        editor.putString("favorite",data.getJSONArray("favorite", new JSONArray()).toString());
        editor.putString("homeDir",data.getString("homeDir", ""));
        editor.putBoolean("darkTheme",data.getBoolean("darkTheme",false));
        editor.putInt("prevPageKey", data.getInt("prevPageKey", -1));
        editor.putInt("nextPageKey", data.getInt("nextPageKey", -1));
        editor.putString("bookmark",data.getJSONObject("bookmark", new JSONObject()).toString());
        editor.putString("bookmark2",data.getJSONObject("bookmark2", new JSONObject()).toString());
        editor.putInt("viewerType",data.getInt("viewerType", 0));
        editor.putBoolean("pageReverse",data.getBoolean("pageReverse", false));
        editor.putBoolean("dataSave",data.getBoolean("dataSave", false));
        editor.putBoolean("stretch",data.getBoolean("stretch", false));
        editor.putInt("startTab",data.getInt("startTab", 0));
        editor.putString("url",data.getString("url", ""));
        editor.putString("defUrl",data.getString("defUrl", "설정되지 않음"));
        editor.putBoolean("leftRight", data.getBoolean("leftRight", false));
        editor.putBoolean("autoUrl", data.getBoolean("autoUrl", true));
        editor.putFloat("pageControlButtonOffset", (float)data.getDouble("pageControlButtonOffset", -1));
        editor.apply();
    }

    public static boolean readPreferenceFromFile(Preference p, Context c, File f){
        try {
            CustomJSONObject data = new CustomJSONObject(readFileToString(f));
            jsonToPref(c, data);
            p.init(c);
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean readPreferenceFromFile(Preference p, Context c, Uri uri){
        try {
            CustomJSONObject data = new CustomJSONObject(readUriToString(c, uri));
            jsonToPref(c, data);
            p.init(c);
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static String readPref(Context context){
        SharedPreferences sharedPref = ((Activity)context).getSharedPreferences("mangaView", Context.MODE_PRIVATE);
        JSONObject data = new JSONObject();
        try {
            data.put("recent",new JSONArray(sharedPref.getString("recent", "[]")));
            data.put("favorite",new JSONArray(sharedPref.getString("favorite", "[]")));
            data.put("homeDir",sharedPref.getString("homeDir",""));
            data.put("darkTheme",sharedPref.getBoolean("darkTheme", false));
            data.put("bookmark",new JSONObject(sharedPref.getString("bookmark", "{}")));
            data.put("bookmark2",new JSONObject(sharedPref.getString("bookmark2", "{}")));
            data.put("viewerType", sharedPref.getInt("viewerType",0));
            data.put("pageReverse",sharedPref.getBoolean("pageReverse",false));
            data.put("dataSave",sharedPref.getBoolean("dataSave", false));
            data.put("stretch",sharedPref.getBoolean("stretch", false));
            data.put("leftRight", sharedPref.getBoolean("leftRight", false));
            data.put("startTab",sharedPref.getInt("startTab", 0));
            data.put("url",sharedPref.getString("url", ""));
            data.put("defUrl",sharedPref.getString("url", "설정되지 않음"));
            data.put("autoUrl", sharedPref.getBoolean("autoUrl", true));
            data.put("prevPageKey", sharedPref.getInt("prevPageKey", -1));
            data.put("nextPageKey", sharedPref.getInt("nextPageKey", -1));
            data.put("pageControlButtonOffset", sharedPref.getFloat("pageControlButtonOffset", -1));
        }catch(Exception e){
            e.printStackTrace();
        }
        return (prefFilter(data.toString()));
    }

    public static String prefFilter(String input){
        // keep newline and filter everything else
        return input.replace("\\n", "/n")
                .replace("\\","")
                .replace("/n", "\\n");
    }

    public static float dpToPixel(float dp, Context context){
        return dp * ((float) context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    public static float pixelToDp(float px, Context context){
        return px / ((float) context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    public static void openViewer(Context context, Manga manga, int code){
        openViewer(context, manga, code, false);
    }

    public static void openViewer(Context context, Manga manga, int code, boolean returnToEpisodes){
        Intent viewer = viewerIntent(context,manga);
        viewer.putExtra("online",true);
        if(returnToEpisodes)
            viewer.putExtra("returnToEpisodes", true);
        if(manga != null && manga.getTitle() != null)
            viewer.putExtra("title", toViewerTitleJson(manga.getTitle(), !manga.isOnline()));
        ((Activity)context).startActivityForResult(viewer, code);
    }

    public static void popup(Context context, View view, final int position, final Title title, final int m, PopupMenu.OnMenuItemClickListener listener, Preference p) {
        PopupMenu popup = new PopupMenu(context, view);
        //Inflating the Popup using xml file
        //todo: clean this part
        popup.getMenuInflater().inflate(R.menu.title_options, popup.getMenu());
        switch (m) {
            case 1:
                //최근
                popup.getMenu().findItem(R.id.del).setVisible(true);
            case 0:
                //검색
                popup.getMenu().findItem(R.id.favAdd).setVisible(true);
                popup.getMenu().findItem(R.id.favDel).setVisible(true);
                break;
            case 2:
                //좋아요
                popup.getMenu().findItem(R.id.favDel).setVisible(true);
                break;
            case 3:
                //저장됨
                popup.getMenu().findItem(R.id.favAdd).setVisible(true);
                popup.getMenu().findItem(R.id.favDel).setVisible(true);
                popup.getMenu().findItem(R.id.remove).setVisible(true);
                break;
        }
        //좋아요 추가/제거 중 하나만 남김
        if (m != 2) {
            if (p.findFavorite(title) > -1) popup.getMenu().removeItem(R.id.favAdd);
            else popup.getMenu().removeItem(R.id.favDel);
        }
        popup.setOnMenuItemClickListener(listener);
        popup.show();
    }

    public static int getNumberFromString(String input){
        if(input.isEmpty()) return -1;
        for(int i = 0; i < input.length(); i++) {
            if(Character.digit(input.charAt(i),10) < 0){
                if(i>0)
                    return Integer.parseInt(input.substring(0,i));
                else
                    return -1;
            }
        }
        return -1;
    }


    public static void showIntegerInputPopup(Context context, String title, IntegerCallback callback, boolean dark){
        AlertDialog.Builder alert;
        if(dark) alert = new AlertDialog.Builder(context,R.style.darkDialog);
        else alert = new AlertDialog.Builder(context);

        alert.setTitle(title);
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setRawInputType(Configuration.KEYBOARD_12KEY);
        alert.setView(input);
        alert.setPositiveButton("확인", (dialog, button) -> {
            //이동 시
            if(input.getText().length()>0) {
                callback.callback(Integer.parseInt(input.getText().toString()));
            }
        });
        alert.setNegativeButton("취소", (dialog, button) -> {
            //취소 시
        });
        alert.show();
    }

    public static void showStringInputPopup(Context context, String title, StringCallback callback, boolean dark){
        AlertDialog.Builder alert;
        if(dark) alert = new AlertDialog.Builder(context,R.style.darkDialog);
        else alert = new AlertDialog.Builder(context);

        alert.setTitle(title);
        final EditText input = new EditText(context);
        alert.setView(input);
        alert.setPositiveButton("확인", (dialog, button) -> {
            //이동 시
            if(input.getText().length()>0) {
                callback.callback(input.getText().toString());
            }
        });
        alert.setNegativeButton("취소", (dialog, button) -> {
            //취소 시
        });
        alert.show();
    }

    public static List<File> getOfflineEpisodes(String path){
        File[] episodeFiles = new File(path).listFiles(pathname -> pathname.isDirectory());
        if(episodeFiles == null)
            return new ArrayList<>();
        //sort
        Arrays.sort(episodeFiles);
        //add as manga
        return Arrays.asList(episodeFiles);
    }
    public static List<DocumentFile> getOfflineEpisodes(DocumentFile home){
        if(home == null)
            return new ArrayList<>();
        DocumentFile[] files = home.listFiles();
        if(files == null)
            return new ArrayList<>();
        Arrays.sort(files, (documentFile, t1) -> documentFile.getName().compareTo(t1.getName()));
        List<DocumentFile> res = new ArrayList<>();
        for(DocumentFile f : files){
            if(f.isDirectory()) res.add(f);
        }
        return res;
    }

    public static boolean useScopedStorageHome(String homeDir) {
        return Build.VERSION.SDK_INT >= CODE_SCOPED_STORAGE
                && homeDir != null
                && homeDir.startsWith("content://");
    }

    public static boolean isLocalMediaPath(String path) {
        return path != null
                && (path.startsWith("/")
                || path.startsWith("file://")
                || path.startsWith("content://"));
    }

    public static DocumentFile documentFileFromUri(Context context, String uriString) {
        if(context == null || uriString == null || uriString.length() == 0)
            return null;
        Uri uri = Uri.parse(uriString);
        try {
            DocumentFile file = DocumentFile.fromTreeUri(context, uri);
            if(file != null)
                return file;
        } catch (Exception ignored) {
        }
        try {
            return DocumentFile.fromSingleUri(context, uri);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String readUriToString(Context context, Uri uri){
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            StringBuilder s = new StringBuilder();
            for (String line; (line = r.readLine()) != null; ) {
                s.append(line).append('\n');
            }
            return s.toString();
        }catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public static final int CODE_SCOPED_STORAGE = 21;

}
