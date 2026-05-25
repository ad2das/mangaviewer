package ml.melun.mangaview.adapter;

import java.util.List;

import ml.melun.mangaview.model.PageItem;

final class StripViewTypePolicy {
    private StripViewTypePolicy() {
    }

    static int viewType(List<Object> items, int position) {
        if(items == null || position < 0 || position >= items.size())
            return StripAdapter.INFO;
        Object item = items.get(position);
        if(item instanceof PageItem)
            return StripAdapter.IMG;
        if(item instanceof InfoItem)
            return StripAdapter.INFO;
        return -1;
    }
}
