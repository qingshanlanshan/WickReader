#!/usr/bin/env bash
# Publishes the download page and the latest release APK to https://wick.qshlab.com.
#   cd reader-android && ./gradlew assembleRelease && site/deploy.sh
# Needs a one-time `npx wrangler@3 login`.
set -euo pipefail
cd "$(dirname "$0")"
# Where feedback is emailed: kept out of git, in feedback-to.txt (one line, a verified Email Routing address).
TO=$(tr -d '[:space:]' < feedback-to.txt 2>/dev/null || true)
if [ -z "$TO" ]; then echo "Put the feedback destination address in site/feedback-to.txt first" >&2; exit 1; fi
# Keep public/ flat: wrangler 3 on Node 18.15 skips subfolders (no recursive readdir).
if find public -mindepth 2 -type f | grep -q .; then echo "public/ must not contain subfolders" >&2; exit 1; fi
# A private npm cache: ~/.npm on this Mac contains root-owned files that make npx fail.
export npm_config_cache="${npm_config_cache:-$PWD/.npm-cache}"

APK=../app/build/outputs/apk/release/app-release.apk
VERSION=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' ../app/build.gradle.kts | head -1)
VERSION_CODE=$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' ../app/build.gradle.kts | head -1)
FILE="WickReader-$VERSION.apk"
SIZE=$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK")
# Cloudflare static assets are limited to 25 MiB per file.
if [ "$SIZE" -gt 26214400 ]; then echo "APK is $SIZE bytes, over the 25 MiB asset limit" >&2; exit 1; fi
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)

rm -f public/WickReader-*.apk
cp "$APK" "public/$FILE"
# The app reads this to offer updates (versionCode decides "newer"); notes are optional.
python3 - "$VERSION" "$VERSION_CODE" "$FILE" "$SIZE" "$SHA" > public/version.json <<'PY'
import json, os, sys
version, code, file, size, sha = sys.argv[1:6]
def notes(lang):
    p = f"notes.{lang}.txt"
    return open(p, encoding="utf-8").read().strip() if os.path.exists(p) else ""
print(json.dumps({"version": version, "versionCode": int(code), "file": file, "size": int(size), "sha256": sha,
                  "notesZh": notes("zh"), "notesEn": notes("en")}, ensure_ascii=False))
PY
# /download always points at the newest APK (the page's button also works without JavaScript).
echo "/download /$FILE 302" > public/_redirects

# wrangler 3 still runs on Node 18 (wrangler 4 needs Node 22).
sed "s/FEEDBACK_TO_ADDRESS/$TO/g" wrangler.jsonc > .deploy.jsonc
trap 'rm -f .deploy.jsonc' EXIT
npx --yes wrangler@3 deploy --config .deploy.jsonc
echo "Published $FILE ($SIZE bytes) to https://wick.qshlab.com"
