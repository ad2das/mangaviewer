package ml.melun.mangaview;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class UtilsTest {
    @Test
    public void readTextStreamUsesUtf8() {
        String text = "{\"title\":\"데스러버\"}";

        assertEquals(text, Utils.readTextStreamForTest(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void documentNamesSortWithNullsLast() {
        assertEquals(0, Utils.compareDocumentNamesForTest(null, null));
        assertEquals(1, Utils.compareDocumentNamesForTest(null, "0001.title"));
        assertEquals(-1, Utils.compareDocumentNamesForTest("0001.title", null));
        assertEquals(-1, Utils.compareDocumentNamesForTest("0001.title", "0002.title"));
    }

    @Test
    public void documentNamesSortNumericPrefixesNaturally() {
        assertEquals(-1, Integer.signum(Utils.compareDocumentNamesForTest("2.episode.2", "10.episode.10")));
        assertEquals(1, Integer.signum(Utils.compareDocumentNamesForTest("10.episode.10", "2.episode.2")));
    }

    @Test
    public void textBytesUseUtf8() {
        String text = "{\"favorite\":\"데스러버\"}";

        assertEquals(text, new String(Utils.utf8BytesForTest(text), StandardCharsets.UTF_8));
    }

    @Test
    public void numberFromStringHandlesMissingAndWholeNumberInput() {
        assertEquals(-1, Utils.getNumberFromString(null));
        assertEquals(-1, Utils.getNumberFromString(""));
        assertEquals(-1, Utils.getNumberFromString("abc123"));
        assertEquals(-1, Utils.getNumberFromString("999999999999999999999"));
        assertEquals(123, Utils.getNumberFromString("123"));
        assertEquals(123, Utils.getNumberFromString("123abc"));
    }

    @Test
    public void sampleBitmapDimensionsNeverDropBelowOnePixel() {
        assertEquals(1, Utils.sampleWidthForTest(100, 0));
        assertEquals(1, Utils.sampleHeightForTest(100, 100, 0));
        assertEquals(50, Utils.sampleHeightForTest(200, 100, 100));
    }

    @Test
    public void offlineEpisodesSkipIncompleteDownloadFolders() throws Exception {
        File root = Files.createTempDirectory("offline-root").toFile();
        File complete = new File(root, "0001.done.1");
        File incomplete = new File(root, "0002.partial.2");
        try {
            complete.mkdirs();
            incomplete.mkdirs();
            new File(incomplete, "downloading").createNewFile();

            List<File> episodes = Utils.getOfflineEpisodes(root.getAbsolutePath());

            assertEquals(1, episodes.size());
            assertEquals(complete.getName(), episodes.get(0).getName());
        } finally {
            new File(incomplete, "downloading").delete();
            incomplete.delete();
            complete.delete();
            root.delete();
        }
    }

    @Test
    public void offlineEpisodesSortNumericPrefixesNaturally() throws Exception {
        File root = Files.createTempDirectory("offline-sort").toFile();
        File second = new File(root, "2.episode.2");
        File tenth = new File(root, "10.episode.10");
        try {
            second.mkdirs();
            tenth.mkdirs();

            List<File> episodes = Utils.getOfflineEpisodes(root.getAbsolutePath());

            assertEquals(second.getName(), episodes.get(0).getName());
            assertEquals(tenth.getName(), episodes.get(1).getName());
        } finally {
            second.delete();
            tenth.delete();
            root.delete();
        }
    }
}
