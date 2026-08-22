#!/usr/bin/env bash
# Sync every app module's versionCode/versionName to a semantic-release version.
# Usage: set-gradle-version.sh <semver>
#
# The Play Store build number (versionCode) must increase monotonically, so we
# derive it deterministically from the semver: M*10000 + m*100 + p (assumes
# minor/patch < 100). Because semantic-release only ever bumps the version
# upward, versionCode is guaranteed to increase too.
#
# One applicationId now ships two bundles, and Play requires a unique
# versionCode per bundle -- so the base is multiplied by ten and each form
# factor takes a slot:
#
#   mobile  base*10 + 0
#   wear    base*10 + 1
#
# Wear takes the higher slot deliberately. A device that matches both bundles
# is handed the higher versionCode, and for a watch that must be the Wear one.
set -euo pipefail

VERSION="${1:?usage: set-gradle-version.sh <semver>}"

CORE="${VERSION%%[-+]*}"
IFS='.' read -r MAJOR MINOR PATCH <<< "$CORE"
BASE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))

write_version() {
  local build_gradle="$1" version_code="$2"
  [ -f "$build_gradle" ] || { echo "no such file: $build_gradle" >&2; exit 1; }
  local tmp
  tmp="$(mktemp)"
  awk -v vn="$VERSION" -v vc="$version_code" '
    /versionCode[[:space:]]*=/ && !doneCode { sub(/versionCode[[:space:]]*=[[:space:]]*[0-9]+/, "versionCode = " vc); doneCode=1 }
    /versionName[[:space:]]*=/ && !doneName { sub(/versionName[[:space:]]*=[[:space:]]*"[^"]*"/, "versionName = \"" vn "\""); doneName=1 }
    { print }
  ' "$build_gradle" > "$tmp"
  mv "$tmp" "$build_gradle"
  echo "${build_gradle} versionName -> ${VERSION}, versionCode -> ${version_code}"
}

MOBILE_CODE=$(( BASE * 10 ))
WEAR_CODE=$(( BASE * 10 + 1 ))

write_version "mobile/build.gradle.kts" "$MOBILE_CODE"
write_version "app/build.gradle.kts"    "$WEAR_CODE"

# --- Play release notes ----------------------------------------------------
#
# supply looks for changelogs/<versionCode>.txt, so the same notes have to be
# written once per form factor -- two files, one release. Without them supply
# logs "Could not find changelog for '103001'" and testers get a silent update
# with no indication of what changed.
#
# This depends on @semantic-release/changelog having already written
# CHANGELOG.md, which is why it is ordered before @semantic-release/exec in
# .releaserc.json. Reorder those and the newest section here is the *previous*
# release, which would ship quietly wrong notes rather than failing.
CHANGELOG="CHANGELOG.md"
CHANGELOG_DIR="fastlane/metadata/android/en-GB/changelogs"
PLAY_NOTES_LIMIT=500

[ -f "$CHANGELOG" ] || { echo "no such file: $CHANGELOG" >&2; exit 1; }

# The section for this version, from its heading to the next release heading.
# release-notes-generator writes "# [1.3.0](...)" for minor/major and
# "## [1.3.1](...)" for patches, so match any run of hashes.
notes="$(awk -v want="$CORE" '
  /^#+ / {
    if (match($0, /[0-9]+\.[0-9]+\.[0-9]+/)) {
      if (found) exit
      if (substr($0, RSTART, RLENGTH) == want) { found = 1; next }
    }
  }
  found { print }
' "$CHANGELOG" \
  | sed -E 's/\(\[[^]]*\]\([^)]*\)\)//g' \
  | sed -E 's/\[([^]]*)\]\([^)]*\)/\1/g' \
  | sed -E 's/^#+[[:space:]]*//' \
  | sed -E 's/^\*[[:space:]]+/- /' \
  | sed -E 's/[[:space:]]+$//' \
  | cat -s \
  | sed -e '/./,$!d')"

# Trailing blank lines survive the squeeze; strip them too.
notes="${notes%"${notes##*[![:space:]]}"}"

if [ -z "$notes" ]; then
  echo "::error::no CHANGELOG.md section found for ${CORE} -- Play would get empty release notes" >&2
  exit 1
fi

if [ "${#notes}" -gt "$PLAY_NOTES_LIMIT" ]; then
  # Play rejects anything longer, so cut at a line boundary rather than
  # mid-sentence and say so, instead of letting supply fail at upload time.
  # Parameter expansion, not `cut -c`: cut works per line and would happily
  # leave every line in place, each individually under the limit.
  notes="${notes:0:$(( PLAY_NOTES_LIMIT - 4 ))}"
  notes="${notes%$'\n'*}"$'\n''...'
  echo "release notes truncated to Play's ${PLAY_NOTES_LIMIT}-character limit"
fi

mkdir -p "$CHANGELOG_DIR"
for code in "$MOBILE_CODE" "$WEAR_CODE"; do
  printf '%s\n' "$notes" > "${CHANGELOG_DIR}/${code}.txt"
  echo "${CHANGELOG_DIR}/${code}.txt written (${#notes} chars)"
done
