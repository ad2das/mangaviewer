package ml.melun.mangaview.activity;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;

final class TagSearchEpisodeSnapshot {
    long savedAt;
    ArrayList<Manga> episodes;

    TagSearchEpisodeSnapshot(List<Manga> episodes) {
        this.savedAt = System.currentTimeMillis();
        this.episodes = new ArrayList<>(episodes);
    }
}

