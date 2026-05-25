package ml.melun.mangaview.adapter;

import java.util.List;

import ml.melun.mangaview.model.PageItem;

final class StripCurrentPageState {
    private PageItem current;
    private int currentMangaId = -1;
    private boolean needUpdate = true;

    PageItem currentVisiblePage(List<Object> items) {
        if(containsCurrentPage(items))
            return current;
        clear();
        return current;
    }

    boolean attachPage(PageItem page) {
        current = page;
        if(page == null || page.manga == null)
            return false;
        if(!needUpdate && currentMangaId == page.manga.getId())
            return false;
        needUpdate = false;
        currentMangaId = page.manga.getId();
        return true;
    }

    void attachInfo() {
        needUpdate = true;
    }

    void clearIfRemoving(List<Object> items, int start, int endExclusive) {
        if(current == null || items == null)
            return;
        int end = Math.min(endExclusive, items.size());
        for(int i = Math.max(0, start); i < end; i++) {
            if(items.get(i) == current) {
                clear();
                return;
            }
        }
    }

    void clear() {
        current = null;
        currentMangaId = -1;
        needUpdate = true;
    }

    private boolean containsCurrentPage(List<Object> items) {
        if(current == null || items == null)
            return false;
        for(Object item : items) {
            if(item == current)
                return true;
        }
        return false;
    }
}
