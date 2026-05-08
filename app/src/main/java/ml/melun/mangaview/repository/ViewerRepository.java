package ml.melun.mangaview.repository;

import android.content.Context;

import java.util.List;

import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.p;

public final class ViewerRepository {
    private ViewerRepository() {
    }

    public static int ensureImagesLoaded(Manga manga, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        return MangaRepository.fetchViewerInitial(manga, requestGroup);
    }

    public static int ensureEpisodesLoaded(Title title) {
        return MangaRepository.fetchEpisodes(title);
    }

    public static List<String> images(Manga manga, Context context) {
        return MangaRepository.imageUrls(manga, context);
    }

    public static int viewerBookmark(Manga manga) {
        return p.getViewerBookmark(manga);
    }

    public static int viewerBookmarkOffset(Manga manga) {
        return p.getViewerBookmarkOffset(manga);
    }

    public static void saveViewerBookmark(Manga manga, int page, int offset) {
        p.setViewerBookmark(manga, page, offset);
    }

    public static void saveTitleBookmark(Title title, int episodeId) {
        p.setBookmark(title, episodeId);
    }
}
