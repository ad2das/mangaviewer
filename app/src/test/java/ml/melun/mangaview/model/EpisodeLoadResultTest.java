package ml.melun.mangaview.model;

import static org.junit.Assert.assertEquals;

import java.util.Collections;

import ml.melun.mangaview.mangaview.Manga;
import org.junit.Test;

public class EpisodeLoadResultTest {
    @Test
    public void storesCodeAndEpisodes() {
        Manga episode = new Manga(7, "episode", "", 1);
        EpisodeLoadResult result = new EpisodeLoadResult(0, Collections.singletonList(episode));

        assertEquals(0, result.getResultCode());
        assertEquals(Collections.singletonList(episode), result.getEpisodes());
    }
}
