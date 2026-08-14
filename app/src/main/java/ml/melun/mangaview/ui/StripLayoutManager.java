package ml.melun.mangaview.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import ml.melun.mangaview.adapter.StripAdapter;
import ml.melun.mangaview.model.PageItem;

public class StripLayoutManager extends NpaLinearLayoutManager {
    private static final int EXTRA_LAYOUT_AHEAD_SCREENS = 1;
    private static final int EXTRA_LAYOUT_BEHIND_SCREENS = 0;
    private static final int EXTRA_LAYOUT_IDLE_SCREENS = 1;
    private static final int EXTRA_LAYOUT_BUSY_AHEAD_SCREENS = 1;
    private static final int EXTRA_LAYOUT_BUSY_BEHIND_SCREENS = 0;
    StripAdapter adapter;
    private final Context context;
    private RecyclerView attachedRecyclerView;
    private int scrollDirection = 1;
    private boolean scrollBusy = false;

    public StripLayoutManager(Context context) {
        super(context);
        this.context = context;
    }

    public StripLayoutManager(Context context, int orientation, boolean reverseLayout) {
        super(context, orientation, reverseLayout);
        this.context = context;
    }

    public StripLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.context = context;
    }


    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        attachedRecyclerView = view;
        adapter = (StripAdapter) view.getAdapter();
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, RecyclerView.Recycler recycler) {
        attachedRecyclerView = null;
        adapter = null;
        super.onDetachedFromWindow(view, recycler);
    }

    @Override
    public void onAdapterChanged(@Nullable RecyclerView.Adapter oldAdapter, @Nullable RecyclerView.Adapter newAdapter) {
        super.onAdapterChanged(oldAdapter, newAdapter);
        adapter = (StripAdapter) newAdapter;
    }

    @Override
    protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
        super.calculateExtraLayoutSpace(state, extraLayoutSpace);
        int measuredHeight = attachedRecyclerView != null ? attachedRecyclerView.getHeight() : 0;
        int fallbackHeight = context != null && context.getResources() != null
                ? context.getResources().getDisplayMetrics().heightPixels
                : 0;
        int extraLayoutAheadPx = extraLayoutSpacePx(
                measuredHeight, fallbackHeight, EXTRA_LAYOUT_AHEAD_SCREENS);
        int extraLayoutBehindPx = extraLayoutSpacePx(
                measuredHeight, fallbackHeight, EXTRA_LAYOUT_BEHIND_SCREENS);
        int extraLayoutIdlePx = extraLayoutSpacePx(
                measuredHeight, fallbackHeight, EXTRA_LAYOUT_IDLE_SCREENS);
        int extraLayoutBusyAheadPx = extraLayoutSpacePx(
                measuredHeight, fallbackHeight, EXTRA_LAYOUT_BUSY_AHEAD_SCREENS);
        int extraLayoutBusyBehindPx = extraLayoutSpacePx(
                measuredHeight, fallbackHeight, EXTRA_LAYOUT_BUSY_BEHIND_SCREENS);
        int aheadPx = scrollBusy ? extraLayoutBusyAheadPx : extraLayoutAheadPx;
        int behindPx = scrollBusy ? extraLayoutBusyBehindPx : extraLayoutBehindPx;
        if(scrollDirection > 0) {
            extraLayoutSpace[0] = Math.max(extraLayoutSpace[0], behindPx);
            extraLayoutSpace[1] = Math.max(extraLayoutSpace[1], aheadPx);
        } else if(scrollDirection < 0) {
            extraLayoutSpace[0] = Math.max(extraLayoutSpace[0], aheadPx);
            extraLayoutSpace[1] = Math.max(extraLayoutSpace[1], behindPx);
        } else {
            extraLayoutSpace[0] = Math.max(extraLayoutSpace[0], extraLayoutIdlePx);
            extraLayoutSpace[1] = Math.max(extraLayoutSpace[1], extraLayoutIdlePx);
        }
    }

    public void setScrollDirection(int dy) {
        if(dy > 0)
            scrollDirection = 1;
        else if(dy < 0)
            scrollDirection = -1;
        else
            scrollDirection = 0;
    }

    public void setScrollBusy(boolean busy) {
        scrollBusy = busy;
    }

    static int extraLayoutSpacePx(int measuredHeightPx, int fallbackHeightPx, int screens) {
        int viewportHeight = measuredHeightPx > 0 ? measuredHeightPx : Math.max(0, fallbackHeightPx);
        return viewportHeight * Math.max(0, screens);
    }
    
    public void scrollToPage(PageItem page){
        scrollToPageWithOffset(page, 0);
    }

    public void scrollToPageWithOffset(PageItem page, int offset){
        if(adapter == null || page == null)
            return;
        int position = adapter.findExactPagePosition(page);
        if(position != RecyclerView.NO_POSITION)
            scrollToPositionWithOffset(position, offset);
    }



}
