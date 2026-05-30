package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderSurfaceViewTest {
    @Test
    public void shortFastSwipeStartsFling() {
        assertTrue(ReaderSurfaceView.shouldStartFlingForTest(42f, 2400, 50, 16));
    }

    @Test
    public void tapSizedMovementDoesNotStartFling() {
        assertFalse(ReaderSurfaceView.shouldStartFlingForTest(8f, 2400, 50, 16));
    }

    @Test
    public void slowDragDoesNotStartFling() {
        assertFalse(ReaderSurfaceView.shouldStartFlingForTest(80f, 30, 50, 16));
    }
}
