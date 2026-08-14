package ml.melun.mangaview.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StripLayoutManagerTest {
    @Test
    public void measuredWindowHeightWinsAfterResize() {
        assertEquals(360, StripLayoutManager.extraLayoutSpacePx(360, 2_340, 1));
        assertEquals(720, StripLayoutManager.extraLayoutSpacePx(360, 2_340, 2));
    }

    @Test
    public void fallbackIsUsedOnlyBeforeFirstLayout() {
        assertEquals(2_340, StripLayoutManager.extraLayoutSpacePx(0, 2_340, 1));
        assertEquals(0, StripLayoutManager.extraLayoutSpacePx(0, 2_340, 0));
    }

    @Test
    public void invalidInputsFailClosed() {
        assertEquals(0, StripLayoutManager.extraLayoutSpacePx(-1, -1, 1));
        assertEquals(0, StripLayoutManager.extraLayoutSpacePx(360, 2_340, -1));
    }
}
