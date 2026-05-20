package ml.melun.mangaview.ui;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class ViewerClippedImageViewTest {
    @Test
    public void visibleClipMapsToSourceWindowAtViewerScale() {
        int[] rects = ViewerClippedImageView.computeVisibleRectsForTest(1000, 3000, 500, 1500, 250, 750);

        assertArrayEquals(new int[] { 0, 500, 1000, 1500, 0, 250, 500, 750 }, rects);
    }

    @Test
    public void clipsOutsideImageDrawAreaAreIgnored() {
        assertNull(ViewerClippedImageView.computeVisibleRectsForTest(1000, 3000, 500, 1500, 1600, 1700));
    }
}
