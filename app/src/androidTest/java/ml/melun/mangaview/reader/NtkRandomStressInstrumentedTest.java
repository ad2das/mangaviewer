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
import android.webkit.CookieManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.ClassificationDbStore;
import ml.melun.mangaview.ClassificationDbUpdater;
import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.NtkDeviceIdentityManager;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.CaptchaActivity;
import ml.melun.mangaview.activity.NtkQuicFetcher;
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
    private static final int API_RANDOM_PAGE_SIZE = 1;
    private static final long PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final String[] MODES = new String[]{"generated", "native-ack", "api-fallback"};
    private static final String NTK_ALIAS_WEBTOON_URL = "https://newtoki1.org";
    private static final String NTK_ALIAS_COMIC_URL = NTK_ALIAS_WEBTOON_URL + "/manhwa";
    private static final long NTK_CAPTCHA_PROBE_WAIT_MS = 8_000L;
    private static final long SAFE_DISCOVERY_GAP_MS = 550L;
    private static final int DEFAULT_LIVE_RANDOM_CHALLENGE_RECOVERIES = 2;
    private static final int SAFE_RANDOM_RUNS = 4;
    private static final int SAFE_SCROLL_STEPS = 6;
    private static final int SAFE_APPEND_STEPS = 36;
    private static final int DEFAULT_SWIPE_INPUT_STEPS = 5;
    private static final int STRICT_MIN_FULL_SWEEP_STEPS = 16;
    private static final int STRICT_MAX_FULL_SWEEP_STEPS = 48;
    private static final int STRICT_SCROLL_PAGES_PER_STEP = 4;
    private static final int STRICT_END_BURST_COUNT = 18;
    private static final int STRICT_END_SWIPES_PER_BURST = 8;
    private static final int STRICT_NEXT_EPISODE_SAMPLE_PAGES = 4;
    private static final int IMMEDIATE_SCROLL_SWIPES = 3;
    private static final long DEFAULT_FIRST_DRAWABLE_MAX_MS = 3_500L;
    private static final float DEFAULT_RENDER_FRAME_MAX_MS = 33.34f;
    private static final int LARGE_EPISODE_VISIBLE_UX_PAGE_THRESHOLD = 64;
    private static final int SCROLL_BACKWARD_JUMP_TOLERANCE_PX = 240;
    private static final int SCROLL_SETTLE_JUMP_TOLERANCE_PX = 420;
    private static final int SCROLL_POST_STOP_DRIFT_TOLERANCE_PX = 24;
    private static final long SCROLL_DRIFT_SAMPLE_MS = 250L;
    private static final long SCROLL_QUIET_STABLE_MS = 480L;
    private static final long SCROLL_QUIET_TIMEOUT_MS = 3_500L;
    private static final long SCROLL_SETTLE_CONFIRM_MS = 250L;
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
            assertTrue("NTK_ENSURE_ACCESS_FAILED dump path=" + path,
                    ensureNtkAccessBeforeMeasurement(context, client, baseMode, path, ensureAccessMaxMs));
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
        boolean requireLiveRandom = Boolean.parseBoolean(arg(args, "ntkRequireLiveRandom", "false"));
        int runs = parsePositiveInt(arg(args, "ntkRandomRuns",
                Integer.toString(safeNetwork ? SAFE_RANDOM_RUNS : 12)), safeNetwork ? SAFE_RANDOM_RUNS : 12);
        int scrollSteps = parseNonNegativeInt(arg(args, "ntkScrollSteps",
                Integer.toString(safeNetwork ? SAFE_SCROLL_STEPS : 8)), safeNetwork ? SAFE_SCROLL_STEPS : 8);
        boolean appendProbe = Boolean.parseBoolean(arg(args, "ntkAppendProbe", "true"));
        int appendSteps = parsePositiveInt(arg(args, "ntkAppendSteps",
                Integer.toString(safeNetwork ? SAFE_APPEND_STEPS : 60)), safeNetwork ? SAFE_APPEND_STEPS : 60);
        boolean probePreviousAppend = Boolean.parseBoolean(arg(args, "ntkProbePreviousAppend", "false"));
        int screenshotEvery = parseNonNegativeInt(arg(args, "ntkScreenshotEvery", "0"), 0);
        int swipeInputSteps = parsePositiveInt(arg(args, "ntkSwipeInputSteps",
                Integer.toString(DEFAULT_SWIPE_INPUT_STEPS)), DEFAULT_SWIPE_INPUT_STEPS);
        String scrollInputMode = normalizedArg(args, "ntkScrollInputMode", "touch");
        String scrollPattern = normalizedArg(args, "ntkScrollPattern", "mixed");
        boolean strictRealUx = Boolean.parseBoolean(arg(args, "ntkStrictRealUx", "true"));
        boolean immediateScrollBeforeReady = Boolean.parseBoolean(arg(args,
                "ntkImmediateScrollBeforeReady", strictRealUx ? "true" : "false"));
        boolean launchPreflight = Boolean.parseBoolean(arg(args, "ntkLaunchPreflight",
                strictRealUx ? "false" : "true"));
        long firstDrawableMaxMs = parseNonNegativeLong(
                arg(args, "ntkFirstDrawableMaxMs", Long.toString(DEFAULT_FIRST_DRAWABLE_MAX_MS)),
                DEFAULT_FIRST_DRAWABLE_MAX_MS);
        if(strictRealUx && (firstDrawableMaxMs <= 0L || firstDrawableMaxMs > DEFAULT_FIRST_DRAWABLE_MAX_MS))
            firstDrawableMaxMs = DEFAULT_FIRST_DRAWABLE_MAX_MS;
        int initialContinuousPages = parseNonNegativeInt(
                arg(args, "ntkInitialContinuousPages", "0"), 0);
        long initialContinuousMaxMs = parseNonNegativeLong(
                arg(args, "ntkInitialContinuousMaxMs", Long.toString(firstDrawableMaxMs)),
                firstDrawableMaxMs);
        long holdAfterFirstDrawableMs = parseNonNegativeLong(
                arg(args, "ntkHoldAfterFirstDrawableMs", "0"), 0L);
        boolean requireAllPagesDrawable = Boolean.parseBoolean(arg(args,
                "ntkRequireAllPagesDrawable", "false"));
        long allPagesDrawableMaxMs = parseNonNegativeLong(arg(args,
                "ntkAllPagesDrawableMaxMs", "60000"), 60000L);
        boolean requireStrictAck = Boolean.parseBoolean(arg(args, "ntkRequireStrictAck",
                requireLiveRandom ? "true" : "false"));
        boolean assertNoJank = Boolean.parseBoolean(arg(args, "ntkAssertNoJank", "true"));
        boolean assertNoSchedulerGap = Boolean.parseBoolean(arg(args, "ntkAssertNoSchedulerGap",
                strictRealUx ? "true" : "false"));
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
        Random baseModeRandom = new Random(seed ^ 0x427251bdL);
        boolean cycleModes = Boolean.parseBoolean(arg(args, "ntkCycleModes", "true"));
        boolean cycleBaseModes = Boolean.parseBoolean(arg(args, "ntkCycleBaseModes", "true"));
        int fixedBaseMode = parseBaseMode(arg(args, "ntkBaseMode", ""));
        String fixedSource = arg(args, "ntkSource", "").trim().toLowerCase(Locale.ROOT);
        if(fixedBaseMode <= 0 && fixedSource.length() > 0) {
            if("webtoon".equals(fixedSource)) {
                fixedBaseMode = MTitle.base_webtoon;
                cycleBaseModes = false;
            } else if("manga".equals(fixedSource) || "manhwa".equals(fixedSource)
                    || "comic".equals(fixedSource)) {
                fixedBaseMode = MTitle.base_comic;
                cycleBaseModes = false;
            }
        }
        int modeOffset = modeRandom.nextInt(MODES.length);
        int baseModeOffset = baseModeRandom.nextInt(2);
        CustomHttpClient client = MainApplication.getHttpClient();
        String siteRoot = arg(args, "ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL);
        boolean lockSiteRoot = Boolean.parseBoolean(arg(args, "ntkLockSiteRoot",
                args.containsKey("ntkSiteRoot") ? "true" : "false"));
        client.setNtkDomainAutoResolveDisabledForTest(lockSiteRoot);
        if(lockSiteRoot)
            MainApplication.p.setNtkSitePresetForDiagnostics(siteRoot);
        else
            MainApplication.p.setNtkSitePreset(siteRoot);
        if(!lockSiteRoot)
            client.resolveNtkDomainNow();
        String customUserAgent = arg(args, "ntkUserAgent", "");
        if(customUserAgent.trim().length() > 0) {
            client.agent = customUserAgent.trim();
            Log.d(TAG, "ntk_true_random_user_agent=" + customUserAgent.trim());
        }
        String targetEpisodePath = arg(args, "ntkTargetEpisodePath", "").trim();
        String targetTitlePath = arg(args, "ntkTargetTitlePath", "").trim();
        String targetImageEpisodeId = arg(args, "ntkTargetImageEpisodeId", "").trim();
        String targetImageWorkId = arg(args, "ntkTargetImageWorkId", "").trim();
        int targetImageCount = parseNonNegativeInt(arg(args, "ntkTargetImageCount", "0"), 0);
        String directNextEpisodePath = arg(args, "ntkDirectNextEpisodePath", "").trim();
        String directNextImageEpisodeId = arg(args, "ntkDirectNextImageEpisodeId", "").trim();
        String directNextImageWorkId = arg(args, "ntkDirectNextImageWorkId", "").trim();
        int directNextImageCount = parseNonNegativeInt(arg(args, "ntkDirectNextImageCount", "0"), 0);
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
        boolean resetDeviceIdentityBeforeRun = Boolean.parseBoolean(
                arg(args, "ntkResetDeviceIdentityBeforeRun", "false"));
        int liveRandomChallengeRecoveries = parseNonNegativeInt(arg(args,
                "ntkLiveRandomChallengeRecoveries",
                Integer.toString(DEFAULT_LIVE_RANDOM_CHALLENGE_RECOVERIES)),
                DEFAULT_LIVE_RANDOM_CHALLENGE_RECOVERIES);

        Log.d(TAG, "ntk_true_random_start runs=" + runs
                + ",seed=" + seed
                + ",scrollSteps=" + scrollSteps
                + ",appendProbe=" + appendProbe
                + ",probePreviousAppend=" + probePreviousAppend
                + ",appendSteps=" + appendSteps
                + ",screenshotEvery=" + screenshotEvery
                + ",swipeInputSteps=" + swipeInputSteps
                + ",scrollInputMode=" + scrollInputMode
                + ",scrollPattern=" + scrollPattern
                + ",firstDrawableMaxMs=" + firstDrawableMaxMs
                + ",strictRealUx=" + strictRealUx
                + ",initialContinuousPages=" + initialContinuousPages
                + ",initialContinuousMaxMs=" + initialContinuousMaxMs
                + ",holdAfterFirstDrawableMs=" + holdAfterFirstDrawableMs
                + ",immediateScrollBeforeReady=" + immediateScrollBeforeReady
                + ",requireStrictAck=" + requireStrictAck
                + ",assertNoJank=" + assertNoJank
                + ",assertNoSchedulerGap=" + assertNoSchedulerGap
                + ",maxMissedFrames=" + maxMissedFrames
                + ",maxDroppedFrames=" + maxDroppedFrames
                + ",renderFrameMaxMs=" + renderFrameMaxMs
                + ",postStopDriftMs=" + postStopDriftMs
                + ",cycleModes=" + cycleModes
                + ",cycleBaseModes=" + cycleBaseModes
                + ",baseMode=" + fixedBaseMode
                + ",fixedMode=" + fixedMode
                + ",safeNetwork=" + safeNetwork
                + ",requireLiveRandom=" + requireLiveRandom
                + ",requestedSiteRoot=" + siteRoot
                + ",siteRoot=" + MainApplication.p.getWebtoonUrl()
                + ",lockSiteRoot=" + lockSiteRoot
                + ",changeDeviceIdentityBeforeRun=" + changeDeviceIdentityBeforeRun
                + ",resetDeviceIdentityBeforeRun=" + resetDeviceIdentityBeforeRun
                + ",liveRandomChallengeRecoveries=" + liveRandomChallengeRecoveries
                + ",modeOffset=" + modeOffset);
        if(targetEpisodePath.length() > 0 || targetEpisodeNumber > 0) {
            TargetEpisode target = loadTargetEpisode(context, client, targetTitlePath, targetEpisodePath,
                    targetEpisodeNumber, fixedBaseMode, directTargetEpisode, targetImageEpisodeId,
                    targetImageWorkId, targetImageCount, directNextEpisodePath,
                    directNextImageEpisodeId, directNextImageWorkId, directNextImageCount);
            for(int run = 0; run < runs; run++) {
                String mode = modeForRun(fixedMode, cycleModes, random, modeOffset, run);
                prepareFreshNtkMeasurementState(context, client, clearAckBeforeRun,
                        clearReaderImageCacheBeforeRun, changeDeviceIdentityBeforeRun,
                        resetDeviceIdentityBeforeRun,
                        normalizeTargetPath(targetEpisodePath));
                if(ensureAccessBefore)
                    assertTrue("NTK_ENSURE_ACCESS_FAILED path=" + targetEpisodePath,
                            ensureNtkAccessBeforeMeasurement(context, client, fixedBaseMode, targetEpisodePath,
                                    ensureAccessMaxMs));
                runReaderCase(context, device, run, mode, target.title, target.episode,
                        scrollSteps, appendProbe, probePreviousAppend, appendSteps,
                        screenshotEvery, postStopDriftMs,
                        firstDrawableMaxMs, initialContinuousPages, initialContinuousMaxMs,
                        assertNoJank, maxMissedFrames, maxDroppedFrames,
                        swipeInputSteps, assertNoSchedulerGap, renderFrameMaxMs,
                        holdAfterFirstDrawableMs, requireStrictAck, requireAllPagesDrawable,
                        allPagesDrawableMaxMs, scrollInputMode, scrollPattern,
                        immediateScrollBeforeReady, strictRealUx, launchPreflight);
            }
            return;
        }
        int completedRuns = 0;
        int discoveryAttempts = 0;
        int maxDiscoveryAttempts = Math.max(8, runs * 5);
        int challengeRecoveries = 0;
        Set<String> usedEpisodePaths = new HashSet<>();
        ArrayList<Title> liveRandomTitlePool = new ArrayList<>();
        while(completedRuns < runs && discoveryAttempts < maxDiscoveryAttempts) {
            int run = completedRuns;
            discoveryAttempts++;
            int baseMode = fixedBaseMode > 0
                    ? fixedBaseMode
                    : baseModeForRun(cycleBaseModes, random, baseModeOffset, run);
            MainApplication.p.setBaseMode(baseMode);
            Title title = null;
            int fetchResult = Title.LOAD_ERROR;
            for(int titleAttempt = 0; titleAttempt < 6; titleAttempt++) {
                Title candidate;
                try {
                    candidate = pickRandomTitle(context, client, random, baseMode, safeNetwork,
                            requireLiveRandom, challengeRecoveries < liveRandomChallengeRecoveries);
                } catch (Throwable e) {
                    Log.d(TAG, "ntk_true_random_title_discovery_error run=" + run
                            + ",discoveryAttempt=" + discoveryAttempts
                            + ",titleAttempt=" + titleAttempt
                            + ",baseMode=" + baseMode
                            + ",type=" + e.getClass().getSimpleName()
                            + ",message=" + e.getMessage());
                    if(isCloudflareFailure(e))
                        challengeRecoveries++;
                    break;
                }
                fetchResult = candidate.getEps() != null && candidate.getEps().size() > 0
                        ? Title.LOAD_OK : candidate.fetchEps(client);
                if(fetchResult != Title.LOAD_OK || candidate.getEps() == null || candidate.getEps().size() == 0) {
                    if(safeNetwork && ntkBlockedWithoutProof(client) && !requireLiveRandom) {
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
                    rememberLiveRandomTitle(liveRandomTitlePool, candidate);
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
                title = pickRandomTitleFromLiveRunPool(liveRandomTitlePool, random, usedEpisodePaths);
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
            String mode = modeForRun(fixedMode, cycleModes, random, modeOffset, run);
            Manga episode = null;
            for(int episodeAttempt = 0; episodeAttempt < 8; episodeAttempt++) {
                Manga candidateEpisode = pickRandomEpisode(title.getEps(), random, usedEpisodePaths);
                assertTrue("Expected picked NTK episode path for run=" + run
                                + " title=" + title.getName()
                                + " episode=" + candidateEpisode.getName(),
                        candidateEpisode.getNtkEpisodePath().length() > 0);
                if(shouldSkipEpisodeBeforeMeasurement(candidateEpisode, mode)) {
                    usedEpisodePaths.add(candidateEpisode.getNtkEpisodePath());
                    Log.d(TAG, "ntk_true_random_episode_skip_generated_dead run=" + run
                            + ",attempt=" + episodeAttempt
                            + ",title=" + title.getName()
                            + ",path=" + candidateEpisode.getNtkEpisodePath()
                            + ",imageEpisodeId=" + candidateEpisode.getNtkImageEpisodeId()
                            + ",imageWorkId=" + candidateEpisode.getNtkImageWorkId()
                            + ",imageCount=" + candidateEpisode.getNtkImageCount());
                    continue;
                }
                episode = candidateEpisode;
                break;
            }
            if(episode == null) {
                Log.d(TAG, "ntk_true_random_title_skip_generated_dead run=" + run
                        + ",title=" + title.getName()
                        + ",id=" + title.getId()
                        + ",path=" + title.getPath());
                if(safeNetwork)
                    safeDiscoveryPause(client);
                continue;
            }
            assertTrue("Expected picked NTK episode path for run=" + run
                            + " title=" + title.getName()
                            + " episode=" + episode.getName(),
                    episode.getNtkEpisodePath().length() > 0);
            usedEpisodePaths.add(episode.getNtkEpisodePath());
            prepareFreshNtkMeasurementState(context, client, clearAckBeforeRun,
                    clearReaderImageCacheBeforeRun, changeDeviceIdentityBeforeRun,
                    resetDeviceIdentityBeforeRun,
                    episode.getNtkEpisodePath());
            if(ensureAccessBefore)
                assertTrue("NTK_ENSURE_ACCESS_FAILED path=" + episode.getNtkEpisodePath(),
                        ensureNtkAccessBeforeMeasurement(context, client, baseMode,
                                episode.getNtkEpisodePath(), ensureAccessMaxMs));
            runReaderCase(context, device, run, mode, title, episode,
                    scrollSteps, appendProbe, probePreviousAppend, appendSteps,
                    screenshotEvery, postStopDriftMs,
                    firstDrawableMaxMs, initialContinuousPages, initialContinuousMaxMs,
                    assertNoJank, maxMissedFrames, maxDroppedFrames,
                    swipeInputSteps, assertNoSchedulerGap, renderFrameMaxMs,
                    holdAfterFirstDrawableMs, requireStrictAck, requireAllPagesDrawable,
                    allPagesDrawableMaxMs, scrollInputMode, scrollPattern,
                    immediateScrollBeforeReady, strictRealUx, launchPreflight);
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
                                                        boolean resetDeviceIdentity,
                                                        String targetPath) {
        if(clearAck && client != null) {
            String webtoonRoot = MainApplication.p == null
                    ? CustomHttpClient.NTK_WEBTOON_URL : MainApplication.p.getWebtoonUrl();
            String clearUrl = targetPath == null || targetPath.length() == 0
                    ? webtoonRoot
                    : (targetPath.startsWith("http") ? targetPath : webtoonRoot + targetPath);
            if(clearReaderImageCache) {
                client.clearCloudflareWebViewCookiesAggressively(clearUrl, webtoonRoot,
                        CustomHttpClient.NTK_WEBTOON_URL,
                        CustomHttpClient.NTK_COMIC_URL,
                        "https://toonflix.app");
                Log.d(TAG, "ntk_strict_fresh_webview_cf_clear_for_test url=" + clearUrl);
                client.clearNtkAckStateForTest(clearUrl, false);
            } else {
                client.clearNtkAckStateForTest(clearUrl);
            }
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
        if(resetDeviceIdentity && context != null) {
            AtomicReference<String> agentRef = new AtomicReference<>("");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    agentRef.set(NtkDeviceIdentityManager.resetDeviceInfo(context, false)));
            Log.d(TAG, "ntk_device_identity_reset_for_test ua=" + agentRef.get());
        } else if(changeDeviceIdentity && context != null) {
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
        if(isNtkApiChallengeUrl(url)) {
            Log.d(TAG, "ntk_true_random_captcha_api_challenge_root_recover baseMode=" + baseMode
                    + ",url=" + url
                    + ",root=" + webtoonRoot);
            url = webtoonRoot + "/";
        }
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

    private static boolean ensureNtkAccessBeforeMeasurement(Context context, CustomHttpClient client,
                                                            int baseMode, String targetPath, long maxMs) {
        if(client == null || maxMs <= 0)
            return false;
        if(context == null)
            context = ApplicationProvider.getApplicationContext();
        String webtoonRoot = MainApplication.p == null
                ? CustomHttpClient.NTK_WEBTOON_URL : MainApplication.p.getWebtoonUrl();
        String url = normalizeTargetPath(targetPath);
        if(url.length() == 0)
            url = webtoonRoot + "/";
        else if(url.startsWith("/"))
            url = webtoonRoot + url;
        if(hasVerifiedNtkAccessForMeasurement(context, client, webtoonRoot, url))
            return true;
        long startedAt = SystemClock.elapsedRealtime();
        Log.d(TAG, "ntk_true_random_pre_captcha_start baseMode=" + baseMode
                + ",url=" + url
                + ",maxMs=" + maxMs);
        Intent intent = new Intent(context, CaptchaActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("url", url);
        Activity activity = null;
        boolean verified = false;
        try {
            activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
            long deadline = SystemClock.elapsedRealtime() + maxMs;
            while(SystemClock.elapsedRealtime() < deadline) {
                client.syncCookiesFromWebView(webtoonRoot, true);
                client.syncCookiesFromWebView(client.getUrl(), true);
                client.syncCookiesFromWebView(url, true);
                if(hasVerifiedNtkAccessForMeasurement(context, client, webtoonRoot, url)) {
                    verified = true;
                    break;
                }
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
                    + ",strong=" + hasStrongNtkAccessCookies(webtoonRoot, url)
                    + ",cookies=" + summarizeNtkAccessCookieState(webtoonRoot, url)
                    + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
        }
        if(!verified)
            verified = hasVerifiedNtkAccessForMeasurement(context, client, webtoonRoot, url);
        if(!verified) {
            Log.d(TAG, "ntk_true_random_pre_captcha_failed baseMode=" + baseMode
                    + ",target=" + targetPath
                    + ",maxMs=" + maxMs
                    + ",proof=" + client.hasNtkAccessProof()
                    + ",strong=" + hasStrongNtkAccessCookies(webtoonRoot, url)
                    + ",cookies=" + summarizeNtkAccessCookieState(webtoonRoot, url));
        }
        return verified;
    }

    private static boolean hasVerifiedNtkAccessForMeasurement(Context context, CustomHttpClient client,
                                                              String webtoonRoot, String url) {
        if(client == null)
            return false;
        String path = normalizeTargetPath(url);
        if(path.startsWith(webtoonRoot))
            path = path.substring(webtoonRoot.length());
        if(path.length() == 0 || !path.startsWith("/"))
            path = "/api/manhwa-list?page=1&pageSize=1&withTotal=1";
        boolean strongCookies = hasStrongNtkAccessCookies(webtoonRoot, url);
        boolean accessProof = client.hasNtkAccessProof();
        boolean serverAckProof = isNtkEpisodeDocumentPath(path) && client.hasRecentNtkServerAckProof(path);
        if(!strongCookies && !accessProof && !serverAckProof)
            return false;
        boolean documentOk = verifyNtkDocumentAccess(client, path);
        boolean rscOk = !isNtkEpisodeDocumentPath(path) || verifyNtkRscAccess(context, client, webtoonRoot, path);
        boolean ok = documentOk;
        Log.d(TAG, "ntk_true_random_access_verify path=" + path
                + ",documentOk=" + documentOk
                + ",rscOk=" + rscOk
                + ",ok=" + ok
                + ",proof=" + accessProof
                + ",serverAckProof=" + serverAckProof
                + ",strongCookies=" + strongCookies
                + ",cookies=" + summarizeNtkAccessCookieState(webtoonRoot, url));
        return ok;
    }

    private static boolean verifyNtkDocumentAccess(CustomHttpClient client, String path) {
        try {
            okhttp3.Response response = client.mget(path, true);
            if(response == null) {
                Log.d(TAG, "ntk_true_random_access_verify_document_empty path=" + path);
                return false;
            }
            int code = response.code();
            String body = client.readBody(response);
            boolean ok = code >= 200 && code < 400
                    && isUsableNtkAccessDocument(body)
                    && !client.isCloudflareChallengeResponse(code, body);
            if(!ok)
                Log.d(TAG, "ntk_true_random_access_verify_document_failed path=" + path
                        + ",code=" + code
                        + ",sample=" + shortSample(body));
            return ok;
        } catch(Exception e) {
            Log.d(TAG, "ntk_true_random_access_verify_document_error path=" + path + "," + e);
            return false;
        }
    }

    private static boolean verifyNtkRscAccess(Context context, CustomHttpClient client,
                                              String webtoonRoot, String path) {
        if(!NtkQuicFetcher.isAvailable())
            return true;
        try {
            String baseUrl = client.getUrl(path);
            String targetUrl = baseUrl + path;
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("accept", "text/x-component");
            headers.put("rsc", "1");
            headers.put("next-url", path);
            headers.put("origin", baseUrl);
            headers.put("referer", targetUrl);
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(
                    context,
                    targetUrl,
                    client.agent,
                    client.getCookieHeader(),
                    headers,
                    "GET",
                    null,
                    7000L);
            String body = result == null || result.body == null ? "" : result.body;
            int code = result == null ? 0 : result.code;
            boolean ok = result != null && result.error == null && code >= 200 && code < 400
                    && body.length() > 0
                    && !client.isCloudflareChallengeResponse(code, body)
                    && !NtkDeviceIdentityManager.isTrash0607Block(body);
            if(!ok)
                Log.d(TAG, "ntk_true_random_access_verify_rsc_failed path=" + path
                        + ",code=" + code
                        + ",error=" + (result == null ? "null" : result.error)
                        + ",sample=" + shortSample(body));
            return ok;
        } catch(Exception e) {
            Log.d(TAG, "ntk_true_random_access_verify_rsc_error path=" + path + "," + e);
            return false;
        }
    }

    private static boolean isNtkEpisodeDocumentPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.startsWith("/webtoon/") || lower.startsWith("/manhwa/");
    }

    private static boolean isUsableNtkAccessDocument(String body) {
        if(body == null)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        if(lower.length() == 0
                || lower.contains("just a moment")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("/cdn-cgi/challenge-platform")
                || lower.contains("cf-challenge")
                || lower.contains("cf-turnstile")
                || lower.contains("verifying you are human")
                || lower.contains("verify you are human")
                || lower.contains("developer tools blocked")
                || lower.contains("devtools blocked")
                || lower.contains("webpage not available")
                || lower.contains("net::err_")
                || NtkDeviceIdentityManager.isTrash0607Block(body))
            return false;
        return (lower.contains("<html") || lower.contains("<!doctype html"))
                && (lower.contains("/manhwa") || lower.contains("/webtoon")
                || lower.contains("__next") || lower.contains("newtoki"));
    }

    private static String shortSample(String body) {
        if(body == null)
            return "";
        String sample = body.replace('\n', ' ').replace('\r', ' ');
        return sample.length() <= 180 ? sample : sample.substring(0, 180);
    }

    private static boolean hasStrongNtkAccessCookies(String webtoonRoot, String url) {
        String cookies = mergedWebViewCookieString(webtoonRoot, url);
        boolean hasLegacyBase = hasCookieName(cookies, "cf_clearance")
                && hasCookieName(cookies, "nv")
                && hasCookieName(cookies, "ad_guard_l");
        boolean hasCurrentBase = hasCookieName(cookies, "cf_clearance")
                && hasCookieName(cookies, "ad_ack_c");
        boolean hasIdentity = (hasCookieName(cookies, "ntk_fp") && hasCookieName(cookies, "ntk_pid"))
                || (hasCookieName(cookies, "__vsid") && hasCookieName(cookies, "__ntk_ev_id"))
                || hasCookieName(cookies, "newtoki_read");
        return (hasLegacyBase || hasCurrentBase) && hasIdentity;
    }

    private static String summarizeNtkAccessCookieState(String webtoonRoot, String url) {
        String cookies = mergedWebViewCookieString(webtoonRoot, url);
        return "len=" + cookies.length()
                + ",cf=" + hasCookieName(cookies, "cf_clearance")
                + ",nv=" + hasCookieName(cookies, "nv")
                + ",adGuardL=" + hasCookieName(cookies, "ad_guard_l")
                + ",adAck=" + hasCookieName(cookies, "ad_ack_c")
                + ",ntkFp=" + hasCookieName(cookies, "ntk_fp")
                + ",ntkPid=" + hasCookieName(cookies, "ntk_pid")
                + ",vsid=" + hasCookieName(cookies, "__vsid")
                + ",ev=" + hasCookieName(cookies, "__ntk_ev_id")
                + ",read=" + hasCookieName(cookies, "newtoki_read");
    }

    private static String mergedWebViewCookieString(String webtoonRoot, String url) {
        StringBuilder builder = new StringBuilder();
        appendCookieString(builder, webtoonRoot);
        appendCookieString(builder, webtoonRoot + "/manhwa");
        appendCookieString(builder, url);
        return builder.toString();
    }

    private static void appendCookieString(StringBuilder builder, String url) {
        try {
            String value = CookieManager.getInstance().getCookie(url);
            if(value == null || value.length() == 0)
                return;
            if(builder.length() > 0)
                builder.append("; ");
            builder.append(value);
        } catch (Exception ignored) {
        }
    }

    private static boolean hasCookieName(String cookies, String name) {
        if(cookies == null || name == null || name.length() == 0)
            return false;
        String[] parts = cookies.split(";");
        for(String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            int eq = trimmed.indexOf('=');
            String key = eq >= 0 ? trimmed.substring(0, eq) : trimmed;
            if(name.equalsIgnoreCase(key.trim()))
                return true;
        }
        return false;
    }

    private static Title pickRandomTitle(Context context, CustomHttpClient client,
                                         Random random, int baseMode, boolean safeNetwork,
                                         boolean requireLiveRandom,
                                         boolean allowChallengeRecovery) throws Exception {
        Exception apiError = null;
        if(safeNetwork && ntkBlockedWithoutProof(client) && !requireLiveRandom) {
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
                if((!safeNetwork || requireLiveRandom) && allowChallengeRecovery) {
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
                    Log.d(TAG, "ntk_true_random_api_retry_suppressed_safe baseMode=" + baseMode
                            + ",requireLiveRandom=" + requireLiveRandom
                            + ",allowChallengeRecovery=" + allowChallengeRecovery);
                }
                if(ntkBlockedWithoutProof(client)) {
                    Log.d(TAG, "ntk_true_random_access_unverified_before_fallback baseMode=" + baseMode
                            + ",apiType=" + apiError.getClass().getSimpleName()
                            + ",apiMessage=" + apiError.getMessage());
                }
                if(requireLiveRandom)
                    throw new AssertionError("Live-random API title discovery failed; refusing DB/curated fallback"
                            + " baseMode=" + baseMode
                            + " apiType=" + apiError.getClass().getSimpleName()
                            + " apiMessage=" + apiError.getMessage(), apiError);
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
            if(requireLiveRandom)
                throw new AssertionError("Live-random title discovery requires verified NTK access"
                        + " baseMode=" + baseMode, apiError);
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
        if(requireLiveRandom)
            throw new AssertionError("Live-random title discovery failed after API and RSC sources"
                    + " baseMode=" + baseMode
                    + (apiError == null ? "" : " apiType=" + apiError.getClass().getSimpleName()
                    + " apiMessage=" + apiError.getMessage()), apiError);
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

    private static void rememberLiveRandomTitle(ArrayList<Title> pool, Title title) {
        if(pool == null || title == null || title.getPath() == null)
            return;
        for(Title existing : pool) {
            if(existing != null && title.getPath().equals(existing.getPath()))
                return;
        }
        pool.add(title);
        Log.d(TAG, "ntk_true_random_live_pool_add size=" + pool.size()
                + ",id=" + title.getId()
                + ",path=" + title.getPath()
                + ",eps=" + (title.getEps() == null ? 0 : title.getEps().size()));
    }

    private static Title pickRandomTitleFromLiveRunPool(ArrayList<Title> pool, Random random,
                                                        Set<String> usedEpisodePaths) {
        if(pool == null || pool.size() == 0)
            return null;
        ArrayList<Title> candidates = new ArrayList<>();
        for(Title title : pool) {
            if(title == null || title.getEps() == null || title.getEps().size() == 0)
                continue;
            if(hasUnusedNtkEpisode(title, usedEpisodePaths))
                candidates.add(title);
        }
        if(candidates.size() == 0)
            return null;
        Title title = candidates.get(random.nextInt(candidates.size()));
        Log.d(TAG, "ntk_true_random_title_live_pool size=" + pool.size()
                + ",candidates=" + candidates.size()
                + ",id=" + title.getId()
                + ",path=" + title.getPath()
                + ",name=" + title.getName());
        return title;
    }

    private static boolean hasUnusedNtkEpisode(Title title, Set<String> usedEpisodePaths) {
        if(title == null || title.getEps() == null)
            return false;
        for(Manga episode : title.getEps()) {
            if(episode == null)
                continue;
            String path = episode.getNtkEpisodePath();
            if(path != null && path.length() > 0
                    && (usedEpisodePaths == null || !usedEpisodePaths.contains(path)))
                return true;
        }
        return false;
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
            episode.setNtkImageWorkId(curatedImageWorkId(path));
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
        int maxPage = Math.max(1, total <= 0 ? 80 : (int)Math.ceil(total / (double)API_RANDOM_PAGE_SIZE));
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
        CustomHttpClient.PageResponse directPage = null;
        Exception directError = null;
        try {
            directPage = client.runWithFetchMode(CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW,
                    () -> client.mgetNtkDesktopSearchPage(path, PAGE_CACHE_TTL_MS));
            if(!shouldRetryRandomApiWithAlias(client, directPage, null))
                return directPage;
        } catch(Exception e) {
            directError = e;
        }
        try {
            CustomHttpClient.PageResponse aliasPage = client.runWithSitePreset(
                    NTK_ALIAS_COMIC_URL, NTK_ALIAS_WEBTOON_URL,
                    () -> client.runWithFetchMode(CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW,
                            () -> client.mgetNtkDesktopSearchPage(path, PAGE_CACHE_TTL_MS)));
            Log.d(TAG, "ntk_true_random_api_alias path=" + path
                    + ",directCode=" + (directPage == null ? 0 : directPage.code)
                    + ",aliasCode=" + (aliasPage == null ? 0 : aliasPage.code)
                    + ",aliasBodyLen=" + (aliasPage == null || aliasPage.body == null ? 0 : aliasPage.body.length()));
            if(aliasPage != null && aliasPage.code >= 200 && aliasPage.code < 400
                    && !client.isCloudflareChallengeResponse(aliasPage.code, aliasPage.body)) {
                client.applyResolvedNtkRootFromSearch(NTK_ALIAS_WEBTOON_URL);
                return aliasPage;
            }
            if(directPage != null)
                return directPage;
        } catch(Exception aliasError) {
            Log.d(TAG, "ntk_true_random_api_alias_error path=" + path
                    + ",type=" + aliasError.getClass().getSimpleName()
                    + ",message=" + aliasError.getMessage());
            if(directError != null)
                throw directError;
            throw aliasError;
        }
        if(directError != null)
            throw directError;
        throw new Exception("Unable to fetch random NTK API path=" + path);
    }

    private static boolean shouldRetryRandomApiWithAlias(CustomHttpClient client,
                                                         CustomHttpClient.PageResponse page,
                                                         Exception error) {
        if(client == null)
            return false;
        if(error != null)
            return isCloudflareFailure(error);
        if(page == null)
            return true;
        return page.code == 0 || page.code == 403
                || client.isCloudflareChallengeResponse(page.code, page.body);
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

    private static boolean isNtkApiChallengeUrl(String url) {
        String path = normalizeTargetPath(url);
        return path.startsWith("/api/");
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
        return api + "?page=" + page + "&pageSize=" + API_RANDOM_PAGE_SIZE + "&withTotal=1";
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

    private static Manga pickRandomEpisode(List<Manga> episodes, Random random, Set<String> usedEpisodePaths) {
        ArrayList<Manga> candidates = new ArrayList<>();
        ArrayList<Manga> numericCandidates = new ArrayList<>();
        ArrayList<Manga> positiveImageCandidates = new ArrayList<>();
        ArrayList<Manga> positiveNumericCandidates = new ArrayList<>();
        ArrayList<Manga> freshPositiveNumericCandidates = new ArrayList<>();
        ArrayList<Manga> freshNumericCandidates = new ArrayList<>();
        ArrayList<Manga> freshPositiveImageCandidates = new ArrayList<>();
        ArrayList<Manga> freshCandidates = new ArrayList<>();
        for(Manga episode : episodes) {
            if(episode != null && episode.getNtkEpisodePath().length() > 0) {
                candidates.add(episode);
                boolean numericPath = isNumericNtkEpisodePath(episode.getNtkEpisodePath());
                boolean freshPath = usedEpisodePaths == null
                        || !usedEpisodePaths.contains(episode.getNtkEpisodePath());
                if(freshPath)
                    freshCandidates.add(episode);
                if(numericPath)
                    numericCandidates.add(episode);
                if(episode.getNtkImageCount() > 0) {
                    positiveImageCandidates.add(episode);
                    if(freshPath)
                        freshPositiveImageCandidates.add(episode);
                    if(numericPath)
                        positiveNumericCandidates.add(episode);
                    if(numericPath && freshPath)
                        freshPositiveNumericCandidates.add(episode);
                }
                if(numericPath && freshPath)
                    freshNumericCandidates.add(episode);
            }
        }
        assertTrue("Expected at least one episode with NTK path", candidates.size() > 0);
        if(freshPositiveNumericCandidates.size() > 0) {
            Log.d(TAG, "ntk_true_random_episode_candidates total=" + candidates.size()
                    + ",fresh=" + freshCandidates.size()
                    + ",numeric=" + numericCandidates.size()
                    + ",positiveImages=" + positiveImageCandidates.size()
                    + ",positiveNumeric=" + positiveNumericCandidates.size()
                    + ",freshPositiveNumeric=" + freshPositiveNumericCandidates.size());
            return freshPositiveNumericCandidates.get(random.nextInt(freshPositiveNumericCandidates.size()));
        }
        if(freshNumericCandidates.size() > 0) {
            Log.d(TAG, "ntk_true_random_episode_candidates total=" + candidates.size()
                    + ",fresh=" + freshCandidates.size()
                    + ",numeric=" + numericCandidates.size()
                    + ",freshNumeric=" + freshNumericCandidates.size()
                    + ",positiveImages=" + positiveImageCandidates.size());
            return freshNumericCandidates.get(random.nextInt(freshNumericCandidates.size()));
        }
        if(freshPositiveImageCandidates.size() > 0) {
            Log.d(TAG, "ntk_true_random_episode_candidates total=" + candidates.size()
                    + ",fresh=" + freshCandidates.size()
                    + ",positiveImages=" + positiveImageCandidates.size()
                    + ",freshPositiveImages=" + freshPositiveImageCandidates.size());
            return freshPositiveImageCandidates.get(random.nextInt(freshPositiveImageCandidates.size()));
        }
        if(freshCandidates.size() > 0)
            return freshCandidates.get(random.nextInt(freshCandidates.size()));
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

    private static int baseModeForRun(boolean cycleBaseModes, Random random, int baseModeOffset, int run) {
        if(cycleBaseModes)
            return ((run + baseModeOffset) % 2) == 0 ? MTitle.base_comic : MTitle.base_webtoon;
        return random.nextBoolean() ? MTitle.base_comic : MTitle.base_webtoon;
    }

    private static boolean isNumericNtkEpisodePath(String path) {
        return path != null && path.matches("^/(?:manhwa|webtoon)/\\d+/\\d+$");
    }

    private static boolean shouldSkipEpisodeBeforeMeasurement(Manga episode, String mode) {
        String path = episode == null ? "" : episode.getNtkEpisodePath();
        if(path != null && path.matches("^/(?:webtoon|manhwa)/[^/]+/nv-[^/?#]+.*")) {
            Log.d(TAG, "ntk_true_random_generated_probe path=" + path
                    + ",url=metadata"
                    + ",status=0"
                    + ",skip=true"
                    + ",reason=synthetic_nv_episode");
            return true;
        }
        if(path != null && path.startsWith("/webtoon/") && episode.getNtkImageCount() <= 0) {
            Log.d(TAG, "ntk_true_random_generated_probe path=" + path
                    + ",url=metadata"
                    + ",status=0"
                    + ",skip=true"
                    + ",reason=webtoon_image_count_zero");
            return true;
        }
        if(!"generated".equals(mode) || path == null || !path.startsWith("/webtoon/"))
            return false;
        String probeUrl = firstGeneratedImageProbeUrl(episode);
        if(probeUrl.length() == 0)
            return false;
        int status = generatedImageProbeStatus(probeUrl, 2_500);
        boolean skip = status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_GONE;
        Log.d(TAG, "ntk_true_random_generated_probe path=" + episode.getNtkEpisodePath()
                + ",url=" + probeUrl
                + ",status=" + status
                + ",skip=" + skip);
        return skip;
    }

    private static String firstGeneratedImageProbeUrl(Manga episode) {
        if(episode == null)
            return "";
        String path = episode.getNtkEpisodePath();
        if(path == null)
            return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^/(webtoon|manhwa)/([^/?#]+)/([^/?#]+)$")
                .matcher(path);
        if(!matcher.find())
            return "";
        String type = matcher.group(1);
        String pathWorkId = matcher.group(2);
        String pathEpisodeId = matcher.group(3);
        String workId = positiveIdOrFallback(episode.getNtkImageWorkId(),
                pathWorkId.matches("\\d+") ? pathWorkId : Integer.toString(episode.getTitleId()));
        String episodeId = positiveIdOrFallback(episode.getNtkImageEpisodeId(), pathEpisodeId);
        if(workId.length() == 0 || episodeId.length() == 0)
            return "";
        if("webtoon".equals(type))
            return "http://fifa.worldcup73.xyz/black/episodes/" + workId + "/" + episodeId + "/p001.jpeg";
        return "http://apihost93.com/episodes/" + workId + "/" + episodeId + "/p001.jpg";
    }

    private static String positiveIdOrFallback(String value, String fallback) {
        String candidate = value == null ? "" : value.trim();
        if(candidate.matches("\\d+") && Long.parseLong(candidate) > 0L)
            return candidate;
        String fallbackValue = fallback == null ? "" : fallback.trim();
        return fallbackValue.matches("\\d+") && Long.parseLong(fallbackValue) > 0L ? fallbackValue : "";
    }

    private static int generatedImageProbeStatus(String url, int timeoutMs) {
        int status = generatedImageProbeStatus(url, "HEAD", timeoutMs);
        if(status == HttpURLConnection.HTTP_BAD_METHOD || status <= 0)
            status = generatedImageProbeStatus(url, "GET", timeoutMs);
        return status;
    }

    private static int generatedImageProbeStatus(String url, String method, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", MainApplication.getHttpClient().agent);
            if("GET".equals(method))
                connection.setRequestProperty("Range", "bytes=0-0");
            return connection.getResponseCode();
        } catch(Exception e) {
            Log.d(TAG, "ntk_true_random_generated_probe_error url=" + url
                    + ",method=" + method
                    + ",error=" + e);
            return 0;
        } finally {
            if(connection != null)
                connection.disconnect();
        }
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
                                                   String targetImageEpisodeId,
                                                   String targetImageWorkId,
                                                   int targetImageCount,
                                                   String directNextEpisodePath,
                                                   String directNextImageEpisodeId,
                                                   String directNextImageWorkId,
                                                   int directNextImageCount) {
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
            TargetEpisode resolved = tryLoadTargetEpisodeMetadata(context, client, title,
                    normalizedEpisodePath, resolvedTitlePath, baseMode, targetImageEpisodeId,
                    targetImageWorkId, targetImageCount);
            if(resolved != null) {
                ensureDirectNextEpisodeIfRequested(resolved.title, resolved.episode,
                        directNextEpisodePath, directNextImageEpisodeId,
                        directNextImageWorkId, directNextImageCount, baseMode);
                return resolved;
            }
            Manga episode = new Manga(parseEpisodeIdFromPath(normalizedEpisodePath), "ntk-direct-target", "", baseMode);
            String resolvedImageWorkId = targetImageWorkId;
            if(resolvedImageWorkId == null || resolvedImageWorkId.trim().length() == 0)
                resolvedImageWorkId = curatedImageWorkId(normalizedEpisodePath);
            episode.setTitle(title);
            episode.setTitleId(titleId);
            episode.setNtkEpisodePath(normalizedEpisodePath);
            episode.setNtkImageEpisodeId(targetImageEpisodeId);
            episode.setNtkImageWorkId(resolvedImageWorkId);
            episode.setNtkImageCount(targetImageCount);
            ArrayList<Manga> episodes = new ArrayList<>();
            String normalizedNextPath = normalizeTargetPath(directNextEpisodePath);
            if(normalizedNextPath.length() > 0) {
                Manga next = new Manga(parseEpisodeIdFromPath(normalizedNextPath), "ntk-direct-next", "", baseMode);
                String resolvedNextWorkId = directNextImageWorkId;
                if(resolvedNextWorkId == null || resolvedNextWorkId.trim().length() == 0)
                    resolvedNextWorkId = curatedImageWorkId(normalizedNextPath);
                next.setTitle(title);
                next.setTitleId(titleId);
                next.setNtkEpisodePath(normalizedNextPath);
                next.setNtkImageEpisodeId(directNextImageEpisodeId);
                next.setNtkImageWorkId(resolvedNextWorkId);
                next.setNtkImageCount(directNextImageCount);
                episodes.add(next);
            }
            episodes.add(episode);
            title.setEps(episodes);
            for(Manga item : episodes) {
                item.setTitle(title);
                item.setTitleId(titleId);
                item.setEps(title.getEps());
            }
            Log.d(TAG, "ntk_true_random_direct_target path=" + normalizedEpisodePath
                    + ",titlePath=" + resolvedTitlePath
                    + ",titleId=" + titleId
                    + ",baseMode=" + baseMode
                    + ",imageEpisodeId=" + episode.getNtkImageEpisodeId()
                    + ",imageWorkId=" + resolvedImageWorkId
                    + ",imageCount=" + episode.getNtkImageCount()
                    + ",directNextPath=" + normalizedNextPath);
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

    private static void ensureDirectNextEpisodeIfRequested(Title title, Manga episode,
                                                           String directNextEpisodePath,
                                                           String directNextImageEpisodeId,
                                                           String directNextImageWorkId,
                                                           int directNextImageCount,
                                                           int baseMode) {
        String normalizedNextPath = normalizeTargetPath(directNextEpisodePath);
        if(title == null || episode == null || normalizedNextPath.length() == 0)
            return;
        ArrayList<Manga> episodes = title.getEps() instanceof ArrayList
                ? (ArrayList<Manga>) title.getEps()
                : new ArrayList<>(title.getEps() == null
                ? new ArrayList<>() : title.getEps());
        for(Manga item : episodes) {
            if(item != null && normalizedNextPath.equals(item.getNtkEpisodePath())) {
                item.setTitle(title);
                item.setTitleId(title.getId());
                item.setEps(episodes);
                return;
            }
        }
        Manga next = new Manga(parseEpisodeIdFromPath(normalizedNextPath),
                "ntk-direct-next", "", baseMode);
        String resolvedNextWorkId = directNextImageWorkId;
        if(resolvedNextWorkId == null || resolvedNextWorkId.trim().length() == 0)
            resolvedNextWorkId = curatedImageWorkId(normalizedNextPath);
        next.setTitle(title);
        next.setTitleId(title.getId());
        next.setNtkEpisodePath(normalizedNextPath);
        next.setNtkImageEpisodeId(directNextImageEpisodeId);
        next.setNtkImageWorkId(resolvedNextWorkId);
        next.setNtkImageCount(directNextImageCount);
        int currentIndex = episodes.indexOf(episode);
        if(currentIndex < 0) {
            episodes.add(episode);
            currentIndex = episodes.size() - 1;
        }
        episodes.add(Math.max(0, currentIndex), next);
        title.setEps(episodes);
        for(Manga item : episodes) {
            if(item == null)
                continue;
            item.setTitle(title);
            item.setTitleId(title.getId());
            item.setEps(episodes);
        }
    }

    private static TargetEpisode tryLoadTargetEpisodeMetadata(Context context, CustomHttpClient client,
                                                              Title title, String episodePath,
                                                              String titlePath, int baseMode,
                                                              String targetImageEpisodeId,
                                                              String targetImageWorkId,
                                                              int targetImageCount) {
        if(client == null || title == null || episodePath == null || episodePath.length() == 0)
            return null;
        long startedAt = SystemClock.elapsedRealtime();
        int result = title.fetchEps(client);
        if(result == Title.LOAD_CAPTCHA || result == Title.LOAD_ERROR && client.hasRecentCloudflareChallenge()) {
            Log.d(TAG, "ntk_true_random_direct_target_metadata_captcha_skip path=" + episodePath
                    + ",titlePath=" + titlePath
                    + ",result=" + result
                    + ",recentChallenge=" + (client != null && client.hasRecentCloudflareChallenge())
                    + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
            return null;
        }
        if(result != Title.LOAD_OK || title.getEps() == null || title.getEps().size() == 0) {
            Log.d(TAG, "ntk_true_random_direct_target_metadata_miss path=" + episodePath
                    + ",titlePath=" + titlePath
                    + ",result=" + result
                    + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
            return null;
        }
        for(Manga episode : title.getEps()) {
            if(episode == null || !episodePath.equals(episode.getNtkEpisodePath()))
                continue;
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            String requestedImageEpisodeId = targetImageEpisodeId == null ? "" : targetImageEpisodeId.trim();
            String parsedImageEpisodeId = episode.getNtkImageEpisodeId() == null
                    ? "" : episode.getNtkImageEpisodeId().trim();
            if(requestedImageEpisodeId.length() > 0) {
                if(isNtkNumericId(parsedImageEpisodeId) && !isNtkNumericId(requestedImageEpisodeId)) {
                    Log.d(TAG, "ntk_true_random_direct_target_metadata_preserve_numeric_image_episode"
                            + " path=" + episodePath
                            + ",parsed=" + parsedImageEpisodeId
                            + ",requested=" + requestedImageEpisodeId);
                } else {
                    episode.setNtkImageEpisodeId(requestedImageEpisodeId);
                }
            }
            if(targetImageWorkId != null && targetImageWorkId.trim().length() > 0)
                episode.setNtkImageWorkId(targetImageWorkId.trim());
            if(targetImageCount > 0)
                episode.setNtkImageCount(targetImageCount);
            Log.d(TAG, "ntk_true_random_direct_target_metadata_hit path=" + episodePath
                    + ",titlePath=" + titlePath
                    + ",episodes=" + title.getEps().size()
                    + ",name=" + episode.getName()
                    + ",imageEpisodeId=" + episode.getNtkImageEpisodeId()
                    + ",imageWorkId=" + episode.getNtkImageWorkId()
                    + ",imageCount=" + episode.getNtkImageCount()
                    + ",hint=" + episode.hasNtkViewerPayloadHint()
                    + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
            return new TargetEpisode(title, episode);
        }
        Log.d(TAG, "ntk_true_random_direct_target_metadata_not_found path=" + episodePath
                + ",titlePath=" + titlePath
                + ",episodes=" + title.getEps().size()
                + ",sample=" + episodeSample(title.getEps())
                + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
        return null;
    }

    private static String curatedImageWorkId(String path) {
        String normalized = normalizeTargetPath(path);
        if("/webtoon/65384754/1496998".equals(normalized))
            return "17330";
        if("/webtoon/784248/1252104".equals(normalized))
            return "10662";
        String titlePath = titlePathFromEpisodePath(normalized, baseModeForTargetPath(normalized));
        int titleId = titleIdFromPath(titlePath);
        return titleId > 0 ? String.valueOf(titleId) : "";
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
            {"/webtoon/17332/1515337", "/webtoon/17332/1463501"},
            {"/webtoon/3884/185994"},
            {"/webtoon/3774/176692"},
            {"/webtoon/17591/1479761"},
            {"/webtoon/2811/1076716"},
            {"/webtoon/12703/1433404"},
            {"/webtoon/13729/1388127"},
            {"/webtoon/16968/1430500"},
            {"/webtoon/65384754/1496998"},
            {"/webtoon/784248/1252104"}
    };
    private static final String[][] CURATED_NTK_MANHWA_EPISODES = new String[][]{
            {"/manhwa/25694/1767091", "/manhwa/25694/1767431", "/manhwa/25694/1767898", "/manhwa/25694/1768331"},
            {"/manhwa/8209/63505"},
            {"/manhwa/34376/1734715"},
            {"/manhwa/35655/1778269"},
            {"/manhwa/36525/1807424"},
            {"/manhwa/26992/329972"},
            {"/manhwa/34074/1709547"}
    };

    private static void runReaderCase(Context context, UiDevice device, int run, String mode,
                                      Title title, Manga episode, int scrollSteps,
                                      boolean appendProbe, boolean probePreviousAppend,
                                      int appendSteps, int screenshotEvery,
                                      long postStopDriftMs, long firstDrawableMaxMs,
                                      int initialContinuousPages, long initialContinuousMaxMs,
                                      boolean assertNoJank, int maxMissedFrames,
                                      int maxDroppedFrames, int swipeInputSteps,
                                      boolean assertNoSchedulerGap, float renderFrameMaxMs,
                                      long holdAfterFirstDrawableMs, boolean requireStrictAck,
                                      boolean requireAllPagesDrawable, long allPagesDrawableMaxMs,
                                      String scrollInputMode, String scrollPattern,
                                      boolean immediateScrollBeforeReady, boolean strictRealUx,
                                      boolean launchPreflight) {
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
            boolean launchPreflightStarted = launchPreflight &&
                    Utils.startNtkViewerLaunchPreflight(episode, title);
            Log.d(TAG, "ntk_true_random_case_start run=" + run
                    + ",mode=" + mode
                    + ",baseMode=" + title.getBaseMode()
                    + ",titleId=" + title.getId()
                    + ",episodeId=" + episode.getId()
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",imageEpisodeId=" + episode.getNtkImageEpisodeId()
                    + ",imageWorkId=" + episode.getNtkImageWorkId()
                    + ",imageCount=" + episode.getNtkImageCount()
                    + ",title=" + title.getName()
                    + ",episode=" + episode.getName()
                    + ",scrollInputMode=" + scrollInputMode
                    + ",scrollPattern=" + scrollPattern
                    + ",launchPreflightStarted=" + launchPreflightStarted
                    + ",hasNext=" + (nextEpisode != null)
                    + ",hasPrevious=" + (previousEpisode != null));
            activity = startReaderActivityWithoutIdle(context,
                    viewerIntent(context, title, episode, launchPreflightStarted), 12_000L);
            ReaderV2Activity reader = activity instanceof ReaderV2Activity ? (ReaderV2Activity) activity : null;
            AtomicReference<Throwable> immediateScrollError = new AtomicReference<>();
            Thread immediateScrollThread = immediateScrollBeforeReady
                    ? startImmediateScrollBeforeReady(device, reader, run, mode, episode,
                    swipeInputSteps, scrollInputMode, scrollPattern, immediateScrollError)
                    : null;
            long firstDrawableWaitMs = firstDrawableMaxMs > 0L
                    ? Math.max(1500L, firstDrawableMaxMs + 1000L)
                    : 16000L;
            boolean ready = waitForDrawableReady(activity, device, firstDrawableWaitMs);
            if(immediateScrollThread != null) {
                try {
                    immediateScrollThread.join(4_000L);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    immediateScrollError.compareAndSet(null, e);
                }
                assertTrue("Immediate pre-ready scroll failed run=" + run
                                + " mode=" + mode
                                + " path=" + episode.getNtkEpisodePath()
                                + " error=" + immediateScrollError.get(),
                        immediateScrollError.get() == null);
            }
            long observedFirstMs = SystemClock.elapsedRealtime() - startedAt;
            long appFirstMs = readFirstDrawableElapsedMs(activity);
            boolean firstDrawableReady = ready || appFirstMs >= 0L;
            long firstMs = appFirstMs >= 0L ? appFirstMs : observedFirstMs;
            Log.d(TAG, "ntk_true_random_first_drawable run=" + run
                    + ",mode=" + mode
                    + ",ready=" + firstDrawableReady
                    + ",markerReady=" + ready
                    + ",ms=" + firstMs
                    + ",observedMs=" + observedFirstMs
                    + ",appMs=" + appFirstMs
                    + ",maxMs=" + firstDrawableMaxMs
                    + ",path=" + episode.getNtkEpisodePath());
            assertTrue("Expected first drawable run=" + run
                    + " mode=" + mode
                    + " path=" + episode.getNtkEpisodePath()
                    + " elapsedMs=" + firstMs, firstDrawableReady);
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
                int pageCountBeforeContinuous = reader == null ? -1 : readPageCount(reader);
                int requiredContinuousPages = pageCountBeforeContinuous > 0
                        ? Math.min(initialContinuousPages, pageCountBeforeContinuous)
                        : initialContinuousPages;
                boolean continuousReady = waitForInitialContinuousDrawable(
                        reader, continuousWaitMs, requiredContinuousPages);
                long continuousMs = readInitialContinuousDrawableElapsedMs(
                        reader, requiredContinuousPages);
                int pageCount = reader == null ? -1 : readPageCount(reader);
                Log.d(TAG, "ntk_true_random_initial_continuous run=" + run
                        + ",mode=" + mode
                        + ",ready=" + continuousReady
                        + ",pages=" + requiredContinuousPages
                        + ",configuredPages=" + initialContinuousPages
                        + ",pageCount=" + pageCount
                        + ",ms=" + continuousMs
                        + ",maxMs=" + initialContinuousMaxMs
                        + ",path=" + episode.getNtkEpisodePath());
                assertTrue("Expected initial continuous drawable run=" + run
                                + " mode=" + mode
                                + " path=" + episode.getNtkEpisodePath()
                                + " pages=" + requiredContinuousPages
                                + " configuredPages=" + initialContinuousPages
                                + " elapsedMs=" + continuousMs
                                + " pageCount=" + pageCount,
                        continuousReady);
                assertTrue("Expected initial continuous drawable within budget run=" + run
                                + " mode=" + mode
                                + " path=" + episode.getNtkEpisodePath()
                                + " pages=" + requiredContinuousPages
                                + " configuredPages=" + initialContinuousPages
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
            int effectiveScrollSteps = strictRealUx
                    ? strictScrollStepsForEpisode(episode, scrollSteps)
                    : scrollSteps;
            probeScrollContinuity(context, device, reader, run, mode, episode,
                    effectiveScrollSteps, screenshotEvery, postStopDriftMs, assertNoJank,
                    maxMissedFrames, maxDroppedFrames, swipeInputSteps,
                    assertNoSchedulerGap, renderFrameMaxMs, scrollInputMode,
                    scrollPattern);
            if(requireAllPagesDrawable)
                assertAllPagesDrawableNow(run, mode, episode, reader, initialPageCount);
            if(requireStrictAck)
                waitForStrictNtkAckProofBeforeClose(run, mode, episode, 70_000L);
            if(appendProbe && reader != null)
                probeNextAppend(device, reader, run, mode, episode, nextEpisode,
                        initialPageCount, appendSteps);
            if(probePreviousAppend && reader != null && previousEpisode != null)
                assertTrue("Expected previous append run=" + run
                                + " mode=" + mode
                                + " current=" + episode.getNtkEpisodePath()
                                + " previous=" + previousEpisode.getNtkEpisodePath(),
                        probePreviousAppend(device, reader, run, mode,
                                episode, previousEpisode, appendSteps));
        } finally {
            Manga.clearNtkViewerFetchModeOverrideForTest();
            finishActivityForNextLaunch(activity);
            device.wait(Until.gone(By.desc("reader-drawable-ready")), 3000L);
            device.waitForIdle(1500L);
        }
    }

    private static void waitForStrictNtkAckProofBeforeClose(int run, String mode, Manga episode,
                                                            long timeoutMs) {
        String path = episode == null ? "" : episode.getNtkEpisodePath();
        long startedAt = SystemClock.elapsedRealtime();
        boolean proof = false;
        boolean joined = false;
        try {
            CustomHttpClient client = MainApplication.getHttpClient();
            long deadline = startedAt + Math.max(0L, timeoutMs);
            while(SystemClock.elapsedRealtime() < deadline) {
                proof = client.hasRecentStrictNtkAdAckProof(path);
                if(proof)
                    break;
                long remaining = deadline - SystemClock.elapsedRealtime();
                long joinMs = Math.min(2500L, Math.max(100L, remaining));
                joined = client.waitForNtkWebViewAckPreflightProof(path, joinMs) || joined;
                proof = client.hasRecentStrictNtkAdAckProof(path);
                if(proof)
                    break;
                SystemClock.sleep(300L);
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_true_random_ack_wait_error run=" + run
                    + ",mode=" + mode
                    + ",path=" + path
                    + ",error=" + e);
        }
        long ms = SystemClock.elapsedRealtime() - startedAt;
        Log.d(TAG, "ntk_true_random_ack_wait run=" + run
                + ",mode=" + mode
                + ",path=" + path
                + ",joined=" + joined
                + ",strictProof=" + proof
                + ",ms=" + ms
                + ",maxMs=" + timeoutMs);
        assertTrue("Expected strict NTK ACK proof before closing reader run=" + run
                        + " mode=" + mode
                        + " path=" + path
                        + " elapsedMs=" + ms
                        + " maxMs=" + timeoutMs,
                proof);
    }

    private static void waitForAllPagesDrawableBeforeClose(int run, String mode, Manga episode,
                                                           ReaderV2Activity reader, long timeoutMs) {
        String path = episode == null ? "" : episode.getNtkEpisodePath();
        long startedAt = SystemClock.elapsedRealtime();
        ReaderSurfaceView.PageReadinessSnapshot snapshot = null;
        ReaderSurfaceView.VisibleCoverageSnapshot coverage = null;
        long deadline = startedAt + Math.max(0L, timeoutMs);
        long readySince = -1L;
        long lastPageCountChangedAt = startedAt;
        int lastPageCount = -1;
        int expectedMetadataPages = Math.max(0, episode == null ? 0 : episode.getNtkImageCount());
        boolean stableReady = false;
        while(SystemClock.elapsedRealtime() < deadline) {
            snapshot = readPageReadiness(reader);
            coverage = readVisibleCoverage(reader);
            int pageCount = snapshot == null ? -1 : snapshot.getPageCount();
            if(pageCount != lastPageCount) {
                lastPageCount = pageCount;
                lastPageCountChangedAt = SystemClock.elapsedRealtime();
                readySince = -1L;
            }
            if(isAllPagesDrawable(snapshot)) {
                long now = SystemClock.elapsedRealtime();
                if(readySince < 0L)
                    readySince = now;
                boolean metadataStillExpanding = expectedMetadataPages > pageCount;
                long minimumReadyMs = metadataStillExpanding ? 18_000L : 5_000L;
                boolean stable = now - lastPageCountChangedAt >= minimumReadyMs
                        && now - readySince >= minimumReadyMs;
                if(stable) {
                    stableReady = true;
                    break;
                }
            } else {
                readySince = -1L;
            }
            if(reader != null)
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> {
                            reader.testRequestAllPagesWarm();
                            reader.testVisibleCoverageSnapshot();
                        });
            SystemClock.sleep(250L);
        }
        if(!stableReady) {
            snapshot = readPageReadiness(reader);
            coverage = readVisibleCoverage(reader);
        }
        long ms = SystemClock.elapsedRealtime() - startedAt;
        int expectedInstalledPages = expectedInstalledPageCountForNtkEpisode(episode);
        int actualPageCount = snapshot == null ? -1 : snapshot.getPageCount();
        expectedInstalledPages = reconcileExpectedInstalledPageCount(
                episode, expectedInstalledPages, actualPageCount, snapshot);
        Log.d(TAG, "ntk_true_random_all_pages_drawable run=" + run
                + ",mode=" + mode
                + ",path=" + path
                + ",ready=" + isAllPagesDrawable(snapshot)
                + ",expectedGeneratedPages=" + expectedGeneratedPageCountForNtkEpisode(episode)
                + ",expectedInstalledPages=" + expectedInstalledPages
                + ",ms=" + ms
                + ",maxMs=" + timeoutMs
                + ",snapshot=" + formatPageReadiness(snapshot)
                + ",coverage=" + formatCoverage(coverage));
        assertTrue("Expected NTK reader to install all known episode pages before all-pages drawable pass run=" + run
                        + " mode=" + mode
                        + " path=" + path
                        + " expectedInstalledPages=" + expectedInstalledPages
                        + " actualPageCount=" + actualPageCount
                        + " snapshot=" + formatPageReadiness(snapshot),
                expectedInstalledPages <= 0 || actualPageCount >= expectedInstalledPages);
        assertTrue("Expected all NTK pages drawable before closing reader run=" + run
                        + " mode=" + mode
                        + " path=" + path
                        + " elapsedMs=" + ms
                        + " maxMs=" + timeoutMs
                        + " expectedGeneratedPages=" + expectedGeneratedPageCountForNtkEpisode(episode)
                        + " snapshot=" + formatPageReadiness(snapshot)
                        + " coverage=" + formatCoverage(coverage),
                isAllPagesDrawable(snapshot));
    }

    private static ReaderSurfaceView.PageReadinessSnapshot readPageReadiness(ReaderV2Activity activity) {
        final ReaderSurfaceView.PageReadinessSnapshot[] value =
                new ReaderSurfaceView.PageReadinessSnapshot[]{null};
        if(activity == null)
            return null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> value[0] = activity.testSessionPageReadinessSnapshot());
        return value[0];
    }

    private static void assertAllPagesDrawableNow(int run, String mode, Manga episode,
                                                  ReaderV2Activity reader,
                                                  int initialPageCount) {
        ReaderSurfaceView.PageReadinessSnapshot snapshot = readPageReadiness(reader);
        ReaderSurfaceView.VisibleCoverageSnapshot coverage = readVisibleCoverage(reader);
        String path = episode == null ? "" : episode.getNtkEpisodePath();
        int expectedInstalledPages = expectedInstalledPageCountForNtkEpisode(episode);
        int actualPageCount = snapshot == null ? -1 : snapshot.getPageCount();
        expectedInstalledPages = reconcileExpectedInstalledPageCount(
                episode, expectedInstalledPages, actualPageCount, snapshot);
        int immediateExpectedPages = Math.min(
                expectedInstalledPages,
                Math.max(1, expectedGeneratedPageCountForNtkEpisode(episode)));
        int immediateReadyUpperBound = Math.max(1, expectedGeneratedPageCountForNtkEpisode(episode));
        boolean visibleUxReady = isCurrentVisibleUxReady(snapshot, coverage)
                && hasNoUnresolvedPageBefore(snapshot, immediateReadyUpperBound);
        Log.d(TAG, "ntk_true_random_all_pages_drawable_now run=" + run
                + ",mode=" + mode
                + ",path=" + path
                + ",ready=" + visibleUxReady
                + ",expectedInstalledPages=" + expectedInstalledPages
                + ",immediateExpectedPages=" + immediateExpectedPages
                + ",immediateReadyUpperBound=" + immediateReadyUpperBound
                + ",snapshot=" + formatPageReadiness(snapshot)
                + ",coverage=" + formatCoverage(coverage));
        assertTrue("Expected NTK reader to install all known episode pages immediately after high-intensity scroll run=" + run
                        + " mode=" + mode
                        + " path=" + path
                        + " expectedInstalledPages=" + expectedInstalledPages
                        + " immediateExpectedPages=" + immediateExpectedPages
                        + " actualPageCount=" + actualPageCount
                        + " snapshot=" + formatPageReadiness(snapshot),
                immediateExpectedPages <= 0 || actualPageCount >= immediateExpectedPages);
        assertTrue("Expected visible NTK viewport drawable immediately after high-intensity scroll run=" + run
                        + " mode=" + mode
                        + " path=" + path
                        + " expectedGeneratedPages=" + expectedGeneratedPageCountForNtkEpisode(episode)
                        + " immediateReadyUpperBound=" + immediateReadyUpperBound
                        + " snapshot=" + formatPageReadiness(snapshot)
                        + " coverage=" + formatCoverage(coverage),
                visibleUxReady);
    }

    private static boolean isAllPagesDrawable(ReaderSurfaceView.PageReadinessSnapshot snapshot) {
        return snapshot != null
                && snapshot.getPageCount() > 0
                && snapshot.getErrorPages() == 0
                && snapshot.getUnresolvedPages() == 0
                && snapshot.getDrawablePages() >= snapshot.getPageCount();
    }

    private static boolean isCurrentVisibleUxReady(ReaderSurfaceView.PageReadinessSnapshot snapshot,
                                                   ReaderSurfaceView.VisibleCoverageSnapshot coverage) {
        return snapshot != null
                && snapshot.getPageCount() > 0
                && snapshot.getErrorPages() == 0
                && isVisibleViewportReady(coverage);
    }

    private static boolean isLargeEpisodeVisibleUxReady(ReaderV2Activity reader,
                                                        ReaderSurfaceView.PageReadinessSnapshot snapshot,
                                                        ReaderSurfaceView.VisibleCoverageSnapshot coverage) {
        return snapshot != null
                && snapshot.getPageCount() > LARGE_EPISODE_VISIBLE_UX_PAGE_THRESHOLD
                && snapshot.getErrorPages() == 0
                && isVisibleViewportReady(coverage);
    }

    private static boolean hasNoUnresolvedPageBefore(ReaderSurfaceView.PageReadinessSnapshot snapshot,
                                                     int exclusiveUpperBound) {
        if(snapshot == null)
            return false;
        String unresolved = snapshot.getUnresolvedIndexes();
        if(unresolved == null || unresolved.trim().length() == 0)
            return true;
        String[] parts = unresolved.split("\\|");
        for(String part : parts) {
            try {
                if(Integer.parseInt(part.trim()) < exclusiveUpperBound)
                    return false;
            } catch(NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private static int expectedGeneratedPageCountForNtkEpisode(Manga episode) {
        if(episode == null)
            return 0;
        String path = episode.getNtkEpisodePath();
        if(path == null)
            return 0;
        String lower = path.toLowerCase(Locale.ROOT);
        if(!lower.startsWith("/webtoon/") && !lower.startsWith("/manhwa/"))
            return 0;
        int imageCount = episode.getNtkImageCount();
        if(imageCount <= 3)
            return 0;
        return imageCount;
    }

    private static int expectedInstalledPageCountForNtkEpisode(Manga episode) {
        if(episode == null)
            return 0;
        String path = episode.getNtkEpisodePath();
        if(path == null)
            return 0;
        String lower = path.toLowerCase(Locale.ROOT);
        if(!lower.startsWith("/webtoon/") && !lower.startsWith("/manhwa/"))
            return 0;
        int imageCount = episode.getNtkImageCount();
        if(imageCount <= 3)
            return 0;
        int latestEarlyCount = ReaderImageCache.INSTANCE
                .earlyNtkAppendImageUrls(path, 0L)
                .size();
        if(latestEarlyCount > imageCount)
            return latestEarlyCount;
        if(latestEarlyCount > 3 && latestEarlyCount < imageCount)
            return latestEarlyCount;
        return imageCount;
    }

    private static int reconcileExpectedInstalledPageCount(Manga episode, int expectedInstalledPages,
                                                           int actualPageCount,
                                                           ReaderSurfaceView.PageReadinessSnapshot snapshot) {
        if(expectedInstalledPages > 0
                && actualPageCount > 0
                && actualPageCount < expectedInstalledPages
                && isAllPagesDrawable(snapshot)) {
            return actualPageCount;
        }
        return expectedInstalledPages;
    }

    private static String formatPageReadiness(ReaderSurfaceView.PageReadinessSnapshot snapshot) {
        if(snapshot == null)
            return "null";
        return "pages=" + snapshot.getPageCount()
                + ";drawable=" + snapshot.getDrawablePages()
                + ";loading=" + snapshot.getLoadingPages()
                + ";errors=" + snapshot.getErrorPages()
                + ";cards=" + snapshot.getCardPages()
                + ";unresolved=" + snapshot.getUnresolvedPages()
                + ";loadingIndexes=" + snapshot.getLoadingIndexes()
                + ";unresolvedIndexes=" + snapshot.getUnresolvedIndexes();
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
            activity = startReaderActivityWithoutIdle(context, viewerIntent(context, title, episode), 12_000L);
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
                            episode, previousEpisode, appendSteps));
        } finally {
            Manga.clearNtkViewerFetchModeOverrideForTest();
            finishActivityForNextLaunch(activity);
            device.wait(Until.gone(By.desc("reader-drawable-ready")), 3000L);
            device.waitForIdle(1500L);
        }
    }

    private static void finishActivityForNextLaunch(Activity activity) {
        if(activity == null)
            return;
        long startedAt = SystemClock.elapsedRealtime();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if(activity instanceof ReaderV2Activity)
                ((ReaderV2Activity) activity).testPrepareForNextLaunch();
            if(!activity.isFinishing() && !activity.isDestroyed())
                activity.finish();
        });
        long deadline = SystemClock.elapsedRealtime() + 8000L;
        while(!activity.isDestroyed() && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(25L);
        Log.d(TAG, "ntk_true_random_activity_finish_wait destroyed=" + activity.isDestroyed()
                + ",ms=" + (SystemClock.elapsedRealtime() - startedAt));
    }

    private static Activity startReaderActivityWithoutIdle(Context context, Intent intent, long timeoutMs) {
        AtomicReference<Activity> resumedReader = new AtomicReference<>();
        long startedAt = SystemClock.elapsedRealtime();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> context.startActivity(intent));
        long deadline = SystemClock.elapsedRealtime() + Math.max(1L, timeoutMs);
        while(SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for(Activity candidate : activities) {
                    if(candidate instanceof ReaderV2Activity
                            && !candidate.isFinishing()
                            && !candidate.isDestroyed()) {
                        resumedReader.set(candidate);
                        break;
                    }
                }
            });
            Activity activity = resumedReader.get();
            if(activity != null) {
                Log.d(TAG, "ntk_true_random_activity_launch_resumed ms="
                        + (SystemClock.elapsedRealtime() - startedAt)
                        + ",activity=" + activity);
                return activity;
            }
            SystemClock.sleep(50L);
        }
        throw new RuntimeException("ReaderV2Activity did not reach RESUMED within "
                + timeoutMs + "ms for " + intent);
    }

    private static Intent viewerIntent(Context context, Title title, Manga episode) {
        return viewerIntent(context, title, episode, false);
    }

    private static Intent viewerIntent(Context context, Title title, Manga episode,
                                       boolean launchPreflightStarted) {
        Intent intent = new Intent(context, ReaderV2Activity.class);
        intent.putExtra("online", true);
        intent.putExtra("title", Utils.toViewerTitleJsonForReader(title, episode, true));
        intent.putExtra("manga", Utils.toViewerMangaJson(episode, title, false));
        intent.putExtra(ViewerIntentContract.EXTRA_EXACT_EPISODE, true);
        intent.putExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, true);
        if(launchPreflightStarted)
            intent.putExtra("viewerNtkAckPreflightStarted", true);
        intent.putExtra("viewerLaunchStartedAtMs", SystemClock.elapsedRealtime());
        Utils.startImmediateNtkGeneratedInitialPrimeForLaunch(context, episode);
        intent.putExtra("viewerLaunchSourceSite", "ntk");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
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

    private static int strictScrollStepsForEpisode(Manga episode, int configuredSteps) {
        int expectedPages = Math.max(0, expectedGeneratedPageCountForNtkEpisode(episode));
        if(expectedPages <= 0)
            return Math.max(configuredSteps, STRICT_MIN_FULL_SWEEP_STEPS);
        int pageDrivenSteps = (expectedPages + STRICT_SCROLL_PAGES_PER_STEP - 1)
                / STRICT_SCROLL_PAGES_PER_STEP;
        return Math.max(configuredSteps,
                Math.min(STRICT_MAX_FULL_SWEEP_STEPS,
                        Math.max(STRICT_MIN_FULL_SWEEP_STEPS, pageDrivenSteps)));
    }

    private static Thread startImmediateScrollBeforeReady(UiDevice device, ReaderV2Activity reader,
                                                          int run, String mode, Manga episode,
                                                          int swipeInputSteps, String scrollInputMode,
                                                          String scrollPattern,
                                                          AtomicReference<Throwable> errorRef) {
        Thread thread = new Thread(() -> {
            try {
                for(int step = 0; step < IMMEDIATE_SCROLL_SWIPES; step++) {
                    ScrollGesture gesture = scrollGestureForStep(scrollPattern, step, swipeInputSteps);
                    if(!gesture.isForwardScroll())
                        gesture = new ScrollGesture(0.86f, 0.16f,
                                Math.max(2, Math.min(swipeInputSteps, 4)), "immediate-fast-down");
                    long ms = swipeReader(device, reader, gesture.startYRatio, gesture.endYRatio,
                            Math.max(2, Math.min(gesture.inputSteps, 4)), scrollInputMode);
                    SystemClock.sleep(90L);
                    ReaderSurfaceView.VisibleCoverageSnapshot coverage = readVisibleCoverage(reader);
                    Log.d(TAG, "ntk_true_random_immediate_scroll run=" + run
                            + ",mode=" + mode
                            + ",step=" + step
                            + ",dispatchMs=" + ms
                            + ",path=" + (episode == null ? "" : episode.getNtkEpisodePath())
                            + ",gesture=" + gesture
                            + ",coverage=" + formatCoverage(coverage));
                }
            } catch(Throwable t) {
                errorRef.compareAndSet(null, t);
            }
        }, "ntk-immediate-scroll-" + run);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void probeScrollContinuity(Context context, UiDevice device, ReaderV2Activity reader, int run,
                                              String mode, Manga episode, int steps, int screenshotEvery,
                                              long postStopDriftMs, boolean assertNoJank,
                                              int maxMissedFrames, int maxDroppedFrames,
                                              int swipeInputSteps, boolean assertNoSchedulerGap,
                                              float renderFrameMaxMs, String scrollInputMode,
                                              String scrollPattern) {
        File screenshot = new File(context.getExternalCacheDir(), "ntk-random-scroll-" + run + ".png");
        ProgressSnapshot initialProgress = readProgressSnapshot(reader);
        int expectedGeneratedPages = expectedGeneratedPageCountForNtkEpisode(episode);
        Manga expectedNextEpisode = episode == null ? null : episode.nextEp();
        int maxObservedPageCount = reader == null ? -1 : readPageCount(reader);
        int maxObservedScrollOffset = initialProgress.hasScrollOffset() ? initialProgress.scrollOffset : 0;
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
            ReaderSurfaceView.VisibleCoverageSnapshot coverageAfterIdle = readVisibleCoverage(reader);
            ProgressSnapshot progressAfterQuiet = waitForReaderQuietProgress(reader, SCROLL_QUIET_TIMEOUT_MS);
            long quietAt = SystemClock.elapsedRealtime();
            SystemClock.sleep(SCROLL_SETTLE_CONFIRM_MS);
            ProgressSnapshot progressAfterSettle = readProgressSnapshot(reader);
            ReaderSurfaceView.FrameStatsSnapshot frameStats = waitForFrameStatsSnapshot(reader, 5500L);
            DriftSample driftSample = monitorPostStopDrift(reader, progressAfterSettle, postStopDriftMs);
            ProgressSnapshot progressAfterLateSettle = driftSample.last;
            long screenshotStart = SystemClock.elapsedRealtime();
            boolean captureScreenshot = shouldCaptureScrollScreenshot(step, screenshotEvery);
            boolean captured = captureScreenshot && device.takeScreenshot(screenshot);
            long screenshotAt = SystemClock.elapsedRealtime();
            String stats = captured ? screenshotStats(screenshot)
                    : captureScreenshot ? "screenshot=false" : "screenshot=skipped";
            long statsAt = SystemClock.elapsedRealtime();
            ReaderSurfaceView.VisibleCoverageSnapshot coverage = coverageAfterIdle;
            if(coverage != null)
                maxObservedPageCount = Math.max(maxObservedPageCount, coverage.getPageCount());
            if(progressAfterLateSettle != null && progressAfterLateSettle.hasScrollOffset())
                maxObservedScrollOffset = Math.max(maxObservedScrollOffset, progressAfterLateSettle.scrollOffset);
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
                    + ",coverageAfterIdle=" + formatCoverage(coverageAfterIdle)
                    + ",progressAfterQuiet=" + progressAfterQuiet
                    + ",progressAfterSettle=" + progressAfterSettle
                    + ",progressAfterLateSettle=" + progressAfterLateSettle
                    + ",postStopDriftMs=" + postStopDriftMs
                    + ",postStopDrift=" + driftSample
                    + ",frameStats=" + formatFrameStats(frameStats)
                    + ",coverage=" + formatCoverage(coverage)
                    + "," + stats);
            assertNoOppositeScrollJump("scroll run=" + run
                    + " mode=" + mode
                    + " step=" + step
                    + " phase=swipe"
                    + " path=" + episode.getNtkEpisodePath()
                    + " gesture=" + gesture, progressBefore, progressAfterIdle,
                    gesture.isForwardScroll());
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
                assertNoFrameCoverageArtifacts("scroll run=" + run
                                + " mode=" + mode
                                + " step=" + step
                                + " path=" + episode.getNtkEpisodePath(),
                        frameStats);
            }
            if(assertNoJank && didScrollMove(progressBefore, progressAfterLateSettle)) {
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
        if("touch".equals(scrollInputMode) && ("down".equals(scrollPattern) || "fling".equals(scrollPattern))) {
            int finalPageCount = reader == null ? -1 : readPageCount(reader);
            ProgressSnapshot finalProgress = readProgressSnapshot(reader);
            int firstGeneratedNotFoundPage = firstKnownGeneratedNotFoundPage(episode);
            int effectiveExpectedGeneratedPages = reconcileExpectedGeneratedPageCountForScroll(
                    expectedGeneratedPages, firstGeneratedNotFoundPage);
            if(effectiveExpectedGeneratedPages > 3 && finalProgress != null
                    && finalProgress.hasScrollOffset() && finalProgress.maxScroll > 0
                    && (!isAtReaderEnd(finalProgress) || expectedNextEpisode != null)) {
                finalProgress = strictTouchBurstScrollToEnd(device, reader, run, mode, episode,
                        scrollInputMode, swipeInputSteps, assertNoJank, maxMissedFrames,
                        maxDroppedFrames, assertNoSchedulerGap, renderFrameMaxMs,
                        effectiveExpectedGeneratedPages, expectedNextEpisode);
                if(finalProgress != null && finalProgress.hasScrollOffset())
                    maxObservedScrollOffset = Math.max(maxObservedScrollOffset,
                            finalProgress.scrollOffset);
                maxObservedPageCount = Math.max(maxObservedPageCount,
                        reader == null ? -1 : readPageCount(reader));
                finalPageCount = reader == null ? -1 : readPageCount(reader);
                int refreshedFirstGeneratedNotFoundPage = firstKnownGeneratedNotFoundPage(episode);
                if(refreshedFirstGeneratedNotFoundPage > 0
                        && refreshedFirstGeneratedNotFoundPage != firstGeneratedNotFoundPage) {
                    firstGeneratedNotFoundPage = refreshedFirstGeneratedNotFoundPage;
                    effectiveExpectedGeneratedPages = reconcileExpectedGeneratedPageCountForScroll(
                            expectedGeneratedPages, firstGeneratedNotFoundPage);
                }
            }
            Log.d(TAG, "ntk_true_random_touch_scroll_sweep run=" + run
                    + ",mode=" + mode
                    + ",pattern=" + scrollPattern
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",nextPath=" + (expectedNextEpisode == null ? "" : expectedNextEpisode.getNtkEpisodePath())
                    + ",nextLoaded=" + hasLoadedEpisode(reader, expectedNextEpisode)
                    + ",expectedGeneratedPages=" + expectedGeneratedPages
                    + ",effectiveExpectedGeneratedPages=" + effectiveExpectedGeneratedPages
                    + ",firstGeneratedNotFoundPage=" + firstGeneratedNotFoundPage
                    + ",maxObservedPageCount=" + maxObservedPageCount
                    + ",finalPageCount=" + finalPageCount
                    + ",initialProgress=" + initialProgress
                    + ",finalProgress=" + finalProgress
                    + ",maxObservedScrollOffset=" + maxObservedScrollOffset);
            assertTrue("Expected touch/" + scrollPattern + " sweep to expand NTK generated episode run=" + run
                            + " mode=" + mode
                            + " path=" + episode.getNtkEpisodePath()
                            + " expectedPages=" + expectedGeneratedPages
                            + " effectiveExpectedPages=" + effectiveExpectedGeneratedPages
                            + " firstNotFoundPage=" + firstGeneratedNotFoundPage
                            + " maxObservedPageCount=" + maxObservedPageCount
                            + " finalPageCount=" + finalPageCount
                            + " initialProgress=" + initialProgress
                            + " finalProgress=" + finalProgress,
                    effectiveExpectedGeneratedPages <= 0
                            || maxObservedPageCount >= effectiveExpectedGeneratedPages);
            int requiredBootstrapAdvance = 1500;
            if(finalProgress != null && finalProgress.hasScrollOffset() && finalProgress.maxScroll > 0)
                requiredBootstrapAdvance = Math.min(requiredBootstrapAdvance, finalProgress.maxScroll);
            assertTrue("Expected touch/" + scrollPattern + " sweep to advance beyond bootstrap run=" + run
                            + " mode=" + mode
                            + " path=" + episode.getNtkEpisodePath()
                            + " requiredAdvance=" + requiredBootstrapAdvance
                            + " initialProgress=" + initialProgress
                            + " finalProgress=" + finalProgress
                            + " maxObservedScrollOffset=" + maxObservedScrollOffset,
                    initialProgress == null
                            || !initialProgress.hasScrollOffset()
                            || maxObservedScrollOffset >= initialProgress.scrollOffset + requiredBootstrapAdvance);
            if(effectiveExpectedGeneratedPages > 3 && finalProgress.hasScrollOffset() && finalProgress.maxScroll > 0) {
                int minimumFullSweepOffset = Math.max(0,
                        finalProgress.maxScroll - SCROLL_SETTLE_JUMP_TOLERANCE_PX * 3);
                boolean expandedBeyondExpected =
                        maxObservedPageCount > effectiveExpectedGeneratedPages;
                boolean movedPastExpectedGeneratedEpisode =
                        expandedBeyondExpected
                                && finalProgress.page >= effectiveExpectedGeneratedPages - 1;
                boolean expectedNextLoaded =
                        expectedNextEpisode != null && hasLoadedEpisode(reader, expectedNextEpisode);
                if(expectedNextEpisode != null) {
                    assertTrue("Expected touch/" + scrollPattern
                                    + " sweep to auto-append and enter next episode run=" + run
                                    + " mode=" + mode
                                    + " path=" + episode.getNtkEpisodePath()
                                    + " next=" + expectedNextEpisode.getNtkEpisodePath()
                                    + " expectedPages=" + expectedGeneratedPages
                                    + " effectiveExpectedPages=" + effectiveExpectedGeneratedPages
                                    + " expandedBeyondExpected=" + expandedBeyondExpected
                                    + " movedPastExpectedGeneratedEpisode=" + movedPastExpectedGeneratedEpisode
                                    + " expectedNextLoaded=" + expectedNextLoaded
                                    + " maxObservedPageCount=" + maxObservedPageCount
                                    + " finalPageCount=" + finalPageCount
                                    + " finalProgress=" + finalProgress,
                            movedPastExpectedGeneratedEpisode && expectedNextLoaded);
                    return;
                }
                assertTrue("Expected touch/" + scrollPattern + " sweep to move through generated episode run=" + run
                                + " mode=" + mode
                                + " path=" + episode.getNtkEpisodePath()
                                + " expectedPages=" + expectedGeneratedPages
                                + " effectiveExpectedPages=" + effectiveExpectedGeneratedPages
                                + " minimumFullSweepOffset=" + minimumFullSweepOffset
                                + " expandedBeyondExpected=" + expandedBeyondExpected
                                + " movedPastExpectedGeneratedEpisode=" + movedPastExpectedGeneratedEpisode
                                + " maxObservedScrollOffset=" + maxObservedScrollOffset
                                + " finalProgress=" + finalProgress,
                        maxObservedScrollOffset >= minimumFullSweepOffset
                                || movedPastExpectedGeneratedEpisode);
            }
        }
    }

    private static ProgressSnapshot strictTouchBurstScrollToEnd(UiDevice device,
                                                                ReaderV2Activity reader,
                                                                int run, String mode,
                                                                Manga episode,
                                                                String scrollInputMode,
                                                                int swipeInputSteps,
                                                                boolean assertNoJank,
                                                                int maxMissedFrames,
                                                                 int maxDroppedFrames,
                                                                 boolean assertNoSchedulerGap,
                                                                 float renderFrameMaxMs,
                                                                 int expectedGeneratedPages,
                                                                 Manga expectedNextEpisode) {
        ProgressSnapshot latest = readProgressSnapshot(reader);
        int stagnantBursts = 0;
        for(int burst = 0; burst < STRICT_END_BURST_COUNT; burst++) {
            if(isAtReaderEnd(latest) && hasReachedExpectedGeneratedEpisode(reader, latest,
                    expectedGeneratedPages, expectedNextEpisode))
                return latest;
            ProgressSnapshot before = latest;
            resetFrameStatsSnapshot(reader);
            long startedAt = SystemClock.elapsedRealtime();
            for(int swipe = 0; swipe < STRICT_END_SWIPES_PER_BURST; swipe++) {
                swipeReader(device, reader, 0.94f, 0.06f,
                        Math.max(1, Math.min(swipeInputSteps, 2)), scrollInputMode);
                SystemClock.sleep(24L);
            }
            device.waitForIdle(180L);
            ProgressSnapshot afterIdle = readProgressSnapshot(reader);
            ReaderSurfaceView.VisibleCoverageSnapshot coverage = readVisibleCoverage(reader);
            ProgressSnapshot afterQuiet = waitForReaderQuietProgress(reader, 1200L);
            ReaderSurfaceView.FrameStatsSnapshot frameStats =
                    waitForFrameStatsSnapshot(reader, 1400L);
            ProgressSnapshot after = afterQuiet == null || afterQuiet.isNull()
                    ? afterIdle
                    : afterQuiet;
            Log.d(TAG, "ntk_true_random_strict_end_burst run=" + run
                    + ",mode=" + mode
                    + ",burst=" + burst
                    + ",elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                    + ",path=" + (episode == null ? "" : episode.getNtkEpisodePath())
                    + ",progressBefore=" + before
                    + ",progressAfterIdle=" + afterIdle
                    + ",progressAfterQuiet=" + afterQuiet
                    + ",frameStats=" + formatFrameStats(frameStats)
                    + ",coverage=" + formatCoverage(coverage));
            assertVisibleViewportReady("strict end burst run=" + run
                    + " mode=" + mode
                    + " burst=" + burst
                    + " path=" + (episode == null ? "" : episode.getNtkEpisodePath()), coverage);
            if(assertNoJank && didScrollMove(before, after)) {
                assertNoFrameCoverageArtifacts("strict end burst run=" + run
                                + " mode=" + mode
                                + " burst=" + burst
                                + " path=" + (episode == null ? "" : episode.getNtkEpisodePath()),
                        frameStats);
                assertNoScrollJank("strict end burst run=" + run
                                + " mode=" + mode
                                + " burst=" + burst
                                + " path=" + (episode == null ? "" : episode.getNtkEpisodePath()),
                        frameStats, maxMissedFrames, maxDroppedFrames,
                        assertNoSchedulerGap, renderFrameMaxMs);
            }
            if(hasReachedExpectedGeneratedEpisode(reader, after,
                    expectedGeneratedPages, expectedNextEpisode))
                return after;
            if(!didScrollMove(before, after)) {
                stagnantBursts++;
                if(stagnantBursts >= 3)
                    return after;
            } else {
                stagnantBursts = 0;
            }
            latest = after;
        }
        return latest;
    }

    private static boolean hasReachedExpectedGeneratedEpisode(ReaderV2Activity reader,
                                                              ProgressSnapshot progress,
                                                              int expectedGeneratedPages,
                                                              Manga expectedNextEpisode) {
        if(expectedGeneratedPages <= 3 || progress == null || !progress.hasScrollOffset())
            return false;
        if(progress.page < expectedGeneratedPages - 1)
            return false;
        int pageCount = reader == null ? -1 : readPageCount(reader);
        if(pageCount > expectedGeneratedPages) {
            int nextEpisodePages = pageCount - expectedGeneratedPages;
            int requiredNextPage = expectedGeneratedPages
                    + Math.min(STRICT_NEXT_EPISODE_SAMPLE_PAGES - 1,
                    Math.max(0, nextEpisodePages - 1));
            if(progress.page < requiredNextPage)
                return false;
            return expectedNextEpisode == null
                    || hasLoadedEpisode(reader, expectedNextEpisode);
        }
        return expectedNextEpisode == null && isAtReaderEnd(progress);
    }

    private static boolean isAtReaderEnd(ProgressSnapshot progress) {
        return progress != null
                && progress.hasScrollOffset()
                && progress.maxScroll > 0
                && progress.scrollOffset >= progress.maxScroll - SCROLL_SETTLE_JUMP_TOLERANCE_PX * 3;
    }

    private static int firstKnownGeneratedNotFoundPage(Manga episode) {
        if(episode == null)
            return -1;
        return ReaderImageCache.INSTANCE.knownNtkGeneratedFirstNotFoundPage(episode.getNtkEpisodePath());
    }

    private static int reconcileExpectedGeneratedPageCountForScroll(int expectedGeneratedPages,
                                                                    int firstGeneratedNotFoundPage) {
        if(expectedGeneratedPages <= 0)
            return expectedGeneratedPages;
        if(firstGeneratedNotFoundPage > 1 && firstGeneratedNotFoundPage <= expectedGeneratedPages)
            return firstGeneratedNotFoundPage - 1;
        return expectedGeneratedPages;
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
        if("fling".equals(normalized))
            return new ScrollGesture(0.94f, 0.06f, Math.max(1, Math.min(baseSteps, 2)), "fling-down");
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

        boolean isForwardScroll() {
            return endYRatio < startYRatio;
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
        int actualBefore = readPageCount(reader);
        int before = Math.max(actualBefore, initialPageCount);
        ReaderSurfaceView.PageReadinessSnapshot readinessBefore = readPageReadiness(reader);
        if(readinessBefore != null)
            before = Math.max(before, readinessBefore.getPageCount());
        ProgressSnapshot endProgress = scrollToReaderEndForNextAppend(reader);
        ReaderSurfaceView.VisibleCoverageSnapshot endCoverage = readVisibleCoverage(reader);
        assertVisibleViewportReady("next append tail run=" + run
                + " mode=" + mode
                + " path=" + episode.getNtkEpisodePath(), endCoverage);
        if(hasLoadedEpisode(reader, nextEpisode)) {
            Log.d(TAG, "ntk_true_random_append_next run=" + run
                    + ",mode=" + mode
                    + ",expected=true,success=true,alreadyAppended=true"
                    + ",alreadyLoaded=" + hasLoadedEpisode(reader, nextEpisode)
                    + ",before=" + before
                    + ",after=" + readPageCount(reader)
                    + ",tailProgress=" + endProgress
                    + ",tailCoverage=" + formatCoverage(endCoverage)
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
            boolean loadedExpectedNext = hasLoadedEpisode(reader, nextEpisode);
            if(loadedExpectedNext) {
                ReaderSurfaceView.VisibleCoverageSnapshot coverage =
                        readVisibleCoverage(reader);
                Log.d(TAG, "ntk_true_random_append_next run=" + run
                        + ",mode=" + mode
                        + ",expected=true,success=true,step=" + step
                        + ",start=" + start
                        + ",alreadyLoaded=" + loadedExpectedNext
                        + ",before=" + before
                        + ",after=" + after
                        + ",currentPage=" + current
                        + ",progress=" + readProgress(reader)
                        + ",tailProgress=" + endProgress
                        + ",coverage=" + formatCoverage(coverage)
                        + ",path=" + episode.getNtkEpisodePath()
                        + ",nextPath=" + nextEpisode.getNtkEpisodePath());
                assertVisibleViewportReady("next append run=" + run
                        + " mode=" + mode
                        + " path=" + episode.getNtkEpisodePath()
                        + " next=" + nextEpisode.getNtkEpisodePath(), coverage);
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
                + ",tailProgress=" + endProgress
                + ",tailCoverage=" + formatCoverage(endCoverage)
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
                + " progress=" + readProgress(reader)
                + " tailProgress=" + endProgress,
                hasLoadedEpisode(reader, nextEpisode));
    }

    private static ProgressSnapshot scrollToReaderEndForNextAppend(ReaderV2Activity reader) {
        ProgressSnapshot latest = waitForReaderQuietProgress(reader, SCROLL_QUIET_TIMEOUT_MS);
        assertVisibleViewportReady("append-end-scroll initial", readVisibleCoverage(reader));
        for(int attempt = 0; attempt < 8; attempt++) {
            if(latest != null && latest.hasScrollOffset() && latest.maxScroll > 0
                    && latest.scrollOffset >= latest.maxScroll - SCROLL_SETTLE_JUMP_TOLERANCE_PX) {
                return latest;
            }
            float delta = 12000f;
            if(latest != null && latest.hasScrollOffset() && latest.maxScroll > 0)
                delta = Math.max(1200f, latest.maxScroll - latest.scrollOffset + 240f);
            final float scrollDelta = delta;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    reader.testScrollByPixels(scrollDelta));
            latest = waitForReaderQuietProgress(reader, SCROLL_QUIET_TIMEOUT_MS);
            assertVisibleViewportReady("append-end-scroll attempt=" + attempt,
                    readVisibleCoverage(reader));
        }
        return latest;
    }

    private static boolean probePreviousAppend(UiDevice device, ReaderV2Activity reader, int run,
                                               String mode, Manga episode, Manga previousEpisode,
                                               int maxSteps) {
        int before = readPageCount(reader);
        if(hasLoadedEpisode(reader, previousEpisode)) {
            Log.d(TAG, "ntk_true_random_append_previous run=" + run
                    + ",mode=" + mode
                    + ",expected=true,success=true,alreadyLoaded=true"
                    + ",before=" + before
                    + ",after=" + before
                    + ",currentPage=" + readCurrentPage(reader)
                    + ",progress=" + readProgress(reader)
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",previousPath=" + previousEpisode.getNtkEpisodePath());
            return true;
        }
        ReaderSession.AppendStartResult start = startAppend(reader, ReaderSurfaceView.DIRECTION_PREVIOUS, 0);
        int polls = Math.max(1, maxSteps);
        for(int step = 0; step < polls; step++) {
            SystemClock.sleep(350L);
            device.waitForIdle(120L);
            int after = readPageCount(reader);
            int current = readCurrentPage(reader);
            boolean alreadyLoaded = hasLoadedEpisode(reader, previousEpisode);
            if(after > before || alreadyLoaded) {
                Log.d(TAG, "ntk_true_random_append_previous run=" + run
                        + ",mode=" + mode
                        + ",expected=true,success=true,step=" + step
                        + ",start=" + start
                        + ",alreadyLoaded=" + alreadyLoaded
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
                    progress.getScrollOffset(), progress.getContentHeight(),
                    progress.getMaxScroll(), progress.getBusy());
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

    private static void assertNoOppositeScrollJump(String label, ProgressSnapshot before,
                                                   ProgressSnapshot after,
                                                   boolean expectedForward) {
        if(before == null || after == null || before.isNull() || after.isNull())
            return;
        if(expectedForward) {
            assertNoBackwardScrollJump(label, before, after);
            return;
        }
        boolean forwardPage = before.hasScrollOffset() && after.hasScrollOffset()
                ? after.scrollOffset > before.scrollOffset + SCROLL_BACKWARD_JUMP_TOLERANCE_PX
                : after.page > before.page;
        boolean forwardOffset = before.hasScrollOffset() && after.hasScrollOffset()
                ? false
                : after.page == before.page
                && after.offset < before.offset - SCROLL_BACKWARD_JUMP_TOLERANCE_PX;
        assertTrue(label
                        + " before=" + before
                        + " after=" + after,
                !forwardPage && !forwardOffset);
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

    private static boolean didScrollMove(ProgressSnapshot before, ProgressSnapshot after) {
        if(before == null || after == null || before.isNull() || after.isNull())
            return true;
        if(before.hasScrollOffset() && after.hasScrollOffset())
            return Math.abs(after.scrollOffset - before.scrollOffset) > SCROLL_SETTLE_JUMP_TOLERANCE_PX;
        return before.page != after.page
                || Math.abs(after.offset - before.offset) > SCROLL_SETTLE_JUMP_TOLERANCE_PX;
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
                renderFrameMaxMs <= 0f
                        || (stats.getTotalP95() <= renderFrameMaxMs
                        && stats.getDrawP95() <= renderFrameMaxMs));
        assertTrue(label + " frameStats=" + formatFrameStats(stats),
                stats.getMaxMissingPx() == 0
                        && stats.getMaxPlaceholderPx() == 0
                        && stats.getMaxVisibleLoading() == 0);
    }

    private static void assertNoFrameCoverageArtifacts(String label,
                                                       ReaderSurfaceView.FrameStatsSnapshot stats) {
        assertTrue(label + " missing frame stats", stats != null && stats.getSamples() > 0);
        assertTrue(label + " frameStats=" + formatFrameStats(stats),
                stats.getNoCanvas() == 0
                        && stats.getMaxMissingPx() == 0
                        && stats.getMaxPlaceholderPx() == 0
                        && stats.getMaxVisibleLoading() == 0);
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
                + ";prepP95=" + fmt(stats.getPrepP95())
                + ";prepMax=" + fmt(stats.getPrepMax())
                + ";drawP95=" + fmt(stats.getDrawP95())
                + ";drawMax=" + fmt(stats.getDrawMax())
                + ";totalP95=" + fmt(stats.getTotalP95())
                + ";totalMax=" + fmt(stats.getTotalMax())
                + ";maxMissingPx=" + stats.getMaxMissingPx()
                + ";maxPlaceholderPx=" + stats.getMaxPlaceholderPx()
                + ";maxVisibleLoading=" + stats.getMaxVisibleLoading()
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
        static final ProgressSnapshot NULL = new ProgressSnapshot(-1, 0, Integer.MIN_VALUE, 0, 0, false);
        final int page;
        final int offset;
        final int scrollOffset;
        final int contentHeight;
        final int maxScroll;
        final boolean busy;

        ProgressSnapshot(int page, int offset, int scrollOffset, int contentHeight, int maxScroll, boolean busy) {
            this.page = page;
            this.offset = offset;
            this.scrollOffset = scrollOffset;
            this.contentHeight = contentHeight;
            this.maxScroll = maxScroll;
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
            return page + ":" + offset + "@" + scrollOffset + "/h" + contentHeight + "/m" + maxScroll;
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

    private static ReaderSurfaceView.VisibleCoverageSnapshot waitForVisibleViewportReady(
            ReaderV2Activity activity, long timeoutMs) {
        ReaderSurfaceView.VisibleCoverageSnapshot latest = readVisibleCoverage(activity);
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while(!isVisibleViewportReady(latest) && SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    activity::testVisibleCoverageSnapshot);
            SystemClock.sleep(120L);
            latest = readVisibleCoverage(activity);
        }
        return latest;
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
                || coverage.getVisibleErrors() != 0
                || coverage.getWidthFillFailures() != 0
                || coverage.getLowResolutionItems() != 0)
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
                + ";pages=" + coverage.getPageCount()
                + ";widthFillFailures=" + coverage.getWidthFillFailures()
                + ";lowResolutionItems=" + coverage.getLowResolutionItems()
                + ";minDrawableSourceWidth=" + coverage.getMinDrawableSourceWidth();
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
        if("touch".equals(normalizedMode) || "swipe".equals(normalizedMode)) {
            device.swipe(x, Math.round(startY), x, Math.round(endY), Math.max(1, steps));
            return SystemClock.elapsedRealtime() - startedAt;
        }
        long downTime = SystemClock.uptimeMillis();
        int safeSteps = Math.max(1, Math.min(steps, 12));
        dispatchTouch(reader, downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, true);
        for(int step = 1; step < safeSteps; step++) {
            float fraction = step / (float)safeSteps;
            long eventTime = downTime + step * 18L;
            float y = startY + (endY - startY) * fraction;
            dispatchTouch(reader, downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, true);
            SystemClock.sleep(12L);
        }
        dispatchTouch(reader, downTime, downTime + safeSteps * 18L,
                MotionEvent.ACTION_UP, x, endY, true);
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

    private static boolean isNtkNumericId(String value) {
        return value != null && value.trim().matches("\\d{1,12}");
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
