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

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.ReaderV2Activity;
import ml.melun.mangaview.activity.ViewerIntentContract;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

public class NtkFirstScreenSmokeInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Test
    public void ntkFirstScreenAppearsAcrossSampleEpisodes() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        Context context = ApplicationProvider.getApplicationContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Bundle arguments = InstrumentationRegistry.getArguments();
        String siteRoot = arguments == null ? "https://sbxh4.com"
                : arguments.getString("ntkSiteRoot", "https://sbxh4.com");
        MainApplication.p.setNtkSitePreset(siteRoot);
        MainApplication.p.setBaseMode(MTitle.base_webtoon);

        String requestedCase = arguments == null ? "" : arguments.getString("ntkCase", "");
        String customPath = arguments == null ? "" : arguments.getString("ntkPath", "");
        String customUserAgent = arguments == null ? "" : arguments.getString("ntkUserAgent", "");
        if(customUserAgent.trim().length() > 0) {
            MainApplication.getHttpClient().agent = customUserAgent.trim();
            Log.d("ViewerPerf", "ntk_first_screen_user_agent=" + customUserAgent.trim());
        }
        boolean scrollProbe = arguments != null && Boolean.parseBoolean(arguments.getString("ntkScroll", "false"));
        int scrollSteps = arguments == null ? 6 : parsePositiveInt(arguments.getString("ntkScrollSteps", "6"), 6);
        int randomRuns = arguments == null ? 0 : parsePositiveInt(arguments.getString("ntkRandomRuns", "0"), 0);
        boolean realModesOnly = arguments != null && Boolean.parseBoolean(arguments.getString("ntkRealModesOnly", "false"));
        long randomSeed = arguments == null ? SystemClock.elapsedRealtime()
                : parseLong(arguments.getString("ntkRandomSeed", ""), SystemClock.elapsedRealtime());
        if(customPath.length() > 0) {
            Case custom = new Case(
                    arguments.getString("ntkName", "custom-ntk"),
                    arguments.getString("ntkMode", "api-fallback"),
                    parsePositiveInt(arguments.getString("ntkTitleId", "1"), 1),
                    parsePositiveInt(arguments.getString("ntkEpisodeId", "1"), 1),
                    arguments.getString("ntkEpisodeName", "custom"),
                    parseBaseMode(arguments.getString("ntkBaseMode", "webtoon")),
                    customPath,
                    parsePositiveInt(arguments.getString("ntkImageCount", "0"), 0));
            runCase(context, device, custom, scrollProbe, scrollSteps);
            return;
        }
        List<Case> cases = allCases();
        if(realModesOnly && requestedCase.length() == 0)
            cases = realModeCases(cases);
        if(randomRuns > 0 && requestedCase.length() == 0) {
            runRandomCases(context, device, cases, randomRuns, randomSeed, scrollProbe, scrollSteps);
            return;
        }
        boolean ranCase = false;
        for(Case sample : cases) {
            if(requestedCase.length() > 0 && !requestedCase.equals(sample.name))
                continue;
            ranCase = true;
            runCase(context, device, sample, scrollProbe, scrollSteps);
        }
        assertTrue("No NTK smoke case matched " + requestedCase, ranCase);
    }

    private static void runRandomCases(Context context, UiDevice device, List<Case> cases,
                                       int randomRuns, long randomSeed,
                                       boolean scrollProbe, int scrollSteps) {
        assertTrue("No NTK smoke cases configured", cases != null && cases.size() > 0);
        Random random = new Random(randomSeed);
        Log.d(TAG, "ntk_random_start runs=" + randomRuns
                + ",seed=" + randomSeed
                + ",scroll=" + scrollProbe
                + ",scrollSteps=" + scrollSteps);
        ArrayList<Case> shuffled = new ArrayList<>(cases);
        for(int run = 0; run < randomRuns; run++) {
            if(run % shuffled.size() == 0)
                Collections.shuffle(shuffled, random);
            Case sample = shuffled.get(run % shuffled.size());
            Log.d(TAG, "ntk_random_pick run=" + run
                    + ",name=" + sample.name
                    + ",mode=" + sample.mode
                    + ",path=" + sample.path);
            runCase(context, device, sample, scrollProbe, scrollSteps);
        }
    }

    private static List<Case> allCases() {
        return Arrays.asList(
                new Case("webtoon-generated-80", "generated", 18768, 80, "80화", MTitle.base_webtoon, "/webtoon/18768/1577290", 37),
                new Case("webtoon-native-ack-81", "native-ack", 18768, 81, "81화", MTitle.base_webtoon, "/webtoon/18768/1585983", 37),
                new Case("webtoon-api-fallback-82", "api-fallback", 18768, 82, "82화", MTitle.base_webtoon, "/webtoon/18768/1586173", 37),
                new Case("webtoon-api-strict-83", "api-strict", 18768, 83, "83화", MTitle.base_webtoon, "/webtoon/18768/1586501", 39),
                new Case("webtoon-generated-large", "generated", 15741, 103, "103화", MTitle.base_webtoon, "/webtoon/15741/1585893", 180),
                new Case("webtoon-slug-beasts-7", "api-fallback", 719578232, 7, "7화", MTitle.base_webtoon, "/webtoon/u-mp9vqiuy-y68e/lz-beasts_that_cross_the_line-7021779758331478", 28),
                new Case("manhwa-native-ack-numeric", "native-ack", 4127, 15, "15화", MTitle.base_comic, "/manhwa/4127/251114", 28),
                new Case("manhwa-api-fallback-numeric", "api-fallback", 3540, 255, "255화", MTitle.base_comic, "/manhwa/3540/135918", 22),
                new Case("manhwa-api-strict-slug", "api-strict", 25541, 168, "168화", MTitle.base_comic, "/manhwa/25541/u-mp3wtr15-sxjg", 20),
                new Case("manhwa-native-strict-slug", "native-strict", 8044, 117, "117화", MTitle.base_comic, "/manhwa/8044/u-mp9phqym-9fo4", 38)
        );
    }

    private static List<Case> realModeCases(List<Case> cases) {
        ArrayList<Case> filtered = new ArrayList<>();
        for(Case sample : cases) {
            if("generated".equals(sample.mode)
                    || "native-ack".equals(sample.mode)
                    || "api-fallback".equals(sample.mode)) {
                if(isGeneratedNumericSample(sample))
                    filtered.add(sample);
            }
        }
        return filtered;
    }

    private static boolean isGeneratedNumericSample(Case sample) {
        return sample != null
                && sample.imageCount > 0
                && sample.path != null
                && sample.path.matches("^/(?:manhwa|webtoon)/\\d+/\\d+(?:[/?#].*)?$");
    }

    private static void runCase(Context context, UiDevice device, Case sample,
                                boolean scrollProbe, int scrollSteps) {
        Activity activity = null;
        long startedAt = SystemClock.elapsedRealtime();
        try {
            Log.d(TAG, "ntk_first_screen_case_start name=" + sample.name
                    + ",mode=" + sample.mode
                    + ",path=" + sample.path);
            MainApplication.p.setBaseMode(sample.baseMode);
            Manga.setNtkViewerFetchModeOverrideForTest(sample.mode);
            activity = InstrumentationRegistry.getInstrumentation()
                    .startActivitySync(viewerIntent(context, sample));
            boolean ready = waitForDrawableReady(activity, device, 15000L);
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            Log.d(TAG, "ntk_first_screen_case name=" + sample.name
                    + ",mode=" + sample.mode
                    + ",path=" + sample.path
                    + ",ready=" + ready
                    + ",ms=" + elapsed);
            assertTrue("Expected first screen drawable for " + sample.name
                    + " path=" + sample.path + " elapsedMs=" + elapsed, ready);
            if(scrollProbe)
                assertScrollContinuity(context, device, sample, scrollSteps);
        } finally {
            Manga.clearNtkViewerFetchModeOverrideForTest();
            if(activity != null)
                activity.finish();
            device.wait(Until.gone(By.desc("reader-drawable-ready")), 3000L);
            device.waitForIdle(2000L);
        }
    }

    private static boolean waitForDrawableReady(Activity activity, UiDevice device, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while(SystemClock.elapsedRealtime() < deadline) {
            if(device.wait(Until.hasObject(By.desc("reader-drawable-ready")), 250L))
                return true;
            if(activityHasDrawableReadyMarker(activity))
                return true;
            SystemClock.sleep(150L);
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

    private static void assertScrollContinuity(Context context, UiDevice device, Case sample, int steps) {
        File screenshot = new File(context.getExternalCacheDir(), "ntk-scroll-probe-" + sample.name + ".png");
        int width = Math.max(1, device.getDisplayWidth());
        int height = Math.max(1, device.getDisplayHeight());
        int startX = width / 2;
        int startY = (int)(height * 0.82f);
        int endY = (int)(height * 0.24f);
        for(int step = 0; step < steps; step++) {
            long before = SystemClock.elapsedRealtime();
            device.swipe(startX, startY, startX, endY, 36);
            long swipeAt = SystemClock.elapsedRealtime();
            device.waitForIdle(350L);
            long idleAt = SystemClock.elapsedRealtime();
            boolean captured = device.takeScreenshot(screenshot);
            long elapsed = SystemClock.elapsedRealtime() - before;
            long idleElapsed = idleAt - swipeAt;
            ScreenshotStats stats = captured ? screenshotStats(screenshot) : null;
            String visual = stats == null ? "screenshot=false" : stats.toLogString();
            Log.d(TAG, "ntk_scroll_probe name=" + sample.name
                    + ",mode=" + sample.mode
                    + ",step=" + step
                    + ",elapsedMs=" + elapsed
                    + ",idleMs=" + idleElapsed
                    + "," + visual);
            assertTrue("Expected non-blank NTK reader screenshot for " + sample.name
                            + " step=" + step + " stats=" + visual,
                    stats != null && stats.whitePct < 98.0 && stats.lowColorPct < 98.0);
        }
    }

    private static ScreenshotStats screenshotStats(File screenshot) {
        Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        if(bitmap == null)
            return null;
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
            return new ScreenshotStats(width, height,
                    pctValue(nearWhite, sampled),
                    pctValue(nearBlack, sampled),
                    pctValue(lowColor, sampled));
        } finally {
            bitmap.recycle();
        }
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value);
            return parsed >= 0 ? parsed : fallback;
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

    private static int parseBaseMode(String value) {
        return "manhwa".equalsIgnoreCase(value) || "comic".equalsIgnoreCase(value)
                ? MTitle.base_comic
                : MTitle.base_webtoon;
    }

    private static String pct(int value, int total) {
        return String.format(java.util.Locale.US, "%.1f", pctValue(value, total));
    }

    private static double pctValue(int value, int total) {
        if(total <= 0)
            return 0.0;
        return value * 100.0 / total;
    }

    private static Intent viewerIntent(Context context, Case sample) {
        Title title = new Title(sample.name, "", "", Collections.singletonList("ntk"), "", sample.titleId, sample.baseMode);
        title.setSourceSite("ntk");
        Manga episode = new Manga(sample.episodeId, sample.episodeName, "", sample.baseMode);
        episode.setMode(0);
        episode.setTitle(title);
        episode.setTitleId(sample.titleId);
        episode.setNtkEpisodePath(sample.path);
        episode.setNtkImageCount(sample.imageCount);
        title.setEps(Collections.singletonList(episode));

        Intent intent = new Intent(context, ReaderV2Activity.class);
        intent.putExtra("online", true);
        intent.putExtra("title", Utils.toViewerTitleJsonForReader(title, episode, true));
        intent.putExtra("manga", Utils.toViewerMangaJson(episode, title, false));
        intent.putExtra(ViewerIntentContract.EXTRA_EXACT_EPISODE, true);
        intent.putExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, true);
        intent.putExtra("viewerLaunchStartedAtMs", SystemClock.elapsedRealtime());
        intent.putExtra("viewerLaunchSourceSite", "ntk");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return intent;
    }

    private static final class Case {
        final String name;
        final String mode;
        final int titleId;
        final int episodeId;
        final String episodeName;
        final int baseMode;
        final String path;
        final int imageCount;

        Case(String name, String mode, int titleId, int episodeId, String episodeName, int baseMode, String path, int imageCount) {
            this.name = name;
            this.mode = mode;
            this.titleId = titleId;
            this.episodeId = episodeId;
            this.episodeName = episodeName;
            this.baseMode = baseMode;
            this.path = path;
            this.imageCount = imageCount;
        }
    }

    private static final class ScreenshotStats {
        final int width;
        final int height;
        final double whitePct;
        final double blackPct;
        final double lowColorPct;

        ScreenshotStats(int width, int height, double whitePct, double blackPct, double lowColorPct) {
            this.width = width;
            this.height = height;
            this.whitePct = whitePct;
            this.blackPct = blackPct;
            this.lowColorPct = lowColorPct;
        }

        String toLogString() {
            return "screenshot=true,width=" + width
                    + ",height=" + height
                    + ",whitePct=" + String.format(java.util.Locale.US, "%.1f", whitePct)
                    + ",blackPct=" + String.format(java.util.Locale.US, "%.1f", blackPct)
                    + ",lowColorPct=" + String.format(java.util.Locale.US, "%.1f", lowColorPct);
        }
    }
}
