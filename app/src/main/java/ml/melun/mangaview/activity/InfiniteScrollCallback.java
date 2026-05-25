package ml.melun.mangaview.activity;

import ml.melun.mangaview.mangaview.Manga;

public interface InfiniteScrollCallback {
    Manga nextEp(InfiniteLoadCallback callback, Manga curm);
    Manga prevEp(InfiniteLoadCallback callback, Manga curm);
    void updateInfo(Manga m);
}

