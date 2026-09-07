import copy
import hashlib
from pathlib import Path
import tempfile
import unittest

from PIL import Image

from engine_cache_identity import cache_name
import test_verify_engine_episode_document as document_fixture
from verify_engine_episode_inventory import inventory


class InventoryTest(unittest.TestCase):
    def setUp(self):
        fixture = document_fixture.EpisodeDocumentTest()
        fixture.setUp()
        self.plan, self.body = fixture.plan, fixture.body
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.originals = Path(temporary.name)
        image_path = self.originals / 'image.png'
        Image.new('RGB', (2, 3), 'white').save(image_path)
        self.digest = hashlib.sha256(image_path.read_bytes()).hexdigest()
        image_path.rename(self.originals / (self.digest + '.page'))
        pages = [p['pageIdentity'] for p in self.plan['pages']]
        self.sources = dict(success=True, readOnlyCacheExport=True, networkRequests=0, sources=[dict(
            sha256=self.digest, file=self.digest + '.page', cacheBindings=[dict(pageIdentity=p,
                contentRevision='revision', cacheFile=cache_name(p, 'revision', self.digest)) for p in pages])])
        self.placements = [dict(pageIdentity=p, contentRevision='revision', sourceSha256=self.digest,
                                sourceWidth=2, sourceHeight=3) for p in pages]
        self.pixels = dict(independentCapturedPixelsVerified=True, frames=[dict(capturedPixelsMatch=True,
            sourceBands=[dict(pageIdentity=p, sourceSha256=self.digest, sourceTopFraction=[0, 1],
                              sourceBottomFraction=[3, 1]) for p in pages])])

    def verify(self):
        return inventory(self.plan, self.body, self.placements, self.sources, self.originals, self.pixels)

    def test_duplicate_bytes_still_require_both_page_bindings(self):
        self.assertTrue(self.verify()['allEpisodeSourceRowsObserved'])
        self.sources['sources'][0]['cacheBindings'].pop()
        with self.assertRaisesRegex(ValueError, 'exact original cache binding'):
            self.verify()

    def test_unseen_document_page_prevents_complete_coverage(self):
        self.placements.pop()
        self.pixels['frames'][0]['sourceBands'].pop()
        result = self.verify()
        self.assertEqual(result['expectedPages'], 2)
        self.assertEqual(result['availableOriginalPages'], 1)
        self.assertFalse(result['allEpisodeSourceRowsObserved'])
        self.assertEqual(result['missingOriginalPages'], [self.plan['pages'][1]['pageIdentity']])

    def test_fractional_row_gap_is_incomplete(self):
        self.pixels['frames'][0]['sourceBands'][0]['sourceTopFraction'] = [1, 100]
        result = self.verify()
        self.assertFalse(result['allEpisodeSourceRowsObserved'])
        self.assertEqual(result['knownPageRowCoverage']['pages'][0]['missingOrPartialSourceRows'], 1)

    def test_wrong_page_prefix_or_revision_rejected(self):
        binding = self.sources['sources'][0]['cacheBindings'][0]
        good = copy.deepcopy(binding)
        for field, value in [('cacheFile', self.sources['sources'][0]['cacheBindings'][1]['cacheFile']),
                             ('contentRevision', 'another-revision')]:
            binding.update(good)
            binding[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'binding mismatch'):
                self.verify()

    def test_changed_capture_revision_or_original_fails(self):
        self.placements[0]['contentRevision'] = 'old'
        with self.assertRaisesRegex(ValueError, 'another document revision'):
            self.verify()
        self.placements[0]['contentRevision'] = 'revision'
        (self.originals / (self.digest + '.page')).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'original bytes changed'):
            self.verify()

    def test_full_coverage_does_not_claim_whole_episode_or_corpus(self):
        result = self.verify()
        self.assertTrue(result['allEpisodeSourceRowsObserved'])
        for flag in ('wholeEpisodeVerified', 'finalStopVerified', 'sourceResponseBytesBindingVerified',
                     'independentEpisodeCatalogOrderVerified'):
            self.assertFalse(result[flag])
        self.assertEqual(result['corpusCredit'], 0)


if __name__ == '__main__':
    unittest.main()
