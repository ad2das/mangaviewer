package ml.melun.mangaview.mangaview;

import org.junit.Test;

import com.google.gson.Gson;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MangaTest {
    @Test
    public void safeUrlReturnsNullForMissingManga() {
        assertNull(Manga.safeUrl(null));
    }

    @Test
    public void safeUrlReturnsNullWhenUrlAccessFails() {
        Manga manga = new Manga(1, "episode", "", MTitle.base_comic) {
            @Override
            public String getUrl() {
                throw new RuntimeException("url unavailable");
            }
        };

        assertNull(Manga.safeUrl(manga));
    }

    @Test
    public void safeUrlReturnsMangaUrl() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);

        assertEquals("/comic/12", Manga.safeUrl(manga));
    }

    @Test
    public void gsonSerializationIgnoresNavigationLinks() {
        Manga first = new Manga(1, "first", "", MTitle.base_comic);
        Manga second = new Manga(2, "second", "", MTitle.base_comic);
        first.setNextEp(second);
        second.setPrevEp(first);

        String json = new Gson().toJson(first);

        assertTrue(json.contains("\"id\":1"));
    }

    @Test
    public void offlineImagesReturnEmptyListWithoutPath() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);
        manga.setMode(1);

        assertTrue(manga.getImgs(null).isEmpty());
    }

    @Test
    public void onlineImagesReturnSnapshotWhileFetchIsBusy() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic) {
            @Override
            public boolean isFetchInProgress() {
                return true;
            }
        };
        manga.setMode(0);
        manga.setImgs(new ArrayList<>(Arrays.asList("a.jpg", "b.jpg")));

        List<String> images = manga.getImgs(null);
        images.clear();

        assertEquals(Arrays.asList("a.jpg", "b.jpg"), manga.getImgs(null));
    }

    @Test
    public void offlineImagesSkipDownloadArtifacts() throws Exception {
        File dir = Files.createTempDirectory("offline-images").toFile();
        File image = new File(dir, "0001.jpg");
        File part = new File(dir, "0002.jpg.part");
        File flag = new File(dir, "downloading");
        File text = new File(dir, "note.txt");
        try {
            image.createNewFile();
            part.createNewFile();
            flag.createNewFile();
            text.createNewFile();

            Manga manga = new Manga(12, "episode", "", MTitle.base_comic);
            manga.setMode(1);
            manga.setOfflinePath(dir.getAbsolutePath());

            List<String> images = manga.getImgs(null);

            assertEquals(1, images.size());
            assertEquals(image.getAbsolutePath(), images.get(0));
        } finally {
            image.delete();
            part.delete();
            flag.delete();
            text.delete();
            dir.delete();
        }
    }

    @Test
    public void scaledDimensionNeverDropsBelowOnePixel() {
        assertEquals(1, Decoder.scaledDimensionForTest(1, 0.01f));
        assertEquals(50, Decoder.scaledDimensionForTest(100, 0.5f));
    }

    @Test
    public void decodeGridUsesAtLeastOnePixelCells() {
        assertEquals(1, Decoder.gridCellSizeForTest(3, 5));
        assertEquals(20, Decoder.gridCellSizeForTest(100, 5));
    }

    @Test
    public void ntkPageImagesSkipBoardUploadAds() {
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/blacktoon/episodes/1/12712/p001.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/manhwa/25089/296849/p001.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/manhwa/25089/296849/001.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/manhwa/25089/296849/0001.webp\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"/manhwa/25089/296849/p025.webp?token=1\">"));

        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/board_uploads/2026/05/15/ad.png\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/blacktoon/thumbs/15741.png?v2\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<div class=\"banner\"><img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\"></div>"));
    }

    @Test
    public void ntkBoardUploadsAreOnlyFallbackPageImages() {
        assertTrue(Manga.isNtkFallbackBoardPageImageForTest(
                "<article class=\"viewer-content\"><img src=\"https://i.toonflix.app/board_uploads/2026/05/15/page001.jpg\"></article>"));
        assertTrue(Manga.isNtkFallbackBoardPageImageForTest(
                "<div class=\"vw-imgs vw-imgs--single\"><img src=\"https://i.toonflix.app/board_uploads/2026/05/06/155957_947d54918760.jpg\"></div>"));

        org.junit.Assert.assertFalse(Manga.isNtkFallbackBoardPageImageForTest(
                "<div class=\"episodeThumbCard\"><img src=\"https://i.toonflix.app/board_uploads/2026/05/15/thumb.jpg\"></div>"));
        org.junit.Assert.assertFalse(Manga.isNtkFallbackBoardPageImageForTest(
                "<div class=\"banner\"><img src=\"https://i.toonflix.app/board_uploads/2026/05/15/ad.png\"></div>"));
    }

    @Test
    public void ntkBlockedPageDetectedBeforeImageParsing() {
        assertTrue(Manga.looksLikeNtkBlockedPageForTest(
                "<html><head><title>Just a moment...</title></head><body>challenges.cloudflare.com</body></html>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkBlockedPageForTest(
                "<html><body><main><img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\"></main></body></html>"));
    }

    @Test
    public void ntkEmbeddedScriptImagesAreParsed() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"images\":[\"https:\\/\\/i.toonflix.app\\/webtoon_uploads\\/page001.jpg\"]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://i.toonflix.app/webtoon_uploads/page001.jpg", images.get(0));
    }

    @Test
    public void ntkEmbeddedNumberedPageImagesAreParsed() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"images\":[\"https:\\/\\/i.toonflix.app\\/manhwa\\/25089\\/296849\\/p001.jpg\"]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://i.toonflix.app/manhwa/25089/296849/p001.jpg", images.get(0));
    }

    @Test
    public void ntkEmbeddedNumberedPageImagesAllowPlainNumericNames() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"images\":[\"https:\\/\\/i.toonflix.app\\/manhwa\\/25089\\/296849\\/001.jpg\"]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://i.toonflix.app/manhwa/25089/296849/001.jpg", images.get(0));
    }

    @Test
    public void ntkDocumentPreloadBoardUploadsCanBootstrapViewerPages() {
        List<String> images = Manga.ntkDocumentPageImagesForTest(
                "<html><head>"
                        + "<link rel=\"preload\" as=\"image\" href=\"https://i.toonflix.app/board_uploads/2026/05/06/155957_947d54918760.jpg\" fetchPriority=\"high\">"
                        + "<link rel=\"preload\" as=\"image\" href=\"https://i.toonflix.app/board_uploads/2026/05/06/155958_a87a651f9bea.jpg\" fetchPriority=\"auto\">"
                        + "</head><body><main class=\"vw-main\"></main></body></html>");

        assertEquals(2, images.size());
        assertEquals("https://i.toonflix.app/board_uploads/2026/05/06/155957_947d54918760.jpg", images.get(0));
        assertEquals("https://i.toonflix.app/board_uploads/2026/05/06/155958_a87a651f9bea.jpg", images.get(1));
    }

    @Test
    public void ntkDocumentViewerBoardUploadsArePageImagesWhenNoPrimaryHostExists() {
        List<String> images = Manga.ntkDocumentPageImagesForTest(
                "<main class=\"vw-main\"><div class=\"vw-imgs vw-imgs--single\">"
                        + "<img src=\"https://i.toonflix.app/board_uploads/2026/05/06/155957_947d54918760.jpg\" alt=\"page 1\">"
                        + "<img src=\"https://i.toonflix.app/board_uploads/2026/05/06/155958_a87a651f9bea.jpg\" alt=\"page 2\">"
                        + "</div></main>");

        assertEquals(2, images.size());
        assertEquals("https://i.toonflix.app/board_uploads/2026/05/06/155957_947d54918760.jpg", images.get(0));
        assertEquals("https://i.toonflix.app/board_uploads/2026/05/06/155958_a87a651f9bea.jpg", images.get(1));
    }

    @Test
    public void ntkViewerEpisodeNameUsesVisibleEpisodeNumber() {
        assertEquals("275화", Manga.ntkViewerEpisodeNameForTest(
                "<div class=\"vw-ep\"><strong>275</strong><span> - 원펀맨 리메이크 275화</span></div>"));
        assertEquals("274화", Manga.ntkViewerEpisodeNameForTest(
                "<meta property=\"og:title\" content=\"원펀맨 리메이크 274화 | 뉴토끼\">"));
    }

    @Test
    public void ntkEpisodePathFallsBackToCanonicalEpisodeListEntry() {
        Title title = new Title("one punch", "", "", null, "349", 8605, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga canonical = new Manga(349, "275", "", MTitle.base_comic);
        canonical.setTitle(title);
        canonical.setNtkEpisodePath("/manhwa/8605/u-mou88jul-3akm");
        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(canonical);
        title.setEps(episodes);
        Manga candidate = new Manga(349, "275", "", MTitle.base_comic);
        candidate.setTitle(title);

        assertEquals("/manhwa/8605/u-mou88jul-3akm", candidate.getNtkEpisodePath());
        assertEquals("/manhwa/8605/u-mou88jul-3akm", candidate.getUrl());
    }

    @Test
    public void ntkPercentEncodedScriptImagesAreParsed() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>url=https%3A%2F%2Fi.toonflix.app%2Fwebtoon_uploads%2Fpage002.webp</script>");

        assertEquals(1, images.size());
        assertEquals("https://i.toonflix.app/webtoon_uploads/page002.webp", images.get(0));
    }

    @Test
    public void ntkEmbeddedBoardUploadsAreIgnored() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"images\":[\"https:\\/\\/i.toonflix.app\\/board_uploads\\/2026\\/05\\/15\\/page001.jpg\"]}</script>");

        assertEquals(0, images.size());
    }

    @Test
    public void ntkEmbeddedBannerArraysAreIgnoredWhenNoPageImagesExist() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"headerBanners\":[{\"imageUrl\":\"https:\\/\\/i.toonflix.app\\/board_uploads\\/2026\\/05\\/15\\/ad.png\",\"linkUrl\":\"https:\\/\\/ad.example\"}]}</script>");

        assertEquals(0, images.size());
    }

    @Test
    public void wfwfImageReachabilityUsesParsedUrlsWithoutBlockingProbe() {
        assertTrue(Manga.hasUsableWolfPageImagesForTest(Arrays.asList("", "https://i1.imgcloud18.com/page001.jpg")));
        org.junit.Assert.assertFalse(Manga.hasUsableWolfPageImagesForTest(Arrays.asList("", " ")));
    }

    @Test
    public void explicitWfwfTitleKeepsWolfEpisodePath() {
        Title title = new Title("title", "", "", Collections.emptyList(), "", 12683, MTitle.base_comic);
        title.setSourceSite("wfwf");
        Manga manga = new Manga(122, "122화", "", MTitle.base_comic);
        manga.setTitle(title);

        assertEquals("/cl?toon=12683", title.getUrl());
        assertEquals("/cv?toon=12683&num=122", manga.getUrl());
    }

    @Test
    public void explicitNtkTitleUsesNtkEpisodePath() {
        Title title = new Title("title", "", "", Collections.emptyList(), "", 25089, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga manga = new Manga(1, "1화", "", MTitle.base_comic);
        manga.setTitle(title);

        assertEquals("/manhwa/25089", title.getUrl());
        assertEquals("/manhwa/25089/1", manga.getUrl());
    }
}
