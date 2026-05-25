package ml.melun.mangaview.adapter;

import androidx.recyclerview.widget.RecyclerView;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.model.PageItem;

final class StripStableIdPolicy {
    private StripStableIdPolicy() {
    }

    static long itemId(Object item) {
        if(item instanceof PageItem)
            return pageStableId((PageItem) item);
        if(item instanceof InfoItem)
            return infoStableId((InfoItem) item);
        return RecyclerView.NO_ID;
    }

    private static long pageStableId(PageItem item) {
        long episode = episodeStableId(item.manga);
        long image = item.img == null ? 0L : item.img.hashCode();
        return (episode * 1000003L) ^ (((long)item.index) << 17) ^ (((long)item.side) << 1) ^ image;
    }

    private static long infoStableId(InfoItem item) {
        return Long.MIN_VALUE
                ^ (episodeStableId(item.prev) * 1000003L)
                ^ episodeStableId(item.next);
    }

    private static long episodeStableId(Manga manga) {
        if(manga == null)
            return 0L;
        return (((long)manga.getBaseMode()) << 48)
                ^ (((long)manga.getTitleId() & 0xffffL) << 32)
                ^ (manga.getId() & 0xffffffffL);
    }
}
