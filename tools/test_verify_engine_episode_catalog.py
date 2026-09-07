import unittest
import hashlib
import json
from pathlib import Path
import tempfile

from verify_engine_episode_catalog import parse_catalog, parse_ntk_api, parse_ntk_html, verify


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.body = ('<span class="list-header-title">총 3화</span>' + ''.join(
            f'<a class="ep-item" href="/cv?toon=10&num={n}" data-num="{n}">회차</a>'
            for n in (5, 3, 1))).encode()
        self.series = dict(sourceId='wfwf', seriesKey='comic:10')

    def parse(self, body=None):
        return parse_catalog(body or self.body, 'https://source.test/cl?toon=10', self.series)

    def test_preserves_actual_list_order_with_nonconsecutive_ids(self):
        self.assertEqual(self.parse()['episodeKeys'], ['5', '3', '1'])
        self.assertEqual(self.parse()['total'], 3)

    def test_missing_total_wrong_identity_and_ambiguous_rows_fail(self):
        for body in (self.body.replace('총 3화'.encode(), b'unknown'),
                     self.body.replace(b'data-num="5"', b'data-num="9"'),
                     self.body.replace(b'toon=10&num=5', b'toon=11&num=5'),
                     self.body + self.body):
            with self.subTest(body=body), self.assertRaises(ValueError):
                self.parse(body)

    def test_mixed_dom_order_fails(self):
        with self.assertRaisesRegex(ValueError, 'monotonic'):
            self.parse(self.body.replace(b'num=3', b'num=9').replace(b'data-num="3"', b'data-num="9"'))

    def test_http_catalog_requires_complete_total_and_actual_neighbor(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan = dict(episodeIdentity={**self.series, 'episodeKey': '3'}, observedAtNanos=10,
                        navigationKnown=True, previousEpisode={**self.series, 'episodeKey': '1'},
                        nextEpisode={**self.series, 'episodeKey': '5'})
            response = dict(requestId=1, statusCode=200, finalUrl='https://source.test/cl?toon=10',
                            documentBodyFile='exchange-1-body.bin', atMonotonicNs=9)
            ledger = dict(httpObservationHistoryVerified=True, eventsSha256='fixture', completeResponses=[response])
            def bind(body):
                (root / response['documentBodyFile']).write_bytes(body)
                response.update(bodySha256=hashlib.sha256(body).hexdigest(), bodyBytes=len(body))
                return verify(plan, ledger, root)
            self.assertTrue(bind(self.body)['independentEpisodeCatalogOrderVerified'])
            with self.assertRaisesRegex(ValueError, 'advertised episode'):
                bind(self.body.replace('총 3화'.encode(), '총 4화'.encode()))
            plan['nextEpisode']['episodeKey'] = '4'
            with self.assertRaisesRegex(ValueError, 'adjacency'):
                bind(self.body)
            response['atMonotonicNs'] = 11
            with self.assertRaisesRegex(ValueError, 'pages are missing'):
                bind(self.body)


class NtkCatalogTest(unittest.TestCase):
    def setUp(self):
        self.series = dict(sourceId='ntk', seriesKey='/webtoon/work')
        self.url = 'https://source.test/api/webtoon/work/episodes'
        self.payload = {'total': 3, 'episodes': [
            {'sourceEpisodeId': 'z-old', 'epNo': 1, 'title': '999'},
            {'sourceEpisodeId': 'a-new', 'epNo': 10, 'title': '1'},
            {'sourceEpisodeId': 'current', 'epNo': 3, 'title': '500'}]}

    def body(self):
        return json.dumps(self.payload).encode()

    def html(self, page=1, rows=((1, 'old'), (3, 'current')), page_count=1):
        links = ''.join(
            f'<a class="ep-row-v2-link" href="/webtoon/work/{key}"><strong>{number}화</strong></a>'
            for number, key in rows
        )
        pages = ''.join(
            f'<a href="/webtoon/work?epage={number}">{number}</a>'
            for number in range(2, page_count + 1)
        )
        query = '' if page == 1 else f'?epage={page}'
        return f'''<a href="/webtoon/work/latest"><strong>최신화 보기</strong></a>
            {links}{pages}'''.encode()

    def test_uses_explicit_sequence_not_id_title_or_array_order(self):
        self.assertEqual(parse_ntk_api(self.body(), self.url, self.series),
                         ['/webtoon/work/z-old', '/webtoon/work/current', '/webtoon/work/a-new'])

    def test_html_fallback_uses_explicit_sequence_and_ignores_navigation_cards(self):
        body = self.html(rows=((10, 'new'), (2, 'old')))
        parsed = parse_ntk_html(body, 'https://source.test/webtoon/work', self.series)
        self.assertEqual(parsed['episodeKeys'], ['/webtoon/work/old', '/webtoon/work/new'])
        self.assertEqual(parsed['records'], [(2, '/webtoon/work/old'), (10, '/webtoon/work/new')])

    def test_html_fallback_merges_explicit_embedded_episode_rows(self):
        body = '''<a class="ep-row-v2-link" href="/webtoon/work/ep1"><strong>1화</strong></a>
            <script>{"episodes":[{"sourceEpisodeId":"ep2","epNo":2}]}</script>'''.encode()
        parsed = parse_ntk_html(body, 'https://source.test/webtoon/work', self.series)
        self.assertEqual(parsed['episodeKeys'], ['/webtoon/work/ep1', '/webtoon/work/ep2'])

    def test_html_fallback_uses_explicit_parent_row_number_for_split_label(self):
        body = '<li data-episode-number="25"><a class="ep-row-v2-link" href="/webtoon/work/ep">13-1화</a></li>'.encode()
        parsed = parse_ntk_html(body, 'https://source.test/webtoon/work', self.series)
        self.assertEqual(parsed['records'], [(25, '/webtoon/work/ep')])

    def test_html_fallback_rejects_wrong_identity_missing_sequence_and_duplicates(self):
        with self.assertRaisesRegex(ValueError, 'another series'):
            parse_ntk_html(self.html(), 'https://source.test/webtoon/other', self.series)
        missing = b'<a class="ep-row-v2-link" href="/webtoon/work/ep">special</a>'
        with self.assertRaisesRegex(ValueError, 'explicit sequence'):
            parse_ntk_html(missing, 'https://source.test/webtoon/work', self.series)
        split_label = '<a class="ep-row-v2-link" href="/webtoon/work/ep">13-1화</a>'.encode()
        with self.assertRaisesRegex(ValueError, 'explicit sequence'):
            parse_ntk_html(split_label, 'https://source.test/webtoon/work', self.series)
        duplicate = self.html(rows=((1, 'old'), (1, 'new')))
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            parse_ntk_html(duplicate, 'https://source.test/webtoon/work', self.series)
        invalid_page = self.html() + b'<a href="/webtoon/work?epage=0">bad</a>'
        with self.assertRaisesRegex(ValueError, 'page number'):
            parse_ntk_html(invalid_page, 'https://source.test/webtoon/work', self.series)

    def test_rejects_missing_ambiguous_and_invalid_rows(self):
        changes = [lambda p: p.update(total=4), lambda p: p.update(total=True),
                   lambda p: p['episodes'][0].pop('epNo'),
                   lambda p: p['episodes'][0].update(epNo=True),
                   lambda p: p['episodes'][0].update(epNo=3),
                   lambda p: p['episodes'][0].update(sourceEpisodeId='current'),
                   lambda p: p['episodes'][0].update(sourceEpisodeId='../wrong')]
        for change in changes:
            payload = json.loads(self.body())
            change(payload)
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                parse_ntk_api(json.dumps(payload).encode(), self.url, self.series)
        with self.assertRaisesRegex(ValueError, 'another series'):
            parse_ntk_api(self.body(), self.url.replace('/work/', '/other/'), self.series)

    def test_binds_actual_http_bytes_time_origin_and_neighbors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            episode = {**self.series, 'episodeKey': '/webtoon/work/current'}
            plan = dict(episodeIdentity=episode, observedAtNanos=10, navigationKnown=True,
                        finalDocumentUrl='https://source.test/webtoon/work/current',
                        previousEpisode={**self.series, 'episodeKey': '/webtoon/work/z-old'},
                        nextEpisode={**self.series, 'episodeKey': '/webtoon/work/a-new'})
            body = self.body()
            response = dict(requestId=1, statusCode=200, finalUrl=self.url,
                            documentBodyFile='exchange-1-body.bin', atMonotonicNs=9,
                            bodyBytes=len(body), bodySha256=hashlib.sha256(body).hexdigest())
            ledger = dict(httpObservationHistoryVerified=True, eventsSha256='fixture', completeResponses=[response])
            path = root / response['documentBodyFile']
            path.write_bytes(body)
            self.assertTrue(verify(plan, ledger, root)['independentEpisodeCatalogOrderVerified'])
            for changes in ({'atMonotonicNs': 11}, {'finalUrl': self.url.replace('source.test', 'other.test')},
                            {'statusCode': 500}, {'bodySha256': 'changed'},
                            {'documentBodyFile': '../exchange-1-body.bin'}):
                original = response.copy()
                response.update(changes)
                with self.subTest(changes=changes), self.assertRaises(ValueError):
                    verify(plan, ledger, root)
                response.clear()
                response.update(original)
            plan['nextEpisode'] = None
            with self.assertRaisesRegex(ValueError, 'adjacency'):
                verify(plan, ledger, root)

    def test_valid_api_ignores_unsupported_html_after_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            episode = {**self.series, 'episodeKey': '/webtoon/work/current'}
            plan = dict(episodeIdentity=episode, observedAtNanos=10, navigationKnown=True,
                        finalDocumentUrl='https://source.test/webtoon/work/current',
                        previousEpisode={**self.series, 'episodeKey': '/webtoon/work/z-old'},
                        nextEpisode={**self.series, 'episodeKey': '/webtoon/work/a-new'})
            responses = [
                dict(requestId=1, statusCode=200, finalUrl='https://source.test/webtoon/work',
                     documentBodyFile='exchange-1-body.bin', atMonotonicNs=8),
                dict(requestId=2, statusCode=200, finalUrl=self.url,
                     documentBodyFile='exchange-2-body.bin', atMonotonicNs=9),
            ]
            bodies = [b'<html><p>unsupported page</p></html>', self.body()]
            for response, body in zip(responses, bodies):
                (root / response['documentBodyFile']).write_bytes(body)
                response['bodyBytes'] = len(body)
                response['bodySha256'] = hashlib.sha256(body).hexdigest()
            ledger = dict(httpObservationHistoryVerified=True, eventsSha256='api-html-fixture',
                          completeResponses=responses)
            result = verify(plan, ledger, root)
            self.assertTrue(result['independentEpisodeCatalogOrderVerified'])
            self.assertEqual(result['catalogResponses'], [
                {'requestId': 2, 'bodySha256': responses[1]['bodySha256']}])

    def test_html_duplicate_page_allows_identical_and_rejects_changed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            episode = {**self.series, 'episodeKey': '/webtoon/work/current'}
            plan = dict(episodeIdentity=episode, observedAtNanos=10, navigationKnown=True,
                        finalDocumentUrl='https://source.test/webtoon/work/current',
                        previousEpisode={**self.series, 'episodeKey': '/webtoon/work/old'},
                        nextEpisode=None)

            def run(second_body):
                responses = [
                    dict(requestId=1, statusCode=200, finalUrl='https://source.test/webtoon/work',
                         documentBodyFile='exchange-1-body.bin', atMonotonicNs=8),
                    dict(requestId=2, statusCode=200, finalUrl='https://source.test/webtoon/work',
                         documentBodyFile='exchange-2-body.bin', atMonotonicNs=9),
                ]
                for response, body in zip(responses, (body_one, second_body)):
                    (root / response['documentBodyFile']).write_bytes(body)
                    response['bodyBytes'] = len(body)
                    response['bodySha256'] = hashlib.sha256(body).hexdigest()
                ledger = dict(httpObservationHistoryVerified=True, eventsSha256='duplicate-fixture',
                              completeResponses=responses)
                return verify(plan, ledger, root), responses

            body_one = self.html(rows=((1, 'old'), (3, 'current')))
            result, responses = run(body_one)
            self.assertEqual(result['catalogResponses'], [
                {'requestId': 1, 'bodySha256': responses[0]['bodySha256']},
                {'requestId': 2, 'bodySha256': responses[1]['bodySha256']}])
            changed = self.html(rows=((1, 'old'), (4, 'current')))
            with self.assertRaisesRegex(ValueError, 'page changed'):
                run(changed)

    def test_binds_paginated_html_fallback_and_rejects_missing_page(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            page_one = self.html(page=1, rows=((1, 'ep1'), (2, 'ep2')), page_count=2)
            page_two = self.html(page=2, rows=((3, 'ep3'), (4, 'ep4')), page_count=2)
            responses = [
                dict(requestId=1, statusCode=200, finalUrl='https://source.test/webtoon/work',
                     documentBodyFile='exchange-1-body.bin', atMonotonicNs=8),
                dict(requestId=2, statusCode=200, finalUrl='https://source.test/webtoon/work?epage=2',
                     documentBodyFile='exchange-2-body.bin', atMonotonicNs=9),
            ]
            for response, body in zip(responses, (page_one, page_two)):
                (root / response['documentBodyFile']).write_bytes(body)
                response['bodyBytes'] = len(body)
                response['bodySha256'] = hashlib.sha256(body).hexdigest()
            episode = {**self.series, 'episodeKey': '/webtoon/work/ep3'}
            plan = dict(episodeIdentity=episode, observedAtNanos=10,
                        navigationKnown=True, finalDocumentUrl='https://source.test/webtoon/work/ep3',
                        previousEpisode={**self.series, 'episodeKey': '/webtoon/work/ep2'},
                        nextEpisode={**self.series, 'episodeKey': '/webtoon/work/ep4'})
            ledger = dict(httpObservationHistoryVerified=True, eventsSha256='html-fixture',
                          completeResponses=responses)
            result = verify(plan, ledger, root)
            self.assertTrue(result['independentEpisodeCatalogOrderVerified'])
            self.assertEqual(result['orderedEpisodeKeys'], [
                '/webtoon/work/ep1', '/webtoon/work/ep2',
                '/webtoon/work/ep3', '/webtoon/work/ep4'])
            self.assertEqual(len(result['catalogResponses']), 2)
            ledger['completeResponses'] = [responses[0]]
            with self.assertRaisesRegex(ValueError, 'pages are missing'):
                verify(plan, ledger, root)


if __name__ == '__main__':
    unittest.main()
