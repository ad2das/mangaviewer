package ml.melun.mangaview.activity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
        assertNull(ViewerResumeResolver.concreteNtkResumeCandidate(target, title));
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

    @Test
    public void largePathlessNtkResumeUsesVisibleEpisodeNumberBeforeBroadFallback() {
        Title title = new Title("원피스", "", "", null, "", 2, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga target = new Manga(1293, "", "", MTitle.base_comic);
        target.setTitle(title);
        target.setTitleId(title.getId());

        ArrayList<Manga> episodes = new ArrayList<>();
        for(int i = 1300; i >= 1; i--) {
            Manga episode = new Manga(i, "원피스 " + i + "화", "", MTitle.base_comic);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episode.setNtkEpisodePath("/manhwa/2/" + i);
            episodes.add(episode);
        }
        title.setEps(episodes);

        List<Manga> candidates = ViewerResumeResolver.candidates(target, title,
                ViewerResumeResolver.shouldResolveBeforeDirectFetch(target, title));

        assertFalse(candidates.isEmpty());
        assertEquals("/manhwa/2/1293", candidates.get(0).getNtkEpisodePath());
        assertEquals("/manhwa/2/1293",
                ViewerResumeResolver.concreteNtkResumeCandidate(target, title).getNtkEpisodePath());
    }

    @Test
    public void pathlessNtkResumeUsesVisibleEpisodeNumberBeforeStaleProgressIndex() {
        Title title = new Title("원피스", "", "", null, "", 2, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setReadingProgress(1274, 3, 10);
        Manga target = new Manga(1274, "", "", MTitle.base_comic);
        target.setTitle(title);
        target.setTitleId(title.getId());

        ArrayList<Manga> episodes = new ArrayList<>();
        for(int i = 1300; i >= 1; i--) {
            Manga episode = new Manga(i, "원피스 " + i + "화", "", MTitle.base_comic);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episode.setNtkEpisodePath("/manhwa/2/" + i);
            episodes.add(episode);
        }
        title.setEps(episodes);

        List<Manga> candidates = ViewerResumeResolver.candidates(target, title,
                ViewerResumeResolver.shouldResolveBeforeDirectFetch(target, title));

        assertFalse(candidates.isEmpty());
        assertEquals("/manhwa/2/1274", candidates.get(0).getNtkEpisodePath());
    }

    @Test
    public void ntkResumeMangaUsesStoredResumePathWhenEpisodesAreMissing() {
        Title title = new Title("원피스", "", "", null, "", 2, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setBookmark(1290);
        title.setResumeNtkEpisodePath("/manhwa/2/one-piece-1183");

        Manga resume = ViewerResumeResolver.resumeManga(title);

        assertTrue(resume != null);
        assertEquals(1290, resume.getId());
        assertEquals("/manhwa/2/one-piece-1183", resume.getNtkEpisodePath());
    }

    @Test
    public void ntkResumeMangaRestoresPersistedImageIdentityBeforeEpisodeLoad() {
        Title title = new Title("target", "", "", null, "", 25694, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setBookmark(1767091);
        title.setResumeNtkEpisodePath("/manhwa/25694/1767091");
        title.setResumeNtkImageIdentity("25694", "1767091", 4);

        Manga resume = ViewerResumeResolver.resumeManga(title);

        assertTrue(resume != null);
        assertEquals("/manhwa/25694/1767091", resume.getNtkEpisodePath());
        assertEquals("25694", resume.getNtkImageWorkId());
        assertEquals("1767091", resume.getNtkImageEpisodeId());
        assertEquals(4, resume.getNtkImageCount());
    }

    @Test
    public void ntkHomeResumeReconstructsExactNumericRouteFromPersistedImageIdentity() {
        Title title = new Title("legacy recent", "", "", null, "", 25694, MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setBookmark(1767091);
        title.setResumeNtkImageIdentity("25694", "1767091", 112);

        Manga resume = ViewerResumeResolver.resumeManga(title);

        assertTrue(resume != null);
        assertEquals("/manhwa/25694/1767091", resume.getNtkEpisodePath());
        assertEquals("/manhwa/25694/1767091", title.getResumeNtkEpisodePath());
        assertEquals("25694", resume.getNtkImageWorkId());
        assertEquals("1767091", resume.getNtkImageEpisodeId());
        assertEquals(112, resume.getNtkImageCount());
    }

    @Test
    public void ntkWebtoonHomeResumeUsesWebtoonRouteWhenRestoringIdentity() {
        Title title = new Title("legacy webtoon", "", "", null, "", 16968, MTitle.base_webtoon);
        title.setSourceSite("ntk");
        title.setBookmark(1463195);
        title.setResumeNtkImageIdentity("16968", "1463195", 81);

        Manga resume = ViewerResumeResolver.resumeManga(title);

        assertTrue(resume != null);
        assertEquals("/webtoon/16968/1463195", resume.getNtkEpisodePath());
    }

    @Test
    public void ntkSlugWebtoonImageIdentityIsNotMisusedAsViewerRoute() {
        Title title = new Title("slug webtoon", "", "", null, "", 16968, MTitle.base_webtoon);
        title.setSourceSite("ntk");
        title.setBookmark(1463195);
        title.setResumeNtkImageIdentity("834922", "1463195", 81);

        Manga resume = ViewerResumeResolver.resumeManga(title);

        assertTrue(resume != null);
        assertEquals("", resume.getNtkEpisodePath());
    }

    @Test
    public void resumeMangaPrefersSavedEpisodeIdOverStaleProgressIndex() {
        Title title = new Title("마왕의 딸은 너무 착해!!", "", "", null, "", 1001, MTitle.base_webtoon);
        title.setSourceSite("wfwf");
        title.setBookmark(24);
        title.setReadingProgress(24, 2, 30);

        ArrayList<Manga> episodes = new ArrayList<>();
        for(int i = 30; i >= 1; i--) {
            Manga episode = new Manga(i, "마왕의 딸은 너무 착해!! " + i + "화", "", MTitle.base_webtoon);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episodes.add(episode);
        }
        title.setEps(episodes);

        Manga resolved = ViewerResumeResolver.resumeManga(title);

        assertEquals(24, resolved.getId());
    }
}
