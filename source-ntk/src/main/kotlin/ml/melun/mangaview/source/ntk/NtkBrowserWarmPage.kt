package ml.melun.mangaview.source.ntk

/**
 * Starts Chromium, the NTK origin, persistent request credentials and static guard bytes before a
 * reader target is known. Episode-scoped work only runs in the real episode document.
 */
internal object NtkBrowserWarmPage {
    private val template = """
            <!doctype html><html><head><meta charset="utf-8"></head><body>
            <script>
            __REQUEST_KEY_SOURCE__
            (() => {
              const origin = __NTK_ORIGIN__;
              const generation = __NTK_GENERATION__;
              if (window.top !== window.self || location.origin !== origin) return;
              const report = (phase, status = 0) => {
                try {
                  window.NtkNativeManifest.onWarmPhase(
                    origin, generation, phase, Number(status) || 0
                  );
                } catch (_) {}
              };
              const reportDocumentReady = () => report('document-ready', 200);
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', reportDocumentReady, {once: true});
              } else {
                reportDocumentReady();
              }
              const cookie = name => (document.cookie || '').split(';').map(value => value.trim())
                .find(value => value.startsWith(name + '='))?.slice(name.length + 1) || '';
              const randomHex = count => {
                const bytes = new Uint8Array(count);
                crypto.getRandomValues(bytes);
                return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
              };
              const seedIdentity = () => {
                if (!/^[a-fA-F0-9]{16,64}${'$'}/.test(cookie('ntk_fp'))) {
                  document.cookie = 'ntk_fp=' + randomHex(16) +
                    '; Path=/; Max-Age=31536000; SameSite=Lax; Secure';
                }
                if (!/^[a-fA-F0-9]{16,64}${'$'}/.test(cookie('ntk_pid'))) {
                  document.cookie = 'ntk_pid=' + randomHex(16) +
                    '; Path=/; Max-Age=31536000; SameSite=Lax; Secure';
                }
              };
              const warmKey = async () => !!(await window.__nativeGetBrowserKey?.());
              const warmNv = async () => {
                if ((cookie('nv').split('.')[0] || '').length >= 40) return true;
                const response = await fetch('/api/nv-issue', {
                  method: 'POST', credentials: 'same-origin', cache: 'no-store'
                });
                return response.ok;
              };
              seedIdentity();
              window.__nativeWarmKey = warmKey();
              report('origin-ready', 200);
              const task = async (name, work) => {
                const ok = await work().catch(() => false);
                report(name, ok ? 200 : 0);
                return ok;
              };
              const credentialWarmup = Promise.all([
                task('key-ready', () => window.__nativeWarmKey),
                task('nv-ready', warmNv)
              ]);
              Promise.race([
                credentialWarmup,
                new Promise(resolve => window.setTimeout(() => resolve(null), 1500))
              ]).then(results => report(
                'complete',
                Array.isArray(results) && results.every(Boolean) ? 200 : 0
              ));
            })();
            </script></body></html>
    """.trimIndent()

    fun html(origin: String, generation: Long): String = template
        .replace("__REQUEST_KEY_SOURCE__", NtkBrowserRequestKey.source)
        .replace("__NTK_ORIGIN__", origin.jsString())
        .replace("__NTK_GENERATION__", generation.toString())

    fun script(origin: String, generation: Long): String = html(origin, generation)
        .substringAfter("<script>")
        .substringBeforeLast("</script>")
        .trim()

    private fun String.jsString(): String = buildString(length + 2) {
        append('"')
        this@jsString.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                else -> append(character)
            }
        }
        append('"')
    }
}
