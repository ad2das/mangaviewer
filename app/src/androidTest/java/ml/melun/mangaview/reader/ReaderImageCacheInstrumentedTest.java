package ml.melun.mangaview.reader;

import static org.junit.Assert.assertEquals;

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
}
