package ml.melun.mangaview.repository;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Bookmark;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MainPage;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Ranking;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedList;
import ml.melun.mangaview.mangaview.UpdatedManga;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;

public final class MangaRepository {
    private MangaRepository() {
    }

    public static MainPage loadComicHome(CustomHttpClient.RequestGroup requestGroup) throws Exception {
        return getHttpClient().runWithRequestGroup(requestGroup, () -> new MainPage(getHttpClient()));
    }

    public static int search(Search search, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null)
            return search.fetch(getHttpClient());
        return getHttpClient().runWithRequestGroup(requestGroup, () -> search.fetch(getHttpClient()));
    }

    public static int fetchBookmark(Bookmark bookmark, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null)
            return bookmark.fetch(getHttpClient());
        return getHttpClient().runWithRequestGroup(requestGroup, () -> bookmark.fetch(getHttpClient()));
    }

    public static ArrayList<UpdatedManga> loadUpdates(UpdatedList updated, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null) {
            updated.fetch(getHttpClient());
        } else {
            getHttpClient().runWithRequestGroup(requestGroup, () -> {
                updated.fetch(getHttpClient());
                return true;
            });
        }
        return updated.getResult();
    }

    public static int fetchEpisodes(Title title) {
        return title.fetchEps(getHttpClient());
    }

    public static int fetchManga(Manga manga) {
        return manga.fetch(getHttpClient());
    }

    public static int fetchViewerInitial(Manga manga, CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null)
            return manga.fetchForViewerInitial(getHttpClient());
        return getHttpClient().runWithRequestGroup(requestGroup, () -> manga.fetchForViewerInitial(getHttpClient()));
    }

    public static Ranking<Title> loadWebtoonSection(MainPageWebtoon parser, String title, String path, int baseMode,
                                                    CustomHttpClient.RequestGroup requestGroup) throws Exception {
        if(requestGroup == null)
            return parser.parseWolfTitle(getHttpClient(), title, path, baseMode);
        return getHttpClient().runWithRequestGroup(requestGroup,
                () -> parser.parseWolfTitle(getHttpClient(), title, path, baseMode));
    }

    public static List<String> imageUrls(Manga manga, Context context) {
        return manga.getImgs(context);
    }

    public static void backfillRecentProgress(int limit) {
        p.backfillRecentProgress(getHttpClient(), limit);
    }

    public static String resolveUrl(String path) {
        return getHttpClient().getUrl(path);
    }
}
