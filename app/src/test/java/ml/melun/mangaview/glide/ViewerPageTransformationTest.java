package ml.melun.mangaview.glide;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewerPageTransformationTest {
    @Test
    public void autoSplitAcceptsNearSquareSpreadsWithMargins() {
        assertTrue(ViewerPageTransformation.shouldAutoSplitForTest(1000, 1000));
        assertTrue(ViewerPageTransformation.shouldAutoSplitForTest(950, 1000));
        assertTrue(ViewerPageTransformation.shouldAutoSplitForTest(1800, 1200));
    }

    @Test
    public void autoSplitRejectsNormalPortraitPages() {
        assertFalse(ViewerPageTransformation.shouldAutoSplitForTest(800, 1000));
        assertFalse(ViewerPageTransformation.shouldAutoSplitForTest(700, 1000));
        assertFalse(ViewerPageTransformation.shouldAutoSplitForTest(0, 1000));
        assertFalse(ViewerPageTransformation.shouldAutoSplitForTest(1000, 0));
    }
}
