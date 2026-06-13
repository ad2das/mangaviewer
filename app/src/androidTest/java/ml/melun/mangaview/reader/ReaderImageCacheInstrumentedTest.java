package ml.melun.mangaview.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;

public class ReaderImageCacheInstrumentedTest {
    @Test
    public void extractsRealImageFromHtmlWithoutUsingPageChromeImages() {
        String source = "https://i1.imgcloud18.com/12683/fee9b0d1_42_0.jpg";
        String html = "<html><body>"
                + "<img src=\"https://example.com/logo.png\">"
                + "<img src=\"https://example.com/data/banner/20.jpg\">"
                + "<img data-original=\"https://cdn.example.com/comic/12683/fee9b0d1_42_0.jpg\">"
                + "</body></html>";

        List<String> candidates = ReaderImageCache.extractImageCandidatesForTest(
                html,
                source,
                MTitle.base_comic);

        assertEquals(1, candidates.size());
        assertEquals("https://cdn.example.com/comic/12683/fee9b0d1_42_0.jpg", candidates.get(0));
    }

    @Test
    public void ntkHtmlFallbackKeepsOnlyNtkImageHosts() {
        String source = "https://i.toonflix.app/3c161e656f947bc5bce35f9c73a7b8f1.jpg";
        String html = "<html><body>"
                + "<img src=\"https://image-comic.pstatic.net/webtoon/849864/7/001.jpg\">"
                + "<img src=\"https://i.toonflix.app/webtoon_uploads/page001.jpg\">"
                + "</body></html>";

        List<String> candidates = ReaderImageCache.extractImageCandidatesForTest(
                html,
                source,
                MTitle.base_webtoon);

        assertFalse(candidates.contains("https://image-comic.pstatic.net/webtoon/849864/7/001.jpg"));
        assertEquals(1, candidates.size());
        assertEquals("https://i.toonflix.app/webtoon_uploads/page001.jpg", candidates.get(0));
    }
}
