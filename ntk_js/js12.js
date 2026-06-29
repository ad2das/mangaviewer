(function () {
  try {
    var m = document.cookie.match(/(?:^|; )theme=([^;]+)/);
    var t = m ? decodeURIComponent(m[1]) : "light";
    if (t !== "dark" && t !== "light") t = "light";
    document.documentElement.setAttribute("data-theme", t);
  } catch (_) {}
})();
