package ml.melun.mangaview.glide;

import android.util.Log;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CustomGlideModuleTest {
    @Test
    public void expectedImageMissesStayOutOfWarningLogs() {
        assertEquals(Log.ERROR, CustomGlideModule.glideLogLevelForTest());
    }
}
