#!/usr/bin/env bash
# Prints the RELEASE_NOTES.md section for a tag (v0.1.0 -> "## 0.1.0"), without its heading.
# Fails when the section is missing or empty, so a release can't go out without notes.
set -euo pipefail

tag=${1:?usage: release-notes.sh vX.Y.Z [notes-file]}
file=${2:-$(cd "$(dirname "$0")/.." && pwd)/RELEASE_NOTES.md}
version=${tag#v}

notes=$(awk -v heading="## $version" '
  $0 == heading { inside = 1; next }
  inside && /^## / { exit }
  inside { print }
' "$file" | sed -e '/./,$!d')

if [ -z "${notes//[[:space:]]/}" ]; then
  echo "RELEASE_NOTES.md has no notes under \"## $version\". Add them before tagging $tag." >&2
  exit 1
fi
printf '%s\n' "$notes"
