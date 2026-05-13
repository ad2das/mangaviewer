package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TitleAdapterTest {
    @Test
    public void validTitlePositionRejectsOutOfRangeRows() {
        assertFalse(TitleAdapter.isValidTitlePositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(TitleAdapter.isValidTitlePositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validTitlePositionRejectsMissingData() {
        assertFalse(TitleAdapter.isValidTitlePositionForTest(null, 0));
        assertFalse(TitleAdapter.isValidTitlePositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validTitlePositionAcceptsExistingRow() {
        assertTrue(TitleAdapter.isValidTitlePositionForTest(Arrays.asList("a", "b"), 1));
    }
}
