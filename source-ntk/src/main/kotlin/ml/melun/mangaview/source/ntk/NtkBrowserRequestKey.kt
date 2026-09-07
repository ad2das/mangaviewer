package ml.melun.mangaview.source.ntk

/** Owns the provider browser signing key and exposes one shared cold-registration flight. */
internal object NtkBrowserRequestKey {
    val source: String = """
        (() => {
          if (window.__nativeGetBrowserKey) return;
          const cookie = name => {
            const raw = (document.cookie || '').split(';').map(value => value.trim())
              .find(value => value.startsWith(name + '='))?.slice(name.length + 1) || '';
            try { return decodeURIComponent(raw); } catch (_) { return raw; }
          };
          const base64Url = bytes => {
            let text = '';
            for (const byte of bytes) text += String.fromCharCode(byte);
            return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
          };
          const digest = async value => base64Url(new Uint8Array(await crypto.subtle.digest(
            'SHA-256', new TextEncoder().encode(value)
          )));
          const valid = value => value && value.privateKey instanceof CryptoKey &&
            typeof value.keyId === 'string' && /^[A-Za-z0-9_-]{43}${'$'}/.test(value.keyId) &&
            value.fingerprint === cookie('ntk_fp') &&
            value.pid === (cookie('ntk_pid') || null) &&
            typeof value.certificate === 'string' &&
            /^[A-Za-z0-9_-]{100,2000}\.[A-Za-z0-9_-]{43}${'$'}/.test(value.certificate) &&
            Number(value.certificateExpiresAt || 0) > Date.now() + 300000;
          const database = () => new Promise(resolve => {
            try {
              const request = indexedDB.open('ntk-browser-request-key', 1);
              request.onupgradeneeded = () => {
                if (!request.result.objectStoreNames.contains('keys')) {
                  request.result.createObjectStore('keys');
                }
              };
              request.onerror = () => resolve(null);
              request.onblocked = () => resolve(null);
              request.onsuccess = () => resolve(request.result);
            } catch (_) { resolve(null); }
          });
          const read = async () => {
            const db = await database();
            if (!db) return null;
            return new Promise(resolve => {
              try {
                const transaction = db.transaction('keys', 'readonly');
                const request = transaction.objectStore('keys').get('manhwa-v1');
                request.onsuccess = () => resolve(request.result || null);
                request.onerror = () => resolve(null);
                transaction.oncomplete = () => db.close();
                transaction.onabort = () => { db.close(); resolve(null); };
              } catch (_) { db.close(); resolve(null); }
            });
          };
          const write = async value => {
            const db = await database();
            if (!db) return;
            await new Promise(resolve => {
              try {
                const transaction = db.transaction('keys', 'readwrite');
                transaction.objectStore('keys').put(value, 'manhwa-v1');
                transaction.oncomplete = () => { db.close(); resolve(); };
                transaction.onerror = () => { db.close(); resolve(); };
                transaction.onabort = () => { db.close(); resolve(); };
              } catch (_) { db.close(); resolve(); }
            });
          };
          const register = async () => {
            const pair = await crypto.subtle.generateKey(
              {name: 'ECDSA', namedCurve: 'P-256'}, false, ['sign', 'verify']
            );
            const publicJwk = await crypto.subtle.exportKey('jwk', pair.publicKey);
            const canonical = {
              crv: publicJwk.crv, kty: publicJwk.kty, x: publicJwk.x, y: publicJwk.y
            };
            const keyId = await digest(
              'ntk-browser-request-key-v1:' + JSON.stringify(canonical)
            );
            const startedAt = Date.now();
            const response = await fetch('/api/client-key/register', {
              method: 'POST', credentials: 'same-origin', cache: 'no-store',
              headers: {'content-type': 'application/json'},
              body: JSON.stringify({publicKey: publicJwk, credentialMode: 'certificate-v1'})
            });
            const receivedAt = Date.now();
            const payload = await response.json().catch(() => null);
            if (!response.ok || payload?.ok !== true || payload?.keyId !== keyId ||
                typeof payload?.certificate !== 'string') return null;
            const serverNow = Number(payload.serverNow);
            const value = {
              keyId, publicJwk, privateKey: pair.privateKey,
              expiresAt: Number(payload.legacyExpiresAt || payload.expiresAt || 0),
              certificateExpiresAt: Number(payload.certificateExpiresAt || 0),
              serverTimeOffsetMs: Number.isFinite(serverNow)
                ? serverNow - ((startedAt + receivedAt) / 2) : 0,
              certificate: payload.certificate,
              credentialMode: 'certificate-v1',
              fingerprint: cookie('ntk_fp'),
              pid: cookie('ntk_pid') || null,
              storageVersion: 2
            };
            if (!valid(value)) return null;
            await write(value);
            return value;
          };
          let flight = null;
          window.__nativeGetBrowserKey = () => {
            if (!flight) {
              flight = (async () => {
                const stored = await read();
                return valid(stored) ? stored : register();
              })().finally(() => { flight = null; });
            }
            return flight;
          };
        })();
    """.trimIndent()
}
