package ml.melun.mangaview.adapter;

import android.view.View;
import android.view.ViewGroup;

final class StripPageHeightApplier {
    private StripPageHeightApplier() {
    }

    static void apply(StripImageViewHolder holder, int targetHeight) {
        if(holder == null || holder.frame == null)
            return;
        applyHeight(holder.itemView, targetHeight, false);
        applyHeight(holder.frame, targetHeight == ViewGroup.LayoutParams.WRAP_CONTENT
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : ViewGroup.LayoutParams.MATCH_PARENT, true);
        int minHeight = targetHeight == ViewGroup.LayoutParams.WRAP_CONTENT ? 0 : targetHeight;
        holder.itemView.setMinimumHeight(minHeight);
        holder.frame.setMinimumHeight(minHeight);
        holder.appliedItemHeight = targetHeight;
    }

    private static void applyHeight(View view, int height, boolean matchWidth) {
        if(view == null)
            return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if(params == null)
            return;
        int targetWidth = matchWidth ? ViewGroup.LayoutParams.MATCH_PARENT : params.width;
        if(params.height != height || params.width != targetWidth) {
            params.width = targetWidth;
            params.height = height;
            view.setLayoutParams(params);
        }
    }
}
