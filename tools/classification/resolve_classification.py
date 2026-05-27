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
    (("전투", "격투", "싸움", "전쟁", "생존을 건 사투"), ["액션"]),
    (("무림", "검신", "천마", "소림", "강호", "무협", "검객", "검왕"), ["무협", "액션"]),
    (("학교", "학원", "동아리", "교실", "고등학교", "대학교"), ["학원"]),
    (("살인", "범인", "탐정", "저주", "실종", "복수", "범죄", "형사", "사건"), ["스릴러", "미스터리"]),
    (("귀신", "괴담", "악몽", "공포", "좀비", "유령", "악령", "오싹"), ["공포", "스릴러"]),
    (("요리", "셰프", "식당", "먹방"), ["요리"]),
    (("야구", "축구", "농구", "복싱", "골프", "테니스", "스포츠"), ["스포츠"]),
    (("연애", "신부", "남친", "여친", "결혼", "키스", "첫사랑", "로맨스"), ["로맨스"]),
    (("개그", "코미디", "웃음", "병맛"), ["개그"]),
    (("BL", "비엘", "오메가버스", "보이즈러브"), ["BL"]),
    (("백합", "GL", "그녀들"), ["백합"]),
    (("일상물", "소소한 일상"), ["일상"]),
    (("시대", "조선", "황제", "역사"), ["시대"]),
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


def item_sources(item: dict[str, Any], canonical: dict[str, str]) -> dict[str, list[str]]:
    sources: dict[str, list[str]] = {}
    for field in LEGACY_FIELDS:
        values = item.get(field)
        if isinstance(values, list):
            sources[field] = unique(normalize_tag(value, canonical) for value in values)
    return sources


def text_inference_tags(item: dict[str, Any], canonical: dict[str, str]) -> list[str]:
    text = " ".join(str(item.get(field, "")) for field in ("name", "naverName", "release", "description"))
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
    for entry in external_evidence:
        values = entry.get("normalizedTags") or entry.get("mappedTags") or entry.get("tags")
        if isinstance(values, list):
            tags.extend(normalize_tag(tag, canonical) for tag in values)
    if tags:
        sources[EXTERNAL_EVIDENCE_FIELD] = unique([*sources.get(EXTERNAL_EVIDENCE_FIELD, []), *tags])
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
    source_tags = item.get("sourceTags")
    external_tags = item.get("externalTags")
    if isinstance(source_tags, list) and isinstance(external_tags, list):
        source_keys = {str(tag).casefold() for tag in source_tags}
        external_keys = {str(tag).casefold() for tag in external_tags}
        if source_keys & external_keys:
            return min(max(match_score, 0.88), 0.96)
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


def fallback_tags(item: dict[str, Any], kind: str, canonical: dict[str, str]) -> tuple[list[str], str, float, dict[str, Any]]:
    text = " ".join(str(item.get(field, "")) for field in ("name", "naverName", "release"))
    lower = text.casefold()
    for needles, tags in TITLE_KEYWORD_PRIORS:
        if any(needle.casefold() in lower for needle in needles):
            resolved = unique(normalize_tag(tag, canonical) for tag in tags)
            return resolved, "title_keyword_prior_forced_non_empty", 0.74, {
                "source": "automated-title-keyword-prior",
                "field": "name",
                "mappedTags": resolved,
                "confidence": 0.74,
                "forced": True,
            }
    resolved = unique(normalize_tag(tag, canonical) for tag in GLOBAL_PRIORS.get(kind, ["드라마"]))
    return resolved, "global_prior_forced_non_empty", 0.74, {
        "source": "automated-global-prior",
        "field": "kind",
        "mappedTags": resolved,
        "confidence": 0.74,
        "forced": True,
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
        return "low"
    return "forced_fallback"


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
) -> dict[str, Any]:
    canonical, facets_by_tag = taxonomy_sets(taxonomy)
    sources = item_sources(item, canonical)
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
            confidence = 0.78
            resolution_method = "source_description_evidence_agreement"
        else:
            review_status = "auto_source_with_auxiliary_evidence"
            confidence = 0.74
            resolution_method = "source_plus_description_evidence"
    elif has_external_evidence:
        review_status = "auto_evidence_only"
        confidence = 0.74
        resolution_method = "description_evidence_only"
    elif has_inferred_only:
        review_status = "auto_inferred_low_confidence"
        confidence = 0.74
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
        title_only_fallback = resolution_method == "title_keyword_prior_forced_non_empty"
        for tag in resolved_tags:
            tag_scores[tag] = {
                "score": confidence,
                "sources": [fallback_evidence["field"]],
                "sourceFamilies": ["forced_fallback"],
                "evidenceRefs": ["fallback"],
            }
    weak_sensitive_statuses = {
        "auto_source_only",
        "auto_inferred_low_confidence",
        "auto_evidence_only",
        "auto_forced_fallback",
    }
    if has_sensitive and review_status in weak_sensitive_statuses:
        review_status = "auto_sensitive_high_confidence"
        confidence = max(confidence, 0.74)

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
    next_item["resolvedTags"] = resolved_tags
    next_item["canonicalTags"] = resolved_tags
    next_item["classification"] = {
        "resolvedTags": resolved_tags,
        "facets": facets,
        "confidence": round(confidence, 2),
        "confidenceLabel": confidence_label(confidence),
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
) -> dict[str, Any]:
    titles = db.get("titles", {}) if isinstance(db.get("titles"), dict) else {}
    resolved_titles: dict[str, Any] = {}
    counts: Counter[str] = Counter()
    for key, item in titles.items():
        if not isinstance(item, dict):
            continue
        next_item = resolve_item(str(key), item, kind, taxonomy, overrides, (external_evidence_by_id or {}).get(str(key), []))
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
        "policy": "official/external evidence > WFWF source > inference > title keyword prior > global prior; forceNonEmpty=true",
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
    parser.add_argument("--low-confidence-output", default="")
    args = parser.parse_args()

    taxonomy = load_json(Path(args.taxonomy), {})
    override_root = load_json(Path(args.manual_overrides), {"overrides": {}})
    overrides = override_root.get("overrides", {}) if isinstance(override_root.get("overrides"), dict) else {}
    external_evidence = load_evidence_jsonl([Path(path) for path in args.external_evidence]) if args.external_evidence else {}
    data = resolve_db(load_json(Path(args.input), {}), args.kind, taxonomy, overrides, external_evidence)
    output = Path(args.output)
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.asset_output:
        copy_asset(output, Path(args.asset_output))
    if args.low_confidence_output:
        write_low_confidence_queue(Path(args.low_confidence_output), data)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
