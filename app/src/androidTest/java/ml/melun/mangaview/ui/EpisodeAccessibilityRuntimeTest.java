package ml.melun.mangaview.ui;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class EpisodeAccessibilityRuntimeTest {
    @Test
    public void toolbarPublishesAndDispatchesFourNamedActions() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<AccessibilityNodeInfo.AccessibilityAction>> actions =
                new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EpisodeToolbarView toolbar = new EpisodeToolbarView(context);
            toolbar.setActions(new EpisodeToolbarView.Actions() {
                @Override public void onBack() { calls.addAndGet(1); }
                @Override public void onFavorite() { calls.addAndGet(10); }
                @Override public void onDownload() { calls.addAndGet(100); }
                @Override public void onMore(android.view.View anchor) { calls.addAndGet(1000); }
            });
            toolbar.setFavorite(true);
            AccessibilityNodeInfo info = initializedNode(toolbar);
            assertEquals("회차 화면 도구 모음", info.getContentDescription());
            actions.set(new ArrayList<>(info.getActionList()));
            info.recycle();
            assertTrue(toolbar.performAccessibilityAction(
                    R.id.accessibility_episode_toolbar_back, null));
            assertTrue(toolbar.performAccessibilityAction(
                    R.id.accessibility_episode_toolbar_favorite, null));
            assertTrue(toolbar.performAccessibilityAction(
                    R.id.accessibility_episode_toolbar_download, null));
            assertTrue(toolbar.performAccessibilityAction(
                    R.id.accessibility_episode_toolbar_more, null));
        });

        assertEquals(1111, calls.get());
        assertEquals("뒤로 가기", labelFor(actions.get(), R.id.accessibility_episode_toolbar_back));
        assertEquals("즐겨찾기에서 제거",
                labelFor(actions.get(), R.id.accessibility_episode_toolbar_favorite));
        assertEquals("회차 다운로드",
                labelFor(actions.get(), R.id.accessibility_episode_toolbar_download));
        assertEquals("더보기", labelFor(actions.get(), R.id.accessibility_episode_toolbar_more));
    }

    @Test
    public void rowSeparatesOpenAndDownloadWithoutSpeakingAutomationPath() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        AtomicReference<AccessibilityNodeInfo> node = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EpisodeRowView row = new EpisodeRowView(context);
            row.setSelectedStateLabel(R.string.episode_current_state);
            row.setOnClickListener(view -> opens.incrementAndGet());
            row.setOnActionClickListener(downloads::incrementAndGet);
            row.bind("1185화", "2026-08-14", true, true, true,
                    true, true, true);
            row.setEpisodeIdentity("/manhwa/one-piece/1185");
            assertEquals("1185화, 2026-08-14, 새 회차", row.getContentDescription());
            assertEquals("/manhwa/one-piece/1185",
                    row.getTag(R.id.episode_automation_identity));
            node.set(initializedNode(row));
            assertTrue(row.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, null));
            assertTrue(row.performAccessibilityAction(
                    R.id.accessibility_episode_row_action, null));
        });

        AccessibilityNodeInfo info = node.get();
        assertEquals("1185화, 2026-08-14, 새 회차", info.getContentDescription());
        assertFalse(info.getContentDescription().toString().contains("/manhwa/"));
        assertTrue(info.isSelected());
        assertEquals("현재 읽는 회차",
                AccessibilityNodeInfoCompat.wrap(info).getStateDescription());
        assertEquals("회차 열기", labelFor(info.getActionList(), AccessibilityNodeInfo.ACTION_CLICK));
        assertEquals("에피소드 다운로드",
                labelFor(info.getActionList(), R.id.accessibility_episode_row_action));
        assertEquals(1, opens.get());
        assertEquals(1, downloads.get());
        info.recycle();
    }

    @Test
    public void unselectedRowClearsStateAndExposesRemovalAction() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<AccessibilityNodeInfo> node = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EpisodeRowView row = new EpisodeRowView(context);
            row.bind("1화", "", false, true, false, false, true, false);
            node.set(initializedNode(row));
        });

        AccessibilityNodeInfo info = node.get();
        assertFalse(info.isSelected());
        assertNull(AccessibilityNodeInfoCompat.wrap(info).getStateDescription());
        assertEquals("저장한 회차 삭제",
                labelFor(info.getActionList(), R.id.accessibility_episode_row_action));
        info.recycle();
    }

    private static String labelFor(List<AccessibilityNodeInfo.AccessibilityAction> actions, int id) {
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action.getId() == id && action.getLabel() != null)
                return action.getLabel().toString();
        }
        return null;
    }

    private static AccessibilityNodeInfo initializedNode(android.view.View view) {
        // createAccessibilityNodeInfo() intentionally returns a reduced snapshot for a view that
        // is not attached to a ViewRoot. Invoke the same production initialization callback on a
        // fresh platform node so this focused test does not need to launch the whole application.
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        view.onInitializeAccessibilityNodeInfo(info);
        return info;
    }
}
