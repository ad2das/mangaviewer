package ml.melun.mangaview.repository;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CacheFileStoreTest {
    @Test
    public void readUtf8TextHandlesShortReads() throws Exception {
        String text = "{\"title\":\"데스러버\",\"pages\":[1,2,3]}";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        assertEquals(text, CacheFileStore.readUtf8TextForTest(new OneByteInputStream(bytes)));
    }

    @Test
    public void cacheFileNamesDoNotCollideForSimilarKeys() {
        String first = CacheFileStore.fileNameForKeyForTest("viewer/a?episode=1");
        String second = CacheFileStore.fileNameForKeyForTest("viewer_a_episode_1");

        org.junit.Assert.assertNotEquals(first, second);
    }

    @Test
    public void writeUtf8TextReplacesFileContents() throws Exception {
        File file = File.createTempFile("cache", ".json");
        try {
            CacheFileStore.writeUtf8TextForTest(file, "{\"old\":true}");
            CacheFileStore.writeUtf8TextForTest(file, "{\"title\":\"데스러버\"}");

            assertEquals("{\"title\":\"데스러버\"}", CacheFileStore.readUtf8TextForTest(new java.io.FileInputStream(file)));
        } finally {
            file.delete();
        }
    }

    @Test
    public void memoryCacheStoresRecentStructuredCacheText() {
        CacheFileStore.clearMemoryForTest();
        CacheFileStore.rememberMemoryForTest("episodeSnapshot", "{\"ok\":true}");

        assertEquals("{\"ok\":true}", CacheFileStore.readMemoryForTest("episodeSnapshot"));
    }

    @Test
    public void memoryCacheEvictsOldStructuredCacheText() {
        CacheFileStore.clearMemoryForTest();
        for(int i = 0; i < 65; i++)
            CacheFileStore.rememberMemoryForTest("key" + i, "value" + i);

        assertNull(CacheFileStore.readMemoryForTest("key0"));
        assertEquals("value64", CacheFileStore.readMemoryForTest("key64"));
    }

    private static final class OneByteInputStream extends InputStream {
        private final byte[] bytes;
        private int index;

        OneByteInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() throws IOException {
            return index < bytes.length ? bytes[index++] & 0xff : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if(index >= bytes.length)
                return -1;
            buffer[offset] = bytes[index++];
            return 1;
        }
    }
}
