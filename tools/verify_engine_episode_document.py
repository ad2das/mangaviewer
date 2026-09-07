"""Independently parse supported WFWF viewer documents and compare the complete ordered page plan."""
import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urljoin, urlsplit


def require(condition, message):
    if not condition:
        raise ValueError(message)


class ViewerDocument(HTMLParser):
    VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.images = []
        self.content_containers = 0

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if attrs.get('id') == 'vimg-area':
            require(tag == 'div', 'unexpected viewer content container')
            self.content_containers += 1
        if tag == 'img':
            content = any(t == 'div' and a.get('id') == 'vimg-area' for t, a in self.stack)
            self.images.append((attrs, content))
        if tag not in self.VOID:
            self.stack.append((tag, attrs))

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                break


def decode_document(body):
    labels = {v.decode('ascii').lower() for v in re.findall(
        rb'''(?i)<meta\b[^>]*\bcharset\s*=\s*["']?([a-z0-9_-]+)''', body)}
    require(len(labels) <= 1, 'conflicting document encodings')
    label = next(iter(labels), 'utf-8')
    require(label in ('utf-8', 'utf8', 'euc-kr'), 'unsupported document encoding')
    return body.decode(label, errors='strict')


def candidate(raw, base):
    if not raw or raw.strip().lower().startswith('data:'):
        return None
    value = urljoin(base, raw.strip())
    parts = urlsplit(value)
    require(parts.scheme in ('http', 'https') and parts.hostname and not any(c.isspace() for c in value),
            'invalid viewer image URL')
    require(re.search(r'\.(?:png|jpe?g|webp)$', parts.path, re.I) is not None, 'unsupported viewer image path')
    return value


def parse_pages(body, base):
    parser = ViewerDocument()
    parser.feed(decode_document(body))
    parser.close()
    require(parser.content_containers == 1, 'document has no unique supported viewer content container')
    records = []
    for index, (attrs, content) in enumerate(parser.images):
        if not content:
            continue
        require(not any(k in attrs for k in ('data-original', 'data-lazy-src', 'data-url')),
                'unsupported competing lazy source in viewer container')
        lazy = candidate(attrs.get('data-src'), base)
        direct = candidate(attrs.get('src'), base)
        first = lazy or direct
        require(first is not None, 'viewer container image has no source')
        alternatives = [first]
        if direct and direct != first:
            a, b = urlsplit(first), urlsplit(direct)
            origin = lambda u: (u.scheme, u.hostname, u.port or (443 if u.scheme == 'https' else 80))
            # A placeholder/another file is not an alternative for this page.
            if (a.path, a.query) == (b.path, b.query) and origin(a) != origin(b):
                alternatives.append(direct)
        records.append({'sourceRecord': f'img:{index}', 'candidates': alternatives})
    require(bool(records), 'viewer document contains no page records')
    return records


def verify_plan(plan, body, ntk_authorization=None):
    require(hashlib.sha256(body).hexdigest() == plan['documentSha256'] and len(body) == plan['documentBytes'],
            'original document hash/length mismatch')
    episode = plan['episodeIdentity']
    if episode['sourceId'] == 'ntk':
        from verify_engine_ntk_document import verify
        return verify(plan, body, ntk_authorization)
    require(episode['sourceId'] == 'wfwf', 'independent document parser does not yet support this source')
    match = re.fullmatch(r'(comic|webtoon):([0-9]+)', episode['seriesKey'])
    require(match is not None, 'invalid WFWF series identity')
    uri = urlsplit(plan['finalDocumentUrl'])
    query = parse_qs(uri.query)
    require(uri.path == ('/cv' if match[1] == 'comic' else '/view') and query.get('toon') == [match[2]] and
            query.get('num') == [episode['episodeKey']], 'final document URL belongs to another episode')
    expected = parse_pages(body, plan['finalDocumentUrl'])
    pages = plan['pages']
    require(len(pages) == len(expected), 'complete document page count disagrees with plan')
    for index, (page, record) in enumerate(zip(pages, expected)):
        require(type(page.get('ordinal')) is int and page['ordinal'] == index and
                page['pageIdentity'] == {**episode, 'pageKey': f'p{index:04d}'}, 'page order/identity disagrees with document')
        require(page['sourceRecord'] == record['sourceRecord'] and page['candidates'] == record['candidates'],
                'page source record/candidates disagree with independent document parse')
    return {'independentDocumentPageOrderVerified': True, 'episodeIdentity': episode, 'pageCount': len(expected),
            'documentSha256': plan['documentSha256'], 'contentRevision': plan['contentRevision'],
            'pages': pages, 'independentEpisodeCatalogOrderVerified': False, 'sourceResponseBytesBindingVerified': False,
            'wholeEpisodeVerified': False, 'physicalPresentationVerified': False, 'corpusCredit': 0}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        raw = args.plan.read_bytes()
        plan = json.loads(raw)
        require(re.fullmatch(r'document-[0-9]+\.html', plan['documentFile']) is not None, 'invalid document filename')
        result = verify_plan(plan, args.plan.with_name(plan['documentFile']).read_bytes())
        result['planSha256'] = hashlib.sha256(raw).hexdigest()
    except (OSError, ValueError, KeyError, TypeError) as failure:
        result = {'independentDocumentPageOrderVerified': False, 'error': str(failure), 'corpusCredit': 0}
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'pages'}))
    raise SystemExit(0 if result['independentDocumentPageOrderVerified'] else 1)
