package ml.melun.mangaview.source.ntk

/** Runs the provider's current guard as soon as its real episode DOM can be observed. */
internal object NtkBrowserEarlyAck {
    val source: String = """
        (() => {
          if (window.__nativeEarlyAck) return;
          window.__nativeEarlyAck = true;
          if (window.top !== window.self || location.protocol !== 'https:') return;
          const scope = location.pathname;
          if (!/^\/(webtoon|manhwa)\/[^/]+\/[^/]+$/.test(scope)) return;

          const phase = (name, status = 0) => {
            try {
              window.NtkNativeManifest.onPhase(
                location.origin,
                scope,
                name,
                Number(status) || 0
              );
            } catch (_) {}
          };
          const cookie = name => {
            const escaped = name.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&');
            const match = (document.cookie || '').match(
              new RegExp('(?:^|;\\s*)' + escaped + '=([^;]*)')
            );
            if (!match) return '';
            try { return decodeURIComponent(match[1] || ''); } catch (_) { return match[1] || ''; }
          };
          const randomHex = count => {
            const bytes = new Uint8Array(count);
            crypto.getRandomValues(bytes);
            return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
          };
          const hash = (seed, value) => {
            let result = seed >>> 0;
            for (let index = 0; index < value.length; index += 1) {
              result ^= value.charCodeAt(index);
              result = Math.imul(result, 0x1000193) >>> 0;
            }
            return ('00000000' + result.toString(16)).slice(-8);
          };
          const seedIdentity = () => {
            if (!/^[a-fA-F0-9]{16,64}$/.test(cookie('ntk_fp'))) {
              const nav = navigator;
              const material = [
                nav.userAgent || '', nav.language || '',
                Array.isArray(nav.languages) ? nav.languages.join(',') : '',
                String(nav.hardwareConcurrency || 0), String(nav.deviceMemory || 0),
                nav.platform || '', String(nav.maxTouchPoints || 0),
                screen ? String(screen.width || 0) + 'x' + String(screen.height || 0) +
                  'x' + String(screen.colorDepth || 0) : '',
                String(new Date().getTimezoneOffset()),
                typeof Intl !== 'undefined' && Intl.DateTimeFormat
                  ? Intl.DateTimeFormat().resolvedOptions().timeZone || '' : ''
              ].join('|');
              const fingerprint = material.replace(/\|/g, '')
                ? hash(0x811c9dc5, material) + hash(0xbb40e64d, material) +
                  hash(0x9e3779b1, material) + hash(0x5f356495, material)
                : randomHex(16);
              document.cookie = 'ntk_fp=' + encodeURIComponent(fingerprint) +
                '; Path=/; Max-Age=31536000; SameSite=Lax; Secure';
              window.__ntk_fp_ready = 1;
            }
            if (!/^[a-fA-F0-9]{16,64}$/.test(cookie('ntk_pid'))) {
              document.cookie = 'ntk_pid=' + randomHex(16) +
                '; Path=/; Max-Age=31536000; SameSite=Lax; Secure';
            }
          };
          const base64Url = bytes => {
            let text = '';
            for (const byte of bytes) text += String.fromCharCode(byte);
            return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
          };
          const digest = async value => base64Url(new Uint8Array(
            await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
          ));
          const providerHandshakeReady = new Promise(resolve => {
            const name = '__ntk_hs_ok';
            let value = window[name];
            let settled = false;
            let timeoutId = 0;
            const finish = next => {
              value = next;
              if (settled) return;
              settled = true;
              if (timeoutId) window.clearTimeout(timeoutId);
              try {
                Object.defineProperty(window, name, {
                  configurable: true,
                  enumerable: true,
                  writable: true,
                  value: next
                });
              } catch (_) {}
              resolve(next === 1);
            };
            if (value === 1) return finish(value);
            try {
              Object.defineProperty(window, name, {
                configurable: true,
                enumerable: true,
                get: () => value,
                set: finish
              });
            } catch (_) {
              resolve(false);
              return;
            }
            timeoutId = window.setTimeout(() => finish(value), 2500);
          });
          const prewarmNvSession = async () => {
            const present = cookie('nv');
            if ((present.split('.')[0] || '').length >= 40) return;
            phase('nv-prewarm-start');
            const response = await fetch('/api/nv-issue', {
              method: 'POST', credentials: 'same-origin', cache: 'no-store'
            });
            phase('nv-prewarm-end', response.status);
          };
          const keyDatabase = () => new Promise(resolve => {
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
          const readStoredKey = async () => {
            const database = await keyDatabase();
            if (!database) return null;
            return new Promise(resolve => {
              try {
                const transaction = database.transaction('keys', 'readonly');
                const request = transaction.objectStore('keys').get('manhwa-v1');
                request.onsuccess = () => resolve(request.result || null);
                request.onerror = () => resolve(null);
                transaction.oncomplete = () => database.close();
                transaction.onabort = () => { database.close(); resolve(null); };
              } catch (_) { database.close(); resolve(null); }
            });
          };
          const writeStoredKey = async value => {
            const database = await keyDatabase();
            if (!database) return;
            await new Promise(resolve => {
              try {
                const transaction = database.transaction('keys', 'readwrite');
                transaction.objectStore('keys').put(value, 'manhwa-v1');
                transaction.oncomplete = () => { database.close(); resolve(); };
                transaction.onerror = () => { database.close(); resolve(); };
                transaction.onabort = () => { database.close(); resolve(); };
              } catch (_) { database.close(); resolve(); }
            });
          };
          const validStoredKey = value => value && value.privateKey instanceof CryptoKey &&
            typeof value.keyId === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value.keyId) &&
            value.fingerprint === cookie('ntk_fp') && value.pid === (cookie('ntk_pid') || null) &&
            typeof value.certificate === 'string' && value.certificate.length >= 100 &&
            Number(value.certificateExpiresAt || 0) > Date.now() + 300000;
          const registerKey = async () => {
            const pair = await crypto.subtle.generateKey(
              {name: 'ECDSA', namedCurve: 'P-256'}, false, ['sign', 'verify']
            );
            const publicJwk = await crypto.subtle.exportKey('jwk', pair.publicKey);
            const canonicalJwk = {
              crv: publicJwk.crv, kty: publicJwk.kty, x: publicJwk.x, y: publicJwk.y
            };
            const localKeyId = await digest(
              'ntk-browser-request-key-v1:' + JSON.stringify(canonicalJwk)
            );
            const startedAt = Date.now();
            const response = await fetch('/api/client-key/register', {
              method: 'POST', credentials: 'same-origin', cache: 'no-store',
              headers: {'content-type': 'application/json'},
              body: JSON.stringify({publicKey: publicJwk, credentialMode: 'certificate-v1'})
            });
            const receivedAt = Date.now();
            const payload = await response.json().catch(() => null);
            if (!response.ok || payload?.ok !== true || payload?.keyId !== localKeyId ||
                typeof payload?.certificate !== 'string') return null;
            const serverNow = Number(payload.serverNow);
            const value = {
              keyId: localKeyId,
              publicJwk,
              privateKey: pair.privateKey,
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
            if (!validStoredKey(value)) return null;
            await writeStoredKey(value);
            return value;
          };
          const keyFlight = (async () => {
            const stored = await readStoredKey();
            if (validStoredKey(stored)) return stored;
            return registerKey();
          })();
          const rowsReady = () => {
            const rows = Array.from(document.querySelectorAll('[data-br="1"][data-br-n]'));
            if (rows.length === 0) return false;
            return rows.every(row => row.getClientRects().length > 0 &&
              row.getBoundingClientRect().width > 0 && row.getBoundingClientRect().height > 0);
          };
          const providerGuardModule = async () => {
            const providerReady = await providerHandshakeReady;
            if (providerReady) {
              const blockScript = Array.from(document.scripts).find(script => {
                try {
                  return new URL(script.src, location.href).pathname === '/init/block.js';
                } catch (_) { return false; }
              });
              if (blockScript) {
                const blockUrl = new URL(blockScript.src, location.href);
                const version = blockUrl.searchParams.get('wv');
                const moduleUrl = new URL('/wasm/ad-guard/ad_guard.js', location.origin);
                if (version) moduleUrl.searchParams.set('v', version);
                const module = await import(moduleUrl.href);
                if (module.__i5() === true) return module;
              }
            }
            return null;
          };
          const independentGuardModule = async () => {
            const module = await import(location.origin + '/wasm/ad-guard/ad_guard.js');
            await module.default();
            return module.__i5() === true ? module : null;
          };
          const initializeGuard = guard => {
            if (!guard || guard.__i5() !== true || typeof guard._hk !== 'function' ||
                typeof guard._vc !== 'function') return false;
            // Keep this byte contract identical to the provider's current block.js bootstrap.
            // _hk does not accept strings: passing token/scope values appears to succeed at the
            // JS boundary but leaves the WASM guard uninitialized and forces its slow fallback.
            const key = new Uint8Array([
              0x9e, 0x3f, 0x71, 0x2c, 0x8b, 0x4a, 0xd6, 0x15,
              0xe7, 0x5d, 0x33, 0x9a, 0x2f, 0x6c, 0x84, 0xb1,
              0x47, 0x59, 0xae, 0x18, 0xcd, 0x7f, 0x23, 0x60,
              0x95, 0x0a, 0xde, 0x4b, 0x72, 0x36, 0xf8, 0x11
            ]);
            const nonce = new Uint8Array(16);
            crypto.getRandomValues(nonce);
            const handshake = new Uint8Array(8);
            for (let index = 0; index < handshake.length; index += 1) {
              const value = nonce[index % nonce.length];
              const multiplier = key[(index * 3 + 7) % key.length];
              handshake[index] = (((value * multiplier) & 0xff) +
                key[(index * 5 + 13) % key.length]) & 0xff;
            }
            if (guard._hk(nonce, handshake) !== true) return false;
            guard._vc(new Uint8Array(64));
            return true;
          };
          const waitForRows = () => new Promise(resolve => {
            if (rowsReady()) return resolve(true);
            const observer = new MutationObserver(() => {
              if (!rowsReady()) return;
              observer.disconnect();
              resolve(true);
            });
            // A document-start script can run before the HTML element exists. Document itself
            // is already a Node and observes both its future root and the provider rows.
            observer.observe(document.documentElement || document, {
              childList: true,
              subtree: true
            });
            window.setTimeout(() => { observer.disconnect(); resolve(rowsReady()); }, 4000);
          });
          const guardFlight = (async () => {
            phase('early-ack-start');
            seedIdentity();
            void prewarmNvSession().catch(() => phase('nv-prewarm-failed'));
            const challengeFlight = (async () => {
              const response = await window.__nativeChallengeResponse();
              const payload = await response.json();
              const challenge = payload?.challenge;
              return response.ok && challenge?.scope === scope
                ? {value: challenge, receivedAt: performance.now()}
                : null;
            })();
            const canaryFlight = fetch('/api/ad/canary', {
              method: 'POST', credentials: 'include', cache: 'no-store',
              headers: {'Content-Type': 'application/json'},
              body: JSON.stringify({adGuardLoaded: true})
            });
            const independentGuardFlight = independentGuardModule().catch(() => null);
            const [challengeResult, key, rows, marker, independentGuard] = await Promise.all([
              challengeFlight,
              keyFlight,
              waitForRows(),
              canaryFlight,
              independentGuardFlight
            ]);
            if (!challengeResult || !key || !rows || !marker.ok) return;
            const challenge = challengeResult.value;
            // The guard silently ignores a proof submitted before minSeen. A premature call then
            // falls back to the much later hydrated-page controller, producing multi-second
            // variance. Submit exactly once at the earliest server-authorized instant.
            const minSeenMillis = Math.max(0, Math.min(
              5000,
              Math.ceil((Number(challenge.minSeen) || 0) * 1000)
            ));
            // A submission only a few milliseconds over the nominal boundary is occasionally
            // rejected because the server and WebView quantize time independently. A small fixed
            // settle margin is cheaper than falling through to the controller's ~1.5 s retry.
            const settleMarginMillis = minSeenMillis > 0 ? 75 : 0;
            const remaining = challengeResult.receivedAt + minSeenMillis +
              settleMarginMillis - performance.now();
            if (remaining > 0) {
              await new Promise(resolve => window.setTimeout(resolve, remaining));
            }
            window.__ntk_request_key_id = key.keyId;
            window.__ntk_request_key_cert = key.certificate;
            const submit = async (guard, submittedPhase) => {
              const result = guard.__i4(JSON.stringify(challenge), scope);
              if (result?.then) await result;
              phase(submittedPhase);
            };
            if (independentGuard && initializeGuard(independentGuard)) {
              await submit(independentGuard, 'early-ack-guard-fired');
            }
            if (!window.__nativeAckRequestStarted) {
              await new Promise(resolve => window.setTimeout(resolve, 150));
            }
            if (!window.__nativeAckRequestStarted && independentGuard) {
              await submit(independentGuard, 'early-ack-guard-retry-fired');
              await new Promise(resolve => window.setTimeout(resolve, 150));
            }
            if (!window.__nativeAckRequestStarted) {
              const providerGuard = await providerGuardModule().catch(() => null);
              if (providerGuard && !window.__nativeAckRequestStarted) {
                await submit(providerGuard, 'early-ack-provider-fallback-fired');
              }
            }
          })().catch(error => phase(
            'early-ack-failed:' + String(error?.name || 'Error') + ':' +
              String(error?.message || error || '').slice(0, 96)
          ));
          window.addEventListener('ntk-ad-ack-ready', event => {
            if (!event?.detail?.scope || event.detail.scope === scope) {
              phase('early-ack-ready');
            }
          });
          void guardFlight;
        })();
    """.trimIndent()
}
