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
          const guardModule = async () => {
            const prewarmed = await window.__ntkGuardModulePromise;
            if (prewarmed) return prewarmed;
            const [module, encrypted] = await Promise.all([
              import(location.origin + '/wasm/ad-guard/ad_guard.js'),
              fetch(location.origin + '/wasm/ad-guard/ad_guard_bg.wasm', {
                credentials: 'same-origin', cache: 'force-cache'
              }).then(response => {
                if (!response.ok) throw new Error('guard wasm HTTP ' + response.status);
                return response.arrayBuffer();
              })
            ]);
            const blobUrl = URL.createObjectURL(new Blob([encrypted], {
              type: 'application/octet-stream'
            }));
            try {
              await module.default({module_or_path: blobUrl});
            } finally {
              URL.revokeObjectURL(blobUrl);
            }
            return module;
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
              return response.ok && challenge?.scope === scope ? challenge : null;
            })();
            const guardReadyFlight = Promise.all([waitForRows(), guardModule()])
              .then(async ([rows, guard]) => {
                if (!rows || !guard || guard.__i5() !== true) return null;
                const marker = await fetch('/api/ad/canary', {
                  method: 'POST', credentials: 'include', cache: 'no-store',
                  headers: {'Content-Type': 'application/json'},
                  body: JSON.stringify({adGuardLoaded: true})
                });
                return marker.ok ? guard : null;
              });
            const [challenge, key, guard] = await Promise.all([
              challengeFlight,
              keyFlight,
              guardReadyFlight
            ]);
            if (!challenge || !key || !guard) return;
            window.__ntk_request_key_id = key.keyId;
            window.__ntk_request_key_cert = key.certificate;
            const prime = [
              String(challenge.token || ''),
              JSON.stringify({token: String(challenge.token || ''), path: scope}),
              scope
            ];
            for (const value of prime) {
              try { if (guard._vc) guard._vc(value, scope); } catch (_) {}
            }
            for (const value of prime) {
              try { if (guard._hk) guard._hk(value, scope); } catch (_) {}
            }
            const result = guard.__i4(JSON.stringify(challenge), scope);
            if (result?.then) await result;
            phase('early-ack-guard-fired');
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
