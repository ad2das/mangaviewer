#!/usr/bin/env python3
"""Correlate viewer buffer evidence with independent SurfaceFlinger trace events.

This is an evidence verifier, not a performance benchmark. It never substitutes
Latch, swap completion, a navigation checkpoint, or a policy boolean for display.
PresentFenceSignaled itself can originate from an HWC timestamp fallback. Until
an independent display-source calibration is available the CLI deliberately
reports requiresCalibration=true and cannot award a physical-display pass.
The explicitly authorized OBSERVABLE_RENDER_V1 profile can accept the other
evidence while retaining that physical limitation as metadata.

Trace rows and application records are indexed on disk. Perfetto query results
are paged, JSONL/TSV files are streamed, and source-row unions are reduced in order.
No third-party dependency is needed for the synthetic tests; the CLI needs the
official `perfetto` Python package and its trace processor binary.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import sqlite3
import sys
import tempfile
from fractions import Fraction
from pathlib import Path
from typing import Any, Iterable

PACKAGE = "ml.melun.mangaview"
LAYER = re.compile(r"^SurfaceView\[ml\.melun\.mangaview/ml\.melun\.mangaview(?:\.[A-Za-z0-9_$]+)*\.ViewerActivity\]\(BLAST\)#\d+$")
SWAP = re.compile(r"^viewer_swap:(\d+):(\d+):(\d+)$")
QUEUE_WINDOW_NS = 1_000_000_000  # Correlation only; NEVER a timing allowance.
BATCH_SIZE = 4096
NS_PER_MS = 1_000_000
GATES = {
    "first-content-ms": (4000.0, True),
    "native-render-p95-ms": (16.0, False),
    "native-render-gap-ms": (100.0, False),
    "window-frame-gap-ms": (100.0, False),
    "motion-gap-ms": (100.0, False),
    "motion-missed-ratio": (0.01, False),
    "surface-gap-ms": (100.0, False),
    "surface-response-gap-ms": (100.0, False),
    "surface-tail-gap-ms": (100.0, False),
    "surface-missed-ratio": (0.01, False),
    "loading-gap-ms": (100.0, False),
}
REQUIRED_LOCAL_GATES = {
    "native-render-p95-ms", "native-render-gap-ms", "motion-gap-ms", "motion-missed-ratio",
}
OBSERVABLE_ACCEPTANCE_MODE = "OBSERVABLE_RENDER_V1"
UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING = "UNAVAILABLE_REPORTED"
OBSERVABLE_ACCEPTED_RESULT = "OBSERVABLE_RENDER_V1_ACCEPTED"
OBSERVABLE_REJECTED_RESULT = "OBSERVABLE_RENDER_V1_REJECTED"
PRESENT_EVENT_ORIGIN = "PRESENT_FENCE_OR_HWC_VSYNC_FALLBACK"
PRESENT_EVENT_ROLE = "COMPOSITION_PROXY_NOT_PHYSICAL_SCANOUT"
OBSERVABLE_QUALIFICATION_CLAIM = (
    "OBSERVABLE_RENDER_V1; physical presentation timing is unavailable"
)
PHYSICAL_PRESENTATION_PROXY_GATES = {
    "first-content-ms", "surface-gap-ms", "surface-response-gap-ms",
    "surface-tail-gap-ms", "surface-missed-ratio",
}
PHYSICAL_METRIC_FIELDS = (
    "firstPresentationNanos", "firstContentMillis", "surfaceGapMillis",
    "surfaceResponseGapMillis", "surfaceTailGapMillis", "surfaceMissedRatio",
)
PAGE_FIELDS = ("sourceId", "seriesKey", "episodeKey", "pageKey")
TERMINAL_KINDS = {"CANCELLED", "DROPPED", "CONTEXT_LOST"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8-sig") as stream:
        return json.load(stream)


def json_safe(value: Any) -> Any:
    """Retain invalid numeric evidence without emitting nonstandard JSON tokens."""
    if isinstance(value, float) and not math.isfinite(value):
        return {"invalidNonFiniteNumber": repr(value)}
    if isinstance(value, dict):
        return {key: json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    return value


def is_observable_policy(policy: Any) -> bool:
    return (isinstance(policy, dict)
            and policy.get("acceptanceMode") == OBSERVABLE_ACCEPTANCE_MODE
            and policy.get("physicalPresentationTiming") == UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING)


def unavailable_physical_metrics() -> dict[str, None]:
    """Physical scanout metrics are unavailable; never represent them as zero."""
    return {field: None for field in PHYSICAL_METRIC_FIELDS}


def positive_int(value: Any, field: str, *, zero: bool = False) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        raise ValueError(f"{field} is not an integer")
    if isinstance(value, str) and not re.fullmatch(r"\d+", value):
        raise ValueError(f"{field} is not an unsigned integer")
    result = int(value)
    if result < (0 if zero else 1):
        raise ValueError(f"{field} is outside its valid range")
    return result


def page_key(record: dict[str, Any]) -> str:
    values = [record.get(field) for field in PAGE_FIELDS]
    if any(not isinstance(value, str) or not value for value in values):
        raise ValueError("PageId requires four nonempty string fields")
    return json.dumps(values, ensure_ascii=False, separators=(",", ":"))


class IdentitySnapshot:
    """Host-frozen source identities, independent of native presentation records.

    The caller supplies a detached SHA256, never a hash read from the native
    evidence. The snapshot binds candidatePath/candidateSha256 and samples with
    sampleKey, sourceId, seriesKey, requestedEpisodeKeys, episodeOrder {path,
    sha256}, and manifests [{episodeKey,path,sha256}]. Episode-order artifacts
    contain sourceId/seriesKey/episodeKeysInReadingOrder; manifest artifacts
    contain sourceId/seriesKey/episodeKey/pages [{pageKey}]. The host must obtain
    these from authoritative source responses and freeze them independently;
    the verifier does not generate them from observed output or prefetch pages.
    """

    def __init__(self, path: Path, expected_sha256: str) -> None:
        self.root = path.resolve().parent
        self.sha256 = expected_sha256.lower()
        document = self._read(path.resolve(), expected_sha256)
        if document.get("schemaVersion") != 1:
            raise ValueError("Unsupported source identity snapshot schema")
        self.candidate_path = self._path(document.get("candidatePath"))
        if self.candidate_path.name not in ("candidate.json", "attempt.json"):
            raise ValueError("Identity snapshot must bind candidate.json or attempt.json")
        self.candidate_sha256 = document.get("candidateSha256")
        self.candidate = self._read(self.candidate_path, self.candidate_sha256)
        self.samples: dict[str, dict[str, Any]] = {}
        samples = document.get("samples")
        if not isinstance(samples, list) or not samples:
            raise ValueError("Source identity snapshot requires explicit samples")
        for sample in samples:
            key = sample.get("sampleKey") if isinstance(sample, dict) else None
            if not isinstance(key, str) or not key or key in self.samples:
                raise ValueError("Missing or duplicate identity snapshot sampleKey")
            self.samples[key] = sample

    def _path(self, value: Any) -> Path:
        if not isinstance(value, str) or not value or Path(value).is_absolute():
            raise ValueError("Identity artifact path must be relative to the snapshot")
        result = (self.root / value).resolve()
        if not result.is_relative_to(self.root):
            raise ValueError("Identity artifact path escapes the frozen snapshot")
        return result

    @staticmethod
    def _read(path: Path, digest: Any) -> dict[str, Any]:
        if not isinstance(digest, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", digest):
            raise ValueError("Identity artifact lacks a detached SHA256 anchor")
        with path.open("rb") as stream:
            data = stream.read(64 * 1024 * 1024 + 1)
        if len(data) > 64 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != digest.lower():
            raise ValueError("Frozen source identity artifact hash or size mismatch")
        document = json.loads(data.decode("utf-8-sig"))
        if not isinstance(document, dict):
            raise ValueError("Source identity artifact must be an object")
        return document

    def permit_neighbors(self, index: TraceIndex, directory: Path, collection: dict[str, Any],
                         expected: list[dict[str, Any]]) -> dict[str, Any]:
        if not directory.resolve().is_relative_to(self.candidate_path.parent):
            raise ValueError("Source identity snapshot candidate belongs to another evidence root")
        self._read(self.candidate_path, self.candidate_sha256)
        sample = self.samples.get(collection.get("sampleKey"))
        if sample is None:
            raise ValueError("Source identity snapshot does not bind this sampleKey")
        source, series = sample.get("sourceId"), sample.get("seriesKey")
        if any(record.get("sourceId") != source or record.get("seriesKey") != series for record in expected):
            raise ValueError("Source identity snapshot disagrees with requested source/series")
        requested = sample.get("requestedEpisodeKeys")
        if (not isinstance(requested, list) or not requested
                or any(not isinstance(key, str) or not key for key in requested)
                or len(set(requested)) != len(requested)):
            raise ValueError("Identity snapshot requested episode chain is malformed")
        for field, value in (("source", source), ("series", series), ("episode", requested[0])):
            if field in self.candidate and self.candidate[field] != value:
                raise ValueError("Source identity snapshot disagrees with frozen diagnostic candidate")
        order_ref = sample.get("episodeOrder")
        if not isinstance(order_ref, dict):
            raise ValueError("Identity snapshot lacks authoritative episode-order reference")
        order_doc = self._read(self._path(order_ref.get("path")), order_ref.get("sha256"))
        order = order_doc.get("episodeKeysInReadingOrder")
        if (order_doc.get("sourceId") != source or order_doc.get("seriesKey") != series
                or not isinstance(order, list) or not order
                or any(not isinstance(key, str) or not key for key in order)
                or len(set(order)) != len(order) or requested[0] not in order):
            raise ValueError("Authoritative episode order is malformed or belongs to another series")
        start = order.index(requested[0])
        stop = start + len(requested)
        if order[start:stop] != requested:
            raise ValueError("Requested episodes are not consecutive in authoritative reading order")
        neighbors = order[max(0, start - 1):start] + order[stop:stop + 1]
        references = sample.get("manifests")
        if not isinstance(references, list):
            raise ValueError("Identity snapshot requires explicit source manifest references")
        pages: dict[str, list[str]] = {}
        for reference in references:
            if not isinstance(reference, dict):
                raise ValueError("Malformed source manifest reference")
            episode = reference.get("episodeKey")
            if episode in pages or episode not in requested + neighbors:
                raise ValueError("Duplicate or nonadjacent episode manifest in source identity scope")
            manifest = self._read(self._path(reference.get("path")), reference.get("sha256"))
            if (manifest.get("sourceId") != source or manifest.get("seriesKey") != series
                    or manifest.get("episodeKey") != episode or not isinstance(manifest.get("pages"), list)
                    or not manifest["pages"]):
                raise ValueError("Source manifest identity or page list is invalid")
            keys = []
            for record in manifest["pages"]:
                identity = {"sourceId": source, "seriesKey": series, "episodeKey": episode}
                if not isinstance(record, dict) or any(field in record and record[field] != value for field, value in identity.items()):
                    raise ValueError("Source manifest page belongs to another source/series/episode")
                keys.append(page_key({**record, **identity}))
            if len(keys) != len(set(keys)):
                raise ValueError("Source manifest contains duplicate PageIds")
            pages[episode] = keys
        if [page for episode in requested for page in pages.get(episode, [])] != [page_key(record) for record in expected]:
            raise ValueError("Expected pages differ from the independently anchored requested manifests")
        for episode in neighbors:
            index.db.executemany("INSERT INTO permitted_neighbors VALUES(?)", ((page,) for page in pages.get(episode, [])))
        return {"snapshotSha256": self.sha256, "candidateSha256": self.candidate_sha256,
                "permittedNeighborEpisodes": [episode for episode in neighbors if episode in pages],
                "requestedCoverageCreditFromNeighbors": 0}


class Findings:
    """Bound report size without dropping the number or category of failures."""

    def __init__(self) -> None:
        self.items: list[dict[str, Any]] = []
        self.counts: dict[str, int] = {}

    def add(self, gate: str, reason: str, **details: Any) -> None:
        self.counts[gate] = self.counts.get(gate, 0) + 1
        if len(self.items) < 200:
            self.items.append({"gate": gate, "reason": reason, **details})

    def has(self, *gates: str) -> bool:
        return any(self.counts.get(gate, 0) for gate in gates)


class TraceIndex:
    def __init__(self, database: str = ":memory:") -> None:
        self.db = sqlite3.connect(database)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA temp_store=FILE;
            PRAGMA cache_size=-16384;
            CREATE TABLE swaps(id INTEGER PRIMARY KEY, pid INTEGER, process TEXT,
                ts INTEGER, dur INTEGER, token INTEGER, frame INTEGER, native INTEGER,parent_ts INTEGER,parent_dur INTEGER);
            CREATE INDEX swap_identity ON swaps(token,frame,native);
            CREATE TABLE events(id INTEGER PRIMARY KEY, ts INTEGER, name TEXT,
                layer TEXT, frame INTEGER);
            CREATE INDEX event_identity ON events(frame,name,ts);
            CREATE INDEX event_layer ON events(layer,frame,name);
            CREATE INDEX event_time ON events(name,ts);
            CREATE TABLE native(renderer INTEGER, token INTEGER, generation INTEGER,
                frame INTEGER, submitted INTEGER, latency INTEGER, has_regions INTEGER DEFAULT 0,
                full_visual INTEGER,geometry INTEGER,input_revision INTEGER,scroll_offset INTEGER,scroll_cause TEXT,full_actual INTEGER,
                PRIMARY KEY(renderer,token,generation,frame));
            CREATE TABLE terminals(renderer INTEGER,token INTEGER,generation INTEGER,frame INTEGER,
                submitted INTEGER,latency INTEGER,kind TEXT,geometry INTEGER,input_revision INTEGER,
                presentation_count INTEGER,region_count INTEGER,
                PRIMARY KEY(renderer,token,generation));
            CREATE TABLE regions(renderer INTEGER,token INTEGER,generation INTEGER,frame INTEGER,
                page TEXT,top INTEGER,bottom INTEGER,height INTEGER,verified INTEGER,
                screen_top INTEGER,screen_bottom INTEGER,viewport_height INTEGER,viewport_width INTEGER,
                UNIQUE(renderer,token,generation,frame,page,top,bottom));
            CREATE INDEX regions_frame ON regions(renderer,token,generation,frame);
            CREATE INDEX regions_page ON regions(page,top,bottom);
            CREATE TABLE matches(renderer INTEGER,token INTEGER,generation INTEGER,frame INTEGER,
                swap_id INTEGER,layer TEXT,present_id INTEGER,present_ts INTEGER,
                PRIMARY KEY(renderer,token,generation,frame));
            CREATE UNIQUE INDEX match_present ON matches(present_id);
            CREATE UNIQUE INDEX match_swap ON matches(swap_id);
            CREATE TABLE gestures(start INTEGER,end INTEGER,direction TEXT);
            CREATE TABLE expected(page TEXT PRIMARY KEY,ordinal INTEGER);
            CREATE TABLE permitted_neighbors(page TEXT PRIMARY KEY);
        """)
        self.findings = Findings()
        self.trace_bounds: tuple[int, int] | None = None
        self.trace_sha256: str | None = None

    def close(self) -> None:
        self.db.close()

    def reset_series(self) -> None:
        for table in ("native", "terminals", "regions", "matches", "gestures", "expected", "permitted_neighbors"):
            self.db.execute(f"DELETE FROM {table}")

    def add_swap(self, record: dict[str, Any]) -> None:
        match = SWAP.fullmatch(str(record["name"]))
        if match is None:
            self.findings.add("trace", "Malformed viewer_swap label", name=record["name"])
            return
        token, frame, native = map(int, match.groups())
        if min(token, frame, native) <= 0 or record.get("pid") is None or record["dur"] < 0:
            self.findings.add("trace", "Incomplete or invalid swap clock bridge", name=record["name"])
            return
        if (record.get("parent_name") != "viewer_clock"
                or record.get("parent_track_id") != record.get("track_id")
                or record.get("track_id") is None or record.get("parent_ts") is None
                or record.get("parent_dur") is None or record["parent_dur"] < 0
                or record["parent_ts"] > record["ts"]
                or record["parent_ts"] + record["parent_dur"] < record["ts"] + record["dur"]):
            self.findings.add("trace", "Swap lacks a complete directly nested viewer_clock bracket", name=record["name"])
            return
        process = record.get("process_name")
        # Android specialization can leave Perfetto's process name as zygote64.
        # Store the actual PID; ownership is checked against each collection's
        # independently exported PID/package identity before any rows are granted.
        self.db.execute("INSERT INTO swaps VALUES(?,?,?,?,?,?,?,?,?,?)", (
            record["id"], record["pid"], process, record["ts"], record["dur"], token, frame, native,
            record["parent_ts"], record["parent_dur"],
        ))

    def add_event(self, record: dict[str, Any]) -> None:
        if record["name"] not in ("Queue", "Latch", "PresentFenceSignaled"):
            return
        if not LAYER.fullmatch(str(record.get("layer_name"))):
            return
        self.db.execute("INSERT INTO events VALUES(?,?,?,?,?)", (
            record["id"], record["ts"], record["name"], record["layer_name"], record["frame_number"],
        ))

    def load_perfetto(self, trace: Path) -> None:
        from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

        self.trace_sha256 = sha256(trace)
        config = TraceProcessorConfig(load_timeout=120)
        with TraceProcessor(trace=str(trace), config=config) as processor:
            for record in processor.query("SELECT name,severity,value FROM stats WHERE value != 0 AND "
                                          "(severity='data_loss' OR name GLOB '*overrun*' OR "
                                          "name GLOB '*dropped*' OR name GLOB '*packet_loss*')"):
                self.findings.add("trace", "Trace reports data loss", **vars(record))
            bounds = list(processor.query("SELECT start_ts,end_ts FROM trace_bounds"))
            if len(bounds) == 1:
                self.trace_bounds = (bounds[0].start_ts, bounds[0].end_ts)
            else:
                self.findings.add("trace", "Trace bounds unavailable")
            self._paged(processor, "SELECT s.id,s.ts,s.dur,s.name,s.track_id,p.pid,p.name AS process_name,"
                        "clock.ts AS parent_ts,clock.dur AS parent_dur,clock.name AS parent_name,clock.track_id AS parent_track_id "
                        "FROM slice s JOIN thread_track tt ON tt.id=s.track_id "
                        "JOIN thread t ON t.utid=tt.utid JOIN process p ON p.upid=t.upid "
                        "LEFT JOIN slice clock ON clock.id=s.parent_id "
                        "WHERE s.name GLOB 'viewer_swap:*' AND s.id > {after} "
                        "ORDER BY s.id LIMIT {limit}", self.add_swap)
            self._paged(processor, "SELECT id,ts,name,layer_name,frame_number FROM frame_slice "
                        "WHERE instr(layer_name,'ViewerActivity](BLAST)') > 0 AND "
                        "name IN ('Queue','Latch','PresentFenceSignaled') AND id > {after} "
                        "ORDER BY id LIMIT {limit}", self.add_event)
        self.db.commit()

    @staticmethod
    def _paged(processor: Any, query: str, accept: Any) -> None:
        after = -1
        while True:
            count = 0
            for row in processor.query(query.format(after=after, limit=BATCH_SIZE)):
                accept(vars(row))
                after = row.id
                count += 1
            if count < BATCH_SIZE:
                break


def add_native(index: TraceIndex, record: dict[str, Any], findings: Findings,
               *, region: bool) -> tuple[int, int, int, int] | None:
    renderer = positive_int(record.get("rendererIdentity" if region else "renderer"), "renderer")
    token = positive_int(record.get("token"), "token")
    generation = positive_int(record.get("generation"), "generation", zero=True)
    frame = positive_int(record.get("bufferFrameId"), "bufferFrameId", zero=True)
    submitted = positive_int(record.get("submittedAtNanos"), "submittedAtNanos")
    latency = positive_int(record.get("renderLatencyNanos"), "renderLatencyNanos", zero=True)
    geometry = positive_int(record.get("geometryRevision"), "geometryRevision", zero=True)
    input_revision = positive_int(record.get("userInputRevision"), "userInputRevision", zero=True)
    terminal_key = (renderer, token, generation)
    terminal = index.db.execute("SELECT * FROM terminals WHERE renderer=? AND token=? AND generation=?", terminal_key).fetchone()
    if record.get("timestampKind") in TERMINAL_KINDS:
        # A terminated timestamp request can still refer to an already swapped
        # frame. Preserve it for a separate trace audit; never manufacture a
        # buffer identity or turn its scene metadata into displayed image rows.
        if positive_int(record.get("presentedNanos"), "terminal presentedNanos", zero=True) != 0:
            raise ValueError("Terminal evidence cannot claim a presentation timestamp")
        values = (frame, submitted, latency, record["timestampKind"], geometry, input_revision)
        if index.db.execute("SELECT 1 FROM native WHERE renderer=? AND token=? AND generation=? LIMIT 1", terminal_key).fetchone():
            findings.add("terminal", "Terminal and native presentation claim the same request", renderer=renderer, token=token)
        if terminal is None:
            index.db.execute("INSERT INTO terminals VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                             (*terminal_key, *values, int(not region), int(region)))
        elif tuple(terminal[field] for field in ("frame", "submitted", "latency", "kind", "geometry", "input_revision")) != values:
            findings.add("terminal", "Conflicting terminal request metadata", renderer=renderer, token=token)
        elif not region and terminal["presentation_count"]:
            findings.add("terminal", "Duplicate terminal presentation record", renderer=renderer, token=token)
        else:
            index.db.execute("UPDATE terminals SET presentation_count=presentation_count+?,region_count=region_count+? "
                             "WHERE renderer=? AND token=? AND generation=?", (int(not region), int(region), *terminal_key))
        return None
    if frame == 0:
        raise ValueError("Zero bufferFrameId requires an explicit terminal timestamp kind")
    if terminal is not None:
        findings.add("terminal", "Native presentation and terminal claim the same request", renderer=renderer, token=token)
    scroll_offset = None if region else positive_int(record.get("scrollOffsetUnits"), "scrollOffsetUnits", zero=True)
    scroll_cause = None if region else record.get("scrollCause")
    if not region and scroll_cause not in ("USER_INPUT", "EPISODE_NAVIGATION", "GEOMETRY_CORRECTION"):
        raise ValueError("Invalid scrollCause")
    key = (renderer, token, generation, frame)
    previous = index.db.execute("SELECT * FROM native WHERE renderer=? AND token=? AND generation=? AND frame=?", key).fetchone()
    full = None if region else (1 if record.get("fullVisual") == "true" else 0)
    full_actual = None if region else (1 if record.get("fullActual") == "true" else 0)
    if not region and record.get("fullVisual") not in ("true", "false"):
        raise ValueError("fullVisual must be true or false")
    if not region and record.get("fullActual") not in ("true", "false"):
        raise ValueError("fullActual must explicitly represent content versus loading")
    if previous is None:
        index.db.execute("INSERT INTO native VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                         (*key, submitted, latency, int(region), full, geometry, input_revision, scroll_offset, scroll_cause, full_actual))
    elif (previous["submitted"] != submitted or previous["latency"] != latency
          or previous["geometry"] != geometry or previous["input_revision"] != input_revision):
        findings.add("display", "Conflicting native frame metadata", renderer=renderer, token=token, frame=frame)
    elif not region and previous["full_visual"] is not None:
        findings.add("display", "Duplicate native presentation record", renderer=renderer, token=token, frame=frame)
    else:
        index.db.execute("UPDATE native SET has_regions=max(has_regions,?),full_visual=coalesce(full_visual,?),"
                         "scroll_offset=coalesce(scroll_offset,?),scroll_cause=coalesce(scroll_cause,?) "
                         ",full_actual=coalesce(full_actual,?) WHERE renderer=? AND token=? AND generation=? AND frame=?",
                         (int(region), full, scroll_offset, scroll_cause, full_actual, *key))
    return key


def load_series(index: TraceIndex, directory: Path, findings: Findings,
                identities: IdentitySnapshot | None = None) -> dict[str, Any]:
    collection = read_json(directory / "collection.json")
    positive_int(collection.get("processPid"), "processPid")
    if collection.get("packageName") != PACKAGE:
        raise ValueError("Collection packageName does not identify the target app")
    expected = read_json(directory / "expected-pages.json")
    if not isinstance(expected, list) or not expected:
        raise ValueError("Expected pages must be a nonempty ordered array")
    episodes: set[tuple[str, str, str]] = set()
    for ordinal, record in enumerate(expected):
        key = page_key(record)
        episodes.add(tuple(record[field] for field in PAGE_FIELDS[:3]))
        try:
            index.db.execute("INSERT INTO expected VALUES(?,?)", (key, ordinal))
        except sqlite3.IntegrityError:
            findings.add("coverage", "Expected PageId is duplicated", page=json.loads(key))
    if len(episodes) != positive_int(collection.get("requiredEpisodes"), "requiredEpisodes"):
        findings.add("coverage", "Expected episode count differs from collection requirement", count=len(episodes))
    if identities is not None:
        collection["verifiedPageIdentityScope"] = identities.permit_neighbors(index, directory, collection, expected)
    with (directory / "presentation-evidence.tsv").open(encoding="utf-8-sig", newline="") as stream:
        for line, record in enumerate(csv.DictReader(stream, delimiter="\t"), 2):
            try:
                if record.get("kind") == "gesture":
                    start = positive_int(record.get("windowStart"), "windowStart")
                    end = positive_int(record.get("windowEnd"), "windowEnd")
                    if end < start or record.get("direction") not in ("FORWARD", "REVERSE"):
                        raise ValueError("Invalid directed gesture window")
                    index.db.execute("INSERT INTO gestures VALUES(?,?,?)", (start, end, record["direction"]))
                elif record.get("kind") == "presentation":
                    add_native(index, record, findings, region=False)
                else:
                    raise ValueError("Unknown presentation evidence row kind")
            except (ValueError, TypeError, KeyError) as error:
                findings.add("display", str(error), file="presentation-evidence.tsv", line=line)
    with (directory / "presented-regions.jsonl").open(encoding="utf-8-sig") as stream:
        for line, text in enumerate(stream, 1):
            try:
                record = json.loads(text)
                key = add_native(index, record, findings, region=True)
                if key is None:
                    continue  # Retained in terminals, with no row/display credit.
                page = page_key(record)
                top = positive_int(record.get("sourceTopRow"), "sourceTopRow", zero=True)
                bottom = positive_int(record.get("sourceBottomRowExclusive"), "sourceBottomRowExclusive")
                height = positive_int(record.get("sourceHeightRows"), "sourceHeightRows")
                if not top < bottom <= height:
                    raise ValueError("Invalid source row range")
                screen_top = positive_int(record.get("screenTopPx"), "screenTopPx", zero=True)
                screen_bottom = positive_int(record.get("screenBottomPx"), "screenBottomPx")
                viewport_height = positive_int(record.get("viewportHeightPx"), "viewportHeightPx")
                viewport_width = positive_int(record.get("viewportWidthPx"), "viewportWidthPx")
                if not screen_top < screen_bottom <= viewport_height:
                    raise ValueError("Invalid clipped viewport image range")
                if record.get("imageIdentityVerified") is not True:
                    raise ValueError("Image identity was not verified")
                if index.db.execute("SELECT 1 FROM expected WHERE page=? UNION ALL "
                                    "SELECT 1 FROM permitted_neighbors WHERE page=?", (page, page)).fetchone() is None:
                    raise ValueError("Displayed PageId is neither requested nor an independently anchored immediate neighbor")
                index.db.execute("INSERT INTO regions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                 (*key, page, top, bottom, height, 1, screen_top, screen_bottom, viewport_height, viewport_width))
            except (ValueError, TypeError, KeyError, sqlite3.IntegrityError) as error:
                findings.add("coverage", f"Invalid/duplicate image region: {error}", line=line)
    return collection


def match_frames(index: TraceIndex, findings: Findings, collection: dict[str, Any] | None = None) -> tuple[int, int] | None:
    collection = collection or {}
    expected_pid = collection.get("processPid") if collection.get("packageName") == PACKAGE else None
    offsets: list[int] = []  # Intersection endpoints only, never a guessed skew tolerance.
    for native in index.db.execute("SELECT * FROM native ORDER BY submitted,renderer,token"):
        key = tuple(native[field] for field in ("renderer", "token", "generation", "frame"))
        bridges = index.db.execute("SELECT * FROM swaps WHERE token=? AND frame=? AND native BETWEEN ? AND ?", (
            native["token"], native["frame"], native["submitted"], native["submitted"] + native["latency"],
        )).fetchmany(2)
        if len(bridges) != 1:
            findings.add("display", "Missing or ambiguous exact buffer/clock bridge", renderer=key[0], token=key[1], frame=key[3])
            continue
        bridge = bridges[0]
        owned = expected_pid is not None and bridge["pid"] == expected_pid
        if not owned:
            findings.add("display", "Native swap process ownership is unresolved or disagrees with the collected PID",
                         tracePid=bridge["pid"], traceProcessName=bridge["process"], collectedPid=expected_pid)
            continue
        lower = bridge["parent_ts"] - bridge["native"]
        upper = bridge["ts"] - bridge["native"]
        offsets = [max(offsets[0], lower), min(offsets[1], upper)] if offsets else [lower, upper]
        queues = index.db.execute("SELECT * FROM events WHERE name='Queue' AND frame=? AND ts BETWEEN ? AND ?", (
            native["frame"], bridge["ts"], bridge["ts"] + bridge["dur"] + QUEUE_WINDOW_NS,
        )).fetchmany(2)
        if len(queues) != 1:
            findings.add("display", "Missing or ambiguous target BLAST Queue", token=key[1], frame=key[3])
            continue
        queue = queues[0]
        presents = index.db.execute("SELECT * FROM events WHERE name='PresentFenceSignaled' AND layer=? AND frame=?",
                                    (queue["layer"], queue["frame"])).fetchmany(2)
        if len(presents) != 1 or presents[0]["ts"] < queue["ts"]:
            findings.add("display", "Missing, duplicate, or out-of-order actual present event; Latch is insufficient",
                         token=key[1], frame=key[3], layer=queue["layer"])
            continue
        present = presents[0]
        latches = index.db.execute("SELECT ts FROM events WHERE name='Latch' AND layer=? AND frame=?",
                                   (queue["layer"], queue["frame"])).fetchmany(2)
        if len(latches) != 1 or not queue["ts"] <= latches[0]["ts"] <= present["ts"]:
            findings.add("display", "Missing, duplicate, or noncausal Latch metadata", token=key[1], frame=key[3])
            continue
        try:
            index.db.execute("INSERT INTO matches VALUES(?,?,?,?,?,?,?,?)", (*key, bridge["id"], queue["layer"], present["id"], present["ts"]))
        except sqlite3.IntegrityError:
            findings.add("display", "One traced buffer was claimed by multiple native frames", token=key[1], frame=key[3])
        if native["full_visual"] == 0 and native["full_actual"] != 0:
            findings.add("display", "Actually displayed frame contains uncovered viewport area", token=key[1], frame=key[3])
        if not native["has_regions"] and native["full_actual"] != 0:
            findings.add("display", "Actually displayed buffer has no image-region identity evidence", token=key[1], frame=key[3])
    if not offsets:
        findings.add("display", "No native-to-trace clock bridge was established")
        return None
    if offsets[0] > offsets[1]:
        findings.add("display", "Nested viewer_clock intervals have no common clock offset; clock gap or malformed evidence is unresolved",
                     maximumLowerOffsetNanos=offsets[0], minimumUpperOffsetNanos=offsets[1])
        return None
    # The parent trace begins before sampling CLOCK_MONOTONIC; the child begins
    # after that sample. Scheduling delay widens that observed bracket, and only
    # the intersection is used. No uncertainty is added to performance goals.
    return offsets[0], offsets[1]


def check_terminals(index: TraceIndex, collection: dict[str, Any], offsets: tuple[int, int] | None,
                    findings: Findings) -> dict[str, Any]:
    """Prove pre-swap termination by covered trace absence, never by its label."""
    result: dict[str, Any] = {"count": 0, "preSwapConfirmed": 0, "unresolved": 0,
                              "reportedRegionCount": 0, "displayCredit": 0, "records": []}
    for terminal in index.db.execute("SELECT * FROM terminals ORDER BY submitted,renderer,token"):
        result["count"] += 1
        result["reportedRegionCount"] += terminal["region_count"]
        start = terminal["submitted"]
        end = start + terminal["latency"]
        swap_count = index.db.execute("SELECT count(*) FROM swaps WHERE pid=? AND token=? AND native BETWEEN ? AND ?",
                                      (collection.get("processPid"), terminal["token"], start, end)).fetchone()[0]
        complete_trace = (not index.findings.counts and offsets is not None and index.trace_bounds is not None
                          and index.trace_bounds[0] <= start + offsets[0]
                          and index.trace_bounds[1] >= end + offsets[1])
        reason = None
        if not terminal["presentation_count"]:
            reason = "Terminal region metadata has no explicit terminal presentation record"
        elif swap_count:
            reason = "Terminal request has a traced swap; its label cannot establish absence of actual display"
        elif terminal["frame"] != 0:
            reason = "Terminal claims a buffer identity without a trace-backed swap"
        elif not complete_trace:
            reason = "Trace loss, missing clock bridge, or incomplete span prevents proof of pre-swap termination"
        record = {"renderer": terminal["renderer"], "token": terminal["token"], "generation": terminal["generation"],
                  "bufferFrameId": terminal["frame"], "timestampKind": terminal["kind"],
                  "submittedAtNanos": start, "submissionEndAtNanos": end,
                  "reportedRegionCount": terminal["region_count"], "traceSwapCount": swap_count,
                  "preSwapConfirmed": reason is None, "displayCredit": 0}
        if reason is not None:
            result["unresolved"] += 1
            findings.add("terminal", reason, **record)
        else:
            result["preSwapConfirmed"] += 1
        if len(result["records"]) < 200:
            result["records"].append(record)
    result["complete"] = not findings.has("terminal")
    return result


def check_coverage(index: TraceIndex, findings: Findings) -> dict[str, int]:
    covered = 0
    expected_count = 0
    for expected in index.db.execute("SELECT page FROM expected ORDER BY ordinal"):
        expected_count += 1
        cursor = 0
        height = None
        valid = True
        for region in index.db.execute("SELECT r.top,r.bottom,r.height FROM regions r JOIN matches m USING(renderer,token,generation,frame) "
                                       "WHERE r.page=? AND r.verified=1 ORDER BY r.top,r.bottom", (expected["page"],)):
            if height is not None and region["height"] != height:
                valid = False
                findings.add("coverage", "Conflicting source image heights", page=json.loads(expected["page"]))
            height = region["height"]
            if region["top"] > cursor:
                valid = False
                findings.add("coverage", "Source rows were never independently presented", page=json.loads(expected["page"]),
                             missingTopRow=cursor, missingBottomRowExclusive=region["top"])
            cursor = max(cursor, region["bottom"])
        if height is None or cursor != height:
            valid = False
            findings.add("coverage", "Page is missing actual display proof through its final source row",
                         page=json.loads(expected["page"]), coveredThroughRow=cursor, sourceHeightRows=height)
        covered += int(valid)
    return {"expectedPages": expected_count, "fullyCoveredPages": covered}


def check_position(index: TraceIndex, findings: Findings) -> int:
    """Use the geometry captured in the displayed frame, never current telemetry."""
    previous = None
    compared = 0
    for native in index.db.execute("SELECT n.*,m.present_ts FROM native n JOIN matches m USING(renderer,token,generation,frame) "
                                   "ORDER BY m.present_ts,n.renderer,n.token"):
        key = tuple(native[field] for field in ("renderer", "token", "generation", "frame"))
        centers = index.db.execute("SELECT * FROM regions WHERE renderer=? AND token=? AND generation=? AND frame=? "
                                   "AND screen_top*2<=viewport_height AND screen_bottom*2>viewport_height", key).fetchmany(2)
        if len(centers) > 1:
            findings.add("position", "Displayed center image is ambiguous", token=key[1], frame=key[3])
            previous = None
            continue
        center = centers[0] if centers else None
        current = (native, center)
        # No comparison is possible before a visible center image exists. Initial
        # first-content arrival is not itself a position jump.
        if previous is not None:
            prior, prior_center = previous
            if prior["generation"] == native["generation"] and prior["input_revision"] == native["input_revision"]:
                if (prior["geometry"] == native["geometry"] and prior["scroll_offset"] is not None
                        and native["scroll_offset"] is not None and prior["scroll_offset"] != native["scroll_offset"]):
                    findings.add("position", "Global offset changed with unchanged input and geometry, including across pages",
                                 previousToken=prior["token"], token=native["token"], scrollCause=native["scroll_cause"])
                if center is not None and prior_center is not None:
                    compared += 1
                    if center["page"] != prior_center["page"]:
                        findings.add("position", "Center PageId changed without user input", previousToken=prior["token"], token=native["token"])
                    else:
                        def source_position(region: sqlite3.Row) -> tuple[Fraction, Fraction]:
                            rows_per_pixel = Fraction(region["bottom"] - region["top"], region["screen_bottom"] - region["screen_top"])
                            return (region["top"] + (Fraction(region["viewport_height"], 2) - region["screen_top"]) * rows_per_pixel,
                                    rows_per_pixel)

                        before, old_pixel = source_position(prior_center)
                        after, new_pixel = source_position(center)
                        if abs(after - before) > max(old_pixel, new_pixel):
                            findings.add("position", "Center source row moved by more than one rendered pixel without user input",
                                         previousToken=prior["token"], token=native["token"],
                                         sourceRowDisplacement=float(after - before),
                                         previousViewportWidth=prior_center["viewport_width"], viewportWidth=center["viewport_width"])
        previous = current if center is not None else None
    return compared


def evaluate_timing(observation: dict[str, Any], findings: Findings,
                    policy: dict[str, Any]) -> dict[str, Any]:
    gate = observation.get("gate")
    if gate not in GATES:
        findings.add("timing", "Unknown timing gate", observation=observation)
        return {**observation, "withinGoal": False, "passed": False}
    limit, inclusive = GATES[gate]
    value = observation.get("value")
    valid = isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0
    goal = valid and (value <= limit if inclusive else value < limit)
    if observation.get("limit") != limit or observation.get("inclusive") is not inclusive:
        findings.add("timing", "Artifact altered the fixed timing goal", observedGate=gate)
        goal = False
    if not goal:
        candidates = [exception["id"] for exception in policy.get("exceptions", []) if exception.get("gate") == gate]
        findings.add("timing", "Goal unmet; no independently verified exact-sample attribution is available",
                     observedGate=gate, value=value, limit=limit, sampleKey=observation.get("sampleKey"),
                     registeredCandidateExceptions=candidates)
    # A JSON claim such as independentlyVerified=true is never an attribution
    # verifier. Preserve the raw observation even when a candidate is registered.
    return {**observation, "limit": limit, "inclusive": inclusive, "withinGoal": bool(goal),
            "passed": bool(goal), "exceptionApplied": None}


def evaluate_composition_proxy(observation: dict[str, Any], findings: Findings) -> dict[str, Any]:
    """Retain a PFS-derived timing value without deciding a physical gate.

    FrameTracer's PresentFenceSignaled event is useful for correlating a native
    buffer with a compositor-side event, but this verifier has no independent
    scanout provenance.  A proxy value is therefore disclosed with no boolean
    physical timing decision.  An absent or invalid proxy remains a finding;
    unknown timing is never converted to a passing zero.
    """
    gate = observation.get("gate")
    if gate not in GATES:
        findings.add("timing", "Unknown composition proxy timing gate", observation=observation)
        return {**observation, "withinGoal": None, "passed": None,
                "physicalGateDecision": "UNAVAILABLE", "measurementRole": PRESENT_EVENT_ROLE}
    limit, inclusive = GATES[gate]
    value = observation.get("value")
    valid = isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0
    if not valid:
        findings.add("timing", "Composition proxy timing measurement is unavailable",
                     observedGate=gate, value=value, sampleKey=observation.get("sampleKey"))
    return {**observation, "limit": limit, "inclusive": inclusive,
            "withinGoal": None, "passed": None, "exceptionApplied": None,
            "physicalGateDecision": "UNAVAILABLE",
            "measurementSource": "SurfaceFlinger.FrameTracer.PresentFenceSignaled",
            "measurementRole": PRESENT_EVENT_ROLE}


def check_timing(index: TraceIndex, directory: Path, collection: dict[str, Any],
                 offsets: tuple[int, int] | None, findings: Findings,
                 policy: dict[str, Any]) -> list[dict[str, Any]]:
    raw = read_json(directory / "timing-observations.json")
    if not isinstance(raw, list):
        raise ValueError("Timing observations must be an array")
    sample_key = collection["sampleKey"]
    seen: set[str] = set()
    results = []
    for observation in raw:
        gate = observation.get("gate")
        if gate in seen or observation.get("sampleKey") != sample_key:
            findings.add("timing", "Duplicate or cross-sample timing observation", observedGate=gate)
        seen.add(gate)
        if is_observable_policy(policy) and gate in PHYSICAL_PRESENTATION_PROXY_GATES:
            results.append(evaluate_composition_proxy(observation, findings))
        else:
            results.append(evaluate_timing(observation, findings, policy))
    for gate in REQUIRED_LOCAL_GATES - seen:
        findings.add("timing", "Required local timing observation missing", observedGate=gate)
    if offsets is None:
        findings.add("timing", "Actual-display timing cannot be placed on the input clock")
        return results
    start = positive_int(collection.get("startedAtNanos"), "startedAtNanos")
    end = positive_int(collection.get("collectionEndAtNanos", collection.get("completedAtNanos")), "collectionEndAtNanos")
    period = positive_int(collection.get("refreshPeriodNanos"), "refreshPeriodNanos")
    if end <= start:
        raise ValueError("Collection end is not after entry")
    if index.trace_bounds is None or index.trace_bounds[0] > start + offsets[0] or index.trace_bounds[1] < end + offsets[1]:
        findings.add("display", "Trace does not span entry through final evidence harvest")
    for event in index.db.execute("SELECT e.id,e.layer,e.frame,e.ts FROM events e LEFT JOIN matches m ON m.present_id=e.id "
                                  "WHERE e.name='PresentFenceSignaled' AND e.ts BETWEEN ? AND ? AND m.present_id IS NULL",
                                  (start + offsets[0], end + offsets[1])):
        findings.add("display", "Actually displayed buffer is unrepresented by native image evidence",
                     layer=event["layer"], frame=event["frame"], presentedTraceNanos=event["ts"])
    outside = index.db.execute("SELECT count(*) FROM matches WHERE present_ts<? OR present_ts>?",
                               (start + offsets[0], end + offsets[1])).fetchone()[0]
    if outside:
        findings.add("display", "Frame display proof falls outside the collected interval", count=outside)
    first = index.db.execute("SELECT min(m.present_ts) FROM matches m JOIN regions r USING(renderer,token,generation,frame) "
                             "JOIN expected e ON e.page=r.page WHERE r.verified=1").fetchone()[0]
    actual = []
    if first is None:
        findings.add("timing", "First actual image timestamp unavailable")
    else:
        actual.append(("first-content-ms", (first - offsets[0] - start) / NS_PER_MS))
    maximum_gap = response_gap = tail_gap = 0
    missed = rendered = windows = 0
    previous_end = 0
    for gesture in index.db.execute("SELECT * FROM gestures ORDER BY start,end"):
        windows += 1
        if gesture["start"] < start or gesture["end"] > end or gesture["start"] <= previous_end:
            findings.add("timing", "Overlapping gesture or gesture outside the collected interval")
        previous_end = gesture["end"]
        lower = gesture["start"] + offsets[0]
        upper = gesture["end"] + offsets[1]
        first_ts = last_ts = None
        for match in index.db.execute("SELECT DISTINCT present_ts FROM matches WHERE present_ts BETWEEN ? AND ? ORDER BY present_ts", (lower, upper)):
            timestamp = match["present_ts"]
            rendered += 1
            if last_ts is not None:
                gap = timestamp - last_ts
                maximum_gap = max(maximum_gap, gap)
                missed += max(0, (gap + period // 2) // period - 1)
            else:
                first_ts = timestamp
            last_ts = timestamp
        if first_ts is None:
            findings.add("timing", "Actual display evidence misses an entire gesture", startedAtNanos=gesture["start"])
            gap = upper - lower
            response_gap = max(response_gap, gap)
            tail_gap = max(tail_gap, gap)
            missed += max(1, gap // period)
        else:
            response = first_ts - lower
            tail = upper - last_ts
            response_gap = max(response_gap, response)
            tail_gap = max(tail_gap, tail)
            missed += max(0, response // period - 1) + max(0, tail // period)
    if not windows:
        findings.add("timing", "No real gesture windows were recorded")
    actual.extend([
        ("surface-gap-ms", maximum_gap / NS_PER_MS),
        ("surface-response-gap-ms", response_gap / NS_PER_MS),
        ("surface-tail-gap-ms", tail_gap / NS_PER_MS),
        ("surface-missed-ratio", missed / (missed + rendered) if missed + rendered else None),
    ])
    for gate, value in actual:
        limit, inclusive = GATES[gate]
        observation = {"gate": gate, "value": value, "limit": limit, "inclusive": inclusive,
                       "sampleKey": sample_key, "measurementSource": "SurfaceFlinger.FrameTracer",
                       "clockUncertaintyNanos": offsets[1] - offsets[0]}
        if is_observable_policy(policy) and gate in PHYSICAL_PRESENTATION_PROXY_GATES:
            results.append(evaluate_composition_proxy(observation, findings))
        else:
            results.append(evaluate_timing(observation, findings, policy))
    return results


def check_loading(index: TraceIndex, collection: dict[str, Any], offsets: tuple[int, int] | None,
                  findings: Findings, policy: dict[str, Any]) -> dict[str, Any]:
    """A represented loading buffer is evidence, never an omitted image sample."""
    result: dict[str, Any] = {"intervals": [], "intervalCount": 0, "maximumAfterFirstContentMillis": 0.0}
    if offsets is None:
        return result
    first = index.db.execute("SELECT min(m.present_ts) FROM matches m JOIN regions r USING(renderer,token,generation,frame) "
                             "JOIN expected e ON e.page=r.page WHERE r.verified=1").fetchone()[0]
    collection_end = positive_int(collection.get("collectionEndAtNanos", collection.get("completedAtNanos")), "collectionEndAtNanos") + offsets[1]
    pending = None

    def finish(end: int) -> None:
        nonlocal pending
        if pending is None:
            return
        start, first_token, count = pending
        if end < start:
            findings.add("display", "Loading interval has noncausal timestamps")
            return
        after_first = first is not None and end > first
        relevant_start = max(start, first) if after_first else start
        duration = (end - relevant_start) / NS_PER_MS
        record = {"startTraceNanos": start, "endTraceNanos": end, "durationMillis": (end - start) / NS_PER_MS,
                  "firstToken": first_token, "representedBuffers": count, "afterFirstContent": after_first,
                  "attribution": "UNPROVEN", "excludedFromEvidence": False,
                  "measurementSource": "SurfaceFlinger.FrameTracer.PresentFenceSignaled",
                   "measurementRole": PRESENT_EVENT_ROLE}
        if after_first:
            result["maximumAfterFirstContentMillis"] = max(result["maximumAfterFirstContentMillis"], duration)
            record["timingDecision"] = evaluate_timing({"gate": "loading-gap-ms", "value": duration,
                                                        "limit": 100.0, "inclusive": False,
                                                        "sampleKey": collection["sampleKey"],
                                                        "measurementSource": "SurfaceFlinger.FrameTracer.PresentFenceSignaled",
                                                        "measurementRole": PRESENT_EVENT_ROLE},
                                                       findings, policy)
        if len(result["intervals"]) < 200:
            result["intervals"].append(record)
        result["intervalCount"] += 1
        pending = None

    for frame in index.db.execute("SELECT n.token,n.full_actual,m.present_ts FROM native n JOIN matches m "
                                   "USING(renderer,token,generation,frame) ORDER BY m.present_ts"):
        if frame["full_actual"] == 0:
            pending = (pending[0], pending[1], pending[2] + 1) if pending else (frame["present_ts"], frame["token"], 1)
        else:
            finish(frame["present_ts"])
    finish(collection_end)
    return result


def verify_series(index: TraceIndex, directory: Path, policy: dict[str, Any] | None = None,
                  identities: IdentitySnapshot | None = None) -> dict[str, Any]:
    policy = policy if policy is not None else {"exceptions": []}
    observable_profile = is_observable_policy(policy)
    index.reset_series()
    findings = Findings()
    collection: dict[str, Any] = {}
    coverage: dict[str, int] = {}
    timing: list[dict[str, Any]] = []
    offsets = None
    position_comparisons = 0
    loading: dict[str, Any] = {}
    terminals: dict[str, Any] = {}
    try:
        collection = load_series(index, directory, findings, identities)
        if not isinstance(collection.get("sampleKey"), str) or not collection["sampleKey"]:
            raise ValueError("Collection sampleKey is missing")
        offsets = match_frames(index, findings, collection)
        terminals = check_terminals(index, collection, offsets, findings)
        coverage = check_coverage(index, findings)
        position_comparisons = check_position(index, findings)
        timing = check_timing(index, directory, collection, offsets, findings, policy)
        loading = check_loading(index, collection, offsets, findings, policy)
    except (OSError, ValueError, TypeError, KeyError, sqlite3.Error) as error:
        findings.add("artifact", f"Incomplete or malformed collection: {error}")
    artifact_ok = not findings.has("artifact")
    correlation = artifact_ok and not findings.has("display", "terminal") and not index.findings.counts
    rows_complete = artifact_ok and not findings.has("coverage") and coverage.get("expectedPages", 0) > 0
    timing_complete = artifact_ok and not findings.has("timing") and bool(timing)
    if not observable_profile:
        findings.add("calibration", "FrameTracer PresentFenceSignaled does not identify real present-fence versus HWC fallback provenance; independent display-source calibration is required")
    no_auto_jump_complete = artifact_ok and not findings.has("position", "coverage", "display", "terminal")
    observable_complete = observable_profile and not findings.items and not index.findings.counts
    result = (OBSERVABLE_ACCEPTED_RESULT if observable_complete else OBSERVABLE_REJECTED_RESULT
              if observable_profile else "PHYSICAL_RENDER_REJECTED")
    return {
        "sampleKey": collection.get("sampleKey", directory.name), "evidenceDirectory": str(directory),
        "passed": bool(observable_complete), "displayEvidenceComplete": False,
        "observableEvidenceComplete": bool(observable_complete), "result": result,
        "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE if observable_profile else None,
        "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING if observable_profile else None,
        "exactPhysicalPresentationTimeVerified": False,
        "displayCorrelationComplete": correlation, "coverageComplete": rows_complete,
        "timingComplete": timing_complete, "physicalTimingComplete": False,
        "requiresCalibration": True, "noAutoJumpEvidenceComplete": no_auto_jump_complete,
        "measurementUncertainty": {"presentEventOrigin": PRESENT_EVENT_ORIGIN,
                                   "presentEventRole": PRESENT_EVENT_ROLE,
                                   "physicalPresentationTiming": (UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING
                                                                   if observable_profile else "INDEPENDENT_CALIBRATION_REQUIRED"),
                                   "clockMethod": "Intersection of directly nested viewer_clock parent-begin / viewer_swap child-begin brackets",
                                   "nativeToTraceOffsetNanos": list(offsets) if offsets else None},
        "physicalMetrics": unavailable_physical_metrics(),
        "metrics": {**coverage, "nativeFrames": index.db.execute("SELECT count(*) FROM native").fetchone()[0],
                    "matchedFrames": index.db.execute("SELECT count(*) FROM matches").fetchone()[0],
                    "permittedNeighborPages": index.db.execute("SELECT count(*) FROM permitted_neighbors").fetchone()[0],
                    "observedNeighborRegions": index.db.execute("SELECT count(*) FROM regions r JOIN permitted_neighbors p ON p.page=r.page").fetchone()[0],
                    "matchedNeighborRegions": index.db.execute("SELECT count(*) FROM regions r JOIN permitted_neighbors p ON p.page=r.page "
                                                               "JOIN matches m USING(renderer,token,generation,frame)").fetchone()[0],
                    "stableInputCenterComparisons": position_comparisons},
        "rawTimingObservations": timing, "violations": findings.items, "violationCounts": findings.counts,
        "loadingObservations": loading,
        "terminalObservations": terminals,
        "pageIdentityScope": collection.get("verifiedPageIdentityScope", {"snapshotSha256": None,
                             "permittedNeighborEpisodes": [], "requestedCoverageCreditFromNeighbors": 0}),
    }


def validate_policy(policy: Any) -> dict[str, Any]:
    if not isinstance(policy, dict) or not isinstance(policy.get("exceptions"), list):
        raise ValueError("Policy must contain an explicit exceptions array")
    has_mode = "acceptanceMode" in policy
    has_physical_timing = "physicalPresentationTiming" in policy
    if has_mode or has_physical_timing:
        if (policy.get("acceptanceMode") != OBSERVABLE_ACCEPTANCE_MODE
                or policy.get("physicalPresentationTiming") != UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING):
            raise ValueError("Policy acceptanceMode and physicalPresentationTiming must be the exact authorized pair")
    seen = set()
    for exception in policy["exceptions"]:
        if not isinstance(exception, dict):
            raise ValueError("Malformed timing exception")
        identity = exception.get("id")
        maximum = exception.get("maximumValue")
        if (not isinstance(identity, str) or not identity or identity in seen or exception.get("gate") not in GATES
                or exception.get("cause") not in ("EXTERNAL", "DEVICE")
                or not re.fullmatch(r"[a-fA-F0-9]{64}", str(exception.get("evidenceSha256")))
                or not isinstance(maximum, (int, float)) or isinstance(maximum, bool)
                or not math.isfinite(maximum) or maximum < 0
                or not policy.get("deviceFingerprint")
                or exception.get("deviceFingerprint") != policy["deviceFingerprint"]):
            raise ValueError("Invalid, unbound, or duplicate timing exception")
        seen.add(identity)
    return policy


def verify(index: TraceIndex, evidence_root: Path, policy: dict[str, Any],
           identities: IdentitySnapshot | None = None) -> dict[str, Any]:
    observable_profile = is_observable_policy(policy)
    directories = sorted({path.parent for path in evidence_root.rglob("collection.json")})
    reports = [verify_series(index, directory, policy, identities) for directory in directories]
    if not directories:
        index.findings.add("artifact", "No per-series collection.json artifacts were found")
    sample_keys = [report["sampleKey"] for report in reports]
    if len(sample_keys) != len(set(sample_keys)):
        index.findings.add("artifact", "Duplicate collection sampleKey")
    observable_complete = (observable_profile and bool(reports) and not index.findings.counts
                           and all(report["observableEvidenceComplete"] for report in reports))
    result = (OBSERVABLE_ACCEPTED_RESULT if observable_complete else OBSERVABLE_REJECTED_RESULT
              if observable_profile else "PHYSICAL_RENDER_REJECTED")
    return {
        "schemaVersion": 1, "passed": bool(observable_complete), "displayEvidenceComplete": False,
        "observableEvidenceComplete": bool(observable_complete), "result": result,
        "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE if observable_profile else None,
        "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING if observable_profile else None,
        "exactPhysicalPresentationTimeVerified": False,
        "displayCorrelationComplete": bool(reports) and not index.findings.counts and all(report["displayCorrelationComplete"] for report in reports),
        "coverageComplete": bool(reports) and all(report["coverageComplete"] for report in reports),
        "timingComplete": bool(reports) and all(report["timingComplete"] for report in reports),
        "noAutoJumpEvidenceComplete": bool(reports) and all(report["noAutoJumpEvidenceComplete"] for report in reports),
        "physicalTimingComplete": False, "requiresCalibration": True, "traceSha256": index.trace_sha256,
        "verifierSha256": sha256(Path(__file__)),
        "identitySnapshotSha256": identities.sha256 if identities else None,
        "measurementUncertainty": {"presentEventOrigin": PRESENT_EVENT_ORIGIN,
                                   "presentEventRole": PRESENT_EVENT_ROLE,
                                   "physicalPresentationTiming": (UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING
                                                                   if observable_profile else "INDEPENDENT_CALIBRATION_REQUIRED")},
        "physicalMetrics": unavailable_physical_metrics(),
        "violations": index.findings.items, "violationCounts": index.findings.counts,
        "series": reports,
        "qualificationClaim": (OBSERVABLE_QUALIFICATION_CLAIM
                                if observable_profile else
                                "NONE; this report alone never establishes 200-episode completion"),
    }


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--evidence-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--identity-snapshot", type=Path,
                        help="Optional independently frozen candidate, source episode-order and manifest references")
    parser.add_argument("--identity-snapshot-sha256",
                        help="Detached host SHA256 anchor; never obtained from native presentation artifacts")
    arguments = parser.parse_args(argv)
    try:
        policy = validate_policy(read_json(arguments.policy) if arguments.policy else {"exceptions": []})
        if bool(arguments.identity_snapshot) != bool(arguments.identity_snapshot_sha256):
            raise ValueError("Identity snapshot and its detached host SHA256 must be supplied together")
        identities = IdentitySnapshot(arguments.identity_snapshot, arguments.identity_snapshot_sha256) if arguments.identity_snapshot else None
        with tempfile.TemporaryDirectory(prefix="viewer-display-verification-") as temporary:
            index = TraceIndex(str(Path(temporary) / "trace-index.sqlite"))
            try:
                index.load_perfetto(arguments.trace)
                report = verify(index, arguments.evidence_directory, policy, identities)
            finally:
                index.close()
        report["policySha256"] = sha256(arguments.policy) if arguments.policy else None
        report["trace"] = str(arguments.trace.resolve())
    except Exception as error:  # A failed parser/import/trace query must still leave a failed report.
        report = {"schemaVersion": 1, "passed": False, "displayEvidenceComplete": False,
                  "observableEvidenceComplete": False, "result": "VERIFIER_ERROR",
                  "acceptanceMode": None, "physicalPresentationTiming": None,
                  "exactPhysicalPresentationTimeVerified": False,
                  "coverageComplete": False, "timingComplete": False,
                  "physicalTimingComplete": False, "requiresCalibration": True,
                  "physicalMetrics": unavailable_physical_metrics(),
                  "violations": [{"gate": "verifier", "reason": f"{type(error).__name__}: {error}"}], "series": []}
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(json_safe(report), ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "requiresCalibration": report["requiresCalibration"],
                      "output": str(arguments.output)}, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
