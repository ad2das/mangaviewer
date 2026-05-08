package ml.melun.mangaview.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

public class StableScrollbarRecyclerView extends RecyclerView {
    private int virtualItemCount = RecyclerView.NO_POSITION;

    public StableScrollbarRecyclerView(@NonNull Context context) {
        super(context);
    }

    public StableScrollbarRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public StableScrollbarRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setVirtualItemCount(int count) {
        int next = count > 0 ? count : RecyclerView.NO_POSITION;
        if(virtualItemCount == next)
            return;
        virtualItemCount = next;
        invalidate();
    }

    @Override
    public int computeVerticalScrollRange() {
        int baseRange = super.computeVerticalScrollRange();
        Adapter adapter = getAdapter();
        int loadedCount = adapter == null ? 0 : adapter.getItemCount();
        if(baseRange <= 0 || loadedCount <= 0 || virtualItemCount <= loadedCount)
            return baseRange;
        float averageItemRange = baseRange / (float) loadedCount;
        return Math.max(baseRange, Math.round(averageItemRange * virtualItemCount));
    }
}
