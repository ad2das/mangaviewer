package ml.melun.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DownloaderTest {
    @Test
    public void progressStepKeepsFractionalValues() {
        assertEquals(0.5f, Downloader.progressStepForTest(1000, 2000), 0.0001f);
        assertEquals(333.3333f, Downloader.progressStepForTest(1000, 3), 0.001f);
    }

    @Test
    public void progressStepRejectsEmptyWork() {
        assertEquals(0f, Downloader.progressStepForTest(1000, 0), 0.0001f);
        assertEquals(0f, Downloader.progressStepForTest(1000, -1), 0.0001f);
    }
}
