package ml.melun.mangaview.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpisodeRowViewTest {
    @Test
    public void physicalDragCancelsPressOnlyAfterTouchSlop() {
        assertFalse(EpisodeRowView.exceededTouchSlopForTest(3f, 4f, 5));
        assertTrue(EpisodeRowView.exceededTouchSlopForTest(4f, 4f, 5));
        assertTrue(EpisodeRowView.exceededTouchSlopForTest(1f, 0f, 0));
    }
}
