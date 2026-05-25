package ml.melun.mangaview.contracts;

import ml.melun.mangaview.mangaview.MainPage;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Ranking;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;

public interface SourceRepository {
    MangaRepository.Cancellation cancellation();

    Search createSearch(String query, int mode, int baseMode);

    MainPageWebtoon createWebtoonParser(int baseMode);

    MainPage loadComicHome(MangaRepository.Cancellation cancellation) throws Exception;

    int search(Search search, MangaRepository.Cancellation cancellation) throws Exception;

    int fetchEpisodes(Title title, MangaRepository.Cancellation cancellation);

    int fetchEpisodesForeground(Title title, MangaRepository.Cancellation cancellation);

    int fetchEpisodesBackground(Title title, MangaRepository.Cancellation cancellation);

    int fetchViewerInitial(Manga manga, MangaRepository.Cancellation cancellation) throws Exception;

    Ranking<Title> loadWebtoonSection(MainPageWebtoon parser, String title, String path, int baseMode,
                                      MangaRepository.Cancellation cancellation) throws Exception;
}
