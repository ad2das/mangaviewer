package ml.melun.mangaview.reader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.ReaderV2Activity;
import ml.melun.mangaview.activity.ViewerIntentContract;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertTrue;

public class ReaderActualViewerVisualInstrumentedTest {
    @Test
    public void actualViewerRendersLocalPageToBottomWithoutClippedRows() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File source = patternedPage(context);
        Title title = new Title("Actual Viewer Visual", "", "", null, "", 900001, MTitle.base_comic);
        Manga episode = new Manga(900101, "Actual Viewer Visual 1화", "", MTitle.base_comic);
        episode.setMode(1);
        episode.setImgs(Collections.singletonList(source.getAbsolutePath()));
        title.setEps(Collections.singletonList(episode));

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(viewerIntent(context, episode, title));
        try {
            Bitmap rendered = waitForRenderedReader(context, 10000L);
            File renderedFile = new File(context.getExternalCacheDir(), "reader_actual_viewer_after.png");
            try(FileOutputStream output = new FileOutputStream(renderedFile)) {
                rendered.compress(Bitmap.CompressFormat.PNG, 100, output);
            }

            int bottom = rendered.getHeight() - Math.max(48, rendered.getHeight() / 12);
            int centerX = rendered.getWidth() / 2;
            int bottomPixel = rendered.getPixel(centerX, bottom);
            assertTrue(
                    "Expected actual ReaderV2Activity bottom pixels to come from the page image, not a clipped blank row. "
                            + "view=" + rendered.getWidth() + "x" + rendered.getHeight()
                            + " bottom=#" + Integer.toHexString(bottomPixel),
                    isLowerPageColor(bottomPixel));
        } finally {
            activity.finish();
        }
    }

    private static Intent viewerIntent(Context context, Manga episode, Title title) {
        Intent intent = new Intent(context, ReaderV2Activity.class);
        intent.putExtra("online", false);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("manga", Utils.toViewerMangaJson(episode, title));
        intent.putExtra(ViewerIntentContract.EXTRA_EXACT_EPISODE, true);
        intent.putExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, true);
        intent.putExtra("viewerLaunchStartedAtMs", android.os.SystemClock.elapsedRealtime());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static File patternedPage(Context context) throws Exception {
        File file = new File(context.getCacheDir(), "reader-actual-viewer-pattern.png");
        Bitmap bitmap = Bitmap.createBitmap(503, 1000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(190, 34, 52));
        canvas.drawRect(0, 0, 503, 730, paint);
        paint.setColor(Color.rgb(32, 190, 92));
        canvas.drawRect(0, 730, 503, 1000, paint);
        try(FileOutputStream output = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private static Bitmap waitForRenderedReader(Context context, long timeoutMs) throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue("Expected actual ReaderV2Activity to report a drawable page",
                device.wait(Until.hasObject(By.desc("reader-drawable-ready")), timeoutMs));
        File screenshot = new File(context.getExternalCacheDir(), "reader_actual_viewer_screen.png");
        long deadline = System.currentTimeMillis() + timeoutMs;
        AssertionError lastError = null;
        while(System.currentTimeMillis() < deadline) {
            assertTrue("Expected emulator screenshot to be captured", device.takeScreenshot(screenshot));
            Bitmap frame = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
            int y = frame.getHeight() - Math.max(48, frame.getHeight() / 12);
            int bottomPixel = frame.getPixel(frame.getWidth() / 2, y);
            if(isLowerPageColor(bottomPixel))
                return frame;
            frame.recycle();
            lastError = new AssertionError("reader bottom not ready: #" + Integer.toHexString(bottomPixel));
            Thread.sleep(100L);
        }
        throw lastError == null ? new AssertionError("reader did not render") : lastError;
    }

    private static boolean isLowerPageColor(int pixel) {
        return Color.green(pixel) > 120 && Color.red(pixel) < 120 && Color.blue(pixel) < 140;
    }
}
