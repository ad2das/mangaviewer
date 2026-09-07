package ml.melun.mangaview.source.ntk

/** Submits the provider guard at its advertised minSeen boundary instead of waiting for UI hydration. */
internal object NtkBrowserEarliestAck {
    val source: String = """
        (() => {
          if (window.__nativeEarliestAck) return;
          window.__nativeEarliestAck = true;
          if (window.top !== window.self || location.protocol !== 'https:') return;
          const scope = location.pathname;
          if (!/^\/(webtoon|manhwa)\/[^/]+\/[^/]+${'$'}/.test(scope)) return;

          const report = (phase, status = 0) => {
            try {
              window.NtkNativeManifest.onPhase(
                location.origin, scope, phase, Number(status) || 0, nativeRequestId, nativeDocumentEpoch
              );
            } catch (_) {}
          };
          const validKey = key => key && key.privateKey instanceof CryptoKey &&
            typeof key.keyId === 'string' && /^[A-Za-z0-9_-]{43}${'$'}/.test(key.keyId) &&
            typeof key.certificate === 'string' && key.certificate.length >= 100 &&
            Number(key.certificateExpiresAt || 0) > Date.now() + 300000;
          const rowsReady = () => {
            const rows = Array.from(document.querySelectorAll('[data-br="1"][data-br-n]'));
            return rows.length > 0 && rows.every(row =>
              row.getClientRects().length > 0 &&
              row.getBoundingClientRect().width > 0 &&
              row.getBoundingClientRect().height > 0
            );
          };
          const waitForRows = () => new Promise(resolve => {
            if (rowsReady()) return resolve(true);
            const observer = new MutationObserver(() => {
              if (!rowsReady()) return;
              observer.disconnect();
              resolve(true);
            });
            observer.observe(document.documentElement || document, {
              childList: true, subtree: true
            });
            window.setTimeout(() => {
              observer.disconnect();
              resolve(rowsReady());
            }, 2500);
          });
          const initializeGuard = guard => {
            if (!guard || guard.__i5() !== true || typeof guard._hk !== 'function' ||
                typeof guard._vc !== 'function') return false;
            const key = new Uint8Array([
              0x9e, 0x3f, 0x71, 0x2c, 0x8b, 0x4a, 0xd6, 0x15,
              0xe7, 0x5d, 0x33, 0x9a, 0x2f, 0x6c, 0x84, 0xb1,
              0x47, 0x59, 0xae, 0x18, 0xcd, 0x7f, 0x23, 0x60,
              0x95, 0x0a, 0xde, 0x4b, 0x72, 0x36, 0xf8, 0x11
            ]);
            const nonce = crypto.getRandomValues(new Uint8Array(16));
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
          const loadGuard = async () => {
            const guard = await import(location.origin + '/wasm/ad-guard/ad_guard.js');
            await guard.default();
            return initializeGuard(guard) ? guard : null;
          };
          const challenge = async () => {
            const response = await window.__nativeChallengeResponse?.();
            if (!response?.ok) return null;
            const value = (await response.json().catch(() => null))?.challenge;
            return value?.scope === scope
              ? {value, receivedAt: Number(window.__nativeChallengeReceivedAt) || performance.now()}
              : null;
          };
          const canary = () => fetch('/api/ad/canary', {
            method: 'POST', credentials: 'include', cache: 'no-store',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({adGuardLoaded: true})
          });

          void (async () => {
            report('earliest-ack-start');
            const [challengeResult, browserKey, rows, marker, guard] = await Promise.all([
              challenge(),
              window.__nativeGetBrowserKey?.(),
              waitForRows(),
              canary(),
              loadGuard().catch(() => null)
            ]);
            if (!challengeResult || !validKey(browserKey) || !rows || !marker?.ok || !guard) {
              report('earliest-ack-unavailable:' + [
                !!challengeResult, validKey(browserKey), !!rows, !!marker?.ok, !!guard
              ].map(value => value ? '1' : '0').join(''));
              return;
            }
            const minSeen = Number(challengeResult.value.minSeen);
            if (!Number.isFinite(minSeen) || minSeen < 0) {
              report('earliest-ack-invalid-min-seen');
              return;
            }
            const minSeenMillis = Math.ceil(minSeen * 1000);
            if (!Number.isSafeInteger(minSeenMillis)) return;
            const remaining = challengeResult.receivedAt + minSeenMillis + 5 - performance.now();
            const deadline = performance.now() + Math.max(0, remaining);
            while (performance.now() < deadline) {
              const wait = Math.min(2147483647, Math.ceil(deadline - performance.now()));
              await new Promise(resolve => window.setTimeout(resolve, wait));
            }
            const invoke = phase => {
              if (window.__nativeAckRequestStarted) return false;
              window.__ntk_request_key_id = browserKey.keyId;
              window.__ntk_request_key_cert = browserKey.certificate;
              guard.__i4(JSON.stringify(challengeResult.value), scope);
              report(phase);
              return true;
            };
            let providerRedriveUsed = false;
            window.addEventListener('ntk-native-challenge-consumed', event => {
              if (providerRedriveUsed || event?.detail?.scope !== scope) return;
              providerRedriveUsed = true;
              invoke('earliest-ack-provider-ready-fired');
            });
            if (window.__nativeProviderChallengeConsumed) {
              providerRedriveUsed = true;
              invoke('earliest-ack-provider-ready-fired');
            } else {
              invoke('earliest-ack-fired');
            }
            await new Promise(resolve => window.setTimeout(resolve, 150));
            if (!window.__nativeAckRequestStarted) {
              invoke('earliest-ack-retry-fired');
            }
          })().catch(error => report(
            'earliest-ack-failed:' + String(error?.name || 'Error').slice(0, 48)
          ));
        })();
    """.trimIndent()
}
