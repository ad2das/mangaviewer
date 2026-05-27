#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import json
import re
import subprocess
import time
import urllib.parse
import urllib.request
from difflib import SequenceMatcher
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Iterable


OFFICIAL_SOURCE_RELIABILITY = {
    "naver": 1.0,
    "kakao": 1.0,
    "webtoon": 1.0,
    "lezhin": 0.95,
    "anilist": 0.78,
    "mangadex": 0.78,
    "kitsu": 0.72,
    "mangaupdates": 0.80,
    "search": 0.42,
    "ai": 0.62,
}

FIELD_RELIABILITY = {
    "official.genre": 1.0,
    "official.category": 0.92,
    "metadata.genre": 0.85,
    "metadata.tag": 0.75,
    "snippet": 0.45,
    "ai.synopsis": 0.70,
    "ai.title": 0.22,
}

TITLE_DESCRIPTION_RULES = [
    (("회귀", "환생", "빙의", "악녀", "공작", "황녀", "마왕", "용사", "마법", "공녀", "후궁", "악마", "천사"), ["판타지"]),
    (("던전", "헌터", "각성", "레벨", "랭커", "SSS", "퀘스트"), ["액션", "판타지"]),
    (("전투", "격투", "싸움", "전쟁", "생존을 건 사투"), ["액션"]),
    (("무림", "검신", "천마", "소림", "강호", "무협", "검객", "검왕"), ["무협", "액션"]),
    (("학교", "학원", "선배", "동아리", "반장", "교실", "학생", "고등학교", "대학교"), ["학원"]),
    (("살인", "범인", "탐정", "저주", "실종", "복수", "범죄", "형사", "사건"), ["스릴러", "미스터리"]),
    (("귀신", "괴담", "악몽", "공포", "좀비", "유령", "악령", "오싹"), ["공포", "스릴러"]),
    (("요리", "셰프", "식당", "먹방"), ["요리"]),
    (("야구", "축구", "농구", "복싱", "골프", "테니스", "스포츠"), ["스포츠"]),
    (("연애", "신부", "남친", "여친", "결혼", "키스", "첫사랑", "로맨스"), ["로맨스"]),
    (("개그", "코미디", "웃음", "바보", "병맛", "엉뚱"), ["개그"]),
    (("BL", "비엘", "오메가버스", "보이즈러브"), ["BL"]),
    (("백합", "GL", "그녀들"), ["백합"]),
    (("일상물", "소소한 일상"), ["일상"]),
    (("시대", "조선", "왕", "황제", "역사"), ["시대"]),
    (("히어로", "괴인", "악당", "빌런", "초능력", "능력자", "구해야", "구한다"), ["액션", "판타지"]),
    (("드래곤", "용족", "마족", "마녀", "요괴", "괴물", "신비", "이능", "마계", "천계"), ["판타지"]),
    (("조직", "건달", "야쿠자", "마피아", "갱", "폭력", "대결", "복수극", "추격", "암살"), ["액션", "스릴러"]),
    (("저승", "사자", "수호령", "영혼", "귀", "빙의", "환생", "전생"), ["판타지"]),
    (("RPG", "게임", "플레이어", "스테이지", "랭킹", "랭크", "스킬", "아이템"), ["게임", "판타지"]),
    (("아이돌", "배우", "연예계", "오케스트라", "밴드", "가수", "음악", "연주"), ["음악", "드라마"]),
    (("자전거", "라이딩", "군대", "군인", "농구부", "축구부", "야구부", "시합", "대회"), ["스포츠"]),
    (("동거", "짝사랑", "사랑", "고백", "연인", "남편", "아내", "신혼", "스캔들"), ["로맨스"]),
    (("펜션", "감금", "납치", "살해", "죽음", "피", "잔혹", "인터넷 방송", "스토킹"), ["스릴러"]),
]


def normalize_title(value: str) -> str:
    text = re.sub(r"\([^)]*\)", "", value or "")
    text = re.sub(r"[\W_]+", "", text, flags=re.UNICODE)
    return text.casefold()


def similarity(left: str, right: str) -> float:
    left_key = normalize_title(left)
    right_key = normalize_title(right)
    if not left_key or not right_key:
        return 0.0
    score = SequenceMatcher(None, left_key, right_key).ratio()
    if left_key in right_key or right_key in left_key:
        score = max(score, min(len(left_key), len(right_key)) / max(len(left_key), len(right_key)))
    return score


def identity_score(source_item: dict[str, Any], candidate: dict[str, Any]) -> tuple[float, dict[str, float]]:
    title_score = max(
        [similarity(str(source_item.get("name", "")), str(candidate.get("title", "")))]
        + [similarity(str(source_item.get("name", "")), str(title)) for title in candidate.get("altTitles", []) if isinstance(title, str)]
    )
    author_score = 0.0
    source_authors = {str(author).casefold() for author in source_item.get("authors", []) if isinstance(author, str)}
    candidate_authors = {str(author).casefold() for author in candidate.get("authors", []) if isinstance(author, str)}
    if source_authors and candidate_authors:
        author_score = 1.0 if source_authors & candidate_authors else 0.0

    synopsis_score = similarity(str(source_item.get("description", ""))[:240], str(candidate.get("description", ""))[:240])
    year_score = 0.0
    if source_item.get("year") and candidate.get("year"):
        try:
            diff = abs(int(source_item["year"]) - int(candidate["year"]))
            year_score = max(0.0, 1.0 - (diff / 5.0))
        except (TypeError, ValueError):
            year_score = 0.0

    signals = {
        "title": title_score,
        "author": author_score,
        "synopsis": synopsis_score,
        "year": year_score,
    }
    score = 0.45 * title_score + 0.25 * author_score + 0.22 * synopsis_score + 0.08 * year_score
    if title_score >= 0.99 and not author_score and synopsis_score < 0.20:
        score = min(score, 0.70)
    return round(score, 4), {key: round(value, 4) for key, value in signals.items()}


def load_db(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def iter_titles(data: dict[str, Any]) -> Iterable[tuple[str, dict[str, Any]]]:
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    for key, item in titles.items():
        if isinstance(item, dict):
            yield str(key), item


def write_query_plan(db_path: Path, output: Path, kind: str) -> None:
    data = load_db(db_path)
    rows = []
    for key, item in iter_titles(data):
        title = str(item.get("name", "")).strip()
        if not title:
            continue
        rows.append(
            {
                "id": key,
                "kind": kind,
                "title": title,
                "queries": [
                    f'"{title}" 웹툰 장르',
                    f'"{title}" 만화 장르',
                    f'"{title}" site:comic.naver.com',
                    f'"{title}" site:webtoon.kakao.com',
                    f'"{title}" site:page.kakao.com',
                    f'"{title}" site:webtoons.com',
                    f'"{title}" manga genre',
                ],
            }
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


def import_candidates(db_path: Path, candidates_path: Path, output: Path) -> None:
    data = load_db(db_path)
    candidates = [json.loads(line) for line in candidates_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    by_id = {key: item for key, item in iter_titles(data)}
    rows = []
    for candidate in candidates:
        key = str(candidate.get("id") or candidate.get("itemId") or "")
        source_item = by_id.get(key)
        if not source_item:
            continue
        score, signals = identity_score(source_item, candidate)
        tags = candidate.get("normalizedTags") or candidate.get("tags") or []
        if not isinstance(tags, list) or not tags:
            continue
        source = str(candidate.get("source", "search"))
        field = str(candidate.get("field", "snippet"))
        rows.append(
            {
                "id": key,
                "source": source,
                "sourceFamily": "official_platform" if source in {"naver", "kakao", "webtoon", "lezhin"} else "external_metadata",
                "field": field,
                "normalizedTags": tags,
                "identityScore": score,
                "identitySignals": signals,
                "sourceReliability": OFFICIAL_SOURCE_RELIABILITY.get(source, 0.50),
                "fieldReliability": FIELD_RELIABILITY.get(field, 0.60),
                "url": candidate.get("url", ""),
            }
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


def infer_tags_from_text(text: str) -> list[str]:
    lower = text.casefold()
    tags: list[str] = []
    for needles, mapped in TITLE_DESCRIPTION_RULES:
        if any(needle.casefold() in lower for needle in needles):
            for tag in mapped:
                if tag not in tags:
                    tags.append(tag)
    return tags


def infer_descriptions(db_path: Path, output: Path) -> None:
    data = load_db(db_path)
    rows = []
    for key, item in iter_titles(data):
        text = " ".join(str(item.get(field, "")) for field in ("name", "description", "release"))
        tags = infer_tags_from_text(text)
        if not tags:
            continue
        basis = [field for field in ("name", "description", "release") if item.get(field)]
        rows.append(
            {
                "id": key,
                "source": "ai",
                "sourceFamily": "ai_adjudication",
                "field": "ai.synopsis" if item.get("description") else "ai.title",
                "normalizedTags": tags,
                "identityScore": 1.0,
                "identitySignals": {"wfwfItem": 1.0},
                "sourceReliability": 0.62 if item.get("description") else 0.22,
                "fieldReliability": 0.70 if item.get("description") else 0.22,
                "url": "",
                "basis": basis,
            }
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


class DuckDuckGoParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.results: list[dict[str, str]] = []
        self._current: dict[str, str] | None = None
        self._field = ""
        self._parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr = {key: value or "" for key, value in attrs}
        classes = set(attr.get("class", "").split())
        if tag == "a" and "result__a" in classes:
            self._current = {"url": decode_duckduckgo_url(attr.get("href", "")), "title": "", "snippet": ""}
            self._field = "title"
            self._parts = []
        elif self._current is not None and "result__snippet" in classes:
            self._field = "snippet"
            self._parts = []

    def handle_data(self, data: str) -> None:
        if self._field and self._current is not None:
            self._parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if self._current is None or not self._field:
            return
        if tag == "a" and self._field == "title":
            self._current["title"] = compact_text(" ".join(self._parts))
            self._field = ""
            self._parts = []
        elif tag in {"a", "div"} and self._field == "snippet":
            self._current["snippet"] = compact_text(" ".join(self._parts))
            if self._current.get("title"):
                self.results.append(self._current)
            self._current = None
            self._field = ""
            self._parts = []


def compact_text(value: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(value or "")).strip()


def decode_duckduckgo_url(value: str) -> str:
    parsed = urllib.parse.urlparse(html.unescape(value or ""))
    query = urllib.parse.parse_qs(parsed.query)
    if query.get("uddg"):
        return query["uddg"][0]
    return html.unescape(value or "")


def fetch_text(url: str, timeout: float = 6.0) -> str:
    try:
        completed = subprocess.run(
            [
                "curl.exe",
                "-L",
                "--silent",
                "--show-error",
                "--max-time",
                str(int(timeout)),
                "-A",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36",
                "-H",
                "Accept-Language: ko-KR,ko;q=0.9,en-US;q=0.5,en;q=0.3",
                url,
            ],
            check=False,
            capture_output=True,
            timeout=timeout + 2.0,
        )
        if completed.stdout:
            return completed.stdout.decode("utf-8", errors="replace")
    except Exception:
        pass
    return ""
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36",
            "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.5,en;q=0.3",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", errors="replace")


def duckduckgo_search(query: str, max_results: int) -> list[dict[str, str]]:
    url = "https://duckduckgo.com/html/?" + urllib.parse.urlencode({"q": query})
    parser = DuckDuckGoParser()
    parser.feed(fetch_text(url))
    return parser.results[:max_results]


def strip_tags(value: str) -> str:
    return compact_text(re.sub(r"<[^>]+>", " ", value or ""))


def bing_search(query: str, max_results: int) -> list[dict[str, str]]:
    url = "https://www.bing.com/search?" + urllib.parse.urlencode({"q": query})
    page = fetch_text(url)
    results: list[dict[str, str]] = []
    for block in re.findall(r'<li class="b_algo".*?</li>', page, flags=re.IGNORECASE | re.DOTALL):
        link_match = re.search(r'<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', block, flags=re.IGNORECASE | re.DOTALL)
        if not link_match:
            continue
        snippet_match = re.search(r"<p[^>]*>(.*?)</p>", block, flags=re.IGNORECASE | re.DOTALL)
        results.append(
            {
                "url": html.unescape(link_match.group(1)),
                "title": strip_tags(link_match.group(2)),
                "snippet": strip_tags(snippet_match.group(1) if snippet_match else ""),
            }
        )
        if len(results) >= max_results:
            break
    return results


def web_search(query: str, max_results: int) -> list[dict[str, str]]:
    results = bing_search(query, max_results)
    if results:
        return results
    return duckduckgo_search(query, max_results)


def search_priority(item: dict[str, Any]) -> int:
    classification = item.get("classification") if isinstance(item.get("classification"), dict) else {}
    flags = classification.get("flags") if isinstance(classification.get("flags"), dict) else {}
    status = str(classification.get("reviewStatus", ""))
    label = str(classification.get("confidenceLabel", ""))
    if flags.get("nonEmptyForced") or label == "forced_fallback":
        return 0
    if flags.get("sourceConflict") or status == "auto_source_external_conflict":
        return 1
    if "low_confidence" in status or label == "low":
        return 2
    return 3


def search_web_evidence(db_path: Path, output: Path, kind: str, limit: int, max_results: int, sleep: float) -> None:
    data = load_db(db_path)
    candidates = sorted(iter_titles(data), key=lambda pair: (search_priority(pair[1]), int(pair[0]) if pair[0].isdigit() else 0))
    searched = 0
    keyword = "웹툰" if kind == "webtoon" else "만화"
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as handle:
        for key, item in candidates:
            if searched >= limit:
                break
            if search_priority(item) > 2:
                continue
            title = str(item.get("name", "")).strip()
            if not title:
                continue
            searched += 1
            queries = [f'"{title}" {keyword} 장르', f'"{title}" {keyword} 줄거리 장르']
            accepted = False
            for query in queries:
                try:
                    results = web_search(query, max_results)
                except Exception:
                    continue
                for result in results:
                    combined = compact_text(" ".join([result.get("title", ""), result.get("snippet", "")]))
                    title_similarity = similarity(title, result.get("title", ""))
                    text_identity = normalize_title(title) in normalize_title(combined)
                    if not text_identity and title_similarity < 0.88:
                        continue
                    tags = infer_tags_from_text(combined)
                    if not tags:
                        continue
                    identity = max(0.58, min(0.78, title_similarity if title_similarity else 0.62))
                    row = {
                        "id": key,
                        "source": "search",
                        "sourceFamily": "search",
                        "field": "snippet",
                        "normalizedTags": tags,
                        "identityScore": round(identity, 4),
                        "identitySignals": {
                            "title": round(title_similarity, 4),
                            "titleInSnippet": 1.0 if text_identity else 0.0,
                        },
                        "sourceReliability": OFFICIAL_SOURCE_RELIABILITY["search"],
                        "fieldReliability": FIELD_RELIABILITY["snippet"],
                        "url": result.get("url", ""),
                        "query": query,
                        "resultTitle": result.get("title", ""),
                        "snippet": result.get("snippet", ""),
                    }
                    handle.write(json.dumps(row, ensure_ascii=False) + "\n")
                    handle.flush()
                    accepted = True
                    break
                if accepted:
                    break
                if sleep:
                    time.sleep(sleep)
            if sleep:
                time.sleep(sleep)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build external genre evidence JSONL or search query plans.")
    sub = parser.add_subparsers(dest="command", required=True)
    query = sub.add_parser("query-plan")
    query.add_argument("--db", required=True)
    query.add_argument("--kind", choices=("webtoon", "comic"), required=True)
    query.add_argument("--output", required=True)
    ingest = sub.add_parser("import-candidates")
    ingest.add_argument("--db", required=True)
    ingest.add_argument("--candidates", required=True)
    ingest.add_argument("--output", required=True)
    infer = sub.add_parser("infer-descriptions")
    infer.add_argument("--db", required=True)
    infer.add_argument("--output", required=True)
    search = sub.add_parser("search-web")
    search.add_argument("--db", required=True)
    search.add_argument("--kind", choices=("webtoon", "comic"), required=True)
    search.add_argument("--output", required=True)
    search.add_argument("--limit", type=int, default=250)
    search.add_argument("--max-results", type=int, default=5)
    search.add_argument("--sleep", type=float, default=0.15)
    args = parser.parse_args()

    if args.command == "query-plan":
        write_query_plan(Path(args.db), Path(args.output), args.kind)
    elif args.command == "import-candidates":
        import_candidates(Path(args.db), Path(args.candidates), Path(args.output))
    elif args.command == "infer-descriptions":
        infer_descriptions(Path(args.db), Path(args.output))
    elif args.command == "search-web":
        search_web_evidence(Path(args.db), Path(args.output), args.kind, args.limit, args.max_results, args.sleep)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
