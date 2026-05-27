#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sqlite3
import string
import time
import unicodedata
from pathlib import Path
from typing import Any


def normalize_name(value: str) -> str:
    text = (value or "").lower()
    return "".join(
        ch
        for ch in text
        if not ch.isspace()
        and ch not in string.punctuation
        and not unicodedata.category(ch).startswith("P")
    )


def normalize_tag(value: str) -> str:
    return (value or "").strip().lower()


def unique(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value or "").strip()
        key = text.casefold()
        if not text or key in seen:
            continue
        seen.add(key)
        result.append(text)
    return result


def resolved_tags(item: dict[str, Any]) -> list[str]:
    direct = item.get("resolvedTags")
    if isinstance(direct, list):
        return unique([str(value) for value in direct])
    classification = item.get("classification")
    nested = classification.get("resolvedTags") if isinstance(classification, dict) else None
    if isinstance(nested, list):
        return unique([str(value) for value in nested])
    return []


def source_site(item: dict[str, Any], default: str) -> str:
    value = str(item.get("sourceSite") or item.get("source_site") or default or "wfwf").strip().lower()
    return value if value in {"wfwf", "ntk"} else "wfwf"


def ensure_schema(db: sqlite3.Connection) -> None:
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS classification_titles (
            kind TEXT NOT NULL,
            source_site TEXT NOT NULL DEFAULT 'wfwf',
            id INTEGER NOT NULL,
            path TEXT,
            name TEXT NOT NULL,
            normalized_name TEXT NOT NULL,
            thumb TEXT,
            release TEXT,
            updated_at INTEGER DEFAULT 0,
            PRIMARY KEY(kind,source_site,id)
        );
        CREATE TABLE IF NOT EXISTS classification_title_tags (
            kind TEXT NOT NULL,
            source_site TEXT NOT NULL DEFAULT 'wfwf',
            id INTEGER NOT NULL,
            tag TEXT NOT NULL,
            normalized_tag TEXT NOT NULL,
            PRIMARY KEY(kind,source_site,id,normalized_tag)
        );
        CREATE TABLE IF NOT EXISTS classification_meta (
            key TEXT PRIMARY KEY,
            value TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_classification_tag
            ON classification_title_tags(kind,source_site,normalized_tag,id);
        CREATE INDEX IF NOT EXISTS idx_classification_title_id
            ON classification_titles(kind,source_site,id);
        CREATE INDEX IF NOT EXISTS idx_classification_title_name
            ON classification_titles(kind,source_site,normalized_name);
        """
    )


def insert_json_db(db: sqlite3.Connection, path: Path, kind: str, default_source_site: str, updated_at: int) -> int:
    data = json.loads(path.read_text(encoding="utf-8"))
    titles = data.get("titles", {})
    if not isinstance(titles, dict):
        return 0
    count = 0
    for key, item in titles.items():
        if not isinstance(item, dict):
            continue
        try:
            title_id = int(key)
        except ValueError:
            continue
        name = str(item.get("name") or "").strip()
        tags = resolved_tags(item)
        if not name or not tags:
            continue
        site = source_site(item, default_source_site)
        db.execute(
            """
            INSERT OR REPLACE INTO classification_titles(
                kind,source_site,id,path,name,normalized_name,thumb,release,updated_at
            ) VALUES(?,?,?,?,?,?,?,?,?)
            """,
            (
                kind,
                site,
                title_id,
                str(item.get("path") or ""),
                name,
                normalize_name(name),
                str(item.get("thumb") or ""),
                str(item.get("release") or ""),
                updated_at,
            ),
        )
        for tag in tags:
            db.execute(
                """
                INSERT OR REPLACE INTO classification_title_tags(
                    kind,source_site,id,tag,normalized_tag
                ) VALUES(?,?,?,?,?)
                """,
                (kind, site, title_id, tag, normalize_tag(tag)),
            )
        count += 1
    return count


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def gzip_file(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as src, gzip.open(target, "wb", compresslevel=9) as dst:
        for chunk in iter(lambda: src.read(1024 * 1024), b""):
            dst.write(chunk)


def build(args: argparse.Namespace) -> None:
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    updated_at = int(time.time())
    db = sqlite3.connect(output)
    try:
        ensure_schema(db)
        webtoon_count = insert_json_db(db, Path(args.webtoon), "webtoon", args.source_site, updated_at)
        comic_count = insert_json_db(db, Path(args.comic), "comic", args.source_site, updated_at)
        db.execute("INSERT OR REPLACE INTO classification_meta(key,value) VALUES('version',?)", (args.version,))
        db.execute("INSERT OR REPLACE INTO classification_meta(key,value) VALUES('webtoonCount',?)", (str(webtoon_count),))
        db.execute("INSERT OR REPLACE INTO classification_meta(key,value) VALUES('comicCount',?)", (str(comic_count),))
        db.commit()
        db.execute("VACUUM")
    finally:
        db.close()

    db_sha = sha256(output)
    gzip_output = Path(args.gzip_output)
    gzip_file(output, gzip_output)
    manifest = {
        "version": args.version,
        "baseUrl": gzip_output.name,
        "baseSha256": db_sha,
        "minSupportedVersion": "",
        "patches": [],
    }
    manifest_output = Path(args.manifest_output)
    manifest_output.parent.mkdir(parents=True, exist_ok=True)
    manifest_output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"webtoon titles: {webtoon_count}")
    print(f"comic titles: {comic_count}")
    print(f"sqlite: {output} sha256={db_sha}")
    print(f"gzip: {gzip_output} size={gzip_output.stat().st_size}")
    print(f"manifest: {manifest_output}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build release SQLite classification DB assets.")
    parser.add_argument("--webtoon", default="webtoon-classification.json")
    parser.add_argument("--comic", default="comic-classification.json")
    parser.add_argument("--output", default="release/classification-base.sqlite")
    parser.add_argument("--gzip-output", default="release/classification-base.sqlite.gz")
    parser.add_argument("--manifest-output", default="release/classification-manifest.json")
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-site", default="wfwf")
    args = parser.parse_args()
    build(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
