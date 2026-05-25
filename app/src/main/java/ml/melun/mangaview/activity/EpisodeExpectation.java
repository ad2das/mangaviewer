package ml.melun.mangaview.activity;

import ml.melun.mangaview.mangaview.Manga;

interface EpisodeExpectation {
    boolean isStillExpected(Manga target);
}

