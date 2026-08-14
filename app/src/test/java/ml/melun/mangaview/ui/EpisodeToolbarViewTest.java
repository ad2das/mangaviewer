package ml.melun.mangaview.ui;

import org.junit.Test;

import ml.melun.mangaview.R;

import static org.junit.Assert.assertEquals;

public final class EpisodeToolbarViewTest {
    @Test
    public void physicalHitTargetsRemainUnchanged() {
        assertEquals(0, EpisodeToolbarView.slotForPositionForTest(20f, 360f, 1f));
        assertEquals(-1, EpisodeToolbarView.slotForPositionForTest(100f, 360f, 1f));
        assertEquals(1, EpisodeToolbarView.slotForPositionForTest(216f, 360f, 1f));
        assertEquals(2, EpisodeToolbarView.slotForPositionForTest(264f, 360f, 1f));
        assertEquals(3, EpisodeToolbarView.slotForPositionForTest(312f, 360f, 1f));
    }

    @Test
    public void accessibilityActionsRouteToEveryToolbarSlot() {
        assertEquals(0, EpisodeToolbarView.slotForAccessibilityActionForTest(
                R.id.accessibility_episode_toolbar_back));
        assertEquals(1, EpisodeToolbarView.slotForAccessibilityActionForTest(
                R.id.accessibility_episode_toolbar_favorite));
        assertEquals(2, EpisodeToolbarView.slotForAccessibilityActionForTest(
                R.id.accessibility_episode_toolbar_download));
        assertEquals(3, EpisodeToolbarView.slotForAccessibilityActionForTest(
                R.id.accessibility_episode_toolbar_more));
        assertEquals(-1, EpisodeToolbarView.slotForAccessibilityActionForTest(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
    }

    @Test
    public void favoriteActionReflectsCurrentState() {
        assertEquals(R.string.episode_toolbar_add_favorite,
                EpisodeToolbarView.favoriteActionLabelForTest(false));
        assertEquals(R.string.episode_toolbar_remove_favorite,
                EpisodeToolbarView.favoriteActionLabelForTest(true));
    }
}
