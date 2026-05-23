package ml.melun.mangaview.runtime;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AppDispatchersTest {
    @Test
    public void imageWarmupPoolStaysBoundedForBackgroundPrefetch() {
        assertTrue(AppDispatcherPolicy.IMAGE_WARMUP_CORE_THREADS >= 1);
        assertTrue(AppDispatcherPolicy.IMAGE_WARMUP_MAX_THREADS >= 2);
        assertTrue(AppDispatcherPolicy.IMAGE_WARMUP_QUEUE_SIZE >= 64);
        assertTrue(AppDispatcherPolicy.IMAGE_WARMUP_MAX_THREADS <= 4);
        assertTrue(AppDispatcherPolicy.IMAGE_WARMUP_QUEUE_SIZE <= 96);
    }
}
