package ml.melun.mangaview.repository;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DownloadRepositoryTest {
    @Test
    public void queuePayloadUsesTempFileBesideFinalFile() {
        assertEquals("queue.json.tmp", DownloadRepository.queueTempFileNameForTest("queue.json"));
    }

    @Test
    public void writeQueuePayloadReplacesFileAtomically() throws Exception {
        File dir = Files.createTempDirectory("download-queue").toFile();
        File file = new File(dir, "queue.json");
        File temp = new File(dir, "queue.json.tmp");
        try {
            DownloadRepository.writeQueuePayloadForTest(file, "{\"old\":true}");
            DownloadRepository.writeQueuePayloadForTest(file, "{\"new\":true}");

            assertEquals("{\"new\":true}", new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(temp.exists());
        } finally {
            file.delete();
            temp.delete();
            dir.delete();
        }
    }
}
