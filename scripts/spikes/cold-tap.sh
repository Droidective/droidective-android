#!/usr/bin/env bash
# Spike 1: how long a widget tap takes to reach Shizuku and flip Wi-Fi when the app's process is
# dead, timed from the tap to WifiService's own log line, both on the device clock.
#
# Needs: Droidective installed with Shizuku allowed, and a home-screen bar with the Wi-Fi toggle
# on the launcher's first or second page. Each round flips Wi-Fi; the phone's Wi-Fi is put back
# as it was when the script ends.
#
# usage: scripts/spikes/cold-tap.sh SERIAL [ROUNDS]
set -euo pipefail

serial=${1:?usage: cold-tap.sh SERIAL [ROUNDS]}
rounds=${2:-3}
ui="$(cd "$(dirname "$0")" && pwd)/ui.py"
adb_() { adb -s "$serial" "$@"; }

wifi_before=$(adb_ shell settings get global wifi_on | tr -d '\r')
restore() {
  if [ "$wifi_before" = "0" ]; then adb_ shell svc wifi disable; else adb_ shell svc wifi enable; fi
}
trap restore EXIT

for round in $(seq 1 "$rounds"); do
  adb_ shell input keyevent KEYCODE_HOME
  # Long enough for Android to cache the process, which is the state the low-memory killer takes.
  sleep 65
  adb_ shell am kill com.droidective.android
  sleep 1
  # HOME returns to the launcher's first page; the bar may be on the second.
  if ! ANDROID_SERIAL="$serial" python3 -I "$ui" has "Wi-Fi"; then
    adb_ shell input swipe 900 1200 150 1200 200
    sleep 2
  fi
  pid=$(adb_ shell pidof com.droidective.android | tr -d '\r' || true)
  if ! read -r x y < <(ANDROID_SERIAL="$serial" python3 -I "$ui" bounds "Wi-Fi"); then
    echo "round $round: the Wi-Fi toggle isn't on screen"
    continue
  fi
  adb_ logcat -c
  tap_at=$(adb_ shell date +%s.%N | tr -d '\r')
  adb_ shell input tap "$x" "$y"
  sleep 12
  line=$(adb_ logcat -d -v epoch | grep -m1 -E "WifiService.*setWifiEnabled" || true)
  if [ -z "$line" ]; then
    echo "round $round: process '${pid:-dead}': no setWifiEnabled within 12 s"
    continue
  fi
  at=$(echo "$line" | awk '{print $1}')
  ms=$(python3 -c "print(round(($at - $tap_at) * 1000))")
  echo "round $round: process '${pid:-dead}' → setWifiEnabled after $ms ms:" \
    "$(echo "$line" | grep -oE 'package=[^ ]+|uid=[0-9]+|enable=[a-z]+' | tr '\n' ' ')"
done
