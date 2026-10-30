#!/usr/bin/env bash
# Switch a box to a saved design in seconds. Only the look changes: disabled apps, ad blocking and
# everything else stay as they are. The launcher must already be on the box (install.sh puts it there).
#
# Usage: ./design.sh list                                the saved designs
#        ./design.sh current <box ip>                    the design a box is showing
#        ./design.sh <design> <box ip> [<box ip> ...]    asks whether to apply a new logo
#
# Options: --logo <file>   use this logo, without asking
#          --no-logo       no logo, without asking
#          --keep-logo     reuse the saved logo if there is one, without asking
#          --city <name>   fix the city for the temperature. Otherwise each device finds its own
#
# With a logo, the design takes its colours from it. Your logo stays on this computer in
# ~/.tvboost/logo and is never added to the repository.
#
# Exit status: 0 done, 1 a box failed, 2 the command line or the design is wrong.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
PY=tools/venv/bin/python
PKG=com.tvboost.home
FILES=/sdcard/Android/data/$PKG/files
SAVED="$HOME/.tvboost/logo"
USER_TV_PORT="${TV_PORT:-}"
tv() { "$PY" -I tools/tv.py "$@"; }
. tools/common.sh
usage() { sed -n 5,7p "$HERE/design.sh" | sed 's/^# *//' >&2; exit 2; }

LOGO=""
CITY=""
MODE=ask
POSITIONAL=()
while [ $# -gt 0 ]; do
  case "$1" in
    --logo) [ $# -ge 2 ] || usage; LOGO="$2"; MODE=file; shift 2 ;;
    --no-logo) MODE=none; shift ;;
    --keep-logo) MODE=keep; shift ;;
    --city) [ $# -ge 2 ] || usage; CITY="$2"; shift 2 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done
set -- ${POSITIONAL[@]+"${POSITIONAL[@]}"}

[ $# -ge 1 ] || usage
case "$1" in
  list)
    for d in "$HERE"/designs/*/; do
      [ -f "$d/config.json" ] || continue
      printf '%-20s %s\n' "$(basename "$d")" "$(head -n 1 "$d/about.txt" 2>/dev/null)"
    done
    exit 0 ;;
  current)
    [ $# -eq 2 ] || usage
    export TV_HOST="$2"
    detect_port "$2"
    tv "cat $FILES/design.txt 2>/dev/null || echo 'no saved design recorded'"
    exit 0 ;;
esac

[ $# -ge 2 ] || usage
NAME="$1"
shift
DESIGN="$HERE/designs/$NAME"
[ -f "$DESIGN/config.json" ] || { echo "no design called '$NAME'. Saved designs:" >&2; "$HERE/design.sh" list >&2; exit 2; }

saved_logo() { ls "$SAVED"/logo.* 2>/dev/null | head -n 1; }

if [ "$MODE" = ask ]; then
  if [ -t 0 ]; then
    printf 'Apply a new logo? (y/N): '
    read -r reply
    case "$reply" in
      [yY]*)
        printf 'Path to your logo file: '
        read -r LOGO
        LOGO="${LOGO%\"}"; LOGO="${LOGO#\"}"; LOGO="${LOGO%\'}"; LOGO="${LOGO#\'}"
        MODE=file ;;
      *) MODE=keep ;;
    esac
  else
    MODE=keep
  fi
fi

case "$MODE" in
  file)
    [ -f "$LOGO" ] || { echo "no logo file at: $LOGO" >&2; exit 2; }
    mkdir -p "$SAVED"
    rm -f "$SAVED"/logo.*
    ext="${LOGO##*.}"
    cp "$LOGO" "$SAVED/logo.$ext" ;;
  keep)
    LOGO="$(saved_logo)"
    [ -n "$LOGO" ] && echo "using your saved logo: $LOGO" ;;
  none) LOGO="" ;;
esac

spec() { "$PY" -I -c "import json; print(json.load(open('$DESIGN/images.json')).get('$1') or '')"; }

# The pictures are drawn here, outside the repository, for every design, and the design's questions are
# answered here too: only this computer keeps the name and the city.
OUT="$HOME/.tvboost/designs/$NAME"
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$DESIGN/config.json" "$OUT/config.json"
if [ -n "$(spec asks_name)" ]; then
  "$PY" -I tools/profile.py name "$OUT/config.json" || exit 1
fi
# Each device works out its own place. --city fixes one, for a device whose connection points somewhere else.
if [ -n "$CITY" ]; then
  "$PY" -I tools/profile.py city "$OUT/config.json" "$CITY" || exit 1
fi
if [ -n "$LOGO" ]; then
  "$PY" -I tools/make_design_images.py "$DESIGN" "$OUT" --logo "$LOGO" >/dev/null || exit 1
else
  "$PY" -I tools/make_design_images.py "$DESIGN" "$OUT" >/dev/null || exit 1
fi

put() {
  "$PY" -I tools/b64push.py "$1" "/data/local/tmp/tvboost-$2" >/dev/null || return 1
  tv "cp /data/local/tmp/tvboost-$2 $FILES/$2; rm -f /data/local/tmp/tvboost-$2"
}

# The small helper that fetches live data, so the launcher itself never needs the internet.
install_helper() {
  local apk=launcher/helper/build/outputs/apk/release/helper-release.apk
  if [ ! -f "$apk" ]; then
    mkdir -p "$(dirname "$apk")"
    curl -fsSL -o "$apk.part" "https://github.com/chiragjain307/TVBoost/releases/latest/download/tvboost-helper.apk" \
      || { echo "no helper app: run ./build.sh" >&2; rm -f "$apk.part"; return 1; }
    want=$(grep helper launcher/RELEASE.sha256 | awk '{print $1}')
    [ "$want" = "$(shasum -a 256 "$apk.part" | awk '{print $1}')" ] \
      || { echo "the downloaded helper does not match launcher/RELEASE.sha256" >&2; rm -f "$apk.part"; return 1; }
    mv "$apk.part" "$apk"
  fi
  "$PY" -I tools/b64push.py "$apk" /data/local/tmp/tvboost-helper.apk >/dev/null || return 1
  tv "pm install -r /data/local/tmp/tvboost-helper.apk; rm -f /data/local/tmp/tvboost-helper.apk" | grep -q Success || return 1
  # Lets the helper use the device's own location where it has one. Most TVs do not, and that is fine.
  tv "pm grant com.tvboost.helper android.permission.ACCESS_COARSE_LOCATION" >/dev/null 2>&1 || true
}

failed=0
for ip in "$@"; do
  export TV_HOST="$ip"
  detect_port "$ip"
  if ! tv "pm list packages $PKG" | grep -q "$PKG"; then
    echo "$ip: the launcher is not on this box, run install.sh first" >&2
    failed=1
    continue
  fi
  tools/theme.sh "$OUT" >/dev/null || { failed=1; continue; }
  # theme.sh does not know these, and a design without one must not keep the last design's file.
  for extra in clock.jpg welcome.jpg; do
    if [ -f "$OUT/$extra" ]; then put "$OUT/$extra" "$extra" || { failed=1; continue 2; }
    else tv "rm -f $FILES/$extra"; fi
  done
  if [ -n "$(spec helper)" ]; then install_helper || failed=1; fi
  tv "echo $NAME > $FILES/design.txt"
  echo "$ip now shows $NAME"
done
exit "$failed"
