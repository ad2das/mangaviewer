package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** Regression gates for attached WebViews that render in the background. */
public final class NtkWebViewAccessibilityArchitectureTest {
    @Test
    public void sharedDocumentFallbackIsHiddenFromAccessibilityBeforeAttachment() throws Exception {
        String manager = read(sourcePath("mangaview", "NtkWebViewFallbackManager.java"));
        String attach = method(manager,
                "private void attachSharedWebViewToActivity()",
                "private static void suppressBackgroundWebViewAccessibility(");
        String suppress = method(manager,
                "private static void suppressBackgroundWebViewAccessibility(",
                "private void timeoutOnMain(");

        int accessibility = attach.indexOf("suppressBackgroundWebViewAccessibility(webView)");
        int attachment = attach.indexOf("decor.addView(webView, 0, params)");
        assertTrue(accessibility >= 0);
        assertTrue(attachment > accessibility);
        assertTrue(suppress.contains(
                "View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"));
        assertTrue(suppress.contains("view.setClickable(false)"));
        assertTrue(suppress.contains("view.setLongClickable(false)"));
        assertTrue(suppress.contains("view.setFocusable(false)"));
        assertTrue(suppress.contains("view.setFocusableInTouchMode(false)"));
    }

    @Test
    public void transientViewerFramesSuppressOnlyBackgroundInstances() throws Exception {
        String manager = read(sourcePath("mangaview", "NtkWebViewFallbackManager.java"));
        String attachment = method(manager,
                "boolean ackOnlyInteractiveCloudflareFrame =",
                "Log.d(TAG, \"ntk_images_api_hidden_document path=\"");

        assertTrue(attachment.contains(
                "boolean userInteractiveFrame = visibleRealMainFrame\n" +
                        "                        || ackOnlyInteractiveCloudflareFrame"));
        assertTrue(attachment.contains(
                "if(!userInteractiveFrame)\n" +
                        "                    suppressBackgroundWebViewAccessibility(view)"));
        assertTrue(attachment.contains("view.setClickable(userInteractiveFrame)"));
        assertTrue(attachment.contains("if(visibleRealMainFrame)"));
        assertTrue(attachment.contains("decor.addView(view, params)"));
        assertTrue(attachment.contains("view.requestFocusFromTouch()"));
    }

    @Test
    public void offscreenReaderWebViewIsHiddenButCaptchaWebViewRemainsExposed() throws Exception {
        String broker = read(sourcePath("activity", "NtkBrowserSessionBroker.kt"));
        String accessibility = method(broker,
                "view.importantForAccessibility = if (visible)",
                "view.overScrollMode = View.OVER_SCROLL_NEVER");
        String captchaLayout = read(resourcePath("layout", "activity_captcha.xml"));

        assertTrue(accessibility.contains("View.IMPORTANT_FOR_ACCESSIBILITY_AUTO"));
        assertTrue(accessibility.contains(
                "View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"));
        assertTrue(captchaLayout.contains("android:id=\"@+id/captchaWebView\""));
        assertTrue(captchaLayout.contains("android:importantForAccessibility=\"yes\""));
    }

    private static String method(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path sourcePath(String area, String name) {
        return projectPath("src", "main", "java", "ml", "melun", "mangaview", area, name);
    }

    private static Path resourcePath(String area, String name) {
        return projectPath("src", "main", "res", area, name);
    }

    private static Path projectPath(String... parts) {
        Path appRelative = Paths.get("app");
        for(String part : parts)
            appRelative = appRelative.resolve(part);
        if(Files.exists(appRelative))
            return appRelative;
        Path direct = Paths.get("");
        for(String part : parts)
            direct = direct.resolve(part);
        return direct;
    }
}
