package ml.melun.mangaview.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

public class ViewerRecyclerView extends RecyclerView {
    private static final int MAX_VIEWER_FLING_DP_PER_SECOND = 3200;

    public ViewerRecyclerView(@NonNull Context context) {
        super(context);
    }

    public ViewerRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ViewerRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        int maxVelocity = maxViewerFlingVelocity(getResources().getDisplayMetrics().density);
        return super.fling(
                capVelocity(velocityX, maxVelocity),
                capVelocity(velocityY, maxVelocity));
    }

    static int capVelocityForTest(int velocity, int maxVelocity) {
        return capVelocity(velocity, maxVelocity);
    }

    static int maxViewerFlingVelocityForTest(float density) {
        return maxViewerFlingVelocity(density);
    }

    private static int maxViewerFlingVelocity(float density) {
        return Math.max(1, Math.round(MAX_VIEWER_FLING_DP_PER_SECOND * Math.max(1f, density)));
    }

    private static int capVelocity(int velocity, int maxVelocity) {
        int cap = Math.max(0, maxVelocity);
        if(cap == 0)
            return 0;
        if(velocity > cap)
            return cap;
        if(velocity < -cap)
            return -cap;
        return velocity;
    }
}
