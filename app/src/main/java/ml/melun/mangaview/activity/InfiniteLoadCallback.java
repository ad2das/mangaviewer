package ml.melun.mangaview.activity;

import ml.melun.mangaview.mangaview.Manga;

public interface InfiniteLoadCallback {
    void prevLoaded(Manga m);
    void nextLoaded(Manga m);
}

