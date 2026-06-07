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

version_name="4.6-${version_code}"

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
  :app:assembleDebug

stable_apk="$(python3 - <<'PY'
import json
from pathlib import Path

metadata = json.loads(Path("app/build/outputs/apk/debug/output-metadata.json").read_text(encoding="utf-8"))
print(Path("app/build/outputs/apk/debug") / metadata["elements"][0]["outputFile"])
PY
)"

build_tools_dir="$(find "${ANDROID_HOME:?ANDROID_HOME is required}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
zipalign="${build_tools_dir}/zipalign"
apksigner="${build_tools_dir}/apksigner"
test -x "${zipalign}"
test -x "${apksigner}"

mkdir -p release-work app/build/outputs/apk/debug
unsigned_apk="release-work/${apk_name%.apk}-unsigned.apk"
aligned_apk="release-work/${apk_name%.apk}-aligned.apk"

python3 tools/patch_apk_manifest_version.py \
  --input "${stable_apk}" \
  --output "${unsigned_apk}" \
  --version-code "${version_code}" \
  --version-name "${version_name}"

"${zipalign}" -f -p 4 "${unsigned_apk}" "${aligned_apk}"
"${apksigner}" sign \
  --ks config/mangaviewer-debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "${apk_path}" \
  "${aligned_apk}"
"${apksigner}" verify "${apk_path}"
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

latest_sha="$(git ls-remote origin "refs/heads/${target_branch}" | awk '{print $1}')"
if [ "${GITHUB_EVENT_NAME:-}" = "push" ] && [ -n "${latest_sha}" ] && [ "${latest_sha}" != "${GITHUB_SHA:-}" ]; then
  echo "Skipping metadata sync because branch moved from ${GITHUB_SHA:-unknown} to ${latest_sha}"
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -- version.json releases.html
if git diff --cached --quiet; then
  echo "No release metadata changes to sync"
else
  git commit -m "Update release metadata ${version_code} [skip ci]"
  git push origin "HEAD:${target_branch}"
fi
