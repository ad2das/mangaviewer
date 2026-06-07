#!/usr/bin/env python3
import argparse
import shutil
import struct
import zipfile
from pathlib import Path


UTF8_FLAG = 0x00000100
TYPE_INT_DEC = 0x10
TYPE_STRING = 0x03


def read_u16(data, offset):
    return struct.unpack_from("<H", data, offset)[0]


def read_u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def write_u32(data, offset, value):
    struct.pack_into("<I", data, offset, value)


def decode_length8(data, offset):
    first = data[offset]
    if first & 0x80:
        return ((first & 0x7F) << 8) | data[offset + 1], 2
    return first, 1


def decode_length16(data, offset):
    first = read_u16(data, offset)
    if first & 0x8000:
        return ((first & 0x7FFF) << 16) | read_u16(data, offset + 2), 4
    return first, 2


class StringPool:
    def __init__(self, data, offset):
        self.data = data
        self.offset = offset
        self.header_size = read_u16(data, offset + 2)
        self.size = read_u32(data, offset + 4)
        self.string_count = read_u32(data, offset + 8)
        self.flags = read_u32(data, offset + 16)
        self.strings_start = read_u32(data, offset + 20)
        self.utf8 = bool(self.flags & UTF8_FLAG)
        self.offsets = [
            read_u32(data, offset + self.header_size + i * 4)
            for i in range(self.string_count)
        ]
        self.strings = [self._read_string(i) for i in range(self.string_count)]

    def _string_abs_offset(self, index):
        return self.offset + self.strings_start + self.offsets[index]

    def _read_string(self, index):
        pos = self._string_abs_offset(index)
        if self.utf8:
            _, n1 = decode_length8(self.data, pos)
            byte_len, n2 = decode_length8(self.data, pos + n1)
            start = pos + n1 + n2
            return self.data[start : start + byte_len].decode("utf-8")
        char_len, n1 = decode_length16(self.data, pos)
        start = pos + n1
        return self.data[start : start + char_len * 2].decode("utf-16le")

    def set_same_size(self, index, value):
        pos = self._string_abs_offset(index)
        if self.utf8:
            _, n1 = decode_length8(self.data, pos)
            byte_len, n2 = decode_length8(self.data, pos + n1)
            encoded = value.encode("utf-8")
            if len(encoded) != byte_len:
                raise ValueError(
                    f"replacement string byte length differs: {len(encoded)} != {byte_len}"
                )
            start = pos + n1 + n2
            self.data[start : start + byte_len] = encoded
        else:
            char_len, n1 = decode_length16(self.data, pos)
            encoded = value.encode("utf-16le")
            if len(value) != char_len:
                raise ValueError(
                    f"replacement string length differs: {len(value)} != {char_len}"
                )
            start = pos + n1
            self.data[start : start + char_len * 2] = encoded
        self.strings[index] = value


def patch_manifest(manifest, version_code, version_name):
    data = bytearray(manifest)
    if read_u16(data, 0) != 0x0003:
        raise ValueError("not an Android binary XML document")

    offset = read_u16(data, 2)
    pool = None
    patched_code = False
    patched_name = False

    while offset < len(data):
        chunk_type = read_u16(data, offset)
        header_size = read_u16(data, offset + 2)
        chunk_size = read_u32(data, offset + 4)

        if chunk_type == 0x0001 and pool is None:
            pool = StringPool(data, offset)
        elif chunk_type == 0x0102:
            if pool is None:
                raise ValueError("start element appeared before string pool")
            attr_ext = offset + 16
            attr_start = read_u16(data, attr_ext + 8)
            attr_size = read_u16(data, attr_ext + 10)
            attr_count = read_u16(data, attr_ext + 12)
            attrs = attr_ext + attr_start
            for i in range(attr_count):
                attr = attrs + i * attr_size
                name_index = read_u32(data, attr + 4)
                if name_index >= len(pool.strings):
                    continue
                name = pool.strings[name_index]
                value_type = data[attr + 15]
                value_data = read_u32(data, attr + 16)
                raw_value = read_u32(data, attr + 8)
                if name == "versionCode":
                    write_u32(data, attr + 16, version_code)
                    data[attr + 15] = TYPE_INT_DEC
                    patched_code = True
                elif name == "versionName":
                    string_index = value_data if value_type == TYPE_STRING else raw_value
                    if string_index == 0xFFFFFFFF:
                        raise ValueError("versionName has no string value")
                    pool.set_same_size(string_index, version_name)
                    write_u32(data, attr + 8, string_index)
                    data[attr + 15] = TYPE_STRING
                    write_u32(data, attr + 16, string_index)
                    patched_name = True

        offset += chunk_size

    if not patched_code:
        raise ValueError("versionCode attribute not found")
    if not patched_name:
        raise ValueError("versionName attribute not found")
    return bytes(data)


def rewrite_apk(input_apk, output_apk, manifest):
    with zipfile.ZipFile(input_apk, "r") as zin, zipfile.ZipFile(
        output_apk, "w", compression=zipfile.ZIP_DEFLATED
    ) as zout:
        for item in zin.infolist():
            name = item.filename
            upper = name.upper()
            if upper.startswith("META-INF/"):
                continue
            data = manifest if name == "AndroidManifest.xml" else zin.read(item)
            info = zipfile.ZipInfo(name, item.date_time)
            info.compress_type = item.compress_type
            info.external_attr = item.external_attr
            zout.writestr(info, data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    args = parser.parse_args()

    input_apk = Path(args.input)
    output_apk = Path(args.output)
    with zipfile.ZipFile(input_apk, "r") as apk:
        manifest = apk.read("AndroidManifest.xml")
    patched = patch_manifest(manifest, args.version_code, args.version_name)

    tmp = output_apk.with_suffix(output_apk.suffix + ".tmp")
    rewrite_apk(input_apk, tmp, patched)
    if output_apk.exists():
        output_apk.unlink()
    shutil.move(tmp, output_apk)


if __name__ == "__main__":
    main()
