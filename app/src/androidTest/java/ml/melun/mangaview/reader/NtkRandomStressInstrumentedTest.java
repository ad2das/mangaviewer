package ml.melun.mangaview.reader;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.ReaderV2Activity;
import ml.melun.mangaview.activity.ViewerIntentContract;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

public class NtkRandomStressInstrumentedTest {
    private static final String TAG = "ViewerPerf";
    private static final int PAGE_SIZE = 30;
    private static final long PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final String[] MODES = new String[]{"generated", "native-ack", "api-fallback"};

    @Test
    public void randomNtkEpisodesOpenAndScroll() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        Context context = ApplicationProvider.getApplicationContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Bundle args = InstrumentationRegistry.getArguments();
        int runs = parsePositiveInt(arg(args, "ntkRandomRuns", "12"), 12);
        int scrollSteps = parsePositiveInt(arg(args, "ntkScrollSteps", "8"), 8);
        long seed = parseLong(arg(args, "ntkRandomSeed", ""), SystemClock.elapsedRealtime());
        Random random = new Random(seed);
        CustomHttpClient client = MainApplication.getHttpClient();
        MainApplication.p.setNtkSitePreset("https://sbxh4.com");

        Log.d(TAG, "ntk_true_random_start runs=" + runs
                + ",seed=" + seed
                + ",scrollSteps=" + scrollSteps);
        for(int run = 0; run < runs; run++) {
            int baseMode = random.nextBoolean() ? MTitle.base_comic : MTitle.base_webtoon;
            MainApplication.p.setBaseMode(baseMode);
            Title title = pickRandomTitle(client, random, baseMode);
            int fetchResult = title.fetchEps(client);
            assertTrue("Expected NTK episode list for run=" + run
                    + " title=" + title.getName()
                    + " id=" + title.getId()
                    + " result=" + fetchResult,
                    fetchResult == Title.LOAD_OK && title.getEps() != null && title.getEps().size() > 0);
            Manga episode = pickRandomEpisode(title.getEps(), random);
            assertTrue("Expected picked NTK episode path for run=" + run
                            + " title=" + title.getName()
                            + " episode=" + episode.getName(),
                    episode.getNtkEpisodePath().length() > 0);
            String mode = MODES[random.nextInt(MODES.length)];
            runReaderCase(context, device, run, mode, title, episode, scrollSteps);
        }
    }

    private static Title pickRandomTitle(CustomHttpClient client, Random random, int baseMode) throws Exception {
        String listPath = listPath(baseMode, 1);
        CustomHttpClient.PageResponse first = client.mgetCachedPage(listPath, PAGE_CACHE_TTL_MS);
        JSONObject firstJson = new JSONObject(first.body == null ? "{}" : first.body);
        int total = Math.max(0, firstJson.optInt("total", 0));
        int maxPage = Math.max(1, total <= 0 ? 80 : (int)Math.ceil(total / (double)PAGE_SIZE));
        for(int attempt = 0; attempt < 8; attempt++) {
            int page = 1 + random.nextInt(maxPage);
            CustomHttpClient.PageResponse response = client.mgetCachedPage(listPath(baseMode, page), PAGE_CACHE_TTL_MS);
            JSONObject json = new JSONObject(response.body == null ? "{}" : response.body);
            JSONArray works = json.optJSONArray("works");
            if(works == null || works.length() == 0)
                continue;
            JSONObject work = works.optJSONObject(random.nextInt(works.length()));
            Title title = titleFromWork(work, baseMode);
            if(title != null) {
                Log.d(TAG, "ntk_true_random_title baseMode=" + baseMode
                        + ",page=" + page
                        + ",id=" + title.getId()
                        + ",path=" + title.getPath()
                        + ",name=" + title.getName());
                return title;
            }
        }
        throw new AssertionError("Unable to pick random NTK title baseMode=" + baseMode);
    }

    private static String listPath(int baseMode, int page) {
        String api = baseMode == MTitle.base_webtoon ? "/api/works" : "/api/manhwa-list";
        return api + "?page=" + page + "&pageSize=" + PAGE_SIZE + "&withTotal=1";
    }

    private static Title titleFromWork(JSONObject work, int baseMode) {
        if(work == null)
            return null;
        String sourceWorkId = firstNonEmpty(work.optString("sourceWorkId", ""), work.optString("id", ""));
        String name = work.optString("title", "").trim();
        if(sourceWorkId.length() == 0 || name.length() == 0)
            return null;
        int id = parsePositiveInt(sourceWorkId, stableId(sourceWorkId));
        Title title = new Title(name, work.optString("thumbnailUrl", ""), "", new ArrayList<>(),
                releaseLabel(work), id, baseMode);
        title.setSourceSite("ntk");
        title.setPath(titlePath(baseMode, sourceWorkId));
        return title;
    }

    private static String titlePath(int baseMode, String sourceWorkId) {
        String value = sourceWorkId == null ? "" : sourceWorkId.trim();
        if(value.startsWith("/manhwa/") || value.startsWith("/webtoon/"))
            return value;
        String segment = baseMode == MTitle.base_webtoon ? "webtoon" : "manhwa";
        return "/" + segment + "/" + value;
    }

    private static String releaseLabel(JSONObject work) {
        String ep = work.optString("ep", "").trim();
        if(ep.length() > 0)
            return ep;
        String latest = work.optString("latestEpisodeNumber", "").trim();
        return latest.length() == 0 ? "" : latest;
    }

    private static Manga pickRandomEpisode(List<Manga> episodes, Random random) {
        ArrayList<Manga> candidates = new ArrayList<>();
        for(Manga episode : episodes) {
            if(episode != null && episode.getNtkEpisodePath().length() > 0)
                candidates.add(episode);
        }
        assertTrue("Expected at least one episode with NTK path", candidates.size() > 0);
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static void runReaderCase(Context context, UiDevice device, int run, String mode,
                                      Title title, Manga episode, int scrollSteps) {
        Activity activity = null;
        long startedAt = SystemClock.elapsedRealtime();
        try {
            episode.setMode(0);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            Manga.setNtkViewerFetchModeOverrideForTest(mode);
            Log.d(TAG, "ntk_true_random_case_start run=" + run
                    + ",mode=" + mode
                    + ",baseMode=" + title.getBaseMode()
                    + ",titleId=" + title.getId()
                    + ",episodeId=" + episode.getId()
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",title=" + title.getName()
                    + ",episode=" + episode.getName());
            activity = InstrumentationRegistry.getInstrumentation()
                    .startActivitySync(viewerIntent(context, title, episode));
            boolean ready = waitForDrawableReady(activity, device, 16000L);
            long firstMs = SystemClock.elapsedRealtime() - startedAt;
            Log.d(TAG, "ntk_true_random_first_drawable run=" + run
                    + ",mode=" + mode
                    + ",ready=" + ready
                    + ",ms=" + firstMs
                    + ",path=" + episode.getNtkEpisodePath());
            assertTrue("Expected first drawable run=" + run
                    + " mode=" + mode
                    + " path=" + episode.getNtkEpisodePath()
                    + " elapsedMs=" + firstMs, ready);
            probeScrollContinuity(context, device, run, mode, episode, scrollSteps);
        } finally {
            Manga.clearNtkViewerFetchModeOverrideForTest();
            if(activity != null)
                activity.finish();
            device.wait(Until.gone(By.desc("reader-drawable-ready")), 3000L);
            device.waitForIdle(1500L);
        }
    }

    private static Intent viewerIntent(Context context, Title title, Manga episode) {
        Intent intent = new Intent(context, ReaderV2Activity.class);
        intent.putExtra("online", true);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("manga", Utils.toViewerMangaJson(episode, title));
        intent.putExtra(ViewerIntentContract.EXTRA_EXACT_EPISODE, true);
        intent.putExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, true);
        intent.putExtra("viewerLaunchStartedAtMs", SystemClock.elapsedRealtime());
        intent.putExtra("viewerLaunchSourceSite", "ntk");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return intent;
    }

    private static boolean waitForDrawableReady(Activity activity, UiDevice device, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while(SystemClock.elapsedRealtime() < deadline) {
            if(device.wait(Until.hasObject(By.desc("reader-drawable-ready")), 250L))
                return true;
            if(activityHasDrawableReadyMarker(activity))
                return true;
            SystemClock.sleep(120L);
        }
        return device.hasObject(By.desc("reader-drawable-ready"))
                || activityHasDrawableReadyMarker(activity);
    }

    private static boolean activityHasDrawableReadyMarker(Activity activity) {
        if(activity == null)
            return false;
        final boolean[] ready = new boolean[]{false};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View strip = activity.findViewById(ml.melun.mangaview.R.id.strip);
            CharSequence description = strip == null ? null : strip.getContentDescription();
            ready[0] = description != null && "reader-drawable-ready".contentEquals(description);
        });
        return ready[0];
    }

    private static void probeScrollContinuity(Context context, UiDevice device, int run,
                                              String mode, Manga episode, int steps) {
        File screenshot = new File(context.getExternalCacheDir(), "ntk-random-scroll-" + run + ".png");
        int width = Math.max(1, device.getDisplayWidth());
        int height = Math.max(1, device.getDisplayHeight());
        int startX = width / 2;
        int startY = (int)(height * 0.82f);
        int endY = (int)(height * 0.24f);
        for(int step = 0; step < steps; step++) {
            long before = SystemClock.elapsedRealtime();
            device.swipe(startX, startY, startX, endY, 36);
            long swipeAt = SystemClock.elapsedRealtime();
            device.waitForIdle(450L);
            long idleAt = SystemClock.elapsedRealtime();
            boolean captured = device.takeScreenshot(screenshot);
            String stats = captured ? screenshotStats(screenshot) : "screenshot=false";
            Log.d(TAG, "ntk_true_random_scroll run=" + run
                    + ",mode=" + mode
                    + ",step=" + step
                    + ",elapsedMs=" + (SystemClock.elapsedRealtime() - before)
                    + ",idleMs=" + (idleAt - swipeAt)
                    + ",path=" + episode.getNtkEpisodePath()
                    + "," + stats);
        }
    }

    private static String screenshotStats(File screenshot) {
        Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        if(bitmap == null)
            return "screenshot=false";
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int sampled = 0;
            int nearWhite = 0;
            int nearBlack = 0;
            int lowColor = 0;
            int stepX = Math.max(1, width / 64);
            int stepY = Math.max(1, height / 96);
            for(int y = height / 12; y < height - height / 12; y += stepY) {
                for(int x = width / 8; x < width - width / 8; x += stepX) {
                    int pixel = bitmap.getPixel(x, y);
                    int red = Color.red(pixel);
                    int green = Color.green(pixel);
                    int blue = Color.blue(pixel);
                    sampled++;
                    if(red > 245 && green > 245 && blue > 245)
                        nearWhite++;
                    if(red < 10 && green < 10 && blue < 10)
                        nearBlack++;
                    if(Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) < 6)
                        lowColor++;
                }
            }
            return "screenshot=true,width=" + width
                    + ",height=" + height
                    + ",whitePct=" + pct(nearWhite, sampled)
                    + ",blackPct=" + pct(nearBlack, sampled)
                    + ",lowColorPct=" + pct(lowColor, sampled);
        } finally {
            bitmap.recycle();
        }
    }

    private static String pct(int value, int total) {
        if(total <= 0)
            return "0.0";
        return String.format(Locale.US, "%.1f", value * 100.0 / total);
    }

    private static String arg(Bundle args, String key, String fallback) {
        String value = args == null ? null : args.getString(key);
        return value == null ? fallback : value;
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && first.trim().length() > 0 ? first.trim() : second == null ? "" : second.trim();
    }

    private static int stableId(String value) {
        int hash = 0x811c9dc5;
        for(int i = 0; value != null && i < value.length(); i++)
            hash = (hash ^ value.charAt(i)) * 0x01000193;
        hash &= 0x7fffffff;
        return hash == 0 ? 1 : hash;
    }
}
