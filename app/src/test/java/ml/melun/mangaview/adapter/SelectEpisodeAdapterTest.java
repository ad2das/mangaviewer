package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelectEpisodeAdapterTest {
    @Test
    public void validSelectionPositionRejectsOutOfRangePositions() {
        assertFalse(SelectEpisodeAdapter.isValidSelectionPositionForTest(Arrays.asList("a", "b"), new boolean[] { false, false }, -1));
        assertFalse(SelectEpisodeAdapter.isValidSelectionPositionForTest(Arrays.asList("a", "b"), new boolean[] { false, false }, 2));
    }

    @Test
    public void validSelectionPositionRejectsMissingOrMismatchedState() {
        assertFalse(SelectEpisodeAdapter.isValidSelectionPositionForTest(null, new boolean[] { false }, 0));
        assertFalse(SelectEpisodeAdapter.isValidSelectionPositionForTest(Collections.singletonList("a"), null, 0));
        assertFalse(SelectEpisodeAdapter.isValidSelectionPositionForTest(Arrays.asList("a", "b"), new boolean[] { false }, 1));
    }

    @Test
    public void validSelectionPositionAcceptsExistingRow() {
        assertTrue(SelectEpisodeAdapter.isValidSelectionPositionForTest(Arrays.asList("a", "b"), new boolean[] { false, true }, 1));
    }
}
