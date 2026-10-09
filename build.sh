#!/usr/bin/env bash
# Builds dist/VitaIRC.apk. Gradle's caches and build outputs stay outside
# the project folder, under ~/.vitairc-android-build.
set -euo pipefail
cd "$(dirname "$0")"
# JDK 17: JAVA_HOME if set, otherwise Homebrew's
[ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17
OUT="$HOME/.vitairc-android-build"
# release key (kept outside the project; see README "Signing")
KEYS="$HOME/.android-keys/vitairc-android.properties"
if [ -z "${VITAIRC_KEYSTORE:-}" ] && [ -f "$KEYS" ]; then
  set -a; . "$KEYS"; set +a
fi
./gradlew --project-cache-dir "$OUT/.gradle" "${@:-assembleRelease}"
mkdir -p dist
cp "$OUT/app/outputs/apk/release/app-release.apk" dist/VitaIRC.apk 2>/dev/null && echo "Done: dist/VitaIRC.apk" || true
