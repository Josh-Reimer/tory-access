#!/usr/bin/env bash
# Drive a real phone from macOS (adb) or from Termux itself (Shizuku via rish).
#
#   bash scripts/device.sh install          # stage APK, pm install, verify it really landed
#   bash scripts/device.sh launch           # am start
#   bash scripts/device.sh dump             # JSON state (tor, sessions, prompt, hosts)
#   bash scripts/device.sh add-host LABEL HOST [PORT] [USER] [PASSWORD]
#   bash scripts/device.sh connect LABEL    # opens a session tab (foreground)
#   bash scripts/device.sh answer yes|no|<secret>   # answer the dialog that's up
#   bash scripts/device.sh send 'uname -a'  # type + Enter into the active session
#   bash scripts/device.sh screen           # terminal screen as text
#   bash scripts/device.sh cmd start-tor|stop-tor|restart-tor|hub|terminal|close-all
#   bash scripts/device.sh logs             # logcat for our tags
#   bash scripts/device.sh ui               # uiautomator dump (Compose testTags = resource-ids)
#   bash scripts/device.sh shot out.png     # screencap (trust this over an empty ui dump)
#
# Backend: adb if a device is attached, else Shizuku via scripts/rish.local.sh
# (copy scripts/rish-template.sh there and fill in your RISH_APPLICATION_ID).
# Debug-build automation only — the receiver doesn't exist in release builds.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="${TORY_PKG:-com.joshreimer.toryaccess.debug}"
ACTIVITY="$PKG/com.joshreimer.toryaccess.MainActivity"
RECEIVER="$PKG/com.joshreimer.toryaccess.debug.AutomationReceiver"
APK="${TORY_APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"

# ---------------------------------------------------------------- backend
if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
  BACKEND=adb
elif [ -f "$ROOT/scripts/rish.local.sh" ]; then
  BACKEND=rish
else
  echo "No adb device and no scripts/rish.local.sh (see scripts/rish-template.sh)." >&2
  exit 1
fi

# Run one shell command on the device as the shell user (uid 2000).
dsh() {
  if [ "$BACKEND" = adb ]; then
    adb shell "$1"
  else
    # bash, not ./ — no exec bit on shared storage.
    bash "$ROOT/scripts/rish.local.sh" "$1"
  fi
}

shell_quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

broadcast() { # action [am extras...]
  local action="$1"; shift
  local out
  out="$(dsh "am broadcast -n $RECEIVER -a $action $*")"
  # 'Broadcast completed: result=0, data="…"' (data may span lines) → print just the data.
  printf '%s\n' "$out" | sed -n '/^Broadcast completed: /,$p' \
    | sed '1s/^Broadcast completed: result=[0-9-]*, data="//' | sed '$s/"$//'
  printf '%s\n' "$out" | grep -q 'result=0' || { printf '%s\n' "$out" >&2; return 1; }
}

case "${1:-}" in
  install)
    [ -f "$APK" ] || { echo "No APK at $APK — run: bash scripts/build.sh" >&2; exit 1; }
    before="$(dsh "dumpsys package $PKG | grep lastUpdateTime" || true)"
    if [ "$BACKEND" = adb ]; then
      adb install -r "$APK"
    else
      # system_server can't read through FUSE (/sdcard) — and inside proot the APK's path
      # isn't even the device's path. Stream the bytes into /data/local/tmp instead.
      bash "$ROOT/scripts/rish.local.sh" "cat > /data/local/tmp/tory-access.apk" < "$APK"
      dsh "pm install -r /data/local/tmp/tory-access.apk"
    fi
    # Never trust pm's exit text alone — confirm lastUpdateTime actually moved.
    after="$(dsh "dumpsys package $PKG | grep lastUpdateTime")"
    now="$(dsh 'date "+%Y-%m-%d %H:%M"')"
    echo "lastUpdateTime: ${after##*=}   device now: $now"
    if [ "$before" = "$after" ]; then
      echo "!! lastUpdateTime didn't change — the install did NOT land." >&2
      exit 1
    fi
    ;;
  launch) dsh "am start -n $ACTIVITY" ;;
  cmd) dsh "am start -n $ACTIVITY --es tory.cmd $(shell_quote "$2")" ;;
  connect) dsh "am start -n $ACTIVITY --es tory.connect $(shell_quote "$2")" ;;
  dump) broadcast tory.DUMP ;;
  add-host)
    extras="--es label $(shell_quote "$2") --es host $(shell_quote "$3") --ei port ${4:-22} --es user $(shell_quote "${5:-root}")"
    [ -n "${6:-}" ] && extras="$extras --es password $(shell_quote "$6")"
    broadcast tory.ADD_HOST "$extras"
    ;;
  remove-host) broadcast tory.REMOVE_HOST "--es label $(shell_quote "$2")" ;;
  answer) broadcast tory.ANSWER "--es value $(shell_quote "$2")" ;;
  send) broadcast tory.SEND "--es text $(shell_quote "$2") --ez enter true" ;;
  type) broadcast tory.SEND "--es text $(shell_quote "$2")" ;;
  screen) broadcast tory.SCREEN ;;
  scrollback) broadcast tory.SCREEN "--ez scrollback true" ;;
  newnym) broadcast tory.NEWNYM ;;
  logs) dsh "logcat -d -v brief -s ToryAutomation:V ToryTor:V ToryTerminal:V HubService:V AndroidRuntime:E" | tail -n "${2:-200}" ;;
  ui)
    dsh "input keyevent KEYCODE_WAKEUP; uiautomator dump /data/local/tmp/tory-ui.xml >/dev/null && cat /data/local/tmp/tory-ui.xml" \
      | grep -o 'resource-id="[^"]*"\|text="[^"][^"]*"' | sed 's/^/  /'
    ;;
  shot)
    out="${2:-tory-screen.png}"
    dsh "screencap -p /data/local/tmp/tory-screen.png"
    if [ "$BACKEND" = adb ]; then adb pull /data/local/tmp/tory-screen.png "$out" >/dev/null
    else dsh "cat /data/local/tmp/tory-screen.png" > "$out"; fi
    echo "$out"
    ;;
  *)
    sed -n '2,20p' "$0"
    exit 2
    ;;
esac
