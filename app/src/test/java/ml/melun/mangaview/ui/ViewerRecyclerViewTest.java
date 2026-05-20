package ml.melun.mangaview.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewerRecyclerViewTest {
    @Test
    public void capVelocityKeepsSmallFlingUnchanged() {
        assertEquals(2400, ViewerRecyclerView.capVelocityForTest(2400, 5200));
        assertEquals(-2400, ViewerRecyclerView.capVelocityForTest(-2400, 5200));
    }

    @Test
    public void capVelocityLimitsLargeFlingMagnitude() {
        assertEquals(5200, ViewerRecyclerView.capVelocityForTest(18000, 5200));
        assertEquals(-5200, ViewerRecyclerView.capVelocityForTest(-18000, 5200));
    }

    @Test
    public void maxFlingVelocityScalesWithDensity() {
        assertEquals(3200, ViewerRecyclerView.maxViewerFlingVelocityForTest(1f));
        assertEquals(8800, ViewerRecyclerView.maxViewerFlingVelocityForTest(2.75f));
    }
}
