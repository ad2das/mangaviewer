package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainUpdatedAdapterTest {
    @Test
    public void validUpdatedPositionRejectsOutOfRangePositions() {
        assertFalse(MainUpdatedAdapter.isValidUpdatedPositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(MainUpdatedAdapter.isValidUpdatedPositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validUpdatedPositionRejectsMissingData() {
        assertFalse(MainUpdatedAdapter.isValidUpdatedPositionForTest(null, 0));
        assertFalse(MainUpdatedAdapter.isValidUpdatedPositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validUpdatedPositionAcceptsExistingRow() {
        assertTrue(MainUpdatedAdapter.isValidUpdatedPositionForTest(Arrays.asList("a", "b"), 1));
    }
}
