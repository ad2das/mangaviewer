#!/usr/bin/env python3
import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--package", default="ml.melun.mangaview")
    args = parser.parse_args()

    source = Path("app/src/main/AndroidManifest.xml")
    out_dir = Path("app/src/bazel")
    out_dir.mkdir(parents=True, exist_ok=True)

    manifest = source.read_text(encoding="utf-8")
    manifest = manifest.replace("${applicationId}", args.package)
    if " package=" not in manifest.split(">", 1)[0]:
        manifest = manifest.replace(
            "<manifest ",
            (
                f'<manifest package="{args.package}" '
                f'android:versionCode="{args.version_code}" '
                f'android:versionName="{args.version_name}" '
            ),
            1,
        )

    (out_dir / "AndroidManifest.xml").write_text(manifest, encoding="utf-8")
    (out_dir / "LibraryManifest.xml").write_text(
        (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            f'package="{args.package}" />\n'
        ),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
