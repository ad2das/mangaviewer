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
import org.jsoup.Jsoup;
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
import ml.melun.mangaview.mangaview.MainPageWebtoon;
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
        boolean appendProbe = Boolean.parseBoolean(arg(args, "ntkAppendProbe", "true"));
        int appendSteps = parsePositiveInt(arg(args, "ntkAppendSteps", "60"), 60);
        long seed = parseLong(arg(args, "ntkRandomSeed", ""), SystemClock.elapsedRealtime());
        Random random = new Random(seed);
        CustomHttpClient client = MainApplication.getHttpClient();
        MainApplication.p.setNtkSitePreset(arg(args, "ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL));
        String customUserAgent = arg(args, "ntkUserAgent", "");
        if(customUserAgent.trim().length() > 0) {
            client.agent = customUserAgent.trim();
            Log.d(TAG, "ntk_true_random_user_agent=" + customUserAgent.trim());
        }

        Log.d(TAG, "ntk_true_random_start runs=" + runs
                + ",seed=" + seed
                + ",scrollSteps=" + scrollSteps
                + ",appendProbe=" + appendProbe
                + ",appendSteps=" + appendSteps);
        for(int run = 0; run < runs; run++) {
            int baseMode = random.nextBoolean() ? MTitle.base_comic : MTitle.base_webtoon;
            MainApplication.p.setBaseMode(baseMode);
            Title title = null;
            int fetchResult = Title.LOAD_ERROR;
            for(int titleAttempt = 0; titleAttempt < 6; titleAttempt++) {
                Title candidate = pickRandomTitle(client, random, baseMode);
                fetchResult = candidate.fetchEps(client);
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
            String mode = MODES[random.nextInt(MODES.length)];
            runReaderCase(context, device, run, mode, title, episode,
                    scrollSteps, appendProbe, appendSteps);
        }
    }

    private static Title pickRandomTitle(CustomHttpClient client, Random random, int baseMode) throws Exception {
        Exception apiError = null;
        try {
            return pickRandomTitleFromApi(client, random, baseMode);
        } catch (Exception e) {
            apiError = e;
            Log.d(TAG, "ntk_true_random_api_title_unavailable baseMode=" + baseMode
                    + ",type=" + e.getClass().getSimpleName()
                    + ",message=" + e.getMessage());
        }
        Title htmlTitle = pickRandomTitleFromHtmlSections(client, random, baseMode);
        if(htmlTitle != null)
            return htmlTitle;
        if(apiError != null)
            throw new AssertionError("Unable to pick random NTK title after API and HTML discovery failures"
                    + " baseMode=" + baseMode
                    + " apiType=" + apiError.getClass().getSimpleName()
                    + " apiMessage=" + apiError.getMessage(), apiError);
        throw new AssertionError("Unable to pick random NTK title baseMode=" + baseMode);
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
        int blockedAttempts = 0;
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
                CustomHttpClient.PageResponse page = client.mgetCachedPage(path, PAGE_CACHE_TTL_MS);
                ArrayList<Title> parsed = MainPageWebtoon.parseWolfTitles(
                        Jsoup.parse(page.body == null ? "" : page.body), baseMode, PAGE_SIZE);
                ArrayList<Title> candidates = new ArrayList<>();
                for(Title title : parsed) {
                    if(title == null || title.getPath() == null || title.getPath().length() == 0)
                        continue;
                    title.setSourceSite("ntk");
                    candidates.add(title);
                }
                Log.d(TAG, "ntk_true_random_html_title_source baseMode=" + baseMode
                        + ",attempt=" + attempt
                        + ",path=" + path
                        + ",code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length())
                        + ",parsed=" + parsed.size()
                        + ",candidates=" + candidates.size());
                if(candidates.size() == 0)
                    continue;
                Title title = candidates.get(random.nextInt(candidates.size()));
                Log.d(TAG, "ntk_true_random_title_html baseMode=" + baseMode
                        + ",path=" + path
                        + ",id=" + title.getId()
                        + ",titlePath=" + title.getPath()
                        + ",name=" + title.getName());
                return title;
            } catch (Exception e) {
                Log.d(TAG, "ntk_true_random_html_title_skip baseMode=" + baseMode
                        + ",attempt=" + attempt
                        + ",path=" + path
                        + ",type=" + e.getClass().getSimpleName()
                        + ",message=" + e.getMessage());
                if(isCloudflareFailure(e) && ++blockedAttempts >= 2)
                    break;
            }
        }
        return null;
    }

    private static ArrayList<Title> pickRandomTitleCandidatesFromRsc(CustomHttpClient client, String path, int baseMode) {
        ArrayList<Title> candidates = new ArrayList<>();
        try {
            CustomHttpClient.PageResponse rsc = client.mgetNtkRscPage(path, PAGE_CACHE_TTL_MS);
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
        }
        return candidates;
    }

    private static boolean isCloudflareFailure(Exception e) {
        String message = e == null ? null : e.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("cloudflare") || lower.contains("challenge");
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
        for(Manga episode : episodes) {
            if(episode != null && episode.getNtkEpisodePath().length() > 0)
                candidates.add(episode);
        }
        assertTrue("Expected at least one episode with NTK path", candidates.size() > 0);
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static void runReaderCase(Context context, UiDevice device, int run, String mode,
                                      Title title, Manga episode, int scrollSteps,
                                      boolean appendProbe, int appendSteps) {
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
            probeScrollContinuity(context, device, run, mode, episode, scrollSteps);
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
        if(readPageCount(reader) > before) {
            Log.d(TAG, "ntk_true_random_append_next run=" + run
                    + ",mode=" + mode
                    + ",expected=true,success=true,alreadyAppended=true"
                    + ",before=" + before
                    + ",after=" + readPageCount(reader)
                    + ",path=" + episode.getNtkEpisodePath()
                    + ",nextPath=" + nextEpisode.getNtkEpisodePath());
            return;
        }
        int width = Math.max(1, device.getDisplayWidth());
        int height = Math.max(1, device.getDisplayHeight());
        int startX = width / 2;
        int startY = (int)(height * 0.84f);
        int endY = (int)(height * 0.16f);
        boolean reachedBoundary = false;
        for(int step = 0; step < maxSteps; step++) {
            device.swipe(startX, startY, startX, endY, 38);
            device.waitForIdle(550L);
            int after = readPageCount(reader);
            int current = readCurrentPage(reader);
            if(after > before) {
                Log.d(TAG, "ntk_true_random_append_next run=" + run
                        + ",mode=" + mode
                        + ",expected=true,success=true,step=" + step
                        + ",before=" + before
                        + ",after=" + after
                        + ",currentPage=" + current
                        + ",path=" + episode.getNtkEpisodePath()
                        + ",nextPath=" + nextEpisode.getNtkEpisodePath());
                return;
            }
            if(current >= before - 1)
                reachedBoundary = true;
        }
        int after = readPageCount(reader);
        int current = readCurrentPage(reader);
        Log.d(TAG, "ntk_true_random_append_next run=" + run
                + ",mode=" + mode
                + ",expected=true,success=false"
                + ",reachedBoundary=" + reachedBoundary
                + ",before=" + before
                + ",after=" + after
                + ",currentPage=" + current
                + ",path=" + episode.getNtkEpisodePath()
                + ",nextPath=" + nextEpisode.getNtkEpisodePath());
        assertTrue("Reached next boundary without append run=" + run
                + " mode=" + mode
                + " path=" + episode.getNtkEpisodePath()
                + " next=" + nextEpisode.getNtkEpisodePath()
                + " before=" + before
                + " after=" + after
                + " currentPage=" + current,
                !reachedBoundary);
    }

    private static boolean probePreviousAppend(UiDevice device, ReaderV2Activity reader, int run,
                                               String mode, Manga episode, Manga previousEpisode,
                                               int maxSteps) {
        int before = readPageCount(reader);
        int width = Math.max(1, device.getDisplayWidth());
        int height = Math.max(1, device.getDisplayHeight());
        int startX = width / 2;
        int startY = (int)(height * 0.22f);
        int endY = (int)(height * 0.84f);
        for(int step = 0; step < maxSteps; step++) {
            device.swipe(startX, startY, startX, endY, 36);
            device.waitForIdle(650L);
            int after = readPageCount(reader);
            int current = readCurrentPage(reader);
            if(after > before) {
                Log.d(TAG, "ntk_true_random_append_previous run=" + run
                        + ",mode=" + mode
                        + ",expected=true,success=true,step=" + step
                        + ",before=" + before
                        + ",after=" + after
                        + ",currentPage=" + current
                        + ",path=" + episode.getNtkEpisodePath()
                        + ",previousPath=" + previousEpisode.getNtkEpisodePath());
                return true;
            }
        }
        Log.d(TAG, "ntk_true_random_append_previous run=" + run
                + ",mode=" + mode
                + ",expected=true,success=false"
                + ",before=" + before
                + ",after=" + readPageCount(reader)
                + ",currentPage=" + readCurrentPage(reader)
                + ",path=" + episode.getNtkEpisodePath()
                + ",previousPath=" + previousEpisode.getNtkEpisodePath());
        return false;
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
