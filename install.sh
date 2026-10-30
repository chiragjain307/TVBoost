#!/usr/bin/env bash
# Set up an Android TV or box. A box with a verified device profile gets the full setup. Any other box is
# refused unless you pass --universal, which installs the launcher, design and DNS and disables nothing
# except the stock launcher. A debloat list can never land on hardware it was not tested on.
#
# Usage: ./install.sh [options] <box ip> [<box ip> ...]
#
# Options: --dry-run         show every planned action and change nothing
#          --design <name>   a saved design, see ./design.sh list (default: 01-navy-gold)
#          --logo <file>     use this logo, without asking
#          --no-logo         no logo, without asking
#          --keep-logo       reuse the saved logo if there is one, without asking
#          --no-dns          leave the box's DNS alone
#          --universal       launcher, design and DNS only, for any Android 8+ device. No debloat list
#          --saved-apps      reuse your last choice of apps to disable, without asking
#
# Exit status: 0 every box passed, 1 a box failed, 2 a box was refused or the command line is wrong.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
PY=tools/venv/bin/python
APK=launcher/app/build/outputs/apk/release/app-release.apk
MIN_FREE_KB=51200
RELEASE_URL=https://github.com/chiragjain307/tvbox-revive/releases/latest/download/tvbox-revive-launcher.apk
USER_TV_PORT="${TV_PORT:-}"

DRY=""
DNS=1
UNIVERSAL=""
SAVED_APPS=""
DESIGN=01-navy-gold
LOGO_ARGS=()
IPS=()
usage() { sed -n 6,15p "$HERE/install.sh" | sed 's/^# *//' >&2; exit 2; }
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --no-dns) DNS=""; shift ;;
    --universal) UNIVERSAL=1; shift ;;
    --saved-apps) SAVED_APPS=--saved; shift ;;
    --design) [ $# -ge 2 ] || usage; DESIGN="$2"; shift 2 ;;
    --logo) [ $# -ge 2 ] || usage; LOGO_ARGS=(--logo "$2"); shift 2 ;;
    --no-logo|--keep-logo) LOGO_ARGS=("$1"); shift ;;
    -h|--help) usage ;;
    -*) echo "not an option: $1" >&2; usage ;;
    *) IPS+=("$1"); shift ;;
  esac
done
[ ${#IPS[@]} -ge 1 ] || usage
[ -f "designs/$DESIGN/config.json" ] || { echo "no design called '$DESIGN': see ./design.sh list" >&2; exit 2; }

if [ ! -x "$PY" ]; then
  echo "first run: setting up the tools"
  python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt || exit 2
fi

tv() { "$PY" -I tools/tv.py "$@"; }
. tools/common.sh

# Asked once per run, not once per box, so a list of boxes all get the same logo.
if [ -z "$DRY" ] && [ ${#LOGO_ARGS[@]} -eq 0 ] && [ -t 0 ]; then
  printf 'Apply a new logo? (y/N): '
  read -r reply
  case "$reply" in
    [yY]*)
      printf 'Path to your logo file: '
      read -r logo
      logo="${logo%\"}"; logo="${logo#\"}"; logo="${logo%\'}"; logo="${logo#\'}"
      [ -f "$logo" ] || { echo "no logo file at: $logo" >&2; exit 2; }
      LOGO_ARGS=(--logo "$logo") ;;
    *) LOGO_ARGS=(--keep-logo) ;;
  esac
fi
[ ${#LOGO_ARGS[@]} -gt 0 ] || LOGO_ARGS=(--keep-logo)

# Use a local build if there is one, otherwise the released APK, checked against the hash in this repo.
get_apk() {
  [ -f "$APK" ] && return 0
  echo "downloading the launcher from the latest release"
  mkdir -p "$(dirname "$APK")"
  curl -fsSL -o "$APK.part" "$RELEASE_URL" || { echo "download failed: run ./build.sh instead" >&2; rm -f "$APK.part"; return 1; }
  want=$(grep launcher launcher/RELEASE.sha256 | awk '{print $1}')
  got=$(shasum -a 256 "$APK.part" | awk '{print $1}')
  [ "$want" = "$got" ] || { echo "the downloaded APK does not match launcher/RELEASE.sha256" >&2; rm -f "$APK.part"; return 1; }
  mv "$APK.part" "$APK"
}

one() {
  export TV_HOST="$1"
  detect_port "$1"
  local device list free sdk rc stock dns="$DNS"
  device=$("$PY" -I tools/device_match.py)
  rc=$?
  [ "$rc" -ne 2 ] || return 2
  if [ "$rc" -ne 0 ] && [ -z "$UNIVERSAL" ]; then
    echo "To add only the launcher, design and DNS, run again with --universal." >&2
    return 2
  fi
  sdk=$(tv "getprop ro.build.version.sdk" | tr -dc '0-9')
  if [ -n "$UNIVERSAL" ]; then
    device=universal
    [ "${sdk:-0}" -ge 26 ] || { echo "the launcher needs Android 8 or newer, this device is SDK ${sdk:-unknown}" >&2; return 2; }
    list="$HOME/.tvbox-revive/universal-$TV_HOST.txt"
    mkdir -p "$HOME/.tvbox-revive"
    tv "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" \
      | tail -n 1 | tr -d '\r' | cut -d/ -f1 > "$list"
  else
    list="$HERE/devices/$device/debloat.txt"
    stock=$("$PY" -I -c "import json; print(json.load(open('devices/$device/device.json'))['stock_launcher'])")
    rm -f "$HOME/.tvbox-revive/universal-$TV_HOST.txt"
  fi
  # Android 8 has no Private DNS setting.
  [ "${sdk:-0}" -ge 28 ] || dns=""
  free=$(tv "df -k /data | tail -n 1" | awk '{print $4}')
  case "$free" in ''|*[!0-9]*) free=0 ;; esac
  echo "matched profile: $device ($((free / 1024)) MB free on /data)"
  [ "$free" -ge "$MIN_FREE_KB" ] || { echo "not enough free storage on the box" >&2; return 1; }
  if [ -n "$DRY" ]; then
    cat <<EOF
DRY RUN. Nothing was changed. On this box install.sh would:
  1. save a recovery manifest and an undo record under ~/.tvbox-revive
  2. install the launcher and hand Home over from the stock launcher, rolling back if Home does not land
  3. disable these $(grep -cvE '^[[:space:]]*(#|$)' "$list") packages (all can be re-enabled, $device mode):
$(grep -vE '^[[:space:]]*(#|$)' "$list" | sed 's/^/       /')
  4. apply design $DESIGN (${LOGO_ARGS[*]:-ask about a logo})
  5. $([ -n "$dns" ] && echo 'turn on AdGuard Private DNS (dns.adguard-dns.com)' || echo 'leave DNS alone')
  6. reboot and run the health check
EOF
    return 0
  fi
  # Nothing is disabled until the owner has chosen what to keep.
  if [ "$device" = universal ]; then
    if [ -t 0 ]; then
      printf 'Disable the stock launcher %s so the new one can be Home? (y/N): ' "$(cat "$list")"
      read -r reply
      case "$reply" in [yY]*) ;; *) echo "stopped: nothing was changed"; return 1 ;; esac
    fi
  else
    list="$HOME/.tvbox-revive/selected-$TV_HOST.txt"
    mkdir -p "$HOME/.tvbox-revive"
    "$PY" -I tools/choose_apps.py "$device" "$list" $SAVED_APPS || return 1
    if ! grep -qx "$stock" "$list"; then
      echo "You kept the stock launcher, and Android will not let another launcher be Home while it is on." >&2
      echo "Run again and disable $stock, or use your box as it is." >&2
      return 1
    fi
  fi
  printf '%s\n' "$list" > "$HOME/.tvbox-revive/installed-$TV_HOST.txt"
  "$PY" -I tools/backup.py "$device" || return 1
  "$PY" -I tools/recovery.py --save "$list" || return 1
  get_apk || return 1
  SKIP_BUILD=1 launcher/build-and-install.sh || return 1
  # The disable guard reads each package's permission block, and some firmware prints none for listed
  # packages. A verified profile only reaches this line after matching a box already tested with them.
  if [ "$device" != universal ]; then
    TVINPUT_ALLOW=1 tools/disable.sh "$list" || return 1
  fi
  "$HERE/design.sh" "$DESIGN" "$TV_HOST" ${LOGO_ARGS[@]+"${LOGO_ARGS[@]}"} || return 1
  if [ -n "$dns" ]; then
    tv "settings put global private_dns_specifier dns.adguard-dns.com; settings put global private_dns_mode hostname" || return 1
  fi
  sleep 15   # Android writes package state about 10 s after the last change
  "$PY" -I tools/health_check.py "$device" --reboot
}

failed=0
summary=""
for ip in "${IPS[@]}"; do
  started=$SECONDS
  echo "=================== $ip"
  one "$ip"
  rc=$?
  took=$((SECONDS - started))
  case $rc in
    0) result="DONE" ;;
    2) result="REFUSED" ;;
    *) result="FAILED" ;;
  esac
  [ "$rc" -eq 0 ] || { [ "$failed" -eq 2 ] || failed=$rc; }
  summary="$summary$ip  $result  ${took}s"$'\n'
done
echo "=================== summary"
printf '%s' "$summary"
exit "$failed"
