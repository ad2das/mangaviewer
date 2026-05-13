package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainAdapterTest {
    @Test
    public void validDataPositionRejectsOutOfRangePositions() {
        assertFalse(MainAdapter.isValidDataPositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(MainAdapter.isValidDataPositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validDataPositionRejectsMissingData() {
        assertFalse(MainAdapter.isValidDataPositionForTest(null, 0));
        assertFalse(MainAdapter.isValidDataPositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validDataPositionAcceptsExistingItem() {
        assertTrue(MainAdapter.isValidDataPositionForTest(Arrays.asList("a", "b"), 1));
    }
}
