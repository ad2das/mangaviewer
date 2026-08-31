package ml.melun.mangaview.source.ntk

internal object NtkBrowserCaptureScript {
    const val BRIDGE_NAME = "NtkNativeManifest"

    val source: String = """
        (() => {
          if (window.__nativeManifestCapture) return;
          window.__nativeManifestCapture = true;
          const report = (phase, status = 0) => {
            try {
              window.NtkNativeManifest.onPhase(
                location.origin,
                location.pathname,
                phase,
                Number(status) || 0
              );
            } catch (_) {}
          };
          window.addEventListener('ntk-ad-ack-ready', event => {
            const scope = event?.detail?.scope;
            if (!scope || scope === location.pathname) report('ack-ready');
          });
          const requestIdentity = async args => {
            try {
              const request = new Request(args[0], args[1]);
              const contentType = request.headers.get('content-type') || '';
              const body = contentType.toLowerCase().includes('json')
                ? JSON.parse(await request.clone().text())
                : null;
              return {
                method: request.method || '',
                contentType,
                workId: typeof body?.workId === 'string' ? body.workId : '',
                episodeId: typeof body?.episodeId === 'string' ? body.episodeId : '',
                token: typeof body?.token === 'string' ? body.token : ''
              };
            } catch (_) {
              return null;
            }
          };
          const captureManifest = (response, requestPath, identityPromise) => {
            Promise.all([response.clone().json(), identityPromise]).then(([payload, identity]) => {
              const images = Array.isArray(payload?.images) ? payload.images : [];
              if (response.ok && payload?.ok === true && Array.isArray(payload.images)) {
                window.NtkNativeManifest.onImages(
                  location.origin,
                  location.pathname,
                  JSON.stringify({
                    ok: true,
                    endpoint: requestPath,
                    responseUrl: response.url || '',
                    responseContentType: response.headers.get('content-type') || '',
                    requestMethod: identity?.method || '',
                    requestContentType: identity?.contentType || '',
                    requestWorkId: identity?.workId || '',
                    requestEpisodeId: identity?.episodeId || '',
                    requestToken: identity?.token || '',
                    images
                  })
                );
              }
            }).catch(error => report(
              'manifest-capture-failed:' + String(error?.name || 'Error') + ':' +
                String(error?.message || error || '').slice(0, 96),
              response.status
            ));
          };
          const reportAuthorizationShape = (response, requestPath) => {
            response.clone().json().then(payload => {
              if (requestPath === '/api/ad/challenge') {
                const challenge = payload?.challenge;
                report(
                  'challenge-meta:ok=' + (payload?.ok === true) +
                  ',ackValid=' + (payload?.ackValid === true) +
                  ',trusted=' + (payload?.trusted === true) +
                  ',challenge=' + (!!challenge) +
                  ',minSeen=' + (Number(challenge?.minSeen) || 0) +
                  ',slots=' + (Number(challenge?.slotCount) || 0) +
                  ',impressions=' + (Array.isArray(challenge?.impressionUrls) ? challenge.impressionUrls.length : 0) +
                  ',guard=' + (typeof challenge?.guardVersion === 'string' && challenge.guardVersion.length > 0),
                  response.status
                );
              } else if (requestPath === '/api/ad/ack') {
                report(
                  'ack-meta:ok=' + (payload?.ok === true) +
                  ',acked=' + (payload?.acked === true) +
                  ',status=' + String(payload?.status || '').slice(0, 24),
                  response.status
                );
              }
            }).catch(() => {});
          };
          const originalFetch = window.fetch.bind(window);
          window.fetch = async (...args) => {
            let requestPath = '';
            try {
              const raw = typeof args[0] === 'string' ? args[0] : args[0]?.url;
              requestPath = new URL(raw, location.href).pathname;
            } catch (_) {}
            if (requestPath === '/api/ad/challenge') report('challenge-start');
            if (requestPath === '/api/ad/canary') report('canary-start');
            if (requestPath === '/api/ad/ack') {
              window.__nativeAckRequestStarted = true;
              report('ack-start');
            }
            if (requestPath === '/api/client-key/register') report('key-register-start');
            if (requestPath === '/api/webtoon-images' ||
                requestPath === '/api/manhwa-images') report('manifest-start');
            const capturesImages = requestPath === '/api/webtoon-images' ||
              requestPath === '/api/manhwa-images';
            const identityPromise = capturesImages
              ? requestIdentity(args)
              : Promise.resolve(null);
            const response = await originalFetch(...args);
            try {
              if (requestPath === '/api/ad/challenge') {
                report('challenge-end', response.status);
              }
              if (requestPath === '/api/ad/canary') report('canary-end', response.status);
              if (requestPath === '/api/ad/ack') report('ack-end', response.status);
              if (requestPath === '/api/client-key/register') {
                report('key-register-end', response.status);
              }
              if (requestPath === '/api/ad/challenge' || requestPath === '/api/ad/ack') {
                reportAuthorizationShape(response, requestPath);
              }
              if (capturesImages) {
                report('manifest-end', response.status);
                captureManifest(response, requestPath, identityPromise);
              }
            } catch (_) {}
            return response;
          };
        })();
    """.trimIndent()
}
