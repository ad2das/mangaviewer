package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpisodeAdapterTest {
    @Test
    public void validEpisodePositionRejectsHeaderAndOutOfRangeRows() {
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 0));
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 3));
    }

    @Test
    public void validEpisodePositionRejectsMissingData() {
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(null, 1));
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Collections.emptyList(), 1));
    }

    @Test
    public void validEpisodePositionAcceptsExistingEpisodeRow() {
        assertTrue(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 2));
    }
}
