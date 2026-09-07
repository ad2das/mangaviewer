"""Failure injection tests for the offline display evidence chain; no device claims."""

import csv
import json
import tempfile
import unittest
from pathlib import Path

from verify_display_trace import (
    IdentitySnapshot,
    Findings,
    OBSERVABLE_ACCEPTANCE_MODE,
    OBSERVABLE_ACCEPTED_RESULT,
    PHYSICAL_METRIC_FIELDS,
    PRESENT_EVENT_ORIGIN,
    PRESENT_EVENT_ROLE,
    TraceIndex,
    UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
    evaluate_timing,
    json_safe,
    sha256,
    validate_policy,
    verify_series,
)


ACTIVITY_LAYER = "SurfaceView[ml.melun.mangaview/ml.melun.mangaview.activity.ViewerActivity](BLAST)#10"
OTHER_LAYER = "SurfaceView[ml.melun.mangaview/ml.melun.mangaview.MainActivity](BLAST)#11"
START = 10_000_000_000
OFFSET = 3_000_000_000
PERIOD = 16_666_667
OBSERVABLE_POLICY = {
    "exceptions": [],
    "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE,
    "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING,
}
PAGE = {"sourceId": "ntk", "seriesKey": "series", "episodeKey": "episode", "pageKey": "page"}
HEADERS = ["kind", "direction", "windowStart", "windowEnd", "renderer", "token", "generation",
           "bufferFrameId", "submittedAtNanos", "renderLatencyNanos", "fullVisual",
           "geometryRevision", "userInputRevision", "scrollOffsetUnits", "scrollCause", "fullActual",
           "timestampKind", "presentedNanos"]


class DisplayTraceVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.index = TraceIndex()
        self.index.trace_bounds = (START + OFFSET - PERIOD, START + OFFSET + 10 * PERIOD)
        self.regions = []
        self.native_rows = []
        self.collection = {"startedAtNanos": START, "startedAtMillis": START // 1_000_000,
                           "collectionEndAtNanos": START + 7 * PERIOD,
                           "refreshPeriodNanos": PERIOD, "requiredEpisodes": 1,
                           "sampleKey": "sample-0", "externalDisplayVerificationRequired": True,
                           "processPid": 100, "packageName": "ml.melun.mangaview"}
        self.timings = [{"gate": gate, "value": value, "limit": limit, "inclusive": False, "sampleKey": "sample-0"}
                        for gate, value, limit in [("native-render-p95-ms", 1.0, 16.0),
                                                   ("native-render-gap-ms", 1.0, 100.0),
                                                   ("motion-gap-ms", 16.0, 100.0),
                                                   ("motion-missed-ratio", 0.0, 0.01)]]
        for frame in range(1, 7):
            self.add_frame(frame)

    def tearDown(self):
        self.index.close()
        self.temporary.cleanup()

    def add_frame(self, frame, *, offset=OFFSET):
        native = START + frame * PERIOD
        self.index.add_swap({"id": frame, "name": f"viewer_swap:{frame}:{frame}:{native}",
                             "ts": native + offset, "dur": 1_000_000, "pid": 100,
                             "parent_name": "viewer_clock", "parent_ts": native + offset - 100_000,
                             "parent_dur": 1_200_000, "track_id": 1, "parent_track_id": 1,
                             "process_name": "ml.melun.mangaview"})
        for ordinal, (name, delay) in enumerate([("Queue", 200_000), ("Latch", 300_000),
                                                ("PresentFenceSignaled", 1_000_000)]):
            self.index.add_event({"id": frame * 10 + ordinal, "name": name, "ts": native + offset + delay,
                                  "layer_name": ACTIVITY_LAYER, "frame_number": frame})
        self.regions.append({**PAGE, "rendererIdentity": 1, "token": frame, "generation": 1,
                             "bufferFrameId": frame, "submittedAtNanos": native - 1000,
                             "renderLatencyNanos": 1_002_000, "sourceTopRow": 0,
                             "sourceBottomRowExclusive": 100, "sourceHeightRows": 100,
                             "screenTopPx": 0, "screenBottomPx": 100, "viewportHeightPx": 100, "viewportWidthPx": 100,
                             "geometryRevision": 1, "userInputRevision": 1,
                             "presentedNanos": 0, "timestampKind": "COMPOSITION_LATCH",
                             "imageIdentityVerified": True})
        self.native_rows.append({"kind": "presentation", "renderer": 1, "token": frame,
                                 "generation": 1, "bufferFrameId": frame,
                                 "submittedAtNanos": native - 1000, "renderLatencyNanos": 1_002_000,
                                 "geometryRevision": 1, "userInputRevision": 1,
                                 "scrollOffsetUnits": 0, "scrollCause": "USER_INPUT",
                                 "fullVisual": "true", "fullActual": "true"})

    def write(self):
        for name, value in [("collection.json", self.collection), ("expected-pages.json", [PAGE]),
                            ("timing-observations.json", self.timings)]:
            (self.directory / name).write_text(json.dumps(value), encoding="utf-8")
        (self.directory / "presented-regions.jsonl").write_text(
            "".join(json.dumps(region) + "\n" for region in self.regions), encoding="utf-8")
        with (self.directory / "presentation-evidence.tsv").open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=HEADERS, delimiter="\t")
            writer.writeheader()
            writer.writerow({"kind": "gesture", "direction": "FORWARD", "windowStart": START + PERIOD,
                             "windowEnd": START + 6 * PERIOD + 2_000_000})
            writer.writerows(self.native_rows)

    def verify(self, policy=None, identities=None):
        self.write()
        return verify_series(self.index, self.directory, policy, identities)

    def identity_snapshot(self, mutate=None, *, order=None, pages=None):
        def artifact(name, value):
            path = self.directory / name
            path.write_text(json.dumps(value), encoding="utf-8")
            return {"path": name, "sha256": sha256(path)}
        candidate = artifact("candidate.json", {"source": PAGE["sourceId"], "series": PAGE["seriesKey"], "episode": PAGE["episodeKey"]})
        order_ref = artifact("source-episode-order.json", {"sourceId": PAGE["sourceId"], "seriesKey": PAGE["seriesKey"],
                             "episodeKeysInReadingOrder": order or ["previous", "episode", "next"]})
        manifests = []
        for episode, page_keys in (pages or {"previous": ["page"], "episode": ["page"], "next": ["page"]}).items():
            reference = artifact(f"source-manifest-{episode}.json", {"sourceId": PAGE["sourceId"], "seriesKey": PAGE["seriesKey"],
                                 "episodeKey": episode, "pages": [{"pageKey": key} for key in page_keys]})
            manifests.append({"episodeKey": episode, **reference})
        document = {"schemaVersion": 1, "candidatePath": candidate["path"], "candidateSha256": candidate["sha256"],
                    "samples": [{"sampleKey": "sample-0", "sourceId": PAGE["sourceId"], "seriesKey": PAGE["seriesKey"],
                                 "requestedEpisodeKeys": ["episode"], "episodeOrder": order_ref, "manifests": manifests}]}
        if mutate:
            mutate(document)
        path = self.directory / "identity-snapshot.json"
        path.write_text(json.dumps(document), encoding="utf-8")
        return IdentitySnapshot(path, sha256(path))

    def test_authoritative_immediate_next_page_is_context_without_requested_credit(self):
        self.regions[-1].update(episodeKey="next", userInputRevision=2)
        self.native_rows[-1]["userInputRevision"] = 2
        result = self.verify(identities=self.identity_snapshot())
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertTrue(result["coverageComplete"], result["violations"])
        self.assertEqual(result["metrics"]["expectedPages"], 1)
        self.assertEqual(result["metrics"]["fullyCoveredPages"], 1)
        self.assertEqual(result["metrics"]["matchedNeighborRegions"], 1)
        self.assertEqual(result["pageIdentityScope"]["requestedCoverageCreditFromNeighbors"], 0)
        self.assertFalse(result["passed"])

    def test_immediate_previous_context_does_not_start_requested_first_content_clock(self):
        self.regions[0].update(episodeKey="previous", userInputRevision=0)
        self.native_rows[0]["userInputRevision"] = 0
        result = self.verify(identities=self.identity_snapshot())
        self.assertTrue(result["coverageComplete"])
        first = next(row for row in result["rawTimingObservations"] if row["gate"] == "first-content-ms")
        self.assertGreater(first["value"], 2 * PERIOD / 1_000_000)

    def test_neighbor_rows_cannot_fill_requested_page_row_gap(self):
        for region in self.regions[:-1]:
            region["sourceBottomRowExclusive"] = 50
        self.regions[-1].update(episodeKey="next", userInputRevision=2)
        self.native_rows[-1]["userInputRevision"] = 2
        result = self.verify(identities=self.identity_snapshot())
        self.assertFalse(result["coverageComplete"])
        self.assertEqual(result["metrics"]["fullyCoveredPages"], 0)
        self.assertEqual(result["metrics"]["matchedNeighborRegions"], 1)

    def test_only_neighbor_content_is_neither_requested_coverage_nor_first_content(self):
        for region in self.regions:
            region["episodeKey"] = "next"
        result = self.verify(identities=self.identity_snapshot())
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertFalse(result["coverageComplete"])
        self.assertTrue(any("First actual image timestamp unavailable" in row["reason"] for row in result["violations"]))

    def test_native_neighbor_claim_without_independent_snapshot_is_unverified(self):
        self.regions[-1].update(episodeKey="next", permittedNeighbor=True, authoritative=True)
        result = self.verify()
        self.assertFalse(result["coverageComplete"])
        self.assertEqual(result["metrics"]["permittedNeighborPages"], 0)

    def test_wrong_page_within_allowed_neighbor_episode_still_fails(self):
        self.regions[-1].update(episodeKey="next", pageKey="wrong-page")
        self.assertFalse(self.verify(identities=self.identity_snapshot())["coverageComplete"])

    def test_wrong_texture_inside_permitted_neighbor_still_fails(self):
        self.regions[-1].update(episodeKey="next", imageIdentityVerified=False)
        self.assertFalse(self.verify(identities=self.identity_snapshot())["coverageComplete"])

    def test_distant_episode_is_not_permitted_context(self):
        self.regions[-1]["episodeKey"] = "far"
        result = self.verify(identities=self.identity_snapshot(order=["previous", "episode", "next", "far"]))
        self.assertFalse(result["coverageComplete"])
        self.assertEqual(result["metrics"]["matchedNeighborRegions"], 0)

    def test_declared_nonadjacent_manifest_cannot_expand_scope(self):
        identities = self.identity_snapshot(order=["previous", "episode", "next", "far"],
                                            pages={"episode": ["page"], "far": ["page"]})
        result = self.verify(identities=identities)
        self.assertTrue(any("nonadjacent" in row["reason"] for row in result["violations"]))
        self.assertFalse(result["coverageComplete"])

    def test_changed_source_manifest_hash_fails_closed(self):
        identities = self.identity_snapshot()
        path = self.directory / "source-manifest-next.json"
        path.write_text(path.read_text() + " ", encoding="utf-8")
        self.assertFalse(self.verify(identities=identities)["displayCorrelationComplete"])

    def test_changed_candidate_hash_fails_closed(self):
        identities = self.identity_snapshot()
        path = self.directory / "candidate.json"
        path.write_text(path.read_text() + " ", encoding="utf-8")
        self.assertFalse(self.verify(identities=identities)["displayCorrelationComplete"])

    def test_identity_snapshot_requires_detached_host_hash(self):
        self.identity_snapshot()
        with self.assertRaisesRegex(ValueError, "hash or size mismatch"):
            IdentitySnapshot(self.directory / "identity-snapshot.json", "0" * 64)

    def test_cross_sample_snapshot_cannot_authorize_observed_neighbor(self):
        identities = self.identity_snapshot(lambda doc: doc["samples"][0].update(sampleKey="another-sample"))
        self.regions[-1]["episodeKey"] = "next"
        self.assertFalse(self.verify(identities=identities)["coverageComplete"])

    def test_cross_series_identity_snapshot_fails_closed(self):
        identities = self.identity_snapshot(lambda doc: doc["samples"][0].update(seriesKey="wrong-series"))
        self.assertFalse(self.verify(identities=identities)["coverageComplete"])

    def test_expected_pages_must_equal_requested_source_manifests(self):
        identities = self.identity_snapshot(pages={"episode": ["page", "omitted-page"], "next": ["page"]})
        self.assertFalse(self.verify(identities=identities)["coverageComplete"])

    def test_requested_chain_cannot_skip_authoritative_episode(self):
        identities = self.identity_snapshot(lambda doc: doc["samples"][0].update(requestedEpisodeKeys=["episode", "far"]),
                                            order=["previous", "episode", "next", "far"])
        result = self.verify(identities=identities)
        self.assertTrue(any("not consecutive" in row["reason"] for row in result["violations"]))

    def test_duplicate_authoritative_episode_order_is_not_guessed(self):
        identities = self.identity_snapshot(order=["episode", "next", "next"])
        self.assertFalse(self.verify(identities=identities)["coverageComplete"])

    def test_identity_reference_cannot_escape_frozen_snapshot_root(self):
        with self.assertRaisesRegex(ValueError, "escapes"):
            self.identity_snapshot(lambda doc: doc.update(candidatePath="../candidate.json"))

    def test_complete_correlation_and_rows_still_require_calibration(self):
        result = self.verify()
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertTrue(result["coverageComplete"], result["violations"])
        self.assertTrue(result["timingComplete"], result["violations"])
        self.assertEqual(result["metrics"]["matchedFrames"], 6)
        self.assertTrue(result["requiresCalibration"])
        self.assertFalse(result["displayEvidenceComplete"])
        self.assertFalse(result["passed"])

    def test_observable_profile_accepts_complete_proxy_evidence_without_physical_claim(self):
        result = self.verify(OBSERVABLE_POLICY)
        self.assertTrue(result["passed"], result["violations"])
        self.assertTrue(result["observableEvidenceComplete"])
        self.assertEqual(result["result"], OBSERVABLE_ACCEPTED_RESULT)
        self.assertEqual(result["acceptanceMode"], OBSERVABLE_ACCEPTANCE_MODE)
        self.assertEqual(result["physicalPresentationTiming"], UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING)
        self.assertTrue(result["requiresCalibration"])
        self.assertFalse(result["exactPhysicalPresentationTimeVerified"])
        self.assertFalse(result["displayEvidenceComplete"])
        self.assertFalse(result["physicalTimingComplete"])
        self.assertEqual(result["measurementUncertainty"]["presentEventOrigin"], PRESENT_EVENT_ORIGIN)
        self.assertEqual(result["measurementUncertainty"]["presentEventRole"], PRESENT_EVENT_ROLE)
        self.assertTrue(all(result["physicalMetrics"][field] is None for field in PHYSICAL_METRIC_FIELDS))
        proxy = next(item for item in result["rawTimingObservations"] if item["gate"] == "surface-gap-ms")
        self.assertIsNone(proxy["passed"])
        self.assertIsNone(proxy["withinGoal"])
        self.assertEqual(proxy["physicalGateDecision"], "UNAVAILABLE")
        self.assertEqual(proxy["measurementRole"], "COMPOSITION_PROXY_NOT_PHYSICAL_SCANOUT")

    def test_observable_profile_does_not_bypass_missing_source_rows(self):
        for region in self.regions:
            region["sourceBottomRowExclusive"] = 50
        result = self.verify(OBSERVABLE_POLICY)
        self.assertFalse(result["passed"])
        self.assertFalse(result["observableEvidenceComplete"])
        self.assertFalse(result["coverageComplete"])

    def test_observable_profile_does_not_bypass_wrong_buffer_or_generation(self):
        self.regions[0]["generation"] = 2
        result = self.verify(OBSERVABLE_POLICY)
        self.assertFalse(result["passed"])
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertTrue(any(item["gate"] in ("coverage", "display") for item in result["violations"]))

    def test_observable_profile_does_not_bypass_ambiguous_present_match(self):
        self.index.db.execute("INSERT INTO events SELECT 1000,ts,name,layer,frame FROM events WHERE id=12")
        result = self.verify(OBSERVABLE_POLICY)
        self.assertFalse(result["passed"])
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertFalse(result["observableEvidenceComplete"])

    def test_observable_profile_does_not_bypass_unknown_local_stall(self):
        self.timings[0]["value"] = None
        result = self.verify(OBSERVABLE_POLICY)
        self.assertFalse(result["passed"])
        self.assertFalse(result["timingComplete"])
        self.assertTrue(any(item["gate"] == "timing" for item in result["violations"]))

    def test_missing_egl_callback_is_not_missing_external_display(self):
        self.native_rows.clear()
        result = self.verify()
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertTrue(result["coverageComplete"])

    def add_terminal(self, *, token=99, kind="CANCELLED", regions=True):
        terminal = {**self.native_rows[0], "token": token, "bufferFrameId": 0,
                    "timestampKind": kind, "presentedNanos": 0}
        self.native_rows.append(terminal)
        if regions:
            self.regions.append({**self.regions[0], "token": token, "bufferFrameId": 0,
                                 "timestampKind": kind, "presentedNanos": 0})
        return terminal

    def test_zero_buffer_terminal_is_retained_without_display_or_row_credit(self):
        self.add_terminal()
        result = self.verify()
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertEqual(result["metrics"]["nativeFrames"], 6)
        self.assertEqual(result["metrics"]["matchedFrames"], 6)
        self.assertEqual(result["terminalObservations"]["count"], 1)
        self.assertEqual(result["terminalObservations"]["preSwapConfirmed"], 1)
        self.assertEqual(result["terminalObservations"]["reportedRegionCount"], 1)
        self.assertEqual(result["terminalObservations"]["displayCredit"], 0)
        self.assertFalse(result["passed"])

    def test_terminal_regions_cannot_fill_missing_actual_source_rows(self):
        self.add_terminal()
        for region in self.regions[:-1]:
            region["sourceBottomRowExclusive"] = 50
        result = self.verify()
        self.assertTrue(result["terminalObservations"]["complete"])
        self.assertFalse(result["coverageComplete"])
        self.assertEqual(result["metrics"]["fullyCoveredPages"], 0)

    def test_terminal_label_cannot_hide_an_actual_unrepresented_buffer(self):
        terminal = {**self.native_rows[0], "bufferFrameId": 0,
                    "timestampKind": "CANCELLED", "presentedNanos": 0}
        self.native_rows[0] = terminal
        self.regions[0].update(bufferFrameId=0, timestampKind="CANCELLED")
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertFalse(result["terminalObservations"]["complete"])
        self.assertEqual(result["metrics"]["matchedFrames"], 5)
        self.assertTrue(any("unrepresented" in row["reason"] for row in result["violations"]))
        self.assertTrue(any("traced swap" in row["reason"] for row in result["violations"]))

    def test_terminal_swap_without_present_is_still_unresolved(self):
        self.native_rows[0].update(bufferFrameId=0, timestampKind="DROPPED", presentedNanos=0)
        self.regions[0].update(bufferFrameId=0, timestampKind="DROPPED")
        self.index.db.execute("DELETE FROM events WHERE frame=1")
        result = self.verify()
        self.assertFalse(result["terminalObservations"]["complete"])
        self.assertEqual(result["terminalObservations"]["records"][0]["traceSwapCount"], 1)

    def test_trace_loss_cannot_establish_absence_for_zero_buffer_terminal(self):
        self.add_terminal(kind="CONTEXT_LOST")
        self.index.findings.add("trace", "Synthetic packet loss")
        result = self.verify()
        self.assertFalse(result["terminalObservations"]["complete"])
        self.assertEqual(result["terminalObservations"]["preSwapConfirmed"], 0)

    def test_terminal_outside_trace_span_is_not_assumed_pre_swap(self):
        terminal = self.add_terminal(regions=False)
        terminal["submittedAtNanos"] = START - 10 * PERIOD
        result = self.verify()
        self.assertFalse(result["terminalObservations"]["complete"])

    def test_zero_buffer_without_terminal_kind_remains_malformed(self):
        self.add_terminal(kind="UNAVAILABLE")
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertTrue(any("Zero bufferFrameId" in row["reason"] for row in result["violations"]))

    def test_duplicate_terminal_or_terminal_native_conflict_is_not_hidden(self):
        terminal = self.add_terminal(token=1)
        self.native_rows.append(dict(terminal))
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertTrue(any("same request" in row["reason"] for row in result["violations"]))
        self.assertTrue(any("Duplicate terminal" in row["reason"] for row in result["violations"]))

    def test_terminal_scene_metadata_without_terminal_record_is_incomplete(self):
        self.add_terminal()
        self.native_rows.pop()
        self.assertFalse(self.verify()["terminalObservations"]["complete"])

    def test_positive_buffer_terminal_never_receives_display_credit(self):
        self.native_rows[0].update(timestampKind="DROPPED", presentedNanos=0)
        self.regions[0].update(timestampKind="DROPPED")
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertEqual(result["metrics"]["matchedFrames"], 5)
        self.assertEqual(result["terminalObservations"]["displayCredit"], 0)

    def test_latch_only_never_grants_rows(self):
        self.index.db.execute("DELETE FROM events WHERE name='PresentFenceSignaled'")
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertFalse(result["coverageComplete"])
        self.assertEqual(result["metrics"]["matchedFrames"], 0)

    def test_duplicate_present_is_ambiguous(self):
        self.index.db.execute("INSERT INTO events SELECT 1000,ts,name,layer,frame FROM events WHERE id=12")
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertEqual(result["metrics"]["matchedFrames"], 5)

    def test_duplicate_queue_on_another_target_layer_is_ambiguous(self):
        self.index.db.execute("INSERT INTO events SELECT 1000,ts,name,replace(layer,'#10','#20'),frame FROM events WHERE id=10")
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_duplicate_swap_cannot_be_picked_by_time_proximity(self):
        self.index.db.execute("INSERT INTO swaps SELECT 1000,pid,process,ts+1,dur,token,frame,native,parent_ts,parent_dur FROM swaps WHERE id=1")
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_wrong_buffer_frame_id_does_not_use_nearest_frame(self):
        self.index.db.execute("UPDATE events SET frame=frame+100")
        result = self.verify()
        self.assertFalse(result["coverageComplete"])
        self.assertEqual(result["metrics"]["matchedFrames"], 0)

    def test_wrong_layer_does_not_enter_trace_index(self):
        self.index.db.execute("DELETE FROM events")
        for identity, name in enumerate(["Queue", "Latch", "PresentFenceSignaled"]):
            self.index.add_event({"id": identity, "name": name, "ts": START + OFFSET + PERIOD + 500_000,
                                  "layer_name": OTHER_LAYER, "frame_number": 1})
        self.assertEqual(self.index.db.execute("SELECT count(*) FROM events").fetchone()[0], 0)
        self.assertFalse(self.verify()["coverageComplete"])

    def test_wrong_page_is_not_credited_to_expected_page(self):
        for region in self.regions:
            region["pageKey"] = "wrong-image"
        self.assertFalse(self.verify()["coverageComplete"])

    def test_stable_input_and_geometry_detects_global_offset_jump(self):
        self.native_rows[3]["scrollOffsetUnits"] = 100
        result = self.verify()
        self.assertFalse(result["noAutoJumpEvidenceComplete"])
        self.assertTrue(any("Global offset changed" in item["reason"] for item in result["violations"]))

    def test_geometry_correction_preserving_visible_source_center_is_allowed(self):
        self.regions[3]["geometryRevision"] = 2
        self.native_rows[3]["geometryRevision"] = 2
        self.native_rows[3]["scrollOffsetUnits"] = 100
        self.native_rows[3]["scrollCause"] = "GEOMETRY_CORRECTION"
        self.assertTrue(self.verify()["noAutoJumpEvidenceComplete"])

    def test_geometry_change_cannot_hide_center_source_row_jump(self):
        self.regions[3].update(geometryRevision=2, sourceTopRow=10)
        self.native_rows[3].update(geometryRevision=2, scrollOffsetUnits=100, scrollCause="GEOMETRY_CORRECTION")
        result = self.verify()
        self.assertFalse(result["noAutoJumpEvidenceComplete"])
        self.assertTrue(any("Center source row" in item["reason"] for item in result["violations"]))

    def test_changed_user_input_allows_intended_motion(self):
        for i in range(3, 6):
            self.regions[i].update(userInputRevision=2, sourceTopRow=10)
            self.native_rows[i].update(userInputRevision=2, scrollOffsetUnits=100)
        self.assertTrue(self.verify()["noAutoJumpEvidenceComplete"])

    def test_source_center_one_rendered_pixel_rounding_is_allowed(self):
        self.regions[3].update(geometryRevision=2, sourceTopRow=1)
        self.native_rows[3]["geometryRevision"] = 2
        self.assertTrue(self.verify()["noAutoJumpEvidenceComplete"])

    def test_width_change_preserves_source_center(self):
        self.regions[3].update(geometryRevision=2, screenBottomPx=200, viewportHeightPx=200, viewportWidthPx=200)
        self.native_rows[3].update(geometryRevision=2, scrollCause="GEOMETRY_CORRECTION")
        self.assertTrue(self.verify()["noAutoJumpEvidenceComplete"])

    def test_first_visible_center_has_no_prior_center_to_compare(self):
        self.regions[0].update(screenBottomPx=40)
        self.native_rows[0]["scrollOffsetUnits"] = 100
        self.assertTrue(self.verify()["noAutoJumpEvidenceComplete"])

    def test_page_changed_jump_cannot_escape_global_offset_or_center_check(self):
        second_page = {**PAGE, "pageKey": "second-page"}
        for i in range(3, 6):
            self.regions[i]["pageKey"] = "second-page"
            self.native_rows[i]["scrollOffsetUnits"] = 100
        self.write()
        (self.directory / "expected-pages.json").write_text(json.dumps([PAGE, second_page]), encoding="utf-8")
        result = verify_series(self.index, self.directory)
        self.assertTrue(result["coverageComplete"])
        self.assertFalse(result["noAutoJumpEvidenceComplete"])
        self.assertTrue(any("Center PageId changed" in item["reason"] for item in result["violations"]))

    def test_unverified_image_identity_is_rejected(self):
        for region in self.regions:
            region["imageIdentityVerified"] = False
        self.assertFalse(self.verify()["coverageComplete"])

    def test_row_gap_is_not_hidden_by_reaching_bottom(self):
        for i, region in enumerate(self.regions):
            region["sourceTopRow"] = 0 if i % 2 else 51
            region["sourceBottomRowExclusive"] = 50 if i % 2 else 100
        self.assertFalse(self.verify()["coverageComplete"])

    def test_adjacent_row_ranges_cover_whole_source(self):
        for i, region in enumerate(self.regions):
            region["sourceTopRow"] = 0 if i % 2 else 50
            region["sourceBottomRowExclusive"] = 50 if i % 2 else 100
        self.assertTrue(self.verify()["coverageComplete"])

    def test_duplicate_region_is_not_silently_deduplicated(self):
        self.regions.append(dict(self.regions[0]))
        self.assertFalse(self.verify()["coverageComplete"])

    def test_clock_bridge_outside_native_submission_fails(self):
        self.index.db.execute("UPDATE swaps SET native=native+2000000")
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertFalse(result["timingComplete"])

    def test_impossible_clock_bracket_intersection_cannot_be_absorbed_into_latency(self):
        self.index.db.execute("UPDATE swaps SET ts=ts+200000,parent_ts=parent_ts+200000 WHERE id=6")
        self.index.db.execute("UPDATE events SET ts=ts+200000 WHERE frame=6")
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])
        self.assertFalse(result["timingComplete"])

    def test_preemption_inside_observed_clock_bracket_is_not_a_guessed_clock_failure(self):
        self.index.db.execute("UPDATE swaps SET ts=ts+5000000,parent_dur=parent_dur+5000000 WHERE id=6")
        self.index.db.execute("UPDATE events SET ts=ts+5000000 WHERE frame=6")
        self.native_rows[5]["renderLatencyNanos"] += 5_000_000
        self.regions[5]["renderLatencyNanos"] += 5_000_000
        result = self.verify()
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertEqual(result["measurementUncertainty"]["nativeToTraceOffsetNanos"], [OFFSET - 100_000, OFFSET])

    def test_old_swap_without_direct_clock_parent_is_incomplete(self):
        self.index.add_swap({"id": 1000, "name": f"viewer_swap:7:7:{START}", "ts": START + OFFSET,
                             "dur": 1000, "pid": 100, "process_name": "ml.melun.mangaview"})
        self.assertFalse(self.verify()["displayCorrelationComplete"])
        self.assertTrue(any("viewer_clock bracket" in item["reason"] for item in self.index.findings.items))

    def test_wrong_thread_clock_parent_cannot_establish_a_bracket(self):
        self.index.add_swap({"id": 1000, "name": f"viewer_swap:7:7:{START}", "ts": START + OFFSET,
                             "dur": 1000, "pid": 100, "process_name": "ml.melun.mangaview", "track_id": 1,
                             "parent_name": "viewer_clock", "parent_track_id": 2,
                             "parent_ts": START + OFFSET - 1000, "parent_dur": 3000})
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_truncated_clock_parent_cannot_establish_a_bracket(self):
        self.index.add_swap({"id": 1000, "name": f"viewer_swap:7:7:{START}", "ts": START + OFFSET,
                             "dur": 1000, "pid": 100, "process_name": "ml.melun.mangaview", "track_id": 1,
                             "parent_name": "viewer_clock", "parent_track_id": 1,
                             "parent_ts": START + OFFSET - 1000, "parent_dur": -1})
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_extra_displayed_buffer_without_application_evidence_fails(self):
        self.index.add_event({"id": 1000, "name": "PresentFenceSignaled", "ts": START + OFFSET + 3 * PERIOD + 2_000_000,
                              "layer_name": ACTIVITY_LAYER, "frame_number": 999})
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_displayed_native_frame_without_image_regions_fails(self):
        self.regions.pop()
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_initial_represented_loading_is_disclosed_and_keeps_first_content_clock(self):
        self.regions.pop(0)
        self.native_rows[0].update(fullVisual="false", fullActual="false")
        result = self.verify()
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertTrue(result["coverageComplete"])
        loading = result["loadingObservations"]["intervals"][0]
        self.assertFalse(loading["afterFirstContent"])
        self.assertFalse(loading["excludedFromEvidence"])
        first = next(item for item in result["rawTimingObservations"] if item["gate"] == "first-content-ms")
        self.assertGreater(first["value"], 2 * PERIOD / 1_000_000)

    def test_initial_unrepresented_loading_buffer_is_still_missing_evidence(self):
        self.regions.pop(0)
        self.native_rows.pop(0)
        result = self.verify()
        self.assertFalse(result["displayCorrelationComplete"])

    def test_after_content_loading_over_100ms_needs_independent_attribution(self):
        self.regions = self.regions[:1]
        for row in self.native_rows[1:]:
            row.update(fullVisual="false", fullActual="false")
        self.collection["collectionEndAtNanos"] = START + 30 * PERIOD
        self.index.trace_bounds = (START + OFFSET - PERIOD, START + OFFSET + 31 * PERIOD)
        result = self.verify()
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertFalse(result["timingComplete"])
        interval = result["loadingObservations"]["intervals"][0]
        self.assertTrue(interval["afterFirstContent"])
        self.assertGreater(interval["timingDecision"]["value"], 100)
        self.assertFalse(interval["timingDecision"]["passed"])

    def test_loading_flag_cannot_hide_wrong_image_identity(self):
        self.native_rows[2].update(fullVisual="false", fullActual="false")
        self.regions[2]["imageIdentityVerified"] = False
        self.assertFalse(self.verify()["coverageComplete"])

    def test_trace_data_loss_invalidates_otherwise_complete_correlation(self):
        self.index.findings.add("trace", "Injected ftrace overrun", value=1)
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_stale_zygote_process_name_needs_independent_collection_pid(self):
        self.index.db.execute("UPDATE swaps SET process='zygote64'")
        self.collection.pop("processPid")
        self.assertFalse(self.verify()["displayCorrelationComplete"])
        self.collection.update(processPid=100, packageName="ml.melun.mangaview")
        self.assertTrue(self.verify()["displayCorrelationComplete"])

    def test_independent_collection_pid_mismatch_fails_even_with_correct_process_name(self):
        self.collection.update(processPid=999, packageName="ml.melun.mangaview")
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_trace_must_include_last_gesture_tail(self):
        self.index.trace_bounds = (START + OFFSET - PERIOD, START + OFFSET + 5 * PERIOD)
        self.assertFalse(self.verify()["displayCorrelationComplete"])

    def test_missing_timing_measurement_fails(self):
        self.timings.pop()
        self.assertFalse(self.verify()["timingComplete"])

    def test_gesture_tail_freeze_is_not_hidden_by_regular_earlier_frames(self):
        self.collection["collectionEndAtNanos"] = START + 30 * PERIOD
        self.index.trace_bounds = (START + OFFSET - PERIOD, START + OFFSET + 31 * PERIOD)
        self.write()
        evidence = self.directory / "presentation-evidence.tsv"
        with evidence.open(encoding="utf-8", newline="") as stream:
            rows = list(csv.DictReader(stream, delimiter="\t"))
        rows[0]["windowEnd"] = str(START + 25 * PERIOD)
        with evidence.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=HEADERS, delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)
        result = verify_series(self.index, self.directory)
        self.assertFalse(result["timingComplete"])
        tail = next(item for item in result["rawTimingObservations"] if item["gate"] == "surface-tail-gap-ms")
        self.assertGreater(tail["value"], 100)

    def test_production_reverse_gesture_enum_is_accepted(self):
        self.write()
        path = self.directory / "presentation-evidence.tsv"
        path.write_text(path.read_text(encoding="utf-8").replace("FORWARD", "REVERSE"), encoding="utf-8")
        result = verify_series(self.index, self.directory)
        self.assertTrue(result["displayCorrelationComplete"], result["violations"])
        self.assertTrue(result["timingComplete"])

    def test_entry_clock_uncertainty_cannot_relax_four_second_limit(self):
        shifted = START - 4_000_000_000 + PERIOD + 950_000
        self.collection["startedAtNanos"] = shifted
        self.index.trace_bounds = (shifted + OFFSET - PERIOD, START + OFFSET + 10 * PERIOD)
        result = self.verify()
        first = next(item for item in result["rawTimingObservations"] if item["gate"] == "first-content-ms")
        self.assertGreater(first["value"], 4000)
        self.assertFalse(first["passed"])

    def test_existing_limits_are_not_relaxed_by_artifact(self):
        self.timings[0]["limit"] = 32.0
        self.assertFalse(self.verify()["timingComplete"])

    def test_policy_boolean_is_not_display_calibration(self):
        result = self.verify({"exceptions": [], "displayCalibration": {"verified": True}, "independentlyVerified": True})
        self.assertTrue(result["requiresCalibration"])
        self.assertFalse(result["passed"])

    def test_policy_requires_the_exact_observable_pair(self):
        with self.assertRaisesRegex(ValueError, "exact authorized pair"):
            validate_policy({"exceptions": [], "acceptanceMode": OBSERVABLE_ACCEPTANCE_MODE})
        with self.assertRaisesRegex(ValueError, "exact authorized pair"):
            validate_policy({"exceptions": [], "acceptanceMode": "OBSERVABLE_RENDER_V0",
                             "physicalPresentationTiming": UNAVAILABLE_PHYSICAL_PRESENTATION_TIMING})

    def test_policy_boolean_is_not_exact_sample_timing_attribution(self):
        findings = Findings()
        policy = validate_policy({"deviceFingerprint": "test-device", "exceptions": [
            {"id": "device-slow", "gate": "native-render-p95-ms", "cause": "DEVICE",
             "evidenceSha256": "a" * 64, "maximumValue": 50.0, "deviceFingerprint": "test-device"}]})
        result = evaluate_timing({"gate": "native-render-p95-ms", "value": 20.0, "limit": 16.0,
                                  "inclusive": False, "sampleKey": "sample-0", "independentlyVerified": True,
                                  "attribution": {"exceptionId": "device-slow", "independentlyVerified": True,
                                                  "evidenceSha256": "a" * 64}}, findings, policy)
        self.assertFalse(result["passed"])
        self.assertIsNone(result["exceptionApplied"])

    def test_nonfinite_and_boundary_timing(self):
        for value, expected in [(float("nan"), False), (float("inf"), False), (16.0, False), (15.999, True)]:
            result = evaluate_timing({"gate": "native-render-p95-ms", "value": value, "limit": 16.0,
                                      "inclusive": False, "sampleKey": "s"}, Findings(), {"exceptions": []})
            self.assertEqual(result["passed"], expected)

    def test_nonfinite_failure_can_always_be_serialized_as_valid_json(self):
        self.timings[0]["value"] = float("nan")
        result = self.verify()
        self.assertFalse(result["timingComplete"])
        encoded = json.dumps(json_safe(result), allow_nan=False)
        self.assertIn("invalidNonFiniteNumber", encoded)


if __name__ == "__main__":
    unittest.main()
