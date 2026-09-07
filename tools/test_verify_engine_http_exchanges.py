import copy
import json
from pathlib import Path
import tempfile
import unittest

from verify_engine_http_exchanges import sha, verify, bind_plan
from engine_cache_identity import cache_name


class HttpLedgerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.body = b'<html>actual response</html>'
        base = dict(requestId=7, channel='engine', requestUrl='https://example.test/page', method='GET',
                    priority='VISIBLE', requestBodySha256=None, requestBodyBytes=0, statusCode=None,
                    finalUrl=None, contentType=None, contentLength=None, bodyBytes=0, bodySha256=None,
                    documentBodyFile=None, documentBodyLimitExceeded=False, errorType=None)
        self.events = []
        for ordinal, phase in enumerate(('STARTED', 'HEADERS', 'BODY_COMPLETE', 'CLOSED'), 1):
            event = dict(base, ordinal=ordinal, phase=phase, atMonotonicNs=ordinal)
            if ordinal > 1:
                event.update(statusCode=200, finalUrl=base['requestUrl'], contentType='text/html')
            if ordinal >= 3:
                event['bodyBytes'] = len(self.body)
            if ordinal == 3:
                event.update(bodySha256=sha(self.body), documentBodyFile='exchange-7-body.bin')
            self.events.append(event)
        self.seal = dict(observations=4, overflow=0, lateObservations=0, activeObservedRequests=0,
                         sealedAtMonotonicNs=5, retainedDocumentBytesBeforeExport=len(self.body))
        (self.root / 'exchange-7-body.bin').write_bytes(self.body)

    def run_ledger(self):
        (self.root / 'events.jsonl').write_text('\n'.join(map(json.dumps, self.events)))
        (self.root / 'seal.json').write_text(json.dumps(self.seal))
        return verify(self.root)

    def test_complete_actual_body(self):
        result = self.run_ledger()
        self.assertEqual(result['completeResponseCount'], 1)
        self.assertFalse(result['sourceResponseBytesBindingVerified'])

    def test_reject_corrupt_document(self):
        (self.root / 'exchange-7-body.bin').write_bytes(b'forged')
        with self.assertRaisesRegex(ValueError, 'body mismatch'):
            self.run_ledger()

    def test_reject_missing_close_even_with_rewritten_seal(self):
        self.events.pop()
        self.seal['observations'] = 3
        with self.assertRaisesRegex(ValueError, 'unclosed'):
            self.run_ledger()

    def test_reject_identity_clock_and_overflow_mutations(self):
        original = copy.deepcopy(self.events)
        for key, value in [('requestUrl', 'https://wrong.test'), ('atMonotonicNs', 1),
                           ('ordinal', 8), ('bodyBytes', 0)]:
            with self.subTest(key=key):
                self.events = copy.deepcopy(original)
                self.events[2][key] = value
                with self.assertRaises(ValueError):
                    self.run_ledger()
        self.events = original
        self.seal['overflow'] = 1
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.run_ledger()

    def test_partial_close_has_no_complete_response(self):
        self.events.pop(2)
        self.events[-1]['ordinal'] = 3
        self.seal.update(observations=3, retainedDocumentBytesBeforeExport=0)
        (self.root / 'exchange-7-body.bin').unlink()
        self.assertEqual(self.run_ledger()['completeResponseCount'], 0)

    def test_binding_requires_observed_document_and_correct_image_url_and_bytes(self):
        from test_verify_engine_episode_document import EpisodeDocumentTest
        fixture = EpisodeDocumentTest()
        fixture.setUp()
        plan = dict(fixture.plan, observedAtNanos=10)
        image = b'original image bytes'
        digest = sha(image)
        (self.root / (digest + '.page')).write_bytes(image)
        source = dict(file=digest + '.page', sha256=digest, cacheBindings=[
            dict(pageIdentity=p['pageIdentity'], contentRevision=plan['contentRevision'],
                 cacheFile=cache_name(p['pageIdentity'], plan['contentRevision'], digest)) for p in plan['pages']])
        document = dict(requestId=1, statusCode=200, finalUrl=plan['finalDocumentUrl'],
                        bodySha256=sha(fixture.body), bodyBytes=len(fixture.body),
                        documentBodyFile='exchange-1-body.bin', atMonotonicNs=9, channel='catalog-wfwf')
        response = dict(requestId=2, statusCode=200, finalUrl='https://cdn.test/redirect.png',
                        requestUrl=plan['pages'][0]['candidates'][0], bodySha256=digest,
                        bodyBytes=len(image), channel='engine')
        ledger = dict(httpObservationHistoryVerified=True, eventsSha256='ledger',
                      completeResponses=[document, response])
        def bind():
            return bind_plan(plan, fixture.body, ledger, dict(success=True, sources=[source]), self.root)
        self.assertTrue(bind()['sourceResponseBytesBindingVerified'])
        response['requestUrl'] = 'https://wrong.test/same-bytes.png'
        self.assertFalse(bind()['sourceResponseBytesBindingVerified'])
        response['requestUrl'] = plan['pages'][0]['candidates'][0]
        response['bodySha256'] = '0' * 64
        self.assertFalse(bind()['sourceResponseBytesBindingVerified'])
        document['atMonotonicNs'] = 11
        with self.assertRaisesRegex(ValueError, 'matches the observed'):
            bind()

    def archive_fixture(self):
        from test_verify_engine_episode_document import EpisodeDocumentTest
        fixture = EpisodeDocumentTest()
        fixture.setUp()
        plan = dict(fixture.plan, observedAtNanos=10)
        digest = sha(self.body)
        (self.root / (digest + '.page')).write_bytes(self.body)
        source = dict(file=digest + '.page', sha256=digest, cacheBindings=[
            dict(pageIdentity=p['pageIdentity'], contentRevision=plan['contentRevision'],
                 cacheFile=cache_name(p['pageIdentity'], plan['contentRevision'], digest)) for p in plan['pages']])
        document = dict(requestId=7, statusCode=200, finalUrl=plan['finalDocumentUrl'],
                        bodySha256=sha(fixture.body), bodyBytes=len(fixture.body),
                        documentBodyFile='exchange-7-body.bin', atMonotonicNs=9, channel='catalog-wfwf')
        current = dict(httpObservationHistoryVerified=True, eventsSha256='current', completeResponses=[document])
        for event in self.events:
            event['requestUrl'] = plan['pages'][0]['candidates'][0]
            if event['phase'] != 'STARTED':
                event['finalUrl'] = event['requestUrl']
        self.run_ledger()
        return plan, fixture.body, current, dict(success=True, sources=[source])

    def test_archive_proves_bytes_without_creating_current_requests(self):
        args = self.archive_fixture()
        self.assertFalse(bind_plan(*args, self.root)['sourceResponseBytesBindingVerified'])
        result = bind_plan(*args, self.root, archived_http_directories=[self.root])
        self.assertTrue(result['sourceResponseBytesBindingVerified'])
        self.assertFalse(result['allSourceResponsesObservedInCurrentRun'])
        for binding in result['pageBindings']:
            self.assertEqual(binding['matchingRequestIds'], [])
            archived = binding['archivedResponses'][0]
            self.assertEqual(archived['requestId'], 7)
            self.assertEqual(archived['httpDirectory'], str(self.root.resolve()))
            self.assertNotIn('atMonotonicNs', archived)
        self.assertEqual(result['documentRequestIds'], [7])  # Same numeric ID, distinct ledger.

    def test_archive_summary_cannot_replace_raw_sealed_evidence(self):
        args = self.archive_fixture()
        (self.root / 'http-history.json').write_text(json.dumps({'httpObservationHistoryVerified': True}))
        self.seal['overflow'] = 1
        (self.root / 'seal.json').write_text(json.dumps(self.seal))
        with self.assertRaisesRegex(ValueError, 'incomplete ledger'):
            bind_plan(*args, self.root, archived_http_directories=[self.root])

    def test_archive_wrong_url_and_wrong_cache_revision_fail(self):
        args = self.archive_fixture()
        for event in self.events:
            event['requestUrl'] = 'https://wrong.test/same-bytes'
        self.run_ledger()
        result = bind_plan(*args, self.root, archived_http_directories=[self.root])
        self.assertFalse(result['sourceResponseBytesBindingVerified'])
        args[3]['sources'][0]['cacheBindings'][0]['cacheFile'] = cache_name(
            args[0]['pages'][0]['pageIdentity'], 'another-revision', sha(self.body))
        with self.assertRaisesRegex(ValueError, 'cache page/revision'):
            bind_plan(*args, self.root, archived_http_directories=[self.root])

    def test_archive_changed_body_and_missing_close_fail(self):
        args = self.archive_fixture()
        (self.root / 'exchange-7-body.bin').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'body mismatch'):
            bind_plan(*args, self.root, archived_http_directories=[self.root])
        (self.root / 'exchange-7-body.bin').write_bytes(self.body)
        self.events.pop()
        self.seal['observations'] = 3
        (self.root / 'events.jsonl').write_text('\n'.join(map(json.dumps, self.events)))
        (self.root / 'seal.json').write_text(json.dumps(self.seal))
        with self.assertRaisesRegex(ValueError, 'unclosed'):
            bind_plan(*args, self.root, archived_http_directories=[self.root])


if __name__ == '__main__':
    unittest.main()
