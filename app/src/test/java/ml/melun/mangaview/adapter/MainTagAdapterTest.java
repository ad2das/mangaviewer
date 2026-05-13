package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainTagAdapterTest {
    @Test
    public void validTagPositionRejectsOutOfRangePositions() {
        boolean[] selected = new boolean[] { false, false };

        assertFalse(MainTagAdapter.isValidTagPositionForTest(Arrays.asList("a", "b"), selected, -1));
        assertFalse(MainTagAdapter.isValidTagPositionForTest(Arrays.asList("a", "b"), selected, 2));
    }

    @Test
    public void validTagPositionRejectsMismatchedSelectionState() {
        assertFalse(MainTagAdapter.isValidTagPositionForTest(Arrays.asList("a", "b"), new boolean[] { false }, 1));
        assertFalse(MainTagAdapter.isValidTagPositionForTest(Collections.singletonList("a"), null, 0));
    }

    @Test
    public void validTagPositionAcceptsMatchingState() {
        assertTrue(MainTagAdapter.isValidTagPositionForTest(Arrays.asList("a", "b"), new boolean[] { false, true }, 1));
    }
}
