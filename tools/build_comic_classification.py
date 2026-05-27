#!/usr/bin/env python3
"""
Build comic-classification.json from source comic genre pages.

The Android app reads only the generated JSON. This script is an offline
maintenance tool so comic genre lookup does not happen on user devices.
"""

from __future__ import annotations

import argparse
import html as html_lib
import json
import math
import re
import subprocess
import sys
import unicodedata
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date
from pathlib import Path
from typing import Counter as CounterType, DefaultDict, Dict, Iterable, List, Optional, Set, Tuple
from urllib.parse import quote, urljoin

from build_webtoon_classification import (
    DEFAULT_SOURCE_ROOT,
    SourceParser,
    SourceTitle,
    http_get,
    merge_tags,
    normalize_source_root,
    resolve_source_root,
)


COMIC_GENRES = [
    "17",
    "드라마",
    "액션",
    "SF",
    "TS",
    "개그",
    "게임",
    "공포",
    "도박",
    "호러",
    "라노벨",
    "러브코미디",
    "로맨스",
    "먹방",
    "미스터리",
    "백합",
    "붕탁",
    "성인",
    "순정",
    "스릴러",
    "스포츠",
    "시대",
    "애니화",
    "판타지",
    "학원",
    "BL",
    "여장",
    "역사",
    "요리",
    "음악",
    "이세계",
    "일상",
    "전생",
    "추리",
]
COMIC_GENRE_SET = set(COMIC_GENRES)

COMIC_DAYS = ["recent", "10", "11", "12", "14", "16", "15", "13", "20"]
COMIC_ALPHABETS = ["ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "a", "0"]


def comic_genre_path(genre: str, order: str) -> str:
    return f"/cm?type1=genre&type2={quote(genre, encoding='euc-kr')}&o={order}"


def comic_day_path(value: str, order: str) -> str:
    return f"/cm?type1=complete&type2={value}&o={order}"


def comic_alphabet_path(value: str, order: str) -> str:
    return f"/cm?type1=alphabet&type2={quote(value, encoding='euc-kr')}&o={order}"


def source_paths() -> List[Tuple[str, str]]:
    paths: List[Tuple[str, str]] = [("/cm?type1=complete&type2=recent&o=f", "")]
    for value in COMIC_DAYS:
        paths.append((comic_day_path(value, "n"), ""))
    for genre in COMIC_GENRES:
        paths.append((comic_genre_path(genre, "n"), normalize_comic_genre(genre)))
    for alphabet in COMIC_ALPHABETS:
        paths.append((comic_alphabet_path(alphabet, "n"), ""))
    return list(dict.fromkeys(paths))


def normalize_comic_genre(genre: str) -> str:
    if genre == "17":
        return "성인"
    if genre == "호러":
        return "공포"
    return genre


COMIC_TAG_SET = {normalize_comic_genre(genre) for genre in COMIC_GENRES}
COMIC_CLASSIFIER_TAGS = [
    tag
    for tag in [
        "성인",
        "BL",
        "SF",
        "TS",
        "액션",
        "개그",
        "게임",
        "공포",
        "도박",
        "라노벨",
        "러브코미디",
        "로맨스",
        "먹방",
        "미스터리",
        "백합",
        "붕탁",
        "순정",
        "스릴러",
        "스포츠",
        "시대",
        "애니화",
        "판타지",
        "학원",
        "여장",
        "역사",
        "요리",
        "음악",
        "이세계",
        "일상",
        "전생",
        "추리",
        "드라마",
    ]
    if tag in COMIC_TAG_SET
]


def normalize_comic_genres(value: str) -> List[str]:
    value = re.sub(r"\s+", " ", value or "").strip()
    if not value:
        return []
    lower = value.lower()
    checks = [
        ("성인", ["17", "19", "19금", "성인", "어른", "고수위"]),
        ("BL", ["bl", "비엘", "보이즈러브"]),
        ("SF", ["sf", "에스에프", "우주", "로봇", "미래", "사이버"]),
        ("TS", ["ts", "성전환", "여체화", "남체화"]),
        ("액션", ["액션", "격투", "배틀", "전투"]),
        ("개그", ["개그", "코믹", "코미디"]),
        ("게임", ["게임"]),
        ("공포", ["공포", "호러", "괴담"]),
        ("도박", ["도박", "카지노", "마작"]),
        ("라노벨", ["라노벨", "라이트노벨"]),
        ("러브코미디", ["러브코미디", "러브 코미디", "럽코"]),
        ("로맨스", ["로맨스", "연애"]),
        ("먹방", ["먹방"]),
        ("미스터리", ["미스터리"]),
        ("백합", ["백합", "gl"]),
        ("붕탁", ["붕탁"]),
        ("순정", ["순정"]),
        ("스릴러", ["스릴러", "범죄"]),
        ("스포츠", ["스포츠"]),
        ("시대", ["시대", "사극"]),
        ("애니화", ["애니화", "애니메이션"]),
        ("판타지", ["판타지", "마법", "마왕", "용사", "던전"]),
        ("학원", ["학원", "학교"]),
        ("여장", ["여장", "남장"]),
        ("역사", ["역사"]),
        ("요리", ["요리"]),
        ("음악", ["음악"]),
        ("이세계", ["이세계"]),
        ("일상", ["일상"]),
        ("전생", ["전생", "환생"]),
        ("추리", ["추리"]),
        ("드라마", ["드라마", "성장"]),
    ]
    result: List[str] = []
    direct = normalize_comic_genre(value)
    if direct in COMIC_GENRE_SET:
        result.append(direct)
    for tag, needles in checks:
        if any(needle.lower() in lower for needle in needles) and tag not in result:
            result.append(tag)
    return result


def meaningful_tags(tags: Iterable[str]) -> List[str]:
    return [tag for tag in merge_tags(tags) if tag and tag != "미분류"]


KNOWN_COMIC_TITLE_TAGS: Dict[str, List[str]] = {
    "사무라이8 하치마루전": ["액션", "SF", "판타지"],
    "아다치와 시마무라": ["백합", "로맨스", "학원", "일상"],
    "하네배드!": ["스포츠", "학원"],
    "열혈강호": ["액션", "시대", "드라마"],
    "스즈미야 하루히의 우울": ["라노벨", "학원", "SF", "일상"],
    "디그레이맨": ["액션", "판타지"],
    "도로헤도로": ["액션", "판타지", "스릴러"],
    "모든 것이 F가 된다": ["미스터리", "추리"],
    "마계왕자": ["판타지"],
    "메르 / 메르헤븐": ["판타지", "액션"],
    "리얼 어카운트": ["게임", "스릴러"],
    "닥터 K 시리즈": ["드라마"],
    "도쿄 레이븐즈 외전 sword of song": ["라노벨", "판타지", "액션"],
    "데이트 어 라이브": ["라노벨", "러브코미디", "SF", "판타지"],
    "데이트 어 파티": ["개그", "러브코미디"],
    "단칸방의 침략자": ["라노벨", "러브코미디", "SF"],
    "The Gate 더 게이트": ["판타지", "액션", "이세계"],
    "드래곤, 집을 사다": ["판타지", "개그"],
    "데빌즈 라인": ["스릴러", "로맨스", "판타지"],
    "디지몬 유니버스 어플리 몬스터즈": ["게임", "SF", "액션"],
    "노예구": ["스릴러", "성인"],
    "늑대폐하의 신부": ["순정", "로맨스", "시대"],
    "나의 신부": ["로맨스", "순정"],
    "느긋캠프": ["일상"],
    "날조트랩": ["백합", "로맨스"],
    "개와 가위는 쓰기 나름": ["라노벨", "개그", "미스터리"],
    "기생수 리버시": ["공포", "스릴러", "SF"],
    "십자가의 6인": ["스릴러", "액션"],
    "부기팝은 웃지 않는다 VS이매지네이터": ["라노벨", "미스터리", "스릴러"],
    "날씨의 아이": ["로맨스", "판타지", "드라마"],
    "프린세스 커넥트! Re:Dive": ["게임", "판타지", "개그"],
    "Unnamed Memory": ["라노벨", "판타지", "로맨스"],
    "쓰르라미 울 적에 업": ["공포", "미스터리", "스릴러"],
    "라디앙": ["판타지", "액션"],
    "매리지 톡신": ["액션", "러브코미디"],
    "이노센트 데빌": ["스릴러", "액션"],
    "HORIZON": ["SF", "액션"],
}


def normalize_known_title(value: str) -> str:
    value = unicodedata.normalize("NFKC", value or "").lower()
    value = re.sub(r"\s+", "", value)
    value = re.sub(r"[\[\]\(\){}<>〈〉《》「」『』:：,，.!?~ㆍ·'\"“”‘’_\-]", "", value)
    return value


KNOWN_COMIC_TITLE_TAGS_NORMALIZED = {
    normalize_known_title(title): tags for title, tags in KNOWN_COMIC_TITLE_TAGS.items()
}


def inference_text(title: SourceTitle) -> str:
    return unicodedata.normalize(
        "NFKC",
        " ".join([title.name or "", title.release or "", title.description or "", " ".join(title.source_tags or [])]),
    ).lower()


def infer_comic_tags(title: SourceTitle, classifier: Optional["ComicTagClassifier"] = None) -> List[str]:
    text = inference_text(title)
    result: List[str] = []

    def add(tag: str, *needles: str) -> None:
        if any(needle.lower() in text for needle in needles) and tag not in result:
            result.append(tag)

    known = KNOWN_COMIC_TITLE_TAGS_NORMALIZED.get(normalize_known_title(title.name))
    if known:
        return known[:]

    add("성인", "17", "19금", "성인", "성노예", "음란", "포르노", "미다라", "팬티", "가슴", "섹스", "섹스트랜스", "밀월", "남친이 있는데", "꼬추")
    add("BL", "bl", "비엘", "보이즈러브")
    add("SF", "sf", "우주", "로봇", "미래", "사이버", "기계소녀", "디멘션", "타임머신", "휴먼 로스트", "human lost", "프로그램", "어플리")
    add("TS", "ts", "성전환", "여체화", "남체화", "여고생이 되었", "체인지", "변하여", "변신")
    add("액션", "fate", "액션", "격투", "전투", "전쟁", "검성", "검사", "검신", "검왕", "킬러", "암살", "사무라이", "시노비", "살육", "블릿", "팽", "퇴치", "특공", "배틀", "히어로", "강호", "바키", "블레이드", "패링", "톡신")
    add("개그", "개그", "코미디", "러브코미디", "럽코", "4컷", "쨩", "바보", "귀엽", "웃어", "코믹")
    add("게임", "게임", "플레이어", "게이머", "mmo", "리얼 어카운트", "칸코레", "프린세스 커넥트", "러브라이브", "어플리 몬스터")
    add("공포", "공포", "호러", "괴담", "귀신", "좀비", "유령", "악령", "저주", "오컬트", "사신", "블러디", "흡혈", "뱀파이어")
    add("도박", "도박", "카지노", "마작", "포커", "장기")
    add("라노벨", "라노벨", "라이트노벨", "스즈미야", "라노베", "단칸방", "현자의 손자")
    add("러브코미디", "러브코미디", "러브 코미디", "럽코")
    add("로맨스", "로맨스", "연애", "첫사랑", "사랑", "고백", "결혼", "혼약", "신부", "여친", "남친", "여자친구", "소꿉친구", "히로인", "데이트", "교제", "허니", "발렌타인")
    add("먹방", "먹방", "요리", "밥", "식당", "셰프", "요리사", "점심식사", "찻집", "커피", "바메이드", "아틀리에")
    add("요리", "먹방", "요리", "밥", "식당", "셰프", "요리사", "점심식사", "찻집", "커피", "바메이드")
    add("미스터리", "미스터리", "추리", "탐정", "사건", "비밀", "수수께끼", "부기팝", "모든 것이 f")
    add("백합", "백합", "gl", "아다치와 시마무라", "날조트랩", "여자동료")
    add("순정", "순정")
    add("스릴러", "스릴러", "범죄", "살인", "납치", "추적", "감금", "데드", "패러사이트", "크라임", "리볼버", "블러디", "십자가", "도망자", "지뢰")
    add("스포츠", "스포츠", "축구", "야구", "농구", "배구", "복싱", "배드민턴", "셔틀콕", "하네배드", "이닝", "풀 사이드", "골프", "테니스")
    add("시대", "시대", "사극", "전국", "에도", "왕국", "제국", "무녀", "노부나", "사무라이", "왕자", "공주", "공작", "귀족", "강호")
    add("학원", "학교", "학원", "학생", "고교", "고등학교", "동아리", "방과후", "방과 후", "보건실", "선생님", "선생", "후배", "풍기", "여고생", "미술부", "미술실", "교실")
    add("여장", "여장", "남장")
    add("음악", "음악", "밴드", "아이돌", "가수", "피아노", "소나타", "rock")
    add("이세계", "이세계", "전생", "환생", "용사", "마왕", "던전", "마법", "소환사", "마계", "마술", "미궁", "추방", "악역영애")
    add("전생", "전생", "환생", "회귀")
    add("판타지", "판타지", "이세계", "전생", "환생", "용사", "마왕", "던전", "마법", "마녀", "야수", "엘프", "유니콘", "드래곤", "용왕", "몬스터", "괴물", "마계", "무녀", "흡혈", "마술", "소환사", "미궁", "마물", "괴수", "요괴", "천사", "악마", "천년영웅")
    add("일상", "일상", "힐링", "가족", "직장", "회사", "집", "셋방살이", "캠프", "아빠", "엄마", "어머니", "아틀리에", "관찰일기")
    add("드라마", "드라마", "휴먼", "성장", "인간실격", "인간", "전장", "장례", "여행")
    if result:
        return result
    if classifier is not None:
        return classifier.predict(title)
    return ["드라마"]


def comic_title_features(title: SourceTitle) -> Set[str]:
    text = inference_text(title)
    text = re.sub(r"[\[\]\(\){}<>〈〉《》「」『』:：,，.!?~ㆍ·'\"“”‘’_\-]", " ", text)
    words = re.findall(r"[a-z0-9]+|[가-힣]+", text)
    features: Set[str] = set()
    for word in words:
        if len(word) < 2 or word in {"단편", "외전", "시리즈", "완전판", "개정판"}:
            continue
        features.add(f"w:{word}")
        for size in (2, 3, 4):
            if len(word) < size:
                continue
            for idx in range(0, len(word) - size + 1):
                features.add(f"g{size}:{word[idx:idx + size]}")
    return features


class ComicTagClassifier:
    def __init__(self, examples: List[Tuple[SourceTitle, List[str]]]) -> None:
        self.doc_count = 0
        self.tag_docs: CounterType[str] = Counter()
        self.feature_docs: CounterType[str] = Counter()
        self.tag_feature_docs: DefaultDict[str, CounterType[str]] = defaultdict(Counter)
        for title, tags in examples:
            clean_tags = [tag for tag in meaningful_tags(tags) if tag in COMIC_TAG_SET]
            if not clean_tags:
                continue
            features = comic_title_features(title)
            if not features:
                continue
            self.doc_count += 1
            self.feature_docs.update(features)
            for tag in clean_tags:
                self.tag_docs[tag] += 1
                self.tag_feature_docs[tag].update(features)

    @classmethod
    def from_data(cls, existing_titles: Dict[str, object], source_titles: Dict[int, SourceTitle]) -> "ComicTagClassifier":
        examples: List[Tuple[SourceTitle, List[str]]] = []
        for toon_id, source in source_titles.items():
            previous = existing_titles.get(str(toon_id), {})
            previous_tags: List[str] = []
            if isinstance(previous, dict):
                for field in ("manualTags", "externalTags", "sourceTags", "tags"):
                    values = previous.get(field)
                    if isinstance(values, list):
                        previous_tags.extend(str(value) for value in values if value)
            tags = merge_tags(meaningful_tags(source.source_tags), meaningful_tags(previous_tags))
            if tags:
                examples.append((source, tags))
        return cls(examples)

    def predict(self, title: SourceTitle) -> List[str]:
        if self.doc_count <= 0:
            return ["드라마"]
        features = comic_title_features(title)
        if not features:
            return ["드라마"]
        scores: List[Tuple[float, str]] = []
        for tag in COMIC_CLASSIFIER_TAGS:
            tag_doc_count = self.tag_docs[tag]
            if tag_doc_count < 5 or tag_doc_count >= self.doc_count:
                continue
            other_doc_count = self.doc_count - tag_doc_count
            score = math.log((tag_doc_count + 1.0) / (other_doc_count + 1.0))
            evidence = 0
            for feature in features:
                feature_doc_count = self.feature_docs[feature]
                tag_feature_doc_count = self.tag_feature_docs[tag][feature]
                other_feature_doc_count = feature_doc_count - tag_feature_doc_count
                if tag_feature_doc_count < 2:
                    continue
                odds = math.log((tag_feature_doc_count + 0.5) / (tag_doc_count + 1.0))
                odds -= math.log((other_feature_doc_count + 0.5) / (other_doc_count + 1.0))
                if odds <= 0.45:
                    continue
                score += min(odds, 3.0)
                evidence += 1
            if evidence:
                scores.append((score, tag))
        scores.sort(reverse=True)
        selected = [tag for score, tag in scores if score >= 2.8][:3]
        if selected:
            return selected
        if scores:
            return [scores[0][1]]
        return ["드라마"]


def extract_inline_comic_tags(item_text: Iterable[str], name: str) -> List[str]:
    tags: List[str] = []
    normalized_name = normalize_known_title(name)
    for value in item_text:
        text = re.sub(r"\s+", " ", value or "").strip()
        if not text or text == "-" or re.fullmatch(r"/[가-힣A-Za-z0-9]{1,6}", text):
            continue
        if re.fullmatch(r"\d+\s*화", text) or re.fullmatch(r"\d+\s*일전", text):
            continue
        normalized_text = normalize_known_title(text)
        if normalized_text and normalized_name and (normalized_text == normalized_name or normalized_text in normalized_name):
            continue
        if "/" not in text and "+" not in text and normalize_comic_genre(text) not in COMIC_TAG_SET:
            continue
        for raw in re.split(r"[/,+·ㆍ|]+", text):
            for tag in normalize_comic_genres(raw):
                if tag not in tags:
                    tags.append(tag)
    return tags


class ComicSourceParser(SourceParser):
    def _finish_item(self) -> None:
        toon_id = self._toon_id
        super()._finish_item()
        title = self.titles.get(toon_id)
        if title is None:
            return
        title.source_tags = merge_tags(extract_inline_comic_tags(self._item_text, title.name), title.source_tags)


def fetch_source_titles(root: str, delay: float, max_paths: int, workers: int) -> Dict[int, SourceTitle]:
    titles: Dict[int, SourceTitle] = {}
    paths = source_paths()
    if max_paths > 0:
        paths = paths[:max_paths]
    with ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = {executor.submit(fetch_source_path_titles, root, path, delay): (path, genre) for path, genre in paths}
        for future in as_completed(futures):
            path, genre = futures[future]
            try:
                parsed = future.result()
            except Exception as exc:
                print(f"warn: source fetch failed: {path}: {exc}", file=sys.stderr)
                continue
            for toon_id, title in parsed.items():
                existing = titles.get(toon_id)
                if existing is None:
                    existing = title
                    titles[toon_id] = existing
                if genre and genre not in existing.source_tags:
                    existing.source_tags.append(genre)
    return titles


def enrich_source_details(root: str, titles: Dict[int, SourceTitle], delay: float, workers: int, cache_path: Optional[Path]) -> None:
    detail_cache = load_detail_cache(cache_path)
    targets: List[SourceTitle] = []
    cache_hits = 0
    for title in titles.values():
        cached = detail_cache.get(str(title.toon_id))
        if apply_cached_detail(title, cached):
            cache_hits += 1
            continue
        targets.append(title)
    print(f"detail cache hits: {cache_hits} fetches: {len(targets)}", file=sys.stderr)
    if not targets:
        return
    completed = 0
    with ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = {executor.submit(fetch_source_detail_tags, root, title.toon_id, delay): title for title in targets}
        for future in as_completed(futures):
            title = futures[future]
            try:
                tags, description = future.result()
            except Exception:
                tags, description = [], ""
            if tags:
                title.source_tags = merge_tags(tags, meaningful_tags(title.source_tags))
            title.description = description
            detail_cache[str(title.toon_id)] = {"name": title.name, "tags": tags, "description": description}
            completed += 1
            if completed % 500 == 0 or completed == len(targets):
                print(f"detail progress: {completed}/{len(targets)}", file=sys.stderr)
                save_detail_cache(cache_path, detail_cache)
    save_detail_cache(cache_path, detail_cache)


def fetch_source_detail_tags(root: str, toon_id: int, delay: float) -> Tuple[List[str], str]:
    html = http_get(urljoin(root.rstrip("/") + "/", f"cl?toon={toon_id}"), delay, timeout=12)
    return parse_source_detail(html)


def parse_source_detail(html: str) -> Tuple[List[str], str]:
    tags: List[str] = []
    match = re.search(r"<strong>\s*장르\s*:\s*</strong>\s*([^<]+)", html, flags=re.IGNORECASE)
    if match:
        for raw in re.split(r"[/,·ㆍ|]+", html_lib.unescape(match.group(1))):
            for tag in normalize_comic_genres(raw):
                if tag not in tags:
                    tags.append(tag)
    desc = ""
    desc_match = re.search(r'<meta\s+name=["\']description["\']\s+content=["\']([^"\']*)', html, flags=re.IGNORECASE)
    if desc_match:
        desc = re.sub(r"\s+", " ", html_lib.unescape(desc_match.group(1))).strip()
    return tags, desc


def load_detail_cache(path: Optional[Path]) -> Dict[str, object]:
    if path is None or not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        entries = data.get("entries", data)
        return entries if isinstance(entries, dict) else {}
    except Exception:
        return {}


def save_detail_cache(path: Optional[Path], cache: Dict[str, object]) -> None:
    if path is None:
        return
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"version": 1, "entries": cache}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except Exception:
        pass


def apply_cached_detail(title: SourceTitle, cached: object) -> bool:
    if not isinstance(cached, dict):
        return False
    tags = cached.get("tags")
    if not isinstance(tags, list):
        return False
    clean_tags = meaningful_tags(str(tag) for tag in tags)
    if clean_tags:
        title.source_tags = merge_tags(clean_tags, meaningful_tags(title.source_tags))
    description = cached.get("description")
    if isinstance(description, str):
        title.description = description
    return bool(clean_tags)


def fetch_source_path_titles(root: str, path: str, delay: float) -> Dict[int, SourceTitle]:
    html = http_get(urljoin(root.rstrip("/") + "/", path.lstrip("/")), delay)
    parser = ComicSourceParser(root)
    parser.feed(html)
    return parser.titles


def merge_existing(path: Path) -> Dict[str, object]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def build_output(existing: Dict[str, object], source_titles: Dict[int, SourceTitle], source_root: str) -> Dict[str, object]:
    existing_titles = existing.get("titles", {}) if isinstance(existing.get("titles"), dict) else {}
    classifier = ComicTagClassifier.from_data(existing_titles, source_titles)
    titles: Dict[str, object] = {}
    for key, item in existing_titles.items():
        if isinstance(item, dict) and item.get("manual", False):
            titles[key] = item
    for toon_id, source in source_titles.items():
        previous = existing_titles.get(str(toon_id), {})
        if not isinstance(previous, dict):
            previous = {}
        if previous.get("manual", False):
            titles[str(toon_id)] = previous
            continue
        manual_tags = previous.get("manualTags") if isinstance(previous.get("manualTags"), list) else []
        source_tags = merge_tags(meaningful_tags(source.source_tags), meaningful_tags(previous.get("sourceTags") if isinstance(previous.get("sourceTags"), list) else []))
        inferred_tags = [] if manual_tags or source_tags else infer_comic_tags(source, classifier)
        next_item = dict(previous)
        next_item.update(
            {
                "name": source.name,
                "thumb": source.thumb or previous.get("thumb", ""),
                "release": source.release or previous.get("release", ""),
                "description": source.description or previous.get("description", ""),
            }
        )
        if manual_tags:
            next_item["manualTags"] = manual_tags
        if source_tags:
            next_item["sourceTags"] = source_tags
        else:
            next_item.pop("sourceTags", None)
        if inferred_tags:
            next_item["inferredTags"] = inferred_tags
        elif "inferredTags" in next_item:
            next_item.pop("inferredTags", None)
        titles[str(toon_id)] = next_item
    return {
        "version": int(existing.get("version", 1)) if existing else 1,
        "updated": date.today().isoformat(),
        "sourceRoot": source_root,
        "sources": ["source-comic-genre"],
        "titles": dict(sorted(titles.items(), key=lambda item: int(item[0]))),
    }


def write_unclassified(path: Path, data: Dict[str, object]) -> None:
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    unclassified = []
    for key, item in titles.items():
        if not isinstance(item, dict):
            continue
        tags = merge_tags(item.get("manualTags") if isinstance(item.get("manualTags"), list) else [], item.get("sourceTags") if isinstance(item.get("sourceTags"), list) else [], item.get("inferredTags") if isinstance(item.get("inferredTags"), list) else [], item.get("tags") if isinstance(item.get("tags"), list) else [])
        if not meaningful_tags(tags):
            unclassified.append({"id": int(key), "name": item.get("name", ""), "thumb": item.get("thumb", "")})
    path.write_text(json.dumps(unclassified, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def copy_outputs(output: Path, asset_output: Path) -> None:
    asset_output.parent.mkdir(parents=True, exist_ok=True)
    asset_output.write_text(output.read_text(encoding="utf-8"), encoding="utf-8")


def resolve_output(output: Path, asset_output: Path, external_evidence: str = "") -> None:
    resolver = Path(__file__).resolve().parent / "classification" / "resolve_classification.py"
    command = [
            sys.executable,
            str(resolver),
            "--kind",
            "comic",
            "--input",
            str(output),
            "--output",
            str(output),
            "--asset-output",
            str(asset_output),
    ]
    if external_evidence:
        command.extend(["--external-evidence", external_evidence])
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build comic-classification.json from source comic genres.")
    parser.add_argument("--output", default="comic-classification.json")
    parser.add_argument("--asset-output", default="app/src/main/assets/comic-classification.json")
    parser.add_argument("--no-resolve", action="store_true")
    parser.add_argument("--external-evidence", default="tools/classification/comic-external-evidence.jsonl")
    parser.add_argument("--source-root", default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--no-source-root-resolve", action="store_true")
    parser.add_argument("--max-source-paths", type=int, default=0, help="0 means all configured source paths")
    parser.add_argument("--delay", type=float, default=0.35)
    parser.add_argument("--source-workers", type=int, default=16)
    parser.add_argument("--no-source-detail-fetch", action="store_true")
    parser.add_argument("--source-detail-workers", type=int, default=16)
    parser.add_argument("--source-detail-cache", default=".comic-source-detail-cache.json")
    parser.add_argument("--no-source-detail-cache", action="store_true")
    parser.add_argument("--unclassified-output", default="")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--allow-empty-source", action="store_true")
    args = parser.parse_args()

    output = Path(args.output)
    existing = merge_existing(output)
    source_root = resolve_source_root(args.source_root, auto_resolve=not args.no_source_root_resolve)
    if source_root != normalize_source_root(args.source_root):
        print(f"resolved source root: {args.source_root} -> {source_root}", file=sys.stderr)

    print("fetching comic source titles...", file=sys.stderr)
    source_titles = fetch_source_titles(source_root, args.delay, args.max_source_paths, args.source_workers)
    print(f"source titles: {len(source_titles)}", file=sys.stderr)
    if not source_titles and not args.allow_empty_source:
        raise RuntimeError("source title fetch returned 0 titles; refusing to overwrite classification DB")
    if not args.no_source_detail_fetch:
        print(f"fetching comic detail genres: {len(source_titles)}", file=sys.stderr)
        enrich_source_details(
            source_root,
            source_titles,
            args.delay,
            args.source_detail_workers,
            None if args.no_source_detail_cache else Path(args.source_detail_cache),
        )

    data = build_output(existing, source_titles, source_root)
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    if args.dry_run:
        print(text)
    else:
        output.write_text(text, encoding="utf-8")
        if args.asset_output and not args.no_resolve:
            resolve_output(output, Path(args.asset_output), args.external_evidence if Path(args.external_evidence).exists() else "")
        elif args.asset_output:
            copy_outputs(output, Path(args.asset_output))
        if args.unclassified_output:
            write_unclassified(Path(args.unclassified_output), data)
        print(f"wrote {output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
