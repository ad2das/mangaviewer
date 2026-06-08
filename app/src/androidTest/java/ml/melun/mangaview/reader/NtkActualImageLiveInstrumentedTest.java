package ml.melun.mangaview.reader;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.CaptchaActivity;
import ml.melun.mangaview.activity.ReaderV2Activity;
import ml.melun.mangaview.activity.ViewerIntentContract;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;

@RunWith(AndroidJUnit4.class)
public class NtkActualImageLiveInstrumentedTest {
    private static final String TAG = "ViewerPerf";
    private static final String PACKAGE_NAME = "ml.melun.mangaview";
    private String ntkRoot;

    @Before
    public void setUp() {
        LiveNetworkAssume.assumeEnabled();
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.init(context);
        ntkRoot = InstrumentationRegistry.getArguments()
                .getString("ntkSiteRoot", "https://sbxh4.com");
        MainApplication.p.setNtkSitePreset(ntkRoot);
        MainApplication.p.setBaseMode(MTitle.base_comic);
        MainApplication.getHttpClient().clearPageCache();
        MainApplication.getHttpClient().clearLastCloudflareChallenge();
        MainApplication.getHttpClient().clearNtkAccessVerification();
        Search.clearNtkResultCaches();
    }

    @Test
    public void ntkModeOnePieceEpisodeRendersActualImage() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue("Expected preferences to be in NTK mode", MainApplication.p.isNtkSite());
        assertTrue("Expected HTTP client to be in NTK mode", MainApplication.getHttpClient().isNtk());

        ensureNtkClearance();

        Title title = findCurrentNtkComicTitleWithEpisodes();
        assertNotNull("Expected a current NTK comic title with episodes", title);

        Manga episode = findRenderableEpisode(title);
        assertNotNull("Expected NTK comic episode", episode);
        episode.setMode(0);
        episode.setTitle(title);
        episode.setTitleId(title.getId());

        MangaRepository.Cancellation cancellation = MangaRepository.cancellation()
                .prioritizeWebViewFallback();
        int viewerResult = MangaRepository.fetchViewerInitial(episode, cancellation);
        if(viewerResult == Title.LOAD_CAPTCHA) {
            runCaptchaFlow();
            viewerResult = MangaRepository.fetchViewerInitial(episode, MangaRepository.cancellation()
                    .prioritizeWebViewFallback());
        }
        assertTrue("Expected NTK One Piece 1184 viewer fetch to load, result=" + viewerResult
                        + ",title=" + title.getName()
                        + ",path=" + episode.getNtkEpisodePath()
                        + ",imageEpisodeId=" + episode.getNtkImageEpisodeId()
                        + ",imageCount=" + episode.getNtkImageCount()
                        + ",parseReason=" + episode.getNtkViewerParseReason(),
                viewerResult == Title.LOAD_OK);

        List<String> images = MangaRepository.imageUrls(episode, context);
        Log.d(TAG, "ntk_actual_image_urls title=" + title.getName()
                + ",episode=" + episode.getName()
                + ",path=" + episode.getNtkEpisodePath()
                + ",imageEpisodeId=" + episode.getNtkImageEpisodeId()
                + ",imageCount=" + episode.getNtkImageCount()
                + ",parseReason=" + episode.getNtkViewerParseReason()
                + ",count=" + images.size()
                + ",first=" + (images.isEmpty() ? "" : images.get(0)));
        assertTrue("Expected NTK image URLs for " + title.getName(), images.size() > 0);

        File firstImage = ReaderImageCache.INSTANCE.getOrFetchFileForeground(context, episode, images.get(0));
        Bitmap decoded = BitmapFactory.decodeFile(firstImage.getAbsolutePath());
        assertNotNull("Expected first NTK image to decode: " + firstImage.getAbsolutePath(), decoded);
        try {
            assertTrue("Expected decoded first NTK image to contain real pixels "
                            + decoded.getWidth() + "x" + decoded.getHeight(),
                    decoded.getWidth() > 64
                            && decoded.getHeight() > 64
                            && countNonBlankPixels(decoded, 16, 16) > 1000);
        } finally {
            decoded.recycle();
        }

        Activity activity = InstrumentationRegistry.getInstrumentation()
                .startActivitySync(viewerIntent(context, episode, title));
        try {
            assertTrue("Expected ReaderV2Activity strip to appear",
                    device.wait(Until.hasObject(By.res(PACKAGE_NAME, "strip")), 60000L));
            assertTrue("Expected ReaderV2Activity to render actual NTK image",
                    waitForDrawableReady(activity, device, 60000L));
            File screenshot = new File(context.getExternalCacheDir(), "ntk_actual_onepiece1184_reader.png");
            assertTrue("Expected NTK reader screenshot", device.takeScreenshot(screenshot));
            Bitmap rendered = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
            assertNotNull("Expected readable NTK reader screenshot", rendered);
            try {
                int nonBlank = countNonBlankPixels(rendered, 180, 120);
                Log.d(TAG, "ntk_actual_reader_screenshot file=" + screenshot.getAbsolutePath()
                        + ",width=" + rendered.getWidth()
                        + ",height=" + rendered.getHeight()
                        + ",nonBlank=" + nonBlank);
                assertTrue("Expected NTK reader screenshot to contain rendered image pixels, nonBlank=" + nonBlank,
                        nonBlank > 1000);
            } finally {
                rendered.recycle();
            }
        } finally {
            activity.finish();
        }
    }

    private void ensureNtkClearance() throws Exception {
        if(MainApplication.getHttpClient().hasRecentNtkAccessVerification())
            return;
        runCaptchaFlow();
    }

    private void runCaptchaFlow() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, CaptchaActivity.class);
        intent.putExtra("url", ntkRoot + "/");
        try(ActivityScenario<CaptchaActivity> ignored = ActivityScenario.launch(intent)) {
            long deadline = System.currentTimeMillis() + 120_000L;
            while(System.currentTimeMillis() < deadline) {
                if(MainApplication.getHttpClient().hasRecentNtkAccessVerification())
                    return;
                Thread.sleep(500L);
            }
        }
        assertTrue("Expected in-app NTK captcha flow to produce verified clearance",
                MainApplication.getHttpClient().hasRecentNtkAccessVerification());
    }

    private Title findCurrentNtkComicTitleWithEpisodes() throws Exception {
        CustomHttpClient client = MainApplication.getHttpClient();
        CustomHttpClient.PageResponse page = client.mgetNtkDesktopDocumentPage("/manhwa", 0L);
        assertTrue("Expected current NTK manhwa page, code=" + page.code
                        + ",bodyLen=" + (page.body == null ? 0 : page.body.length()),
                page.code >= 200 && page.code < 400 && page.body != null && page.body.length() > 0);
        List<Title> titles = MainPageWebtoon.parseNtkTitleListPayload(page.body, MTitle.base_comic, 12);
        Log.d(TAG, "ntk_actual_category_titles count=" + titles.size());
        int attempts = 0;
        int maxAttempts = parseIntArgument("ntkMaxTitleAttempts", 3);
        for(Title title : titles) {
            if(title == null)
                continue;
            attempts++;
            Log.d(TAG, "ntk_actual_title_candidate index=" + attempts
                    + ",id=" + title.getId()
                    + ",name=" + title.getName()
                    + ",source=" + title.getSourceSite()
                    + ",path=" + title.getPath());
            int episodeResult = client.runWithFetchMode(CustomHttpClient.FetchMode.DIRECT_ONLY,
                    () -> MangaRepository.fetchEpisodesForeground(title));
            if(episodeResult == Title.LOAD_CAPTCHA) {
                runCaptchaFlow();
                episodeResult = client.runWithFetchMode(CustomHttpClient.FetchMode.DIRECT_ONLY,
                        () -> MangaRepository.fetchEpisodesForeground(title));
            }
            List<Manga> episodes = Title.orderedEpisodeSnapshot(title.getEps());
            int episodeCount = episodes == null ? 0 : episodes.size();
            Log.d(TAG, "ntk_actual_title_episode_result index=" + attempts
                    + ",result=" + episodeResult
                    + ",eps=" + episodeCount);
            if(episodeResult == Title.LOAD_OK && episodeCount > 0)
                return title;
            if(attempts >= maxAttempts)
                break;
        }
        return null;
    }

    private static int parseIntArgument(String key, int fallback) {
        try {
            String value = InstrumentationRegistry.getArguments().getString(key);
            return value == null || value.trim().length() == 0 ? fallback : Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Manga findRenderableEpisode(Title title) {
        List<Manga> episodes = Title.orderedEpisodeSnapshot(title == null ? null : title.getEps());
        if(episodes == null)
            return null;
        for(int i = episodes.size() - 1; i >= 0; i--) {
            Manga episode = episodes.get(i);
            if(episode == null)
                continue;
            if(episode.getNtkEpisodePath() != null && episode.getNtkEpisodePath().length() > 0)
                return episode;
        }
        return null;
    }

    private static Intent viewerIntent(Context context, Manga episode, Title title) {
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

    private static int countNonBlankPixels(Bitmap bitmap, int ignoredTopPx, int ignoredBottomPx) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int count = 0;
        int stepX = Math.max(1, width / 80);
        int stepY = Math.max(1, height / 120);
        for(int y = Math.max(0, ignoredTopPx); y < height - Math.max(0, ignoredBottomPx); y += stepY) {
            for(int x = 0; x < width; x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                int red = Color.red(pixel);
                int green = Color.green(pixel);
                int blue = Color.blue(pixel);
                if(!(red > 245 && green > 245 && blue > 245)
                        && !(red < 10 && green < 10 && blue < 10))
                    count++;
            }
        }
        return count;
    }
}
