package ml.melun.mangaview.adapter;

import android.content.Context;
import android.os.Handler;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.model.PageItem;
import ml.melun.mangaview.repository.MangaRepository;

final class StripItemListMutator {
    interface Callbacks {
        boolean autoCut();
        List<Object> items();
        void setItems(List<Object> items);
        void setCount(int count);
        void seedPageHeightFromWarmup(PageItem item);
        void clearCurrentIfRemoving(int start, int endExclusive);
        void clearCurrentPage();
        void trimReusablePageStateToLoadedItems();
        void clearReusablePageState();
        void notifyItemRangeInserted(int start, int count);
        void notifyItemRangeRemoved(int start, int count);
        void notifyItemChanged(int position);
    }

    private final Context context;
    private final Handler mainHandler;
    private final Callbacks callbacks;
    private final int maxStackSize;
    private final int appendBatchItems;
    private final long appendBatchDelayMs;

    StripItemListMutator(Context context, Handler mainHandler, Callbacks callbacks,
                         int maxStackSize, int appendBatchItems, long appendBatchDelayMs) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.callbacks = callbacks;
        this.maxStackSize = maxStackSize;
        this.appendBatchItems = appendBatchItems;
        this.appendBatchDelayMs = appendBatchDelayMs;
    }

    void appendManga(Manga manga) {
        List<Object> items = ensureItems();
        if(hasMangaLoaded(manga))
            return;
        int previousSize = items.size();
        List<String> images = MangaRepository.imageUrls(manga, context);
        if(images == null || images.size() == 0)
            return;
        List<Object> pending = StripMangaItemBuilder.appendItems(manga, images, callbacks.autoCut(),
                items.size() == 0, callbacks::seedPageHeightFromWarmup);
        items.addAll(pending);
        callbacks.notifyItemRangeInserted(previousSize, items.size() - previousSize);
        updateCount();
        trimFirstIfNeeded();
    }

    void appendMangaIncremental(Manga manga) {
        List<Object> items = ensureItems();
        if(hasMangaLoaded(manga))
            return;
        List<String> images = MangaRepository.imageUrls(manga, context);
        if(images == null || images.size() == 0)
            return;
        ArrayList<Object> pending = StripMangaItemBuilder.appendItems(manga, images, callbacks.autoCut(),
                items.size() == 0, callbacks::seedPageHeightFromWarmup);
        appendMangaBatch(pending, 0);
    }

    int insertManga(Manga manga) {
        List<Object> items = callbacks.items();
        if(items == null || items.size() == 0) {
            appendManga(manga);
            return 0;
        }
        if(hasMangaLoaded(manga))
            return 0;
        int previousSize = items.size();
        List<String> images = MangaRepository.imageUrls(manga, context);
        if(images == null || images.size() == 0)
            return 0;
        List<Object> pending = StripMangaItemBuilder.prependItems(manga, images, callbacks.autoCut(),
                callbacks::seedPageHeightFromWarmup);
        items.addAll(0, pending);

        int inserted = items.size() - previousSize;
        callbacks.notifyItemRangeInserted(0, inserted);
        updateCount();
        trimLastIfNeeded();
        return inserted;
    }

    void refreshInfoItems() {
        List<Object> items = callbacks.items();
        if(items == null)
            return;
        for(int i = 0; i < items.size(); i++) {
            if(items.get(i) instanceof InfoItem)
                callbacks.notifyItemChanged(i);
        }
    }

    int findLastPagePosition(Manga manga) {
        List<Object> items = callbacks.items();
        if(manga == null || items == null)
            return RecyclerView.NO_POSITION;
        for(int i = items.size() - 1; i >= 0; i--) {
            Object item = items.get(i);
            if(item instanceof PageItem && sameManga(((PageItem)item).manga, manga))
                return i;
        }
        return RecyclerView.NO_POSITION;
    }

    boolean hasMangaLoaded(Manga manga) {
        return findFirstPagePosition(manga) != RecyclerView.NO_POSITION;
    }

    int findFirstPagePosition(Manga manga) {
        List<Object> items = callbacks.items();
        if(manga == null || items == null)
            return RecyclerView.NO_POSITION;
        for(int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if(item instanceof PageItem && sameManga(((PageItem)item).manga, manga))
                return i;
        }
        return RecyclerView.NO_POSITION;
    }

    int findPagePosition(PageItem page) {
        int position = findFirstMatchingPagePosition(page);
        if(position != RecyclerView.NO_POSITION)
            return position;
        return page == null ? RecyclerView.NO_POSITION : findFirstPagePosition(page.manga);
    }

    int findExactPagePosition(PageItem page) {
        return findFirstMatchingPagePosition(page);
    }

    int findFirstMatchingPagePosition(PageItem page) {
        List<Object> items = callbacks.items();
        if(page == null || items == null)
            return RecyclerView.NO_POSITION;
        for(int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if(item instanceof PageItem) {
                PageItem other = (PageItem) item;
                if(sameManga(other.manga, page.manga)
                        && other.index == page.index
                        && other.side == page.side
                        && (page.img == null || page.img.length() == 0 || page.img.equals(other.img)))
                    return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    void popFirst() {
        List<Object> items = callbacks.items();
        if(items == null)
            return;
        int size = 0;
        for(int i = 1; i < items.size(); i++) {
            if(items.get(i) instanceof InfoItem) {
                size = i;
                break;
            }
        }
        if(size > 0) {
            callbacks.clearCurrentIfRemoving(0, size);
            items.subList(0, size).clear();
            updateCount();
            callbacks.trimReusablePageStateToLoadedItems();
            callbacks.notifyItemRangeRemoved(0, size);
        }
    }

    void popLast() {
        List<Object> items = callbacks.items();
        if(items == null)
            return;
        int originalSize = items.size();
        int reverseSize = -1;
        for(int i = originalSize - 2; i >= 0; i--) {
            if(items.get(i) instanceof InfoItem) {
                reverseSize = i;
                break;
            }
        }
        if(reverseSize >= 0 && originalSize > reverseSize + 1) {
            int removeStart = reverseSize + 1;
            int removeCount = originalSize - removeStart;
            callbacks.clearCurrentIfRemoving(removeStart, originalSize);
            items.subList(removeStart, originalSize).clear();
            updateCount();
            callbacks.trimReusablePageStateToLoadedItems();
            callbacks.notifyItemRangeRemoved(removeStart, removeCount);
        }
    }

    PageItem getPageAtPosition(int position) {
        List<Object> items = callbacks.items();
        if(items == null || position < 0 || position >= items.size())
            return null;
        Object item = items.get(position);
        return item instanceof PageItem ? (PageItem)item : null;
    }

    void removeAll() {
        List<Object> items = callbacks.items();
        if(items == null || items.size() == 0)
            return;
        int size = items.size();
        items.clear();
        callbacks.clearReusablePageState();
        callbacks.clearCurrentPage();
        callbacks.setCount(0);
        callbacks.notifyItemRangeRemoved(0, size);
    }

    private void appendMangaBatch(List<Object> pending, int offset) {
        List<Object> items = callbacks.items();
        if(pending == null || pending.size() == 0 || items == null)
            return;
        if(offset >= pending.size()) {
            updateCount();
            trimFirstIfNeeded();
            return;
        }
        int start = items.size();
        int end = Math.min(pending.size(), offset + appendBatchItems);
        for(int i = offset; i < end; i++)
            items.add(pending.get(i));
        callbacks.notifyItemRangeInserted(start, end - offset);
        if(end < pending.size())
            mainHandler.postDelayed(() -> appendMangaBatch(pending, end), appendBatchDelayMs);
        else {
            updateCount();
            trimFirstIfNeeded();
        }
    }

    private List<Object> ensureItems() {
        List<Object> items = callbacks.items();
        if(items == null) {
            items = new ArrayList<>();
            callbacks.setItems(items);
        }
        return items;
    }

    private void trimFirstIfNeeded() {
        if(loadedEpisodeCount() > maxStackSize)
            popFirst();
    }

    private void trimLastIfNeeded() {
        if(loadedEpisodeCount() > maxStackSize)
            popLast();
    }

    private void updateCount() {
        callbacks.setCount(loadedEpisodeCount());
    }

    private int loadedEpisodeCount() {
        List<Object> items = callbacks.items();
        if(items == null)
            return 0;
        Set<String> loaded = new LinkedHashSet<>();
        for(Object item : items) {
            if(item instanceof PageItem) {
                Manga manga = ((PageItem) item).manga;
                if(manga != null)
                    loaded.add(PageItem.episodeKey(manga));
            }
        }
        return loaded.size();
    }

    private boolean sameManga(Manga first, Manga second) {
        return Manga.sameEpisodeIdentity(first, second) || sameImageList(first, second);
    }

    private boolean sameImageList(Manga firstManga, Manga secondManga) {
        if(firstManga == null || secondManga == null || firstManga == secondManga)
            return firstManga == secondManga;
        List<String> first = MangaRepository.imageUrls(firstManga, context);
        List<String> second = MangaRepository.imageUrls(secondManga, context);
        return first != null && second != null && !first.isEmpty() && first.equals(second);
    }
}
