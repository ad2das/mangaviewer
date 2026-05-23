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
    public void viewerDiskCacheStaysUnderAndroidLowStoragePurgeQuota() {
        long size = CustomGlideModule.viewerDiskCacheSizeBytesForTest();
        assertTrue(size >= 16L * 1024L * 1024L);
        assertTrue(size <= 32L * 1024L * 1024L);
    }
}
