package ml.melun.mangaview.source.ntk

/** Decides whether a superseded authorization document can keep its Chromium renderer. */
internal enum class NtkBrowserSupersession {
    REUSE_RESIDENT_BROWSER,
    RETIRE_UNFINISHED_BROWSER,
}

internal fun browserSupersession(completedManifestPayload: String?): NtkBrowserSupersession =
    if (completedManifestPayload == null) {
        NtkBrowserSupersession.RETIRE_UNFINISHED_BROWSER
    } else {
        NtkBrowserSupersession.REUSE_RESIDENT_BROWSER
    }

internal fun browserSupersessionControl(manifestConsumed: Boolean): Int =
    if (manifestConsumed) NtkBrowserProtocol.MSG_QUIESCE else NtkBrowserProtocol.MSG_CANCEL
