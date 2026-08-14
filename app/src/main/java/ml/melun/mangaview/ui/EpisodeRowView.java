package ml.melun.mangaview.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import ml.melun.mangaview.R;

/** A single HWUI node for an episode row; no child views or XML inflation. */
public final class EpisodeRowView extends View {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rowRect = new RectF();
    private final RectF thumbRect = new RectF();
    private final RectF actionRect = new RectF();

    private CharSequence rawTitle = "";
    private CharSequence rawDate = "";
    private CharSequence displayTitle = "";
    private CharSequence displayDate = "";
    private boolean showNew;
    private boolean showAction;
    private boolean downloadAction;
    private boolean showThumbnail = true;
    private boolean selected;
    private boolean actionPressed;
    private boolean rowPressCancelled;
    private boolean pressCancellationSent;
    private float pressDownX;
    private float pressDownY;
    private Runnable pressListener;
    private Runnable pressCancelListener;
    private Runnable actionClickListener;
    @StringRes private int selectedStateLabel = R.string.episode_selected_state;

    private int backgroundColor;
    private int titleColor;
    private int metaColor;
    private int accentColor;
    private int actionSurfaceColor;
    private int thumbnailSurfaceColor;

    private final float density;
    private final float rowInsetX;
    private final float rowInsetY;
    private final float cornerRadius;
    private final float thumbSize;
    private final float titleTextSize;
    private final float metaTextSize;
    private final int touchSlop;

    public EpisodeRowView(Context context) {
        this(context, null);
    }

    public EpisodeRowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        rowInsetX = dp(12);
        rowInsetY = dp(6);
        cornerRadius = dp(12);
        thumbSize = dp(64);
        titleTextSize = sp(15);
        metaTextSize = sp(12);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        titlePaint.setTextSize(titleTextSize);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        metaPaint.setTextSize(metaTextSize);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(2));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
        setFocusable(true);
        // Episode rows never contain editable data. Explicitly exclude the leaf as well as the
        // NTK root so AutofillManager cannot run its fill-dialog hint scan ahead of ACTION_DOWN's
        // press listener on devices where RecyclerView temporarily reparents the row.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumHeight((int) dp(108));
        setWillNotDraw(false);
    }

    public void setPalette(int background, int title, int meta, int accent, int actionSurface, int thumbnailSurface) {
        backgroundColor = background;
        titleColor = title;
        metaColor = meta;
        accentColor = accent;
        actionSurfaceColor = actionSurface;
        thumbnailSurfaceColor = thumbnailSurface;
        invalidate();
    }

    public void bind(CharSequence title, CharSequence date, boolean isNew, boolean hasAction,
                     boolean isDownloadAction, boolean isSelected, boolean enabled, boolean hasThumbnail) {
        rawTitle = title == null ? "" : title;
        rawDate = date == null ? "" : date;
        showNew = isNew;
        showAction = hasAction;
        downloadAction = isDownloadAction;
        selected = isSelected;
        showThumbnail = hasThumbnail;
        setEnabled(enabled);
        updateSelectionAccessibility();
        updateContentDescription();
        rebuildDisplayText();
        invalidate();
    }

    /** Stable automation identity kept out of the user-facing accessibility label. */
    public void setEpisodeIdentity(@Nullable CharSequence identity) {
        setTag(R.id.episode_automation_identity, identity == null ? "" : identity);
    }

    private void updateContentDescription() {
        String label = buildSpokenLabel(rawTitle, rawDate, showNew,
                getContext().getString(R.string.episode_new_state));
        setContentDescription(label.length() == 0 ? null : label);
        setImportantForAccessibility(label.length() == 0
                ? IMPORTANT_FOR_ACCESSIBILITY_NO
                : IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setSelectedStateLabel(@StringRes int label) {
        selectedStateLabel = label;
        updateSelectionAccessibility();
    }

    public void setSelectedState(boolean isSelected, int background, int title) {
        selected = isSelected;
        updateSelectionAccessibility();
        backgroundColor = background;
        titleColor = title;
        titlePaint.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        invalidate();
    }

    public void setShowNew(boolean value) {
        if (showNew == value) return;
        showNew = value;
        updateContentDescription();
        rebuildDisplayText();
        invalidate();
    }

    public void setOnPressListener(@Nullable Runnable listener) {
        pressListener = listener;
    }

    /**
     * Reports that a physical row press can no longer become that row's click.
     * This is deliberately separate from the action-button click callback so a
     * caller can release an ACTION_DOWN launch token immediately on drag/CANCEL.
     */
    public void setOnPressCancelListener(@Nullable Runnable listener) {
        pressCancelListener = listener;
    }

    public void setOnActionClickListener(@Nullable Runnable listener) {
        actionClickListener = listener;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
        info.setContentDescription(getContentDescription());
        info.setSelected(selected);
        AccessibilityNodeInfoCompat.wrap(info).setStateDescription(
                ViewCompat.getStateDescription(this));
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.setClickable(isEnabled());
        if (isEnabled()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    getContext().getString(R.string.episode_open)));
        }
        if (isEnabled() && showAction) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    R.id.accessibility_episode_row_action,
                    getContext().getString(actionLabel(downloadAction))));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle arguments) {
        if (action == R.id.accessibility_episode_row_action) {
            if (!isEnabled() || !showAction || actionClickListener == null) return false;
            actionClickListener.run();
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // ACTION_DOWN atomically reveals an already-staged reader. Keep the episode row on the
        // same source-scoped unbuffered path as the reader surface so ViewRoot never defers that
        // first physical sample to a later display batch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildDisplayText();
    }

    private void rebuildDisplayText() {
        float contentStart = showThumbnail ? dp(106) : dp(26);
        float contentEnd = showAction ? getWidth() - dp(76) : getWidth() - dp(26);
        float maxWidth = Math.max(0f, contentEnd - contentStart - (showNew ? dp(44) : 0f));
        displayTitle = TextUtils.ellipsize(rawTitle, titlePaint, maxWidth, TextUtils.TruncateAt.END);
        displayDate = TextUtils.ellipsize(rawDate, metaPaint,
                Math.max(0f, contentEnd - contentStart), TextUtils.TruncateAt.END);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        rowRect.set(rowInsetX, rowInsetY, width - rowInsetX, height - rowInsetY);
        fillPaint.setColor(backgroundColor);
        canvas.drawRoundRect(rowRect, cornerRadius, cornerRadius, fillPaint);

        float centerY = height * 0.5f;
        float contentStart;
        if (showThumbnail) {
            float thumbLeft = rowInsetX + dp(14);
            thumbRect.set(thumbLeft, centerY - thumbSize * 0.5f, thumbLeft + thumbSize, centerY + thumbSize * 0.5f);
            fillPaint.setColor(thumbnailSurfaceColor);
            canvas.drawRoundRect(thumbRect, dp(8), dp(8), fillPaint);
            accentPaint.setColor(metaColor);
            accentPaint.setAlpha(100);
            canvas.drawCircle(thumbRect.centerX(), thumbRect.centerY(), dp(11), accentPaint);
            accentPaint.setAlpha(255);
            contentStart = dp(106);
        } else {
            contentStart = dp(26);
        }

        titlePaint.setColor(titleColor);
        titlePaint.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        metaPaint.setColor(metaColor);
        canvas.drawText(displayTitle, 0, displayTitle.length(), contentStart, centerY - dp(9), titlePaint);
        canvas.drawText(displayDate, 0, displayDate.length(), contentStart, centerY + dp(18), metaPaint);

        if (showNew) {
            float badgeLeft = Math.min(width - dp(120), contentStart + titlePaint.measureText(displayTitle.toString()) + dp(8));
            RectF badge = actionRect;
            badge.set(badgeLeft, centerY - dp(25), badgeLeft + dp(36), centerY - dp(7));
            accentPaint.setColor(accentColor);
            canvas.drawRoundRect(badge, dp(9), dp(9), accentPaint);
            metaPaint.setColor(0xffffffff);
            metaPaint.setTypeface(Typeface.DEFAULT_BOLD);
            metaPaint.setTextSize(sp(9));
            canvas.drawText("NEW", badgeLeft + dp(7), centerY - dp(12), metaPaint);
            metaPaint.setTypeface(Typeface.DEFAULT);
            metaPaint.setTextSize(metaTextSize);
        }

        if (showAction) {
            float left = width - rowInsetX - dp(60);
            actionRect.set(left, centerY - dp(24), left + dp(48), centerY + dp(24));
            fillPaint.setColor(actionSurfaceColor);
            canvas.drawRoundRect(actionRect, dp(12), dp(12), fillPaint);
            iconPaint.setColor(downloadAction ? accentColor : metaColor);
            float cx = actionRect.centerX();
            float cy = actionRect.centerY();
            if (downloadAction) {
                canvas.drawLine(cx, cy - dp(10), cx, cy + dp(6), iconPaint);
                canvas.drawLine(cx - dp(6), cy, cx, cy + dp(6), iconPaint);
                canvas.drawLine(cx + dp(6), cy, cx, cy + dp(6), iconPaint);
                canvas.drawLine(cx - dp(8), cy + dp(10), cx + dp(8), cy + dp(10), iconPaint);
            } else {
                canvas.drawLine(cx - dp(7), cy - dp(7), cx + dp(7), cy + dp(7), iconPaint);
                canvas.drawLine(cx + dp(7), cy - dp(7), cx - dp(7), cy + dp(7), iconPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                actionPressed = showAction && actionRect.contains(event.getX(), event.getY());
                rowPressCancelled = false;
                pressCancellationSent = false;
                pressDownX = event.getX();
                pressDownY = event.getY();
                if (actionPressed) {
                    setPressed(true);
                    // An action-button gesture must invalidate any row launch token
                    // left by an earlier press before the action callback can run.
                    dispatchPressCancelled();
                } else if (pressListener != null) {
                    pressListener.run();
                    setPressed(true);
                } else {
                    setPressed(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!actionPressed && !rowPressCancelled
                        && exceededTouchSlop(
                        event.getX() - pressDownX,
                        event.getY() - pressDownY,
                        touchSlop)) {
                    rowPressCancelled = true;
                    setPressed(false);
                    dispatchPressCancelled();
                }
                return true;
            case MotionEvent.ACTION_UP:
                boolean insideAction = showAction && actionRect.contains(event.getX(), event.getY());
                setPressed(false);
                if (actionPressed && insideAction && actionClickListener != null) {
                    actionClickListener.run();
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                } else if (!actionPressed && !rowPressCancelled) {
                    performClick();
                }
                actionPressed = false;
                rowPressCancelled = false;
                pressCancellationSent = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!actionPressed && !rowPressCancelled)
                    dispatchPressCancelled();
                actionPressed = false;
                rowPressCancelled = false;
                setPressed(false);
                return true;
            default:
                return true;
        }
    }

    private void dispatchPressCancelled() {
        if (pressCancellationSent)
            return;
        pressCancellationSent = true;
        if (pressCancelListener != null)
            pressCancelListener.run();
    }

    private static boolean exceededTouchSlop(float deltaX, float deltaY, int slop) {
        float threshold = Math.max(0, slop);
        return deltaX * deltaX + deltaY * deltaY > threshold * threshold;
    }

    static boolean exceededTouchSlopForTest(float deltaX, float deltaY, int slop) {
        return exceededTouchSlop(deltaX, deltaY, slop);
    }

    private void updateSelectionAccessibility() {
        setSelected(selected);
        ViewCompat.setStateDescription(this, selected
                ? getContext().getString(selectedStateLabel)
                : null);
    }

    private static String buildSpokenLabel(CharSequence title, CharSequence date, boolean isNew,
                                           CharSequence newStateLabel) {
        String safeTitle = title == null ? "" : title.toString().trim();
        String safeDate = date == null ? "" : date.toString().trim();
        StringBuilder label = new StringBuilder(safeTitle);
        if (safeDate.length() > 0) {
            if (label.length() > 0) label.append(", ");
            label.append(safeDate);
        }
        if (isNew) {
            if (label.length() > 0) label.append(", ");
            label.append(newStateLabel);
        }
        return label.toString();
    }

    @StringRes
    private static int actionLabel(boolean isDownloadAction) {
        return isDownloadAction ? R.string.download_episode : R.string.episode_remove_download;
    }

    static String spokenLabelForTest(CharSequence title, CharSequence date, boolean isNew) {
        return buildSpokenLabel(title, date, isNew, "새 회차");
    }

    static int actionLabelForTest(boolean isDownloadAction) {
        return actionLabel(isDownloadAction);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
