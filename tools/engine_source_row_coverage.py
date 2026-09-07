"""Exact coverage accounting for declared original pages; episode-manifest authority is a separate gate."""
from fractions import Fraction


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identity(value):
    keys = ('sourceId', 'seriesKey', 'episodeKey', 'pageKey')
    require(isinstance(value, dict) and all(isinstance(value.get(k), str) and value[k] for k in keys),
            'source page identity is incomplete')
    return tuple(value[k] for k in keys)


def fraction(value):
    require(isinstance(value, list) and len(value) == 2 and all(type(n) is int for n in value) and value[1] > 0,
            'source endpoint is not an exact fraction')
    return Fraction(*value)


def merge(intervals):
    result = []
    for start, end in sorted(intervals):
        require(start < end, 'source interval is empty or inverted')
        if result and start <= result[-1][1]:
            result[-1] = (result[-1][0], max(end, result[-1][1]))
        else:
            result.append((start, end))
    return result


def encoded(interval):
    return [[n.numerator, n.denominator] for n in interval]


def coverage(pages, pixel_reports):
    require(bool(pages), 'declared page set is empty')
    declared = {}
    for page in pages:
        key = identity(page['pageIdentity'])
        require(key not in declared, 'declared page identity is duplicated')
        require(type(page.get('sourceHeight')) is int and page['sourceHeight'] > 0, 'source height is invalid')
        digest = page.get('sourceSha256')
        require(isinstance(digest, str) and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest),
                'source digest is invalid')
        declared[key] = (page, [])
    for report in pixel_reports:
        require(report.get('independentCapturedPixelsVerified') is True, 'captured pixels have not been verified')
        require(isinstance(report.get('frames'), list) and bool(report['frames']), 'pixel report has no frames')
        for frame in report['frames']:
            require(frame.get('capturedPixelsMatch') is True, 'frame pixels do not match')
            for band in frame['sourceBands']:
                key = identity(band['pageIdentity'])
                require(key in declared, 'capture references an undeclared page')
                page, intervals = declared[key]
                require(band['sourceSha256'] == page['sourceSha256'], 'capture references another source version')
                start, end = fraction(band['sourceTopFraction']), fraction(band['sourceBottomFraction'])
                require(0 <= start < end <= page['sourceHeight'], 'captured source interval is out of bounds')
                intervals.append((start, end))
    reports = []
    for page, intervals in declared.values():
        covered = merge(intervals)
        cursor = Fraction(0)
        missing = []
        for start, end in covered:
            if cursor < start:
                missing.append((cursor, start))
            cursor = end
        if cursor < page['sourceHeight']:
            missing.append((cursor, Fraction(page['sourceHeight'])))
        # Any positive uncovered part of [row,row+1) makes that original row incomplete.
        incomplete_rows = merge([(a.numerator // a.denominator,
                                  -(-b.numerator // b.denominator)) for a, b in missing])
        missing_count = sum(b - a for a, b in incomplete_rows)
        reports.append({'pageIdentity': page['pageIdentity'], 'sourceSha256': page['sourceSha256'],
            'sourceHeight': page['sourceHeight'], 'observedSourceIntervals': [encoded(v) for v in covered],
            'missingSourceIntervals': [encoded(v) for v in missing],
            'incompleteSourceRowRanges': [list(v) for v in incomplete_rows],
            'fullyObservedSourceRows': page['sourceHeight'] - missing_count,
            'missingOrPartialSourceRows': missing_count})
    return {'allDeclaredSourceRowsObserved': all(not p['missingSourceIntervals'] for p in reports),
        'pages': reports, 'wholeEpisodeVerified': False, 'physicalPresentationVerified': False, 'corpusCredit': 0,
        'scope': 'Coverage of declared page identities/versions; independent complete episode order and final stop remain required.'}


def sampling_coverage(pages, pixel_reports):
    """Aggregate reference dependencies without changing the continuous coverage gate."""
    from engine_source_sampling import reference_identity
    require(bool(pages) and bool(pixel_reports), 'missing sampling inventory or pixel reports')
    declared = {}
    for page in pages:
        key = identity(page['pageIdentity'])
        require(key not in declared and type(page['sourceHeight']) is int and page['sourceHeight'] > 0,
                'invalid or duplicate sampled source page')
        digest = page.get('sourceSha256')
        require(isinstance(digest, str) and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest),
                'sampled source digest is invalid')
        declared[key] = (page, [])
    expected = reference_identity()
    for report in pixel_reports:
        require(report.get('independentCapturedPixelsVerified') is True and bool(report.get('frames')),
                'sampled rows require verified captured pixels')
        require(report.get('sourceSamplingReference') == expected, 'sampling reference changed or absent')
        for frame in report['frames']:
            require(frame.get('capturedPixelsMatch') is True, 'sampled frame pixels do not match')
            for band in frame['sourceBands']:
                key = identity(band['pageIdentity'])
                require(key in declared, 'sampled source page is undeclared')
                page, ranges = declared[key]
                require(band['sourceSha256'] == page['sourceSha256'], 'sampled source version changed')
                for span in band['sampledSourceRowRanges']:
                    require(isinstance(span, list) and len(span) == 2 and all(type(n) is int for n in span) and
                            0 <= span[0] < span[1] <= page['sourceHeight'], 'invalid discrete source-row range')
                    ranges.append(tuple(span))
    results = []
    for page, ranges in declared.values():
        observed = merge(ranges)
        missing, cursor = [], 0
        for start, end in observed:
            if start > cursor:
                missing.append([cursor, start])
            cursor = end
        if cursor < page['sourceHeight']:
            missing.append([cursor, page['sourceHeight']])
        results.append({'pageIdentity': page['pageIdentity'], 'sourceSha256': page['sourceSha256'],
                        'sourceHeight': page['sourceHeight'], 'sampledSourceRowRanges': [list(v) for v in observed],
                        'missingReferenceSourceRowRanges': missing,
                        'referenceSampledSourceRows': sum(b - a for a, b in observed)})
    return {'sourceSamplingReference': expected, 'pages': results,
            'allDeclaredSourceRowsSampledInReference': all(not p['missingReferenceSourceRowRanges'] for p in results),
            'continuousAreaCoverageVerified': False, 'physicalPresentationVerified': False,
            'wholeEpisodeVerified': False, 'corpusCredit': 0}
