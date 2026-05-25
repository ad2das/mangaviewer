package ml.melun.mangaview.activity;

import ml.melun.mangaview.mangaview.Manga;

final class PreparedManga {
    final Manga manga;
    final int result;

    PreparedManga(Manga manga, int result) {
        this.manga = manga;
        this.result = result;
    }
}

