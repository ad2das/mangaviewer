package ml.melun.mangaview.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class DragHandleRecyclerView extends RecyclerView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean draggingScrollbar = false;
    private float dragThumbOffset = 0f;
    private boolean readerFrameInvalidationsSuppressed = false;

    public DragHandleRecyclerView(@NonNull Context context) {
        super(context);
        initializeCustomScrollbar();
    }

    public DragHandleRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initializeCustomScrollbar();
    }

    public DragHandleRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializeCustomScrollbar();
    }

    private void initializeCustomScrollbar() {
        // This view draws and owns the persistent drag handle below. Leaving the platform
        // scrollbar enabled starts its delayed 250 ms fade animation after a row press, which
        // keeps the covered EpisodeActivity ViewRoot producing HWUI frames behind the native
        // reader surface. The custom handle has no fade animator, so it is the sole owner.
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
    }

    /**
     * Freezes the already-recorded episode-list RenderNodes while the native reader owns the
     * window. RecyclerView.suppressLayout() alone still lets row pressed-state, adapter, and
     * drawable invalidations schedule covered HWUI frames that contend with the GLES surface.
     */
    public void setReaderFrameInvalidationsSuppressed(boolean suppressed) {
        if(readerFrameInvalidationsSuppressed == suppressed)
            return;
        readerFrameInvalidationsSuppressed = suppressed;
        if(!suppressed) {
            // State changes were retained while frozen. Rebuild once only after the reader layer
            // has been hidden and normal episode ownership is being restored.
            super.requestLayout();
            super.invalidate();
        }
    }

    public boolean areReaderFrameInvalidationsSuppressed() {
        return readerFrameInvalidationsSuppressed;
    }

    @Override
    public void requestLayout() {
        if(!readerFrameInvalidationsSuppressed)
            super.requestLayout();
    }

    @Override
    public void invalidate() {
        if(!readerFrameInvalidationsSuppressed)
            super.invalidate();
    }

    @Override
    public void invalidate(Rect dirty) {
        if(!readerFrameInvalidationsSuppressed)
            super.invalidate(dirty);
    }

    @Override
    public void invalidate(int left, int top, int right, int bottom) {
        if(!readerFrameInvalidationsSuppressed)
            super.invalidate(left, top, right, bottom);
    }

    @Override
    public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
        if(readerFrameInvalidationsSuppressed)
            return null;
        return super.invalidateChildInParent(location, dirty);
    }

    @Override
    public void onDescendantInvalidated(View child, View target) {
        if(!readerFrameInvalidationsSuppressed)
            super.onDescendantInvalidated(child, target);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if(startScrollbarDrag(event.getX(), event.getY()))
                    return true;
                break;
            case MotionEvent.ACTION_MOVE:
                if(draggingScrollbar) {
                    moveScrollbar(event.getY());
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(draggingScrollbar) {
                    moveScrollbar(event.getY());
                    draggingScrollbar = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;
                }
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        drawDragScrollbar(canvas);
    }

    private boolean startScrollbarDrag(float x, float y) {
        if(!canUseScrollbar()) return false;
        if(x < getWidth() - dp(64)) return false;
        RectF thumb = thumbRect();
        draggingScrollbar = true;
        dragThumbOffset = y >= thumb.top && y <= thumb.bottom ? y - thumb.top : thumb.height() / 2f;
        getParent().requestDisallowInterceptTouchEvent(true);
        moveScrollbar(y);
        invalidate();
        return true;
    }

    private void moveScrollbar(float y) {
        LayoutManager manager = getLayoutManager();
        Adapter<?> adapter = getAdapter();
        if(!(manager instanceof LinearLayoutManager) || adapter == null) return;
        int count = adapter.getItemCount();
        if(count <= 1) return;
        float thumbHeight = thumbHeight();
        float trackTop = trackTop();
        float trackBottom = trackBottom();
        float trackRange = Math.max(1f, trackBottom - trackTop - thumbHeight);
        float targetTop = clamp(y - dragThumbOffset, trackTop, trackTop + trackRange);
        int target = Math.round(((targetTop - trackTop) / trackRange) * (count - 1));
        target = Math.max(0, Math.min(count - 1, target));
        ((LinearLayoutManager) manager).scrollToPositionWithOffset(target, 0);
        invalidate();
    }

    private void drawDragScrollbar(Canvas canvas) {
        if(!canUseScrollbar()) return;
        RectF thumb = thumbRect();
        float trackRight = getWidth() - dp(8);
        float trackLeft = trackRight - dp(28);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x26000000);
        rect.set(trackLeft, trackTop(), trackRight, trackBottom());
        canvas.drawRoundRect(rect, dp(14), dp(14), paint);

        paint.setColor(draggingScrollbar ? 0xffffffff : 0xeeffffff);
        canvas.drawRoundRect(thumb, dp(12), dp(12), paint);

        paint.setColor(0x66000000);
        float gripLeft = thumb.left + dp(7);
        float gripRight = thumb.right - dp(7);
        float centerY = thumb.centerY();
        drawGrip(canvas, gripLeft, gripRight, centerY - dp(10));
        drawGrip(canvas, gripLeft, gripRight, centerY);
        drawGrip(canvas, gripLeft, gripRight, centerY + dp(10));
    }

    private void drawGrip(Canvas canvas, float left, float right, float top) {
        rect.set(left, top, right, top + dp(3));
        canvas.drawRoundRect(rect, dp(2), dp(2), paint);
    }

    private RectF thumbRect() {
        float height = thumbHeight();
        int range = Math.max(1, computeVerticalScrollRange() - computeVerticalScrollExtent());
        int offset = Math.max(0, computeVerticalScrollOffset());
        float topInset = trackTop();
        float trackRange = Math.max(1f, trackBottom() - topInset - height);
        float top = topInset + (offset / (float) range) * trackRange;
        float right = getWidth() - dp(10);
        return new RectF(right - dp(24), top, right, top + height);
    }

    private float thumbHeight() {
        int range = computeVerticalScrollRange();
        int extent = computeVerticalScrollExtent();
        float trackHeight = Math.max(1f, trackBottom() - trackTop());
        if(range <= 0 || extent <= 0) return Math.min(dp(96), trackHeight);
        float proportional = trackHeight * (extent / (float) range);
        return clamp(proportional, Math.min(dp(96), trackHeight), trackHeight);
    }

    private float trackTop() {
        return Math.max(getPaddingTop(), dp(8));
    }

    private float trackBottom() {
        float bottom = getHeight() - dp(12);
        float minimum = trackTop() + dp(160);
        if(bottom < minimum)
            bottom = getHeight() - dp(8);
        return Math.max(trackTop() + 1f, bottom);
    }

    private boolean canUseScrollbar() {
        Adapter<?> adapter = getAdapter();
        return getWidth() > 0 &&
                getHeight() > 0 &&
                adapter != null &&
                adapter.getItemCount() > 8 &&
                (canScrollVertically(1) || canScrollVertically(-1));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
