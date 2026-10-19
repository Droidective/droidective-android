#!/usr/bin/env bash
# Rebuilds the launcher icon from the Mac app's icon at a pinned commit of the main repo, so the
# phone shows the same logo as the Mac. scripts/launcher-icon-layers.swift splits the 1024 px
# master into adaptive-icon layers and sips scales them to each density. Needs macOS (swift,
# sips). Bump COMMIT when the Mac's icon changes, run this, then check the icon on a launcher.
set -euo pipefail

COMMIT="dec146ddbe730a761c3260123d0ea024963ad489"
MASTER="App/Resources/Assets.xcassets/AppIcon.appiconset/icon_512x512@2x.png"
DENSITIES=("mdpi:108" "hdpi:162" "xhdpi:216" "xxhdpi:324" "xxxhdpi:432")
LAYERS=("background" "foreground" "monochrome")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${ROOT}/build/launcher-icon"

mkdir -p "$WORK"
curl -fsSL "https://raw.githubusercontent.com/Droidective/Droidective/${COMMIT}/${MASTER}" \
  -o "${WORK}/master.png"
swift -O "${ROOT}/scripts/launcher-icon-layers.swift" "${WORK}/master.png" "$WORK"

for density in "${DENSITIES[@]}"; do
  dir="${ROOT}/app/src/main/res/mipmap-${density%%:*}"
  size="${density##*:}"
  mkdir -p "$dir"
  for layer in "${LAYERS[@]}"; do
    out="${dir}/ic_launcher_${layer}.png"
    sips -z "$size" "$size" "${WORK}/${layer}.png" --out "$out" >/dev/null
  done
done
echo "mipmap-*/ic_launcher_{background,foreground,monochrome}.png <- Droidective@${COMMIT:0:7}"
