package ml.melun.mangaview.repository;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

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
