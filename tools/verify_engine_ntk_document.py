"""Independent protected NTK document/actual image-API byte and page-order verification."""
import base64
import hashlib
from html.parser import HTMLParser
import json
import re
from urllib.parse import urlsplit


def require(value, message):
    if not value:
        raise ValueError(message)


class Scripts(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.current = None
        self.scripts = []

    def handle_starttag(self, tag, attrs):
        if tag == 'script':
            require(self.current is None, 'nested script element')
            self.current = []

    def handle_data(self, data):
        if self.current is not None:
            self.current.append(data)

    def handle_endtag(self, tag):
        if tag == 'script' and self.current is not None:
            self.scripts.append(''.join(self.current))
            self.current = None


def document_roots(body):
    parser = Scripts()
    parser.feed(body.decode('utf-8', errors='strict'))
    parser.close()
    require(parser.current is None, 'unfinished document script')
    decoder = json.JSONDecoder()
    roots, chunks = [], []
    for script in parser.scripts:
        pushes = list(re.finditer(r'self\.__next_f\.push\(', script))
        if pushes:
            for push in pushes:
                value, _ = decoder.raw_decode(script[push.end():].lstrip())
                require(isinstance(value, list) and value, 'invalid Flight push')
                if value[0] == 1:
                    require(len(value) == 2 and isinstance(value[1], str), 'invalid Flight text chunk')
                    chunks.append(value[1])
        elif script.strip().startswith(('{', '[')):
            roots.append(json.loads(script))
    data = ''.join(chunks).encode('utf-8')
    position = 0
    while position < len(data):
        if data[position:position + 1] == b'\n':
            position += 1
            continue
        prefix = re.match(rb'[0-9a-fA-F]*:', data[position:])
        require(prefix is not None, 'invalid Flight record prefix')
        position += prefix.end()
        if data[position:position + 1] == b'T':
            length = re.match(rb'T([0-9a-fA-F]+),', data[position:])
            require(length is not None, 'invalid Flight text length')
            position += length.end() + int(length[1], 16)
            require(position <= len(data), 'truncated Flight text record')
        else:
            end = data.find(b'\n', position)
            if end < 0:
                end = len(data)
            line = data[position:end]
            if line.startswith((b'{', b'[')):
                roots.append(json.loads(line))
            else:
                require(b'imagesToken' not in line, 'unparsed viewer Flight record')
            position = end
    return roots


def descriptor(body, episode):
    found = []
    def walk(value):
        if isinstance(value, dict):
            if 'imagesToken' in value and 'episodeId' in value:
                found.append(value)
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)
    for root in document_roots(body):
        walk(root)
    require(len(found) == 1, 'document has no unique supported protected viewer descriptor')
    value = found[0]
    kind, work = episode['seriesKey'].strip('/').split('/')
    require(kind in ('webtoon', 'manhwa') and episode['episodeKey'].startswith(episode['seriesKey'] + '/'),
            'invalid NTK episode path')
    remote = episode['episodeKey'].rsplit('/', 1)[1]
    require(value['sourceWorkId'] == work and value['episodeId'] == remote, 'viewer descriptor identity mismatch')
    metas = value['imageMetas']
    require(isinstance(metas, list) and metas and
            [m['page'] for m in metas] == list(range(1, len(metas) + 1)), 'document page metadata is incomplete or unordered')
    return value, kind, work, remote, len(metas)


def load_authorization(directory, plan):
    index = json.loads((directory / 'index.json').read_bytes())
    require(type(index['overflow']) is int and index['overflow'] == 0, 'authorization observations overflowed')
    records = [r for r in index['records'] if all(r[k] == plan['episodeIdentity'][k]
               for k in ('sourceId', 'seriesKey', 'episodeKey')) and r['documentSha256'] == plan['documentSha256'] and
               r['documentReplaySha256'] == plan['documentReplaySha256'] and r['authEpoch'] == plan['authEpoch']]
    require(len(records) == 1, 'no unique NTK authorization for original document')
    record = records[0]
    require(re.fullmatch(r'authorization-[0-9]+\.json', record['payloadFile']), 'invalid authorization filename')
    payload = (directory / record['payloadFile']).read_bytes()
    require(len(payload) == record['payloadBytes'] and hashlib.sha256(payload).hexdigest() == record['payloadSha256'],
            'authorization payload changed')
    return record, payload


def verify(plan, body, authorization):
    require(hashlib.sha256(body).hexdigest() == plan['documentSha256'] and len(body) == plan['documentBytes'],
            'original NTK document bytes changed')
    episode = plan['episodeIdentity']
    require(episode['sourceId'] == 'ntk' and urlsplit(plan['finalDocumentUrl']).path == episode['episodeKey'],
            'NTK document belongs to another episode')
    value, kind, work, remote, count = descriptor(body, episode)
    require(authorization is not None, 'original NTK authorization evidence is missing')
    record, payload_bytes = authorization
    require(all(record[k] == episode[k] for k in ('sourceId', 'seriesKey', 'episodeKey')) and
            record['documentSha256'] == plan['documentSha256'] and
            record['documentReplaySha256'] == plan['documentReplaySha256'] and record['authEpoch'] == plan['authEpoch'],
            'NTK authorization document binding changed')
    require(0 < record['observedMonotonicNanos'] <= plan['observedAtNanos'] and
            0 < record['ackObservedElapsedRealtimeNanos'] <= record['documentRetiredElapsedRealtimeNanos'] and
            0 < record['manifestObservedElapsedRealtimeNanos'] <= record['documentRetiredElapsedRealtimeNanos'],
            'authorization observation order is invalid')
    require(hashlib.sha256(payload_bytes).hexdigest() == record['payloadSha256'] and len(payload_bytes) == record['payloadBytes'],
            'NTK authorization bytes changed')
    envelope = json.loads(payload_bytes)
    endpoint = f'/api/{kind}-images'
    final = urlsplit(envelope['responseUrl'])
    source = urlsplit(plan['finalDocumentUrl'])
    require(envelope['endpoint'] == endpoint and final.path == endpoint and
            (final.scheme, final.netloc) == (source.scheme, source.netloc) and
            envelope['responseStatus'] == 200 and envelope['ok'] is True, 'image API response identity/status mismatch')
    require(envelope['requestMethod'] == 'POST' and envelope['requestWorkId'] == work and
            envelope['requestEpisodeId'] == remote, 'image API request identity mismatch')
    for field in ('requestContentType', 'responseContentType'):
        require(envelope[field].split(';')[0].strip().lower() == 'application/json', 'image API is not JSON')
    token = envelope['requestToken']
    if token != value['imagesToken']:
        encoded = token.split('.')[0]
        require(re.fullmatch('[A-Za-z0-9_-]+', encoded), 'invalid dynamic request token')
        claims = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        require(claims.get('w') == work and claims.get('e') == remote and claims.get('t') == kind,
                'dynamic request token belongs to another episode')
    raw = base64.b64decode(envelope['responseBodyBase64'], validate=True)
    require(len(raw) == envelope['responseBodyBytes'], 'raw image API byte length changed')
    response = json.loads(raw.decode('utf-8', errors='strict'))
    require(response['ok'] is True and response['images'] == envelope['images'], 'raw response disagrees with browser envelope')
    images = response['images']
    require(len(images) == count and len(plan['pages']) == count and
            sorted(image['page'] for image in images) == list(range(1, count + 1)), 'image API page sequence is incomplete')
    for index, (page, image) in enumerate(zip(plan['pages'], sorted(images, key=lambda i: i['page']))):
        candidates = list(dict.fromkeys(url.strip() for url in [image['src'], *image.get('srcCandidates', [])] if url.strip()))
        require(candidates and all(urlsplit(url).scheme in ('http', 'https') and urlsplit(url).hostname for url in candidates),
                'image API contains invalid page candidates')
        require(page['ordinal'] == index and page['pageIdentity'] == {**episode, 'pageKey': f'p{index:04d}'} and
                page['sourceRecord'] == f'image-api-page:{index + 1}' and page['candidates'] == candidates,
                'page plan differs from original image API order/candidates')
    return {'independentDocumentPageOrderVerified': True, 'imageApiResponseBytesVerified': True,
            'imageApiResponseSha256': hashlib.sha256(raw).hexdigest(), 'authorizationSha256': record['payloadSha256'],
            'episodeIdentity': episode, 'pageCount': count, 'documentSha256': plan['documentSha256'],
            'contentRevision': plan['contentRevision'], 'pages': plan['pages'],
            'sourceResponseBytesBindingVerified': False, 'independentEpisodeCatalogOrderVerified': False,
            'wholeEpisodeVerified': False, 'physicalPresentationVerified': False, 'corpusCredit': 0}
