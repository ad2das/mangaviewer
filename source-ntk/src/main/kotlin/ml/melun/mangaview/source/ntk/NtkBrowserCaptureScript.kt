package ml.melun.mangaview.source.ntk

internal object NtkBrowserCaptureScript {
    const val BRIDGE_NAME = "NtkNativeManifest"

    fun boundSource(request: RemoteRequest, source: String): String =
        "(() => { const nativeRequestId = ${request.requestId}; " +
            "const nativeDocumentEpoch = ${request.documentEpoch}; " +
            "const nativeCaptureEvidence = ${request.captureEvidence};\n$source\n})();"

    val source: String = """
        (() => {
          if (window.top !== window.self) return;
          if (window.__nativeManifestCapture) return;
          window.__nativeManifestCapture = true;
          const report = (phase, status = 0) => {
            try {
              window.NtkNativeManifest.onPhase(
                location.origin,
                location.pathname,
                phase,
                Number(status) || 0, nativeRequestId, nativeDocumentEpoch
              );
            } catch (_) {}
          };
          const scheduleManifest = () => {
            if (!window.__nativeAckReady || window.__nativeManifestObserved ||
                window.__nativeManifestFallbackScheduled) return;
            const descriptor = window.__nativeManifestDescriptor;
            if (!descriptor || typeof descriptor.path !== 'string' ||
                !descriptor.body) return;
            window.__nativeManifestFallbackScheduled = true;
            window.setTimeout(() => {
              window.__nativeManifestFallbackScheduled = false;
              if (window.__nativeManifestObserved || window.__nativeManifestFlight ||
                  typeof window.__nativePrepareManifestRequest !== 'function') return;
              window.__nativeManifestFlight = window.__nativePrepareManifestRequest(descriptor)
                .then(prepared => {
                  if (!prepared || window.__nativeManifestObserved) return null;
                  return window.fetch(prepared.path, prepared.init);
                }).then(response => {
                if (!response) {
                  window.__nativeManifestFlight = null;
                  return null;
                }
                if (!response.ok) window.__nativeManifestFlight = null;
                return response;
              }).catch(error => {
                window.__nativeManifestFlight = null;
                return null;
              });
            }, 50);
          };
          window.__nativeScheduleManifest = scheduleManifest;
          window.addEventListener('ntk-ad-ack-ready', event => {
            const scope = event?.detail?.scope;
            if (!scope || scope === location.pathname) {
              window.__nativeAckReady = true;
              report('ack-ready');
              scheduleManifest();
            }
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
                  headerNames: Array.from(request.headers.keys()).sort().join('|'),
                  workId: typeof body?.workId === 'string' ? body.workId : '',
                episodeId: typeof body?.episodeId === 'string' ? body.episodeId : '',
                token: typeof body?.token === 'string' ? body.token : ''
              };
            } catch (_) {
              return null;
            }
          };
          const captureManifest = (response, requestPath, identityPromise) => {
            const observed = nativeCaptureEvidence
              ? response.clone().arrayBuffer().then(buffer => {
                  if (buffer.byteLength > 128 * 1024) throw new Error('manifest-evidence-capacity-exceeded');
                  const bytes = new Uint8Array(buffer);
                  const payload = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
                  const chunks = [];
                  for (let i = 0; i < bytes.length; i += 8192) {
                    chunks.push(String.fromCharCode(...bytes.subarray(i, i + 8192)));
                  }
                  return {payload, rawBodyBase64: btoa(chunks.join('')), rawBodyBytes: bytes.length};
                })
              : response.clone().json().then(payload => ({payload}));
            Promise.all([observed, identityPromise]).then(([captured, identity]) => {
              const payload = captured.payload;
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
                    ...(nativeCaptureEvidence ? {
                      responseStatus: response.status,
                      responseBodyBase64: captured.rawBodyBase64,
                      responseBodyBytes: captured.rawBodyBytes
                    } : {}),
                    images
                  }), nativeRequestId, nativeDocumentEpoch
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
              } else if (requestPath === '/api/ad/canary') {
                report(
                  'canary-meta:ok=' + (payload?.ok === true) +
                  ',guardLoaded=' + (payload?.guardLoaded === true),
                  response.status
                );
              } else if (requestPath === '/api/ad/ack') {
                const detail = String(
                  payload?.status || payload?.code || payload?.reason || payload?.error || ''
                ).slice(0, 48);
                report(
                  'ack-meta:ok=' + (payload?.ok === true) +
                  ',acked=' + (payload?.acked === true) +
                  ',detail=' + detail,
                  response.status
                );
                if (!response.ok || payload?.ok !== true) {
                  window.dispatchEvent(new CustomEvent('ntk-ad-ack-rejected', {
                    detail: {scope: location.pathname, status: response.status}
                  }));
                }
              }
            }).catch(() => {});
          };
          const originalFetch = window.fetch.bind(window);
          window.__nativeAckStartedPromise = new Promise(resolve => {
            window.__nativeResolveAckStarted = resolve;
          });
          window.__nativeWaitForCanary = async () => {
            while (window.__nativeCanaryFlight) {
              const observed = window.__nativeCanaryFlight;
              await observed.catch(() => false);
              if (observed === window.__nativeCanaryFlight) break;
            }
            const settledAt = Number(window.__nativeCanarySettledAt || 0);
            const remaining = settledAt > 0
              ? 120 - (performance.now() - settledAt)
              : 0;
            if (remaining > 0) {
              await new Promise(resolve => window.setTimeout(resolve, remaining));
            }
          };
          window.fetch = async (...args) => {
            let requestPath = '';
            try {
              const raw = typeof args[0] === 'string' ? args[0] : args[0]?.url;
              requestPath = new URL(raw, location.href).pathname;
            } catch (_) {}
            if (requestPath === '/api/ad/challenge') report('challenge-start');
            if (requestPath === '/api/ad/canary') {
              report('canary-start');
            }
            if (requestPath === '/api/ad/ack') {
              // Claim the single outgoing ACK before awaiting the latest canary. Without this,
              // an Activity launch can delay the continuation long enough for the bounded guard
              // retry to enqueue a second ACK while the first one is already pending.
              window.__nativeAckRequestStarted = true;
              window.__nativeResolveAckStarted?.(true);
              // Provider guard initialization may launch a second canary and schedule ACK only a
              // few milliseconds before that response commits. Serialize the outgoing ACK behind
              // the newest observed canary instead of paying for a full document recovery reload.
              await window.__nativeWaitForCanary();
              report('ack-start');
            }
            if (requestPath === '/api/client-key/register') report('key-register-start');
            if (requestPath === '/api/webtoon-images' ||
                requestPath === '/api/manhwa-images') report('manifest-start');
            const capturesImages = requestPath === '/api/webtoon-images' ||
              requestPath === '/api/manhwa-images';
            if (capturesImages && window.__nativeManifestNetwork) {
              report('manifest-deduplicated');
              return window.__nativeManifestNetwork.then(response => response.clone());
            }
            if (capturesImages) window.__nativeManifestObserved = true;
            const identityPromise = capturesImages
              ? requestIdentity(args)
              : Promise.resolve(null);
            if (capturesImages) identityPromise.then(identity => report(
              'manifest-request-headers:' + String(identity?.headerNames || '').slice(0, 120)
            ));
            const network = originalFetch(...args);
            if (capturesImages) window.__nativeManifestNetwork = network;
            if (requestPath === '/api/ad/canary') {
              window.__nativeCanarySettledAt = 0;
              window.__nativeCanaryFlight = network.then(
                response => {
                  window.__nativeCanarySettledAt = performance.now();
                  return response.ok;
                },
                () => false
              );
            }
            let response;
            try {
              response = await network;
            } catch (error) {
              if (capturesImages && window.__nativeManifestNetwork === network) {
                window.__nativeManifestNetwork = null;
              }
              throw error;
            }
            try {
              if (requestPath === '/api/ad/challenge') {
                report('challenge-end', response.status);
              }
              if (requestPath === '/api/ad/canary') report('canary-end', response.status);
              if (requestPath === '/api/ad/ack') report('ack-end', response.status);
              if (requestPath === '/api/client-key/register') {
                report('key-register-end', response.status);
              }
              if (requestPath === '/api/ad/challenge' || requestPath === '/api/ad/canary' ||
                  requestPath === '/api/ad/ack') {
                reportAuthorizationShape(response, requestPath);
              }
              if (capturesImages) {
                report('manifest-end', response.status);
                if (!response.ok && window.__nativeManifestNetwork === network) {
                  window.__nativeManifestNetwork = null;
                }
                if (!response.ok) response.clone().json().then(payload => report(
                  'manifest-meta:' + String(
                    payload?.status || payload?.code || payload?.reason ||
                    payload?.error || payload?.message || ''
                  ).slice(0, 64),
                  response.status
                )).catch(() => {});
                captureManifest(response, requestPath, identityPromise);
              }
            } catch (_) {}
            return response;
          };
        })();
    """.trimIndent()
}
