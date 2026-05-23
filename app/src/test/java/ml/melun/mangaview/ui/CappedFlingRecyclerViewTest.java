package ml.melun.mangaview.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CappedFlingRecyclerViewTest {
    @Test
    public void flingVelocityIsCappedSymmetrically() {
        assertEquals(6500, CappedFlingRecyclerView.clampFlingVelocityForTest(12000));
        assertEquals(-6500, CappedFlingRecyclerView.clampFlingVelocityForTest(-12000));
        assertEquals(3200, CappedFlingRecyclerView.clampFlingVelocityForTest(3200));
    }
}
