package ml.melun.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import ml.melun.mangaview.mangaview.MTitle;

public class FirebaseSyncManagerTest {
    @Test
    public void newerGenerationMakesEarlierResultsStale() {
        FirebaseSyncManager.SyncGenerationGate gate =
                new FirebaseSyncManager.SyncGenerationGate();

        long first = gate.begin();
        long second = gate.begin();

        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
        gate.invalidate();
        assertFalse(gate.isCurrent(second));
    }

    @Test
    public void publicationRequiresTheSameSignedInIdentity() {
        FirebaseSyncManager.SyncGenerationGate gate =
                new FirebaseSyncManager.SyncGenerationGate();
        long generation = gate.begin();

        assertTrue(gate.canPublish(generation, "user-a", "user-a"));
        assertFalse(gate.canPublish(generation, "user-a", "user-b"));
        assertFalse(gate.canPublish(generation - 1L, "user-a", "user-a"));
    }

    @Test
    public void workerBackfillSnapshotCannotMutateTheLiveRecentCollection() {
        MTitle live = title(42, 7, -1, 0);
        List<MTitle> liveRecents = new ArrayList<>(Collections.singletonList(live));

        Preference.RecentProgressBackfillResult workerResult =
                Preference.backfillRecentProgressSnapshot(
                        null, liveRecents, 30, () -> false);

        assertEquals(1, workerResult.titles.size());
        assertNotSame(live, workerResult.titles.get(0));
        workerResult.titles.get(0).setReadingProgress(7, 3, 12);
        assertEquals(-1, live.getBookmarkEpisodeIndex());
        assertEquals(0, live.getEpisodeCount());
    }

    @Test
    public void firebaseSuccessListenerOnlySchedulesTheDataLane() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/ml/melun/mangaview/FirebaseSyncManager.java").toPath()),
                StandardCharsets.UTF_8);

        assertTrue(source.contains(
                ".addOnSuccessListener(snapshot -> handleDownloadedState("));
        assertTrue(source.contains(
                "submitSyncWork(generation, uid, afterSync, () ->"));
        int callbackStart = source.indexOf("stateDoc(uid).get(Source.SERVER)");
        int callbackEnd = source.indexOf("private void handleDownloadedState", callbackStart);
        String firestoreCallback = source.substring(callbackStart, callbackEnd);
        assertFalse(firestoreCallback.contains("readTitleList("));
        assertFalse(firestoreCallback.contains("backfillRecentProgress"));
        assertFalse(firestoreCallback.contains("PreferenceStore.set"));
    }

    private static MTitle title(int id, int bookmarkId, int bookmarkIndex, int episodeCount) {
        MTitle title = new MTitle(
                "title",
                id,
                "thumb",
                "author",
                Collections.emptyList(),
                "release",
                MTitle.base_comic);
        title.setSourceSite("wfwf");
        title.setReadingProgress(bookmarkId, bookmarkIndex, episodeCount);
        return title;
    }
}
