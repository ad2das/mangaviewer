import copy
import json
import unittest

try:
    from verify_engine_surface_fixture import CONSUMER_BASE, TARGET_LAYER, SurfaceFixtureError, bind_frames, bind_live_frames
    from verify_engine_surface_fixture import _binder_indexes, _binder_paths
except ImportError:
    from tools.verify_engine_surface_fixture import CONSUMER_BASE, TARGET_LAYER, SurfaceFixtureError, bind_frames, bind_live_frames
    from tools.verify_engine_surface_fixture import _binder_indexes, _binder_paths


OWNER_UID = 10236
OWNER_PID = 5225


def _fixture_rows():
    frames = []
    slices = []
    events = []
    transactions = []
    flows = []
    releases = []
    next_id = 1
    for token in range(1, 9):
        epoch = 1 if token <= 4 else 2
        egl = token if token <= 4 else token - 4
        layer_id = 1220 if epoch == 1 else 1228
        consumer = f"{CONSUMER_BASE}#{epoch}"
        layer_name = f"{TARGET_LAYER}#{layer_id}"
        issued = token * 1000
        swap_time = issued + 100
        swap_id = next_id
        next_id += 1
        egl_id = next_id
        next_id += 1
        queue_id = next_id
        next_id += 1
        on_frame_id = next_id
        next_id += 1
        acquire_id = next_id
        next_id += 1
        queue_event_id = next_id
        next_id += 1
        latch_event_id = next_id
        next_id += 1
        send_id, receive_id, handler_id = next_id, next_id + 1, next_id + 2
        next_id += 3
        slices.extend([
            {"id": swap_id, "parent_id": 0, "ts": issued - 20, "dur": 180,
             "name": f"viewer_swap:{token}:{egl}:{issued + 10}", "pid": OWNER_PID, "tid": 500,
             "uid": OWNER_UID},
            {"id": egl_id, "parent_id": swap_id, "ts": issued - 10, "dur": 160,
             "name": "eglSwapBuffers", "pid": OWNER_PID, "tid": 500, "uid": OWNER_UID},
            {"id": queue_id, "parent_id": egl_id, "ts": issued, "dur": 130,
             "name": "queueBuffer", "pid": OWNER_PID, "tid": 500, "uid": OWNER_UID},
            {"id": on_frame_id, "parent_id": queue_id, "ts": issued + 10, "dur": 80,
             "name": f"onFrameAvailable - {consumer}(f:0,a:0)", "pid": OWNER_PID, "tid": 500,
             "uid": OWNER_UID},
            {"id": acquire_id, "parent_id": 0, "ts": issued + 25, "dur": 40,
             "name": f"acquireNextBufferLocked - {consumer}(f:0,a:0)frame={egl}",
             "pid": OWNER_PID, "tid": 502, "uid": OWNER_UID},
            {"id": send_id, "parent_id": acquire_id, "ts": issued + 30, "dur": 0,
             "name": "binder transaction async", "pid": OWNER_PID, "tid": 502,
             "uid": OWNER_UID, "binder_id": token},
            {"id": receive_id, "parent_id": 0, "ts": issued + 170, "dur": 0,
             "name": "binder async rcv", "pid": 476, "tid": 480, "uid": 1000,
             "process_name": "/system/bin/surfaceflinger", "binder_id": token},
            {"id": handler_id, "parent_id": 0, "ts": issued + 175, "dur": 10,
             "name": "setTransactionState", "pid": 476, "tid": 480, "uid": 1000},
        ])
        flows.append({"slice_out": send_id, "slice_in": receive_id})
        releases.append({"id": token, "binder_id": token, "ts": issued + 190, "pid": 476, "tid": 480})
        events.extend([
            {"id": queue_event_id, "ts": issued + 70, "dur": 0, "name": "Queue",
             "layer_name": layer_name, "frame_number": egl},
            {"id": latch_event_id, "ts": issued + 90, "dur": 0, "name": "Latch",
             "layer_name": layer_name, "frame_number": egl},
        ])
        transactions.append({
            "snapshotId": token,
            "transactionId": (OWNER_PID << 32) + token,
            "postTime": issued + 180,
            "uid": OWNER_UID,
            "layerId": layer_id,
            "layerName": TARGET_LAYER,
            "bufferId": 9000 + token,
            "frameNumber": egl,
            "width": 64,
            "height": 96,
        })
        frames.append({
            "token": token,
            "sessionId": 700,
            "rendererEpoch": 1,
            "surfaceEpoch": epoch,
            "eglFrameId": egl,
            "captureIssuedMonotonicNs": issued,
            "swapCompletedMonotonicNs": swap_time,
        })
    return frames, slices, events, transactions, flows, releases


class EngineSurfaceFixtureBinderTest(unittest.TestCase):
    def setUp(self):
        self.rows = list(_fixture_rows())

    def test_indexed_binder_candidates_equal_scan_and_preserve_duplicate_flows(self):
        _, slices, _, _, flows, releases = self.rows
        by_id = {row['id']: row for row in slices}
        for duplicate in (False, True):
            candidates = flows + ([flows[0].copy()] if duplicate else [])
            indexes = _binder_indexes(by_id, candidates, releases)
            for acquire in slices:
                if not acquire['name'].startswith('acquireNextBufferLocked'):
                    continue
                with self.subTest(duplicate=duplicate, acquire=acquire['id']):
                    self.assertEqual(_binder_paths(acquire, by_id, candidates, releases),
                                     _binder_paths(acquire, by_id, candidates, releases, indexes))

    def _bind(self, *, trace_loss=None):
        return bind_frames(*self.rows[:4], owner_uid=OWNER_UID, owner_pid=OWNER_PID, trace_loss=trace_loss,
                           binder_flows=self.rows[4], binder_releases=self.rows[5])

    def _reject(self, reason: str):
        with self.assertRaisesRegex(SurfaceFixtureError, reason):
            self._bind()

    def test_good_binding(self):
        report = self._bind()
        self.assertTrue(report["producerLayerBindingVerified"])
        self.assertTrue(report["observableLatchVerified"])
        self.assertEqual(report["frameCount"], 8)
        self.assertEqual(report["surfaceEpochs"], [1, 2])
        self.assertEqual([row["token"] for row in report["bindings"]], list(range(1, 9)))

    def test_wrong_uid_rejected(self):
        self.rows[3][0]["uid"] = OWNER_UID + 1
        self._reject("transaction")

    def test_uid_comes_from_exact_binder_dispatch_not_process_start_snapshot(self):
        for row in self.rows[1]:
            if row.get("pid") == OWNER_PID:
                row["uid"] = 0
        report = self._bind()
        self.assertEqual(report["bindings"][0]["producerUid"], OWNER_UID)
        self.assertEqual(report["bindings"][0]["processMetadataUid"], 0)
        self.rows[3][0]["uid"] = OWNER_UID + 1
        self._reject("transaction")

    def test_wrong_transaction_pid_rejected(self):
        self.rows[3][0]["transactionId"] = ((OWNER_PID + 1) << 32) + 1
        self._reject("transaction")

    def test_same_frame_other_layer_is_ambiguous(self):
        other = copy.deepcopy(self.rows[3][0])
        other["layerId"] = 99
        other["layerName"] = "OtherLayer"
        other["transactionId"] += 100
        other["bufferId"] += 100
        self.rows[3].append(other)
        self._reject("ambiguous")

    def test_wrong_consumer_rejected(self):
        for row in self.rows[1]:
            if row["name"].startswith("onFrameAvailable") and row["id"] == 4:
                row["name"] = row["name"].replace("#1(", "#9999(")
        self._reject("unique consumer acquire")

    def test_changed_parent_id_rejected(self):
        for row in self.rows[1]:
            if row["name"].startswith("onFrameAvailable") and row["id"] == 4:
                row["parent_id"] = 0
        self._reject("eglSwapBuffers->queueBuffer->onFrameAvailable")

    def test_transaction_post_outside_server_handler_rejected(self):
        self.rows[3][0]["postTime"] = 100000
        self._reject("transaction")

    def test_missing_binder_flow_rejected(self):
        self.rows[4].pop(0)
        self._reject("Binder")

    def test_unrelated_send_parent_rejected(self):
        next(row for row in self.rows[1] if row['name'] == 'binder transaction async')['parent_id'] = 0
        self._reject("Binder")

    def test_wrong_receiver_message_rejected(self):
        next(row for row in self.rows[1] if row['name'] == 'binder async rcv')['binder_id'] = 900
        self._reject("Binder")

    def test_missing_release_rejected(self):
        self.rows[5].pop(0)
        self._reject("buffer release")

    def test_release_before_handler_rejected(self):
        self.rows[5][0]['ts'] = 1172
        self._reject("handler")

    def test_wrong_release_thread_rejected(self):
        self.rows[5][0]['tid'] = 999
        self._reject("buffer release")

    def test_multiple_server_handlers_rejected(self):
        row = copy.deepcopy(next(row for row in self.rows[1] if row['name'] == 'setTransactionState'))
        row['id'] = 999
        self.rows[1].append(row)
        self._reject("unique setTransactionState")

    def test_duplicate_transaction_candidate_rejected(self):
        self.rows[3].append(copy.deepcopy(self.rows[3][0]))
        self._reject("ambiguous")

    def test_missing_queue_rejected(self):
        self.rows[2] = [event for event in self.rows[2] if event["name"] != "Queue" or event["frame_number"] != 1]
        self._reject("exactly one Queue")

    def test_missing_latch_rejected(self):
        self.rows[2] = [event for event in self.rows[2] if event["name"] != "Latch" or event["frame_number"] != 1]
        self._reject("no Latch")

    def test_stale_surface_epoch_rejected(self):
        self.rows[0][4]["surfaceEpoch"] = 1
        self._reject("surface epoch")

    def test_trace_loss_rejected(self):
        with self.assertRaisesRegex(SurfaceFixtureError, "trace stats"):
            self._bind(trace_loss=[{"severity": "error", "value": 1}])

    def test_post_time_before_atrace_entry_still_requires_exact_dispatch(self):
        self.rows[3][0]['postTime'] = 1172
        self.assertTrue(self._bind()['producerLayerBindingVerified'])

    def test_post_time_outside_kernel_dispatch_rejected(self):
        for timestamp in (1169, 1190):
            with self.subTest(timestamp=timestamp):
                self.rows[3][0]['postTime'] = timestamp
                self._reject('transaction candidate')

    def test_wrong_raw_token_rejected(self):
        self.rows[0][0]["token"] = 9
        self._reject("exactly 1 through 8")


class EngineLiveSurfaceBinderTest(unittest.TestCase):
    def setUp(self):
        self.rows = json.loads(json.dumps(_fixture_rows()).replace(
            'ml.melun.mangaview.viewer.runtime.EngineReadbackProbeActivity',
            'ml.melun.mangaview.activity.ViewerActivity'))
        self.rows[0] = self.rows[0][1:4:2]
        for frame in self.rows[0]:
            token = frame['token']
            frame.update(rendererId=12, width=64, viewportHeight=96, inputRevision=token*3, geometryRevision=5)
            swap = next(row for row in self.rows[1] if row['name'].startswith(f'viewer_swap:{token}:'))
            parent = dict(swap, id=10000+token, parent_id=0, ts=swap['ts']-10, dur=swap['dur']+20,
                name=f'engine_frame:700:12:1:{token}:{token*3}:5')
            swap['parent_id'] = parent['id']
            self.rows[1].append(parent)

    def bind(self, **kwargs):
        return bind_live_frames(*self.rows[:4], owner_uid=OWNER_UID, owner_pid=OWNER_PID,
            binder_flows=self.rows[4], binder_releases=self.rows[5], **kwargs)

    def test_nonconsecutive_capture_scope_keeps_binder_and_engine_identity_proofs(self):
        result = self.bind()
        self.assertEqual(result['frameCount'], 2)
        self.assertEqual(result['surfaceEpochs'], [1])
        self.assertTrue(result['producerLayerBindingVerified'])
        self.assertFalse(result['physicalPresentationVerified'])
        self.assertEqual([v['inputRevision'] for v in result['bindings']], [6, 12])

    def test_wrong_input_revision_cannot_adopt_an_owned_buffer(self):
        self.rows[0][0]['inputRevision'] += 1
        with self.assertRaisesRegex(SurfaceFixtureError, 'exact captured engine frame'):
            self.bind()

    def test_wrong_viewport_cannot_adopt_an_owned_buffer(self):
        self.rows[0][0]['viewportHeight'] = 95
        with self.assertRaisesRegex(SurfaceFixtureError, 'transaction candidate'):
            self.bind()

    def test_trace_errors_are_not_waived_for_live_frames(self):
        with self.assertRaisesRegex(SurfaceFixtureError, 'trace stats'):
            self.bind(trace_loss=[{'severity': 'error', 'value': 1}])

    def test_live_scope_does_not_weaken_original_fixture_shape(self):
        with self.assertRaisesRegex(SurfaceFixtureError, 'exactly eight'):
            bind_frames(*self.rows[:4], owner_uid=OWNER_UID, owner_pid=OWNER_PID)


if __name__ == "__main__":
    unittest.main()
