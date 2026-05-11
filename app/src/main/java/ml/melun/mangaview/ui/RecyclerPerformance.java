package ml.melun.mangaview.ui;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import android.view.View;

import com.bumptech.glide.Glide;

public final class RecyclerPerformance {
    private RecyclerPerformance() {
    }

    public static void tune(RecyclerView recyclerView, int cacheSize) {
        if(recyclerView == null)
            return;
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(Math.max(2, cacheSize));
        recyclerView.setItemAnimator(null);
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if(recyclerView.getItemAnimator() instanceof SimpleItemAnimator)
            ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
        if(manager instanceof LinearLayoutManager)
            ((LinearLayoutManager) manager).setInitialPrefetchItemCount(Math.max(2, Math.min(cacheSize, 8)));
    }

    public static void bindImageRequestPausing(RecyclerView recyclerView) {
        if(recyclerView == null)
            return;
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                RecyclerView.Adapter adapter = rv.getAdapter();
                if(adapter instanceof SmoothScrollAdapter)
                    ((SmoothScrollAdapter) adapter).setScrollBusy(newState != RecyclerView.SCROLL_STATE_IDLE);
                try {
                    if(newState == RecyclerView.SCROLL_STATE_IDLE)
                        Glide.with(rv).resumeRequests();
                    else
                        Glide.with(rv).pauseRequests();
                } catch (RuntimeException ignored) {
                }
                if(newState == RecyclerView.SCROLL_STATE_IDLE && adapter instanceof SmoothScrollAdapter)
                    ((SmoothScrollAdapter) adapter).onScrollIdle(rv);
            }
        });
    }

    public static void refreshVisibleRange(RecyclerView recyclerView, RecyclerView.Adapter adapter, int ahead) {
        if(recyclerView == null || adapter == null || adapter.getItemCount() == 0)
            return;
        RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        if(first == RecyclerView.NO_POSITION)
            return;
        int start = Math.max(0, first);
        int end = Math.min(adapter.getItemCount() - 1, Math.max(last, first) + Math.max(0, ahead));
        adapter.notifyItemRangeChanged(start, end - start + 1, "scroll_idle");
    }
}
