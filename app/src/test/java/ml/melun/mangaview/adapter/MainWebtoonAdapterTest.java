package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainWebtoonAdapterTest {
    @Test
    public void validPositionRejectsOutOfRangePositions() {
        assertFalse(AdapterPositionGuard.isValidPositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(AdapterPositionGuard.isValidPositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validPositionRejectsMissingRows() {
        assertFalse(AdapterPositionGuard.isValidPositionForTest(null, 0));
        assertFalse(AdapterPositionGuard.isValidPositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validPositionAcceptsExistingRow() {
        assertTrue(AdapterPositionGuard.isValidPositionForTest(Arrays.asList("a", "b"), 1));
    }
}
