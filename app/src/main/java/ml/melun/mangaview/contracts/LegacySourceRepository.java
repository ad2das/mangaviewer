package ml.melun.mangaview.contracts;

import ml.melun.mangaview.mangaview.MainPage;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Ranking;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;

public final class LegacySourceRepository implements SourceRepository {
    @Override
    public MangaRepository.Cancellation cancellation() {
        return MangaRepository.cancellation();
    }

    @Override
    public Search createSearch(String query, int mode, int baseMode) {
        return MangaRepository.createSearch(query, mode, baseMode);
    }

    @Override
    public MainPageWebtoon createWebtoonParser(int baseMode) {
        return MangaRepository.createWebtoonParser(baseMode);
    }

    @Override
    public MainPage loadComicHome(MangaRepository.Cancellation cancellation) throws Exception {
        return MangaRepository.loadComicHome(cancellation);
    }

    @Override
    public int search(Search search, MangaRepository.Cancellation cancellation) throws Exception {
        return MangaRepository.search(search, cancellation);
    }

    @Override
    public int fetchEpisodes(Title title, MangaRepository.Cancellation cancellation) {
        return MangaRepository.fetchEpisodes(title, cancellation);
    }

    @Override
    public int fetchEpisodesForeground(Title title, MangaRepository.Cancellation cancellation) {
        return MangaRepository.fetchEpisodesForeground(title, cancellation);
    }

    @Override
    public int fetchEpisodesBackground(Title title, MangaRepository.Cancellation cancellation) {
        return MangaRepository.fetchEpisodesBackground(title, cancellation);
    }

    @Override
    public int fetchViewerInitial(Manga manga, MangaRepository.Cancellation cancellation) throws Exception {
        return MangaRepository.fetchViewerInitial(manga, cancellation);
    }

    @Override
    public Ranking<Title> loadWebtoonSection(MainPageWebtoon parser, String title, String path, int baseMode,
                                             MangaRepository.Cancellation cancellation) throws Exception {
        return MangaRepository.loadWebtoonSection(parser, title, path, baseMode, cancellation);
    }
}
