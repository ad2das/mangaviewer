package ml.melun.mangaview.source.ntk

/** The app supplies credentials only for its own process-local CONNECT relay. */
interface NtkBrowserProxyOwner {
    fun ntkBrowserProxyCredentials(host: String, realm: String): Pair<String, String>?
}
