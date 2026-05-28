package ml.melun.mangaview.glide;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

public final class ViewerBitmapTrim {
    private static final int MAX_SAMPLES = 72;
    private static final int WHITE_THRESHOLD = 246;
    private static final int BLACK_THRESHOLD = 24;
    private static final int ALPHA_THRESHOLD = 8;
    private static final int MIN_INTERNAL_BLANK_BAND_PX = 6;
    private static final int MAX_NON_BLANK_SAMPLES = 1;

    private ViewerBitmapTrim() {
    }

    public static Bitmap trimBlankVerticalEdges(Bitmap bitmap) {
        return trimBlankVerticalEdges(bitmap, false);
    }

    public static Bitmap trimBlankVerticalEdges(Bitmap bitmap, boolean recycleSource) {
        if(bitmap == null || bitmap.isRecycled())
            return bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if(width <= 0 || height <= 2)
            return bitmap;
        int top = firstContentRow(bitmap, width, height);
        if(top <= 0 && top < height)
            top = 0;
        if(top >= height)
            return bitmap;
        int bottom = lastContentRow(bitmap, width, height, top);
        if(bottom < top)
            return bitmap;
        int trimmedHeight = bottom - top + 1;
        if(trimmedHeight <= 0 || trimmedHeight < Math.max(1, height / 4))
            return bitmap;
        Bitmap edgeTrimmed = top == 0 && bottom == height - 1
                ? bitmap
                : Bitmap.createBitmap(bitmap, 0, top, width, trimmedHeight);
        Bitmap trimmed = trimInternalBlankBands(edgeTrimmed);
        if(edgeTrimmed != bitmap && trimmed != edgeTrimmed && !edgeTrimmed.isRecycled())
            edgeTrimmed.recycle();
        if(recycleSource && trimmed != bitmap && !bitmap.isRecycled())
            bitmap.recycle();
        return trimmed;
    }

    private static Bitmap trimInternalBlankBands(Bitmap bitmap) {
        if(bitmap == null || bitmap.isRecycled())
            return bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if(width <= 0 || height <= MIN_INTERNAL_BLANK_BAND_PX * 2)
            return bitmap;
        List<int[]> keepRanges = new ArrayList<>();
        int keepStart = 0;
        boolean removedAny = false;
        int y = 0;
        while(y < height) {
            if(!isBlankRow(bitmap, width, y)) {
                y++;
                continue;
            }
            int bandStart = y;
            while(y < height && isBlankRow(bitmap, width, y))
                y++;
            int bandEnd = y;
            int bandHeight = bandEnd - bandStart;
            if(shouldRemoveInternalBand(bandStart, bandEnd, bandHeight)) {
                if(bandStart > keepStart)
                    keepRanges.add(new int[]{keepStart, bandStart});
                keepStart = bandEnd;
                removedAny = true;
            }
        }
        if(!removedAny)
            return bitmap;
        if(keepStart < height)
            keepRanges.add(new int[]{keepStart, height});
        int newHeight = 0;
        for(int[] range : keepRanges)
            newHeight += range[1] - range[0];
        if(newHeight <= 0 || newHeight >= height || newHeight < Math.max(1, height / 4))
            return bitmap;
        Bitmap.Config config = bitmap.getConfig();
        if(config == null || config == Bitmap.Config.HARDWARE)
            config = Bitmap.Config.ARGB_8888;
        Bitmap trimmed = Bitmap.createBitmap(width, newHeight, config);
        Canvas canvas = new Canvas(trimmed);
        int dstTop = 0;
        for(int[] range : keepRanges) {
            Bitmap segment = Bitmap.createBitmap(bitmap, 0, range[0], width, range[1] - range[0]);
            canvas.drawBitmap(segment, 0, dstTop, null);
            dstTop += segment.getHeight();
            segment.recycle();
        }
        return trimmed;
    }

    private static boolean shouldRemoveInternalBand(int start, int end, int bandHeight) {
        if(start <= 0)
            return false;
        if(bandHeight < MIN_INTERNAL_BLANK_BAND_PX)
            return false;
        return true;
    }

    private static int firstContentRow(Bitmap bitmap, int width, int height) {
        int y = 0;
        while(y < height && isBlankRow(bitmap, width, y))
            y++;
        return y;
    }

    private static int lastContentRow(Bitmap bitmap, int width, int height, int top) {
        int y = height - 1;
        while(y >= top && isBlankRow(bitmap, width, y))
            y--;
        return y;
    }

    private static boolean isBlankRow(Bitmap bitmap, int width, int y) {
        int samples = Math.min(MAX_SAMPLES, Math.max(2, width));
        int nonBlank = 0;
        for(int i = 0; i < samples; i++) {
            int x = samples == 1 ? 0 : Math.round((width - 1) * (i / (float)(samples - 1)));
            if(!isBlankPixel(bitmap.getPixel(x, y)) && ++nonBlank > MAX_NON_BLANK_SAMPLES)
                return false;
        }
        return true;
    }

    private static boolean isBlankPixel(int pixel) {
        if(Color.alpha(pixel) < ALPHA_THRESHOLD)
            return true;
        int red = Color.red(pixel);
        int green = Color.green(pixel);
        int blue = Color.blue(pixel);
        return red >= WHITE_THRESHOLD && green >= WHITE_THRESHOLD && blue >= WHITE_THRESHOLD
                || red <= BLACK_THRESHOLD && green <= BLACK_THRESHOLD && blue <= BLACK_THRESHOLD;
    }
}
