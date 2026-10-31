#!/usr/bin/env bash
# Put a box back the way it was before install.sh: disabled packages and stock Home from the undo
# record, DNS and system settings back to their saved values, and the launcher with its design removed.
#
# Usage: ./undo.sh <box ip>
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
PY=tools/venv/bin/python
[ $# -eq 1 ] || { echo "usage: ./undo.sh <box ip>" >&2; exit 2; }
USER_TV_PORT="${TV_PORT:-}"
export TV_HOST="$1"
tv() { "$PY" -I tools/tv.py "$@"; }
. tools/common.sh
detect_port "$1"

installed="$HOME/.tvboost/installed-$TV_HOST.txt"
[ -f "$installed" ] || { echo "no install record for this device on this computer" >&2; exit 1; }
list="$(cat "$installed")"
if device=$("$PY" -I tools/device_match.py 2>/dev/null); then
  stock=$("$PY" -I -c "import json; print(json.load(open('devices/$device/device.json'))['stock_launcher'])")
else
  stock=$(cat "$list")
fi
# On Google TV the stock launcher was never touched, so there is nothing to bring back.
stays="$HOME/.tvboost/stock-stays-$TV_HOST"
[ -f "$stays" ] || tools/restore.sh "$list" "$stock" || exit 1

backup=""
latest="$HOME/.tvboost/backups/latest-$TV_HOST.txt"
[ -f "$latest" ] && backup="$(cat "$latest")/manifest.json"
mode=null
name=null
if [ -f "$backup" ]; then
  mode=$("$PY" -I -c "import json; print(json.load(open('$backup'))['private_dns_mode'])")
  name=$("$PY" -I -c "import json; print(json.load(open('$backup'))['private_dns_specifier'])")
fi
if [ "$mode" = null ]; then
  tv "settings delete global private_dns_mode; settings delete global private_dns_specifier"
else
  [ "$name" = null ] && tv "settings delete global private_dns_specifier" || tv "settings put global private_dns_specifier $name"
  tv "settings put global private_dns_mode $mode"
fi
tv "pm uninstall com.tvboost.home; pm uninstall com.tvboost.helper" >/dev/null
# Android rewrites its list of notification apps just after an uninstall, and on Google TV that left the list
# empty. So wait for that, write the saved values back, and read each one to be sure it stayed.
sleep 5
put_back() {   # <name in the backup> <secure setting>
  [ -f "$backup" ] || return 0
  local value try
  value=$("$PY" -I -c "import json; print(json.load(open('$backup')).get('$1', 'not saved'))")
  case "$value" in
    'not saved') return 0 ;;
    null) tv "settings delete secure $2" >/dev/null; return 0 ;;
  esac
  for try in 1 2 3; do
    tv "settings put secure $2 '$value'"
    sleep 3
    [ "$(tv "settings get secure $2" | tail -n 1 | tr -d '\r')" = "$value" ] && return 0
  done
  echo "could not put back $2: set it to '$value'" >&2
}
put_back notification_listeners enabled_notification_listeners
put_back accessibility_services enabled_accessibility_services
put_back accessibility_enabled accessibility_enabled
put_back touch_exploration touch_exploration_granted_accessibility_services
rm -f "$stays"
tv "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1; settings get global private_dns_mode"
echo "undo finished. Reboot the box once to settle it."
