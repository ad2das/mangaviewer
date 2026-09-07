package ml.melun.mangaview.source.ntk

/**
 * Builds the provider's current image-manifest request inside the provider origin.
 *
 * This deliberately returns a request description instead of issuing fetch itself. The capture
 * wrapper remains the single network entry point, so native delivery and diagnostics observe the
 * speculative request exactly like the site's own request.
 */
internal object NtkBrowserManifestRequest {
    val source: String = """
        (() => {
          if (window.__nativePrepareManifestRequest) return;
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
          const randomBase64Url = count => {
            const bytes = new Uint8Array(count);
            crypto.getRandomValues(bytes);
            return base64Url(bytes);
          };
          const sha256 = async value => new Uint8Array(await crypto.subtle.digest(
            'SHA-256', new TextEncoder().encode(value)
          ));
          const nvSessionValid = value => typeof value === 'string' &&
            (value.split('.')[0] || '').length >= 40;
          const nvSession = async () => {
            const present = cookie('nv');
            if (nvSessionValid(present)) return present;
            const response = await fetch('/api/nv-issue', {
              method: 'POST', credentials: 'same-origin', cache: 'no-store'
            });
            if (!response.ok) return '';
            const payload = await response.clone().json().catch(() => null);
            const issued = typeof payload?.session === 'string' ? payload.session : cookie('nv');
            return nvSessionValid(issued) ? issued : '';
          };
          const nvFlight = nvSession();
          const contentProof = async (session, token, nonce) => {
            const key = await crypto.subtle.importKey(
              'raw', new TextEncoder().encode(session),
              {name: 'HMAC', hash: 'SHA-256'}, false, ['sign']
            );
            return base64Url(new Uint8Array(await crypto.subtle.sign(
              'HMAC', key, new TextEncoder().encode(token + '.' + nonce)
            )));
          };
          const validBrowserKey = value => value && value.privateKey instanceof CryptoKey &&
            typeof value.keyId === 'string' && /^[A-Za-z0-9_-]{43}${'$'}/.test(value.keyId) &&
            value.fingerprint === cookie('ntk_fp') &&
            value.pid === (cookie('ntk_pid') || null) &&
            typeof value.certificate === 'string' &&
            /^[A-Za-z0-9_-]{100,2000}\.[A-Za-z0-9_-]{43}${'$'}/.test(value.certificate) &&
            Number(value.certificateExpiresAt || 0) > Date.now() + 300000;
          const signedManhwaHeaders = async (path, body, session) => {
            const key = await window.__nativeGetBrowserKey?.();
            if (!validBrowserKey(key)) return null;
            const timestamp = Math.floor(Date.now() + Number(key.serverTimeOffsetMs || 0));
            const signingNonce = randomBase64Url(24);
            const bodyHash = base64Url(await sha256(body));
            const canonical = [
              'ntk-brsig-v1', 'POST', path, location.pathname, key.keyId,
              String(timestamp), signingNonce, bodyHash
            ].join('\n');
            const signature = base64Url(new Uint8Array(await crypto.subtle.sign(
              {name: 'ECDSA', hash: 'SHA-256'}, key.privateKey,
              new TextEncoder().encode(canonical)
            )));
            return {
              'content-type': 'application/json',
              'x-images-client': 'viewer-v1',
              'x-nv-session': session,
              'x-ntk-key-id': key.keyId,
              'x-ntk-ts': String(timestamp),
              'x-ntk-nonce': signingNonce,
              'x-ntk-sig': signature,
              'x-ntk-key-cert': key.certificate
            };
          };
          window.__nativePrepareManifestRequest = async descriptor => {
            if (!descriptor ||
                !['/api/manhwa-images', '/api/webtoon-images'].includes(descriptor.path) ||
                typeof descriptor.body?.workId !== 'string' ||
                typeof descriptor.body?.episodeId !== 'string' ||
                typeof descriptor.body?.token !== 'string' ||
                descriptor.body.token.length === 0) return null;
            const session = await nvFlight;
            if (!session) return null;
            const nonce = randomBase64Url(24);
            const proof = await contentProof(session, descriptor.body.token, nonce);
            const body = JSON.stringify({
              workId: descriptor.body.workId,
              episodeId: descriptor.body.episodeId,
              token: descriptor.body.token,
              nonce,
              proof
            });
            const headers = descriptor.path === '/api/manhwa-images'
              ? await signedManhwaHeaders(descriptor.path, body, session)
              : {
                  'content-type': 'application/json',
                  'x-images-client': 'viewer-v1',
                  'x-nv-session': session
                };
            if (!headers) return null;
            return {
              path: descriptor.path,
              init: {
                method: 'POST', credentials: 'same-origin', cache: 'no-store', headers, body
              }
            };
          };
        })();
    """.trimIndent()
}
