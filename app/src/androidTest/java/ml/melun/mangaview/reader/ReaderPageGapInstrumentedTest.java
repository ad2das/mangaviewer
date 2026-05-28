package ml.melun.mangaview.reader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ReaderPageGapInstrumentedTest {
    @Test
    public void readerDrawsAdjacentPagesWithoutBackgroundGap() throws Exception {
        ReaderSurfaceView view = new ReaderSurfaceView(ApplicationProvider.getApplicationContext());
        attachForTest(view);
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1800);
        view.setPageGapPx(0);
        view.setPageCount(2);
        view.setPageBitmap(0, solidBitmap(Color.rgb(220, 32, 32)));
        view.setPageBitmap(1, solidBitmap(Color.rgb(32, 200, 80)));

        Bitmap frame = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame));

        assertTrue("Expected red first page and green second page with no black rows between them. "
                        + boundarySummary(frame),
                hasDirectRedGreenBoundary(frame));
    }

    private static Bitmap solidBitmap(int color) {
        Bitmap bitmap = Bitmap.createBitmap(720, 360, Bitmap.Config.RGB_565);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static boolean hasDirectRedGreenBoundary(Bitmap bitmap) {
        int x = bitmap.getWidth() / 2;
        boolean sawRed = false;
        for(int y = 0; y < bitmap.getHeight(); y++) {
            int pixel = bitmap.getPixel(x, y);
            if(isRed(pixel)) {
                sawRed = true;
                continue;
            }
            if(sawRed && isGreen(pixel))
                return true;
            if(sawRed && isBlack(pixel))
                return false;
        }
        return false;
    }

    private static String boundarySummary(Bitmap bitmap) {
        int x = bitmap.getWidth() / 2;
        int redStart = -1;
        int redEnd = -1;
        int greenStart = -1;
        int firstBlackAfterRed = -1;
        for(int y = 0; y < bitmap.getHeight(); y++) {
            int pixel = bitmap.getPixel(x, y);
            if(isRed(pixel)) {
                if(redStart < 0)
                    redStart = y;
                redEnd = y;
            } else if(redEnd >= 0 && firstBlackAfterRed < 0 && isBlack(pixel)) {
                firstBlackAfterRed = y;
            }
            if(redEnd >= 0 && greenStart < 0 && isGreen(pixel))
                greenStart = y;
        }
        return "redStart=" + redStart
                + " redEnd=" + redEnd
                + " firstBlackAfterRed=" + firstBlackAfterRed
                + " greenStart=" + greenStart;
    }

    private static boolean isRed(int pixel) {
        return Color.red(pixel) > 160 && Color.green(pixel) < 80 && Color.blue(pixel) < 80;
    }

    private static boolean isGreen(int pixel) {
        return Color.green(pixel) > 140 && Color.red(pixel) < 100 && Color.blue(pixel) < 120;
    }

    private static boolean isBlack(int pixel) {
        return Color.red(pixel) < 24 && Color.green(pixel) < 24 && Color.blue(pixel) < 24;
    }

    private static void attachForTest(View view) throws Exception {
        java.lang.reflect.Method method = View.class.getDeclaredMethod("onAttachedToWindow");
        method.setAccessible(true);
        method.invoke(view);
    }
}
