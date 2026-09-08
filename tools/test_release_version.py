import hashlib
import tempfile
import unittest
from pathlib import Path
from release_version import MAX_VERSION, metadata, next_version


class ReleaseVersionTest(unittest.TestCase):
    def test_rebuild_always_exceeds_the_last_published_apk(self):
        first = next_version(2147000000, [{"name": "mangaViewer_2147000000-debug.apk"}])
        second = next_version(2147000000, [{"name": f"mangaViewer_{first}-debug.apk"}])
        self.assertEqual((first, second), (2147000001, 2147000002))

    def test_ignores_classification_and_handles_old_releases(self):
        self.assertEqual(2147000001, next_version(2147000000, [
            {"name": "classification-base.sqlite.gz"}, {"name": "version.json"},
            {"name": "mangaViewer_2113262403-debug.apk"}]))

    def test_uses_largest_published_version_even_if_asset_order_changes(self):
        self.assertEqual(2147000013, next_version(2147000000, [
            {"name": "mangaViewer_2147000012-debug.apk"}, {"name": "mangaViewer_2147000001-debug.apk"}]))

    def test_rejects_exhaustion_without_wrapping_or_downgrading(self):
        with self.assertRaises(ValueError):
            next_version(2147000000, [{"name": f"mangaViewer_{MAX_VERSION}-debug.apk"}])

    def test_metadata_describes_the_signed_file(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "mangaViewer_2147000001-debug.apk"
            apk.write_bytes(b"signed fixture")
            row = metadata(2147000001, "5.0.0", "ad2das/mangaviewer", "main-latest", apk)
            self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), row["sha256"])
            self.assertEqual(apk.stat().st_size, row["size"])
            self.assertTrue(row["link"].endswith(apk.name))


if __name__ == "__main__":
    unittest.main()
