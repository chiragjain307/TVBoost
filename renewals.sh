#!/usr/bin/env bash
# Add or change subscription renewal dates, then send them to one or more boxes. Apps do not share billing
# dates, so they are entered here once. Monthly and yearly dates roll forward by themselves.
#
# Usage: ./renewals.sh <box ip> [<box ip> ...]
set -uo pipefail
cd "$(dirname "$0")"
PY=tools/venv/bin/python
USER_TV_PORT="${TV_PORT:-}"
. tools/common.sh
[ $# -ge 1 ] || { sed -n 5p renewals.sh | sed 's/^# *//' >&2; exit 2; }
"$PY" -I tools/profile.py renewals
FILES=/sdcard/Android/data/com.tvboxrevive.home/files
for ip in "$@"; do
  export TV_HOST="$ip"
  detect_port "$ip"
  "$PY" -I tools/b64push.py "$HOME/.tvbox-revive/renewals.json" /data/local/tmp/tvr-renewals.json >/dev/null \
    && "$PY" -I tools/tv.py "cp /data/local/tmp/tvr-renewals.json $FILES/renewals.json; rm -f /data/local/tmp/tvr-renewals.json" \
    && echo "$ip updated. Press Home on the box to refresh the panel."
done
