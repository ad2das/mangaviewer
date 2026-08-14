package ml.melun.mangaview.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ml.melun.mangaview.R;

public class EpisodeRowViewTest {
    @Test
    public void physicalDragCancelsPressOnlyAfterTouchSlop() {
        assertFalse(EpisodeRowView.exceededTouchSlopForTest(3f, 4f, 5));
        assertTrue(EpisodeRowView.exceededTouchSlopForTest(4f, 4f, 5));
        assertTrue(EpisodeRowView.exceededTouchSlopForTest(1f, 0f, 0));
    }

    @Test
    public void spokenLabelContainsOnlyUserFacingEpisodeText() {
        assertEquals("1185화, 2026-08-14, 새 회차",
                EpisodeRowView.spokenLabelForTest("1185화", "2026-08-14", true));
        assertFalse(EpisodeRowView.spokenLabelForTest("1185화", "", false)
                .contains("episode:"));
        assertFalse(EpisodeRowView.spokenLabelForTest("1185화", "", false)
                .contains("/manhwa/"));
    }

    @Test
    public void rowActionsHaveDistinctDownloadAndRemovalLabels() {
        assertEquals(R.string.download_episode, EpisodeRowView.actionLabelForTest(true));
        assertEquals(R.string.episode_remove_download, EpisodeRowView.actionLabelForTest(false));
    }
}
