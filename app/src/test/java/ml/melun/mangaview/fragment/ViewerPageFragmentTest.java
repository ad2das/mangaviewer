package ml.melun.mangaview.fragment;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewerPageFragmentTest {
    @Test
    public void imageTargetRejectsMissingImage() {
        assertEquals("", ViewerPageFragment.imageTargetForTest(null));
        assertEquals("", ViewerPageFragment.imageTargetForTest("   "));
    }

    @Test
    public void imageTargetKeepsOfflinePath() {
        assertEquals("/storage/page.jpg", ViewerPageFragment.imageTargetForTest("/storage/page.jpg"));
    }
}
