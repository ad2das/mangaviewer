#!/usr/bin/env python3
"""Bulk-adb drop-in for qualify_engine_scroll_cold: same receipts, no per-file adb.

The stock DeviceCacheHost issues one `adb` subprocess per file (read/metadata/
is_regular/delete). On this host that is ~10k processes per scoped run, and it
dominates the wall clock of every qualification attempt. This module keeps the
exact same host contract and the exact same receipt shapes, but resolves the
hot paths with device-side scripts:

  scan(root)          -> one `run-as sh` per root: name, type, sha256, stat
  delete_many(root)   -> one `run-as sh` per root reading a pushed name list
  pull_tar(root)      -> one `exec-out run-as tar` stream per root
  push_tar(root)      -> one `run-as tar -xf -` stream per root
  set_metadata_many   -> one `run-as sh` per root for chmod/touch fix-ups

Every scan validates names against the same patterns and rejects symlinks and
non-regular entries exactly like list_root; every mutation is an explicit
validated filename. Function names, orderings, error strings, and JSON are
kept byte-compatible with the stock module so receipts stay comparable.
"""

from __future__ import annotations

import base64
import io
import json
import subprocess
import tarfile
import tempfile
import uuid
from pathlib import Path

from qualify_engine_scroll_cold import (  # noqa: F401 - re-exported drop-in surface
    ColdScopeError,
    PAGES_ROOT,
    PLANS_ROOT,
    ROOT_ORDER,
    ROOT_PATTERNS,
    STAGING_ROOT,
    _preflight_backup,
    _require,
    canonical_scope,
    derive_scope,
    plan_file_name,
    read_database_rows,
    roots_digest,
    scope_digest,
    sha256_hex,
    storage_name,
    validate_name,
    verify_root_paths,
)

SCAN_SCRIPT = r"""
root="$1"
cd "$root" 2>/dev/null || exit 0
for f in *; do
  [ -e "$f" ] || [ -L "$f" ] || continue
  if [ -f "$f" ] && [ ! -L "$f" ]; then
    stat_line=$(stat -c '%a %u %g %s %Y' "$f")
    sha_line=$(sha256sum "$f")
    printf 'F %s %s %s\n' "$f" "${sha_line%% *}" "$stat_line"
  else
    printf 'B %s\n' "$f"
  fi
done
"""

DELETE_SCRIPT = r"""
cd "$1" 2>/dev/null || exit 0
while IFS= read -r n; do
  [ -n "$n" ] || continue
  rm -f -- "$n"
done < "$2"
"""

META_SCRIPT = r"""
cd "$1" 2>/dev/null || exit 0
while IFS=$(printf '\t') read -r n mode epoch; do
  [ -n "$n" ] || continue
  chmod "$mode" -- "$n"
  touch -d "@$epoch" -- "$n"
done < "$2"
"""

REMOTE_SCRIPT_DIR = "/data/local/tmp"


class BulkCacheHost:
    """adb-backed cache host with the stock interface plus bulk primitives."""

    def __init__(self, device, package: str, spool_dir: Path | None = None):
        self.device = device
        self.package = package
        self.spool_dir = Path(spool_dir) if spool_dir is not None else Path(tempfile.gettempdir())
        self._scripts: dict[str, str] = {}

    # -- transport helpers -------------------------------------------------

    def _install_script(self, key: str, text: str) -> str:
        if key not in self._scripts:
            payload = base64.b64encode(text.encode("utf-8")).decode("ascii")
            remote = f"{REMOTE_SCRIPT_DIR}/mv-bulk-{uuid.uuid4().hex[:10]}.sh"
            command = (f"echo {payload} | base64 -d > {remote} && chmod 644 {remote}")
            self.device.run("shell", command, check=True)
            self._scripts[key] = remote
        return self._scripts[key]

    def _exec_script(self, key: str, *arguments: str):
        remote = self._install_script(key, {"scan": SCAN_SCRIPT, "delete": DELETE_SCRIPT,
                                            "meta": META_SCRIPT}[key])
        return self.device.run("shell", "run-as", self.package, "sh", remote, *arguments,
                               check=False)

    def _push_list(self, names: list[str]) -> str:
        handle = tempfile.NamedTemporaryFile(delete=False, dir=str(self.spool_dir), suffix=".list")
        handle.write(("\n".join(names) + "\n").encode("utf-8"))
        handle.close()
        remote = f"{REMOTE_SCRIPT_DIR}/mv-list-{uuid.uuid4().hex[:10]}"
        try:
            self.device.run("push", handle.name, remote, check=True)
        finally:
            Path(handle.name).unlink(missing_ok=True)
        return remote

    def _remove_remote(self, *paths: str) -> None:
        for path in paths:
            if path:
                self.device.run("shell", "rm", "-f", path, check=False)

    def _path(self, root: str, name: str) -> str:
        validate_name(root, name)
        return f"{root}/{name}"

    # -- bulk primitives ---------------------------------------------------

    def scan(self, root: str) -> tuple[list[str], dict[str, dict]]:
        """Return sorted names and per-name {sha256, metadata} or {'bad': reason}."""
        result = self._exec_script("scan", root)
        if result.returncode != 0:
            detail = (result.stdout + result.stderr).decode(errors="replace").lower()
            if "no such file" in detail:
                return [], {}
            raise ColdScopeError(f"cannot scan {root}: {detail[-500:]}")
        names: list[str] = []
        entries: dict[str, dict] = {}
        for line in result.stdout.decode(errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            fields = line.split()
            if fields[0] == "F" and len(fields) == 8:
                name, sha = fields[1], fields[2]
                mode, uid, gid, size, mtime = fields[3:8]
                validate_name(root, name)
                if name in entries:
                    raise ColdScopeError(f"duplicate names in {root}: {name!r}")
                names.append(name)
                entries[name] = {
                    "sha256": sha,
                    "metadata": {"mode": mode, "uid": int(uid), "gid": int(gid),
                                 "sizeBytes": int(size), "mtimeEpoch": int(mtime)},
                }
            elif fields[0] == "B" and len(fields) == 2:
                name = fields[1]
                validate_name(root, name)
                if name in entries:
                    raise ColdScopeError(f"duplicate names in {root}: {name!r}")
                names.append(name)
                entries[name] = {"bad": "cache entry is not a regular file"}
            else:
                raise ColdScopeError(f"unparsable scan line for {root}: {line!r}")
        names.sort()
        return names, entries

    def delete_many(self, root: str, names: list[str]) -> None:
        if not names:
            return
        remote = self._push_list(names)
        try:
            result = self._exec_script("delete", root, remote)
        finally:
            self._remove_remote(remote)
        if result.returncode != 0:
            raise ColdScopeError(f"bulk delete failed for {root}: "
                                 f"{result.stderr.decode(errors='replace')[-300:]}")

    def pull_tar(self, root: str) -> bytes:
        result = self.device.run("exec-out", "run-as", self.package, "tar", "-cf", "-",
                                 "-C", root, ".", check=True)
        return result.stdout

    def push_tar(self, root: str, members: list[dict]) -> None:
        """members: [{'name','data','mode','mtimeEpoch','uid','gid'}]"""
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as archive:
            for member in members:
                info = tarfile.TarInfo(name=member["name"])
                info.size = len(member["data"])
                info.mode = int(str(member.get("mode") or "600"), 8)
                info.uid = int(member.get("uid") or 0)
                info.gid = int(member.get("gid") or 0)
                info.mtime = int(member.get("mtimeEpoch") or 0)
                archive.addfile(info, io.BytesIO(member["data"]))
        result = subprocess.run(
            [self.device.adb, "shell", "run-as", self.package, "tar", "-xf", "-", "-C", root],
            input=buffer.getvalue(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise ColdScopeError(f"bulk restore failed for {root}: "
                                 f"{result.stderr.decode(errors='replace')[-300:]}")

    def set_metadata_many(self, root: str, entries: list[tuple[str, str, int]]) -> None:
        if not entries:
            return
        handle = tempfile.NamedTemporaryFile(delete=False, dir=str(self.spool_dir), suffix=".meta")
        handle.write(("\n".join(f"{name}\t{mode}\t{epoch}" for name, mode, epoch in entries) + "\n")
                     .encode("utf-8"))
        handle.close()
        remote = f"{REMOTE_SCRIPT_DIR}/mv-meta-{uuid.uuid4().hex[:10]}"
        try:
            self.device.run("push", handle.name, remote, check=True)
            result = self._exec_script("meta", root, remote)
        finally:
            Path(handle.name).unlink(missing_ok=True)
            self._remove_remote(remote)
        if result.returncode != 0:
            raise ColdScopeError(f"bulk metadata restore failed for {root}: "
                                 f"{result.stderr.decode(errors='replace')[-300:]}")

    # -- stock per-file interface (kept for compatibility) -----------------

    def list(self, root: str) -> list[str]:
        names, _ = self.scan(root)
        return names

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
        self.delete_many(root, [name])

    def exists(self, root: str, name: str) -> bool:
        return self.device.run("shell", "run-as", self.package, "test", "-e",
                               self._path(root, name), check=False).returncode == 0

    def is_regular(self, root: str, name: str) -> bool:
        names, entries = self.scan(root)
        return name in entries and "bad" not in entries[name]

    def root_safe(self, root: str) -> bool:
        _require(root in ROOT_ORDER, f"unknown cache root: {root!r}")
        parts = root.split("/")
        for index in range(1, len(parts) + 1):
            component = "/".join(parts[:index])
            result = self.device.run("shell", "run-as", self.package, "test", "-L",
                                     component, check=False)
            if result.returncode == 0:
                return False
        return True

    def metadata(self, root: str, name: str) -> dict:
        raw = self.device.checked_text("shell", "run-as", self.package,
                                       "stat", "-c'%a %u %g %s %Y'", self._path(root, name))
        mode, uid, gid, size, mtime = raw.split()
        return {"mode": mode, "uid": int(uid), "gid": int(gid),
                "sizeBytes": int(size), "mtimeEpoch": int(mtime)}

    def set_metadata(self, root: str, name: str, metadata: dict) -> None:
        self.set_metadata_many(root, [(name, metadata["mode"], metadata["mtimeEpoch"])])


# -- drop-in fast implementations of the hot cold functions ----------------

def _scanned_entries(host: BulkCacheHost, root: str) -> tuple[list[str], dict[str, dict]]:
    names, entries = host.scan(root)
    for name in names:
        if "bad" in entries[name]:
            raise ColdScopeError(f"cache entry is not a regular file: {root}/{name}")
    return names, entries


def _entry_from_scan(root: str, name: str, scanned: dict) -> dict:
    entry = {"root": root, "name": validate_name(root, name),
             "sha256": scanned[name]["sha256"], "sizeBytes": scanned[name]["metadata"]["sizeBytes"]}
    entry["metadata"] = scanned[name]["metadata"]
    return entry


def capture_inventory(host) -> dict:
    roots = {}
    for root in ROOT_ORDER:
        names, scanned = _scanned_entries(host, root)
        roots[root] = {"entries": [_entry_from_scan(root, name, scanned) for name in names]}
    return {"roots": roots, "inventorySha256": roots_digest(roots)}


def backup_cache(host, backup_root: Path) -> dict:
    backup_root.mkdir(parents=True, exist_ok=True)
    roots = {}
    for root in ROOT_ORDER:
        target_dir = backup_root / root.replace("/", "__")
        target_dir.mkdir(parents=True, exist_ok=True)
        names, scanned = _scanned_entries(host, root)
        blob = host.pull_tar(root) if names else b""
        extracted: dict[str, bytes] = {}
        if blob:
            with tarfile.open(fileobj=io.BytesIO(blob), mode="r:") as archive:
                for member in archive.getmembers():
                    if not member.isfile():
                        continue
                    name = member.name.removeprefix("./")
                    if name in scanned:
                        extracted[name] = archive.extractfile(member).read()
        entries = []
        for name in names:
            data = extracted.get(name, b"")
            digest = sha256_hex(data)
            _require(digest == scanned[name]["sha256"], f"backup read verification failed: {name}")
            target = target_dir / validate_name(root, name)
            target.write_bytes(data)
            _require(sha256_hex(target.read_bytes()) == digest, f"backup write verification failed: {name}")
            entries.append({"root": root, "name": name, "sha256": digest, "sizeBytes": len(data),
                            "metadata": scanned[name]["metadata"]})
        roots[root] = {"entries": entries}
    manifest = {"roots": roots, "entries": sum(len(roots[root]["entries"]) for root in roots)}
    manifest["inventorySha256"] = roots_digest(roots)
    manifest["cacheBaselineSha256"] = manifest["inventorySha256"]
    return manifest


def verify_baseline(host, baseline: dict) -> dict:
    for root in ROOT_ORDER:
        expected = {entry["name"]: entry for entry in baseline["roots"][root]["entries"]}
        names, scanned = _scanned_entries(host, root)
        _require(names == sorted(expected), f"cache root {root} does not match the baseline set")
        for name in names:
            entry = expected[name]
            _require(scanned[name]["sha256"] == entry["sha256"],
                     f"cache file {root}/{name} does not match the baseline hash")
            mismatch = _metadata_mismatch_scanned(scanned[name]["metadata"], entry.get("metadata") or {})
            _require(mismatch is None, f"cache file {root}/{name} metadata changed: {mismatch}")
    return {"globalBaselineExact": True,
            "entries": sum(len(baseline["roots"][root]["entries"]) for root in ROOT_ORDER),
            "inventorySha256": baseline["inventorySha256"]}


def _metadata_mismatch_scanned(current: dict, expected: dict) -> str | None:
    if not expected:
        return None
    if not current:
        return "host metadata unavailable"
    for key in ("mode", "uid", "gid", "mtimeEpoch", "sizeBytes"):
        if key in expected and current.get(key) != expected[key]:
            return f"{key} {current.get(key)!r} != {expected[key]!r}"
    return None


def restore_cache_baseline(host, backup_root: Path, baseline: dict) -> dict:
    verify_root_paths(host)
    _preflight_backup(backup_root, baseline)
    removed: dict[str, list[str]] = {root: [] for root in ROOT_ORDER}
    restored: list[dict] = []
    unchanged: list[dict] = []
    for root in ROOT_ORDER:
        expected = {entry["name"]: entry for entry in baseline["roots"][root]["entries"]}
        names, scanned = _scanned_entries(host, root)
        current = set(names)
        extra = [name for name in sorted(current) if name not in expected]
        host.delete_many(root, extra)
        removed[root].extend(extra)
        to_push: list[dict] = []
        to_fix: list[tuple[str, str, int]] = []
        for name, entry in sorted(expected.items()):
            stored = backup_root / root.replace("/", "__") / name
            data = stored.read_bytes()
            same_bytes = name in current and scanned[name]["sha256"] == entry["sha256"]
            metadata = entry.get("metadata") or {}
            if not same_bytes:
                to_push.append({"name": name, "data": data, "mode": metadata.get("mode") or "600",
                                "mtimeEpoch": metadata.get("mtimeEpoch") or 0,
                                "uid": metadata.get("uid"), "gid": metadata.get("gid")})
                restored.append({"root": root, "name": name})
            else:
                unchanged.append({"root": root, "name": name})
                mismatch = _metadata_mismatch_scanned(scanned[name]["metadata"], metadata)
                if mismatch is not None:
                    to_fix.append((name, metadata["mode"], metadata["mtimeEpoch"]))
        if to_push:
            host.push_tar(root, to_push)
        if to_fix:
            host.set_metadata_many(root, to_fix)
        names_after, scanned_after = _scanned_entries(host, root)
        _require(names_after == sorted(expected), f"cache root {root} does not match the baseline set")
        for name, entry in sorted(expected.items()):
            _require(scanned_after[name]["sha256"] == entry["sha256"],
                     f"restored hash mismatch: {root}/{name}")
            mismatch = _metadata_mismatch_scanned(scanned_after[name]["metadata"],
                                                  entry.get("metadata") or {})
            _require(mismatch is None, f"restored metadata mismatch: {root}/{name}: {mismatch}")
    proof = verify_baseline(host, baseline)
    proof.update({"removedRunCreated": removed, "restored": restored, "unchanged": unchanged})
    return proof


def isolate_scope(host, scope: dict) -> dict:
    verify_root_paths(host)
    page_keys = set(scope["pageCacheKeys"])
    deleted = {PAGES_ROOT: [], PLANS_ROOT: [], STAGING_ROOT: []}
    for root, keys in ((PAGES_ROOT, page_keys), (PLANS_ROOT, set(scope["planFiles"])),
                       (STAGING_ROOT, set(scope["stagingFiles"]))):
        names, _ = _scanned_entries(host, root)
        targets = [name for name in names
                   if (name.split("-", 1)[0] if root == PAGES_ROOT else name) in keys]
        host.delete_many(root, targets)
        deleted[root].extend(targets)
    return {"deleted": deleted,
            "deletedCount": sum(len(value) for value in deleted.values())}


def _present_scoped(host, scope: dict) -> tuple[list[dict], list[str], list[str], list[str]]:
    entries: list[dict] = []
    page_keys = set(scope["pageCacheKeys"])
    names, scanned = _scanned_entries(host, PAGES_ROOT)
    present_keys: set[str] = set()
    for name in names:
        key = name.split("-", 1)[0]
        if key in page_keys:
            entries.append(_entry_from_scan(PAGES_ROOT, name, scanned))
            present_keys.add(key)
    plan_names, plan_scanned = _scanned_entries(host, PLANS_ROOT)
    staging_names, staging_scanned = _scanned_entries(host, STAGING_ROOT)
    plans = set(plan_names)
    staging = set(staging_names)
    absent_plans, absent_staging = [], []
    for name in scope["planFiles"]:
        if name in plans:
            entries.append(_entry_from_scan(PLANS_ROOT, name, plan_scanned))
        else:
            absent_plans.append(name)
    for name in scope["stagingFiles"]:
        if name in staging:
            entries.append(_entry_from_scan(STAGING_ROOT, name, staging_scanned))
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


DeviceCacheHost = BulkCacheHost
