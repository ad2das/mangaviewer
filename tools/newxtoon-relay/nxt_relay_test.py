import base64
import json
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

import websocket

PORT = 9555
URL = "https://newxtoon1.com/comics?page=1&sort=latest"

with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/list", timeout=6) as r:
    targets = json.load(r)
page = next(t for t in targets if t.get("type") == "page")

ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=10, suppress_origin=True)
ws.settimeout(1.0)
seq = 0


def send(method, params=None):
    global seq
    seq += 1
    ws.send(json.dumps({"id": seq, "method": method, "params": params or {}}))
    return seq


def wait_id(rid, budget=6.0):
    t0 = time.time()
    while time.time() - t0 < budget:
        try:
            m = json.loads(ws.recv())
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            return None
        if m.get("id") == rid:
            return m
    return None


def evaljs(expr, budget=6.0):
    rid = send("Runtime.evaluate", {"returnByValue": True, "expression": expr})
    r = wait_id(rid, budget)
    return ((r or {}).get("result") or {}).get("result", {}).get("value")


send("Page.enable")
send("Network.enable")
send("Runtime.enable")
send("Page.navigate", {"url": URL})
print("NAV", URL, flush=True)

deadline = time.time() + 150
clicked = 0
passed = False
last = 0.0
while time.time() < deadline:
    now = time.time()
    if now - last >= 4:
        last = now
        val = evaljs("JSON.stringify({t:document.title,u:location.href.slice(0,80)})")
        try:
            st = json.loads(val or "{}")
        except Exception:
            st = {}
        print("STATE", val, flush=True)
        t = st.get("t", "")
        if t and "Just a moment" not in t and "잠시만" not in t:
            passed = True
            break
        if clicked < 5 and ("Just a moment" in t or "잠시만" in t):
            v2 = evaljs("""
(function(){
  function deep(){var out=[];function walk(root){var l=root.querySelectorAll('iframe');for(var i=0;i<l.length;i++){out.push(l[i]);}var a=root.querySelectorAll('*');for(var j=0;j<a.length;j++){var e=a[j];if(e.shadowRoot){walk(e.shadowRoot);}}}walk(document);return out;}
  var fr=deep();
  for(var i=0;i<fr.length;i++){var f=fr[i];var s=(f.src||'')+(f.id||'')+(f.className||'');if(s.indexOf('challenges.cloudflare.com')>=0||s.indexOf('turnstile')>=0||s.indexOf('cf-chl')>=0){var b=f.getBoundingClientRect();if(b.width>=80&&b.height>=30){return JSON.stringify({x:b.left,y:b.top,w:b.width,h:b.height});}}}
  var sels=['#challenge-stage','.cf-turnstile','#turnstile-wrapper','[id*=turnstile]','[class*=turnstile]','#cf-challenge-running','.main-content'];
  for(var s=0;s<sels.length;s++){var e=document.querySelector(sels[s]);if(!e){continue;}var r=e.getBoundingClientRect();if(r.width>=100&&r.height>=30){return JSON.stringify({x:r.left,y:r.top,w:r.width,h:r.height});}}
  return 'NONE';
})()
""")
            print("FRAME", v2, flush=True)
            if v2 and v2 != "NONE":
                try:
                    fr = json.loads(v2)
                    cx = fr["x"] + 30.0
                    cy = fr["y"] + fr["h"] / 2.0
                    clicked += 1
                    print("CLICK", clicked, round(cx), round(cy), flush=True)
                    send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": cx, "y": cy})
                    time.sleep(0.15)
                    send("Input.dispatchMouseEvent", {"type": "mousePressed", "x": cx, "y": cy, "button": "left", "buttons": 1, "clickCount": 1})
                    time.sleep(0.09)
                    send("Input.dispatchMouseEvent", {"type": "mouseReleased", "x": cx, "y": cy, "button": "left", "buttons": 0, "clickCount": 1})
                except Exception as e:
                    print("CLICK-ERR", e, flush=True)
            else:
                vw = evaljs("JSON.stringify({w:innerWidth,h:innerHeight})")
                try:
                    dim = json.loads(vw or "{}")
                    cx = dim.get("w", 1280) / 2.0
                    cy = dim.get("h", 800) * 0.45
                    clicked += 1
                    print("CLICK-CENTER", clicked, round(cx), round(cy), flush=True)
                    send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": cx, "y": cy})
                    time.sleep(0.15)
                    send("Input.dispatchMouseEvent", {"type": "mousePressed", "x": cx, "y": cy, "button": "left", "buttons": 1, "clickCount": 1})
                    time.sleep(0.09)
                    send("Input.dispatchMouseEvent", {"type": "mouseReleased", "x": cx, "y": cy, "button": "left", "buttons": 0, "clickCount": 1})
                except Exception as e:
                    print("CLICK-ERR2", e, flush=True)

print("PASSED" if passed else "NOT-PASSED", flush=True)
ua = evaljs("navigator.userAgent")
print("UA", ua, flush=True)

rid = send("Storage.getCookies", {})
r = wait_id(rid, 8.0)
ck = ((r or {}).get("result") or {}).get("cookies") or []
jar = {}
for c in ck:
    if "newxtoon" in (c.get("domain") or ""):
        jar[c.get("name")] = c.get("value")
        print("COOKIE", c.get("name"), "=", (c.get("value") or "")[:90], "exp", c.get("expires"), flush=True)
ws.close()

with open("C:\\Users\\Administrator\\AppData\\Local\\Temp\\opencode\\nxt_clearance.json", "w") as f:
    json.dump({"ua": ua, "cookies": jar}, f)

if not passed:
    print("NO-CLEARANCE, skip curl test", flush=True)
    sys.exit(0)

from curl_cffi import requests as cr

print("== curl_cffi test via relay2 ==", flush=True)
s = cr.Session(impersonate="chrome", proxy="http://127.0.0.1:8899")
h = {"User-Agent": ua, "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
     "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"}
try:
    resp = s.get(URL, headers=h, cookies=jar, timeout=30)
    print("STATUS", resp.status_code, flush=True)
    print("MITIGATED", resp.headers.get("cf-mitigated"), flush=True)
    body = resp.text
    print("LEN", len(body), flush=True)
    print("HEAD", body[:300].replace("\n", " "), flush=True)
except Exception as e:
    print("CURL-ERR", type(e).__name__, str(e)[:200], flush=True)
