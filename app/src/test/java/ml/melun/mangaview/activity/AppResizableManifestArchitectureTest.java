package ml.melun.mangaview.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppResizableManifestArchitectureTest {
    @Test
    public void everyResizableScreenHandlesAllDividerDrivenSizeChanges() throws Exception {
        String manifest = new String(Files.readAllBytes(
                Paths.get("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("android:resizeableActivity=\"true\""));
        assertFalse(manifest.contains("android:configChanges=\"orientation|screenSize\""));
        assertTrue(manifest.contains(
                "orientation|screenSize|smallestScreenSize|screenLayout"));
        Matcher activity = Pattern.compile("<activity\\s+([\\s\\S]*?)(?:/>|</activity>)")
                .matcher(manifest);
        int count = 0;
        while(activity.find()) {
            count++;
            String declaration = activity.group(1);
            Matcher name = Pattern.compile("android:name=\"([^\"]+)\"").matcher(declaration);
            assertTrue("activity declaration has no name", name.find());
            assertTrue(name.group(1) + " does not handle divider-driven configuration changes",
                    declaration.contains("android:configChanges=\"orientation|screenSize|smallestScreenSize|screenLayout\""));
        }
        assertTrue("manifest activity inventory unexpectedly small: " + count, count >= 14);
    }

    @Test
    public void readerDropsImmersiveFullscreenChromeInsideMultiWindow() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/ml/melun/mangaview/activity/ReaderChromeStyler.kt")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("!activity.isInMultiWindowMode"));
        assertTrue(source.contains("clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)"));
        assertTrue(source.contains("immersiveFlags.inv()"));
    }
}
