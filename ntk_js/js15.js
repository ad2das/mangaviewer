var _K=[125,58,94,28,143,75,45,106,25,63,92,30,141,74,43,111];var _D=function(b){var d=Uint8Array.from(atob(b),function(c){return c.charCodeAt(0)});for(var i=0;i<d.length;i++)d[i]^=_K[i%16];return new TextDecoder().decode(d);};_D("l4jj9jzrF0r1oug+YMang+CGsoEXa8b+jdPhimbegkNd0cqIY/CZh5WzsIMxZguEy76ymBJnDYG/k7esCaahy13WyYhj7K2BkrewiDmhjO6Rp948Yt6Zh5uGfPIG1sDg+dbDqGTinUr1qNj0P+PG8fUatKQHp4rq8q/19QbCwOTZFH7wEv8NhqSrt40RoaH7XdbLlGLAnUf1qej1OvrHyfUata87p7jiOdT1imHxj4T2srK6F6ez7/K01PUG7gVPkafqPGLHoYaEg7CDCWrH8snXy6ija8HwqdLGkqFqwMPJ0f65YtK5RjnT1IZh6r5PlqLO9wXfDYa5qretOaGO013W6Ihj/bGHjKe3vimhoftd0fS0ZNiNSvW0wPUCzsDl6Rq1rzumtdLyr8A+YfGfgu6Ss5g/a8HhhdPWumDPt4Pqqn73A8vA/4Uft7I5oaDHXdb+jWX8kYaOr3zzGP7A5MTXy7VkwKWBkptyPiiKnoKJs4kaeKn7YgVqcLrE3szv4oGZ8ArHwNn51tuCTfzR7r3VyqFd/fG28dbq9J1n+KCb1bq2mAlvyPGB0saZsb+P8fWI3jxi1rWBtYty");
try { window.__ntk_ib_loaded = 1; } catch (_) {}
(function () {
  try {
    var _k = [0x9e,0x3f,0x71,0x2c,0x8b,0x4a,0xd6,0x15,0xe7,0x5d,0x33,0x9a,0x2f,0x6c,0x84,0xb1,0x47,0x59,0xae,0x18,0xcd,0x7f,0x23,0x60,0x95,0x0a,0xde,0x4b,0x72,0x36,0xf8,0x11];
    function _h(nonce) { for(var r=new Uint8Array(8),i=0;i<8;i++){var n=nonce[i%nonce.length],k=_k[(i*3+7)%32];r[i]=(((n*k)&0xFF)+_k[(i*5+13)%32])&0xFF} return r; }
    function _g(n){ if(!self.crypto||!self.crypto.getRandomValues)return new Uint8Array([0x5a,0x3c,0x7f,0x1e,0x9d,0x2b,0x6a,0x48,0x33,0x8e,0x4f,0x1a,0x7c,0x2d,0x9b,0x55]); var b=new Uint8Array(16);self.crypto.getRandomValues(b);return b; }
    var _b = "\x2f\x61\x70\x69\x2f\x61\x64\x2f";
    var _v = "";try{_v=new URL((document.currentScript&&document.currentScript.src)||location.href).search||"";}catch(_){}
    try{var _q=new URLSearchParams(_v);var _wv=_q.get("wv");if(_wv)_v="?v="+encodeURIComponent(_wv);}catch(_){}
    var _n = _g();
    var _s = _h(_n);

    import(_b + "\x67\x75\x61\x72\x64\x2d\x6a\x73" + _v).then(function (mod) {
      return mod.default({ module_or_path: _b + "\x67\x75\x61\x72\x64\x2d\x77\x61\x73\x6d" + _v }).then(function () { return mod; });
    }).then(function (mod) {
      try {
        var _hs_ok = false;
        if (typeof mod._hk === 'function') { _hs_ok = mod._hk(_n, _s); }
        try { window.__ntk_hs_ok = _hs_ok ? 1 : 0; } catch (_) {}
        if (typeof mod._vc === 'function') { mod._vc(new Uint8Array(64)); }
        try { mod.runBlockBeforeInteractive(); } catch (_) {}
      } catch (_) {}
    }).catch(function (_) {});
  } catch (_) {}
})();
