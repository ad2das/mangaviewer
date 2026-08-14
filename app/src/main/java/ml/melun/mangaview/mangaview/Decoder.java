package ml.melun.mangaview.mangaview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;

public class Decoder {
    int __seed=0;
    int id=0;
    int view_cnt;
    int cx=5, cy=5;
    private static final int MAX_DISPLAY_BITMAP_BYTES = 64 * 1024 * 1024;
    private static final Paint BITMAP_SCALE_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    public int getCnt(){
        return view_cnt;
    }

    public Decoder(int seed, int id){
        view_cnt = seed;
        __seed = seed/10;
        this.id = id;
        if(__seed>30000){
            cx = 1;
            cy = 6;
        }else if(__seed>20000){
            cx = 1;
        } else if (__seed>10000) {
            cy = 1;
        }
    }

    public Bitmap decode(Bitmap input, int width){
        Bitmap sampled = getSampleBitmap(input, width, null);
        Bitmap output = null;
        try {
            output = decode(sampled);
            return output;
        } finally {
            if(sampled != input && sampled != output && !sampled.isRecycled())
                sampled.recycle();
        }
    }
    public Bitmap decode(Bitmap input, int width, BitmapPool pool){
        BitmapCandidate sampled = getSampleCandidate(input, width, pool);
        Bitmap output = decode(sampled.bitmap, pool);
        if(sampled.owned && output != sampled.bitmap)
            putBitmap(pool, sampled.bitmap);
        return output;
    }
    public Bitmap downSample(final Bitmap input, int maxBytes) {
        if(input.getByteCount() > maxBytes) {
            Float ratio = (maxBytes*1.0f/input.getByteCount());
            return downSize(input, ratio);
        }
        return input;
    }
    public Bitmap downSample(final Bitmap input, int maxBytes, BitmapPool pool) {
        if(input.getByteCount() > maxBytes) {
            Float ratio = (maxBytes*1.0f/input.getByteCount());
            return downSize(input, ratio, pool);
        }
        return input;
    }
    public Bitmap downSize(final Bitmap input, Float ratio) {
        Bitmap bitmap = Bitmap.createScaledBitmap(input, scaledDimension(input.getWidth(), ratio), scaledDimension(input.getHeight(), ratio), true);
        return bitmap;
    }
    public Bitmap downSize(final Bitmap input, Float ratio, BitmapPool pool) {
        int width = scaledDimension(input.getWidth(), ratio);
        int height = scaledDimension(input.getHeight(), ratio);
        Bitmap bitmap = obtainBitmap(pool, width, height, displayConfig(input));
        new Canvas(bitmap).drawBitmap(input, null, new Rect(0, 0, width, height), BITMAP_SCALE_PAINT);
        return bitmap;
    }

    static int scaledDimensionForTest(int size, float ratio) {
        return scaledDimension(size, ratio);
    }

    private static int scaledDimension(int size, float ratio) {
        return Math.max(1, (int) (size * ratio));
    }

    public Bitmap decode(Bitmap input){
        Bitmap downsampled = downSample(input, MAX_DISPLAY_BITMAP_BYTES);
        Bitmap output = null;
        try {
            output = decodeDownsampled(downsampled, null);
            return output;
        } finally {
            if(downsampled != input && downsampled != output && !downsampled.isRecycled())
                downsampled.recycle();
        }
    }

    public Bitmap decode(Bitmap input, BitmapPool pool){
        BitmapCandidate downsampled = downSampleCandidate(input, MAX_DISPLAY_BITMAP_BYTES, pool);
        Bitmap output = decodeDownsampled(downsampled.bitmap, pool);
        if(downsampled.owned && output != downsampled.bitmap)
            putBitmap(pool, downsampled.bitmap);
        return output;
    }

    private Bitmap decodeDownsampled(Bitmap input, BitmapPool pool) {
        if(view_cnt==0) return input;
        int[][] order = new int[cx*cy][2];
        for (int i = 0; i < cx*cy; i++) {
            order[i][0] = i;
            if (id < 554714) order[i][1] = _random(i);
            else order[i][1] = newRandom(i);
        }
        java.util.Arrays.sort(order, (a, b) -> {
            //return Double.compare(a[1], b[1]);
            return a[1] != b[1] ? a[1] - b[1] : a[0] - b[0];
        });
        //create new bitmap
        Bitmap output = obtainBitmap(pool, input.getWidth(), input.getHeight(), displayConfig(input));

        Canvas canvas = new Canvas(output);

        int row_w = gridCellSize(input.getWidth(), cx);
        int row_h = gridCellSize(input.getHeight(), cy);
        Rect src = new Rect();
        Rect dst = new Rect();
        for (int i = 0; i < cx*cy; i++) {
            int[] o = order[i];
            int ox = i % cx;
            int oy = i / cx;
            int tx = o[0] % cx;
            int ty = o[0] / cx;
            src.set(ox * row_w, oy * row_h, ox * row_w + row_w, oy * row_h + row_h);
            dst.set(tx * row_w, ty * row_h, tx * row_w + row_w, ty * row_h + row_h);
            canvas.drawBitmap(input, src, dst, null);
        }
        return output;
    }

    private static Bitmap getSampleBitmap(Bitmap input, int width, BitmapPool pool) {
        width = sampleWidth(input.getWidth(), width);
        if(input.getWidth() <= width)
            return input;
        int height = sampleHeight(input.getWidth(), input.getHeight(), width);
        Bitmap bitmap = obtainBitmap(pool, width, height, displayConfig(input));
        new Canvas(bitmap).drawBitmap(input, null, new Rect(0, 0, width, height), BITMAP_SCALE_PAINT);
        return bitmap;
    }

    private static BitmapCandidate getSampleCandidate(Bitmap input, int width, BitmapPool pool) {
        width = sampleWidth(input.getWidth(), width);
        if(input.getWidth() <= width)
            return new BitmapCandidate(input, false);
        int height = sampleHeight(input.getWidth(), input.getHeight(), width);
        Bitmap bitmap = obtainBitmap(pool, width, height, displayConfig(input));
        new Canvas(bitmap).drawBitmap(input, null, new Rect(0, 0, width, height), BITMAP_SCALE_PAINT);
        return new BitmapCandidate(bitmap, pool != null);
    }

    private BitmapCandidate downSampleCandidate(final Bitmap input, int maxBytes, BitmapPool pool) {
        if(input.getByteCount() > maxBytes) {
            Float ratio = (maxBytes*1.0f/input.getByteCount());
            return new BitmapCandidate(downSize(input, ratio, pool), pool != null);
        }
        return new BitmapCandidate(input, false);
    }

    private static int sampleWidth(int inputWidth, int requestedWidth) {
        if(inputWidth <= 0)
            return 1;
        if(requestedWidth <= 0)
            return 1;
        return Math.max(1, Math.min(inputWidth, requestedWidth));
    }

    private static int sampleHeight(int inputWidth, int inputHeight, int targetWidth) {
        if(inputWidth <= 0 || inputHeight <= 0)
            return 1;
        float ratio = (float) inputHeight / (float) inputWidth;
        return Math.max(1, Math.round(ratio * Math.max(1, targetWidth)));
    }

    private int _random(int index){
        double x = Math.sin(__seed+index) * 10000;
        return (int) Math.floor((x - Math.floor(x)) * 100000);
    }

    static int gridCellSizeForTest(int size, int cells) {
        return gridCellSize(size, cells);
    }

    private static int gridCellSize(int size, int cells) {
        if(cells <= 0)
            return Math.max(1, size);
        return Math.max(1, size / cells);
    }

    private static Bitmap.Config displayConfig(Bitmap bitmap) {
        return Bitmap.Config.ARGB_8888;
    }

    private static Bitmap obtainBitmap(BitmapPool pool, int width, int height, Bitmap.Config config) {
        if(pool != null)
            return pool.get(Math.max(1, width), Math.max(1, height), config);
        return Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), config);
    }

    private static void putBitmap(BitmapPool pool, Bitmap bitmap) {
        if(pool == null || bitmap == null || bitmap.isRecycled())
            return;
        pool.put(bitmap);
    }

    private static class BitmapCandidate {
        final Bitmap bitmap;
        final boolean owned;

        BitmapCandidate(Bitmap bitmap, boolean owned) {
            this.bitmap = bitmap;
            this.owned = owned;
        }
    }

    private int newRandom(int index){
        index++;
        double t = 100 * Math.sin(10 * (__seed+index))
                , n = 1000 * Math.cos(13 * (__seed+index))
                , a = 10000 * Math.tan(14 * (__seed+index));
        t = Math.floor(100 * (t - Math.floor(t)));
        n = Math.floor(1000 * (n - Math.floor(n)));
        a = Math.floor(10000 * (a - Math.floor(a)));
        return (int)(t + n + a);
    }
}
