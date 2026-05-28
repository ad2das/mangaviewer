package ml.melun.mangaview.glide;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import ml.melun.mangaview.mangaview.Decoder;
import ml.melun.mangaview.model.PageItem;

public class ViewerPageTransformation extends BitmapTransformation {
    private static final String ID = "ml.melun.mangaview.glide.ViewerPageTransformation.5";
    private static final float SPREAD_ASPECT_RATIO = 0.90f;
    private final int index;
    private final int side;
    private final int baseMode;
    private final int mangaId;
    private final int titleId;
    private final int seed;
    private final boolean autoCut;
    private final boolean reverse;
    private final int viewerWidth;

    public ViewerPageTransformation(PageItem item, boolean autoCut, boolean reverse, int viewerWidth) {
        this.index = item == null ? 0 : item.index;
        this.side = item == null ? PageItem.FIRST : item.side;
        this.baseMode = item == null || item.manga == null ? 0 : item.manga.getBaseMode();
        this.mangaId = item == null || item.manga == null ? 0 : item.manga.getId();
        this.titleId = item == null || item.manga == null ? 0 : item.manga.getTitleId();
        this.seed = item == null || item.manga == null ? 0 : item.manga.getSeed();
        this.autoCut = autoCut;
        this.reverse = reverse;
        this.viewerWidth = Math.max(viewerWidth, 1);
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform, int outWidth, int outHeight) {
        Bitmap decoded = new Decoder(seed, mangaId).decode(toTransform, viewerWidth, pool);
        if(!autoCut)
            return decoded;

        int decodedWidth = decoded.getWidth();
        int decodedHeight = decoded.getHeight();
        Bitmap displayBitmap;
        if(shouldAutoSplit(decodedWidth, decodedHeight)) {
            int cropWidth = Math.max(1, decodedWidth / 2);
            int cropX;
            if(side == PageItem.FIRST)
                cropX = reverse ? 0 : decodedWidth - cropWidth;
            else
                cropX = reverse ? decodedWidth - cropWidth : 0;
            displayBitmap = pool.get(cropWidth, decodedHeight, displayConfig(decoded));
            Rect src = new Rect(cropX, 0, cropX + cropWidth, decodedHeight);
            Rect dst = new Rect(0, 0, cropWidth, decodedHeight);
            new Canvas(displayBitmap).drawBitmap(decoded, src, dst, null);
        } else if(side == PageItem.FIRST) {
            displayBitmap = decoded;
        } else {
            displayBitmap = pool.get(Math.max(decodedWidth, 1), 1, displayConfig(decoded));
            new Canvas(displayBitmap).drawColor(android.graphics.Color.TRANSPARENT);
        }
        return ViewerBitmapTrim.trimBlankVerticalEdges(displayBitmap);
    }

    private static Bitmap.Config displayConfig(Bitmap bitmap) {
        Bitmap.Config config = bitmap == null ? null : bitmap.getConfig();
        return config == Bitmap.Config.RGB_565 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
    }

    private static boolean shouldAutoSplit(int width, int height) {
        if(width <= 0 || height <= 0)
            return false;
        return (float) width / (float) height >= SPREAD_ASPECT_RATIO;
    }

    static boolean shouldAutoSplitForTest(int width, int height) {
        return shouldAutoSplit(width, height);
    }

    @Override
    public boolean equals(Object o) {
        if(!(o instanceof ViewerPageTransformation))
            return false;
        ViewerPageTransformation other = (ViewerPageTransformation)o;
        return index == other.index
                && side == other.side
                && baseMode == other.baseMode
                && mangaId == other.mangaId
                && titleId == other.titleId
                && seed == other.seed
                && autoCut == other.autoCut
                && reverse == other.reverse
                && viewerWidth == other.viewerWidth;
    }

    @Override
    public int hashCode() {
        int result = ID.hashCode();
        result = 31 * result + index;
        result = 31 * result + side;
        result = 31 * result + baseMode;
        result = 31 * result + mangaId;
        result = 31 * result + titleId;
        result = 31 * result + seed;
        result = 31 * result + (autoCut ? 1 : 0);
        result = 31 * result + (reverse ? 1 : 0);
        result = 31 * result + viewerWidth;
        return result;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        String key = ID + ":" + index + ":" + side + ":" + baseMode + ":" + mangaId
                + ":" + titleId + ":" + seed + ":" + autoCut + ":" + reverse + ":" + viewerWidth;
        messageDigest.update(key.getBytes(StandardCharsets.UTF_8));
    }
}
