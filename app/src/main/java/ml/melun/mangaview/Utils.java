package ml.melun.mangaview;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.webkit.CookieManager;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import ml.melun.mangaview.activity.CaptchaActivity;
import ml.melun.mangaview.activity.EpisodeActivity;
import ml.melun.mangaview.activity.ViewerIntentContract;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.interfaces.IntegerCallback;
import ml.melun.mangaview.interfaces.StringCallback;
import ml.melun.mangaview.reader.ReaderImageCache;
import ml.melun.mangaview.reader.ReaderLaunchPreparer;
import ml.melun.mangaview.reader.ReaderWarmupCoordinator;
import ml.melun.mangaview.repository.DownloadRepository;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PreparedViewerLaunch;
import ml.melun.mangaview.runtime.ViewerPreparationCoordinator;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.activity.CaptchaActivity.REQUEST_CAPTCHA;
import static ml.melun.mangaview.activity.SettingsActivity.urlSettingPopup;

public class Utils {
    private static final Map<Context, Integer> viewerLaunchTokens = new WeakHashMap<>();
    private static final Map<Context, Long> viewerLaunchTimes = new WeakHashMap<>();
    private static final Map<Context, String> viewerLaunchKeys = new WeakHashMap<>();
    private static final Map<Activity, Long> focusedDestinationLaunchTimes = new WeakHashMap<>();
    private static int viewerLaunchSequence = 0;
    private static final long VIEWER_LAUNCH_DEBOUNCE_MS = 450L;
    private static final int VIEWER_TITLE_EPISODE_WINDOW_BEFORE = 4;
    private static final int VIEWER_TITLE_EPISODE_WINDOW_AFTER = 12;
    private static final int VIEWER_TITLE_JSON_SOFT_LIMIT_CHARS = 180 * 1024;
    private static final String MANGA_STATE_V2 = "manga_state_v2";
    private static final String MANGA_ID = "manga_id";
    private static final String MANGA_NAME = "manga_name";
    private static final String MANGA_DATE = "manga_date";
    private static final String MANGA_BASE_MODE = "manga_base_mode";
    private static final String MANGA_MODE = "manga_mode";
    private static final String MANGA_OFFLINE_PATH = "manga_offline_path";
    private static final int DOWNLOAD_NOTIFICATION_PERMISSION_REQUEST = 132_324;

    private static int captchaCount = 1;
    private static long lastAutoCloudflareCaptchaAt = 0L;
    private static long lastCaptchaActivityStartedAt = 0L;
    private static long lastNtkWebViewCookieSyncAt = 0L;
    private static final long NTK_WEBVIEW_COOKIE_SYNC_INTERVAL_MS = 60_000L;
    private static final long CAPTCHA_ACTIVITY_MIN_INTERVAL_MS = 3_000L;
    private static final long NTK_CAPTCHA_ACTIVITY_MIN_INTERVAL_MS = 30_000L;
    private static final long NTK_WARP_ASSIST_AUTO_LAUNCH_MIN_INTERVAL_MS = 60_000L;
    private static final long NTK_INITIAL_JPG_HEDGE_DELAY_MS = 220L;
    private static final long NTK_INITIAL_JPG_HEDGE_RECHECK_MS = 220L;
    private static final long NTK_INITIAL_JPG_HEDGE_MAX_WAIT_MS = 2_200L;
    private static final String CLOUDFLARE_WARP_PACKAGE = "com.cloudflare.onedotonedotonedotone";
    private static final String CLOUDFLARE_ONE_PACKAGE = "com.cloudflare.cloudflareoneagent";
    private static volatile long lastCloudflareWarpAssistStartedAtMs = 0L;
    private static final int GLIDE_URL_CACHE_MAX = 512;
    private static final Map<String, GlideUrl> glideUrlCache = new LinkedHashMap<String, GlideUrl>(GLIDE_URL_CACHE_MAX, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GlideUrl> eldest) {
            return size() > GLIDE_URL_CACHE_MAX;
        }
    };

    public static final String ReservedChars = "|\\?*<\":>+[]/'";

    public static boolean deleteRecursive(File fileOrDirectory) {
        if(fileOrDirectory == null || !fileOrDirectory.exists()) return false;
        if(!checkWriteable(fileOrDirectory)) return false;
        try {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if(children == null) return false;
                for (File child : children)
                    if(!deleteRecursive(child)) return false;
            }
            return fileOrDirectory.delete();
        }catch (Exception e){
            return false;
        }
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
//            ml.melun.mangaview.report.CrashReporter.record(e);
//        } finally {
//            try {
//                if (reader != null) {
//                    reader.close();
//                }
//            } catch (Exception e) {
//                ml.melun.mangaview.report.CrashReporter.record(e);
//            }
//        }
//        return null;
//    }
//
//    public static String httpsGet(String urlin){
//        return httpsGet(urlin, "");
//    }
    public static Intent episodeIntent(Context context,Title title){
        if(p != null)
            p.ensureSourceSiteForTitle(title);
        Intent episodeView = new Intent(context, EpisodeActivity.class);
        episodeView.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        episodeView.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        episodeView.putExtra("title", toViewerTitleJson(title, true));
        return episodeView;
    }

    public static Intent viewerIntent(Context context, Manga manga){
        return viewerIntent(context, manga, true);
    }

    private static Intent viewerIntent(Context context, Manga manga, boolean warmupContinue){
        return viewerIntent(context, manga, warmupContinue, true, true, true);
    }

    private static Intent viewerIntent(Context context, Manga manga, boolean warmupContinue,
                                       boolean includeViewerTitle, boolean includeMangaEpisodes,
                                       boolean includeTitleEpisodes){
        Intent viewer = new Intent(context, ml.melun.mangaview.activity.ReaderV2Activity.class);
        Title title = manga == null ? null : manga.getTitle();
        viewer.putExtra("manga", toViewerMangaJson(manga, title, includeMangaEpisodes && title == null));
        if(includeViewerTitle)
            viewer.putExtra("title", toViewerTitleJsonForReader(title, manga, includeTitleEpisodes));
        return viewer;
    }

    private static boolean shouldUseReaderV2(Manga manga) {
        if(manga == null || !manga.isOnline())
            return false;
        Title title = manga.getTitle();
        String source = title == null ? "" : title.getSourceSite();
        if("wfwf".equals(source) || "ntk".equals(source))
            return true;
        return getHttpClient() != null;
    }

    private static boolean shouldScheduleViewerIntentWarmup(Context context, Manga manga, boolean warmupContinue) {
        boolean hasLoadedImages = false;
        if(manga != null) {
            List<String> images = MangaRepository.imageUrls(manga, context);
            hasLoadedImages = images != null && images.size() > 0;
        }
        return shouldScheduleViewerIntentWarmup(warmupContinue, manga != null && manga.isOnline(), hasLoadedImages);
    }

    private static boolean shouldScheduleViewerIntentWarmup(boolean warmupContinue, boolean online, boolean hasLoadedImages) {
        return warmupContinue && online && !hasLoadedImages;
    }

    static boolean shouldScheduleViewerIntentWarmupForTest(boolean warmupContinue, boolean online, boolean hasLoadedImages) {
        return shouldScheduleViewerIntentWarmup(warmupContinue, online, hasLoadedImages);
    }

    public static void openViewerPrepared(Context context, Manga manga, int code) {
        openViewerPrepared(context, manga, code, false);
    }

    public static void openViewerPrepared(Context context, Manga manga, int code, boolean returnToEpisodes) {
        openViewerPrepared(context, manga, code, returnToEpisodes, true, false,
                manga == null ? null : manga.getTitle(), true);
    }

    public static void openViewerPrepared(Context context, Manga manga, int code, boolean returnToEpisodes,
                                          boolean online, boolean recent, Title title, boolean includeTitleEpisodes) {
        openViewerPrepared(context, manga, code, returnToEpisodes, online, recent, title, includeTitleEpisodes, false);
    }

    public static void openViewerPrepared(Context context, Manga manga, int code, boolean returnToEpisodes,
                                          boolean online, boolean recent, Title title, boolean includeTitleEpisodes,
                                          boolean exactEpisode) {
        openViewerPrepared(context, manga, code, returnToEpisodes, online, recent, title, includeTitleEpisodes, exactEpisode, false);
    }

    public static void openContinueViewer(Context context, Manga manga, int code) {
        openContinueViewer(context, manga, code, false);
    }

    public static void openContinueViewer(Context context, Manga manga, int code, boolean returnToEpisodes) {
        openContinueViewer(context, manga, code, returnToEpisodes, false,
                manga == null ? null : manga.getTitle(),
                manga == null || !manga.isOnline() || isMinimalOnlineViewerManga(manga));
    }

    public static void openContinueViewer(Context context, Manga manga, int code, boolean returnToEpisodes,
                                          boolean recent, Title title, boolean includeTitleEpisodes) {
        openViewerPrepared(context, manga, code, returnToEpisodes, true, recent,
                title != null ? title : (manga == null ? null : manga.getTitle()),
                includeTitleEpisodes, false, true);
    }

    private static void openViewerPrepared(Context context, Manga manga, int code, boolean returnToEpisodes,
                                           boolean online, boolean recent, Title title, boolean includeTitleEpisodes,
                                           boolean exactEpisode, boolean waitForFirstFrame) {
        if(context == null || manga == null)
            return;
        int launchToken = nextViewerLaunchToken(context);
        Title launchTitle = title != null ? title : manga.getTitle();
        if(launchTitle != null) {
            if(p != null)
                p.ensureSourceSiteForTitle(launchTitle);
            switchToTitleSourceSite(launchTitle);
            manga.setTitle(launchTitle);
            manga.setTitleId(launchTitle.getId());
            manga.ensureNtkEpisodePathFromIdentity();
        }
        ml.melun.mangaview.runtime.BackgroundPrefetchBudget.suppressForUserNavigation();
        if(online && manga.isOnline()) {
            String ntkPath = manga.getNtkEpisodePath();
            if(ntkPath != null && ntkPath.length() > 0) {
                CustomHttpClient client = getHttpClient();
                if(client != null && client.isNtk())
                    client.preStartNtkAckForPath(ntkPath);
            }
            if(exactEpisode && shouldWaitForExactFirstFrame(launchTitle)) {
                ViewerWarmupManager.logMetric("viewer_exact_prepare_gate", manga.getId());
                launchExactWhenFirstFrameReady(context, manga, code, returnToEpisodes, online, recent,
                        launchTitle, includeTitleEpisodes, launchToken);
                return;
            }
            if(!exactEpisode && shouldWaitForContinueFirstFrame(waitForFirstFrame, recent, launchTitle)) {
                ViewerWarmupManager.logMetric("viewer_continue_prepare_gate", manga.getId());
                launchWhenFirstFrameReady(context, manga, code, returnToEpisodes, online, recent,
                        launchTitle, includeTitleEpisodes, launchToken);
                return;
            }
        }
        launchPreparedViewer(context, manga, code, returnToEpisodes, online, recent, launchTitle, includeTitleEpisodes, launchToken, exactEpisode);
    }

    private static boolean shouldWaitForExactFirstFrame(Title title) {
        String source = title == null ? "" : title.getSourceSite();
        return shouldWaitForExactFirstFrame(source, p != null && p.isNtkSite());
    }

    static boolean shouldWaitForExactFirstFrameForTest(String sourceSite, boolean ntkSite) {
        return shouldWaitForExactFirstFrame(sourceSite, ntkSite);
    }

    private static boolean shouldWaitForExactFirstFrame(String sourceSite, boolean ntkSite) {
        return false;
    }

    private static boolean shouldWaitForContinueFirstFrame(boolean waitForFirstFrame, boolean recent, Title title) {
        String source = title == null ? "" : title.getSourceSite();
        return shouldWaitForContinueFirstFrame(waitForFirstFrame, recent, source, p != null && p.isNtkSite());
    }

    static boolean shouldWaitForContinueFirstFrameForTest(boolean waitForFirstFrame, boolean recent) {
        return shouldWaitForContinueFirstFrame(waitForFirstFrame, recent, "", false);
    }

    static boolean shouldWaitForContinueFirstFrameForTest(boolean waitForFirstFrame, boolean recent,
                                                          String sourceSite, boolean ntkSite) {
        return shouldWaitForContinueFirstFrame(waitForFirstFrame, recent, sourceSite, ntkSite);
    }

    private static boolean shouldWaitForContinueFirstFrame(boolean waitForFirstFrame, boolean recent,
                                                           String sourceSite, boolean ntkSite) {
        return false;
    }

    private static void launchExactWhenFirstFrameReady(Context context, Manga manga, int code, boolean returnToEpisodes,
                                                       boolean online, boolean recent, Title title, boolean includeTitleEpisodes,
                                                       int launchToken) {
        Context appContext = context.getApplicationContext();
        int width = context instanceof Activity
                ? getScreenWidth(((Activity) context).getWindowManager().getDefaultDisplay())
                : context.getResources().getDisplayMetrics().widthPixels;
        AppDispatchers.submitNavigation(() -> {
            String readerPreparedKey = ReaderLaunchPreparer.prepareFirstFrame(appContext, manga, title, width, true);
            if(readerPreparedKey != null) {
                AppDispatchers.runOnMain(() -> launchPreparedViewer(context, manga, code, returnToEpisodes,
                        online, recent, title, includeTitleEpisodes, launchToken, true));
                return;
            }
            PreparedViewerLaunch prepared = ViewerPreparationCoordinator.prepareExact(appContext, manga, title, 0,
                    width, false, p.getReverse(), MangaRepository.cancellation(), exactFirstFrameWaitMs(title),
                    shouldAllowExactForegroundFallback(title));
            if(!prepared.canLaunch()) {
                if(shouldLaunchExactWithoutPrepared(prepared)) {
                    ViewerWarmupManager.logMetric("viewer_exact_unprepared_foreground_launch", manga == null ? -1 : manga.getId());
                    AppDispatchers.runOnMain(() -> launchPreparedViewer(context, manga, code, returnToEpisodes,
                            online, recent, title, includeTitleEpisodes, launchToken, true));
                    return;
                }
                ViewerWarmupManager.logMetric("viewer_exact_unprepared_blocked", manga == null ? -1 : manga.getId());
                AppDispatchers.runOnMain(() -> showViewerPreparationIssue(context, launchToken, prepared, manga));
                return;
            }
            Manga launchManga = prepared.getManga();
            Title launchTitle = title != null ? title : prepared.getTitle();
            if(launchTitle == null && launchManga != null)
                launchTitle = launchManga.getTitle();
            final Title finalLaunchTitle = launchTitle;
            AppDispatchers.runOnMain(() -> launchPreparedViewer(context, launchManga, code, returnToEpisodes,
                    online, recent, finalLaunchTitle, includeTitleEpisodes, launchToken, true));
        });
    }

    private static boolean shouldLaunchExactWithoutPrepared(PreparedViewerLaunch prepared) {
        return prepared == null || prepared.getStatus() == PreparedViewerLaunch.Status.OFFLINE;
    }

    static boolean shouldLaunchExactWithoutPreparedForTest(PreparedViewerLaunch prepared) {
        return shouldLaunchExactWithoutPrepared(prepared);
    }

    private static long exactFirstFrameWaitMs(Title title) {
        String source = title == null ? "" : title.getSourceSite();
        return exactFirstFrameWaitMs(source, p != null && p.isNtkSite());
    }

    static long exactFirstFrameWaitMsForTest(String sourceSite, boolean ntkSite) {
        return exactFirstFrameWaitMs(sourceSite, ntkSite);
    }

    static boolean shouldAllowExactForegroundFallbackForTest(String sourceSite, boolean ntkSite) {
        return shouldAllowExactForegroundFallback(sourceSite, ntkSite);
    }

    private static boolean shouldAllowExactForegroundFallback(Title title) {
        String source = title == null ? "" : title.getSourceSite();
        return shouldAllowExactForegroundFallback(source, p != null && p.isNtkSite());
    }

    private static long exactFirstFrameWaitMs(String sourceSite, boolean ntkSite) {
        String source = sourceSite == null ? "" : sourceSite.trim().toLowerCase(Locale.ROOT);
        if("wfwf".equals(source))
            return 450L;
        if("ntk".equals(source))
            return 350L;
        if(!ntkSite)
            return 450L;
        return 350L;
    }

    private static boolean shouldAllowExactForegroundFallback(String sourceSite, boolean ntkSite) {
        String source = sourceSite == null ? "" : sourceSite.trim().toLowerCase(Locale.ROOT);
        if("ntk".equals(source))
            return true;
        if("wfwf".equals(source))
            return true;
        return true;
    }

    private static void launchWhenFirstFrameReady(Context context, Manga manga, int code, boolean returnToEpisodes,
                                                  boolean online, boolean recent, Title title, boolean includeTitleEpisodes,
                                                  int launchToken) {
        Context appContext = context.getApplicationContext();
        if(shouldLaunchContinueFallback(context, manga))
            AppDispatchers.main().postDelayed(() -> launchPreparedViewer(context, manga, code, returnToEpisodes,
                            online, recent, title, includeTitleEpisodes, launchToken, false),
                    continueLaunchFallbackMs(title));
        AppDispatchers.submitUserAction(() -> {
            try {
                PreparedViewerLaunch prepared = ViewerPreparationCoordinator.prepareContinue(appContext, manga, title,
                        false, p.getReverse(), MangaRepository.cancellation());
                if(!prepared.canLaunch() && shouldBlockUnpreparedContinueLaunch(prepared)) {
                    ViewerWarmupManager.logMetric("viewer_continue_unprepared_blocked", manga.getId());
                    AppDispatchers.runOnMain(() -> showViewerPreparationIssue(context, launchToken, prepared, manga));
                    return;
                }
                ViewerWarmupManager.logMetric(prepared.canLaunch() ? "viewer_continue_prepared_ready" : "viewer_continue_prepared_fallback", manga.getId());
                Manga launchManga = prepared.canLaunch() ? prepared.getManga() : manga;
                Title launchTitle = title != null ? title : prepared.getTitle();
                if(launchTitle == null && launchManga != null)
                    launchTitle = launchManga.getTitle();
                final Title finalLaunchTitle = launchTitle;
                AppDispatchers.runOnMain(() -> launchPreparedViewer(context, launchManga, code, returnToEpisodes,
                        online, recent, finalLaunchTitle, includeTitleEpisodes, launchToken, false));
            } catch (RuntimeException e) {
                ViewerWarmupManager.logMetric("viewer_continue_prepare_exception", manga.getId());
                ml.melun.mangaview.report.CrashReporter.record(e);
                AppDispatchers.runOnMain(() -> launchPreparedViewer(context, manga, code, returnToEpisodes,
                        online, recent, title, includeTitleEpisodes, launchToken, false));
            }
        });
    }

    private static void showViewerPreparationIssue(Context context, int launchToken,
                                                   PreparedViewerLaunch prepared, Manga manga) {
        if(!cancelViewerLaunchToken(context, launchToken))
            return;
        if(prepared != null && prepared.isCaptcha()) {
            showCaptchaPopup(Manga.safeUrl(manga), context, REQUEST_CAPTCHA, p);
            return;
        }
        Toast.makeText(context, "회차 준비 중입니다. 잠시 후 다시 눌러주세요.", Toast.LENGTH_SHORT).show();
    }

    private static boolean shouldBlockUnpreparedContinueLaunch(PreparedViewerLaunch prepared) {
        return prepared != null && (prepared.isCaptcha()
                || prepared.getStatus() == PreparedViewerLaunch.Status.PATHLESS_NTK);
    }

    private static boolean shouldBlockUnpreparedContinueFallback(Manga manga) {
        return false;
    }

    private static boolean shouldLaunchContinueFallback(Context context, Manga manga) {
        boolean hasLoadedImages = false;
        String source = "";
        boolean hasNtkEpisodePath = false;
        if(manga != null) {
            List<String> images = MangaRepository.imageUrls(manga, context);
            hasLoadedImages = images != null && images.size() > 0;
            Title title = manga.getTitle();
            source = title == null ? "" : title.getSourceSite();
            hasNtkEpisodePath = manga.getNtkEpisodePath().length() > 0;
            boolean needsEpisodesBeforeFallback = "wfwf".equals(source)
                    || ("ntk".equals(source) && !hasNtkEpisodePath);
            if(needsEpisodesBeforeFallback
                    && title != null && snapshotEpisodes(title).size() == 0 && !hasLoadedImages)
                return false;
        }
        return shouldLaunchContinueFallback(source, manga != null && manga.isOnline(), hasLoadedImages, hasNtkEpisodePath);
    }

    private static boolean shouldLaunchContinueFallback(boolean online, boolean hasLoadedImages) {
        return shouldLaunchContinueFallback("", online, hasLoadedImages, false);
    }

    private static boolean shouldLaunchContinueFallback(String sourceSite, boolean online,
                                                        boolean hasLoadedImages, boolean hasNtkEpisodePath) {
        if(online && "ntk".equals(sourceSite) && !hasLoadedImages && !hasNtkEpisodePath)
            return false;
        return true;
    }

    static boolean shouldLaunchContinueFallbackForTest(boolean online, boolean hasLoadedImages) {
        return shouldLaunchContinueFallback(online, hasLoadedImages);
    }

    static boolean shouldLaunchContinueFallbackForTest(String sourceSite, boolean online,
                                                       boolean hasLoadedImages, boolean hasNtkEpisodePath) {
        return shouldLaunchContinueFallback(sourceSite, online, hasLoadedImages, hasNtkEpisodePath);
    }

    static boolean shouldBlockUnpreparedContinueFallbackForTest(boolean online) {
        return shouldBlockUnpreparedContinueFallback(null);
    }

    private static long continueLaunchFallbackMs(Title title) {
        String source = title == null ? "" : title.getSourceSite();
        return continueLaunchFallbackMs(source, p != null && p.isNtkSite());
    }

    static long continueLaunchFallbackMsForTest(String sourceSite, boolean ntkSite) {
        return continueLaunchFallbackMs(sourceSite, ntkSite);
    }

    private static long continueLaunchFallbackMs(String sourceSite, boolean ntkSite) {
        String source = sourceSite == null ? "" : sourceSite.trim().toLowerCase(Locale.ROOT);
        if("ntk".equals(source))
            return 220L;
        if("wfwf".equals(source))
            return 520L;
        return ntkSite ? 220L : 520L;
    }

    private static void switchToTitleSourceSite(Title title) {
        if(title == null || p == null)
            return;
        String source = title.getSourceSite();
        if(source == null || source.length() == 0)
            return;
        boolean targetNtk = "ntk".equals(source);
        if(targetNtk) {
            p.setNtkSitePreset(p.getNtkResolvedRoot());
            return;
        }
        if(!p.isNtkSite())
            return;
        p.setSitePreset(CustomHttpClient.DEFAULT_COMIC_URL, CustomHttpClient.WEBTOON_URL);
    }

    private static void launchPreparedViewer(Context context, Manga manga, int code, boolean returnToEpisodes,
                                             boolean online, boolean recent, Title title, boolean includeTitleEpisodes,
                                             int launchToken) {
        launchPreparedViewer(context, manga, code, returnToEpisodes, online, recent, title, includeTitleEpisodes, launchToken, false);
    }

    private static void launchPreparedViewer(Context context, Manga manga, int code, boolean returnToEpisodes,
                                             boolean online, boolean recent, Title title, boolean includeTitleEpisodes,
                                             int launchToken, boolean exactEpisode) {
        if(context == null || manga == null) {
            ViewerWarmupManager.logMetric("viewer_launch_abort_null", manga == null ? -1 : manga.getId());
            return;
        }
        if(context instanceof Activity && !canUseActivity((Activity) context)) {
            ViewerWarmupManager.logMetric("viewer_launch_abort_activity_before", manga.getId());
            return;
        }
        int width = context instanceof Activity
                ? getScreenWidth(((Activity) context).getWindowManager().getDefaultDisplay())
                : context.getResources().getDisplayMetrics().widthPixels;
        Context appContext = context.getApplicationContext();
        Title launchTitle = title != null ? title : manga.getTitle();
        if(launchTitle != null) {
            manga.setTitle(launchTitle);
            manga.setTitleId(launchTitle.getId());
            manga.ensureNtkEpisodePathFromIdentity();
        }
        boolean ntkLaunchPreflightStarted = startNtkViewerLaunchPreflight(manga, launchTitle);
        String preparedKey = null;
        try {
            preparedKey = ReaderWarmupCoordinator.readyKey(appContext, manga, launchTitle, width, exactEpisode);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        if(!consumeViewerLaunchToken(context, launchToken, viewerLaunchDebounceKey(manga, title, exactEpisode))) {
            ViewerWarmupManager.logMetric("viewer_launch_abort_token", manga.getId());
            return;
        }
        if(context instanceof Activity && !canUseActivity((Activity) context)) {
            ViewerWarmupManager.logMetric("viewer_launch_abort_activity_after", manga.getId());
            return;
        }
        boolean includeMangaEpisodes = launchTitle == null;
        Intent viewer = viewerIntent(context, manga, false, false, includeMangaEpisodes, false);
        viewer.putExtra("online", online);
        if(preparedKey != null)
            viewer.putExtra(ReaderLaunchPreparer.EXTRA_PREPARED_KEY, preparedKey);
        if(exactEpisode) {
            viewer.putExtra(ViewerIntentContract.EXTRA_EXACT_EPISODE, true);
            if(shouldStartExactEpisodeAtFirstPage(manga))
                viewer.putExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, true);
        }
        if(returnToEpisodes)
            viewer.putExtra("returnToEpisodes", true);
        if(launchTitle != null)
            viewer.putExtra("title", toViewerTitleJsonForReader(launchTitle, manga, includeTitleEpisodes));
        if(recent)
            viewer.putExtra("recent", true);
        long viewerLaunchStartedAtMs = SystemClock.elapsedRealtime();
        viewer.putExtra("viewerLaunchStartedAtMs", viewerLaunchStartedAtMs);
        viewer.putExtra("viewerLaunchSourceSite", launchTitle == null ? "" : launchTitle.getSourceSite());
        viewer.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if(ntkLaunchPreflightStarted)
            viewer.putExtra("viewerNtkAckPreflightStarted", true);
        try {
            ViewerWarmupManager.logMetric(preparedKey == null ? "viewer_launch_start_unprepared" : "viewer_launch_start_prepared", manga.getId());
            if(context instanceof Activity) {
                ((Activity) context).startActivityForResult(viewer, code);
                ((Activity) context).overridePendingTransition(0, 0);
            } else {
                viewer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(viewer);
            }
        } catch(RuntimeException e) {
            ViewerWarmupManager.logMetric("viewer_launch_exception", manga.getId());
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public static boolean startNtkViewerLaunchPreflight(Manga manga, Title title) {
        if(manga == null)
            return false;
        String path = manga.getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return false;
        String sourceSite = title == null ? "" : title.getSourceSite();
        boolean ntkSource = "ntk".equalsIgnoreCase(sourceSite)
                || path.startsWith("/webtoon/")
                || path.startsWith("/manhwa/");
        if(!ntkSource)
            return false;
        Thread thread = new Thread(() -> {
            try {
                CustomHttpClient client = getHttpClient();
                boolean slugWebtoon = false;
                try {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("^/webtoon/([^/?#]+)/([^/?#]+)")
                            .matcher(path);
                    slugWebtoon = matcher.find()
                            && (!matcher.group(1).matches("\\d+")
                            || !matcher.group(2).matches("\\d+"));
                } catch(Exception ignored) {
                }
                try {
                    if(slugWebtoon) {
                        boolean naverOriginal = path.matches("(?i)^/webtoon/[^/?#]+/(?:naver|nv)-\\d{5,}-\\d+(?:[/?#].*)?$");
                        if(naverOriginal) {
                            manga.startNtkVerifiedInitialImageProbeForLaunchPreflight(client);
                            android.util.Log.d("ViewerPerf",
                                    "viewer_ntk_webtoon_launch_api_prefetch_start path=" + path
                                            + ",naverOriginal=true");
                        } else {
                            startImmediateNtkGeneratedInitialPrime(client, manga, path);
                            android.util.Log.d("ViewerPerf",
                                    "viewer_ntk_webtoon_launch_api_prefetch_defer path=" + path
                                            + ",reason=activity_first");
                        }
                    } else {
                        startImmediateNtkGeneratedInitialPrime(client, manga, path);
                    }
                } catch(Exception e) {
                    android.util.Log.d("ViewerPerf", "viewer_ntk_image_api_preflight_error path=" + path + "," + e);
                }
                if(!slugWebtoon)
                    client.performNtkNativeAckBypass(client.getUrl(path), path);
            } catch(Exception e) {
                android.util.Log.d("ViewerPerf", "viewer_ntk_ack_preflight_error path=" + path + "," + e);
            }
        }, "viewer-ntk-launch-preflight");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.start();
        android.util.Log.d("ViewerPerf", "viewer_ntk_launch_preflight_start path=" + path);
        return true;
    }

    public static void startImmediateNtkGeneratedInitialPrimeForLaunch(Context context, Manga manga) {
        if(context == null || manga == null)
            return;
        CustomHttpClient client = getHttpClient();
        if(client == null)
            return;
        String path = manga.getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return;
        startImmediateNtkGeneratedInitialPrime(client, manga, path);
    }

    private static void startImmediateNtkGeneratedInitialPrime(CustomHttpClient client, Manga manga, String path) {
        if(client == null || manga == null || path == null || path.length() == 0)
            return;
        Context context = client.getContext();
        if(context == null)
            return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^/(webtoon|manhwa)/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(path.trim());
        if(!matcher.find())
            return;
        String segment = matcher.group(1).toLowerCase(Locale.ROOT);
        String pathWorkId = matcher.group(2).trim();
        String pathEpisodeId = matcher.group(3).trim();
        if("webtoon".equals(segment) && !pathEpisodeId.matches("\\d{1,12}")) {
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_skip path=" + path
                    + ",reason=slug_webtoon_non_numeric_episode");
            return;
        }
        if("webtoon".equals(segment) && !pathWorkId.matches("\\d{1,12}")) {
            if(!pathEpisodeId.matches("\\d{1,12}")) {
                android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_skip path=" + path
                        + ",reason=slug_webtoon_non_numeric_episode");
                return;
            }
            int count = manga.getNtkImageCount() > 0 ? manga.getNtkImageCount() : 1;
            ArrayList<String> urls = new ArrayList<>(count);
            for(int page = 1; page <= count; page++) {
                String pageName = String.format(Locale.ROOT, "p%03d.jpeg", page);
                urls.add("https://fifa.worldcup73.xyz/wt/episodes/" + pathWorkId + "/" + pathEpisodeId + "/" + pageName);
            }
            if(urls.size() > 1)
                ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
            String first = urls.isEmpty() ? "" : urls.get(0);
            int immediateCount = Math.min(1, urls.size());
            int startedCount = 0;
            for(int index = 0; index < immediateCount; index++) {
                String image = urls.get(index);
                if(image == null || image.length() == 0)
                    continue;
                if(ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                        context.getApplicationContext(), manga, image, null, false, null, index, true))
                    startedCount++;
            }
            if(urls.size() > 1) {
                final Context appContext = context.getApplicationContext();
                final ArrayList<String> initialUrls = new ArrayList<>(urls.subList(0, Math.min(4, urls.size())));
                Thread adjacentThread = new Thread(() -> {
                    try {
                        Thread.sleep(80L);
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    int adjacentStarted = 0;
                    for(int i = immediateCount; i < initialUrls.size(); i++) {
                        if(ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                                appContext, manga, initialUrls.get(i), null, false, null, i, true))
                            adjacentStarted++;
                    }
                    android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_slug_generated_adjacent_prime path=" + path
                            + ",count=" + initialUrls.size()
                            + ",adjacentStarted=" + adjacentStarted);
                }, "NtkInitialAdjacentPrime");
                adjacentThread.setPriority(Thread.MAX_PRIORITY);
                adjacentThread.start();
                startImmediateNtkGeneratedForwardPrime(appContext, manga, path, urls, 4, 24, 120L,
                        "viewer_ntk_immediate_slug_generated_forward_prime");
            }
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_slug_generated_initial_prime path=" + path
                    + ",count=" + urls.size()
                    + ",started=" + (startedCount > 0)
                    + ",startedCount=" + startedCount
                    + ",first=" + (first.length() == 0 ? "" : first.substring(first.lastIndexOf('/') + 1)));
            return;
        }
        boolean largeUntrustedWebtoonCount = "webtoon".equals(segment) && manga.getNtkImageCount() > 64;
        if(largeUntrustedWebtoonCount && !pathEpisodeId.matches("\\d{1,12}")) {
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_skip path=" + path
                    + ",reason=untrusted_large_webtoon_count,count=" + manga.getNtkImageCount());
            return;
        } else if(largeUntrustedWebtoonCount) {
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_limited path=" + path
                    + ",reason=untrusted_large_webtoon_count_initial_only,count=" + manga.getNtkImageCount());
        }
        String imageWorkId = manga.getNtkImageWorkId() == null ? "" : manga.getNtkImageWorkId().trim();
        if(imageWorkId.length() == 0)
            imageWorkId = pathWorkId;
        String recordedImageEpisodeId = manga.getNtkImageEpisodeId() == null ? "" : manga.getNtkImageEpisodeId().trim();
        CustomHttpClient.NtkCachedImageIdentity cachedIdentity =
                CustomHttpClient.cachedNtkImageIdentity(path);
        if("webtoon".equals(segment)
                && pathWorkId.matches("\\d{1,12}")
                && cachedIdentity != null
                && cachedIdentity.workId != null
                && cachedIdentity.workId.length() > 0
                && !cachedIdentity.workId.matches("\\d{1,12}")
                && cachedIdentity.episodeId != null
                && cachedIdentity.episodeId.matches("\\d{1,12}")
                && cachedIdentity.count > 0
                && cachedIdentity.count <= 128) {
            int cachedCount = Math.min(cachedIdentity.count, 64);
            ArrayList<String> cachedWtUrls = new ArrayList<>(cachedCount);
            for(int page = 1; page <= cachedCount; page++) {
                cachedWtUrls.add("https://fifa.worldcup73.xyz/wt/episodes/"
                        + cachedIdentity.workId + "/" + cachedIdentity.episodeId
                        + "/p" + String.format(Locale.ROOT, "%03d", page) + ".jpeg");
            }
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, cachedWtUrls);
            int immediateCachedCount = Math.min(4, cachedWtUrls.size());
            int cachedStarted = 0;
            for(int index = 0; index < immediateCachedCount; index++) {
                boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                        context.getApplicationContext(), manga, cachedWtUrls.get(index), null, false, null, index, true);
                if(started)
                    cachedStarted++;
            }
            if(cachedWtUrls.size() > immediateCachedCount) {
                startImmediateNtkGeneratedForwardPrime(context.getApplicationContext(), manga, path,
                        cachedWtUrls, immediateCachedCount, 24, 120L,
                        "viewer_ntk_immediate_cached_wt_forward_prime");
            }
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_cached_wt_initial_prime path=" + path
                    + ",slug=" + cachedIdentity.workId
                    + ",episodeId=" + cachedIdentity.episodeId
                    + ",count=" + cachedWtUrls.size()
                    + ",startedCount=" + cachedStarted);
            return;
        }
        String imageEpisodeId;
        if("webtoon".equals(segment) && pathEpisodeId.matches("\\d{1,12}")
                && !pathEpisodeId.equals(recordedImageEpisodeId))
            imageEpisodeId = pathEpisodeId;
        else {
            imageEpisodeId = recordedImageEpisodeId;
            if(imageEpisodeId.length() == 0 && pathEpisodeId.matches("\\d{1,12}"))
                imageEpisodeId = pathEpisodeId;
        }
        if(!imageWorkId.matches("\\d{1,12}") || !imageEpisodeId.matches("\\d{1,12}"))
            return;
        boolean canPrimePathEpisodeWithImageWork = "webtoon".equals(segment)
                && imageWorkId.matches("\\d{1,12}")
                && pathEpisodeId.matches("\\d{1,12}");
        if("webtoon".equals(segment)
                && pathEpisodeId.matches("\\d{1,12}")
                && (largeUntrustedWebtoonCount || recordedImageEpisodeId.matches("\\d{1,12}"))
                && !canPrimePathEpisodeWithImageWork
                && ReaderImageCache.INSTANCE.earlyNtkGeneratedSuccessImageUrls(
                        path, SystemClock.elapsedRealtime() - 30000L).isEmpty()) {
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_defer_unverified path=" + path
                    + ",workId=" + imageWorkId
                    + ",pathEpisodeId=" + pathEpisodeId
                    + ",recordedEpisodeId=" + recordedImageEpisodeId
                    + ",count=" + manga.getNtkImageCount());
            return;
        }
        boolean useWtEpisodeUrls = "webtoon".equals(segment)
                && !pathWorkId.matches("\\d{1,12}")
                && imageWorkId.equals(pathWorkId)
                && recordedImageEpisodeId.matches("\\d{1,12}")
                && !recordedImageEpisodeId.equals(pathEpisodeId)
                && manga.getNtkImageCount() > 0
                && manga.getNtkImageCount() <= 64;
        int initialLimit = "webtoon".equals(segment) && manga.getNtkImageCount() > 0
                ? manga.getNtkImageCount()
                : 4;
        int count = manga.getNtkImageCount() > 0
                ? Math.min(initialLimit, manga.getNtkImageCount())
                : initialLimit;
        ArrayList<String> urls = new ArrayList<>(count);
        String initialExtension = useWtEpisodeUrls ? "jpeg" : ntkGeneratedInitialExtension(segment, pathEpisodeId);
        boolean shouldVerifyBeforeGeneratedPrime = "webtoon".equals(segment)
                && "jpg".equals(initialExtension)
                && manga.getNtkImageCount() > 0
                && imageWorkId.matches("\\d{1,12}")
                && imageEpisodeId.matches("\\d{1,12}")
                && ReaderImageCache.INSTANCE.earlyNtkGeneratedSuccessImageUrls(
                        path, SystemClock.elapsedRealtime() - 30000L).isEmpty();
        if(shouldVerifyBeforeGeneratedPrime) {
            manga.startNtkVerifiedInitialImageProbe(client);
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_defer_verify path=" + path
                    + ",segment=" + segment
                    + ",workId=" + imageWorkId
                    + ",pathWorkId=" + pathWorkId
                    + ",imageEpisodeId=" + imageEpisodeId
                    + ",count=" + manga.getNtkImageCount());
            return;
        }
        for(int page = 1; page <= count; page++) {
            String pageName = String.format(Locale.ROOT, "p%03d.%s", page, initialExtension);
            String url;
            if("webtoon".equals(segment)) {
                url = useWtEpisodeUrls
                        ? "https://fifa.worldcup73.xyz/wt/episodes/" + pathWorkId + "/" + pathEpisodeId + "/" + pageName
                        : "http://fifa.worldcup73.xyz/black/episodes/" + imageWorkId + "/" + imageEpisodeId + "/" + pageName;
            } else {
                url = "http://apihost93.com/" + segment + "/" + imageWorkId + "/" + imageEpisodeId + "/" + pageName;
            }
            urls.add(url);
        }
        ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
        String first = urls.isEmpty() ? "" : urls.get(0);
        boolean webtoon = "webtoon".equals(segment);
        int immediateCount = Math.min(webtoon ? 1 : 2, urls.size());
        int startedCount = 0;
        for(int index = 0; index < immediateCount; index++) {
            String image = urls.get(index);
            if(image == null || image.length() == 0)
                continue;
            boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                    context.getApplicationContext(), manga, image, null, false, null, index, true);
            if(started)
                startedCount++;
        }
        if(webtoon && immediateCount < Math.min(4, urls.size())) {
            final Context appContext = context.getApplicationContext();
            final int adjacentLimit = Math.min(4, urls.size());
            final ArrayList<String> initialUrls = new ArrayList<>(urls.subList(0, adjacentLimit));
            AppDispatchers.runIoDelayed(() -> {
                int adjacentStarted = 0;
                for(int index = immediateCount; index < initialUrls.size(); index++) {
                    String image = initialUrls.get(index);
                    if(image == null || image.length() == 0)
                        continue;
                    if(ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            appContext, manga, image, null, false, null, index, true))
                        adjacentStarted++;
                }
                android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_adjacent_prime path=" + path
                        + ",delayMs=80,count=" + initialUrls.size()
                        + ",adjacentStarted=" + adjacentStarted);
            }, 80L);
        }
        int hedgeStartedCount = 0;
        int hedgeScheduledCount = 0;
        ArrayList<String> immediateExtensionHedgeUrls = new ArrayList<>();
        if(webtoon && immediateCount > 0 && "jpg".equals(initialExtension)) {
            immediateExtensionHedgeUrls = ntkGeneratedExtensionUrls(
                    new ArrayList<>(urls.subList(0, immediateCount)),
                    "jpeg");
            long scheduledAtMs = SystemClock.elapsedRealtime();
            for(int index = 0; index < immediateExtensionHedgeUrls.size(); index++) {
                String image = immediateExtensionHedgeUrls.get(index);
                if(image == null || image.length() == 0 || image.equals(urls.get(index)))
                    continue;
                boolean scheduled = scheduleDelayedInitialJpgHedge(
                        context.getApplicationContext(),
                        manga,
                        path,
                        urls.get(index),
                        image,
                        scheduledAtMs,
                        index);
                if(scheduled)
                    hedgeScheduledCount++;
            }
        }
        int wtHedgeStartedCount = 0;
        if(false && webtoon && immediateCount > 0
                && pathWorkId.matches("\\d{1,12}")
                && pathEpisodeId.matches("\\d{1,12}")) {
            for(int index = 0; index < immediateCount; index++) {
                String pageName = String.format(Locale.ROOT, "p%03d.%s", index + 1, initialExtension);
                String image = "https://fifa.worldcup73.xyz/wt/episodes/"
                        + pathWorkId + "/" + pathEpisodeId + "/" + pageName;
                boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                        context.getApplicationContext(), manga, image, null, false, null, index, true);
                if(started)
                    wtHedgeStartedCount++;
            }
        }
        if(false && webtoon && urls.size() > immediateCount) {
            startImmediateNtkGeneratedForwardPrime(context.getApplicationContext(), manga, path, urls,
                    immediateCount, 24, 160L, "viewer_ntk_immediate_generated_forward_prime");
            ArrayList<String> extensionHedgeUrls = ntkGeneratedExtensionUrls(urls,
                    "jpg".equals(initialExtension) ? "jpeg" : "jpg");
            startImmediateNtkGeneratedForwardPrime(context.getApplicationContext(), manga, path, extensionHedgeUrls,
                    immediateCount, 24, 160L, "viewer_ntk_immediate_generated_forward_prime_extension_hedge");
        }
        boolean jpgHedgeStarted = false;
        boolean jpgHedgeScheduled = false;
        if(webtoon && "jpg".equals(initialExtension) && !urls.isEmpty() && hedgeScheduledCount == 0) {
            String jpgHedge = replaceNtkGeneratedImageExtension(urls.get(0), "jpeg");
            if(jpgHedge.length() > 0 && !jpgHedge.equals(urls.get(0))) {
                jpgHedgeScheduled = scheduleDelayedInitialJpgHedge(
                        context.getApplicationContext(),
                        manga,
                        path,
                        urls.get(0),
                        jpgHedge,
                        SystemClock.elapsedRealtime(),
                        0);
            }
        } else if(hedgeScheduledCount > 0) {
            jpgHedgeScheduled = true;
        }
        android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_prime path=" + path
                + ",segment=" + segment
                + ",workId=" + imageWorkId
                + ",imageEpisodeId=" + imageEpisodeId
                + ",count=" + urls.size()
                + ",started=" + (startedCount > 0)
                + ",startedCount=" + startedCount
                + ",hedgeStartedCount=" + hedgeStartedCount
                + ",hedgeScheduledCount=" + hedgeScheduledCount
                + ",wtHedgeStartedCount=" + wtHedgeStartedCount
                + ",jpgHedgeStarted=" + jpgHedgeStarted
                + ",jpgHedgeScheduled=" + jpgHedgeScheduled
                + ",first=" + (first.length() == 0 ? "" : first.substring(first.lastIndexOf('/') + 1)));
    }

    private static void startImmediateNtkGeneratedForwardPrime(Context appContext,
                                                               Manga manga,
                                                               String path,
                                                               ArrayList<String> urls,
                                                               int startIndex,
                                                               int maxCount,
                                                               long delayMs,
                                                               String logStage) {
        if(appContext == null || manga == null || urls == null || urls.size() <= startIndex || maxCount <= 0)
            return;
        final int safeStart = Math.max(0, startIndex);
        final int safeEnd = Math.min(urls.size(), safeStart + maxCount);
        if(safeStart >= safeEnd)
            return;
        Thread forwardThread = new Thread(() -> {
            try {
                if(delayMs > 0L)
                    Thread.sleep(delayMs);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            int started = 0;
            for(int i = safeStart; i < safeEnd; i++) {
                String image = urls.get(i);
                if(image == null || image.length() == 0)
                    continue;
                if(ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                        appContext, manga, image, null, false, null, i, true))
                    started++;
            }
            android.util.Log.d("ViewerPerf", logStage + " path=" + path
                    + ",start=" + safeStart
                    + ",end=" + safeEnd
                    + ",started=" + started);
        }, "NtkGeneratedForwardPrime");
        forwardThread.setPriority(Thread.NORM_PRIORITY + 1);
        forwardThread.start();
    }

    private static ArrayList<String> ntkGeneratedExtensionUrls(ArrayList<String> urls, String extension) {
        ArrayList<String> variants = new ArrayList<>();
        if(urls == null || urls.isEmpty() || extension == null || extension.length() == 0)
            return variants;
        for(String url : urls) {
            String variant = replaceNtkGeneratedImageExtension(url, extension);
            variants.add(variant.length() == 0 ? url : variant);
        }
        return variants;
    }

    private static boolean scheduleDelayedInitialJpgHedge(Context appContext,
                                                          Manga manga,
                                                          String path,
                                                          String first,
                                                          String jpgHedge,
                                                          long scheduledAtMs,
                                                          int pageIndex) {
        if(appContext == null || manga == null || first == null || first.length() == 0
                || jpgHedge == null || jpgHedge.length() == 0)
            return false;
        AppDispatchers.runIoDelayed(() -> maybeStartDelayedInitialJpgHedge(
                appContext, manga, path, first, jpgHedge, scheduledAtMs, pageIndex), NTK_INITIAL_JPG_HEDGE_DELAY_MS);
        android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_jpg_hedge_scheduled path=" + path
                + ",delayMs=" + NTK_INITIAL_JPG_HEDGE_DELAY_MS
                + ",first=" + first.substring(first.lastIndexOf('/') + 1)
                + ",hedge=" + jpgHedge.substring(jpgHedge.lastIndexOf('/') + 1));
        return true;
    }

    private static void maybeStartDelayedInitialJpgHedge(Context appContext,
                                                         Manga manga,
                                                         String path,
                                                         String first,
                                                         String jpgHedge,
                                                         long scheduledAtMs,
                                                         int pageIndex) {
        long elapsedMs = SystemClock.elapsedRealtime() - scheduledAtMs;
        if(ReaderImageCache.INSTANCE.cachedExactFile(appContext, manga, first) != null
                || hasRecentInitialGeneratedSuccess(path, first)) {
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_jpg_hedge_skip path=" + path
                    + ",reason=primary_ready"
                    + ",elapsedMs=" + elapsedMs
                    + ",first=" + first.substring(first.lastIndexOf('/') + 1));
            return;
        }
        if(!ReaderImageCache.INSTANCE.isKnownNtkGeneratedNotFound(manga, first)) {
            if(elapsedMs < NTK_INITIAL_JPG_HEDGE_MAX_WAIT_MS) {
                AppDispatchers.runIoDelayed(() -> maybeStartDelayedInitialJpgHedge(
                        appContext, manga, path, first, jpgHedge, scheduledAtMs, pageIndex),
                        NTK_INITIAL_JPG_HEDGE_RECHECK_MS);
                android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_jpg_hedge_wait path=" + path
                        + ",reason=primary_not_failed"
                        + ",elapsedMs=" + elapsedMs
                        + ",recheckMs=" + NTK_INITIAL_JPG_HEDGE_RECHECK_MS
                        + ",first=" + first.substring(first.lastIndexOf('/') + 1));
            } else {
                android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_jpg_hedge_skip path=" + path
                        + ",reason=primary_not_failed"
                        + ",elapsedMs=" + elapsedMs
                        + ",first=" + first.substring(first.lastIndexOf('/') + 1));
            }
            return;
        }
        if(ReaderImageCache.INSTANCE.hasActiveFetch(manga, first)
                && elapsedMs < NTK_INITIAL_JPG_HEDGE_MAX_WAIT_MS) {
            AppDispatchers.runIoDelayed(() -> maybeStartDelayedInitialJpgHedge(
                    appContext, manga, path, first, jpgHedge, scheduledAtMs, pageIndex), NTK_INITIAL_JPG_HEDGE_RECHECK_MS);
            android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_jpg_hedge_wait path=" + path
                    + ",elapsedMs=" + elapsedMs
                    + ",recheckMs=" + NTK_INITIAL_JPG_HEDGE_RECHECK_MS
                    + ",first=" + first.substring(first.lastIndexOf('/') + 1));
            return;
        }
        boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                appContext, manga, jpgHedge, null, false, null, pageIndex, true);
        android.util.Log.d("ViewerPerf", "viewer_ntk_immediate_generated_initial_jpg_hedge_start path=" + path
                + ",started=" + started
                + ",elapsedMs=" + elapsedMs
                + ",first=" + first.substring(first.lastIndexOf('/') + 1)
                + ",hedge=" + jpgHedge.substring(jpgHedge.lastIndexOf('/') + 1));
    }

    private static boolean hasRecentInitialGeneratedSuccess(String path, String image) {
        List<String> successes = ReaderImageCache.INSTANCE.earlyNtkGeneratedSuccessImageUrls(
                path, SystemClock.elapsedRealtime() - 30_000L);
        for(String success : successes) {
            if(image.equals(success))
                return true;
        }
        return false;
    }

    private static String ntkGeneratedInitialExtension(String segment, String pathEpisodeId) {
        if("webtoon".equals(segment) && pathEpisodeId != null && pathEpisodeId.matches("\\d{1,12}"))
            return "jpeg";
        return "jpg";
    }

    private static boolean isNtkImageUrlReachableQuickly(String url, int timeoutMs) {
        if(url == null || url.length() == 0)
            return false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(Math.max(250, timeoutMs));
            connection.setReadTimeout(Math.max(250, timeoutMs));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36");
            int code = connection.getResponseCode();
            String type = connection.getContentType();
            return code >= 200 && code < 300 && type != null && type.toLowerCase(Locale.ROOT).startsWith("image/");
        } catch(Exception ignored) {
            return false;
        } finally {
            if(connection != null)
                connection.disconnect();
        }
    }

    private static String replaceNtkGeneratedImageExtension(String url, String extension) {
        if(url == null || url.length() == 0 || extension == null || extension.length() == 0)
            return "";
        int cut = url.length();
        int query = url.indexOf('?');
        if(query >= 0)
            cut = Math.min(cut, query);
        int fragment = url.indexOf('#');
        if(fragment >= 0)
            cut = Math.min(cut, fragment);
        String main = url.substring(0, cut);
        String suffix = url.substring(cut);
        int dot = main.lastIndexOf('.');
        int slash = main.lastIndexOf('/');
        if(dot <= slash)
            return url;
        return main.substring(0, dot + 1) + extension + suffix;
    }

    private static final class NtkUnsignedWorkResult {
        final String workId;
        final List<String> urls;

        NtkUnsignedWorkResult(String workId, List<String> urls) {
            this.workId = workId;
            this.urls = urls;
        }
    }

    private static void startNtkWebtoonMetadataImageApiPreflight(CustomHttpClient client, Manga manga,
                                                                 String path) {
        if(client == null || manga == null || path == null || path.length() == 0)
            return;
        Context context = client.getContext();
        if(context == null)
            return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^/webtoon/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(path.trim());
        if(!matcher.find())
            return;
        boolean slugWebtoon = !matcher.group(1).matches("\\d+")
                || !matcher.group(2).matches("\\d+");
        if(!slugWebtoon)
            return;
        String pathWorkId = matcher.group(1).trim();
        String imageWorkId = manga.getNtkImageWorkId() == null ? "" : manga.getNtkImageWorkId().trim();
        String pathEpisodeId = matcher.group(2).trim();
        LinkedHashSet<String> workIds = new LinkedHashSet<>();
        if(imageWorkId.matches("\\d{1,12}"))
            workIds.add(imageWorkId);
        if(pathWorkId.matches("\\d{1,12}"))
            workIds.add(pathWorkId);
        if(workIds.isEmpty() || pathEpisodeId.length() == 0)
            return;
        Thread thread = new Thread(() -> {
            long startedAt = SystemClock.elapsedRealtime();
            final java.util.concurrent.atomic.AtomicBoolean firstStreamStarted =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            CustomHttpClient.NtkViewerImageUrlsCallback callback = urls -> {
                if(urls == null || urls.isEmpty())
                    return;
                ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
                if(firstStreamStarted.compareAndSet(false, true)) {
                    String first = urls.get(0);
                    boolean started = first != null && first.length() > 0
                            && ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            context.getApplicationContext(), manga, first, null, false, null, 0, true);
                    android.util.Log.d("ViewerPerf",
                            "viewer_ntk_metadata_image_api_first_stream path=" + path
                                    + ",started=" + started
                                    + ",first=" + (first == null ? "" : first.substring(first.lastIndexOf('/') + 1)));
                }
            };
            try {
                java.util.concurrent.ExecutorService executor =
                        java.util.concurrent.Executors.newFixedThreadPool(workIds.size(), runnable -> {
                            Thread worker = new Thread(runnable, "viewer-ntk-unsigned-image-api-worker");
                            worker.setDaemon(true);
                            return worker;
                        });
                java.util.concurrent.CompletionService<NtkUnsignedWorkResult> completion =
                        new java.util.concurrent.ExecutorCompletionService<>(executor);
                int submitted = 0;
                for(String workId : workIds) {
                    completion.submit(() -> new NtkUnsignedWorkResult(workId,
                            client.fetchNtkWebtoonUnsignedViewerImageUrls(
                                    path, workId, pathEpisodeId, callback)));
                    submitted++;
                }
                List<String> urls = new ArrayList<>();
                String hitWorkId = "";
                long deadlineMs = SystemClock.elapsedRealtime() + 12_000L;
                try {
                    for(int i = 0; i < submitted; i++) {
                        long waitMs = Math.max(1L, deadlineMs - SystemClock.elapsedRealtime());
                        java.util.concurrent.Future<NtkUnsignedWorkResult> future =
                                completion.poll(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if(future == null)
                            break;
                        NtkUnsignedWorkResult result = future.get();
                        if(result != null && result.urls != null && !result.urls.isEmpty()) {
                            urls = result.urls;
                            hitWorkId = result.workId;
                            break;
                        }
                    }
                } finally {
                    executor.shutdownNow();
                }
                if(urls != null && !urls.isEmpty()) {
                    ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
                    callback.onUrls(urls);
                }
                android.util.Log.d("ViewerPerf", "viewer_ntk_unsigned_image_api_preflight_done path=" + path
                        + ",count=" + (urls == null ? 0 : urls.size())
                        + ",workIds=" + workIds
                        + ",hitWorkId=" + hitWorkId
                        + ",episodeId=" + pathEpisodeId
                        + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
            } catch(Exception e) {
                android.util.Log.d("ViewerPerf", "viewer_ntk_unsigned_image_api_preflight_error path="
                        + path + "," + e
                        + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
            }
        }, "viewer-ntk-unsigned-image-api");
        thread.setDaemon(true);
        thread.start();
        android.util.Log.d("ViewerPerf", "viewer_ntk_unsigned_image_api_preflight_start path=" + path
                + ",workIds=" + workIds
                + ",episodeId=" + pathEpisodeId);
    }

    private static boolean shouldStartExactEpisodeAtFirstPage(Manga manga) {
        if(manga == null || !manga.useBookmark() || p == null)
            return true;
        return p.getViewerBookmark(manga) <= 0;
    }

    private static boolean isNtkLaunchSource(Title title) {
        String source = title == null ? "" : title.getSourceSite();
        return "ntk".equals(source == null ? "" : source.trim().toLowerCase(Locale.ROOT))
                || (p != null && p.isNtkSite());
    }

    private static synchronized int nextViewerLaunchToken(Context context) {
        int token = ++viewerLaunchSequence;
        viewerLaunchTokens.put(launchTokenKey(context), token);
        return token;
    }

    public static synchronized void cancelPendingViewerLaunches(Context context) {
        if(context == null)
            return;
        viewerLaunchTokens.put(launchTokenKey(context), ++viewerLaunchSequence);
    }

    private static synchronized boolean consumeViewerLaunchToken(Context context, int token, String launchKey) {
        Context key = launchTokenKey(context);
        Integer latest = viewerLaunchTokens.get(key);
        if(latest == null || latest != token)
            return false;
        long now = SystemClock.uptimeMillis();
        Long lastLaunchAt = viewerLaunchTimes.get(key);
        String lastLaunchKey = viewerLaunchKeys.get(key);
        if(lastLaunchAt != null && !shouldAllowViewerLaunch(now, lastLaunchAt, launchKey, lastLaunchKey)) {
            viewerLaunchTokens.put(key, ++viewerLaunchSequence);
            return false;
        }
        viewerLaunchTokens.put(key, ++viewerLaunchSequence);
        viewerLaunchTimes.put(key, now);
        viewerLaunchKeys.put(key, launchKey);
        return true;
    }

    private static synchronized boolean cancelViewerLaunchToken(Context context, int token) {
        Context key = launchTokenKey(context);
        Integer latest = viewerLaunchTokens.get(key);
        if(latest == null || latest != token)
            return false;
        viewerLaunchTokens.put(key, ++viewerLaunchSequence);
        return true;
    }

    static boolean shouldAllowViewerLaunchForTest(long now, long lastLaunchAt) {
        return shouldAllowViewerLaunch(now, lastLaunchAt, "same", "same");
    }

    static boolean shouldAllowViewerLaunchForTest(long now, long lastLaunchAt,
                                                  String launchKey, String lastLaunchKey) {
        return shouldAllowViewerLaunch(now, lastLaunchAt, launchKey, lastLaunchKey);
    }

    private static boolean shouldAllowViewerLaunch(long now, long lastLaunchAt,
                                                   String launchKey, String lastLaunchKey) {
        if(!sameViewerLaunchKey(launchKey, lastLaunchKey))
            return true;
        return now - lastLaunchAt >= VIEWER_LAUNCH_DEBOUNCE_MS;
    }

    private static boolean sameViewerLaunchKey(String launchKey, String lastLaunchKey) {
        if(launchKey == null || launchKey.length() == 0 || lastLaunchKey == null || lastLaunchKey.length() == 0)
            return true;
        return launchKey.equals(lastLaunchKey);
    }

    private static String viewerLaunchDebounceKey(Manga manga, Title title, boolean exactEpisode) {
        if(manga == null)
            return "";
        Title launchTitle = title != null ? title : manga.getTitle();
        return (launchTitle == null ? -1 : launchTitle.getId())
                + ":" + manga.getBaseMode()
                + ":" + manga.getId()
                + ":" + manga.getTitleId()
                + ":" + Manga.safeUrl(manga)
                + ":" + exactEpisode;
    }

    private static Context launchTokenKey(Context context) {
        return context == null ? null : context.getApplicationContext();
    }

    private static boolean canUseActivity(Activity activity) {
        if(activity == null || activity.isFinishing())
            return false;
        return !activity.isDestroyed();
    }

    public static synchronized boolean consumeFocusedDestinationLaunch(Activity activity, long debounceMs) {
        if(!canUseActivity(activity))
            return false;
        long now = SystemClock.uptimeMillis();
        Long lastLaunchAt = focusedDestinationLaunchTimes.get(activity);
        if(lastLaunchAt != null && !shouldAllowDestinationLaunch(now, lastLaunchAt, debounceMs))
            return false;
        focusedDestinationLaunchTimes.put(activity, now);
        return true;
    }

    static boolean shouldAllowDestinationLaunchForTest(long now, long lastLaunchAt, long debounceMs) {
        return shouldAllowDestinationLaunch(now, lastLaunchAt, debounceMs);
    }

    static boolean shouldBlockCaptchaForOffline(boolean connected) {
        return !connected;
    }

    private static boolean shouldAllowDestinationLaunch(long now, long lastLaunchAt, long debounceMs) {
        return now - lastLaunchAt >= debounceMs;
    }

    public static boolean canUseContextForUi(Context context) {
        if(context == null)
            return false;
        if(context instanceof Activity)
            return canUseActivity((Activity) context);
        return true;
    }

    public static void safeToast(Context context, String message, int duration) {
        if(!canUseContextForUi(context) || message == null)
            return;
        try {
            Toast.makeText(context, message, duration).show();
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public static boolean safeStartActivity(Context context, Intent intent) {
        if(!canUseContextForUi(context) || intent == null)
            return false;
        try {
            if(!(context instanceof Activity))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    public static boolean safeShowDialog(AlertDialog.Builder builder) {
        if(builder == null)
            return false;
        try {
            builder.show();
            return true;
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    public static void safeGlideClear(View view) {
        if(view == null || !canUseContextForUi(view.getContext()))
            return;
        try {
            Glide.with(view).clear(view);
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public static void safeGlideLoad(ImageView view, Object model, int placeholderResId) {
        if(view == null || !canUseContextForUi(view.getContext()))
            return;
        try {
            if(placeholderResId != 0)
                Glide.with(view).load(model).placeholder(placeholderResId).into(view);
            else
                Glide.with(view).load(model).into(view);
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            if(placeholderResId != 0)
                view.setImageResource(placeholderResId);
        }
    }

    public static <T> ArrayList<T> snapshotList(List<T> source) {
        if(source == null)
            return new ArrayList<>();
        try {
            return new ArrayList<>(source);
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return new ArrayList<>();
        }
    }

    public static ArrayList<Manga> snapshotEpisodes(Title title) {
        if(title == null)
            return new ArrayList<>();
        ArrayList<Manga> episodes = Title.orderedEpisodeSnapshot(title.getEps());
        return episodes == null ? new ArrayList<>() : episodes;
    }

    public static ArrayList<Manga> snapshotEpisodes(Manga manga) {
        if(manga == null)
            return new ArrayList<>();
        ArrayList<Manga> episodes = Title.orderedEpisodeSnapshot(manga.getEps());
        return episodes == null ? new ArrayList<>() : episodes;
    }

    public static <T> T safeGet(List<T> source, int index) {
        if(source == null || index < 0 || index >= source.size())
            return null;
        try {
            return source.get(index);
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    public static int clampIndex(int index, int size) {
        if(size <= 0)
            return -1;
        return Math.max(0, Math.min(index, size - 1));
    }

    public static String toViewerMangaJson(Manga manga, Title title) {
        return toViewerMangaJson(manga, title, true);
    }

    public static String toViewerMangaJson(Manga manga, Title title, boolean includeEpisodes) {
        return new Gson().toJson(viewerMangaCopy(manga, title, includeEpisodes));
    }

    public static String toViewerTitleJson(Title title) {
        return toViewerTitleJson(title, true);
    }

    public static String toViewerTitleJson(Title title, boolean includeEpisodes) {
        if(title == null)
            return null;
        Title copy = new Title(title.minimize());
        if(includeEpisodes)
            copy.setEps(viewerEpisodeCopies(snapshotEpisodes(title)));
        return new Gson().toJson(copy);
    }

    public static String toViewerTitleJsonForReader(Title title, Manga anchor, boolean includeEpisodes) {
        if(title == null)
            return null;
        if(!includeEpisodes)
            return toViewerTitleJsonAround(title, anchor,
                    VIEWER_TITLE_EPISODE_WINDOW_BEFORE, VIEWER_TITLE_EPISODE_WINDOW_AFTER);
        String full = toViewerTitleJson(title, true);
        if(full == null || full.length() <= VIEWER_TITLE_JSON_SOFT_LIMIT_CHARS)
            return full;
        try {
            ViewerWarmupManager.logMetric("viewer_title_json_windowed_for_binder", snapshotEpisodes(title).size());
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests do not provide android.util.Log; logging is not part of this contract.
        }
        return toViewerTitleJsonAround(title, anchor,
                VIEWER_TITLE_EPISODE_WINDOW_BEFORE, VIEWER_TITLE_EPISODE_WINDOW_AFTER);
    }

    public static String toViewerTitleJsonAround(Title title, Manga anchor, int before, int after) {
        if(title == null)
            return null;
        Title copy = new Title(title.minimize());
        List<Manga> episodes = snapshotEpisodes(title);
        if(episodes == null || episodes.isEmpty())
            return new Gson().toJson(copy);
        int anchorIndex = viewerEpisodeIndex(episodes, anchor);
        if(anchorIndex < 0)
            return new Gson().toJson(copy);
        int first = Math.max(0, anchorIndex - Math.max(0, before));
        int last = Math.min(episodes.size() - 1, anchorIndex + Math.max(0, after));
        copy.setEps(viewerEpisodeCopies(episodes.subList(first, last + 1)));
        return new Gson().toJson(copy);
    }

    private static int viewerEpisodeIndex(List<Manga> episodes, Manga anchor) {
        if(episodes == null || anchor == null)
            return -1;
        String anchorPath = anchor.getNtkEpisodePath();
        String anchorNumber = Manga.visibleEpisodeNumberKey(anchor.getName());
        for(int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if(episode == null)
                continue;
            if(anchor.getId() > 0 && anchor.getId() == episode.getId())
                return i;
            if(anchorPath != null && anchorPath.length() > 0 && anchorPath.equals(episode.getNtkEpisodePath()))
                return i;
            if(anchorNumber != null && anchorNumber.length() > 0
                    && anchorNumber.equals(Manga.visibleEpisodeNumberKey(episode.getName())))
                return i;
        }
        return -1;
    }

    private static Manga viewerMangaCopy(Manga source, Title title) {
        return viewerMangaCopy(source, title, true);
    }

    private static Manga viewerMangaCopy(Manga source, Title title, boolean includeEpisodes) {
        if(source == null)
            return null;
        Manga copy = viewerEpisodeCopy(source, true);
        if(includeEpisodes) {
            List<Manga> episodes = title != null ? snapshotEpisodes(title) : snapshotEpisodes(source);
            copy.setEps(viewerEpisodeCopies(episodes));
        }
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
        copy.setNtkEpisodePath(source.hasExplicitNtkEpisodePath() ? source.getNtkEpisodePath() : "");
        copy.setNtkImageEpisodeId(source.getNtkImageEpisodeId());
        copy.setNtkImageWorkId(source.getNtkImageWorkId());
        copy.setNtkViewerPayloadHint(source.getNtkViewerPayloadHint());
        copy.setNtkImageCount(source.getNtkImageCount());
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
        ArrayList<Manga> episodes = snapshotEpisodes(title);
        if(episodes.size() == 0)
            title.setEps(episodes);
        int index = findEpisodeIndex(episodes, manga);
        if(index < 0) {
            manga.setTitle(title);
            episodes.add(manga);
            title.setEps(episodes);
            index = episodes.size() - 1;
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
        if(snapshotEpisodes(title).size() == 0) {
            Toast.makeText(context, "다운로드할 회차 정보를 불러오지 못했습니다", Toast.LENGTH_SHORT).show();
            return false;
        }
        if(!ensureOfflineHomeWritable(context))
            return false;
        maybeRequestDownloadNotificationPermission(context);
        try {
            DownloadRepository.enqueue(context, title, selected);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            Toast.makeText(context, "다운로드 대기열 저장에 실패했습니다", Toast.LENGTH_SHORT).show();
            return false;
        }
        Toast.makeText(context,"오프라인 저장을 시작합니다.", Toast.LENGTH_LONG).show();
        return true;
    }

    private static void maybeRequestDownloadNotificationPermission(Context context) {
        if(!(context instanceof Activity))
            return;
        Activity activity = (Activity) context;
        if(!canUseActivity(activity))
            return;
        boolean permissionGranted = ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if(shouldRequestNotificationPermissionForDownloads(Build.VERSION.SDK_INT, permissionGranted))
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    DOWNLOAD_NOTIFICATION_PERMISSION_REQUEST);
    }

    static boolean shouldRequestNotificationPermissionForDownloads(int sdkVersion, boolean permissionGranted) {
        return sdkVersion >= Build.VERSION_CODES.TIRAMISU && !permissionGranted;
    }

    private static int findEpisodeIndex(List<Manga> episodes, Manga target) {
        if(episodes == null || target == null)
            return -1;
        for(int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if(episode == null)
                continue;
            if(Manga.sameEpisodeIdentity(episode, target))
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
            safeToast(context,"오프라인 저장 폴더를 먼저 설정해 주세요", Toast.LENGTH_LONG);
            return false;
        }
        if(useScopedStorageHome(homeDir)) {
            DocumentFile home = DocumentFile.fromTreeUri(context, Uri.parse(homeDir));
            if(home == null || !home.canWrite()) {
                safeToast(context,"오프라인 저장 폴더 권한을 다시 설정해 주세요", Toast.LENGTH_LONG);
                return false;
            }
            return true;
        }
        File home = new File(homeDir);
        if(!home.exists() && !home.mkdirs()) {
            safeToast(context,"오프라인 저장 폴더를 만들 수 없습니다", Toast.LENGTH_LONG);
            return false;
        }
        if(!home.canWrite()) {
            safeToast(context,"오프라인 저장 폴더에 쓸 수 없습니다", Toast.LENGTH_LONG);
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
        if(title == null)
            return null;
        for(Manga episode : snapshotEpisodes(title)) {
            if(episode != null && episode.getId() == id && episode.getBaseMode() == baseMode)
                return episode;
        }
        return null;
    }
    public static void showPopup(Context context, String title, String content, DialogInterface.OnClickListener clickListener, DialogInterface.OnCancelListener cancelListener){
        if(!canUseContextForUi(context))
            return;
        AlertDialog.Builder builder;
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("확인", clickListener)
                .setOnCancelListener(cancelListener);
        safeShowDialog(builder);
    }

    public static void showYesNoPopup(Context context, String title, String content,
                                      DialogInterface.OnClickListener posClickListener,
                                      DialogInterface.OnClickListener negClickListener,
                                      DialogInterface.OnCancelListener cancelListener){
        if(!canUseContextForUi(context))
            return;

        AlertDialog.Builder builder;
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("예", posClickListener)
                .setNegativeButton("아니오", negClickListener)
                .setOnCancelListener(cancelListener);
        safeShowDialog(builder);
    }

    public static void showYesNoPopup(boolean dark, Context context, String title, String content,
                                      DialogInterface.OnClickListener posClickListener,
                                      DialogInterface.OnClickListener negClickListener,
                                      DialogInterface.OnCancelListener cancelListener){
        if(!canUseContextForUi(context))
            return;

        AlertDialog.Builder builder;
        if (dark) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("예", posClickListener)
                .setNegativeButton("아니오", negClickListener)
                .setOnCancelListener(cancelListener);
        safeShowDialog(builder);
    }

    public static void showYesNoNeutralPopup(Context context, String title, String content, String neutral,
                                             DialogInterface.OnClickListener posClickListener,
                                             DialogInterface.OnClickListener negClickListener,
                                             DialogInterface.OnClickListener neuClickListener,
                                             DialogInterface.OnCancelListener cancelListener){
        if(!canUseContextForUi(context))
            return;

        AlertDialog.Builder builder;
        if (new Preference(context).getDarkTheme()) builder = new AlertDialog.Builder(context, R.style.darkDialog);
        else builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
                .setMessage(content)
                .setPositiveButton("예", posClickListener)
                .setNegativeButton("아니오", negClickListener)
                .setNeutralButton(neutral, neuClickListener)
                .setOnCancelListener(cancelListener);
        safeShowDialog(builder);
    }

    public static void showErrorPopup(Context context, String message, Exception e, boolean force_close){
        if(!canUseContextForUi(context))
            return;
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
        safeShowDialog(builder);
    }

    public static boolean checkConnection(Context context){
        if(context == null)
            return false;
        try {
            Context targetContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
            ConnectivityManager connectivityManager
                    = (ConnectivityManager) targetContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(connectivityManager == null)
                return false;
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
        } catch (Exception e) {
            return false;
        }
    }



    public static void showCaptchaPopup(String url, Context context, int code, Exception e, boolean force_close, Fragment fragment, Preference p){
        if(canUseContextForUi(context)) {
            if(shouldBlockCaptchaForOffline(checkConnection(context))) {
                showNoConnectionCaptchaFallback(context, force_close);
                return;
            }
            if(showNtkTurnstileCaptchaIfNeeded(url, context, code, fragment, p))
                return;
            boolean offerWarpAssist = shouldOfferNtkWarpAssistForFailure(context);
            boolean autoCaptchaStarted = !offerWarpAssist && shouldOpenCloudflareCaptchaAutomatically()
                    && startCaptchaActivity(context, code, fragment, url);
            if (autoCaptchaStarted) {
                markAutoCloudflareCaptchaStarted();
            } else if (!checkConnection(context)) {
                //no internet
                //showErrorPopup(context, "네트워크 연결이 없습니다.", e, force_close);
                safeToast(context, "네트워크 연결이 없습니다.", Toast.LENGTH_LONG);
                if (force_close && context instanceof Activity) ((Activity) context).finish();
            } else if (!getHttpClient().isNtk() && captchaCount == 0) {
                startCaptchaActivity(context, code, fragment, url);
            } else {
                AlertDialog.Builder builder;
                String title = "오류";
                String content = "정보를 불러오는데 실패하였습니다.";
                if (new Preference(context).getDarkTheme())
                    builder = new AlertDialog.Builder(context, R.style.darkDialog);
                else builder = new AlertDialog.Builder(context);
                if(offerWarpAssist) {
                    title = "NTK 네트워크 차단";
                    content = "현재 네트워크에서 NTK TLS/SNI 경로가 차단된 상태입니다. WARP 또는 VPN을 켠 뒤 다시 시도해 주세요.";
                }
                builder.setTitle(title)
                        .setMessage(content)
                        .setNeutralButton("확인", (dialogInterface, i) -> {
                            if (force_close) ((Activity) context).finish();
                        })
                        .setPositiveButton(offerWarpAssist ? "WARP 열기" : "CAPTCHA 인증",
                                (dialog, which) -> {
                                    if(offerWarpAssist)
                                        openCloudflareWarpAssist(context);
                                    else
                                        startCaptchaActivity(context, code, fragment, url);
                                })
                        .setNegativeButton("URL 설정", (dialogInterface, i) -> urlSettingPopup(context, p))
                        .setOnCancelListener(dialogInterface -> {
                            if (force_close) ((Activity) context).finish();
                        });
                if (e != null && !offerWarpAssist) {
                    builder.setNeutralButton("자세히", (dialog, which) -> showStackTrace(context, e));
                }
                safeShowDialog(builder);
            }
            captchaCount++;
        }
    }

    private static void showNoConnectionCaptchaFallback(Context context, boolean forceClose) {
        safeToast(context, "?ㅽ듃?뚰겕 ?곌껐???놁뒿?덈떎.", Toast.LENGTH_LONG);
        if(forceClose && context instanceof Activity)
            ((Activity) context).finish();
    }

    private static boolean shouldOpenCloudflareCaptchaAutomatically() {
        if(!getHttpClient().isNtk())
            return false;
        if(!getHttpClient().hasRecentCloudflareChallenge())
            return false;
        if(getHttpClient().hasNtkAccessProof() && !getHttpClient().hasRecentCloudflareChallenge())
            return false;
        long now = System.currentTimeMillis();
        if(now - lastAutoCloudflareCaptchaAt < NTK_CAPTCHA_ACTIVITY_MIN_INTERVAL_MS)
            return false;
        return true;
    }

    private static void markAutoCloudflareCaptchaStarted() {
        lastAutoCloudflareCaptchaAt = System.currentTimeMillis();
    }

    public static boolean showNtkTurnstileCaptchaIfNeeded(String url, Context context, int code, Fragment fragment, Preference preference) {
        if(!canUseContextForUi(context) || !getHttpClient().isNtk())
            return false;
        if(shouldBlockCaptchaForOffline(checkConnection(context)))
            return false;
        syncNtkCloudflareCookies(preference, false);
        if(isNtkEpisodeUrl(url))
            return false;
        if(getHttpClient().hasNtkAccessProof())
            return verifyNtkAccessAndOpenCaptchaIfNeeded(context, code, fragment, preference);
        if(openRecentNtkCloudflareChallenge(context, code, fragment))
            return true;
        if(shouldOpenCloudflareCaptchaAutomatically()) {
            if(startCaptchaActivity(context, code, fragment, null)) {
                markAutoCloudflareCaptchaStarted();
                captchaCount++;
                return true;
            }
        }
        return false;
    }

    private static boolean shouldOfferNtkWarpAssistForFailure(Context context) {
        try {
            return shouldOfferNtkWarpAssistForFailureForTest(getHttpClient().isNtk(),
                    activeNetworkHasVpn(context),
                    getHttpClient().hasNtkAccessProof(),
                    getHttpClient().hasRecentCloudflareChallenge(),
                    getHttpClient().hasRecentNtkHardBlock());
        } catch (Exception e) {
            return false;
        }
    }

    static boolean shouldOfferNtkWarpAssistForFailureForTest(boolean ntk, boolean vpnActive,
                                                             boolean accessProof,
                                                             boolean recentChallenge,
                                                             boolean recentHardBlock) {
        return ntk && !vpnActive && !accessProof && (!recentChallenge || recentHardBlock);
    }

    public static boolean hasRecentCloudflareWarpAssistLaunch(long windowMs) {
        return hasRecentCloudflareWarpAssistLaunchForTest(lastCloudflareWarpAssistStartedAtMs,
                SystemClock.uptimeMillis(), windowMs);
    }

    static boolean hasRecentCloudflareWarpAssistLaunchForTest(long startedAtMs, long nowMs, long windowMs) {
        return startedAtMs > 0L && windowMs > 0L && nowMs >= startedAtMs && nowMs - startedAtMs <= windowMs;
    }

    private static boolean activeNetworkHasVpn(Context context) {
        if(context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            return false;
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if(manager == null)
                return false;
            android.net.Network network = manager.getActiveNetwork();
            if(network == null)
                return false;
            android.net.NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean openCloudflareWarpAssist(Context context) {
        if(context == null)
            return false;
        if(openInstalledPackage(context, CLOUDFLARE_WARP_PACKAGE))
            return markCloudflareWarpAssistStarted("installed-warp");
        if(openInstalledPackage(context, CLOUDFLARE_ONE_PACKAGE))
            return markCloudflareWarpAssistStarted("installed-cloudflare-one");
        boolean functionalPlayStore = isFunctionalPlayStoreInstalled(context);
        if(functionalPlayStore) {
            if(openPlayStoreMarket(context, CLOUDFLARE_WARP_PACKAGE))
                return markCloudflareWarpAssistStarted("market-warp");
            if(openPlayStoreMarket(context, CLOUDFLARE_ONE_PACKAGE))
                return markCloudflareWarpAssistStarted("market-cloudflare-one");
        }
        if(shouldPreferVpnSettingsBeforeWebPlayForTest(functionalPlayStore)
                && openVpnSettings(context))
            return markCloudflareWarpAssistStarted("vpn-settings");
        if(shouldAllowWebPlayFallbackForTest(functionalPlayStore)) {
            if(openPlayStoreWeb(context, CLOUDFLARE_WARP_PACKAGE))
                return markCloudflareWarpAssistStarted("web-play-warp");
            if(openPlayStoreWeb(context, CLOUDFLARE_ONE_PACKAGE))
                return markCloudflareWarpAssistStarted("web-play-cloudflare-one");
        }
        if(functionalPlayStore && openVpnSettings(context))
            return markCloudflareWarpAssistStarted("vpn-settings-after-market");
        safeToast(context, "VPN settings could not be opened.", Toast.LENGTH_SHORT);
        android.util.Log.d("ViewerPerf", "ntk_warp_assist_failed functionalPlayStore=" + functionalPlayStore);
        return false;
    }

    public static boolean openCloudflareWarpAssistForCurrentNtkHardBlock(Context context) {
        try {
            boolean shouldOffer = shouldOfferNtkWarpAssistForFailure(context);
            boolean recentLaunch = hasRecentCloudflareWarpAssistLaunch(
                    NTK_WARP_ASSIST_AUTO_LAUNCH_MIN_INTERVAL_MS);
            boolean shouldOpen = shouldAutoOpenNtkWarpAssistForHardBlockForTest(
                    shouldOffer,
                    getHttpClient().hasRecentNtkHardBlock(),
                    recentLaunch);
            android.util.Log.d("ViewerPerf", "ntk_warp_assist_auto_check shouldOffer="
                    + shouldOffer + ",recentHardBlock=" + getHttpClient().hasRecentNtkHardBlock()
                    + ",recentLaunch=" + recentLaunch + ",shouldOpen=" + shouldOpen);
            return shouldOpen && openCloudflareWarpAssist(context);
        } catch (Exception e) {
            android.util.Log.d("ViewerPerf", "ntk_warp_assist_auto_error " + e);
            return false;
        }
    }

    static boolean shouldAutoOpenNtkWarpAssistForHardBlockForTest(boolean shouldOffer,
                                                                  boolean recentHardBlock,
                                                                  boolean recentLaunch) {
        return shouldOffer && recentHardBlock && !recentLaunch;
    }

    static boolean shouldPreferVpnSettingsBeforeWebPlayForTest(boolean functionalPlayStore) {
        return !functionalPlayStore;
    }

    static boolean shouldAllowWebPlayFallbackForTest(boolean functionalPlayStore) {
        return functionalPlayStore;
    }

    private static boolean markCloudflareWarpAssistStarted(String route) {
        lastCloudflareWarpAssistStartedAtMs = SystemClock.uptimeMillis();
        android.util.Log.d("ViewerPerf", "ntk_warp_assist_route route=" + route);
        return true;
    }

    private static boolean openInstalledPackage(Context context, String packageName) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if(intent == null)
                return false;
            addNewTaskFlagIfNeeded(context, intent);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean openPlayStoreMarket(Context context, String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + packageName));
            intent.setPackage("com.android.vending");
            addNewTaskFlagIfNeeded(context, intent);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean openPlayStoreWeb(Context context, String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + packageName));
            addNewTaskFlagIfNeeded(context, intent);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean openVpnSettings(Context context) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_VPN_SETTINGS);
            intent.setPackage("com.android.settings");
            addNewTaskFlagIfNeeded(context, intent);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
        }
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_VPN_SETTINGS);
            intent.setClassName("com.android.settings", "com.android.settings.Settings$VpnSettingsActivity");
            addNewTaskFlagIfNeeded(context, intent);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
        }
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            intent.setPackage("com.android.settings");
            addNewTaskFlagIfNeeded(context, intent);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isFunctionalPlayStoreInstalled(Context context) {
        if(context == null)
            return false;
        try {
            String sourceDir = context.getPackageManager()
                    .getApplicationInfo("com.android.vending", 0).sourceDir;
            return isFunctionalPlayStoreForTest("com.android.vending", sourceDir);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isFunctionalPlayStoreForTest(String packageName, String sourceDir) {
        if(!"com.android.vending".equals(packageName))
            return false;
        String lower = sourceDir == null ? "" : sourceDir.toLowerCase(Locale.ROOT);
        return !lower.contains("/licensechecker") && !lower.endsWith("licensechecker.apk");
    }

    private static void addNewTaskFlagIfNeeded(Context context, Intent intent) {
        if(!(context instanceof Activity))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static boolean showNtkTurnstileCaptchaIfNeeded(Context context, int code, Fragment fragment, Preference preference) {
        return showNtkTurnstileCaptchaIfNeeded(null, context, code, fragment, preference);
    }

    public static boolean startNtkTurnstileCaptchaIfNeeded(Context context, int code, Fragment fragment, Preference preference) {
        if(!canUseContextForUi(context) || !getHttpClient().isNtk())
            return false;
        if(shouldBlockCaptchaForOffline(checkConnection(context)))
            return false;
        syncNtkCloudflareCookies(preference, false);
        if(!getHttpClient().hasNtkAccessProof()) {
            if(startCaptchaActivity(context, code, fragment, null)) {
                captchaCount++;
                return true;
            }
            return false;
        }
        verifyNtkAccessAndOpenCaptchaIfNeeded(context, code, fragment, preference);
        return false;
    }

    private static boolean shouldSuppressNtkCaptchaAfterRecentVerification() {
        CustomHttpClient client = getHttpClient();
        return shouldSuppressNtkCaptchaAfterRecentVerificationForTest(
                client.isNtk(), client.hasNtkAccessProof(), client.hasRecentCloudflareChallenge());
    }

    static boolean shouldSuppressNtkCaptchaAfterRecentVerificationForTest(boolean ntk,
                                                                          boolean accessProof,
                                                                          boolean recentChallenge) {
        return ntk && accessProof && !recentChallenge;
    }

    public static boolean verifyNtkAccessAndOpenCaptchaIfNeeded(Context context, int code, Fragment fragment, Preference preference) {
        if(!canUseContextForUi(context) || !getHttpClient().isNtk())
            return false;
        if(shouldBlockCaptchaForOffline(checkConnection(context)))
            return false;
        if(shouldSuppressNtkCaptchaAfterRecentVerification())
            return false;
        syncNtkCloudflareCookies(preference, false);
        if(shouldSuppressNtkCaptchaAfterRecentVerification())
            return false;
        AppDispatchers.runUserAction(() -> {
            if(shouldSuppressNtkCaptchaAfterRecentVerification())
                return;
            boolean challenged = isNtkAccessChallengeActive();
            if(challenged) {
                if(shouldSuppressNtkCaptchaAfterRecentVerification())
                    return;
                clearNtkChallengeCookies(preference);
                AppDispatchers.runOnMain(() -> {
                    if(!canUseContextForUi(context) || shouldSuppressNtkCaptchaAfterRecentVerification())
                        return;
                    if(startCaptchaActivity(context, code, fragment, null))
                        captchaCount++;
                });
            } else {
                getHttpClient().markNtkAccessVerified();
            }
        });
        return false;
    }

    private static boolean openRecentNtkCloudflareChallenge(Context context, int code, Fragment fragment) {
        String challengedUrl = getHttpClient().getLastCloudflareChallengeUrl();
        if(challengedUrl == null || challengedUrl.length() == 0)
            return false;
        if(!getHttpClient().isNtkUrl(challengedUrl) && !isSafeNtkPageImageUrl(challengedUrl))
            return false;
        if(!getHttpClient().hasRecentCloudflareChallenge())
            return false;
        if(!startCaptchaActivity(context, code, fragment, null))
            return false;
        captchaCount++;
        return true;
    }

    public static boolean startNtkTurnstileCaptcha(Context context, int code, Fragment fragment, Preference preference) {
        if(!canUseContextForUi(context) || !getHttpClient().isNtk())
            return false;
        if(shouldBlockCaptchaForOffline(checkConnection(context)))
            return false;
        syncNtkCloudflareCookies(preference, true);
        if(!getHttpClient().hasNtkAccessProof()) {
            if(startCaptchaActivity(context, code, fragment, null)) {
                captchaCount++;
                return true;
            }
            return false;
        }
        AppDispatchers.runUserAction(() -> {
            if(shouldSuppressNtkCaptchaAfterRecentVerification())
                return;
            if(!isNtkAccessChallengeActive())
                return;
            if(shouldSuppressNtkCaptchaAfterRecentVerification())
                return;
            clearNtkChallengeCookies(preference);
            AppDispatchers.runOnMain(() -> {
                if(!canUseContextForUi(context) || shouldSuppressNtkCaptchaAfterRecentVerification())
                    return;
                if(startCaptchaActivity(context, code, fragment, null))
                    captchaCount++;
            });
        });
        return true;
    }

    private static void clearNtkChallengeCookies(Preference preference) {
        Preference source = preference != null ? preference : p;
        if(source != null)
            getHttpClient().clearCloudflareWebViewCookies(source.getWebtoonUrl(), source.getUrl());
        else {
            getHttpClient().clearCloudflareCookies();
            getHttpClient().clearNtkAccessVerification();
        }
    }

    private static boolean isNtkAccessChallengeActive() {
        if(isNtkAccessPathChallenged("/api/manhwa-list?page=1&pageSize=1&withTotal=1"))
            return true;
        return isNtkAccessPathChallenged("");
    }

    private static boolean isNtkAccessPathChallenged(String path) {
        okhttp3.Response response = null;
        try {
            response = getHttpClient().mget(path, true);
            if(response == null)
                return NtkCaptchaPolicy.isAccessProbeChallenged(false, 0, null, false);
            int code = response.code();
            String body = CustomHttpClient.readBody(response);
            response = null;
            return NtkCaptchaPolicy.isAccessProbeChallenged(true, code, body, getHttpClient().isCloudflareChallengeResponse(code, body));
        } catch (Exception e) {
            String message = e.getMessage();
            return message != null && message.toLowerCase(Locale.ROOT).contains("cloudflare");
        } finally {
            if(response != null)
                response.close();
        }
    }

    private static boolean isNtkEpisodeUrl(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("^(https?://[^/]+)?/(webtoon|manhwa)/\\d+/\\d+.*");
    }

    private static void syncNtkCloudflareCookies(Preference preference) {
        syncNtkCloudflareCookies(preference, false);
    }

    private static void syncNtkCloudflareCookies(Preference preference, boolean allowWebViewSync) {
        Preference source = preference != null ? preference : p;
        if(source == null)
            return;
        getHttpClient().restoreClearanceFromDisk();
        if(getHttpClient().hasFreshCloudflareClearance() || !allowWebViewSync)
            return;
        long now = System.currentTimeMillis();
        if(now - lastNtkWebViewCookieSyncAt < NTK_WEBVIEW_COOKIE_SYNC_INTERVAL_MS)
            return;
        lastNtkWebViewCookieSyncAt = now;
        getHttpClient().syncCookiesFromWebView(source.getWebtoonUrl(), true);
        getHttpClient().syncCookiesFromWebView(source.getUrl(), true);
    }

    static boolean startCaptchaActivity(Context context, int code, Fragment fragment, String url){
        if(!canUseContextForUi(context))
            return false;
        if(shouldBlockCaptchaForOffline(checkConnection(context))) {
            showNoConnectionCaptchaFallback(context, false);
            return false;
        }
        if(shouldSkipNtkCaptchaLaunch())
            return false;
        long now = System.currentTimeMillis();
        long minInterval = getHttpClient().isNtk()
                ? NTK_CAPTCHA_ACTIVITY_MIN_INTERVAL_MS
                : CAPTCHA_ACTIVITY_MIN_INTERVAL_MS;
        if(now - lastCaptchaActivityStartedAt < minInterval)
            return false;
        lastCaptchaActivityStartedAt = now;
        Intent captchaIntent = new Intent(context, CaptchaActivity.class);
        url = captchaUrl(url);
        captchaIntent.putExtra("url", url);
        try {
            if(fragment == null && context instanceof Activity) {
                ((Activity)context).startActivityForResult(captchaIntent, code);
                return true;
            } else if(fragment != null && fragment.isAdded()) {
                fragment.startActivityForResult(captchaIntent, code);
                return true;
            }
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return false;
    }

    static boolean startCaptchaActivity(Context context, int code, Fragment fragment){
        if(!canUseContextForUi(context))
            return false;
        if(shouldBlockCaptchaForOffline(checkConnection(context))) {
            showNoConnectionCaptchaFallback(context, false);
            return false;
        }
        if(shouldSkipNtkCaptchaLaunch())
            return false;
        long now = System.currentTimeMillis();
        long minInterval = getHttpClient().isNtk()
                ? NTK_CAPTCHA_ACTIVITY_MIN_INTERVAL_MS
                : CAPTCHA_ACTIVITY_MIN_INTERVAL_MS;
        if(now - lastCaptchaActivityStartedAt < minInterval)
            return false;
        lastCaptchaActivityStartedAt = now;
        Intent captchaIntent = new Intent(context, CaptchaActivity.class);
        captchaIntent.putExtra("url", captchaUrl(null));
        try {
            if(fragment == null && context instanceof Activity) {
                ((Activity)context).startActivityForResult(captchaIntent, code);
                return true;
            } else if(fragment != null && fragment.isAdded()) {
                fragment.startActivityForResult(captchaIntent, code);
                return true;
            }
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return false;
    }

    private static boolean shouldSkipNtkCaptchaLaunch() {
        CustomHttpClient client = getHttpClient();
        return shouldSkipNtkCaptchaLaunchForTest(
                client.isNtk(), client.hasNtkAccessProof(), client.hasRecentCloudflareChallenge());
    }

    static boolean shouldSkipNtkCaptchaLaunchForTest(boolean ntk, boolean accessProof,
                                                     boolean recentChallenge) {
        return ntk && accessProof && !recentChallenge;
    }

    private static String captchaUrl(String url) {
        if(getHttpClient().isNtk()) {
            String challengedUrl = recentNtkCaptchaChallengeUrl(url);
            if(isSafeNtkPageImageUrl(challengedUrl))
                return challengedUrl;
            if(url != null && url.length() > 0) {
                if(url.startsWith("http://") || url.startsWith("https://")) {
                    if((getHttpClient().isNtkUrl(url) || isSafeNtkPageImageUrl(url)) && !isNtkApiUrl(url))
                        return url;
                    return ntkCaptchaLandingUrl();
                }
                if(url.startsWith("/"))
                    return getHttpClient().getUrl(url) + url;
                return getHttpClient().getUrl() + "/" + url;
            }
            if(challengedUrl != null)
                return challengedUrl;
            return ntkCaptchaLandingUrl();
        }
        if(url != null && url.length() > 0) {
            if(url.startsWith("http://") || url.startsWith("https://"))
                return url;
            if(url.startsWith("/"))
                return getHttpClient().getUrl(url) + url;
            return getHttpClient().getUrl() + "/" + url;
        }
        return null;
    }

    private static String recentNtkCaptchaChallengeUrl(String preferredUrl) {
        if(!getHttpClient().hasRecentCloudflareChallenge())
            return null;
        String challengedUrl = getHttpClient().getLastCloudflareChallengeUrl();
        if(challengedUrl == null || challengedUrl.length() == 0)
            return null;
        if(!getHttpClient().isNtkUrl(challengedUrl) && !isSafeNtkPageImageUrl(challengedUrl))
            return null;
        if(isNtkApiUrl(challengedUrl))
            return null;
        if(isSafeNtkPageImageUrl(challengedUrl) && !sameNtkEpisodeScope(challengedUrl, preferredUrl))
            return null;
        return challengedUrl;
    }

    private static boolean sameNtkEpisodeScope(String challengedUrl, String preferredUrl) {
        String preferredEpisode = ntkEpisodeScope(preferredUrl);
        if(preferredEpisode == null)
            return true;
        String challengedEpisode = ntkEpisodeScope(challengedUrl);
        return challengedEpisode == null || preferredEpisode.equals(challengedEpisode);
    }

    private static String ntkEpisodeScope(String url) {
        if(url == null || url.length() == 0)
            return null;
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if(path == null || path.length() == 0)
                return null;
            String[] parts = path.split("/");
            ArrayList<String> segments = new ArrayList<>();
            for(String part : parts) {
                if(part != null && part.length() > 0)
                    segments.add(part.toLowerCase(Locale.ROOT));
            }
            for(int i = 0; i + 2 < segments.size(); i++) {
                String first = segments.get(i);
                if(("webtoon".equals(first) || "manhwa".equals(first))
                        && isSafeNtkEpisodeSegment(segments.get(i + 1))
                        && isSafeNtkEpisodeSegment(segments.get(i + 2)))
                    return "/" + first + "/" + segments.get(i + 1) + "/" + segments.get(i + 2);
                if(("black".equals(first) || "blacktoon".equals(first) || "wt".equals(first))
                        && i + 3 < segments.size()
                        && "episodes".equals(segments.get(i + 1))
                        && isSafeNtkEpisodeSegment(segments.get(i + 2))
                        && isSafeNtkEpisodeSegment(segments.get(i + 3)))
                    return "/webtoon/" + segments.get(i + 2) + "/" + segments.get(i + 3);
            }
        } catch(Exception ignored) {
        }
        return null;
    }

    private static boolean isSafeNtkEpisodeSegment(String segment) {
        if(segment == null || segment.length() == 0)
            return false;
        for(int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if(!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'))
                return false;
        }
        return true;
    }

    private static String ntkCaptchaLandingUrl() {
        String webtoonUrl = p == null ? "" : p.getWebtoonUrl();
        if(webtoonUrl != null && webtoonUrl.length() > 0 && getHttpClient().isNtkUrl(webtoonUrl))
            return webtoonUrl;
        String root = getHttpClient().getUrl();
        if(root != null && root.endsWith("/manhwa"))
            root = root.substring(0, root.length() - 7);
        return root;
    }

    private static boolean isNtkApiUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        try {
            String path = Uri.parse(url).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).startsWith("/api/");
        } catch (Exception e) {
            return false;
        }
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


    public static GlideUrl getGlideUrl(String image){
        return getGlideUrl(image, guessImageBaseMode(image));
    }

    public static String viewerImageRequestUrl(String image, int baseMode) {
        return normalizeImageUrl(image, baseMode);
    }

    public static Map<String, String> viewerImageRequestHeaders(String image, int baseMode) {
        String referer = getHttpClient().getUrl(baseMode);
        String url = normalizeImageUrl(image, baseMode);
        boolean ntkSiteUrl = getHttpClient().isNtkUrl(url);
        boolean protectedImageHost = isProtectedImageHost(url);
        boolean ntkImage = ntkSiteUrl || protectedImageHost;
        if(ntkImage)
            referer = getSiteRoot(baseMode);
        String cookie = cookieHeaderForViewerImage(url, baseMode, ntkSiteUrl, protectedImageHost);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", referer);
        headers.put("User-Agent", getHttpClient().agent);
        if(cookie != null && cookie.length() > 0)
            headers.put("Cookie", cookie);
        if(ntkImage) {
            headers.put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Sec-Fetch-Dest", "image");
            headers.put("Sec-Fetch-Mode", "no-cors");
            headers.put("Sec-Fetch-Site", secFetchSiteForViewerImage(referer, url));
            addClientHintHeaderMap(headers);
        }
        return headers;
    }

    public static GlideUrl getGlideUrl(String image, int baseMode){
        String referer = getHttpClient().getUrl(baseMode);
        String url = normalizeImageUrl(image, baseMode);
        boolean ntkSiteUrl = getHttpClient().isNtkUrl(url);
        boolean protectedImageHost = isProtectedImageHost(url);
        boolean ntkImage = ntkSiteUrl || protectedImageHost;
        if(ntkImage)
            referer = getSiteRoot(baseMode);
        String cookie = cookieHeaderForViewerImage(url, baseMode, ntkSiteUrl, protectedImageHost);
        String cacheKey = baseMode + "|" + url + "|" + referer + "|" + getHttpClient().agent + "|" + (cookie == null ? "" : cookie);
        synchronized (glideUrlCache) {
            GlideUrl cached = glideUrlCache.get(cacheKey);
            if(cached != null)
                return cached;
        }
        LazyHeaders.Builder headers = new LazyHeaders.Builder()
                .addHeader("Referer", referer)
                .addHeader("User-Agent", getHttpClient().agent);
        if(cookie != null && cookie.length() > 0)
            headers.addHeader("Cookie", cookie);
        if(ntkImage) {
            headers.addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            headers.addHeader("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.addHeader("Sec-Fetch-Dest", "image");
            headers.addHeader("Sec-Fetch-Mode", "no-cors");
            headers.addHeader("Sec-Fetch-Site", secFetchSiteForViewerImage(referer, url));
            addClientHintHeaders(headers);
        }
        GlideUrl glideUrl = new GlideUrl(url, headers.build());
        synchronized (glideUrlCache) {
            glideUrlCache.put(cacheKey, glideUrl);
        }
        return glideUrl;
    }

    private static void addClientHintHeaderMap(Map<String, String> headers) {
        headers.put("sec-ch-ua", CustomHttpClient.clientHintUa(getHttpClient().agent));
        headers.put("sec-ch-ua-mobile", CustomHttpClient.clientHintMobile(getHttpClient().agent));
        headers.put("sec-ch-ua-platform", CustomHttpClient.clientHintPlatform(getHttpClient().agent));
    }

    private static void addClientHintHeaders(LazyHeaders.Builder headers) {
        headers.addHeader("sec-ch-ua", CustomHttpClient.clientHintUa(getHttpClient().agent));
        headers.addHeader("sec-ch-ua-mobile", CustomHttpClient.clientHintMobile(getHttpClient().agent));
        headers.addHeader("sec-ch-ua-platform", CustomHttpClient.clientHintPlatform(getHttpClient().agent));
    }

    private static String secFetchSiteForViewerImage(String referer, String imageUrl) {
        try {
            String refererHost = URI.create(referer).getHost();
            String imageHost = URI.create(imageUrl).getHost();
            if(refererHost == null || imageHost == null)
                return "same-origin";
            refererHost = refererHost.toLowerCase(Locale.ROOT);
            imageHost = imageHost.toLowerCase(Locale.ROOT);
            if(refererHost.equals(imageHost))
                return "same-origin";
            return "cross-site";
        } catch(Exception ignored) {
            return "same-origin";
        }
    }

    static String secFetchSiteForViewerImageForTest(String referer, String imageUrl) {
        return secFetchSiteForViewerImage(referer, imageUrl);
    }

    private static boolean isProtectedImageHost(String url) {
        if(url == null)
            return false;
        try {
            android.net.Uri parsed = android.net.Uri.parse(url);
            String host = parsed.getHost();
            if(host == null)
                return false;
            host = host.toLowerCase(Locale.ROOT);
            String path = parsed.getEncodedPath();
            if(path != null
                    && isSafeNtkPageImagePath(path.toLowerCase(Locale.ROOT))
                    && !host.contains("naver")
                    && !host.contains("pstatic"))
                return true;
            return host.matches("y\\d+stm\\.com")
                    || host.matches("w\\d+cloud\\.com")
                    || host.matches("i\\d+\\.imgcloud\\d+\\.com")
                    || host.matches("flysky\\d*m\\.com")
                    || host.matches("apihost\\d*\\.com")
                    || "moamoabon.com".equals(host)
                    || host.matches("fvcdn\\d*\\.com")
                    || host.matches("aws-cdn\\d*\\.site")
                    || host.matches("[a-z0-9-]+\\.worldcup\\d+\\.xyz");
        } catch (Exception e) {
            return false;
        }
    }

    private static int chromeMajorVersion(String userAgent) {
        try {
            if(userAgent == null)
                return -1;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(userAgent);
            if(!matcher.find())
                return -1;
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return -1;
        }
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
            return normalizeVolatileNtkImageCdn("https:" + url);
        if(url.startsWith("/"))
            return normalizeVolatileNtkImageCdn(getSiteRoot(baseMode) + url);
        if(!url.startsWith("http") && !url.contains("://"))
            return normalizeVolatileNtkImageCdn(getSiteRoot(baseMode) + "/" + url);
        return normalizeVolatileNtkImageCdn(url);
    }

    private static String normalizeVolatileNtkImageCdn(String url) {
        if(url == null || url.length() == 0)
            return "";
        try {
            Uri parsed = Uri.parse(url);
            String host = parsed.getHost();
            String path = parsed.getEncodedPath();
            if(host == null || path == null)
                return url;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if(!lowerHost.matches("aws-cdn\\d*\\.site")
                    && !lowerHost.matches("flysky\\d*m\\.com")
                    && !lowerHost.matches("apihost\\d*\\.com")
                    && !"moamoabon.com".equals(lowerHost)
                    && !lowerHost.matches("fvcdn\\d*\\.com")
                    && !lowerHost.matches("[a-z0-9-]+\\.worldcup\\d+\\.xyz"))
                return url;
            if(!isSafeNtkPageImagePath(lowerPath))
                return url;
            if("https".equalsIgnoreCase(parsed.getScheme())
                    && lowerHost.matches("[a-z0-9-]+\\.worldcup\\d+\\.xyz")
                    && (lowerPath.contains("/black/episodes/")
                    || lowerPath.contains("/webtoon_uploads/")
                    || lowerPath.contains("/manhwa_uploads/")
                    || lowerPath.contains("/comic_uploads/")))
                return parsed.buildUpon().scheme("http").build().toString();
            return url;
        } catch(Exception ignored) {
            return url;
        }
    }

    private static boolean isSafeNtkPageImagePath(String path) {
        if(path == null || path.length() == 0)
            return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if(lower.startsWith("/api/")
                || lower.startsWith("/cdn-cgi/")
                || lower.contains("/challenge")
                || lower.contains("/turnstile")
                || lower.contains("/cloudflare")
                || lower.contains("/verification")
                || lower.contains("/captcha")
                || lower.contains("/banner")
                || lower.contains("/advert")
                || lower.contains("/sponsor")
                || lower.contains("/popup")
                || lower.contains("/ads/")
                || lower.contains("/ad/"))
            return false;
        return lower.contains("/blacktoon/episodes/")
                || lower.contains("/black/episodes/")
                || lower.contains("/manhwa/")
                || lower.contains("/webtoon/")
                || lower.contains("/wt/episodes/")
                || lower.contains("/webtoon_uploads/")
                || lower.contains("/manhwa_uploads/")
                || lower.contains("/comic_uploads/");
    }

    private static String cookieHeaderForViewerImage(String url, int baseMode,
                                                     boolean ntkSiteUrl,
                                                     boolean protectedImageHost) {
        String nativeCookie = getHttpClient().getCookieHeader();
        if(!protectedImageHost || ntkSiteUrl || !isSafeNtkPageImageUrl(url))
            return nativeCookie;
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        mergeCookieHeader(merged, nativeCookie);
        try {
            CookieManager manager = CookieManager.getInstance();
            mergeCookieHeader(merged, manager.getCookie(getSiteRoot(baseMode)));
            mergeCookieHeader(merged, manager.getCookie(url));
        } catch(Exception ignored) {
        }
        StringBuilder builder = new StringBuilder();
        for(Map.Entry<String, String> entry : merged.entrySet()) {
            if(entry.getKey() == null || entry.getKey().length() == 0
                    || entry.getValue() == null || entry.getValue().length() == 0)
                continue;
            if(builder.length() > 0)
                builder.append("; ");
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static boolean isSafeNtkPageImageUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        try {
            Uri parsed = Uri.parse(url);
            String path = parsed.getEncodedPath();
            return path != null && isSafeNtkPageImagePath(path);
        } catch(Exception ignored) {
            return false;
        }
    }

    private static void mergeCookieHeader(LinkedHashMap<String, String> out, String header) {
        if(out == null || header == null || header.length() == 0)
            return;
        String[] parts = header.split(";");
        for(String part : parts) {
            if(part == null)
                continue;
            String trimmed = part.trim();
            int split = trimmed.indexOf('=');
            if(split <= 0 || split >= trimmed.length() - 1)
                continue;
            out.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1).trim());
        }
    }

    private static String getSiteRoot(int baseMode) {
        String url = getHttpClient().getUrl(baseMode);
        while(url.endsWith("/"))
            url = url.substring(0, url.length() - 1);
        if(url.endsWith("/cm"))
            return url.substring(0, url.length() - 3);
        if(url.endsWith("/manhwa"))
            return url.substring(0, url.length() - 7);
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
        try (InputStream input = new FileInputStream(data)) {
            return readUtf8Text(input, false);
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return "";
    }

    static String readTextStreamForTest(InputStream input) {
        return readUtf8Text(input, false);
    }

    private static String readUtf8Text(InputStream input, boolean appendLineBreaks) {
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line);
                if(appendLineBreaks)
                    raw.append('\n');
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return raw.toString();
    }

    public static Bitmap getSample(Bitmap input, int width){
        //scale down bitmap to avoid outofmem exception
        width = sampleWidth(input.getWidth(), width);
        if(input.getWidth()<=width) return input;
        else{
            //ratio
            int height = sampleHeight(input.getWidth(), input.getHeight(), width);
            return Bitmap.createScaledBitmap(input, width, height,false);
        }
    }

    static int sampleWidthForTest(int inputWidth, int requestedWidth) {
        return sampleWidth(inputWidth, requestedWidth);
    }

    static int sampleHeightForTest(int inputWidth, int inputHeight, int targetWidth) {
        return sampleHeight(inputWidth, inputHeight, sampleWidth(inputWidth, targetWidth));
    }

    private static int sampleWidth(int inputWidth, int requestedWidth) {
        if(inputWidth <= 0)
            return 1;
        if(requestedWidth <= 0)
            return 1;
        return Math.max(1, Math.min(inputWidth, requestedWidth));
    }

    private static int sampleHeight(int inputWidth, int inputHeight, int targetWidth) {
        if(inputWidth <= 0 || inputHeight <= 0)
            return 1;
        float ratio = (float) inputHeight / (float) inputWidth;
        return Math.max(1, Math.round(ratio * Math.max(1, targetWidth)));
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
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public static boolean writePreferenceToFile(Context c, File f){
        try (FileOutputStream stream = new FileOutputStream(f)) {
            stream.write(utf8Bytes(readPref(c)));
            stream.flush();
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
        return true;
    }

    public static boolean writePreferenceToFile(Context c, Uri uri){
        try (OutputStream stream = c.getContentResolver().openOutputStream(uri)) {
            if(stream == null)
                return false;
            stream.write(utf8Bytes(readPref(c)));
            stream.flush();
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
        return true;
    }

    static byte[] utf8BytesForTest(String text) {
        return utf8Bytes(text);
    }

    private static byte[] utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
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
        editor.putString("webtoonUrl",data.getString("webtoonUrl", CustomHttpClient.WEBTOON_URL));
        editor.putString("defUrl",data.getString("defUrl", "설정되지 않음"));
        editor.putInt("baseMode", data.getInt("baseMode", MTitle.base_comic));
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
            p.forceWfwfSitePresetIfNeeded();
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
        return true;
    }

    public static boolean readPreferenceFromFile(Preference p, Context c, Uri uri){
        try {
            CustomJSONObject data = new CustomJSONObject(readUriToString(c, uri));
            jsonToPref(c, data);
            p.init(c);
            p.forceWfwfSitePresetIfNeeded();
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
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
            data.put("webtoonUrl",sharedPref.getString("webtoonUrl", CustomHttpClient.WEBTOON_URL));
            data.put("defUrl",sharedPref.getString("defUrl", "설정되지 않음"));
            data.put("baseMode", sharedPref.getInt("baseMode", MTitle.base_comic));
            data.put("autoUrl", sharedPref.getBoolean("autoUrl", true));
            data.put("prevPageKey", sharedPref.getInt("prevPageKey", -1));
            data.put("nextPageKey", sharedPref.getInt("nextPageKey", -1));
            data.put("pageControlButtonOffset", sharedPref.getFloat("pageControlButtonOffset", -1));
        }catch(Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
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
        openViewerPrepared(context, manga, code, returnToEpisodes, true, false,
                manga == null ? null : manga.getTitle(), manga == null || !manga.isOnline() || isMinimalOnlineViewerManga(manga));
    }

    private static boolean isMinimalOnlineViewerManga(Manga manga) {
        if(manga == null || !manga.isOnline())
            return false;
        String name = manga.getName();
        if(name != null && name.length() > 0)
            return false;
        try {
            List<String> images = manga.getImgs(null);
            return images == null || images.size() == 0;
        } catch (Exception ignored) {
            return true;
        }
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
        if(input == null || input.isEmpty()) return -1;
        for(int i = 0; i < input.length(); i++) {
            if(Character.digit(input.charAt(i),10) < 0){
                if(i>0) {
                    try {
                        return Integer.parseInt(input.substring(0,i));
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
                else
                    return -1;
            }
        }
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return -1;
        }
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
        File[] episodeFiles = new File(path).listFiles(pathname -> pathname.isDirectory() && isCompleteOfflineDirectory(pathname));
        if(episodeFiles == null)
            return new ArrayList<>();
        //sort
        Arrays.sort(episodeFiles, (left, right) -> compareDocumentNames(
                left == null ? null : left.getName(),
                right == null ? null : right.getName()));
        //add as manga
        return Arrays.asList(episodeFiles);
    }
    public static List<DocumentFile> getOfflineEpisodes(DocumentFile home){
        if(home == null)
            return new ArrayList<>();
        DocumentFile[] files = home.listFiles();
        if(files == null)
            return new ArrayList<>();
        Arrays.sort(files, (documentFile, t1) -> compareDocumentNames(
                documentFile == null ? null : documentFile.getName(),
                t1 == null ? null : t1.getName()));
        List<DocumentFile> res = new ArrayList<>();
        for(DocumentFile f : files){
            if(f != null && f.isDirectory() && isCompleteOfflineDirectory(f)) res.add(f);
        }
        return res;
    }

    private static boolean isCompleteOfflineDirectory(File directory) {
        return directory != null && !new File(directory, "downloading").exists();
    }

    private static boolean isCompleteOfflineDirectory(DocumentFile directory) {
        return directory != null && directory.findFile("downloading") == null;
    }

    static int compareDocumentNamesForTest(String left, String right) {
        return compareDocumentNames(left, right);
    }

    private static int compareDocumentNames(String left, String right) {
        if(left == null && right == null)
            return 0;
        if(left == null)
            return 1;
        if(right == null)
            return -1;
        int leftNumber = leadingNumber(left);
        int rightNumber = leadingNumber(right);
        if(leftNumber >= 0 && rightNumber >= 0 && leftNumber != rightNumber)
            return Integer.compare(leftNumber, rightNumber);
        return left.compareTo(right);
    }

    private static int leadingNumber(String value) {
        if(value == null || value.length() == 0)
            return -1;
        int end = 0;
        while(end < value.length() && Character.isDigit(value.charAt(end)))
            end++;
        if(end == 0)
            return -1;
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean useScopedStorageHome(String homeDir) {
        return homeDir != null
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
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return readUtf8Text(in, true);
        }catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return "";
    }

    public static final int CODE_SCOPED_STORAGE = 21;

}
