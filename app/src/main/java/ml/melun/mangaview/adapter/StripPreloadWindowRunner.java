package ml.melun.mangaview.adapter;

import java.util.List;

import com.bumptech.glide.Priority;

import ml.melun.mangaview.glide.ViewerPreloadPolicy;
import ml.melun.mangaview.model.PageItem;

final class StripPreloadWindowRunner {
    interface Delegate {
        boolean canStart();
        long generation();
        void preloadDecoded(PageItem page, Priority priority, long generation, boolean preview);
        void preloadSource(PageItem page, Priority priority);
    }

    private final Delegate delegate;

    StripPreloadWindowRunner(Delegate delegate) {
        this.delegate = delegate;
    }

    void preloadDirectionalWindow(List<Object> items, int adapterPosition, int direction,
                                  ViewerPreloadPolicy.Window window, long generation,
                                  boolean stopOnGenerationChange) {
        if(items == null || window == null || direction == 0 || !delegate.canStart())
            return;
        int preloaded = 0;
        int position = adapterPosition;
        while(position >= 0 && position < items.size() && preloaded < window.totalLimit) {
            if(stopOnGenerationChange && generation != delegate.generation())
                return;
            Object next = items.get(position);
            if(next instanceof PageItem) {
                int tier = ViewerPreloadPolicy.tierForOffset(window, preloaded);
                if(tier == ViewerPreloadPolicy.TIER_DECODED)
                    delegate.preloadDecoded((PageItem) next,
                            StripPreloadWindowPolicy.priorityForTier(tier), generation, true);
                else
                    delegate.preloadSource((PageItem) next, StripPreloadWindowPolicy.priorityForTier(tier));
                preloaded++;
            }
            position += direction;
        }
    }
}
