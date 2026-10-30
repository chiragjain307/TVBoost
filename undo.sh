#!/usr/bin/env bash
# Put a box back the way it was before install.sh: disabled packages and stock Home from the undo
# record, DNS back to its saved values, and the launcher with its design removed.
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

installed="$HOME/.tvbox-revive/installed-$TV_HOST.txt"
[ -f "$installed" ] || { echo "no install record for this device on this computer" >&2; exit 1; }
list="$(cat "$installed")"
if device=$("$PY" -I tools/device_match.py 2>/dev/null); then
  stock=$("$PY" -I -c "import json; print(json.load(open('devices/$device/device.json'))['stock_launcher'])")
else
  stock=$(cat "$list")
fi
tools/restore.sh "$list" "$stock" || exit 1

backup=""
latest="$HOME/.tvbox-revive/backups/latest-$TV_HOST.txt"
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
tv "pm uninstall com.tvboxrevive.home; pm uninstall com.tvboxrevive.helper" >/dev/null
tv "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1; settings get global private_dns_mode"
echo "undo finished. Reboot the box once to settle it."
