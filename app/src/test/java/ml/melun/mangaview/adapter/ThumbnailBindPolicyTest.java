package ml.melun.mangaview.adapter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThumbnailBindPolicyTest {
    @Test
    public void skipDeferredBindWhenSameOrPendingKeyAlreadyAssigned() {
        assertTrue(ThumbnailBindPolicy.shouldSkipDeferredBind("image-key", "image-key"));
        assertTrue(ThumbnailBindPolicy.shouldSkipDeferredBind("pending:image-key", "image-key"));
        assertFalse(ThumbnailBindPolicy.shouldSkipDeferredBind("other-key", "image-key"));
    }

    @Test
    public void clearBeforeDeferredBindOnlyForLoadedRealImageTags() {
        assertFalse(ThumbnailBindPolicy.shouldClearBeforeDeferredBind("image-key", false));
        assertFalse(ThumbnailBindPolicy.shouldClearBeforeDeferredBind("pending:image-key", true));
        assertFalse(ThumbnailBindPolicy.shouldClearBeforeDeferredBind(ThumbnailBindPolicy.TAG_PLACEHOLDER, true));
        assertFalse(ThumbnailBindPolicy.shouldClearBeforeDeferredBind(ThumbnailBindPolicy.TAG_EMPTY, true));
        assertFalse(ThumbnailBindPolicy.shouldClearBeforeDeferredBind(null, true));
        assertTrue(ThumbnailBindPolicy.shouldClearBeforeDeferredBind("image-key", true));
    }

    @Test
    public void pendingKeyPrefixesStableModelKey() {
        assertEquals("pending:image-key", ThumbnailBindPolicy.pendingKey("image-key"));
        assertEquals("pending:", ThumbnailBindPolicy.pendingKey(null));
    }
}
