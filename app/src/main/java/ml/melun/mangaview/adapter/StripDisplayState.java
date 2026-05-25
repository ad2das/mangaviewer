package ml.melun.mangaview.adapter;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import ml.melun.mangaview.model.PageItem;

final class StripDisplayState {
    private final int trackLimit;
    private final Set<String> displayedImages = new LinkedHashSet<>();
    private boolean firstVisibleLogged = false;
    private boolean nextPageCacheHitLogged = false;

    StripDisplayState(int trackLimit) {
        this.trackLimit = Math.max(1, trackLimit);
    }

    boolean markDisplayedAndShouldLogFirstVisible(String pageKey) {
        if(pageKey != null && pageKey.length() > 0) {
            displayedImages.add(pageKey);
            trimDisplayedTracker();
        }
        if(!shouldLogFirstVisible(firstVisibleLogged))
            return false;
        firstVisibleLogged = true;
        return true;
    }

    boolean containsDisplayed(String pageKey) {
        return pageKey != null && displayedImages.contains(pageKey);
    }

    void retainDisplayed(Set<String> activePageKeys) {
        displayedImages.retainAll(activePageKeys);
    }

    boolean markNextPageCacheHit(PageItem item) {
        if(item == null || item.index <= 0 || nextPageCacheHitLogged)
            return false;
        nextPageCacheHitLogged = true;
        return true;
    }

    static boolean shouldLogFirstVisible(boolean alreadyLogged) {
        return !alreadyLogged;
    }

    private void trimDisplayedTracker() {
        while(displayedImages.size() > trackLimit) {
            Iterator<String> iterator = displayedImages.iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }
}
