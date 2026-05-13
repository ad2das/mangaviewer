package ml.melun.mangaview;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

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

    @Test
    public void fileExtensionIgnoresQueryAndFragment() {
        assertEquals("jpg", Downloader.fileExtensionForTest("https://example.com/image.jpg?token=abc#part"));
        assertEquals("webp", Downloader.fileExtensionForTest("https://example.com/a.b/image.webp"));
    }

    @Test
    public void fileExtensionFallsBackWhenMissingOrUnsafe() {
        assertEquals("jpg", Downloader.fileExtensionForTest("https://example.com/image"));
        assertEquals("jpg", Downloader.fileExtensionForTest("https://example.com/image."));
        assertEquals("jpg", Downloader.fileExtensionForTest(null));
    }

    @Test
    public void payloadEncodingUsesUtf8() {
        String payload = "{\"name\":\"데스러버\"}\n[0,1]";

        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), Downloader.encodePayloadForTest(payload));
        assertEquals(payload, Downloader.decodePayloadForTest(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
