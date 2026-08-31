package ml.melun.mangaview.source.ntk

internal object NtkBrowserProtocol {
    const val MSG_RESOLVE = 1
    const val MSG_CANCEL = 2
    const val MSG_QUIESCE = 3
    const val MSG_PAYLOAD = 4
    const val MSG_ERROR = 5
    const val MSG_WARM = 6
    const val MSG_DESCRIPTOR = 7

    const val KEY_REQUEST_ID = "requestId"
    const val KEY_ORIGIN = "origin"
    const val KEY_PATH = "path"
    const val KEY_USER_AGENT = "userAgent"
    const val KEY_PAYLOAD = "payload"
    const val KEY_ERROR = "error"
    const val KEY_WORK_ID = "workId"
    const val KEY_EPISODE_ID = "episodeId"
    const val KEY_TOKEN = "token"
    const val KEY_API_PATH = "apiPath"
    const val KEY_EXPECTED_COUNT = "expectedCount"
    const val KEY_RESPONSE_COOKIES = "responseCookies"
}
