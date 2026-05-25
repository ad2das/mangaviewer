package ml.melun.mangaview.adapter;

import ml.melun.mangaview.mangaview.Manga;

public final class StripInitialLoadCoordinator {
    private StripInitialLoadCoordinator() {
    }

    public static void load(StripAdapterSession session, Manga manga) {
        if(session != null && manga != null)
            session.appendManga(manga);
    }
}
