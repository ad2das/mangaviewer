package ml.melun.mangaview.glide;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class ViewerWarmupCachePolicyTest {
    @Test
    public void clearDecodedWorkKeepsBoundedDecodedCache() {
        assertFalse(ViewerWarmupCachePolicy.shouldEvictDecodedCacheWhenClearingWork());
    }
}
