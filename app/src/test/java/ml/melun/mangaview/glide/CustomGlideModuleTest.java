package ml.melun.mangaview.glide;

import android.util.Log;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CustomGlideModuleTest {
    @Test
    public void expectedImageMissesStayOutOfWarningLogs() {
        assertEquals(Log.ERROR, CustomGlideModule.glideLogLevelForTest());
    }

    @Test
    public void viewerDiskCacheKeepsEnoughPagesForColdContinue() {
        long size = CustomGlideModule.viewerDiskCacheSizeBytesForTest();
        assertTrue(size >= 128L * 1024L * 1024L);
        assertTrue(size <= 256L * 1024L * 1024L);
    }
}
