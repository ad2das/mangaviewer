package ml.melun.mangaview.activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class MainActivityExitSafetyArchitectureTest {
    @Test
    public void downloaderStopCannotLeaveTheWindowPermanentlyUntouchable() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/ml/melun/mangaview/activity/MainActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("DOWNLOADER_EXIT_TIMEOUT_MS"));
        assertTrue(source.contains("clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)"));
        assertTrue(source.contains("waitingPanel.setVisibility(View.GONE)"));
        assertTrue(source.contains("lifecycleHandler.postDelayed("));
        assertTrue(source.contains("if(awaitingDownloaderExit && BROADCAST_STOP.equals(intent.getAction()))"));
        assertTrue(source.contains("lifecycleHandler.removeCallbacks(downloaderExitTimeout)"));
    }
}
