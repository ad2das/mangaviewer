"""Synthetic filesystem/SQLite tests for tools/qualify_engine_scroll_cold.py.

No device, no ADB: scenarios run on a temporary local host plus a local SQLite database
mirroring the engine_pages / engine_publications schema with the real storage-relative
`pages/<name>` and `staging/<name>` paths. The adb adapter itself is exercised through a
recording fake device so its exact command contract is covered.
"""

from __future__ import annotations

import sqlite3
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qualify_engine_scroll_cold as cold
from engine_cache_identity import cache_name

SOURCE = "ntk"
SERIES = "/webtoon/843194"
PACKAGE = "ml.melun.mangaview"
REVISION = "b8" * 32
DIGEST = "a1" * 32


class LocalCacheHost:
    def __init__(self, base: Path):
        self.base = Path(base)
        for root in cold.ROOT_ORDER:
            (self.base / root).mkdir(parents=True, exist_ok=True)
        self.mutations: list[tuple[str, str, str]] = []
        self.overrides: dict[str, dict] = {}
        self.unsafe_roots: set[str] = set()

    def _dir(self, root: str) -> Path:
        return self.base / root

    def list(self, root: str) -> list[str]:
        directory = self._dir(root)
        if not directory.is_dir():
            return []
        return [path.name for path in directory.iterdir()]

    def read(self, root: str, name: str) -> bytes:
        return (self._dir(root) / name).read_bytes()

    def write(self, root: str, name: str, data: bytes) -> None:
        self.mutations.append(("write", root, name))
        (self._dir(root) / name).write_bytes(data)

    def delete(self, root: str, name: str) -> None:
        self.mutations.append(("delete", root, name))
        (self._dir(root) / name).unlink()

    def exists(self, root: str, name: str) -> bool:
        return (self._dir(root) / name).exists()

    def is_regular(self, root: str, name: str) -> bool:
        path = self._dir(root) / name
        return path.is_file() and not path.is_symlink()

    def root_safe(self, root: str) -> bool:
        return root not in self.unsafe_roots

    def _default_metadata(self, root: str, name: str) -> dict:
        stat = (self._dir(root) / name).stat()
        return {"mode": f"{stat.st_mode & 0o777:03o}", "uid": int(stat.st_uid),
                "gid": int(stat.st_gid), "sizeBytes": stat.st_size,
                "mtimeEpoch": int(stat.st_mtime)}

    def metadata(self, root: str, name: str) -> dict:
        return dict(self.overrides.get(f"{root}/{name}") or self._default_metadata(root, name))

    def set_metadata(self, root: str, name: str, metadata: dict) -> None:
        self.mutations.append(("set_metadata", root, name))
        self.overrides[f"{root}/{name}"] = dict(metadata)

    def tamper_metadata(self, root: str, name: str, changes: dict) -> None:
        current = self.metadata(root, name)
        current.update(changes)
        self.overrides[f"{root}/{name}"] = current


class FakeResult:
    def __init__(self, returncode: int = 0, stdout: bytes = b"", stderr: bytes = b""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class FakeDevice:
    """Records the exact adapter command contract; no sleep, no adb."""

    def __init__(self):
        self.calls: list[tuple[str, tuple, object]] = []
        self.responses: dict[tuple, object] = {}
        self.read_data: dict[str, bytes] = {}
        self.installs: list[tuple[bytes, str, str, str]] = []

    def run(self, *args, check: bool = True, stdout=None):
        self.calls.append(("run", args, check))
        response = self.responses.get(args)
        if response is None:
            return FakeResult()
        return response(*args) if callable(response) else response

    def checked_text(self, *args):
        self.calls.append(("checked_text", args, None))
        response = self.responses[args]
        if isinstance(response, bytes):
            return response.decode(errors="replace").strip()
        return response.stdout.decode(errors="replace").strip()

    def read(self, name: str) -> bytes:
        self.calls.append(("read", (name,), None))
        return self.read_data[name]

    def install_file(self, local: Path, target: str, mode: str, label: str) -> None:
        self.calls.append(("install", (str(local), target, mode, label), None))
        self.installs.append((Path(local).read_bytes(), target, mode, label))


def page_identity(episode: str, page: str) -> dict:
    return {"sourceId": SOURCE, "seriesKey": SERIES, "episodeKey": episode, "pageKey": page}


def page_file_name(episode: str, page: str, revision: str = REVISION, digest: str = DIGEST) -> str:
    return cache_name(page_identity(episode, page), revision, digest)


def plan_export(episode: str, next_episode: str | None, pages: list[str]) -> dict:
    return {
        "episodeIdentity": {"sourceId": SOURCE, "seriesKey": SERIES, "episodeKey": episode},
        "contentRevision": REVISION,
        "nextEpisode": None if next_episode is None else
        {"sourceId": SOURCE, "seriesKey": SERIES, "episodeKey": next_episode},
        "pages": [{"ordinal": index, "pageIdentity": page_identity(episode, page),
                   "sourceRecord": f"page:{index}"} for index, page in enumerate(pages)],
    }


def staging_name() -> str:
    return f"{uuid.uuid4()}.part"


def page_row(episode: str, page: str, revision: str = REVISION, digest: str = DIGEST,
             relative_path: str | None = None, cache_key: str | None = None) -> dict:
    identity = page_identity(episode, page)
    name = cache_name(identity, revision, digest)
    return {"cacheKey": cache_key or cold.cache_key(identity), "contentRevision": revision,
            "sourceKey": SOURCE, "seriesKey": SERIES, "episodeKey": episode, "pageKey": page,
            "relativePath": relative_path or f"pages/{name}", "sha256": digest, "byteCount": 10}


def publication_row(episode: str, page: str, staging: str, revision: str = REVISION,
                    digest: str = DIGEST) -> dict:
    identity = page_identity(episode, page)
    name = cache_name(identity, revision, digest)
    return {"publicationId": f"pub-{page}", "cacheKey": cold.cache_key(identity),
            "contentRevision": revision, "sourceKey": SOURCE, "seriesKey": SERIES,
            "episodeKey": episode, "pageKey": page, "stagingRelativePath": f"staging/{staging}",
            "destinationRelativePath": f"pages/{name}", "sha256": digest, "byteCount": 10}


def make_database(path: Path, page_rows: list[dict], publication_rows: list[dict]) -> Path:
    connection = sqlite3.connect(path)
    try:
        connection.execute(
            "CREATE TABLE engine_pages (cacheKey TEXT, contentRevision TEXT, sourceKey TEXT, "
            "seriesKey TEXT, episodeKey TEXT, pageKey TEXT, relativePath TEXT, byteCount INTEGER, "
            "sha256 TEXT, mediaType TEXT, widthPx INTEGER, heightPx INTEGER, "
            "createdAtEpochMillis INTEGER, lastAccessEpochMillis INTEGER, PRIMARY KEY (cacheKey, contentRevision))")
        connection.execute(
            "CREATE TABLE engine_publications (publicationId TEXT PRIMARY KEY, cacheKey TEXT, "
            "contentRevision TEXT, sourceKey TEXT, seriesKey TEXT, episodeKey TEXT, pageKey TEXT, "
            "stagingRelativePath TEXT, destinationRelativePath TEXT, byteCount INTEGER, sha256 TEXT, "
            "mediaType TEXT, widthPx INTEGER, heightPx INTEGER, createdAtEpochMillis INTEGER)")
        for row in page_rows:
            connection.execute(
                "INSERT INTO engine_pages VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (row["cacheKey"], row.get("contentRevision", REVISION), row["sourceKey"], row["seriesKey"],
                 row["episodeKey"], row["pageKey"], row["relativePath"], row.get("byteCount", 10),
                 row.get("sha256", DIGEST), "image/webp", 10, 10, 0, 0))
        for row in publication_rows:
            connection.execute(
                "INSERT INTO engine_publications VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (row["publicationId"], row["cacheKey"], row.get("contentRevision", REVISION),
                 row["sourceKey"], row["seriesKey"], row["episodeKey"], row["pageKey"],
                 row["stagingRelativePath"], row["destinationRelativePath"], row.get("byteCount", 10),
                 row.get("sha256", DIGEST), "image/webp", 10, 10, 0))
        connection.commit()
    finally:
        connection.close()
    return path


def requested(episode: str = "ep-b") -> dict:
    return {"sourceId": SOURCE, "seriesKey": SERIES, "episodeKey": episode}


class ColdScopeTest(unittest.TestCase):
    def test_chain_follows_explicit_next_episode_only(self) -> None:
        plans = [
            plan_export("ep-b", "ep-c", ["p1"]),
            plan_export("ep-c", "ep-d", ["p1"]),
            plan_export("ep-d", "ep-e", ["p1"]),
            plan_export("unrelated", "other", ["p1"]),
            plan_export("ep-a", "ep-b", ["p1"]),
        ]
        scope = cold.derive_scope(requested(), plans)
        self.assertEqual(sorted(scope["scopeEpisodes"]), ["ep-b", "ep-c", "ep-d", "ep-e"])
        self.assertNotIn("ep-a", scope["scopeEpisodes"])
        self.assertNotIn("unrelated", scope["scopeEpisodes"])
        self.assertEqual(scope["chainLinks"][-1]["nextEpisodeKey"], "ep-e")
        self.assertFalse(scope["chainLinks"][-1]["planExportPresent"])

    def test_chain_requires_matching_series(self) -> None:
        plan = plan_export("ep-b", "ep-c", ["p1"])
        plan["nextEpisode"]["seriesKey"] = "/webtoon/other"
        with self.assertRaises(cold.ColdScopeError):
            cold.derive_scope(requested(), [plan])

    def test_plan_page_identity_must_match_source_series_and_episode(self) -> None:
        for field, value in (("seriesKey", "/webtoon/other"), ("sourceId", "other"),
                             ("episodeKey", "other")):
            plan = plan_export("ep-b", None, ["p1"])
            plan["pages"][0]["pageIdentity"][field] = value
            with self.assertRaises(cold.ColdScopeError):
                cold.derive_scope(requested(), [plan])

    def test_cache_key_reuses_engine_cache_identity(self) -> None:
        identity = page_identity("ep-b", "p0000")
        expected = cache_name(identity, REVISION, DIGEST).split("-", 1)[0]
        self.assertEqual(cold.cache_key(identity), expected)
        plan_name = cold.plan_file_name({"sourceId": SOURCE, "seriesKey": SERIES, "episodeKey": "ep-b"})
        self.assertEqual(plan_name, cold.cache_key(
            {"sourceId": SOURCE, "seriesKey": SERIES, "episodeKey": "ep-b",
             "pageKey": cold.PLAN_PAGE_KEY}) + ".plan")

    def test_scope_digest_is_deterministic_and_sensitive(self) -> None:
        plans = [plan_export("ep-b", "ep-c", ["p1"])]
        first = cold.derive_scope(requested(), plans)
        second = cold.derive_scope(requested(), plans)
        self.assertEqual(first["scopeSha256"], second["scopeSha256"])
        changed = cold.derive_scope(requested(), [plan_export("ep-b", "ep-c", ["p1", "p2"])])
        self.assertNotEqual(first["scopeSha256"], changed["scopeSha256"])
        self.assertEqual(first["scopeSha256"], cold.scope_digest(first))

    def test_storage_relative_database_paths_translate_to_fixed_roots(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            destination = page_file_name("ep-b", "p1")
            staging = staging_name()
            database = make_database(root / "viewer.db", [page_row("ep-b", "p1")],
                                     [publication_row("ep-b", "p1", staging)])
            rows = cold.read_database_rows(database)
            scope = cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])], database_rows=rows)
            key = cold.cache_key(page_identity("ep-b", "p1"))
            self.assertIn(key, scope["pageCacheKeys"])
            self.assertEqual(scope["journalFiles"], [destination])
            self.assertEqual(scope["stagingFiles"], [staging])
            self.assertEqual(scope["reconciliations"]["databasePageFiles"], [destination])
            host = LocalCacheHost(root / "device")
            host.write(cold.PAGES_ROOT, destination, b"body")
            host.write(cold.STAGING_ROOT, staging, b"part")
            isolated = cold.isolate_scope(host, scope)
            self.assertEqual(isolated["deleted"][cold.PAGES_ROOT], [destination])
            self.assertEqual(isolated["deleted"][cold.STAGING_ROOT], [staging])

    def test_database_row_must_match_identity_key_and_digest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            identity = page_identity("ep-b", "p1")
            with self.assertRaises(cold.ColdScopeError):
                database = make_database(root / "a.db", [page_row("ep-b", "p1", cache_key="0" * 64)], [])
                cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])],
                                  database_rows=cold.read_database_rows(database))
            with self.assertRaises(cold.ColdScopeError):
                wrong = page_row("ep-b", "p1",
                                 relative_path=f"pages/{cold.cache_key(identity)}-{'0' * 64}-{'b' * 64}.page")
                database = make_database(root / "b.db", [wrong], [])
                cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])],
                                  database_rows=cold.read_database_rows(database))
            with self.assertRaises(cold.ColdScopeError):
                wrong = page_row("ep-b", "p1", relative_path=f"app_engine_pages_v1/{page_file_name('ep-b', 'p1')}")
                database = make_database(root / "c.db", [wrong], [])
                cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])],
                                  database_rows=cold.read_database_rows(database))
            with self.assertRaises(cold.ColdScopeError):
                wrong = publication_row("ep-b", "p1", staging_name())
                wrong["destinationRelativePath"] = wrong["destinationRelativePath"].replace(
                    "pages/", "staging/", 1)
                database = make_database(root / "d.db", [], [wrong])
                cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])],
                                  database_rows=cold.read_database_rows(database))

    def test_binding_cache_file_must_match_revision(self) -> None:
        binding = {"pageIdentity": page_identity("ep-b", "p1"), "contentRevision": REVISION,
                   "cacheFile": f"{cold.cache_key(page_identity('ep-b', 'p1'))}-{'0' * 64}-{DIGEST}.page"}
        with self.assertRaises(cold.ColdScopeError):
            cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])], bindings=[binding])

    def test_backup_isolate_and_restore_preserve_unrelated(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            host = LocalCacheHost(root / "device")
            backup = root / "backup"
            scope = cold.derive_scope(requested(), [plan_export("ep-b", "ep-c", ["p1"])])
            scope_page = page_file_name("ep-b", "p1")
            unrelated_page = page_file_name("other", "p9")
            plan_name = scope["planFiles"][0]
            staging = staging_name()
            host.write(cold.PAGES_ROOT, scope_page, b"scope-body")
            host.write(cold.PAGES_ROOT, unrelated_page, b"unrelated-body")
            host.write(cold.PLANS_ROOT, plan_name, b"plan-body")
            host.write(cold.STAGING_ROOT, staging, b"part-body")
            baseline = cold.backup_cache(host, backup)
            self.assertEqual(baseline["entries"], 4)
            self.assertEqual(baseline["inventorySha256"], cold.capture_inventory(host)["inventorySha256"])
            receipt = cold.restore_cache_baseline(host, backup, baseline)
            self.assertTrue(receipt["globalBaselineExact"])
            isolated = cold.isolate_scope(host, scope)
            self.assertEqual(isolated["deletedCount"], 2)
            self.assertEqual(cold.scope_inventory(host, scope)["scopedEntriesAbsent"], True)
            self.assertTrue(host.exists(cold.PAGES_ROOT, unrelated_page))
            self.assertTrue(host.exists(cold.STAGING_ROOT, staging))
            host.write(cold.PAGES_ROOT, page_file_name("ep-c", "p1"), b"run-created")
            host.write(cold.STAGING_ROOT, staging_name(), b"run-staging")
            restore = cold.restore_cache_baseline(host, backup, baseline)
            self.assertTrue(restore["globalBaselineExact"])
            self.assertEqual(len(restore["removedRunCreated"][cold.PAGES_ROOT]), 1)
            self.assertEqual(len(restore["removedRunCreated"][cold.STAGING_ROOT]), 1)
            self.assertTrue(host.exists(cold.PAGES_ROOT, scope_page))
            self.assertTrue(host.exists(cold.PLANS_ROOT, plan_name))
            proof = cold.verify_scope_restored(host, baseline, scope)
            self.assertTrue(proof["scopeRestorationVerified"])

    def test_orphan_deleted_unrelated_original_is_restored(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            host = LocalCacheHost(root / "device")
            backup = root / "backup"
            unrelated = page_file_name("other", "p1")
            host.write(cold.PAGES_ROOT, unrelated, b"keep-me")
            baseline = cold.backup_cache(host, backup)
            host.delete(cold.PAGES_ROOT, unrelated)
            self.assertFalse(host.exists(cold.PAGES_ROOT, unrelated))
            cold.restore_cache_baseline(host, backup, baseline)
            self.assertEqual(host.read(cold.PAGES_ROOT, unrelated), b"keep-me")
            self.assertTrue(cold.verify_baseline(host, baseline)["globalBaselineExact"])

    def test_metadata_is_restored_for_unchanged_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            host = LocalCacheHost(root / "device")
            backup = root / "backup"
            name = page_file_name("other", "p1")
            host.write(cold.PAGES_ROOT, name, b"original")
            baseline = cold.backup_cache(host, backup)
            original_metadata = baseline["roots"][cold.PAGES_ROOT]["entries"][0]["metadata"]
            host.tamper_metadata(cold.PAGES_ROOT, name, {"mode": "777", "mtimeEpoch": 1, "uid": 12345})
            self.assertNotEqual(host.metadata(cold.PAGES_ROOT, name), original_metadata)
            receipt = cold.restore_cache_baseline(host, backup, baseline)
            self.assertEqual(receipt["unchanged"], [{"root": cold.PAGES_ROOT, "name": name}])
            self.assertEqual(host.metadata(cold.PAGES_ROOT, name), original_metadata)
            self.assertTrue(cold.verify_baseline(host, baseline)["globalBaselineExact"])

    def test_restore_preflights_backup_before_any_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            host = LocalCacheHost(root / "device")
            backup = root / "backup"
            good = page_file_name("other", "p1")
            host.write(cold.PAGES_ROOT, good, b"original")
            baseline = cold.backup_cache(host, backup)
            (backup / cold.PAGES_ROOT.replace("/", "__") / good).unlink()
            host.write(cold.PAGES_ROOT, page_file_name("other", "p2"), b"run-created")
            host.mutations.clear()
            with self.assertRaises(cold.ColdScopeError):
                cold.restore_cache_baseline(host, backup, baseline)
            self.assertEqual(host.mutations, [])
            self.assertTrue(host.exists(cold.PAGES_ROOT, page_file_name("other", "p2")))

    def test_symlinked_root_rejected_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            host = LocalCacheHost(root / "device")
            backup = root / "backup"
            name = page_file_name("other", "p1")
            host.write(cold.PAGES_ROOT, name, b"original")
            baseline = cold.backup_cache(host, backup)
            host.unsafe_roots.add(cold.STAGING_ROOT)
            host.mutations.clear()
            with self.assertRaises(cold.ColdScopeError):
                cold.restore_cache_baseline(host, backup, baseline)
            with self.assertRaises(cold.ColdScopeError):
                cold.isolate_scope(host, cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])]))
            self.assertEqual(host.mutations, [])

    def test_tampered_backup_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            host = LocalCacheHost(root / "device")
            backup = root / "backup"
            name = page_file_name("other", "p1")
            host.write(cold.PAGES_ROOT, name, b"original")
            baseline = cold.backup_cache(host, backup)
            host.delete(cold.PAGES_ROOT, name)
            tampered = backup / cold.PAGES_ROOT.replace("/", "__") / name
            tampered.write_bytes(b"tampered")
            host.mutations.clear()
            with self.assertRaises(cold.ColdScopeError):
                cold.restore_cache_baseline(host, backup, baseline)
            self.assertEqual(host.mutations, [])

    def test_unsafe_names_are_rejected(self) -> None:
        for name in ("../evil", "a/b", "..", "UPPER.page", "0.part"):
            with self.assertRaises(cold.ColdScopeError):
                cold.validate_name(cold.PAGES_ROOT, name)
        for relative_path in ("../pages/x.page", "pages/../x.page", "other/x.page",
                              "pages/x.page", "pages\\x.page", "/pages/x.page"):
            with self.assertRaises(cold.ColdScopeError):
                cold.storage_name(relative_path, "pages")

        class UnsafeHost:
            def list(self, root: str) -> list[str]:
                return ["../evil"]

        with self.assertRaises(cold.ColdScopeError):
            cold.list_root(UnsafeHost(), cold.PAGES_ROOT)

    def test_scope_inventory_records_absence_then_presence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            host = LocalCacheHost(Path(tmp))
            scope = cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])])
            absent = cold.scope_inventory(host, scope)
            self.assertTrue(absent["scopedEntriesAbsent"])
            self.assertEqual(absent["scopedCacheEntriesPresent"], 0)
            self.assertEqual(absent["absentPlanFiles"], scope["planFiles"])
            host.write(cold.PAGES_ROOT, page_file_name("ep-b", "p1"), b"body")
            present = cold.scope_inventory(host, scope)
            self.assertFalse(present["scopedEntriesAbsent"])
            self.assertEqual(present["scopedCacheEntriesPresent"], 1)

    def test_relative_paths_outside_roots_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            database = make_database(Path(tmp) / "viewer.db", [page_row(
                "ep-b", "p1", relative_path="app_engine_episode_plans_v1/some.plan")], [])
            rows = cold.read_database_rows(database)
            with self.assertRaises(cold.ColdScopeError):
                cold.derive_scope(requested(), [plan_export("ep-b", None, ["p1"])], database_rows=rows)

    def test_device_cache_host_command_contract(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            device = FakeDevice()
            host = cold.DeviceCacheHost(device, PACKAGE, spool_dir=Path(tmp))
            root = cold.PAGES_ROOT
            name = page_file_name("ep-b", "p1")
            path = f"{root}/{name}"

            device.responses[("shell", "run-as", PACKAGE, "ls", "-1", root)] = FakeResult(0, b"one\ntwo\n")
            self.assertEqual(host.list(root), ["one", "two"])
            device.responses[("shell", "run-as", PACKAGE, "ls", "-1", root)] = FakeResult(
                1, b"", b"ls: app_engine_pages_v1/pages: No such file or directory")
            self.assertEqual(host.list(root), [])

            device.responses[("shell", "run-as", PACKAGE, "test", "-f", path)] = FakeResult(0)
            device.responses[("shell", "run-as", PACKAGE, "test", "-L", path)] = FakeResult(0)
            self.assertFalse(host.is_regular(root, name))
            device.responses[("shell", "run-as", PACKAGE, "test", "-L", path)] = FakeResult(1)
            self.assertTrue(host.is_regular(root, name))

            host.delete(root, name)
            self.assertIn(("run", ("shell", "run-as", PACKAGE, "rm", "-f", "--", path), True), device.calls)
            before = len(device.calls)
            with self.assertRaises(cold.ColdScopeError):
                host.delete(root, "../evil")
            self.assertEqual(len(device.calls), before)

            device.responses[("shell", "run-as", PACKAGE, "test", "-L", "app_engine_pages_v1")] = FakeResult(1)
            device.responses[("shell", "run-as", PACKAGE, "test", "-L", f"{root}")] = FakeResult(0)
            self.assertFalse(host.root_safe(root))
            device.responses[("shell", "run-as", PACKAGE, "test", "-L", f"{root}")] = FakeResult(1)
            self.assertTrue(host.root_safe(root))

            stat_args = ("shell", "run-as", PACKAGE, "stat", "-c'%a %u %g %s %Y'", path)
            device.responses[stat_args] = FakeResult(0, b"600 10001 10001 123 1700000000")
            self.assertEqual(host.metadata(root, name),
                             {"mode": "600", "uid": 10001, "gid": 10001,
                              "sizeBytes": 123, "mtimeEpoch": 1700000000})
            host.set_metadata(root, name, {"mode": "600", "uid": 10001, "gid": 10001,
                                           "mtimeEpoch": 1700000000})
            for expected in (("shell", "run-as", PACKAGE, "chmod", "600", path),
                             ("shell", "run-as", PACKAGE, "chown", "10001:10001", path),
                             ("shell", "run-as", PACKAGE, "touch", "-d", "@1700000000", path)):
                self.assertIn(("run", expected, True), device.calls)

            host.write(root, name, b"payload")
            self.assertEqual(device.installs[0][0], b"payload")
            self.assertEqual(device.installs[0][1], path)
            self.assertEqual(device.installs[0][2], "0600")
            self.assertEqual(list(Path(tmp).iterdir()), [])
            device.read_data[path] = b"payload"
            self.assertEqual(host.read(root, name), b"payload")


if __name__ == "__main__":
    unittest.main()
