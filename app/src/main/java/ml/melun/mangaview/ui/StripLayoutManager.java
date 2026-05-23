package ml.melun.mangaview.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ml.melun.mangaview.adapter.StripAdapter;
import ml.melun.mangaview.model.PageItem;

public class StripLayoutManager extends NpaLinearLayoutManager {
    private static final int EXTRA_LAYOUT_AHEAD_SCREENS = 1;
    private static final int EXTRA_LAYOUT_BEHIND_SCREENS = 0;
    private static final int EXTRA_LAYOUT_IDLE_SCREENS = 1;
    private static final int EXTRA_LAYOUT_BUSY_AHEAD_SCREENS = 1;
    private static final int EXTRA_LAYOUT_BUSY_BEHIND_SCREENS = 0;
    StripAdapter adapter;
    private final int extraLayoutAheadPx;
    private final int extraLayoutBehindPx;
    private final int extraLayoutIdlePx;
    private final int extraLayoutBusyAheadPx;
    private final int extraLayoutBusyBehindPx;
    private int scrollDirection = 1;
    private boolean scrollBusy = false;

    public StripLayoutManager(Context context) {
        super(context);
        extraLayoutAheadPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_AHEAD_SCREENS);
        extraLayoutBehindPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BEHIND_SCREENS);
        extraLayoutIdlePx = extraLayoutSpacePx(context, EXTRA_LAYOUT_IDLE_SCREENS);
        extraLayoutBusyAheadPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BUSY_AHEAD_SCREENS);
        extraLayoutBusyBehindPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BUSY_BEHIND_SCREENS);
    }

    public StripLayoutManager(Context context, int orientation, boolean reverseLayout) {
        super(context, orientation, reverseLayout);
        extraLayoutAheadPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_AHEAD_SCREENS);
        extraLayoutBehindPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BEHIND_SCREENS);
        extraLayoutIdlePx = extraLayoutSpacePx(context, EXTRA_LAYOUT_IDLE_SCREENS);
        extraLayoutBusyAheadPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BUSY_AHEAD_SCREENS);
        extraLayoutBusyBehindPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BUSY_BEHIND_SCREENS);
    }

    public StripLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        extraLayoutAheadPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_AHEAD_SCREENS);
        extraLayoutBehindPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BEHIND_SCREENS);
        extraLayoutIdlePx = extraLayoutSpacePx(context, EXTRA_LAYOUT_IDLE_SCREENS);
        extraLayoutBusyAheadPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BUSY_AHEAD_SCREENS);
        extraLayoutBusyBehindPx = extraLayoutSpacePx(context, EXTRA_LAYOUT_BUSY_BEHIND_SCREENS);
    }


    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        adapter = (StripAdapter) view.getAdapter();
    }

    @Override
    public void onAdapterChanged(@Nullable RecyclerView.Adapter oldAdapter, @Nullable RecyclerView.Adapter newAdapter) {
        super.onAdapterChanged(oldAdapter, newAdapter);
        adapter = (StripAdapter) newAdapter;
    }

    @Override
    protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
        super.calculateExtraLayoutSpace(state, extraLayoutSpace);
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

    private static int extraLayoutSpacePx(Context context, int screens) {
        if(context == null || context.getResources() == null)
            return 0;
        return context.getResources().getDisplayMetrics().heightPixels * Math.max(0, screens);
    }
    
    public void scrollToPage(PageItem page){
        scrollToPageWithOffset(page, 0);
    }

    public void scrollToPageWithOffset(PageItem page, int offset){
        if(adapter == null || page == null)
            return;
        List<Object> items = adapter.getItems();
        if(items == null)
            return;
        for(int i=0; i<items.size(); i++){
            Object item = items.get(i);
            if(item instanceof PageItem){
                if(isSamePage((PageItem)item, page)) {
                    scrollToPositionWithOffset(i, offset);
                    return;
                }
            }
        }
    }

    private boolean isSamePage(PageItem a, PageItem b) {
        if(a == null || b == null || a.manga == null || b.manga == null)
            return false;
        return a.index == b.index
                && a.side == b.side
                && a.manga.getId() == b.manga.getId()
                && a.manga.getTitleId() == b.manga.getTitleId()
                && a.manga.getBaseMode() == b.manga.getBaseMode();
    }



}
