package ml.melun.mangaview.activity;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewerActivity2Test {
    @Test
    public void usablePageUrlRejectsMissingUrls() {
        assertFalse(ViewerActivity2.isUsablePageUrlForTest(null));
        assertFalse(ViewerActivity2.isUsablePageUrlForTest(""));
        assertFalse(ViewerActivity2.isUsablePageUrlForTest("   "));
    }

    @Test
    public void usablePageUrlAcceptsNonBlankUrls() {
        assertTrue(ViewerActivity2.isUsablePageUrlForTest("/image/1.jpg"));
    }
}
