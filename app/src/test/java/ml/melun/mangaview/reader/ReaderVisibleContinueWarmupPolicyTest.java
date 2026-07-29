package ml.melun.mangaview.reader;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertEquals;

public class ReaderVisibleContinueWarmupPolicyTest {
    @Test
    public void visibleContinueWarmsOneSourceBodyUnlessDataSaveIsEnabled() {
        assertEquals(
                ReaderWarmupCoordinator.WarmupProfile.FIRST_BYTE,
                ReaderWarmupCoordinator.visibleContinueProfileForTest(false));
        assertEquals(
                ReaderWarmupCoordinator.WarmupProfile.URL_ONLY,
                ReaderWarmupCoordinator.visibleContinueProfileForTest(true));
    }

    @Test
    public void forwardWarmupChoosesOnlyTheNewerEpisode() {
        Title title = new Title("title", "", "", new ArrayList<>(), "", 7, MTitle.base_comic);
        Manga newer = new Manga(30, "30화", "", MTitle.base_comic);
        Manga current = new Manga(20, "20화", "", MTitle.base_comic);
        Manga older = new Manga(10, "10화", "", MTitle.base_comic);
        title.setEps(Arrays.asList(newer, current, older));
        current.setTitle(title);

        assertEquals(30, ReaderWarmupCoordinator.forwardNextEpisodeIdForTest(current, title));
    }

    @Test
    public void newestEpisodeDoesNotWrapBackToAnOlderEpisode() {
        Title title = new Title("title", "", "", new ArrayList<>(), "", 7, MTitle.base_comic);
        Manga newest = new Manga(30, "30화", "", MTitle.base_comic);
        Manga older = new Manga(20, "20화", "", MTitle.base_comic);
        title.setEps(Arrays.asList(newest, older));
        newest.setTitle(title);

        assertEquals(0, ReaderWarmupCoordinator.forwardNextEpisodeIdForTest(newest, title));
    }
}
