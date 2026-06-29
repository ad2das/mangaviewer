/* visitorlive tracker v2 — 대형 분석 서비스 표준 (page_view, session, UTM, referrer, SPA)
 *
 * 임베드 예시:
 *   <script src="https://whoas.xyz/live/track.js" async></script>
 *   <script src="https://whoas.xyz/live/track.js" data-site="myblog" async></script>
 *
 * 자동 추적: page_view (자동), session_start, session_end
 * 수동 이벤트: window.vlive('event_name', {props})
 */
(function () {
  if (window.__vlive) return; window.__vlive = true;

  var script = document.currentScript || (function() {
    var ss = document.getElementsByTagName('script');
    return ss[ss.length - 1];
  })();

  // ── SERVER & SITE ──
  var SERVER, SITE;
  try {
    var u = new URL(script.src);
    SERVER = u.host;
    SITE = script.getAttribute('data-site') || u.searchParams.get('site') || '';
  } catch(e) {
    SERVER = location.host;
    SITE = '';
  }
  if (!SITE) SITE = location.hostname.replace(/[^a-zA-Z0-9._-]/g,'').toLowerCase().slice(0,64);
  if (!SITE) SITE = 'unknown';

  // ── ANON_ID (영구 — 브라우저 단위) ──
  var COOKIE_NAME = '__vsid';
  function readCookie() { try { var m=document.cookie.match(new RegExp('(?:^|; )'+COOKIE_NAME+'=([^;]+)')); return m?decodeURIComponent(m[1]):''; } catch(_){return '';} }
  function writeCookie(v) { try { var sec=location.protocol==='https:'?'; Secure':''; document.cookie=COOKIE_NAME+'='+encodeURIComponent(v)+'; path=/; max-age=31536000; SameSite=Lax'+sec; } catch(_){} }
  function readLS() { try { return localStorage.getItem(COOKIE_NAME)||''; } catch(_){return '';} }
  function writeLS(v) { try { localStorage.setItem(COOKIE_NAME, v); } catch(_){} }
  function readSS() { try { return sessionStorage.getItem(COOKIE_NAME)||''; } catch(_){return '';} }
  function writeSS(v) { try { sessionStorage.setItem(COOKIE_NAME, v); } catch(_){} }

  function genId() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,function(c){
      var r=Math.random()*16|0,v=c==='x'?r:(r&0x3)|0x8; return v.toString(16);
    });
  }

  function resolveAnonId() {
    var v = readCookie() || readLS() || readSS();
    if (!v) v = genId();
    writeCookie(v); writeLS(v); writeSS(v);
    return v;
  }
  var ANON_ID = resolveAnonId();

  // ── SESSION_ID (30분 idle 만료) ──
  var SESSION_KEY = '__vlive_sess';
  var SESSION_IDLE_MS = 30 * 60 * 1000; // 30분

  function getSession() {
    try {
      var raw = sessionStorage.getItem(SESSION_KEY) || localStorage.getItem(SESSION_KEY);
      if (raw) {
        var s = JSON.parse(raw);
        if (s && s.id && s.last && (Date.now()-s.last) < SESSION_IDLE_MS) {
          s.last = Date.now();
          var data = JSON.stringify(s);
          sessionStorage.setItem(SESSION_KEY, data);
          localStorage.setItem(SESSION_KEY, data);
          return { id: s.id, isNew: false, started: s.started };
        }
      }
    } catch(_){}
    var now = Date.now();
    var sess = { id: genId(), started: now, last: now };
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(sess)); } catch(_){}
    try { localStorage.setItem(SESSION_KEY, JSON.stringify(sess)); } catch(_){}
    return { id: sess.id, isNew: true, started: now };
  }
  function touchSession() {
    try {
      var raw = sessionStorage.getItem(SESSION_KEY) || localStorage.getItem(SESSION_KEY);
      if (raw) {
        var s = JSON.parse(raw); s.last = Date.now();
        var data = JSON.stringify(s);
        sessionStorage.setItem(SESSION_KEY, data);
        localStorage.setItem(SESSION_KEY, data);
      }
    } catch(_){}
  }
  var SESSION = getSession();

  // ── UTM/REFERRER ──
  function parseUTM() {
    var p = new URLSearchParams(location.search);
    return {
      utm_source: p.get('utm_source')||'',
      utm_medium: p.get('utm_medium')||'',
      utm_campaign: p.get('utm_campaign')||'',
      utm_term: p.get('utm_term')||'',
      utm_content: p.get('utm_content')||''
    };
  }

  // ── 이벤트 BUILDER ──
  function buildEvent(name, props) {
    var utm = parseUTM();
    return {
      n: name,
      anon_id: ANON_ID,
      session_id: SESSION.id,
      site: SITE,
      page_path: location.pathname,
      page_url: location.href.slice(0,2000),
      page_title: (document.title||'').slice(0,200),
      referrer: (document.referrer||'').slice(0,1000),
      utm_source: utm.utm_source.slice(0,100),
      utm_medium: utm.utm_medium.slice(0,100),
      utm_campaign: utm.utm_campaign.slice(0,200),
      utm_term: utm.utm_term.slice(0,200),
      utm_content: utm.utm_content.slice(0,200),
      screen_w: screen.width||0,
      screen_h: screen.height||0,
      viewport_w: window.innerWidth||0,
      viewport_h: window.innerHeight||0,
      ts: Date.now(),
      props: props || null
    };
  }

  // ── 전송 (sendBeacon → fetch keepalive fallback) ──
  function send(ev) {
    var url = 'https://' + SERVER + '/collect';
    var body = JSON.stringify(ev);
    try {
      if (navigator.sendBeacon) {
        var blob = new Blob([body], {type:'application/json'});
        if (navigator.sendBeacon(url, blob)) return;
      }
    } catch(_){}
    try {
      fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, body:body, keepalive:true, mode:'no-cors'}).catch(function(){});
    } catch(_){}
  }

  // ── 공개 API ──
  window.vlive = function(name, props) {
    if (typeof name !== 'string' || !name) return;
    touchSession();
    send(buildEvent(name, props));
  };

  // ── 자동: page_view ──
  function pageView() {
    touchSession();
    send(buildEvent('page_view'));
  }

  // ── 첫 진입 ──
  if (SESSION.isNew) send(buildEvent('session_start'));
  pageView();

  // ── SPA pushState/replaceState/popstate hook ──
  (function(){
    var lastPath = location.pathname + location.search;
    function check(){
      var cur = location.pathname + location.search;
      if (cur !== lastPath) { lastPath = cur; pageView(); }
    }
    var origPush = history.pushState;
    var origReplace = history.replaceState;
    history.pushState = function(){ var r=origPush.apply(this, arguments); setTimeout(check,0); return r; };
    history.replaceState = function(){ var r=origReplace.apply(this, arguments); setTimeout(check,0); return r; };
    window.addEventListener('popstate', check);
  })();

  // ── 실시간 동접 alive beacon (whos.amung.us 모델, HTTP polling) ──
  // 기존 WebSocket 영속 연결 → 60초 polling 으로 교체. 30만 동접 부하 99% 감소.
  // sendBeacon 로 비차단 전송, 페이지 background 시 skip.
  function aliveBeacon() {
    if (document.visibilityState !== 'visible') return;
    var url = 'https://' + SERVER + '/beacon';
    var body = JSON.stringify({anon_id: ANON_ID, site: SITE});
    try {
      if (navigator.sendBeacon) {
        var blob = new Blob([body], {type:'application/json'});
        if (navigator.sendBeacon(url, blob)) { touchSession(); return; }
      }
    } catch(_){}
    try {
      fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, body:body, keepalive:true, mode:'no-cors'}).catch(function(){});
      touchSession();
    } catch(_){}
  }
  aliveBeacon(); // 첫 진입 즉시 1번
  setInterval(aliveBeacon, 60000); // 60초마다
  // 페이지 visibility 복귀 시 즉시 1번 (background→foreground 전환 케이스)
  document.addEventListener('visibilitychange', function(){
    if (document.visibilityState === 'visible') aliveBeacon();
  });

  // ── 페이지 이탈 시 session_end ──
  var sessionStartedAt = SESSION.started;
  var pageviewCount = 1;
  window.addEventListener('pagehide', function() {
    var ev = buildEvent('session_end');
    ev.duration_sec = Math.floor((Date.now()-sessionStartedAt)/1000);
    ev.props = JSON.stringify({pageviews: pageviewCount});
    send(ev);
  });

  // pageView 카운트
  var origPageView = pageView;
  pageView = function() { pageviewCount++; origPageView(); };
})();
