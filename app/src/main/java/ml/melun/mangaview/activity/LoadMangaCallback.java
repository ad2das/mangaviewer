package ml.melun.mangaview.activity;

import ml.melun.mangaview.mangaview.Manga;

public interface LoadMangaCallback {
    void post(Manga m);
}

