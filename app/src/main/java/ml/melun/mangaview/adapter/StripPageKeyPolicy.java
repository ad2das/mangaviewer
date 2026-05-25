package ml.melun.mangaview.adapter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ml.melun.mangaview.model.PageItem;

final class StripPageKeyPolicy {
    private StripPageKeyPolicy() {
    }

    static String preloadKey(PageItem page, boolean autoCut, boolean reverse, int width) {
        return bindKey(page, autoCut, reverse, width);
    }

    static String decodedCacheKey(PageItem page, boolean autoCut, boolean reverse, int width) {
        return bindKey(page, autoCut, reverse, width);
    }

    static String decodedPreviewCacheKey(PageItem page, boolean autoCut, boolean reverse, int previewWidth) {
        if(page == null || page.manga == null || !isUsableImageUrl(page.img))
            return "";
        return page.pageKey(autoCut, reverse, previewWidth);
    }

    static String bindKey(PageItem page, boolean autoCut, boolean reverse, int width) {
        if(page == null || page.manga == null || !isUsableImageUrl(page.img))
            return "";
        return page.pageKey(autoCut, reverse, width);
    }

    static Set<String> activePageKeys(List<Object> items, boolean autoCut, boolean reverse, int width) {
        Set<String> active = new LinkedHashSet<>();
        if(items == null)
            return active;
        for(Object item : items) {
            if(item instanceof PageItem) {
                String key = bindKey((PageItem) item, autoCut, reverse, width);
                if(key.length() > 0)
                    active.add(key);
            }
        }
        return active;
    }

    static Set<String> activeEpisodeKeys(List<Object> items) {
        Set<String> active = new LinkedHashSet<>();
        if(items == null)
            return active;
        for(Object item : items) {
            if(item instanceof PageItem) {
                String key = PageItem.episodeKey(((PageItem) item).manga);
                if(key.length() > 0)
                    active.add(key);
            }
        }
        return active;
    }

    static Set<String> activePreloadKeys(Set<String> pageKeys) {
        Set<String> preloadKeys = new LinkedHashSet<>();
        if(pageKeys == null)
            return preloadKeys;
        for(String pageKey : pageKeys)
            addPreloadKeys(preloadKeys, pageKey);
        return preloadKeys;
    }

    static Set<String> activePreloadKeys(List<Object> items, boolean autoCut, boolean reverse,
                                         int width, int previewWidth) {
        Set<String> preloadKeys = new LinkedHashSet<>();
        if(items == null)
            return preloadKeys;
        for(Object item : items) {
            if(!(item instanceof PageItem))
                continue;
            PageItem page = (PageItem) item;
            addPreloadKeys(preloadKeys, bindKey(page, autoCut, reverse, width));
            addPreloadKeys(preloadKeys, decodedPreviewCacheKey(page, autoCut, reverse, previewWidth));
        }
        return preloadKeys;
    }

    static void addPreloadKeys(Set<String> preloadKeys, String pageKey) {
        if(preloadKeys == null || pageKey == null || pageKey.length() == 0)
            return;
        preloadKeys.add(pageKey);
        preloadKeys.add(decodedPreloadRequestKey(pageKey));
        preloadKeys.add(sourcePreloadRequestKey(pageKey));
    }

    static String decodedPreloadRequestKey(String pageKey) {
        return "decoded:" + pageKey;
    }

    static String sourcePreloadRequestKey(String pageKey) {
        return "source:" + pageKey;
    }

    static boolean isUsableImageUrl(String image) {
        return image != null && image.trim().length() > 0;
    }
}
