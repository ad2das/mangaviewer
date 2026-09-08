"""Allocate monotonically increasing sideload versions and describe the exact signed APK."""
import argparse
import hashlib
import json
import re
from pathlib import Path

MAX_VERSION = 2_147_483_647
APK_NAME = re.compile(r"mangaViewer_(\d+)-debug\.apk")


def next_version(base, assets):
    if not 1 <= base < MAX_VERSION:
        raise ValueError("Base Android versionCode leaves no upgrade space")
    published = [int(match[1]) for item in assets if (match := APK_NAME.fullmatch(item["name"]))]
    value = max([base, *published]) + 1
    if value > MAX_VERSION:
        raise ValueError("Android versionCode is exhausted; refusing a duplicate or downgrade")
    return value


def metadata(version, version_name, repo, tag, apk):
    if not 1 <= version <= MAX_VERSION:
        raise ValueError("Invalid Android versionCode")
    apk = Path(apk)
    if apk.name != f"mangaViewer_{version}-debug.apk" or not apk.is_file():
        raise ValueError("Signed APK filename does not match its release version")
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"version": version, "versionName": version_name,
            "link": f"https://github.com/{repo}/releases/download/{tag}/{apk.name}",
            "sha256": digest, "size": apk.stat().st_size}


def main():
    parser = argparse.ArgumentParser()
    actions = parser.add_subparsers(dest="action", required=True)
    allocate = actions.add_parser("next")
    allocate.add_argument("--base", type=int, required=True)
    allocate.add_argument("--assets", type=Path, required=True)
    describe = actions.add_parser("metadata")
    describe.add_argument("--version", type=int, required=True)
    describe.add_argument("--version-name", required=True)
    describe.add_argument("--repo", required=True)
    describe.add_argument("--tag", required=True)
    describe.add_argument("--apk", type=Path, required=True)
    describe.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "next":
        print(next_version(args.base, json.loads(args.assets.read_text(encoding="utf-8"))))
    else:
        args.output.write_text(json.dumps(metadata(args.version, args.version_name, args.repo, args.tag, args.apk),
                                          separators=(",", ":")), encoding="utf-8")


if __name__ == "__main__":
    main()
