package ml.melun.mangaview.source.ntk

internal object NtkBrowserProtocol {
    const val MSG_RESOLVE = 1
    const val MSG_CANCEL = 2
    const val MSG_QUIESCE = 3
    const val MSG_PAYLOAD = 4
    const val MSG_ERROR = 5
    const val MSG_WARM = 6
    const val MSG_DESCRIPTOR = 7
    const val MSG_ACK_READY = 8
    const val MSG_WARM_PHASE = 9
    const val MSG_PREFLIGHT_ADJACENT = 11
    // Confirms only removal of this request subscription, not Chromium/process reclamation.
    const val MSG_REQUEST_DETACHED = 12
    const val MSG_RETIRE_DOCUMENT = 13
    // Engine service only: document closed and its WebView.destroy() returned; not a PSS measurement.
    const val MSG_DOCUMENT_RETIRED = 14
    const val MSG_DOCUMENT_REQUEST_READY = 15

    const val KEY_REQUEST_ID = "requestId"
    const val KEY_ORIGIN = "origin"
    const val KEY_PATH = "path"
    const val KEY_USER_AGENT = "userAgent"
    const val KEY_PREPARATION_INTENT = "preparationIntent"
    const val KEY_FINGERPRINT = "fingerprint"
    const val KEY_PERSISTENT_ID = "persistentId"
    const val KEY_PAYLOAD = "payload"
    const val KEY_CAPTURE_EVIDENCE = "captureEvidence"
    const val KEY_ERROR = "error"
    const val KEY_WORK_ID = "workId"
    const val KEY_EPISODE_ID = "episodeId"
    const val KEY_TOKEN = "token"
    const val KEY_API_PATH = "apiPath"
    const val KEY_EXPECTED_COUNT = "expectedCount"
    const val KEY_PHASE = "phase"
    const val KEY_STATUS = "status"
    const val KEY_AGE_MILLIS = "ageMillis"
    const val KEY_ADJACENT_PATH = "adjacentPath"
}
