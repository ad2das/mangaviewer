package ml.melun.mangaview.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ViewerColdStateSnapshotTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void absentAndActuallyEmptyTreesAreCold() throws Exception {
        File absent = new File(temporaryFolder.getRoot(), "absent");
        File empty = temporaryFolder.newFolder("empty", "nested");

        assertTrue(ViewerColdStateSnapshot.isCacheTreeEmptyForTest(absent));
        assertTrue(ViewerColdStateSnapshot.isCacheTreeEmptyForTest(
                empty.getParentFile().getParentFile()));
    }

    @Test
    public void firstCacheFileRejectsColdWithoutInventoryingTheTree() throws Exception {
        File root = temporaryFolder.newFolder("warm-cache");
        File nested = new File(root, "a/b/c");
        assertTrue(nested.mkdirs());
        assertTrue(new File(nested, "image.bin").createNewFile());

        assertFalse(ViewerColdStateSnapshot.isCacheTreeEmptyForTest(root));
    }

    @Test
    public void inconclusiveDeepEmptyTreeFailsClosed() throws Exception {
        File root = temporaryFolder.newFolder("deep-cache");
        File cursor = root;
        for(int index = 0; index < 40; index++) {
            cursor = new File(cursor, "d" + index);
            assertTrue(cursor.mkdir());
        }

        assertFalse(ViewerColdStateSnapshot.isCacheTreeEmptyForTest(root));
    }

    @Test
    public void strictSpoolBytesAreIncludedInViewerDiskColdProof() throws Exception {
        File reader = temporaryFolder.newFolder("reader-cache");
        File glide = temporaryFolder.newFolder("glide-cache");
        File spool = temporaryFolder.newFolder("strict-spool");
        File activeBody = new File(spool, "strict-primary.tmp");
        try(FileOutputStream output = new FileOutputStream(activeBody)) {
            output.write(new byte[] { 1, 2, 3, 4, 5 });
        }

        assertArrayEquals(
                new long[] { 1L, 5L },
                ViewerColdStateSnapshot.combinedCacheStatsForTest(reader, glide, spool));
    }
}
