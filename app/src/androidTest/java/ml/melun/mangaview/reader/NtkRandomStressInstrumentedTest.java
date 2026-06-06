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
    private static final long NTK_CAPTCHA_PROBE_WAIT_MS = 18_000L;
    private static final int SCROLL_BACKWARD_JUMP_TOLERANCE_PX = 240;
    private static final int SCROLL_SETTLE_JUMP_TOLERANCE_PX = 420;

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
        CustomHttpClient.PageResponse page = MainApplication.getHttpClient().mgetNtkRscPage(path, 0);
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
        int runs = parsePositiveInt(arg(args, "ntkRandomRuns", "12"), 12);
        int scrollSteps = parseNonNegativeInt(arg(args, "ntkScrollSteps", "8"), 8);
        boolean appendProbe = Boolean.parseBoolean(arg(args, "ntkAppendProbe", "true"));
        int appendSteps = parsePositiveInt(arg(args, "ntkAppendSteps", "60"), 60);
        int screenshotEvery = parseNonNegativeInt(arg(args, "ntkScreenshotEvery", "0"), 0);
        long seed = parseLong(arg(args, "ntkRandomSeed", ""), SystemClock.elapsedRealtime());
        Random random = new Random(seed);
        Random modeRandom = new Random(seed ^ 0x5a17c3e2L);
        boolean cycleModes = Boolean.parseBoolean(arg(args, "ntkCycleModes", "true"));
        int fixedBaseMode = parseBaseMode(arg(args, "ntkBaseMode", ""));
        int modeOffset = modeRandom.nextInt(MODES.length);
        CustomHttpClient client = MainApplication.getHttpClient();
        MainApplication.p.setNtkSitePreset(arg(args, "ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL));
        String customUserAgent = arg(args, "ntkUserAgent", "");
        if(customUserAgent.trim().length() > 0) {
            client.agent = customUserAgent.trim();
            Log.d(TAG, "ntk_true_random_user_agent=" + customUserAgent.trim());
        }
        String targetEpisodePath = arg(args, "ntkTargetEpisodePath", "").trim();
        String targetTitlePath = arg(args, "ntkTargetTitlePath", "").trim();
        String fixedMode = arg(args, "ntkMode", "").trim();

        Log.d(TAG, "ntk_true_random_start runs=" + runs
                + ",seed=" + seed
                + ",scrollSteps=" + scrollSteps
                + ",appendProbe=" + appendProbe
                + ",appendSteps=" + appendSteps
                + ",screenshotEvery=" + screenshotEvery
                + ",cycleModes=" + cycleModes
                + ",baseMode=" + fixedBaseMode
                + ",fixedMode=" + fixedMode
                + ",modeOffset=" + modeOffset);
        if(targetEpisodePath.length() > 0) {
            TargetEpisode target = loadTargetEpisode(client, targetTitlePath, targetEpisodePath, fixedBaseMode);
            for(int run = 0; run < runs; run++) {
                String mode = modeForRun(fixedMode, cycleModes, random, modeOffset, run);
                runReaderCase(context, device, run, mode, target.title, target.episode,
                        scrollSteps, appendProbe, appendSteps, screenshotEvery);
            }
            return;
        }
        for(int run = 0; run < runs; run++) {
            int baseMode = fixedBaseMode > 0
                    ? fixedBaseMode
                    : (random.nextBoolean() ? MTitle.base_comic : MTitle.base_webtoon);
            MainApplication.p.setBaseMode(baseMode);
            Title title = null;
            int fetchResult = Title.LOAD_ERROR;
            for(int titleAttempt = 0; titleAttempt < 6; titleAttempt++) {
                Title candidate = pickRandomTitle(context, client, random, baseMode);
                fetchResult = candidate.getEps() != null && candidate.getEps().size() > 0
                        ? Title.LOAD_OK : candidate.fetchEps(client);
                if(fetchResult != Title.LOAD_OK || candidate.getEps() == null || candidate.getEps().size() == 0) {
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
            }
            assertTrue("Expected NTK episode list for run=" + run
                    + " result=" + fetchResult,
                    title != null);
            Manga episode = pickRandomEpisode(title.getEps(), random);
            assertTrue("Expected picked NTK episode path for run=" + run
                            + " title=" + title.getName()
                            + " episode=" + episode.getName(),
                    episode.getNtkEpisodePath().length() > 0);
            String mode = modeForRun(fixedMode, cycleModes, random, modeOffset, run);
            runReaderCase(context, device, run, mode, title, episode,
                    scrollSteps, appendProbe, appendSteps, screenshotEvery);
        }
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

    private static Title pickRandomTitle(Context context, CustomHttpClient client,
                                         Random random, int baseMode) throws Exception {
        Exception apiError = null;
        try {
            return pickRandomTitleFromApi(client, random, baseMode);
        } catch (Exception e) {
            apiError = e;
            Log.d(TAG, "ntk_true_random_api_title_unavailable baseMode=" + baseMode
                    + ",type=" + e.getClass().getSimpleName()
                    + ",message=" + e.getMessage());
            if(isCloudflareFailure(e)) {
                ensureNtkAccessAfterChallenge(context, client, baseMode);
                try {
                    return pickRandomTitleFromApi(client, random, baseMode);
                } catch (Exception retry) {
                    apiError = retry;
                    Log.d(TAG, "ntk_true_random_api_title_retry_unavailable baseMode=" + baseMode
                            + ",type=" + retry.getClass().getSimpleName()
                            + ",message=" + retry.getMessage());
                }
                if(client.hasRecentCloudflareChallenge() && !client.hasNtkAccessProof()) {
                    throw new AssertionError("Unable to verify NTK access before random title fallback"
                            + " baseMode=" + baseMode
                            + " apiType=" + apiError.getClass().getSimpleName()
                            + " apiMessage=" + apiError.getMessage(), apiError);
                }
                Title dbTitle = pickRandomTitleFromClassificationDb(context, random, baseMode);
                if(dbTitle != null)
                    return dbTitle;
                Title numericTitle = pickRandomTitleFromNumericProbe(client, random, baseMode);
                if(numericTitle != null)
                    return numericTitle;
            }
        }
        if(client.hasRecentCloudflareChallenge() && !client.hasNtkAccessProof()) {
            throw new AssertionError("Unable to verify NTK access before non-API random title fallback"
                    + " baseMode=" + baseMode
                    + (apiError == null ? "" : " apiType=" + apiError.getClass().getSimpleName()
                    + " apiMessage=" + apiError.getMessage()), apiError);
        }
        Title htmlTitle = pickRandomTitleFromHtmlSections(client, random, baseMode);
        if(htmlTitle != null)
            return htmlTitle;
        Title dbTitle = pickRandomTitleFromClassificationDb(context, random, baseMode);
        if(dbTitle != null)
            return dbTitle;
        Title numericTitle = pickRandomTitleFromNumericProbe(client, random, baseMode);
        if(numericTitle != null)
            return numericTitle;
        if(apiError != null)
            throw new AssertionError("Unable to pick random NTK title after API and HTML discovery failures"
                    + " baseMode=" + baseMode
                    + " apiType=" + apiError.getClass().getSimpleName()
                    + " apiMessage=" + apiError.getMessage(), apiError);
        throw new AssertionError("Unable to pick random NTK title baseMode=" + baseMode);
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

    private static Title pickRandomTitleFromApi(CustomHttpClient client, Random random, int baseMode) throws Exception {
        String listPath = listPath(baseMode, 1);
        CustomHttpClient.PageResponse first = fetchRandomApiPage(client, listPath);
        JSONObject firstJson = new JSONObject(first.body == null ? "{}" : first.body);
        int total = Math.max(0, firstJson.optInt("total", 0));
        int maxPage = Math.max(1, total <= 0 ? 80 : (int)Math.ceil(total / (double)PAGE_SIZE));
        for(int attempt = 0; attempt < 8; attempt++) {
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

    private static Title pickRandomTitleFromHtmlSections(CustomHttpClient client, Random random, int baseMode) {
        String[][] sections = MainPageWebtoon.getSections(baseMode, true);
        if(sections == null || sections.length == 0)
            return null;
        for(int attempt = 0; attempt < 18; attempt++) {
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
        ArrayList<Manga> positiveImageCandidates = new ArrayList<>();
        for(Manga episode : episodes) {
            if(episode != null && episode.getNtkEpisodePath().length() > 0) {
                candidates.add(episode);
                if(episode.getNtkImageCount() > 0)
                    positiveImageCandidates.add(episode);
            }
        }
        assertTrue("Expected at least one episode with NTK path", candidates.size() > 0);
        if(positiveImageCandidates.size() > 0) {
            Log.d(TAG, "ntk_true_random_episode_candidates total=" + candidates.size()
                    + ",positiveImages=" + positiveImageCandidates.size());
            return positiveImageCandidates.get(random.nextInt(positiveImageCandidates.size()));
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static String modeForRun(String fixedMode, boolean cycleModes, Random random,
                                     int modeOffset, int run) {
        if(fixedMode != null && fixedMode.trim().length() > 0)
            return fixedMode.trim();
        return cycleModes ? MODES[(modeOffset + run) % MODES.length] : MODES[random.nextInt(MODES.length)];
    }

    private static TargetEpisode loadTargetEpisode(CustomHttpClient client, String titlePath,
                                                   String episodePath, int fixedBaseMode) {
        String normalizedEpisodePath = normalizeTargetPath(episodePath);
        int baseMode = fixedBaseMode > 0 ? fixedBaseMode : baseModeForTargetPath(normalizedEpisodePath);
        MainApplication.p.setBaseMode(baseMode);
        String resolvedTitlePath = normalizeTargetPath(titlePath);
        if(resolvedTitlePath.length() == 0)
            resolvedTitlePath = titlePathFromEpisodePath(normalizedEpisodePath, baseMode);
        int titleId = titleIdFromPath(resolvedTitlePath);
        Title title = new Title("ntk-target-" + titleId, "", "", new ArrayList<>(), "", titleId, baseMode);
        title.setSourceSite("ntk");
        title.setPath(resolvedTitlePath);
        int result = title.fetchEps(client);
        assertTrue("Expected NTK target title episodes result=" + result
                        + " titlePath=" + resolvedTitlePath,
                result == Title.LOAD_OK && title.getEps() != null && title.getEps().size() > 0);
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

    private static void runReaderCase(Context context, UiDevice device, int run, String mode,
                                      Title title, Manga episode, int scrollSteps,
                                      boolean appendProbe, int appendSteps, int screenshotEvery) {
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
                    + ",hasNext=" + (nextEpisode != null)
                    + ",hasPrevious=" + (previousEpisode != null));
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
            ReaderV2Activity reader = activity instanceof ReaderV2Activity ? (ReaderV2Activity) activity : null;
            int initialPageCount = reader == null ? -1 : readPageCount(reader);
            probeScrollContinuity(context, device, reader, run, mode, episode,
                    scrollSteps, screenshotEvery);
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

    private static void probeScrollContinuity(Context context, UiDevice device, ReaderV2Activity reader, int run,
                                              String mode, Manga episode, int steps, int screenshotEvery) {
        File screenshot = new File(context.getExternalCacheDir(), "ntk-random-scroll-" + run + ".png");
        for(int step = 0; step < steps; step++) {
            ProgressSnapshot progressBefore = readProgressSnapshot(reader);
            long before = SystemClock.elapsedRealtime();
            long dispatchMs = swipeReader(device, reader, 0.82f, 0.24f, 36);
            long swipeAt = SystemClock.elapsedRealtime();
            device.waitForIdle(450L);
            long idleAt = SystemClock.elapsedRealtime();
            ProgressSnapshot progressAfterIdle = readProgressSnapshot(reader);
            ProgressSnapshot progressAfterQuiet = waitForReaderQuietProgress(reader, 1800L);
            long quietAt = SystemClock.elapsedRealtime();
            SystemClock.sleep(900L);
            ProgressSnapshot progressAfterSettle = readProgressSnapshot(reader);
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
                    + ",progressBefore=" + progressBefore
                    + ",progressAfterIdle=" + progressAfterIdle
                    + ",progressAfterQuiet=" + progressAfterQuiet
                    + ",progressAfterSettle=" + progressAfterSettle
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
            assertVisibleViewportReady("scroll run=" + run
                    + " mode=" + mode
                    + " step=" + step
                    + " path=" + episode.getNtkEpisodePath(), coverage);
        }
    }

    private static boolean shouldCaptureScrollScreenshot(int step, int screenshotEvery) {
        return screenshotEvery > 0 && step % screenshotEvery == 0;
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
            ReaderSurfaceView.ProgressPosition progress = activity.testCurrentProgressPosition();
            value[0] = progress == null
                    ? ProgressSnapshot.NULL
                    : new ProgressSnapshot(progress.getPage(), progress.getOffset());
        });
        return value[0];
    }

    private static ProgressSnapshot waitForReaderQuietProgress(ReaderV2Activity activity, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        ProgressSnapshot latest = readProgressSnapshot(activity);
        while(SystemClock.elapsedRealtime() < deadline) {
            ReaderSurfaceView.VisibleCoverageSnapshot coverage = readVisibleCoverage(activity);
            latest = readProgressSnapshot(activity);
            if(coverage != null && !coverage.getBusy())
                return latest;
            SystemClock.sleep(80L);
        }
        return latest;
    }

    private static void assertNoBackwardScrollJump(String label, ProgressSnapshot before,
                                                   ProgressSnapshot after) {
        if(before == null || after == null || before.isNull() || after.isNull())
            return;
        boolean backwardPage = after.page < before.page;
        boolean backwardOffset = after.page == before.page
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
        int offsetDelta = Math.abs(after.offset - before.offset);
        boolean samePageSmallMove = pageDelta == 0 && offsetDelta <= SCROLL_SETTLE_JUMP_TOLERANCE_PX;
        boolean adjacentEdgeMove = pageDelta == 1 && offsetDelta <= SCROLL_SETTLE_JUMP_TOLERANCE_PX;
        assertTrue(label
                        + " before=" + before
                        + " after=" + after
                        + " pageDelta=" + pageDelta
                        + " offsetDelta=" + offsetDelta,
                samePageSmallMove || adjacentEdgeMove);
    }

    private static final class ProgressSnapshot {
        static final ProgressSnapshot NULL = new ProgressSnapshot(-1, 0);
        final int page;
        final int offset;

        ProgressSnapshot(int page, int offset) {
            this.page = page;
            this.offset = offset;
        }

        boolean isNull() {
            return page < 0;
        }

        @Override
        public String toString() {
            return isNull() ? "null" : page + ":" + offset;
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
                                    float startYRatio, float endYRatio, int steps) {
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
        long downTime = SystemClock.uptimeMillis();
        int safeSteps = Math.max(1, Math.min(steps, 12));
        dispatchTouch(reader, downTime, downTime, MotionEvent.ACTION_DOWN, x, startY);
        for(int step = 1; step < safeSteps; step++) {
            float fraction = step / (float)safeSteps;
            long eventTime = downTime + step * 18L;
            float y = startY + (endY - startY) * fraction;
            dispatchTouch(reader, downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, false);
            SystemClock.sleep(12L);
        }
        dispatchTouch(reader, downTime, downTime + safeSteps * 18L,
                MotionEvent.ACTION_UP, x, endY);
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
