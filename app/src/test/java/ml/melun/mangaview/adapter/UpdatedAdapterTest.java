package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdatedAdapterTest {
    @Test
    public void validUpdatedListPositionRejectsOutOfRangePositions() {
        assertFalse(UpdatedAdapter.isValidUpdatedListPositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(UpdatedAdapter.isValidUpdatedListPositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validUpdatedListPositionRejectsMissingData() {
        assertFalse(UpdatedAdapter.isValidUpdatedListPositionForTest(null, 0));
        assertFalse(UpdatedAdapter.isValidUpdatedListPositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validUpdatedListPositionAcceptsExistingRow() {
        assertTrue(UpdatedAdapter.isValidUpdatedListPositionForTest(Arrays.asList("a", "b"), 1));
    }
}
