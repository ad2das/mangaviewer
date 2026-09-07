import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("audit_memory_attribution.py")
SPEC = importlib.util.spec_from_file_location("audit_memory_attribution", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class MemoryAttributionAuditTest(unittest.TestCase):
    def test_webview_process_and_dependency_are_not_app_owned(self):
        raw = """
          *APP* UID 10233 ProcessRecord{app 10:ml.melun.mangaview/u0a233}
            packageList={ml.melun.mangaview}
          *APP* UID 10151 ProcessRecord{wv 20:com.google.android.webview:webview_service/u0a151}
            packageList={com.google.android.webview}
          *APP* UID 10144 ProcessRecord{browser 30:com.google.android.googlequicksearchbox/u0a144}
            packageList={com.google.android.googlequicksearchbox}
            packageDependencies={com.google.android.webview}
        """.strip()
        records = MODULE.parse_process_records(raw)
        owned = {record["pid"] for record in records if record["owned_by_package"]}
        webview = {record["pid"] for record in records if record["actual_webview_process"]}
        dependency_only = {record["pid"] for record in records if record["webview_dependency_only"]}
        self.assertEqual({10}, owned)
        self.assertEqual({20}, webview)
        self.assertEqual({30}, dependency_only)

    def test_component_rows_are_detail_and_not_an_additive_pss_source(self):
        raw = """
        TOTAL PSS: 100 TOTAL RSS: 140 TOTAL SWAP (KB): 0
         App Summary
                       Pss(KB)
           Native Heap:    60
              Graphics:    40
        """
        parsed = MODULE.parse_meminfo(raw)
        self.assertEqual(100, parsed["total_pss_kib"])
        self.assertEqual(60, parsed["app_summary_pss_kib"]["Native Heap"])
        self.assertEqual(40, parsed["app_summary_pss_kib"]["Graphics"])
        self.assertNotIn("component_sum_kib", parsed)

    def test_terminal_zero_check_rejects_nonzero_pending_work(self):
        path = Path(self.id())
        try:
            path.write_text(
                '{"mode":"FIXTURE_REGRESSION_NO_CORPUS_CREDIT","cycles":[{"terminal":'
                '{"activeFetches":0,"activeDecodes":0,"activeUploads":1,"activeManifests":0,'
                '"retryWakeups":0,"retiringPages":0,"residentTextures":0}}]}',
                encoding="utf-8",
            )
            result = MODULE.parse_resource_regression(path)
            self.assertFalse(result["terminal_pipeline_resources_zero"])
        finally:
            if path.exists():
                path.unlink()

    def test_actual_saved_audit_is_explicitly_incomplete_and_no_credit(self):
        report = MODULE.build_report()
        self.assertEqual("INCOMPLETE", report["status"])
        self.assertFalse(report["qualification_credit"])
        self.assertTrue(report["negative_controls"]["global_webview_process_not_promoted_to_app_owner"])
        self.assertTrue(report["negative_controls"]["gfxinfo_not_added_to_pss"])
        self.assertFalse(report["sources"]["live_activity_manager_meminfo_pair_present"])
        self.assertTrue(all(
            not snapshot["owned_processes_missing_meminfo"]
            for snapshot in report["activity_manager_ownership"]["snapshots"]
        ))


if __name__ == "__main__":
    unittest.main()
