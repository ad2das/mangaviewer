import base64
import hashlib
import json
import unittest

from verify_engine_ntk_document import verify, document_roots


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


class NtkDocumentTest(unittest.TestCase):
    def setUp(self):
        self.descriptor = dict(sourceWorkId='10', episodeId='episode-a', imagesToken='token',
                               imageMetas=[{'page': 1}, {'page': 2}])
        self.images = [dict(page=n, src=f'https://cdn.test/font-{n}.woff',
                            srcCandidates=[f'https://cdn.test/font-{n}.woff', f'https://other.test/{n}']) for n in (1, 2)]
        self.envelope = dict(ok=True, endpoint='/api/webtoon-images', responseUrl='https://source.test/api/webtoon-images',
                             requestMethod='POST', requestWorkId='10', requestEpisodeId='episode-a', requestToken='token',
                             responseStatus=200, requestContentType='application/json', responseContentType='application/json')
        self.episode = dict(sourceId='ntk', seriesKey='/webtoon/10', episodeKey='/webtoon/10/episode-a')

    def evidence(self):
        flight = '0:' + json.dumps(self.descriptor) + '\n'
        # Split in the middle of a JSON token; join before decoding records.
        body = ''.join('<script>self.__next_f.push(' + json.dumps([1, chunk]) + ')</script>'
                       for chunk in (flight[:19], flight[19:])).encode()
        raw = json.dumps(dict(ok=True, images=self.images), separators=(',', ':')).encode()
        envelope = dict(self.envelope, images=self.images, responseBodyBase64=base64.b64encode(raw).decode(), responseBodyBytes=len(raw))
        payload = json.dumps(envelope).encode()
        record = dict(**self.episode, documentSha256=sha(body), documentReplaySha256='replay', authEpoch=0,
                      observedMonotonicNanos=9, ackObservedElapsedRealtimeNanos=6,
                      manifestObservedElapsedRealtimeNanos=7, documentRetiredElapsedRealtimeNanos=8,
                      payloadSha256=sha(payload), payloadBytes=len(payload))
        plan = dict(episodeIdentity=self.episode, documentSha256=sha(body), documentBytes=len(body),
                    documentReplaySha256='replay', authEpoch=0, observedAtNanos=10,
                    finalDocumentUrl='https://source.test' + self.episode['episodeKey'], contentRevision='revision',
                    pages=[dict(ordinal=i, pageIdentity={**self.episode, 'pageKey': f'p{i:04d}'},
                                sourceRecord=f'image-api-page:{i + 1}',
                                candidates=[f'https://cdn.test/font-{i + 1}.woff', f'https://other.test/{i + 1}']) for i in range(2)])
        return plan, body, (record, payload)

    def test_original_document_and_raw_api_order(self):
        result = verify(*self.evidence())
        self.assertTrue(result['independentDocumentPageOrderVerified'])
        self.assertTrue(result['imageApiResponseBytesVerified'])
        self.assertFalse(result['sourceResponseBytesBindingVerified'])

    def test_raw_response_missing_duplicate_and_wrong_request_fail(self):
        self.images.pop()
        with self.assertRaisesRegex(ValueError, 'sequence is incomplete'):
            verify(*self.evidence())
        self.setUp()
        self.images[1]['page'] = 1
        with self.assertRaisesRegex(ValueError, 'sequence is incomplete'):
            verify(*self.evidence())
        self.setUp()
        self.envelope['requestEpisodeId'] = 'other'
        with self.assertRaisesRegex(ValueError, 'request identity'):
            verify(*self.evidence())

    def test_changed_payload_document_or_plan_candidates_fail(self):
        plan, body, (record, payload) = self.evidence()
        with self.assertRaisesRegex(ValueError, 'authorization bytes changed'):
            verify(plan, body, (record, payload + b' '))
        with self.assertRaisesRegex(ValueError, 'document bytes changed'):
            verify(plan, body + b' ', (record, payload))
        plan['pages'][0]['candidates'].reverse()
        with self.assertRaisesRegex(ValueError, 'page plan differs'):
            verify(plan, body, (record, payload))

    def test_flight_text_records_cannot_supply_fake_viewer_objects(self):
        text = json.dumps(self.descriptor).encode()
        flight = '1:T' + format(len(text), 'x') + ',' + text.decode() + '\n:HL["hint"]\n2:{"safe":true}\n'
        body = ('<script>self.__next_f.push(' + json.dumps([1, flight]) + ')</script>').encode()
        self.assertEqual(document_roots(body), [{'safe': True}])


if __name__ == '__main__':
    unittest.main()
