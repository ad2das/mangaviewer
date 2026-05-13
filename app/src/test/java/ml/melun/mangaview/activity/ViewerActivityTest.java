package ml.melun.mangaview.activity;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewerActivityTest {
    @Test
    public void validEpisodePickerPositionRejectsOutOfRangeRows() {
        assertFalse(ViewerActivity.isValidEpisodePickerPositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(ViewerActivity.isValidEpisodePickerPositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validEpisodePickerPositionRejectsMissingData() {
        assertFalse(ViewerActivity.isValidEpisodePickerPositionForTest(null, 0));
        assertFalse(ViewerActivity.isValidEpisodePickerPositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validEpisodePickerPositionAcceptsExistingRow() {
        assertTrue(ViewerActivity.isValidEpisodePickerPositionForTest(Arrays.asList("a", "b"), 1));
    }
}
