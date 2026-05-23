package ml.melun.mangaview.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public class ViewerClippedImageView extends AppCompatImageView {
    private final Rect clipRect = new Rect();
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private Bitmap viewerBitmap;
    private boolean fastBitmapDraw;

    public ViewerClippedImageView(Context context) {
        super(context);
    }

    public ViewerClippedImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ViewerClippedImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap directBitmap = viewerBitmap;
        if(directBitmap != null && !directBitmap.isRecycled()) {
            drawClippedBitmap(canvas, directBitmap, getAlpha() >= 1f ? 255 : Math.round(getAlpha() * 255));
            return;
        }
        Drawable drawable = getDrawable();
        if(!(drawable instanceof BitmapDrawable) || getScaleType() != ScaleType.FIT_CENTER) {
            super.onDraw(canvas);
            return;
        }
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if(bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0)
            return;
        drawClippedBitmap(canvas, bitmap, drawable.getAlpha());
    }

    public void setViewerBitmap(@Nullable Bitmap bitmap) {
        setViewerBitmap(bitmap, false);
    }

    public void setViewerBitmap(@Nullable Bitmap bitmap, boolean fastDraw) {
        if(viewerBitmap == bitmap && fastBitmapDraw == fastDraw)
            return;
        fastBitmapDraw = fastDraw;
        viewerBitmap = bitmap;
        super.setImageDrawable(null);
        invalidate();
    }

    public void setFastBitmapDraw(boolean fastDraw) {
        if(fastBitmapDraw == fastDraw)
            return;
        fastBitmapDraw = fastDraw;
        invalidate();
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        viewerBitmap = null;
        fastBitmapDraw = false;
        super.setImageDrawable(drawable);
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        setViewerBitmap(bm);
    }

    private void drawClippedBitmap(Canvas canvas, Bitmap bitmap, int alpha) {
        if(!canvas.getClipBounds(clipRect)) {
            super.onDraw(canvas);
            return;
        }
        if(!computeVisibleRects(bitmap.getWidth(), bitmap.getHeight(), getWidth(), getHeight(),
                getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom(),
                clipRect, srcRect, dstRect)) {
            return;
        }
        bitmapPaint.setAlpha(alpha);
        bitmapPaint.setColorFilter(null);
        bitmapPaint.setAntiAlias(!fastBitmapDraw);
        bitmapPaint.setFilterBitmap(!fastBitmapDraw);
        bitmapPaint.setDither(!fastBitmapDraw);
        canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint);
    }

    static int[] computeVisibleRectsForTest(int bitmapWidth, int bitmapHeight, int viewWidth, int viewHeight,
                                            int clipTop, int clipBottom) {
        return computeVisibleInts(bitmapWidth, bitmapHeight, viewWidth, viewHeight,
                0, 0, 0, 0, 0, clipTop, viewWidth, clipBottom);
    }

    private static boolean computeVisibleRects(int bitmapWidth, int bitmapHeight, int viewWidth, int viewHeight,
                                               int paddingLeft, int paddingTop, int paddingRight, int paddingBottom,
                                               Rect clip, Rect outSrc, Rect outDst) {
        int contentWidth = viewWidth - paddingLeft - paddingRight;
        int contentHeight = viewHeight - paddingTop - paddingBottom;
        if(bitmapWidth <= 0 || bitmapHeight <= 0 || contentWidth <= 0 || contentHeight <= 0)
            return false;
        float scale = Math.min(contentWidth / (float) bitmapWidth, contentHeight / (float) bitmapHeight);
        if(scale <= 0f)
            return false;
        int drawWidth = Math.max(1, Math.round(bitmapWidth * scale));
        int drawHeight = Math.max(1, Math.round(bitmapHeight * scale));
        int drawLeft = paddingLeft + (contentWidth - drawWidth) / 2;
        int drawTop = paddingTop + (contentHeight - drawHeight) / 2;
        int drawRight = drawLeft + drawWidth;
        int drawBottom = drawTop + drawHeight;
        int dstLeft = Math.max(clip.left, drawLeft);
        int dstTop = Math.max(clip.top, drawTop);
        int dstRight = Math.min(clip.right, drawRight);
        int dstBottom = Math.min(clip.bottom, drawBottom);
        if(dstRight <= dstLeft || dstBottom <= dstTop)
            return false;
        int srcLeft = clamp(Math.round((dstLeft - drawLeft) / scale), 0, bitmapWidth);
        int srcTop = clamp(Math.round((dstTop - drawTop) / scale), 0, bitmapHeight);
        int srcRight = clamp(Math.round((dstRight - drawLeft) / scale), 0, bitmapWidth);
        int srcBottom = clamp(Math.round((dstBottom - drawTop) / scale), 0, bitmapHeight);
        if(srcRight <= srcLeft || srcBottom <= srcTop)
            return false;
        outSrc.set(srcLeft, srcTop, srcRight, srcBottom);
        outDst.set(dstLeft, dstTop, dstRight, dstBottom);
        return true;
    }

    private static int[] computeVisibleInts(int bitmapWidth, int bitmapHeight, int viewWidth, int viewHeight,
                                            int paddingLeft, int paddingTop, int paddingRight, int paddingBottom,
                                            int clipLeft, int clipTop, int clipRight, int clipBottom) {
        int contentWidth = viewWidth - paddingLeft - paddingRight;
        int contentHeight = viewHeight - paddingTop - paddingBottom;
        if(bitmapWidth <= 0 || bitmapHeight <= 0 || contentWidth <= 0 || contentHeight <= 0)
            return null;
        float scale = Math.min(contentWidth / (float) bitmapWidth, contentHeight / (float) bitmapHeight);
        if(scale <= 0f)
            return null;
        int drawWidth = Math.max(1, Math.round(bitmapWidth * scale));
        int drawHeight = Math.max(1, Math.round(bitmapHeight * scale));
        int drawLeft = paddingLeft + (contentWidth - drawWidth) / 2;
        int drawTop = paddingTop + (contentHeight - drawHeight) / 2;
        int drawRight = drawLeft + drawWidth;
        int drawBottom = drawTop + drawHeight;
        int dstLeft = Math.max(clipLeft, drawLeft);
        int dstTop = Math.max(clipTop, drawTop);
        int dstRight = Math.min(clipRight, drawRight);
        int dstBottom = Math.min(clipBottom, drawBottom);
        if(dstRight <= dstLeft || dstBottom <= dstTop)
            return null;
        int srcLeft = clamp(Math.round((dstLeft - drawLeft) / scale), 0, bitmapWidth);
        int srcTop = clamp(Math.round((dstTop - drawTop) / scale), 0, bitmapHeight);
        int srcRight = clamp(Math.round((dstRight - drawLeft) / scale), 0, bitmapWidth);
        int srcBottom = clamp(Math.round((dstBottom - drawTop) / scale), 0, bitmapHeight);
        if(srcRight <= srcLeft || srcBottom <= srcTop)
            return null;
        return new int[] { srcLeft, srcTop, srcRight, srcBottom, dstLeft, dstTop, dstRight, dstBottom };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
