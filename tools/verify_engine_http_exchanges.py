"""Validate the sealed, actual-read HTTP ledger without crediting cache hits as requests."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify(directory):
    raw = (directory / 'events.jsonl').read_bytes()
    seal_raw = (directory / 'seal.json').read_bytes()
    seal = json.loads(seal_raw)
    events = [json.loads(line) for line in raw.splitlines()]
    require(events and seal['observations'] == len(events), 'missing or truncated observations')
    for key in ('overflow', 'lateObservations', 'activeObservedRequests'):
        require(type(seal[key]) is int and seal[key] == 0, f'incomplete ledger: {key}')
    exchanges = {}
    retained = 0
    documents = set()
    identity = ('channel', 'requestUrl', 'method', 'priority', 'requestBodySha256', 'requestBodyBytes')
    if any('preferQuic' in event for event in events):
        require(all(type(event.get('preferQuic')) is bool for event in events), 'incomplete protocol preference evidence')
        identity += ('preferQuic',)
    headers = ('statusCode', 'finalUrl', 'contentType', 'contentLength')
    for ordinal, event in enumerate(events, 1):
        require(type(event['ordinal']) is int and event['ordinal'] == ordinal, 'event ordinal gap')
        request = event['requestId']
        require(type(request) is int and request > 0, 'invalid request id')
        time = event['atMonotonicNs']
        require(type(time) is int and 0 < time <= seal['sealedAtMonotonicNs'], 'event outside seal')
        phase = event['phase']
        if phase == 'STARTED':
            require(request not in exchanges, 'duplicate request')
            require(event['bodyBytes'] == 0 and event['bodySha256'] is None, 'body before request')
            exchanges[request] = {'start': event, 'last': event, 'headers': None, 'body': None,
                                  'closed': False, 'failed': False}
        else:
            require(request in exchanges, 'event without request')
            item = exchanges[request]
            require(not item['closed'], 'event after terminal request')
            require(time >= item['last']['atMonotonicNs'], 'request clock reversed')
            require(all(event[k] == item['start'][k] for k in identity), 'request identity changed')
            if phase == 'HEADERS':
                require(item['headers'] is None and item['last']['phase'] == 'STARTED', 'duplicate/late headers')
                require(type(event['statusCode']) is int and 100 <= event['statusCode'] <= 599,
                        'invalid response status')
                require(isinstance(event['finalUrl'], str) and event['finalUrl'].startswith(('https://', 'http://')),
                        'missing final URL')
                item['headers'] = event
            elif phase == 'REQUEST_FAILED':
                require(item['headers'] is None and event['errorType'], 'invalid request failure')
                item['closed'] = True
            else:
                require(item['headers'] is not None, 'body without headers')
                require(all(event[k] == item['headers'][k] for k in headers), 'response identity changed')
                require(type(event['bodyBytes']) is int and event['bodyBytes'] >= item['last']['bodyBytes'],
                        'body byte count reversed')
                if phase == 'BODY_COMPLETE':
                    require(item['body'] is None and not item['failed'], 'duplicate/failed body completion')
                    require(isinstance(event['bodySha256'], str) and
                            re.fullmatch('[0-9a-f]{64}', event['bodySha256']), 'missing full body digest')
                    item['body'] = event
                elif phase == 'BODY_FAILED':
                    require(item['body'] is None and event['errorType'], 'invalid body failure')
                    item['failed'] = True
                elif phase == 'CLOSED':
                    require(item['body'] is None or event['bodyBytes'] == item['body']['bodyBytes'],
                            'bytes changed after EOF')
                    item['closed'] = True
                else:
                    raise ValueError('unknown HTTP phase')
            item['last'] = event
        filename = event['documentBodyFile']
        if filename is not None:
            require(phase == 'BODY_COMPLETE' and not event['documentBodyLimitExceeded'] and
                    filename == f'exchange-{request}-body.bin' and filename not in documents,
                    'invalid document export')
            body = (directory / filename).read_bytes()
            require(len(body) == event['bodyBytes'] and sha(body) == event['bodySha256'],
                    'HTTP document body mismatch')
            retained += len(body)
            documents.add(filename)
    require(all(item['closed'] for item in exchanges.values()), 'unclosed HTTP request')
    require(retained == seal['retainedDocumentBytesBeforeExport'], 'document byte accounting mismatch')
    require(documents == {p.name for p in directory.glob('exchange-*-body.bin')}, 'unaccounted document file')
    complete = [item['body'] for item in exchanges.values() if item['body'] is not None]
    return {'httpObservationHistoryVerified': True, 'eventsSha256': sha(raw), 'sealSha256': sha(seal_raw),
            'requestCount': len(exchanges), 'completeResponseCount': len(complete),
            'completeResponses': complete, 'sourceResponseBytesBindingVerified': False,
            'allNetworkRoutesObserved': False, 'corpusCredit': 0}


def bind_plan(plan, body, ledger, sources, source_directory, ntk_authorization=None,
              archived_http_directories=()):
    from verify_engine_episode_document import verify_plan
    from engine_cache_identity import cache_name
    verify_plan(plan, body, ntk_authorization)
    require(ledger.get('httpObservationHistoryVerified') is True, 'HTTP ledger is not verified')
    responses = ledger['completeResponses']
    documents = [r['requestId'] for r in responses if r['statusCode'] == 200 and
                 r['finalUrl'] == plan['finalDocumentUrl'] and r['bodySha256'] == sha(body) and
                 r['bodyBytes'] == len(body) and r['documentBodyFile'] is not None and
                 r['atMonotonicNs'] <= plan['observedAtNanos']]
    require(documents, 'no actual complete HTTP response matches the observed episode document')
    require(sources.get('success') is True, 'source export incomplete')
    archives = []
    seen = set()
    for directory in archived_http_directories:
        directory = Path(directory).resolve()
        require(directory not in seen, 'duplicate archived HTTP directory')
        seen.add(directory)
        # Re-read the actual sealed ledger, never a caller-supplied summary or
        # fabricated merged current-run ledger. Request IDs stay namespaced.
        proof = verify(directory)
        archives.append((directory, proof))
    bindings = []
    missing = []
    current_only = True
    for page in plan['pages']:
        originals = [s for s in sources['sources'] if any(
            b['pageIdentity'] == page['pageIdentity'] and b['contentRevision'] == plan['contentRevision']
            for b in s['cacheBindings'])]
        require(len(originals) <= 1, 'ambiguous original source binding')
        if not originals:
            missing.append({'pageIdentity': page['pageIdentity'], 'reason': 'original bytes unavailable'})
            continue
        original = originals[0]
        require(original['file'] == original['sha256'] + '.page' and
                re.fullmatch('[0-9a-f]{64}', original['sha256']), 'invalid original path')
        actual = (source_directory / original['file']).read_bytes()
        require(sha(actual) == original['sha256'], 'original source changed')
        cache_bindings = [b for b in original['cacheBindings'] if
                          b['pageIdentity'] == page['pageIdentity'] and
                          b['contentRevision'] == plan['contentRevision']]
        require(all(b.get('cacheFile') == cache_name(page['pageIdentity'], plan['contentRevision'], original['sha256'])
                    for b in cache_bindings), 'original cache page/revision binding changed')
        matches = [r['requestId'] for r in responses if r['channel'] == 'engine' and
                   r['statusCode'] == 200 and r['requestUrl'] in page['candidates'] and
                   r['bodySha256'] == original['sha256'] and r['bodyBytes'] == len(actual)]
        archived_matches = []
        if not matches:
            current_only = False
            for directory, proof in archives:
                for response in proof['completeResponses']:
                    if (response['channel'] == 'engine' and response['method'] == 'GET' and
                            response['statusCode'] == 200 and response['requestUrl'] in page['candidates'] and
                            response['bodySha256'] == original['sha256'] and response['bodyBytes'] == len(actual)):
                        archived_matches.append({'httpDirectory': str(directory),
                            'eventsSha256': proof['eventsSha256'], 'sealSha256': proof['sealSha256'],
                            'requestId': response['requestId'], 'requestUrl': response['requestUrl'],
                            'finalUrl': response['finalUrl'], 'bodySha256': response['bodySha256'],
                            'bodyBytes': response['bodyBytes']})
        if matches or archived_matches:
            bindings.append({'pageIdentity': page['pageIdentity'], 'sourceSha256': original['sha256'],
                             'matchingRequestIds': matches, 'archivedResponses': archived_matches,
                             'originEvidence': 'CURRENT_RUN_HTTP' if matches else 'SEPARATELY_SEALED_HTTP_ARCHIVE'})
        else:
            missing.append({'pageIdentity': page['pageIdentity'],
                            'reason': 'no observed complete HTTP body; cache is not origin evidence'})
    return {'episodeDocumentHttpBytesVerified': True, 'documentRequestIds': documents,
            'sourceResponseBytesBindingVerified': not missing, 'pageBindings': bindings,
            'allSourceResponsesObservedInCurrentRun': not missing and current_only,
            'archivedHttpLedgers': [{'directory': str(directory), 'eventsSha256': proof['eventsSha256'],
                                    'sealSha256': proof['sealSha256']} for directory, proof in archives],
            'archivedResponseScope': 'Byte provenance only; not current-run requests, timing, freshness or cross-run clock continuity.',
            'missingPageBindings': missing, 'httpEventsSha256': ledger['eventsSha256'],
            'documentSha256': sha(body), 'wholeEpisodeVerified': False, 'corpusCredit': 0}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.directory)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        report = {'httpObservationHistoryVerified': False, 'error': str(failure), 'corpusCredit': 0}
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'completeResponses'}))
    raise SystemExit(0 if report['httpObservationHistoryVerified'] else 1)
