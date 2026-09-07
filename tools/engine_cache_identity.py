"""Independent encoding of an immutable engine cache object's page and revision."""
import hashlib
import re
import struct

from engine_source_row_coverage import identity, require


def cache_name(page, revision, digest):
    require(isinstance(revision, str) and bool(revision), 'content revision is missing')
    require(isinstance(digest, str) and re.fullmatch(r'[0-9a-f]{64}', digest), 'invalid source digest')
    fields = [part.encode('utf-8') for part in identity(page)]
    key = hashlib.sha256(b''.join(struct.pack('>I', len(part)) + part for part in fields)).hexdigest()
    version = hashlib.sha256(revision.encode('utf-8')).hexdigest()
    return f'{key}-{version}-{digest}.page'
