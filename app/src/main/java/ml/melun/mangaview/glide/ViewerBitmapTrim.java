package ml.melun.mangaview.glide;

import android.graphics.Bitmap;
import android.graphics.Color;

public final class ViewerBitmapTrim {
    private static final int MAX_SAMPLES = 72;
    private static final int WHITE_THRESHOLD = 246;
    private static final int ALPHA_THRESHOLD = 8;
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
        Bitmap trimmed = top == 0 && bottom == height - 1
                ? bitmap
                : Bitmap.createBitmap(bitmap, 0, top, width, trimmedHeight);
        if(recycleSource && trimmed != bitmap && !bitmap.isRecycled())
            bitmap.recycle();
        return trimmed;
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
        return red >= WHITE_THRESHOLD && green >= WHITE_THRESHOLD && blue >= WHITE_THRESHOLD;
    }
}
