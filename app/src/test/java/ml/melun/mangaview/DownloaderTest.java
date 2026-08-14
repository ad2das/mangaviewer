package ml.melun.mangaview;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void imageDownloadParallelismIsBounded() {
        assertEquals(0, Downloader.imageDownloadParallelismForTest(0));
        assertEquals(1, Downloader.imageDownloadParallelismForTest(1));
        assertEquals(2, Downloader.imageDownloadParallelismForTest(100));
    }

    @Test
    public void imageTempFileUsesPartSuffixBesideFinalImage() {
        assertEquals("0001.jpg", Downloader.imageOutputNameForTest("0001"));
        assertEquals("0001.jpg.part", Downloader.imagePartOutputNameForTest("0001"));
    }

    @Test
    public void genericTempFileUsesPartSuffixBesideFinalFile() {
        assertEquals("thumb.webp", Downloader.fileOutputNameForTest("thumb", "webp"));
        assertEquals("thumb.webp.part", Downloader.filePartOutputNameForTest("thumb", "webp"));
    }

    @Test
    public void titleSummaryTempFileUsesPartSuffixBesideFinalFile() {
        assertEquals("title.gson.part", Downloader.titleSummaryPartNameForTest());
    }

    @Test
    public void writeTitleSummaryReplacesFileAtomically() throws Exception {
        File dir = Files.createTempDirectory("title-summary").toFile();
        File file = new File(dir, "title.gson");
        File temp = new File(dir, "title.gson.part");
        try {
            Downloader.writeTitleSummaryForTest(file, "{\"old\":true}");
            Downloader.writeTitleSummaryForTest(file, "{\"new\":true}");

            assertEquals("{\"new\":true}", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            assertFalse(temp.exists());
        } finally {
            file.delete();
            temp.delete();
            new File(dir, "title.gson.bak").delete();
            dir.delete();
        }
    }

    @Test
    public void stagedEpisodeReplacesExistingOnlyAfterStageIsComplete() throws Exception {
        File parent = Files.createTempDirectory("episode-stage").toFile();
        File existing = new File(parent, "0001.episode");
        File staged = new File(parent,
                Downloader.episodeStageNameForTest(existing.getName(), 123L));
        assertTrue(existing.mkdir());
        assertTrue(staged.mkdir());
        Files.write(new File(existing, "0000.jpg").toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(staged, "0000.jpg").toPath(), "new".getBytes(StandardCharsets.UTF_8));
        try {
            assertTrue(Downloader.publishStagedEpisodeForTest(existing, staged));
            assertEquals("new", new String(Files.readAllBytes(
                    new File(existing, "0000.jpg").toPath()), StandardCharsets.UTF_8));
            assertFalse(staged.exists());
        } finally {
            deleteTree(parent);
        }
    }

    @Test
    public void invalidStageNeverDeletesExistingEpisode() throws Exception {
        File parent = Files.createTempDirectory("episode-stage-invalid").toFile();
        File existing = new File(parent, "0001.episode");
        File missingStage = new File(parent, "missing-stage");
        assertTrue(existing.mkdir());
        Files.write(new File(existing, "0000.jpg").toPath(), "old".getBytes(StandardCharsets.UTF_8));
        try {
            assertFalse(Downloader.publishStagedEpisodeForTest(existing, missingStage));
            assertEquals("old", new String(Files.readAllBytes(
                    new File(existing, "0000.jpg").toPath()), StandardCharsets.UTF_8));
        } finally {
            deleteTree(parent);
        }
    }

    private static void deleteTree(File file) {
        if(file == null || !file.exists())
            return;
        File[] children = file.listFiles();
        if(children != null)
            for(File child : children)
                deleteTree(child);
        file.delete();
    }

    @Test
    public void retryableDownloadRunKeepsQueueFile() {
        assertEquals(false, Downloader.shouldDeleteQueueFileAfterRunForTest(false, 3));
    }

    @Test
    public void finishedDownloadRunDeletesQueueFile() {
        assertEquals(true, Downloader.shouldDeleteQueueFileAfterRunForTest(false, null));
        assertEquals(true, Downloader.shouldDeleteQueueFileAfterRunForTest(false, 1));
        assertEquals(true, Downloader.shouldDeleteQueueFileAfterRunForTest(true, null));
    }
}
