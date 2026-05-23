package ml.melun.mangaview.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

public class CappedFlingRecyclerView extends RecyclerView {
    private static final int MAX_FLING_VELOCITY = 6500;

    public CappedFlingRecyclerView(@NonNull Context context) {
        super(context);
    }

    public CappedFlingRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CappedFlingRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        return super.fling(clampFlingVelocity(velocityX), clampFlingVelocity(velocityY));
    }

    public static int clampFlingVelocityForTest(int velocity) {
        return clampFlingVelocity(velocity);
    }

    private static int clampFlingVelocity(int velocity) {
        if(velocity > MAX_FLING_VELOCITY)
            return MAX_FLING_VELOCITY;
        if(velocity < -MAX_FLING_VELOCITY)
            return -MAX_FLING_VELOCITY;
        return velocity;
    }
}
