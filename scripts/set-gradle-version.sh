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

write_version "mobile/build.gradle.kts" "$(( BASE * 10 ))"
write_version "app/build.gradle.kts"    "$(( BASE * 10 + 1 ))"
