// newxtoon document relay for mangaviewer.
//
// A Cloudflare Worker subrequest is not handed the zone challenge, so the app can read catalog
// and chapter HTML without ever holding a cf_clearance cookie.
//
// Deploy: Cloudflare dashboard -> Workers & Pages -> Create Worker -> paste this file -> Deploy.
// The app calls it as:  https://<your-worker>.workers.dev/?url=<encoded origin url>
//
// Optional hardening: add a Worker variable PROXY_KEY. When set, the app must send &key=<value>,
// which keeps the relay from being usable as an open proxy by anyone else.

const ALLOWED_HOST = /(^|\.)newxtoon\d*\.(com|to|net|cc)$/i;

const DEFAULT_USER_AGENT =
  'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/133.0.0.0 Mobile Safari/537.36';

export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);
    const target = incoming.searchParams.get('url');
    if (!target) {
      return new Response('missing url', { status: 400 });
    }

    if (env && env.PROXY_KEY && incoming.searchParams.get('key') !== env.PROXY_KEY) {
      return new Response('forbidden', { status: 403 });
    }

    let destination;
    try {
      destination = new URL(target);
    } catch (error) {
      return new Response('bad url', { status: 400 });
    }
    if (destination.protocol !== 'https:' || !ALLOWED_HOST.test(destination.hostname)) {
      return new Response('host not allowed', { status: 403 });
    }

    const upstream = await fetch(destination.toString(), {
      method: request.method === 'POST' ? 'POST' : 'GET',
      headers: {
        'User-Agent': request.headers.get('User-Agent') || DEFAULT_USER_AGENT,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7',
        'Referer': destination.origin + '/',
      },
      redirect: 'follow',
    });

    const headers = new Headers();
    headers.set('Access-Control-Allow-Origin', '*');
    headers.set('Cache-Control', 'no-store');
    headers.set(
      'Content-Type',
      upstream.headers.get('Content-Type') || 'text/html; charset=utf-8',
    );
    return new Response(upstream.body, { status: upstream.status, headers });
  },
};
