import copy
import hashlib
import unittest
from verify_engine_episode_document import verify_plan


class EpisodeDocumentTest(unittest.TestCase):
    def setUp(self):
        self.body = b'''<html><body><img src="/banner.png"><div id="vimg-area">
          <img data-src="/data/page.png" src="data:image/gif;base64,blank">
          <img data-src="/data/page.png" src="data:image/gif;base64,blank">
          </div><img src="/footer.png"></body></html>'''
        episode = dict(sourceId='wfwf', seriesKey='comic:10', episodeKey='3')
        self.plan = dict(episodeIdentity=episode, documentSha256=hashlib.sha256(self.body).hexdigest(),
            documentBytes=len(self.body), contentRevision='revision', finalDocumentUrl='https://source.test/cv?toon=10&num=3',
            pages=[dict(ordinal=i, pageIdentity={**episode, 'pageKey': f'p{i:04d}'}, sourceRecord=f'img:{i+1}',
                        candidates=['https://source.test/data/page.png']) for i in range(2)])

    def test_full_page_order_excludes_outside_images_and_retains_duplicate_urls(self):
        result = verify_plan(self.plan, self.body)
        self.assertTrue(result['independentDocumentPageOrderVerified'])
        self.assertEqual(result['pageCount'], 2)
        self.assertFalse(result['wholeEpisodeVerified'])

    def test_missing_reordered_or_wrong_source_page_rejected(self):
        for change in ('omit', 'reorder', 'source'):
            plan = copy.deepcopy(self.plan)
            if change == 'omit':
                plan['pages'].pop()
            elif change == 'reorder':
                plan['pages'].reverse()
            else:
                plan['pages'][0]['candidates'] = ['https://source.test/wrong.png']
            with self.subTest(change=change), self.assertRaises(ValueError):
                verify_plan(plan, self.body)

    def test_changed_document_and_wrong_episode_rejected(self):
        with self.assertRaisesRegex(ValueError, 'hash/length'):
            verify_plan(self.plan, self.body + b' ')
        self.plan['finalDocumentUrl'] = 'https://source.test/cv?toon=10&num=4'
        with self.assertRaisesRegex(ValueError, 'another episode'):
            verify_plan(self.plan, self.body)

    def test_unknown_container_is_not_a_partial_success(self):
        body = self.body.replace(b'vimg-area', b'other-layout')
        plan = dict(self.plan, documentBytes=len(body), documentSha256=hashlib.sha256(body).hexdigest())
        with self.assertRaisesRegex(ValueError, 'unique supported'):
            verify_plan(plan, body)

    def test_declared_korean_encoding_is_decoded_without_replacement(self):
        body = ('<meta charset="euc-kr"><title>원본</title>').encode('euc-kr') + self.body
        plan = dict(self.plan, documentBytes=len(body), documentSha256=hashlib.sha256(body).hexdigest())
        self.assertTrue(verify_plan(plan, body)['independentDocumentPageOrderVerified'])


if __name__ == '__main__':
    unittest.main()
