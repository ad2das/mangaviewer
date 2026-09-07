package ml.melun.mangaview.source.ntk

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/** Stable, installation-local identifiers shared by native challenge and the isolated WebView. */
data class NtkBrowserIdentity(
    val fingerprint: String,
    val persistentId: String,
) {
    init {
        require(HEX_ID.matches(fingerprint)) { "NTK fingerprint is invalid" }
        require(HEX_ID.matches(persistentId)) { "NTK persistent id is invalid" }
    }

    companion object {
        fun forDevice(context: Context, namespace: String = "primary"): NtkBrowserIdentity {
            require(IDENTITY_NAMESPACE.matches(namespace)) { "NTK identity namespace is invalid" }
            val app = context.applicationContext
            val androidId = Settings.Secure.getString(
                app.contentResolver,
                Settings.Secure.ANDROID_ID,
            ).orEmpty()
            val digest = MessageDigest.getInstance("SHA-256").digest(
                "${app.packageName}|$androidId|ntk-browser-v1|$namespace"
                    .toByteArray(Charsets.UTF_8),
            )
            return NtkBrowserIdentity(
                fingerprint = digest.copyOfRange(0, 16).toHex(),
                persistentId = digest.copyOfRange(16, 32).toHex(),
            )
        }

        private val HEX_ID = Regex("^[a-f0-9]{32}$")
        private val IDENTITY_NAMESPACE = Regex("^[a-z0-9_-]{1,32}$")
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
