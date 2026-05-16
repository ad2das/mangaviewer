package ml.melun.mangaview.runtime;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AppDispatchersTest {
    @Test
    public void imageWarmupPoolAllowsConcurrentViewerPrefetch() {
        assertTrue(AppDispatcherPolicy.IMAGE_WARMUP_CORE_THREADS >= 3);
        assertTrue(AppDispatcherPolicy.IMAGE_WARMUP_MAX_THREADS >= 6);
        assertTrue(AppDispatcherPolicy.IMAGE_WARMUP_QUEUE_SIZE >= 160);
    }
}
