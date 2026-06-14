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
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.ClassificationDbStore;
import ml.melun.mangaview.ClassificationDbUpdater;
import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.NtkDeviceIdentityManager;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.CaptchaActivity;
import ml.melun.mangaview.activity.ReaderV2Activity;
import ml.melun.mangaview.activity.ViewerIntentContract;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

public class NtkRandomStressInstrumentedTest {
    private static final String TAG = "ViewerPerf";
    private static final int PAGE_SIZE = 30;
    private static final long PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final String[] MODES = new String[]{"generated", "native-ack", "api-fallback"};
    private static final long NTK_CAPTCHA_PROBE_WAIT_MS = 8_000L;
    private static final long SAFE_DISCOVERY_GAP_MS = 550L;
    private static final int SAFE_RANDOM_RUNS = 4;
    private static final int SAFE_SCROLL_STEPS = 6;
    private static final int SAFE_APPEND_STEPS = 36;
    private static final int DEFAULT_SWIPE_INPUT_STEPS = 5;
    private static final long DEFAULT_FIRST_DRAWABLE_MAX_MS = 3_500L;
    private static final float DEFAULT_RENDER_FRAME_MAX_MS = 16.67f;
    private static final int SCROLL_BACKWARD_JUMP_TOLERANCE_PX = 240;
    private static final int SCROLL_SETTLE_JUMP_TOLERANCE_PX = 420;
    private static final int SCROLL_POST_STOP_DRIFT_TOLERANCE_PX = 24;
    private static final long SCROLL_DRIFT_SAMPLE_MS = 250L;
    private static final long SCROLL_QUIET_STABLE_MS = 480L;
    private static final long DEFAULT_POST_STOP_DRIFT_SAMPLE_MS = 650L;

    @Test
    public void dumpNtkRscListPayloadWhenRequested() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        Bundle args = InstrumentationRegistry.getArguments();
        String path = arg(args, "ntkDumpRscPath", "");
        Assume.assumeTrue("Pass -e ntkDumpRscPath /path to dump a live NTK RSC list payload",
                path.trim().length() > 0);
        int baseMode = parsePositiveInt(arg(args, "ntkDumpBaseMode", "1"), MTitle.base_comic);
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset(arg(args, "ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL));
        MainApplication.p.setBaseMode(baseMode);
        CustomHttpClient client = MainApplication.getHttpClient();
        boolean ensureAccessBefore = Boolean.parseBoolean(arg(args, "ntkEnsureAccessBefore", "false"));
        long ensureAccessMaxMs = parseNonNegativeLong(arg(args, "ntkEnsureAccessMaxMs", "180000"), 180000L);
        if(ensureAccessBefore)
            ensureNtkAccessBeforeMeasurement(context, client, baseMode, path, ensureAccessMaxMs);
        CustomHttpClient.PageResponse page = client.mgetNtkRscPage(path, 0);
        String body = page == null || page.body == null ? "" : page.body;
        ArrayList<Title> parsed = MainPageWebtoon.parseNtkTitleListPayload(body, baseMode, PAGE_SIZE);
        File out = new File(context.getCacheDir(), "ntk-rsc-dump.txt");
        try(FileOutputStream stream = new FileOutputStream(out, false)) {
            stream.write(body.getBytes(StandardCharsets.UTF_8));
        }
        Log.d(TAG, "ntk_rsc_dump path=" + path
                + ",code=" + (page == null ? 0 : page.code)
                + ",bodyLen=" + body.length()
                + ",parsed=" + parsed.size()
                + ",file=" + out.getAbsolutePath());
    }

    @Test
    public void randomNtkEpisodesOpenAndScroll() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        Context context = ApplicationProvider.getApplicationContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Bundle args = InstrumentationRegistry.getArguments();
        boolean safeNetwork = Boolean.parseBoolean(arg(args, "ntkSafeNetwork", "true"));
        int runs = parsePositiveInt(arg(args, "ntkRandomRuns",
                Integer.toString(safeNetwork ? SAFE_RANDOM_RUNS : 12)), safeNetwork ? SAFE_RANDOM_RUNS : 12);
        int scrollSteps = parseNonNegativeInt(arg(args, "ntkScrollSteps",
                Integer.toString(safeNetwork ? SAFE_SCROLL_STEPS : 8)), safeNetwork ? SAFE_SCROLL_STEPS : 8);
        boolean appendProbe = Boolean.parseBoolean(arg(args, "ntkAppendProbe", "true"));
        int appendSteps = parsePositiveInt(arg(args, "ntkAppendSteps",
                Integer.toString(safeNetwork ? SAFE_APPEND_STEPS : 60)), safeNetwork ? SAFE_APPEND_STEPS : 60);
        int screenshotEvery = parseNonNegativeInt(arg(args, "ntkScreenshotEvery", "0"), 0);
        int swipeInputSteps = parsePositiveInt(arg(args, "ntkSwipeInputSteps",
                Integer.toString(DEFAULT_SWIPE_INPUT_STEPS)), DEFAULT_SWIPE_INPUT_STEPS);
        String scrollInputMode = normalizedArg(args, "ntkScrollInputMode", "touch");
        String scrollPattern = normalizedArg(args, "ntkScrollPattern", "mixed");
        long firstDrawableMaxMs = parseNonNegativeLong(
                arg(args, "ntkFirstDrawableMaxMs", Long.toString(DEFAULT_FIRST_DRAWABLE_MAX_MS)),
                DEFAULT_FIRST_DRAWABLE_MAX_MS);
        int initialContinuousPages = parseNonNegativeInt(
                arg(args, "ntkInitialContinuousPages", "0"), 0);
        long initialContinuousMaxMs = parseNonNegativeLong(
                arg(args, "ntkInitialContinuousMaxMs", Long.toString(firstDrawableMaxMs)),
                firstDrawableMaxMs);
        long holdAfterFirstDrawableMs = parseNonNegativeLong(
                arg(args, "ntkHoldAfterFirstDrawableMs", "0"), 0L);
        boolean assertNoJank = Boolean.parseBoolean(arg(args, "ntkAssertNoJank", "true"));
        boolean assertNoSchedulerGap = Boolean.parseBoolean(arg(args, "ntkAssertNoSchedulerGap", "false"));
        int maxMissedFrames = parseNonNegativeInt(arg(args, "ntkMaxMissedFrames", "0"), 0);
        int maxDroppedFrames = parseNonNegativeInt(arg(args, "ntkMaxDroppedFrames", "0"), 0);
        float renderFrameMaxMs = parseNonNegativeFloat(
                arg(args, "ntkRenderFrameMaxMs", Float.toString(DEFAULT_RENDER_FRAME_MAX_MS)),
                DEFAULT_RENDER_FRAME_MAX_MS);
        long postStopDriftMs = parseNonNegativeLong(
                arg(args, "ntkPostStopDriftMs", Long.toString(DEFAULT_POST_STOP_DRIFT_SAMPLE_MS)),
                DEFAULT_POST_STOP_DRIFT_SAMPLE_MS);
        long seed = parseLong(arg(args, "ntkRandomSeed", ""), SystemClock.elapsedRealtime());
        Random random = new Random(seed);
        Random modeRandom = new Random(seed ^ 0x5a17c3e2L);
        boolean cycleModes = Boolean.parseBoolean(arg(args, "ntkCycleModes", "true"));
        int fixedBaseMode = parseBaseMode(arg(args, "ntkBaseMode", ""));
        int modeOffset = modeRandom.nextInt(MODES.length);
        CustomHttpClient client = MainApplication.getHttpClient();
        String siteRoot = arg(args, "ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL);
        boolean lockSiteRoot = Boolean.parseBoolean(arg(args, "ntkLockSiteRoot",
                args.containsKey("ntkSiteRoot") ? "true" : "false"));
        client.setNtkDomainAutoResolveDisabledForTest(lockSiteRoot);
        MainApplication.p.setNtkSitePreset(siteRoot);
        String customUserAgent = arg(args, "ntkUserAgent", "");
        if(customUserAgent.trim().length() > 0) {
            client.agent = customUserAgent.trim();
            Log.d(TAG, "ntk_true_random_user_agent=" + customUserAgent.trim());
        }
        String targetEpisodePath = arg(args, "ntkTargetEpisodePath", "").trim();
        String targetTitlePath = arg(args, "ntkTargetTitlePath", "").trim();
        String targetImageEpisodeId = arg(args, "ntkTargetImageEpisodeId", "").trim();
        int targetEpisodeNumber = parseNonNegativeInt(arg(args, "ntkTargetEpisodeNumber", "0"), 0);
        boolean directTargetEpisode = Boolean.parseBoolean(arg(args, "ntkDirectTargetEpisode", "false"));
        boolean ensureAccessBefore = Boolean.parseBoolean(arg(args, "ntkEnsureAccessBefore", "false"));
        long ensureAccessMaxMs = parseNonNegativeLong(arg(args, "ntkEnsureAccessMaxMs", "180000"), 180000L);
        String fixedMode = arg(args, "ntkMode", "").trim();
        boolean clearAckBeforeRun = Boolean.parseBoolean(arg(args, "ntkClearAckBeforeRun", "false"));
        boolean clearReaderImageCacheBeforeRun = Boolean.parseBoolean(
                arg(args, "ntkClearReaderImageCacheBeforeRun", "false"));
        boolean changeDeviceIdentityBeforeRun = Boolean.parseBoolean(
                arg(args, "ntkChangeDeviceIdentityBeforeRun", "false"));

        Log.d(TAG, "ntk_true_random_start runs=" + runs
                + ",seed=" + seed
                + ",scrollSteps=" + scrollSteps
                + ",appendProbe=" + appendProbe
                + ",appendSteps=" + appendSteps
                + ",screenshotEvery=" + screenshotEvery
                + ",swipeInputSteps=" + swipeInputSteps
                + ",scrollInputMode=" + scrollInputMode
                + ",scrollPattern=" + scrollPattern
                + ",firstDrawableMaxMs=" + firstDrawableMaxMs
                + ",initialContinuousPages=" + initialContinuousPages
                + ",initialContinuousMaxMs=" + initialContinuousMaxMs
                + ",holdAfterFirstDrawableMs=" + holdAfterFirstDrawableMs
                + ",assertNoJank=" + assertNoJank
                + ",assertNoSchedulerGap=" + assertNoSchedulerGap
                + ",maxMissedFrames=" + maxMissedFrames
                + ",maxDroppedFrames=" + maxDroppedFrames
                + ",renderFrameMaxMs=" + renderFrameMaxMs
                + ",postStopDriftMs=" + postStopDriftMs
                + ",cycleModes=" + cycleModes
                + ",baseMode=" + fixedBaseMode
                + ",fixedMode=" + fixedMode
                + ",safeNetwork=" + safeNetwork
                + ",siteRoot=" + MainApplication.p.getWebtoonUrl()
                + ",lockSiteRoot=" + lockSiteRoot
                + ",changeDeviceIdentityBeforeRun=" + changeDeviceIdentityBeforeRun
                + ",modeOffset=" + modeOffset);
        if(ensureAccessBefore)
            ensureNtkAccessBeforeMeasurement(context, client, fixedBaseMode, targetEpisodePath, ensureAccessMaxMs);
        if(targetEpisodePath.length() > 0 || targetEpisodeNumber > 0) {
            TargetEpisode target = loadTargetEpisode(context, client, targetTitlePath, targetEpisodePath,
                    targetEpisodeNumber, fixedBaseMode, directTargetEpisode, targetImageEpisodeId);
            for(int run = 0; run < runs; run++) {
                String mode = modeForRun(fixedMode, cycleModes, random, modeOffset, run);
                prepareFreshNtkMeasurementState(context, client, clearAckBeforeRun,
                        clearReaderImageCacheBeforeRun, changeDeviceIdentityBeforeRun,
                        normalizeTargetPath(targetEpisodePath));
                runReaderCase(context, device, run, mode, target.title, target.episode,
                        scrollSteps, appendProbe, appendSteps, screenshotEvery, postStopDriftMs,
                        firstDrawableMaxMs, initialContinuousPages, initialContinuousMaxMs,
                        assertNoJank, maxMissedFrames, maxDroppedFrames,
                        swipeInputSteps, assertNoSchedulerGap, renderFrameMaxMs,
                        holdAfterFirstDrawableMs, scrollInputMode, scrollPattern);
            }
            return;
        }
        int completedRuns = 0;
        int discoveryAttempts = 0;
        int maxDiscoveryAttempts = Math.max(8, runs * 8);
        while(completedRuns < runs && discoveryAttempts < maxDiscoveryAttempts) {
            int run = completedRuns;
            discoveryAttempts++;
            int baseMode = fixedBaseMode > 0
                    ? fixedBaseMode
                    : (random.nextBoolean() ? MTitle.base_comic : MTitle.base_webtoon);
            MainApplication.p.setBaseMode(baseMode);
            Title title = null;
            int fetchResult = Title.LOAD_ERROR;
            for(int titleAttempt = 0; titleAttempt < 6; titleAttempt++) {
                Title candidate;
                try {
                    candidate = pickRandomTitle(context, client, random, baseMode, safeNetwork);
                } catch (Throwable e) {
                    Log.d(TAG, "ntk_true_random_title_discovery_error run=" + run
                            + ",discoveryAttempt=" + discoveryAttempts
                            + ",titleAttempt=" + titleAttempt
                            + ",baseMode=" + baseMode
                            + ",type=" + e.getClass().getSimpleName()
                            + ",message=" + e.getMessage());
                    break;
                }
                fetchResult = candidate.getEps() != null && candidate.getEps().size() > 0
                        ? Title.LOAD_OK : candidate.fetchEps(client);
                if(fetchResult != Title.LOAD_OK || candidate.getEps() == null || candidate.getEps().size() == 0) {
                    if(safeNetwork && ntkBlockedWithoutProof(client)) {
                        Log.d(TAG, "ntk_true_random_title_fetch_blocked_safe run=" + run
                                + ",attempt=" + titleAttempt
                                + ",result=" + fetchResult
                                + ",path=" + candidate.getPath());
                        break;
                    }
                    ensureNtkAccessAfterChallenge(context, client, baseMode);
                    fetchResult = candidate.fetchEps(client);
                }
                if(fetchResult == Title.LOAD_CAPTCHA
                        || (fetchResult == Title.LOAD_ERROR
                        && client.hasRecentCloudflareChallenge()
                        && !client.hasNtkAccessProof())) {
                    Log.d(TAG, "ntk_true_random_title_access_blocked run=" + run
                            + ",attempt=" + titleAttempt
                            + ",title=" + candidate.getName()
                            + ",id=" + candidate.getId()
                            + ",path=" + candidate.getPath()
                            + ",result=" + fetchResult);
                    break;
                }
                if(fetchResult == Title.LOAD_OK && candidate.getEps() != null && candidate.getEps().size() > 0) {
                    title = candidate;
                    break;
                }
                Log.d(TAG, "ntk_true_random_title_skip run=" + run
                        + ",attempt=" + titleAttempt
                        + ",title=" + candidate.getName()
                        + ",id=" + candidate.getId()
                        + ",path=" + candidate.getPath()
                        + ",result=" + fetchResult);
                if(safeNetwork)
                    safeDiscoveryPause(client);
            }
            if(title == null) {
                Log.d(TAG, "ntk_true_random_title_discovery_retry run=" + run
                        + ",discoveryAttempt=" + discoveryAttempts
                        + ",result=" + fetchResult
                        + ",baseMode=" + baseMode);
                if(safeNetwork)
                    safeDiscoveryPause(client);
                continue;
            }
            Manga episode = pickRandomEpisode(title.getEps(), random);
            assertTrue("Expected picked NTK episode path for run=" + run
                            + " title=" + title.getName()
                            + " episode=" + episode.getName(),
                    episode.getNtkEpisodePath().length() > 0);
            String mode = modeForRun(fixedMode, cycleModes, random, modeOffset, run);
            prepareFreshNtkMeasurementState(context, client, clearAckBeforeRun,
                    clearReaderImageCacheBeforeRun, changeDeviceIdentityBeforeRun,
                    episode.getNtkEpisodePath());
            runReaderCase(context, device, run, mode, title, episode,
                    scrollSteps, appendProbe, appendSteps, screenshotEvery, postStopDriftMs,
                    firstDrawableMaxMs, initialContinuousPages, initialContinuousMaxMs,
                    assertNoJank, maxMissedFrames, maxDroppedFrames,
                    swipeInputSteps, assertNoSchedulerGap, renderFrameMaxMs,
                    holdAfterFirstDrawableMs, scrollInputMode, scrollPattern);
            completedRuns++;
        }
        assertTrue("Expected NTK episode list for all runs completed=" + completedRuns
                        + " requested=" + runs
                        + " discoveryAttempts=" + discoveryAttempts,
                completedRuns == runs);
    }

    private static void prepareFreshNtkMeasurementState(Context context, CustomHttpClient client,
                                                        boolean clearAck,
                                                        boolean clearReaderImageCache,
                                                        boolean changeDeviceIdentity,
                                                        String targetPath) {
        if(clearAck && client != null) {
            String webtoonRoot = MainApplication.p == null
                    ? CustomHttpClient.NTK_WEBTOON_URL : MainApplication.p.getWebtoonUrl();
            String clearUrl = targetPath == null || targetPath.length() == 0
                    ? webtoonRoot
                    : (targetPath.startsWith("http") ? targetPath : webtoonRoot + targetPath);
            client.clearNtkAckStateForTest(clearUrl);
        }
        if(clearReaderImageCache && context != null) {
            Manga.clearNtkGeneratedExtensionCacheForTest();
            ReaderImageCache.clearNtkGeneratedEpisodeExtensionHintsForTest();
            ReaderImageCache.clearVolatileStateForTest();
            Log.d(TAG, "ntk_generated_extension_cache_clear_for_test");
            File dir = new File(context.getCacheDir(), "reader_image_cache_v1");
            int deleted = deleteRecursivelyForTest(dir);
            Log.d(TAG, "ntk_reader_image_cache_clear_for_test deleted=" + deleted
                    + ",path=" + dir.getAbsolutePath());
        }
        if(changeDeviceIdentity && context != null) {
            AtomicReference<String> agentRef = new AtomicReference<>("");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    agentRef.set(NtkDeviceIdentityManager.changeDeviceInfo(context, false)));
            Log.d(TAG, "ntk_device_identity_changed_for_test ua=" + agentRef.get());
        }
    }

    private static int deleteRecursivelyForTest(File file) {
        if(file == null || !file.exists())
            return 0;
        int deleted = 0;
        if(file.isDirectory()) {
            File[] children = file.listFiles();
            if(children != null) {
                for(File child : children)
                    deleted += deleteRecursivelyForTest(child);
            }
        }
        if(file.delete())
            deleted++;
        return deleted;
    }

    private static void ensureNtkAccessAfterChallenge(Context context, CustomHttpClient client, int baseMode) {
        if(client == null || !client.hasRecentCloudflareChallenge() || client.hasNtkAccessProof())
            return;
        if(context == null)
            context = ApplicationProvider.getApplicationContext();
        long startedAt = SystemClock.elapsedRealtime();
        String webtoonRoot = MainApplication.p == null
                ? CustomHttpClient.NTK_WEBTOON_URL : MainApplication.p.getWebtoonUrl();
        String url = client.getLastCloudflareChallengeUrl();
        if(url == null || url.length() == 0 || url.startsWith("/api/"))
            url = webtoonRoot + "/";
        else if(url.startsWith("/"))
            url = webtoonRoot + url;
        Log.d(TAG, "ntk_true_random_captcha_start baseMode=" + baseMode + ",url=" + url);
        Intent intent = new Intent(context, CaptchaActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("url", url);
        Activity activity = null;
        try {
            activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
            long deadline = SystemClock.elapsedRealtime() + NTK_CAPTCHA_PROBE_WAIT_MS;
            while(SystemClock.elapsedRealtime() < deadline) {
                client.syncCookiesFromWebView(webtoonRoot, true);
                client.syncCookiesFromWebView(client.getUrl(), true);
                if(client.hasNtkAccessProof())
                    break;
                SystemClock.sleep(1000L);
            }
        } finally {
            Activity toFinish = activity;
            if(toFinish != null && !toFinish.isDestroyed()) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    if(!toFinish.isFinishing())
                        toFinish.finish();
                });
            }
            Log.d(TAG, "ntk_true_random_captcha_done baseMode=" + baseMode
                    + ",clearance=" + client.hasNtkAccessProof()
                    + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
        }
    }

    private static void ensureNtkAccessBeforeMeasurement(Context context, CustomHttpClient client,
                                                         int baseMode, String targetPath, long maxMs) {
        if(client == null || client.hasNtkAccessProof() || maxMs <= 0)
            return;
        if(context == null)
            context = ApplicationProvider.getApplicationContext();
        String webtoonRoot = MainApplication.p == null
                ? CustomHttpClient.NTK_WEBTOON_URL : MainApplication.p.getWebtoonUrl();
        String url = normalizeTargetPath(targetPath);
        if(url.length() == 0)
            url = webtoonRoot + "/";
        else if(url.startsWith("/"))
            url = webtoonRoot + url;
        long startedAt = SystemClock.elapsedRealtime();
        Log.d(TAG, "ntk_true_random_pre_captcha_start baseMode=" + baseMode
                + ",url=" + url
                + ",maxMs=" + maxMs);
        Intent intent = new Intent(context, CaptchaActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("url", url);
        Activity activity = null;
        try {
            activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
            long deadline = SystemClock.elapsedRealtime() + maxMs;
            while(SystemClock.elapsedRealtime() < deadline) {
                client.syncCookiesFromWebView(webtoonRoot, true);
                client.syncCookiesFromWebView(client.getUrl(), true);
                if(client.hasNtkAccessProof())
                    break;
                SystemClock.sleep(500L);
            }
        } finally {
            Activity toFinish = activity;
            if(toFinish != null && !toFinish.isDestroyed()) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    if(!toFinish.isFinishing())
                        toFinish.finish();
                });
            }
            Log.d(TAG, "ntk_true_random_pre_captcha_done baseMode=" + baseMode
                    + ",clearance=" + client.hasNtkAccessProof()
                    + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
        }
    }

    private static Title pickRandomTitle(Context context, CustomHttpClient client,
                                         Random random, int baseMode, boolean safeNetwork) throws Exception {
        Exception apiError = null;
        if(safeNetwork && ntkBlockedWithoutProof(client)) {
            Title dbTitle = pickRandomTitleFromClassificationDb(context, random, baseMode);
            if(dbTitle != null)
                return dbTitle;
            Title curatedTitle = pickRandomTitleFromCuratedNtkPool(random, baseMode);
            if(curatedTitle != null)
                return curatedTitle;
        }
        try {
            return pickRandomTitleFromApi(client, random, baseMode, safeNetwork);
        } catch (Exception e) {
            apiError = e;
            Log.d(TAG, "ntk_true_random_api_title_unavailable baseMode=" + baseMode
                    + ",type=" + e.getClass().getSimpleName()
                    + ",message=" + e.getMessage());
            if(isCloudflareFailure(e)) {
                if(!safeNetwork) {
                    ensureNtkAccessAfterChallenge(context, client, baseMode);
                    try {
                        return pickRandomTitleFromApi(client, random, baseMode, safeNetwork);
                    } catch (Exception retry) {
                        apiError = retry;
                        Log.d(TAG, "ntk_true_random_api_title_retry_unavailable baseMode=" + baseMode
                                + ",type=" + retry.getClass().getSimpleName()
                                + ",message=" + retry.getMessage());
                    }
                } else {
                    Log.d(TAG, "ntk_true_random_api_retry_suppressed_safe baseMode=" + baseMode);
                }
                if(ntkBlockedWithoutProof(client)) {
                    Log.d(TAG, "ntk_true_random_access_unverified_before_fallback baseMode=" + baseMode
                            + ",apiType=" + apiError.getClass().getSimpleName()
                            + ",apiMessage=" + apiError.getMessage());
                }
                Title dbTitle = pickRandomTitleFromClassificationDb(context, random, baseMode);
                if(dbTitle != null)
                    return dbTitle;
                Title curatedTitle = pickRandomTitleFromCuratedNtkPool(random, baseMode);
                if(curatedTitle != null)
                    return curatedTitle;
                if(safeNetwork)
                    throw new AssertionError("Unable to pick safe fallback NTK title baseMode=" + baseMode, apiError);
                Title numericTitle = pickRandomTitleFromNumericProbe(client, random, baseMode);
                if(numericTitle != null)
                    return numericTitle;
            }
        }
        if(safeNetwork && ntkBlockedWithoutProof(client)) {
            Title dbTitle = pickRandomTitleFromClassificationDb(context, random, baseMode);
            if(dbTitle != null)
                return dbTitle;
            Title curatedTitle = pickRandomTitleFromCuratedNtkPool(random, baseMode);
            if(curatedTitle != null)
                return curatedTitle;
            throw new AssertionError("Unable to pick safe fallback NTK title after access block baseMode=" + baseMode,
                    apiError);
        }
        Title htmlTitle = pickRandomTitleFromHtmlSections(client, random, baseMode, safeNetwork);
        if(htmlTitle != null)
            return htmlTitle;
        Title dbTitle = pickRandomTitleFromClassificationDb(context, random, baseMode);
        if(dbTitle != null)
            return dbTitle;
        Title numericTitle = pickRandomTitleFromNumericProbe(client, random, baseMode);
        if(numericTitle != null)
            return numericTitle;
        if(client.hasRecentCloudflareChallenge() && !client.hasNtkAccessProof()) {
            throw new AssertionError("Unable to verify NTK access after API, HTML, DB, and numeric random title fallbacks"
                    + " baseMode=" + baseMode
                    + (apiError == null ? "" : " apiType=" + apiError.getClass().getSimpleName()
                    + " apiMessage=" + apiError.getMessage()), apiError);
        }
        if(apiError != null)
            throw new AssertionError("Unable to pick random NTK title after API and HTML discovery failures"
                    + " baseMode=" + baseMode
                    + " apiType=" + apiError.getClass().getSimpleName()
                    + " apiMessage=" + apiError.getMessage(), apiError);
        throw new AssertionError("Unable to pick random NTK title baseMode=" + baseMode);
    }

    private static boolean ntkBlockedWithoutProof(CustomHttpClient client) {
        return client != null
                && !client.hasNtkAccessProof()
                && (client.hasRecentNtkHardBlock() || client.hasRecentCloudflareChallenge());
    }

    private static void safeDiscoveryPause(CustomHttpClient client) {
        if(ntkBlockedWithoutProof(client))
            SystemClock.sleep(SAFE_DISCOVERY_GAP_MS * 4L);
        else
            SystemClock.sleep(SAFE_DISCOVERY_GAP_MS);
    }

    private static Title pickRandomTitleFromClassificationDb(Context context, Random random, int baseMode) {
        Title title = pickRandomTitleFromClassificationDbOnce(context, random, baseMode);
        if(title == null) {
            long startedAt = SystemClock.elapsedRealtime();
            ClassificationDbUpdater.updateInBackground(context);
            Log.d(TAG, "ntk_true_random_db_update_ms=" + (SystemClock.elapsedRealtime() - startedAt));
            title = pickRandomTitleFromClassificationDbOnce(context, random, baseMode);
        }
        if(title != null) {
            title.setSourceSite("ntk");
            Log.d(TAG, "ntk_true_random_title_db baseMode=" + baseMode
                    + ",id=" + title.getId()
                    + ",path=" + title.getPath()
                    + ",name=" + title.getName());
        }
        return title;
    }

    private static Title pickRandomTitleFromClassificationDbOnce(Context context, Random random, int baseMode) {
        boolean comic = baseMode == MTitle.base_comic;
        int total = ClassificationDbStore.getTitleCount(context, comic, "ntk");
        if(total <= 0)
            return null;
        for(int attempt = 0; attempt < 8; attempt++) {
            int offset = random.nextInt(total);
            ArrayList<Title> titles = ClassificationDbStore.getTitles(context, comic, "ntk", offset, 1);
            if(titles.size() == 0)
                continue;
            Title title = titles.get(0);
            if(title == null || title.getPath() == null || title.getPath().trim().length() == 0)
                continue;
            String expected = comic ? "/manhwa/" : "/webtoon/";
            if(!title.getPath().startsWith(expected))
                continue;
            return title;
        }
        return null;
    }

    private static Title pickRandomTitleFromCuratedNtkPool(Random random, int baseMode) {
        String[][] pool = baseMode == MTitle.base_webtoon
                ? CURATED_NTK_WEBTOON_EPISODES
                : CURATED_NTK_MANHWA_EPISODES;
        if(pool.length == 0)
            return null;
        String[] episodes = pool[random.nextInt(pool.length)];
        if(episodes == null || episodes.length == 0)
            return null;
        Title title = titleFromCuratedEpisodePaths(episodes, baseMode);
        Log.d(TAG, "ntk_true_random_title_curated baseMode=" + baseMode
                + ",id=" + title.getId()
                + ",path=" + title.getPath()
                + ",episodes=" + (title.getEps() == null ? 0 : title.getEps().size()));
        return title;
    }

    private static Title titleFromCuratedEpisodePaths(String[] paths, int baseMode) {
        String first = normalizeTargetPath(paths[0]);
        String titlePath = titlePathFromEpisodePath(first, baseMode);
        int titleId = titleIdFromPath(titlePath);
        Title title = new Title("ntk-curated-" + titleId, "", "", new ArrayList<>(), "", titleId, baseMode);
        title.setSourceSite("ntk");
        title.setPath(titlePath);
        ArrayList<Manga> episodes = new ArrayList<>();
        for(int i = 0; i < paths.length; i++) {
            String path = normalizeTargetPath(paths[i]);
            int id = parsePositiveInt(path.substring(path.lastIndexOf('/') + 1), stableId(path));
            Manga episode = new Manga(id, "curated " + (paths.length - i), "", baseMode);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episode.setNtkEpisodePath(path);
            episode.setNtkImageCount(CURATED_NTK_IMAGE_COUNT);
            episodes.add(episode);
        }
        title.setEps(episodes);
        for(Manga episode : title.getEps()) {
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episode.setEps(title.getEps());
        }
        return title;
    }

    private static Title pickRandomTitleFromNumericProbe(CustomHttpClient client, Random random, int baseMode) {
        if(client.hasRecentCloudflareChallenge() && !client.hasNtkAccessProof()) {
            Log.d(TAG, "ntk_true_random_numeric_probe_after_challenge baseMode=" + baseMode
                    + ",reason=recent_challenge_without_access_proof");
            return null;
        }
        String segment = baseMode == MTitle.base_webtoon ? "webtoon" : "manhwa";
        for(int attempt = 0; attempt < 28; attempt++) {
            int id = randomNtkNumericTitleId(random, baseMode);
            Title title = new Title("ntk-" + segment + "-" + id, "", "",
                    new ArrayList<>(), "", id, baseMode);
            title.setSourceSite("ntk");
            title.setPath("/" + segment + "/" + id);
            long startedAt = SystemClock.elapsedRealtime();
            int result = title.fetchEps(client);
            int episodes = title.getEps() == null ? 0 : title.getEps().size();
            Log.d(TAG, "ntk_true_random_numeric_probe baseMode=" + baseMode
                    + ",attempt=" + attempt
                    + ",id=" + id
                    + ",path=" + title.getPath()
                    + ",result=" + result
                    + ",episodes=" + episodes
                    + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
            if(result == Title.LOAD_OK && episodes > 0)
                return title;
            if(Thread.currentThread().isInterrupted())
                return null;
        }
        return null;
    }

    private static int randomNtkNumericTitleId(Random random, int baseMode) {
        int roll = random.nextInt(100);
        if(baseMode == MTitle.base_webtoon) {
            if(roll < 82)
                return 1 + random.nextInt(24_000);
            if(roll < 95)
                return 24_001 + random.nextInt(16_000);
            return 800_000 + random.nextInt(120_000);
        }
        if(roll < 86)
            return 1 + random.nextInt(28_000);
        return 28_001 + random.nextInt(12_000);
    }

    private static Title pickRandomTitleFromApi(CustomHttpClient client, Random random,
                                                int baseMode, boolean safeNetwork) throws Exception {
        String listPath = listPath(baseMode, 1);
        CustomHttpClient.PageResponse first = fetchRandomApiPage(client, listPath);
        JSONObject firstJson = new JSONObject(first.body == null ? "{}" : first.body);
        int total = Math.max(0, firstJson.optInt("total", 0));
        int maxPage = Math.max(1, total <= 0 ? 80 : (int)Math.ceil(total / (double)PAGE_SIZE));
        int attempts = safeNetwork ? 3 : 8;
        for(int attempt = 0; attempt < attempts; attempt++) {
            if(safeNetwork && attempt > 0)
                safeDiscoveryPause(client);
            int page = 1 + random.nextInt(maxPage);
            CustomHttpClient.PageResponse response = fetchRandomApiPage(client, listPath(baseMode, page));
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

    private static CustomHttpClient.PageResponse fetchRandomApiPage(CustomHttpClient client, String path) throws Exception {
        return client.runWithFetchMode(CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW,
                () -> client.mgetCachedPage(path, PAGE_CACHE_TTL_MS));
    }

    private static Title pickRandomTitleFromHtmlSections(CustomHttpClient client, Random random,
                                                         int baseMode, boolean safeNetwork) {
        String[][] sections = MainPageWebtoon.getSections(baseMode, true);
        if(sections == null || sections.length == 0)
            return null;
        int attempts = safeNetwork ? 4 : 18;
        for(int attempt = 0; attempt < attempts; attempt++) {
            if(safeNetwork && attempt > 0)
                safeDiscoveryPause(client);
            String[] section = sections[random.nextInt(sections.length)];
            if(section == null || section.length < 2 || section[1] == null || section[1].length() == 0)
                continue;
            int pageNumber = 1 + random.nextInt(12);
            String path = ntkCategoryPagePath(section[1], pageNumber);
            try {
                ArrayList<Title> rscCandidates = pickRandomTitleCandidatesFromRsc(client, path, baseMode);
                if(rscCandidates.size() > 0) {
                    Title title = rscCandidates.get(random.nextInt(rscCandidates.size()));
                    Log.d(TAG, "ntk_true_random_title_rsc baseMode=" + baseMode
                            + ",path=" + path
                            + ",id=" + title.getId()
                            + ",titlePath=" + title.getPath()
                            + ",name=" + title.getName());
                    return title;
                }
                Log.d(TAG, "ntk_true_random_rsc_title_empty baseMode=" + baseMode
                        + ",attempt=" + attempt
                        + ",path=" + path);
            } catch (Exception e) {
                Log.d(TAG, "ntk_true_random_html_title_skip baseMode=" + baseMode
                        + ",attempt=" + attempt
                        + ",path=" + path
                        + ",type=" + e.getClass().getSimpleName()
                        + ",message=" + e.getMessage());
            }
        }
        return null;
    }

    private static ArrayList<Title> pickRandomTitleCandidatesFromRsc(CustomHttpClient client, String path, int baseMode) throws Exception {
        ArrayList<Title> candidates = new ArrayList<>();
        try {
            CustomHttpClient.PageResponse rsc = client.mgetNtkRscPage(path, PAGE_CACHE_TTL_MS);
            if(rsc != null && client.isCloudflareChallengeResponse(rsc.code, rsc.body)) {
                Log.d(TAG, "ntk_true_random_rsc_title_blocked baseMode=" + baseMode
                        + ",path=" + path
                        + ",code=" + rsc.code
                        + ",bodyLen=" + (rsc.body == null ? 0 : rsc.body.length()));
                throw new Exception("Cloudflare challenge RSC code=" + rsc.code + " path=" + path);
            }
            ArrayList<Title> parsed = MainPageWebtoon.parseNtkTitleListPayload(
                    rsc == null ? "" : rsc.body, baseMode, PAGE_SIZE);
            for(Title title : parsed) {
                if(title == null || title.getPath() == null || title.getPath().length() == 0)
                    continue;
                title.setSourceSite("ntk");
                candidates.add(title);
            }
            Log.d(TAG, "ntk_true_random_rsc_title_source baseMode=" + baseMode
                    + ",path=" + path
                    + ",code=" + (rsc == null ? 0 : rsc.code)
                    + ",bodyLen=" + (rsc == null || rsc.body == null ? 0 : rsc.body.length())
                    + ",parsed=" + parsed.size()
                    + ",candidates=" + candidates.size());
        } catch (Exception e) {
            Log.d(TAG, "ntk_true_random_rsc_title_skip baseMode=" + baseMode
                    + ",path=" + path
                    + ",type=" + e.getClass().getSimpleName()
                    + ",message=" + e.getMessage());
            throw e;
        }
        return candidates;
    }

    private static boolean isCloudflareFailure(Throwable e) {
        String message = e == null ? null : e.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("cloudflare")
                || lower.contains("challenge")
                || lower.contains("ntk hard block")
                || lower.contains("request failed: /api/");
    }

    private static String ntkCategoryPagePath(String path, int page) {
        if(path == null || path.length() == 0 || page <= 1)
            return path;
        if(path.matches(".*[?&]page=\\d+.*"))
            return path.replaceFirst("([?&]page=)\\d+", "$1" + page);
        return path + (path.contains("?") ? "&" : "?") + "page=" + page;
    }

    private static String listPath(int baseMode, int page) {
        String api = baseMode == MTitle.base_webtoon ? "/api/works" : "/api/manhwa-list";
        return api + "?page=" + page + "&pageSize=" + PAGE_SIZE + "&withTotal=1";
    }

    private static Title titleFromWork(JSONObject work, int baseMode) {
        if(work == null)
            return null;
        String sourceWorkId = canonicalWorkId(work);
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
        ArrayList<Manga> numericCandidates = new ArrayList<>();
        ArrayList<Manga> positiveImageCandidates = new ArrayList<>();
        ArrayList<Manga> positiveNumericCandidates = new ArrayList<>();
        for(Manga episode : episodes) {
            if(episode != null && episode.getNtkEpisodePath().length() > 0) {
                candidates.add(episode);
                boolean numericPath = isNumericNtkEpisodePath(episode.getNtkEpisodePath());
                if(numericPath)
                    numericCandidates.add(episode);
                if(episode.getNtkImageCount() > 0) {
                    positiveImageCandidates.add(episode);
                    if(numericPath)
                        positiveNumericCandidates.add(episode);
                }
            }
        }
        assertTrue("Expected at least one episode with NTK path", candidates.size() > 0);
        if(positiveNumericCandidates.size() > 0) {
            Log.d(TAG, "ntk_true_random_episode_candidates total=" + candidates.size()
                    + ",numeric=" + numericCandidates.size()
                    + ",positiveImages=" + positiveImageCandidates.size()
                    + ",positiveNumeric=" + positiveNumericCandidates.size());
            return positiveNumericCandidates.get(random.nextInt(positiveNumericCandidates.size()));
        }
        if(numericCandidates.size() > 0) {
            Log.d(TAG, "ntk_true_random_episode_candidates total=" + candidates.size()
                    + ",numeric=" + numericCandidates.size()
                    + ",positiveImages=" + positiveImageCandidates.size());
            return numericCandidates.get(random.nextInt(numericCandidates.size()));
        }
        if(positiveImageCandidates.size() > 0) {
            Log.d(TAG, "ntk_true_random_episode_candidates total=" + candidates.size()
                    + ",positiveImages=" + positiveImageCandidates.size());
            return positiveImageCandidates.get(random.nextInt(positiveImageCandidates.size()));
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static boolean isNumericNtkEpisodePath(String path) {
        return path != null && path.matches("^/(?:manhwa|webtoon)/\\d+/\\d+$");
    }

    private static String modeForRun(String fixedMode, boolean cycleModes, Random random,
                                     int modeOffset, int run) {
        if(fixedMode != null && fixedMode.trim().length() > 0)
            return fixedMode.trim();
        return cycleModes ? MODES[(modeOffset + run) % MODES.length] : MODES[random.nextInt(MODES.length)];
    }

    private static TargetEpisode loadTargetEpisode(Context context, CustomHttpClient client, String titlePath,
                                                   String episodePath, int targetEpisodeNumber,
                                                   int fixedBaseMode, boolean directTargetEpisode,
                                                   String targetImageEpisodeId) {
        String normalizedEpisodePath = normalizeTargetPath(episodePath);
        int baseMode = fixedBaseMode > 0 ? fixedBaseMode : baseModeForTargetPath(
                normalizedEpisodePath.length() > 0 ? normalizedEpisodePath : titlePath);
        MainApplication.p.setBaseMode(baseMode);
        String resolvedTitlePath = normalizeTargetPath(titlePath);
        if(resolvedTitlePath.length() == 0)
            resolvedTitlePath = titlePathFromEpisodePath(normalizedEpisodePath, baseMode);
        int titleId = titleIdFromPath(resolvedTitlePath);
        Title title = new Title("ntk-target-" + titleId, "", "", new ArrayList<>(), "", titleId, baseMode);
        title.setSourceSite("ntk");
        title.setPath(resolvedTitlePath);
        if(directTargetEpisode && normalizedEpisodePath.length() > 0) {
            Manga episode = new Manga(parseEpisodeIdFromPath(normalizedEpisodePath), "ntk-direct-target", "", baseMode);
            episode.setTitle(title);
            episode.setTitleId(titleId);
            episode.setNtkEpisodePath(normalizedEpisodePath);
            episode.setNtkImageEpisodeId(targetImageEpisodeId);
            ArrayList<Manga> episodes = new ArrayList<>();
            episodes.add(episode);
            title.setEps(episodes);
            Log.d(TAG, "ntk_true_random_direct_target path=" + normalizedEpisodePath
                    + ",titlePath=" + resolvedTitlePath
                    + ",titleId=" + titleId
                    + ",baseMode=" + baseMode);
            return new TargetEpisode(title, episode);
        }
        int result = title.fetchEps(client);
        if(result == Title.LOAD_CAPTCHA || result == Title.LOAD_ERROR && client.hasRecentCloudflareChallenge()) {
            ensureNtkAccessAfterChallenge(context, client, baseMode);
            result = title.fetchEps(client);
        }
        assertTrue("Expected NTK target title episodes result=" + result
                        + " titlePath=" + resolvedTitlePath,
                result == Title.LOAD_OK && title.getEps() != null && title.getEps().size() > 0);
        if(targetEpisodeNumber > 0 && normalizedEpisodePath.length() == 0) {
            for(Manga episode : title.getEps()) {
                if(episode != null && episodeNumber(episode.getName()) == targetEpisodeNumber) {
                    Log.d(TAG, "ntk_true_random_target_number number=" + targetEpisodeNumber
                            + ",path=" + episode.getNtkEpisodePath()
                            + ",titlePath=" + resolvedTitlePath
                            + ",episodes=" + title.getEps().size()
                            + ",name=" + episode.getName());
                    return new TargetEpisode(title, episode);
                }
            }
            throw new AssertionError("Target episode number not found number=" + targetEpisodeNumber
                    + " titlePath=" + resolvedTitlePath
                    + " episodes=" + episodeSample(title.getEps()));
        }
        for(Manga episode : title.getEps()) {
            if(episode != null && normalizedEpisodePath.equals(episode.getNtkEpisodePath())) {
                Log.d(TAG, "ntk_true_random_target path=" + normalizedEpisodePath
                        + ",titlePath=" + resolvedTitlePath
                        + ",episodes=" + title.getEps().size()
                        + ",name=" + episode.getName());
                return new TargetEpisode(title, episode);
            }
        }
        throw new AssertionError("Target episode not found titlePath=" + resolvedTitlePath
                + " episodePath=" + normalizedEpisodePath
                + " episodes=" + title.getEps().size());
    }

    private static int episodeNumber(String name) {
        if(name == null)
            return -1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(name);
        if(!matcher.find())
            return -1;
        return parsePositiveInt(matcher.group(1), -1);
    }

    private static int parseEpisodeIdFromPath(String path) {
        if(path == null)
            return 1;
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)(?!.*\\d)").matcher(tail);
        if(matcher.find())
            return parsePositiveInt(matcher.group(1), 1);
        return 1;
    }

    private static String episodeSample(List<Manga> episodes) {
        if(episodes == null || episodes.size() == 0)
            return "[]";
        StringBuilder builder = new StringBuilder("[");
        int count = Math.min(episodes.size(), 20);
        for(int i = 0; i < count; i++) {
            Manga episode = episodes.get(i);
            if(i > 0)
                builder.append(", ");
            builder.append(episode == null ? "null" : episode.getName() + "=" + episode.getNtkEpisodePath());
        }
        if(episodes.size() > count)
            builder.append(", ...");
        return builder.append(']').toString();
    }

    private static String normalizeTargetPath(String path) {
        String value = path == null ? "" : path.trim();
        if(value.startsWith("https://") || value.startsWith("http://")) {
            int slash = value.indexOf('/', value.indexOf("//") + 2);
            return slash < 0 ? "" : value.substring(slash);
        }
        return value;
    }

    private static int baseModeForTargetPath(String path) {
        return path != null && path.startsWith("/webtoon/") ? MTitle.base_webtoon : MTitle.base_comic;
    }

    private static String titlePathFromEpisodePath(String episodePath, int baseMode) {
        String normalized = normalizeTargetPath(episodePath);
        String[] parts = normalized.split("/");
        if(parts.length >= 3 && parts[1].length() > 0 && parts[2].length() > 0)
            return "/" + parts[1] + "/" + parts[2];
        String segment = baseMode == MTitle.base_webtoon ? "webtoon" : "manhwa";
        return "/" + segment + "/0";
    }

    private static int titleIdFromPath(String path) {
        String[] parts = normalizeTargetPath(path).split("/");
        if(parts.length >= 3)
            return parsePositiveInt(parts[2], stableId(parts[2]));
        return 0;
    }

    private static final class TargetEpisode {
        final Title title;
        final Manga episode;

        TargetEpisode(Title title, Manga episode) {
            this.title = title;
            this.episode = episode;
        }
    }

    private static final int CURATED_NTK_IMAGE_COUNT = 80;
    private static final String[][] CURATED_NTK_WEBTOON_EPISODES = new String[][]{
            {"/webtoon/17332/1515337"}
    };
    private static final String[][] CURATED_NTK_MANHWA_EPISODES = new String[][]{
            {"/manhwa/25694/1767091", "/manhwa/25694/1767431", "/manhwa/25694/1767898", "/manhwa/25694/1768331"}
    };

    private static void runReaderCase(Context context, UiDevice device, int run, String mode,
                                      Title title, Manga episode, int scrollSteps,
                                      boolean appendProbe, int appendSteps, int screenshotEvery,
                                      long postStopDriftMs, long firstDrawableMaxMs,
                                      int initialContinuousPages, long initialContinuousMaxMs,
                                      boolean assertNoJank, int maxMissedFrames,
                                      int maxDroppedFrames, int swipeInputSteps,
                                      boolean assertNoSchedulerGap, float renderFrameMaxMs,
                                      long holdAfterFirstDrawableMs, String scrollInputMode,
                                      String scrollPattern) {
        Activity activity = null;
        long startedAt = SystemClock.elapsedRealtime();
        Manga nextEpisode = null;
        Manga previousEpisode = null;
        try {
            episode.setMode(0);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            nextEpisode = episode.nextEp();
            previousEpisode = episode.prevEp();
            Manga.setNtkViewerFetchModeOverrideForTest(mode);
            Log.d(TAG, "ntk_true_random_case_start run=" + run
                    + ",mode=" + mode
                    + ",baseMode=" + title.getBaseMode()
                    + ",titleId=" + title.getId()
                    + ",episodeId=" + episode.getId()
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",title=" + title.getName()
                    + ",episode=" + episode.getName()
                    + ",scrollInputMode=" + scrollInputMode
                    + ",scrollPattern=" + scrollPattern
                    + ",hasNext=" + (nextEpisode != null)
                    + ",hasPrevious=" + (previousEpisode != null));
            activity = InstrumentationRegistry.getInstrumentation()
                    .startActivitySync(viewerIntent(context, title, episode));
            ReaderV2Activity reader = activity instanceof ReaderV2Activity ? (ReaderV2Activity) activity : null;
            long firstDrawableWaitMs = firstDrawableMaxMs > 0L
                    ? Math.max(1500L, firstDrawableMaxMs + 1000L)
                    : 16000L;
            boolean ready = waitForDrawableReady(activity, device, firstDrawableWaitMs);
            long observedFirstMs = SystemClock.elapsedRealtime() - startedAt;
            long appFirstMs = readFirstDrawableElapsedMs(activity);
            long firstMs = appFirstMs >= 0L ? appFirstMs : observedFirstMs;
            Log.d(TAG, "ntk_true_random_first_drawable run=" + run
                    + ",mode=" + mode
                    + ",ready=" + ready
                    + ",ms=" + firstMs
                    + ",observedMs=" + observedFirstMs
                    + ",appMs=" + appFirstMs
                    + ",maxMs=" + firstDrawableMaxMs
                    + ",path=" + episode.getNtkEpisodePath());
            assertTrue("Expected first drawable run=" + run
                    + " mode=" + mode
                    + " path=" + episode.getNtkEpisodePath()
                    + " elapsedMs=" + firstMs, ready);
            assertTrue("Expected first drawable within budget run=" + run
                            + " mode=" + mode
                            + " path=" + episode.getNtkEpisodePath()
                            + " elapsedMs=" + firstMs
                            + " maxMs=" + firstDrawableMaxMs,
                    firstDrawableMaxMs <= 0L || firstMs <= firstDrawableMaxMs);
            if(initialContinuousPages > 0) {
                long continuousWaitMs = initialContinuousMaxMs > 0L
                        ? Math.max(1500L, initialContinuousMaxMs + 1000L)
                        : 16000L;
                boolean continuousReady = waitForInitialContinuousDrawable(
                        reader, continuousWaitMs, initialContinuousPages);
                long continuousMs = readInitialContinuousDrawableElapsedMs(
                        reader, initialContinuousPages);
                int pageCount = reader == null ? -1 : readPageCount(reader);
                Log.d(TAG, "ntk_true_random_initial_continuous run=" + run
                        + ",mode=" + mode
                        + ",ready=" + continuousReady
                        + ",pages=" + initialContinuousPages
                        + ",pageCount=" + pageCount
                        + ",ms=" + continuousMs
                        + ",maxMs=" + initialContinuousMaxMs
                        + ",path=" + episode.getNtkEpisodePath());
                assertTrue("Expected initial continuous drawable run=" + run
                                + " mode=" + mode
                                + " path=" + episode.getNtkEpisodePath()
                                + " pages=" + initialContinuousPages
                                + " elapsedMs=" + continuousMs
                                + " pageCount=" + pageCount,
                        continuousReady);
                assertTrue("Expected initial continuous drawable within budget run=" + run
                                + " mode=" + mode
                                + " path=" + episode.getNtkEpisodePath()
                                + " pages=" + initialContinuousPages
                                + " elapsedMs=" + continuousMs
                                + " maxMs=" + initialContinuousMaxMs,
                        initialContinuousMaxMs <= 0L || continuousMs <= initialContinuousMaxMs);
            }
            if(holdAfterFirstDrawableMs > 0L) {
                Log.d(TAG, "ntk_true_random_hold_after_first_drawable ms="
                        + holdAfterFirstDrawableMs
                        + ",path=" + episode.getNtkEpisodePath());
                SystemClock.sleep(holdAfterFirstDrawableMs);
            }
            int initialPageCount = reader == null ? -1 : readPageCount(reader);
            probeScrollContinuity(context, device, reader, run, mode, episode,
                    scrollSteps, screenshotEvery, postStopDriftMs, assertNoJank,
                    maxMissedFrames, maxDroppedFrames, swipeInputSteps,
                    assertNoSchedulerGap, renderFrameMaxMs, scrollInputMode,
                    scrollPattern);
            if(appendProbe && reader != null)
                probeNextAppend(device, reader, run, mode, episode, nextEpisode,
                        initialPageCount, appendSteps);
        } finally {
            Manga.clearNtkViewerFetchModeOverrideForTest();
            if(activity != null)
                activity.finish();
            device.wait(Until.gone(By.desc("reader-drawable-ready")), 3000L);
            device.waitForIdle(1500L);
        }
        if(appendProbe && previousEpisode != null)
            runPreviousAppendCase(context, device, run, mode, title, episode, previousEpisode, appendSteps);
    }

    private static void runPreviousAppendCase(Context context, UiDevice device, int run, String mode,
                                              Title title, Manga episode, Manga previousEpisode,
                                              int appendSteps) {
        Activity activity = null;
        long startedAt = SystemClock.elapsedRealtime();
        try {
            episode.setMode(0);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            Manga.setNtkViewerFetchModeOverrideForTest(mode);
            Log.d(TAG, "ntk_true_random_previous_case_start run=" + run
                    + ",mode=" + mode
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",previousPath=" + previousEpisode.getNtkEpisodePath());
            activity = InstrumentationRegistry.getInstrumentation()
                    .startActivitySync(viewerIntent(context, title, episode));
            boolean ready = waitForDrawableReady(activity, device, 16000L);
            long firstMs = SystemClock.elapsedRealtime() - startedAt;
            assertTrue("Expected first drawable before previous append run=" + run
                    + " mode=" + mode
                    + " path=" + episode.getNtkEpisodePath()
                    + " elapsedMs=" + firstMs, ready);
            assertTrue("Expected previous append run=" + run
                            + " mode=" + mode
                            + " current=" + episode.getNtkEpisodePath()
                            + " previous=" + previousEpisode.getNtkEpisodePath(),
                    probePreviousAppend(device, (ReaderV2Activity) activity, run, mode,
                            episode, previousEpisode, Math.min(appendSteps, 12)));
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

    private static boolean waitForDrawableReady(Activity activity, UiDevice device, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while(SystemClock.elapsedRealtime() < deadline) {
            if(activityHasDrawableReadyMarker(activity))
                return true;
            String blockingStatus = readBlockingStatus(activity);
            if(blockingStatus.length() > 0) {
                Log.d(TAG, "ntk_true_random_first_drawable_fast_fail status=" + blockingStatus);
                return false;
            }
            if(device.hasObject(By.textContains("캡차"))) {
                Log.d(TAG, "ntk_true_random_first_drawable_fast_fail status=ui-captcha");
                return false;
            }
            if(activityHasBlockingStatus(activity)) {
                Log.d(TAG, "ntk_true_random_first_drawable_fast_fail status="
                        + readReaderStatusText(activity));
                return false;
            }
            if(device.wait(Until.hasObject(By.desc("reader-drawable-ready")), 50L))
                return true;
            if(activityHasDrawableReadyMarker(activity))
                return true;
            if(activityHasBlockingStatus(activity)) {
                Log.d(TAG, "ntk_true_random_first_drawable_fast_fail status="
                        + readReaderStatusText(activity));
                return false;
            }
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

    private static boolean activityHasBlockingStatus(Activity activity) {
        String status = readReaderStatusText(activity);
        return status.contains("캡차 확인이 필요합니다");
    }

    private static String readBlockingStatus(Activity activity) {
        if(!(activity instanceof ReaderV2Activity))
            return "";
        String value = ((ReaderV2Activity) activity).testBlockingStatus();
        return value == null ? "" : value;
    }

    private static String readReaderStatusText(Activity activity) {
        if(!(activity instanceof ReaderV2Activity))
            return "";
        final String[] value = new String[]{""};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                value[0] = ((ReaderV2Activity) activity).testStatusText());
        return value[0] == null ? "" : value[0];
    }

    private static long readFirstDrawableElapsedMs(Activity activity) {
        if(!(activity instanceof ReaderV2Activity))
            return -1L;
        final long[] value = new long[]{-1L};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                value[0] = ((ReaderV2Activity) activity).testFirstDrawableElapsedMs());
        return value[0];
    }

    private static boolean waitForInitialContinuousDrawable(ReaderV2Activity activity,
                                                            long timeoutMs,
                                                            int requiredPages) {
        if(activity == null || requiredPages <= 0)
            return false;
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while(SystemClock.elapsedRealtime() < deadline) {
            if(readInitialContinuousDrawableElapsedMs(activity, requiredPages) >= 0L)
                return true;
            SystemClock.sleep(80L);
        }
        return readInitialContinuousDrawableElapsedMs(activity, requiredPages) >= 0L;
    }

    private static long readInitialContinuousDrawableElapsedMs(ReaderV2Activity activity,
                                                               int requiredPages) {
        if(activity == null)
            return -1L;
        final long[] value = new long[]{-1L};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                value[0] = activity.testInitialContinuousDrawableElapsedMs(requiredPages));
        return value[0];
    }

    private static void probeScrollContinuity(Context context, UiDevice device, ReaderV2Activity reader, int run,
                                              String mode, Manga episode, int steps, int screenshotEvery,
                                              long postStopDriftMs, boolean assertNoJank,
                                              int maxMissedFrames, int maxDroppedFrames,
                                              int swipeInputSteps, boolean assertNoSchedulerGap,
                                              float renderFrameMaxMs, String scrollInputMode,
                                              String scrollPattern) {
        File screenshot = new File(context.getExternalCacheDir(), "ntk-random-scroll-" + run + ".png");
        for(int step = 0; step < steps; step++) {
            ProgressSnapshot progressBefore = readProgressSnapshot(reader);
            resetFrameStatsSnapshot(reader);
            ScrollGesture gesture = scrollGestureForStep(scrollPattern, step, swipeInputSteps);
            long before = SystemClock.elapsedRealtime();
            long dispatchMs = swipeReader(device, reader, gesture.startYRatio, gesture.endYRatio,
                    gesture.inputSteps, scrollInputMode);
            long swipeAt = SystemClock.elapsedRealtime();
            device.waitForIdle(450L);
            long idleAt = SystemClock.elapsedRealtime();
            ProgressSnapshot progressAfterIdle = readProgressSnapshot(reader);
            ProgressSnapshot progressAfterQuiet = waitForReaderQuietProgress(reader, 1800L);
            long quietAt = SystemClock.elapsedRealtime();
            SystemClock.sleep(900L);
            ProgressSnapshot progressAfterSettle = readProgressSnapshot(reader);
            ReaderSurfaceView.FrameStatsSnapshot frameStats = waitForFrameStatsSnapshot(reader, 1200L);
            DriftSample driftSample = monitorPostStopDrift(reader, progressAfterSettle, postStopDriftMs);
            ProgressSnapshot progressAfterLateSettle = driftSample.last;
            long screenshotStart = SystemClock.elapsedRealtime();
            boolean captureScreenshot = shouldCaptureScrollScreenshot(step, screenshotEvery);
            boolean captured = captureScreenshot && device.takeScreenshot(screenshot);
            long screenshotAt = SystemClock.elapsedRealtime();
            String stats = captured ? screenshotStats(screenshot)
                    : captureScreenshot ? "screenshot=false" : "screenshot=skipped";
            long statsAt = SystemClock.elapsedRealtime();
            ReaderSurfaceView.VisibleCoverageSnapshot coverage = readVisibleCoverage(reader);
            Log.d(TAG, "ntk_true_random_scroll run=" + run
                    + ",mode=" + mode
                    + ",step=" + step
                    + ",elapsedMs=" + (statsAt - before)
                    + ",dispatchMs=" + dispatchMs
                    + ",postSwipeMs=" + (swipeAt - before - dispatchMs)
                    + ",idleMs=" + (idleAt - swipeAt)
                    + ",quietMs=" + (quietAt - idleAt)
                    + ",screenshotMs=" + (screenshotAt - screenshotStart)
                    + ",statsMs=" + (statsAt - screenshotAt)
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",input=" + scrollInputMode
                    + ",gesture=" + gesture
                    + ",progressBefore=" + progressBefore
                    + ",progressAfterIdle=" + progressAfterIdle
                    + ",progressAfterQuiet=" + progressAfterQuiet
                    + ",progressAfterSettle=" + progressAfterSettle
                    + ",progressAfterLateSettle=" + progressAfterLateSettle
                    + ",postStopDriftMs=" + postStopDriftMs
                    + ",postStopDrift=" + driftSample
                    + ",frameStats=" + formatFrameStats(frameStats)
                    + ",coverage=" + formatCoverage(coverage)
                    + "," + stats);
            assertNoBackwardScrollJump("scroll run=" + run
                    + " mode=" + mode
                    + " step=" + step
                    + " phase=swipe"
                    + " path=" + episode.getNtkEpisodePath(), progressBefore, progressAfterIdle);
            assertNoBackwardScrollJump("scroll run=" + run
                    + " mode=" + mode
                    + " step=" + step
                    + " phase=settle"
                    + " path=" + episode.getNtkEpisodePath(), progressAfterQuiet, progressAfterSettle);
            assertNoUnexpectedSettleJump("scroll run=" + run
                    + " mode=" + mode
                    + " step=" + step
                    + " phase=quiet"
                    + " path=" + episode.getNtkEpisodePath(), progressAfterQuiet, progressAfterSettle);
            assertNoUnexpectedSettleJump("scroll run=" + run
                    + " mode=" + mode
                    + " step=" + step
                    + " phase=post-stop"
                    + " path=" + episode.getNtkEpisodePath(), progressAfterSettle, progressAfterLateSettle);
            assertNoPostStopDrift("scroll run=" + run
                    + " mode=" + mode
                    + " step=" + step
                    + " phase=post-stop-drift"
                    + " path=" + episode.getNtkEpisodePath(), driftSample);
            if(assertNoJank) {
                assertNoScrollJank("scroll run=" + run
                        + " mode=" + mode
                        + " step=" + step
                        + " path=" + episode.getNtkEpisodePath(),
                        frameStats, maxMissedFrames, maxDroppedFrames,
                        assertNoSchedulerGap, renderFrameMaxMs);
            }
            assertVisibleViewportReady("scroll run=" + run
                    + " mode=" + mode
                    + " step=" + step
                    + " path=" + episode.getNtkEpisodePath(), coverage);
        }
    }

    private static boolean shouldCaptureScrollScreenshot(int step, int screenshotEvery) {
        return screenshotEvery > 0 && step % screenshotEvery == 0;
    }

    private static ScrollGesture scrollGestureForStep(String pattern, int step, int baseInputSteps) {
        int baseSteps = Math.max(2, baseInputSteps);
        String normalized = pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
        if("reverse".equals(normalized) || "up".equals(normalized))
            return new ScrollGesture(0.24f, 0.82f, baseSteps, "reverse");
        if("slow".equals(normalized))
            return new ScrollGesture(0.82f, 0.24f, Math.max(baseSteps, 12), "slow");
        if("fast".equals(normalized))
            return new ScrollGesture(0.86f, 0.16f, Math.min(baseSteps, 4), "fast");
        if("mixed".equals(normalized)) {
            switch(Math.floorMod(step, 4)) {
                case 1:
                    return new ScrollGesture(0.78f, 0.32f, Math.max(baseSteps, 12), "slow-down");
                case 2:
                    return new ScrollGesture(0.24f, 0.82f, Math.max(3, Math.min(baseSteps, 5)), "reverse-fast");
                case 3:
                    return new ScrollGesture(0.70f, 0.42f, Math.max(baseSteps, 8), "short-down");
                case 0:
                default:
                    return new ScrollGesture(0.86f, 0.16f, Math.max(3, Math.min(baseSteps, 5)), "fast-down");
            }
        }
        return new ScrollGesture(0.82f, 0.24f, baseSteps, "down");
    }

    private static final class ScrollGesture {
        final float startYRatio;
        final float endYRatio;
        final int inputSteps;
        final String name;

        ScrollGesture(float startYRatio, float endYRatio, int inputSteps, String name) {
            this.startYRatio = startYRatio;
            this.endYRatio = endYRatio;
            this.inputSteps = inputSteps;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + ":" + String.format(Locale.US, "%.2f", startYRatio)
                    + "->" + String.format(Locale.US, "%.2f", endYRatio)
                    + "x" + inputSteps;
        }
    }

    private static void probeNextAppend(UiDevice device, ReaderV2Activity reader, int run,
                                        String mode, Manga episode, Manga nextEpisode,
                                        int initialPageCount, int maxSteps) {
        if(nextEpisode == null) {
            Log.d(TAG, "ntk_true_random_append_next run=" + run
                    + ",mode=" + mode
                    + ",expected=false,path=" + episode.getNtkEpisodePath());
            return;
        }
        int before = initialPageCount > 0 ? initialPageCount : readPageCount(reader);
        if(readPageCount(reader) > before || hasLoadedEpisode(reader, nextEpisode)) {
            Log.d(TAG, "ntk_true_random_append_next run=" + run
                    + ",mode=" + mode
                    + ",expected=true,success=true,alreadyAppended=true"
                    + ",alreadyLoaded=" + hasLoadedEpisode(reader, nextEpisode)
                    + ",before=" + before
                    + ",after=" + readPageCount(reader)
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",nextPath=" + nextEpisode.getNtkEpisodePath());
            return;
        }
        ReaderSession.AppendStartResult start = startAppend(reader, ReaderSurfaceView.DIRECTION_NEXT, before - 1);
        int polls = Math.max(1, maxSteps);
        for(int step = 0; step < polls; step++) {
            SystemClock.sleep(350L);
            device.waitForIdle(120L);
            int after = readPageCount(reader);
            int current = readCurrentPage(reader);
            if(after > before || hasLoadedEpisode(reader, nextEpisode)) {
                Log.d(TAG, "ntk_true_random_append_next run=" + run
                        + ",mode=" + mode
                        + ",expected=true,success=true,step=" + step
                        + ",start=" + start
                        + ",alreadyLoaded=" + hasLoadedEpisode(reader, nextEpisode)
                        + ",before=" + before
                        + ",after=" + after
                        + ",currentPage=" + current
                        + ",progress=" + readProgress(reader)
                        + ",path=" + episode.getNtkEpisodePath()
                        + ",nextPath=" + nextEpisode.getNtkEpisodePath());
                return;
            }
        }
        int after = readPageCount(reader);
        int current = readCurrentPage(reader);
        Log.d(TAG, "ntk_true_random_append_next run=" + run
                + ",mode=" + mode
                + ",expected=true,success=false"
                + ",start=" + start
                + ",before=" + before
                + ",after=" + after
                + ",currentPage=" + current
                + ",progress=" + readProgress(reader)
                + ",path=" + episode.getNtkEpisodePath()
                + ",nextPath=" + nextEpisode.getNtkEpisodePath());
        assertTrue("Next append did not load adjacent episode run=" + run
                + " mode=" + mode
                + " path=" + episode.getNtkEpisodePath()
                + " next=" + nextEpisode.getNtkEpisodePath()
                + " start=" + start
                + " before=" + before
                + " after=" + after
                + " currentPage=" + current
                + " progress=" + readProgress(reader),
                start != ReaderSession.AppendStartResult.STARTED
                        || after > before
                        || hasLoadedEpisode(reader, nextEpisode));
    }

    private static boolean probePreviousAppend(UiDevice device, ReaderV2Activity reader, int run,
                                               String mode, Manga episode, Manga previousEpisode,
                                               int maxSteps) {
        int before = readPageCount(reader);
        ReaderSession.AppendStartResult start = startAppend(reader, ReaderSurfaceView.DIRECTION_PREVIOUS, 0);
        int polls = Math.max(1, maxSteps);
        for(int step = 0; step < polls; step++) {
            SystemClock.sleep(350L);
            device.waitForIdle(120L);
            int after = readPageCount(reader);
            int current = readCurrentPage(reader);
            if(after > before) {
                Log.d(TAG, "ntk_true_random_append_previous run=" + run
                        + ",mode=" + mode
                        + ",expected=true,success=true,step=" + step
                        + ",start=" + start
                        + ",before=" + before
                        + ",after=" + after
                        + ",currentPage=" + current
                        + ",progress=" + readProgress(reader)
                        + ",path=" + episode.getNtkEpisodePath()
                        + ",previousPath=" + previousEpisode.getNtkEpisodePath());
                return true;
            }
            if(step == 0 || step == maxSteps - 1) {
                Log.d(TAG, "ntk_true_random_append_previous_probe run=" + run
                        + ",mode=" + mode
                        + ",step=" + step
                        + ",start=" + start
                        + ",before=" + before
                        + ",after=" + after
                        + ",currentPage=" + current
                        + ",progress=" + readProgress(reader)
                        + ",path=" + episode.getNtkEpisodePath()
                        + ",previousPath=" + previousEpisode.getNtkEpisodePath());
            }
        }
        Log.d(TAG, "ntk_true_random_append_previous run=" + run
                + ",mode=" + mode
                + ",expected=true,success=false"
                + ",start=" + start
                + ",before=" + before
                + ",after=" + readPageCount(reader)
                + ",currentPage=" + readCurrentPage(reader)
                + ",progress=" + readProgress(reader)
                + ",path=" + episode.getNtkEpisodePath()
                + ",previousPath=" + previousEpisode.getNtkEpisodePath());
        return false;
    }

    private static ReaderSession.AppendStartResult startAppend(ReaderV2Activity reader, int direction, int anchor) {
        AtomicReference<ReaderSession.AppendStartResult> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(reader.testStartBoundaryAppend(direction, anchor)));
        return result.get();
    }

    private static int readPageCount(ReaderV2Activity activity) {
        final int[] value = new int[]{-1};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value[0] = activity.testPageCount());
        return value[0];
    }

    private static int readCurrentPage(ReaderV2Activity activity) {
        final int[] value = new int[]{-1};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value[0] = activity.testCurrentPage());
        return value[0];
    }

    private static boolean hasLoadedEpisode(ReaderV2Activity activity, Manga episode) {
        final boolean[] value = new boolean[]{false};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> value[0] = activity.testHasLoadedEpisode(episode));
        return value[0];
    }

    private static String readProgress(ReaderV2Activity activity) {
        final String[] value = new String[]{"null"};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ReaderSurfaceView.ProgressPosition progress = activity.testCurrentProgressPosition();
            value[0] = progress == null ? "null" : progress.getPage() + ":" + progress.getOffset();
        });
        return value[0];
    }

    private static ProgressSnapshot readProgressSnapshot(ReaderV2Activity activity) {
        final ProgressSnapshot[] value = new ProgressSnapshot[]{ProgressSnapshot.NULL};
        if(activity == null)
            return value[0];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ReaderSurfaceView.ScrollPositionSnapshot progress =
                    activity.testCurrentScrollPositionSnapshot();
            value[0] = progress == null
                    ? ProgressSnapshot.NULL
                    : new ProgressSnapshot(progress.getPage(), progress.getOffset(),
                    progress.getScrollOffset(), progress.getBusy());
        });
        return value[0];
    }

    private static ProgressSnapshot waitForReaderQuietProgress(ReaderV2Activity activity, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        ProgressSnapshot latest = readProgressSnapshot(activity);
        ProgressSnapshot stableCandidate = latest;
        long stableSince = SystemClock.elapsedRealtime();
        while(SystemClock.elapsedRealtime() < deadline) {
            latest = readProgressSnapshot(activity);
            if(!sameProgress(stableCandidate, latest)) {
                stableCandidate = latest;
                stableSince = SystemClock.elapsedRealtime();
            }
            if(latest != null && !latest.busy
                    && SystemClock.elapsedRealtime() - stableSince >= SCROLL_QUIET_STABLE_MS)
                return latest;
            SystemClock.sleep(80L);
        }
        return latest;
    }

    private static boolean sameProgress(ProgressSnapshot a, ProgressSnapshot b) {
        if(a == null || b == null)
            return false;
        if(a.isNull() || b.isNull())
            return a.isNull() == b.isNull();
        if(a.hasScrollOffset() && b.hasScrollOffset())
            return Math.abs(a.scrollOffset - b.scrollOffset) <= SCROLL_POST_STOP_DRIFT_TOLERANCE_PX;
        return a.page == b.page && Math.abs(a.offset - b.offset) <= SCROLL_POST_STOP_DRIFT_TOLERANCE_PX;
    }

    private static ReaderSurfaceView.FrameStatsSnapshot waitForFrameStatsSnapshot(
            ReaderV2Activity activity, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        ReaderSurfaceView.FrameStatsSnapshot latest = readFrameStatsSnapshot(activity);
        while(SystemClock.elapsedRealtime() < deadline) {
            latest = readFrameStatsSnapshot(activity);
            if(latest != null && latest.getSamples() > 0)
                return latest;
            SystemClock.sleep(80L);
        }
        return latest;
    }

    private static ReaderSurfaceView.FrameStatsSnapshot readFrameStatsSnapshot(ReaderV2Activity activity) {
        final ReaderSurfaceView.FrameStatsSnapshot[] value =
                new ReaderSurfaceView.FrameStatsSnapshot[]{null};
        if(activity == null)
            return null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                value[0] = activity.testFrameStatsSnapshot());
        return value[0];
    }

    private static void resetFrameStatsSnapshot(ReaderV2Activity activity) {
        if(activity == null)
            return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::testResetFrameStatsSnapshot);
    }

    private static DriftSample monitorPostStopDrift(ReaderV2Activity activity, ProgressSnapshot baseline,
                                                    long durationMs) {
        if(durationMs <= 0L)
            return new DriftSample(baseline, baseline, 0, 0, 0, 0L);
        long deadline = SystemClock.elapsedRealtime() + durationMs;
        ProgressSnapshot last = baseline == null ? ProgressSnapshot.NULL : baseline;
        int maxPageDelta = 0;
        int maxOffsetDelta = 0;
        int changedSamples = 0;
        int samples = 0;
        while(SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(Math.min(SCROLL_DRIFT_SAMPLE_MS,
                    Math.max(1L, deadline - SystemClock.elapsedRealtime())));
            ProgressSnapshot next = readProgressSnapshot(activity);
            samples++;
            if(baseline != null && !baseline.isNull() && next != null && !next.isNull()) {
                int pageDelta = Math.abs(next.page - baseline.page);
                int offsetDelta = comparableOffsetDelta(baseline, next);
                maxPageDelta = Math.max(maxPageDelta, pageDelta);
                maxOffsetDelta = Math.max(maxOffsetDelta, offsetDelta);
                boolean changed = baseline.hasScrollOffset() && next.hasScrollOffset()
                        ? offsetDelta > SCROLL_POST_STOP_DRIFT_TOLERANCE_PX
                        : pageDelta != 0 || offsetDelta > SCROLL_POST_STOP_DRIFT_TOLERANCE_PX;
                if(changed)
                    changedSamples++;
            }
            last = next;
        }
        return new DriftSample(baseline, last, maxPageDelta, maxOffsetDelta, changedSamples, samples);
    }

    private static void assertNoBackwardScrollJump(String label, ProgressSnapshot before,
                                                   ProgressSnapshot after) {
        if(before == null || after == null || before.isNull() || after.isNull())
            return;
        boolean backwardPage = before.hasScrollOffset() && after.hasScrollOffset()
                ? after.scrollOffset < before.scrollOffset - SCROLL_BACKWARD_JUMP_TOLERANCE_PX
                : after.page < before.page;
        boolean backwardOffset = before.hasScrollOffset() && after.hasScrollOffset()
                ? false
                : after.page == before.page
                && after.offset > before.offset + SCROLL_BACKWARD_JUMP_TOLERANCE_PX;
        assertTrue(label
                        + " before=" + before
                        + " after=" + after,
                !backwardPage && !backwardOffset);
    }

    private static void assertNoUnexpectedSettleJump(String label, ProgressSnapshot before,
                                                     ProgressSnapshot after) {
        if(before == null || after == null || before.isNull() || after.isNull())
            return;
        int pageDelta = Math.abs(after.page - before.page);
        int offsetDelta = comparableOffsetDelta(before, after);
        if(before.hasScrollOffset() && after.hasScrollOffset()) {
            assertTrue(label
                            + " before=" + before
                            + " after=" + after
                            + " scrollDelta=" + offsetDelta,
                    offsetDelta <= SCROLL_SETTLE_JUMP_TOLERANCE_PX);
            return;
        }
        boolean samePageSmallMove = pageDelta == 0 && offsetDelta <= SCROLL_SETTLE_JUMP_TOLERANCE_PX;
        boolean adjacentEdgeMove = pageDelta == 1 && offsetDelta <= SCROLL_SETTLE_JUMP_TOLERANCE_PX;
        assertTrue(label
                        + " before=" + before
                        + " after=" + after
                        + " pageDelta=" + pageDelta
                        + " offsetDelta=" + offsetDelta,
                samePageSmallMove || adjacentEdgeMove);
    }

    private static void assertNoPostStopDrift(String label, DriftSample sample) {
        if(sample == null || sample.baseline == null || sample.baseline.isNull())
            return;
        assertTrue(label + " " + sample,
                (sample.baseline.hasScrollOffset() || sample.maxPageDelta == 0)
                        && sample.maxOffsetDelta <= SCROLL_POST_STOP_DRIFT_TOLERANCE_PX
                        && sample.changedSamples == 0);
    }

    private static int comparableOffsetDelta(ProgressSnapshot before, ProgressSnapshot after) {
        if(before != null && after != null && before.hasScrollOffset() && after.hasScrollOffset())
            return Math.abs(after.scrollOffset - before.scrollOffset);
        if(before == null || after == null)
            return Integer.MAX_VALUE;
        return Math.abs(after.offset - before.offset);
    }

    private static void assertNoScrollJank(String label, ReaderSurfaceView.FrameStatsSnapshot stats,
                                           int maxMissedFrames, int maxDroppedFrames,
                                           boolean assertNoSchedulerGap, float renderFrameMaxMs) {
        assertTrue(label + " missing frame stats", stats != null && stats.getSamples() > 0);
        if(assertNoSchedulerGap) {
            assertTrue(label + " frameStats=" + formatFrameStats(stats)
                            + " maxMissedFrames=" + maxMissedFrames,
                    stats.getMissedFrames() <= maxMissedFrames);
        }
        assertTrue(label + " frameStats=" + formatFrameStats(stats)
                        + " maxDroppedFrames=" + maxDroppedFrames,
                stats.getDroppedFrames() <= maxDroppedFrames
                        && stats.getDroppedFrameDebt() <= maxDroppedFrames);
        assertTrue(label + " frameStats=" + formatFrameStats(stats),
                stats.getNoCanvas() == 0);
        assertTrue(label + " frameStats=" + formatFrameStats(stats)
                        + " renderFrameMaxMs=" + renderFrameMaxMs,
                renderFrameMaxMs <= 0f || stats.getTotalMax() <= renderFrameMaxMs);
    }

    private static String formatFrameStats(ReaderSurfaceView.FrameStatsSnapshot stats) {
        if(stats == null)
            return "null";
        return "samples=" + stats.getSamples()
                + ";strictOverBudget=" + stats.getStrictOverBudget()
                + ";missedIntervals=" + stats.getMissedIntervals()
                + ";missedFrames=" + stats.getMissedFrames()
                + ";droppedFrames=" + stats.getDroppedFrames()
                + ";droppedFrameDebt=" + stats.getDroppedFrameDebt()
                + ";callbackP95=" + fmt(stats.getCallbackP95())
                + ";callbackMax=" + fmt(stats.getCallbackMax())
                + ";drawP95=" + fmt(stats.getDrawP95())
                + ";totalP95=" + fmt(stats.getTotalP95())
                + ";totalMax=" + fmt(stats.getTotalMax())
                + ";noCanvas=" + stats.getNoCanvas()
                + ";coalesced=" + stats.getCoalesced();
    }

    private static String fmt(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static final class DriftSample {
        final ProgressSnapshot baseline;
        final ProgressSnapshot last;
        final int maxPageDelta;
        final int maxOffsetDelta;
        final int changedSamples;
        final long samples;

        DriftSample(ProgressSnapshot baseline, ProgressSnapshot last, int maxPageDelta,
                    int maxOffsetDelta, int changedSamples, long samples) {
            this.baseline = baseline;
            this.last = last;
            this.maxPageDelta = maxPageDelta;
            this.maxOffsetDelta = maxOffsetDelta;
            this.changedSamples = changedSamples;
            this.samples = samples;
        }

        @Override
        public String toString() {
            return "baseline=" + baseline
                    + ";last=" + last
                    + ";maxPageDelta=" + maxPageDelta
                    + ";maxOffsetDelta=" + maxOffsetDelta
                    + ";changedSamples=" + changedSamples
                    + ";samples=" + samples;
        }
    }

    private static final class ProgressSnapshot {
        static final ProgressSnapshot NULL = new ProgressSnapshot(-1, 0, Integer.MIN_VALUE, false);
        final int page;
        final int offset;
        final int scrollOffset;
        final boolean busy;

        ProgressSnapshot(int page, int offset, int scrollOffset, boolean busy) {
            this.page = page;
            this.offset = offset;
            this.scrollOffset = scrollOffset;
            this.busy = busy;
        }

        boolean isNull() {
            return page < 0;
        }

        boolean hasScrollOffset() {
            return scrollOffset != Integer.MIN_VALUE;
        }

        @Override
        public String toString() {
            if(isNull())
                return "null";
            return page + ":" + offset + "@" + scrollOffset;
        }
    }

    private static ReaderSurfaceView.VisibleCoverageSnapshot readVisibleCoverage(ReaderV2Activity activity) {
        final ReaderSurfaceView.VisibleCoverageSnapshot[] value =
                new ReaderSurfaceView.VisibleCoverageSnapshot[]{null};
        if(activity == null)
            return null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> value[0] = activity.testVisibleCoverageSnapshot());
        return value[0];
    }

    private static void assertVisibleViewportReady(String label,
                                                   ReaderSurfaceView.VisibleCoverageSnapshot coverage) {
        assertTrue("Expected visible viewport with no loading " + label
                        + " coverage=" + formatCoverage(coverage),
                isVisibleViewportReady(coverage));
    }

    private static boolean isVisibleViewportReady(ReaderSurfaceView.VisibleCoverageSnapshot coverage) {
        if(coverage == null
                || coverage.getPlaceholderPx() != 0
                || coverage.getVisibleLoading() != 0
                || coverage.getVisibleErrors() != 0)
            return false;
        if(coverage.getMissingPx() <= 2
                && coverage.getDrawablePx() >= Math.max(1, coverage.getViewportPx() - 2))
            return true;
        return coverage.getPageCount() <= 1
                && coverage.getDrawableItems() > 0
                && coverage.getDrawablePx() > 0
                && coverage.getVisibleCards() == 0;
    }

    private static String formatCoverage(ReaderSurfaceView.VisibleCoverageSnapshot coverage) {
        if(coverage == null)
            return "null";
        return "viewportPx=" + coverage.getViewportPx()
                + ";drawablePx=" + coverage.getDrawablePx()
                + ";missingPx=" + coverage.getMissingPx()
                + ";placeholderPx=" + coverage.getPlaceholderPx()
                + ";drawableItems=" + coverage.getDrawableItems()
                + ";items=" + coverage.getTotalItems()
                + ";loading=" + coverage.getVisibleLoading()
                + ";errors=" + coverage.getVisibleErrors()
                + ";cards=" + coverage.getVisibleCards()
                + ";busy=" + coverage.getBusy()
                + ";pages=" + coverage.getPageCount();
    }

    private static long swipeReader(UiDevice device, ReaderV2Activity reader,
                                    float startYRatio, float endYRatio, int steps,
                                    String inputMode) {
        if(reader == null)
            return 0L;
        final int[] bounds = new int[4];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View view = reader.findViewById(ml.melun.mangaview.R.id.strip);
            if(view == null)
                view = reader.getWindow().getDecorView();
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            bounds[0] = location[0];
            bounds[1] = location[1];
            bounds[2] = Math.max(1, view.getWidth());
            bounds[3] = Math.max(1, view.getHeight());
        });
        int x = bounds[0] + bounds[2] / 2;
        float startY = bounds[1] + bounds[3] * startYRatio;
        float endY = bounds[1] + bounds[3] * endYRatio;
        long startedAt = SystemClock.elapsedRealtime();
        String normalizedMode = inputMode == null ? "" : inputMode.trim().toLowerCase(Locale.ROOT);
        if("direct".equals(normalizedMode) || "programmatic".equals(normalizedMode)) {
            float scrollDelta = startY - endY;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    reader.testScrollByPixels(scrollDelta));
            return SystemClock.elapsedRealtime() - startedAt;
        }
        long downTime = SystemClock.uptimeMillis();
        int safeSteps = Math.max(1, Math.min(steps, 12));
        dispatchTouch(reader, downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, false);
        for(int step = 1; step < safeSteps; step++) {
            float fraction = step / (float)safeSteps;
            long eventTime = downTime + step * 18L;
            float y = startY + (endY - startY) * fraction;
            dispatchTouch(reader, downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, false);
            SystemClock.sleep(12L);
        }
        dispatchTouch(reader, downTime, downTime + safeSteps * 18L,
                MotionEvent.ACTION_UP, x, endY, false);
        return SystemClock.elapsedRealtime() - startedAt;
    }

    private static void dispatchTouch(ReaderV2Activity reader, long downTime, long eventTime,
                                      int action, float x, float y) {
        dispatchTouch(reader, downTime, eventTime, action, x, y, true);
    }

    private static void dispatchTouch(ReaderV2Activity reader, long downTime, long eventTime,
                                      int action, float x, float y, boolean sync) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            boolean injected = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .injectInputEvent(event, sync);
            assertTrue("Expected injected touch event action=" + action, injected);
        } finally {
            event.recycle();
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

    private static String normalizedArg(Bundle args, String key, String fallback) {
        String value = arg(args, key, fallback);
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return value.length() == 0 ? fallback : value;
    }

    private static int parseBaseMode(String value) {
        if(value == null)
            return 0;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if(normalized.length() == 0 || "random".equals(normalized))
            return 0;
        if("webtoon".equals(normalized) || "toon".equals(normalized))
            return MTitle.base_webtoon;
        if("comic".equals(normalized) || "manhwa".equals(normalized) || "manga".equals(normalized))
            return MTitle.base_comic;
        return parsePositiveInt(normalized, 0);
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int parseNonNegativeInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value);
            return parsed >= 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseNonNegativeLong(String value, long fallback) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value);
            return parsed >= 0L ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float parseNonNegativeFloat(String value, float fallback) {
        try {
            float parsed = Float.parseFloat(value == null ? "" : value);
            return parsed >= 0f ? parsed : fallback;
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

    private static String canonicalWorkId(JSONObject work) {
        String sourceWorkId = work == null ? "" : work.optString("sourceWorkId", "");
        if(parsePositiveInt(sourceWorkId, 0) > 0)
            return sourceWorkId.trim();
        String id = work == null ? "" : work.optString("id", "");
        if(parsePositiveInt(id, 0) > 0)
            return id.trim();
        return firstNonEmpty(sourceWorkId, id);
    }

    private static int stableId(String value) {
        int hash = 0x811c9dc5;
        for(int i = 0; value != null && i < value.length(); i++)
            hash = (hash ^ value.charAt(i)) * 0x01000193;
        hash &= 0x7fffffff;
        return hash == 0 ? 1 : hash;
    }
}
