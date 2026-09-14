#!/usr/bin/env python3
"""Host-only reversible scoped engine-cache isolation for the qualification harness.

Scope is derived exclusively from verified plan exports plus baseline database rows and
emitted cache bindings; the chain follows explicit nextEpisode pointers only. Engine
database paths are storage-relative (`pages/<name>`, `staging/<name>`) and are translated
to the fixed remote roots exactly, with the file name re-derived from the page identity,
content revision and body digest. Every deletion and restoration is an explicit validated
regular filename under one of three fixed roots; there is no wildcard removal, no
traversal, no symlink following, and no recursive deletion.

Host contract (list/read/write/delete required):

    list(root) -> iterable of names
    read(root, name) -> bytes
    write(root, name, data) -> None
    delete(root, name) -> None
    exists(root, name) -> bool
    is_regular(root, name) -> bool          # must reject symlinks
    root_safe(root) -> bool                 # fixed root path contains no symlink
    metadata(root, name) -> dict            # mode/uid/gid/sizeBytes/mtimeEpoch
    set_metadata(root, name, metadata)      # restore mode/uid/gid/mtime
"""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import tempfile
import uuid
from pathlib import Path

from engine_cache_identity import cache_name

PAGES_ROOT = "app_engine_pages_v1/pages"
STAGING_ROOT = "app_engine_pages_v1/staging"
PLANS_ROOT = "app_engine_episode_plans_v1"
ROOT_ORDER = (PAGES_ROOT, STAGING_ROOT, PLANS_ROOT)
STORAGE_ROOTS = {"pages": PAGES_ROOT, "staging": STAGING_ROOT}
PAGE_NAME = re.compile(r"[0-9a-f]{64}-[0-9a-f]{64}-[0-9a-f]{64}\.page")
STAGING_NAME = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.part")
PLAN_NAME = re.compile(r"[0-9a-f]{64}\.plan")
ROOT_PATTERNS = {PAGES_ROOT: PAGE_NAME, STAGING_ROOT: STAGING_NAME, PLANS_ROOT: PLAN_NAME}
PLAN_PAGE_KEY = "engine-complete-plan"
NULL_DIGEST = "0" * 64
KEY_PATTERN = re.compile(r"[0-9a-f]{64}")


class ColdScopeError(RuntimeError):
    pass


def _require(condition, message) -> None:
    if not condition:
        raise ColdScopeError(message)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def revision_digest(revision: str) -> str:
    return hashlib.sha256(revision.encode("utf-8")).hexdigest()


def cache_key(page_identity: dict) -> str:
    name = cache_name(page_identity, "scope", NULL_DIGEST)
    return name.split("-", 1)[0]


def expected_page_file(page_identity: dict, revision, digest) -> str:
    _require(isinstance(revision, str) and bool(revision), "page content revision is missing")
    _require(isinstance(digest, str) and bool(KEY_PATTERN.fullmatch(digest)), "page body digest is invalid")
    return cache_name(page_identity, revision, digest)


def plan_file_name(episode_identity: dict) -> str:
    page = {"sourceId": episode_identity["sourceId"], "seriesKey": episode_identity["seriesKey"],
            "episodeKey": episode_identity["episodeKey"], "pageKey": PLAN_PAGE_KEY}
    return cache_key(page) + ".plan"


def validate_name(root: str, name: str) -> str:
    _require(root in ROOT_PATTERNS, f"unknown cache root: {root!r}")
    _require(isinstance(name, str) and bool(name), "cache name is empty")
    _require("/" not in name and "\\" not in name, f"cache name contains a separator: {name!r}")
    _require(name == Path(name).name and ".." not in name, f"cache name is not a plain file: {name!r}")
    _require(not name.endswith(" ") and not name.endswith("."), f"cache name is Windows-ambiguous: {name!r}")
    _require(bool(ROOT_PATTERNS[root].fullmatch(name)), f"cache name does not match {root}: {name!r}")
    return name


def storage_name(relative_path, storage_root: str) -> str:
    """Translate an engine database storage-relative path (`pages/x`, `staging/x`)."""
    _require(storage_root in STORAGE_ROOTS, f"unknown storage root: {storage_root!r}")
    expected_root = STORAGE_ROOTS[storage_root]
    _require(isinstance(relative_path, str) and bool(relative_path), "relative cache path is missing")
    normalized = relative_path.replace("\\", "/")
    parts = normalized.split("/")
    _require(len(parts) == 2 and parts[0] == storage_root and parts[1] not in ("", ".", ".."),
             f"cache relative path is outside {expected_root}: {relative_path!r}")
    return validate_name(expected_root, parts[1])


def list_root(host, root: str) -> list[str]:
    names = sorted(host.list(root))
    _require(len(names) == len(set(names)), f"duplicate names in {root}")
    for name in names:
        validate_name(root, name)
    regular = getattr(host, "is_regular", None)
    if regular is not None:
        for name in names:
            _require(bool(regular(root, name)), f"cache entry is not a regular file: {root}/{name}")
    return names


def verify_root_paths(host) -> None:
    """Reject symlinked fixed-root directories before any write or delete."""
    checker = getattr(host, "root_safe", None)
    if checker is None:
        return
    for root in ROOT_ORDER:
        _require(bool(checker(root)), f"cache root path is symlinked or unsafe: {root}")


def _host_metadata(host, root: str, name: str) -> dict:
    reader = getattr(host, "metadata", None)
    if reader is None:
        return {}
    value = reader(root, name)
    return value if isinstance(value, dict) else {}


def _metadata_mismatch(host, root: str, name: str, entry: dict) -> str | None:
    expected = entry.get("metadata") or {}
    if not expected:
        return None
    current = _host_metadata(host, root, name)
    if not current:
        return "host metadata unavailable"
    for key in ("mode", "uid", "gid", "mtimeEpoch", "sizeBytes"):
        if key in expected and current.get(key) != expected[key]:
            return f"{key} {current.get(key)!r} != {expected[key]!r}"
    return None


def _entry(host, root: str, name: str) -> dict:
    data = host.read(root, name)
    entry = {"root": root, "name": validate_name(root, name),
             "sha256": sha256_hex(data), "sizeBytes": len(data)}
    metadata = _host_metadata(host, root, name)
    if metadata:
        entry["metadata"] = metadata
    return entry


def _normalize_database_rows(database_rows: dict | None) -> tuple[list[dict], list[dict]]:
    if not database_rows:
        return [], []
    pages = database_rows.get("enginePages") or []
    publications = database_rows.get("enginePublications") or []
    _require(isinstance(pages, list) and isinstance(publications, list), "database rows are malformed")
    return pages, publications


def _episode_identity(value, label: str) -> dict:
    _require(isinstance(value, dict), f"{label} identity is missing")
    keys = ("sourceId", "seriesKey", "episodeKey")
    _require(all(isinstance(value.get(key), str) and value[key] for key in keys),
             f"{label} identity is incomplete")
    return {key: value[key] for key in keys}


def _row_page_identity(row: dict, label: str) -> dict:
    keys = ("sourceKey", "seriesKey", "episodeKey", "pageKey")
    _require(all(isinstance(row.get(key), str) and row[key] for key in keys),
             f"{label} row identity is incomplete")
    return {"sourceId": row["sourceKey"], "seriesKey": row["seriesKey"],
            "episodeKey": row["episodeKey"], "pageKey": row["pageKey"]}


def derive_scope(requested: dict, plans: list[dict], database_rows: dict | None = None,
                 bindings: list[dict] | None = None, max_chain: int = 64) -> dict:
    requested_identity = _episode_identity(requested, "requested episode")
    source, series = requested_identity["sourceId"], requested_identity["seriesKey"]
    plans_by_episode: dict[str, dict] = {}
    for plan in plans or []:
        identity = _episode_identity(plan.get("episodeIdentity"), "plan export")
        _require(identity["sourceId"] == source and identity["seriesKey"] == series,
                 f"plan export belongs to another series: {identity}")
        _require(identity["episodeKey"] not in plans_by_episode,
                 f"duplicate plan export for {identity['episodeKey']}")
        revision = plan.get("contentRevision")
        _require(isinstance(revision, str) and bool(revision), "plan export lacks a content revision")
        pages = plan.get("pages")
        _require(isinstance(pages, list) and bool(pages), f"plan export has no pages: {identity}")
        plans_by_episode[identity["episodeKey"]] = plan

    chain: list[str] = []
    links: list[dict] = []
    cursor = requested_identity["episodeKey"]
    while True:
        _require(cursor not in chain, f"nextEpisode chain cycles at {cursor}")
        chain.append(cursor)
        _require(len(chain) <= max_chain, "nextEpisode chain exceeds the bound")
        plan = plans_by_episode.get(cursor)
        if plan is None:
            break
        next_identity = plan.get("nextEpisode")
        if next_identity is None:
            break
        next_episode = _episode_identity(next_identity, "nextEpisode")
        _require(next_episode["sourceId"] == source and next_episode["seriesKey"] == series,
                 f"nextEpisode leaves the series: {next_episode}")
        links.append({"episodeKey": cursor, "nextEpisodeKey": next_episode["episodeKey"],
                      "planExportPresent": next_episode["episodeKey"] in plans_by_episode})
        cursor = next_episode["episodeKey"]

    page_keys: dict[str, dict] = {}
    plan_keys: set[str] = set()
    for episode in chain:
        plan = plans_by_episode.get(episode)
        if plan is None:
            continue
        for page in plan["pages"]:
            identity = _episode_identity(page.get("pageIdentity"), "plan page")
            _require(identity["sourceId"] == source and identity["seriesKey"] == series
                     and identity["episodeKey"] == episode,
                     f"plan page identity does not match its episode: {identity}")
            page_key = page["pageIdentity"].get("pageKey")
            _require(isinstance(page_key, str) and bool(page_key), "plan page key is missing")
            page_identity = {**identity, "pageKey": page_key}
            key = cache_key(page_identity)
            plan_keys.add(key)
            entry = page_keys.setdefault(key, {"episodeKeys": set(), "sources": set(), "files": set()})
            entry["episodeKeys"].add(episode)
            entry["sources"].add("plan")

    pages_rows, publication_rows = _normalize_database_rows(database_rows)
    db_keys: set[str] = set()
    db_files: set[str] = set()
    staging_names: set[str] = set()
    journal_names: set[str] = set()
    for row in pages_rows:
        if not (row.get("sourceKey") == source and row.get("seriesKey") == series
                and row.get("episodeKey") in chain):
            continue
        page_identity = _row_page_identity(row, "database page")
        key = cache_key(page_identity)
        _require(key == row.get("cacheKey"),
                 f"database cache key does not match its page identity: {row.get('cacheKey')!r}")
        name = storage_name(row.get("relativePath"), "pages")
        _require(name == expected_page_file(page_identity, row.get("contentRevision"), row.get("sha256")),
                 f"database page filename does not match identity/revision/digest: {name!r}")
        db_keys.add(key)
        db_files.add(name)
        entry = page_keys.setdefault(key, {"episodeKeys": set(), "sources": set(), "files": set()})
        entry["episodeKeys"].add(row["episodeKey"])
        entry["sources"].add("database")
        entry["files"].add(name)
    for row in publication_rows:
        if not (row.get("sourceKey") == source and row.get("seriesKey") == series
                and row.get("episodeKey") in chain):
            continue
        page_identity = _row_page_identity(row, "database publication")
        key = cache_key(page_identity)
        _require(key == row.get("cacheKey"),
                 f"publication cache key does not match its page identity: {row.get('cacheKey')!r}")
        staging = storage_name(row.get("stagingRelativePath"), "staging")
        destination = storage_name(row.get("destinationRelativePath"), "pages")
        _require(destination == expected_page_file(page_identity, row.get("contentRevision"), row.get("sha256")),
                 f"publication destination does not match identity/revision/digest: {destination!r}")
        staging_names.add(staging)
        journal_names.add(destination)
        entry = page_keys.setdefault(key, {"episodeKeys": set(), "sources": set(), "files": set()})
        entry["episodeKeys"].add(row["episodeKey"])
        entry["sources"].add("database-publication")
        entry["files"].add(destination)

    binding_keys: set[str] = set()
    for binding in bindings or []:
        identity = _episode_identity(binding.get("pageIdentity"), "binding")
        if not (identity["sourceId"] == source and identity["seriesKey"] == series
                and identity["episodeKey"] in chain):
            continue
        page_key = binding["pageIdentity"].get("pageKey")
        _require(isinstance(page_key, str) and bool(page_key), "binding page key is missing")
        page_identity = {**identity, "pageKey": page_key}
        key = cache_key(page_identity)
        binding_keys.add(key)
        entry = page_keys.setdefault(key, {"episodeKeys": set(), "sources": set(), "files": set()})
        entry["episodeKeys"].add(identity["episodeKey"])
        entry["sources"].add("binding")
        cache_file = binding.get("cacheFile")
        if cache_file:
            validate_name(PAGES_ROOT, cache_file)
            segments = cache_file.split("-")
            _require(len(segments) == 3 and segments[0] == key,
                     f"binding cache file does not match its page identity: {cache_file!r}")
            revision = binding.get("contentRevision")
            if isinstance(revision, str) and revision:
                _require(segments[1] == revision_digest(revision),
                         f"binding cache file does not match its content revision: {cache_file!r}")
            digest = binding.get("sha256")
            if isinstance(digest, str) and KEY_PATTERN.fullmatch(digest):
                _require(cache_file == expected_page_file(page_identity, revision, digest),
                         f"binding cache file does not match its body digest: {cache_file!r}")
            entry["files"].add(cache_file)

    plan_files = sorted({plan_file_name({"sourceId": source, "seriesKey": series, "episodeKey": episode})
                         for episode in chain})
    scope = {
        "sourceId": source,
        "seriesKey": series,
        "requestedEpisodeKey": requested_identity["episodeKey"],
        "scopeEpisodes": sorted(chain),
        "chainLinks": links,
        "pageCacheKeys": sorted(page_keys),
        "planFiles": plan_files,
        "stagingFiles": sorted(staging_names),
        "journalFiles": sorted(journal_names),
        "pageEvidence": {key: {"episodeKeys": sorted(value["episodeKeys"]),
                               "sources": sorted(value["sources"]),
                               "files": sorted(value["files"])}
                         for key, value in sorted(page_keys.items())},
        "reconciliations": {
            "planOnlyPageKeys": sorted(plan_keys - db_keys - binding_keys),
            "databaseOnlyPageKeys": sorted(db_keys - plan_keys - binding_keys),
            "bindingOnlyPageKeys": sorted(binding_keys - plan_keys - db_keys),
            "databasePageFiles": sorted(db_files),
            "databaseStagingFiles": sorted(staging_names),
        },
    }
    scope["scopeSha256"] = scope_digest(scope)
    return scope


def canonical_scope(scope: dict) -> str:
    fields = ("sourceId", "seriesKey", "requestedEpisodeKey", "scopeEpisodes",
              "pageCacheKeys", "planFiles", "stagingFiles", "journalFiles")
    canonical = {key: scope[key] for key in fields}
    return json.dumps(canonical, sort_keys=True, separators=(",", ":"))


def scope_digest(scope: dict) -> str:
    return sha256_hex(canonical_scope(scope).encode("utf-8"))


def roots_digest(roots: dict) -> str:
    canonical = {root: sorted(roots[root]["entries"], key=lambda item: item["name"]) for root in sorted(roots)}
    return sha256_hex(json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def capture_inventory(host) -> dict:
    roots = {}
    for root in ROOT_ORDER:
        roots[root] = {"entries": [_entry(host, root, name) for name in list_root(host, root)]}
    return {"roots": roots, "inventorySha256": roots_digest(roots)}


def backup_cache(host, backup_root: Path) -> dict:
    backup_root.mkdir(parents=True, exist_ok=True)
    roots = {}
    for root in ROOT_ORDER:
        target_dir = backup_root / root.replace("/", "__")
        target_dir.mkdir(parents=True, exist_ok=True)
        entries = []
        for name in list_root(host, root):
            data = host.read(root, name)
            digest = sha256_hex(data)
            target = target_dir / validate_name(root, name)
            target.write_bytes(data)
            _require(sha256_hex(target.read_bytes()) == digest, f"backup write verification failed: {name}")
            entry = {"root": root, "name": name, "sha256": digest, "sizeBytes": len(data)}
            metadata = _host_metadata(host, root, name)
            if metadata:
                entry["metadata"] = metadata
            entries.append(entry)
        roots[root] = {"entries": entries}
    manifest = {"roots": roots, "entries": sum(len(roots[root]["entries"]) for root in roots)}
    manifest["inventorySha256"] = roots_digest(roots)
    manifest["cacheBaselineSha256"] = manifest["inventorySha256"]
    return manifest


def _preflight_backup(backup_root: Path, baseline: dict) -> None:
    for root in ROOT_ORDER:
        entries = baseline["roots"][root]["entries"]
        names = [entry["name"] for entry in entries]
        _require(names == sorted(set(names)), f"baseline manifest has duplicate {root} entries")
        for entry in entries:
            validate_name(root, entry["name"])
            stored = backup_root / root.replace("/", "__") / entry["name"]
            _require(stored.is_file(), f"scoped backup is incomplete: {root}/{entry['name']}")
            _require(sha256_hex(stored.read_bytes()) == entry["sha256"],
                     f"backup bytes changed before restore: {root}/{entry['name']}")


def verify_baseline(host, baseline: dict) -> dict:
    for root in ROOT_ORDER:
        expected = {entry["name"]: entry for entry in baseline["roots"][root]["entries"]}
        names = list_root(host, root)
        _require(names == sorted(expected), f"cache root {root} does not match the baseline set")
        for name in names:
            entry = expected[name]
            _require(sha256_hex(host.read(root, name)) == entry["sha256"],
                     f"cache file {root}/{name} does not match the baseline hash")
            mismatch = _metadata_mismatch(host, root, name, entry)
            _require(mismatch is None, f"cache file {root}/{name} metadata changed: {mismatch}")
    return {"globalBaselineExact": True,
            "entries": sum(len(baseline["roots"][root]["entries"]) for root in ROOT_ORDER),
            "inventorySha256": baseline["inventorySha256"]}


def restore_cache_baseline(host, backup_root: Path, baseline: dict) -> dict:
    """Restore the exact baseline; preflight proves the backup before the first mutation."""
    verify_root_paths(host)
    _preflight_backup(backup_root, baseline)
    removed: dict[str, list[str]] = {root: [] for root in ROOT_ORDER}
    restored: list[dict] = []
    unchanged: list[dict] = []
    for root in ROOT_ORDER:
        expected = {entry["name"]: entry for entry in baseline["roots"][root]["entries"]}
        current = set(list_root(host, root))
        for name in sorted(current):
            if name not in expected:
                host.delete(root, name)
                removed[root].append(name)
        for name, entry in sorted(expected.items()):
            stored = backup_root / root.replace("/", "__") / name
            data = stored.read_bytes()
            same_bytes = name in current and sha256_hex(host.read(root, name)) == entry["sha256"]
            if not same_bytes:
                host.write(root, name, data)
                restored.append({"root": root, "name": name})
            else:
                unchanged.append({"root": root, "name": name})
            setter = getattr(host, "set_metadata", None)
            if setter is not None and entry.get("metadata"):
                setter(root, name, entry["metadata"])
            _require(sha256_hex(host.read(root, name)) == entry["sha256"],
                     f"restored hash mismatch: {root}/{name}")
            mismatch = _metadata_mismatch(host, root, name, entry)
            _require(mismatch is None, f"restored metadata mismatch: {root}/{name}: {mismatch}")
    proof = verify_baseline(host, baseline)
    proof.update({"removedRunCreated": removed, "restored": restored, "unchanged": unchanged})
    return proof


def isolate_scope(host, scope: dict) -> dict:
    verify_root_paths(host)
    page_keys = set(scope["pageCacheKeys"])
    deleted = {PAGES_ROOT: [], PLANS_ROOT: [], STAGING_ROOT: []}
    page_targets = [name for name in list_root(host, PAGES_ROOT) if name.split("-", 1)[0] in page_keys]
    plan_targets = [name for name in list_root(host, PLANS_ROOT) if name in set(scope["planFiles"])]
    staging_targets = [name for name in list_root(host, STAGING_ROOT) if name in set(scope["stagingFiles"])]
    for root, targets in ((PAGES_ROOT, page_targets), (PLANS_ROOT, plan_targets), (STAGING_ROOT, staging_targets)):
        for name in targets:
            host.delete(root, name)
            deleted[root].append(name)
    return {"deleted": deleted,
            "deletedCount": sum(len(value) for value in deleted.values())}


def _present_scoped(host, scope: dict) -> tuple[list[dict], list[str], list[str], list[str]]:
    entries: list[dict] = []
    page_keys = set(scope["pageCacheKeys"])
    present_keys: set[str] = set()
    for name in list_root(host, PAGES_ROOT):
        key = name.split("-", 1)[0]
        if key in page_keys:
            entries.append(_entry(host, PAGES_ROOT, name))
            present_keys.add(key)
    plans = set(list_root(host, PLANS_ROOT))
    staging = set(list_root(host, STAGING_ROOT))
    absent_plans, absent_staging = [], []
    for name in scope["planFiles"]:
        if name in plans:
            entries.append(_entry(host, PLANS_ROOT, name))
        else:
            absent_plans.append(name)
    for name in scope["stagingFiles"]:
        if name in staging:
            entries.append(_entry(host, STAGING_ROOT, name))
        else:
            absent_staging.append(name)
    return entries, sorted(page_keys - present_keys), absent_plans, absent_staging


def scope_inventory(host, scope: dict) -> dict:
    entries, absent_page_keys, absent_plans, absent_staging = _present_scoped(host, scope)
    entries.sort(key=lambda item: (item["root"], item["name"]))
    canonical = json.dumps(sorted(entries, key=lambda item: (item["root"], item["name"])),
                           sort_keys=True, separators=(",", ":"))
    return {
        "scopeSha256": scope["scopeSha256"],
        "entries": entries,
        "absentPageCacheKeys": absent_page_keys,
        "absentPlanFiles": absent_plans,
        "absentStagingFiles": absent_staging,
        "scopedCacheEntriesPresent": len(entries),
        "scopedEntriesAbsent": len(entries) == 0,
        "inventorySha256": sha256_hex(canonical.encode("utf-8")),
    }


def verify_scope_restored(host, baseline: dict, scope: dict) -> dict:
    proof = verify_baseline(host, baseline)
    entries, absent_page_keys, absent_plans, absent_staging = _present_scoped(host, scope)
    baseline_names = {(root, entry["name"]) for root in ROOT_ORDER
                      for entry in baseline["roots"][root]["entries"]}
    present_names = {(entry["root"], entry["name"]) for entry in entries}
    expected_names = {name for name in baseline_names
                      if (name[0] == PAGES_ROOT and name[1].split("-", 1)[0] in set(scope["pageCacheKeys"]))
                      or (name[0] == PLANS_ROOT and name[1] in set(scope["planFiles"]))
                      or (name[0] == STAGING_ROOT and name[1] in set(scope["stagingFiles"]))}
    _require(present_names == expected_names,
             "scoped entries do not match the scope baseline after restoration")
    proof.update({
        "scopeRestorationVerified": True,
        "scopeSha256": scope["scopeSha256"],
        "scopeEntries": len(expected_names),
        "scopeRestoredFiles": sorted(entries, key=lambda item: (item["root"], item["name"])),
        "absentPageCacheKeys": absent_page_keys,
        "absentPlanFiles": absent_plans,
        "absentStagingFiles": absent_staging,
    })
    return proof


def read_database_rows(db_path: Path) -> dict:
    connection = sqlite3.connect(f"file:{Path(db_path).as_posix()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        pages = [dict(row) for row in connection.execute(
            "SELECT cacheKey, contentRevision, sourceKey, seriesKey, episodeKey, pageKey, "
            "relativePath, sha256, byteCount FROM engine_pages")]
        publications = [dict(row) for row in connection.execute(
            "SELECT publicationId, cacheKey, contentRevision, sourceKey, seriesKey, episodeKey, pageKey, "
            "stagingRelativePath, destinationRelativePath, sha256, byteCount FROM engine_publications")]
    finally:
        connection.close()
    return {"enginePages": pages, "enginePublications": publications}


class DeviceCacheHost:
    """adb-backed cache host. Explicit filenames only; symlinks are rejected, roots checked."""

    def __init__(self, device, package: str, spool_dir: Path | None = None):
        self.device = device
        self.package = package
        self.spool_dir = Path(spool_dir) if spool_dir is not None else Path(tempfile.gettempdir())

    def _run_as(self, *args, check: bool = False):
        return self.device.run("shell", "run-as", self.package, *args, check=check)

    def _path(self, root: str, name: str) -> str:
        validate_name(root, name)
        return f"{root}/{name}"

    def list(self, root: str) -> list[str]:
        result = self._run_as("ls", "-1", root)
        if result.returncode != 0:
            detail = (result.stdout + result.stderr).decode(errors="replace").lower()
            if "no such file" in detail:
                return []
            raise ColdScopeError(f"cannot list {root}: {detail[-500:]}")
        return [line.strip() for line in result.stdout.decode(errors="replace").splitlines() if line.strip()]

    def read(self, root: str, name: str) -> bytes:
        return self.device.read(self._path(root, name))

    def write(self, root: str, name: str, data: bytes) -> None:
        path = self._path(root, name)
        handle = tempfile.NamedTemporaryFile(delete=False, dir=str(self.spool_dir), suffix=".cold")
        handle.write(data)
        handle.close()
        try:
            self.device.install_file(Path(handle.name), path, "0600", f"cold-{uuid.uuid4().hex[:10]}")
        finally:
            Path(handle.name).unlink(missing_ok=True)

    def delete(self, root: str, name: str) -> None:
        self._run_as("rm", "-f", "--", self._path(root, name), check=True)

    def exists(self, root: str, name: str) -> bool:
        return self._run_as("test", "-e", self._path(root, name)).returncode == 0

    def is_regular(self, root: str, name: str) -> bool:
        path = self._path(root, name)
        return (self._run_as("test", "-f", path).returncode == 0
                and self._run_as("test", "-L", path).returncode != 0)

    def root_safe(self, root: str) -> bool:
        _require(root in ROOT_ORDER, f"unknown cache root: {root!r}")
        parts = root.split("/")
        for index in range(1, len(parts) + 1):
            component = "/".join(parts[:index])
            if self._run_as("test", "-L", component).returncode == 0:
                return False
        return True

    def metadata(self, root: str, name: str) -> dict:
        # The format must arrive as one shell token: adb joins argv with plain spaces, so an
        # unquoted "%a %u %g %s %Y" would split into extra operands on the device shell.
        raw = self.device.checked_text("shell", "run-as", self.package, "stat", "-c'%a %u %g %s %Y'",
                                       self._path(root, name))
        mode, uid, gid, size, mtime = raw.split()
        return {"mode": mode, "uid": int(uid), "gid": int(gid),
                "sizeBytes": int(size), "mtimeEpoch": int(mtime)}

    def set_metadata(self, root: str, name: str, metadata: dict) -> None:
        path = self._path(root, name)
        if metadata.get("mode"):
            self._run_as("chmod", str(metadata["mode"]), path, check=True)
        if metadata.get("uid") is not None and metadata.get("gid") is not None:
            self._run_as("chown", f"{metadata['uid']}:{metadata['gid']}", path, check=True)
        if metadata.get("mtimeEpoch") is not None:
            self._run_as("touch", "-d", f"@{int(metadata['mtimeEpoch'])}", path, check=True)
