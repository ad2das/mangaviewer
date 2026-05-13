package ml.melun.mangaview;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

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
}
