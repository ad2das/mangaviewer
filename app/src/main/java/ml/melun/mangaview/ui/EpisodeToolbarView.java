package ml.melun.mangaview.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import ml.melun.mangaview.R;

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
        setContentDescription(context.getString(R.string.episode_toolbar_description));
    }

    public void setActions(@Nullable Actions value) { actions = value; }

    public void setFavorite(boolean value) {
        if (favorite == value) return;
        favorite = value;
        invalidate();
        if (isAttachedToWindow())
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // The canvas has four independent controls. Do not expose the host's otherwise
        // meaningless default click; publish each control as a named local-context action.
        info.setContentDescription(getContentDescription());
        info.setClickable(false);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                R.id.accessibility_episode_toolbar_back,
                getContext().getString(R.string.episode_toolbar_back)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                R.id.accessibility_episode_toolbar_favorite,
                getContext().getString(favorite
                        ? R.string.episode_toolbar_remove_favorite
                        : R.string.episode_toolbar_add_favorite)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                R.id.accessibility_episode_toolbar_download,
                getContext().getString(R.string.episode_toolbar_download)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                R.id.accessibility_episode_toolbar_more,
                getContext().getString(R.string.episode_toolbar_more)));
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle arguments) {
        int slot = slotForAccessibilityAction(action);
        if (slot >= 0)
            return dispatchSlot(slot, true);
        return super.performAccessibilityAction(action, arguments);
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
            if (slot == pressedSlot && dispatchSlot(slot, false))
                performClick();
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
        return slotForPosition(x, getWidth(), density);
    }

    private boolean dispatchSlot(int slot, boolean accessibility) {
        if (!isEnabled() || actions == null) return false;
        if (slot == 0) actions.onBack();
        else if (slot == 1) actions.onFavorite();
        else if (slot == 2) actions.onDownload();
        else if (slot == 3) actions.onMore(this);
        else return false;
        if (accessibility)
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        else
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
        return true;
    }

    private static int slotForPosition(float x, float width, float density) {
        if (x < 56f * density) return 0;
        if (x >= width - 144f * density && x < width - 96f * density) return 1;
        if (x >= width - 96f * density && x < width - 48f * density) return 2;
        if (x >= width - 48f * density) return 3;
        return -1;
    }

    private static int slotForAccessibilityAction(int action) {
        if (action == R.id.accessibility_episode_toolbar_back) return 0;
        if (action == R.id.accessibility_episode_toolbar_favorite) return 1;
        if (action == R.id.accessibility_episode_toolbar_download) return 2;
        if (action == R.id.accessibility_episode_toolbar_more) return 3;
        return -1;
    }

    static int slotForPositionForTest(float x, float width, float density) {
        return slotForPosition(x, width, density);
    }

    static int slotForAccessibilityActionForTest(int action) {
        return slotForAccessibilityAction(action);
    }

    static int favoriteActionLabelForTest(boolean favorite) {
        return favorite
                ? R.string.episode_toolbar_remove_favorite
                : R.string.episode_toolbar_add_favorite;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) { return value * density; }
}
