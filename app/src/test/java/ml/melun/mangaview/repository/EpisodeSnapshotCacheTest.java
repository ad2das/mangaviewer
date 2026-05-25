package ml.melun.mangaview.repository;

import org.junit.Test;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;

public class EpisodeSnapshotCacheTest {
    @Test
    public void keyIncludesNormalizedSourceSite() {
        Title title = new Title(new MTitle("name", 12, "", "", null, "", base_webtoon));
        title.setSourceSite("NTK");

        assertEquals("episodeSnapshotV2_ntk_" + base_webtoon + "_12",
                EpisodeSnapshotCache.key(title, false));
    }

    @Test
    public void keyUsesCurrentSiteFallbackWhenSourceIsMissing() {
        Title title = new Title(new MTitle("name", 7, "", "", null, "", base_webtoon));

        assertEquals("episodeSnapshotV2_wfwf_" + base_webtoon + "_7",
                EpisodeSnapshotCache.key(title, false));
        assertEquals("episodeSnapshotV2_ntk_" + base_webtoon + "_7",
                EpisodeSnapshotCache.key(title, true));
    }

    @Test
    public void legacyKeyMatchesOldViewerCacheKey() {
        Title title = new Title(new MTitle("name", 7, "", "", null, "", base_webtoon));

        assertEquals("episodeSnapshotV1_" + base_webtoon + "_7",
                EpisodeSnapshotCache.legacyKey(title));
    }

    @Test
    public void nullTitleStillUsesVersionedSourceFallbackKey() {
        assertEquals("episodeSnapshotV2_wfwf_0_0",
                EpisodeSnapshotCache.key(null, false));
        assertEquals("episodeSnapshotV2_ntk_0_0",
                EpisodeSnapshotCache.key(null, true));
    }

    @Test
    public void sourceNamesAreNormalizedForStableDiskKeys() {
        Title title = new Title(new MTitle("name", 12, "", "", null, "", base_webtoon));
        title.setSourceSite(" NTK ");

        assertEquals("episodeSnapshotV2_ntk_" + base_webtoon + "_12",
                EpisodeSnapshotCache.key(title, false));
    }
}
