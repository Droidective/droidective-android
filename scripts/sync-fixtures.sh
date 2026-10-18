#!/usr/bin/env bash
# Copies the real-device output ADBKit's tests replay into fixtures/, from a pinned commit of
# the main repo, so Kotlin parsers face the same getprop, dumpsys and logcat text the Mac's do.
# Bump COMMIT to take newer recordings, then review the JSON diff.
set -euo pipefail

COMMIT="17d14e2bdf112884334c4af34c2690a35db2becc"
FILES=("android-emulator.json")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

for file in "${FILES[@]}"; do
  url="https://raw.githubusercontent.com/Droidective/Droidective/${COMMIT}/ADBKit/Tests/ADBKitTests/Fixtures/${file}"
  curl -fsSL "$url" -o "${ROOT}/fixtures/${file}"
  echo "fixtures/${file} <- Droidective@${COMMIT:0:7}"
done
