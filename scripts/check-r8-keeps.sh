#!/usr/bin/env bash
# Fails when R8 stripped a constructor something builds by reflection. Glance makes each
# ActionCallback through its no-argument constructor from a class name stored in a widget's
# PendingIntent; R8 can't see that call, and stripping it broke every widget tap in 0.1.0 and
# 0.1.1. Run after :app:assembleRelease, which writes R8's list of removed members to usage.txt.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
usage="$root/app/build/outputs/mapping/release/usage.txt"
[ -f "$usage" ] || { echo "No $usage: run ./gradlew :app:assembleRelease first." >&2; exit 2; }

callbacks=()
while IFS= read -r file; do
  package=$(sed -n 's/^package //p' "$file" | head -1)
  while IFS= read -r name; do
    callbacks+=("$package.$name")
  done < <(grep -oE "class [A-Za-z0-9_]+ : ActionCallback" "$file" | awk '{print $2}')
done < <(grep -rl ": ActionCallback" "$root/app/src/main/kotlin")

[ "${#callbacks[@]}" -gt 0 ] || { echo "Found no ActionCallback in app sources." >&2; exit 2; }

stripped=0
for class in "${callbacks[@]}"; do
  # usage.txt lists a class as "fqn:" followed by its removed members, indented.
  if awk -v head="$class:" '
    $0 == head { inside = 1; next }
    inside && /^[^ ]/ { inside = 0 }
    inside && /<init>\(\)/ { found = 1 }
    END { exit found ? 0 : 1 }
  ' "$usage"; then
    echo "R8 stripped the no-argument constructor of $class; Glance needs it." >&2
    stripped=1
  fi
done
[ "$stripped" -eq 0 ] && echo "Kept: ${callbacks[*]}"
exit "$stripped"
