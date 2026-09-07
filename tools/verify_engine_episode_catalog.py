"""Independently bind episode adjacency to complete original HTTP catalog documents."""
import hashlib
from html.parser import HTMLParser
import json
import re
from urllib.parse import parse_qs, urljoin, urlsplit

from verify_engine_episode_document import decode_document, require


def parse_ntk_api(body, url, series):
    match = re.fullmatch(r'/(webtoon|manhwa)/([^/?#]+)', series['seriesKey'])
    require(series['sourceId'] == 'ntk' and match is not None, 'invalid NTK series identity')
    require(urlsplit(url).path == f'/api/{match[1]}/{match[2]}/episodes',
            'NTK catalog belongs to another series')
    payload = json.loads(body.decode('utf-8', errors='strict'))
    total, rows = payload.get('total'), payload.get('episodes')
    require(type(total) is int and total > 0 and isinstance(rows, list) and len(rows) == total,
            'NTK catalog does not contain every advertised episode')
    records = []
    for row in rows:
        require(isinstance(row, dict), 'invalid NTK catalog row')
        key, number = row.get('sourceEpisodeId'), row.get('epNo')
        require(isinstance(key, str) and re.fullmatch(r'[^/?#\s]+', key) is not None and
                key not in ('.', '..'), 'unsupported NTK catalog episode identity')
        require(type(number) is int and number > 0, 'NTK catalog lacks explicit episode sequence')
        records.append((number, series['seriesKey'] + '/' + key))
    require(len({key for _, key in records}) == total and len({n for n, _ in records}) == total,
            'NTK catalog contains duplicate identities or ambiguous sequence')
    return [key for _, key in sorted(records)]


class NtkHtmlCatalog(HTMLParser):
    """Collect only the link/script material used by an NTK series document."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.anchors = []
        self.scripts = []
        self._stack = []
        self._anchor = None
        self._script = None

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag == 'a' and self._anchor is None:
            for _, parent_attrs in reversed(self._stack):
                for name in ('data-episode-number', 'data-epno', 'data-ep-no'):
                    if name in parent_attrs and name not in attrs:
                        attrs[name] = parent_attrs[name]
            self._anchor = {'attributes': attrs, 'text': []}
        if tag == 'script' and self._script is None:
            self._script = {'attributes': attrs, 'text': []}
        if tag not in {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
                       'link', 'meta', 'param', 'source', 'track', 'wbr'}:
            self._stack.append((tag, attrs))

    def handle_startendtag(self, tag, attributes):
        self.handle_starttag(tag, attributes)
        self.handle_endtag(tag)

    def handle_data(self, data):
        if self._anchor is not None:
            self._anchor['text'].append(data)
        if self._script is not None:
            self._script['text'].append(data)

    def handle_endtag(self, tag):
        if tag == 'a' and self._anchor is not None:
            self.anchors.append(self._anchor)
            self._anchor = None
        if tag == 'script' and self._script is not None:
            self.scripts.append((self._script['attributes'], ''.join(self._script['text'])))
            self._script = None
        for index in range(len(self._stack) - 1, -1, -1):
            if self._stack[index][0] == tag:
                del self._stack[index:]
                break


def _ntk_series_match(series):
    match = re.fullmatch(r'/(webtoon|manhwa)/([^/?#\s]+)', series['seriesKey'])
    require(series['sourceId'] == 'ntk' and match is not None, 'invalid NTK series identity')
    return match


def _ntk_page_query(url, series_path):
    parts = urlsplit(url)
    if parts.path != series_path:
        return None
    query = parse_qs(parts.query, keep_blank_values=True)
    if 'epage' not in query:
        return None
    require(set(query) == {'epage'} and len(query['epage']) == 1,
            'NTK catalog page query is ambiguous')
    raw = query['epage'][0]
    require(re.fullmatch(r'[0-9]+', raw) is not None and int(raw) > 0,
            'NTK catalog page number is invalid')
    return int(raw)


def _ntk_explicit_sequence(attributes, text):
    for name in ('data-epno', 'data-ep-no', 'data-episode-number', 'data-number'):
        if name in attributes:
            raw = attributes[name].strip()
            require(re.fullmatch(r'[0-9]+', raw) is not None and int(raw) > 0,
                    'NTK episode sequence is invalid')
            return int(raw)
    matches = re.findall(r'(?<![0-9.-])([0-9]+(?:\.[0-9]+)?)\s*화', text)
    require(len(matches) <= 1, 'NTK episode sequence is ambiguous')
    if not matches:
        return None
    raw = matches[0]
    require('.' not in raw and int(raw) > 0, 'NTK episode sequence is not an integer')
    return int(raw)


def parse_ntk_html(body, url, series):
    """Parse one exact NTK series-document page for an independently ordered catalog."""
    _ntk_series_match(series)
    parts = urlsplit(url)
    series_path = series['seriesKey']
    require(parts.path == series_path and not parts.fragment,
            'NTK catalog belongs to another series')
    query = parse_qs(parts.query, keep_blank_values=True)
    require(set(query).issubset({'epage'}) and all(len(values) == 1 for values in query.values()),
            'NTK catalog query is not an exact page query')
    raw_page = query.get('epage', ['1'])[0] if 'epage' in query else '1'
    require(re.fullmatch(r'[0-9]+', raw_page) is not None and int(raw_page) > 0,
            'NTK catalog page number is invalid')
    page = int(raw_page)

    parser = NtkHtmlCatalog()
    parser.feed(decode_document(body))
    parser.close()
    records = {}
    origins = {}

    def add_record(key, sequence, origin):
        require(sequence is not None, 'NTK episode lacks explicit sequence')
        previous = records.get(key)
        if previous is not None:
            require(previous == sequence and {origins[key], origin} == {'anchor', 'json'},
                    'NTK catalog contains duplicate or conflicting episode identity')
            origins[key] = 'anchor+json'
            return
        records[key] = sequence
        origins[key] = origin

    base = url
    nav_labels = ('목록', '최신화 보기', '첫화부터', '처음부터', '정주행', '이어보기', '전체보기',
                  'latest', 'first', 'update')
    prefix = series_path + '/'
    for anchor in parser.anchors:
        attrs = anchor['attributes']
        target = urlsplit(urljoin(base, attrs.get('href', '')))
        page_link = _ntk_page_query(target.geturl(), series_path)
        if page_link is not None:
            continue
        if target.netloc != parts.netloc or not target.path.startswith(prefix):
            continue
        require(not target.query and not target.fragment, 'NTK episode identity has a query or fragment')
        key_suffix = target.path[len(prefix):]
        require(re.fullmatch(r'[\w.-]{1,200}', key_suffix, re.UNICODE) is not None,
                'NTK episode identity is malformed')
        text = ' '.join(''.join(anchor['text']).split())
        if any(label in text.lower() for label in nav_labels):
            continue
        sequence = _ntk_explicit_sequence(attrs, text)
        require(sequence is not None or 'ep-row' not in attrs.get('class', ''),
                'NTK episode row lacks explicit sequence')
        add_record(series_path + '/' + key_suffix, sequence, 'anchor')

    def walk(value, in_episode_list=False):
        if isinstance(value, list):
            for item in value:
                walk(item, in_episode_list)
            return
        if not isinstance(value, dict):
            return
        if in_episode_list:
            raw_key = value.get('sourceEpisodeId', value.get('episodeId', value.get('id')))
            raw_sequence = value.get('epNo', value.get('number'))
            if raw_key is not None:
                require(isinstance(raw_key, (str, int)) and not isinstance(raw_key, bool),
                        'NTK embedded episode identity is malformed')
                key_suffix = str(raw_key).strip()
                require(re.fullmatch(r'[\w.-]{1,200}', key_suffix, re.UNICODE) is not None,
                        'NTK embedded episode identity is malformed')
                require(isinstance(raw_sequence, int) and not isinstance(raw_sequence, bool) and raw_sequence > 0,
                        'NTK embedded episode sequence is missing or invalid')
                add_record(series_path + '/' + key_suffix, raw_sequence, 'json')
        for key, child in value.items():
            walk(child, in_episode_list or key in ('episodes', 'episodeList'))

    for attributes, script in parser.scripts:
        raw = script.strip()
        # Parse only a standalone catalog JSON block. Next.js/RSC scripts are
        # intentionally ignored; their generic string payload is not an
        # independent episode-order contract.
        is_catalog_json = raw.startswith('{"episodes"') or raw.startswith('{"episodeList"')
        if attributes.get('type', '').lower() == 'application/json' and '"episodes"' in raw:
            is_catalog_json = True
        if not is_catalog_json:
            continue
        try:
            root = json.loads(raw)
        except (TypeError, ValueError) as failure:
            raise ValueError('NTK embedded catalog JSON is malformed') from failure
        require(isinstance(root, dict) and any(key in root for key in ('episodes', 'episodeList')),
                'NTK embedded catalog JSON has no episode list')
        walk(root)

    require(records, 'NTK catalog page contains no episodes')
    sequence_values = list(records.values())
    require(len(sequence_values) == len(set(sequence_values)),
            'NTK catalog contains duplicate or ambiguous episode sequence')
    linked_pages = []
    for anchor in parser.anchors:
        target = urlsplit(urljoin(base, anchor['attributes'].get('href', '')))
        if target.netloc == parts.netloc and target.path == series_path and 'epage' in parse_qs(target.query, keep_blank_values=True):
            linked_pages.append(_ntk_page_query(target.geturl(), series_path))
    linked_pages = [value for value in linked_pages if value is not None]
    last_page = max([page, *linked_pages])
    ordered_records = sorted(((sequence, key) for key, sequence in records.items()))
    return {'page': page, 'lastPage': last_page, 'records': ordered_records,
            'episodeKeys': [key for _, key in ordered_records]}


def verify_ntk(plan, ledger, http_directory):
    episode = plan['episodeIdentity']
    series = {k: episode[k] for k in ('sourceId', 'seriesKey')}
    route = '/api' + series['seriesKey'] + '/episodes'
    origin = urlsplit(plan['finalDocumentUrl'])
    catalogs, used = [], []
    html_candidates, html_used = [], []
    api_parse_errors = []
    for response in ledger['completeResponses']:
        url = urlsplit(response['finalUrl'])
        if (url.scheme, url.netloc) != (origin.scheme, origin.netloc):
            continue
        is_api = url.path == route
        is_html = url.path == series['seriesKey']
        if not is_api and not is_html:
            continue
        if response['statusCode'] != 200 or response['documentBodyFile'] is None or response['atMonotonicNs'] > plan['observedAtNanos']:
            continue
        require(response['documentBodyFile'] == f"exchange-{response['requestId']}-body.bin",
                'invalid NTK catalog body filename')
        body = (http_directory / response['documentBodyFile']).read_bytes()
        require(hashlib.sha256(body).hexdigest() == response['bodySha256'] and len(body) == response['bodyBytes'],
                'catalog HTTP bytes changed')
        if is_api:
            try:
                catalogs.append(parse_ntk_api(body, response['finalUrl'], series))
            except ValueError as failure:
                api_parse_errors.append(failure)
            else:
                used.append({'requestId': response['requestId'], 'bodySha256': response['bodySha256']})
        else:
            html_candidates.append((body, response))
    if catalogs:
        require(not api_parse_errors, str(api_parse_errors[0]) if api_parse_errors else '')
        ordered = catalogs[0]
        require(all(keys == ordered for keys in catalogs), 'NTK catalog changed within the observed episode')
    else:
        if not html_candidates and api_parse_errors:
            raise api_parse_errors[0]
        require(html_candidates, 'complete original NTK API or HTML catalog response is missing')
        by_page = {}
        last_page = 0
        for body, response in html_candidates:
            parsed = parse_ntk_html(body, response['finalUrl'], series)
            page = parsed['page']
            previous = by_page.get(page)
            if previous is not None:
                require(previous['records'] == parsed['records'] and
                        previous['lastPage'] == parsed['lastPage'],
                        'NTK HTML catalog page changed within the observed episode')
            else:
                by_page[page] = parsed
            last_page = max(last_page, parsed['lastPage'])
            html_used.append({'requestId': response['requestId'], 'bodySha256': response['bodySha256']})
        require(sorted(by_page) == list(range(1, last_page + 1)),
                'NTK HTML catalog pages are missing')
        records = []
        seen_keys, seen_sequences = set(), set()
        for page in sorted(by_page):
            for sequence, key in by_page[page]['records']:
                require(key not in seen_keys and sequence not in seen_sequences,
                        'NTK HTML catalog contains duplicate episode identity or sequence')
                seen_keys.add(key)
                seen_sequences.add(sequence)
                records.append((sequence, key))
        ordered = [key for _, key in sorted(records)]
        used = html_used
    require(episode['episodeKey'] in ordered, 'episode is absent from complete catalog')
    at = ordered.index(episode['episodeKey'])
    previous = {**series, 'episodeKey': ordered[at - 1]} if at else None
    following = {**series, 'episodeKey': ordered[at + 1]} if at + 1 < len(ordered) else None
    require(plan['navigationKnown'] is True and plan['previousEpisode'] == previous and
            plan['nextEpisode'] == following, 'viewer adjacency disagrees with original catalog order')
    return {'independentEpisodeCatalogOrderVerified': True, 'episodeIdentity': episode,
            'orderedEpisodeKeys': ordered, 'catalogResponses': used, 'httpEventsSha256': ledger['eventsSha256'],
            'wholeEpisodeVerified': False, 'corpusCredit': 0}


class Catalog(HTMLParser):
    VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.rows = []
        self.count_text = []

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag == 'a' and 'ep-item' in attrs.get('class', '').split():
            self.rows.append(attrs)
        if tag not in self.VOID:
            self.stack.append((tag, attrs))

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                break

    def handle_data(self, data):
        if any('list-header-title' in attrs.get('class', '').split() for _, attrs in self.stack):
            self.count_text.append(data)


def parse_catalog(body, url, series):
    match = re.fullmatch(r'(comic|webtoon):([0-9]+)', series['seriesKey'])
    require(series['sourceId'] == 'wfwf' and match is not None, 'unsupported catalog source/series')
    route, viewer = ('/cl', '/cv') if match[1] == 'comic' else ('/list', '/view')
    parts = urlsplit(url)
    query = parse_qs(parts.query)
    require(parts.path == route and query.get('toon') == [match[2]], 'catalog belongs to another series')
    page = query.get('pg', ['1'])
    require(len(page) == 1 and page[0].isdigit() and int(page[0]) > 0, 'invalid catalog page')
    parser = Catalog()
    parser.feed(decode_document(body))
    parser.close()
    total = re.fullmatch(r'\s*총\s*([0-9,]+)\s*화\s*', ''.join(parser.count_text))
    require(total is not None, 'catalog lacks a unique authoritative total')
    keys = []
    for attrs in parser.rows:
        target = urlsplit(urljoin(url, attrs['href']))
        values = parse_qs(target.query)
        number = values.get('num', [])
        require(target.netloc == parts.netloc and target.path == viewer and
                values.get('toon') == [match[2]] and len(number) == 1 and
                re.fullmatch('[1-9][0-9]*', number[0]), 'catalog row has wrong episode identity')
        require(attrs.get('data-num') == number[0], 'catalog row number disagrees with target URL')
        keys.append(number[0])
    require(keys and len(keys) == len(set(keys)), 'catalog rows are empty or duplicated')
    numbers = list(map(int, keys))
    require(numbers == sorted(numbers) or numbers == sorted(numbers, reverse=True), 'catalog DOM order is not monotonic')
    return {'page': int(page[0]), 'total': int(total[1].replace(',', '')), 'episodeKeys': keys}


def verify(plan, ledger, http_directory):
    require(ledger.get('httpObservationHistoryVerified') is True, 'HTTP observation history is incomplete')
    if plan['episodeIdentity']['sourceId'] == 'ntk':
        return verify_ntk(plan, ledger, http_directory)
    episode = plan['episodeIdentity']
    series = {k: episode[k] for k in ('sourceId', 'seriesKey')}
    require(series['sourceId'] == 'wfwf', 'unsupported episode catalog source')
    kind, title_id = series['seriesKey'].split(':')
    route = '/cl' if kind == 'comic' else '/list'
    pages = {}
    used = []
    for response in ledger['completeResponses']:
        url = urlsplit(response['finalUrl'])
        if url.path != route or parse_qs(url.query).get('toon') != [title_id]:
            continue
        if response['statusCode'] != 200 or response['documentBodyFile'] is None or response['atMonotonicNs'] > plan['observedAtNanos']:
            continue
        body = (http_directory / response['documentBodyFile']).read_bytes()
        require(hashlib.sha256(body).hexdigest() == response['bodySha256'] and len(body) == response['bodyBytes'],
                'catalog HTTP bytes changed')
        parsed = parse_catalog(body, response['finalUrl'], series)
        index = parsed['page']
        require(index not in pages or pages[index] == parsed, 'catalog changed within the observed episode')
        pages[index] = parsed
        used.append({'requestId': response['requestId'], 'bodySha256': response['bodySha256']})
    require(pages and sorted(pages) == list(range(1, max(pages) + 1)), 'catalog pages are missing')
    totals = {page['total'] for page in pages.values()}
    require(len(totals) == 1, 'catalog total changed across pages')
    keys = [key for index in sorted(pages) for key in pages[index]['episodeKeys']]
    require(len(keys) == len(set(keys)) == next(iter(totals)), 'catalog does not contain every advertised episode')
    numbers = list(map(int, keys))
    require(numbers == sorted(numbers) or numbers == sorted(numbers, reverse=True), 'catalog page order is inconsistent')
    ordered = sorted(keys, key=int)
    require(episode['episodeKey'] in ordered, 'episode is absent from complete catalog')
    at = ordered.index(episode['episodeKey'])
    expected_previous = {**series, 'episodeKey': ordered[at - 1]} if at else None
    expected_next = {**series, 'episodeKey': ordered[at + 1]} if at + 1 < len(ordered) else None
    require(plan['navigationKnown'] is True and plan['previousEpisode'] == expected_previous and
            plan['nextEpisode'] == expected_next, 'viewer adjacency disagrees with original catalog order')
    return {'independentEpisodeCatalogOrderVerified': True, 'episodeIdentity': episode,
            'orderedEpisodeKeys': ordered, 'catalogResponses': used, 'httpEventsSha256': ledger['eventsSha256'],
            'wholeEpisodeVerified': False, 'corpusCredit': 0}
