package ml.melun.mangaview.source.ntk

import org.json.JSONObject

/** Installs the exact descriptor used by the renderer-local post-ACK manifest fallback. */
internal object NtkBrowserManifestKick {
    fun source(descriptor: RemoteDescriptor): String {
        require(descriptor.apiPath in IMAGE_API_PATHS)
        val path = JSONObject.quote(descriptor.apiPath)
        val body = JSONObject()
            .put("workId", descriptor.workId)
            .put("episodeId", descriptor.episodeId)
            .put("token", descriptor.token)
            .toString()
        return """
            (() => {
              window.__nativeManifestDescriptor = {path: $path, body: $body};
              if (typeof window.__nativeScheduleManifest === 'function') {
                window.__nativeScheduleManifest();
              }
              return 'installed';
            })();
        """.trimIndent()
    }
}
