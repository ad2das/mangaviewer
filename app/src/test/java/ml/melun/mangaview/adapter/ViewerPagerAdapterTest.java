package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewerPagerAdapterTest {
    @Test
    public void validFragmentPositionRejectsOutOfRangePositions() {
        assertFalse(ViewerPagerAdapter.isValidFragmentPositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(ViewerPagerAdapter.isValidFragmentPositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validFragmentPositionRejectsMissingFragments() {
        assertFalse(ViewerPagerAdapter.isValidFragmentPositionForTest(null, 0));
        assertFalse(ViewerPagerAdapter.isValidFragmentPositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validFragmentPositionAcceptsExistingFragment() {
        assertTrue(ViewerPagerAdapter.isValidFragmentPositionForTest(Arrays.asList("a", "b"), 1));
    }
}
