package ml.melun.mangaview.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppWindowSizePolicyTest {
    @Test public void fullHeightIsNotCompact() {
        assertFalse(AppWindowSizePolicy.isCompactHeight(2200, 3f));
        assertFalse(AppWindowSizePolicy.isUltraCompactHeight(2200, 3f));
    }

    @Test public void halfHeightIsCompactButNotUltraCompact() {
        assertTrue(AppWindowSizePolicy.isCompactHeight(1050, 3f));
        assertFalse(AppWindowSizePolicy.isUltraCompactHeight(1050, 3f));
    }

    @Test public void veryShortSplitWindowIsUltraCompact() {
        assertTrue(AppWindowSizePolicy.isCompactHeight(600, 3f));
        assertTrue(AppWindowSizePolicy.isUltraCompactHeight(600, 3f));
    }

    @Test public void unknownDimensionsFailOpenToNormalLayout() {
        assertFalse(AppWindowSizePolicy.isCompactHeight(0, 3f));
        assertFalse(AppWindowSizePolicy.isUltraCompactHeight(600, 0f));
    }
}
