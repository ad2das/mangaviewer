package ml.melun.mangaview.reader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import ml.melun.mangaview.glide.ViewerBitmapTrim;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void trimBlankVerticalEdgesLeavesSourceGuttersDisabled() {
        Bitmap bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.rgb(90, 120, 180));
        canvas.drawRect(0, 8, 20, 24, paint);

        Bitmap trimmed = ViewerBitmapTrim.trimBlankVerticalEdges(bitmap);

        assertEquals(30, trimmed.getHeight());
        assertEquals(Color.WHITE, trimmed.getPixel(10, 0));
        assertEquals(Color.WHITE, trimmed.getPixel(10, trimmed.getHeight() - 1));
    }

    @Test
    public void trimBlankVerticalEdgesPreservesNearBlackSourceEdges() {
        Bitmap bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(18, 18, 18));
        Canvas canvas = new Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.rgb(90, 120, 180));
        canvas.drawRect(0, 7, 20, 25, paint);

        Bitmap trimmed = ViewerBitmapTrim.trimBlankVerticalEdges(bitmap);

        assertEquals(30, trimmed.getHeight());
        assertEquals(Color.rgb(18, 18, 18), trimmed.getPixel(10, 0));
        assertEquals(Color.rgb(18, 18, 18), trimmed.getPixel(10, trimmed.getHeight() - 1));
    }

    @Test
    public void trimBlankVerticalEdgesPreservesInternalBlackSeparators() {
        Bitmap bitmap = Bitmap.createBitmap(20, 42, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.rgb(90, 120, 180));
        canvas.drawRect(0, 0, 20, 16, paint);
        paint.setColor(Color.BLACK);
        canvas.drawRect(0, 16, 20, 26, paint);
        paint.setColor(Color.rgb(170, 120, 90));
        canvas.drawRect(0, 26, 20, 42, paint);

        Bitmap trimmed = ViewerBitmapTrim.trimBlankVerticalEdges(bitmap);

        assertEquals(42, trimmed.getHeight());
        assertEquals(Color.rgb(90, 120, 180), trimmed.getPixel(10, 15));
        assertEquals(Color.BLACK, trimmed.getPixel(10, 16));
        assertEquals(Color.rgb(170, 120, 90), trimmed.getPixel(10, 26));
    }

    @Test
    public void transitionCardDoesNotDrawOverFollowingPageWhenPartiallyVisible() throws Exception {
        ReaderSurfaceView view = new ReaderSurfaceView(ApplicationProvider.getApplicationContext());
        attachForTest(view);
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 600);
        view.setPageGapPx(0);
        view.setPageCount(3);
        view.setPageBitmap(0, solidBitmap(Color.rgb(220, 32, 32)));
        view.setPageCard(1, "next episode");
        view.setPageBitmap(2, solidBitmap(Color.rgb(32, 200, 80)));
        view.scrollToPage(2, 8);

        Bitmap frame = Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame));

        assertTrue("Expected following page to remain visible below the clipped transition card",
                isGreen(frame.getPixel(frame.getWidth() / 2, 100)));
    }

    @Test
    public void partiallyVisibleBitmapIncludesBottomSourceRow() throws Exception {
        ReaderSurfaceView view = new ReaderSurfaceView(ApplicationProvider.getApplicationContext());
        attachForTest(view);
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(101, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(51, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 101, 51);
        view.setPageGapPx(0);
        view.setPageCount(1);
        view.setPageBitmap(0, halfRedHalfGreenBitmap());

        Bitmap frame = Bitmap.createBitmap(101, 51, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame));

        assertTrue("Expected bottom row of a fractional viewport clip to include visible source pixels",
                isGreen(frame.getPixel(frame.getWidth() / 2, frame.getHeight() - 1)));
    }

    @Test
    public void partiallyVisibleTileIncludesBottomSourceRow() throws Exception {
        ReaderSurfaceView view = new ReaderSurfaceView(ApplicationProvider.getApplicationContext());
        attachForTest(view);
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(101, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(51, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 101, 51);
        view.setPageGapPx(0);
        view.setPageCount(1);
        view.setPageTiles(0, 100, 100, Collections.singletonList(
                new ReaderTile(0, 100, 100, 100, halfRedHalfGreenBitmap())));

        Bitmap frame = Bitmap.createBitmap(101, 51, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame));

        assertTrue("Expected bottom row of a fractional tile clip to include visible source pixels",
                isGreen(frame.getPixel(frame.getWidth() / 2, frame.getHeight() - 1)));
    }

    @Test
    public void heightChangingPageResolveIsDeferredDuringScroll() throws Exception {
        ReaderSurfaceView view = new ReaderSurfaceView(ApplicationProvider.getApplicationContext());
        attachForTest(view);
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1000, 600);
        view.setPageGapPx(0);
        view.setPageCount(2);
        view.setPageBitmap(0, bitmapOfSize(1000, 300, Color.rgb(220, 32, 32)));
        view.setPageBitmap(1, bitmapOfSize(1000, 600, Color.rgb(32, 200, 80)));
        view.scrollToPage(1, 0);
        Bitmap before = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(before));
        assertTrue(isGreen(before.getPixel(500, 100)));

        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 500f, 300f, 0);
        view.onTouchEvent(down);
        down.recycle();
        view.setPageBitmap(0, bitmapOfSize(1000, 2000, Color.rgb(220, 32, 32)));

        Bitmap after = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(after));
        assertTrue("Expected current page pixels to remain stable while scrolling",
                isGreen(after.getPixel(500, 100)));

        MotionEvent up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, 500f, 300f, 0);
        view.onTouchEvent(up);
        up.recycle();

        Bitmap afterRelease = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(afterRelease));
        assertTrue("Expected deferred height resolve to keep the current page anchored",
                isGreen(afterRelease.getPixel(500, 100)));
    }

    @Test
    public void multipleDeferredHeightResolvesKeepReleaseAnchor() throws Exception {
        ReaderSurfaceView view = new ReaderSurfaceView(ApplicationProvider.getApplicationContext());
        attachForTest(view);
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1000, 600);
        view.setPageGapPx(0);
        view.setPageCount(3);
        view.setPageBitmap(0, bitmapOfSize(1000, 300, Color.rgb(220, 32, 32)));
        view.setPageBitmap(1, bitmapOfSize(1000, 300, Color.rgb(32, 200, 80)));
        view.setPageBitmap(2, bitmapOfSize(1000, 600, Color.rgb(32, 80, 220)));
        view.scrollToPage(2, 0);

        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 500f, 300f, 0);
        view.onTouchEvent(down);
        down.recycle();
        view.setPageBitmap(0, bitmapOfSize(1000, 2000, Color.rgb(220, 32, 32)));
        view.setPageBitmap(1, bitmapOfSize(1000, 2000, Color.rgb(32, 200, 80)));

        MotionEvent up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, 500f, 300f, 0);
        view.onTouchEvent(up);
        up.recycle();

        ReaderSurfaceView.ProgressPosition position = view.currentProgressPosition();
        assertEquals("Expected release to stay anchored to the page visible before pending height resolves",
                2, position.getPage());
        assertEquals(0, position.getOffset());
    }

    private static Bitmap solidBitmap(int color) {
        Bitmap bitmap = Bitmap.createBitmap(720, 360, Bitmap.Config.RGB_565);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static Bitmap bitmapOfSize(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static Bitmap halfRedHalfGreenBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.rgb(220, 32, 32));
        canvas.drawRect(0, 0, 100, 50, paint);
        paint.setColor(Color.rgb(32, 200, 80));
        canvas.drawRect(0, 50, 100, 100, paint);
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
