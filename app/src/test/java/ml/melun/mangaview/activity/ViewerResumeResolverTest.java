package ml.melun.mangaview.activity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewerResumeResolverTest {
    @Test
    public void pathlessNtkResumePlaceholderSkipsDirectFetchAndLastResort() {
        Title title = new Title("원피스", "", "", null, "", 2, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga target = new Manga(1, "", "", MTitle.base_comic);
        target.setTitle(title);
        target.setTitleId(title.getId());

        assertTrue(ViewerResumeResolver.shouldResolveBeforeDirectFetch(target, title));
        assertTrue(ViewerResumeResolver.shouldBlockPathlessNtkResume(target, title));
        assertFalse(ViewerResumeResolver.shouldUseTargetAsLastResort(target, title));
        assertTrue(ViewerResumeResolver.candidates(target, title, true).isEmpty());
    }

    @Test
    public void ntkResumeWithConcreteEpisodePathCanUseTarget() {
        Title title = new Title("서머타임 렌더링", "", "", null, "", 7843, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setPath("/manhwa/7843");
        Manga target = new Manga(90, "", "", MTitle.base_comic);
        target.setTitle(title);
        target.setTitleId(title.getId());
        target.setNtkEpisodePath("/manhwa/7843/241718");

        assertFalse(ViewerResumeResolver.shouldBlockPathlessNtkResume(target, title));
        assertTrue(ViewerResumeResolver.shouldUseTargetAsLastResort(target, title));
    }

    @Test
    public void pathlessWolfResumeCanStillUseNumericFallback() {
        Title title = new Title("서머타임 렌더링", "", "", null, "", 10017, MTitle.base_comic);
        title.setSourceSite("wfwf");
        Manga target = new Manga(74, "", "", MTitle.base_comic);
        target.setTitle(title);
        target.setTitleId(title.getId());

        assertFalse(ViewerResumeResolver.shouldBlockPathlessNtkResume(target, title));
        assertTrue(ViewerResumeResolver.shouldUseTargetAsLastResort(target, title));
    }

    @Test
    public void ntkResumeUsesRealEpisodesWhenAvailable() {
        Title title = new Title("서머타임 렌더링", "", "", null, "", 7843, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga target = new Manga(90, "", "", MTitle.base_comic);
        target.setTitle(title);
        target.setTitleId(title.getId());

        Manga episode = new Manga(90, "서머타임 렌더링 90화", "", MTitle.base_comic);
        episode.setTitle(title);
        episode.setTitleId(title.getId());
        episode.setNtkEpisodePath("/manhwa/7843/241718");
        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(episode);
        title.setEps(episodes);

        List<Manga> candidates = ViewerResumeResolver.candidates(target, title,
                ViewerResumeResolver.shouldResolveBeforeDirectFetch(target, title));

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.get(0).getNtkEpisodePath().length() > 0);
    }
}
