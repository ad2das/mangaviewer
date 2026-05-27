#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any, Iterable


LEGACY_FIELDS = ("manualTags", "externalTags", "sourceTags", "inferredTags", "tags")
EXTERNAL_EVIDENCE_FIELD = "externalEvidenceTags"
RELIABLE_EXTERNAL_FAMILIES = {"official_platform", "external_metadata"}
RISKY_INFERRED_TAG_SUPPORT = {
    "\uc2a4\ub9b4\ub7ec": (
        "\uc2a4\ub9b4\ub7ec", "\ubc94\uc8c4", "\uc0b4\uc778", "\uc0b4\ud574", "\ub0a9\uce58", "\uac10\uae08", "\ubcf5\uc218", "\uc218\uc0ac", "\ud615\uc0ac",
        "\uc758\ubb38\uc758 \uc8fd\uc74c", "\uc2dc\uccb4", "\uc0dd\uc874\uac8c\uc784", "\ud0c8\ucd9c", "\uc0ac\uc774\ucf54", "\uc0b4\uc778\ub9c8",
        "\ud611\ubc15", "\uc704\ud611", "\uc870\uc9c1", "\ub9c8\ud53c\uc544", "\ud3ed\ub825", "\uc794\ud639", "\uc2e4\uc885",
    ),
    "\ubbf8\uc2a4\ud130\ub9ac": (
        "\ubbf8\uc2a4\ud130\ub9ac", "\ucd94\ub9ac", "\uc758\ubb38", "\uc218\uc218\uaed8\ub07c", "\ubbf8\uc2a4\ud14c\ub9ac", "\uc815\uccb4", "\uc9c4\uc2e4",
        "\ub2e8\uc11c", "\ubc94\uc778", "\uc218\uc0ac", "\uc2e4\uc885", "\uc54c \uc218 \uc5c6\ub294",
    ),
    "\uacf5\ud3ec": (
        "\uacf5\ud3ec", "\ud638\ub7ec", "\uadc0\uc2e0", "\uc720\ub839", "\uc545\ub839", "\uad34\ub2f4", "\uc624\uceec\ud2b8", "\uc800\uc8fc",
        "\uc18c\ub984", "\ud749\uac00", "\uc880\ube44", "\uc545\ub9c8", "\uad34\ubb3c", "\uc12c\ub729",
    ),
    "\ud310\ud0c0\uc9c0": (
        "\ud310\ud0c0\uc9c0", "\ub9c8\ubc95", "\uc774\uc138\uacc4", "\uc804\uc0dd", "\ud68c\uadc0", "\ub9c8\uc655", "\uc545\ub9c8", "\ucc9c\uc0ac",
        "\uc5d8\ud504", "\ub4dc\ub798\uace4", "\ub9c8\ub140", "\ub9c8\ubc95\uc0ac", "\ub2a5\ub825\uc790", "\ucd08\ub2a5\ub825", "\uc800\uc2b9",
        "\uc694\uad34", "\uc815\ub839", "\ub9c8\uc871", "\ub358\uc804",
    ),
    "\uc561\uc158": (
        "\uc561\uc158", "\uc804\ud22c", "\uc2f8\uc6c0", "\uaca9\ud22c", "\uc804\uc7c1", "\ubb34\ub9bc", "\ubb34\ud611", "\uac80", "\ud5cc\ud130",
        "\ucd5c\uac15", "\ud0ac\ub7ec", "\uc554\uc0b4", "\ub300\uacb0", "\ubc30\ud2c0", "\uaca9\uc804", "\ubb34\uc30d", "\ud65c\uadf9", "\uc0ac\ud22c",
    ),
}
UNCLASSIFIED = "미분류"

SOURCE_WEIGHTS = {
    "manualTags": 1.0,
    "externalTags": 0.88,
    "sourceTags": 0.58,
    "tags": 0.50,
    "inferredTags": 0.25,
    EXTERNAL_EVIDENCE_FIELD: 0.75,
}

SOURCE_FAMILIES = {
    "manualTags": "override",
    "externalTags": "official_or_metadata",
    "sourceTags": "wfwf",
    "tags": "legacy",
    "inferredTags": "heuristic_ai",
    EXTERNAL_EVIDENCE_FIELD: "external_evidence",
}

FIELD_RELIABILITY = {
    "manualTags": 1.0,
    "externalTags": 0.95,
    "sourceTags": 0.85,
    "tags": 0.65,
    "inferredTags": 0.55,
    EXTERNAL_EVIDENCE_FIELD: 0.80,
}

SPECIFICITY = {
    "BL": 1.15,
    "백합": 1.15,
    "무협": 1.10,
    "이세계": 1.10,
    "게임": 1.08,
    "요리": 1.08,
    "먹방": 1.08,
    "도박": 1.08,
    "음악": 1.08,
    "스포츠": 1.05,
    "드라마": 0.85,
    "스토리": 0.80,
    "일상": 0.90,
}

TITLE_KEYWORD_PRIORS = [
    (("회귀", "환생", "빙의", "악녀", "공작", "황녀", "마왕", "용사", "마법", "공녀", "후궁"), ["판타지", "로맨스"]),
    (("던전", "헌터", "각성", "레벨", "랭커", "탑", "SSS", "게임", "퀘스트"), ["액션", "판타지"]),
    (("무림", "검신", "천마", "소림", "강호", "무협", "검객", "검왕"), ["무협", "액션"]),
    (("학교", "학원", "선배", "동아리", "반장", "교실", "학생"), ["학원", "일상"]),
    (("살인", "범인", "탐정", "저주", "실종", "복수", "범죄"), ["스릴러", "미스터리"]),
    (("귀신", "괴담", "악몽", "공포", "좀비", "유령"), ["공포", "스릴러"]),
    (("요리", "셰프", "식당", "밥", "맛", "먹방"), ["요리", "일상"]),
    (("야구", "축구", "농구", "복싱", "격투", "골프", "테니스"), ["스포츠"]),
    (("사랑", "연애", "신부", "남친", "여친", "결혼", "키스"), ["로맨스"]),
    (("개그", "코미디", "웃", "바보", "병맛"), ["개그"]),
    (("BL", "비엘", "오메가버스"), ["BL"]),
    (("백합", "GL"), ["백합"]),
]

STRICT_DESCRIPTION_PRIORS = [
    (("회귀", "환생", "빙의", "악녀", "공작", "황녀", "마왕", "용사", "마법", "공녀", "후궁", "악마", "천사"), ["판타지"]),
    (("던전", "헌터", "각성", "레벨", "랭커", "SSS", "퀘스트"), ["액션", "판타지"]),
    (("전투", "격투", "싸움", "전쟁", "생존을 건 사투", "액션", "파이터", "스트리트파이터", "대결전", "조직", "행동대장", "사투", "반격"), ["액션"]),
    (("무림", "검신", "천마", "소림", "강호", "무협", "검객", "검왕", "고수"), ["무협", "액션"]),
    (("학교", "학원", "동아리", "교실", "고등학교", "대학교", "고교", "고교생활", "동급생", "같은반", "같은 반"), ["학원"]),
    (("살인", "범인", "탐정", "저주", "실종", "복수", "범죄", "형사", "사건"), ["스릴러", "미스터리"]),
    (("귀신", "괴담", "악몽", "공포", "좀비", "유령", "악령", "오싹", "처녀귀신", "퇴마", "쫓아내"), ["공포", "스릴러"]),
    (("요리", "셰프", "식당", "먹방", "맛집", "주방"), ["요리"]),
    (("야구", "축구", "농구", "복싱", "골프", "테니스", "스포츠", "라이딩", "자전거", "군대", "군인"), ["스포츠"]),
    (("연애", "신부", "남친", "여친", "여자친구", "남자친구", "결혼", "키스", "첫사랑", "로맨스", "짝사랑", "고백", "연인", "사랑이야기", "러브스토리"), ["로맨스"]),
    (("개그", "코미디", "웃음", "병맛", "엉뚱", "황당", "좌충우돌"), ["개그"]),
    (("BL", "비엘", "오메가버스", "보이즈러브", "남자를 좋아", "게이", "남자와", "남자에게"), ["BL"]),
    (("백합", "GL", "그녀들"), ["백합"]),
    (("일상물", "소소한 일상", "일상", "회사", "직장", "백수", "하숙집", "관리사무소"), ["일상"]),
    (("조선", "왕조", "황제", "역사", "사극"), ["시대"]),
    (("히어로", "괴인", "악당", "빌런", "초능력", "능력자", "구해야", "구한다"), ["액션", "판타지"]),
    (("드래곤", "용족", "마족", "마녀", "요괴", "괴물", "신비", "이능", "마계", "천계"), ["판타지"]),
    (("조직", "건달", "야쿠자", "마피아", "갱", "폭력", "대결", "복수극", "추격", "암살"), ["액션", "스릴러"]),
    (("저승사자", "수호령", "영혼", "빙의", "환생", "전생"), ["판타지"]),
    (("RPG", "게임", "플레이어", "스테이지", "랭킹", "랭크", "스킬", "아이템"), ["게임", "판타지"]),
    (("아이돌", "배우", "연예계", "오케스트라", "밴드", "가수", "음악", "연주"), ["음악", "드라마"]),
    (("자전거", "라이딩", "군대", "군인", "농구부", "축구부", "야구부", "시합", "대회"), ["스포츠"]),
    (("동거", "짝사랑", "사랑", "고백", "연인", "남편", "아내", "신혼", "스캔들"), ["로맨스"]),
    (("펜션", "감금", "납치", "살해", "죽음", "피", "잔혹", "인터넷 방송", "스토킹"), ["스릴러"]),
    (("성생활", "섹스", "성욕", "밤일", "스와핑", "하룻밤", "관계", "29금", "야릇", "에로", "음란", "욕망", "성인", "성 중독", "포르노", "야동", "쾌락"), ["성인"]),
    (("병맛", "엉뚱", "황당", "개그", "코미디", "웃음", "바보", "웃긴", "좌충우돌"), ["개그"]),
    (("유부녀", "속옷", "은밀", "아찔", "발칙", "야근", "노예", "첩으로", "여자탐방", "스폰", "페티시", "벗게", "탐욕", "성적 충동", "최음", "AV"), ["성인"]),
    (("딱풀녀", "천 원짜리", "H를", "불끈", "몸은", "원나잇", "호스트바", "교미", "거유", "야한 말", "섹시", "팜므파탈", "중독되어", "첫경험", "몸을 팔", "하룻밤", "원초적인 즐거움", "썰만화"), ["성인"]),
    (("싸웠노라", "이겼노라", "최강", "맞장", "전사들", "사냥꾼", "살아남", "분쟁", "음모", "모험", "선택받은 자", "반란", "전국구", "행동대장", "핏줄", "건드리지마라", "까불지도", "싸워라", "영웅담", "활약"), ["액션"]),
    (("세상 아닌 세상", "다른 세상", "이세계", "악마", "도깨비", "수인", "신이 인간에게 준 힘", "카발라", "투명 인간", "사람이 되어", "안드로이드", "메르헨 판타지", "후천적 금수저", "구미호", "괴력", "몬스터", "새로운 세상", "저승", "신내림", "무당", "주신", "변신", "가면으로"), ["판타지"]),
    (("충격적인 비밀", "쫓는 시선", "비밀을 밝혀", "도망칠 곳", "죽이려는", "쫓고 쫓기는", "기억상실", "범죄", "브로커", "스파이", "경매시장", "감옥", "법이 적용되지", "무인도", "황폐한 세상"), ["스릴러", "미스터리"]),
    (("타인의 기억", "특별한 능력", "감정을 알 수", "미래", "저승", "악령", "강령", "심판하기 위해", "부모님을 선택", "기생충", "의식을 지배"), ["판타지", "미스터리"]),
    (("흉부외과", "의사", "인턴", "병원", "간병", "수의사", "기생충 섬멸"), ["드라마"]),
    (("오디션", "싱어송라이터", "스타", "배우", "공연자", "노래", "밴드", "음악", "오케스트라", "프로듀서"), ["음악", "드라마"]),
    (("PC방", "RPG", "플레이어", "던전", "퀘스트", "게임", "레벨", "LV", "스테이터스"), ["게임", "판타지"]),
    (("좋아하는 그 사람", "첫사랑", "고백", "연애", "계약연애", "여자친구", "남자친구", "사랑", "입맞춤", "키스", "심장이", "반했습니다", "가짜 커플", "짝사랑", "밀당", "스캔들", "그녀와의 만남", "좋아도 너무 좋은"), ["로맨스"]),
    (("남자가 다가오", "남자에게", "소년을 첩", "이사에게 입맞춤", "보이즈 러브", "남첩", "주인님", "남자와", "남자 둘", "그 남자", "명란젓 같은 입술", "커밍아웃", "집주인의 부탁", "남자가 등장", "형에게", "동네 형", "그와", "그를 본 순간"), ["BL"]),
    (("고등학교", "같은반", "교실", "여고생", "남고생", "동아리", "학교", "선배", "후배", "제자", "공고", "전학", "과외", "합숙", "반 친구", "3반", "수꿉친구"), ["학원"]),
    (("사막", "해적", "어드벤쳐", "여정", "표류", "대해", "배에", "섬", "모험가", "잃어버린 과거", "운명", "시험이 시작"), ["판타지", "액션"]),
    (("고시원", "취준생", "취업", "회사", "출판사", "직장", "대기업 직원", "공무원", "창업", "정리해고", "신문사", "슈퍼", "일상", "하숙", "한 집에서 살"), ["일상", "드라마"]),
    (("벼랑 끝", "아귀떼", "미스테리어스", "후원자", "재앙", "조사를 시작", "수상함", "금지 구역", "위험한", "심리", "트라우마", "위태롭던", "사람배달", "배달알바", "범죄"), ["스릴러", "미스터리"]),
    (("옴니버스", "스토리가 펼쳐", "이야기가 펼쳐", "인생 대역전극", "본격 창업", "기업극화", "웹툰", "스토리"), ["드라마"]),
    (("배구", "야구", "축구", "농구", "복싱", "시합", "합숙", "대표결정전", "세터", "블로킹", "리시브"), ["스포츠"]),
    (("유성이 떨어졌다", "날개 달린", "영웅들", "왕이 될", "원시시대", "부족 생활", "왕국", "황실", "보물", "약혼자", "동양환타지", "요물", "둔갑", "신의 능력", "결계", "불사의 몸", "불사", "돌연변이", "기묘한 능력"), ["판타지"]),
    (("우주력", "성간이동", "별과 별", "괴수와 싸우", "판타지 SF", "대기상태", "지하도시", "바이러스", "초대형", "인간들의 세계", "다른 종족"), ["SF", "액션"]),
    (("연산군", "왕에게 상납", "내시", "고려", "무신정권", "노비", "양반가", "황제", "궁녀", "중전"), ["시대", "역사"]),
    (("사채", "조폭", "괴롭힘", "호빠", "공사", "재벌딸", "조건만남", "뒷세계", "총알받이", "지옥", "10명 안에", "파헤친다", "비밀을 파헤친다", "감금"), ["스릴러", "액션"]),
    (("청춘", "여행", "로드무비", "혼자 외롭게", "가정이 있고", "과거가 공개", "세 남자", "내면", "어긋난", "가족", "부회장", "비서", "비서직", "선언"), ["드라마"]),
    (("봄처럼", "설레는", "애틋한", "간질간질", "달달", "소개팅", "강하게 이끌린", "여행을 함께", "서로에게", "둘만의 시간", "감정이 싹트", "그대"), ["로맨스"]),
    (("남자들 사이", "남자가 나타", "남자들에게", "남자...?", "그를 불러낸", "태이", "요한", "세민", "민재", "태화", "료가", "토오루", "남자… 그리고", "두 사람의 사이는"), ["BL"]),
    (("여군", "부대", "특수부대", "여자들만의 국가", "남자들의 탄압", "국가", "검은늑대"), ["액션", "성인"]),
    (("먹지", "음식", "맛보고 싶다", "케이크", "푸드", "레시피", "식성", "먹을수"), ["요리", "일상"]),
    (("밝히는", "렌탈 아저씨", "야한", "러브호텔", "섹시한", "몸에 좋은", "자야만", "치료제는", "금단의 기술", "처녀귀신", "덮치려고", "가슴도 크다", "음란 계획", "변태클럽"), ["성인"]),
    (("3D 업조", "전성시대", "노동", "대학 생활", "복학", "마을", "평범한", "특이한 사람", "사람들의 이야기", "성장기", "찌질이", "미모", "우리들의 이야기", "대학 일기", "찌질한 이야기"), ["일상", "드라마"]),
    (("모쏠", "꿈의 이상형", "첫경험", "첫사랑", "좋아했다", "그녀가 다시", "저마다의 봄", "좋아하는 사람이", "썸", "유혹", "도화살", "소꿉친구", "남과 여", "설레", "러브 러브"), ["로맨스"]),
    (("봉변", "쓰레기같은", "감당할 수 없는 빚", "빚", "되돌아왔다", "목을 조르는", "치명적인 약점", "위험수위", "돌이킬 수 없는 하루", "망치러", "비밀이 숨겨", "수상하다", "파헤쳐본다", "갖히게", "교도소"), ["스릴러"]),
    (("프로젝트", "게임", "플레이어", "서바이벌", "스테이지", "재건설", "타락게임", "버튼", "10억", "인생 역전"), ["게임", "스릴러"]),
    (("주먹", "킬러", "총성", "부산 주먹", "전설", "서울 진출기", "어둠의 세계", "대행자", "파이널", "통이라", "쌉니다 천리마마트", "유배지"), ["액션", "드라마"]),
    (("달빛조각사", "용이 산다", "용이", "이무기", "신선", "왕세자", "부활", "신의 손", "링만 끼우면", "능력을 지니고", "특수한 능력", "다른 존재들", "기회. 잠들어 있던 능력"), ["판타지"]),
    (("지구가 멸망", "혼자 달", "폐허가 된 서울", "문명의 이기", "공격 받고", "멸망", "세상이 무너지고", "절망의 세상", "고립시킨"), ["SF", "스릴러"]),
    (("먹느냐 먹히느냐", "맛의 비법", "저녁식사", "공복", "목욕관리사", "때밀이", "목욕"), ["요리", "일상"]),
    (("군", "국군", "탈영병", "D.P", "잡으러", "수영부", "낚시", "베이스 투 베이스"), ["스포츠", "드라마"]),
    (("청나라", "환관", "궁궐", "19세기", "5,18", "공모전 대상작", "민감한 소재"), ["역사", "드라마"]),
]

GLOBAL_PRIORS = {
    "webtoon": ["드라마"],
    "comic": ["드라마"],
}


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def load_evidence_jsonl(path: Path | list[Path]) -> dict[str, list[dict[str, Any]]]:
    paths = path if isinstance(path, list) else [path]
    result: dict[str, list[dict[str, Any]]] = {}
    for evidence_path in paths:
        if not evidence_path.exists():
            continue
        merge_evidence_jsonl(evidence_path, result)
    return result


def merge_evidence_jsonl(path: Path, result: dict[str, list[dict[str, Any]]]) -> None:
    if not path.exists():
        return {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(item, dict):
            continue
        key = str(item.get("id") or item.get("itemId") or item.get("titleId") or "")
        if not key:
            continue
        result.setdefault(key, []).append(item)


def unique(values: Iterable[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        tag = str(value or "").strip()
        if not tag or tag == UNCLASSIFIED:
            continue
        key = tag.casefold()
        if key in seen:
            continue
        seen.add(key)
        result.append(tag)
    return result


def taxonomy_sets(taxonomy: dict[str, Any]) -> tuple[dict[str, str], dict[str, str]]:
    aliases = {str(k): str(v) for k, v in taxonomy.get("aliases", {}).items()}
    facets: dict[str, str] = {}
    canonical: dict[str, str] = {}
    for facet in ("genres", "relationship", "rating", "theme", "format"):
        for tag in taxonomy.get(facet, []):
            canonical[str(tag).casefold()] = str(tag)
            facets[str(tag)] = facet
    for alias, target in aliases.items():
        canonical[alias.casefold()] = target
    return canonical, facets


def normalize_tag(tag: str, canonical: dict[str, str]) -> str:
    raw = str(tag or "").strip()
    if not raw:
        return ""
    return canonical.get(raw.casefold(), raw)


def item_sources(item: dict[str, Any], canonical: dict[str, str], include_source_tags: bool = False) -> dict[str, list[str]]:
    sources: dict[str, list[str]] = {}
    for field in LEGACY_FIELDS:
        if not include_source_tags and field in {"sourceTags", "tags"}:
            continue
        values = item.get(field)
        if isinstance(values, list):
            sources[field] = unique(normalize_tag(value, canonical) for value in values)
    return sources


def text_inference_tags(item: dict[str, Any], canonical: dict[str, str]) -> list[str]:
    text = str(item.get("description", ""))
    lower = text.casefold()
    tags: list[str] = []
    for needles, mapped_tags in STRICT_DESCRIPTION_PRIORS:
        if any(needle.casefold() in lower for needle in needles):
            tags.extend(mapped_tags)
    return unique(normalize_tag(tag, canonical) for tag in tags)


def add_external_evidence_sources(
    sources: dict[str, list[str]],
    item: dict[str, Any],
    external_evidence: list[dict[str, Any]],
    canonical: dict[str, str],
) -> None:
    tags: list[str] = []
    weak_tags: list[str] = []
    for entry in external_evidence:
        if str(entry.get("field", "")) == "ai.title":
            continue
        values = entry.get("normalizedTags") or entry.get("mappedTags") or entry.get("tags")
        if isinstance(values, list):
            normalized = [normalize_tag(tag, canonical) for tag in values]
            if str(entry.get("sourceFamily", "")) in RELIABLE_EXTERNAL_FAMILIES:
                tags.extend(normalized)
            else:
                weak_tags.extend(normalized)
    if tags:
        sources[EXTERNAL_EVIDENCE_FIELD] = unique([*sources.get(EXTERNAL_EVIDENCE_FIELD, []), *tags])
    if weak_tags:
        sources["inferredTags"] = unique([*sources.get("inferredTags", []), *weak_tags])
    inferred = text_inference_tags(item, canonical)
    if inferred:
        sources["inferredTags"] = unique([*sources.get("inferredTags", []), *inferred])


def external_identity_score(item: dict[str, Any], external_source_conflict: bool) -> float:
    raw_score = item.get("matchScore", 0.0)
    try:
        match_score = float(raw_score)
    except (TypeError, ValueError):
        match_score = 0.0
    if external_source_conflict:
        return min(match_score, 0.40)
    if item.get("naverTitleId") or item.get("naverName"):
        return min(match_score or 0.70, 0.70)
    return 0.0


def item_evidence(
    item: dict[str, Any],
    sources: dict[str, list[str]],
    external_source_conflict: bool,
    external_evidence: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    names = {
        "manualTags": "override-tags",
        "externalTags": "external-title-match",
        "sourceTags": "wfwf-source",
        "tags": "legacy-merged",
        "inferredTags": "heuristic-inference",
        EXTERNAL_EVIDENCE_FIELD: "external-evidence-jsonl",
    }
    for field, tags in sources.items():
        if not tags:
            continue
        identity_score = 1.0
        accepted = True
        reason = ""
        if field == "externalTags":
            identity_score = external_identity_score(item, external_source_conflict)
            accepted = identity_score >= 0.60 and not external_source_conflict
            if external_source_conflict:
                reason = "external tags ignored because they do not overlap WFWF source tags; probable same-title collision"
            elif identity_score <= 0.70:
                reason = "title-only external match capped; usable only as weak evidence"
        elif field == EXTERNAL_EVIDENCE_FIELD:
            identity_score = max(
                [
                    float(entry.get("identityScore", 0.0))
                    for entry in external_evidence
                    if isinstance(entry.get("identityScore", 0.0), (int, float))
                ]
                or [0.75]
            )
            identity_score = min(identity_score, 0.95)
            accepted = identity_score >= 0.60
            if not accepted:
                reason = "external evidence identityScore below acceptance threshold"
        score = round(SOURCE_WEIGHTS[field] * identity_score * FIELD_RELIABILITY[field], 4)
        evidence.append(
            {
                "source": names[field],
                "sourceFamily": SOURCE_FAMILIES[field],
                "field": field,
                "mappedTags": tags,
                "confidence": SOURCE_WEIGHTS[field],
                "sourceReliability": SOURCE_WEIGHTS[field],
                "fieldReliability": FIELD_RELIABILITY[field],
                "identityScore": round(identity_score, 4),
                "score": score,
                "accepted": accepted,
                **({"reason": reason} if reason else {}),
            }
        )
    for entry in external_evidence:
        if not isinstance(entry, dict):
            continue
        values = entry.get("normalizedTags") or entry.get("mappedTags") or entry.get("tags")
        if not isinstance(values, list):
            continue
        identity_score = float(entry.get("identityScore", 0.75)) if isinstance(entry.get("identityScore", 0.75), (int, float)) else 0.75
        source_reliability = float(entry.get("sourceReliability", 0.75)) if isinstance(entry.get("sourceReliability", 0.75), (int, float)) else 0.75
        field_reliability = float(entry.get("fieldReliability", 0.80)) if isinstance(entry.get("fieldReliability", 0.80), (int, float)) else 0.80
        mapped = unique(str(tag) for tag in values)
        evidence.append(
            {
                "source": str(entry.get("source", "external-evidence")),
                "sourceFamily": str(entry.get("sourceFamily", "external_evidence")),
                "field": str(entry.get("field", "external.evidence")),
                "mappedTags": mapped,
                "confidence": source_reliability,
                "sourceReliability": source_reliability,
                "fieldReliability": field_reliability,
                "identityScore": round(identity_score, 4),
                "score": round(source_reliability * field_reliability * identity_score, 4),
                "accepted": identity_score >= 0.60,
                "url": entry.get("url", ""),
            }
        )
    return evidence


def external_evidence_confidence(external_evidence: list[dict[str, Any]], default: float = 0.66) -> float:
    best = default
    for entry in external_evidence:
        if not isinstance(entry, dict):
            continue
        identity = entry.get("identityScore", 0.0)
        source_reliability = entry.get("sourceReliability", 0.0)
        field_reliability = entry.get("fieldReliability", 0.0)
        if not isinstance(identity, (int, float)) or not isinstance(source_reliability, (int, float)) or not isinstance(field_reliability, (int, float)):
            continue
        score = float(identity) * max(float(source_reliability), float(field_reliability))
        if str(entry.get("field", "")).endswith(".genre"):
            score += 0.04
        best = max(best, min(score, 0.86))
    return best


def tag_specificity(tag: str) -> float:
    return SPECIFICITY.get(tag, 1.0)


def score_tags(evidence: list[dict[str, Any]], accepted: list[str], rejected: list[str]) -> dict[str, dict[str, Any]]:
    by_tag_family: dict[str, dict[str, float]] = {}
    tag_sources: dict[str, list[str]] = {}
    tag_evidence: dict[str, list[str]] = {}
    for idx, entry in enumerate(evidence):
        if not entry.get("accepted", True):
            continue
        family = str(entry.get("sourceFamily", "unknown"))
        base_score = float(entry.get("score", 0.0))
        for tag in entry.get("mappedTags", []):
            score = base_score * tag_specificity(str(tag))
            by_tag_family.setdefault(str(tag), {})
            by_tag_family[str(tag)][family] = max(by_tag_family[str(tag)].get(family, 0.0), score)
            tag_sources.setdefault(str(tag), []).append(str(entry.get("field", "")))
            tag_evidence.setdefault(str(tag), []).append(f"ev{idx}")

    result: dict[str, dict[str, Any]] = {}
    for tag, family_scores in by_tag_family.items():
        total = sum(family_scores.values())
        if len(family_scores) >= 2:
            total *= 1.15
        if len(family_scores) >= 3:
            total *= 1.10
        result[tag] = {
            "score": round(total, 4),
            "sources": unique(tag_sources.get(tag, [])),
            "sourceFamilies": sorted(family_scores.keys()),
            "evidenceRefs": unique(tag_evidence.get(tag, [])),
        }

    for tag in accepted:
        result[tag] = {"score": 1.0, "sources": ["override"], "sourceFamilies": ["override"], "evidenceRefs": []}
    for tag in rejected:
        result.setdefault(tag, {"score": 0.0, "sources": [], "sourceFamilies": [], "evidenceRefs": []})["status"] = "rejected"
    return result


def rank_candidate_tags(candidate_tags: list[str], tag_scores: dict[str, dict[str, Any]]) -> list[str]:
    candidates = unique(candidate_tags)
    return sorted(candidates, key=lambda tag: (-float(tag_scores.get(tag, {}).get("score", 0.0)), candidates.index(tag)))


def has_inferred_risky_tag_support(item: dict[str, Any], tag: str) -> bool:
    needles = RISKY_INFERRED_TAG_SUPPORT.get(tag)
    if not needles:
        return True
    text = str(item.get("description", "")).casefold()
    return any(needle.casefold() in text for needle in needles)


def filter_unsupported_inferred_risky_tags(item: dict[str, Any], tags: list[str], canonical: dict[str, str]) -> list[str]:
    filtered = [tag for tag in tags if has_inferred_risky_tag_support(item, tag)]
    if filtered:
        return filtered
    fallback = normalize_tag("\ub4dc\ub77c\ub9c8", canonical)
    return [fallback] if fallback else tags


def fallback_tags(item: dict[str, Any], kind: str, canonical: dict[str, str]) -> tuple[list[str], str, float, dict[str, Any]]:
    resolved = unique(normalize_tag(tag, canonical) for tag in GLOBAL_PRIORS.get(kind, ["드라마"]))
    return resolved, "forced_placeholder_non_empty", 0.05, {
        "source": "forced-placeholder",
        "field": "kind",
        "mappedTags": resolved,
        "confidence": 0.05,
        "forced": True,
        "reason": "No reliable evidence found; non-empty DB invariant only.",
    }


def confidence_label(confidence: float) -> str:
    if confidence >= 0.88:
        return "very_high"
    if confidence >= 0.74:
        return "high"
    if confidence >= 0.58:
        return "medium"
    if confidence >= 0.35:
        return "low"
    if confidence > 0:
        return "very_low"
    return "none"


def has_rich_source_tags(tags: list[str], sensitive: set[str]) -> bool:
    if len(tags) >= 3:
        return True
    non_sensitive = [tag for tag in tags if tag not in sensitive]
    specific = {
        "판타지",
        "액션",
        "SF",
        "공포",
        "스릴러",
        "미스터리",
        "추리",
        "로맨스",
        "러브코미디",
        "순정",
        "스포츠",
        "무협",
        "시대",
        "역사",
        "요리",
        "먹방",
        "음악",
        "게임",
        "학원",
        "이세계",
        "전생",
        "일상",
        "도박",
        "라노벨",
        "애니화",
    }
    return len(non_sensitive) >= 2 and any(tag in specific for tag in non_sensitive)


def has_explicit_source_tags(tags: list[str]) -> bool:
    return bool(tags) and any(tag != UNCLASSIFIED for tag in tags)


def apply_override(
    title_id: str,
    item: dict[str, Any],
    overrides: dict[str, Any],
    canonical: dict[str, str],
) -> tuple[list[str], list[str], dict[str, Any] | None]:
    override = overrides.get(title_id)
    if not isinstance(override, dict):
        return [], [], None
    accepted = unique(normalize_tag(tag, canonical) for tag in override.get("acceptedTags", []))
    rejected = unique(normalize_tag(tag, canonical) for tag in override.get("rejectedTags", []))
    return accepted, rejected, override


def resolve_item(
    title_id: str,
    item: dict[str, Any],
    kind: str,
    taxonomy: dict[str, Any],
    overrides: dict[str, Any],
    external_evidence: list[dict[str, Any]],
    include_source_tags: bool = False,
    strip_source_tags: bool = False,
) -> dict[str, Any]:
    canonical, facets_by_tag = taxonomy_sets(taxonomy)
    sources = item_sources(item, canonical, include_source_tags=include_source_tags)
    add_external_evidence_sources(sources, item, external_evidence, canonical)
    accepted, rejected, override = apply_override(title_id, item, overrides, canonical)
    rejected_keys = {tag.casefold() for tag in rejected}

    source_order = ("manualTags", EXTERNAL_EVIDENCE_FIELD, "externalTags", "sourceTags", "tags", "inferredTags")
    candidate_tags: list[str] = []
    external_source_conflict = False
    external = sources.get("externalTags", [])
    external_evidence_tags = sources.get(EXTERNAL_EVIDENCE_FIELD, [])
    source = sources.get("sourceTags", []) or sources.get("tags", [])
    external_keys = {tag.casefold() for tag in external}
    external_evidence_keys = {tag.casefold() for tag in external_evidence_tags}
    source_keys = {tag.casefold() for tag in source}
    if external and source and not (external_keys & source_keys):
        external_source_conflict = True
    evidence = item_evidence(item, sources, external_source_conflict, external_evidence)
    tag_scores = score_tags(evidence, accepted, rejected)

    if accepted:
        candidate_tags = accepted
    elif external_source_conflict:
        candidate_tags = [*source, *sources.get("inferredTags", [])]
    elif external and source and (external_keys & source_keys):
        candidate_tags = [*external, *source, *sources.get("inferredTags", [])]
    else:
        for field in source_order:
            candidate_tags.extend(sources.get(field, []))
    resolved_tags = [tag for tag in rank_candidate_tags(candidate_tags, tag_scores) if tag.casefold() not in rejected_keys]
    if not accepted and not external and not external_evidence_tags:
        resolved_tags = filter_unsupported_inferred_risky_tags(item, resolved_tags, canonical)
    if not accepted and len(resolved_tags) > 4:
        resolved_tags = resolved_tags[:4]

    has_manual = bool(accepted or sources.get("manualTags"))
    has_external = bool(sources.get("externalTags"))
    has_external_evidence = bool(external_evidence_tags)
    has_source = bool(sources.get("sourceTags") or sources.get("tags"))
    has_inferred_only = bool(sources.get("inferredTags")) and not has_manual and not has_external and not has_source
    sensitive = set(taxonomy.get("sensitiveTags", []))
    has_sensitive = any(tag in sensitive for tag in resolved_tags)

    resolution_method = "source_weighted"
    fallback_evidence: dict[str, Any] | None = None
    non_empty_forced = False
    title_only_fallback = False

    if has_manual:
        review_status = "override_verified"
        confidence = 1.0
        resolution_method = "override"
    elif has_external and has_source:
        if external_source_conflict:
            review_status = "auto_source_external_conflict"
            confidence = 0.74
            resolution_method = "source_external_conflict_source_preferred"
        else:
            review_status = "auto_verified"
            confidence = 0.88
            resolution_method = "external_source_agreement"
    elif has_external:
        review_status = "external_verified"
        confidence = 0.82
        resolution_method = "external_identity_match"
    elif has_external_evidence and has_source:
        if external_evidence_keys & source_keys:
            review_status = "auto_evidence_supported"
            confidence = external_evidence_confidence(external_evidence, 0.78)
            resolution_method = "source_description_evidence_agreement"
        else:
            review_status = "auto_source_with_auxiliary_evidence"
            confidence = external_evidence_confidence(external_evidence, 0.70)
            resolution_method = "source_plus_description_evidence"
    elif has_external_evidence:
        review_status = "auto_evidence_only"
        confidence = external_evidence_confidence(external_evidence, 0.66)
        resolution_method = "description_evidence_only"
    elif has_inferred_only:
        review_status = "auto_inferred_low_confidence"
        confidence = 0.62
        resolution_method = "heuristic_inference"
    else:
        review_status = "auto_source_only"
        confidence = 0.58
        resolution_method = "wfwf_source_only"
        if has_rich_source_tags(source, sensitive):
            review_status = "auto_source_taxonomy_rich"
            confidence = 0.74
            resolution_method = "wfwf_structured_taxonomy_rich"
        elif has_explicit_source_tags(source):
            review_status = "auto_source_taxonomy_single"
            confidence = 0.74
            resolution_method = "wfwf_structured_taxonomy_single"
    if not resolved_tags:
        resolved_tags, resolution_method, confidence, fallback_evidence = fallback_tags(item, kind, canonical)
        review_status = "auto_forced_fallback"
        non_empty_forced = True
        title_only_fallback = False
        for tag in resolved_tags:
            tag_scores[tag] = {
                "score": confidence,
                "sources": [fallback_evidence["field"]],
                "sourceFamilies": ["forced_fallback"],
                "evidenceRefs": ["fallback"],
            }

    facets: dict[str, list[str]] = {
        "genre": [],
        "relationship": [],
        "rating": [],
        "theme": [],
        "format": [],
    }
    facet_name_map = {"genres": "genre"}
    for tag in resolved_tags:
        facet = facet_name_map.get(facets_by_tag.get(tag, "genre"), facets_by_tag.get(tag, "genre"))
        facets.setdefault(facet, [])
        if tag not in facets[facet]:
            facets[facet].append(tag)

    next_item = dict(item)
    if strip_source_tags:
        next_item.pop("sourceTags", None)
        next_item.pop("tags", None)
    next_item["resolvedTags"] = resolved_tags
    next_item["canonicalTags"] = resolved_tags
    next_item["classification"] = {
        "resolvedTags": resolved_tags,
        "facets": facets,
        "confidence": round(confidence, 2),
        "confidenceLabel": "forced_placeholder" if non_empty_forced else confidence_label(confidence),
        "evidenceQuality": "none" if non_empty_forced else confidence_label(confidence),
        "reviewStatus": review_status,
        "resolutionMethod": resolution_method,
        "taxonomyVersion": taxonomy.get("taxonomyVersion", "unknown"),
        "identity": {
            "externalIdentityScore": round(external_identity_score(item, external_source_conflict), 4) if external else 0.0,
            "externalMatchStatus": "rejected_conflict" if external_source_conflict else ("weak_title_only" if external and external_identity_score(item, False) <= 0.70 else ("accepted" if external else "none")),
            "titleOnlyCapApplied": bool(external and external_identity_score(item, external_source_conflict) <= 0.70),
        },
        "flags": {
            "nonEmptyForced": non_empty_forced,
            "titleOnlyFallback": title_only_fallback,
            "identityAmbiguous": external_source_conflict,
            "sourceConflict": external_source_conflict,
        },
    }
    if override:
        next_item["classification"]["reviewedAt"] = override.get("reviewedAt")
        next_item["classification"]["reviewedBy"] = override.get("reviewedBy")
        next_item["classification"]["note"] = override.get("note", "")
    if fallback_evidence:
        evidence.append(fallback_evidence)
    next_item["evidence"] = evidence
    next_item["tagScores"] = {
        tag: {
            "score": round(float(value.get("score", 0.0)), 4),
            "sources": unique(value.get("sources", [])),
            "sourceFamilies": value.get("sourceFamilies", []),
            **({"status": value.get("status")} if value.get("status") else {}),
        }
        for tag, value in tag_scores.items()
    }
    return next_item


def resolve_db(
    db: dict[str, Any],
    kind: str,
    taxonomy: dict[str, Any],
    overrides: dict[str, Any],
    external_evidence_by_id: dict[str, list[dict[str, Any]]] | None = None,
    include_source_tags: bool = False,
    strip_source_tags: bool = False,
) -> dict[str, Any]:
    titles = db.get("titles", {}) if isinstance(db.get("titles"), dict) else {}
    resolved_titles: dict[str, Any] = {}
    counts: Counter[str] = Counter()
    for key, item in titles.items():
        if not isinstance(item, dict):
            continue
        next_item = resolve_item(
            str(key),
            item,
            kind,
            taxonomy,
            overrides,
            (external_evidence_by_id or {}).get(str(key), []),
            include_source_tags=include_source_tags,
            strip_source_tags=strip_source_tags,
        )
        status = next_item.get("classification", {}).get("reviewStatus", "unknown")
        counts[str(status)] += 1
        resolved_titles[str(key)] = next_item
    result = dict(db)
    result["schemaVersion"] = 2
    result["taxonomyVersion"] = taxonomy.get("taxonomyVersion", "unknown")
    result["classificationKind"] = kind
    result["updated"] = date.today().isoformat()
    result["resolver"] = {
        "name": "tools/classification/resolve_classification.py",
        "policy": "official/external metadata > description-only inference > forced fallback; title-only AI evidence and WFWF source genre ignored by default; forceNonEmpty=true",
        "statusCounts": dict(sorted(counts.items())),
    }
    result["titles"] = dict(sorted(resolved_titles.items(), key=lambda item: int(item[0])))
    return result


def copy_asset(output: Path, asset: Path) -> None:
    asset.parent.mkdir(parents=True, exist_ok=True)
    asset.write_text(output.read_text(encoding="utf-8"), encoding="utf-8")


def write_low_confidence_queue(path: Path, data: dict[str, Any]) -> None:
    titles = data.get("titles", {}) if isinstance(data.get("titles"), dict) else {}
    queue = []
    for key, item in titles.items():
        status = item.get("classification", {}).get("reviewStatus", "")
        if isinstance(status, str) and "low_confidence" in status:
            queue.append(
                {
                    "id": int(key),
                    "name": item.get("name", ""),
                    "thumb": item.get("thumb", ""),
                    "reviewStatus": status,
                    "resolvedTags": item.get("resolvedTags", []),
                    "evidence": item.get("evidence", []),
                }
            )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(queue, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Resolve classification DBs into provenance-backed canonical tags.")
    parser.add_argument("--kind", choices=("webtoon", "comic"), required=True)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--asset-output", default="")
    parser.add_argument("--taxonomy", default="tools/classification/taxonomy.json")
    parser.add_argument("--manual-overrides", default="tools/classification/manual_overrides.json")
    parser.add_argument("--external-evidence", action="append", default=[])
    parser.add_argument("--include-source-tags", action="store_true", help="Trust WFWF/source genre tags as classification evidence.")
    parser.add_argument("--strip-source-tags", action="store_true", help="Remove raw sourceTags/tags from the final resolved DB output.")
    parser.add_argument("--low-confidence-output", default="")
    args = parser.parse_args()

    taxonomy = load_json(Path(args.taxonomy), {})
    override_root = load_json(Path(args.manual_overrides), {"overrides": {}})
    overrides = override_root.get("overrides", {}) if isinstance(override_root.get("overrides"), dict) else {}
    external_evidence = load_evidence_jsonl([Path(path) for path in args.external_evidence]) if args.external_evidence else {}
    data = resolve_db(
        load_json(Path(args.input), {}),
        args.kind,
        taxonomy,
        overrides,
        external_evidence,
        include_source_tags=args.include_source_tags,
        strip_source_tags=args.strip_source_tags,
    )
    output = Path(args.output)
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.asset_output:
        copy_asset(output, Path(args.asset_output))
    if args.low_confidence_output:
        write_low_confidence_queue(Path(args.low_confidence_output), data)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
