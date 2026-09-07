"""Discrete row support of the pinned independent pixel reference, not a coverage waiver."""
from fractions import Fraction
from functools import lru_cache
import hashlib
from pathlib import Path

from engine_source_row_coverage import require

MODEL = 'PILLOW_12_3_BILINEAR_22BIT_THEN_LINEAR_TEXTURE_V1'
COEFFICIENT_SCALE = 1 << 22


def reference_identity():
    import PIL
    from PIL import _imaging
    require(PIL.__version__ == '12.3.0', 'source sampling requires the pinned Pillow reference')
    return {'model': MODEL, 'pillowVersion': PIL.__version__,
            'imagingCoreSha256': hashlib.sha256(Path(_imaging.__file__).read_bytes()).hexdigest(),
            'modelSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'scope': 'Discrete dependencies of the verified independent pixel reference; not continuous-area or physical-display coverage.'}


@lru_cache(maxsize=8192)
def resize_weights(source_height, raster_height, raster_row):
    """Positive quantized weights, matching the pinned reference's vertical filter.

    Algorithm reference: https://github.com/python-pillow/Pillow/blob/12.3.0/src/libImaging/Resample.c
    """
    require(all(type(n) is int for n in (source_height, raster_height, raster_row)) and
            0 < source_height <= 2 ** 24 and raster_height > 0 and 0 <= raster_row < raster_height,
            'invalid source sampling geometry')
    ratio = source_height / raster_height
    radius = max(1.0, ratio)
    center = (raster_row + 0.5) * ratio
    lo = max(0, int(center - radius + 0.5))
    hi = min(source_height, int(center + radius + 0.5))
    weights = [(row, max(0.0, 1.0 - abs((row - center + 0.5) * (1.0 / radius))))
               for row in range(lo, hi)]
    total = sum(weight for _, weight in weights)
    require(total > 0, 'empty resize filter')
    quantized = [(row, int(0.5 + weight / total * COEFFICIENT_SCALE)) for row, weight in weights]
    return tuple((row, weight) for row, weight in quantized if weight > 0)


def sampled_rows(placement, units, captured_top, captured_bottom):
    """Rows contributing to an already verified, exclusively owned pixel band.

    These are reference-filter dependencies. They do not assert continuous-area
    coverage, physical scanout, or bitwise recovery of a downsampled original.
    """
    require(type(units) is int and units in (1, 1024) and
            type(captured_top) is int and type(captured_bottom) is int and
            captured_top < captured_bottom, 'invalid captured sampling band')
    top = Fraction(placement['screenTopUnits'], units)
    bottom = Fraction(placement['screenBottomUnits'], units)
    first, last = placement['rasterTop'], placement['rasterBottom']
    require(bottom > top and 0 <= first < last <= placement['rasterHeight'], 'invalid sampled raster crop')
    result = set()
    for screen_row in range(captured_top, captured_bottom):
        position = (Fraction(2 * screen_row + 1, 2) - top) * (last - first) / (bottom - top) - Fraction(1, 2)
        lower = position.numerator // position.denominator
        blend = position - lower
        for local, weight in ((lower, 1 - blend), (lower + 1, blend)):
            if weight <= 0:
                continue
            raster_row = first + min(last - first - 1, max(0, local))
            result.update(row for row, coefficient in resize_weights(
                placement['sourceHeight'], placement['rasterHeight'], raster_row)
                if placement['sourceTop'] <= row < placement['sourceBottom'])
    return result


def row_ranges(rows):
    result = []
    for row in sorted(set(rows)):
        if result and result[-1][1] == row:
            result[-1][1] = row + 1
        else:
            result.append([row, row + 1])
    return result
