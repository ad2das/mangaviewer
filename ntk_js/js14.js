(function () {
  try {
    var KEY = "ntk_pid";
    function r() {
      var b = new Uint8Array(16);
      crypto && crypto.getRandomValues
        ? crypto.getRandomValues(b)
        : b.forEach(function (_, i) {
            b[i] = Math.random() * 256;
          });
      var h = "";
      for (var i = 0; i < b.length; i++)
        h += ("0" + b[i].toString(16)).slice(-2);
      return h;
    }
    var c = document.cookie.match(/(?:^|; )ntk_pid=([a-f0-9]{32})/);
    var ck = c ? c[1] : "";
    var ls = "";
    try {
      ls = localStorage.getItem(KEY) || "";
    } catch (_) {}
    var pid = ck || (ls.match(/^[a-f0-9]{32}$/) ? ls : "") || r();
    if (!ck) {
      var pidSecure = location.protocol === "https:" ? "; Secure" : "";
      document.cookie =
        "ntk_pid=" + pid + "; Path=/; Max-Age=63072000; SameSite=Lax" + pidSecure;
    }
    try {
      if (ls !== pid) localStorage.setItem(KEY, pid);
    } catch (_) {}
    try {
      if (window.indexedDB) {
        var req = indexedDB.open("ntk", 1);
        req.onupgradeneeded = function (e) {
          e.target.result.createObjectStore("kv");
        };
        req.onsuccess = function (e) {
          try {
            var db = e.target.result;
            var tx = db.transaction("kv", "readwrite");
            tx.objectStore("kv").put(pid, KEY);
          } catch (_) {}
        };
      }
    } catch (_) {}
  } catch (_) {}
})();
