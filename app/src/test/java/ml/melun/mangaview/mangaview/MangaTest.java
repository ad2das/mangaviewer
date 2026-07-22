package ml.melun.mangaview.mangaview;

import org.junit.Test;

import com.google.gson.Gson;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MangaTest {
    @Test
    public void clickPayloadCountRequiresExactPathAndMatchingModelCount() {
        Manga episode = new Manga(5667, "13권", "", MTitle.base_comic);
        episode.setNtkEpisodePath("/manhwa/2640/5667");
        episode.setNtkImageCount(88);
        episode.setNtkViewerPayloadHint(
                "{\"sourceWorkId\":\"2640\",\"episodeId\":\"5667\","
                        + "\"episodePath\":\"/manhwa/2640/5667\",\"imageCount\":88,\"episodes\":true}");

        assertEquals(88, episode.getExactNtkClickPayloadImageCount("/manhwa/2640/5667"));
        assertEquals(0, episode.getExactNtkClickPayloadImageCount("/manhwa/2640/5668"));
        episode.setNtkImageCount(87);
        assertEquals(88, episode.getExactNtkClickPayloadImageCount("/manhwa/2640/5667"));
    }

    @Test
    public void clickPayloadCountRejectsConflictingCountFields() {
        Manga episode = new Manga(5667, "13권", "", MTitle.base_comic);
        episode.setNtkEpisodePath("/manhwa/2640/5667");
        episode.setNtkImageCount(88);
        episode.setNtkViewerPayloadHint(
                "{\"episodePath\":\"/manhwa/2640/5667\",\"imageCount\":88,\"totalPages\":87}");

        assertEquals(0, episode.getExactNtkClickPayloadImageCount("/manhwa/2640/5667"));
    }

    @Test
    public void episodeParserPreservesPathBoundCountWithoutApiTokenPayload() {
        String html = "<a href='/manhwa/2640/5667'><span class='subject'>13권</span></a>"
                + "<script>{\"id\":\"5667\",\"imageCount\":88}</script>";
        List<Manga> episodes = NtkEpisodeParser.parseForTest(
                html, "manhwa", "2640", MTitle.base_comic);

        assertEquals(1, episodes.size());
        assertEquals(88, episodes.get(0).getExactNtkClickPayloadImageCount(
                "/manhwa/2640/5667"));
    }

    @Test
    public void generatedNativeApiManifestRequiresMatchingTrustedCount() {
        List<String> generated = Arrays.asList(
                "https://booktoki9.org/manhwa/10003/96426/p001.jpg",
                "https://booktoki9.org/manhwa/10003/96426/p002.jpg",
                "https://booktoki9.org/manhwa/10003/96426/p003.jpg");

        assertTrue(CustomHttpClient.shouldHoldGeneratedNtkApiManifestUntilKnownCountForTest(
                "/manhwa/10003/96426", generated, 0));
        assertTrue(CustomHttpClient.shouldHoldGeneratedNtkApiManifestUntilKnownCountForTest(
                "/manhwa/10003/96426", generated, 31));
        assertFalse(CustomHttpClient.shouldHoldGeneratedNtkApiManifestUntilKnownCountForTest(
                "/manhwa/10003/96426", generated, 3));

        List<String> browserActual = Arrays.asList(
                "https://i.toonflix.app/manhwa_uploads/010139_deadbeef.png",
                "https://i.toonflix.app/manhwa_uploads/010140_cafebabe.png");
        assertFalse(CustomHttpClient.shouldHoldGeneratedNtkApiManifestUntilKnownCountForTest(
                "/manhwa/10003/96426", browserActual, 0));
    }

    @Test
    public void completeResponseBoundGeneratedManifestUsesItsExactResponseCount() {
        String complete = "{\"ok\":true,\"count\":3,\"images\":["
                + "{\"page\":1,\"src\":\"https://booktoki9.org/manhwa/10003/96426/p001.jpg\"},"
                + "{\"page\":2,\"src\":\"https://booktoki9.org/manhwa/10003/96426/p002.jpg\"},"
                + "{\"page\":3,\"src\":\"https://booktoki9.org/manhwa/10003/96426/p003.jpg\"}]}";
        String missingPage = "{\"ok\":true,\"count\":3,\"images\":["
                + "{\"page\":1,\"src\":\"https://booktoki9.org/manhwa/10003/96426/p001.jpg\"},"
                + "{\"page\":3,\"src\":\"https://booktoki9.org/manhwa/10003/96426/p003.jpg\"}]}";

        assertFalse(CustomHttpClient.shouldHoldResponseBoundGeneratedNtkApiManifestForTest(
                "/manhwa/10003/96426", complete, 3));
        assertTrue(CustomHttpClient.shouldHoldResponseBoundGeneratedNtkApiManifestForTest(
                "/manhwa/10003/96426", missingPage, 3));
    }

    @Test
    public void numericManhwaUsesCountOnlyStripAuthorityOnlyWithFiniteImageCount() {
        assertTrue(Manga.hasFiniteNumericManhwaStripAuthorityForTest(
                "/manhwa/10003/96426", 31));
        assertFalse(Manga.hasFiniteNumericManhwaStripAuthorityForTest(
                "/manhwa/10003/96426", 0));
        assertFalse(Manga.hasFiniteNumericManhwaStripAuthorityForTest(
                "/webtoon/10003/96426", 31));
    }

    @Test
    public void exactNumericManhwaStartsFullJpgBeforeImageCountMetadataArrives() {
        Manga episode = new Manga(1767091, "1화", "", MTitle.base_comic);
        episode.setNtkEpisodePath("/manhwa/25694/1767091");

        assertTrue(episode.shouldStartUnverifiedInitialGeneratedJpgStreamForTest(
                "manhwa", "25694", "1767091", 1));
        assertFalse(episode.shouldStartUnverifiedInitialGeneratedJpgStreamForTest(
                "manhwa", "99999", "1767091", 1));
        assertFalse(episode.shouldStartUnverifiedInitialGeneratedJpgStreamForTest(
                "manhwa", "25694", "1767091", 2));
    }

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
    public void ntkViewerNetworkFailureDoesNotRequireCaptcha() {
        Exception dnsFailure = new Exception("Request failed: net::ERR_NAME_NOT_RESOLVED");

        assertTrue(Manga.isRecoverableNetworkFetchFailureForTest(dnsFailure));
        assertFalse(Manga.isNtkViewerChallengeFailureForTest(true, dnsFailure));
        assertFalse(Manga.isNtkViewerChallengeFailureForTest(true, dnsFailure, true));
    }

    @Test
    public void ntkViewerCloudflareFailureRequiresCaptcha() {
        Exception cloudflare = new Exception("Cloudflare challenge");

        assertTrue(Manga.isNtkViewerChallengeFailureForTest(true, cloudflare));
    }

    @Test
    public void ntkViewerRequestFailureAfterObservedChallengeRequiresCaptcha() {
        Exception viewerFailure = new Exception("Request failed: /webtoon/848000");
        Exception apiFailure = new Exception("Request failed: /api/works?keyword=title");

        assertTrue(Manga.isNtkViewerChallengeFailureForTest(true, viewerFailure, true));
        assertTrue(Manga.isNtkViewerChallengeFailureForTest(true, apiFailure, true));
        assertFalse(Manga.isNtkViewerChallengeFailureForTest(true, viewerFailure, false));
        assertFalse(Manga.isNtkViewerChallengeFailureForTest(false, viewerFailure, true));
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
    public void copyViewerStateSkipsBusyTargetWithoutMutating() {
        Manga target = new Manga(12, "target", "", MTitle.base_comic) {
            @Override
            public boolean isFetchInProgress() {
                return true;
            }
        };
        Manga source = new Manga(12, "source", "", MTitle.base_comic);
        source.setImgs(new ArrayList<>(Arrays.asList("a.jpg")));

        assertTrue(!target.copyViewerStateFrom(source));
        assertNull(target.getImgs(null));
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
                "<img src=\"https://i.toonflix.app/wt/episodes/19353/tk_1075221/p001.jpeg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/manhwa/25089/296849/p001.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/manhwa/25089/296849/001.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/manhwa/25089/296849/0001.webp\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"/manhwa/25089/296849/p025.webp?token=1\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://www.pl3040.com/kr//07/34911/1792086/J0sSOWegkucq.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<img src=\"https://flysky3m.com/7307f2fd9b13a1acfb4c5ed2726909eb.jpg\">"));
        assertTrue(Manga.isNtkPageImageForTest(
                "<main class=\"vw-main\"><div class=\"vw-imgs\"><a href=\"https://i.toonflix.app/webtoon_uploads/page002.webp\"><img src=\"https://i.toonflix.app/webtoon_uploads/page002.webp\"></a></div></main>"));

        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://image-comic.pstatic.net/webtoon/849864/7/001.jpg\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://comic.naver.com/webtoon/849864/7/001.jpg\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/board_uploads/2026/05/15/ad.png\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/blacktoon/thumbs/15741.png?v2\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<div class=\"banner\"><img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\"></div>"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<div class=\"bn-r\" data-br=\"1\"><a class=\"bn-s\" rel=\"noopener noreferrer nofollow\" href=\"https://ad.example\"><img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\" alt=\"page 1\"></a></div>"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://sbxh6.com/api/m/i?a=token&i=0&t=metric.gif\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://sbxh6.com/api/ad/challenge?p=1.gif\">"));
        org.junit.Assert.assertFalse(Manga.isNtkPageImageForTest(
                "<img src=\"https://i.toonflix.app/cdn-cgi/challenge-platform/h/g/page001.jpg\">"));
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
        org.junit.Assert.assertFalse(Manga.isNtkFallbackBoardPageImageForTest(
                "<div class=\"bn-r\" data-br=\"1\"><a class=\"bn-s\" rel=\"noopener noreferrer nofollow\" href=\"https://ad.example\"><img src=\"https://i.toonflix.app/board_uploads/2026/05/15/ad.png\" alt=\"https://ad.example\"></a></div>"));
    }

    @Test
    public void ntkPrimaryImageTrustRejectsMetricAndAdApiUrls() {
        assertTrue(CustomHttpClient.isTrustedNtkPrimaryImageUrlForTest(
                "https://fvcdn3.com/6bf01a3f532d20c6ab5d5899adde24af.jpg"));
        assertTrue(CustomHttpClient.isTrustedNtkPrimaryImageUrlForTest(
                "https://i.toonflix.app/webtoon/849277/nv-849277-2/p001.jpg"));
        assertTrue(CustomHttpClient.isTrustedNtkPrimaryImageUrlForTest(
                "https://www.pl3040.com/kr//07/34732/1812500/rUQHZTa6wJvb.jpg"));

        org.junit.Assert.assertFalse(CustomHttpClient.isTrustedNtkPrimaryImageUrlForTest(
                "https://sbxh6.com/api/m/i?a=token&i=0&t=metric.gif"));
        org.junit.Assert.assertFalse(CustomHttpClient.isTrustedNtkPrimaryImageUrlForTest(
                "https://sbxh6.com/api/ad/challenge?scope=/webtoon/1/2"));
        org.junit.Assert.assertFalse(CustomHttpClient.isTrustedNtkPrimaryImageUrlForTest(
                "https://i.toonflix.app/banner/page001.jpg"));
    }

    @Test
    public void numericNtkEpisodesStartImageApiPrefetchFromDirectPayload() {
        assertTrue(Manga.shouldStartDirectOnlyNtkImageApiPrefetchForTest("/manhwa/34732/1812500"));
        assertTrue(Manga.shouldStartDirectOnlyNtkImageApiPrefetchForTest("/webtoon/17332/1515337"));
        assertFalse(Manga.shouldStartDirectOnlyNtkImageApiPrefetchForTest("/webtoon/68630031/kp-68630031-69262979"));
    }

    @Test
    public void ntkBlockedPageDetectedBeforeImageParsing() {
        assertTrue(Manga.looksLikeNtkBlockedPageForTest(
                "<html><head><title>Just a moment...</title></head><body>challenges.cloudflare.com</body></html>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkBlockedPageForTest(
                "<html><body><main><img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\"></main></body></html>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkBlockedPageForTest(
                "DevToolsBlockerGate {\"imageMetas\":[{\"page\":1},{\"page\":2}],"
                        + "\"imagesToken\":\"viewer-token\"}"));
    }

    @Test
    public void ntkKpDirectShellRequiresActualImages() {
        String shellOnly = "<html><body><div id=\"__next\"></div>"
                + "<script>self.__next_f.push([1,\"{\\\"sourceWorkId\\\":\\\"17247\\\"}\"])</script>"
                + "</body></html>";
        org.junit.Assert.assertFalse(Manga.isUsableNtkKpDirectPageForTest(
                "/webtoon/68630031/kp-68630031-69262979", 200, shellOnly));

        String withImage = shellOnly
                + "<script>self.__next_f.push([1,\"https:\\/\\/i.toonflix.app\\/webtoon_uploads\\/page001.webp\"])</script>";
        assertTrue(Manga.isUsableNtkKpDirectPageForTest(
                "/webtoon/68630031/kp-68630031-69262979", 200, withImage));
    }

    @Test
    public void ntkMissingPageDetectedBeforeImageParsing() {
        assertTrue(Manga.looksLikeNtkMissingPageForTest(
                "<html id=\"__next_error__\"><script>self.__next_f.push([\"NEXT_HTTP_ERROR_FALLBACK\",404])</script></html>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkMissingPageForTest(
                "<html><body><main class=\"vw-main\"><div class=\"vw-imgs\"><img src=\"https://i.toonflix.app/manhwa/34911/1793314/p001.jpg\"></div></main></body></html>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkMissingPageForTest(
                "<html><body><script>self.__next_f.push([\"$\",\"__next_error__\"])</script><img src=\"https://www.pl3040.com/kr//07/34911/1792086/001.jpg\"></body></html>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkMissingPageForTest(
                "<html><body>회차 없음<script>self.__next_f.push([\"https:\\/\\/www.pl3040.com\\/kr\\/\\/07\\/34911\\/1792086\\/001.jpg\"])</script></body></html>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkMissingPageForTest(
                "<html><body>회차 없음<div class=\"vw-imgs\"><img src=\"https://i.toonflix.app/board_uploads/2026/05/17/page001.png\" alt=\"page 1\"/></div></body></html>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkMissingPageForTest(
                "<script>{\"images\":[{\"page\":1,\"src\":\"https://i.toonflix.app/board_uploads/2026/05/17/page001.png\"}]}</script>"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkMissingPageForTest(
                "404: This page could not be found. {\"imageMetas\":[{\"page\":1},{\"page\":2}],"
                        + "\"imagesToken\":\"viewer-token\"}"));

        org.junit.Assert.assertFalse(Manga.looksLikeNtkMissingPageForTest(
                "<html><script>self.__next_f.push([\"NEXT_HTTP_ERROR_FALLBACK\",404])</script>"
                        + "<script>self.__next_f.push([1,\"{\\\"sourceWorkId\\\":\\\"19353\\\","
                        + "\\\"imageCount\\\":2}\"])</script></html>"));
    }

    @Test
    public void ntkEmbeddedScriptImagesAreParsed() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"images\":[\"https:\\/\\/i.toonflix.app\\/webtoon_uploads\\/page001.jpg\"]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://i.toonflix.app/webtoon_uploads/page001.jpg", images.get(0));
    }

    @Test
    public void ntkEmbeddedRootHashImagesAreParsed() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>self.__next_f.push([1,\"{\\\"src\\\":\\\"https:\\\\/\\\\/flysky3m.com\\\\/7307f2fd9b13a1acfb4c5ed2726909eb.jpg\\\"}\"])</script>");

        assertEquals(1, images.size());
        assertEquals("https://flysky3m.com/7307f2fd9b13a1acfb4c5ed2726909eb.jpg", images.get(0));
    }

    @Test
    public void ntkEmbeddedScriptImagesIgnoreNaverOriginalMetadata() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"viewerUrl\":\"https://comic.naver.com/webtoon/detail?titleId=849864&no=7\","
                        + "\"images\":[\"https:\\/\\/image-comic.pstatic.net\\/webtoon\\/849864\\/7\\/001.jpg\","
                        + "\"https:\\/\\/i.toonflix.app\\/webtoon_uploads\\/page001.jpg\"]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://i.toonflix.app/webtoon_uploads/page001.jpg", images.get(0));
    }

    @Test
    public void ntkEpisodeParserPreservesTokenHintButNotBoardOnlyHint() {
        String html = "<script>{\"episodes\":[{\"id\":\"1121174\","
                + "\"sourceEpisodeId\":\"naver-837998-32\",\"epNo\":32,\"imageCount\":75}],"
                + "\"imagesToken\":\"token-value\",\"imageMetas\":[{\"page\":1}],"
                + "\"src\":\"https://i.toonflix.app/webtoon_uploads/page001.jpg\"}</script>";

        List<Manga> episodes = NtkEpisodeParser.parseForTest(html, "webtoon", "837998", MTitle.base_webtoon);

        assertEquals(1, episodes.size());
        assertTrue(episodes.get(0).getNtkViewerPayloadHint().contains("imagesToken"));
        assertTrue(episodes.get(0).getNtkViewerPayloadHint().contains("webtoon_uploads"));

        String boardOnly = "<script>{\"episodes\":[{\"id\":\"1121174\","
                + "\"sourceEpisodeId\":\"naver-837998-32\",\"epNo\":32,\"imageCount\":75}],"
                + "\"src\":\"https://i.toonflix.app/board_uploads/2026/01/ad.jpg\"}</script>";
        List<Manga> boardEpisodes = NtkEpisodeParser.parseForTest(boardOnly, "webtoon", "837998", MTitle.base_webtoon);

        assertEquals(1, boardEpisodes.size());
        assertEquals("", boardEpisodes.get(0).getNtkViewerPayloadHint());
    }

    @Test
    public void ntkProtectedViewerPayloadDoesNotPromotePageChromeBanners() {
        String html = "<a href=\"/webtoon/18190/1518441\">4화</a>"
                + "<script type=\"application/json\" id=\"theme-viewer-data\">{"
                + "\"sourceWorkId\":\"18190\",\"episodeId\":\"1518441\","
                + "\"token\":\"token-value\",\"scopePath\":\"/webtoon/18190/1518441\","
                + "\"imageApiPath\":\"/api/webtoon-images\","
                + "\"images\":[{\"page\":1},{\"page\":2},{\"page\":3}]}"
                + "</script>"
                + "<img width=\"380\" height=\"100\" src=\"https://aws-cdn1.site/board_uploads/2026/05/21/banner.png\">";

        List<Manga> episodes = NtkEpisodeParser.parseForTest(
                html, "webtoon", "18190", MTitle.base_webtoon);

        assertEquals(1, episodes.size());
        String hint = episodes.get(0).getNtkViewerPayloadHint();
        assertTrue(hint.contains("/api/webtoon-images"));
        assertTrue(hint.contains("\"page\":3"));
        assertEquals(0, Manga.ntkViewerPayloadImageUrls(
                hint, "/webtoon/18190/1518441").size());
    }

    @Test
    public void ntkEpisodeListChromeBannersAreNotSavedAsEpisodeImages() {
        String html = "<a href=\"/webtoon/18190/1518441\">4화</a>"
                + "<script>{\"episodes\":[{\"id\":\"1518441\",\"sourceEpisodeId\":\"1518441\","
                + "\"epNo\":4}]}</script>"
                + "<button class=\"thema-home-banner-button\" data-banner-id=\"7\" "
                + "data-banner-href=\"https://ad.example\"><img width=\"380\" height=\"100\" "
                + "src=\"https://aws-cdn1.site/board_uploads/2026/05/21/banner-a.png\"></button>"
                + "<button class=\"thema-home-banner-button\" data-banner-id=\"8\" "
                + "data-banner-href=\"https://ad2.example\"><img width=\"380\" height=\"100\" "
                + "src=\"https://aws-cdn1.site/board_uploads/2026/05/21/banner-b.png\"></button>";

        List<Manga> episodes = NtkEpisodeParser.parseForTest(
                html, "webtoon", "18190", MTitle.base_webtoon);

        assertEquals(1, episodes.size());
        assertEquals(0, Manga.ntkViewerPayloadImageUrls(
                episodes.get(0).getNtkViewerPayloadHint(),
                "/webtoon/18190/1518441").size());
    }

    @Test
    public void ntkNumericViewerPathWorkIdBeatsUnrelatedTitleThumbId() {
        Manga episode = new Manga(4, "4화", "", MTitle.base_webtoon);
        episode.setNtkEpisodePath("/webtoon/18190/1518441");
        episode.setNtkImageWorkId("2009");

        assertEquals("18190", episode.getNtkImageWorkId());
    }

    @Test
    public void ntkThemeViewerPayloadExposesAuthoritativePageCount() {
        String payload = "{\"imageApiPath\":\"/api/webtoon-images\",\"images\":["
                + "{\"page\":1},{\"page\":2},{\"page\":190}]}";

        assertEquals(190, Manga.ntkViewerPayloadPageCount(payload));
    }

    @Test
    public void ntkViewerApiDescriptorManifestKeepsEveryPageSlotUnique() {
        String cvDescriptor = "https://xiaomichina.com/token/cv/Y2YxOWU5LXY1MjA3.txt";
        String qcDescriptor = "https://xiaomichina.com/token/qc/Y2YwYWNlLXY4NjA4.json";
        String rsDescriptor = "https://shaomoi.org/token/rs/Y2YwYWNlLXY4NjA5.js";
        String woffDescriptor = "https://f1spard.site/token/qc/Y2Y0ZjIwLXYyNzc2.woff";
        String woff2Descriptor = "https://f1spard.site/token/cv/Y2Y3Zjc1LXY3NzUx.woff2";
        String body = "{\"ok\":true,\"count\":5,\"images\":["
                + "{\"page\":1,\"src\":\"" + cvDescriptor + "\"},"
                + "{\"page\":2,\"src\":\"" + qcDescriptor + "\"},"
                + "{\"page\":3,\"src\":\"" + rsDescriptor + "\"},"
                + "{\"page\":4,\"src\":\"" + woffDescriptor + "\"},"
                + "{\"page\":5,\"src\":\"" + woff2Descriptor + "\"}]}";

        List<String> urls = CustomHttpClient.extractNtkViewerImageUrlsFromApiBody(
                body, "webtoon", "18190", "1518441");

        assertEquals(5, urls.size());
        assertTrue(urls.get(0).endsWith("#mvpage=1"));
        assertTrue(urls.get(1).endsWith("#mvpage=2"));
        assertTrue(urls.get(2).endsWith("#mvpage=3"));
        assertTrue(urls.get(3).endsWith(".woff#mvpage=4"));
        assertTrue(urls.get(4).endsWith(".woff2#mvpage=5"));
    }

    @Test
    public void ntkViewerApiFastManifestAtomicallyPublishesAllSignedDescriptorSlots() {
        List<String> extensions = new ArrayList<>();
        extensions.addAll(Collections.nCopies(40, "txt"));
        extensions.addAll(Collections.nCopies(38, "json"));
        extensions.addAll(Collections.nCopies(30, "css"));
        extensions.addAll(Collections.nCopies(36, "js"));
        extensions.addAll(Collections.nCopies(27, "woff"));
        extensions.addAll(Collections.nCopies(19, "woff2"));
        assertEquals(190, extensions.size());
        StringBuilder body = new StringBuilder("{\"ok\":true,\"count\":190,\"images\":[");
        for(int page = 1; page <= 190; page++) {
            if(page > 1)
                body.append(',');
            String segment = new String[]{"cv", "mx", "qc", "rs"}[(page - 1) % 4];
            String extension = extensions.get(page - 1);
            body.append("{\"page\":").append(page)
                    .append(",\"src\":\"https://f1spard.site/token/")
                    .append(segment).append("/page-").append(page).append('.')
                    .append(extension).append("\",\"srcCandidates\":[")
                    .append("\"https://shaomoi.org/token/").append(segment)
                    .append("/page-").append(page).append('.').append(extension).append("\"]}");
        }
        body.append("]}");

        List<String> urls = CustomHttpClient.completeNtkViewerImagePageSlotsFromApiBodyForTest(
                body.toString(), 190, "manhwa", "25694", "1767091");

        assertEquals(190, CustomHttpClient.ntkViewerImagesCountFromBodyForTest(body.toString()));
        assertEquals(190, urls.size());
        for(int page = 1; page <= 190; page++) {
            assertTrue(urls.get(page - 1).endsWith("#mvpage=" + page));
        }
        assertTrue(urls.get(144).contains(".woff#mvpage=145"));
        assertTrue(urls.get(171).contains(".woff2#mvpage=172"));
    }

    @Test
    public void ntkViewerApiIncompleteOrUnslottedManifestIsNotComplete() {
        String missingPage = "{\"ok\":true,\"images\":["
                + "{\"page\":1,\"src\":\"https://f1spard.site/token/cv/one.woff\"},"
                + "{\"page\":3,\"src\":\"https://f1spard.site/token/rs/three.woff2\"}]}";

        List<String> urls = CustomHttpClient.completeNtkViewerImagePageSlotsFromApiBodyForTest(
                missingPage, 3, "webtoon", "18190", "1518441");
        String rejected = "{\"ok\":false,\"ad_ack_required\":true,\"images\":["
                + "{\"page\":1,\"src\":\"https://f1spard.site/token/cv/one.woff\"}]}";

        assertTrue(urls.isEmpty());
        assertTrue(CustomHttpClient.completeNtkViewerImagePageSlotsFromApiBodyForTest(
                rejected, 1, "webtoon", "18190", "1518441").isEmpty());
        assertFalse(CustomHttpClient.isTrustedNtkPrimaryImageUrlForTest(
                "https://f1spard.site/assets/not-signed-font.woff2"));
    }

    @Test
    public void ntkNextImageProxyAttributesAreParsed() {
        List<String> images = Manga.ntkDocumentPageImagesForTest(
                "<div class=\"vw-imgs\">"
                        + "<img src=\"/_next/image?url=https%3A%2F%2Fi.toonflix.app%2Fwebtoon_uploads%2Fpage001.webp&w=3840&q=75\">"
                        + "<img srcset=\"/_next/image?url=https%3A%2F%2Fi.toonflix.app%2Fwebtoon_uploads%2Fpage002.webp&w=1080&q=75 1080w,"
                        + " /_next/image?url=https%3A%2F%2Fi.toonflix.app%2Fwebtoon_uploads%2Fpage002.webp&w=1920&q=75 1920w\">"
                        + "</div>");

        assertEquals(2, images.size());
        assertEquals("https://i.toonflix.app/webtoon_uploads/page001.webp", images.get(0));
        assertEquals("https://i.toonflix.app/webtoon_uploads/page002.webp", images.get(1));
    }

    @Test
    public void ntkEmbeddedImagesDeduplicateQueryVariants() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>self.__next_f.push([\"https://i.toonflix.app/webtoon_uploads/page001.webp?width=3840\","
                        + "\"https://i.toonflix.app/webtoon_uploads/page001.webp\","
                        + "\"https://i.toonflix.app/webtoon_uploads/page002.webp\"])</script>");

        assertEquals(2, images.size());
        assertEquals("https://i.toonflix.app/webtoon_uploads/page001.webp?width=3840", images.get(0));
        assertEquals("https://i.toonflix.app/webtoon_uploads/page002.webp", images.get(1));
    }

    @Test
    public void ntkEmbeddedImagesTrimToViewerMetaPageCount() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"imageMetas\":[{\"page\":1},{\"page\":2}],\"imagesToken\":\"token\","
                        + "\"images\":[\"https://i.toonflix.app/webtoon_uploads/page001.webp\","
                        + "\"https://i.toonflix.app/webtoon_uploads/page002.webp\","
                        + "\"https://i.toonflix.app/webtoon_uploads/page003.webp\"]}</script>");

        assertEquals(2, images.size());
        assertEquals("https://i.toonflix.app/webtoon_uploads/page001.webp", images.get(0));
        assertEquals("https://i.toonflix.app/webtoon_uploads/page002.webp", images.get(1));
    }

    @Test
    public void ntkEmbeddedNumberedPageImagesAreParsed() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"images\":[\"https:\\/\\/i.toonflix.app\\/manhwa\\/25089\\/296849\\/p001.jpg\"]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://i.toonflix.app/manhwa/25089/296849/p001.jpg", images.get(0));
    }

    @Test
    public void ntkGeneratedFastPathDoesNotTrimPagesBeforeFirstFrame() {
        assertFalse(Manga.shouldTrimNtkGeneratedPagesBeforeFirstFrameForTest());
    }

    @Test
    public void ntkGeneratedFastPathValidatesFirstVisiblePages() {
        assertEquals(1, Manga.ntkGeneratedInitialValidationPageCountForTest(1));
        assertEquals(2, Manga.ntkGeneratedInitialValidationPageCountForTest(2));
        assertEquals(5, Manga.ntkGeneratedInitialValidationPageCountForTest(64));
    }

    @Test
    public void ntkKnownWebtoonNumericEpisodeUsesImmediateGeneratedFastPath() {
        assertTrue(Manga.shouldUseImmediateNtkGeneratedFastPathForTest(
                MTitle.base_webtoon, "/webtoon/18768/1586173", 37));
        assertFalse(Manga.shouldUseImmediateNtkGeneratedFastPathForTest(
                MTitle.base_webtoon, "/webtoon/840894/1073395", 47));
        assertFalse(Manga.shouldUseImmediateNtkGeneratedFastPathForTest(
                MTitle.base_webtoon, "/webtoon/18768/u-mp3wtr15-sxjg", 37));
        assertFalse(Manga.shouldUseImmediateNtkGeneratedFastPathForTest(
                MTitle.base_webtoon, "/webtoon/18768/1586173", 0));
        assertTrue(Manga.shouldUseImmediateNtkGeneratedFastPathForTest(
                MTitle.base_comic, "/manhwa/18768/1586173", 37));
    }

    @Test
    public void ntkCanonicalWebtoonNumericEpisodePrefersApiFirst() {
        assertTrue(Manga.shouldPreferNtkApiForCanonicalWebtoonPathForTest(
                "/webtoon/840894/1073395"));

        assertFalse(Manga.shouldPreferNtkApiForCanonicalWebtoonPathForTest(
                "/webtoon/18768/1586173"));
        assertFalse(Manga.shouldPreferNtkApiForCanonicalWebtoonPathForTest(
                "/webtoon/840894/u-slug-1073395"));
        assertFalse(Manga.shouldPreferNtkApiForCanonicalWebtoonPathForTest(
                "/manhwa/840894/1073395"));
    }

    @Test
    public void ntkCanonicalWebtoonSlugCdnUrlsUseTitleSlug() {
        assertEquals("최강-매니저", Manga.ntkCanonicalWebtoonSlugCandidateForTest(
                "/webtoon/최강-매니저", "ignored"));
        assertEquals("최강-매니저", Manga.ntkCanonicalWebtoonSlugCandidateForTest(
                "/webtoon/840894", "최강 매니저"));
        assertEquals("https://i.toonflix.app/wt/episodes/최강-매니저/1073395/p001.webp",
                Manga.ntkSlugWebtoonImageUrlForTest("최강-매니저", "1073395", 1, "webp"));
    }

    @Test
    public void ntkViewerMetaImagesAreDerivedFromEpisodePath() {
        List<String> images = Manga.ntkViewerMetaPageImagesForTest(
                "<script>{\\\"imageMetas\\\":[{\\\"page\\\":1},{\\\"page\\\":2}],"
                        + "\\\"imagesToken\\\":\\\"token\\\"}</script>",
                "/manhwa/3540/135918");

        assertTrue(images.isEmpty());
    }

    @Test
    public void ntkViewerMetaSlugWebtoonImagesUseThumbWorkId() {
        List<String> images = Manga.ntkViewerMetaPageImagesForTest(
                "<script>{\"imageMetas\":[{\"page\":1},{\"page\":2}],"
                        + "\"imagesToken\":\"token\","
                        + "\"episodeId\":\"tk_1075221\","
                        + "\"image\":\"https://i.toonflix.app/blacktoon/thumbs/19353.png?v2\"}</script>",
                "/webtoon/849365/tk_1075221");

        assertTrue(images.isEmpty());
    }

    @Test
    public void ntkGeneratedNumericEpisodeUsesUrlPathId() {
        assertEquals("232965", Manga.ntkGeneratedEpisodeIdForTest("/manhwa/11359/232965"));
        assertEquals("1587305", Manga.ntkGeneratedEpisodeIdForTest("/webtoon/18768/1587305"));
    }

    @Test
    public void ntkImageApiNumericEpisodeUsesUrlPathId() {
        assertEquals("232965", Manga.ntkApiEpisodeIdForTest("232965"));
        assertEquals("lz-beasts_that_cross_the_line-7021779758750226",
                Manga.ntkApiEpisodeIdForTest("lz-beasts_that_cross_the_line-7021779758750226"));
    }

    @Test
    public void ntkViewerImageApiUsesEmbeddedEpisodeIdWhenPathIdDiffers() {
        String body = "2f:[\"$\",\"$L31\",null,{\"domain\":\"webtoon\",\"episodeId\":\"1089010\"}]"
                + ",\"episodePath\":\"/webtoon/slug/854725\"";

        assertEquals("1089010", Manga.ntkViewerEmbeddedImageEpisodeIdForTest(body, "854725"));
    }

    @Test
    public void ntkViewerImageApiReadsEscapedEmbeddedEpisodeId() {
        String body = "{\\\"domain\\\":\\\"webtoon\\\",\\\"episodeId\\\":\\\"1089010\\\"}"
                + "{\\\"episodePath\\\":\\\"/webtoon/slug/854725\\\"}";

        assertEquals("1089010", Manga.ntkViewerEmbeddedImageEpisodeIdForTest(body, "854725"));
    }

    @Test
    public void ntkFullHtmlImagesWinOverTokenOnlyDirectPage() {
        String direct = "1:\"$Sreact.fragment\" {\"imagesToken\":\"token\","
                + "\"imageMetas\":[{\"page\":1}],\"episodePath\":\"/webtoon/840540/1546169\"}";
        String fallback = "<html><body><div class=\"vw-imgs\">"
                + "<img alt=\"page 1\" src=\"https://i.toonflix.app/board_uploads/2026/05/15/p001.png\">"
                + "<img alt=\"page 2\" src=\"https://i.toonflix.app/board_uploads/2026/05/15/p002.png\">"
                + "<img alt=\"page 3\" src=\"https://i.toonflix.app/board_uploads/2026/05/15/p003.png\">"
                + "</div></body></html>";

        assertTrue(Manga.shouldPreferNtkHtmlImagePageForTest(
                direct, fallback, "/webtoon/840540/1546169"));
    }

    @Test
    public void ntkBoardUploadAdsDoNotWinOverTokenOnlyDirectPage() {
        String direct = "1:\"$Sreact.fragment\" {\"imagesToken\":\"token\","
                + "\"imageMetas\":[{\"page\":1}],\"episodePath\":\"/webtoon/840540/1546169\"}";
        String fallback = "<html><body><img alt=\"banner\" "
                + "src=\"https://i.toonflix.app/board_uploads/2026/05/15/ad.png\"></body></html>";

        assertFalse(Manga.shouldPreferNtkHtmlImagePageForTest(
                direct, fallback, "/webtoon/840540/1546169"));
    }

    @Test
    public void ntkGeneratedShellIdentityWinsOverTokenOnlyDirectPage() {
        String direct = "1:\"$Sreact.fragment\" {\"imagesToken\":\"token\","
                + "\"imageMetas\":[{\"page\":1}],\"episodePath\":\"/webtoon/13708/1245755\"}";
        String fallback = "<html><body><script>self.__next_f.push([1,\"{"
                + "\\\"sourceWorkId\\\":\\\"13708\\\","
                + "\\\"imageMetas\\\":[{\\\"page\\\":1},{\\\"page\\\":2}],"
                + "\\\"imagesToken\\\":\\\"viewer-token\\\"}\"])</script></body></html>";

        assertTrue(Manga.shouldPreferNtkHtmlImagePageForTest(
                direct, fallback, "/webtoon/13708/1245755"));
    }

    @Test
    public void ntkViewerImageApiPrefersTokenEpisodeOverViewPingEpisode() {
        assertEquals("1542544", Manga.ntkViewerApiImageEpisodeIdForTest(
                "1542544", "", "1542544", "140318"));
        assertEquals("1542544", Manga.ntkViewerApiImageEpisodeIdForTest(
                "", "", "1542544", "140318"));
        assertEquals("140318", Manga.ntkViewerApiImageEpisodeIdForTest(
                "", "", "", "140318"));
        assertEquals("1165013", Manga.ntkViewerApiImageEpisodeIdForTest(
                "nv-849864-7", "1165013", "nv-849864-7", ""));
        assertEquals("1165013", Manga.ntkViewerApiImageEpisodeIdForTest(
                "nv-849864-7", "", "nv-849864-7", "1165013"));
    }

    @Test
    public void ntkViewerImageApiDoesNotRetryStaleKnownEpisodeWhenTokenIsAuthoritative() {
        assertFalse(Manga.shouldRetryNtkKnownImageEpisodeIdForTest(
                "1807424", "1807424", "1807424", "48388"));
        assertFalse(Manga.shouldRetryNtkKnownImageEpisodeIdForTest(
                "", "1807424", "1807424", "48388"));
        assertTrue(Manga.shouldRetryNtkKnownImageEpisodeIdForTest(
                "", "ntk-slug", "140318", "48388"));
        assertFalse(Manga.shouldRetryNtkKnownImageEpisodeIdForTest(
                "kp-68408465-68460644", "kp-68408465-68460644",
                "kp-68408465-68460644", "1153676", 108));
        assertFalse(Manga.shouldRetryNtkKnownImageEpisodeIdForTest(
                "1667148", "1667148", "1667148", "198888", 59));
    }

    @Test
    public void ntkViewerEmptyImageMetasAreConfirmedEmpty() {
        String body = "{\"imageMetas\":[],\"imagesToken\":\"viewer-token\",\"page\":1}";

        assertTrue(Manga.isNtkViewerImageMetasExplicitlyEmptyForTest(body));
        assertTrue(Manga.isNtkViewerConfirmedEmptyPayloadForTest(body, "/webtoon/slug/1"));
    }

    @Test
    public void ntkViewerNonEmptyImageMetasStillUseApi() {
        String body = "{\"imageMetas\":[{\"page\":1,\"width\":720}],\"imagesToken\":\"viewer-token\"}";

        org.junit.Assert.assertFalse(Manga.isNtkViewerImageMetasExplicitlyEmptyForTest(body));
        org.junit.Assert.assertFalse(Manga.isNtkViewerConfirmedEmptyPayloadForTest(body, "/webtoon/slug/1"));
    }

    @Test
    public void ntkViewerCommentOnlyPayloadIsConfirmedEmptyForEpisode() {
        String body = "{\"episodePath\":\"/webtoon/slug/854725\",\"page\":1,\"totalPages\":1,"
                + "\"totalRoots\":0,\"initial\":[],\"bestInitial\":[]}";

        assertTrue(Manga.isNtkViewerConfirmedEmptyPayloadForTest(body, "/webtoon/slug/854725"));
    }

    @Test
    public void ntkEmbeddedNumberedPageImagesAllowPlainNumericNames() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"images\":[\"https:\\/\\/i.toonflix.app\\/manhwa\\/25089\\/296849\\/001.jpg\"]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://i.toonflix.app/manhwa/25089/296849/001.jpg", images.get(0));
    }

    @Test
    public void ntkEmbeddedCurrentCdnImagesAreParsed() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"images\":[{\"page\":1,\"src\":\"https:\\/\\/www.pl3040.com\\/kr\\/\\/07\\/34911\\/1792086\\/J0sSOWegkucq.jpg\"}]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://www.pl3040.com/kr//07/34911/1792086/J0sSOWegkucq.jpg", images.get(0));
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
    public void ntkImageCountFallsBackToCanonicalEpisodeListEntry() {
        Title title = new Title("one punch", "", "", null, "349", 8605, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga canonical = new Manga(349, "275", "", MTitle.base_comic);
        canonical.setTitle(title);
        canonical.setNtkEpisodePath("/manhwa/8605/u-mou88jul-3akm");
        canonical.setNtkImageCount(21);
        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(canonical);
        title.setEps(episodes);
        Manga candidate = new Manga(349, "275", "", MTitle.base_comic);
        candidate.setTitle(title);

        assertEquals(21, candidate.getNtkImageCount());
    }

    @Test
    public void ntkApiFallbackOnlyColdProbesKnownNumericGeneratedUrls() {
        assertTrue(Manga.shouldProbeKnownGeneratedBeforeApiFallbackForTest(
                "/manhwa/36404/1801301", 34));
        assertTrue(Manga.shouldProbeKnownGeneratedBeforeApiFallbackForTest(
                "/webtoon/56792335/1196612", 32));

        assertFalse(Manga.shouldProbeKnownGeneratedBeforeApiFallbackForTest(
                "/manhwa/36404/1801301", 0));
        assertFalse(Manga.shouldProbeKnownGeneratedBeforeApiFallbackForTest(
                "/manhwa/36404/u-slug-1801301", 34));
        assertFalse(Manga.shouldProbeKnownGeneratedBeforeApiFallbackForTest(
                "/webtoon/56792335/u-slug-1196612", 32));
    }

    @Test
    public void ntkGeneratedModeDoesNotProbeBeforeApi() {
        assertFalse(Manga.shouldProbeGeneratedModeBeforeApiForTest(
                "/webtoon/68864262/1587238", 101));
        assertFalse(Manga.shouldProbeGeneratedModeBeforeApiForTest(
                "/webtoon/9713/916314", 43));
        assertFalse(Manga.shouldProbeGeneratedModeBeforeApiForTest(
                "/manhwa/36404/1801301", 34));
    }

    @Test
    public void ntkAppendUsesGeneratedFirstOnlyForLegacyNumericWebtoons() {
        assertTrue(Manga.shouldUseGeneratedAppendBeforeApi(
                MTitle.base_webtoon, "/webtoon/16527/1525931", 128));
        assertFalse(Manga.shouldUseGeneratedAppendBeforeApi(
                MTitle.base_webtoon, "/webtoon/68864262/1587238", 101));
        assertFalse(Manga.shouldUseGeneratedAppendBeforeApi(
                MTitle.base_webtoon, "/webtoon/16527/u-slug-1525931", 128));
        assertFalse(Manga.shouldUseGeneratedAppendBeforeApi(
                MTitle.base_webtoon, "/webtoon/16527/1525931", 0));
    }

    @Test
    public void ntkKpWebtoonEpisodesUseApiImagesWithoutGeneratedProbe() {
        assertTrue(Manga.shouldSkipNtkGeneratedForEpisodePathForTest(
                "/webtoon/61393986/kp-61393986-64942327"));
        assertFalse(Manga.shouldProbeKnownGeneratedBeforeApiFallbackForTest(
                "/webtoon/61393986/kp-61393986-64942327", 67));

        assertTrue(Manga.shouldSkipNtkGeneratedForEpisodePathForTest(
                "/webtoon/56792335/u-slug-1196612"));
    }

    @Test
    public void ntkEpisodeParserKeepsKpImageEpisodeMetadata() {
        List<Manga> episodes = NtkEpisodeParser.parseForTest(
                "<html><head>"
                        + "<link rel=\"preload\" href=\"https://i.toonflix.app/blacktoon/thumbs/13385.png?v2\" as=\"image\"/>"
                        + "</head><body>"
                        + "<a href=\"/webtoon/61393986/kp-61393986-64942327\">68화</a>"
                        + "<script>{\"id\":\"1377023\",\"sourceEpisodeId\":\"kp-61393986-64942327\","
                        + "\"imageCount\":67,\"sourceWorkId\":\"61393986\"}</script>"
                        + "</body></html>",
                "webtoon", "61393986", MTitle.base_webtoon);

        assertEquals(1, episodes.size());
        assertEquals("1377023", episodes.get(0).getNtkImageEpisodeId());
        assertEquals(67, episodes.get(0).getNtkImageCount());
    }

    @Test
    public void ntkEpisodeParserUsesNumericTitleIdForRefreshedSlugImageWorkId() throws Exception {
        Title numericApiTitle = new Title("slug title", "", "", null, "", 1930242432, MTitle.base_webtoon);
        numericApiTitle.setSourceSite("ntk");
        numericApiTitle.setPath("/webtoon/refreshed-slug");
        NtkEpisodeParser.ParseResult parsed = NtkEpisodeParser.parse(
                org.jsoup.Jsoup.parse("<a href=\"/webtoon/refreshed-slug/1039569\">1화</a>"),
                "webtoon", "refreshed-slug", MTitle.base_webtoon, numericApiTitle);

        assertEquals(1, parsed.episodes.size());
        assertEquals("1930242432", parsed.episodes.get(0).getNtkImageWorkId());

        Title slugOnlyTitle = Search.parseNtkApiTitlesForTest(
                "{\"works\":[{\"sourceWorkId\":\"u-moo205z1-yvf4\",\"title\":\"Slug Title\"}]}",
                MTitle.base_webtoon).get(0);
        NtkEpisodeParser.ParseResult slugOnlyParsed = NtkEpisodeParser.parse(
                org.jsoup.Jsoup.parse("<a href=\"/webtoon/u-moo205z1-yvf4/u-episode\">1화</a>"),
                "webtoon", "u-moo205z1-yvf4", MTitle.base_webtoon, slugOnlyTitle);

        assertEquals(1, slugOnlyParsed.episodes.size());
        assertEquals("", slugOnlyParsed.episodes.get(0).getNtkImageWorkId());
    }

    @Test
    public void ntkEpisodePathIsNotGuessedFromNumericIdentity() {
        Title title = new Title("one piece", "", "", null, "", 2, MTitle.base_comic);
        title.setSourceSite("ntk");
        Manga candidate = new Manga(1293, "", "", MTitle.base_comic);
        candidate.setTitle(title);
        candidate.setTitleId(title.getId());

        assertEquals(false, candidate.ensureNtkEpisodePathFromIdentity());
        assertEquals("", candidate.getNtkEpisodePath());
    }

    @Test
    public void ntkEpisodePathToleratesEpisodeListMutationDuringWarmup() {
        Title title = new Title("one punch", "", "", null, "349", 8605, MTitle.base_comic);
        title.setSourceSite("ntk");
        AtomicReference<List<Manga>> episodesRef = new AtomicReference<>();
        Manga mutating = new Manga(349, "275", "", MTitle.base_comic) {
            @Override
            public int getId() {
                List<Manga> episodes = episodesRef.get();
                if(episodes != null && episodes.size() == 2)
                    episodes.add(new Manga(348, "274", "", MTitle.base_comic));
                return super.getId();
            }
        };
        mutating.setTitle(title);
        Manga trailing = new Manga(348, "274", "", MTitle.base_comic);
        trailing.setTitle(title);
        ArrayList<Manga> episodes = new ArrayList<>(Arrays.asList(mutating, trailing));
        episodesRef.set(episodes);
        title.setEps(episodes);
        Manga candidate = new Manga(349, "275", "", MTitle.base_comic);
        candidate.setTitle(title);

        assertEquals("", candidate.getNtkEpisodePath());
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
    public void ntkEmbeddedCurrentCdnImagesWinOverBannerArrays() {
        List<String> images = Manga.ntkEmbeddedPageImagesForTest(
                "<script>{\"headerBanners\":[{\"imagePath\":\"https:\\/\\/i.toonflix.app\\/board_uploads\\/2026\\/05\\/15\\/ad.png\"}],"
                        + "\"images\":[{\"page\":1,\"src\":\"https:\\/\\/www.pl3040.com\\/kr\\/\\/07\\/34911\\/1792086\\/page-a.jpg\"}]}</script>");

        assertEquals(1, images.size());
        assertEquals("https://www.pl3040.com/kr//07/34911/1792086/page-a.jpg", images.get(0));
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
