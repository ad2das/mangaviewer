import json
import logging
import time

from curl_cffi import requests as cr
from mitmproxy import http

log = logging.getLogger("nxt-relay")

CLEAR_PATH = r"C:\Users\Administrator\AppData\Local\Temp\opencode\nxt_clearance.json"
RELAY = "http://127.0.0.1:8899"

with open(CLEAR_PATH, encoding="utf-8") as f:
    _clear = json.load(f)
UA = _clear["ua"]
CLEAR_COOKIES = _clear["cookies"]

_session = cr.Session(impersonate="chrome", proxy=RELAY)

SKIP_REQ = {"host", "content-length", "accept-encoding", "connection", "proxy-connection", "cookie", "user-agent"}
SKIP_RESP = {"content-encoding", "content-length", "transfer-encoding", "connection", "keep-alive", "alt-svc"}


def _merge_cookies(header: str) -> str:
    merged = {}
    for part in (header or "").split(";"):
        if "=" in part:
            k, v = part.strip().split("=", 1)
            if k:
                merged[k] = v
    merged.update(CLEAR_COOKIES)
    return "; ".join(f"{k}={v}" for k, v in merged.items())


def request(flow: http.HTTPFlow) -> None:
    host = flow.request.pretty_host
    if not host.endswith("newxtoon1.com"):
        return
    headers = {k: v for k, v in flow.request.headers.items() if k.lower() not in SKIP_REQ}
    headers["User-Agent"] = UA
    headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in CLEAR_COOKIES.items())
    started = time.time()
    body = flow.request.content if flow.request.method in ("POST", "PUT", "PATCH") else None
    _session.cookies.clear()
    try:
        r = _session.request(
            flow.request.method,
            flow.request.pretty_url,
            headers=headers,
            data=body,
            timeout=45,
            allow_redirects=False,
        )
    except Exception as exc:  # noqa: BLE001
        log.warning("relay failure %s %s: %s", flow.request.method, flow.request.pretty_url, exc)
        flow.response = http.Response.make(502, f"nxt-relay error: {exc}".encode())
        return
    rh = {k: v for k, v in r.headers.items() if k.lower() not in SKIP_RESP}
    flow.response = http.Response.make(r.status_code, r.content, rh)
    if r.status_code != 200:
        log.info(
            "relay-debug %s %s -> %s cf=%s sent=%s body=%s",
            flow.request.method,
            flow.request.path[:80],
            r.status_code,
            r.headers.get("cf-mitigated"),
            sorted(headers.keys()),
            r.text[:160].replace("\n", " "),
        )
    else:
        log.info(
            "relay %s %s -> %s (%d bytes, %.0f ms)",
            flow.request.method,
            flow.request.path[:80],
            r.status_code,
            len(r.content),
            (time.time() - started) * 1000,
        )
