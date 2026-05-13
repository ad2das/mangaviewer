package ml.melun.mangaview;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppUpdateManagerTest {
    @Test
    public void releaseEndpointUsesMainLatestTag() throws Exception {
        Field field = AppUpdateManager.class.getDeclaredField("LATEST_RELEASE_API_URL");
        field.setAccessible(true);

        assertEquals(
                "https://api.github.com/repos/ad2das/mangaviewer/releases/tags/main-latest",
                field.get(null)
        );
    }

    @Test
    public void releaseVersionFallbackUsesMainLatestAsset() throws Exception {
        Field field = AppUpdateManager.class.getDeclaredField("RELEASE_VERSION_URL");
        field.setAccessible(true);

        assertEquals(
                "https://github.com/ad2das/mangaviewer/releases/download/main-latest/version.json",
                field.get(null)
        );
    }

    @Test
    public void completeDownloadRequiresExpectedLengthWhenKnown() {
        assertTrue(AppUpdateManager.isCompleteDownloadForTest(120, 120));
        assertFalse(AppUpdateManager.isCompleteDownloadForTest(119, 120));
        assertTrue(AppUpdateManager.isCompleteDownloadForTest(1, -1));
        assertFalse(AppUpdateManager.isCompleteDownloadForTest(0, -1));
    }
}
