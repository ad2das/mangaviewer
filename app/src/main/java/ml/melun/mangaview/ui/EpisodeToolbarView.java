package ml.melun.mangaview.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/** Lightweight, text-free episode toolbar used by the NTK cold path. */
public final class EpisodeToolbarView extends View {
    public interface Actions {
        void onBack();
        void onFavorite();
        void onDownload();
        void onMore(View anchor);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Actions actions;
    private boolean favorite;
    private int pressedSlot = -1;

    public EpisodeToolbarView(Context context) { this(context, null); }

    public EpisodeToolbarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setFocusable(true);
    }

    public void setActions(@Nullable Actions value) { actions = value; }

    public void setFavorite(boolean value) {
        favorite = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(0xfff4f4f4);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setColor(0xff242424);
        paint.setStyle(Paint.Style.STROKE);
        float cy = getHeight() * 0.5f;

        canvas.drawLine(dp(30), cy - dp(8), dp(22), cy, paint);
        canvas.drawLine(dp(22), cy, dp(30), cy + dp(8), paint);
        canvas.drawLine(dp(22), cy, dp(38), cy, paint);

        float favX = getWidth() - dp(120);
        if (favorite) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xffe85757);
            canvas.drawCircle(favX, cy, dp(9), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xff242424);
        } else {
            canvas.drawCircle(favX, cy, dp(9), paint);
        }

        float downX = getWidth() - dp(72);
        canvas.drawLine(downX, cy - dp(10), downX, cy + dp(5), paint);
        canvas.drawLine(downX - dp(6), cy, downX, cy + dp(6), paint);
        canvas.drawLine(downX + dp(6), cy, downX, cy + dp(6), paint);
        canvas.drawLine(downX - dp(8), cy + dp(10), downX + dp(8), cy + dp(10), paint);

        float moreX = getWidth() - dp(24);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(moreX, cy - dp(7), dp(2), paint);
        canvas.drawCircle(moreX, cy, dp(2), paint);
        canvas.drawCircle(moreX, cy + dp(7), dp(2), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            pressedSlot = slotFor(event.getX());
            setPressed(true);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            int slot = slotFor(event.getX());
            setPressed(false);
            if (slot == pressedSlot && actions != null) {
                if (slot == 0) actions.onBack();
                else if (slot == 1) actions.onFavorite();
                else if (slot == 2) actions.onDownload();
                else if (slot == 3) actions.onMore(this);
                playSoundEffect(android.view.SoundEffectConstants.CLICK);
            }
            pressedSlot = -1;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pressedSlot = -1;
            setPressed(false);
            return true;
        }
        return true;
    }

    private int slotFor(float x) {
        if (x < dp(56)) return 0;
        if (x >= getWidth() - dp(144) && x < getWidth() - dp(96)) return 1;
        if (x >= getWidth() - dp(96) && x < getWidth() - dp(48)) return 2;
        if (x >= getWidth() - dp(48)) return 3;
        return -1;
    }

    private float dp(float value) { return value * density; }
}
