package ml.melun.mangaview.source.ntk

/** Decides whether the exact episode document needs the evaluateJavascript fallback. */
internal fun shouldEvaluateAuthorizationFallback(
    browserDocumentStarted: Boolean,
    authorizationStarted: Boolean,
    captureInstalledAtDocumentStart: Boolean,
): Boolean = browserDocumentStarted &&
    !authorizationStarted &&
    !captureInstalledAtDocumentStart
