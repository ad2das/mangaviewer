package ml.melun.mangaview.source.ntk

/** Starts one challenge in the exact WebView cookie context and shares it with the provider. */
internal object NtkBrowserChallengeSingleFlight {
    val source: String = """
        (() => {
          if (window.__nativeChallengeSingleFlight) return;
          window.__nativeChallengeSingleFlight = true;
          if (window.top !== window.self || location.protocol !== 'https:') return;
          const scope = location.pathname;
          if (!/^\/(webtoon|manhwa)\/[^/]+\/[^/]+${'$'}/.test(scope)) return;

          const nativeFetch = window.fetch.bind(window);
          let challengeFlight = null;
          let delivered = false;
          const phase = (name, status = 0) => {
            try {
              window.NtkNativeManifest.onPhase(
                location.origin,
                scope,
                name,
                Number(status) || 0, nativeRequestId, nativeDocumentEpoch
              );
            } catch (_) {}
          };
          const fingerprintReady = () =>
            /(?:^|;\s*)ntk_fp=[a-fA-F0-9]{16,64}(?:;|${'$'})/.test(document.cookie || '');
          const browserChallenge = () => {
            phase('challenge-preflight-start');
            const seeded = window.__nativeChallengeSeed;
            if (typeof seeded?.payload === 'string' && seeded.payload.length > 0) {
              window.__nativeChallengeSeed = null;
              window.__nativeChallengeReceivedAt = performance.now() -
                Math.max(0, Number(seeded.ageMillis) || 0);
              const response = new Response(seeded.payload, {
                status: 200,
                headers: {'Content-Type': 'application/json'}
              });
              phase('challenge-preflight-seeded', 200);
              phase('challenge-preflight-end', 200);
              return Promise.resolve(response);
            }
            return nativeFetch('/api/ad/challenge', {
              method: 'POST',
              headers: {'Content-Type': 'application/json'},
              body: JSON.stringify({path: scope, force: false}),
              credentials: 'include',
              cache: 'no-store'
            }).then(response => {
              phase('challenge-preflight-end', response.status);
              return response;
            });
          };
          const begin = () => {
            if (challengeFlight || !fingerprintReady()) return false;
            challengeFlight = browserChallenge().catch(error => {
              challengeFlight = null;
              phase('challenge-preflight-failed');
              throw error;
            });
            return true;
          };
          const requestShape = async args => {
            try {
              const input = args[0];
              const init = args[1];
              const rawUrl = typeof input === 'string' ? input : input?.url;
              const method = String(init?.method || input?.method || 'GET').toUpperCase();
              if (method !== 'POST') return null;
              if (new URL(rawUrl, location.href).pathname !== '/api/ad/challenge') return null;
              const request = new Request(args[0], args[1]);
              const payload = JSON.parse(await request.clone().text());
              if (payload?.path !== scope || payload?.force === true) return null;
              return request;
            } catch (_) {
              return null;
            }
          };
          window.fetch = async (...args) => {
            const request = await requestShape(args);
            if (!request) return nativeFetch(...args);
            begin();
            const flight = challengeFlight;
            if (!flight || delivered) return nativeFetch(...args);
            try {
              const response = await flight;
              delivered = true;
              phase('challenge-preflight-reused', response.status);
              window.__nativeProviderChallengeConsumed = true;
              window.dispatchEvent(new CustomEvent('ntk-native-challenge-consumed', {
                detail: {scope}
              }));
              return response.clone();
            } catch (_) {
              return nativeFetch(...args);
            }
          };

          window.__nativeChallengeResponse = async () => {
            begin();
            if (!challengeFlight) return null;
            return (await challengeFlight).clone();
          };

          let waitCount = 0;
          const waitForFingerprint = () => {
            if (begin() || waitCount >= 20) return;
            waitCount += 1;
            window.setTimeout(waitForFingerprint, Math.min(80, 4 * waitCount));
          };
          waitForFingerprint();
        })();
    """.trimIndent()
}
