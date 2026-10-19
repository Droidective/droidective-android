#!/usr/bin/env bash
# Spike 1 on a phone that freezes background apps: tap a home-screen bar's Layout bounds cell N
# times, GAP seconds apart, and report how long each tap took to flip debug.layout (up to 15 s).
# A GAP near five seconds lands taps while OxygenOS is freezing the app. Puts debug.layout back to
# false at the end.
#
# Needs: Droidective with Shell, and a bar with Layout bounds at X Y (device pixels).
# usage: scripts/spikes/tap-late.sh SERIAL X Y N GAP
set -euo pipefail
s=$1
x=$2
y=$3
n=$4
gap=$5
prev=$(adb -s "$s" shell getprop debug.layout | tr -d '\r')
late=0
for i in $(seq 1 "$n"); do
  t0=$(python3 -I -c 'import time; print(time.time())')
  adb -s "$s" shell input tap "$x" "$y"
  now=$prev
  for _ in $(seq 1 150); do
    now=$(adb -s "$s" shell getprop debug.layout | tr -d '\r')
    [ "$now" != "$prev" ] && break
    sleep 0.1
  done
  ms=$(python3 -I -c "import time; print(round((time.time() - $t0) * 1000))")
  if [ "$now" != "$prev" ]; then
    [ "$ms" -gt 1500 ] && late=$((late + 1)) && tag=" LATE" || tag=""
    echo "tap $i: '$prev' -> '$now' in $ms ms$tag"
    prev=$now
  else
    echo "tap $i: nothing in 15 s"
    late=$((late + 1))
  fi
  sleep "$gap"
done
echo "late or lost: $late of $n"
if [ "$prev" = "true" ]; then
  adb -s "$s" shell input tap "$x" "$y"
  sleep 4
fi
echo "debug.layout now '$(adb -s "$s" shell getprop debug.layout | tr -d '\r')'"
