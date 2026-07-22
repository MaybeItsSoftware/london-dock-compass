#!/usr/bin/env bash
# Sync app/build.gradle.kts's versionCode/versionName to a semantic-release version.
# Usage: set-gradle-version.sh <semver> [build-gradle-path]
#
# The Play Store build number (versionCode) must increase monotonically, so we
# derive it deterministically from the semver: M*10000 + m*100 + p (assumes
# minor/patch < 100). Because semantic-release only ever bumps the version
# upward, versionCode is guaranteed to increase too.
set -euo pipefail

VERSION="${1:?usage: set-gradle-version.sh <semver> [build-gradle-path]}"
BUILD_GRADLE="${2:-app/build.gradle.kts}"

CORE="${VERSION%%[-+]*}"
IFS='.' read -r MAJOR MINOR PATCH <<< "$CORE"
VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))

tmp="$(mktemp)"
awk -v vn="$VERSION" -v vc="$VERSION_CODE" '
  /versionCode[[:space:]]*=/ && !doneCode { sub(/versionCode[[:space:]]*=[[:space:]]*[0-9]+/, "versionCode = " vc); doneCode=1 }
  /versionName[[:space:]]*=/ && !doneName { sub(/versionName[[:space:]]*=[[:space:]]*"[^"]*"/, "versionName = \"" vn "\""); doneName=1 }
  { print }
' "$BUILD_GRADLE" > "$tmp"
mv "$tmp" "$BUILD_GRADLE"

echo "app/build.gradle.kts versionName -> ${VERSION}, versionCode -> ${VERSION_CODE}"
