#!/usr/bin/env bash
# Put a theme (config.json + a wallpapers/ folder) on the TV through the launcher's own provider, checking every file's size.
#
#   tools/push-pictures.sh themes/nature-calm [adb serial, default 192.168.1.50:5555]
#
# Needs adb, and USB or network debugging switched on. Replaces the pictures already on the TV: back them up first if
# you want them (adb pull /sdcard/Android/data/com.gathin.tvhome/files). Pictures that are not ticked stay out of the
# rotation: list their names, one per line, in wallpapers/staged.txt and tick them later in Wallpapers.
set -euo pipefail
THEME="${1:?usage: push-pictures.sh <theme folder> [adb serial]}"
SERIAL="${2:-192.168.1.50:5555}"
PKG="${PKG:-com.gathin.tvhome}"
DIR="/sdcard/Android/data/$PKG/files"
URI="content://$PKG.theme"
adb() { command adb -s "$SERIAL" "$@"; }

put() {  # local file, name under files/
  local size; size=$(wc -c < "$1" | tr -d ' ')
  adb push "$1" /data/local/tmp/tvhome-stage >/dev/null
  for _ in 1 2 3 4; do
    adb shell "rm -f $DIR/$2; content write --uri $URI/$2 < /data/local/tmp/tvhome-stage >/dev/null 2>&1" || true
    [ "$(adb shell "ls -l $DIR/$2 2>/dev/null | awk '{print \$5}'" | tr -d '\r')" = "$size" ] && { echo "ok   $2"; return 0; }
    sleep 1
  done
  echo "FAIL $2" >&2; return 1
}

adb shell "mkdir -p $DIR/wallpapers; rm -f $DIR/wallpapers/*" >/dev/null
put "$THEME/config.json" config.json
for f in "$THEME"/wallpapers/*; do put "$f" "wallpapers/$(basename "$f")"; done
adb shell "rm -f /data/local/tmp/tvhome-stage; am force-stop $PKG; input keyevent KEYCODE_HOME"
echo "Done."
