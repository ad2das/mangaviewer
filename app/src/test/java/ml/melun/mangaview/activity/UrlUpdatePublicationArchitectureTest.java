package ml.melun.mangaview.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class UrlUpdatePublicationArchitectureTest {
    @Test
    public void networkProbeCannotMutateGlobalSiteStateBeforeUiOwnsItsRequest() throws Exception {
        String source = read("src/main/java/ml/melun/mangaview/repository/MangaRepository.java");
        String probe = between(source,
                "public static UrlUpdateResult updateUrl(String fetchUrl)",
                "public static boolean applyUrlUpdate");
        assertFalse(probe.contains("p.set"));
        assertFalse(probe.contains("resetCookie()"));
        assertFalse(probe.contains("clearPageCache()"));

        String publication = source.substring(source.indexOf("public static boolean applyUrlUpdate"));
        assertTrue(publication.contains("result.isForRequest(fetchUrl)"));
        assertTrue(publication.contains("p.set"));
    }

    @Test
    public void firstRunCancellationAndBothPublishersAreRequestBound() throws Exception {
        String firstRun = read("src/main/java/ml/melun/mangaview/activity/FirstTimeActivity.java");
        assertTrue(firstRun.contains("startupViewModel.cancelActiveLoad()"));
        assertTrue(firstRun.contains("result.isForRequest(pendingDefUrl)"));

        String main = read("src/main/java/ml/melun/mangaview/activity/MainActivity.java");
        assertTrue(main.contains("result.isForRequest(pendingUrlUpdateRequest)"));
        assertTrue(main.contains("MangaRepository.applyUrlUpdate(pendingUrlUpdateRequest, result)"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return value.substring(from, to);
    }
}
