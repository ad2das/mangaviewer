"""Explicit device-control hypothesis; not yet a qualifying pixel-reference profile."""
import numpy as np


def window_edge(edge_units, height, units=1024, subpixel_bits=8):
    if type(edge_units) is not int or type(height) is not int or height <= 0 or units != 1024 or subpixel_bits != 8:
        raise ValueError('unsupported raster-edge hypothesis parameters')
    f = np.float32
    clip = f(1) - f(f(2) * f(edge_units) / f(height * units))
    half = f(height / 2)
    window_gl = f(f(clip * half) + half)
    snapped_gl = float(np.rint(float(window_gl) * (1 << subpixel_bits))) / (1 << subpixel_bits)
    return height - snapped_gl


def predicted_upper_row(row, edge_units, height):
    return row + 0.5 <= window_edge(edge_units, height)
