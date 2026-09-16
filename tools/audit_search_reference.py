"""Capture independently parsed, complete public search results for the device contract audit.

Usage: python tools/audit_search_reference.py --output artifacts/search-reference
Requires requests and beautifulsoup4. No credentials, images, or reader pages are fetched.
"""
import argparse
import concurrent.futures
import datetime
import json
import pathlib
import re
import time
import urllib.parse

import requests
from bs4 import BeautifulSoup

ORIGINS = {
    "ntk": "https://sbxh9.com",
    "wfwf": "https://wfwf497.com",
    "newxtoon": "https://newxtoon1.com",
    "goodtoon": "https://www.goodtoon004.com",
}


def route(provider, query, kind, field, page):
    if provider == "ntk":
        return "/search?" + urllib.parse.urlencode(dict(q=query, kind=kind, field=field, match="contains", page=page))
    if provider == "wfwf":
        return "/sh?" + urllib.parse.urlencode(dict(t2="", t3="", o="n", pg=page, q=query), encoding="euc-kr")
    if provider == "newxtoon":
        return "/search?" + urllib.parse.urlencode(dict(q=query, page=page))
    return "/?" + urllib.parse.urlencode(dict(q=query, pg=page))


def cards(provider, dom):
    if provider == "ntk":
        return [(a["href"], a.select_one(".subject").get_text(strip=True))
                for a in dom.select(".search-results-grid a[href]")
                if re.fullmatch(r"/(webtoon|manhwa)/[^/]+", a["href"]) and a.select_one(".subject")]
    if provider == "wfwf":
        result = []
        for a in dom.select("a.t-card[href]"):
            url = urllib.parse.urlparse(a["href"])
            params = urllib.parse.parse_qs(url.query)
            title = a.select_one(".t-title")
            if url.path in ("/list", "/cl") and params.get("toon") and title:
                result.append((("comic:" if url.path == "/cl" else "webtoon:") + params["toon"][0], title.get_text(strip=True)))
        return result
    if provider == "newxtoon":
        return [(urllib.parse.urlparse(a["href"]).path.split("/")[-1], a.select_one("h3").get_text(strip=True))
                for a in dom.select("a.comic-link[href]") if a.select_one("h3")
                and re.fullmatch(r"/comics/\d+", urllib.parse.urlparse(a["href"]).path)]
    return [(urllib.parse.unquote(urllib.parse.urlparse(a["href"]).path.strip("/").split("/")[-1]),
             a.select_one(".subject").get_text(strip=True)) for a in dom.select("a.card[href]") if a.select_one(".subject")]


def next_page(provider, dom, current):
    selector = {"ntk": ".pager a[href]", "wfwf": ".pagi a[href]",
                "goodtoon": ".pagination a[href]", "newxtoon": 'nav[data-site-pagination] a[href]'}[provider]
    links = dom.select(selector)
    if provider == "newxtoon" and not links:
        links = dom.select('nav[role="navigation"] a[href]')
    parameter = "pg" if provider in ("wfwf", "goodtoon") else "page"
    numbers = []
    for a in links:
        value = urllib.parse.parse_qs(urllib.parse.urlparse(a["href"]).query).get(parameter, [""])[0]
        if value.isdigit() and int(value) > current:
            numbers.append(int(value))
    return current + 1 if numbers else None


def capture(provider, output, reuse=False, selected_cases=None):
    directory = output / provider
    directory.mkdir(parents=True, exist_ok=True)
    cases = [("survival", "생존", "title"), ("love", "사랑", "title"),
             ("exact", "화산귀환", "title"), ("absent", "mv_no_match_20260917_8fd7", "title")]
    if provider != "wfwf":
        cases.append(("author", "비가", "author"))
    if selected_cases:
        cases = [case for case in cases if case[0] in selected_cases]
    records = []
    session = requests.Session()
    session.mount("https://", requests.adapters.HTTPAdapter(max_retries=3))
    session.headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    for name, query, field in cases:
        items, pages = {}, []
        for kind in (["webtoon", "manhwa"] if provider == "ntk" else [None]):
            number = 1
            while number:
                if number > 160:
                    raise RuntimeError(f"{provider}: page limit exceeded; reference is incomplete")
                url = ORIGINS[provider] + route(provider, query, kind, field, number)
                filename = f"{name}-{kind or 'all'}-{number}.html"
                target = directory / filename
                if reuse and target.exists():
                    html = target.read_text(encoding="utf-8")
                    final_url = url
                else:
                    response = session.get(url, timeout=30)
                    response.raise_for_status()
                    response.encoding = "euc-kr" if provider == "wfwf" else "utf-8"
                    html, final_url = response.text, response.url
                dom = BeautifulSoup(html, "html.parser")
                if provider == "ntk" and not dom.select_one(".search-page-form"):
                    raise RuntimeError(f"{provider}: unrecognized search response")
                batch = cards(provider, dom)
                (directory / filename).write_text(html, encoding="utf-8")
                pages.append(dict(url=final_url, file=filename, count=len(batch),
                                  repeated=sum(key in items for key, _ in batch)))
                for key, title in batch:
                    items[key] = title
                number = next_page(provider, dom, number)
                if len(pages) % 10 == 0:
                    print(f"{provider} {name}: captured {len(pages)} pages so far", flush=True)
                time.sleep(0.12)
        if name == "absent" and items:
            raise RuntimeError(f"{provider}: unrelated results for absent query")
        if name != "absent" and not items:
            raise RuntimeError(f"{provider}: known search returned no recognized cards")
        records.append(dict(name=name, query=query, field=field.upper(), items=items, pages=pages, complete=True))
        print(f"{provider} {name}: {len(items)} unique titles, {len(pages)} pages", flush=True)
        (directory / "reference.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    return provider, records


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--reuse", action="store_true", help="Reuse already captured pages in this output directory")
    parser.add_argument("--providers", nargs="+", choices=ORIGINS, default=list(ORIGINS))
    parser.add_argument("--cases", nargs="+", choices=("survival", "love", "exact", "absent", "author"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = dict(pool.map(lambda provider: capture(provider, args.output, args.reuse, args.cases), args.providers))
    manifest = dict(capturedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), providers=results)
    (args.output / "reference.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
