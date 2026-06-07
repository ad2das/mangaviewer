#!/usr/bin/env bash
set -euo pipefail

repo="${REPO_NAME:-${GITHUB_REPOSITORY:-ad2das/mangaviewer}}"
release_tag="${RELEASE_TAG:-main-latest}"
target_branch="${GITHUB_REF_NAME:-main}"
ci_release_patch_floor="${CI_RELEASE_PATCH_FLOOR:-1000000}"
run_number="${GITHUB_RUN_NUMBER:-0}"

patch_base="$(sed -nE 's/^[[:space:]]*def defaultReleasePatch = ([0-9]+).*$/\1/p' app/build.gradle | head -n 1)"
if [ -z "${patch_base}" ]; then
  echo "Could not find defaultReleasePatch in app/build.gradle" >&2
  exit 1
fi

release_patch="${RELEASE_PATCH:-$((patch_base + ci_release_patch_floor + run_number))}"
date_code="$(date +%y%m%d)"
version_code="$((2112000000 + 10#${date_code} + release_patch))"
apk_name="mangaViewer_${version_code}-debug.apk"
apk_path="app/build/outputs/apk/debug/${apk_name}"
download_url="https://github.com/${repo}/releases/download/${release_tag}/${apk_name}"

echo "releasePatchBase=${patch_base} ciFloor=${ci_release_patch_floor} runNumber=${run_number} releasePatch=${release_patch}"
echo "versionCode=${version_code}"
echo "apk=${apk_name}"

VERSION_CODE="${version_code}" DOWNLOAD_URL="${download_url}" python3 - <<'PY'
import json
import os
import re
from pathlib import Path

version_code = int(os.environ["VERSION_CODE"])
download_url = os.environ["DOWNLOAD_URL"]

Path("version.json").write_text(
    json.dumps({"version": version_code, "link": download_url}, separators=(",", ":")),
    encoding="utf-8",
)

path = Path("releases.html")
text = path.read_text(encoding="utf-8")
text = re.sub(r'tag_name:\s*"\d+"', f'tag_name: "{version_code}"', text, count=1)
text = re.sub(
    r'browser_download_url:\s*"[^"]*mangaViewer_\d+-debug\.apk"',
    f'browser_download_url: "{download_url}"',
    text,
    count=1,
)
path.write_text(text, encoding="utf-8")
PY

chmod +x ./gradlew
./gradlew --configuration-cache --build-cache --parallel \
  -PreleasePatch="${release_patch}" \
  -PreleaseDateCode="${date_code}" \
  :app:assembleDebug

test -f "${apk_path}"

gh release view "${release_tag}" --repo "${repo}" >/dev/null 2>&1 || \
  gh release create "${release_tag}" \
    --repo "${repo}" \
    --target "${target_branch}" \
    --title "Main Latest" \
    --notes "Latest main branch debug APK."

gh release upload "${release_tag}" "${apk_path}" version.json --clobber --repo "${repo}"

release_id="$(gh api "repos/${repo}/releases/tags/${release_tag}" --jq ".id")"
gh api "repos/${repo}/releases/${release_id}/assets" --jq ".[].name" |
while IFS= read -r asset; do
  if [[ "${asset}" =~ ^mangaViewer_[0-9]+-debug\.apk$ && "${asset}" != "${apk_name}" ]]; then
    asset_id="$(gh api "repos/${repo}/releases/${release_id}/assets" --jq ".[] | select(.name==\"${asset}\") | .id")"
    if [ -n "${asset_id}" ]; then
      gh api -X DELETE "repos/${repo}/releases/assets/${asset_id}" --silent
    fi
  fi
done

gh api "repos/${repo}/releases/${release_id}/assets" \
  --jq ".[] | select(.name==\"${apk_name}\") | {name: .name, size: .size, updatedAt: .updated_at, url: .browser_download_url}"
