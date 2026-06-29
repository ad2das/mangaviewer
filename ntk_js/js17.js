// biome-ignore-all lint: legacy ES5 controller moved from inline JSX so soft navigation can execute it safely
(function(){
  function modal(){ return document.getElementById('auth-modal'); }
  function open(tab){
    var m = modal(); if (!m) return;
    setTab(tab || 'login');
    if (typeof m.showModal === 'function') { try { m.showModal(); } catch(_) { m.setAttribute('open',''); } }
    else m.setAttribute('open','');
  }
  function close(){
    var m = modal(); if (!m) return;
    if (typeof m.close === 'function') m.close();
    else m.removeAttribute('open');
  }
  function setTab(name){
    var m = modal(); if (!m) return;
    m.querySelectorAll('[data-auth-tab]').forEach(function(t){
      var on = t.getAttribute('data-auth-tab') === name;
      t.classList.toggle('active', on);
      t.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    m.querySelectorAll('[data-auth-panel]').forEach(function(p){
      p.hidden = p.getAttribute('data-auth-panel') !== name;
    });
  }
  // URL ?auth_error=... 처리 — 페이지 첫 진입 시 한 번만
  if (!window.__authErrChecked) {
    window.__authErrChecked = true;
    try {
      var u = new URL(location.href);
      var err = u.searchParams.get('auth_error');
      if (err) {
        var initTab = (location.hash === '#signup') ? 'signup' : 'login';
        setTimeout(function(){
          open(initTab);
          var m = modal();
          var panel = m && m.querySelector('[data-auth-panel="' + initTab + '"]');
          if (panel) {
            var box = document.createElement('div');
            box.className = 'auth-error';
            box.textContent = err;
            panel.insertBefore(box, panel.firstChild);
          }
        }, 50);
        u.searchParams.delete('auth_error');
        u.hash = '';
        history.replaceState(null, '', u.pathname + (u.search || ''));
      }
    } catch(_) {}
  }
  // 이메일 인증 핸들러 — signup 폼 안의 send/verify 버튼 + 이메일 변경 감지
  if (!window.__emverifBound) {
    window.__emverifBound = true;
    function emForm(){ return document.querySelector('[data-em-form]'); }
    function emEl(sel){ var f = emForm(); return f ? f.querySelector(sel) : null; }
    function emStatus(text, kind){
      var s = emEl('[data-em-status]'); if (!s) return;
      s.textContent = text || '';
      s.className = 'auth-status' + (kind ? ' is-' + kind : '');
    }
    function emReset(){
      var code = emEl('[data-em-code]');
      if (code) { code.value = ''; code.disabled = true; code.placeholder = '먼저 인증코드 받기를 눌러주세요'; }
      var verify = emEl('[data-em-verify]'); if (verify) verify.disabled = true;
      var submit = emEl('[data-em-submit]'); if (submit) submit.disabled = true;
      window.__emailVerified = false;
      window.__emailVerifiedFor = '';
      emStatus('', '');
    }
    async function emPost(url, body){
      try {
        var res = await fetch(url, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'accept': 'application/json' },
          body: JSON.stringify(body),
        });
        var data = null;
        try { data = await res.json(); } catch(_) {}
        return { ok: res.ok, status: res.status, data: data || {} };
      } catch (_) {
        return { ok: false, status: 0, data: { error: '네트워크 오류' } };
      }
    }
    document.addEventListener('click', async function(e){
      var sendBtn = e.target.closest && e.target.closest('[data-em-send]');
      if (sendBtn) {
        e.preventDefault();
        var emailEl = emEl('[data-em-input]');
        var email = emailEl ? String(emailEl.value || '').trim().toLowerCase() : '';
        if (!email) { emStatus('이메일을 입력해주세요', 'err'); return; }
        // 일반 이메일 형식만 client 측에서 빠르게 검사. 허용 도메인 검증은 서버에서
        // (어드민 설정 allowed_email_domains 기준) — 도메인 정책 바뀌어도 client 코드 손볼 필요 없게.
        if (!/^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$/.test(email)) {
          emStatus('이메일 형식이 올바르지 않습니다', 'err');
          return;
        }
        var emLocal = email.split('@')[0];
        if (emLocal.indexOf('+') !== -1 || emLocal.indexOf('.') !== -1) {
          emStatus("'+' 또는 '.' 가 포함된 이메일은 사용할 수 없습니다", 'err');
          return;
        }
        sendBtn.disabled = true;
        emStatus('전송 중...', '');
        var r = await emPost('/api/auth/send-email-code', { email: email });
        if (r.ok && r.data.ok) {
          var codeIn = emEl('[data-em-code]');
          if (codeIn) { codeIn.disabled = false; codeIn.placeholder = '6자리 숫자'; codeIn.focus(); }
          var verifyB = emEl('[data-em-verify]'); if (verifyB) verifyB.disabled = false;
          emStatus('인증 코드를 전송했습니다 (10분 안에 입력)', 'ok');
          var sec = 60;
          var orig = sendBtn.textContent;
          var tick = function(){
            if (sec <= 0) { sendBtn.disabled = false; sendBtn.textContent = orig; return; }
            sendBtn.textContent = '재전송 (' + sec + ')';
            sec -= 1;
            setTimeout(tick, 1000);
          };
          tick();
        } else {
          sendBtn.disabled = false;
          emStatus((r.data && r.data.error) || '전송 실패', 'err');
        }
        return;
      }
      // 닉네임 중복확인 버튼
      var nickBtn = e.target.closest && e.target.closest('[data-nick-check]');
      if (nickBtn) {
        e.preventDefault();
        var nickEl = emEl('[data-nick-input]');
        var statusEl = emEl('[data-nick-status]');
        var nickname = nickEl ? String(nickEl.value || '').trim() : '';
        var setNickStatus = function(text, kind){
          if (!statusEl) return;
          statusEl.textContent = text || '';
          statusEl.className = 'auth-status' + (kind ? ' is-' + kind : '');
        };
        if (!nickname) { setNickStatus('닉네임을 입력하세요', 'err'); return; }
        nickBtn.disabled = true;
        setNickStatus('확인 중...', '');
        var nr = await emPost('/api/auth/check-nickname', { nickname: nickname });
        nickBtn.disabled = false;
        if (nr.ok && nr.data && nr.data.ok) {
          if (nr.data.available) {
            setNickStatus('✓ 사용 가능한 닉네임입니다', 'ok');
            window.__nickVerified = true;
            window.__nickVerifiedFor = nickname;
          } else {
            setNickStatus('이미 사용 중인 닉네임입니다', 'err');
            window.__nickVerified = false;
          }
        } else {
          setNickStatus((nr.data && nr.data.error) || '확인 실패', 'err');
          window.__nickVerified = false;
        }
        refreshSubmit();
        return;
      }
      var verifyBtn = e.target.closest && e.target.closest('[data-em-verify]');
      if (verifyBtn) {
        e.preventDefault();
        var emailEl2 = emEl('[data-em-input]');
        var codeEl = emEl('[data-em-code]');
        var email2 = emailEl2 ? String(emailEl2.value || '').trim() : '';
        var code = String((codeEl && codeEl.value) || '')
          .replace(/[０-９]/g, function(ch){ return String.fromCharCode(ch.charCodeAt(0) - 0xFEE0); })
          .replace(/\D/g, '');
        if (codeEl) codeEl.value = code;
        if (!code) { emStatus('인증코드를 입력하세요', 'err'); return; }
        verifyBtn.disabled = true;
        emStatus('확인 중...', '');
        var r2 = await emPost('/api/auth/verify-email-code', { email: email2, code: code });
        if (r2.ok && r2.data.ok) {
          emStatus('✓ 인증완료', 'ok');
          if (codeEl) codeEl.disabled = true;
          var emEl2 = emEl('[data-em-input]'); if (emEl2) emEl2.readOnly = true;
          window.__emailVerified = true;
          refreshSubmit();
        } else {
          verifyBtn.disabled = false;
          window.__emailVerified = false;
          refreshSubmit();
          emStatus((r2.data && r2.data.error) || '확인 실패', 'err');
        }
        return;
      }
    });

    // ── 회원가입 폼 클라이언트 검증 ──────────────────────────────────────
    // 비밀번호 강도 / 비번 확인 / PIN 강도 / PIN 확인 / 닉네임 중복확인 / 이메일 인증
    // 모두 충족해야 submit 버튼 활성화. 서버 측 validateSignup 과 동일 규칙.
    window.__nickVerified = false;
    window.__nickVerifiedFor = '';
    window.__emailVerified = false;
    window.__emailVerifiedFor = '';

    function checkPwStrength(pw, username){
      if (!pw) return { ok: false, msg: '' };
      if (pw.length < 10 || pw.length > 64) return { ok: false, msg: '10~64자' };
      if (/\s/.test(pw)) return { ok: false, msg: '공백 불가' };
      if (!/^[\x21-\x7e]+$/.test(pw)) return { ok: false, msg: '영문/숫자/특수문자만' };
      if (!/[A-Za-z]/.test(pw)) return { ok: false, msg: '영문 포함 필요' };
      if (!/\d/.test(pw)) return { ok: false, msg: '숫자 포함 필요' };
      if (!/[^A-Za-z0-9]/.test(pw)) return { ok: false, msg: '특수문자 포함 필요' };
      // 같은 문자 3회 연속
      for (var i = 0; i + 2 < pw.length; i++) {
        if (pw[i] === pw[i+1] && pw[i+1] === pw[i+2]) return { ok: false, msg: '같은 문자 3회 연속 불가' };
      }
      // 연속 4자 (1234, abcd, qwer)
      var lowerPw = pw.toLowerCase();
      var keyboardRows = ['1234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm', 'abcdefghijklmnopqrstuvwxyz'];
      for (var ki = 0; ki < keyboardRows.length; ki++) {
        var row = keyboardRows[ki];
        for (var kj = 0; kj + 3 < row.length; kj++) {
          var seq = row.substring(kj, kj + 4);
          var rev = seq.split('').reverse().join('');
          if (lowerPw.indexOf(seq) >= 0 || lowerPw.indexOf(rev) >= 0) {
            return { ok: false, msg: '연속 문자 (1234, abcd 등) 불가' };
          }
        }
      }
      // 숫자 연속 4자 (mod 10 — 9012 도)
      for (var di = 0; di + 3 < pw.length; di++) {
        var d0 = pw.charCodeAt(di), d1 = pw.charCodeAt(di+1), d2 = pw.charCodeAt(di+2), d3 = pw.charCodeAt(di+3);
        if (d0 >= 48 && d0 <= 57 && d1 >= 48 && d1 <= 57 && d2 >= 48 && d2 <= 57 && d3 >= 48 && d3 <= 57) {
          var n0 = d0 - 48, n1 = d1 - 48, n2 = d2 - 48, n3 = d3 - 48;
          if (((n1 - n0 + 10) % 10) === 1 && ((n2 - n1 + 10) % 10) === 1 && ((n3 - n2 + 10) % 10) === 1) return { ok: false, msg: '연속 숫자 불가' };
          if (((n0 - n1 + 10) % 10) === 1 && ((n1 - n2 + 10) % 10) === 1 && ((n2 - n3 + 10) % 10) === 1) return { ok: false, msg: '연속 숫자 불가' };
        }
      }
      if (username && username.length >= 3 && lowerPw.indexOf(username.toLowerCase()) >= 0) {
        return { ok: false, msg: '아이디 포함 불가' };
      }
      return { ok: true, msg: '✓ 사용 가능' };
    }

    function checkPinStrength(pin){
      if (!pin) return { ok: false };
      if (!/^\d{4}$/.test(pin)) return { ok: false, msg: '4자리 숫자' };
      if (/^(\d)\1{3}$/.test(pin)) return { ok: false, msg: '같은 숫자 반복 불가' };
      var ds = pin.split('').map(function(c){ return parseInt(c, 10); });
      var asc = true, desc = true;
      for (var i = 1; i < ds.length; i++) {
        if (((ds[i] - ds[i-1] + 10) % 10) !== 1) asc = false;
        if (((ds[i-1] - ds[i] + 10) % 10) !== 1) desc = false;
      }
      if (asc || desc) return { ok: false, msg: '연속 숫자 불가' };
      return { ok: true };
    }

    function setStatus(el, msg, kind){
      if (!el) return;
      el.textContent = msg || '';
      el.className = 'auth-status' + (kind ? ' is-' + kind : '');
    }

    function refreshSubmit(){
      var submit = emEl('[data-em-submit]');
      if (!submit) return;
      var f = emForm();
      if (!f) return;
      var usernameEl = f.querySelector('[name="username"]');
      var nickEl = f.querySelector('[data-nick-input]');
      var pwEl = f.querySelector('[data-pw-input]');
      var pwConfirmEl = f.querySelector('[data-pw-confirm-input]');
      var pinEl = f.querySelector('[name="pin"]');
      var pinConfirmEl = f.querySelector('[name="pinConfirm"]');

      var username = usernameEl ? String(usernameEl.value || '').trim() : '';
      var nickname = nickEl ? String(nickEl.value || '').trim() : '';
      var pw = pwEl ? String(pwEl.value || '') : '';
      var pwConfirm = pwConfirmEl ? String(pwConfirmEl.value || '') : '';
      var pin = pinEl ? String(pinEl.value || '').trim() : '';
      var pinConfirm = pinConfirmEl ? String(pinConfirmEl.value || '').trim() : '';

      var usernameOk = /^[A-Za-z0-9_]{3,20}$/.test(username);
      var nickOk = nickname.length >= 2 && nickname.length <= 9 && window.__nickVerified && window.__nickVerifiedFor === nickname;
      var emailOk = !!window.__emailVerified;
      var pwCheck = checkPwStrength(pw, username);
      var pwOk = pwCheck.ok;
      var pwMatch = pw.length > 0 && pw === pwConfirm;
      var pinCheck = checkPinStrength(pin);
      var pinOk = pinCheck.ok;
      var pinMatch = pin.length === 4 && pin === pinConfirm;

      submit.disabled = !(usernameOk && nickOk && emailOk && pwOk && pwMatch && pinOk && pinMatch);

      // submit hover 시 어떤 항목이 안 됐는지 안내
      if (submit.disabled) {
        var missing = [];
        if (!usernameOk) missing.push('아이디 (3~20자 영문/숫자/_)');
        if (!emailOk) missing.push('이메일 인증');
        if (!nickOk) missing.push(nickname.length < 2 ? '닉네임 (2~9자)' : (window.__nickVerifiedFor !== nickname ? '닉네임 중복확인' : '닉네임 검증'));
        if (!pwOk) missing.push('비밀번호: ' + (pwCheck.msg || '필요 요건 미충족'));
        if (pwOk && !pwMatch) missing.push('비밀번호 확인 일치');
        if (!pinOk) missing.push('PIN: ' + (pinCheck.msg || '필요 요건 미충족'));
        if (pinOk && !pinMatch) missing.push('PIN 확인 일치');
        submit.title = '아직 입력 안 된 항목:\\n• ' + missing.join('\\n• ');
      } else {
        submit.title = '';
      }
    }

    // 비밀번호 실시간 검증
    document.addEventListener('input', function(e){
      var el = e.target;
      if (!el || !el.matches) return;
      if (el.matches('[data-pw-input]')) {
        var pw = String(el.value || '');
        var f = emForm(); if (!f) return;
        var usernameEl = f.querySelector('[name="username"]');
        var username = usernameEl ? String(usernameEl.value || '').trim() : '';
        var statusEl = f.querySelector('[data-pw-status]');
        if (pw === '') {
          setStatus(statusEl, '10~64자 · 영문+숫자+특수문자 모두 포함 · 연속/반복/흔한 패턴 금지', '');
        } else {
          var res = checkPwStrength(pw, username);
          setStatus(statusEl, res.msg || '', res.ok ? 'ok' : 'err');
        }
        // 확인란도 재평가
        var pwConfirmEl = f.querySelector('[data-pw-confirm-input]');
        var pwConfirmStatus = f.querySelector('[data-pw-confirm-status]');
        if (pwConfirmEl && pwConfirmEl.value) {
          if (pwConfirmEl.value === pw) setStatus(pwConfirmStatus, '✓ 일치', 'ok');
          else setStatus(pwConfirmStatus, '불일치', 'err');
        } else {
          setStatus(pwConfirmStatus, '', '');
        }
        refreshSubmit();
        return;
      }
      if (el.matches('[data-pw-confirm-input]')) {
        var f2 = emForm(); if (!f2) return;
        var pwEl2 = f2.querySelector('[data-pw-input]');
        var pw2 = pwEl2 ? String(pwEl2.value || '') : '';
        var statusEl2 = f2.querySelector('[data-pw-confirm-status]');
        var conf = String(el.value || '');
        if (conf === '') setStatus(statusEl2, '', '');
        else if (conf === pw2) setStatus(statusEl2, '✓ 일치', 'ok');
        else setStatus(statusEl2, '불일치', 'err');
        refreshSubmit();
        return;
      }
      if (el.matches('[name="username"]')) {
        // username 변경 시 비번 정책 (username 포함 금지) 재평가
        var f3 = emForm(); if (!f3) return;
        var pwEl3 = f3.querySelector('[data-pw-input]');
        if (pwEl3 && pwEl3.value) {
          pwEl3.dispatchEvent(new Event('input', { bubbles: true }));
        } else {
          refreshSubmit();
        }
        return;
      }
      if (el.matches('[data-nick-input]')) {
        // 닉네임 변경 시 중복확인 다시 받아야 함
        if (window.__nickVerifiedFor !== String(el.value || '').trim()) {
          window.__nickVerified = false;
          var statusEl3 = emEl('[data-nick-status]');
          if (statusEl3 && statusEl3.textContent && statusEl3.textContent.indexOf('✓') === 0) {
            setStatus(statusEl3, '닉네임 변경됨 — 중복확인 다시', '');
          }
        }
        refreshSubmit();
        return;
      }
      if (el.matches('[name="pin"]') || el.matches('[name="pinConfirm"]')) {
        refreshSubmit();
        return;
      }
    });
    // 인증코드칸 — 키 입력 단계에서 비숫자 차단
    document.addEventListener('keydown', function(e){
      var el = e.target;
      if (!el || !el.matches || !el.matches('[data-em-code]')) return;
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (e.key && e.key.length === 1 && !/^\d$/.test(e.key)) {
        e.preventDefault();
      }
    });
    // paste / IME / 전각 숫자 등 — 사후 정리
    document.addEventListener('input', function(e){
      var el = e.target;
      if (!el || !el.matches) return;
      if (el.matches('[data-em-code]')) {
        var v = String(el.value || '')
          .replace(/[０-９]/g, function(ch){ return String.fromCharCode(ch.charCodeAt(0) - 0xFEE0); })
          .replace(/\D/g, '')
          .slice(0, 6);
        if (v !== el.value) el.value = v;
        return;
      }
      if (el.matches('[data-em-input]')) {
        var emailEl3 = emEl('[data-em-input]');
        if (emailEl3 && emailEl3.readOnly) {
          emailEl3.readOnly = false;
          emReset();
        }
      }
    });
  }
  // 글로벌 클릭 핸들러 한 번만 등록
  if (window.__authBound) return;
  window.__authBound = true;
  document.addEventListener('click', function(e){
    var t = e.target.closest && e.target.closest('[data-auth-open]');
    if (t) {
      e.preventDefault();
      open(t.getAttribute('data-auth-open'));
      return;
    }
    if (e.target.closest && e.target.closest('[data-auth-close]')) {
      close();
      return;
    }
    var tab = e.target.closest && e.target.closest('[data-auth-tab]');
    var m = modal();
    if (tab && m && m.contains(tab)) {
      setTab(tab.getAttribute('data-auth-tab'));
      return;
    }
    if (m && e.target === m) close();
  });

  // PIN 입력 — 숫자만 (회원가입 폼의 pin / pinConfirm input). 키보드/붙여넣기/IME 입력 모두 필터.
  // 입력 즉시 약한 PIN 안내 메시지 표시 (1111, 1234 등). 회원가입 폼의 pin status span 에 직접 출력.
  function _pinStrengthCheck(pin) {
    if (!/^\d{4}$/.test(pin)) return { ok: false, msg: '4자리 숫자' };
    if (/^(\d)\1{3}$/.test(pin)) return { ok: false, msg: '같은 숫자 반복(예: 1111)은 사용 불가' };
    var ds = pin.split('').map(function(c){ return parseInt(c, 10); });
    var asc = true, desc = true;
    for (var i = 1; i < ds.length; i++) {
      if (((ds[i] - ds[i - 1] + 10) % 10) !== 1) asc = false;
      if (((ds[i - 1] - ds[i] + 10) % 10) !== 1) desc = false;
    }
    if (asc || desc) return { ok: false, msg: '연속된 숫자(예: 1234, 9876)는 사용 불가' };
    return { ok: true };
  }
  function _setStatus(el, msg, kind) {
    if (!el) return;
    el.textContent = msg || '';
    el.className = 'auth-status' + (kind ? ' is-' + kind : '');
  }
  document.addEventListener('input', function(e){
    var t = e.target;
    if (!t || !t.matches || !t.matches('[data-pin-input]')) return;
    var cleaned = String(t.value || '').replace(/\D+/g, '').slice(0, 4);
    if (t.value !== cleaned) t.value = cleaned;
    var f = document.querySelector('[data-em-form]');
    if (!f) return;
    if (t.name === 'pin') {
      var pinStatus = f.querySelector('[data-pin-status]');
      if (pinStatus) {
        if (cleaned === '') {
          _setStatus(pinStatus, '로그인 시 비밀번호 입력 후 한 번 더 묻습니다. 연속 숫자(1234) / 같은 숫자 반복(1111) 은 사용 불가.', '');
        } else if (cleaned.length < 4) {
          _setStatus(pinStatus, '4자리 숫자를 입력해주세요', '');
        } else {
          var res = _pinStrengthCheck(cleaned);
          _setStatus(pinStatus, res.ok ? '✓ 사용 가능한 PIN' : res.msg, res.ok ? 'ok' : 'err');
        }
      }
      // pin 변경되면 pinConfirm status 도 즉시 재평가.
      var pinConfirmEl = f.querySelector('[name="pinConfirm"]');
      var pinConfirmStatus = f.querySelector('[data-pin-confirm-status]');
      if (pinConfirmEl && pinConfirmStatus && pinConfirmEl.value) {
        if (pinConfirmEl.value === cleaned && cleaned.length === 4) _setStatus(pinConfirmStatus, '✓ 일치', 'ok');
        else _setStatus(pinConfirmStatus, 'PIN 이 일치하지 않습니다', 'err');
      }
    }
    if (t.name === 'pinConfirm') {
      var pinConfirmStatus2 = f.querySelector('[data-pin-confirm-status]');
      if (pinConfirmStatus2) {
        var pinEl = f.querySelector('[name="pin"]');
        var firstPin = pinEl ? String(pinEl.value || '') : '';
        if (cleaned === '') _setStatus(pinConfirmStatus2, '', '');
        else if (cleaned === firstPin && firstPin.length === 4) _setStatus(pinConfirmStatus2, '✓ 일치', 'ok');
        else _setStatus(pinConfirmStatus2, 'PIN 이 일치하지 않습니다', 'err');
      }
    }
  }, true);
  document.addEventListener('keypress', function(e){
    var t = e.target;
    if (!t || !t.matches || !t.matches('[data-pin-input]')) return;
    if (e.key.length === 1 && !/^\d$/.test(e.key)) e.preventDefault();
  }, true);

  // ─── 로그인 fetch 화 + PIN 모달 흐름 ───
  var pendingToken = null;
  var pendingNext = null;    // "pin-verify" | "pin-setup"
  var pinBuffer = "";
  var pinLength = 4;         // 서버 응답의 pinLength 로 동적 설정 (기존 5/6자리 사용자 호환)
  var setupFirst = null;     // setup 흐름의 1차 PIN
  var setupStep = "first";   // "first" | "confirm"

  function pinPanel(){ var m = modal(); return m ? m.querySelector('[data-auth-panel="pin"]') : null; }
  function setPinError(msg){
    var p = pinPanel(); if (!p) return;
    var el = p.querySelector('[data-pin-error]');
    if (el) el.textContent = msg || "";
  }
  function setPinTitle(t, d){
    var p = pinPanel(); if (!p) return;
    var tt = p.querySelector('[data-pin-title]');
    var dd = p.querySelector('[data-pin-desc]');
    if (tt) tt.textContent = t;
    if (dd) dd.textContent = d;
  }
  function renderDots(){
    var p = pinPanel(); if (!p) return;
    var holder = p.querySelector('[data-pin-dots]');
    if (!holder) return;
    // 기존 dots 모두 제거 후 pinLength 만큼 새로 생성
    while (holder.firstChild) holder.removeChild(holder.firstChild);
    for (var i = 0; i < pinLength; i++) {
      var dot = document.createElement('span');
      dot.setAttribute('data-pin-dot', '');
      dot.setAttribute('data-idx', String(i));
      dot.style.cssText = 'display:inline-block;width:12px;height:12px;border-radius:50%;border:2px solid var(--brand, #e63946);background:' + (i < pinBuffer.length ? 'var(--brand, #e63946)' : 'transparent');
      holder.appendChild(dot);
    }
  }
  function shuffleDigits(){
    var arr = [0,1,2,3,4,5,6,7,8,9];
    for (var i = arr.length - 1; i > 0; i--){
      var j = Math.floor(Math.random() * (i + 1));
      var tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
    }
    return arr;
  }
  function renderKeys(){
    var p = pinPanel(); if (!p) return;
    var digits = shuffleDigits();
    // 12 슬롯: [0..8] = digits[0..8], [9] = clear, [10] = digits[9], [11] = back
    p.querySelectorAll('[data-pin-key]').forEach(function(btn){
      var slot = parseInt(btn.getAttribute('data-slot'), 10);
      if (slot === 9) { btn.textContent = '전체삭제'; btn.setAttribute('data-action', 'clear'); btn.style.color = 'var(--text-muted, #888)'; btn.style.background = 'var(--surface-2, #f7f7fa)'; }
      else if (slot === 11) { btn.textContent = '←'; btn.setAttribute('data-action', 'back'); btn.style.color = 'var(--text-muted, #888)'; btn.style.background = 'var(--surface-2, #f7f7fa)'; }
      else {
        var idx = slot === 10 ? 9 : slot; // slot 10 → digits[9]
        var d = digits[idx];
        btn.textContent = String(d);
        btn.setAttribute('data-digit', String(d));
        btn.removeAttribute('data-action');
        btn.style.color = 'inherit';
        btn.style.background = 'var(--panel, #fff)';
      }
    });
  }
  function resetPinUi(){
    pinBuffer = "";
    setPinError("");
    renderDots();
    renderKeys();
  }
  function showPinPanel(next, len){
    pendingNext = next;
    pinLength = (len === 4 || len === 5 || len === 6) ? len : 4;
    setupFirst = null;
    setupStep = "first";
    if (next === "pin-setup") {
      pinLength = 4; // 신규 등록은 4자리 강제
      setPinTitle("🔐 2차 비밀번호 등록", "처음 로그인이시네요. PIN 4자리를 등록해주세요. 연속된 숫자(1234, 9876) / 같은 숫자 반복(1111)은 사용 불가.");
    } else {
      setPinTitle("🔒 2차 비밀번호 입력", "키패드로 PIN(" + pinLength + "자리) 을 입력해주세요.");
    }
    resetPinUi();
    setTab("pin");
  }

  async function submitVerify(pin){
    setPinError("");
    try {
      var res = await fetch("/api/auth/login-pin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pendingToken: pendingToken, pin: pin }),
      });
      var data = await res.json().catch(function(){ return null; });
      if (!res.ok || !data || !data.ok) {
        setPinError((data && data.error) || "PIN 오류");
        resetPinUi();
        return;
      }
      window.location.reload();
    } catch (e) {
      setPinError("네트워크 오류");
      resetPinUi();
    }
  }
  async function submitSetup(first, second){
    setPinError("");
    try {
      var res = await fetch("/api/auth/setup-pin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pendingToken: pendingToken, pin: first, pinConfirm: second }),
      });
      var data = await res.json().catch(function(){ return null; });
      if (!res.ok || !data || !data.ok) {
        setPinError((data && data.error) || "등록 실패");
        setupFirst = null;
        setupStep = "first";
        setPinTitle("🔐 2차 비밀번호 등록", "다시 입력해주세요.");
        resetPinUi();
        return;
      }
      window.location.reload();
    } catch (e) {
      setPinError("네트워크 오류");
      resetPinUi();
    }
  }

  function onPinComplete(pin){
    if (pendingNext === "pin-verify") {
      submitVerify(pin);
    } else if (pendingNext === "pin-setup") {
      if (setupStep === "first") {
        setupFirst = pin;
        setupStep = "confirm";
        setPinTitle("🔐 2차 비밀번호 등록", "다시 한 번 입력해 확인해주세요");
        pinBuffer = "";
        renderDots();
        renderKeys();
      } else {
        submitSetup(setupFirst, pin);
      }
    }
  }

  document.addEventListener('click', function(e){
    var btn = e.target && e.target.closest && e.target.closest('[data-pin-key]');
    if (!btn) return;
    var act = btn.getAttribute('data-action');
    if (act === 'clear') {
      pinBuffer = "";
      renderDots();
      return;
    }
    if (act === 'back') {
      pinBuffer = pinBuffer.slice(0, -1);
      renderDots();
      return;
    }
    var d = btn.getAttribute('data-digit');
    if (d == null) return;
    if (pinBuffer.length >= pinLength) return;
    pinBuffer += d;
    renderDots();
    if (pinBuffer.length === pinLength) {
      // 자동 제출 — 사용자별 PIN 길이에 도달하면 호출.
      setTimeout(function(){ onPinComplete(pinBuffer); }, 80);
    }
  });
  document.addEventListener('click', function(e){
    var b = e.target && e.target.closest && e.target.closest('[data-pin-back-to-login]');
    if (!b) return;
    pendingToken = null;
    setTab('login');
  });

  // 로그인 form intercept — submit 시 fetch 로 처리. 비번 OK + next=pin-* 이면 PIN 패널 표시.
  document.addEventListener('submit', function(e){
    var form = e.target && e.target.closest && e.target.closest('[data-auth-panel="login"] form');
    if (!form) return;
    e.preventDefault();
    var fd = new FormData(form);
    var body = {};
    fd.forEach(function(v, k){ body[k] = String(v); });
    fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "application/json" },
      body: JSON.stringify(body),
    }).then(function(r){ return r.json().then(function(j){ return { status: r.status, body: j }; }); })
      .then(function(res){
        if (res.body && res.body.ok && res.body.next && res.body.pendingToken) {
          pendingToken = res.body.pendingToken;
          showPinPanel(res.body.next, res.body.pinLength);
          return;
        }
        // 에러
        var msg = (res.body && res.body.error) || "로그인 실패";
        alert(msg);
      }).catch(function(){ alert("네트워크 오류"); });
  });
})();
